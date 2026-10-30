package com.hunter.btc

/**
 * Informe de ganancias por el método FIFO: cada bitcoin que sale se cuenta como vendido al precio del día y
 * se le resta lo que costó el que entró antes (primero en entrar, primero en salir). Lo que se recibe es una
 * compra al precio del día. No sabe si un envío fue una venta, un pago o un traslado a otra cartera tuya, ni
 * si lo recibido fue una compra, un sueldo o un regalo: el informe lo dice y hay que revisarlo. Puro:
 * InformeFiscalTest.
 */
object InformeFiscal {
    /** Un movimiento confirmado: [neto] en sat (+ entra, − sale, comisión incluida), [precio] de 1 BTC ese día. */
    class Mov(val tiempo: Long, val txid: String, val neto: Long, val comision: Long, val precio: Double)

    class Lote(val tiempo: Long, var sats: Long, val coste1Btc: Double)

    /** Una salida: [sats] gastados (con la comisión), [ingreso] lo que valían sin la comisión, [coste] FIFO. */
    class Venta(val tiempo: Long, val txid: String, val sats: Long, val ingreso: Double, val coste: Double,
                val sinCoste: Long) {
        val ganancia get() = ingreso - coste
    }

    class Resultado(val ventas: List<Venta>, val quedan: List<Lote>, val compras: List<Mov>) {
        fun anos() = (ventas.map { ano(it.tiempo) } + compras.map { ano(it.tiempo) }).distinct().sorted()
        fun ganancia(ano: Int) = ventas.filter { ano(it.tiempo) == ano }.sumOf { it.ganancia }
        /** Sats vendidos sin un lote anterior conocido (entraron antes del historial, o vienen de otra cartera). */
        val sinCoste get() = ventas.sumOf { it.sinCoste }
    }

    fun ano(t: Long) = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = t * 1000 }.get(java.util.Calendar.YEAR)

    fun calcular(movs: List<Mov>): Resultado {
        val lotes = ArrayDeque<Lote>(); val ventas = ArrayList<Venta>(); val compras = ArrayList<Mov>()
        for (m in movs.sortedBy { it.tiempo }) {
            if (m.neto > 0) { lotes.addLast(Lote(m.tiempo, m.neto, m.precio)); compras.add(m); continue }
            if (m.neto == 0L) continue
            val sale = -m.neto
            // La comisión es un gasto de la venta: lo que se obtiene es lo enviado sin ella.
            val ingreso = (sale - m.comision).coerceAtLeast(0) / 1e8 * m.precio
            var falta = sale; var coste = 0.0
            while (falta > 0 && lotes.isNotEmpty()) {
                val l = lotes.first(); val usa = minOf(falta, l.sats)
                coste += usa / 1e8 * l.coste1Btc; l.sats -= usa; falta -= usa
                if (l.sats == 0L) lotes.removeFirst()
            }
            ventas.add(Venta(m.tiempo, m.txid, sale, ingreso, coste, falta))
        }
        return Resultado(ventas, lotes.toList(), compras)
    }

    private fun d(x: Double) = "%.2f".format(java.util.Locale.US, x)
    private fun btc(s: Long) = "%.8f".format(java.util.Locale.US, s / 1e8)

    /** CSV con una fila por movimiento y un resumen por año al final. */
    fun csv(r: Resultado, moneda: String): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder("date,type,txid,btc,proceeds_$moneda,cost_basis_$moneda,gain_$moneda,btc_without_known_cost\n")
        val filas = r.compras.map { it.tiempo to "${f.format(java.util.Date(it.tiempo * 1000))},acquisition,${it.txid},${btc(it.neto)},,${d(it.neto / 1e8 * it.precio)},,\n" } +
            r.ventas.map { it.tiempo to "${f.format(java.util.Date(it.tiempo * 1000))},disposal,${it.txid},${btc(it.sats)},${d(it.ingreso)},${d(it.coste)},${d(it.ganancia)},${btc(it.sinCoste)}\n" }
        filas.sortedBy { it.first }.forEach { sb.append(it.second) }
        sb.append("\nyear,gain_$moneda\n")
        for (a in r.anos()) sb.append(a).append(',').append(d(r.ganancia(a))).append('\n')
        sb.append("\n# FIFO, daily prices. Every send counts as a disposal and every receipt as an acquisition at that day's price.\n")
        sb.append("# Transfers between your own wallets are not sales: remove them. Check your country's rules.\n")
        return sb.toString()
    }
}
