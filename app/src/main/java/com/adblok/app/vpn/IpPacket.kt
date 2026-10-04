package com.adblok.app.vpn

/** Работа с IPv4/UDP-пакетами в TUN-интерфейсе. */
object IpPacket {

    const val PROTO_UDP = 17

    fun version(p: ByteArray) = (p[0].toInt() shr 4) and 0x0F
    fun ihl(p: ByteArray) = (p[0].toInt() and 0x0F) * 4
    fun protocol(p: ByteArray) = p[9].toInt() and 0xFF
    fun srcAddr(p: ByteArray) = p.copyOfRange(12, 16)
    fun dstAddr(p: ByteArray) = p.copyOfRange(16, 20)

    fun srcPort(p: ByteArray): Int {
        val o = ihl(p); return ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
    }

    fun dstPort(p: ByteArray): Int {
        val o = ihl(p); return ((p[o + 2].toInt() and 0xFF) shl 8) or (p[o + 3].toInt() and 0xFF)
    }

    fun udpPayloadOffset(p: ByteArray) = ihl(p) + 8

    fun udpPayloadLength(p: ByteArray): Int {
        val o = ihl(p)
        val udpLen = ((p[o + 4].toInt() and 0xFF) shl 8) or (p[o + 5].toInt() and 0xFF)
        return (udpLen - 8).coerceAtLeast(0)
    }

    /** Собирает IPv4/UDP-пакет-ответ: адреса и порты меняются местами. */
    fun buildUdpResponse(request: ByteArray, payload: ByteArray): ByteArray {
        val ipLen = 20
        val total = ipLen + 8 + payload.size
        val out = ByteArray(total)

        out[0] = 0x45
        out[1] = 0
        out[2] = ((total shr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = 0; out[5] = 0
        out[6] = 0x40; out[7] = 0 // Don't fragment
        out[8] = 64 // TTL
        out[9] = PROTO_UDP.toByte()
        // src = dst запроса, dst = src запроса
        System.arraycopy(request, 16, out, 12, 4)
        System.arraycopy(request, 12, out, 16, 4)
        writeChecksum(out, 0, ipLen, 10)

        val sp = srcPort(request)
        val dp = dstPort(request)
        out[20] = ((dp shr 8) and 0xFF).toByte()
        out[21] = (dp and 0xFF).toByte()
        out[22] = ((sp shr 8) and 0xFF).toByte()
        out[23] = (sp and 0xFF).toByte()
        val udpLen = 8 + payload.size
        out[24] = ((udpLen shr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0; out[27] = 0
        System.arraycopy(payload, 0, out, 28, payload.size)
        writeUdpChecksum(out, udpLen)
        return out
    }

    private fun writeChecksum(buf: ByteArray, offset: Int, length: Int, csumPos: Int) {
        buf[csumPos] = 0; buf[csumPos + 1] = 0
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 == 1) sum += (buf[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val csum = sum.inv().toInt() and 0xFFFF
        buf[csumPos] = ((csum shr 8) and 0xFF).toByte()
        buf[csumPos + 1] = (csum and 0xFF).toByte()
    }

    /** UDP-контрольная сумма с псевдозаголовком. */
    private fun writeUdpChecksum(p: ByteArray, udpLen: Int) {
        var sum = 0L
        for (i in 12 until 20 step 2) sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        sum += PROTO_UDP.toLong()
        sum += udpLen.toLong()
        var i = 20
        val end = 20 + udpLen
        while (i < end - 1) {
            sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (udpLen % 2 == 1) sum += (p[end - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        var csum = (sum.inv().toInt() and 0xFFFF)
        if (csum == 0) csum = 0xFFFF
        p[26] = ((csum shr 8) and 0xFF).toByte()
        p[27] = (csum and 0xFF).toByte()
    }
}
