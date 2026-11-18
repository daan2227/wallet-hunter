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
 *  - Spot: comisión 0,1 %. Órdenes límite, stop, stop-límite, trailing stop y OCO
 *    (TP/SL de una tenencia). Convert instantáneo con 0,2 % de diferencial.
 *  - Futuros: margen aislado o cruzado, modo one-way (las órdenes opuestas reducen
 *    la posición y las del mismo lado se suman) o hedge (long y short a la vez),
 *    1–125x, taker 0,05 %, maker 0,02 %, mantenimiento 0,5 %, funding 0,01 % cada
 *    8 h (00/08/16 UTC; los long pagan). Órdenes reduce-only. Liquidación aislada:
 *    se pierde el margen; cruzada: se pierde todo el saldo de Futuros.
 *  - TP/SL salen al precio fijado (taker); si en el mismo tramo se tocan TP y SL se
 *    asume SL (criterio conservador). Stop y trailing se ejecutan al disparo (taker).
 *  - Earn flexible: 5 % TAE sobre VUSD, abonado cada día a las 00:00 UTC.
 *  - Bots: DCA (compra periódica) y grid (compra/venta entre dos precios).
 */
class Simulador {

    enum class Mercado { SPOT, FUT }
    enum class TipoOrden { LIMIT, STOP, STOP_LIMIT, TRAIL }

    class Posicion(val id: Long, val clave: String, val activo: String, val simbolo: String, val long: Boolean,
                   var entrada: Double, var qty: Double, var margen: Double, var apal: Int,
                   var tp: Double, var sl: Double, val abierta: Long, var funding: Double = 0.0, var ultFunding: Long = abierta,
                   val cruzado: Boolean = false) {
        val nocional get() = entrada * qty
        fun pnl(precio: Double) = (precio - entrada) * qty * (if (long) 1 else -1)
        /** Margen aislado: se liquida cuando la pérdida deja el margen en el de mantenimiento. */
        val liquidacion: Double get() = (margen - MANT * nocional) / qty.coerceAtLeast(1e-18) * (if (long) -1 else 1) + entrada
    }

    class Tenencia(val clave: String, val activo: String, val simbolo: String, var qty: Double, var coste: Double)

    /**
     * Orden pendiente. Spot: compra con [importe] VUSD o venta de [qty]. Futuros:
     * [importe] = margen, o [qty] a reducir si [reduce]. [precio] es el límite;
     * [disparo] el precio de activación (stop); [callback] la distancia del trailing
     * (0,01 = 1 %) medida desde [extremo]. Las órdenes con el mismo [grupo] son OCO.
     */
    class Orden(val id: Long, val mercado: Mercado, val clave: String, val activo: String, val simbolo: String,
                val compra: Boolean, var precio: Double, val importe: Double, val qty: Double, val apal: Int,
                val tp: Double, val sl: Double, val creada: Long, var reservado: Double,
                var tipo: TipoOrden = TipoOrden.LIMIT, val disparo: Double = 0.0, val callback: Double = 0.0, var extremo: Double = 0.0,
                val grupo: Long = 0, val reduce: Boolean = false, val cruzado: Boolean = false)

    class Operacion(val t: Long, val mercado: Mercado, val simbolo: String, val lado: String, val entrada: Double,
                    val salida: Double, val qty: Double, val pnl: Double, val comision: Double, val motivo: String)

    /** Movimiento de la cuenta: Deposit, Withdraw, Transfer, Funding, Margin, Earn… [importe] con signo para [cuenta]. */
    class Movimiento(val t: Long, val tipo: String, val cuenta: Mercado, val importe: Double, val nota: String)

    /** Bot de prueba. DCA: compra [importe] cada [cadaSeg]. Grid: [n] celdas entre [lo] y [hi]. */
    class Bot(val id: Long, val tipo: String, val clave: String, val activo: String, val simbolo: String, val creado: Long,
              val importe: Double = 0.0, val cadaSeg: Long = 0, var proximo: Long = 0, var compras: Int = 0, var gastado: Double = 0.0,
              val lo: Double = 0.0, val hi: Double = 0.0, val n: Int = 0, val inversion: Double = 0.0,
              var caja: Double = 0.0, var base: Double = 0.0, val conBase: BooleanArray = BooleanArray(0), val qtyCelda: DoubleArray = DoubleArray(0),
              var beneficio: Double = 0.0, var operaciones: Int = 0) {
        fun nivel(i: Int) = lo + (hi - lo) * i / n
        fun valor(precio: Double) = caja + base * precio
    }

    /** VUSD libres en cada cuenta. */
    var spot = BIENVENIDA; private set
    var fut = BIENVENIDA; private set
    /** Depósitos menos retiros: base para medir el rendimiento. */
    var netoDepositado = 2 * BIENVENIDA; private set
    /** Modo hedge (long y short del mismo par a la vez); si no, one-way. */
    var hedge = false; private set
    var earn = 0.0; private set
    var earnIntereses = 0.0; private set
    private var earnDia = 0L
    val movimientos = ArrayList<Movimiento>()
    val posiciones = ArrayList<Posicion>()
    val tenencias = ArrayList<Tenencia>()
    val ordenes = ArrayList<Orden>()
    val historial = ArrayList<Operacion>()
    val bots = ArrayList<Bot>()
    /** Último precio visto por activo (clave → precio). */
    val precios = HashMap<String, Double>()
    /** Último instante (s) procesado por activo, para no reprocesar velas. */
    val revisado = HashMap<String, Long>()
    private var sigId = 1L
    private val costeRes = HashMap<Long, Double>()   // id de orden de venta spot → coste medio reservado

    /** Avisos de lo que ha pasado (llenados, TP, SL, liquidaciones, bots); la UI los vacía. */
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
        const val APR_EARN = 0.05
        const val SPREAD_CONVERT = 0.002

        fun liquidacionDe(entrada: Double, apal: Int, long: Boolean): Double =
            if (long) entrada * (1 - 1.0 / apal + MANT) else entrada * (1 + 1.0 / apal - MANT)

