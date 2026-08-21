package com.sprit.tvremote.tv

import android.content.Context
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Клиентский сертификат для протокола Remote v2.
 *
 * Телевизор запоминает наш самоподписанный сертификат при спаривании и после этого пускает
 * только его предъявителя. Сертификат с ключом лежит в приватном хранилище приложения
 * (PKCS#12), удаление файла = сброс спаривания.
 */
class CertStore(context: Context) {

    private val file = File(context.filesDir, KEYSTORE_FILE)
    private var cached: KeyStore? = null

    /** Сертификат клиента. Нужен для вычисления секрета при спаривании. */
    fun clientCertificate(): X509Certificate =
        keyStore().getCertificate(alias(keyStore())) as X509Certificate

    /** Забыть спаривание: следующий запуск выпустит новый сертификат. */
    @Synchronized
    fun reset() {
        cached = null
        file.delete()
    }

    /**
     * SSL-контекст, предъявляющий клиентский сертификат и принимающий любой серверный.
     *
     * Сертификат телевизора самоподписанный и проверить его нечем — подлинность устройства
     * подтверждается не им, а PIN-кодом на экране во время спаривания (так же делает
     * androidtvremote2 в Home Assistant).
     */
    fun createSslContext(): SSLContext {
        val store = keyStore()
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, PASSWORD)
        val delegate = factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().first()
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf(FixedAliasKeyManager(delegate, alias(store))),
            arrayOf(TrustAnyServer),
            SecureRandom(),
        )
        return context
    }

    @Synchronized
    private fun keyStore(): KeyStore {
        cached?.let { return it }
        val store = if (file.exists()) load() else generate()
        cached = store
        return store
    }

    private fun load(): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, PASSWORD) }
        return store
    }

    private fun generate(): KeyStore {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE, SecureRandom())
        val keys = generator.generateKeyPair()

        val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, CLIENT_NAME).build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(1000),
            Date(now),
            Date(now + VALIDITY_MS),
            name,
            keys.public,
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(0))
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, CLIENT_NAME)),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keys.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(ALIAS, keys.private, PASSWORD, arrayOf<java.security.cert.Certificate>(certificate))
        file.outputStream().use { store.store(it, PASSWORD) }
        return store
    }

    private fun alias(store: KeyStore): String =
        store.aliases().asSequence().firstOrNull() ?: ALIAS

    companion object {
        /** Имя, которое телевизор показывает в списке спаренных устройств. */
        const val CLIENT_NAME = "Android TV Remote"

        private const val KEYSTORE_FILE = "client.p12"
        private const val ALIAS = "client"
        private val PASSWORD = "atvremote".toCharArray()
        private const val KEY_SIZE = 2048
        private const val VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}

/**
 * Телевизор запрашивает клиентский сертификат, не называя знакомых ему УЦ, поэтому штатный
 * KeyManager решил бы, что подходящего сертификата нет, и не отправил бы ничего. Предъявляем
 * наш единственный сертификат всегда.
 */
private class FixedAliasKeyManager(
    private val delegate: X509ExtendedKeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        delegate.getCertificateChain(this.alias)

    override fun getPrivateKey(alias: String?): PrivateKey? = delegate.getPrivateKey(this.alias)
}

private object TrustAnyServer : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
