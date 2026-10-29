package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Las cuentas del explorador de bloques, a partir de las respuestas Esplora (mempool.space y
 * blockstream.info dan las mismas): bloques proyectados de la mempool, en cuál entraría una transacción,
 * halving, próximo ajuste de dificultad y resúmenes de transacción y bloque. Puro: ExploradorTest.
 */
object Explorador {

    // ── Mempool y bloques proyectados ──────────────────────────────────────────────────────────────

    data class Mempool(val txs: Long, val vsize: Long, val comisionTotal: Long, val histograma: List<Pair<Double, Long>>)

    /** /mempool: {count, vsize, total_fee, fee_histogram: [[tasa, vsize], …]} (tasas de mayor a menor). */
    fun leerMempool(json: String): Mempool? = try {
        val o = JSONObject(json)
        val h = o.optJSONArray("fee_histogram") ?: JSONArray()
        Mempool(o.optLong("count"), o.optLong("vsize"), o.optLong("total_fee"),
            (0 until h.length()).map { h.getJSONArray(it).let { p -> p.getDouble(0) to p.getLong(1) } }.sortedByDescending { it.first })
    } catch (e: Exception) { null }

    /** Un bloque proyectado: cuánto lleva y las tasas (sat/vB) de lo que entra. */
    data class Proyectado(val vsize: Long, val minima: Double, val mediana: Double, val maxima: Double)

    const val VSIZE_BLOQUE = 1_000_000L

    /**
     * Llena bloques de 1 000 000 vB con la mempool de mayor a menor tasa. Cada tramo del histograma son las
     * transacciones con tasa entre la suya y la del tramo anterior; se reparte entre bloques si no cabe.
     * Es lo que hace un minero que ordena por tasa, sin contar dependencias (CPFP) ni la coinbase.
     */
    fun proyectar(h: List<Pair<Double, Long>>, maxBloques: Int = 8): List<Proyectado> {
        val out = ArrayList<Proyectado>()
        var lleno = 0L; var maxT = -1.0; var minT = 0.0
        val tramos = ArrayList<Pair<Double, Long>>()          // los del bloque en curso, para la mediana
        fun cerrar() {
            if (lleno == 0L) return
            var acc = 0L; var med = minT
            for ((t, v) in tramos) { acc += v; if (acc * 2 >= lleno) { med = t; break } }
            out.add(Proyectado(lleno, minT, med, maxT)); lleno = 0; maxT = -1.0; tramos.clear()
        }
        for ((tasa, v0) in h) {
            var v = v0
            while (v > 0 && out.size < maxBloques) {
                val cabe = minOf(v, VSIZE_BLOQUE - lleno)
                if (maxT < 0) maxT = tasa
                minT = tasa; lleno += cabe; v -= cabe; tramos.add(tasa to cabe)
                if (lleno >= VSIZE_BLOQUE) cerrar()
            }
            if (out.size >= maxBloques) break
        }
        if (out.size < maxBloques) cerrar()
        return out
    }

    /**
     * En qué bloque proyectado (0 = el siguiente) entraría una transacción de [tasa], o null si queda detrás de
     * todos los proyectados. Con la mempool vacía o casi, entra en el siguiente.
     */
    fun posicion(tasa: Double, bloques: List<Proyectado>): Int? {
        if (bloques.isEmpty()) return 0
        for ((i, b) in bloques.withIndex()) if (tasa >= b.minima || b.vsize < VSIZE_BLOQUE) return i
        return null
    }

    // ── Halving y dificultad ───────────────────────────────────────────────────────────────────────

    data class Halving(val siguiente: Long, val faltan: Long, val subvencionAhora: Long, val subvencionDespues: Long)

    fun halving(altura: Long): Halving {
        val era = altura / 210_000
        fun sub(e: Long) = if (e >= 64) 0L else 5_000_000_000L shr e.toInt()
        val sig = (era + 1) * 210_000
        return Halving(sig, sig - altura, sub(era), sub(era + 1))
    }

    data class Ajuste(val bloquesHechos: Long, val faltan: Long, val mediaSeg: Double, val cambio: Double, val segundosHasta: Double)

