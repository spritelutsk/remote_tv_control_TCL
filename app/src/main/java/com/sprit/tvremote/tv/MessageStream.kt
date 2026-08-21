package com.sprit.tvremote.tv

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.Socket

/**
 * Кадрирование сообщений: каждое protobuf-сообщение предваряется своей длиной в varint.
 * Так говорят оба протокола — и спаривание (порт 6467), и пульт (порт 6466).
 */
class MessageStream(socket: Socket) {

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Any()

    /** Прочитать одно сообщение. `null`, если собеседник закрыл соединение. */
    fun read(): ByteArray? {
        val length = readVarint() ?: return null
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read < 0) throw EOFException("Соединение оборвалось на середине сообщения")
            offset += read
        }
        return payload
    }

    fun write(payload: ByteArray) = synchronized(writeLock) {
        var length = payload.size
        while (true) {
            if (length and 0x7F.inv() == 0) {
                output.write(length)
                break
            }
            output.write((length and 0x7F) or 0x80)
            length = length ushr 7
        }
        output.write(payload)
        output.flush()
    }

    private fun readVarint(): Int? {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val byte = input.read()
            if (byte < 0) return if (shift == 0) null else throw EOFException("Обрыв внутри varint")
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw EOFException("Слишком длинный varint")
    }
}
