package com.sprit.tvremote.tv

import com.sprit.tvremote.proto.polo.ConfigurationAck
import com.sprit.tvremote.proto.polo.Options
import com.sprit.tvremote.proto.polo.OuterMessage
import com.sprit.tvremote.proto.polo.PairingRequestAck
import com.sprit.tvremote.proto.polo.SecretAck
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Проверка без живого телевизора: чинит ли [Pairing.pair] то, что было обнаружено на реальном
 * TCL BeyondTV — после `STATUS_BAD_SECRET` телевизор не отвечает на повторную попытку на том же
 * соединении (просто молчит), но принимает новое TCP-соединение как ни в чём не бывало.
 *
 * [FakePairingServer] воспроизводит именно это поведение: на первый секрет любого соединения
 * отвечает отказом и сразу рвёт сокет (не дожидаясь второй попытки — TV её и так бы
 * проигнорировал), а на втором соединении принимает.
 */
class PairingRetryTest {

    @Test
    fun `неверный код не обрывает спаривание — приложение переподключается и получает новый код`() {
        val server = FakePairingServer(acceptCount = 2)
        server.start()
        try {
            val (clientContext, clientCertificate) = selfSignedClient()
            // Контрольная сумма PIN проверяется локально ещё до отправки (см. PairingSecret),
            // поэтому наугад взятый код почти всегда её не пройдёт и до сети не дойдёт —
            // подбираем такой, что пройдёт, для пары клиент/сервер именно этого теста.
            val pin = findChecksummedPin(clientCertificate, server.certificate)
            val requests = mutableListOf<Pair<String, Boolean>>()

            runBlocking {
                Pairing.pair(
                    host = "127.0.0.1",
                    sslContext = clientContext,
                    clientCertificate = clientCertificate,
                    port = server.port,
                    requestPin = { deviceName, retry ->
                        requests += deviceName to retry
                        // Сам код неважен — сервер-заглушка отвергает первую попытку и
                        // принимает вторую независимо от значения секрета.
                        pin
                    },
                )
            }

            assertEquals("два запроса кода — по одному на каждое соединение", 2, requests.size)
            assertEquals("первый запрос — обычный, не отказ", false, requests[0].second)
            assertEquals("второй запрос помечен как повтор после отказа", true, requests[1].second)
            assertEquals(2, server.connectionsHandled)
        } finally {
            server.close()
        }
    }

    @Test
    fun `отмена после отказа завершает спаривание, а не зацикливается`() {
        val server = FakePairingServer(acceptCount = 2)
        server.start()
        try {
            val (clientContext, clientCertificate) = selfSignedClient()
            val pin = findChecksummedPin(clientCertificate, server.certificate)
            var attempts = 0

            val error = runCatching {
                runBlocking {
                    Pairing.pair(
                        host = "127.0.0.1",
                        sslContext = clientContext,
                        clientCertificate = clientCertificate,
                        port = server.port,
                        requestPin = { _, _ ->
                            attempts += 1
                            if (attempts == 1) pin else null // на повторе — отмена
                        },
                    )
                }
            }.exceptionOrNull()

            assertEquals(2, attempts)
            assertTrue("отмена после переподключения должна остаться отменой", error is PairingCancelled)
        } finally {
            server.close()
        }
    }

    /**
     * PIN, который [PairingSecret.compute] признаёт корректно оформленным для этой пары
     * сертификатов (иначе `askForSecret` будет спрашивать код заново, ни разу не отправив
     * ничего на сервер). Значение реального секрета серверу-заглушке не важно, только форма.
     */
    private fun findChecksummedPin(client: X509Certificate, server: X509Certificate): String =
        (0..0xFFFF).asSequence()
            .map { suffix -> "%04X".format(suffix) }
            .map { suffix -> "00$suffix" }
            .first { pin -> PairingSecret.compute(pin, client, server) != null }

    /** Свежий самоподписанный клиентский сертификат — как выпускает `CertStore`. */
    private fun selfSignedClient(): Pair<SSLContext, X509Certificate> {
        val (certificate, keys) = generateSelfSignedCertificate("Test Client")
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", keys.private, PAIRING_TEST_PASSWORD, arrayOf<java.security.cert.Certificate>(certificate))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, PAIRING_TEST_PASSWORD)
        val context = SSLContext.getInstance("TLS")
        context.init(factory.keyManagers, arrayOf(TrustAllForTest), SecureRandom())
        return context to certificate
    }
}

