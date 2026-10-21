package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lo que no toca Android de la pestaña Core: el bitcoin.conf, la línea de órdenes, la lectura de lo que
 * contesta `bitcoind` y la cabecera de un snapshot de UTXO. Sin contexto ni red, para probarlo en la JVM.
 */
object CoreConfig {

    /** Mínimo que acepta Core para podar (MiB). */
    const val PRUNE_MIN = 550
    const val RPC_PORT = 8332
    /** Bloques que Core se niega a podar por debajo de la punta (MIN_BLOCKS_TO_KEEP). */
    const val BLOQUES_QUE_QUEDAN = 288

    val PRUNE_OPCIONES = listOf(550, 2000, 5000, 10000)
    val DBCACHE_OPCIONES = listOf(100, 450, 1000)

    data class Ajustes(
        val pruneMiB: Int = PRUNE_MIN,
        val dbcacheMiB: Int = 450,
        /** Sin retransmitir transacciones sueltas: baja mucho el tráfico. Cuesta la estimación de comisión. */
        val soloBloques: Boolean = true,
        /** Parar el nodo si la conexión pasa a ser de datos móviles. */
        val soloWifi: Boolean = true
    ) {
        fun normalizado() = copy(pruneMiB = pruneMiB.coerceAtLeast(PRUNE_MIN),
                                 dbcacheMiB = dbcacheMiB.coerceIn(4, 4096))
    }

    /** El contenido de bitcoin.conf. [cookie] es la ruta ABSOLUTA de la cookie de RPC (en el almacén privado). */
    fun conf(a0: Ajustes, cookie: String): String {
        val a = a0.normalizado()
        require('\n' !in cookie && '\r' !in cookie) { "ruta con saltos de línea" }
        return buildString {
            appendLine("# Generado por Wallet Hunter. Se reescribe en cada arranque.")
            appendLine("server=1")
            appendLine("prune=${a.pruneMiB}")
            appendLine("dbcache=${a.dbcacheMiB}")
            // Sólo se escucha en el propio teléfono, y con cookie (no hay contraseña fija).
            appendLine("rpcbind=127.0.0.1")
            appendLine("rpcallowip=127.0.0.1")
            appendLine("rpcport=$RPC_PORT")
            appendLine("rpccookiefile=$cookie")
            // Un teléfono no abre puertos hacia fuera: sin conexiones entrantes ahorra datos y batería.
            appendLine("listen=0")
            appendLine("maxconnections=12")
            appendLine("blocksonly=${if (a.soloBloques) 1 else 0}")
            appendLine("txindex=0")
            appendLine("shrinkdebugfile=1")
        }
    }

    /** Argumentos de `bitcoind`. -conf lleva todo lo demás. */
    fun argumentos(datadir: String, conf: String): List<String> =
        listOf("-datadir=$datadir", "-conf=$conf", "-printtoconsole=0")

    /** Altura hasta la que se puede podar con [tip] bloques: nunca por encima de tip-288. -1 si todavía no se puede. */
    fun alturaPoda(tip: Int): Int = if (tip <= BLOQUES_QUE_QUEDAN) -1 else tip - BLOQUES_QUE_QUEDAN

    // ── Lo que dice el nodo ──────────────────────────────────────────────────

    data class Estado(
        val cadena: String,
        val bloques: Long,
        val cabeceras: Long,
        val progreso: Double,
        val descargaInicial: Boolean,
        val podado: Boolean,
        val alturaPoda: Long,
        val bytesEnDisco: Long,
        val pares: Int,
        val version: String,
        /** Hay una cadena de snapshot y el nodo aún comprueba la historia por detrás. */
        val validandoAtras: Boolean,
        val progresoAtras: Double
    )

