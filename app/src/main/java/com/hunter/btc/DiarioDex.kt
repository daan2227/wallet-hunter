package com.hunter.btc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Diario del exchange de pruebas: métricas de rendimiento, PnL por día, CSV de
 * operaciones y movimientos, y copia de seguridad del estado. Cálculo puro (sin
 * vistas) para poder probarlo en la JVM.
 */
object Diario {

    class Metricas(
        val operaciones: Int, val ganadoras: Int, val perdedoras: Int,
        val beneficioBruto: Double, val perdidaBruta: Double,
        val mediaGanadora: Double, val mediaPerdedora: Double,
        /** Beneficio bruto / pérdida bruta (∞ sin pérdidas). */ val factorBeneficio: Double,
        /** PnL medio esperado por operación. */ val esperanza: Double,
        /** Mayor caída del PnL realizado acumulado desde un máximo (VUSD, ≥ 0). */ val maxDrawdown: Double,
        val rachaGanadora: Int, val rachaPerdedora: Int,
    ) {
        val tasaAcierto get() = if (operaciones > 0) ganadoras * 100.0 / operaciones else 0.0
    }

    /** Solo cuentan las operaciones cerradas (con precio de salida). */
    fun metricas(ops: List<Simulador.Operacion>): Metricas {
        val c = ops.filter { it.salida > 0 }
        val gan = c.filter { it.pnl > 0 }; val per = c.filter { it.pnl < 0 }
        val bb = gan.sumOf { it.pnl }; val pb = -per.sumOf { it.pnl }
        var acc = 0.0; var pico = 0.0; var dd = 0.0
        var rg = 0; var rp = 0; var maxG = 0; var maxP = 0
        for (o in c.sortedBy { it.t }) {
            acc += o.pnl; if (acc > pico) pico = acc; dd = maxOf(dd, pico - acc)
            if (o.pnl > 0) { rg++; rp = 0 } else if (o.pnl < 0) { rp++; rg = 0 }
            maxG = maxOf(maxG, rg); maxP = maxOf(maxP, rp)
        }
        return Metricas(c.size, gan.size, per.size, bb, pb,
            if (gan.isEmpty()) 0.0 else bb / gan.size, if (per.isEmpty()) 0.0 else -pb / per.size,
            if (pb > 0) bb / pb else if (bb > 0) Double.POSITIVE_INFINITY else 0.0,
            if (c.isEmpty()) 0.0 else c.sumOf { it.pnl } / c.size, dd, maxG, maxP)
    }

    /** PnL realizado por día (clave yyyy-MM-dd en [zona]). */
    fun pnlPorDia(ops: List<Simulador.Operacion>, zona: java.util.TimeZone = java.util.TimeZone.getDefault()): Map<String, Double> {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = zona }
        val out = HashMap<String, Double>()
        for (o in ops) if (o.salida > 0) { val k = f.format(java.util.Date(o.t * 1000)); out[k] = (out[k] ?: 0.0) + o.pnl }
        return out
    }

    private fun campo(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** CSV (UTC, punto decimal) con las operaciones y, debajo, los movimientos de la cuenta. */
    fun csv(sim: Simulador): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun n(x: Double) = java.math.BigDecimal(x).setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val sb = StringBuilder("time_utc,market,symbol,side,entry,exit,quantity,pnl_vusd,fee_vusd,reason,note\n")
        for (o in sim.historial) sb.append(listOf(f.format(java.util.Date(o.t * 1000)), if (o.mercado == Simulador.Mercado.FUT) "futures" else "spot",
            o.simbolo, o.lado, n(o.entrada), if (o.salida > 0) n(o.salida) else "", n(o.qty), if (o.salida > 0) n(o.pnl) else "", n(o.comision),
            o.motivo, o.nota).joinToString(",") { campo(it) }).append('\n')
        sb.append("\ntime_utc,movement,account,amount_vusd,note\n")
        for (m in sim.movimientos) sb.append(listOf(f.format(java.util.Date(m.t * 1000)), m.tipo, sim.nombre(m.cuenta), n(m.importe), m.nota)
            .joinToString(",") { campo(it) }).append('\n')
        return sb.toString()
    }

    /** Escribe [texto] en files/shared y lo ofrece para compartir (Drive, correo…). */
    fun compartir(ctx: Context, nombre: String, mime: String, texto: String) {
        val dir = java.io.File(ctx.filesDir, "shared").apply { mkdirs() }
        val f = java.io.File(dir, nombre); f.writeText(texto)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", f)
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, nombre))
    }
}

