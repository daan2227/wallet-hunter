package com.hunter.btc

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Lightning (experimental): un nodo LDK propio dentro de la app. Recibir con factura, pagar facturas,
 * abrir canales con monedas en cadena y retirar. El nodo funciona mientras la app está abierta; para
 * cobrar, el teléfono tiene que estar en línea.
 */
class LightningActivity : AppCompatActivity() {
    companion object { const val EXTRA_TESTNET = "testnet"; private const val REQ_QR = 91 }

    private lateinit var caja: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val log = ArrayDeque<String>()
    private var testnet = false
    private var ocupado = false
    private var alLeer: ((String) -> Unit)? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(savedInstanceState)
        AppTheme.init(this); AppLock.init(this)
        Fiat.cargar(this) { }   // el precio, para el valor junto a los importes
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        testnet = intent.getBooleanExtra(EXTRA_TESTNET, false)
        val scroll = ScrollView(this).apply { setBackgroundColor(AppTheme.BG_DEEP); isFillViewport = true }
        caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(AppTheme.PAD_SIDE), dp(12), dp(AppTheme.PAD_SIDE), dp(40)) }
        scroll.addView(caja); setContentView(scroll)
        pintar()
    }

    private val sondeo = object : Runnable {
        override fun run() {
            val m = Ln.motor
            if (m != null) Thread {
                var cambio = false
                while (true) { val e = try { m.siguienteEvento() } catch (x: Exception) { null } ?: break; log.addFirst(e); cambio = true
                    runOnUiThread { Toast.makeText(this@LightningActivity, e, Toast.LENGTH_SHORT).show() } }
                while (log.size > 20) log.removeLast()
                if (cambio) runOnUiThread { pintar() }
            }.start()
            h.postDelayed(this, 2000)
        }
    }
    override fun onResume() { super.onResume(); h.post(sondeo) }
    override fun onPause() { super.onPause(); h.removeCallbacks(sondeo) }

    private fun texto(t: String, tam: Float = AppTheme.SP_BODY, color: Int = AppTheme.TXT_PRI, negrita: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(color); typeface = if (negrita) AppTheme.bold(context) else AppTheme.body(context); setPadding(0, dp(4), 0, dp(4))
    }
    private fun boton(t: String, principal: Boolean = false, accion: () -> Unit) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
        setTextColor(if (principal) AppTheme.ON_ACCENT else AppTheme.TXT_PRI)
        typeface = if (principal) AppTheme.bold(context) else AppTheme.medium(context)
        background = GradientDrawable().apply { setColor(if (principal) AppTheme.ACCENT else AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        isClickable = true; foreground = Ui.toque(); setOnClickListener { accion() }
    }

    /** Ejecuta [f] fuera del hilo principal y enseña el error, si lo hay. */
    private fun enHilo(que: String, f: () -> Unit) {
        if (ocupado) return
        ocupado = true; pintar()
        Thread {
            val err = try { f(); null } catch (e: Exception) { RegistroFallos.anotar("Lightning.$que", e); e.message ?: e.javaClass.simpleName }
            runOnUiThread { ocupado = false; pintar(); err?.let { AlertDialog.Builder(this).setTitle(que).setMessage(it).setPositiveButton("OK", null).show() } }
        }.start()
    }

    private fun pintar() {
        caja.removeAllViews()
        caja.addView(Ui.pageTitle(this, "Lightning", lados = false))
        caja.addView(texto("Experimental. Use small amounts: the channels' state lives in this phone, and the node's " +
            "24 words alone do not recover money that is in channels. Lightning traffic does not go through Tor.",
            AppTheme.SP_CAPTION, AppTheme.WARN))
        val m = Ln.motor
        if (m == null) {
            caja.addView(texto(if (testnet) "Testnet" else "Mainnet", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
            caja.addView(boton(if (ocupado) "Starting…" else "Start the node", principal = true) {
                PinAuthHelper.show(this, autoBiometric = false) { ok -> if (ok) enHilo("Start") { Ln.arrancar(this, testnet) } }
            })
            caja.addView(boton("Liquidity provider (LSP) for receiving") { lspDialogo() })
            val (f, u) = Ln.fuente(this)
            caja.addView(boton("Chain source: " + when (f) { "electrum" -> "Electrum $u"; "core" -> "my Bitcoin Core"; else -> "Esplora ${u.ifEmpty { "(blockstream.info)" }}" }) { fuenteDialogo() })
            return
        }
        val (cadena, canales) = try { m.saldos() } catch (e: Exception) { 0L to 0L }
        caja.addView(texto("%,d sat".format(canales), 26f, AppTheme.ACCENT, true))
        Fiat.de(this, canales)?.let { caja.addView(texto("≈ $it", AppTheme.SP_CAPTION, AppTheme.TXT_SEC)) }
        caja.addView(texto("in channels · %,d sat on-chain (node wallet)".format(cadena), AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        caja.addView(boton("Receive (invoice)", principal = true) { recibir() })
        caja.addView(boton("Pay an invoice") { pagarElegir() })
        caja.addView(boton("Add on-chain funds to the node") { fondear() })
        caja.addView(boton("Open a channel") { abrirCanal() })
        caja.addView(boton("Withdraw on-chain funds") { retirar() })
        caja.addView(boton("Withdraw to my main wallet") { retirarACartera() })
        caja.addView(boton("Sync now") { enHilo("Sync") { m.sincronizar() } })
        val cs = try { m.canales() } catch (e: Exception) { emptyList() }
        if (cs.isNotEmpty()) caja.addView(Ui.sectionLabel(this, "Channels", topGap = dp(16)))
        for (c in cs) caja.addView(texto("%,d sat · can send %,d · can receive %,d · %s\n%s…".format(c.capacidad, c.salienteSat, c.entranteSat,
            if (c.listo) "ready" else "pending (${c.confirmaciones} conf.)", c.par.take(20)), AppTheme.SP_CAPTION).apply {
            setOnLongClickListener {
                AlertDialog.Builder(this@LightningActivity).setTitle("Close this channel?")
                    .setMessage("Cooperative close: the balance returns to the node's on-chain wallet after it confirms.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Close") { _, _ -> enHilo("Close channel") { m.cerrarCanal(c) } }.show(); true
            }
        })
        if (log.isNotEmpty()) { caja.addView(Ui.sectionLabel(this, "Events", topGap = dp(16))); log.take(8).forEach { caja.addView(texto(it, AppTheme.SP_CAPTION, AppTheme.TXT_SEC)) } }
        caja.addView(texto("Node ${m.id().take(16)}…", AppTheme.SP_CAPTION, AppTheme.TXT_SEC).apply {
            setOnClickListener { copiar("node id", m.id()) }
        })
        caja.addView(boton("Liquidity provider (LSP)") { lspDialogo() })
        caja.addView(boton("Show the node's 24 words") {
            PinAuthHelper.show(this, autoBiometric = false) { ok -> if (ok) {
                val d = AlertDialog.Builder(this).setTitle("Lightning node words").setMessage(Ln.frase(this) ?: "?").setPositiveButton("OK", null).create()
                d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); d.show() } }
        })
        caja.addView(boton("Stop the node") { Ln.parar(); pintar() })
    }

    private fun copiar(etiqueta: String, t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(etiqueta, t))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun qrDialogo(titulo: String, contenido: String, nota: String) {
        val lado = (resources.displayMetrics.widthPixels * 0.75f).toInt()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(16), dp(16), 0)
            addView(ImageView(context).apply { setImageBitmap(QrAnimado.qr(if (contenido.startsWith("bitcoin:")) contenido else contenido.uppercase(), lado)) }, LinearLayout.LayoutParams(lado, lado))
            addView(texto(nota, AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        }
        AlertDialog.Builder(this).setTitle(titulo).setView(v).setPositiveButton("Copy") { _, _ -> copiar(titulo, contenido) }.setNegativeButton("Close", null).show()
    }

    private fun recibir() {
        val m = Ln.motor ?: return
        val etSat = Ui.input(this, "Amount in sat (empty = any)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etDesc = Ui.input(this, "Description")
        val cbUnif = android.widget.CheckBox(this).apply { text = "Unified QR (on-chain or Lightning; needs an amount)"; setTextColor(AppTheme.TXT_PRI) }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0); addView(etSat); addView(etDesc); addView(cbUnif) }
        AlertDialog.Builder(this).setTitle("Receive").setView(v).setNegativeButton("Cancel", null).setPositiveButton("Create invoice") { _, _ ->
            val sats = etSat.text.toString().toLongOrNull()
            if (cbUnif.isChecked) {
                if (sats == null || sats <= 0) { Toast.makeText(this, "A unified QR needs an amount.", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                Thread {
                    val r = try { Result.success(m.qrUnificado(sats, etDesc.text.toString().ifBlank { "Hunter Suite" })) } catch (e: Exception) { Result.failure(e) }
                    runOnUiThread {
                        r.onSuccess { qrDialogo("Unified QR", it, "The payer's wallet chooses on-chain or Lightning. Keep this screen open until it is paid.") }
                         .onFailure { Toast.makeText(this, "Could not create it: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
                return@setPositiveButton
            }
            Thread {
                // Sin capacidad de entrada suficiente y con LSP configurado: canal "justo a tiempo".
                val jit = Ln.lsp(this) != null && (try { m.entrante() } catch (e: Exception) { 0L }) < (sats ?: 1L)
                val r = try { Result.success(m.factura(sats, etDesc.text.toString().ifBlank { "Hunter Suite" }, jit = jit)) } catch (e: Exception) { Result.failure(e) }
                runOnUiThread {
                    r.onSuccess { qrDialogo("Invoice", it, "Keep this screen open (the node must be online) until it is paid. " +
                        "Without a channel with incoming capacity (or an LSP), it cannot be paid.") }
                     .onFailure { Toast.makeText(this, "Could not create the invoice: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }.show()
    }

    private fun pagarElegir() {
        val et = Ui.input(this, "lnbc… invoice, LNURL or user@domain", mono = true)
        AlertDialog.Builder(this).setTitle("Pay").setView(LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(et) })
            .setNeutralButton("Scan QR") { _, _ -> alLeer = { pagar(it) }; startActivityForResult(Intent(this, QrScanActivity::class.java), REQ_QR) }
            .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> pagar(et.text.toString()) }.show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_QR && res == RESULT_OK) data?.getStringExtra(QrScanActivity.EXTRA_TEXTO)?.let { alLeer?.invoke(it) }
    }

    private val redEsperada get() = if (testnet) org.lightningdevkit.ldknode.Network.TESTNET else org.lightningdevkit.ldknode.Network.BITCOIN

    private fun pagar(fac: String) {
        val m = Ln.motor ?: return
        // Un LNURL o una Lightning Address (usuario@dominio) va por LNURL; una factura (lnbc…) nunca lo parece.
        Lnurl.leer(fac)?.let { lnurl(m, it); return }
        val (sats, desc, red) = try { m.leerFactura(fac) } catch (e: Exception) { Toast.makeText(this, "Not a valid invoice.", Toast.LENGTH_LONG).show(); return }
        val esperada = if (testnet) org.lightningdevkit.ldknode.Network.TESTNET else org.lightningdevkit.ldknode.Network.BITCOIN
        if (red != esperada) { Toast.makeText(this, "That invoice is for another network.", Toast.LENGTH_LONG).show(); return }
        val etLibre = Ui.input(this, "Amount in sat").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val b = AlertDialog.Builder(this).setTitle("Pay this invoice?")
            .setMessage((if (sats != null) "%,d sat".format(sats) + Fiat.entre(this, sats) else "Any amount: enter it below") + (if (desc.isNotBlank()) "\n\n“$desc”" else ""))
            .setNegativeButton("Cancel", null)
        if (sats == null) b.setView(LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(etLibre) })
        b.setPositiveButton("Pay") { _, _ ->
            val libre = etLibre.text.toString().toLongOrNull()
            if (sats == null && (libre == null || libre <= 0)) { Toast.makeText(this, "Enter an amount.", Toast.LENGTH_LONG).show(); return@setPositiveButton }
            conCodigoSiToca(sats ?: libre) { enHilo("Pay") { m.pagar(fac, libre) } }
        }.show()
    }


    // ── LNURL (LUD-06 pagar, LUD-16 Lightning Address, LUD-03 retirar) ──

    private fun aviso(titulo: String, t: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(titulo).setMessage(t).setPositiveButton("OK", null).show()
    }

    private fun lnurl(m: MotorLn, d: Lnurl.Destino) {
        val url = when (d) { is Lnurl.Destino.Url -> d.url; is Lnurl.Destino.Direccion -> Lnurl.urlDe(d) }
        Toast.makeText(this, "Contacting ${Lnurl.dominio(url)}…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = LnurlCliente.get(url)?.let { Lnurl.leerRespuesta(it) } ?: Lnurl.Respuesta.Error("The service did not answer.")
            runOnUiThread {
                when (r) {
                    is Lnurl.Respuesta.Error -> aviso("LNURL", r.motivo)
                    is Lnurl.Respuesta.EsPago -> lnurlPagar(m, r.p, d)
                    is Lnurl.Respuesta.EsRetiro -> lnurlRetirar(m, r.r)
                }
            }
        }.start()
    }

    private fun lnurlPagar(m: MotorLn, p: Lnurl.Pago, d: Lnurl.Destino) {
        val min = (p.minMsat + 999) / 1000; val max = p.maxMsat / 1000
        if (max < min || max < 1) { aviso("LNURL", "The service only accepts amounts below 1 sat."); return }
        val etSat = Ui.input(this, if (min == max) "$min sat" else "Amount: %,d – %,d sat".format(min, max)).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; if (min == max) setText("$min")
        }
        val etCom = if (p.comentarioMax > 0) Ui.input(this, "Comment (optional, up to ${p.comentarioMax} characters)") else null
        val quien = if (d is Lnurl.Destino.Direccion) d.toString() else Lnurl.dominio(p.callback)
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0); addView(etSat); etCom?.let { addView(it) } }
        AlertDialog.Builder(this).setTitle("Pay $quien")
            .setMessage("“${p.descripcion}”" + (p.identificador?.let { "\n$it" } ?: "") + "\nService: ${Lnurl.dominio(p.callback)}")
            .setView(v).setNegativeButton("Cancel", null)
            .setPositiveButton("Pay") { _, _ ->
                val sat = etSat.text.toString().toLongOrNull()
                if (sat == null || sat < min || sat > max) { Toast.makeText(this, "Between %,d and %,d sat.".format(min, max), Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val msat = sat * 1000; val comentario = etCom?.text?.toString()
                conCodigoSiToca(sat) {
                    enHilo("Pay") {
                        val resp = LnurlCliente.get(Lnurl.urlFactura(p, msat, comentario)) ?: throw Exception("The service did not answer.")
                        val f = Lnurl.leerFactura(resp, p)
                        val pr = f.pr ?: throw Exception(f.error ?: "The service did not return an invoice.")
                        // LUD-06: sólo se paga si la factura pide exactamente lo elegido, y en esta red.
                        val (facMsat, red) = m.msatDe(pr)
                        if (red != redEsperada) throw Exception("The service's invoice is for another network.")
                        Lnurl.comprobarImporte(msat, facMsat)?.let { throw Exception(it) }
                        m.pagar(pr)
                        f.accion?.let { a -> runOnUiThread { mostrarAccion(a) } }
                    }
                }
            }.show()
    }

    /** LUD-09: lo que el servicio enseña tras pagar (un mensaje, o un enlace de su mismo dominio). */
    private fun mostrarAccion(a: Lnurl.Accion) {
        if (isFinishing || isDestroyed) return
        when (a) {
            is Lnurl.Accion.Mensaje -> aviso("Message from the service", a.texto)
            is Lnurl.Accion.Enlace -> AlertDialog.Builder(this).setTitle("From the service").setMessage("${a.descripcion}\n\n${a.url}")
                .setNegativeButton("Close", null)
                .setPositiveButton("Open") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(a.url))) }.show()
        }
    }

    private fun lnurlRetirar(m: MotorLn, r: Lnurl.Retiro) {
        val min = maxOf(1, (r.minMsat + 999) / 1000); val max = r.maxMsat / 1000
        if (max < min) { aviso("LNURL", "The service only allows withdrawing less than 1 sat."); return }
        val etSat = Ui.input(this, "Amount: %,d – %,d sat".format(min, max)).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("$max") }
        AlertDialog.Builder(this).setTitle("Receive from ${Lnurl.dominio(r.callback)}")
            .setMessage(r.descripcion.ifBlank { "Withdraw from this service into this node." })
            .setView(LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(etSat) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Receive") { _, _ ->
                val sat = etSat.text.toString().toLongOrNull()
                if (sat == null || sat < min || sat > max) { Toast.makeText(this, "Between %,d and %,d sat.".format(min, max), Toast.LENGTH_LONG).show(); return@setPositiveButton }
                enHilo("Receive") {
                    // Sin capacidad de entrada suficiente y con LSP configurado, la factura abre el canal al cobrar.
                    val jit = Ln.lsp(this) != null && m.entrante() < sat
                    val inv = m.factura(sat, r.descripcion.ifBlank { "LNURL withdraw" }, jit = jit)
                    val resp = LnurlCliente.get(Lnurl.urlRetiro(r, inv)) ?: throw Exception("The service did not answer.")
                    Lnurl.leerOk(resp)?.let { throw Exception(it) }
                    runOnUiThread { Toast.makeText(this, "Requested: the payment arrives when the service pays. Keep this screen open.", Toast.LENGTH_LONG).show() }
                }
            }.show()
    }

    /** Como al enviar en la cadena: por encima del umbral de Perfil (o con la huella activada), el código otra vez. */
    private fun conCodigoSiToca(sats: Long?, accion: () -> Unit) {
        val pide = WalletManager.hasPin(this) &&
            (WalletManager.huellaActivada(this) || sats == null || UmbralEnvio.exige(UmbralEnvio.leer(this), sats))
        if (!pide) { accion(); return }
        PinAuthHelper.pantalla(this, isSetup = false, autoBiometric = false, permitirHuella = false) { ok -> if (ok) accion() }
    }

    private fun fondear() {
        val m = Ln.motor ?: return
        Thread { val a = try { m.direccionCadena() } catch (e: Exception) { null }
            runOnUiThread { if (a == null) Toast.makeText(this, "Could not get an address.", Toast.LENGTH_LONG).show()
                else qrDialogo("Node on-chain address", a, "Send bitcoin here to open channels. It is the node's own wallet, not your other wallets.") } }.start()
    }

    private fun abrirCanal() {
        val m = Ln.motor ?: return
        val etPar = Ui.input(this, "nodeId@host:port", mono = true)
        val etSat = Ui.input(this, "Channel size in sat (e.g. 200000)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0); addView(etPar); addView(etSat) }
        AlertDialog.Builder(this).setTitle("Open a channel").setView(v).setNegativeButton("Cancel", null).setPositiveButton("Open") { _, _ ->
            val s = etSat.text.toString().toLongOrNull() ?: 0
            if (MotorLn.leerPar(etPar.text.toString()) == null) { Toast.makeText(this, "Use nodeId@host:port", Toast.LENGTH_LONG).show(); return@setPositiveButton }
            if (s < 20_000) { Toast.makeText(this, "A channel needs at least ~20 000 sat.", Toast.LENGTH_LONG).show(); return@setPositiveButton }
            enHilo("Open channel") { m.abrirCanal(etPar.text.toString(), s) }
        }.show()
    }

    private fun retirar() {
        val m = Ln.motor ?: return
        val et = Ui.input(this, "Bitcoin address", mono = true)
        AlertDialog.Builder(this).setTitle("Withdraw all on-chain funds of the node").setView(LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(et) })
            .setNegativeButton("Cancel", null).setPositiveButton("Withdraw") { _, _ ->
                val a = et.text.toString().trim()
                if (BtcAddress.validate(a, testnet) !is BtcAddress.Result.Valid) { Toast.makeText(this, "Invalid address.", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val todo = try { m.saldos().first } catch (e: Exception) { null }
                conCodigoSiToca(todo) { enHilo("Withdraw") { val txid = m.vaciarCadena(a); runOnUiThread { copiar("txid", txid) } } }
            }.show()
    }

    /** De dónde sigue la cadena el nodo: Esplora, un servidor Electrum (el propio) o el Bitcoin Core de la app. */
    private fun fuenteDialogo() {
        val (f, u) = Ln.fuente(this)
        val opciones = arrayOf("Esplora (blockstream.info or your own)", "Electrum server (e.g. your Fulcrum/electrs)", "My Bitcoin Core (Core tab, mainnet)")
        val claves = arrayOf("esplora", "electrum", "core")
        var elegida = claves.indexOf(f).coerceAtLeast(0)
        val etUrl = Ui.input(this, "ssl://host:50002 · tcp://192.168.1.10:50001 · https://…", mono = true).apply { setText(u) }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(texto("Applies on the next start. The node's own traffic does not use the app's Tor proxy: " +
                "for privacy prefer your own Electrum server or Core node.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
            addView(etUrl) }
        AlertDialog.Builder(this).setTitle("Chain source")
            .setSingleChoiceItems(opciones, elegida) { _, i -> elegida = i }
            .setView(v)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val clave = claves[elegida]
                try {
                    val cookie = if (clave == "core") "comprobacion:x" else null   // la de verdad se lee al arrancar
                    MotorLn.cadena(clave, etUrl.text.toString(), testnet, cookie, CoreConfig.RPC_PORT)
                    Ln.ponerFuente(this, clave, etUrl.text.toString()); pintar()
                } catch (e: IllegalArgumentException) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            }.show()
    }

    /** Todo lo de la cartera en cadena del nodo a la siguiente dirección sin usar (bc1q) de la cartera principal. */
    private fun retirarACartera() {
        val m = Ln.motor ?: return
        PinAuthHelper.show(this, autoBiometric = false) { ok ->
            if (!ok) return@show
            enHilo("Withdraw") {
                val mn = WalletManager.loadSeed(this) ?: throw IllegalStateException("There is no main wallet (seed) on this phone")
                val (_, libre) = HdScanner.scanBranch(mn, 84, 0, testnet)
                if (libre < 0) throw IllegalStateException("Could not check which address of your wallet is unused (network)")
                val arr = org.json.JSONArray(HunterEngine.deriveAddresses(mn, 84, 0, libre, 1, testnet))
                val dir = arr.getJSONObject(0).getString("addr")
                val txid = m.vaciarCadena(dir)
                runOnUiThread { AlertDialog.Builder(this).setTitle("Sent to your wallet")
                    .setMessage("To $dir (receive #$libre)\nTransaction $txid").setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun lspDialogo() {
        val actual = Ln.lsp(this)
        val etId = Ui.input(this, "LSP node id", mono = true).apply { setText(actual?.first ?: "") }
        val etDir = Ui.input(this, "host:port", mono = true).apply { setText(actual?.second ?: "") }
        val etTok = Ui.input(this, "Token (if the LSP asks for one)").apply { setText(actual?.third ?: "") }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(texto("An LSP (LSPS2) opens a channel to you on the fly when you receive the first payment, for a fee. " +
                "Without one, you can only receive through a channel that someone opened to you. Restart the node to apply.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
            addView(etId); addView(etDir); addView(etTok) }
        AlertDialog.Builder(this).setTitle("Liquidity provider").setView(v).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> Ln.ponerLsp(this, etId.text.toString(), etDir.text.toString(), etTok.text.toString()) }.show()
    }
}
