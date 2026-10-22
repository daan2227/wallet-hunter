package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Exporta TODO el conjunto de UTXO de la cadena (el snapshot de Core) a un CSV legible, con seis columnas:
 *   wallet (la dirección), utxo (txid:vout), funds_btc, wallet_type, public_key y tx_date.
 *
 *  - wallet: la dirección del script (P2PK no tiene: se pone la P2PKH de su clave, como hacen los exploradores).
 *  - public_key: sólo donde la cadena la muestra sin gastar: P2PK (la clave) y P2TR (la clave de salida x-only).
 *    En P2PKH, P2SH y P2WPKH sólo hay un hash hasta que se gastan, y queda vacía.
 *  - tx_date: el día (UTC) del bloque que creó la moneda. El snapshot sólo trae la altura; se pasa a fecha con la hora
 *    de un bloque de cada 100, interpolando: error de unas horas, nunca de más de un día.
 *
 * Son unos 190 millones de monedas y ~130 bytes por fila: por eso hay un importe mínimo. El fichero se escribe
 * sobre la marcha, sin tener nada en memoria, y se avisa del avance.
 */
object ExportadorUtxo {

    const val PASO = 100L

    /** Altura -> hora, a partir de la hora de un bloque de cada [PASO] y de la punta. */
    class TiempoDeBloques(private val horas: LongArray, val tope: Long, private val horaTope: Long) {
        private val dias = arrayOfNulls<String>(30_000)

        fun segundos(h: Long): Long {
            val ultimoMultiplo = (horas.size - 1) * PASO
            return if (h >= ultimoMultiplo) {
                if (tope <= ultimoMultiplo) horas[horas.size - 1]
                else horas[horas.size - 1] + (horaTope - horas[horas.size - 1]) * (h - ultimoMultiplo) / (tope - ultimoMultiplo)
            } else {
                val i = (h / PASO).toInt()
                horas[i] + (horas[i + 1] - horas[i]) * (h - i * PASO) / PASO
            }
        }

        /** "2021-05-03", UTC. */
        fun dia(h: Long): String {
            val d = segundos(h) / 86_400L
            val k = (d - 14_000L).toInt().coerceIn(0, dias.size - 1)
            return dias[k] ?: java.time.LocalDate.ofEpochDay(d).toString().also { dias[k] = it }
        }
    }

    /** Importe de un VARINT comprimido (CompressAmount de Core) -> satoshis. */
    fun importe(x0: Long): Long {
        if (x0 == 0L) return 0L
        var x = x0 - 1
        var e = (x % 10).toInt(); x /= 10
        var n: Long
        if (e < 9) { val d = (x % 9) + 1; x /= 9; n = x * 10 + d } else n = x + 1
        while (e > 0) { n *= 10; e-- }
        return n
    }

    /** CompressAmount: sólo para probar que [importe] la deshace. */
    fun comprimir(n0: Long): Long {
        if (n0 == 0L) return 0L
        var n = n0; var e = 0
        while (n % 10 == 0L && e < 9) { n /= 10; e++ }
        return if (e < 9) { val d = (n % 10).toInt(); n /= 10; 1 + 10 * (9 * n + d - 1) + e } else 1 + 10 * (n - 1) + 9
    }

    /** La hora de un bloque de cada 100 hasta [tope], pedida al nodo por lotes (sólo hacen falta las cabeceras). */
    fun anclas(r: CoreRpc, tope: Long, c: ConversorUtxo.Cancelar, avance: (Long, Long) -> Unit): TiempoDeBloques? {
        val alturas = ArrayList<Long>()
        var h = 0L
        while (h <= tope) { alturas.add(h); h += PASO }
        val extra = if (tope % PASO != 0L) tope else null
        val todas = if (extra != null) alturas + extra else alturas
        val horas = LongArray(todas.size)
        var hecho = 0
        for (lote in todas.chunked(400)) {
            if (c.cancelado) return null
            val hashes = r.lote(lote.map { "getblockhash" to JSONArray().put(it) })
            val cab = r.lote(hashes.map { "getblockheader" to JSONArray().put(it as String).put(true) })
            for ((k, o) in cab.withIndex()) horas[hecho + k] = (o as JSONObject).getLong("time")
            hecho += lote.size
            avance(hecho.toLong(), todas.size.toLong())
        }
        return if (extra != null) TiempoDeBloques(horas.copyOf(alturas.size), tope, horas[horas.size - 1])
               else TiempoDeBloques(horas, tope, horas[horas.size - 1])
    }

