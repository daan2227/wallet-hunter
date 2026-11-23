package com.hunter.btc

/**
 * Backtesting sobre velas (cálculo puro, probado en la JVM). Sin mirar el futuro:
 * la señal se calcula con el CIERRE de la vela i y se ejecuta en la APERTURA de la
 * vela i+1. Stop-loss y take-profit se comprueban dentro de cada vela con su máximo
 * y mínimo (si se tocan los dos en la misma vela, cuenta el stop: criterio prudente).
 * La cuenta reinvierte todo (interés compuesto) con el apalancamiento elegido y paga
 * comisión al entrar y al salir. No es asesoramiento: el pasado no garantiza nada.
 */
object Backtest {

    enum class Estrategia(val nombre: String) {
        CRUCE_MA("Moving average cross"), RSI("RSI reversal"), BOLLINGER("Bollinger bounce")
    }

    class Params(
        val estrategia: Estrategia = Estrategia.CRUCE_MA,
        val rapida: Int = 9, val lenta: Int = 21,
        val rsiN: Int = 14, val rsiBajo: Double = 30.0, val rsiAlto: Double = 70.0,
        val bbN: Int = 20, val bbK: Double = 2.0,
        /** Solo largos (si no, también cortos). */ val soloLargos: Boolean = false,
        /** Comisión por lado (0,0005 = 0,05 %). */ val comision: Double = 0.0005,
        /** Stop-loss y take-profit en % sobre la entrada (0 = sin). */ val slPct: Double = 0.0, val tpPct: Double = 0.0,
        val apalancamiento: Int = 1,
    )

    class Trade(val iEntrada: Int, val iSalida: Int, val largo: Boolean, val entrada: Double, val salida: Double,
                /** Resultado sobre el capital, en fracción (0,05 = +5 %), ya con comisiones y apalancamiento. */ val resultado: Double,
                val motivo: String)

    class Resultado(
        val trades: List<Trade>,
        /** Capital por vela, empezando en 1,0 (valorado a cierre). */ val curva: DoubleArray,
        val retornoPct: Double, val comprarYMantenerPct: Double, val maxDrawdownPct: Double,
        val tasaAcierto: Double, val factorBeneficio: Double, val liquidado: Boolean,
    )

    /** Señal deseada al cierre de cada vela: +1 largo, −1 corto, 0 fuera, null = mantener lo que haya. */
    private fun senales(v: List<GraficoPrecio.Vela>, p: Params): Array<Int?> {
        val n = v.size; val c = FloatArray(n) { v[it].c }
        val out = arrayOfNulls<Int>(n)
        when (p.estrategia) {
            Estrategia.CRUCE_MA -> {
                val r = Indicadores.ema(c, p.rapida); val l = Indicadores.ema(c, p.lenta)
                for (i in 1 until n) {
                    if (r[i].isNaN() || l[i].isNaN() || r[i - 1].isNaN() || l[i - 1].isNaN()) continue
                    if (r[i - 1] <= l[i - 1] && r[i] > l[i]) out[i] = 1
                    else if (r[i - 1] >= l[i - 1] && r[i] < l[i]) out[i] = if (p.soloLargos) 0 else -1
                }
            }
            Estrategia.RSI -> {
                val s = Indicadores.rsi(c, p.rsiN)
                for (i in 1 until n) {
                    if (s[i].isNaN() || s[i - 1].isNaN()) continue
                    when {
                        s[i - 1] < p.rsiBajo && s[i] >= p.rsiBajo -> out[i] = 1                       // sale de sobreventa
                        s[i - 1] > p.rsiAlto && s[i] <= p.rsiAlto -> out[i] = if (p.soloLargos) 0 else -1   // sale de sobrecompra
                        s[i] >= p.rsiAlto -> out[i] = if (p.soloLargos) 0 else null                   // un largo se cierra arriba
                    }
                }
            }
            Estrategia.BOLLINGER -> {
                val b = Indicadores.bollinger(c, p.bbN, p.bbK.toFloat())
                for (i in 0 until n) {
                    if (b.media[i].isNaN()) continue
                    out[i] = when {
                        c[i] < b.baja[i] -> 1
                        c[i] > b.alta[i] -> if (p.soloLargos) 0 else -1
                        else -> null
                    }
                }
            }
        }
        // Bollinger: los largos salen al tocar la media, los cortos también (vuelta a la media)
        if (p.estrategia == Estrategia.BOLLINGER) {
            val b = Indicadores.bollinger(c, p.bbN, p.bbK.toFloat())
            var pos = 0
            for (i in 0 until n) {
                val s = out[i]
                if (s != null) pos = s
                else if (!b.media[i].isNaN() && ((pos == 1 && c[i] >= b.media[i]) || (pos == -1 && c[i] <= b.media[i]))) { out[i] = 0; pos = 0 }
            }
        }
        return out
    }

