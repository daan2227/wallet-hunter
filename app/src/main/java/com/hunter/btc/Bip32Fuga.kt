package com.hunter.btc

import java.math.BigInteger

/**
 * La fuga de BIP32: con una clave pública extendida (xpub/ypub/zpub) y UNA sola clave privada hija de
 * derivación NO endurecida, se recupera la clave privada madre — y con ella toda la cartera.
 *
 * El porqué está en el propio BIP32: la hija no endurecida es  k_hija = (k_madre + IL) mod n, donde
 * IL = HMAC-SHA512(chain code, pubkey_madre || índice)[:32]. El xpub ya trae el chain code y la pubkey
 * de la madre, así que IL se calcula sin secretos y  k_madre = (k_hija − IL) mod n. (Las rutas
 * endurecidas, índice ≥ 2³¹, no sufren esto: meten la clave privada en el HMAC.)
 *
 * Es una AUTOAUDITORÍA: demuestra con TUS datos por qué nunca hay que compartir un xpub junto a una
 * clave hija. No ataca a nadie. El motor es Kotlin puro para poder probarlo en la JVM; la pública de un
 * candidato se comprueba con una lambda [pubDe] (nativa en la app, lenta en las pruebas).
 */
object Bip32Fuga {
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    /** (chain code 32B, pubkey madre 33B) de un xpub/ypub/zpub, o null si no es válido. */
    fun parse(xpub: String): Pair<ByteArray, ByteArray>? {
        val raw = try { Descriptor.b58check(xpub.trim()) } catch (e: Throwable) { null } ?: return null
        if (raw.size != 78) return null
        val chain = raw.copyOfRange(13, 45)
        val pub = raw.copyOfRange(45, 78)
        if (pub[0].toInt() and 0xFF !in 0x02..0x03) return null   // tiene que ser una pública, no un xprv
        return chain to pub
    }

    /** IL = HMAC-SHA512(chain, pubkey || ser32(índice))[:32] mod n. */
    fun tweak(chain: ByteArray, pub33: ByteArray, indice: Long): BigInteger {
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        mac.init(javax.crypto.spec.SecretKeySpec(chain, "HmacSHA512"))
        val i = indice and 0xFFFFFFFFL
        val ser = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())
        val il = mac.doFinal(pub33 + ser).copyOfRange(0, 32)
        return BigInteger(1, il).mod(N)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /**
     * Busca el índice no endurecido (0..[maxIndice]) que liga la hija con la madre y devuelve (índice, k_madre),
     * o null si ninguno encaja. [pubDe] da la pública comprimida (hex, 66) de una clave privada (hex, 64).
     */
    fun recupera(xpub: String, hija: BigInteger, maxIndice: Int = 5000,
                 cancel: () -> Boolean = { false }, pubDe: (String) -> String?): Pair<Long, BigInteger>? {
        val (chain, pub) = parse(xpub) ?: return null
        val objetivo = hex(pub).lowercase()
        if (hija.signum() == 0 || hija >= N) return null
        for (idx in 0..maxIndice) {
            if (cancel()) return null
            if (idx.toLong() >= 0x80000000L) break   // sólo no endurecidas
            val madre = hija.subtract(tweak(chain, pub, idx.toLong())).mod(N)
            if (madre.signum() == 0) continue
            val p = try { pubDe(madre.toString(16).padStart(64, '0')) } catch (e: Throwable) { null }
            if (p != null && p.lowercase() == objetivo) return idx.toLong() to madre
        }
        return null
    }
}
