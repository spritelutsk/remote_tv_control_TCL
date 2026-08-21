package com.sprit.tvremote.tv

import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Диагностика против живого телевизора. Без `-PtvHost=…` все проверки пропускаются, поэтому
 * обычный прогон тестов их не задевает:
 *
 * `gradlew :app:testDebugUnitTest --tests '*LiveTvProbe*' -PtvHost=192.168.1.106`
 *
 * `probe` подключается уже спаренным сертификатом (каталог с `cert.pem`/`key.pem` задаётся
 * через `-PtvCert=…`) и печатает состояние телевизора. `probeUnknownCertificate` показывает, как
 * телевизор отвергает незнакомый сертификат. `probePairingHandshake` доводит спаривание до
 * запроса кода — телевизор при этом на секунду покажет код на экране. `probeVoiceInvite`
 * и `probeVoice` проверяют голосовой канал протокола, `probeTextSearch` — открытие поиска
 * ссылкой.
 */
class LiveTvProbeTest {

    @Test
    fun probe() {
        val host = System.getProperty("tv.host").orEmpty().ifBlank {
            println("tvHost не задан — пропускаю")
            return
        }
        val client = pairedClient() ?: run {
            println("нет спаренного сертификата (-PtvCert=…) — пропускаю")
            return
        }
        println("подключаюсь к $host:$REMOTE_PORT…")
        val socket = connectTls(client, host, REMOTE_PORT, 8_000, 20_000)
        println("рукопожатие прошло, шифр: ${socket.session.cipherSuite}, протокол: ${socket.session.protocol}")

        val gotEvents = CountDownLatch(3)
        val session = RemoteSession(socket, onEvent = { event ->
            println("событие: $event")
            gotEvents.countDown()
        })
        val reader = Thread { runCatching { session.run() }.onFailure { println("чтение оборвалось: $it") } }
        reader.isDaemon = true
        reader.start()

        val ok = gotEvents.await(15, TimeUnit.SECONDS)
        println(if (ok) "телевизор отвечает, сессия живая" else "событий не хватило за 15 с")
        session.close()
    }

    /**
     * Что делает телевизор с незнакомым сертификатом — именно это происходит при первом
     * запуске приложения на телефоне.
     */
    @Test
    fun probeUnknownCertificate() {
        val host = System.getProperty("tv.host").orEmpty().ifBlank {
            println("tvHost не задан — пропускаю")
            return
        }

        val (context, _) = freshClient()

        println("подключаюсь незнакомым сертификатом к $host:$REMOTE_PORT…")
        val socket = try {
            connectTls(context, host, REMOTE_PORT, 8_000, 20_000)
        } catch (error: Exception) {
            println("рукопожатие упало: ${error.javaClass.name}: ${error.message}")
            return
        }
        println("рукопожатие прошло (${socket.session.protocol}) — сертификат ещё не проверен сервером")

        val stream = MessageStream(socket)
        try {
            val raw = stream.read()
            println(if (raw == null) "чтение вернуло null (телевизор закрыл соединение)" else "пришло ${raw.size} байт")
        } catch (error: Exception) {
            println("чтение упало: ${error.javaClass.name}: ${error.message}")
        }
        socket.closeQuietly()
    }

    /**
     * Путь спаривания до запроса кода: телевизор на секунду показывает код на экране, после
     * чего мы отказываемся от ввода и рвём соединение — окно на телевизоре пропадает.
     */
    @Test
    fun probePairingHandshake() {
        val host = System.getProperty("tv.host").orEmpty().ifBlank {
            println("tvHost не задан — пропускаю")
            return
        }
        val (context, certificate) = freshClient()

        println("начинаю спаривание с $host:$PAIRING_PORT…")
        val error = runCatching {
            kotlinx.coroutines.runBlocking {
                Pairing.pair(host, context, certificate) { deviceName, retry ->
                    println("телевизор показал код, имя устройства: «$deviceName», повтор: $retry")
                    null // отказываемся вводить — соединение закроется
                }
            }
        }.exceptionOrNull()

        println(
            when (error) {
                is PairingCancelled -> "путь спаривания дошёл до ввода кода — протокол в порядке"
                null -> "спаривание неожиданно завершилось без кода"
                else -> "спаривание упало: ${error.javaClass.name}: ${error.message}"
            },
        )
    }