    /**
     * Junta las respuestas de getblockchaininfo, getnetworkinfo y getchainstates (las dos últimas pueden faltar).
     * [estados] es el objeto `{"headers":…, "chainstates":[…]}` de getchainstates.
     */
    fun leerEstado(cadena: JSONObject, red: JSONObject?, estados: JSONObject?): Estado {
        var atras = false; var progAtras = 0.0
        val cs: JSONArray? = estados?.optJSONArray("chainstates")
        if (estados != null && cs != null && cs.length() > 1) {
            // Con snapshot hay dos: la del snapshot (validada = false mientras se comprueba) y la de atrás.
            for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i)
                if (c.has("snapshot_blockhash").not()) {   // la de atrás, que viene de génesis
                    atras = true
                    val tope = estados.optLong("headers", 0L).coerceAtLeast(1L)
                    progAtras = (c.optLong("blocks", 0L).toDouble() / tope).coerceIn(0.0, 1.0)
                }
            }
        }
        return Estado(
            cadena = cadena.optString("chain", "?"),
            bloques = cadena.optLong("blocks", 0L),
            cabeceras = cadena.optLong("headers", 0L),
            progreso = cadena.optDouble("verificationprogress", 0.0).coerceIn(0.0, 1.0),
            descargaInicial = cadena.optBoolean("initialblockdownload", true),
            podado = cadena.optBoolean("pruned", false),
            alturaPoda = cadena.optLong("pruneheight", 0L),
            bytesEnDisco = cadena.optLong("size_on_disk", 0L),
            pares = red?.optInt("connections", 0) ?: 0,
            version = red?.optString("subversion", "") ?: "",
            validandoAtras = atras, progresoAtras = progAtras
        )
    }

    // ── Progreso de loadtxoutset, leído del debug.log ────────────────────────
    //
    // loadtxoutset es una sola llamada RPC que no devuelve nada hasta el final y, mientras corre, tiene
    // cs_main tomado: los demás RPC no contestan. Lo único que dice cómo va son las líneas del registro:
    //   2026-10-21T12:55:01Z [snapshot] loading 185000000 coins from snapshot 0000…
    //   2026-10-21T12:59:40Z [snapshot] 3000000 coins loaded (1.62%, 612.40 MB)      (cada millón)
    //   … [snapshot] loaded 185000000 (…) coins from snapshot   /   Aborting after an interrupt  / etc.

    data class CargaSnapshot(
        val total: Long, val cargados: Long, val enCurso: Boolean,
        /** Segundos desde que empezó (hasta [ahora]); -1 si no se sabe. */
        val transcurridoS: Long,
        /** Segundos que faltan al ritmo medio hasta ahora; null si aún no hay datos para estimarlo. */
        val etaS: Long?
    ) { val porcentaje: Double get() = if (total > 0) cargados * 100.0 / total else 0.0 }

    private val RE_INICIO = Regex("""^(\S+)\s+\[snapshot] loading (\d+) coins from snapshot""")
    private val RE_PASO = Regex("""^(\S+)\s+\[snapshot] (\d+) coins loaded""")
    private val FIN_CARGA = listOf("[snapshot] loaded ", "successfully activated", "Aborting after an interrupt",
        "Bad snapshot data", "Mismatch in coins count", "[snapshot] !!!", "Work does not exceed")

    private fun instante(s: String): Long? =
        try { java.time.Instant.parse(s).epochSecond } catch (e: Exception) { null }

    /**
     * La última carga que consta en [lineas] (en orden, las últimas del registro), o null si no hay ninguna.
     * [nodoVivo]: si el nodo ya no corre, una carga a medias no está "en curso" (se cortó).
     * [ahora]: segundos desde la época, para calcular el tiempo transcurrido.
     */
    fun leerCargaSnapshot(lineas: List<String>, nodoVivo: Boolean, ahora: Long): CargaSnapshot? {
        var ini = -1
        for (i in lineas.indices.reversed()) if (RE_INICIO.containsMatchIn(lineas[i])) { ini = i; break }
        if (ini < 0) return null
        val m0 = RE_INICIO.find(lineas[ini]) ?: return null
        val total = m0.groupValues[2].toLongOrNull() ?: return null
        val t0 = instante(m0.groupValues[1])
        var cargados = 0L; var t1 = t0; var fin = false
        for (i in ini + 1 until lineas.size) {
            val l = lineas[i]
            val paso = RE_PASO.find(l)
            if (paso != null) {
                cargados = paso.groupValues[2].toLongOrNull() ?: cargados
                t1 = instante(paso.groupValues[1]) ?: t1
            }
            if (FIN_CARGA.any { f -> f in l }) fin = true
        }
        val enCurso = !fin && nodoVivo
        val fin0 = t0; val fin1 = t1
        val eta = if (enCurso && cargados > 0 && fin0 != null && fin1 != null && fin1 > fin0)
            ((total - cargados).toDouble() * (fin1 - fin0) / cargados).toLong().coerceAtLeast(0) else null
        return CargaSnapshot(total, cargados, enCurso, if (fin0 != null) (ahora - fin0).coerceAtLeast(0) else -1, eta)
    }

    // ── Lo que dice el nodo de scantxoutset y estimatesmartfee ───────────────

    data class Moneda(val txid: String, val vout: Int, val sat: Long, val altura: Int)
    data class Escaneo(val sat: Long, val monedas: List<Moneda>, val altura: Int)

    fun satDeBtc(btc: Double): Long = Math.round(btc * 1e8)

    /** La respuesta de `scantxoutset start`: suma y monedas sin gastar (sólo lo confirmado: es el conjunto de UTXO). */
    fun leerEscaneo(o: JSONObject): Escaneo? {
        if (!o.optBoolean("success", false)) return null
        val arr = o.optJSONArray("unspents") ?: JSONArray()
        val l = ArrayList<Moneda>(arr.length())
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            val v = satDeBtc(u.getDouble("amount"))
            if (v <= 0) continue
            l.add(Moneda(u.getString("txid"), u.getInt("vout"), v, u.optInt("height", 0)))
        }
        return Escaneo(l.sumOf { it.sat }, l, o.optInt("height", 0))
    }

    /** sat/vB de una respuesta de estimatesmartfee (BTC/kvB), o null si el nodo no tiene datos. */
    fun satPorVb(estimacion: JSONObject): Double? {
        if (!estimacion.has("feerate")) return null
        val f = estimacion.optDouble("feerate", Double.NaN) * 1e5
        return f.takeIf { it.isFinite() && it > 0 }
    }

    fun duracion(s: Long): String = when {
        s < 60 -> "< 1 min"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }

    fun bytes(n: Long): String = when {
        n < 1024L -> "$n B"
        n < 1024L * 1024 -> "%.1f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }

    // ── Cabecera de un snapshot (dumptxoutset) ───────────────────────────────

    /** Lo que se lee de los primeros 51 bytes. [base] está en el orden en que se enseñan los hashes de bloque. */
    data class CabeceraSnapshot(val version: Int, val red: String, val base: String, val monedas: Long)

    private val MAGIC = byteArrayOf('u'.code.toByte(), 't'.code.toByte(), 'x'.code.toByte(),
                                    'o'.code.toByte(), 0xff.toByte())

    /** null si no parece un snapshot de Core. Sólo lee la cabecera: Core comprueba el hash entero al cargarlo. */
    fun leerCabeceraSnapshot(b: ByteArray): CabeceraSnapshot? {
        if (b.size < 51) return null
        for (i in MAGIC.indices) if (b[i] != MAGIC[i]) return null
        val ver = (b[5].toInt() and 0xff) or ((b[6].toInt() and 0xff) shl 8)
        val magicRed = b.copyOfRange(7, 11).joinToString("") { "%02x".format(it) }
        val red = when (magicRed) {
            "f9beb4d9" -> "main"; "0b110907" -> "testnet"; "1c163f28" -> "testnet4"
            "0a03cf40" -> "signet"; "fabfb5da" -> "regtest"; else -> "?"
        }
        val hash = b.copyOfRange(11, 43).reversedArray().joinToString("") { "%02x".format(it) }
        var n = 0L
        for (i in 7 downTo 0) n = (n shl 8) or (b[43 + i].toLong() and 0xff)
        return CabeceraSnapshot(ver, red, hash, n)
    }

    /**
     * Bloques en los que Core (v32.0rc4) acepta un snapshot de la red principal: su hash va fijado en
     * chainparams.cpp. Hash del bloque base -> altura. Con otra versión de Core la lista cambia (v29.0 sólo
     * trae 840000 y 880000; v31.1, hasta 935000), así que hay que revisarla al cambiar la etiqueta de core.yml.
     */
    val SNAPSHOTS_ACEPTADOS: Map<String, Int> = linkedMapOf(
        "0000000000000000000320283a032748cef8227873ff4872689bf23f1cda83a5" to 840_000,
        "000000000000000000010b17283c3c400507969a9c2afd1dcf2082ec5cca2880" to 880_000,
        "0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821" to 910_000,
        "0000000000000000000147034958af1652b2b91bba607beacc5e72a56f0fb5ee" to 935_000,
        "00000000000000000001595977e6000ce56129f5c9b4073e31ccc30b90b97da9" to 965_000)

    /** Altura si [base] es un bloque que este Core acepta; null si no. */
    fun alturaAceptada(base: String): Int? = SNAPSHOTS_ACEPTADOS[base.lowercase()]

    /** ¿Una URL de descarga aceptable? Sólo https, para que nadie en la ruta cambie el fichero a mitad. */
    fun urlValida(u: String): Boolean {
        val t = u.trim()
        if (!t.startsWith("https://", ignoreCase = true) || t.length < 12 || t.any { it.isWhitespace() }) return false
        return try { java.net.URI(t).host?.isNotEmpty() == true } catch (e: Exception) { false }
    }
}
