package com.adblok.app.vpn

import java.nio.ByteBuffer

/** Минимальный разбор/сборка DNS-сообщений (RFC 1035). */
object DnsPacket {

    /** Возвращает имя из первого вопроса или null. */
    fun questionName(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 12) return null
        val buf = ByteBuffer.wrap(data, offset, length)
        buf.position(offset + 4)
        val qdCount = buf.short.toInt() and 0xFFFF
        if (qdCount < 1) return null
        var pos = offset + 12
        val end = offset + length
        val sb = StringBuilder()
        while (pos < end) {
            val len = data[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if (len and 0xC0 != 0) return null // сжатие в вопросе не используется
            if (pos + len > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    /** Строит ответ NXDOMAIN на основе запроса. */
    fun buildNxDomain(query: ByteArray, offset: Int, length: Int): ByteArray {
        val out = query.copyOfRange(offset, offset + length)
        // flags: QR=1, RD копируем, RA=1, RCODE=3 (NXDOMAIN)
        val rd = out[2].toInt() and 0x01
        out[2] = (0x80 or (out[2].toInt() and 0x78) or (rd shl 0)).toByte()
        out[3] = 0x83.toByte()
        // ANCOUNT/NSCOUNT/ARCOUNT = 0
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        return out
    }
}
