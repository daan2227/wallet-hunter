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
    private var limite = false
    private var apal = prefs.getInt("apal", 10).coerceIn(1, 125)
    private var conTpSl = false

    val vista = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }

    // ── piezas ──
    private fun texto(sp: Float, color: Int, negrita: Boolean = false) = TextView(act).apply {
        textSize = sp; setTextColor(color); typeface = if (negrita) AppTheme.bold(act) else AppTheme.body(act)
    }
    private fun chip(t: String, alPulsar: () -> Unit) = TextView(act).apply {
        text = t; textSize = AppTheme.SP_CAPTION; gravity = Gravity.CENTER; typeface = AppTheme.medium(act)
        setPadding(dp(11), dp(7), dp(11), dp(7)); isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { alPulsar() }
    }
    private fun marcarChip(c: TextView, on: Boolean) {
        c.background = Ui.cardBg(AppTheme.R_CHIP, if (on) AppTheme.BG_KEY else AppTheme.BG_CARD, act)
        c.setTextColor(if (on) AppTheme.TXT_PRI else AppTheme.TXT_SEC); c.typeface = if (on) AppTheme.bold(act) else AppTheme.medium(act)
    }
    private fun campo(hint: String) = Ui.input(act, hint, true).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }
    private fun EditText.num(): Double = text.toString().replace(",", "").replace(" ", "").toDoubleOrNull() ?: 0.0
    private fun fila(vararg v: View) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; v.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    private fun titulo(t: String) = texto(AppTheme.SP_CAPTION, AppTheme.TXT_SEC, true).apply {
        text = t.uppercase(); letterSpacing = 0.08f; setPadding(dp(2), dp(14), 0, dp(8))
    }
    private fun usd(x: Double) = fmtSim(x)
    private fun signo(x: Double) = (if (x >= 0) "+" else "") + fmtSim(x)
    private fun aviso(t: String) = Toast.makeText(act, t, Toast.LENGTH_SHORT).show()
    private fun guardar() = prefs.edit().putString("estado", sim.aJson()).apply()

    // ── cabecera y cuenta ──
    private val tvPatrimonio = texto(22f, AppTheme.TXT_PRI, true)
    private val tvCuenta = texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC)

    // ── formulario ──
    private val btnCompra = TextView(act); private val btnVenta = TextView(act)
    private lateinit var chipMarket: TextView; private lateinit var chipLimit: TextView
    private val etPrecio = campo("Limit price (USD)")
    private val etImporte = campo("")
    private val filaApal = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val chipsApal = ArrayList<Pair<Int, TextView>>()
    private val contApal = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false; addView(filaApal)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    private val swTpSl = android.widget.Switch(act).apply {
        text = "Take-profit / Stop-loss"; setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_CAPTION; Ui.tintarInterruptor(this)
    }
    private val etTp = campo("Take-profit price (optional)")
    private val etSl = campo("Stop-loss price (optional)")
    private val tvResumen = texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC).apply { setPadding(dp(2), dp(2), 0, dp(8)); setLineSpacing(0f, 1.25f) }
    private val btnAccion = TextView(act).apply {
        textSize = AppTheme.SP_BODY; typeface = AppTheme.bold(act); gravity = Gravity.CENTER; setPadding(0, dp(13), 0, dp(13))
        isClickable = true; foreground = Ui.toque()
    }
    private val contPos = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    init { construir(); refrescar() }

    private fun construir() {
        val btnHist = chip("History & stats") { abrirHistorial() }.apply { marcarChip(this, false) }
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(titulo("Trading simulator").apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(btnHist)
        })
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, act)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(texto(AppTheme.SP_MICRO, AppTheme.WARN, true).apply {
                text = "TEST MODE · VUSD is a virtual test currency: no value, not real money, never leaves this phone"
                setPadding(0, 0, 0, dp(6))
            })
            addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC).apply { text = "Total equity" })
            addView(tvPatrimonio); addView(tvCuenta)
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
                addView(botonPeq("Deposit", AppTheme.OK) { dialogoFondos(0) }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
                addView(botonPeq("Withdraw", AppTheme.TXT_PRI) { dialogoFondos(1) })
                addView(botonPeq("Transfer", AppTheme.ACCENT2) { dialogoFondos(2) })
            })
        })

        vista.addView(Ui.segmented(act, listOf("Spot" to null, "Futures" to null), if (futuros) 1 else 0) { k ->
            futuros = k == 1; prefs.edit().putBoolean("futuros", futuros).apply(); refrescarFormulario()
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })

        for ((b, esCompra) in listOf(btnCompra to true, btnVenta to false)) b.apply {
            textSize = AppTheme.SP_BODY; typeface = AppTheme.bold(act); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
            isClickable = true; foreground = Ui.toque()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (esCompra) marginEnd = dp(4) else marginStart = dp(4) }
            setOnClickListener { compra = esCompra; refrescarFormulario() }
        }
        vista.addView(fila(btnCompra, btnVenta))

        chipMarket = chip("Market") { limite = false; refrescarFormulario() }
        chipLimit = chip("Limit") { limite = true; refrescarFormulario() }
        vista.addView(fila(chipMarket, chipLimit))
        vista.addView(etPrecio)

        for (x in listOf(1, 2, 3, 5, 10, 20, 25, 50, 75, 100, 125)) {
            val c = chip("${x}x") { apal = x; prefs.edit().putInt("apal", apal).apply(); refrescarFormulario() }
            chipsApal.add(x to c); filaApal.addView(c)
        }
        filaApal.addView(chip("✎") { pedirApal() }.apply { marcarChip(this, false) })
        vista.addView(contApal)

        vista.addView(etImporte)
        val pcts = listOf(10, 25, 50, 75, 100)
        vista.addView(fila(*pcts.map { p -> chip("$p%") { porcentaje(p) }.apply { marcarChip(this, false) } }.toTypedArray()))

        swTpSl.setOnCheckedChangeListener { _, b -> conTpSl = b; refrescarFormulario() }
        vista.addView(swTpSl); vista.addView(etTp); vista.addView(etSl)
        val vigila = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = resumen()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(etPrecio, etImporte, etTp, etSl).forEach { it.addTextChangedListener(vigila) }
        vista.addView(tvResumen)
        btnAccion.setOnClickListener { ejecutar() }
        vista.addView(btnAccion)
        vista.addView(contPos)
        refrescarFormulario()
    }

    /** 0 depositar, 1 retirar, 2 transferir (VUSD de prueba). */
    private fun dialogoFondos(tipo: Int) {
        var cuenta = if (futuros) Simulador.Mercado.FUT else Simulador.Mercado.SPOT
        val et = Ui.input(act, "Amount (VUSD)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val tvInfo = texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC).apply { setPadding(0, dp(6), 0, 0) }
        fun info() { tvInfo.text = when (tipo) {
            0 -> "Adds test VUSD to ${sim.nombre(cuenta)} (max ${fmtSim(Simulador.MAX_DEPOSITO)} per deposit). It is counted as a deposit, not as profit."
            1 -> "Removes VUSD from ${sim.nombre(cuenta)}: ${fmtSim(if (cuenta == Simulador.Mercado.SPOT) sim.spot else sim.fut)} available. Nothing is sent anywhere."
            else -> "From ${sim.nombre(cuenta)} (${fmtSim(if (cuenta == Simulador.Mercado.SPOT) sim.spot else sim.fut)} available) to " +
                    sim.nombre(if (cuenta == Simulador.Mercado.SPOT) Simulador.Mercado.FUT else Simulador.Mercado.SPOT)
        } }
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        caja.addView(Ui.segmented(act, if (tipo == 2) listOf("Spot → Futures" to null, "Futures → Spot" to null) else listOf("Spot" to null, "Futures" to null),
            if (cuenta == Simulador.Mercado.SPOT) 0 else 1) { k -> cuenta = if (k == 0) Simulador.Mercado.SPOT else Simulador.Mercado.FUT; info() }
            .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })
        caja.addView(et)
        val rapidos = if (tipo == 0) listOf("1,000" to 1_000.0, "10,000" to 10_000.0, "100,000" to 100_000.0) else listOf("25%" to -25.0, "50%" to -50.0, "Max" to -100.0)
        caja.addView(fila(*rapidos.map { (t, v) -> chip(t) {
            val x = if (v >= 0) v else (if (cuenta == Simulador.Mercado.SPOT) sim.spot else sim.fut) * -v / 100
            et.setText("%.2f".format(java.util.Locale.US, Math.floor(x * 100) / 100))
        }.apply { marcarChip(this, false) } }.toTypedArray()).apply { setPadding(0, dp(4), 0, 0) })
        caja.addView(tvInfo); info()
        androidx.appcompat.app.AlertDialog.Builder(act).setTitle(listOf("Deposit test VUSD", "Withdraw VUSD", "Transfer VUSD")[tipo])
            .setView(caja)
            .setPositiveButton(listOf("Deposit", "Withdraw", "Transfer")[tipo]) { _, _ ->
                val x = et.num()
                val err = when (tipo) { 0 -> sim.depositar(cuenta, x); 1 -> sim.retirar(cuenta, x); else -> sim.transferir(cuenta, x) }
                if (err != null) aviso(err) else { guardar(); refrescar(); aviso("Done") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pedirMargen(p: Simulador.Posicion) {
        val et = Ui.input(act, "VUSD from Futures (${fmtSim(sim.fut)} available)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        androidx.appcompat.app.AlertDialog.Builder(act).setTitle("Add margin · #${p.id}")
            .setView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et)
                addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_MUTED).apply { text = "Current liquidation: ${fmtSim(p.liquidacion)}. More margin moves it further away."; setPadding(0, dp(6), 0, 0) })
            })
            .setPositiveButton("Add") { _, _ -> val err = sim.agregarMargen(p.id, et.num()); if (err != null) aviso(err) else { guardar(); refrescar(); aviso("New liquidation: ${fmtSim(p.liquidacion)}") } }
            .setNegativeButton("Cancel", null).show()
    }

    /** Precio elegido en el libro de órdenes: pasa a orden límite con ese precio. */
    fun ponerPrecioLimite(p: Double) {
        limite = true
        etPrecio.setText(if (p >= 1) "%.2f".format(java.util.Locale.US, p) else "%.8f".format(java.util.Locale.US, p).trimEnd('0').trimEnd('.'))
        refrescarFormulario(); aviso("Limit price set to ${fmtSim(p)}")
    }

    /** El gráfico cambió de activo: rótulos del formulario y líneas. */
    fun alCambiarActivo() { refrescarFormulario(); refrescar() }

    private fun pedirApal() {
        val et = Ui.input(act, "1–125", true).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(apal.toString()) }
        androidx.appcompat.app.AlertDialog.Builder(act).setTitle("Leverage")
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
        val (tC, tV) = if (futuros) "Buy / Long" to "Sell / Short" else "Buy" to "Sell"
        btnCompra.text = tC; btnVenta.text = tV
        btnCompra.background = Ui.cardBg(AppTheme.R_CHIP, if (compra) AppTheme.OK else AppTheme.BG_CARD, act)
        btnVenta.background = Ui.cardBg(AppTheme.R_CHIP, if (!compra) AppTheme.RED else AppTheme.BG_CARD, act)
        btnCompra.setTextColor(if (compra) 0xFF0B0E11.toInt() else AppTheme.TXT_SEC)
        btnVenta.setTextColor(if (!compra) 0xFFFFFFFF.toInt() else AppTheme.TXT_SEC)
        marcarChip(chipMarket, !limite); marcarChip(chipLimit, limite)
        etPrecio.visibility = if (limite) View.VISIBLE else View.GONE
        contApal.visibility = if (futuros) View.VISIBLE else View.GONE
        chipsApal.forEach { (x, c) -> marcarChip(c, x == apal) }
        etImporte.hint = when { futuros -> "Margin (VUSD) · ${apal}x"; compra -> "Amount (VUSD)"; else -> "Quantity (${a.simbolo})" }
        swTpSl.visibility = if (futuros) View.VISIBLE else View.GONE
        val tpsl = futuros && conTpSl
        etTp.visibility = if (tpsl) View.VISIBLE else View.GONE; etSl.visibility = if (tpsl) View.VISIBLE else View.GONE
        btnAccion.text = when {
            futuros -> (if (compra) "Open long " else "Open short ") + a.simbolo + " · ${apal}x"
            else -> (if (compra) "Buy " else "Sell ") + a.simbolo
        } + if (limite) " (limit)" else ""
        btnAccion.background = Ui.cardBg(AppTheme.R_INNER, if (compra) AppTheme.OK else AppTheme.RED, act)
        btnAccion.setTextColor(if (compra) 0xFF0B0E11.toInt() else 0xFFFFFFFF.toInt())
        resumen()
    }

    /** Estimación de lo que hará la orden: tamaño, nocional, comisión, liquidación. */
    private fun resumen() {
        val a = activo()
        val p = if (limite) etPrecio.num() else (precioActual() ?: 0.0)
        val imp = etImporte.num()
        if (p <= 0 || imp <= 0) {
            tvResumen.text = (if (futuros) "Available %s VUSD in Futures\nFees: taker 0.05%% · maker 0.02%% · funding 0.01%% / 8h · isolated margin · min 5 VUSD"
                else "Available %s VUSD in Spot\nFee: 0.1%% · min order 5 VUSD").format(fmtSim(if (futuros) sim.fut else sim.spot))
            return
        }
        tvResumen.text = when {
            futuros -> {
                val noc = imp * apal; val com = noc * if (limite) Simulador.COM_MAKER else Simulador.COM_TAKER
                "Size ≈ %s %s · notional %s VUSD\nFee ≈ %s VUSD · liquidation ≈ %s".format(fmtCant(noc / p), a.simbolo, usd(noc), usd(com),
                    usd(Simulador.liquidacionDe(p, apal, compra)))
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
        val tp = if (futuros && conTpSl) etTp.num() else 0.0
        val sl = if (futuros && conTpSl) etSl.num() else 0.0
        val err: String? = if (limite) {
            val lp = etPrecio.num()
            when {
                lp <= 0 -> "Enter a limit price"
                compra && lp >= actual -> "A limit buy must be below the current price (use Market)"
                !compra && lp <= actual -> "A limit sell must be above the current price (use Market)"
                futuros -> sim.ordenLimite(Simulador.Mercado.FUT, a.clave, js, a.simbolo, compra, lp, imp, 0.0, apal, tp, sl)
                else -> sim.ordenLimite(Simulador.Mercado.SPOT, a.clave, js, a.simbolo, compra, lp, if (compra) imp else 0.0, if (compra) 0.0 else imp, 1, 0.0, 0.0)
            }
        } else when {
            futuros -> sim.abrirFuturo(a.clave, js, a.simbolo, compra, imp, apal, actual, tp, sl)
            compra -> sim.comprarSpot(a.clave, js, a.simbolo, imp, actual)
            else -> sim.venderSpot(a.clave, imp, actual)
        }
        if (err != null) { aviso(err); return }
        sim.precios[a.clave] = actual
        aviso(if (limite) "Limit order placed" else "Order filled at ${fmtSim(actual)}")
        etImporte.setText(""); etTp.setText(""); etSl.setText("")
        guardar(); refrescar()
    }

    // ── posiciones, tenencias y órdenes ──
    private fun tarjeta() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, act)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    private fun dato(k: String, v: String, color: Int = AppTheme.TXT_PRI) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(1), 0, dp(1))
        addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC).apply { text = k; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        addView(texto(AppTheme.SP_MICRO, color, true).apply { text = v })
    }
    private fun botonPeq(t: String, color: Int, alPulsar: () -> Unit) = TextView(act).apply {
        text = t; textSize = AppTheme.SP_CAPTION; typeface = AppTheme.bold(act); setTextColor(color); gravity = Gravity.CENTER
        background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_KEY, act); setPadding(dp(12), dp(6), dp(12), dp(6))
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
        tvCuenta.text = "Spot %s · Futures %s VUSD available\nUnrealized PnL %s · total return %s".format(
            fmtSim(sim.spot), fmtSim(sim.fut), signo(noReal), signo(sim.rendimiento()))
        tvCuenta.setTextColor(if (noReal >= 0) AppTheme.TXT_SEC else AppTheme.RED)
        contPos.removeAllViews()

        if (sim.posiciones.isNotEmpty()) {
            contPos.addView(titulo("Positions (${sim.posiciones.size})"))
            for (p in sim.posiciones.sortedByDescending { it.clave == a.clave }) {
                val m = precioDe(p.clave); val pnl = m?.let { p.pnl(it) } ?: 0.0
                val card = tarjeta()
                card.addView(cabeceraTarjeta("${if (p.long) "Long" else "Short"} ${p.apal}x · ${p.simbolo}", if (p.long) AppTheme.OK else AppTheme.RED,
                    "%s (%s%.2f%%)".format(signo(pnl), if (pnl >= 0) "+" else "", pnl / p.margen * 100), if (pnl >= 0) AppTheme.OK else AppTheme.RED))
                card.addView(dato("Entry / mark", "${fmtSim(p.entrada)} / ${m?.let { fmtSim(it) } ?: "—"}"))
                card.addView(dato("Size · margin", "${fmtCant(p.qty)} ${p.simbolo} · ${fmtSim(p.margen)} VUSD"))
                card.addView(dato("Position ID", "#${p.id} · isolated"))
                card.addView(dato("Liquidation", fmtSim(p.liquidacion), AppTheme.WARN))
                card.addView(dato("TP / SL", "${if (p.tp > 0) fmtSim(p.tp) else "—"} / ${if (p.sl > 0) fmtSim(p.sl) else "—"}"))
                if (p.funding != 0.0) card.addView(dato("Funding", signo(p.funding), if (p.funding >= 0) AppTheme.OK else AppTheme.RED))
                card.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
                    addView(botonPeq("TP/SL", AppTheme.TXT_PRI) { editarTpSl(p) })
                    addView(botonPeq("+ Margin", AppTheme.TXT_PRI) { pedirMargen(p) })
                    addView(botonPeq("50%", AppTheme.RED) {
                        val px = precioDe(p.clave); if (px == null) { aviso("No price for ${p.simbolo} yet"); return@botonPeq }
                        sim.cerrarFuturo(p.id, px, fraccion = 0.5); guardar(); refrescar(); aviso("Closed 50% at ${fmtSim(px)}")
                    })
                    addView(botonPeq("Close", AppTheme.RED) {
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
                card.addView(cabeceraTarjeta("${fmtCant(h.qty)} ${h.simbolo}", AppTheme.TXT_PRI,
                    "%s (%s%.2f%%)".format(signo(pnl), if (pnl >= 0) "+" else "", if (h.coste > 0) pnl / h.coste * 100 else 0.0), if (pnl >= 0) AppTheme.OK else AppTheme.RED))
                card.addView(dato("Avg cost / price", "${fmtSim(h.coste / h.qty)} / ${m?.let { fmtSim(it) } ?: "—"}"))
                card.addView(dato("Value", valor?.let { fmtSim(it) + " VUSD" } ?: "—"))
                card.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
                    addView(botonPeq("Sell all", AppTheme.RED) {
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
                val lado = if (o.mercado == Simulador.Mercado.FUT) (if (o.compra) "Limit long ${o.apal}x" else "Limit short ${o.apal}x") else (if (o.compra) "Limit buy" else "Limit sell")
                card.addView(cabeceraTarjeta("$lado · ${o.simbolo}", if (o.compra) AppTheme.OK else AppTheme.RED, "@ ${fmtSim(o.precio)}", AppTheme.TXT_PRI))
                card.addView(dato(if (o.mercado == Simulador.Mercado.FUT) "Margin" else if (o.compra) "Amount" else "Quantity",
                    if (o.mercado == Simulador.Mercado.SPOT && !o.compra) "${fmtCant(o.reservado)} ${o.simbolo}" else "${fmtSim(o.importe)} VUSD"))
                card.addView(dato("Order ID", "#${o.id} · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(o.creada * 1000))))
                if (o.tp > 0 || o.sl > 0) card.addView(dato("TP / SL", "${if (o.tp > 0) fmtSim(o.tp) else "—"} / ${if (o.sl > 0) fmtSim(o.sl) else "—"}"))
                card.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
                    addView(botonPeq("Cancel", AppTheme.TXT_PRI) { sim.cancelarOrden(o.id); guardar(); refrescar() })
                })
                contPos.addView(card)
            }
        }
        if (sim.posiciones.isEmpty() && sim.ordenes.isEmpty() && sim.tenencias.none { it.qty > 1e-12 })
            contPos.addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_MUTED).apply {
                text = "No open positions or orders. Prices update while the Home is open (positions on other assets are checked every 20 s)."
                setPadding(dp(2), dp(10), 0, 0)
            })

        // líneas sobre el gráfico del activo abierto
        val lineas = ArrayList<GraficoTA.LineaPrecio>()
        for (p in sim.posiciones.filter { it.clave == a.clave }) {
            lineas.add(GraficoTA.LineaPrecio(p.entrada.toFloat(), AppTheme.BLUE, "${if (p.long) "Long" else "Short"} ${p.apal}x"))
            lineas.add(GraficoTA.LineaPrecio(p.liquidacion.toFloat(), AppTheme.WARN, "Liq"))
            if (p.tp > 0) lineas.add(GraficoTA.LineaPrecio(p.tp.toFloat(), AppTheme.OK, "TP"))
            if (p.sl > 0) lineas.add(GraficoTA.LineaPrecio(p.sl.toFloat(), AppTheme.RED, "SL"))
        }
        for (o in sim.ordenes.filter { it.clave == a.clave })
            lineas.add(GraficoTA.LineaPrecio(o.precio.toFloat(), AppTheme.ACCENT2, if (o.compra) "Buy limit" else "Sell limit"))
        chart.lineasPrecio = lineas
        resumen()
    }

    private fun editarTpSl(p: Simulador.Posicion) {
        val etT = Ui.input(act, "Take-profit (empty = none)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; if (p.tp > 0) setText(p.tp.toString()) }
        val etS = Ui.input(act, "Stop-loss (empty = none)", true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; if (p.sl > 0) setText(p.sl.toString()) }
        androidx.appcompat.app.AlertDialog.Builder(act).setTitle("TP / SL · ${if (p.long) "Long" else "Short"} ${p.simbolo}")
            .setView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(etT); addView(etS)
                addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_MUTED).apply { text = "Liquidation: ${fmtSim(p.liquidacion)}"; setPadding(0, dp(6), 0, 0) })
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
        fun sec(t: String) = caja.addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC, true).apply { text = t.uppercase(); letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(4)) })
        sec("Account")
        caja.addView(texto(AppTheme.SP_MICRO, AppTheme.WARN, true).apply { text = "Test account · VUSD has no value and is not real money" })
        caja.addView(dato("Equity", fmtSim(sim.patrimonio()) + " VUSD"))
        caja.addView(dato("Spot available / in orders", "${fmtSim(sim.spot)} / ${fmtSim(sim.reservado(Simulador.Mercado.SPOT))}"))
        caja.addView(dato("Futures available / in orders", "${fmtSim(sim.fut)} / ${fmtSim(sim.reservado(Simulador.Mercado.FUT))}"))
        caja.addView(dato("Futures margin in use", fmtSim(sim.posiciones.sumOf { it.margen })))
        caja.addView(dato("Net deposits", fmtSim(sim.netoDepositado) + " VUSD"))
        val total = sim.rendimiento()
        caja.addView(dato("Total return", "%s (%s%.2f%%)".format(signo(total), if (total >= 0) "+" else "", if (sim.netoDepositado > 0) total / sim.netoDepositado * 100 else 0.0), if (total >= 0) AppTheme.OK else AppTheme.RED))
        sec("Closed trades")
        caja.addView(dato("Trades", st.operaciones.toString()))
        caja.addView(dato("Win rate", if (st.operaciones > 0) "%.1f%% (%d / %d)".format(st.ganadoras * 100.0 / st.operaciones, st.ganadoras, st.operaciones) else "—"))
        caja.addView(dato("Realized PnL", signo(st.pnl), if (st.pnl >= 0) AppTheme.OK else AppTheme.RED))
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
        if (sim.historial.isEmpty()) caja.addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_MUTED).apply { text = "No trades yet." })
        for (h in sim.historial.asReversed().take(150)) {
            caja.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6))
                val cerrada = h.salida > 0
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(texto(AppTheme.SP_CAPTION, AppTheme.TXT_PRI, true).apply {
                        text = "${if (h.mercado == Simulador.Mercado.FUT) "Futures" else "Spot"} · ${h.lado} ${h.simbolo}"
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (cerrada) addView(texto(AppTheme.SP_CAPTION, if (h.pnl >= 0) AppTheme.OK else AppTheme.RED, true).apply { text = signo(h.pnl) })
                })
                addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC).apply {
                    text = sdf.format(java.util.Date(h.t * 1000)) + " · " + h.motivo + " · " +
                        (if (cerrada) "${fmtSim(h.entrada)} → ${fmtSim(h.salida)}" else "@ ${fmtSim(h.entrada)}") +
                        " · ${fmtCant(h.qty)} · fee ${fmtSim(h.comision)}"
                })
            })
        }
        sec("Account movements")
        if (sim.movimientos.isEmpty()) caja.addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_MUTED).apply { text = "None." })
        for (m in sim.movimientos.asReversed().take(100)) caja.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
            addView(texto(AppTheme.SP_MICRO, AppTheme.TXT_SEC).apply {
                text = "${sdf.format(java.util.Date(m.t * 1000))} · ${m.tipo} · ${sim.nombre(m.cuenta)}\n${m.nota}"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(texto(AppTheme.SP_CAPTION, if (m.importe >= 0) AppTheme.OK else AppTheme.RED, true).apply { text = signo(m.importe) })
        })
        androidx.appcompat.app.AlertDialog.Builder(act).setTitle("Test exchange · VUSD")
            .setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Reset account") { _, _ ->
                androidx.appcompat.app.AlertDialog.Builder(act).setTitle("Reset simulator")
                    .setMessage("Close everything and start again with ${fmtSim(Simulador.BIENVENIDA)} VUSD in Spot and in Futures? History is deleted.")
                    .setPositiveButton("Reset") { _, _ -> sim.reiniciar(); guardar(); refrescar() }
                    .setNegativeButton("Cancel", null).show()
            }.show()
    }
}