    /**
     * Чем заставить телевизор прислать приглашение `remote_voice_begin`: перебираем кнопки и
     * способ нажатия. Приглашение — единственный признак, что телевизор действительно слушает.
     */
    @Test
    fun probeVoiceInvite() {
        val host = System.getProperty("tv.host").orEmpty().ifBlank {
            println("tvHost не задан — пропускаю")
            return
        }
        val client = pairedClient() ?: run {
            println("нет спаренного сертификата (-PtvCert=…) — пропускаю")
            return
        }
        val socket = connectTls(client, host, REMOTE_PORT, 8_000, 20_000)
        val session = RemoteSession(socket, onEvent = {})
        val reader = Thread { runCatching { session.run() } }
        reader.isDaemon = true
        reader.start()
        Thread.sleep(2_000)

        val keyCodes = listOf(
            com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_SEARCH,
            com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_ASSIST,
            com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_VOICE_ASSIST,
        )
        for (keyCode in keyCodes) {
            for (long in listOf(false, true)) {
                session.sendKey(com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_HOME)
                Thread.sleep(2_500)
                session.awaitVoiceInvite(1) // очистить очередь

                if (long) {
                    session.sendKey(keyCode, com.sprit.tvremote.proto.remote.RemoteDirection.START_LONG)
                    Thread.sleep(800)
                    session.sendKey(keyCode, com.sprit.tvremote.proto.remote.RemoteDirection.END_LONG)
                } else {
                    session.sendKey(keyCode)
                }
                val invite = session.awaitVoiceInvite(5_000)
                println("$keyCode ${if (long) "удержание" else "нажатие"} → приглашение: $invite")
            }
        }
        session.sendKey(com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_HOME)
        session.close()
    }

    /**
     * Обходной путь к голосу: распознать речь на телефоне и отправить телевизору результат.
     * Проверяем, каким способом до телевизора доходит запрос — через поле ввода или ссылкой.
     */
    @Test
    fun probeTextSearch() {
        val host = System.getProperty("tv.host").orEmpty().ifBlank {
            println("tvHost не задан — пропускаю")
            return
        }
        val client = pairedClient() ?: run {
            println("нет спаренного сертификата (-PtvCert=…) — пропускаю")
            return
        }
        val socket = connectTls(client, host, REMOTE_PORT, 8_000, 20_000)
        val apps = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val session = RemoteSession(socket, onEvent = { if (it is TvEvent.App) apps.add(it.packageName) })
        val reader = Thread { runCatching { session.run() } }
        reader.isDaemon = true
        reader.start()
        Thread.sleep(2_000)

        val query = "%D0%BA%D0%BE%D1%82%D0%B8%D0%BA%D0%B8" // «котики»
        // Ссылка со схемой vnd.youtube — единственная, которая открывает поиск: обычная
        // https-ссылка отдаётся системному диалогу выбора, а ссылка с решёткой рвёт соединение.
        val links = listOf("vnd.youtube" to "vnd.youtube://results?search_query=$query")
        for ((title, link) in links) {
            session.sendKey(com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_HOME)
            Thread.sleep(2_500)
            apps.clear()
            runCatching { session.launchApp(link) }
                .onFailure { println("$title → оборвалось: ${it.message}") }
            Thread.sleep(7_000)
            println("$title → приложения: ${apps.toList()}")
        }
        session.sendKey(com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_HOME)
        session.close()
    }

    /**
     * Уже спаренный клиент: пара `cert.pem`/`key.pem`, которую телевизор знает. Каталог с ними
     * задаётся через `-PtvCert=…`; без него пробы, требующие спаривания, пропускаются.
     */
    private fun pairedClient(): SSLContext? {
        val dir = File(System.getProperty("tv.certDir").orEmpty())
        val certificateFile = File(dir, "cert.pem")
        val keyFile = File(dir, "key.pem")
        if (!certificateFile.isFile || !keyFile.isFile) return null

        val certificate = readCertificate(certificateFile)
        val privateKey = readPrivateKey(keyFile)

        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", privateKey, PASSWORD, arrayOf<java.security.cert.Certificate>(certificate))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, PASSWORD)
        return SSLContext.getInstance("TLS").apply {
            init(
                factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().toTypedArray(),
                arrayOf(TrustAll),
                SecureRandom(),
            )
        }
    }

