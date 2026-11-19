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

    val vista = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }

    // ── piezas ──
    private fun texto(sp: Float, color: Int, negrita: Boolean = false) = TextView(act).apply {
        textSize = sp; setTextColor(color); typeface = if (negrita) AppTheme.bold(act) else AppTheme.body(act)
    }
    private fun chip(t: String, alPulsar: () -> Unit) = TextView(act).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; typeface = AppTheme.medium(act); minHeight = dp(40)
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
    /** Chip de apalancamiento: caja #1E1E20; la elegida con borde blanco de 2 dp. */
    private fun marcarApal(c: TextView, on: Boolean) {
        c.background = DexTema.fondo(act, DexTema.CHIP, 8f, if (on) DexTema.TXT else 0, 2f)
        c.setTextColor(if (on) DexTema.TXT else DexTema.TXT2); c.typeface = if (on) AppTheme.bold(act) else AppTheme.medium(act)
        c.isSelected = on
    }
    private fun etiqueta(t: String) = texto(15f, DexTema.TXT).apply { text = t; minWidth = dp(64) }
    /** Texto con flecha que abre un menú (Isolated ▾, One-way ▾, VUSD · Futures ▾). */
    private fun desplegable() = TextView(act).apply {
        textSize = 14f; setTextColor(DexTema.TXT); typeface = AppTheme.medium(act); minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0); isClickable = true; foreground = Ui.toque(); compoundDrawablePadding = dp(2)
        setCompoundDrawables(null, null, act.getDrawable(R.drawable.ic_caret_down)?.mutate()?.apply {
            setTint(DexTema.TXT); setBounds(0, 0, dp(16), dp(16)) }, null)
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

    // ── cabecera y cuenta ──
    private val tvPatrimonio = texto(22f, DexTema.TXT, true)
    private val tvCuenta = texto(AppTheme.SP_MICRO, DexTema.TXT2)

    // ── formulario ──
    private val segLS = SegmentoLargoCorto(act)
    private val chipsTipo = ArrayList<TextView>()
    private val etPrecio = campo("Limit price (USD)")
    private val etDisparo = campo("Trigger price (USD)")
    private val etCallback = campo("Callback rate % (0.1–20)")
    private val ddMargen = desplegable(); private val ddPos = desplegable(); private val ddCuenta = desplegable()
    private val filaModo = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val swReducir = DexTema.casilla(act, "Reduce-only", false) { }
    private val etImporte = campo("")
    private val filaApal = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val chipsApal = ArrayList<Pair<Int, TextView>>()
    private val contApal = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }
    /** "TP/SL" en azul, como enlace: despliega los dos campos. */
    private val swTpSl = TextView(act).apply {
        text = "TP/SL"; textSize = 16f; setTextColor(DexTema.LINK); typeface = AppTheme.medium(act); minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
        isClickable = true; foreground = Ui.toque()
    }
    private val tvDisponible = texto(14f, DexTema.TXT).apply { minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; isClickable = true; foreground = Ui.toque()
        compoundDrawablePadding = dp(6) }
    private val tvVolumen = texto(14f, DexTema.TXT)
    private val tvEstimado = texto(13f, DexTema.TXT2).apply { gravity = Gravity.END }
    private val etTp = campo("Take-profit price (optional)")
    private val etSl = campo("Stop-loss price (optional)")
    private val tvResumen = texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { setPadding(dp(2), dp(2), 0, dp(8)); setLineSpacing(0f, 1.25f) }
    private val btnAccion = TextView(act).apply {
        textSize = 18f; typeface = AppTheme.bold(act); gravity = Gravity.CENTER; minHeight = dp(56)
        isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }
    private val contPos = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    init { construir(); refrescar() }

    private fun construir() {
        // ── fila de cuenta y modos: VUSD · Futures ▾ ······ Isolated ▾ One-way ▾ ──
        ddCuenta.setOnClickListener { menuCuenta() }
        ddMargen.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, ddMargen)
            pm.menu.add(0, 0, 0, "Isolated · loss limited to the position margin"); pm.menu.add(0, 1, 1, "Cross · uses the whole Futures balance")
            pm.setOnMenuItemClickListener { cruzado = it.itemId == 1; prefs.edit().putBoolean("cruzado", cruzado).apply(); refrescarFormulario(); true }; pm.show()
        }
        ddPos.setOnClickListener {
            val pm = androidx.appcompat.widget.PopupMenu(act, ddPos)
            pm.menu.add(0, 0, 0, "One-way · opposite orders reduce"); pm.menu.add(0, 1, 1, "Hedge · long and short at once")
            pm.setOnMenuItemClickListener { cambiarModoPos(it.itemId == 1); true }; pm.show()
        }
        filaModo.addView(ddCuenta)
        filaModo.addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        filaModo.addView(ddMargen); filaModo.addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) }); filaModo.addView(ddPos)
        vista.addView(filaModo)

        // ── Long / Short en diagonal ──
        segLS.alCambiar = { l -> compra = l; refrescarFormulario() }
        vista.addView(segLS.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(12) } })

        // ── tipo de orden en píldoras ──
        val filaTipos = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Market", "Limit", "Stop", "Stop-limit", "Trailing").forEachIndexed { k, t ->
            val c = chip(t) { tipoUi = k; refrescarFormulario() }; chipsTipo.add(c); filaTipos.addView(c)
        }
        vista.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(filaTipos)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } })
        vista.addView(etDisparo); vista.addView(etPrecio); vista.addView(etCallback)
        swReducir.setOnCheckedChangeListener { _, b -> soloReducir = b; refrescarFormulario() }
        vista.addView(swReducir)

        // ── Lever: chips + lápiz ──
        for (x in listOf(1, 2, 3, 5, 10, 20, 25, 50, 75, 100, 125)) {
            val c = TextView(act).apply {
                text = "${x}X"; textSize = 15f; gravity = Gravity.CENTER; minHeight = dp(44); minWidth = dp(56)
                setPadding(dp(10), 0, dp(10), 0); isClickable = true; foreground = Ui.toque(); contentDescription = "Leverage ${x}x"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener { apal = x; prefs.edit().putInt("apal", apal).apply(); refrescarFormulario() }
            }
            chipsApal.add(x to c); filaApal.addView(c)
        }
        contApal.addView(etiqueta("Lever"))
        contApal.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(filaApal)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        contApal.addView(android.widget.ImageView(act).apply {
            setImageResource(R.drawable.ic_edit); setColorFilter(DexTema.TXT); scaleType = android.widget.ImageView.ScaleType.CENTER
            background = DexTema.fondo(act, 0, 8f, DexTema.STROKE); contentDescription = "Custom leverage"
            isClickable = true; foreground = Ui.toque(); setOnClickListener { pedirApal() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginStart = dp(6) }
        })
        vista.addView(contApal)

        // ── Margin / Amount: etiqueta + campo; porcentajes debajo ──
        val etqImporte = etiqueta("Margin")
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(etqImporte); etImporte.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(etImporte)
            tag = etqImporte
        })
        filaImporte = etqImporte
        val pcts = listOf(10, 25, 50, 100)
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(64), dp(8), 0, dp(10))
            pcts.forEachIndexed { k, p -> addView(TextView(act).apply {
                text = "$p%"; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(40); setTextColor(DexTema.TXT)
                background = DexTema.fondo(act, DexTema.CHIP, 8f); isClickable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (k > 0) marginStart = dp(8) }
                setOnClickListener { porcentaje(p) } }) }
        })

        // ── volumen, disponible ⊕ y precio estimado ──
        vista.addView(tvVolumen.apply { setPadding(0, dp(4), 0, 0) })
        tvDisponible.setCompoundDrawablesWithIntrinsicBounds(null, null, act.getDrawable(R.drawable.ic_plus_circle)?.mutate()?.apply { setTint(DexTema.TXT) }, null)
        tvDisponible.setOnClickListener { dialogoFondos(2) }
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tvDisponible); addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }); addView(tvEstimado)
        })
        swTpSl.setOnClickListener { conTpSl = !conTpSl; refrescarFormulario() }
        vista.addView(swTpSl); vista.addView(etTp); vista.addView(etSl)
        val vigila = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = resumen()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(etPrecio, etImporte, etTp, etSl, etDisparo, etCallback).forEach { it.addTextChangedListener(vigila) }
        btnAccion.setOnClickListener { ejecutar() }
        vista.addView(btnAccion)
        vista.addView(tvResumen.apply { setPadding(dp(2), dp(10), 0, 0) })

        // ── cuenta de prueba (resumen) ──
        val btnHist = chip("History & stats") { abrirHistorial() }.apply { marcarChip(this, false) }
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(titulo("Test account").apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(btnHist)
        })
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = DexTema.fondo(act, DexTema.SURFACE, 12f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(texto(13f, DexTema.WARN, true).apply {
                text = "TEST MODE · VUSD is a virtual test currency: no value, not real money, never leaves this phone"
                setPadding(0, 0, 0, dp(8))
            })
            addView(texto(13f, DexTema.TXT2).apply { text = "Total equity" })
            addView(tvPatrimonio); addView(tvCuenta)
            addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; setPadding(0, dp(10), 0, 0)
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(botonPeq("Deposit", DexTema.UP) { dialogoFondos(0) }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
                    addView(botonPeq("Withdraw", DexTema.TXT) { dialogoFondos(1) })
                    addView(botonPeq("Transfer", DexTema.TXT) { dialogoFondos(2) })
                    addView(botonPeq("Earn", DexTema.TXT) { dialogoEarn() })
                    addView(botonPeq("Convert", DexTema.TXT) { dialogoConvert() })
                    addView(botonPeq("Bots", DexTema.TXT) { dialogoBots() })
                    addView(botonPeq("Calculator", DexTema.TXT) { dialogoCalculadora() })
                })
            })
        })
        vista.addView(contPos)
        refrescarFormulario()
    }
    private var filaImporte: TextView? = null

    /** Menú de la cuenta (como "Virtual USDT Account ▾"). */
    private fun menuCuenta() {
        val pm = androidx.appcompat.widget.PopupMenu(act, ddCuenta)
        listOf("Deposit test VUSD", "Withdraw", "Transfer Spot ⇄ Futures", "Earn", "Convert", "Bots", "Futures calculator", "History & stats")
            .forEachIndexed { k, t -> pm.menu.add(0, k, k, t) }
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 0 -> dialogoFondos(0); 1 -> dialogoFondos(1); 2 -> dialogoFondos(2); 3 -> dialogoEarn(); 4 -> dialogoConvert()
                5 -> dialogoBots(); 6 -> dialogoCalculadora(); else -> abrirHistorial() }; true }
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
        if (b.tipo == "DCA") {
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
                if (b.tipo == "GRID" && p == null) { aviso("No price for ${b.simbolo} yet"); return@botonPeq }
                sim.pararBot(b.id, p ?: 0.0); guardar(); refrescar(); aviso(if (b.tipo == "GRID") "Grid stopped, funds back in Spot" else "DCA bot stopped")
            })
        })
    }
    private fun intervaloTxt(s: Long) = when { s % 604_800 == 0L -> "${s / 604_800}w"; s % 86_400 == 0L -> "${s / 86_400}d"; else -> "${s / 3600}h" }

    private fun dialogoBots() {
        val a = activo()
        val caja = cajaDialogo()
        caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
            text = "Test bots run on Spot with real prices for ${a.simbolo}, while the DEx tab is open (missed periods are caught up with 1-minute candles)."
            setPadding(0, 0, 0, dp(10))
        })
        var tipo = 0
        val pDca = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val pGrid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        caja.addView(DexTema.pestanas(act, listOf("DCA" to null, "Grid" to null), 0) { k -> tipo = k
            pDca.visibility = if (k == 0) View.VISIBLE else View.GONE; pGrid.visibility = if (k == 1) View.VISIBLE else View.GONE }
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
        caja.addView(pDca); caja.addView(pGrid)
        caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "Available in Spot: ${fmtSim(sim.spot)} VUSD"; setPadding(0, dp(8), 0, 0) })
        DexTema.dialogo(act).setTitle("New bot · ${a.simbolo}").setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Start") { _, _ ->
                val err = if (tipo == 0) sim.crearDca(a.clave, a.aJson(), a.simbolo, etDca.num(), cada)
                    else sim.crearGrid(a.clave, a.aJson(), a.simbolo, etLo.num(), etHi.num(), etN.text.toString().toIntOrNull() ?: 0, etInv.num(), px)
                if (err != null) aviso(err) else {
                    if (tipo == 0) sim.procesar(a.clave, px, px, px)       // primera compra ya
                    notificar(); guardar(); refrescar(); aviso("Bot started")
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

    /** Spot o Futuros: lo eligen las pestañas de arriba del Home. */
    fun ponerModo(f: Boolean) {
        if (f == futuros) return
        futuros = f; prefs.edit().putBoolean("futuros", f).apply(); refrescarFormulario()
    }

    /** Precio elegido en el libro de órdenes: pasa a orden límite con ese precio. */
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
        val a = activo()
        segLS.textos = if (futuros) "Long" to "Short" else "Buy" to "Sell"; segLS.poner(compra)
        ddCuenta.text = "VUSD · ${if (futuros) "Futures" else "Spot"}"
        ddMargen.text = if (cruzado) "Cross" else "Isolated"; ddPos.text = if (sim.hedge) "Hedge" else "One-way"
        chipsTipo.forEachIndexed { k, c -> marcarChip(c, k == tipoUi) }
        etPrecio.visibility = if (tipoUi == 1 || tipoUi == 3) View.VISIBLE else View.GONE
        etDisparo.visibility = if (tipoUi == 2 || tipoUi == 3) View.VISIBLE else View.GONE
        etCallback.visibility = if (tipoUi == 4) View.VISIBLE else View.GONE
        etPrecio.hint = "Limit price (USD)"
        etDisparo.hint = "Trigger price (USD) · ${if (compra) "above" else "below"} current"
        val reducir = futuros && soloReducir && tipoUi != 0
        swReducir.visibility = if (futuros && tipoUi != 0) View.VISIBLE else View.GONE
        ddMargen.visibility = if (futuros) View.VISIBLE else View.GONE; ddPos.visibility = ddMargen.visibility
        contApal.visibility = if (futuros && !reducir) View.VISIBLE else View.GONE
        chipsApal.forEach { (x, c) -> marcarApal(c, x == apal) }
        filaImporte?.text = when { soloReducir && futuros && tipoUi != 0 -> "Qty"; futuros -> "Margin"; compra -> "Amount"; else -> "Qty" }
        etImporte.hint = when {
            reducir -> "Quantity to close (${a.simbolo}) · empty = whole position"
            futuros -> "Margin (VUSD) · ${apal}x ${if (cruzado) "cross" else "isolated"}"
            compra -> "Amount (VUSD)"; else -> "Quantity (${a.simbolo})" }
        // TP/SL: futuros al abrir; spot en compras a mercado (crea una OCO de venta)
        swTpSl.visibility = if ((futuros && !reducir) || (!futuros && compra && tipoUi == 0)) View.VISIBLE else View.GONE
        val tpsl = swTpSl.visibility == View.VISIBLE && conTpSl
        etTp.visibility = if (tpsl) View.VISIBLE else View.GONE; etSl.visibility = if (tpsl) View.VISIBLE else View.GONE
        btnAccion.text = when {
            reducir -> (if (compra) "Close short " else "Close long ") + a.simbolo
            futuros -> (if (compra) "Open long " else "Open short ") + a.simbolo + " · ${apal}x"
            else -> (if (compra) "Buy " else "Sell ") + a.simbolo
        } + listOf("", " (limit)", " (stop)", " (stop-limit)", " (trailing)")[tipoUi]
        btnAccion.background = DexTema.fondo(act, if (compra) DexTema.UP else DexTema.DOWN, 100f)
        btnAccion.setTextColor(if (compra) DexTema.ON_UP else 0xFFFFFFFF.toInt())
        swTpSl.text = if (conTpSl) "TP/SL  −" else "TP/SL  +"
        resumen()
    }

    /** Estimación de lo que hará la orden: tamaño, nocional, comisión, liquidación. */
    private fun resumen() {
        val a = activo()
        val act0 = precioActual() ?: 0.0
        val disp = if (futuros) sim.fut else if (compra) sim.spot else (sim.tenencias.firstOrNull { it.clave == a.clave }?.qty ?: 0.0)
        tvDisponible.text = if (!futuros && !compra) "${fmtCant(disp)} ${a.simbolo} available" else "${fmtSim(disp)} VUSD available"
        tvDisponible.contentDescription = tvDisponible.text.toString() + ". Transfer funds"
        val impV = etImporte.num()
        tvVolumen.text = "Total trading vol. " + when {
            impV <= 0 -> "--"
            futuros && !(soloReducir && tipoUi != 0) -> fmtSim(impV * apal) + " VUSD"
            futuros || (!compra) -> fmtCant(impV) + " " + a.simbolo
            else -> fmtSim(impV) + " VUSD" }
        tvEstimado.text = if (act0 > 0) "Est. filled price " + fmtSim(when (tipoUi) { 1, 3 -> etPrecio.num().takeIf { it > 0 } ?: act0; 2 -> etDisparo.num().takeIf { it > 0 } ?: act0; else -> act0 }) else ""
        val p = when (tipoUi) { 1, 3 -> etPrecio.num(); 2 -> etDisparo.num(); else -> precioActual() ?: 0.0 }
        val imp = etImporte.num()
        if (p <= 0 || imp <= 0) {
            tvResumen.text = (if (futuros) "Available %s VUSD in Futures · ${if (sim.hedge) "hedge" else "one-way"} mode\nFees: taker 0.05%% · maker 0.02%% · funding 0.01%% / 8h · min 5 VUSD"
                else "Available %s VUSD in Spot\nFee: 0.1%% · min order 5 VUSD").format(fmtSim(if (futuros) sim.fut else sim.spot))
            return
        }
        tvResumen.text = when {
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
                when {
                    lp <= 0 -> "Enter a limit price"
                    compra && lp >= actual -> "A limit buy must be below the current price (use Market or Stop)"
                    !compra && lp <= actual -> "A limit sell must be above the current price (use Market or Stop)"
                    else -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.LIMIT, lp, 0.0, 0.0, importe, qty, apal,
                        if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
                }
            }
            2 -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.STOP, 0.0, etDisparo.num(), 0.0, importe, qty, apal,
                if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
            3 -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.STOP_LIMIT, etPrecio.num(), etDisparo.num(), 0.0, importe, qty, apal,
                if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
            4 -> sim.nuevaOrden(m, a.clave, js, a.simbolo, compra, Simulador.TipoOrden.TRAIL, 0.0, 0.0, etCallback.num() / 100, importe, qty, apal,
                if (futuros) tp else 0.0, if (futuros) sl else 0.0, reducir, cruzado, actual)
            else -> when {
                futuros -> sim.abrirFuturo(a.clave, js, a.simbolo, compra, imp, apal, actual, tp, sl, cruzado = cruzado)
                compra -> {
                    val antes = sim.tenencias.firstOrNull { it.clave == a.clave }?.qty ?: 0.0
                    sim.comprarSpot(a.clave, js, a.simbolo, imp, actual) ?: if (tp > 0 || sl > 0) {
                        val q = (sim.tenencias.firstOrNull { it.clave == a.clave }?.qty ?: 0.0) - antes
                        sim.ocoSpot(a.clave, q, tp, sl)?.let { "Bought, but TP/SL not set: $it" }
                    } else null
                }
                else -> sim.venderSpot(a.clave, imp, actual)
            }
        }
        if (err != null) { aviso(err); guardar(); refrescar(); return }
        aviso(if (tipoUi != 0) "Order placed" else "Order filled at ${fmtSim(actual)}")
        etImporte.setText(""); etTp.setText(""); etSl.setText("")
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
        background = Ui.cardBg(AppTheme.R_CHIP, DexTema.CHIP, act); setPadding(dp(12), dp(6), dp(12), dp(6))
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
        val noReal = sim.pnlNoRealizado()
        tvPatrimonio.text = "%s VUSD".format(fmtSim(sim.patrimonio()))
        tvCuenta.text = "Spot %s · Futures %s VUSD available%s\nUnrealized PnL %s · total return %s".format(
            fmtSim(sim.spot), fmtSim(sim.fut), if (sim.earn > 0) " · Earn ${fmtSim(sim.earn)}" else "", signo(noReal), signo(sim.rendimiento()))
        tvCuenta.setTextColor(if (noReal >= 0) DexTema.TXT2 else DexTema.DOWN)
        contPos.removeAllViews()

        if (sim.posiciones.isNotEmpty()) {
            contPos.addView(titulo("Positions (${sim.posiciones.size})"))
            for (p in sim.posiciones.sortedByDescending { it.clave == a.clave }) {
                val m = precioDe(p.clave); val pnl = m?.let { p.pnl(it) } ?: 0.0
                val card = tarjeta()
                card.addView(cabeceraTarjeta("${if (p.long) "Long" else "Short"} ${p.apal}x · ${p.simbolo}${if (p.cruzado) " · cross" else ""}", if (p.long) DexTema.UP else DexTema.DOWN,
                    "%s (%s%.2f%%)".format(signo(pnl), if (pnl >= 0) "+" else "", pnl / p.margen * 100), if (pnl >= 0) DexTema.UP else DexTema.DOWN))
                card.addView(dato("Entry / mark", "${fmtSim(p.entrada)} / ${m?.let { fmtSim(it) } ?: "—"}"))
                card.addView(dato("Size · margin", "${fmtCant(p.qty)} ${p.simbolo} · ${fmtSim(p.margen)} VUSD"))
                card.addView(dato("Position ID", "#${p.id} · ${if (p.cruzado) "cross" else "isolated"} · ${if (sim.hedge) "hedge" else "one-way"}"))
                val liqP = sim.liquidacion(p)
                card.addView(dato("Liquidation", if (liqP <= 0) "none (cross balance covers it)" else fmtSim(liqP), DexTema.WARN))
                card.addView(dato("TP / SL", "${if (p.tp > 0) fmtSim(p.tp) else "—"} / ${if (p.sl > 0) fmtSim(p.sl) else "—"}"))
                if (p.funding != 0.0) card.addView(dato("Funding", signo(p.funding), if (p.funding >= 0) DexTema.UP else DexTema.DOWN))
                card.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
                    addView(botonPeq("TP/SL", DexTema.TXT) { editarTpSl(p) })
                    if (!p.cruzado) addView(botonPeq("+ Margin", DexTema.TXT) { pedirMargen(p) })
                    addView(botonPeq("50%", DexTema.DOWN) {
                        val px = precioDe(p.clave); if (px == null) { aviso("No price for ${p.simbolo} yet"); return@botonPeq }
                        sim.cerrarFuturo(p.id, px, fraccion = 0.5); guardar(); refrescar(); aviso("Closed 50% at ${fmtSim(px)}")
                    })
                    addView(botonPeq("Close", DexTema.DOWN) {
                        val px = precioDe(p.clave); if (px == null) { aviso("No price for ${p.simbolo} yet"); return@botonPeq }
                        sim.cerrarFuturo(p.id, px); guardar(); refrescar(); aviso("Closed at ${fmtSim(px)}")
                    })
                })
                contPos.addView(card)
            }
        }
        if (sim.tenencias.any { it.qty > 1e-12 }) {
            contPos.addView(titulo("Spot holdings"))
            for (h in sim.tenencias.filter { it.qty > 1e-12 }) {
                val m = precioDe(h.clave); val valor = m?.let { it * h.qty }; val pnl = valor?.let { it - h.coste } ?: 0.0
                val card = tarjeta()
                card.addView(cabeceraTarjeta("${fmtCant(h.qty)} ${h.simbolo}", DexTema.TXT,
                    "%s (%s%.2f%%)".format(signo(pnl), if (pnl >= 0) "+" else "", if (h.coste > 0) pnl / h.coste * 100 else 0.0), if (pnl >= 0) DexTema.UP else DexTema.DOWN))
                card.addView(dato("Avg cost / price", "${fmtSim(h.coste / h.qty)} / ${m?.let { fmtSim(it) } ?: "—"}"))
                card.addView(dato("Value", valor?.let { fmtSim(it) + " VUSD" } ?: "—"))
                card.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
                    addView(botonPeq("TP/SL", DexTema.TXT) { ocoTenencia(h) })
                    addView(botonPeq("Sell all", DexTema.DOWN) {
                        val px = precioDe(h.clave); if (px == null) { aviso("No price for ${h.simbolo} yet"); return@botonPeq }
                        sim.venderSpot(h.clave, h.qty, px)?.let { aviso(it) }; guardar(); refrescar()
                    })
                })
                contPos.addView(card)
            }
        }
        if (sim.ordenes.isNotEmpty()) {
            contPos.addView(titulo("Open orders (${sim.ordenes.size})"))
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
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
                    addView(botonPeq("Cancel", DexTema.TXT) { sim.cancelarOrden(o.id); guardar(); refrescar() })
                })
                contPos.addView(card)
            }
        }
        if (sim.bots.isNotEmpty()) {
            contPos.addView(titulo("Bots (${sim.bots.size})"))
            for (b in sim.bots) contPos.addView(tarjetaBot(b))
        }
        if (sim.posiciones.isEmpty() && sim.ordenes.isEmpty() && sim.tenencias.none { it.qty > 1e-12 } && sim.bots.isEmpty())
            contPos.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
                text = "No open positions or orders. Prices update while the Home is open (positions on other assets are checked every 20 s)."
                setPadding(dp(2), dp(10), 0, 0)
            })

        // líneas sobre el gráfico del activo abierto
        // qué se pinta lo decide "Chart Settings → Order display"
        val g = act.getSharedPreferences("home_chart", Context.MODE_PRIVATE)
        val vPos = g.getBoolean("o_pos", true); val vTpSl = g.getBoolean("o_tpsl", true); val vTrig = g.getBoolean("o_trig", true)
        val vLiq = g.getBoolean("o_liq", true); val vBe = g.getBoolean("o_be", false)
        val lineas = ArrayList<GraficoTA.LineaPrecio>()
        for (p in sim.posiciones.filter { it.clave == a.clave }) {
            if (vPos) lineas.add(GraficoTA.LineaPrecio(p.entrada.toFloat(), DexTema.LINK, "${if (p.long) "Long" else "Short"} ${p.apal}x"))
            if (vLiq) sim.liquidacion(p).takeIf { it > 0 }?.let { lineas.add(GraficoTA.LineaPrecio(it.toFloat(), DexTema.WARN, "Liq")) }
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
            lineas.add(GraficoTA.LineaPrecio(px.toFloat(), DexTema.LINK, t))
        }
        if (vTrig) for (b in sim.bots.filter { it.clave == a.clave && it.tipo == "GRID" }) {
            lineas.add(GraficoTA.LineaPrecio(b.hi.toFloat(), DexTema.TXT2, "Grid high"))
            lineas.add(GraficoTA.LineaPrecio(b.lo.toFloat(), DexTema.TXT2, "Grid low"))
        }
        chart.lineasPrecio = lineas
        resumen()
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
        Thread {
            val res = trabajos.map { (a, desde) ->
                val n = ((ahora - desde) / 60 + 3).toInt().coerceIn(3, 1000)
                Triple(a, desde, try { Mercado.velas(a, "1m", n) } catch (e: Throwable) { emptyList() })
            }
            act.runOnUiThread {
                vigilando = false
                var cambio = false
                for ((a, desde, v) in res) {
                    if (v.isEmpty()) continue
                    for (k in v) if (k.t >= desde) cambio = sim.procesar(a.clave, k.l.toDouble(), k.h.toDouble(), k.c.toDouble(), k.t + 60, k.t) or cambio
                    val u = v.last().c.toDouble(); cambio = sim.procesar(a.clave, u, u, u, ahora) or cambio
                }
                notificar(); guardar(); if (vista.isAttachedToWindow) refrescar()
            }
        }.start()
    }

    // ── historial y estadísticas ──
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
        sec("Closed trades")
        caja.addView(dato("Trades", st.operaciones.toString()))
        caja.addView(dato("Win rate", if (st.operaciones > 0) "%.1f%% (%d / %d)".format(st.ganadoras * 100.0 / st.operaciones, st.ganadoras, st.operaciones) else "—"))
        caja.addView(dato("Realized PnL", signo(st.pnl), if (st.pnl >= 0) DexTema.UP else DexTema.DOWN))
        caja.addView(dato("Best / worst", "${signo(st.mejor)} / ${signo(st.peor)}"))
        caja.addView(dato("Fees paid", fmtSim(st.comisiones) + " VUSD"))
        val curva = sim.curva()
        if (curva.size > 1) {
            sec("Realized PnL over time")
            caja.addView(GraficoPrecio(act).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
                poner(curva.map { it.second.toFloat() })
            })
        }
        sec("Trade history")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        if (sim.historial.isEmpty()) caja.addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply { text = "No trades yet." })
        for (h in sim.historial.asReversed().take(150)) {
            caja.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6))
                val cerrada = h.salida > 0
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(texto(AppTheme.SP_CAPTION, DexTema.TXT, true).apply {
                        text = "${if (h.mercado == Simulador.Mercado.FUT) "Futures" else "Spot"} · ${h.lado} ${h.simbolo}"
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (cerrada) addView(texto(AppTheme.SP_CAPTION, if (h.pnl >= 0) DexTema.UP else DexTema.DOWN, true).apply { text = signo(h.pnl) })
                })
                addView(texto(AppTheme.SP_MICRO, DexTema.TXT2).apply {
                    text = sdf.format(java.util.Date(h.t * 1000)) + " · " + h.motivo + " · " +
                        (if (cerrada) "${fmtSim(h.entrada)} → ${fmtSim(h.salida)}" else "@ ${fmtSim(h.entrada)}") +
                        " · ${fmtCant(h.qty)} · fee ${fmtSim(h.comision)}"
                })
            })
        }
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
}
