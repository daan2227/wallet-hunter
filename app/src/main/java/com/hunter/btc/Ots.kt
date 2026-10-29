package com.hunter.btc

import java.util.TreeMap

/**
 * OpenTimestamps: probar que un archivo existía en una fecha, anclándolo en Bitcoin sin gastar nada.
 *
 * El archivo no sale del teléfono: sólo su hash, y además mezclado con un nonce aleatorio, así que el
 * calendario no puede saber de qué archivo se trata. Los calendarios públicos juntan muchos hashes en un
 * árbol de Merkle y anclan la raíz en una transacción; la prueba (.ots) es el camino de operaciones
 * (añadir bytes, hash) desde el hash del archivo hasta la raíz de Merkle de un bloque.
 *
 * Formato (python-opentimestamps): cabecera mágica, versión 1, la operación de hash del archivo y su
 * digest, y el árbol: en cada nodo, atestaciones y operaciones, con 0xff delante de cada rama que no sea
 * la última. Se serializa en el mismo orden que la referencia, así que lo que se lee se escribe idéntico.
 *
 * Puro: OtsTest lo prueba con los ejemplos oficiales del cliente de OpenTimestamps.
 */
object Ots {

    val MAGIA: ByteArray = byteArrayOf(0) + "OpenTimestamps".toByteArray() + byteArrayOf(0, 0) + "Proof".toByteArray() +
        byteArrayOf(0, 0xbf.toByte(), 0x89.toByte(), 0xe2.toByte(), 0xe8.toByte(), 0x84.toByte(), 0xe8.toByte(), 0x92.toByte(), 0x94.toByte())
    private const val MAX_MSG = 4096
    private const val MAX_URI = 1000
    private val URI_OK = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._/:".toSet()

    // ── Operaciones ────────────────────────────────────────────────────────────────────────────────

    /** Una operación del árbol: [tag] y, en las binarias, su argumento. */
    class Op(val tag: Int, val arg: ByteArray? = null) : Comparable<Op> {
        fun aplicar(m: ByteArray): ByteArray {
            require(m.size <= MAX_MSG) { "mensaje demasiado largo" }
            val r = when (tag) {
                0x08 -> java.security.MessageDigest.getInstance("SHA-256").digest(m)
                0x02 -> java.security.MessageDigest.getInstance("SHA-1").digest(m)
                0x03 -> Ripemd160.digest(m)
                0xf0 -> m + arg!!
                0xf1 -> arg!! + m
                0xf2 -> m.reversedArray()
                0xf3 -> m.joinToString("") { "%02x".format(it) }.toByteArray()
                else -> throw IllegalArgumentException("operación 0x%02x no admitida".format(tag))
            }
            require(r.size <= MAX_MSG) { "resultado demasiado largo" }
            return r
        }
        val binaria get() = tag == 0xf0 || tag == 0xf1
        /** El orden de la referencia: por tag; con el mismo tag, por argumento. */
        override fun compareTo(other: Op): Int {
            if (tag != other.tag) return tag.compareTo(other.tag)
            return comparar(arg ?: ByteArray(0), other.arg ?: ByteArray(0))
        }
        override fun equals(other: Any?) = other is Op && compareTo(other) == 0
        override fun hashCode() = tag * 31 + (arg?.contentHashCode() ?: 0)
        companion object {
            val SHA256 = Op(0x08)
            fun append(b: ByteArray) = Op(0xf0, b)
            /** Los tags que se saben leer (Keccak, sólo de Ethereum, no). */
            val CONOCIDAS = setOf(0x08, 0x02, 0x03, 0xf0, 0xf1, 0xf2, 0xf3)
        }
    }

    // ── Atestaciones ───────────────────────────────────────────────────────────────────────────────

