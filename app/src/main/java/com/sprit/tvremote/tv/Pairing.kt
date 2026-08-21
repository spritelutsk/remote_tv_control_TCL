package com.sprit.tvremote.tv

import com.google.protobuf.ByteString
import com.sprit.tvremote.proto.polo.Configuration
import com.sprit.tvremote.proto.polo.Options
import com.sprit.tvremote.proto.polo.OuterMessage
import com.sprit.tvremote.proto.polo.PairingRequest
import com.sprit.tvremote.proto.polo.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** Спаривание не удалось и повторять его бессмысленно без вмешательства пользователя. */
class PairingFailed(message: String) : Exception(message)

/** Пользователь закрыл диалог ввода PIN. */
class PairingCancelled : Exception("Спаривание отменено")

/**
 * Спаривание с телевизором по протоколу Polo (порт 6467).
 *
 * Обмен идёт сообщениями [OuterMessage]: запрос спаривания → согласование кодировки →
 * телевизор показывает шестизначный код → клиент доказывает, что видит этот код, отправляя
 * SHA-256 от параметров обоих сертификатов и второй половины кода.
 */
object Pairing {

    private const val SERVICE_NAME = "atvremote"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 5 * 60 * 1000
    private const val PIN_LENGTH = PairingSecret.PIN_LENGTH

    /**
     * Провести спаривание целиком. [requestPin] показывает пользователю диалог и возвращает
     * введённый код или `null`, если он передумал.
     *
     * @throws PairingFailed при ошибке протокола
     * @throws PairingCancelled если пользователь отказался
     */
    suspend fun pair(
        host: String,
        sslContext: SSLContext,
        clientCertificate: X509Certificate,
        requestPin: suspend (deviceName: String, retry: Boolean) -> String?,
    ) = withContext(Dispatchers.IO) {
        val socket = connectTls(
            sslContext,
            host,
            PAIRING_PORT,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
        )
        socket.use {
            val serverCertificate = socket.session.peerCertificates.first() as X509Certificate
            val deviceName = deviceNameOf(serverCertificate) ?: host
            val stream = MessageStream(socket)

            stream.write(
                message()
                    .setPairingRequest(
                        PairingRequest.newBuilder()
                            .setClientName(CertStore.CLIENT_NAME)
                            .setServiceName(SERVICE_NAME),
                    )
                    .build()
                    .toByteArray(),
            )

            while (true) {
                val raw = stream.read() ?: throw PairingFailed("Телевизор закрыл соединение")
                val incoming = OuterMessage.parseFrom(raw)
                if (incoming.status != OuterMessage.Status.STATUS_OK) {
                    throw PairingFailed(statusText(incoming.status))
                }
                when {
                    incoming.hasPairingRequestAck() -> stream.write(optionsMessage())
                    incoming.hasOptions() -> stream.write(configurationMessage())
                    incoming.hasConfigurationAck() -> stream.write(
                        secretMessage(
                            askForSecret(clientCertificate, serverCertificate, deviceName, requestPin),
                        ),
                    )
                    incoming.hasSecretAck() -> return@use
                    else -> throw PairingFailed("Телевизор ответил неожиданным сообщением")
                }
            }
        }
    }

    /** Спрашивать код, пока пользователь не введёт совпадающий с контрольным байтом. */
    private suspend fun askForSecret(
        clientCertificate: X509Certificate,
        serverCertificate: X509Certificate,
        deviceName: String,
        requestPin: suspend (deviceName: String, retry: Boolean) -> String?,
    ): ByteArray {
        var retry = false
        while (true) {
            val pin = requestPin(deviceName, retry)?.trim() ?: throw PairingCancelled()
            val secret = PairingSecret.compute(pin, clientCertificate, serverCertificate)
            if (secret != null) return secret
            retry = true
        }
    }

    private fun message(): OuterMessage.Builder = OuterMessage.newBuilder()
        .setProtocolVersion(2)
        .setStatus(OuterMessage.Status.STATUS_OK)

    private fun encoding(): Options.Encoding.Builder = Options.Encoding.newBuilder()
        .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
        .setSymbolLength(PIN_LENGTH)

    private fun optionsMessage(): ByteArray = message()
        .setOptions(
            Options.newBuilder()
                .setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT)
                .addInputEncodings(encoding()),
        )
        .build()
        .toByteArray()

    private fun configurationMessage(): ByteArray = message()
        .setConfiguration(
            Configuration.newBuilder()
                .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
                .setEncoding(encoding()),
        )
        .build()
        .toByteArray()

    private fun secretMessage(secret: ByteArray): ByteArray = message()
        .setSecret(Secret.newBuilder().setSecret(ByteString.copyFrom(secret)))
        .build()
        .toByteArray()

    private fun statusText(status: OuterMessage.Status): String = when (status) {
        OuterMessage.Status.STATUS_BAD_SECRET -> "Телевизор не принял код спаривания"
        OuterMessage.Status.STATUS_BAD_CONFIGURATION -> "Телевизор не принял параметры спаривания"
        else -> "Телевизор ответил ошибкой ($status)"
    }

    /**
     * Имя устройства из его сертификата. NVIDIA SHIELD, например, представляется как
     * `CN=atvremote/darcy/darcy/SHIELD Android TV/XX:XX:XX:XX:XX:XX`.
     */
    private fun deviceNameOf(certificate: X509Certificate): String? {
        val subject = certificate.subjectX500Principal.getName(X500Principal.RFC1779)
        val commonName = subject.split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter("=")
            ?: return null
        val parts = commonName.split("/").filter { it.isNotBlank() }
        // Последний элемент — MAC-адрес, перед ним человекочитаемое имя.
        return parts.getOrNull(parts.size - 2)?.takeIf { it.isNotBlank() }
    }
}
