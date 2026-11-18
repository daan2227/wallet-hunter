package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exchange de pruebas (spot y futuros perpetuos) con una moneda virtual, VUSD
 * (1 VUSD = 1 USD de referencia), y precios reales. VUSD no tiene valor, no sale
 * del teléfono y no se puede convertir en nada real: vive en este objeto y se
 * guarda como JSON en las preferencias privadas de la app (sin copia en la nube).
 *
 * Como un exchange: dos cuentas (Spot y Futuros) con transferencias entre ellas,
 * depósitos y retiros de prueba, libro de movimientos, orden mínima de 5 VUSD.
 *  - Spot: comisión 0,1 % (maker y taker). Comprar descuenta VUSD; vender los devuelve.
 *  - Futuros: margen aislado, apalancamiento 1–125x, taker 0,05 %, maker 0,02 %,
 *    margen de mantenimiento 0,5 % y funding de 0,01 % cada 8 h (00/08/16 UTC;
 *    los long pagan, los short cobran). Liquidación: se pierde el margen.
 *  - Órdenes límite: se reserva el saldo; se llenan al precio límite (maker) cuando
 *    el precio lo toca. TP/SL salen al precio fijado (taker); si en el mismo tramo
 *    se tocan TP y SL se asume SL (criterio conservador).
 */
class Simulador {

    enum class Mercado { SPOT, FUT }

    class Posicion(val id: Long, val clave: String, val activo: String, val simbolo: String, val long: Boolean,
                   val entrada: Double, var qty: Double, var margen: Double, val apal: Int,
                   var tp: Double, var sl: Double, val abierta: Long, var funding: Double = 0.0, var ultFunding: Long = abierta) {
        val nocional get() = entrada * qty
        fun pnl(precio: Double) = (precio - entrada) * qty * (if (long) 1 else -1)
        /** Margen aislado: se liquida cuando la pérdida deja el margen en el de mantenimiento. */
        val liquidacion: Double get() = (margen - MANT * nocional) / qty.coerceAtLeast(1e-18) * (if (long) -1 else 1) + entrada
    }

    class Tenencia(val clave: String, val activo: String, val simbolo: String, var qty: Double, var coste: Double)

    /** Orden límite pendiente. Spot: compra con [importe] VUSD o venta de [qty]. Futuros: [importe] = margen. */
    class Orden(val id: Long, val mercado: Mercado, val clave: String, val activo: String, val simbolo: String,
                val compra: Boolean, val precio: Double, val importe: Double, val qty: Double, val apal: Int,
                val tp: Double, val sl: Double, val creada: Long, val reservado: Double)

    class Operacion(val t: Long, val mercado: Mercado, val simbolo: String, val lado: String, val entrada: Double,
                    val salida: Double, val qty: Double, val pnl: Double, val comision: Double, val motivo: String)

    /** Movimiento de la cuenta: Deposit, Withdraw, Transfer, Funding. [importe] con signo para [cuenta]. */
    class Movimiento(val t: Long, val tipo: String, val cuenta: Mercado, val importe: Double, val nota: String)

    /** VUSD libres en cada cuenta. */
    var spot = BIENVENIDA; private set
    var fut = BIENVENIDA; private set
    /** Depósitos menos retiros: base para medir el rendimiento. */
    var netoDepositado = 2 * BIENVENIDA; private set
    val movimientos = ArrayList<Movimiento>()
    val posiciones = ArrayList<Posicion>()
    val tenencias = ArrayList<Tenencia>()
    val ordenes = ArrayList<Orden>()
    val historial = ArrayList<Operacion>()
    /** Último precio visto por activo (clave → precio). */
    val precios = HashMap<String, Double>()
    /** Último instante (s) procesado por activo, para no reprocesar velas. */
    val revisado = HashMap<String, Long>()
    private var sigId = 1L

    /** Avisos de lo que ha pasado (llenados, TP, SL, liquidaciones); la UI los vacía. */
    val eventos = ArrayList<String>()