    sealed class Atestacion(val tag: ByteArray) : Comparable<Atestacion> {
        /** El mensaje en este punto es la raíz de Merkle del bloque [altura]. */
        class Bitcoin(val altura: Long) : Atestacion(TAG_BTC)
        /** Aún sin anclar: el calendario [uri] la completará cuando la incluya en un bloque. */
        class Pendiente(val uri: String) : Atestacion(TAG_PEND)
        /** Otra cadena u otro tipo: se conserva tal cual, no se comprueba. */
        class Otra(tag: ByteArray, val carga: ByteArray) : Atestacion(tag)

        fun carga(): ByteArray = when (this) {
            is Bitcoin -> varuint(altura)
            is Pendiente -> varbytes(uri.toByteArray())
            is Otra -> carga
        }
        override fun compareTo(other: Atestacion): Int {
            if (this is Bitcoin && other is Bitcoin) return altura.compareTo(other.altura)
            if (this is Pendiente && other is Pendiente) return uri.compareTo(other.uri)
            val t = comparar(tag, other.tag)
            return if (t != 0 || this !is Otra || other !is Otra) t else comparar(carga, other.carga)
        }
        override fun equals(other: Any?) = other is Atestacion && compareTo(other) == 0
        override fun hashCode() = tag.contentHashCode() * 31 + carga().contentHashCode()
    }

    val TAG_BTC: ByteArray = hexa("0588960d73d71901")
    val TAG_PEND: ByteArray = hexa("83dfe30d2ef90c8e")

    // ── El árbol ───────────────────────────────────────────────────────────────────────────────────

    /** Un nodo: el mensaje en ese punto, sus atestaciones y las ramas que salen de él. */
    class Sello(val msg: ByteArray) {
        val atestaciones = java.util.TreeSet<Atestacion>()
        val ops = TreeMap<Op, Sello>()

        /** La rama de [op], creándola si no está. */
        fun rama(op: Op): Sello = ops.getOrPut(op) { Sello(op.aplicar(msg)) }

        /** Une [otro] (del mismo mensaje) en este: lo que traiga de más un calendario. */
        fun fusionar(otro: Sello) {
            require(otro.msg.contentEquals(msg)) { "no son del mismo mensaje" }
            atestaciones.addAll(otro.atestaciones)
            for ((op, s) in otro.ops) rama(op).fusionar(s)
        }

        /** Todas las atestaciones con el mensaje al que se refieren. */
        fun todas(): List<Pair<ByteArray, Atestacion>> =
            atestaciones.map { msg to it } + ops.values.flatMap { it.todas() }

        /** Los nodos con una atestación pendiente (para actualizar), con su URI. */
        fun pendientes(): List<Pair<Sello, String>> =
            atestaciones.filterIsInstance<Atestacion.Pendiente>().map { this to it.uri } + ops.values.flatMap { it.pendientes() }
    }

    /** Un archivo .ots: la operación de hash del archivo ([hashTag]) y el árbol desde su digest. */
    class Archivo(val hashTag: Int, val sello: Sello) {
        val digest: ByteArray get() = sello.msg
    }

    // ── Leer y escribir ────────────────────────────────────────────────────────────────────────────

    /** Lee un .ots. Lanza IllegalArgumentException si no lo es o está mal. */
    fun leer(b: ByteArray): Archivo {
        val c = Lector(b)
        require(c.bytes(MAGIA.size).contentEquals(MAGIA)) { "No es un archivo .ots" }
        require(c.u8() == 1) { "Versión de .ots no admitida" }
        val tag = c.u8()
        val n = when (tag) { 0x08 -> 32; 0x02, 0x03 -> 20; else -> throw IllegalArgumentException("Hash de archivo no admitido") }
        val s = leerSello(c, c.bytes(n), 256)
        require(c.quedan() == 0) { "Sobran bytes al final" }
        return Archivo(tag, s)
    }

    /** Lee el árbol que devuelve un calendario para [msg]. */
    fun leerSello(b: ByteArray, msg: ByteArray): Sello {
        val c = Lector(b); val s = leerSello(c, msg, 256)
        require(c.quedan() == 0) { "Sobran bytes al final" }
        return s
    }