/**
 * Calendario de PnL de un mes: cada día en verde o rojo con intensidad según el
 * resultado. Las flechas ‹ › de arriba cambian de mes.
 */
class VistaCalendario(ctx: Context, private val pnl: Map<String, Double>) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val cal = java.util.Calendar.getInstance()
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; textAlign = Paint.Align.CENTER; typeface = AppTheme.medium(ctx) }
    private val pCelda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fClave = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    private val fMes = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US)
    private val altoCab = 44f * d; private val altoFila = 46f * d

    init { cal.set(java.util.Calendar.DAY_OF_MONTH, 1); describir() }

    private fun describir() {
        val m = cal.get(java.util.Calendar.MONTH); val y = cal.get(java.util.Calendar.YEAR)
        val delMes = pnl.filterKeys { it.startsWith("%04d-%02d".format(y, m + 1)) }
        contentDescription = "PnL calendar, ${fMes.format(cal.time)}: ${delMes.size} trading days, total %s VUSD. Tap the left or right edge of the title to change month."
            .format(fmtSim(delMes.values.sum()))
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (altoCab + 18f * d + altoFila * 6).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val col = w / 7
        pTxt.color = DexTema.TXT; pTxt.textSize = 15f * d
        c.drawText(fMes.format(cal.time), w / 2, 28f * d, pTxt)
        pTxt.color = DexTema.TXT2; c.drawText("‹", 16f * d, 28f * d, pTxt); c.drawText("›", w - 16f * d, 28f * d, pTxt)
        pTxt.textSize = 11f * d
        listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, s -> c.drawText(s, col * i + col / 2, altoCab + 10f * d, pTxt) }
        val k = cal.clone() as java.util.Calendar
        val desplaza = (k.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7      // lunes primero
        val dias = k.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val maxAbs = (1..dias).maxOfOrNull { dd -> k.set(java.util.Calendar.DAY_OF_MONTH, dd); Math.abs(pnl[fClave.format(k.time)] ?: 0.0) }?.coerceAtLeast(1e-9) ?: 1.0
        for (dd in 1..dias) {
            k.set(java.util.Calendar.DAY_OF_MONTH, dd)
            val pos = desplaza + dd - 1; val fila = pos / 7; val cl = pos % 7
            val x0 = col * cl + 2f * d; val y0 = altoCab + 18f * d + fila * altoFila + 2f * d
            val v = pnl[fClave.format(k.time)]
            pCelda.color = if (v == null || v == 0.0) DexTema.CHIP else {
                val a = (60 + 160 * Math.abs(v) / maxAbs).toInt().coerceIn(60, 220)
                ((if (v > 0) DexTema.UP else DexTema.DOWN) and 0x00FFFFFF) or (a shl 24) }
            c.drawRoundRect(x0, y0, x0 + col - 4f * d, y0 + altoFila - 4f * d, 6f * d, 6f * d, pCelda)
            pTxt.textSize = 11f * d; pTxt.color = if (v != null && v != 0.0) DexTema.sobre(pCelda.color or (0xFF shl 24)) else DexTema.TXT2
            c.drawText("$dd", x0 + (col - 4f * d) / 2, y0 + 15f * d, pTxt)
            if (v != null && v != 0.0) {
                pTxt.textSize = 10f * d
                val t = (if (v > 0) "+" else "") + if (Math.abs(v) >= 1000) "%.1fK".format(v / 1000) else "%.0f".format(v)
                c.drawText(t, x0 + (col - 4f * d) / 2, y0 + 32f * d, pTxt)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && e.y < altoCab) {
            when { e.x < width * 0.25f -> cal.add(java.util.Calendar.MONTH, -1); e.x > width * 0.75f -> cal.add(java.util.Calendar.MONTH, 1); else -> return true }
            describir(); invalidate(); performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
