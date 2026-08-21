package com.sprit.tvremote.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Кадрирование сообщений: длина varint, затем ровно столько байт. */
class MessageStreamTest {

    @Test
    fun `сообщения читаются в том же виде, в каком записаны`() {
        val transport = LoopbackSocket()
        val stream = MessageStream(transport)

        val short = byteArrayOf(1, 2, 3)
        val long = ByteArray(500) { (it % 251).toByte() }
        stream.write(short)
        stream.write(long)

        assertArrayEquals(short, stream.read())
        assertArrayEquals(long, stream.read())
        assertNull("после последнего сообщения поток пуст", stream.read())
    }

    @Test
    fun `длина больше 127 занимает несколько байт varint`() {
        val transport = LoopbackSocket()
        MessageStream(transport).write(ByteArray(300))

        val written = transport.written()
        // 300 = 0b100101100 -> 0xAC 0x02
        assertEquals(0xAC, written[0].toInt() and 0xFF)
        assertEquals(0x02, written[1].toInt() and 0xFF)
        assertEquals(302, written.size)
    }

    /** Сокет-заглушка: всё записанное тут же становится доступным для чтения. */
    private class LoopbackSocket : Socket() {
        private val buffer = ByteArrayOutputStream()
        private var readPosition = 0

        fun written(): ByteArray = buffer.toByteArray()

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(byte: Int) = buffer.write(byte)
            override fun write(bytes: ByteArray, offset: Int, length: Int) =
                buffer.write(bytes, offset, length)
        }

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                val data = buffer.toByteArray()
                return if (readPosition < data.size) data[readPosition++].toInt() and 0xFF else -1
            }
        }
    }
}