    companion object {
        const val MONEDA = "VUSD"
        /** Depósito de bienvenida en cada cuenta al abrir o reiniciar. */
        const val BIENVENIDA = 10_000.0
        const val MIN_ORDEN = 5.0
        const val MAX_DEPOSITO = 1_000_000.0
        const val COM_SPOT = 0.001
        const val COM_TAKER = 0.0005
        const val COM_MAKER = 0.0002
        const val MANT = 0.005
        const val FUNDING = 0.0001
        const val FUNDING_SEG = 8 * 3600L

        fun liquidacionDe(entrada: Double, apal: Int, long: Boolean): Double =
            if (long) entrada * (1 - 1.0 / apal + MANT) else entrada * (1 + 1.0 / apal - MANT)

        fun deJson(s: String?): Simulador = Simulador().apply { cargar(s) }
    }

    private fun nuevoId() = sigId++

    // ── Valoración ──
    fun precio(clave: String) = precios[clave] ?: 0.0
    fun pnlNoRealizado() = posiciones.sumOf { p -> precios[p.clave]?.let { p.pnl(it) } ?: 0.0 }
    fun valorSpot() = tenencias.sumOf { it.qty * (precios[it.clave] ?: (it.coste / it.qty.coerceAtLeast(1e-18))) }
    /** Patrimonio: saldo libre + reservado en órdenes + márgenes + PnL abierto + valor spot. */
    fun patrimonio() = spot + fut + ordenes.sumOf { if (it.mercado == Mercado.SPOT && !it.compra) 0.0 else it.reservado } +
            posiciones.sumOf { it.margen } + pnlNoRealizado() + valorSpot() +
            ordenes.filter { it.mercado == Mercado.SPOT && !it.compra }.sumOf { it.qty * (precios[it.clave] ?: it.precio) }

    fun rendimiento() = patrimonio() - netoDepositado
    fun reservado(m: Mercado) = ordenes.filter { it.mercado == m && (m == Mercado.FUT || it.compra) }.sumOf { it.reservado }

    // ── Cuenta: depósitos, retiros y transferencias de prueba ──
    fun depositar(cuenta: Mercado, x: Double, t: Long = ahora()): String? {
        if (x <= 0 || x.isNaN()) return "Enter an amount"
        if (x > MAX_DEPOSITO) return "Max ${fmtSim(MAX_DEPOSITO)} $MONEDA per deposit"
        if (cuenta == Mercado.SPOT) spot += x else fut += x
        netoDepositado += x; movimientos.add(Movimiento(t, "Deposit", cuenta, x, "Test funds")); return null
    }
    fun retirar(cuenta: Mercado, x: Double, t: Long = ahora()): String? {
        val libre = if (cuenta == Mercado.SPOT) spot else fut
        if (x <= 0 || x.isNaN()) return "Enter an amount"
        if (x > libre + 1e-9) return "Only ${fmtSim(libre)} $MONEDA available"
        val q = minOf(x, libre)
        if (cuenta == Mercado.SPOT) spot -= q else fut -= q
        netoDepositado -= q; movimientos.add(Movimiento(t, "Withdraw", cuenta, -q, "Removed from the test account")); return null
    }
    fun transferir(desde: Mercado, x: Double, t: Long = ahora()): String? {
        val libre = if (desde == Mercado.SPOT) spot else fut
        if (x <= 0 || x.isNaN()) return "Enter an amount"
        if (x > libre + 1e-9) return "Only ${fmtSim(libre)} $MONEDA available"
        val q = minOf(x, libre)
        if (desde == Mercado.SPOT) { spot -= q; fut += q } else { fut -= q; spot += q }
        val hacia = if (desde == Mercado.SPOT) Mercado.FUT else Mercado.SPOT
        movimientos.add(Movimiento(t, "Transfer", desde, -q, "To ${nombre(hacia)}"))
        movimientos.add(Movimiento(t, "Transfer", hacia, q, "From ${nombre(desde)}")); return null
    }
    fun nombre(m: Mercado) = if (m == Mercado.SPOT) "Spot" else "Futures"

    // ── Spot ──
    fun comprarSpot(clave: String, activo: String, simbolo: String, usdt: Double, precio: Double, maker: Boolean = false, t: Long = ahora()): String? {
        if (usdt <= 0 || precio <= 0) return "Enter an amount"
        if (!maker && usdt < MIN_ORDEN) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
        if (!maker && usdt > spot + 1e-9) return "Not enough $MONEDA in Spot"
        if (!maker) spot -= usdt
        val qty = usdt / precio * (1 - COM_SPOT)
        val h = tenencias.firstOrNull { it.clave == clave }
        if (h == null) tenencias.add(Tenencia(clave, activo, simbolo, qty, usdt)) else { h.qty += qty; h.coste += usdt }
        historial.add(Operacion(t, Mercado.SPOT, simbolo, "Buy", precio, 0.0, qty, 0.0, usdt * COM_SPOT, if (maker) "Limit" else "Market"))
        precios[clave] = precios[clave] ?: precio
        return null
    }

