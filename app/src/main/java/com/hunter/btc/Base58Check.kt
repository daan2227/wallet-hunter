package com.hunter.btc

object Base58Check {
    private const val ALPHA = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun decodePayload(addr: String, skip: Int): ByteArray? {
        return try {
            val buf = LongArray(25)
            for (ch in addr) {
                val idx = ALPHA.indexOf(ch)
                if (idx < 0) return null
                var carry = idx.toLong()
                for (j in 24 downTo 0) { carry += 58L * buf[j]; buf[j] = carry % 256; carry /= 256 }
            }
            val bytes = ByteArray(25) { buf[it].toByte() }
            bytes.drop(skip).take(20).toByteArray()
        } catch (e: Exception) { null }
    }
}
