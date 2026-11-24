package com.hunter.btc

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Simulador de trading bajo el gráfico del Home: Spot y Futuros con VUSD (moneda virtual de pruebas)
 * y el precio real del activo abierto. Toda la lógica está en [Simulador]; aquí
 * solo la interfaz, el guardado y la vigilancia de precios de los activos con
 * posiciones u órdenes abiertas (aunque no estén en el gráfico).
 */
class PanelTrading(private val act: MainActivity, private val activo: () -> Mercado.Activo,
                   private val precioActual: () -> Double?, private val chart: GraficoTA) {

    private val prefs = act.getSharedPreferences("simulador", Context.MODE_PRIVATE)
    val sim: Simulador = Simulador.deJson(prefs.getString("estado", null))
    private fun dp(v: Int) = act.dp(v)

    private var futuros = prefs.getBoolean("futuros", true)
    private var compra = true
    /** 0 market, 1 limit, 2 stop, 3 stop-limit, 4 trailing stop. */
    private var tipoUi = 0
    private val limite get() = tipoUi != 0
    private var cruzado = prefs.getBoolean("cruzado", false)
    private var soloReducir = false
    private var apal = prefs.getInt("apal", 10).coerceIn(1, 125)
    private var conTpSl = false

    val vista = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    // ── piezas ──
    private fun texto(sp: Float, color: Int, negrita: Boolean = false) = TextView(act).apply {
        textSize = sp; setTextColor(color); typeface = if (negrita) AppTheme.bold(act) else AppTheme.body(act)
    }
    private fun chip(t: String, alPulsar: () -> Unit) = TextView(act).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; typeface = AppTheme.medium(act); minHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0); isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { alPulsar() }
    }
    /** Píldora: elegida rellena #1E1E20 y blanca; el resto con contorno y gris. */
    private fun marcarChip(c: TextView, on: Boolean) {
        c.background = if (on) DexTema.fondo(act, DexTema.CHIP, 100f) else DexTema.fondo(act, 0, 100f, DexTema.STROKE)
        c.setTextColor(if (on) DexTema.TXT else DexTema.TXT2); c.typeface = if (on) AppTheme.bold(act) else AppTheme.medium(act)
        c.isSelected = on
    }
    private fun campo(hint: String) = DexTema.campo(act, hint, true).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }
    private fun EditText.num(): Double = text.toString().replace(",", "").replace(" ", "").toDoubleOrNull() ?: 0.0
    private fun fila(vararg v: View) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; v.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    private fun titulo(t: String) = texto(16f, DexTema.TXT, true).apply {
        text = t; setPadding(dp(2), dp(18), 0, dp(10))
    }
    private fun usd(x: Double) = fmtSim(x)
    private fun signo(x: Double) = (if (x >= 0) "+" else "") + fmtSim(x)
    private fun aviso(t: String) = Toast.makeText(act, t, Toast.LENGTH_SHORT).show()
    private fun guardar() = prefs.edit().putString("estado", sim.aJson()).apply()
    /** Confirmación antes de acciones que no se deshacen (cerrar, vender todo, parar un bot). */
    private fun confirmar(titulo: String, msg: String, ok: String, accion: () -> Unit) =
        DexTema.dialogo(act).setTitle(titulo).setMessage(msg).setPositiveButton(ok) { _, _ -> accion() }.setNegativeButton("Cancel", null).show()

    // ── formulario (columna izquierda) ──
    private val segLS = SegmentoLargoCorto(act).apply { diagonal = false }
    private fun botonCaja(t: String) = TextView(act).apply {
        text = t; textSize = 13f; setTextColor(DexTema.TXT); typeface = AppTheme.medium(act); gravity = Gravity.CENTER; maxLines = 1
        minHeight = dp(48); background = DexTema.fondo(act, DexTema.CHIP, 6f); isClickable = true; foreground = Ui.toque()
    }
    private val btnMargen = botonCaja("Isolated"); private val btnApal = botonCaja("10x"); private val btnPos = botonCaja("One-way")
    private val filaModo = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val tvTipo = TextView(act).apply {
        textSize = 16f; setTextColor(DexTema.TXT); typeface = AppTheme.medium(act); gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48)
        isClickable = true; foreground = Ui.toque(); contentDescription = "Order type"
    }
    private val btnBbo = TextView(act).apply {
        text = "BBO"; textSize = 15f; setTextColor(DexTema.TXT); typeface = AppTheme.bold(act); gravity = Gravity.CENTER
        minHeight = dp(48); minWidth = dp(52); isClickable = true; foreground = Ui.toque(); contentDescription = "Best bid or offer"
    }
    private val parPrecio = DexTema.campoEtiquetado(act, "Price (USD)", btnBbo)
    private val etPrecio = parPrecio.second
    private val parDisparo = DexTema.campoEtiquetado(act, "Trigger price (USD)")
    private val etDisparo = parDisparo.second
    private val parCallback = DexTema.campoEtiquetado(act, "Callback rate (%)")
    private val etCallback = parCallback.second
    private val tvUnidad = TextView(act).apply { textSize = 15f; setTextColor(DexTema.TXT); typeface = AppTheme.medium(act) }
    private val parImporte = DexTema.campoEtiquetado(act, "Amount", tvUnidad)
    private val etImporte = parImporte.second
    private val slider = SliderPorcentaje(act)
    private val swReducir = DexTema.casilla(act, "Reduce-only", false) { }
    private val swTpSl = DexTema.casilla(act, "TP/SL", false) { }
    private val parTp = DexTema.campoEtiquetado(act, "Take-profit (USD)")
    private val etTp = parTp.second
    private val parSl = DexTema.campoEtiquetado(act, "Stop-loss (USD)")
    private val etSl = parSl.second
    private val tvDisponible = texto(14f, DexTema.TXT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; compoundDrawablePadding = dp(6) }
    private val tvMax = texto(14f, DexTema.TXT); private val tvCoste = texto(14f, DexTema.TXT)
    private val tvResumen = texto(12f, DexTema.TXT2).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.25f) }
    private val btnAccion = TextView(act).apply {
        textSize = 17f; typeface = AppTheme.bold(act); gravity = Gravity.CENTER; minHeight = dp(50)
        isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    // ── listas (debajo): Positions / Open orders / Assets / Bots ──
    val vistaListas = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private var tabLista = 0
    private val contPos = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
    private lateinit var rotularTabs: (List<String>) -> Unit

    /** Libro completo sin agrupar (lo pone el Home): las órdenes a mercado lo recorren. */
    var libroActual: (() -> Mercado.Libro?)? = null
    /** Vigencia de las órdenes límite: 0 GTC, 1 Post-only, 2 IOC, 3 FOK. */
    private var tif = 0
    private val tvTif = TextView(act).apply {
        textSize = 14f; setTextColor(DexTema.TXT); gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); isClickable = true; foreground = Ui.toque()
        setPadding(dp(8), 0, 0, 0); compoundDrawablePadding = dp(2)
    }

    /**
     * Simula el llenado contra el libro: [compra] consume asks (si no, bids) hasta
     * [cantidad] (en VUSD de nocional o en unidades), sin pasar de [limite]. Devuelve
     * (precio medio, fracción llenada). Sin libro, todo al precio actual. Si el libro
     * visible se agota sin límite, el resto va al último nivel con un 0,1 % extra.
     */
    private fun recorrer(compra: Boolean, cantidad: Double, porNocional: Boolean, limite: Double?): Pair<Double, Double>? {
        val ref = precioActual() ?: return null
        if (cantidad <= 0) return null
        val l = libroActual?.invoke()
        val niveles = when { l == null || (if (compra) l.asks else l.bids).isEmpty() -> listOf(ref to Double.MAX_VALUE / 4); compra -> l.asks; else -> l.bids }
        var resto = cantidad; var noc = 0.0; var qty = 0.0; var ultimoP = ref
        for ((p, q) in niveles) {
            if (limite != null && (if (compra) p > limite else p < limite)) break
            val disp = if (porNocional) p * q else q
            val toma = minOf(resto, disp)
            if (porNocional) { noc += toma; qty += toma / p } else { qty += toma; noc += toma * p }
            resto -= toma; ultimoP = p
            if (resto <= cantidad * 1e-9) { resto = 0.0; break }
        }
        if (resto > 0 && limite == null) {
            val p = ultimoP * (if (compra) 1.001 else 0.999)
            if (porNocional) { noc += resto; qty += resto / p } else { qty += resto; noc += resto * p }
            resto = 0.0
        }
        if (qty <= 0) return null
        return noc / qty to (1 - resto / cantidad)
    }
    /** Cantidad que mueve la orden (nocional en VUSD o unidades para ventas spot). */
    private fun cantidadOrden(imp: Double): Pair<Double, Boolean> = when {
        futuros -> imp * apal to true
        compra -> imp to true
        else -> imp to false
    }

    /** Mejor bid/ask del libro para el botón BBO (lo pone el Home). */
    var bbo: (() -> Pair<Double?, Double?>)? = null


    private fun filaDato(k: String, v: TextView) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(28)
        addView(texto(14f, DexTema.TXT2).apply { text = k; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        addView(v)
    }

    private fun construir() {
        // Comprar / Vender
        segLS.alCambiar = { l -> compra = l; refrescarFormulario() }
        vista.addView(segLS.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })
        // Isolated · 10x · One-way
        listOf(btnMargen, btnApal, btnPos).forEachIndexed { k, b ->
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (k > 0) marginStart = dp(6) }; filaModo.addView(b) }
        btnMargen.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, btnMargen)
            pm.menu.add(0, 0, 0, "Isolated · loss limited to the position margin"); pm.menu.add(0, 1, 1, "Cross · uses the whole Futures balance")
            pm.setOnMenuItemClickListener { cruzado = it.itemId == 1; prefs.edit().putBoolean("cruzado", cruzado).apply(); refrescarFormulario(); true }; pm.show()
        }
        btnApal.setOnClickListener { dialogoApal() }
        btnPos.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, btnPos)
            pm.menu.add(0, 0, 0, "One-way · opposite orders reduce"); pm.menu.add(0, 1, 1, "Hedge · long and short at once")
            pm.setOnMenuItemClickListener { cambiarModoPos(it.itemId == 1); true }; pm.show()
        }
        vista.addView(filaModo.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
        // tipo de orden: caja desplegable con ⓘ
        val nombres = listOf("Market", "Limit", "Stop market", "Stop limit", "Trailing stop")
        tvTipo.setCompoundDrawables(null, null, act.getDrawable(R.drawable.ic_caret_down)?.mutate()?.apply { setTint(DexTema.TXT2); setBounds(0, 0, dp(18), dp(18)) }, null)
        tvTipo.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, tvTipo)
            nombres.forEachIndexed { k, t -> pm.menu.add(0, k, k, t) }
            pm.setOnMenuItemClickListener { tipoUi = it.itemId; refrescarFormulario(); true }; pm.show()
        }
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = DexTema.fondo(act, DexTema.CHIP, 6f)
            setPadding(dp(12), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            tvTipo.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(tvTipo)
            addView(android.widget.ImageView(act).apply {
                setImageResource(R.drawable.ic_info); setColorFilter(DexTema.TXT2); scaleType = android.widget.ImageView.ScaleType.CENTER
                contentDescription = "About order types"; isClickable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { infoTipos() }
            })
        })
        vista.addView(parDisparo.first); vista.addView(parPrecio.first); vista.addView(parCallback.first)
        tvTif.setCompoundDrawables(null, null, act.getDrawable(R.drawable.ic_caret_down)?.mutate()?.apply { setTint(DexTema.TXT2); setBounds(0, 0, dp(16), dp(16)) }, null)
        tvTif.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, tvTif)
            listOf("GTC · good till cancelled", "Post-only · only adds liquidity", "IOC · fill what you can now, cancel the rest", "FOK · fill all now or nothing")
                .forEachIndexed { k, t -> pm.menu.add(0, k, k, t) }
            pm.setOnMenuItemClickListener { tif = it.itemId; refrescarFormulario(); true }; pm.show()
        }
        btnBbo.setOnClickListener {
            val (b, a) = bbo?.invoke() ?: (null to null)
            val p = if (compra) b else a
            if (p == null) aviso("Order book not loaded yet") else { if (tipoUi != 1 && tipoUi != 3) tipoUi = 1
                etPrecio.setText(if (p >= 1) "%.2f".format(java.util.Locale.US, p) else "%.8f".format(java.util.Locale.US, p).trimEnd('0').trimEnd('.')); refrescarFormulario() }
        }
        vista.addView(parImporte.first)
        slider.alCambiar = { p -> porcentaje(p) }
        vista.addView(slider)
        // disponible ⊕
        tvDisponible.setCompoundDrawables(null, null, act.getDrawable(R.drawable.ic_plus_circle)?.mutate()?.apply { setTint(DexTema.ACCENT); setBounds(0, 0, dp(22), dp(22)) }, null)
        vista.addView(filaDato("Available", tvDisponible).apply { minimumHeight = dp(44); isClickable = true; foreground = Ui.toque()
            contentDescription = "Available funds. Transfer or deposit"; setOnClickListener { dialogoFondos(2) } })
        swTpSl.setOnCheckedChangeListener { _, b -> conTpSl = b; refrescarFormulario() }
        swReducir.setOnCheckedChangeListener { _, b -> soloReducir = b; refrescarFormulario() }
        vista.addView(swTpSl); vista.addView(parTp.first); vista.addView(parSl.first)
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            swReducir.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(swReducir)
            addView(tvTif)
        })
        vista.addView(filaDato("Max", tvMax)); vista.addView(filaDato("Cost", tvCoste))
        btnAccion.setOnClickListener { ejecutar() }
        vista.addView(btnAccion)
        vista.addView(tvResumen)
        construirScalp(); vista.addView(cajaScalp, 0)
        val vigila = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { listOf(etPrecio, etImporte, etTp, etSl, etDisparo, etCallback).forEach { it.error = null }; resumen() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(etPrecio, etImporte, etTp, etSl, etDisparo, etCallback).forEach { it.addTextChangedListener(vigila) }

        // pestañas de listas
        val (filaTabs, rot) = DexTema.pestanasSubrayado(act, listOf("Positions", "Open orders", "Assets", "Bots", "History"), 0) { k -> tabLista = k; refrescar() }
        rotularTabs = rot
        vistaListas.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(filaTabs) })
        vistaListas.addView(View(act).apply { setBackgroundColor(DexTema.STROKE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)) })
        vistaListas.addView(contPos)
        refrescarFormulario()
    }

    private fun infoTipos() {
        DexTema.dialogo(act).setTitle("Order types").setMessage(
            "Market: fills now at the current price (taker fee).\n\n" +
            "Limit: waits until the price reaches yours (maker fee). Buy below / sell above the market.\n\n" +
            "Stop market: when the price touches the trigger, it executes at market.\n\n" +
            "Stop limit: when the price touches the trigger, a limit order is placed.\n\n" +
            "Trailing stop: follows the best price and executes when it turns back by the callback %.\n\n" +
            "Reduce-only: only closes a position. GTC: the order stays until filled or cancelled.")
            .setPositiveButton("OK", null).show()
    }

    /** Apalancamiento: valores rápidos o uno propio (1–125x). */
    private fun dialogoApal() {
        val caja = cajaDialogo()
        val et = DexTema.campo(act, "1–125", true).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(apal.toString()) }
        caja.addView(et)
        val filas = listOf(listOf(1, 2, 3, 5, 10), listOf(20, 25, 50, 75, 125))
        for (f in filas) caja.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
            f.forEachIndexed { k, x -> addView(TextView(act).apply {
                text = "${x}x"; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(48); setTextColor(DexTema.TXT)
                background = DexTema.fondo(act, DexTema.CHIP, 6f); isClickable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (k > 0) marginStart = dp(6) }
                setOnClickListener { et.setText(x.toString()) } }) }
        })
        caja.addView(texto(12f, DexTema.TXT2).apply { setPadding(0, dp(10), 0, 0)
            text = "Higher leverage means a closer liquidation price. Max position: ${fmtSim(sim.fut)} VUSD × leverage." })
        DexTema.dialogo(act).setTitle("Adjust leverage").setView(caja)
            .setPositiveButton("Confirm") { _, _ ->
                val x = et.text.toString().toIntOrNull()
                if (x == null || x !in 1..125) aviso("Leverage must be 1–125x") else { apal = x; prefs.edit().putInt("apal", apal).apply(); refrescarFormulario() }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Menú de la cuenta de prueba (lo abre el "⋯" de la cabecera). */
    fun menuCuenta(ancla: View) {
        val pm = androidx.appcompat.widget.PopupMenu(act, ancla)
        listOf("Deposit test VUSD", "Withdraw", "Transfer Spot ⇄ Futures", "Earn", "Convert", "Bots", "Futures calculator", "History & stats",
            if (scalpOn) "Scalp mode: on" else "Scalp mode: off")
            .forEachIndexed { k, t -> pm.menu.add(0, k, k, t) }
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 0 -> dialogoFondos(0); 1 -> dialogoFondos(1); 2 -> dialogoFondos(2); 3 -> dialogoEarn(); 4 -> dialogoConvert()
                5 -> dialogoBots(); 6 -> dialogoCalculadora(); 7 -> abrirHistorial(); else -> alternarScalp() }; true }
        pm.show()
    }

    /** 0 depositar, 1 retirar, 2 transferir (VUSD de prueba). */
    private fun dialogoFondos(tipo: Int) {
        var cuenta = if (futuros) Simulador.Mercado.FUT else Simulador.Mercado.SPOT
        val et = DexTema.campo(act, "Amount (VUSD)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val tvInfo = texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { setPadding(0, dp(6), 0, 0) }
        fun info() { tvInfo.text = when (tipo) {
            0 -> "Adds test VUSD to ${sim.nombre(cuenta)} (max ${fmtSim(Simulador.MAX_DEPOSITO)} per deposit). It is counted as a deposit, not as profit."
            1 -> "Removes VUSD from ${sim.nombre(cuenta)}: ${fmtSim(if (cuenta == Simulador.Mercado.SPOT) sim.spot else sim.fut)} available. Nothing is sent anywhere."
            else -> "From ${sim.nombre(cuenta)} (${fmtSim(if (cuenta == Simulador.Mercado.SPOT) sim.spot else sim.fut)} available) to " +
                    sim.nombre(if (cuenta == Simulador.Mercado.SPOT) Simulador.Mercado.FUT else Simulador.Mercado.SPOT)
        } }
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        caja.addView(DexTema.pestanas(act, if (tipo == 2) listOf("Spot → Futures" to null, "Futures → Spot" to null) else listOf("Spot" to null, "Futures" to null),
            if (cuenta == Simulador.Mercado.SPOT) 0 else 1) { k -> cuenta = if (k == 0) Simulador.Mercado.SPOT else Simulador.Mercado.FUT; info() }
            .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })
        caja.addView(et)
        val rapidos = if (tipo == 0) listOf("1,000" to 1_000.0, "10,000" to 10_000.0, "100,000" to 100_000.0) else listOf("25%" to -25.0, "50%" to -50.0, "Max" to -100.0)
        caja.addView(fila(*rapidos.map { (t, v) -> chip(t) {
            val x = if (v >= 0) v else (if (cuenta == Simulador.Mercado.SPOT) sim.spot else sim.fut) * -v / 100
            et.setText("%.2f".format(java.util.Locale.US, Math.floor(x * 100) / 100))
        }.apply { marcarChip(this, false) } }.toTypedArray()).apply { setPadding(0, dp(4), 0, 0) })
        caja.addView(tvInfo); info()
        DexTema.dialogo(act).setTitle(listOf("Deposit test VUSD", "Withdraw VUSD", "Transfer VUSD")[tipo])
            .setView(caja)
            .setPositiveButton(listOf("Deposit", "Withdraw", "Transfer")[tipo]) { _, _ ->
                val x = et.num()
                val err = when (tipo) { 0 -> sim.depositar(cuenta, x); 1 -> sim.retirar(cuenta, x); else -> sim.transferir(cuenta, x) }
                if (err != null) aviso(err) else { guardar(); refrescar(); aviso("Done") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pedirMargen(p: Simulador.Posicion) {
        val et = DexTema.campo(act, "VUSD from Futures (${fmtSim(sim.fut)} available)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        DexTema.dialogo(act).setTitle("Add margin · #${p.id}")
            .setView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et)
                addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "Current liquidation: ${fmtSim(p.liquidacion)}. More margin moves it further away."; setPadding(0, dp(6), 0, 0) })
            })
            .setPositiveButton("Add") { _, _ -> val err = sim.agregarMargen(p.id, et.num()); if (err != null) aviso(err) else { guardar(); refrescar(); aviso("New liquidation: ${fmtSim(p.liquidacion)}") } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun numero(hint: String, valor: Double? = null) = DexTema.campo(act, hint, true).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
        if (valor != null && valor > 0) setText(if (valor >= 1) "%.2f".format(java.util.Locale.US, valor) else "%.8f".format(java.util.Locale.US, valor).trimEnd('0').trimEnd('.'))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }
    private fun cajaDialogo() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
    private fun alCambiar(vararg et: EditText, f: () -> Unit) = et.forEach { it.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) = f()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }) }
    /** Último precio de un activo (1 min); null si no responde. */
    private fun pedirPrecio(a: Mercado.Activo, cb: (Double?) -> Unit) = Thread {
        val v = try { Mercado.velas(a, "1m", 2) } catch (e: Throwable) { emptyList() }
        act.runOnUiThread { cb(v.lastOrNull()?.c?.toDouble()?.also { sim.precios[a.clave] = it }) }
    }.start()

    private fun cambiarModoPos(h: Boolean) {
        if (h == sim.hedge) return
        val err = sim.ponerHedge(h); if (err != null) { aviso(err); return }
        guardar(); refrescarFormulario()
        aviso(if (h) "Hedge mode: long and short of the same pair can be open at once" else "One-way mode: opposite orders reduce your position")
    }

    /** TP/SL de una tenencia spot: venta límite + venta stop en OCO. */
    private fun ocoTenencia(h: Simulador.Tenencia) {
        val caja = cajaDialogo()
        val etQ = numero("Quantity (${h.simbolo})", h.qty); val etT = numero("Take-profit price (sell limit)"); val etS = numero("Stop-loss price (sell stop)")
        caja.addView(etQ); caja.addView(etT); caja.addView(etS)
        caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
            text = "OCO: whichever hits first sells the quantity and cancels the other. Current price: ${precioDe(h.clave)?.let { fmtSim(it) } ?: "—"}"
        })
        DexTema.dialogo(act).setTitle("TP / SL · ${h.simbolo}").setView(caja)
            .setPositiveButton("Place") { _, _ ->
                precioDe(h.clave)?.let { sim.precios[h.clave] = it }
                val err = sim.ocoSpot(h.clave, etQ.num(), etT.num(), etS.num())
                if (err != null) aviso(err) else { guardar(); refrescar(); aviso("TP/SL placed") }
            }.setNegativeButton("Cancel", null).show()
    }

    // ── Earn ──
    private fun dialogoEarn() {
        sim.devengar()
        val caja = cajaDialogo()
        caja.addView(texto(AppTheme.SP_BODY, DexTema.TXT, true).apply { text = "Flexible Earn · ${"%.1f".format(Simulador.APR_EARN * 100)}% APR on VUSD" })
        caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
            text = "Interest is credited every day at 00:00 UTC and compounds. Redeem any time to Spot. Test interest in VUSD, not real yield."
            setPadding(0, dp(4), 0, dp(8))
        })
        caja.addView(dato("In Earn", fmtSim(sim.earn) + " VUSD"))
        caja.addView(dato("Accruing today (est.)", "+" + fmtSim(sim.earnHoy()), DexTema.UP))
        caja.addView(dato("Total interest earned", "+" + fmtSim(sim.earnIntereses), DexTema.UP))
        caja.addView(dato("Daily at current balance", "+" + fmtSim(sim.earn * Simulador.APR_EARN / 365), DexTema.UP))
        caja.addView(dato("Available in Spot", fmtSim(sim.spot) + " VUSD"))
        var redimir = false
        val et = numero("Amount (VUSD)")
        caja.addView(DexTema.pestanas(act, listOf("Subscribe" to null, "Redeem" to null), 0) { k -> redimir = k == 1 }
            .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(8) } })
        caja.addView(et)
        caja.addView(fila(chip("Max") { val x = if (redimir) sim.earn else sim.spot
            et.setText("%.2f".format(java.util.Locale.US, Math.floor(x * 100) / 100)) }.apply { marcarChip(this, false) }))
        DexTema.dialogo(act).setTitle("Earn").setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Confirm") { _, _ ->
                val err = if (redimir) sim.redimirEarn(et.num()) else sim.suscribirEarn(et.num())
                if (err != null) aviso(err) else { guardar(); refrescar(); aviso(if (redimir) "Redeemed to Spot" else "Subscribed") }
            }.setNegativeButton("Close", null).show()
    }

    // ── Convert ──
    private fun dialogoConvert() {
        val caja = cajaDialogo()
        // origen: VUSD o una tenencia; destino: VUSD o cualquier par (selector)
        var desde: Simulador.Tenencia? = null
        var hacia: Mercado.Activo? = activo().takeIf { it.tipo == Mercado.Tipo.CRYPTO }
        val tvDesde = texto(AppTheme.SP_BODY, DexTema.TXT, true); val tvHacia = texto(AppTheme.SP_BODY, DexTema.TXT, true)
        val et = numero("Amount")
        val tvCot = texto(AppTheme.SP_CAPTION, DexTema.TXT2).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.25f) }
        fun pDe(clave: String?) = if (clave == null) 1.0 else sim.precios[clave]
        fun cotizar() {
            val pD = desde?.let { pDe(it.clave) } ?: 1.0; val pH = hacia?.let { pDe(it.clave) } ?: 1.0
            val q = et.num()
            tvDesde.text = "From  ${desde?.simbolo ?: "VUSD"}  ▾   (available ${fmtCant(desde?.qty ?: sim.spot)})"
            tvHacia.text = "To  ${hacia?.simbolo ?: "VUSD"}  ▾"
            et.hint = "Amount (${desde?.simbolo ?: "VUSD"})"
            tvCot.text = if (hacia != null && pDe(hacia?.clave) == null || desde != null && pDe(desde?.clave) == null) "Getting prices…"
                else if (q <= 0) "Rate: 1 ${desde?.simbolo ?: "VUSD"} ≈ ${fmtCant(pD / pH)} ${hacia?.simbolo ?: "VUSD"} · spread 0.2%, no fee"
                else "You receive ≈ ${fmtCant(q * pD / pH * (1 - Simulador.SPREAD_CONVERT))} ${hacia?.simbolo ?: "VUSD"}\nValue ≈ ${fmtSim(q * pD)} VUSD · spread 0.2%, no fee"
        }
        fun precios() {
            desde?.let { h -> Mercado.Activo.deJson(h.activo)?.let { a -> pedirPrecio(a) { cotizar() } } }
            hacia?.let { a -> pedirPrecio(a) { cotizar() } }
        }
        tvDesde.apply { isClickable = true; foreground = Ui.toque(); setPadding(0, dp(8), 0, dp(8)) }.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, tvDesde)
            pm.menu.add(0, 0, 0, "VUSD (${fmtSim(sim.spot)})")
            val ts = sim.tenencias.filter { it.qty > 1e-12 }
            ts.forEachIndexed { k, h -> pm.menu.add(0, k + 1, k + 1, "${h.simbolo} (${fmtCant(h.qty)})") }
            pm.setOnMenuItemClickListener { desde = if (it.itemId == 0) null else ts[it.itemId - 1]; et.setText(""); cotizar(); precios(); true }
            pm.show()
        }
        tvHacia.apply { isClickable = true; foreground = Ui.toque(); setPadding(0, dp(8), 0, dp(8)) }.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, tvHacia)
            pm.menu.add(0, 0, 0, "VUSD"); pm.menu.add(0, 1, 1, "Choose a coin…")
            pm.setOnMenuItemClickListener {
                if (it.itemId == 0) { hacia = null; cotizar() } else act.abrirSelectorPar { a -> hacia = a; cotizar(); precios() }; true
            }
            pm.show()
        }
        caja.addView(tvDesde); caja.addView(tvHacia); caja.addView(et)
        caja.addView(fila(chip("Max") { et.setText((desde?.qty ?: sim.spot).let { if (desde == null) "%.2f".format(java.util.Locale.US, Math.floor(it * 100) / 100)
            else "%.8f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.') }) }.apply { marcarChip(this, false) }))
        caja.addView(tvCot)
        alCambiar(et) { cotizar() }
        cotizar(); precios()
        DexTema.dialogo(act).setTitle("Convert").setView(caja)
            .setPositiveButton("Convert") { _, _ ->
                val h = hacia
                val err = sim.convertir(desde?.clave, et.num(), desde?.let { pDe(it.clave) } ?: 0.0,
                    h?.let { Triple(it.clave, it.aJson(), it.simbolo) }, h?.let { pDe(it.clave) } ?: 0.0)
                if (err != null) aviso(err) else { guardar(); refrescar(); aviso("Converted") }
            }.setNegativeButton("Cancel", null).show()
    }

    // ── Bots ──
    private fun tarjetaBot(b: Simulador.Bot) = tarjeta().apply {
        val px = precioDe(b.clave)
        if (b.tipo == "IA") {
            val pos = sim.posiciones.firstOrNull { it.id == b.posId }
            val vivo = pos?.let { p -> px?.let { p.pnl(it) } } ?: 0.0
            val total = b.beneficio + vivo
            addView(cabeceraTarjeta("AI bot · ${b.simbolo} · ${b.iv}", DexTema.LINK, signo(total), if (total >= 0) DexTema.UP else DexTema.DOWN))
            addView(dato("Margin · max leverage · min confidence", "${fmtSim(b.importe)} VUSD · ${b.apalMax}x · ${b.umbral}%"))
            addView(dato("Closed trades · win rate", if (b.operaciones == 0) "0" else "${b.operaciones} · ${b.ganadas * 100 / b.operaciones}%"))
            addView(dato("Realized PnL", signo(b.beneficio), if (b.beneficio >= 0) DexTema.UP else DexTema.DOWN))
            addView(dato("Now", if (pos == null) (if (b.ultVela == 0L) "waiting for the first closed candle" else "flat, waiting for a signal")
                else "${if (pos.long) "Long" else "Short"} ${pos.apal}x from ${fmtSim(pos.entrada)} · ${signo(vivo)}", if (pos == null) DexTema.TXT2 else if (vivo >= 0) DexTema.UP else DexTema.DOWN))
        } else if (b.tipo == "DCA") {
            addView(cabeceraTarjeta("DCA bot · ${b.simbolo}", DexTema.LINK, "${fmtSim(b.importe)} VUSD / ${intervaloTxt(b.cadaSeg)}", DexTema.TXT))
            addView(dato("Buys done · invested", "${b.compras} · ${fmtSim(b.gastado)} VUSD"))
            addView(dato("Next buy", java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(b.proximo * 1000))))
        } else {
            val valor = px?.let { b.valor(it) }; val pnl = valor?.let { it - b.inversion } ?: 0.0
            addView(cabeceraTarjeta("Grid bot · ${b.simbolo}", DexTema.LINK, signo(pnl), if (pnl >= 0) DexTema.UP else DexTema.DOWN))
            addView(dato("Range · grids", "${fmtSim(b.lo)} – ${fmtSim(b.hi)} · ${b.n}"))
            addView(dato("Investment · value", "${fmtSim(b.inversion)} · ${valor?.let { fmtSim(it) } ?: "—"} VUSD"))
            addView(dato("Grid profit · trades", "${signo(b.beneficio)} · ${b.operaciones}", if (b.beneficio >= 0) DexTema.UP else DexTema.DOWN))
            if (px != null && (px < b.lo || px > b.hi)) addView(dato("Status", "price out of range", DexTema.WARN))
        }
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
            addView(botonPeq("Stop", DexTema.DOWN) {
                val p = precioDe(b.clave)
                if ((b.tipo == "GRID" || (b.tipo == "IA" && b.posId != 0L)) && p == null) { aviso("No price for ${b.simbolo} yet"); return@botonPeq }
                val nombre = when (b.tipo) { "GRID" -> "grid"; "IA" -> "AI"; else -> "DCA" }
                val detalle = when (b.tipo) {
                    "GRID" -> "The bot sells its ${b.simbolo} at market and returns everything to Spot."
                    "IA" -> "No more AI trades. An open bot position is closed at market."
                    else -> "No more periodic buys. Coins already bought stay in Spot." }
                confirmar("Stop $nombre bot", detalle, "Stop") {
                    sim.pararBot(b.id, precioDe(b.clave) ?: p ?: 0.0); notificar(); guardar(); refrescar()
                    aviso(when (b.tipo) { "GRID" -> "Grid stopped, funds back in Spot"; "IA" -> "AI bot stopped"; else -> "DCA bot stopped" })
                }
            })
        })
    }
    private fun intervaloTxt(s: Long) = when { s % 604_800 == 0L -> "${s / 604_800}w"; s % 86_400 == 0L -> "${s / 86_400}d"; else -> "${s / 3600}h" }

    private fun dialogoBots() {
        val a = activo()
        val caja = cajaDialogo()
        caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
            text = "Test bots trade ${a.simbolo} with real prices: DCA and Grid on Spot, AI on Futures. They keep running in the background about every 15 minutes."
            setPadding(0, 0, 0, dp(10))
        })
        var tipo = 0
        val pDca = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val pGrid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val pIA = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val tvSaldo = texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "Available in Spot: ${fmtSim(sim.spot)} VUSD"; setPadding(0, dp(8), 0, 0) }
        caja.addView(DexTema.pestanas(act, listOf("DCA" to null, "Grid" to null, "AI" to null), 0) { k -> tipo = k
            pDca.visibility = if (k == 0) View.VISIBLE else View.GONE; pGrid.visibility = if (k == 1) View.VISIBLE else View.GONE
            pIA.visibility = if (k == 2) View.VISIBLE else View.GONE
            tvSaldo.text = if (k == 2) "Available in Futures: ${fmtSim(sim.fut)} VUSD" else "Available in Spot: ${fmtSim(sim.spot)} VUSD" }
            .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })
        // DCA
        val etDca = numero("VUSD per buy")
        var cada = 86_400L
        pDca.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "Buys ${a.simbolo} at market every interval, starting now." })
        pDca.addView(etDca)
        pDca.addView(DexTema.pestanas(act, listOf("1h" to null, "4h" to null, "1d" to null, "1w" to null), 2) { k -> cada = longArrayOf(3600, 14_400, 86_400, 604_800)[k] })
        // Grid
        val px = precioActual() ?: 0.0
        val etLo = numero("Lower price", px * 0.9); val etHi = numero("Upper price", px * 1.1)
        val etN = numero("Grids (2–100)", 10.0).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("10") }
        val etInv = numero("Investment (VUSD)")
        val tvG = texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { setPadding(0, dp(4), 0, 0) }
        fun infoGrid() {
            val lo = etLo.num(); val hi = etHi.num(); val n = etN.text.toString().toIntOrNull() ?: 0
            tvG.text = if (lo > 0 && hi > lo && n >= 2) "Step %s (%.2f%%) · profit per grid ≈ %.2f%% after fees · current %s"
                .format(fmtSim((hi - lo) / n), (hi - lo) / n / lo * 100, (hi - lo) / n / lo * 100 - 0.2, fmtSim(px)) else "Buys low and sells high inside the range."
        }
        pGrid.addView(etLo); pGrid.addView(etHi); pGrid.addView(etN); pGrid.addView(etInv); pGrid.addView(tvG)
        alCambiar(etLo, etHi, etN) { infoGrid() }; infoGrid()
        // IA
        var ivIA = "1h"
        val etMarIA = numero("Margin per trade (VUSD)")
        val etApIA = numero("Max leverage (1–20)").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("3") }
        val etUmIA = numero("Min confidence % (30–95)").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("60") }
        pIA.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "At each closed candle the on-device AI engine scores ${a.simbolo}. On a Long or Short signal with enough confidence it opens an isolated futures position with the plan's stop and first target, and flips on a strong opposite signal. It only uses chart data. Paper trading, not advice." })
        val tvModoIA = texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { setPadding(0, dp(4), 0, 0) }
        fun modoIA() { tvModoIA.text = if (BotIA.esScalp(ivIA)) "Scalping mode: EMA 9/21, session VWAP, 3-candle momentum and order-book imbalance; stop 1.2 ATR, target 1.5× the stop, stop moves to break-even at +1R. Decides every 20 s while the DEx is open."
            else "Swing mode: full on-device chart engine (trend, structure, RSI, MACD, patterns, levels)." }
        pIA.addView(DexTema.pestanas(act, listOf("1m" to null, "5m" to null, "15m" to null, "1h" to null, "4h" to null, "1d" to null), 3, padH = 10f) { k ->
            ivIA = listOf("1m", "5m", "15m", "1h", "4h", "1d")[k]; modoIA() })
        pIA.addView(tvModoIA); modoIA()
        pIA.addView(etMarIA); pIA.addView(etApIA); pIA.addView(etUmIA)
        caja.addView(pDca); caja.addView(pGrid); caja.addView(pIA)
        caja.addView(tvSaldo)
        DexTema.dialogo(act).setTitle("New bot · ${a.simbolo}").setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Start") { _, _ ->
                val err = if (tipo == 0) sim.crearDca(a.clave, a.aJson(), a.simbolo, etDca.num(), cada)
                    else if (tipo == 2) sim.crearBotIA(a.clave, a.aJson(), a.simbolo, ivIA, etMarIA.num(),
                        etApIA.text.toString().toIntOrNull() ?: 0, etUmIA.text.toString().toIntOrNull() ?: 0)
                    else sim.crearGrid(a.clave, a.aJson(), a.simbolo, etLo.num(), etHi.num(), etN.text.toString().toIntOrNull() ?: 0, etInv.num(), px)
                if (err != null) aviso(err) else {
                    if (tipo == 0) sim.procesar(a.clave, px, px, px)       // primera compra ya
                    notificar(); guardar(); refrescar(); aviso("Bot started")
                    if (tipo == 2) vigilar()                               // primera decisión ya
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ── Calculadora de futuros ──
    private fun dialogoCalculadora() {
        val caja = cajaDialogo()
        var largo = true
        val etApal = numero("Leverage (1–125)").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(apal.toString()) }
        val etEnt = numero("Entry price", precioActual()); val etSal = numero("Exit price")
        val etMar = numero("Margin (VUSD)"); val etRoe = numero("Target ROE % (optional)")
        val tv = texto(AppTheme.SP_CAPTION, DexTema.TXT).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.3f) }
        fun calcular() {
            val x = etApal.text.toString().toIntOrNull()?.coerceIn(1, 125) ?: 0
            val e = etEnt.num(); val sP = etSal.num(); val m = etMar.num(); val roe = etRoe.num()
            if (x <= 0 || e <= 0 || m <= 0) { tv.text = "Enter leverage, entry and margin."; return }
            val noc = m * x; val q = noc / e; val d = if (largo) 1 else -1
            val feeIn = noc * Simulador.COM_TAKER
            val sb = StringBuilder()
            sb.append("Size ${fmtCant(q)} · notional ${fmtSim(noc)} VUSD\n")
            sb.append("Liquidation (isolated) ${fmtSim(Simulador.liquidacionDe(e, x, largo))}\n")
            sb.append("Opening fee ${fmtSim(feeIn)} VUSD\n")
            if (sP > 0) {
                val pnl = (sP - e) * q * d; val fees = feeIn + sP * q * Simulador.COM_TAKER
                sb.append("PnL ${signo(pnl)} VUSD · ROE ${"%+.2f".format(pnl / m * 100)}%\n")
                sb.append("Net after fees ${signo(pnl - fees)} VUSD\n")
            }
            if (roe != 0.0) sb.append("Price for ${"%+.1f".format(roe)}% ROE: ${fmtSim(e * (1 + d * roe / 100 / x))}\n")
            sb.append("Max margin with ${fmtSim(sim.fut)} VUSD in Futures: ${fmtSim(sim.fut / (1 + x * Simulador.COM_TAKER))}")
            tv.text = sb.toString()
        }
        caja.addView(DexTema.pestanas(act, listOf("Long" to null, "Short" to null), 0) { k -> largo = k == 0; calcular() }
            .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })
        listOf(etApal, etEnt, etSal, etMar, etRoe).forEach { caja.addView(it) }
        caja.addView(tv)
        alCambiar(etApal, etEnt, etSal, etMar, etRoe) { calcular() }; calcular()
        DexTema.dialogo(act).setTitle("Futures calculator").setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Close", null).show()
    }

    /** Al volver a la app: recoge lo que la vigilancia en segundo plano haya cambiado. */
    fun recargar() { sim.cargar(prefs.getString("estado", null)); refrescar() }

    /** Spot o Futuros: lo eligen las pestañas de arriba del Home. */
    fun ponerModo(f: Boolean) {
        if (f == futuros) return
        futuros = f; prefs.edit().putBoolean("futuros", f).apply(); refrescarFormulario(); refrescar()
    }

    /** Precio elegido en el libro de órdenes: pasa a orden límite con ese precio. */
    /** Rellena el formulario con el plan del modo IA (lado, apalancamiento, TP y SL); el importe lo pone el usuario. */
    fun aplicarPlan(largo: Boolean, stop: Double, objetivo: Double, apalancamiento: Int) {
        fun f(x: Double) = if (x >= 1) "%.2f".format(java.util.Locale.US, x) else "%.8f".format(java.util.Locale.US, x).trimEnd('0').trimEnd('.')
        compra = largo; tipoUi = 0
        if (futuros) { apal = apalancamiento.coerceIn(1, 125); prefs.edit().putInt("apal", apal).apply() }
        swTpSl.isChecked = true; conTpSl = true
        etTp.setText(f(objetivo)); etSl.setText(f(stop))
        refrescarFormulario(); etImporte.requestFocus()
        aviso("Plan loaded: set the amount and review before sending")
    }

    fun ponerPrecioLimite(p: Double) {
        if (tipoUi != 1 && tipoUi != 3) tipoUi = 1
        etPrecio.setText(if (p >= 1) "%.2f".format(java.util.Locale.US, p) else "%.8f".format(java.util.Locale.US, p).trimEnd('0').trimEnd('.'))
        refrescarFormulario(); aviso("Limit price set to ${fmtSim(p)}")
    }

    /** El gráfico cambió de activo: rótulos del formulario y líneas. */
    fun alCambiarActivo() { refrescarFormulario(); refrescar() }

    private fun pedirApal() {
        val et = DexTema.campo(act, "1–125", true).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(apal.toString()) }
        DexTema.dialogo(act).setTitle("Leverage")
            .setView(LinearLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("Set") { _, _ ->
                val x = et.text.toString().toIntOrNull()
                if (x == null || x !in 1..125) aviso("Leverage must be 1–125x") else { apal = x; prefs.edit().putInt("apal", apal).apply(); refrescarFormulario() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun porcentaje(p: Int) {
        val a = activo()
        val v = when {
            futuros -> sim.fut / (1 + apal * Simulador.COM_TAKER) * p / 100
            compra -> sim.spot * p / 100
            else -> (sim.tenencias.firstOrNull { it.clave == a.clave }?.qty ?: 0.0) * p / 100
        }
        etImporte.setText(if (!futuros && !compra) "%.8f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.') else "%.2f".format(java.util.Locale.US, Math.floor(v * 100) / 100))
    }

    private fun refrescarFormulario() {
        pintarScalp()
        val a = activo()
        segLS.textos = "Buy" to "Sell"; segLS.poner(compra)
        btnMargen.text = if (cruzado) "Cross" else "Isolated"; btnApal.text = "${apal}x"; btnPos.text = if (sim.hedge) "Hedge" else "One-way"
        filaModo.visibility = if (futuros) View.VISIBLE else View.GONE
        tvTipo.text = listOf("Market", "Limit", "Stop market", "Stop limit", "Trailing stop")[tipoUi]
        parPrecio.first.visibility = if (tipoUi == 1 || tipoUi == 3) View.VISIBLE else View.GONE
        parDisparo.first.visibility = if (tipoUi == 2 || tipoUi == 3) View.VISIBLE else View.GONE
        parCallback.first.visibility = if (tipoUi == 4) View.VISIBLE else View.GONE
        etDisparo.hint = "Trigger price (USD) · ${if (compra) "above" else "below"} market"
        val reducir = futuros && soloReducir && tipoUi != 0
        swReducir.visibility = if (futuros && tipoUi != 0) View.VISIBLE else View.INVISIBLE
        tvTif.text = listOf("GTC", "Post-only", "IOC", "FOK")[tif]; tvTif.contentDescription = "Time in force: ${tvTif.text}"
        tvTif.visibility = if (tipoUi == 1) View.VISIBLE else View.GONE
        (swReducir.parent as? View)?.visibility = if (swReducir.visibility == View.VISIBLE || tvTif.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        tvUnidad.text = if (reducir || (!futuros && !compra)) a.simbolo else "VUSD"
        etImporte.hint = when { reducir -> "Quantity to close"; futuros -> "Margin"; compra -> "Amount"; else -> "Quantity" }
        // TP/SL: futuros al abrir; spot en compras a mercado (crea una OCO de venta)
        swTpSl.visibility = if ((futuros && !reducir) || (!futuros && compra && tipoUi == 0)) View.VISIBLE else View.GONE
        val tpsl = swTpSl.visibility == View.VISIBLE && conTpSl
        parTp.first.visibility = if (tpsl) View.VISIBLE else View.GONE; parSl.first.visibility = if (tpsl) View.VISIBLE else View.GONE
        btnAccion.text = when {
            reducir -> if (compra) "Close short" else "Close long"
            futuros -> if (compra) "Buy / Long" else "Sell / Short"
            else -> (if (compra) "Buy " else "Sell ") + a.simbolo
        }
        btnAccion.background = DexTema.fondo(act, if (compra) DexTema.UP else DexTema.DOWN, 8f)
        btnAccion.setTextColor(if (compra) DexTema.ON_UP else 0xFFFFFFFF.toInt())
        resumen()
    }

    /** Estimación de lo que hará la orden: tamaño, nocional, comisión, liquidación. */
    private fun resumen() {
        val a = activo()
        val act0 = precioActual() ?: 0.0
        // sin precio no se puede operar: botón desactivado y dicho, no un botón que no hace nada
        btnAccion.isEnabled = act0 > 0; btnAccion.alpha = if (act0 > 0) 1f else 0.45f
        btnAccion.contentDescription = if (act0 > 0) btnAccion.text else "${btnAccion.text}, unavailable until the price loads"
        val disp = if (futuros) sim.fut else if (compra) sim.spot else (sim.tenencias.firstOrNull { it.clave == a.clave }?.qty ?: 0.0)
        tvDisponible.text = if (!futuros && !compra) "${fmtCant(disp)} ${a.simbolo}" else "${fmtSim(disp)} VUSD"
        val impV = etImporte.num()
        val pRef = when (tipoUi) { 1, 3 -> etPrecio.num().takeIf { it > 0 } ?: act0; 2 -> etDisparo.num().takeIf { it > 0 } ?: act0; else -> act0 }
        // Máx: lo más grande que se puede abrir; Coste: lo que sale de la cuenta con esta orden
        tvMax.text = when {
            pRef <= 0 -> "--"
            futuros -> "${fmtCant(disp / (1 + apal * Simulador.COM_TAKER) * apal / pRef)} ${a.simbolo}"
            compra -> "${fmtCant(disp / pRef * (1 - Simulador.COM_SPOT))} ${a.simbolo}"
            else -> "${fmtCant(disp)} ${a.simbolo}" }
        tvCoste.text = when {
            impV <= 0 -> "0.00 VUSD"
            futuros && !(soloReducir && tipoUi != 0) -> fmtSim(impV + impV * apal * (if (tipoUi == 1) Simulador.COM_MAKER else Simulador.COM_TAKER)) + " VUSD"
            futuros -> "0.00 VUSD"
            compra -> fmtSim(impV) + " VUSD"
            else -> fmtCant(impV) + " " + a.simbolo }
        val p = when (tipoUi) { 1, 3 -> etPrecio.num(); 2 -> etDisparo.num(); else -> precioActual() ?: 0.0 }
        val imp = etImporte.num()
        if (p <= 0 || imp <= 0) {
            tvResumen.text = if (futuros) "Fees: taker 0.05% · maker 0.02% · funding 0.01% / 8h · min 5 VUSD" else "Fee: 0.1% · min order 5 VUSD"
            return
        }
        val desliz = if (tipoUi == 0 && act0 > 0) cantidadOrden(imp).let { (c, n) -> recorrer(compra, c, n, null)?.first }?.let { px ->
            "Est. fill ≈ %s (slippage %.3f%%)\n".format(fmtSim(px), Math.abs(px - act0) / act0 * 100) } ?: "" else ""
        tvResumen.text = desliz + when {
            futuros -> {
                val noc = imp * apal; val com = noc * if (limite) Simulador.COM_MAKER else Simulador.COM_TAKER
                if (soloReducir && tipoUi != 0) "Closes up to %s %s of the %s position".format(fmtCant(imp), a.simbolo, if (compra) "short" else "long")
                else "Size ≈ %s %s · notional %s VUSD\nFee ≈ %s VUSD · liquidation ≈ %s".format(fmtCant(noc / p), a.simbolo, usd(noc), usd(com),
                    if (cruzado) "uses whole Futures balance (cross)" else usd(Simulador.liquidacionDe(p, apal, compra)))
            }
            compra -> "You get ≈ %s %s · fee %s VUSD".format(fmtCant(imp / p * (1 - Simulador.COM_SPOT)), a.simbolo, usd(imp * Simulador.COM_SPOT))
            else -> "You get ≈ %s VUSD · fee %s VUSD".format(usd(imp * p * (1 - Simulador.COM_SPOT)), usd(imp * p * Simulador.COM_SPOT))
        }
    }
    private fun fmtCant(x: Double) = if (x >= 1000) "%,.2f".format(x) else "%.6g".format(x)

    /** Ejecuta ya (taker) [imp] al precio [px]: futuros, compra spot (con TP/SL en OCO) o venta spot. */
    private fun ejecutarMercado(a: Mercado.Activo, js: String, imp: Double, px: Double, tp: Double, sl: Double): String? = when {
        futuros -> sim.abrirFuturo(a.clave, js, a.simbolo, compra, imp, apal, px, tp, sl, cruzado = cruzado)
        compra -> {
            val antes = sim.tenencias.firstOrNull { it.clave == a.clave }?.qty ?: 0.0
            sim.comprarSpot(a.clave, js, a.simbolo, imp, px) ?: if (tp > 0 || sl > 0) {
                val q = (sim.tenencias.firstOrNull { it.clave == a.clave }?.qty ?: 0.0) - antes
                sim.ocoSpot(a.clave, q, tp, sl)?.let { "Bought, but TP/SL not set: $it" }
            } else null
        }
        else -> sim.venderSpot(a.clave, imp, px)
    }

    /** El error va en el campo que lo causa (y se anuncia); si no es de un campo, aviso. */
    private fun mostrarError(err: String) {
        val e = err.lowercase()
        val et = when {
            "take-profit" in e -> etTp; "stop-loss" in e -> etSl; "trigger" in e || " stop must" in e -> etDisparo
            "callback" in e -> etCallback; "limit price" in e || "limit buy" in e || "limit sell" in e -> etPrecio
            "amount" in e || "margin" in e || "minimum" in e || "not enough" in e || "quantity" in e -> etImporte
            else -> null
        }
        if (et != null && (et.parent?.parent as? View)?.visibility == View.VISIBLE) { et.error = err; et.requestFocus(); et.announceForAccessibility(err) }
        else aviso(err)
    }

    private fun ejecutar() {
        val a = activo(); val js = a.aJson()
        val actual = precioActual()
        if (actual == null || actual <= 0) { aviso("Price not loaded yet"); return }
        val imp = etImporte.num()
        val conTp = swTpSl.visibility == View.VISIBLE && conTpSl
        val tp = if (conTp) etTp.num() else 0.0
        val sl = if (conTp) etSl.num() else 0.0
        val reducir = futuros && soloReducir
        sim.precios[a.clave] = actual
        val m = if (futuros) Simulador.Mercado.FUT else Simulador.Mercado.SPOT
        val importe = if (reducir || (!futuros && !compra)) 0.0 else imp
        val qty = if (reducir || (!futuros && !compra)) imp else 0.0
        val err: String? = when (tipoUi) {
            1 -> {
                val lp = etPrecio.num()
                val cruza = if (compra) lp >= actual else lp <= actual       // tomaría liquidez del libro
                when {
                    lp <= 0 -> "Enter a limit price"
                    !cruza && tif >= 2 -> "${if (tif == 2) "IOC" else "FOK"} order expired: nothing to fill at your price right now"
                    !cruza -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.LIMIT, lp, 0.0, 0.0, importe, qty, apal,
                        if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
                    tif == 1 -> "Post-only order rejected: at that price it would fill immediately (take liquidity)"
                    reducir -> "A reduce-only limit must not cross the market; use a price ${if (compra) "below" else "above"} it"
                    else -> {
                        // cruza: se llena ya contra el libro hasta el límite (taker). GTC deja el resto en el libro.
                        val (cant, porNoc) = cantidadOrden(imp)
                        val r = recorrer(compra, cant, porNoc, lp)
                        val frac = r?.second ?: 0.0
                        when {
                            r == null || frac <= 0 -> if (tif == 0) sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.LIMIT, lp, 0.0, 0.0,
                                importe, qty, apal, if (futuros) tp else 0.0, if (futuros) sl else 0.0, false, cruzado, actual) else "${if (tif == 2) "IOC" else "FOK"} order expired: no liquidity at your price"
                            tif == 3 && frac < 0.999 -> "FOK order cancelled: only ${"%.1f".format(frac * 100)}% could fill at your price"
                            else -> {
                                val llena = if (frac >= 0.999) 1.0 else frac
                                val e1 = ejecutarMercado(a, js, imp * llena, r!!.first, tp, sl)
                                if (e1 == null && llena < 1.0 && tif == 0) sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.LIMIT, lp, 0.0, 0.0,
                                    if (importe > 0) importe * (1 - llena) else 0.0, if (qty > 0) qty * (1 - llena) else 0.0, apal,
                                    if (futuros) tp else 0.0, if (futuros) sl else 0.0, false, cruzado, actual)?.let { "Partly filled; the rest could not be placed: $it" }
                                else e1
                            }
                        }
                    }
                }
            }
            2 -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.STOP, 0.0, etDisparo.num(), 0.0, importe, qty, apal,
                if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
            3 -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.STOP_LIMIT, etPrecio.num(), etDisparo.num(), 0.0, importe, qty, apal,
                if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
            4 -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.TRAIL, 0.0, 0.0, etCallback.num() / 100, importe, qty, apal,
                if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
            else -> {
                // a mercado: el precio es el medio de lo que se come del libro (deslizamiento real)
                val (cant, porNoc) = cantidadOrden(imp)
                ejecutarMercado(a, js, imp, recorrer(compra, cant, porNoc, null)?.first ?: actual, tp, sl)
            }
        }
        if (err != null) { mostrarError(err); guardar(); refrescar(); return }
        aviso(if (tipoUi != 0) "Order placed" else "Order filled at ≈ ${fmtSim(sim.historial.lastOrNull()?.entrada?.takeIf { it > 0 } ?: actual)}")
        etImporte.setText(""); etTp.setText(""); etSl.setText(""); slider.poner(0f)
        guardar(); refrescar()
    }

    // ── posiciones, tenencias y órdenes ──
    private fun tarjeta() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; background = Ui.cardBg(AppTheme.R_INNER, DexTema.SURFACE, act)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    private fun dato(k: String, v: String, color: Int = DexTema.TXT) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(1), 0, dp(1))
        addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = k; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        addView(texto(AppTheme.SP_MICRO, color, true).apply { text = v })
    }
    private fun botonPeq(t: String, color: Int, alPulsar: () -> Unit) = TextView(act).apply {
        text = t; textSize = AppTheme.SP_CAPTION; typeface = AppTheme.bold(act); setTextColor(color); gravity = Gravity.CENTER
        background = DexTema.fondoTactil(act, DexTema.CHIP, 6f, 34f); setPadding(dp(14), 0, dp(14), 0); minHeight = dp(48)
        isClickable = true; foreground = Ui.toque(); setOnClickListener { alPulsar() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
    }
    private fun cabeceraTarjeta(izq: String, colIzq: Int, der: String, colDer: Int) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4))
        addView(texto(AppTheme.SP_CAPTION, colIzq, true).apply { text = izq; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        addView(texto(AppTheme.SP_CAPTION, colDer, true).apply { text = der })
    }
    private fun precioDe(clave: String): Double? = if (clave == activo().clave) precioActual() ?: sim.precios[clave] else sim.precios[clave]

    /** Repinta cuenta, listas y líneas del gráfico. */
    fun refrescar() {
        val a = activo()
        val nPos = if (futuros) sim.posiciones.size else sim.tenencias.count { it.qty > 1e-12 }
        if (::rotularTabs.isInitialized) rotularTabs(listOf(if (futuros) "Positions ($nPos)" else "Holdings ($nPos)",
            "Open orders (${sim.ordenes.size})", "Assets", "Bots (${sim.bots.size})", "History"))
        contPos.removeAllViews()
        when (tabLista) {
            0 -> if (futuros) listaPosiciones(a) else listaTenencias()
            1 -> listaOrdenes()
            2 -> listaActivos()
            3 -> listaBots()
            else -> listaHistorial(a)
        }
        // líneas sobre el gráfico del activo abierto
        // qué se pinta lo decide "Chart Settings → Order display"
        val g = act.getSharedPreferences("home_chart", Context.MODE_PRIVATE)
        val vPos = g.getBoolean("o_pos", true); val vTpSl = g.getBoolean("o_tpsl", true); val vTrig = g.getBoolean("o_trig", true)
        val vLiq = g.getBoolean("o_liq", true); val vBe = g.getBoolean("o_be", false)
        val lineas = ArrayList<GraficoTA.LineaPrecio>()
        for (p in sim.posiciones.filter { it.clave == a.clave }) {
            if (vPos) lineas.add(GraficoTA.LineaPrecio(p.entrada.toFloat(), DexTema.TXT, "${if (p.long) "Long" else "Short"} ${p.apal}x"))
            if (vLiq) sim.liquidacion(p).takeIf { it > 0 }?.let { lineas.add(GraficoTA.LineaPrecio(it.toFloat(), DexTema.LIQ, "Liq")) }
            if (vTpSl && p.tp > 0) lineas.add(GraficoTA.LineaPrecio(p.tp.toFloat(), DexTema.UP, "TP"))
            if (vTpSl && p.sl > 0) lineas.add(GraficoTA.LineaPrecio(p.sl.toFloat(), DexTema.DOWN, "SL"))
            // precio de equilibrio: entrada más las dos comisiones taker (apertura y cierre)
            if (vBe) lineas.add(GraficoTA.LineaPrecio((p.entrada * (1 + (if (p.long) 2 else -2) * Simulador.COM_TAKER)).toFloat(), DexTema.TXT2, "Breakeven"))
        }
        for (o in sim.ordenes.filter { it.clave == a.clave && (if (it.grupo != 0L) vTpSl else vTrig) }) {
            val (px, t) = when (o.tipo) {
                Simulador.TipoOrden.LIMIT -> o.precio to if (o.grupo != 0L) "TP" else if (o.compra) "Buy limit" else "Sell limit"
                Simulador.TipoOrden.STOP -> o.disparo to if (o.grupo != 0L) "SL" else if (o.compra) "Buy stop" else "Sell stop"
                Simulador.TipoOrden.STOP_LIMIT -> o.disparo to "Stop-limit"
                Simulador.TipoOrden.TRAIL -> (if (o.compra) o.extremo * (1 + o.callback) else o.extremo * (1 - o.callback)) to "Trailing"
            }
            lineas.add(GraficoTA.LineaPrecio(px.toFloat(), DexTema.ACCENT, t))
        }
        if (vTrig) for (b in sim.bots.filter { it.clave == a.clave && it.tipo == "GRID" }) {
            lineas.add(GraficoTA.LineaPrecio(b.hi.toFloat(), DexTema.TXT2, "Grid high"))
            lineas.add(GraficoTA.LineaPrecio(b.lo.toFloat(), DexTema.TXT2, "Grid low"))
        }
        chart.lineasPrecio = lineas
        resumen()
    }

    /** Estado vacío como en los exchanges: icono, saldo y "Add funds". */
    private fun vacio(titulo: String, sub: String, boton: String?, accion: () -> Unit = {}) = contPos.addView(LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(28), 0, dp(28))
        addView(android.widget.ImageView(act).apply { setImageResource(R.drawable.ic_vault); setColorFilter(DexTema.TXT2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)) })
        addView(texto(17f, DexTema.TXT, true).apply { text = titulo; gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6)) })
        addView(texto(14f, DexTema.TXT2).apply { text = sub; gravity = Gravity.CENTER })
        if (boton != null) addView(TextView(act).apply {
            text = boton; textSize = 15f; setTextColor(DexTema.TXT); gravity = Gravity.CENTER; minHeight = dp(48)
            background = DexTema.fondo(act, 0, 8f, DexTema.TXT2); setPadding(dp(28), 0, dp(28), 0); isClickable = true; foreground = Ui.toque()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            setOnClickListener { accion() } })
    })

    private fun listaPosiciones(a: Mercado.Activo) {
        if (sim.posiciones.isEmpty()) { vacio("Available funds: ${fmtSim(sim.fut)} VUSD", "Transfer funds to your Futures wallet", "Add funds") { dialogoFondos(2) }; return }
        for (p in sim.posiciones.sortedByDescending { it.clave == a.clave }) {
            val m = precioDe(p.clave); val pnl = m?.let { p.pnl(it) } ?: 0.0
            val card = tarjeta()
            card.addView(cabeceraTarjeta("${if (p.long) "Long" else "Short"} ${p.apal}x · ${p.simbolo}${if (p.cruzado) " · cross" else ""}", if (p.long) DexTema.UP else DexTema.DOWN,
                "%s (%s%.2f%%)".format(signo(pnl), if (pnl >= 0) "+" else "", pnl / p.margen * 100), if (pnl >= 0) DexTema.UP else DexTema.DOWN))
            card.addView(dato("Entry / mark", "${fmtSim(p.entrada)} / ${m?.let { fmtSim(it) } ?: "—"}"))
            card.addView(dato("Size · margin", "${fmtCant(p.qty)} ${p.simbolo} · ${fmtSim(p.margen)} VUSD"))
            card.addView(dato("Position ID", "#${p.id} · ${if (p.cruzado) "cross" else "isolated"} · ${if (sim.hedge) "hedge" else "one-way"}"))
            val liqP = sim.liquidacion(p)
            card.addView(dato("Liquidation", if (liqP <= 0) "none (cross balance covers it)" else fmtSim(liqP), DexTema.LIQ))
            card.addView(dato("TP / SL", "${if (p.tp > 0) fmtSim(p.tp) else "—"} / ${if (p.sl > 0) fmtSim(p.sl) else "—"}"))
            if (p.funding != 0.0) card.addView(dato("Funding", signo(p.funding), if (p.funding >= 0) DexTema.UP else DexTema.DOWN))
            card.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0)
                addView(botonPeq("TP/SL", DexTema.TXT) { editarTpSl(p) })
                if (!p.cruzado) addView(botonPeq("+ Margin", DexTema.TXT) { pedirMargen(p) })
                for ((txt, f) in listOf("50%" to 0.5, "Close" to 1.0)) addView(botonPeq(txt, DexTema.TXT) {
                    val px = precioDe(p.clave); if (px == null) { aviso("No price for ${p.simbolo} yet"); return@botonPeq }
                    val est = p.pnl(px) * f - px * p.qty * f * Simulador.COM_TAKER
                    confirmar(if (f < 1) "Close 50%" else "Close position",
                        "${if (p.long) "Long" else "Short"} ${p.apal}x ${p.simbolo}: close ${fmtCant(p.qty * f)} at market (≈ ${fmtSim(px)}).\nEstimated PnL after fee: ${signo(est)} VUSD.",
                        if (f < 1) "Close 50%" else "Close") {
                        val px2 = precioDe(p.clave) ?: px
                        sim.cerrarFuturo(p.id, px2, fraccion = f); guardar(); refrescar(); aviso("Closed at ${fmtSim(px2)}")
                    }
                })
            })
            contPos.addView(card)
        }
    }

    private fun listaTenencias() {
        val ts = sim.tenencias.filter { it.qty > 1e-12 }
        if (ts.isEmpty()) { vacio("Available funds: ${fmtSim(sim.spot)} VUSD", "Buy a coin to hold it in Spot", "Add funds") { dialogoFondos(0) }; return }
        for (h in ts) {
            val m = precioDe(h.clave); val valor = m?.let { it * h.qty }; val pnl = valor?.let { it - h.coste } ?: 0.0
            val card = tarjeta()
            card.addView(cabeceraTarjeta("${fmtCant(h.qty)} ${h.simbolo}", DexTema.TXT,
                "%s (%s%.2f%%)".format(signo(pnl), if (pnl >= 0) "+" else "", if (h.coste > 0) pnl / h.coste * 100 else 0.0), if (pnl >= 0) DexTema.UP else DexTema.DOWN))
            card.addView(dato("Avg cost / price", "${fmtSim(h.coste / h.qty)} / ${m?.let { fmtSim(it) } ?: "—"}"))
            card.addView(dato("Value", valor?.let { fmtSim(it) + " VUSD" } ?: "—"))
            card.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0)
                addView(botonPeq("TP/SL", DexTema.TXT) { ocoTenencia(h) })
                addView(botonPeq("Sell all", DexTema.TXT) {
                    val px = precioDe(h.clave); if (px == null) { aviso("No price for ${h.simbolo} yet"); return@botonPeq }
                    confirmar("Sell all ${h.simbolo}", "Sell ${fmtCant(h.qty)} ${h.simbolo} at market (≈ ${fmtSim(px)}).\nYou get ≈ ${fmtSim(h.qty * px * (1 - Simulador.COM_SPOT))} VUSD.", "Sell") {
                        sim.venderSpot(h.clave, h.qty, precioDe(h.clave) ?: px)?.let { aviso(it) }; guardar(); refrescar()
                    }
                })
            })
            contPos.addView(card)
        }
    }

    private fun listaOrdenes() {
        if (sim.ordenes.isEmpty()) { vacio("No open orders", "Limit, stop and trailing orders wait here until they fill", null); return }
        for (o in sim.ordenes) {
            val card = tarjeta()
            val tipoTxt = when (o.tipo) { Simulador.TipoOrden.LIMIT -> if (o.grupo != 0L) "TP (OCO)" else "Limit"; Simulador.TipoOrden.STOP -> if (o.grupo != 0L) "SL (OCO)" else "Stop"
                Simulador.TipoOrden.STOP_LIMIT -> "Stop-limit"; Simulador.TipoOrden.TRAIL -> "Trailing" }
            val lado = if (o.mercado == Simulador.Mercado.FUT) "$tipoTxt ${if (o.reduce) "close " + (if (o.compra) "short" else "long") else (if (o.compra) "long ${o.apal}x" else "short ${o.apal}x")}${if (o.cruzado) " cross" else ""}"
                else "$tipoTxt ${if (o.compra) "buy" else "sell"}"
            val precioTxt = when (o.tipo) {
                Simulador.TipoOrden.LIMIT -> "@ ${fmtSim(o.precio)}"
                Simulador.TipoOrden.STOP -> "trigger ${fmtSim(o.disparo)}"
                Simulador.TipoOrden.STOP_LIMIT -> "${fmtSim(o.disparo)} → ${fmtSim(o.precio)}"
                Simulador.TipoOrden.TRAIL -> "%.2f%% from %s".format(o.callback * 100, fmtSim(o.extremo))
            }
            card.addView(cabeceraTarjeta("$lado · ${o.simbolo}", if (o.compra) DexTema.UP else DexTema.DOWN, precioTxt, DexTema.TXT))
            if (o.tipo == Simulador.TipoOrden.TRAIL) card.addView(dato("Triggers at", fmtSim(if (o.compra) o.extremo * (1 + o.callback) else o.extremo * (1 - o.callback))))
            card.addView(dato(if (o.reduce) "Close" else if (o.mercado == Simulador.Mercado.FUT) "Margin" else if (o.compra) "Amount" else "Quantity",
                when { o.reduce -> if (o.qty > 0) "${fmtCant(o.qty)} ${o.simbolo}" else "whole position"
                    o.mercado == Simulador.Mercado.SPOT && !o.compra -> "${fmtCant(maxOf(o.reservado, o.qty))} ${o.simbolo}"
                    else -> "${fmtSim(o.importe)} VUSD" }))
            card.addView(dato("Order ID", "#${o.id} · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(o.creada * 1000))))
            if (o.tp > 0 || o.sl > 0) card.addView(dato("TP / SL", "${if (o.tp > 0) fmtSim(o.tp) else "—"} / ${if (o.sl > 0) fmtSim(o.sl) else "—"}"))
            card.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0)
                addView(botonPeq("Cancel", DexTema.TXT) { sim.cancelarOrden(o.id); guardar(); refrescar() })
            })
            contPos.addView(card)
        }
    }

    private fun listaBots() {
        if (sim.bots.isEmpty()) { vacio("No bots running", "DCA and Grid trade the open pair on Spot; the AI bot trades it on Futures with test VUSD", "Create bot") { dialogoBots() }; return }
        contPos.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 0, 0, dp(8))
            addView(botonPeq("+ New bot", DexTema.TXT) { dialogoBots() }) })
        for (b in sim.bots) contPos.addView(tarjetaBot(b))
    }

    /** Cuenta de prueba: aviso, patrimonio, saldos y acciones. */
    private fun listaActivos() {
        val noReal = sim.pnlNoRealizado()
        val card = tarjeta()
        card.addView(texto(13f, DexTema.WARN, true).apply {
            text = "TEST MODE · VUSD is a virtual test currency: no value, not real money, never leaves this phone"; setPadding(0, 0, 0, dp(10)) })
        card.addView(texto(13f, DexTema.TXT2).apply { text = "Total equity" })
        card.addView(texto(24f, DexTema.TXT, true).apply { text = "%s VUSD".format(fmtSim(sim.patrimonio())) })
        card.addView(dato("Spot available", fmtSim(sim.spot) + " VUSD"))
        card.addView(dato("Futures available", fmtSim(sim.fut) + " VUSD"))
        if (sim.earn > 0) card.addView(dato("Earn", fmtSim(sim.earn) + " VUSD"))
        card.addView(dato("Unrealized PnL", signo(noReal), if (noReal >= 0) DexTema.UP else DexTema.DOWN))
        val r = sim.rendimiento()
        card.addView(dato("Total return", signo(r), if (r >= 0) DexTema.UP else DexTema.DOWN))
        card.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(botonPeq("Deposit", DexTema.TXT) { dialogoFondos(0) }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
                addView(botonPeq("Withdraw", DexTema.TXT) { dialogoFondos(1) })
                addView(botonPeq("Transfer", DexTema.TXT) { dialogoFondos(2) })
                addView(botonPeq("Earn", DexTema.TXT) { dialogoEarn() })
                addView(botonPeq("Convert", DexTema.TXT) { dialogoConvert() })
                addView(botonPeq("Calculator", DexTema.TXT) { dialogoCalculadora() })
                addView(botonPeq("History", DexTema.TXT) { abrirHistorial() })
            })
        })
        contPos.addView(card)
        contPos.addView(tarjetaRiesgo())
        if (futuros) listaTenencias()
    }

    // ── Cartera y riesgo ──
    private fun tarjetaRiesgo() = tarjeta().apply {
        val total = sim.patrimonio().coerceAtLeast(1e-9)
        addView(texto(15f, DexTema.TXT, true).apply { text = "Portfolio & risk"; setPadding(0, 0, 0, dp(8)) })
        val partes = Riesgo.reparto(sim)
        val colores = listOf(DexTema.ACCENT, DexTema.UP, DexTema.LIQ, DexTema.DOWN, DexTema.TXT, DexTema.TXT2, DexTema.STROKE)
        val sumaPartes = partes.sumOf { it.valor }.coerceAtLeast(1e-9)
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
            clipToOutline = true; background = DexTema.fondo(act, DexTema.CHIP, 5f)
            partes.forEachIndexed { i, p -> addView(View(act).apply {
                setBackgroundColor(colores[i % colores.size])
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (p.valor / sumaPartes).toFloat()).apply { if (i > 0) marginStart = dp(2) }
            }) }
        })
        partes.forEachIndexed { i, p ->
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0)
                contentDescription = "%s: %s VUSD, %.1f percent".format(p.nombre, fmtSim(p.valor), p.valor / sumaPartes * 100)
                addView(View(act).apply { background = DexTema.fondo(act, colores[i % colores.size], 3f)
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) } })
                addView(texto(13f, DexTema.TXT).apply { text = p.nombre; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                addView(texto(13f, DexTema.TXT2).apply { text = "%s · %.1f%%".format(fmtSim(p.valor), p.valor / sumaPartes * 100) })
            })
        }
        val e = Riesgo.exposicion(sim)
        addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) })
        addView(dato("Long exposure", "${fmtSim(e.larga)} VUSD · %.0f%%".format(e.larga / total * 100), DexTema.UP))
        addView(dato("Short exposure", "${fmtSim(e.corta)} VUSD · %.0f%%".format(e.corta / total * 100), DexTema.DOWN))
        addView(dato("Net · gross leverage", "${signo(e.neta)} · %.2fx".format(e.bruta / total), if (e.neta >= 0) DexTema.TXT else DexTema.DOWN))
        val pc = Riesgo.peorCaso(sim)
        if (sim.posiciones.isNotEmpty()) {
            addView(dato("If every stop is hit", "${signo(pc.perdida)} VUSD · %.1f%% of equity".format(pc.perdida / total * 100), if (pc.perdida >= 0) DexTema.UP else DexTema.DOWN))
            if (pc.sinStop > 0) addView(dato("Without stop-loss", "${pc.sinStop} position${if (pc.sinStop > 1) "s" else ""}: counted as liquidated", DexTema.LIQ))
        }
        if (pc.spotSinProteger > 0) addView(dato("Spot holdings (no stop)", "${fmtSim(pc.spotSinProteger)} VUSD at market risk", DexTema.TXT2))
        val act0 = Riesgo.activos(sim)
        if (act0.size >= 2) {
            val tvCor = texto(13f, DexTema.TXT).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.3f) }
            addView(tvCor)
            addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
                addView(botonPeq("30-day correlation", DexTema.TXT) {
                    tvCor.text = "Loading daily candles…"
                    val lista = act0.entries.take(8).mapNotNull { (k, js) -> Mercado.Activo.deJson(js)?.let { k to it } }
                    Thread {
                        val cierres = lista.mapNotNull { (_, a) -> try { Mercado.velas(a, "1d", 31).map { it.c.toDouble() }.toDoubleArray().takeIf { it.size >= 6 }?.let { a.simbolo to it } } catch (e: Throwable) { null } }
                        val filas = ArrayList<String>()
                        for (i in cierres.indices) for (j in i + 1 until cierres.size) {
                            val c = Riesgo.correlacion(cierres[i].second, cierres[j].second) ?: continue
                            val txt = when { c >= 0.7 -> "move together"; c >= 0.3 -> "related"; c > -0.3 -> "little relation"; else -> "move opposite" }
                            filas.add("${cierres[i].first} / ${cierres[j].first}  %+.2f  $txt".format(c))
                        }
                        act.runOnUiThread { tvCor.text = if (filas.isEmpty()) "Not enough daily data." else
                            filas.joinToString("\n") + "\nHigh correlation means less diversification: they tend to fall together." }
                    }.start()
                })
            })
        }
    }

    private fun editarTpSl(p: Simulador.Posicion) {
        val etT = DexTema.campo(act, "Take-profit (empty = none)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; if (p.tp > 0) setText(p.tp.toString()) }
        val etS = DexTema.campo(act, "Stop-loss (empty = none)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; if (p.sl > 0) setText(p.sl.toString()) }
        DexTema.dialogo(act).setTitle("TP / SL · ${if (p.long) "Long" else "Short"} ${p.simbolo}")
            .setView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(etT); addView(etS)
                addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "Liquidation: ${fmtSim(sim.liquidacion(p))}"; setPadding(0, dp(6), 0, 0) })
            })
            .setPositiveButton("Save") { _, _ ->
                val err = sim.ponerTpSl(p.id, etT.num(), etS.num())
                if (err != null) aviso(err) else { guardar(); refrescar() }
            }.setNegativeButton("Cancel", null).show()
    }

    // ── precios ──
    /** Precio en vivo del activo del gráfico (solo el último: lo anterior ya pasó). */
    fun tick(clave: String, ultimo: Double) {
        if (ultimo <= 0) return
        val cambio = sim.procesar(clave, ultimo, ultimo, ultimo)
        notificar(); if (cambio) guardar()
        refrescar()
    }

    private fun notificar() {
        if (sim.eventos.isEmpty()) return
        Toast.makeText(act, sim.eventos.joinToString("\n"), Toast.LENGTH_LONG).show(); sim.eventos.clear()
    }

    private var vigilando = false
    /**
     * Revisa con velas de 1 min todos los activos con posiciones u órdenes, desde la
     * última revisión: así un TP, SL, liquidación u orden límite que ocurrió con la
     * app cerrada o mirando otro activo se aplica con su precio y hora reales.
     */
    fun vigilar() {
        if (vigilando) return
        val ahora = System.currentTimeMillis() / 1000
        val trabajos = sim.clavesActivas().mapNotNull { k -> Mercado.Activo.deJson(sim.activoDe(k))?.let { it to (sim.revisado[k] ?: ahora) } }
        if (trabajos.isEmpty()) return
        vigilando = true
        val tareasIA = BotIA.tareas(sim, ahora)
        Thread {
            val senales = try { BotIA.calcular(tareasIA) } catch (e: Throwable) { emptyList() }
            val res = trabajos.map { (a, desde) ->
                val n = ((ahora - desde) / 60 + 3).toInt().coerceIn(3, 1000)
                Triple(a, desde, try { Mercado.velas(a, "1m", n) } catch (e: Throwable) { emptyList() })
            }
            act.runOnUiThread {
                vigilando = false
                var cambio = false
                for ((a, desde, v) in res) {
                    if (v.isEmpty()) continue
                    cambio = sim.procesarVelas(a.clave, v, System.currentTimeMillis() / 1000) or cambio
                }
                cambio = BotIA.aplicar(sim, senales) or cambio
                notificar(); guardar(); if (vista.isAttachedToWindow) refrescar()
            }
        }.start()
    }

    // ── historial y estadísticas ──
    private fun notaVista(h: Simulador.Operacion) = texto(13f, DexTema.TXT).apply { text = "✎ " + h.nota; tag = "nota"; setPadding(0, dp(2), 0, 0) }

    /** Nota del diario para una operación (las #etiquetas quedan en el texto y en el CSV). */
    private fun notaOperacion(h: Simulador.Operacion, alGuardar: () -> Unit) {
        val et = DexTema.campo(act, "Why did you take it? What did you learn? #tags", false).apply { setText(h.nota); minLines = 2 }
        DexTema.dialogo(act).setTitle("Trade note · ${h.lado} ${h.simbolo}")
            .setView(LinearLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("Save") { _, _ -> h.nota = et.text.toString().trim().take(500); guardar(); alGuardar(); aviso("Note saved") }
            .setNegativeButton("Cancel", null).show()
    }

    /** Restaurar una copia: se pega el contenido del archivo .json de "Backup". */
    private fun restaurarCopia() {
        val et = DexTema.campo(act, "Paste the backup JSON here", true).apply { minLines = 4; maxLines = 8 }
        DexTema.dialogo(act).setTitle("Restore backup")
            .setView(LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et)
                addView(texto(12f, DexTema.TXT2).apply { text = "Replaces the whole test account (balances, positions, orders, bots, history)."; setPadding(0, dp(6), 0, 0) }) })
            .setPositiveButton("Restore") { _, _ ->
                val txt = et.text.toString().trim()
                val ok = try { val o = org.json.JSONObject(txt); o.has("spot") || o.has("saldo") } catch (e: Exception) { false }
                if (!ok) { aviso("That is not a DEx backup"); return@setPositiveButton }
                confirmar("Replace test account", "Your current test account will be replaced by the backup.", "Replace") {
                    sim.cargar(txt); guardar(); refrescar(); aviso("Backup restored") }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Una operación del historial (tocar = nota del diario). */
    private fun filaOperacion(h: Simulador.Operacion, sdf: java.text.SimpleDateFormat) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); minimumHeight = dp(48)
        isClickable = true; foreground = Ui.toque()
        setOnClickListener { notaOperacion(h) { val v = findViewWithTag<TextView>("nota"); if (v != null) v.text = "✎ " + h.nota else addView(notaVista(h)) } }
        val cerrada = h.salida > 0
        val lado = Diario.lado(h)
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(texto(AppTheme.SP_CAPTION, DexTema.TXT, true).apply {
                text = "${if (h.mercado == Simulador.Mercado.FUT) "Futures" else "Spot"} · ${h.lado} ${h.simbolo}"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(when (lado) { Diario.Lado.LONG -> DexTema.UP; Diario.Lado.SHORT -> DexTema.DOWN; else -> DexTema.TXT })
            })
            if (cerrada) addView(texto(AppTheme.SP_CAPTION, if (h.pnl >= 0) DexTema.UP else DexTema.DOWN, true).apply { text = signo(h.pnl) })
        })
        addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
            text = sdf.format(java.util.Date(h.t * 1000)) + " · " + h.motivo + " · " +
                (if (cerrada) "${fmtSim(h.entrada)} → ${fmtSim(h.salida)}" else "@ ${fmtSim(h.entrada)}") +
                " · ${fmtCant(h.qty)} · fee ${fmtSim(h.comision)}"
        })
        if (h.nota.isNotBlank()) addView(notaVista(h))
    }

    // ── pestaña History: todas las operaciones con filtro Long / Short / Spot ──
    private var filtroHist = 0
    private var soloPar = false
    private fun listaHistorial(a: Mercado.Activo) {
        val filtros = listOf(null, Diario.Lado.LONG, Diario.Lado.SHORT, Diario.Lado.SPOT)
        contPos.addView(DexTema.pestanas(act, listOf("All" to null, "Long" to null, "Short" to null, "Spot" to null), filtroHist, padH = 10f) { k ->
            filtroHist = k; refrescar() })
        contPos.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4))
            addView(botonPeq(if (soloPar) "✓ Only ${a.simbolo}" else "All pairs", if (soloPar) DexTema.ACCENT else DexTema.TXT) { soloPar = !soloPar; refrescar() }
                .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0; contentDescription = if (soloPar) "Showing only ${a.simbolo}. Tap to show all pairs" else "Showing all pairs. Tap to show only ${a.simbolo}" })
            addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            addView(botonPeq("Stats & export", DexTema.TXT) { abrirHistorial() }) })
        val l = Diario.filtrar(sim.historial, filtros[filtroHist], if (soloPar) a.simbolo else null)
        if (l.isEmpty()) { vacio("No trades", "Trades you open and close with test VUSD show up here", null); return }
        val cierres = l.filter { it.salida > 0 }
        val pnl = cierres.sumOf { it.pnl }
        contPos.addView(texto(AppTheme.SP_CAPTION, DexTema.TXT2).apply { setPadding(0, dp(4), 0, dp(4))
            text = "${l.size} entries · ${cierres.size} closed · ${cierres.count { it.pnl > 0 }} won · PnL ${signo(pnl)} VUSD" })
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        for (h in l.asReversed().take(100)) contPos.addView(filaOperacion(h, sdf))
        if (l.size > 100) contPos.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "Showing the latest 100. Export CSV in Stats & export for all." })
    }

    private fun abrirHistorial() {
        val st = sim.stats()
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(12)) }
        fun sec(t: String) = caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2, true).apply { text = t.uppercase(); letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(4)) })
        sec("Account")
        caja.addView(texto(AppTheme.SP_MICRO, DexTema.WARN, true).apply { text = "Test account · VUSD has no value and is not real money" })
        caja.addView(dato("Equity", fmtSim(sim.patrimonio()) + " VUSD"))
        caja.addView(dato("Spot available / in orders", "${fmtSim(sim.spot)} / ${fmtSim(sim.reservado(Simulador.Mercado.SPOT))}"))
        caja.addView(dato("Futures available / in orders", "${fmtSim(sim.fut)} / ${fmtSim(sim.reservado(Simulador.Mercado.FUT))}"))
        caja.addView(dato("Futures margin in use", fmtSim(sim.posiciones.sumOf { it.margen })))
        caja.addView(dato("Net deposits", fmtSim(sim.netoDepositado) + " VUSD"))
        val total = sim.rendimiento()
        caja.addView(dato("Total return", "%s (%s%.2f%%)".format(signo(total), if (total >= 0) "+" else "", if (sim.netoDepositado > 0) total / sim.netoDepositado * 100 else 0.0), if (total >= 0) DexTema.UP else DexTema.DOWN))
        // exportar y copia de seguridad
        val hoy = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        caja.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; setPadding(0, dp(8), 0, 0)
            addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL
                addView(botonPeq("Export CSV", DexTema.TXT) { Diario.compartir(act, "dex-trades-$hoy.csv", "text/csv", Diario.csv(sim)) }
                    .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
                addView(botonPeq("Backup", DexTema.TXT) { Diario.compartir(act, "dex-backup-$hoy.json", "application/json", sim.aJson()) })
                addView(botonPeq("Restore", DexTema.TXT) { restaurarCopia() })
            }) })
        sec("Closed trades")
        val mt = Diario.metricas(sim.historial)
        caja.addView(dato("Trades", mt.operaciones.toString()))
        caja.addView(dato("Win rate", if (mt.operaciones > 0) "%.1f%% (%d won / %d lost)".format(mt.tasaAcierto, mt.ganadoras, mt.perdedoras) else "—"))
        caja.addView(dato("Realized PnL", signo(st.pnl), if (st.pnl >= 0) DexTema.UP else DexTema.DOWN))
        caja.addView(dato("Profit factor", when { mt.factorBeneficio.isInfinite() -> "∞ (no losses)"; mt.operaciones == 0 -> "—"; else -> "%.2f".format(mt.factorBeneficio) }))
        caja.addView(dato("Avg win / avg loss", "${signo(mt.mediaGanadora)} / ${signo(mt.mediaPerdedora)}"))
        caja.addView(dato("Expectancy per trade", signo(mt.esperanza), if (mt.esperanza >= 0) DexTema.UP else DexTema.DOWN))
        caja.addView(dato("Max drawdown (realized)", "−" + fmtSim(mt.maxDrawdown), if (mt.maxDrawdown > 0) DexTema.DOWN else DexTema.TXT))
        caja.addView(dato("Longest win / loss streak", "${mt.rachaGanadora} / ${mt.rachaPerdedora}"))
        caja.addView(dato("Best / worst", "${signo(st.mejor)} / ${signo(st.peor)}"))
        caja.addView(dato("Fees paid", fmtSim(st.comisiones) + " VUSD"))
        val an = ScalpUtil.analizar(sim.historial)
        if (an.porLado.isNotEmpty()) {
            sec("By side, pair and hour (Futures)")
            fun filaG(g: ScalpUtil.Grupo) = caja.addView(dato("${g.clave} · ${g.operaciones} trades · ${if (g.operaciones > 0) g.ganadas * 100 / g.operaciones else 0}% won",
                signo(g.resultado), if (g.resultado >= 0) DexTema.UP else DexTema.DOWN))
            an.porLado.forEach { filaG(it) }
            caja.addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(1, dp(6)) })
            an.porPar.take(8).forEach { filaG(it) }
            caja.addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(1, dp(6)) })
            an.porHora.forEach { filaG(it) }
            if (an.duracionMedia > 0) {
                val d = an.duracionMedia.toLong()
                caja.addView(dato("Average duration", if (d < 3600) "${d / 60} min ${d % 60} s" else "%.1f h".format(d / 3600.0)))
                caja.addView(dato("Average MAE / MFE", "%.2f%% / +%.2f%%".format(an.maeMedio, an.mfeMedio)))
                caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "MAE: how far trades went against you; MFE: how far in your favour (% of price). If MFE is much bigger than your result, targets may be too far or exits too late." })
            }
        }
        sec("PnL calendar")
        caja.addView(VistaCalendario(act, Diario.pnlPorDia(sim.historial)).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        val curva = sim.curva()
        if (curva.size > 1) {
            sec("Realized PnL over time")
            caja.addView(GraficoPrecio(act).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
                poner(curva.map { it.second.toFloat() })
            })
        }
        sec("Trade history · tap a trade to add a note")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        if (sim.historial.isEmpty()) caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "No trades yet." })
        for (h in sim.historial.asReversed().take(150)) caja.addView(filaOperacion(h, sdf))
        sec("Account movements")
        if (sim.movimientos.isEmpty()) caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "None." })
        for (m in sim.movimientos.asReversed().take(100)) caja.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
            addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
                text = "${sdf.format(java.util.Date(m.t * 1000))} · ${m.tipo} · ${sim.nombre(m.cuenta)}\n${m.nota}"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(texto(AppTheme.SP_CAPTION, if (m.importe >= 0) DexTema.UP else DexTema.DOWN, true).apply { text = signo(m.importe) })
        })
        DexTema.dialogo(act).setTitle("Test exchange · VUSD")
            .setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Reset account") { _, _ ->
                DexTema.dialogo(act).setTitle("Reset simulator")
                    .setMessage("Close everything and start again with ${fmtSim(Simulador.BIENVENIDA)} VUSD in Spot and in Futures? History is deleted.")
                    .setPositiveButton("Reset") { _, _ -> sim.reiniciar(); guardar(); refrescar() }
                    .setNegativeButton("Cancel", null).show()
            }.show()
    }

    // ── Scalping (botones rápidos, flujo de órdenes, sesión y límite diario) ──
    private val prefsScalp = act.getSharedPreferences("dex_scalp", Context.MODE_PRIVATE)
    var scalpOn = prefsScalp.getBoolean("on", false); private set
    /** Últimas operaciones del mercado (las pone el Home desde el panel del libro). */
    var tradesActual: (() -> List<Mercado.Trade>)? = null
    /** Lo llama el Home cuando cambia el modo para que el botón muestre el estado. */
    var alCambiarScalp: ((Boolean) -> Unit)? = null
    private val cajaScalp = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 0, 0, dp(12)) }
    private val cMarS = DexTema.campoEtiquetado(act, "Margin VUSD"); private val etMarS = cMarS.second
    private val cApS = DexTema.campoEtiquetado(act, "Leverage x"); private val etApS = cApS.second.apply { inputType = InputType.TYPE_CLASS_NUMBER }
    private val cTpS = DexTema.campoEtiquetado(act, "Take-profit %"); private val etTpS = cTpS.second
    private val cSlS = DexTema.campoEtiquetado(act, "Stop-loss %"); private val etSlS = cSlS.second
    private val cLimS = DexTema.campoEtiquetado(act, "Daily loss limit VUSD"); private val etLimS = cLimS.second
    private val vBid = View(act); private val vAsk = View(act)
    private val tvFlujo = texto(12f, DexTema.TXT2)
    private val tvSesion = texto(12f, DexTema.TXT2).apply { setLineSpacing(0f, 1.25f) }
    private val hScalp = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickScalp = object : Runnable { override fun run() {
        if (scalpOn && futuros && cajaScalp.isAttachedToWindow) { pintarFlujo(); pintarSesion(); pintarSenal(); revisarAlertasScalp()
            chart.muros = if (prefsScalp.getBoolean("heat", true)) ScalpUtil.muros(libroActual?.invoke()) else emptyList() }
        else if (chart.muros.isNotEmpty()) chart.muros = emptyList()
        if (scalpOn) hScalp.postDelayed(this, 2_000) } }

    fun alternarScalp() {
        scalpOn = !scalpOn; prefsScalp.edit().putBoolean("on", scalpOn).apply()
        if (!scalpOn) chart.muros = emptyList()
        if (scalpOn && !futuros) aviso("Scalp mode works on Futures: switch to the Futures tab")
        hScalp.removeCallbacks(tickScalp); if (scalpOn) hScalp.post(tickScalp)
        alCambiarScalp?.invoke(scalpOn); refrescarFormulario()
    }

    private fun construirScalp() {
        etMarS.setText(prefsScalp.getString("m", "100")); etApS.setText(prefsScalp.getString("x", "20"))
        etTpS.setText(prefsScalp.getString("tp", "0.4")); etSlS.setText(prefsScalp.getString("sl", "0.25"))
        val lim = sim.limiteDia; etLimS.setText(if (lim > 0) "%.0f".format(java.util.Locale.US, lim) else "")
        fun guardarCfg() = prefsScalp.edit().putString("m", etMarS.text.toString()).putString("x", etApS.text.toString())
            .putString("tp", etTpS.text.toString()).putString("sl", etSlS.text.toString()).apply()
        alCambiar(etMarS, etApS, etTpS, etSlS) { guardarCfg() }
        alCambiar(etLimS) { sim.limiteDia = etLimS.num().coerceAtLeast(0.0); guardar(); pintarSesion() }
        fun fila(vararg vs: View) = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL
            vs.forEachIndexed { k, v -> addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (k > 0) marginStart = dp(8); bottomMargin = dp(8) }) } }
        fun corto(t: String, color: Int, f: () -> Unit) = botonPeq(t, color, f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; minHeight = dp(48); setPadding(dp(6), 0, dp(6), 0) }
        cajaScalp.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(texto(15f, DexTema.TXT, true).apply { text = "⚡ Quick scalp"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(botonPeq("Hide", DexTema.TXT2) { alternarScalp() }) })
        // tamaño: margen fijo o por riesgo
        cajaScalp.addView(DexTema.pestanas(act, listOf("Margin" to null, "Risk VUSD" to null, "Risk %" to null), modoTam, padH = 8f) { k ->
            modoTam = k; prefsScalp.edit().putInt("tam", k).apply(); rotularTam(); pintarPrevia() }
            .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
        cajaScalp.addView(fila(cMarS.first, cApS.first)); cajaScalp.addView(fila(cTpS.first, cSlS.first))
        // atajos de tamaño: % del saldo de Futuros como margen
        cajaScalp.addView(fila(*listOf(25, 50, 100).map { pc -> corto("$pc%", DexTema.TXT) {
            val x = etApS.text.toString().toIntOrNull()?.coerceIn(1, 125) ?: 1
            val m = Math.floor(sim.fut * pc / 100 / (1 + x * Simulador.COM_TAKER) * 100) / 100
            if (modoTam != 0) { modoTam = 0; prefsScalp.edit().putInt("tam", 0).apply(); rotularTam() }
            etMarS.setText("%.2f".format(java.util.Locale.US, m)) }.apply { contentDescription = "Use $pc percent of Futures balance as margin" } }.toTypedArray()))
        rotularTam()
        // plan: escalado, trailing, break-even, límite + ajustes
        val fChips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(t: String, clave: String, def: Boolean): TextView = corto(t, DexTema.TXT) { }.apply {
            fun pintar() { val on = prefsScalp.getBoolean(clave, def); setTextColor(if (on) DexTema.ACCENT else DexTema.TXT2)
                text = (if (on) "✓ " else "") + t; contentDescription = "$t ${if (on) "on" else "off"}" }
            setOnClickListener { prefsScalp.edit().putBoolean(clave, !prefsScalp.getBoolean(clave, def)).apply(); pintar(); pintarPrevia() }
            pintar() }
        listOf(chip("Scale", "esc", false), chip("Trail", "trail", false), chip("BE", "be", false), chip("Limit", "lim", false)).forEachIndexed { k, v ->
            fChips.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (k > 0) marginStart = dp(4) }) }
        fChips.addView(corto("⚙", DexTema.TXT) { dialogoAjustesScalp() }.apply { contentDescription = "Scalp settings" },
            LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        cajaScalp.addView(fChips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        cajaScalp.addView(tvPrevia)
        cajaScalp.addView(tvSenal)
        alCambiar(etMarS, etApS, etTpS, etSlS) { pintarPrevia() }
        fun grande(t: String, fondo: Int, largo: Boolean) = TextView(act).apply {
            text = t; textSize = 17f; typeface = AppTheme.bold(act); gravity = Gravity.CENTER; minHeight = dp(52)
            setTextColor(if (largo) DexTema.ON_UP else 0xFFFFFFFF.toInt()); background = DexTema.fondo(act, fondo, 8f); isClickable = true; foreground = Ui.toque()
            contentDescription = "Market ${if (largo) "long" else "short"} with the quick scalp settings"
            setOnClickListener { scalpAbrir(largo) } }
        cajaScalp.addView(fila(grande("Long ▲", DexTema.UP, true), grande("Short ▼", DexTema.DOWN, false)))
        cajaScalp.addView(fila(corto("Reverse", DexTema.TXT) { scalpInvertir() }, corto("Close 50%", DexTema.DOWN) { scalpCerrarPar(0.5) },
            corto("Close all", DexTema.DOWN) { scalpCerrarPar(1.0) }))
        // flujo de órdenes
        cajaScalp.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(vBid, LinearLayout.LayoutParams(0, dp(6), 1f)); addView(vAsk, LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginStart = dp(2) }) })
        cajaScalp.addView(tvFlujo); cajaScalp.addView(tvSesion)
        cajaScalp.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(8))
            addView(corto("New session", DexTema.TXT) { prefsScalp.edit().putLong("sesion", System.currentTimeMillis() / 1000).apply(); pintarSesion(); aviso("New scalp session") }
                .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }) })
        cajaScalp.addView(cLimS.first)
        cajaScalp.addView(texto(11f, DexTema.TXT2).apply { text = "Empty or 0 = no limit. When today's futures loss reaches it, new positions are blocked until 00:00 UTC. More rules (max trades, pause after losses, daily target) in ⚙." })
        cajaScalp.addView(View(act).apply { setBackgroundColor(DexTema.STROKE); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10) } })
        if (scalpOn) hScalp.post(tickScalp)
    }

    private val tvPrevia = texto(12f, DexTema.TXT2).apply { setLineSpacing(0f, 1.25f); setPadding(0, 0, 0, dp(6)) }
    /** Qué pasa con la configuración actual: TP/SL en precio, ROE y VUSD netos de comisiones. */
    private fun pintarPrevia() {
        val p = precioActual() ?: 0.0; val x = etApS.text.toString().toIntOrNull() ?: 0; val m = margenScalp(x.coerceAtLeast(1))
        val tpP = etTpS.num(); val slP = etSlS.num()
        if (p <= 0 || m <= 0 || x !in 1..125) { tvPrevia.text = "TP and SL are % of price from your fill (not ROE)."; return }
        val noc = m * x; val fees = noc * Simulador.COM_TAKER * 2
        val (tpL, slL) = sim.nivelesPct(p, true, tpP, slP)
        fun neto(pct: Double) = noc * pct / 100 - fees
        tvPrevia.text = buildString {
            append("TP and SL are % of price from your fill.\n")
            if (tpP > 0) append("TP %.2f%% = %+.1f%% ROE ≈ %s VUSD net · long at %s\n".format(tpP, tpP * x, usd2(neto(tpP)), fmtSim(tpL)))
            if (slP > 0) append("SL %.2f%% = −%.1f%% ROE ≈ %s VUSD net · long at %s\n".format(slP, slP * x, usd2(-noc * slP / 100 - fees), fmtSim(slL)))
            val pl = planScalp()
            if (pl.tp1Pct > 0) append("Scale: TP1 %.2f%% closes %.0f%%, TP2 %.2f%% closes %.0f%%%s\n".format(pl.tp1Pct, pl.tp1Frac * 100, pl.tp2Pct, pl.tp2Frac * 100, if (pl.be) ", then stop to break-even" else ""))
            else if (pl.be) append("BE needs ‘Scale’ (moves the stop when TP1 fills)\n")
            if (pl.trailPct > 0) append("Trailing %.2f%% once %.2f%% in profit\n".format(pl.trailPct, pl.trailPct))
            if (modoTam != 0) append("Margin for this risk ≈ ${fmtSim(m)} VUSD\n")
            append("Fees open + close ≈ ${fmtSim(fees)} VUSD")
            if (slP > 0 && slP >= 100.0 / x * 0.95) append(" · ⚠ SL is beyond liquidation at ${x}x")
        }
        tvPrevia.setTextColor(if (slP > 0 && slP >= 100.0 / x * 0.95) DexTema.DOWN else DexTema.TXT2)
    }

    private var modoTam = prefsScalp.getInt("tam", 0)
    private val tvSenal = texto(12f, DexTema.TXT2).apply { setPadding(0, 0, 0, dp(6)) }
    private val ultimaAlerta = HashMap<ScalpUtil.Alerta, Long>()

    private fun rotularTam() {
        val (et, h) = when (modoTam) { 1 -> "Risk VUSD" to "VUSD lost if SL hits"; 2 -> "Risk % of Futures" to "% of Futures lost if SL hits"; else -> "Margin VUSD" to "Margin VUSD" }
        ((cMarS.first.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView)?.text = et
        etMarS.hint = h
    }

    /** Margen de la próxima entrada según el modo de tamaño. */
    private fun margenScalp(x: Int): Double = when (modoTam) {
        1 -> ScalpUtil.margenPorRiesgo(etMarS.num(), etSlS.num(), x)
        2 -> ScalpUtil.margenPorRiesgo(sim.fut * etMarS.num() / 100, etSlS.num(), x)
        else -> etMarS.num()
    }

    private fun numPref(k: String, def: Double) = prefsScalp.getString(k, null)?.toDoubleOrNull() ?: def

    private fun planScalp(): Simulador.Plan {
        val esc = prefsScalp.getBoolean("esc", false)
        return Simulador.Plan(etTpS.num(), etSlS.num(),
            if (esc) numPref("t1", 0.2) else 0.0, if (esc) numPref("f1", 50.0) / 100 else 0.0,
            if (esc) numPref("t2", 0.3) else 0.0, if (esc) numPref("f2", 30.0) / 100 else 0.0,
            if (prefsScalp.getBoolean("trail", false)) numPref("tr", 0.15) else 0.0, prefsScalp.getBoolean("be", false))
    }

    /** Ajustes del scalping: escalones, trailing, caducidad de límites, heatmap, alertas y reglas de riesgo. */
    private fun dialogoAjustesScalp() {
        val caja = cajaDialogo()
        fun campo(et: String, k: String, def: String) = DexTema.campoEtiquetado(act, et).also { (c, e) ->
            e.setText(prefsScalp.getString(k, def)); caja.addView(c)
            alCambiar(e) { prefsScalp.edit().putString(k, e.text.toString()).apply() } }
        fun titulo(t: String) = caja.addView(texto(13f, DexTema.TXT, true).apply { text = t; setPadding(0, dp(10), 0, dp(6)) })
        titulo("Scale out (when ‘Scale’ is on)")
        campo("TP1 % of price", "t1", "0.2"); campo("TP1 closes % of size", "f1", "50")
        campo("TP2 % of price", "t2", "0.3"); campo("TP2 closes % of size", "f2", "30")
        caja.addView(texto(11f, DexTema.TXT2).apply { text = "The rest closes at the main take-profit. With ‘BE’ on, the stop moves to break-even (covers fees) when TP1 fills." })
        titulo("Trailing stop (when ‘Trail’ is on)")
        campo("Trailing distance % of price", "tr", "0.15")
        caja.addView(texto(11f, DexTema.TXT2).apply { text = "Starts once the price moves that % in your favour, then follows the best price." })
        titulo("Limit entries (when ‘Limit’ is on)")
        campo("Cancel if not filled after (seconds, 0 = never)", "cad", "30")
        caja.addView(texto(11f, DexTema.TXT2).apply { text = "Long buys at the best bid, Short sells at the best ask: maker fee 0.02% instead of 0.05%." })
        titulo("Chart and alerts")
        caja.addView(DexTema.casilla(act, "Liquidity heatmap on the chart", prefsScalp.getBoolean("heat", true)) { prefsScalp.edit().putBoolean("heat", it).apply() })
        caja.addView(DexTema.casilla(act, "Scalp alerts (VWAP cross, book imbalance, volume spike)", prefsScalp.getBoolean("alertas", true)) { prefsScalp.edit().putBoolean("alertas", it).apply() })
        titulo("Risk rules (Futures)")
        val (cMo, eMo) = DexTema.campoEtiquetado(act, "Max trades per day (0 = no limit)").also { it.second.inputType = InputType.TYPE_CLASS_NUMBER; it.second.setText(if (sim.maxOpsDia > 0) sim.maxOpsDia.toString() else "") }
        val (cPs, ePs) = DexTema.campoEtiquetado(act, "Pause after N losses in a row (0 = off)").also { it.second.inputType = InputType.TYPE_CLASS_NUMBER; it.second.setText(if (sim.perdidasSeguidas > 0) sim.perdidasSeguidas.toString() else "") }
        val (cPm, ePm) = DexTema.campoEtiquetado(act, "Pause minutes").also { it.second.inputType = InputType.TYPE_CLASS_NUMBER; it.second.setText(sim.pausaMin.toString()) }
        val (cOd, eOd) = DexTema.campoEtiquetado(act, "Daily profit target VUSD (0 = off)").also { it.second.setText(if (sim.objetivoDia > 0) "%.0f".format(java.util.Locale.US, sim.objetivoDia) else "") }
        listOf(cMo, cPs, cPm, cOd).forEach { caja.addView(it) }
        caja.addView(texto(11f, DexTema.TXT2).apply { text = "When a rule triggers, new Futures positions and orders are blocked (closing is always allowed)." })
        DexTema.dialogo(act).setTitle("Scalp settings").setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Save") { _, _ ->
                sim.maxOpsDia = eMo.text.toString().toIntOrNull()?.coerceIn(0, 1000) ?: 0
                sim.perdidasSeguidas = ePs.text.toString().toIntOrNull()?.coerceIn(0, 50) ?: 0
                sim.pausaMin = ePm.text.toString().toIntOrNull()?.coerceIn(1, 1440) ?: 30
                sim.objetivoDia = eOd.num().coerceAtLeast(0.0)
                guardar(); pintarSesion(); pintarPrevia()
            }.setNegativeButton("Close", null).show()
    }

    /** Señal de scalping en vivo sobre las velas cerradas del gráfico. */
    private fun pintarSenal() {
        val v = chart.velasActuales()
        if (v.size < 41) { tvSenal.text = "AI signal: waiting for candles"; return }
        val s = Scalping.senal(v.dropLast(1), Scalping.desequilibrio(libroActual?.invoke()))
        if (s == null) { tvSenal.text = "AI signal: —"; return }
        tvSenal.text = "AI signal (chart TF): " + when (s.dir) { 1 -> "▲ LONG"; -1 -> "▼ SHORT"; else -> "■ wait" } +
            " · ${s.confianza}% · " + s.motivos.joinToString(", ")
        tvSenal.setTextColor(when (s.dir) { 1 -> DexTema.UP; -1 -> DexTema.DOWN; else -> DexTema.TXT2 })
    }

    /** Alertas de scalping con vibración (cada tipo como mucho una vez por minuto). */
    private fun revisarAlertasScalp() {
        if (!prefsScalp.getBoolean("alertas", true)) return
        val ahora = System.currentTimeMillis()
        val nuevas = ScalpUtil.alertas(chart.velasActuales(), Scalping.desequilibrio(libroActual?.invoke()))
            .filter { ahora - (ultimaAlerta[it] ?: 0L) > 60_000 }
        if (nuevas.isEmpty()) return
        nuevas.forEach { ultimaAlerta[it] = ahora }
        cajaScalp.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        aviso("⚡ ${activo().simbolo}: " + nuevas.joinToString(" · ") { it.texto })
    }

    private fun pintarScalp() {
        cajaScalp.visibility = if (scalpOn && futuros) View.VISIBLE else View.GONE
        if (cajaScalp.visibility == View.VISIBLE) { pintarFlujo(); pintarSesion(); pintarPrevia() }
    }

    private fun pintarFlujo() {
        val d = Scalping.desequilibrio(libroActual?.invoke())
        val t = tradesActual?.invoke().orEmpty(); val dl = Scalping.delta(t)
        val pb = (((d ?: 0.0) + 1) / 2).toFloat().coerceIn(0.02f, 0.98f)
        vBid.background = DexTema.fondo(act, DexTema.UP, 3f); vAsk.background = DexTema.fondo(act, DexTema.DOWN, 3f)
        (vBid.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = pb; vBid.layoutParams = it }
        (vAsk.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 1 - pb; vAsk.layoutParams = it }
        tvFlujo.text = (if (d == null) "Book: —" else "Book ±0.5%%: %.0f%% bids · %.0f%% asks".format((d + 1) * 50, (1 - d) * 50)) +
            (if (dl == null) "" else "  ·  Delta last ${t.size} trades: %+.0f%% %s".format(dl * 100, if (dl >= 0) "buyers" else "sellers"))
        tvFlujo.contentDescription = tvFlujo.text
    }

    private fun pintarSesion() {
        val ahora = System.currentTimeMillis() / 1000
        val desde = prefsScalp.getLong("sesion", 0L).takeIf { it > 0 } ?: (ahora - ahora % 86_400)
        val s = Scalping.sesion(sim.historial, desde, ahora)
        val hoy = sim.resultadoHoy(ahora)
        val lim = sim.limiteDia
        val bloq = sim.bloqueadoHoy(ahora)
        tvSesion.text = "Session %d min · %d trades · %.0f%% wins\nResult %s VUSD (%+.2f/min) · fees %s\nToday %s VUSD%s".format(
            s.minutos.toInt(), s.operaciones, s.acierto, usd2(s.resultado), s.porMinuto, usd2(s.comisiones).removePrefix("+"), usd2(hoy),
            if (lim > 0) " of −${fmtSim(lim)} limit" + (if (bloq) " · BLOCKED until 00:00 UTC" else "") else "")
        tvSesion.setTextColor(if (bloq) DexTema.DOWN else DexTema.TXT2)
    }

    private fun scalpAbrir(largo: Boolean) {
        val a = activo(); val p0 = precioActual()
        if (p0 == null || p0 <= 0) { aviso("Price not loaded yet"); return }
        val x = etApS.text.toString().toIntOrNull() ?: 0
        if (x !in 1..125) { aviso("Leverage must be 1–125x"); return }
        val m = margenScalp(x)
        if (m <= 0) { aviso(if (modoTam == 0) "Enter a margin" else "Enter the risk and a stop-loss %"); return }
        val plan = planScalp()
        if (prefsScalp.getBoolean("lim", false)) {
            val (bid, ask) = bbo?.invoke() ?: (null to null)
            val pl = (if (largo) bid else ask) ?: p0
            val cad = prefsScalp.getString("cad", "30")?.toLongOrNull()?.coerceIn(0, 3600) ?: 30
            val err = sim.limiteConPlan(a.clave, a.aJson(), a.simbolo, largo, m, x, pl, plan, cad)
            if (err != null) { aviso(err); return }
            cajaScalp.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            guardar(); refrescar()
            aviso("Limit ${if (largo) "long" else "short"} ${x}x at ${fmtSim(pl)} (maker 0.02%)" + if (cad > 0) " · cancels in ${cad}s if not filled" else "")
            return
        }
        val px = recorrer(largo, m * x, true, null)?.first ?: p0
        val err = sim.abrirConPlan(a.clave, a.aJson(), a.simbolo, largo, m, x, px, plan)
        if (err != null) { aviso(err); return }
        cajaScalp.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        val pos = sim.posiciones.lastOrNull { it.clave == a.clave && it.long == largo && !it.cruzado }
        notificar(); guardar(); refrescar()
        aviso("${if (largo) "Long" else "Short"} ${x}x at ${fmtSim(px)}" +
            (pos?.let { p -> (if (p.tp > 0) " · TP ${fmtSim(p.tp)}" else "") + (if (p.sl > 0) " · SL ${fmtSim(p.sl)}" else "") } ?: ""))
    }

    private fun scalpCerrarPar(fraccion: Double = 1.0) {
        val a = activo(); val px = precioActual() ?: run { aviso("Price not loaded yet"); return }
        val ps = sim.posiciones.filter { it.clave == a.clave }
        if (ps.isEmpty()) { aviso("No ${a.simbolo} positions"); return }
        for (p in ps) sim.cerrarFuturo(p.id, recorrer(!p.long, p.qty * fraccion, false, null)?.first ?: px, if (fraccion >= 0.999) "Scalp close" else "Close", fraccion = fraccion)
        cajaScalp.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        notificar(); guardar(); refrescar(); aviso("Closed ${ps.size} ${a.simbolo} position${if (ps.size > 1) "s" else ""}")
    }

    /** Cierra lo abierto en el par y abre lo contrario con la configuración rápida. */
    private fun scalpInvertir() {
        val a = activo(); val ps = sim.posiciones.filter { it.clave == a.clave }
        if (ps.isEmpty()) { aviso("No ${a.simbolo} position to reverse"); return }
        val largo = !ps.maxByOrNull { it.nocional }!!.long
        scalpCerrarPar(1.0); scalpAbrir(largo)
    }

    // al final: así todas las propiedades ya están inicializadas al construir la vista
    init { construir(); refrescar() }
    private fun usd2(x: Double) = "%+,.2f".format(x)
}
