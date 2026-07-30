package com.btcseedrecovery

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun decode(addr: String): ByteArray? {
        return try {
            val lower = addr.lowercase()
            val sep = lower.lastIndexOf('1')
            if (sep < 1) return null
            val data = lower.drop(sep + 1).dropLast(6).map { CHARSET.indexOf(it) }
            if (data.any { it < 0 }) return null
            val result = convertBits(data, 5, 8, false)
            if (result != null && result.size == 20) result else null
        } catch (e: Exception) { null }
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        val maxv = (1 shl to) - 1
        for (v in data) {
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad && bits > 0) {
            out.add(((acc shl (to - bits)) and maxv).toByte())
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toByteArray()
    }
}