    /** Свежий самоподписанный сертификат — такой же, какой выпускает приложение при установке. */
    private fun freshClient(): Pair<SSLContext, X509Certificate> {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keys = generator.generateKeyPair()
        val name = org.bouncycastle.asn1.x500.X500NameBuilder(org.bouncycastle.asn1.x500.style.BCStyle.INSTANCE)
            .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.CN, "Probe Client")
            .build()
        val now = System.currentTimeMillis()
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name,
            java.math.BigInteger.valueOf(1000),
            java.util.Date(now),
            java.util.Date(now + 86_400_000L),
            name,
            keys.public,
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keys.private)
        val certificate = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", keys.private, PASSWORD, arrayOf<java.security.cert.Certificate>(certificate))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, PASSWORD)
        val context = SSLContext.getInstance("TLS")
        context.init(
            factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().toTypedArray(),
            arrayOf(TrustAll),
            SecureRandom(),
        )
        return context to certificate
    }

    /**
     * Голосовой поиск: поддерживает ли телевизор голос и открывается ли сессия. Телевизор
     * покажет ассистента и послушает секунду тишины.
     */
    @Test
    fun probeVoice() {
        val host = System.getProperty("tv.host").orEmpty().ifBlank {
            println("tvHost не задан — пропускаю")
            return
        }
        val client = pairedClient() ?: run {
            println("нет спаренного сертификата (-PtvCert=…) — пропускаю")
            return
        }
        val socket = connectTls(client, host, REMOTE_PORT, 8_000, 20_000)
        val session = RemoteSession(
            socket,
            onEvent = { println("событие: $it") },
            onRawMessage = { message ->
                val text = message.toString().lines().joinToString(" ") { it.trim() }
                if ("remote_ping_request" !in text) println("  сообщение: $text")
            },
        )
        val reader = Thread { runCatching { session.run() } }
        reader.isDaemon = true
        reader.start()

        Thread.sleep(2_000) // дать рукопожатию договориться о возможностях
        println("голос поддерживается: ${session.isVoiceSupported}")

        // Речь для проверки распознавания задаётся файлом WAV 8 кГц / 16 бит / моно
        // (-PtvVoice=…); без него отправляем тишину — тогда проверяется только конвейер.
        val wav = File(System.getProperty("tv.voiceFile").orEmpty())
        val pcm = if (wav.isFile) {
            wav.readBytes().drop(WAV_HEADER_BYTES).toByteArray()
        } else {
            ByteArray(3 * 8_000 * 2)
        }
        println("звук: ${pcm.size / 2 / 8000.0} с, из файла: ${wav.isFile}")

        val voiceSessionId = session.startVoice()
        println("номер голосовой сессии: $voiceSessionId")
        if (voiceSessionId == null) {
            println("телевизор не пригласил — звук отправлять бессмысленно")
            session.close()
            return
        }
        pcm.asSequence().chunked(8 * 1024).forEach { chunk ->
            session.sendVoiceChunk(chunk.toByteArray(), voiceSessionId)
            Thread.sleep(500) // отдаём звук в реальном темпе, как микрофон
        }
        session.endVoice(voiceSessionId)
        println("звук отправлен, жду реакции телевизора…")
        Thread.sleep(5_000)

        // Если бы телевизор оборвал связь на потоке звука, отправка сюда бы не дошла.
        session.sendKey(com.sprit.tvremote.proto.remote.RemoteKeyCode.KEYCODE_BACK)
        println("соединение живо после голосовой сессии")
        session.close()
    }

    private fun readCertificate(file: File): X509Certificate =
        file.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun readPrivateKey(file: File): PrivateKey =
        PEMParser(file.reader()).use { parser ->
            when (val parsed = parser.readObject()) {
                is PEMKeyPair -> JcaPEMKeyConverter().getKeyPair(parsed).private
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> JcaPEMKeyConverter().getPrivateKey(parsed)
                else -> error("не понял формат ключа: ${parsed?.javaClass}")
            }
        }

    private object TrustAll : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        val PASSWORD = "probe".toCharArray()
        const val WAV_HEADER_BYTES = 44
    }
}