    /** Vende [qty]; con [costeReservado] la cantidad ya se apartó de la tenencia (orden límite) a ese coste medio. */
    fun venderSpot(clave: String, qty: Double, precio: Double, motivo: String = "Market", t: Long = ahora(), costeReservado: Double? = null): String? {
        val yaReservado = costeReservado != null
        val h = tenencias.firstOrNull { it.clave == clave } ?: return "Nothing to sell"
        if (qty <= 0 || precio <= 0) return "Enter an amount"
        if (!yaReservado && qty > h.qty * (1 + 1e-9)) return "Not enough ${h.simbolo}"
        if (!yaReservado && qty * precio < MIN_ORDEN && qty < h.qty * (1 - 1e-9)) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
        val q = if (yaReservado) qty else minOf(qty, h.qty)
        val bruto = q * precio; val com = bruto * COM_SPOT
        // coste medio de lo vendido (la parte reservada ya se descontó de la tenencia)
        val costeMedio = costeReservado ?: (h.coste / h.qty)
        val coste = costeMedio * q
        if (!yaReservado) { h.coste -= coste; h.qty -= q }
        spot += bruto - com
        historial.add(Operacion(t, Mercado.SPOT, h.simbolo, "Sell", costeMedio, precio, q, bruto - com - coste, com, motivo))
        if (h.qty <= 1e-12 && ordenes.none { it.clave == clave && it.mercado == Mercado.SPOT && !it.compra }) tenencias.remove(h)
        return null
    }
    private val costeRes = HashMap<Long, Double>()   // id de orden de venta → coste medio reservado

    // ── Futuros ──
    fun abrirFuturo(clave: String, activo: String, simbolo: String, long: Boolean, margen: Double, apal: Int, precio: Double,
                    tp: Double = 0.0, sl: Double = 0.0, maker: Boolean = false, t: Long = ahora()): String? {
        if (margen <= 0 || precio <= 0) return "Enter a margin amount"
        if (apal !in 1..125) return "Leverage must be 1–125x"
        val nocional = margen * apal; val com = nocional * (if (maker) COM_MAKER else COM_TAKER)
        if (!maker && nocional < MIN_ORDEN) return "Minimum position is ${fmtSim(MIN_ORDEN)} $MONEDA notional"
        if (!maker && margen + com > fut + 1e-9) return "Not enough $MONEDA in Futures (margin + fee)"
        val liq = liquidacionDe(precio, apal, long)
        if (tp > 0 && (if (long) tp <= precio else tp >= precio)) return "Take-profit must be ${if (long) "above" else "below"} entry"
        if (sl > 0 && (if (long) sl >= precio else sl <= precio)) return "Stop-loss must be ${if (long) "below" else "above"} entry"
        if (sl > 0 && (if (long) sl <= liq else sl >= liq)) return "Stop-loss is beyond the liquidation price"
        if (!maker) fut -= margen + com
        posiciones.add(Posicion(nuevoId(), clave, activo, simbolo, long, precio, nocional / precio, margen, apal, tp, sl, t))
        historial.add(Operacion(t, Mercado.FUT, simbolo, if (long) "Open long ${apal}x" else "Open short ${apal}x", precio, 0.0, nocional / precio, 0.0, com, if (maker) "Limit" else "Market"))
        precios[clave] = precios[clave] ?: precio
        return null
    }

