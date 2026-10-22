package com.hunter.btc

/**
 * scriptPubKey -> dirección (P2PKH, P2SH, segwit v0 y v1+), para enseñar a quién
 * paga una transacción que no ha construido la app (PSBT). Pura: SpkDireccionTest.
 */
object SpkDireccion {
    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val B32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)

    private fun base58check(payload: ByteArray): String {
        val datos = payload + sha(sha(payload)).copyOfRange(0, 4)
        var n = java.math.BigInteger(1, datos)
        val sb = StringBuilder()
        val b58 = java.math.BigInteger.valueOf(58)
        while (n.signum() > 0) { val qr = n.divideAndRemainder(b58); sb.append(B58[qr[1].toInt()]); n = qr[0] }
        for (b in datos) { if (b.toInt() == 0) sb.append('1') else break }
        return sb.reverse().toString()
    }

    private fun polymod(v: IntArray): Int {
        val g = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var c = 1
        for (x in v) { val b = c ushr 25; c = ((c and 0x1ffffff) shl 5) xor x; for (i in 0 until 5) if ((b shr i) and 1 == 1) c = c xor g[i] }
        return c
    }

    private fun segwit(hrp: String, ver: Int, prog: ByteArray): String {
        val datos = ArrayList<Int>(); datos.add(ver)
        var acc = 0; var bits = 0
        for (b in prog) { acc = (acc shl 8) or (b.toInt() and 0xff); bits += 8; while (bits >= 5) { bits -= 5; datos.add((acc shr bits) and 31) } }
        if (bits > 0) datos.add((acc shl (5 - bits)) and 31)
        val exp = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }
        val cte = if (ver == 0) 1 else 0x2bc830a3
        val mod = polymod((exp + datos + List(6) { 0 }).toIntArray()) xor cte
        val cs = (0 until 6).map { (mod shr (5 * (5 - it))) and 31 }
        return hrp + "1" + (datos + cs).joinToString("") { B32[it].toString() }
    }

    /** La dirección P2PKH de un hash de clave de 20 bytes. */
    fun p2pkh(h20: ByteArray, testnet: Boolean = false): String =
        base58check(byteArrayOf((if (testnet) 0x6f else 0x00).toByte()) + h20)

    /** La dirección P2SH de un hash de script de 20 bytes. */
    fun p2sh(h20: ByteArray, testnet: Boolean = false): String =
        base58check(byteArrayOf((if (testnet) 0xc4 else 0x05).toByte()) + h20)

    /** La dirección, o null si el script no es de un tipo estándar con dirección. */
    fun de(spk: ByteArray, testnet: Boolean = false): String? {
        val u = spk.map { it.toInt() and 0xff }
        return when {
            u.size == 25 && u[0] == 0x76 && u[1] == 0xa9 && u[2] == 0x14 && u[23] == 0x88 && u[24] == 0xac ->
                base58check(byteArrayOf((if (testnet) 0x6f else 0x00).toByte()) + spk.copyOfRange(3, 23))
            u.size == 23 && u[0] == 0xa9 && u[1] == 0x14 && u[22] == 0x87 ->
                base58check(byteArrayOf((if (testnet) 0xc4 else 0x05).toByte()) + spk.copyOfRange(2, 22))
            u.size in 4..42 && (u[0] == 0x00 || u[0] in 0x51..0x60) && u[1] == u.size - 2 -> {
                val ver = if (u[0] == 0) 0 else u[0] - 0x50
                if (ver == 0 && u[1] != 20 && u[1] != 32) null
                else segwit(if (testnet) "tb" else "bc", ver, spk.copyOfRange(2, spk.size))
            }
            else -> null
        }
    }
}