    private fun leerSello(c: Lector, msg: ByteArray, prof: Int): Sello {
        require(prof > 0) { "Árbol demasiado profundo" }
        val s = Sello(msg)
        fun etiqueta(t: Int) {
            if (t == 0x00) {
                val tag = c.bytes(8); val carga = c.varbytes(8192)
                val a = when {
                    tag.contentEquals(TAG_BTC) -> Lector(carga).let { l -> Atestacion.Bitcoin(l.varuint()).also { require(l.quedan() == 0 && it.altura <= 0xffffffffL) } }
                    tag.contentEquals(TAG_PEND) -> Lector(carga).let { l ->
                        val u = l.varbytes(MAX_URI); require(l.quedan() == 0 && u.all { it.toInt().toChar() in URI_OK }) { "URI no válida" }
                        Atestacion.Pendiente(String(u, Charsets.US_ASCII))
                    }
                    else -> Atestacion.Otra(tag, carga)
                }
                s.atestaciones.add(a)
            } else {
                require(t in Op.CONOCIDAS) { "Operación 0x%02x no admitida".format(t) }
                val op = if (t == 0xf0 || t == 0xf1) Op(t, c.varbytes(MAX_MSG).also { require(it.isNotEmpty()) }) else Op(t)
                require(op !in s.ops) { "Operación repetida" }
                s.ops[op] = leerSello(c, op.aplicar(msg), prof - 1)
            }
        }
        var t = c.u8()
        while (t == 0xff) { etiqueta(c.u8()); t = c.u8() }
        etiqueta(t)
        return s
    }

