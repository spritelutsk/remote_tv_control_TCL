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
 * Телевизор отверг код: сессия спаривания на нём одноразовая. Если после `STATUS_BAD_SECRET`
 * отправить секрет снова на том же соединении, телевизор не отвечает вовсе (проверено на живом
 * устройстве) — он не читает повторную попытку, а просто молчит, пока не истечёт таймаут.
 * Значит для повтора нужно новое TCP-соединение, а не повторное сообщение на старом.
 */
private class WrongSecret : Exception()

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
     * Код может быть отвергнут двумя разными способами: опечатка ловится локально (её видно
     * по контрольной сумме, ничего не уходит в сеть) и просто просит ввести заново на том же
     * соединении. А код, который прошёл локальную проверку, но не совпал с настоящим (например,
     * телевизор уже показывает новый, а введён старый), телевизор отвергает необратимо для этого
     * соединения — тогда переподключаемся заново, и телевизор обычно показывает свежий код.
     *
     * @throws PairingFailed при ошибке протокола
     * @throws PairingCancelled если пользователь отказался
     */
    suspend fun pair(
        host: String,
        sslContext: SSLContext,
        clientCertificate: X509Certificate,
        /** Не для настоящего телевизора — юнит-тесты подставляют сюда сервер-заглушку. */
        port: Int = PAIRING_PORT,
        requestPin: suspend (deviceName: String, retry: Boolean) -> String?,
    ) = withContext(Dispatchers.IO) {
        var retry = false
        while (true) {
            try {
                pairOnce(host, port, sslContext, clientCertificate, retry, requestPin)
                return@withContext
            } catch (_: WrongSecret) {
                retry = true
            }
        }
    }

    private suspend fun pairOnce(
        host: String,
        port: Int,
        sslContext: SSLContext,
        clientCertificate: X509Certificate,
        retry: Boolean,
        requestPin: suspend (deviceName: String, retry: Boolean) -> String?,
    ) {
        val socket = connectTls(
            sslContext,
            host,
            port,
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

            // Отметку «код не подошёл» показываем на первом же запросе только если сюда
            // привёл именно отказ телевизора на предыдущем соединении — не обычный запуск.
            var askRetry = retry
            while (true) {
                val raw = stream.read() ?: throw PairingFailed("Телевизор закрыл соединение")
                val incoming = OuterMessage.parseFrom(raw)
                if (incoming.status == OuterMessage.Status.STATUS_BAD_SECRET) throw WrongSecret()
                if (incoming.status != OuterMessage.Status.STATUS_OK) {
                    throw PairingFailed(statusText(incoming.status))
                }
                when {
                    incoming.hasPairingRequestAck() -> stream.write(optionsMessage())
                    incoming.hasOptions() -> stream.write(configurationMessage())
                    incoming.hasConfigurationAck() -> {
                        val secret = askForSecret(clientCertificate, serverCertificate, deviceName, askRetry, requestPin)
                        askRetry = false // дальше в пределах этого соединения — только опечатки
                        stream.write(secretMessage(secret))
                    }
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
        initialRetry: Boolean,
        requestPin: suspend (deviceName: String, retry: Boolean) -> String?,
    ): ByteArray {
        var retry = initialRetry
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

    // STATUS_BAD_SECRET сюда не попадает — его перехватывает pairOnce() ещё до этого вызова.
    private fun statusText(status: OuterMessage.Status): String = when (status) {
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