    fun ejecutar(v: List<GraficoPrecio.Vela>, p: Params): Resultado {
        val n = v.size
        val curva = DoubleArray(n) { 1.0 }
        if (n < 3) return Resultado(emptyList(), curva, 0.0, 0.0, 0.0, 0.0, 0.0, false)
        val sen = senales(v, p)
        val trades = ArrayList<Trade>()
        val ap = p.apalancamiento.coerceIn(1, 125).toDouble()
        var capital = 1.0; var pos = 0; var entrada = 0.0; var iEnt = 0; var capEnt = 1.0
        var liquidado = false
        fun cerrar(i: Int, precio: Double, motivo: String) {
            val bruto = (precio / entrada - 1) * pos
            val res = (bruto * ap - 2 * p.comision * ap).coerceAtLeast(-1.0)
            capital = capEnt * (1 + res)
            trades.add(Trade(iEnt, i, pos == 1, entrada, precio, res, motivo))
            pos = 0
            if (capital <= 1e-9) { capital = 0.0; liquidado = true }
        }
        for (i in 1 until n) {
            val k = v[i]
            // 1) la señal de la vela anterior se ejecuta en esta apertura
            val s = sen[i - 1]
            if (!liquidado && s != null && s != pos) {
                if (pos != 0) cerrar(i, k.o.toDouble(), "Signal")
                if (s != 0 && !liquidado) { pos = s; entrada = k.o.toDouble(); iEnt = i; capEnt = capital }
            }
            // 2) dentro de la vela: liquidación, stop y objetivo
            if (pos != 0) {
                val hi = k.h.toDouble(); val lo = k.l.toDouble()
                val liq = entrada * (1 - pos / ap * 0.995)                       // pérdida ≈ todo el margen
                val sl = if (p.slPct > 0) entrada * (1 - pos * p.slPct / 100) else Double.NaN
                val tp = if (p.tpPct > 0) entrada * (1 + pos * p.tpPct / 100) else Double.NaN
                val toca = { x: Double, contraLargo: Boolean -> if (contraLargo == (pos == 1)) lo <= x else hi >= x }
                when {
                    !sl.isNaN() && toca(sl, true) && (ap <= 1 || (if (pos == 1) sl > liq else sl < liq)) -> cerrar(i, sl, "Stop-loss")
                    ap > 1 && toca(liq, true) -> { cerrar(i, liq, "Liquidated") }
                    !tp.isNaN() && toca(tp, false) -> cerrar(i, tp, "Take-profit")
                }
            }
            // 3) valor a cierre
            curva[i] = if (pos != 0) (capEnt * (1 + ((k.c / entrada - 1) * pos * ap - p.comision * ap))).coerceAtLeast(0.0) else capital
        }
        if (pos != 0) cerrar(n - 1, v.last().c.toDouble(), "End of data")
        curva[n - 1] = capital
        var pico = curva[0]; var dd = 0.0
        for (x in curva) { if (x > pico) pico = x; if (pico > 0) dd = maxOf(dd, (pico - x) / pico) }
        val gan = trades.filter { it.resultado > 0 }; val per = trades.filter { it.resultado < 0 }
        val bb = gan.sumOf { it.resultado }; val pb = -per.sumOf { it.resultado }
        return Resultado(trades, curva, (capital - 1) * 100, (v.last().c / v.first().o - 1) * 100.0, dd * 100,
            if (trades.isEmpty()) 0.0 else gan.size * 100.0 / trades.size,
            if (pb > 0) bb / pb else if (bb > 0) Double.POSITIVE_INFINITY else 0.0, liquidado)
    }
}
