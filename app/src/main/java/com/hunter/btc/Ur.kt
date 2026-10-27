package com.hunter.btc

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Uniform Resources (BCR-2020-005/006): el formato "ur:crypto-psbt/…" de los QR animados que usan
 * Keystone, SeedSigner, Passport, Jade y Sparrow para pasarse una PSBT sin cables.
 *
 * Una PSBT grande no cabe en un QR: se parte en fragmentos ("fountain codes") y se muestran en bucle.
 * Los primeros N son los trozos tal cual; los siguientes son mezclas XOR elegidas con un generador
 * pseudoaleatorio común (Xoshiro256**), así quien lee puede reconstruir el mensaje aunque se le
 * escapen fotogramas. Todo tiene que coincidir bit a bit con las demás implementaciones: se comprobó
 * contra la de referencia en JavaScript (@ngraveio/bc-ur), ver UrTest.
 */
object Ur {
    private const val PALABRAS = "ableacidalsoapexaquaarchatomauntawayaxisbackbaldbarnbeltbetabiasbluebodybragbrewbulbbuzzcalmcashcatschefcityclawcodecolacookcostcruxcurlcuspcyandarkdatadaysdelidicedietdoordowndrawdropdrumdulldutyeacheasyechoedgeepicevenexamexiteyesfactfairfernfigsfilmfishfizzflapflewfluxfoxyfreefrogfuelfundgalagamegeargemsgiftgirlglowgoodgraygrimgurugushgyrohalfhanghardhawkheathelphighhillholyhopehornhutsicedideaidleinchinkyintoirisironitemjadejazzjoinjoltjowljudojugsjumpjunkjurykeepkenokeptkeyskickkilnkingkitekiwiknoblamblavalazyleaflegsliarlimplionlistlogoloudloveluaulucklungmainmanymathmazememomenumeowmildmintmissmonknailnavyneednewsnextnoonnotenumbobeyoboeomitonyxopenovalowlspaidpartpeckplaypluspoempoolposepuffpumapurrquadquizraceramprealredorichroadrockroofrubyruinrunsrustsafesagascarsetssilkskewslotsoapsolosongstubsurfswantacotasktaxitenttiedtimetinytoiltombtoystriptunatwinuglyundouniturgeuservastveryvetovialvibeviewvisavoidvowswallwandwarmwaspwavewaxywebswhatwhenwhizwolfworkyankyawnyellyogayurtzapszerozestzinczonezoom"

    // ── Bytewords (estilo mínimo: primera y última letra de cada palabra) ─────────────────────────

    private fun palabra(i: Int) = PALABRAS.substring(i * 4, i * 4 + 4)
    private val tabla: IntArray by lazy {
        IntArray(26 * 26) { -1 }.also { t -> for (i in 0 until 256) { val w = palabra(i); t[(w[3] - 'a') * 26 + (w[0] - 'a')] = i } }
    }

    private fun crc32(b: ByteArray): Long = CRC32().apply { update(b) }.value

    fun bytewordsMin(datos: ByteArray): String {
        val crc = crc32(datos)
        val todo = datos + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        val sb = StringBuilder(todo.size * 2)
        for (b in todo) { val w = palabra(b.toInt() and 0xff); sb.append(w[0]).append(w[3]) }
        return sb.toString()
    }