    /** Cierra la posición entera o una [fraccion] (0–1) a mercado. */
    fun cerrarFuturo(id: Long, precio: Double, motivo: String = "Close", t: Long = ahora(), fraccion: Double = 1.0) {
        val p = posiciones.firstOrNull { it.id == id } ?: return
        val lado = if (p.long) "Long" else "Short"
        if (fraccion < 0.999 && motivo != "Liquidated") {
            val f = fraccion.coerceIn(0.01, 0.99); val q = p.qty * f; val m = p.margen * f
            val pnl = (precio - p.entrada) * q * (if (p.long) 1 else -1); val com = precio * q * COM_TAKER
            fut += (m + pnl - com).coerceAtLeast(0.0)
            p.qty -= q; p.margen -= m
            historial.add(Operacion(t, Mercado.FUT, p.simbolo, "$lado ${p.apal}x", p.entrada, precio, q, pnl - com, com, "Close ${Math.round(f * 100)}%"))
            return
        }
        posiciones.remove(p)
        if (motivo == "Liquidated") {
            historial.add(Operacion(t, Mercado.FUT, p.simbolo, "$lado ${p.apal}x", p.entrada, precio, p.qty, -p.margen + p.funding, 0.0, motivo))
            return
        }
        val pnl = p.pnl(precio); val com = precio * p.qty * COM_TAKER
        fut += (p.margen + pnl - com).coerceAtLeast(0.0)
        historial.add(Operacion(t, Mercado.FUT, p.simbolo, "$lado ${p.apal}x", p.entrada, precio, p.qty, pnl - com + p.funding, com, motivo))
    }

    /** Añade margen aislado desde la cuenta de Futuros (aleja la liquidación). */
    fun agregarMargen(id: Long, x: Double, t: Long = ahora()): String? {
        val p = posiciones.firstOrNull { it.id == id } ?: return "Position closed"
        if (x <= 0) return "Enter an amount"; if (x > fut + 1e-9) return "Only ${fmtSim(fut)} $MONEDA in Futures"
        val q = minOf(x, fut); fut -= q; p.margen += q
        if (p.sl > 0 && (if (p.long) p.sl <= p.liquidacion else p.sl >= p.liquidacion)) p.sl = 0.0
        movimientos.add(Movimiento(t, "Margin", Mercado.FUT, -q, "Added to ${if (p.long) "long" else "short"} ${p.simbolo} #${p.id}"))
        return null
    }

    fun ponerTpSl(id: Long, tp: Double, sl: Double): String? {
        val p = posiciones.firstOrNull { it.id == id } ?: return "Position closed"
        val ref = precios[p.clave] ?: p.entrada
        if (tp > 0 && (if (p.long) tp <= ref else tp >= ref)) return "Take-profit must be ${if (p.long) "above" else "below"} the current price"
        if (sl > 0 && (if (p.long) sl >= ref else sl <= ref)) return "Stop-loss must be ${if (p.long) "below" else "above"} the current price"
        if (sl > 0 && (if (p.long) sl <= p.liquidacion else sl >= p.liquidacion)) return "Stop-loss is beyond the liquidation price"
        p.tp = tp; p.sl = sl; return null
    }

    // ── Órdenes límite ──
    fun ordenLimite(mercado: Mercado, clave: String, activo: String, simbolo: String, compra: Boolean, precio: Double,
                    importe: Double, qty: Double, apal: Int, tp: Double, sl: Double, t: Long = ahora()): String? {
        if (precio <= 0) return "Enter a limit price"
        val reservado: Double
        when (mercado) {
            Mercado.SPOT -> if (compra) {
                if (importe <= 0) return "Enter an amount"; if (importe < MIN_ORDEN) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
                if (importe > spot + 1e-9) return "Not enough $MONEDA in Spot"
                spot -= importe; reservado = importe
            } else {
                val h = tenencias.firstOrNull { it.clave == clave } ?: return "Nothing to sell"
                if (qty <= 0) return "Enter an amount"; if (qty > h.qty * (1 + 1e-9)) return "Not enough ${h.simbolo}"
                if (qty * precio < MIN_ORDEN) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
                val q = minOf(qty, h.qty); val cm = h.coste / h.qty
                h.qty -= q; h.coste -= cm * q; costeRes[sigId] = cm; reservado = q
            }
            Mercado.FUT -> {
                if (importe <= 0) return "Enter a margin amount"; if (apal !in 1..125) return "Leverage must be 1–125x"
                if (importe * apal < MIN_ORDEN) return "Minimum position is ${fmtSim(MIN_ORDEN)} $MONEDA notional"
                val liq = liquidacionDe(precio, apal, compra)
                if (tp > 0 && (if (compra) tp <= precio else tp >= precio)) return "Take-profit must be ${if (compra) "above" else "below"} the limit price"
                if (sl > 0 && (if (compra) sl >= precio else sl <= precio)) return "Stop-loss must be ${if (compra) "below" else "above"} the limit price"
                if (sl > 0 && (if (compra) sl <= liq else sl >= liq)) return "Stop-loss is beyond the liquidation price"
                val res = importe + importe * apal * COM_MAKER
                if (res > fut + 1e-9) return "Not enough $MONEDA in Futures (margin + fee)"
                fut -= res; reservado = res
            }
        }
        ordenes.add(Orden(nuevoId(), mercado, clave, activo, simbolo, compra, precio, importe, qty, apal, tp, sl, t, reservado))
        return null
    }

