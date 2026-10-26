package com.hunter.btc

/**
 * scriptPubKey -> dirección (P2PKH, P2SH, segwit v0 y v1+), para enseñar a quién
 * paga una transacción que no ha construido la app (PSBT). Pura: SpkDireccionTest.
 *
 * Sin BigInteger ni colecciones: el CSV de UTXO de la pestaña Core llama a esto unas 190 millones de veces,
 * y la versión anterior (BigInteger para base58, listas con cajas para bech32, dos MessageDigest nuevos por
 * dirección) era más de la mitad del tiempo de toda la exportación.
 */
object SpkDireccion {
    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val B32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    // MessageDigest no es seguro entre hilos; getInstance() en cada llamada busca el proveedor cada vez.
    private val sha = ThreadLocal.withInitial { java.security.MessageDigest.getInstance("SHA-256") }

    /** version(1) + h20(20) en Base58Check: 25 bytes, el algoritmo de EncodeBase58 de Core sobre un IntArray. */
    private fun base58check(version: Int, h20: ByteArray, desde: Int): String {
        val datos = ByteArray(25)
        datos[0] = version.toByte(); System.arraycopy(h20, desde, datos, 1, 20)
        val md = sha.get()
        md.update(datos, 0, 21); val h1 = md.digest()
        val h2 = md.digest(h1)
        System.arraycopy(h2, 0, datos, 21, 4)
        var ceros = 0
        while (ceros < 25 && datos[ceros].toInt() == 0) ceros++
        // Base 58^5 por limbo (656 356 768 < 2^31): un quinto de vueltas que dígito a dígito.
        val L = IntArray(8); var nl = 0
        for (i in ceros until 25) {
            var carry = (datos[i].toLong() and 0xff)
            var k = 0
            while (k < nl || carry != 0L) {
                val v = (if (k < nl) L[k].toLong() * 256 else 0L) + carry
                L[k] = (v % 656_356_768L).toInt(); carry = v / 656_356_768L
                k++
            }
            if (k > nl) nl = k
        }
        val dig = CharArray(nl * 5); var nd = 0
        for (k in 0 until nl) { var x = L[k]; for (j in 0 until 5) { dig[nd++] = B58[x % 58]; x /= 58 } }
        while (nd > 0 && dig[nd - 1] == '1') nd--
        val out = CharArray(ceros + nd)
        for (i in 0 until ceros) out[i] = '1'
        for (i in 0 until nd) out[ceros + i] = dig[nd - 1 - i]
        return String(out)
    }

    private fun paso(c: Int, x: Int): Int {
        val b = c ushr 25
        var r = ((c and 0x1ffffff) shl 5) xor x
        if (b and 1 != 0) r = r xor 0x3b6a57b2
        if (b and 2 != 0) r = r xor 0x26508e6d
        if (b and 4 != 0) r = r xor 0x1ea119fa
        if (b and 8 != 0) r = r xor 0x3d4233dd
        if (b and 16 != 0) r = r xor 0x2a1462b3
        return r
    }

    private fun segwit(hrp: String, ver: Int, prog: ByteArray, desde: Int, largo: Int): String {
        val datos = IntArray(1 + (largo * 8 + 4) / 5)
        var n = 0
        datos[n++] = ver
        var acc = 0; var bits = 0
        for (i in desde until desde + largo) {
            acc = (acc shl 8) or (prog[i].toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; datos[n++] = (acc shr bits) and 31 }
        }
        if (bits > 0) datos[n++] = (acc shl (5 - bits)) and 31
        var c = 1
        for (ch in hrp) c = paso(c, ch.code shr 5)
        c = paso(c, 0)
        for (ch in hrp) c = paso(c, ch.code and 31)
        for (i in 0 until n) c = paso(c, datos[i])
        for (i in 0 until 6) c = paso(c, 0)
        val mod = c xor (if (ver == 0) 1 else 0x2bc830a3)
        val out = CharArray(hrp.length + 1 + n + 6)
        var o = 0
        for (ch in hrp) out[o++] = ch
        out[o++] = '1'
        for (i in 0 until n) out[o++] = B32[datos[i]]
        for (i in 0 until 6) out[o++] = B32[(mod shr (5 * (5 - i))) and 31]
        return String(out)
    }

    /** La dirección P2PKH de un hash de clave de 20 bytes. */
    fun p2pkh(h20: ByteArray, testnet: Boolean = false): String = base58check(if (testnet) 0x6f else 0x00, h20, 0)

    /** La dirección P2SH de un hash de script de 20 bytes. */
    fun p2sh(h20: ByteArray, testnet: Boolean = false): String = base58check(if (testnet) 0xc4 else 0x05, h20, 0)

    /** La dirección, o null si el script no es de un tipo estándar con dirección. */
    fun de(spk: ByteArray, testnet: Boolean = false): String? = de(spk, spk.size, testnet)

    /** Igual, mirando sólo los [n] primeros bytes de [spk]: así quien ya tiene el script en un búfer no lo copia. */
    fun de(spk: ByteArray, n: Int, testnet: Boolean = false): String? {
        fun u(i: Int) = spk[i].toInt() and 0xff
        return when {
            n == 25 && u(0) == 0x76 && u(1) == 0xa9 && u(2) == 0x14 && u(23) == 0x88 && u(24) == 0xac ->
                base58check(if (testnet) 0x6f else 0x00, spk, 3)
            n == 23 && u(0) == 0xa9 && u(1) == 0x14 && u(22) == 0x87 ->
                base58check(if (testnet) 0xc4 else 0x05, spk, 2)
            n in 4..42 && (u(0) == 0x00 || u(0) in 0x51..0x60) && u(1) == n - 2 -> {
                val ver = if (u(0) == 0) 0 else u(0) - 0x50
                if (ver == 0 && u(1) != 20 && u(1) != 32) null
                else segwit(if (testnet) "tb" else "bc", ver, spk, 2, n - 2)
            }
            else -> null
        }
    }
}
