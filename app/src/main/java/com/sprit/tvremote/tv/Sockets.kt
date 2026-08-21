package com.sprit.tvremote.tv

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Порт протокола пульта. */
const val REMOTE_PORT = 6466

/** Порт протокола спаривания. */
const val PAIRING_PORT = 6467

/**
 * Установить TLS-соединение с телевизором и сразу выполнить рукопожатие: если телевизор не
 * знает наш сертификат, ошибка должна прийти здесь, а не при первой команде.
 */
internal fun connectTls(
    sslContext: SSLContext,
    host: String,
    port: Int,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
): SSLSocket {
    val plain = Socket()
    try {
        plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
        plain.tcpNoDelay = true
    } catch (error: Throwable) {
        plain.closeQuietly()
        throw error
    }
    val socket = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
    return try {
        socket.soTimeout = readTimeoutMs
        socket.startHandshake()
        socket
    } catch (error: Throwable) {
        socket.closeQuietly()
        throw error
    }
}

internal fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Закрываем на пути обработки ошибки — вторая ошибка ничего не добавит.
    }
}