    class Resultado(val monedas: Long, val filas: Long, val bytes: Long)

    private fun hex(b: ByteArray, desde: Int = 0, hasta: Int = b.size): String {
        val sb = StringBuilder((hasta - desde) * 2)
        for (i in desde until hasta) { val v = b[i].toInt() and 0xff; sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15]) }
        return sb.toString()
    }

    /**
     * @param minimoSat sólo se escriben las monedas de al menos este importe (0 = todas)
     * @param columnas lo que lleva cada fila (en el orden de [InstantaneaUtxo.Col])
     * @param soloConClave sólo las monedas cuya clave pública se ve en la cadena (P2PK y Taproot)
     * @param progreso (monedas leídas, monedas en total, filas escritas)
     * @return null si se canceló
     */
    fun exportar(entrada: File, salida: File, minimoSat: Long, tiempo: TiempoDeBloques, c: ConversorUtxo.Cancelar,
                 columnas: Set<InstantaneaUtxo.Col> = InstantaneaUtxo.PREDETERMINADAS, soloConClave: Boolean = false,
                 progreso: (Long, Long, Long) -> Unit): Resultado? {
        if (columnas.isEmpty()) throw ConversorUtxo.FormatoInvalido("no columns selected")
        val cols = InstantaneaUtxo.Col.values().filter { it in columnas }
        val quiereHash = InstantaneaUtxo.Col.HASH in columnas
        val quiereScript = InstantaneaUtxo.Col.SCRIPT in columnas
        val parcial = File(salida.path + ".part")
        var monedas = 0L; var filas = 0L
        try {
            BufferedOutputStream(FileOutputStream(parcial), 1 shl 20).use { out ->
                entrada.inputStream().use { fin ->
                    val l = ConversorUtxo.Lector(fin)
                    val cab = ByteArray(51); l.leer(cab, 51)
                    val h = CoreConfig.leerCabeceraSnapshot(cab) ?: throw ConversorUtxo.FormatoInvalido("that is not a UTXO snapshot")
                    val total = h.monedas
                    out.write((InstantaneaUtxo.cabecera(columnas) + "\n").toByteArray())
                    val txid = ByteArray(32); val h20 = ByteArray(20); val h32 = ByteArray(32); val datos = ByteArray(1 shl 14)
                    val sb = StringBuilder(256)
                    var siguienteAviso = 0L
                    while (monedas < total) {
                        l.leer(txid, 32)
                        val n = l.compactSize()
                        if (n <= 0 || n > total - monedas) throw ConversorUtxo.FormatoInvalido("bad coin count for a transaction")
                        var txidHex: String? = null
                        for (i in 0 until n) {
                            val vout = l.compactSize()
                            val codigo = l.varint()
                            val altura = codigo shr 1
                            val coinbase = (codigo and 1L) == 1L
                            val sat = importe(l.varint())
                            val ns = l.varint()
                            if (ns < 0 || ns > 10_000_000L) throw ConversorUtxo.FormatoInvalido("bad script size")
                            val nSize = ns.toInt()
                            val pasa = sat >= minimoSat
                            var tipo = ""; var addr = ""; var pub = ""; var hash = ""; var script = ""
                            when {
                                nSize < 2 -> {
                                    l.leer(h20, 20)
                                    if (pasa) {
                                        if (nSize == 0) { tipo = "P2PKH"; addr = SpkDireccion.p2pkh(h20) }
                                        else { tipo = "P2SH"; addr = SpkDireccion.p2sh(h20) }
                                        if (quiereHash) hash = hex(h20)
                                        if (quiereScript) script = if (nSize == 0) "76a914" + hex(h20) + "88ac" else "a914" + hex(h20) + "87"
                                    }
                                }
                                nSize < 6 -> {
                                    l.leer(h32, 32)
                                    if (pasa) {
                                        tipo = "P2PK"
                                        val k: ByteArray? = if (nSize < 4) ByteArray(33).also { it[0] = nSize.toByte(); System.arraycopy(h32, 0, it, 1, 32) }
                                                            else ConversorUtxo.descomprimir(nSize - 2, h32)
                                        if (k != null) {
                                            pub = hex(k)
                                            val h = ConversorUtxo.hash160(k)
                                            addr = SpkDireccion.p2pkh(h)
                                            if (quiereHash) hash = hex(h)
                                            if (quiereScript) script = (if (k.size == 33) "21" else "41") + pub + "ac"
                                        }
                                    }
                                }
                                else -> {
                                    val len = nSize - 6
                                    if (len <= datos.size) {
                                        l.leer(datos, len)
                                        if (pasa) {
                                            val s = datos.copyOf(len)
                                            addr = SpkDireccion.de(s) ?: ""
                                            if (quiereScript) script = hex(s)
                                            when {
                                                len == 22 && s[0].toInt() == 0 && s[1].toInt() == 0x14 -> { tipo = "P2WPKH"; hash = hex(s, 2, 22) }
                                                len == 34 && s[0].toInt() == 0 && s[1].toInt() == 0x20 -> { tipo = "P2WSH"; hash = hex(s, 2, 34) }
                                                len == 34 && s[0].toInt() == 0x51 && s[1].toInt() == 0x20 -> { tipo = "P2TR"; pub = hex(s, 2, 34); hash = pub }
                                                else -> tipo = if (addr.isEmpty()) "Non-standard" else "Other"
                                            }
                                        }
                                    } else { l.saltar(len.toLong()); if (pasa) tipo = "Non-standard" }
                                }
                            }
                            monedas++
                            if (pasa && !(soloConClave && pub.isEmpty())) {
                                if (txidHex == null) txidHex = hex(txid.reversedArray())    // el txid se muestra al revés
                                sb.setLength(0)
                                for ((k, col) in cols.withIndex()) {
                                    if (k > 0) sb.append(',')
                                    when (col) {
                                        InstantaneaUtxo.Col.WALLET -> sb.append(addr)
                                        InstantaneaUtxo.Col.UTXO -> sb.append(txidHex).append(':').append(vout)
                                        InstantaneaUtxo.Col.TXID -> sb.append(txidHex)
                                        InstantaneaUtxo.Col.VOUT -> sb.append(vout)
                                        InstantaneaUtxo.Col.FONDO_BTC -> sb.append(InstantaneaUtxo.btc(sat))
                                        InstantaneaUtxo.Col.FONDO_SAT -> sb.append(sat)
                                        InstantaneaUtxo.Col.TIPO -> sb.append(tipo)
                                        InstantaneaUtxo.Col.CLAVE -> sb.append(pub)
                                        InstantaneaUtxo.Col.HASH -> sb.append(hash)
                                        InstantaneaUtxo.Col.SCRIPT -> sb.append(script)
                                        InstantaneaUtxo.Col.ALTURA -> sb.append(altura)
                                        InstantaneaUtxo.Col.FECHA -> sb.append(tiempo.dia(altura))
                                        InstantaneaUtxo.Col.COINBASE -> sb.append(if (coinbase) "yes" else "no")
                                        InstantaneaUtxo.Col.CONFIRMACIONES -> sb.append((tiempo.tope - altura + 1).coerceAtLeast(1))
                                    }
                                }
                                sb.append('\n')
                                out.write(sb.toString().toByteArray(Charsets.US_ASCII)); filas++
                            }
                        }
                        if (monedas >= siguienteAviso) {
                            if (c.cancelado) { parcial.delete(); return null }
                            progreso(monedas, total, filas); siguienteAviso = monedas + 500_000
                        }
                    }
                }
            }
            if (!parcial.renameTo(salida)) throw ConversorUtxo.FormatoInvalido("could not move the file into place")
        } catch (e: Exception) { parcial.delete(); throw e }
        return Resultado(monedas, filas, salida.length())
    }
}