    /**
     * Estimación del próximo ajuste: [inicio] es la altura del primer bloque del periodo de 2016 y [tInicio] su
     * hora; [tPunta] la hora del bloque [altura]. Cambio = 600 / media − 1, con los topes del protocolo (×4, ÷4).
     * null si aún no hay bloques en el periodo para medir.
     */
    fun ajuste(altura: Long, tInicio: Long, tPunta: Long): Ajuste? {
        val inicio = altura - altura % 2016
        val hechos = altura - inicio
        if (hechos <= 0 || tPunta <= tInicio) return null
        val media = (tPunta - tInicio).toDouble() / hechos
        val cambio = (600.0 / media - 1.0).coerceIn(-0.75, 3.0)
        val faltan = 2016 - hechos
        return Ajuste(hechos, faltan, media, cambio, faltan * media)
    }
    fun inicioPeriodo(altura: Long) = altura - altura % 2016

    // ── Transacciones y bloques ────────────────────────────────────────────────────────────────────

    data class ResumenTx(val txid: String, val confirmaciones: Long, val altura: Long?, val hora: Long?, val comision: Long,
                         val vsize: Long, val tasa: Double, val rbf: Boolean, val entradas: Int, val salidas: Int, val totalSalidas: Long)

    /** /tx/:txid con la altura de la punta (para las confirmaciones). */
    fun leerTx(json: String, punta: Long?): ResumenTx? = try {
        val o = JSONObject(json)
        val st = o.optJSONObject("status")
        val confirmada = st?.optBoolean("confirmed") == true
        val alt = if (confirmada) st!!.optLong("block_height") else null
        val vin = o.getJSONArray("vin"); val vout = o.getJSONArray("vout")
        val vsize = (o.optLong("weight") + 3) / 4
        val fee = o.optLong("fee")
        ResumenTx(o.getString("txid"),
            if (alt != null && punta != null && punta >= alt) punta - alt + 1 else 0,
            alt, if (confirmada) st!!.optLong("block_time") else null, fee, vsize,
            if (vsize > 0) fee.toDouble() / vsize else 0.0,
            (0 until vin.length()).any { vin.getJSONObject(it).optLong("sequence", 0xffffffffL) < 0xfffffffeL },
            vin.length(), vout.length(), (0 until vout.length()).sumOf { vout.getJSONObject(it).optLong("value") })
    } catch (e: Exception) { null }

    data class ResumenBloque(val hash: String, val altura: Long, val hora: Long, val txs: Long, val tamano: Long, val peso: Long)

    fun leerBloque(o: JSONObject): ResumenBloque = ResumenBloque(o.optString("id"), o.optLong("height"), o.optLong("timestamp"),
        o.optLong("tx_count"), o.optLong("size"), o.optLong("weight"))

    /** /blocks: los últimos bloques. */
    fun leerBloques(json: String): List<ResumenBloque> = try {
        val a = JSONArray(json); (0 until a.length()).map { leerBloque(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    data class Saldo(val confirmado: Long, val sinConfirmar: Long, val txs: Long)

    /** /address/:a: lo confirmado y lo pendiente (entradas menos salidas). */
    fun leerDireccion(json: String): Saldo? = try {
        val o = JSONObject(json)
        fun neto(s: JSONObject?) = (s?.optLong("funded_txo_sum") ?: 0) - (s?.optLong("spent_txo_sum") ?: 0)
        val c = o.optJSONObject("chain_stats"); val m = o.optJSONObject("mempool_stats")
        Saldo(neto(c), neto(m), (c?.optLong("tx_count") ?: 0) + (m?.optLong("tx_count") ?: 0))
    } catch (e: Exception) { null }

    // ── Qué es lo buscado ──────────────────────────────────────────────────────────────────────────

    sealed class Busqueda {
        data class Tx(val txid: String) : Busqueda()
        data class BloqueHash(val hash: String) : Busqueda()
        data class BloqueAltura(val altura: Long) : Busqueda()
        data class Direccion(val d: String) : Busqueda()
    }

    /** Un txid, un hash de bloque (64 hex que empiezan por ceros), una altura o una dirección. null si nada. */
    fun que(entrada: String, testnet: Boolean = false): Busqueda? {
        val t = entrada.trim()
        if (Regex("^[0-9a-fA-F]{64}$").matches(t)) return if (t.startsWith("00000000")) Busqueda.BloqueHash(t.lowercase()) else Busqueda.Tx(t.lowercase())
        if (Regex("^[0-9]{1,8}$").matches(t)) return Busqueda.BloqueAltura(t.toLong())
        if (BtcAddress.validate(t, testnet) is BtcAddress.Result.Valid) return Busqueda.Direccion(t)
        return null
    }
}