        fun deJson(s: String?): Simulador = Simulador().apply { cargar(s) }
    }

    private fun nuevoId() = sigId++
    private fun dir(long: Boolean) = if (long) 1 else -1

    // ── Valoración ──
    fun precio(clave: String) = precios[clave] ?: 0.0
    fun pnlNoRealizado() = posiciones.sumOf { p -> precios[p.clave]?.let { p.pnl(it) } ?: 0.0 }
    fun valorSpot() = tenencias.sumOf { it.qty * (precios[it.clave] ?: (it.coste / it.qty.coerceAtLeast(1e-18))) }
    fun valorBots() = bots.filter { it.tipo == "GRID" }.sumOf { it.valor(precios[it.clave] ?: 0.0) }
    /** Patrimonio: saldos + reservado en órdenes + márgenes + PnL abierto + spot + Earn + bots. */
    fun patrimonio() = spot + fut + earn + ordenes.sumOf { o ->
        if (o.mercado == Mercado.SPOT && !o.compra) o.reservado * (precios[o.clave] ?: o.precio) else o.reservado } +
            posiciones.sumOf { it.margen } + pnlNoRealizado() + valorSpot() + valorBots()

    fun rendimiento() = patrimonio() - netoDepositado
    fun reservado(m: Mercado) = ordenes.filter { it.mercado == m && (m == Mercado.FUT || it.compra) }.sumOf { it.reservado }

    /** Precio de liquidación: aislado con su margen; cruzado con todo el saldo de Futuros. */
    fun liquidacion(p: Posicion): Double {
        if (!p.cruzado) return p.liquidacion
        val cruz = posiciones.filter { it.cruzado }
        val e = fut + cruz.sumOf { it.margen + it.pnl(precios[it.clave] ?: it.entrada) }
        val m = cruz.sumOf { MANT * it.nocional }
        val pc = precios[p.clave] ?: p.entrada
        return (pc + (m - e) / (dir(p.long) * p.qty.coerceAtLeast(1e-18))).coerceAtLeast(0.0)
    }

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

    fun ponerHedge(h: Boolean): String? {
        if (h == hedge) return null
        if (posiciones.isNotEmpty() || ordenes.any { it.mercado == Mercado.FUT }) return "Close all futures positions and orders before changing the position mode"
        hedge = h; return null
    }

    // ── Spot ──
    private fun anadirTenencia(clave: String, activo: String, simbolo: String, qty: Double, coste: Double) {
        val h = tenencias.firstOrNull { it.clave == clave }
        if (h == null) tenencias.add(Tenencia(clave, activo, simbolo, qty, coste)) else { h.qty += qty; h.coste += coste }
    }

    /** Compra con [usdt] VUSD. [prefinanciado]: el dinero ya salió de Spot (orden pendiente). */
    fun comprarSpot(clave: String, activo: String, simbolo: String, usdt: Double, precio: Double, maker: Boolean = false, t: Long = ahora(),
                    prefinanciado: Boolean = maker, motivo: String? = null): String? {
        if (usdt <= 0 || precio <= 0) return "Enter an amount"
        if (!prefinanciado && usdt < MIN_ORDEN) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
        if (!prefinanciado && usdt > spot + 1e-9) return "Not enough $MONEDA in Spot"
        if (!prefinanciado) spot -= usdt
        val qty = usdt / precio * (1 - COM_SPOT)
        anadirTenencia(clave, activo, simbolo, qty, usdt)
        historial.add(Operacion(t, Mercado.SPOT, simbolo, "Buy", precio, 0.0, qty, 0.0, usdt * COM_SPOT, motivo ?: if (maker) "Limit" else "Market"))
        precios[clave] = precios[clave] ?: precio
        return null
    }

    /** Vende [qty]; con [costeReservado] la cantidad ya se apartó de la tenencia (orden pendiente) a ese coste medio. */
    fun venderSpot(clave: String, qty: Double, precio: Double, motivo: String = "Market", t: Long = ahora(), costeReservado: Double? = null): String? {
        val yaReservado = costeReservado != null
        val h = tenencias.firstOrNull { it.clave == clave } ?: return "Nothing to sell"
        if (qty <= 0 || precio <= 0) return "Enter an amount"
        if (!yaReservado && qty > h.qty * (1 + 1e-9)) return "Not enough ${h.simbolo}"
        if (!yaReservado && qty * precio < MIN_ORDEN && qty < h.qty * (1 - 1e-9)) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
        val q = if (yaReservado) qty else minOf(qty, h.qty)
        val bruto = q * precio; val com = bruto * COM_SPOT
        val costeMedio = costeReservado ?: (h.coste / h.qty)
        val coste = costeMedio * q
        if (!yaReservado) { h.coste -= coste; h.qty -= q }
        spot += bruto - com
        historial.add(Operacion(t, Mercado.SPOT, h.simbolo, "Sell", costeMedio, precio, q, bruto - com - coste, com, motivo))
        limpiarTenencia(clave)
        return null
    }
    private fun limpiarTenencia(clave: String) {
        val h = tenencias.firstOrNull { it.clave == clave } ?: return
        if (h.qty <= 1e-12 && ordenes.none { it.clave == clave && it.mercado == Mercado.SPOT && !it.compra }) tenencias.remove(h)
    }

    /**
     * Convert instantáneo (sin libro): de VUSD o de una tenencia a otra cripto o a
     * VUSD, a precio de mercado con [SPREAD_CONVERT] de diferencial y sin comisión.
     * [desde]/[hacia] = null es VUSD. [cantidad] en unidades de lo que se entrega.
     */
    fun convertir(desde: String?, cantidad: Double, pDesde: Double, hacia: Triple<String, String, String>?, pHacia: Double, t: Long = ahora()): String? {
        if (cantidad <= 0 || cantidad.isNaN()) return "Enter an amount"
        if (desde == null && hacia == null) return "Choose two different assets"
        if (desde != null && desde == hacia?.first) return "Choose two different assets"
        if ((desde != null && pDesde <= 0) || (hacia != null && pHacia <= 0)) return "Price not available"
        val valor = if (desde == null) cantidad else cantidad * pDesde
        if (valor < MIN_ORDEN) return "Minimum convert is ${fmtSim(MIN_ORDEN)} $MONEDA"
        val recibe: Double = valor * (1 - SPREAD_CONVERT)
        var simDesde = MONEDA; var coste = cantidad
        if (desde == null) { if (cantidad > spot + 1e-9) return "Not enough $MONEDA in Spot"; spot -= minOf(cantidad, spot) }
        else {
            val h = tenencias.firstOrNull { it.clave == desde } ?: return "Nothing to convert"
            if (cantidad > h.qty * (1 + 1e-9)) return "Not enough ${h.simbolo}"
            val q = minOf(cantidad, h.qty); coste = h.coste / h.qty * q
            h.qty -= q; h.coste -= coste; simDesde = h.simbolo; precios[desde] = pDesde
        }
        if (hacia == null) {
            spot += recibe
            historial.add(Operacion(t, Mercado.SPOT, simDesde, "Convert to $MONEDA", coste / cantidad, pDesde, cantidad, recibe - coste, valor * SPREAD_CONVERT, "Convert"))
        } else {
            val q = recibe / pHacia
            anadirTenencia(hacia.first, hacia.second, hacia.third, q, if (desde == null) cantidad else coste)
            precios[hacia.first] = pHacia
            if (desde != null) historial.add(Operacion(t, Mercado.SPOT, simDesde, "Convert to ${hacia.third}", coste / cantidad, pDesde, cantidad,
                valor - coste, valor * SPREAD_CONVERT, "Convert"))
            historial.add(Operacion(t, Mercado.SPOT, hacia.third, "Convert from $simDesde", pHacia, 0.0, q, 0.0, valor * SPREAD_CONVERT, "Convert"))
        }
        if (desde != null) limpiarTenencia(desde)
        return null
    }

    // ── Futuros ──
    /**
     * Abre (o, en one-way, suma o reduce) una posición. [maker] decide la comisión;
     * [prefinanciado] indica que margen y comisión ya salieron de Futuros (orden pendiente).
     */
    fun abrirFuturo(clave: String, activo: String, simbolo: String, long: Boolean, margen: Double, apal: Int, precio: Double,
                    tp: Double = 0.0, sl: Double = 0.0, maker: Boolean = false, t: Long = ahora(),
                    prefinanciado: Boolean = maker, cruzado: Boolean = false): String? {
        if (margen <= 0 || precio <= 0) return "Enter a margin amount"
        if (apal !in 1..125) return "Leverage must be 1–125x"
        val nocional = margen * apal; val com = nocional * (if (maker) COM_MAKER else COM_TAKER)
        if (!prefinanciado && nocional < MIN_ORDEN) return "Minimum position is ${fmtSim(MIN_ORDEN)} $MONEDA notional"
        if (!prefinanciado && margen + com > fut + 1e-9) return "Not enough $MONEDA in Futures (margin + fee)"
        val liq = liquidacionDe(precio, apal, long)
        if (tp > 0 && (if (long) tp <= precio else tp >= precio)) return "Take-profit must be ${if (long) "above" else "below"} entry"
        if (sl > 0 && (if (long) sl >= precio else sl <= precio)) return "Stop-loss must be ${if (long) "below" else "above"} entry"
        if (!cruzado && sl > 0 && (if (long) sl <= liq else sl >= liq)) return "Stop-loss is beyond the liquidation price"
        if (!prefinanciado) fut -= margen + com
        val qtyTotal = nocional / precio
        var qty = qtyTotal
        // One-way: una orden contraria primero reduce lo abierto en el otro lado.
        if (!hedge) for (op in posiciones.filter { it.clave == clave && it.long != long }.toList()) {
            if (qty <= 1e-12) break
            val q = minOf(qty, op.qty)
            cerrarFuturo(op.id, precio, "Reduced by opposite order", t, q / op.qty)
            qty -= q
        }
        val usado = (qty / qtyTotal).coerceIn(0.0, 1.0)
        if (usado < 1.0) fut += (margen + com) * (1 - usado)       // lo que solo redujo no usa margen ni paga apertura
        if (qty <= 1e-12) { precios[clave] = precios[clave] ?: precio; return null }
        val m = margen * usado; val c = com * usado
        val misma = if (hedge) null else posiciones.firstOrNull { it.clave == clave && it.long == long && it.cruzado == cruzado }
        if (misma != null) {
            misma.entrada = (misma.entrada * misma.qty + precio * qty) / (misma.qty + qty)
            misma.qty += qty; misma.margen += m
            misma.apal = Math.round(misma.nocional / misma.margen).toInt().coerceIn(1, 125)
            if (tp > 0) misma.tp = tp; if (sl > 0) misma.sl = sl
        } else posiciones.add(Posicion(nuevoId(), clave, activo, simbolo, long, precio, qty, m, apal, tp, sl, t, cruzado = cruzado))
        historial.add(Operacion(t, Mercado.FUT, simbolo, (if (long) "Open long " else "Open short ") + "${apal}x" + if (cruzado) " cross" else "",
            precio, 0.0, qty, 0.0, c, if (maker) "Limit" else "Market"))
        precios[clave] = precios[clave] ?: precio
        return null
    }

    /** Cierra la posición entera o una [fraccion] (0–1) a mercado. */
    fun cerrarFuturo(id: Long, precio: Double, motivo: String = "Close", t: Long = ahora(), fraccion: Double = 1.0) {
        val p = posiciones.firstOrNull { it.id == id } ?: return
        val lado = "${if (p.long) "Long" else "Short"} ${p.apal}x" + if (p.cruzado) " cross" else ""
        if (fraccion < 0.999 && motivo != "Liquidated") {
            val f = fraccion.coerceIn(1e-9, 0.999); val q = p.qty * f; val m = p.margen * f
            val pnl = p.pnl(precio) * f; val com = precio * q * COM_TAKER
            fut += (m + pnl - com).coerceAtLeast(0.0)
            p.qty -= q; p.margen -= m
            historial.add(Operacion(t, Mercado.FUT, p.simbolo, lado, p.entrada, precio, q, pnl - com,
                com, if (motivo == "Close") "Close ${Math.round(f * 100)}%" else motivo))
            return
        }
        posiciones.remove(p)
        if (motivo == "Liquidated") {
            historial.add(Operacion(t, Mercado.FUT, p.simbolo, lado, p.entrada, precio, p.qty, -p.margen + p.funding, 0.0, motivo))
            return
        }
        val pnl = p.pnl(precio); val com = precio * p.qty * COM_TAKER
        fut += (p.margen + pnl - com).coerceAtLeast(0.0)
        historial.add(Operacion(t, Mercado.FUT, p.simbolo, lado, p.entrada, precio, p.qty, pnl - com + p.funding, com, motivo))
    }

    /** Liquidación cruzada: se cierran todas las posiciones cruzadas y se pierde el saldo de Futuros. */
    private fun liquidarCruzado(clave: String, precio: Double, t: Long) {
        val cruz = posiciones.filter { it.cruzado }
        for (p in cruz) {
            posiciones.remove(p)
            val px = if (p.clave == clave) precio else precios[p.clave] ?: p.entrada
            historial.add(Operacion(t, Mercado.FUT, p.simbolo, "${if (p.long) "Long" else "Short"} ${p.apal}x cross", p.entrada, px, p.qty,
                -p.margen + p.funding, 0.0, "Liquidated"))
        }
        if (fut > 0) movimientos.add(Movimiento(t, "Liquidation", Mercado.FUT, -fut, "Cross margin liquidation"))
        fut = 0.0
    }

    /** Cierra hasta [qty] (0 = todo) de las posiciones long ([cierraLong]) o short de un par. */
    private fun reducirFuturo(clave: String, cierraLong: Boolean, qty: Double, precio: Double, motivo: String, t: Long): Boolean {
        val ps = posiciones.filter { it.clave == clave && it.long == cierraLong }
        if (ps.isEmpty()) return false
        var resto = if (qty <= 0) Double.MAX_VALUE else qty
        for (p in ps) { if (resto <= 1e-12) break; val q = minOf(resto, p.qty); cerrarFuturo(p.id, precio, motivo, t, q / p.qty); resto -= q }
        return true
    }

    /** Añade margen aislado desde la cuenta de Futuros (aleja la liquidación). */
    fun agregarMargen(id: Long, x: Double, t: Long = ahora()): String? {
        val p = posiciones.firstOrNull { it.id == id } ?: return "Position closed"
        if (p.cruzado) return "Cross positions already use the whole Futures balance"
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
        val liq = liquidacion(p)
        if (sl > 0 && (if (p.long) sl <= liq else sl >= liq)) return "Stop-loss is beyond the liquidation price"
        p.tp = tp; p.sl = sl; return null
    }

    // ── Órdenes pendientes ──
    fun ordenLimite(mercado: Mercado, clave: String, activo: String, simbolo: String, compra: Boolean, precio: Double,
                    importe: Double, qty: Double, apal: Int, tp: Double, sl: Double, t: Long = ahora()): String? =
        nuevaOrden(mercado, clave, activo, simbolo, compra, TipoOrden.LIMIT, precio, 0.0, 0.0, importe, qty, apal, tp, sl, t = t)

    /**
     * Crea una orden pendiente de cualquier tipo. [ref] es el precio actual (para
     * validar stops y arrancar el trailing). Devuelve un error o null.
     */
    fun nuevaOrden(mercado: Mercado, clave: String, activo: String, simbolo: String, compra: Boolean, tipo: TipoOrden,
                   precio: Double, disparo: Double, callback: Double, importe: Double, qty: Double, apal: Int, tp: Double, sl: Double,
                   reduce: Boolean = false, cruzado: Boolean = false, ref: Double = precios[clave] ?: 0.0, t: Long = ahora()): String? {
        when (tipo) {
            TipoOrden.LIMIT -> if (precio <= 0) return "Enter a limit price"
            TipoOrden.STOP, TipoOrden.STOP_LIMIT -> {
                if (disparo <= 0) return "Enter a trigger price"
                if (tipo == TipoOrden.STOP_LIMIT && precio <= 0) return "Enter a limit price"
                if (ref > 0 && (if (compra) disparo <= ref else disparo >= ref))
                    return "A ${if (compra) "buy" else "sell"} stop must trigger ${if (compra) "above" else "below"} the current price"
            }
            TipoOrden.TRAIL -> {
                if (callback !in 0.001..0.2) return "Trailing callback must be 0.1%–20%"
                if (ref <= 0) return "Price not loaded yet"
            }
        }
        val id = nuevoId()
        val reservado: Double
        when {
            mercado == Mercado.SPOT && compra -> {
                if (importe <= 0) return "Enter an amount"; if (importe < MIN_ORDEN) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
                if (importe > spot + 1e-9) return "Not enough $MONEDA in Spot"
                spot -= importe; reservado = importe
            }
            mercado == Mercado.SPOT -> {
                val h = tenencias.firstOrNull { it.clave == clave } ?: return "Nothing to sell"
                if (qty <= 0) return "Enter an amount"; if (qty > h.qty * (1 + 1e-9)) return "Not enough ${h.simbolo}"
                val pRef = if (precio > 0) precio else if (disparo > 0) disparo else ref
                if (qty * pRef < MIN_ORDEN) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
                val q = minOf(qty, h.qty); val cm = h.coste / h.qty
                h.qty -= q; h.coste -= cm * q; costeRes[id] = cm; reservado = q
            }
            reduce -> {
                if (posiciones.none { it.clave == clave && it.long == !compra }) return "No ${if (compra) "short" else "long"} position to reduce"
                reservado = 0.0
            }
            else -> {
                if (importe <= 0) return "Enter a margin amount"; if (apal !in 1..125) return "Leverage must be 1–125x"
                if (importe * apal < MIN_ORDEN) return "Minimum position is ${fmtSim(MIN_ORDEN)} $MONEDA notional"
                if (tipo == TipoOrden.LIMIT) {
                    val liq = liquidacionDe(precio, apal, compra)
                    if (tp > 0 && (if (compra) tp <= precio else tp >= precio)) return "Take-profit must be ${if (compra) "above" else "below"} the limit price"
                    if (sl > 0 && (if (compra) sl >= precio else sl <= precio)) return "Stop-loss must be ${if (compra) "below" else "above"} the limit price"
                    if (!cruzado && sl > 0 && (if (compra) sl <= liq else sl >= liq)) return "Stop-loss is beyond the liquidation price"
                }
                val res = importe + importe * apal * (if (tipo == TipoOrden.LIMIT) COM_MAKER else COM_TAKER)
                if (res > fut + 1e-9) return "Not enough $MONEDA in Futures (margin + fee)"
                fut -= res; reservado = res
            }
        }
        ordenes.add(Orden(id, mercado, clave, activo, simbolo, compra, precio, importe, qty, apal, tp, sl, t, reservado,
            tipo, disparo, callback, if (tipo == TipoOrden.TRAIL) ref else 0.0, 0, reduce, cruzado))
        return null
    }

    /** TP/SL de una tenencia spot: una venta límite (TP) y una venta stop (SL) en OCO. */
    fun ocoSpot(clave: String, qty: Double, tp: Double, sl: Double, t: Long = ahora()): String? {
        val h = tenencias.firstOrNull { it.clave == clave } ?: return "Nothing to protect"
        val ref = precios[clave] ?: 0.0
        if (tp <= 0 && sl <= 0) return "Enter a take-profit and/or a stop-loss"
        if (ref > 0 && tp > 0 && tp <= ref) return "Take-profit must be above the current price"
        if (ref > 0 && sl > 0 && sl >= ref) return "Stop-loss must be below the current price"
        if (qty <= 0) return "Enter an amount"; if (qty > h.qty * (1 + 1e-9)) return "Not enough ${h.simbolo}"
        val q = minOf(qty, h.qty)
        if (q * (if (sl > 0) sl else tp) < MIN_ORDEN) return "Minimum order is ${fmtSim(MIN_ORDEN)} $MONEDA"
        val cm = h.coste / h.qty; h.qty -= q; h.coste -= cm * q
        val idA = nuevoId(); costeRes[idA] = cm
        val grupo = if (tp > 0 && sl > 0) idA else 0L
        if (tp > 0) ordenes.add(Orden(idA, Mercado.SPOT, clave, h.activo, h.simbolo, false, tp, 0.0, q, 1, 0.0, 0.0, t, q, TipoOrden.LIMIT, grupo = grupo))
        if (sl > 0) {
            val idB = if (tp > 0) nuevoId() else idA
            ordenes.add(Orden(idB, Mercado.SPOT, clave, h.activo, h.simbolo, false, 0.0, 0.0, q, 1, 0.0, 0.0, t, if (tp > 0) 0.0 else q,
                TipoOrden.STOP, disparo = sl, grupo = grupo))
        }
        return null
    }

    private fun quitarGrupo(o: Orden): List<Orden> {
        val g = if (o.grupo != 0L) ordenes.filter { it.grupo == o.grupo } else listOf(o)
        ordenes.removeAll(g.toSet()); return g
    }

    fun cancelarOrden(id: Long) {
        val o = ordenes.firstOrNull { it.id == id } ?: return
        val g = quitarGrupo(o)
        val res = g.sumOf { it.reservado }
        if (o.mercado == Mercado.SPOT && !o.compra) {
            val cm = g.firstNotNullOfOrNull { costeRes.remove(it.id) } ?: 0.0
            g.forEach { costeRes.remove(it.id) }
            anadirTenencia(o.clave, o.activo, o.simbolo, res, cm * res)
        } else if (o.mercado == Mercado.SPOT) spot += res else fut += res
    }

    private fun nombreOrden(o: Orden) = (when (o.tipo) { TipoOrden.LIMIT -> "Limit"; TipoOrden.STOP -> "Stop"; TipoOrden.STOP_LIMIT -> "Stop-limit"; TipoOrden.TRAIL -> "Trailing stop" }) +
        " " + (if (o.mercado == Mercado.FUT) (if (o.reduce) "reduce " else "") + (if (o.compra) "long" else "short") else (if (o.compra) "buy" else "sell"))

    private fun llenar(o: Orden, px: Double, maker: Boolean, t: Long) {
        val g = quitarGrupo(o)
        val motivo = when (o.tipo) { TipoOrden.LIMIT -> if (o.grupo != 0L) "Take-profit (OCO)" else "Limit"; TipoOrden.STOP -> if (o.grupo != 0L) "Stop-loss (OCO)" else "Stop"
            TipoOrden.STOP_LIMIT -> "Stop-limit"; TipoOrden.TRAIL -> "Trailing stop" }
        when {
            o.mercado == Mercado.SPOT && o.compra -> comprarSpot(o.clave, o.activo, o.simbolo, o.reservado, px, maker, t, prefinanciado = true, motivo = motivo)
            o.mercado == Mercado.SPOT -> {
                val total = g.sumOf { it.reservado }
                val cm = g.firstNotNullOfOrNull { costeRes.remove(it.id) } ?: 0.0
                g.forEach { costeRes.remove(it.id) }
                if (tenencias.none { it.clave == o.clave }) tenencias.add(Tenencia(o.clave, o.activo, o.simbolo, 0.0, 0.0))
                venderSpot(o.clave, total, px, motivo, t, cm)
            }
            o.reduce -> if (!reducirFuturo(o.clave, !o.compra, o.qty, px, motivo, t)) eventos.add("${nombreOrden(o)} ${o.simbolo}: no position left to reduce")
            else -> {
                val com = o.importe * o.apal * (if (maker) COM_MAKER else COM_TAKER)
                fut += (o.reservado - o.importe - com).coerceAtLeast(0.0)
                abrirFuturo(o.clave, o.activo, o.simbolo, o.compra, o.importe, o.apal, px, o.tp, o.sl, maker, t, prefinanciado = true, cruzado = o.cruzado)
            }
        }
        eventos.add("${nombreOrden(o)} ${o.simbolo} filled at ${fmtSim(px)}")
    }

    // ── Earn flexible ──
    fun suscribirEarn(x: Double, t: Long = ahora()): String? {
        if (x <= 0) return "Enter an amount"; if (x > spot + 1e-9) return "Only ${fmtSim(spot)} $MONEDA in Spot"
        devengar(t); if (earn <= 0) earnDia = t / 86_400
        val q = minOf(x, spot); spot -= q; earn += q
        movimientos.add(Movimiento(t, "Earn", Mercado.SPOT, -q, "Subscribed to Flexible Earn")); return null
    }
    fun redimirEarn(x: Double, t: Long = ahora()): String? {
        devengar(t)
        if (x <= 0) return "Enter an amount"; if (x > earn + 1e-9) return "Only ${fmtSim(earn)} $MONEDA in Earn"
        val q = minOf(x, earn); earn -= q; spot += q
        movimientos.add(Movimiento(t, "Earn", Mercado.SPOT, q, "Redeemed from Flexible Earn")); return null
    }
    /** Abona el interés de cada día completo (00:00 UTC) desde el último abono. */
    fun devengar(t: Long = ahora()): Boolean {
        val hoy = t / 86_400
        if (earn <= 0) { earnDia = hoy; return false }
        var cambio = false
        while (earnDia < hoy) {
            earnDia++
            val i = earn * APR_EARN / 365; earn += i; earnIntereses += i; cambio = true
            movimientos.add(Movimiento(earnDia * 86_400, "Earn interest", Mercado.SPOT, i, "Flexible Earn, ${"%.1f".format(APR_EARN * 100)}% APR"))
        }
        return cambio
    }
    /** Interés estimado acumulado hoy (se abona a las 00:00 UTC). */
    fun earnHoy(t: Long = ahora()) = earn * APR_EARN / 365 * ((t % 86_400) / 86_400.0)

    // ── Bots ──
    fun crearDca(clave: String, activo: String, simbolo: String, importe: Double, cadaSeg: Long, t: Long = ahora()): String? {
        if (importe < MIN_ORDEN) return "Minimum ${fmtSim(MIN_ORDEN)} $MONEDA per buy"
        if (cadaSeg < 3600) return "Interval must be at least 1 hour"
        bots.add(Bot(nuevoId(), "DCA", clave, activo, simbolo, t, importe, cadaSeg, proximo = t)); return null
    }

    fun crearGrid(clave: String, activo: String, simbolo: String, lo: Double, hi: Double, n: Int, inversion: Double, precio: Double, t: Long = ahora()): String? {
        if (lo <= 0 || hi <= lo) return "The upper price must be above the lower price"
        if (n !in 2..100) return "Grids must be 2–100"
        if (precio <= 0) return "Price not loaded yet"
        if (inversion / n < MIN_ORDEN) return "At least ${fmtSim(MIN_ORDEN)} $MONEDA per grid (${fmtSim(MIN_ORDEN * n)} total)"
        if (inversion > spot + 1e-9) return "Not enough $MONEDA in Spot"
        if ((hi - lo) / n / lo <= COM_SPOT * 2) return "Grid too dense: each step must be wider than the fees (0.2%)"
        spot -= inversion
        val b = Bot(nuevoId(), "GRID", clave, activo, simbolo, t, lo = lo, hi = hi, n = n, inversion = inversion, caja = inversion,
            conBase = BooleanArray(n), qtyCelda = DoubleArray(n))
        val por = inversion / n
        for (i in 0 until n) if (precio < b.nivel(i + 1)) {   // celdas por encima del precio: se compra ya para venderlas al subir
            val q = por / precio * (1 - COM_SPOT); b.caja -= por; b.base += q; b.conBase[i] = true; b.qtyCelda[i] = q; b.operaciones++
        }
        bots.add(b); precios[clave] = precio
        return null
    }

    /** Para un bot. Grid: vende lo que tenga a [precio] y devuelve todo a Spot. */
    fun pararBot(id: Long, precio: Double, t: Long = ahora()) {
        val b = bots.firstOrNull { it.id == id } ?: return
        bots.remove(b)
        if (b.tipo == "GRID") {
            val venta = b.base * precio * (1 - COM_SPOT); val total = b.caja + venta
            spot += total
            historial.add(Operacion(t, Mercado.SPOT, b.simbolo, "Grid bot stopped", b.inversion, total, b.base, total - b.inversion, b.base * precio * COM_SPOT, "Grid bot"))
        }
    }

    private fun procesarBots(clave: String, lo: Double, hi: Double, ultimo: Double, t: Long, inicio: Long): Boolean {
        var cambio = false
        for (b in bots.filter { it.clave == clave && it.creado <= inicio }) {
            if (b.tipo == "DCA") {
                if (t < b.proximo) continue
                if (spot + 1e-9 >= b.importe) {
                    comprarSpot(b.clave, b.activo, b.simbolo, b.importe, ultimo, t = t, motivo = "DCA bot")
                    b.compras++; b.gastado += b.importe; eventos.add("DCA bot bought ${fmtSim(b.importe)} $MONEDA of ${b.simbolo}")
                } else eventos.add("DCA bot ${b.simbolo}: not enough $MONEDA in Spot, buy skipped")
                b.proximo = t - (t - b.proximo) % b.cadaSeg + b.cadaSeg
                cambio = true
            } else {
                val por = b.inversion / b.n
                for (i in 0 until b.n) {
                    if (!b.conBase[i] && lo <= b.nivel(i) && b.caja + 1e-9 >= por) {
                        val q = por / b.nivel(i) * (1 - COM_SPOT); b.caja -= por; b.base += q; b.conBase[i] = true; b.qtyCelda[i] = q
                        b.operaciones++; cambio = true
                    } else if (b.conBase[i] && hi >= b.nivel(i + 1)) {
                        val q = b.qtyCelda[i]; val ingreso = q * b.nivel(i + 1) * (1 - COM_SPOT)
                        b.caja += ingreso; b.base -= q; b.conBase[i] = false; b.qtyCelda[i] = 0.0
                        b.beneficio += ingreso - por; b.operaciones++; cambio = true
                        historial.add(Operacion(t, Mercado.SPOT, b.simbolo, "Grid sell", b.nivel(i), b.nivel(i + 1), q, ingreso - por, q * b.nivel(i + 1) * COM_SPOT, "Grid bot"))
                    }
                }
            }
        }
        return cambio
    }

    /**
     * Procesa un tramo de precio de un activo: mínimo [lo], máximo [hi] y último
     * [ultimo], ocurrido hasta el instante [t] (s). Llena órdenes, dispara stops,
     * TP/SL, liquida, cobra funding, mueve los bots y abona Earn. [inicio] es cuándo
     * empezó el tramo: lo creado después no se evalúa con él. Devuelve true si algo cambió.
     */
    fun procesar(clave: String, lo: Double, hi: Double, ultimo: Double, t: Long = ahora(), inicio: Long = t): Boolean {
        if (ultimo <= 0) return false
        precios[clave] = ultimo
        var cambio = devengar(t)
        for (o in ordenes.filter { it.clave == clave && it.creada <= inicio }) {
            if (o !in ordenes) continue
            when (o.tipo) {
                TipoOrden.LIMIT -> if (if (o.compra) lo <= o.precio else hi >= o.precio) { llenar(o, o.precio, true, t); cambio = true }
                TipoOrden.STOP -> if (if (o.compra) hi >= o.disparo else lo <= o.disparo) { llenar(o, o.disparo, false, t); cambio = true }
                TipoOrden.STOP_LIMIT -> if (if (o.compra) hi >= o.disparo else lo <= o.disparo) {
                    o.tipo = TipoOrden.LIMIT; cambio = true
                    eventos.add("Stop-limit ${o.simbolo} triggered at ${fmtSim(o.disparo)}: limit order at ${fmtSim(o.precio)} placed")
                }
                TipoOrden.TRAIL -> {
                    val ext = if (o.compra) minOf(o.extremo, lo) else maxOf(o.extremo, hi)
                    if (ext != o.extremo) { o.extremo = ext; cambio = true }
                    val disp = if (o.compra) ext * (1 + o.callback) else ext * (1 - o.callback)
                    if (if (o.compra) hi >= disp else lo <= disp) { llenar(o, disp, false, t); cambio = true }
                }
            }
        }
        for (p in posiciones.filter { it.clave == clave && it.abierta <= inicio }) {
            if (p !in posiciones) continue
            var f = (p.ultFunding / FUNDING_SEG + 1) * FUNDING_SEG
            while (f <= t) { val pago = p.nocional * FUNDING * (if (p.long) -1 else 1); p.funding += pago; fut += pago; p.ultFunding = f
                movimientos.add(Movimiento(f, "Funding", Mercado.FUT, pago, "${if (p.long) "Long" else "Short"} ${p.simbolo} #${p.id}")); f += FUNDING_SEG }
            val liq = liquidacion(p)
            val slToca = p.sl > 0 && (if (p.long) lo <= p.sl else hi >= p.sl)
            val liqToca = if (p.long) lo <= liq else hi >= liq
            val tpToca = p.tp > 0 && (if (p.long) hi >= p.tp else lo <= p.tp)
            val lado = "${if (p.long) "Long" else "Short"} ${p.simbolo}"
            when {
                slToca && (if (p.long) p.sl > liq else p.sl < liq) -> { cerrarFuturo(p.id, p.sl, "Stop-loss", t); eventos.add("$lado: stop-loss hit at ${fmtSim(p.sl)}") }
                liqToca && p.cruzado -> { liquidarCruzado(clave, liq, t); eventos.add("CROSS MARGIN LIQUIDATED ($lado at ${fmtSim(liq)}): all cross positions closed") }
                liqToca -> { cerrarFuturo(p.id, liq, "Liquidated", t); eventos.add("$lado LIQUIDATED at ${fmtSim(liq)}") }
                tpToca -> { cerrarFuturo(p.id, p.tp, "Take-profit", t); eventos.add("$lado: take-profit hit at ${fmtSim(p.tp)}") }
                else -> continue
            }
            cambio = true
        }
        if (procesarBots(clave, lo, hi, ultimo, t, inicio)) cambio = true
        revisado[clave] = maxOf(revisado[clave] ?: 0L, t)
        return cambio
    }

    fun clavesActivas(): Set<String> = (posiciones.map { it.clave } + ordenes.map { it.clave } + bots.map { it.clave }).toSet()
    /** JSON del Activo guardado para una clave (para pedir sus precios aunque no esté en el gráfico). */
    fun activoDe(clave: String): String? = posiciones.firstOrNull { it.clave == clave }?.activo ?: ordenes.firstOrNull { it.clave == clave }?.activo
        ?: bots.firstOrNull { it.clave == clave }?.activo ?: tenencias.firstOrNull { it.clave == clave }?.activo

    /** Cuenta nueva: borra todo y vuelve a dar el depósito de bienvenida en Spot y Futuros. */
    fun reiniciar(t: Long = ahora()) {
        posiciones.clear(); tenencias.clear(); ordenes.clear(); historial.clear(); costeRes.clear(); eventos.clear(); revisado.clear()
        movimientos.clear(); bots.clear()
        spot = BIENVENIDA; fut = BIENVENIDA; netoDepositado = 2 * BIENVENIDA; earn = 0.0; earnIntereses = 0.0; earnDia = t / 86_400
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
        put("spot", spot); put("fut", fut); put("neto", netoDepositado); put("sig", sigId); put("hedge", hedge)
        put("earn", earn); put("earnI", earnIntereses); put("earnD", earnDia)
        put("mov", JSONArray().apply { movimientos.takeLast(500).forEach { m -> put(JSONObject().put("t", m.t).put("ti", m.tipo).put("c", m.cuenta.name)
            .put("i", m.importe).put("n", m.nota)) } })
        put("pos", JSONArray().apply { posiciones.forEach { p -> put(JSONObject().put("id", p.id).put("c", p.clave).put("a", p.activo).put("s", p.simbolo)
            .put("l", p.long).put("e", p.entrada).put("q", p.qty).put("m", p.margen).put("x", p.apal).put("tp", p.tp).put("sl", p.sl)
            .put("t", p.abierta).put("f", p.funding).put("uf", p.ultFunding).put("cr", p.cruzado)) } })
        put("ten", JSONArray().apply { tenencias.forEach { h -> put(JSONObject().put("c", h.clave).put("a", h.activo).put("s", h.simbolo).put("q", h.qty).put("k", h.coste)) } })
        put("ord", JSONArray().apply { ordenes.forEach { o -> put(JSONObject().put("id", o.id).put("mk", o.mercado.name).put("c", o.clave).put("a", o.activo)
            .put("s", o.simbolo).put("b", o.compra).put("p", o.precio).put("i", o.importe).put("q", o.qty).put("x", o.apal).put("tp", o.tp)
            .put("sl", o.sl).put("t", o.creada).put("r", o.reservado).put("cm", costeRes[o.id] ?: JSONObject.NULL)
            .put("ty", o.tipo.name).put("d", o.disparo).put("cb", o.callback).put("ex", o.extremo).put("g", o.grupo).put("ro", o.reduce).put("cr", o.cruzado)) } })
        put("bots", JSONArray().apply { bots.forEach { b -> put(JSONObject().put("id", b.id).put("ty", b.tipo).put("c", b.clave).put("a", b.activo)
            .put("s", b.simbolo).put("t", b.creado).put("i", b.importe).put("cada", b.cadaSeg).put("prox", b.proximo).put("nc", b.compras)
            .put("gas", b.gastado).put("lo", b.lo).put("hi", b.hi).put("n", b.n).put("inv", b.inversion).put("caja", b.caja).put("base", b.base)
            .put("cb", JSONArray().apply { b.conBase.forEach { put(it) } }).put("qc", JSONArray().apply { b.qtyCelda.forEach { put(it) } })
            .put("ben", b.beneficio).put("ops", b.operaciones)) } })
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
            sigId = o.optLong("sig", 1); hedge = o.optBoolean("hedge", false)
            earn = o.optDouble("earn", 0.0); earnIntereses = o.optDouble("earnI", 0.0); earnDia = o.optLong("earnD", ahora() / 86_400)
            o.optJSONArray("mov")?.let { a -> for (i in 0 until a.length()) { val m = a.getJSONObject(i)
                movimientos.add(Movimiento(m.getLong("t"), m.getString("ti"), Mercado.valueOf(m.getString("c")), m.getDouble("i"), m.optString("n"))) } }
            o.optJSONArray("pos")?.let { a -> for (i in 0 until a.length()) { val p = a.getJSONObject(i)
                posiciones.add(Posicion(p.getLong("id"), p.getString("c"), p.optString("a"), p.getString("s"), p.getBoolean("l"), p.getDouble("e"),
                    p.getDouble("q"), p.getDouble("m"), p.getInt("x"), p.optDouble("tp", 0.0), p.optDouble("sl", 0.0), p.getLong("t"),
                    p.optDouble("f", 0.0), p.optLong("uf", p.getLong("t")), p.optBoolean("cr", false))) } }
            o.optJSONArray("ten")?.let { a -> for (i in 0 until a.length()) { val h = a.getJSONObject(i)
                tenencias.add(Tenencia(h.getString("c"), h.optString("a"), h.getString("s"), h.getDouble("q"), h.getDouble("k"))) } }
            o.optJSONArray("ord")?.let { a -> for (i in 0 until a.length()) { val r = a.getJSONObject(i)
                val ord = Orden(r.getLong("id"), Mercado.valueOf(r.getString("mk")), r.getString("c"), r.optString("a"), r.getString("s"), r.getBoolean("b"),
                    r.getDouble("p"), r.getDouble("i"), r.getDouble("q"), r.getInt("x"), r.optDouble("tp", 0.0), r.optDouble("sl", 0.0), r.getLong("t"), r.getDouble("r"),
                    TipoOrden.valueOf(r.optString("ty", "LIMIT")), r.optDouble("d", 0.0), r.optDouble("cb", 0.0), r.optDouble("ex", 0.0),
                    r.optLong("g", 0), r.optBoolean("ro", false), r.optBoolean("cr", false))
                ordenes.add(ord); if (ord.mercado == Mercado.SPOT && !ord.compra && r.has("cm") && !r.isNull("cm")) costeRes[ord.id] = r.optDouble("cm", 0.0) } }
            o.optJSONArray("bots")?.let { a -> for (i in 0 until a.length()) { val b = a.getJSONObject(i)
                val cb = b.optJSONArray("cb"); val qc = b.optJSONArray("qc")
                bots.add(Bot(b.getLong("id"), b.getString("ty"), b.getString("c"), b.optString("a"), b.getString("s"), b.getLong("t"),
                    b.optDouble("i", 0.0), b.optLong("cada", 0), b.optLong("prox", 0), b.optInt("nc", 0), b.optDouble("gas", 0.0),
                    b.optDouble("lo", 0.0), b.optDouble("hi", 0.0), b.optInt("n", 0), b.optDouble("inv", 0.0), b.optDouble("caja", 0.0), b.optDouble("base", 0.0),
                    BooleanArray(cb?.length() ?: 0) { cb!!.getBoolean(it) }, DoubleArray(qc?.length() ?: 0) { qc!!.getDouble(it) },
                    b.optDouble("ben", 0.0), b.optInt("ops", 0))) } }
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