/**
 * Сервер-заглушка на loopback-порту, ведущая себя как настоящий телевизор в наблюдавшемся
 * сценарии: каждое новое соединение честно проводит рукопожатие протокола, а на первый же
 * присланный секрет отвечает `STATUS_BAD_SECRET` и закрывает сокет — не читая дальше.
 */
private class FakePairingServer(private val acceptCount: Int) {
    private val executor = Executors.newFixedThreadPool(acceptCount + 1)
    private val serverSocket: SSLServerSocket
    val port: Int
    val certificate: X509Certificate

    @Volatile
    var connectionsHandled = 0
        private set

    init {
        val (cert, keys) = generateSelfSignedCertificate("Fake TV")
        certificate = cert
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", keys.private, PAIRING_TEST_PASSWORD, arrayOf<java.security.cert.Certificate>(certificate))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, PAIRING_TEST_PASSWORD)
        val context = SSLContext.getInstance("TLS")
        context.init(factory.keyManagers, arrayOf(TrustAllForTest), SecureRandom())
        serverSocket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverSocket.needClientAuth = false
        port = serverSocket.localPort
    }

    fun start() {
        executor.submit {
            repeat(acceptCount) { index ->
                val socket = runCatching { serverSocket.accept() as SSLSocket }.getOrNull() ?: return@submit
                executor.submit { handle(socket, isLast = index == acceptCount - 1) }
            }
        }
    }

    /**
     * `ExecutorService.submit(Runnable)` молча проглатывает исключения, если результат нигде не
     * дожидаются (как здесь) — без этой обёртки баг в самой заглушке выглядел бы для теста как
     * необъяснимый обрыв соединения, а не как явная ошибка со стектрейсом.
     */
    private fun handle(socket: SSLSocket, isLast: Boolean) {
        try {
            handleUnsafe(socket, isLast)
        } catch (error: Throwable) {
            System.err.println("FakePairingServer.handle (isLast=$isLast) упал:")
            error.printStackTrace()
        }
    }

    private fun handleUnsafe(socket: SSLSocket, isLast: Boolean) {
        socket.use {
            val stream = MessageStream(socket)
            fun message() = OuterMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(OuterMessage.Status.STATUS_OK)

            stream.read() ?: return // pairing_request
            stream.write(message().setPairingRequestAck(PairingRequestAck.newBuilder()).build().toByteArray())

            stream.read() ?: return // options от клиента
            stream.write(message().setOptions(Options.newBuilder()).build().toByteArray())

            stream.read() ?: return // configuration от клиента
            stream.write(message().setConfigurationAck(ConfigurationAck.newBuilder()).build().toByteArray())

            stream.read() ?: return // secret — содержимое не проверяем, нас интересует таймлайн
            connectionsHandled += 1
            if (isLast) {
                // secret — обязательное поле в polo.proto (proto2); значение неважно, лишь бы
                // сообщение вообще собралось.
                stream.write(
                    message().setSecretAck(
                        SecretAck.newBuilder().setSecret(com.google.protobuf.ByteString.EMPTY),
                    ).build().toByteArray(),
                )
            } else {
                // Как настоящий телевизор: сообщаем об отказе и сразу рвём соединение, не читая
                // возможную повторную попытку на этом же сокете.
                stream.write(
                    OuterMessage.newBuilder()
                        .setProtocolVersion(2)
                        .setStatus(OuterMessage.Status.STATUS_BAD_SECRET)
                        .build()
                        .toByteArray(),
                )
            }
        }
    }

    fun close() {
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }
}

private val PAIRING_TEST_PASSWORD = "test".toCharArray()

private object TrustAllForTest : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Свежий самоподписанный сертификат RSA-2048 — годится и для клиента, и для сервера-заглушки. */
private fun generateSelfSignedCertificate(commonName: String): Pair<X509Certificate, KeyPair> {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048, SecureRandom())
    val keys = generator.generateKeyPair()
    val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build()
    val now = System.currentTimeMillis()
    val builder = JcaX509v3CertificateBuilder(
        name,
        BigInteger.valueOf(1000),
        Date(now),
        Date(now + 86_400_000L),
        name,
        keys.public,
    )
    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keys.private)
    val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
    return certificate to keys
}
