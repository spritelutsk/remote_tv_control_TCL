package com.sprit.tvremote.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Секрет спаривания должен совпадать байт в байт с эталонной реализацией протокола
 * (androidtvremote2): телевизор сверяет его с тем, что посчитал сам, и любое расхождение —
 * это отказ в спаривании.
 *
 * Сертификаты и ожидаемый секрет в `src/test/resources/pairing` посчитаны именно этой
 * библиотекой, см. `docs/android.md`.
 */
class PairingSecretTest {

    private val client = readCertificate("/pairing/client.pem")
    private val server = readCertificate("/pairing/server.pem")
    private val expected = readLines("/pairing/expected.txt")
    private val pin = expected[0]
    private val secretHex = expected[1]

    @Test
    fun `секрет совпадает с эталоном`() {
        val secret = PairingSecret.compute(pin, client, server)
        assertEquals(secretHex, secret?.toHex())
    }

    @Test
    fun `код с опечаткой отвергается до отправки`() {
        val wrongChecksum = "00" + pin.substring(2)
        assertNull(PairingSecret.compute(wrongChecksum, client, server))

        val wrongSuffix = pin.substring(0, 2) + "FFFF"
        assertNull(PairingSecret.compute(wrongSuffix, client, server))
    }

    @Test
    fun `код неверной длины или не шестнадцатеричный отвергается`() {
        assertNull(PairingSecret.compute(pin.dropLast(1), client, server))
        assertNull(PairingSecret.compute("ZZZZZZ", client, server))
    }

    private fun readCertificate(resource: String): X509Certificate =
        javaClass.getResourceAsStream(resource).use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
        }

    private fun readLines(resource: String): List<String> =
        javaClass.getResourceAsStream(resource)!!.bufferedReader().readLines().filter { it.isNotBlank() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