    fun cancelarOrden(id: Long) {
        val o = ordenes.firstOrNull { it.id == id } ?: return
        ordenes.remove(o)
        if (o.mercado == Mercado.SPOT && !o.compra) {
            val cm = costeRes.remove(o.id) ?: 0.0
            val h = tenencias.firstOrNull { it.clave == o.clave }
            if (h == null) tenencias.add(Tenencia(o.clave, o.activo, o.simbolo, o.reservado, cm * o.reservado))
            else { h.qty += o.reservado; h.coste += cm * o.reservado }
        } else if (o.mercado == Mercado.SPOT) spot += o.reservado else fut += o.reservado
    }

    private fun llenar(o: Orden, t: Long) {
        ordenes.remove(o)
        when {
            o.mercado == Mercado.SPOT && o.compra -> { comprarSpot(o.clave, o.activo, o.simbolo, o.reservado, o.precio, maker = true, t = t) }
            o.mercado == Mercado.SPOT -> {
                if (tenencias.none { it.clave == o.clave }) tenencias.add(Tenencia(o.clave, o.activo, o.simbolo, 0.0, 0.0))
                venderSpot(o.clave, o.reservado, o.precio, "Limit", t, costeRes.remove(o.id) ?: 0.0) }
            else -> abrirFuturo(o.clave, o.activo, o.simbolo, o.compra, o.importe, o.apal, o.precio, o.tp, o.sl, maker = true, t = t)
        }
        eventos.add("Limit ${if (o.compra) (if (o.mercado == Mercado.FUT) "long" else "buy") else (if (o.mercado == Mercado.FUT) "short" else "sell")} ${o.simbolo} filled at ${fmtSim(o.precio)}")
    }

    /**
     * Procesa un tramo de precio de un activo: mínimo [lo], máximo [hi] y último
     * [ultimo], ocurrido hasta el instante [t] (s). Llena órdenes, dispara TP/SL,
     * liquida y cobra funding. [inicio] es cuándo empezó el tramo: lo creado después
     * no se evalúa con él (una vela anterior no puede llenar una orden nueva).
     * Devuelve true si algo cambió.
     */
    fun procesar(clave: String, lo: Double, hi: Double, ultimo: Double, t: Long = ahora(), inicio: Long = t): Boolean {
        if (ultimo <= 0) return false
        precios[clave] = ultimo
        var cambio = false
        for (o in ordenes.filter { it.clave == clave && it.creada <= inicio }) {
            val toca = if (o.compra) lo <= o.precio else hi >= o.precio
            if (toca) { llenar(o, t); cambio = true }
        }
        for (p in posiciones.filter { it.clave == clave && it.abierta <= inicio }) {
            // funding en cada frontera de 8 h cruzada
            var f = (p.ultFunding / FUNDING_SEG + 1) * FUNDING_SEG
            while (f <= t) { val pago = p.nocional * FUNDING * (if (p.long) -1 else 1); p.funding += pago; fut += pago; p.ultFunding = f; f += FUNDING_SEG
                movimientos.add(Movimiento(f - FUNDING_SEG, "Funding", Mercado.FUT, pago, "${if (p.long) "Long" else "Short"} ${p.simbolo} #${p.id}")) }
            val liq = p.liquidacion
            val slToca = p.sl > 0 && (if (p.long) lo <= p.sl else hi >= p.sl)
            val liqToca = if (p.long) lo <= liq else hi >= liq
            val tpToca = p.tp > 0 && (if (p.long) hi >= p.tp else lo <= p.tp)
            val lado = "${if (p.long) "Long" else "Short"} ${p.simbolo}"
            when {
                slToca -> { cerrarFuturo(p.id, p.sl, "Stop-loss", t); eventos.add("$lado: stop-loss hit at ${fmtSim(p.sl)}") }
                liqToca -> { cerrarFuturo(p.id, liq, "Liquidated", t); eventos.add("$lado LIQUIDATED at ${fmtSim(liq)}") }
                tpToca -> { cerrarFuturo(p.id, p.tp, "Take-profit", t); eventos.add("$lado: take-profit hit at ${fmtSim(p.tp)}") }
                else -> continue
            }
            cambio = true
        }
        revisado[clave] = maxOf(revisado[clave] ?: 0L, t)
        return cambio
    }