    fun escribir(a: Archivo): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(MAGIA); o.write(1); o.write(a.hashTag); o.write(a.sello.msg)
        escribirSello(o, a.sello)
        return o.toByteArray()
    }

    /** Sólo el árbol desde [s] (lo que devuelve un calendario), sin cabecera. */
    fun escribirSello(s: Sello): ByteArray = java.io.ByteArrayOutputStream().also { escribirSello(it, s) }.toByteArray()

    private fun escribirSello(o: java.io.ByteArrayOutputStream, s: Sello) {
        require(s.atestaciones.isNotEmpty() || s.ops.isNotEmpty()) { "Un nodo vacío no se puede escribir" }
        val ats = s.atestaciones.toList()
        fun at(a: Atestacion) { o.write(a.tag); o.write(varbytes(a.carga())) }
        for (a in ats.dropLast(1)) { o.write(0xff); o.write(0x00); at(a) }
        if (s.ops.isEmpty()) { o.write(0x00); at(ats.last()); return }
        if (ats.isNotEmpty()) { o.write(0xff); o.write(0x00); at(ats.last()) }
        val ops = s.ops.entries.toList()
        for ((i, e) in ops.withIndex()) {
            if (i < ops.size - 1) o.write(0xff)
            o.write(e.key.tag); e.key.arg?.let { o.write(varbytes(it)) }
            escribirSello(o, e.value)
        }
    }

    // ── Sellar y comprobar ─────────────────────────────────────────────────────────────────────────

    /**
     * El esqueleto de un sello nuevo para [digest] (SHA-256 del archivo): añade [nonce] y hace SHA-256, que
     * es lo que se manda a los calendarios. Devuelve el archivo y el nodo donde se unen sus respuestas.
     */
    fun nuevo(digest: ByteArray, nonce: ByteArray): Pair<Archivo, Sello> {
        require(digest.size == 32 && nonce.isNotEmpty())
        val raiz = Sello(digest)
        val compromiso = raiz.rama(Op.append(nonce)).rama(Op.SHA256)
        return Archivo(0x08, raiz) to compromiso
    }

    /** El hash del archivo con la operación del .ots. */
    fun hashArchivo(tag: Int, datos: ByteArray): ByteArray = Op(tag).aplicar0(datos)
    private fun Op.aplicar0(d: ByteArray): ByteArray = when (tag) {   // sin el límite de 4096: es el archivo entero
        0x08 -> java.security.MessageDigest.getInstance("SHA-256").digest(d)
        0x02 -> java.security.MessageDigest.getInstance("SHA-1").digest(d)
        0x03 -> Ripemd160.digest(d)
        else -> throw IllegalArgumentException("Hash de archivo no admitido")
    }

    /**
     * ¿Ancla [msg] la cabecera de bloque [cabecera] (80 bytes)? Sí si es su raíz de Merkle. Devuelve la hora
     * del bloque (segundos Unix) o null.
     */
    fun horaSiAncla(msg: ByteArray, cabecera: ByteArray): Long? {
        if (cabecera.size != 80 || msg.size != 32) return null
        if (!cabecera.copyOfRange(36, 68).contentEquals(msg)) return null
        return (0 until 4).fold(0L) { a, i -> a or ((cabecera[68 + i].toLong() and 0xff) shl (8 * i)) }
    }

    /**
     * ¿Tiene la cabecera la prueba de trabajo que dice su campo bits, y una dificultad al menos la mínima de
     * mainnet? Así un servidor no puede inventarse una cabecera con la raíz que le convenga: tendría que minarla.
     */
    fun trabajoValido(cabecera: ByteArray): Boolean {
        if (cabecera.size != 80) return false
        val bits = (0 until 4).fold(0L) { a, i -> a or ((cabecera[72 + i].toLong() and 0xff) shl (8 * i)) }
        val exp = (bits ushr 24).toInt(); val mant = bits and 0x7fffffL
        if (mant == 0L || (bits and 0x800000L) != 0L || exp < 3 || exp > 32) return false
        val objetivo = java.math.BigInteger.valueOf(mant).shiftLeft(8 * (exp - 3))
        val limite = java.math.BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000", 16)
        if (objetivo > limite) return false
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val h = java.math.BigInteger(1, sha.digest(sha.digest(cabecera)).reversedArray())
        return h <= objetivo
    }

    /** El hash (como se muestra) de una cabecera de 80 bytes. */
    fun hashCabecera(cabecera: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        return sha.digest(sha.digest(cabecera)).reversedArray().joinToString("") { "%02x".format(it) }
    }

    /** Calendarios públicos a los que se manda, y los únicos a los que se pide actualizar (no cualquier URI de un .ots ajeno). */
    val CALENDARIOS = listOf(
        "https://a.pool.opentimestamps.org", "https://b.pool.opentimestamps.org",
        "https://a.pool.eternitywall.com", "https://ots.btc.catallaxy.com")
    fun calendarioConocido(uri: String): Boolean {
        val h = uri.removePrefix("https://").substringBefore('/')
        return uri.startsWith("https://") && (h.endsWith(".opentimestamps.org") || h.endsWith(".eternitywall.com") || h == "ots.btc.catallaxy.com")
    }

    // ── Piezas ─────────────────────────────────────────────────────────────────────────────────────

    private class Lector(val b: ByteArray) {
        var p = 0
        fun quedan() = b.size - p
        fun u8(): Int { require(p < b.size) { "El archivo se acaba antes de tiempo" }; return b[p++].toInt() and 0xff }
        fun bytes(n: Int): ByteArray { require(n >= 0 && p + n <= b.size) { "El archivo se acaba antes de tiempo" }; return b.copyOfRange(p, p + n).also { p += n } }
        fun varuint(): Long {
            var v = 0L; var sh = 0
            repeat(9) { val x = u8(); v = v or ((x and 0x7f).toLong() shl sh); if (x and 0x80 == 0) return v; sh += 7 }
            throw IllegalArgumentException("Número demasiado largo")
        }
        fun varbytes(max: Int): ByteArray { val n = varuint(); require(n <= max) { "Dato demasiado largo" }; return bytes(n.toInt()) }
    }

    private fun varuint(v0: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream(); var v = v0
        while (true) { val x = (v and 0x7f).toInt(); v = v ushr 7; if (v == 0L) { o.write(x); break }; o.write(x or 0x80) }
        return o.toByteArray()
    }
    private fun varbytes(b: ByteArray) = varuint(b.size.toLong()) + b
    private fun comparar(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
        return a.size - b.size
    }
    private fun hexa(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
