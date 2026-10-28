package com.hunter.btc

/**
 * Busca, en TUS PROPIAS transacciones, dos firmas de la MISMA clave pública con el mismo nonce (el valor r
 * de ECDSA repetido). Cuando pasa, cualquiera puede sacar la clave privada de lo que hay publicado en la
 * cadena: es el fallo clásico que dejó sin fondos a quien usó ciertos monederos con un generador de números
 * aleatorios malo. Si aparece, la app avisa de mover el dinero ya.
 *
 * Sólo lee las firmas (r) de scriptSig (P2PKH) y de los testigos (P2WPKH, P2SH-P2WPKH): r sale de la firma
 * DER, sin necesitar importes ni sighash. Puro: se prueba en la JVM (AuditoriaNonceTest).
 */
object AuditoriaNonce {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** (clave pública hex, r hex, firma hex) de cada firma hallada en [rawHex]; vacío si no se puede leer. */
    fun firmasDe(rawHex: String): List<Triple<String, String, String>> = try {
        val b = ByteArray(rawHex.length / 2) { rawHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val c = TxDecoder.Cursor(b)
        c.u32()                                   // version
        val segwit = b.size > c.p + 1 && b[c.p].toInt() == 0 && b[c.p + 1].toInt() == 1
        if (segwit) c.p += 2
        val nin = c.varint().toInt(); require(nin in 0..100_000)
        val scriptsSig = ArrayList<ByteArray>()
        for (i in 0 until nin) { c.bytes(32); c.u32(); scriptsSig.add(c.bytes(c.varint().toInt())); c.u32() }
        val nout = c.varint().toInt(); require(nout in 0..100_000)
        for (j in 0 until nout) { c.u64(); c.bytes(c.varint().toInt()) }
        val testigos = ArrayList<List<ByteArray>>()
        if (segwit) for (i in 0 until nin) {
            val n = c.varint().toInt(); testigos.add((0 until n).map { c.bytes(c.varint().toInt()) })
        }
        val out = ArrayList<Triple<String, String, String>>()
        for (ss in scriptsSig) out += dePila(empujes(ss))
        for (w in testigos) out += dePila(w)
        out
    } catch (e: Exception) { emptyList() }

    /** De una pila (scriptSig deshecho, o testigo): si es <firma> <pubkey>, saca (pubkey, r, firma). */
    private fun dePila(pila: List<ByteArray>): List<Triple<String, String, String>> {
        if (pila.size != 2) return emptyList()
        val sig = pila[0]; val pub = pila[1]
        val esPub = (pub.size == 33 && (pub[0].toInt() == 2 || pub[0].toInt() == 3)) || (pub.size == 65 && pub[0].toInt() == 4)
        if (!esPub) return emptyList()
        val r = rDeDer(sig) ?: return emptyList()
        return listOf(Triple(hex(pub), r, hex(sig)))
    }

    /** Los elementos que empuja un scriptSig (sólo pushes; cualquier opcode raro lo descarta). */
    private fun empujes(script: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(); var i = 0
        while (i < script.size) {
            val op = script[i].toInt() and 0xff; i++
            val n = when {
                op in 1..75 -> op
                op == 0x4c -> { if (i >= script.size) return emptyList(); (script[i].toInt() and 0xff).also { i++ } }
                op == 0x4d -> { if (i + 1 >= script.size) return emptyList(); ((script[i].toInt() and 0xff) or ((script[i + 1].toInt() and 0xff) shl 8)).also { i += 2 } }
                else -> return emptyList()
            }
            if (i + n > script.size) return emptyList()
            out.add(script.copyOfRange(i, i + n)); i += n
        }
        return out
    }

    /** r (hex, sin ceros de relleno de signo) de una firma DER (con o sin el byte de sighash al final). */
    private fun rDeDer(sig: ByteArray): String? {
        if (sig.size < 8 || sig[0].toInt() != 0x30) return null
        if ((sig[1].toInt() and 0xff) > sig.size - 2) return null       // longitud declarada imposible
        if (sig[2].toInt() != 0x02) return null
        val rlen = sig[3].toInt() and 0xff
        if (rlen <= 0 || 4 + rlen > sig.size) return null
        var r = sig.copyOfRange(4, 4 + rlen)
        r = r.dropWhile { it.toInt() == 0 }.toByteArray()               // el 0x00 de signo de DER
        if (r.isEmpty() || r.size > 33) return null
        return hex(r)
    }

    /**
     * Las claves públicas (hex) con nonce repetido entre estas transacciones: dos firmas DISTINTAS con el
     * mismo (pubkey, r). Vacío = ninguna (bien). Las transacciones repetidas no cuentan (se miran las firmas
     * distintas, no cuántas veces aparecen).
     */
    fun clavesExpuestas(rawTxs: List<String>): List<String> {
        val porClaveYr = HashMap<String, HashMap<String, MutableSet<String>>>()   // pub → r → {firmas distintas}
        for (raw in rawTxs.toSet()) for ((pub, r, sig) in firmasDe(raw))
            porClaveYr.getOrPut(pub) { HashMap() }.getOrPut(r) { HashSet() }.add(sig)
        val exp = LinkedHashSet<String>()
        for ((pub, porR) in porClaveYr) if (porR.values.any { it.size >= 2 }) exp.add(pub)
        return exp.toList()
    }
}