    fun clavesActivas(): Set<String> = (posiciones.map { it.clave } + ordenes.map { it.clave }).toSet()
    /** JSON del Activo guardado para una clave (para pedir sus precios aunque no esté en el gráfico). */
    fun activoDe(clave: String): String? = posiciones.firstOrNull { it.clave == clave }?.activo ?: ordenes.firstOrNull { it.clave == clave }?.activo
        ?: tenencias.firstOrNull { it.clave == clave }?.activo

    /** Cuenta nueva: borra todo y vuelve a dar el depósito de bienvenida en Spot y Futuros. */
    fun reiniciar(t: Long = ahora()) {
        posiciones.clear(); tenencias.clear(); ordenes.clear(); historial.clear(); costeRes.clear(); eventos.clear(); revisado.clear(); movimientos.clear()
        spot = BIENVENIDA; fut = BIENVENIDA; netoDepositado = 2 * BIENVENIDA
        movimientos.add(Movimiento(t, "Deposit", Mercado.SPOT, BIENVENIDA, "Welcome test funds"))
        movimientos.add(Movimiento(t, "Deposit", Mercado.FUT, BIENVENIDA, "Welcome test funds"))
    }
    init { reiniciar() }

    // ── Estadísticas ──
    class Stats(val operaciones: Int, val ganadoras: Int, val pnl: Double, val comisiones: Double, val mejor: Double, val peor: Double)
    fun cerradas() = historial.filter { it.salida > 0 }
    fun stats(): Stats {
        val c = cerradas()
        return Stats(c.size, c.count { it.pnl > 0 }, c.sumOf { it.pnl }, historial.sumOf { it.comision },
            c.maxOfOrNull { it.pnl } ?: 0.0, c.minOfOrNull { it.pnl } ?: 0.0)
    }
    /** PnL realizado acumulado en el tiempo (t, VUSD). */
    fun curva(): List<Pair<Long, Double>> {
        var acc = 0.0; val out = arrayListOf((historial.firstOrNull()?.t ?: ahora()) to acc)
        for (o in historial) if (o.salida > 0) { acc += o.pnl; out.add(o.t to acc) }
        return out
    }

    // ── Persistencia ──
    fun aJson(): String = JSONObject().apply {
        put("spot", spot); put("fut", fut); put("neto", netoDepositado); put("sig", sigId)
        put("mov", JSONArray().apply { movimientos.takeLast(500).forEach { m -> put(JSONObject().put("t", m.t).put("ti", m.tipo).put("c", m.cuenta.name)
            .put("i", m.importe).put("n", m.nota)) } })
        put("pos", JSONArray().apply { posiciones.forEach { p -> put(JSONObject().put("id", p.id).put("c", p.clave).put("a", p.activo).put("s", p.simbolo)
            .put("l", p.long).put("e", p.entrada).put("q", p.qty).put("m", p.margen).put("x", p.apal).put("tp", p.tp).put("sl", p.sl)
            .put("t", p.abierta).put("f", p.funding).put("uf", p.ultFunding)) } })
        put("ten", JSONArray().apply { tenencias.forEach { h -> put(JSONObject().put("c", h.clave).put("a", h.activo).put("s", h.simbolo).put("q", h.qty).put("k", h.coste)) } })
        put("ord", JSONArray().apply { ordenes.forEach { o -> put(JSONObject().put("id", o.id).put("mk", o.mercado.name).put("c", o.clave).put("a", o.activo)
            .put("s", o.simbolo).put("b", o.compra).put("p", o.precio).put("i", o.importe).put("q", o.qty).put("x", o.apal).put("tp", o.tp)
            .put("sl", o.sl).put("t", o.creada).put("r", o.reservado).put("cm", costeRes[o.id] ?: 0.0)) } })
        put("his", JSONArray().apply { historial.takeLast(500).forEach { h -> put(JSONObject().put("t", h.t).put("mk", h.mercado.name).put("s", h.simbolo)
            .put("ld", h.lado).put("e", h.entrada).put("x", h.salida).put("q", h.qty).put("p", h.pnl).put("c", h.comision).put("m", h.motivo)) } })
        put("pre", JSONObject().apply { precios.forEach { (k, v) -> put(k, v) } })
        put("rev", JSONObject().apply { revisado.forEach { (k, v) -> put(k, v) } })
    }.toString()

