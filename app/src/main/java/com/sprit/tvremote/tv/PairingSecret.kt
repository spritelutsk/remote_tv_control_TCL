package com.sprit.tvremote.tv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Секрет спаривания — доказательство того, что мы видим экран телевизора.
 *
 * Это SHA-256 от модуля и открытой экспоненты обоих сертификатов и последних четырёх символов
 * показанного кода. Первые два символа кода — контрольный байт того же хеша, поэтому опечатку
 * видно ещё до отправки.
 */
internal object PairingSecret {

    const val PIN_LENGTH = 6

    /** Секрет для введённого кода или `null`, если код набран с ошибкой. */
    fun compute(
        pin: String,
        clientCertificate: X509Certificate,
        serverCertificate: X509Certificate,
    ): ByteArray? {
        if (pin.length != PIN_LENGTH || pin.any { it.digitToIntOrNull(16) == null }) return null

        val client = clientCertificate.publicKey as RSAPublicKey
        val server = serverCertificate.publicKey as RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(client.modulus.toUnsignedBytes())
        digest.update(client.publicExponent.toUnsignedBytes())
        digest.update(server.modulus.toUnsignedBytes())
        digest.update(server.publicExponent.toUnsignedBytes())
        digest.update(pin.substring(2).hexToBytes())
        val hash = digest.digest()

        val checksum = pin.substring(0, 2).toInt(16)
        return if ((hash[0].toInt() and 0xFF) == checksum) hash else null
    }

    /**
     * Число без знакового бита и без ведущих нулей: шестнадцатеричная запись, дополненная нулём
     * слева при нечётной длине. Именно в таком виде его подаёт в хеш эталонная реализация
     * протокола, а `BigInteger.toByteArray()` дал бы лишний нулевой байт.
     */
    private fun BigInteger.toUnsignedBytes(): ByteArray {
        val hex = toString(16).uppercase()
        return (if (hex.length % 2 == 0) hex else "0$hex").hexToBytes()
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(16) shl 4) or this[index * 2 + 1].digitToInt(16)).toByte()
    }
}
