package com.hunter.btc

/**
 * Mensajes DNS en binario (RFC 1035): construir una consulta y leer una respuesta, con la compresión de
 * nombres resuelta. Los nombres se guardan en forma canónica (minúsculas, con punto final) y el RDATA de
 * los tipos que llevan nombres (CNAME, DNAME, RRSIG) se reescribe sin comprimir, como lo pide DNSSEC.
 * Puro: lo prueba Bip353Test con respuestas generadas por dnspython.
 */
object Dns {
    const val TXT = 16; const val CNAME = 5; const val DNAME = 39; const val DS = 43; const val RRSIG = 46; const val DNSKEY = 48

    /** Un registro: dueño canónico ("a.b."), tipo, clase, TTL y RDATA (canónico si lleva nombres). */
    class Rr(val nombre: String, val tipo: Int, val clase: Int, val ttl: Long, val rdata: ByteArray)

    class Mensaje(val rcode: Int, val respuestas: List<Rr>)

    /** La consulta de [nombre] y [tipo]: RD (recursiva), CD (la validación la hacemos aquí) y EDNS con DO. */
    fun consulta(nombre: String, tipo: Int): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0, 0x01, 0x10, 0, 1, 0, 0, 0, 0, 0, 1))      // id 0, RD+CD; 1 pregunta, 1 adicional
        o.write(aWire(nombre)); o.write(byteArrayOf((tipo shr 8).toByte(), tipo.toByte(), 0, 1))
        o.write(byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0x80.toByte(), 0, 0, 0))   // OPT: 4096 bytes, DO
        return o.toByteArray()
    }

    /** Lee una respuesta; null si no es un mensaje DNS bien formado. */
    fun leer(b: ByteArray): Mensaje? = try {
        require(b.size >= 12)
        val rcode = b[3].toInt() and 0x0f
        val qd = u16(b, 4); val an = u16(b, 6)
        var p = 12
        repeat(qd) { p = nombre(b, p).second + 4 }
        val rrs = ArrayList<Rr>()
        repeat(an) {
            val (n, q) = nombre(b, p); p = q
            val tipo = u16(b, p); val clase = u16(b, p + 2)
            val ttl = ((b[p + 4].toLong() and 0xff) shl 24) or ((b[p + 5].toLong() and 0xff) shl 16) or ((b[p + 6].toLong() and 0xff) shl 8) or (b[p + 7].toLong() and 0xff)
            val len = u16(b, p + 8); p += 10
            require(p + len <= b.size)
            val rd = when (tipo) {
                CNAME, DNAME -> aWire(nombre(b, p).first)
                RRSIG -> { val (signer, q2) = nombre(b, p + 18); b.copyOfRange(p, p + 18) + aWire(signer) + b.copyOfRange(q2, p + len) }
                else -> b.copyOfRange(p, p + len)
            }
            rrs.add(Rr(n, tipo, clase, ttl, rd)); p += len
        }
        Mensaje(rcode, rrs)
    } catch (e: Exception) { null }

    private fun u16(b: ByteArray, p: Int): Int { require(p + 1 < b.size); return ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff) }

    /** Un nombre en [p] (con punteros de compresión): el nombre canónico y dónde sigue el mensaje. */
    fun nombre(b: ByteArray, p0: Int): Pair<String, Int> {
        val sb = StringBuilder(); var p = p0; var fin = -1; var saltos = 0
        while (true) {
            require(p < b.size)
            val l = b[p].toInt() and 0xff
            when {
                l == 0 -> { if (fin < 0) fin = p + 1; break }
                l and 0xc0 == 0xc0 -> {
                    require(p + 1 < b.size && ++saltos < 64)
                    if (fin < 0) fin = p + 2
                    p = ((l and 0x3f) shl 8) or (b[p + 1].toInt() and 0xff)
                }
                l and 0xc0 == 0 -> {
                    require(p + 1 + l <= b.size)
                    for (i in 1..l) { val c = b[p + i].toInt() and 0xff; sb.append(if (c in 'A'.code..'Z'.code) (c + 32).toChar() else c.toChar()) }
                    sb.append('.'); p += 1 + l
                    require(sb.length <= 255)
                }
                else -> throw IllegalArgumentException("etiqueta")
            }
        }
        return (if (sb.isEmpty()) "." else sb.toString()) to fin
    }

    /** Un nombre canónico a binario (minúsculas, sin comprimir). */
    fun aWire(n: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for (et in canonico(n).trimEnd('.').split('.').filter { it.isNotEmpty() }) {
            val b = et.toByteArray(Charsets.ISO_8859_1); require(b.size in 1..63); o.write(b.size); o.write(b)
        }
        o.write(0); return o.toByteArray()
    }

    fun canonico(n: String): String { val t = n.trim().lowercase(); return if (t.endsWith(".")) t else "$t." }
    fun etiquetas(n: String): Int = canonico(n).trimEnd('.').split('.').count { it.isNotEmpty() }
    /** ¿Es [a] igual a [b] o está por debajo de él? */
    fun dentroDe(a: String, b: String): Boolean { val x = canonico(a); val y = canonico(b); return y == "." || x == y || x.endsWith(".$y") }
    fun padre(n: String): String = canonico(n).let { if (it == ".") "." else it.substringAfter('.').ifEmpty { "." } }

    /** Las cadenas (<character-string>) de un RDATA de TXT, en orden. */
    fun cadenasTxt(rd: ByteArray): List<ByteArray>? {
        val out = ArrayList<ByteArray>(); var p = 0
        while (p < rd.size) { val l = rd[p].toInt() and 0xff; if (p + 1 + l > rd.size) return null; out.add(rd.copyOfRange(p + 1, p + 1 + l)); p += 1 + l }
        return out
    }
}