    fun cargar(s: String?) {
        reiniciar(); if (s.isNullOrEmpty()) return
        try {
            val o = JSONObject(s)
            movimientos.clear()
            if (o.has("fut")) { spot = o.getDouble("spot"); fut = o.getDouble("fut"); netoDepositado = o.optDouble("neto", 2 * BIENVENIDA) }
            else {   // versión anterior: un solo saldo → cuenta de Futuros
                spot = 0.0; fut = o.optDouble("saldo", BIENVENIDA); netoDepositado = BIENVENIDA
                movimientos.add(Movimiento(ahora(), "Deposit", Mercado.FUT, BIENVENIDA, "Migrated test balance"))
            }
            sigId = o.optLong("sig", 1)
            o.optJSONArray("mov")?.let { a -> for (i in 0 until a.length()) { val m = a.getJSONObject(i)
                movimientos.add(Movimiento(m.getLong("t"), m.getString("ti"), Mercado.valueOf(m.getString("c")), m.getDouble("i"), m.optString("n"))) } }
            o.optJSONArray("pos")?.let { a -> for (i in 0 until a.length()) { val p = a.getJSONObject(i)
                posiciones.add(Posicion(p.getLong("id"), p.getString("c"), p.optString("a"), p.getString("s"), p.getBoolean("l"), p.getDouble("e"),
                    p.getDouble("q"), p.getDouble("m"), p.getInt("x"), p.optDouble("tp", 0.0), p.optDouble("sl", 0.0), p.getLong("t"),
                    p.optDouble("f", 0.0), p.optLong("uf", p.getLong("t")))) } }
            o.optJSONArray("ten")?.let { a -> for (i in 0 until a.length()) { val h = a.getJSONObject(i)
                tenencias.add(Tenencia(h.getString("c"), h.optString("a"), h.getString("s"), h.getDouble("q"), h.getDouble("k"))) } }
            o.optJSONArray("ord")?.let { a -> for (i in 0 until a.length()) { val r = a.getJSONObject(i)
                val ord = Orden(r.getLong("id"), Mercado.valueOf(r.getString("mk")), r.getString("c"), r.optString("a"), r.getString("s"), r.getBoolean("b"),
                    r.getDouble("p"), r.getDouble("i"), r.getDouble("q"), r.getInt("x"), r.optDouble("tp", 0.0), r.optDouble("sl", 0.0), r.getLong("t"), r.getDouble("r"))
                ordenes.add(ord); if (ord.mercado == Mercado.SPOT && !ord.compra) costeRes[ord.id] = r.optDouble("cm", 0.0) } }
            o.optJSONArray("his")?.let { a -> for (i in 0 until a.length()) { val h = a.getJSONObject(i)
                historial.add(Operacion(h.getLong("t"), Mercado.valueOf(h.getString("mk")), h.getString("s"), h.getString("ld"), h.getDouble("e"),
                    h.getDouble("x"), h.getDouble("q"), h.getDouble("p"), h.getDouble("c"), h.getString("m"))) } }
            o.optJSONObject("pre")?.let { p -> p.keys().forEach { k -> precios[k] = p.getDouble(k) } }
            o.optJSONObject("rev")?.let { p -> p.keys().forEach { k -> revisado[k] = p.getLong(k) } }
        } catch (e: Exception) { reiniciar() }
    }

    private fun ahora() = System.currentTimeMillis() / 1000
}

/** Formato de precio para avisos del simulador. */
internal fun fmtSim(x: Double): String = when { x == 0.0 -> "0"; Math.abs(x) >= 1 -> "%,.2f".format(x); else -> "%.6g".format(x) }