    /** null si no son bytewords mínimos válidos o el CRC no cuadra. */
    fun deBytewordsMin(s0: String): ByteArray? {
        val s = s0.lowercase()
        if (s.length % 2 != 0 || s.length < 10) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val x = s[2 * i] - 'a'; val y = s[2 * i + 1] - 'a'
            if (x !in 0..25 || y !in 0..25) return null
            val v = tabla[y * 26 + x]; if (v < 0) return null
            out[i] = v.toByte()
        }
        val cuerpo = out.copyOfRange(0, out.size - 4)
        val c = out.copyOfRange(out.size - 4, out.size)
        val leido = ((c[0].toLong() and 0xff) shl 24) or ((c[1].toLong() and 0xff) shl 16) or
                    ((c[2].toLong() and 0xff) shl 8) or (c[3].toLong() and 0xff)
        return if (leido == crc32(cuerpo)) cuerpo else null
    }

    // ── CBOR mínimo: enteros sin signo, cadenas de bytes y arrays ───────────────────────────────

    private fun cabecera(mayor: Int, n: Long, o: java.io.ByteArrayOutputStream) {
        val m = mayor shl 5
        when {
            n < 24 -> o.write(m or n.toInt())
            n < 0x100 -> { o.write(m or 24); o.write(n.toInt()) }
            n < 0x10000 -> { o.write(m or 25); o.write((n shr 8).toInt()); o.write(n.toInt() and 0xff) }
            n < 0x100000000L -> { o.write(m or 26); for (s in intArrayOf(24, 16, 8, 0)) o.write(((n shr s) and 0xff).toInt()) }
            else -> { o.write(m or 27); for (s in 56 downTo 0 step 8) o.write(((n shr s) and 0xff).toInt()) }
        }
    }

    /** CBOR de una cadena de bytes: es todo lo que lleva un ur:crypto-psbt / ur:psbt. */
    fun cborBytes(b: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { cabecera(2, b.size.toLong(), it); it.write(b) }.toByteArray()

    private class Lector(val b: ByteArray) {
        var p = 0
        fun cab(): Pair<Int, Long> {
            val c = b[p++].toInt() and 0xff
            val mayor = c shr 5; val inf = c and 0x1f
            fun leer(k: Int): Long { var v = 0L; repeat(k) { v = (v shl 8) or (b[p++].toLong() and 0xff) }; return v }
            val n: Long = when (inf) {
                in 0..23 -> inf.toLong()
                24 -> leer(1); 25 -> leer(2); 26 -> leer(4); 27 -> leer(8)
                else -> throw IllegalArgumentException("CBOR")
            }
            return mayor to n
        }
        fun uint(): Long { val (m, n) = cab(); require(m == 0 && n >= 0); return n }
        fun bytes(): ByteArray {
            val (m, n) = cab(); require(m == 2 && n in 0..(b.size - p).toLong())
            val r = b.copyOfRange(p, p + n.toInt()); p += n.toInt(); return r
        }
    }

    /** Los bytes de dentro de un CBOR que es una cadena de bytes (con o sin etiqueta delante); null si no. */
    fun deCborBytes(c: ByteArray): ByteArray? = try {
        val l = Lector(c)
        val (m, _) = l.cab()
        if (m != 6) l.p = 0          // una etiqueta (algunos ponen la 310 de crypto-psbt) se salta
        val r = l.bytes(); if (l.p == c.size) r else null
    } catch (e: Exception) { null }

    // ── Generador común y elección de fragmentos ─────────────────────────────────────────────────

    class Xoshiro(semilla: ByteArray) {
        private val s = LongArray(4)
        init {
            val d = MessageDigest.getInstance("SHA-256").digest(semilla)
            for (i in 0 until 4) { var v = 0L; for (n in 0 until 8) v = (v shl 8) or (d[i * 8 + n].toLong() and 0xff); s[i] = v }
        }
        private fun rotl(x: Long, k: Int) = (x shl k) or (x ushr (64 - k))
        fun next(): Long {
            val r = rotl(s[1] * 5, 7) * 9
            val t = s[1] shl 17
            s[2] = s[2] xor s[0]; s[3] = s[3] xor s[1]; s[1] = s[1] xor s[2]; s[0] = s[0] xor s[3]
            s[2] = s[2] xor t; s[3] = rotl(s[3], 45)
            return r
        }
        fun nextDouble(): Double = next().toULong().toDouble() / 18446744073709551616.0
        fun nextInt(lo: Int, hi: Int): Int = Math.floor(nextDouble() * (hi - lo + 1) + lo).toInt()
    }

    /** Grado de la mezcla: método del alias con probabilidades 1/k, como la implementación de referencia. */
    private fun grado(n: Int, rng: Xoshiro): Int {
        val p = DoubleArray(n) { 1.0 / (it + 1) }
        var suma = 0.0; for (v in p) suma += v
        val esc = DoubleArray(n) { (p[it] * n) / suma }
        val prob = DoubleArray(n); val alias = IntArray(n)
        val peq = ArrayList<Int>(); val gra = ArrayList<Int>()
        for (i in n - 1 downTo 0) if (esc[i] < 1) peq.add(i) else gra.add(i)
        while (peq.isNotEmpty() && gra.isNotEmpty()) {
            val menos = peq.removeAt(peq.size - 1); val mas = gra.removeAt(gra.size - 1)
            prob[menos] = esc[menos]; alias[menos] = mas
            esc[mas] = (esc[mas] + esc[menos]) - 1
            if (esc[mas] < 1) peq.add(mas) else gra.add(mas)
        }
        while (gra.isNotEmpty()) prob[gra.removeAt(gra.size - 1)] = 1.0
        while (peq.isNotEmpty()) prob[peq.removeAt(peq.size - 1)] = 1.0
        val c = Math.floor(rng.nextDouble() * n).toInt()
        return (if (rng.nextDouble() < prob[c]) c else alias[c]) + 1
    }

    /** Qué fragmentos (índices desde 0) van mezclados en la parte [seq] (desde 1). */
    fun elegirFragmentos(seq: Long, n: Int, checksum: Long): List<Int> {
        if (seq <= n) return listOf((seq - 1).toInt())
        val semilla = ByteArray(8)
        for (i in 0 until 4) { semilla[i] = (seq ushr (24 - 8 * i)).toByte(); semilla[4 + i] = (checksum ushr (24 - 8 * i)).toByte() }
        val rng = Xoshiro(semilla)
        val g = grado(n, rng)
        val quedan = (0 until n).toMutableList(); val res = ArrayList<Int>()
        while (quedan.isNotEmpty()) { val i = rng.nextInt(0, quedan.size - 1); res.add(quedan.removeAt(i)) }
        return res.subList(0, g)
    }

    // ── Codificador ──────────────────────────────────────────────────────────────────────────────

    /** Partes "ur:<tipo>/…" para mostrar en bucle; [siguiente] da la próxima (las primeras N son los trozos puros). */
    class Codificador(val tipo: String, private val mensaje: ByteArray, maxFragmento: Int = 200, minFragmento: Int = 10) {
        private val largo = mensaje.size
        private val fragLargo: Int
        private val frags: List<ByteArray>
        private val checksum = crc32(mensaje)
        private var seq = 0L
        init {
            require(mensaje.isNotEmpty())
            val maxCuenta = (largo + minFragmento - 1) / minFragmento
            var fl = 0
            for (cuenta in 1..maxCuenta) { fl = (largo + cuenta - 1) / cuenta; if (fl <= maxFragmento) break }
            fragLargo = fl
            frags = (0 until (largo + fl - 1) / fl).map { i ->
                ByteArray(fl).also { val d = i * fl; System.arraycopy(mensaje, d, it, 0, minOf(fl, largo - d)) }
            }
        }
        val partes: Int get() = frags.size
        fun unaSola() = frags.size == 1

        fun siguiente(): String {
            seq++
            if (unaSola()) return "ur:$tipo/" + bytewordsMin(mensaje)
            val idx = elegirFragmentos(seq, frags.size, checksum)
            val mezcla = ByteArray(fragLargo)
            for (i in idx) for (k in 0 until fragLargo) mezcla[k] = (mezcla[k].toInt() xor frags[i][k].toInt()).toByte()
            val o = java.io.ByteArrayOutputStream()
            o.write(0x85)
            cabecera(0, seq, o); cabecera(0, frags.size.toLong(), o); cabecera(0, largo.toLong(), o); cabecera(0, checksum, o)
            cabecera(2, mezcla.size.toLong(), o); o.write(mezcla)
            return "ur:$tipo/$seq-${frags.size}/" + bytewordsMin(o.toByteArray())
        }
    }

    // ── Decodificador ────────────────────────────────────────────────────────────────────────────

    private class Parte(val idx: Set<Int>, val datos: ByteArray)

    /** Va recibiendo lo que lee la cámara. [listo] cuando se reconstruyó el mensaje (CBOR) y cuadra el CRC. */
    class Decodificador {
        var tipo: String? = null; private set
        var resultado: ByteArray? = null; private set
        var error: String? = null; private set
        private var n = 0; private var largo = 0; private var checksum = -1L; private var fragLargo = 0
        private val simples = HashMap<Int, ByteArray>()
        private val mixtas = ArrayList<Parte>()
        private val cola = ArrayDeque<Parte>()

        val listo get() = resultado != null
        val total get() = n
        val recibidos get() = simples.size

        /** true si la parte se aceptó (aunque todavía no complete nada). */
        fun recibir(texto0: String): Boolean {
            if (listo) return false
            val texto = texto0.trim().lowercase()
            if (!texto.startsWith("ur:")) return false
            val trozos = texto.removePrefix("ur:").split('/')
            val t = trozos[0]
            if (t.isEmpty() || !t.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
            if (tipo != null && tipo != t) return false
            if (trozos.size == 2) {
                val cbor = deBytewordsMin(trozos[1]) ?: return false
                tipo = t; resultado = cbor; return true
            }
            if (trozos.size != 3) return false
            val sl = trozos[1].split('-'); if (sl.size != 2) return false
            val seq = sl[0].toLongOrNull() ?: return false
            val len = sl[1].toLongOrNull() ?: return false
            val cbor = deBytewordsMin(trozos[2]) ?: return false
            val l = Lector(cbor)
            val pLen: Long; val pLargo: Long; val pCrc: Long; val frag: ByteArray
            try {
                val (m, cnt) = l.cab(); require(m == 4 && cnt == 5L)
                val pSeq = l.uint(); pLen = l.uint(); pLargo = l.uint(); pCrc = l.uint(); frag = l.bytes()
                require(pSeq == seq && pLen == len && frag.isNotEmpty() && pLen in 1..10_000 && pLargo in 1..50_000_000)
            } catch (e: Exception) { return false }
            if (n == 0) { n = pLen.toInt(); largo = pLargo.toInt(); checksum = pCrc; fragLargo = frag.size; tipo = t }
            else if (n.toLong() != pLen || largo.toLong() != pLargo || checksum != pCrc || fragLargo != frag.size) return false
            cola.addLast(Parte(elegirFragmentos(seq, n, checksum).toSet(), frag))
            while (!listo && cola.isNotEmpty()) procesar(cola.removeFirst())
            return true
        }

        private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

        private fun reducir(a: Parte, b: Parte) =
            if (a.idx.containsAll(b.idx)) Parte(a.idx - b.idx, xor(a.datos, b.datos)) else a

        private fun procesar(p: Parte) {
            if (p.idx.size == 1) {
                val i = p.idx.first()
                if (simples.containsKey(i)) return
                simples[i] = p.datos
                if (simples.size == n) {
                    val todo = ByteArray(n * fragLargo)
                    for (k in 0 until n) System.arraycopy(simples[k]!!, 0, todo, k * fragLargo, fragLargo)
                    val msg = todo.copyOf(largo)
                    if (crc32(msg) == checksum) resultado = msg else error = "checksum"
                } else reducirMixtasCon(p)
            } else {
                if (mixtas.any { it.idx == p.idx }) return
                var q = p
                for ((k, v) in simples) q = reducir(q, Parte(setOf(k), v))
                for (m in mixtas) q = reducir(q, m)
                if (q.idx.size == 1) cola.addLast(q)
                else if (q.idx.isNotEmpty()) { reducirMixtasCon(q); mixtas.add(q) }
            }
        }

        private fun reducirMixtasCon(p: Parte) {
            val nuevas = ArrayList<Parte>()
            for (m in mixtas) { val r = reducir(m, p); if (r.idx.size == 1) cola.addLast(r) else nuevas.add(r) }
            mixtas.clear(); mixtas.addAll(nuevas)
        }
    }
}
