package com.hunter.btc

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Una cartera cuyas claves están en otro aparato (vigilada por xpub/descriptor; y la multifirma y la
 * herencia, que se apoyan en lo mismo): saldo, direcciones, recibir, preparar la PSBT para que la firme
 * el otro aparato (QR animado o fichero) y, cuando vuelve firmada, finalizarla y difundirla.
 *
 * Aquí no hay ninguna clave privada: lo peor que puede pasar es enseñar algo mal, nunca firmarlo.
 */
class CarteraExternaActivity : AppCompatActivity() {

    companion object { const val EXTRA_ID = "cartera_id"; private const val REQ_QR = 81; private const val REQ_FICHERO = 82 }

    private lateinit var motor: MotorCartera
    private lateinit var entrada: CarterasExternas.Entrada
    private lateinit var caja: LinearLayout
    private val monedas = ArrayList<Moneda>()
    private var libreRecibir = -1
    private var libreCambio = -1
    private var cargando = false
    private var alLeer: ((String) -> Unit)? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(savedInstanceState)
        AppTheme.init(this); AppLock.init(this)
        Fiat.cargar(this) { }
        val e = CarterasExternas.de(this, intent.getStringExtra(EXTRA_ID) ?: "")
        val m = e?.let { CarterasExternas.motorDe(it.descriptor).first }
        if (e == null || m == null) { Toast.makeText(this, "This wallet could not be opened.", Toast.LENGTH_LONG).show(); finish(); return }
        entrada = e; motor = m
        val scroll = ScrollView(this).apply { setBackgroundColor(AppTheme.BG_DEEP); isFillViewport = true }
        caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(AppTheme.PAD_SIDE), dp(12), dp(AppTheme.PAD_SIDE), dp(40)) }
        scroll.addView(caja); setContentView(scroll)
        pintar(); refrescar()
    }

    // ── Pantalla ───────────────────────────────────────────────────────────────────────────────

    private fun texto(t: String, tam: Float = AppTheme.SP_BODY, color: Int = AppTheme.TXT_PRI, negrita: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = tam; setTextColor(color)
            typeface = if (negrita) AppTheme.bold(context) else AppTheme.body(context)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun boton(t: String, principal: Boolean = false, accion: () -> Unit) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
        setTextColor(if (principal) AppTheme.ON_ACCENT else AppTheme.TXT_PRI)
        typeface = if (principal) AppTheme.bold(context) else AppTheme.medium(context)
        background = GradientDrawable().apply { setColor(if (principal) AppTheme.ACCENT else AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        isClickable = true; foreground = Ui.toque(); setOnClickListener { accion() }
    }

    private fun btc(sat: Long) = "%.8f BTC".format(java.util.Locale.US, sat / 1e8)

    private fun pintar() {
        caja.removeAllViews()
        caja.addView(Ui.pageTitle(this, entrada.nombre, lados = false))
        caja.addView(texto(motor.descripcion + if (motor.testnet) " · testnet" else "", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        for (a in motor.avisos) caja.addView(texto("⚠ $a", AppTheme.SP_CAPTION, AppTheme.WARN))
        (motor as? MotorHerencia)?.let { h ->
            caja.addView(texto("The heir can spend any coin that stays unmoved for ${h.bloques} blocks (~${h.bloques / 144} days). " +
                "To keep the heir locked out, send the coins to this same wallet before then: that restarts the clock.",
                AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        }
        (motor as? MotorHucha)?.let { h ->
            val cerrada = h.bloqueada(System.currentTimeMillis() / 1000, altura ?: 0)
            caja.addView(texto(if (cerrada) "🔒 Locked: nothing can spend these coins before ${h.descripcion.substringAfter("from ")}. " +
                "You can keep adding to it." else "🔓 Unlocked: you can spend these coins now (sign with this phone).",
                AppTheme.SP_CAPTION, if (cerrada) AppTheme.WARN else AppTheme.TXT_SEC))
        }
        val total = monedas.sumOf { it.valor }
        val pend = monedas.filter { !it.confirmada }.sumOf { it.valor }
        caja.addView(texto(if (cargando && monedas.isEmpty()) "Checking the network…" else btc(total), 26f, AppTheme.ACCENT, true))
        if (!(cargando && monedas.isEmpty())) Fiat.de(this, total)?.let { caja.addView(texto("≈ $it", AppTheme.SP_CAPTION, AppTheme.TXT_SEC)) }
        if (pend > 0) caja.addView(texto("of which unconfirmed: ${btc(pend)}", AppTheme.SP_CAPTION, AppTheme.WARN))
        caja.addView(boton("Receive") { recibir() })
        caja.addView(boton("Send (prepare PSBT to sign elsewhere)", principal = true) { enviar() })
        caja.addView(boton("Broadcast a signed PSBT or transaction") { difundirElegir() })
        caja.addView(boton("Descriptor (copy / export)") { verDescriptor() })
        caja.addView(boton("Refresh") { refrescar() })
        if (monedas.isNotEmpty()) {
            caja.addView(Ui.sectionLabel(this, "Coins", topGap = dp(16)))
            for (m in monedas.sortedByDescending { it.valor }) {
                val dir = motor.direccion(m.c, m.i) ?: "?"
                caja.addView(texto("${btc(m.valor)}${if (m.confirmada) "" else " (pending)"}\n${if (m.c == 1) "change" else "receive"} #${m.i} · ${dir.take(14)}…${dir.takeLast(6)}",
                    AppTheme.SP_CAPTION, if (m.confirmada) AppTheme.TXT_PRI else AppTheme.WARN).apply {
                    // Tocar copia la dirección entera (para comprobarla en el firmador o en un explorador).
                    isClickable = true; foreground = Ui.toque()
                    setOnClickListener {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("address", dir))
                        Toast.makeText(this@CarteraExternaActivity, "Address copied", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        caja.addView(boton("Remove this wallet from the app") {
            AlertDialog.Builder(this).setTitle("Remove ${entrada.nombre}?")
                .setMessage("Only the descriptor is removed from this phone. The coins are not affected: the keys are on the other device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ -> CarterasExternas.quitar(this, entrada.id); finish() }.show()
        })
    }

    /** Recorre las dos ramas (hueco 20) y junta las monedas de las direcciones usadas. */
    /** Altura de la red (para una hucha con altura de bloque); null hasta saberla. */
    private var altura: Long? = null

    private fun refrescar() {
        if (cargando) return
        cargando = true; pintar()
        Thread {
            BalanceLookup.iniciarMemo()
            (motor as? MotorHucha)?.let { if (!it.esFecha) altura = ChainInfo.tipHeight(motor.testnet)?.toLong() }
            val nuevas = ArrayList<Moneda>(); var fallo = false
            try {
                val ramas = (0..1).map { c -> motor.escanear(c, { a -> BalanceLookup.isUsed(a, motor.testnet) }) }
                for ((c, r) in ramas.withIndex()) {
                    if (r.incompleta) fallo = true
                    for (i in r.usadas) {
                        val dir = motor.direccion(c, i) ?: continue
                        val us = ChainInfo.utxos(dir, motor.testnet)
                        if (us == null) { fallo = true; continue }
                        for (u in us) nuevas.add(Moneda(c, i, u.optString("txid"), u.optInt("vout"), u.optLong("value"), u.optBoolean("confirmed", true)))
                    }
                }
                // Avisos de movimientos: las usadas y la próxima de recibir, con el nombre de la cartera.
                if (AvisosMovimientos.activos(this)) {
                    val dirs = HashMap<String, String>()
                    for ((c, r) in ramas.withIndex()) {
                        r.usadas.forEach { i -> motor.direccion(c, i)?.let { dirs[it] = entrada.nombre } }
                        if (c == 0 && r.primeraLibre >= 0) motor.direccion(0, r.primeraLibre)?.let { dirs[it] = entrada.nombre }
                    }
                    AvisosMovimientos.registrar(this, dirs)
                }
                runOnUiThread {
                    libreRecibir = ramas[0].primeraLibre; libreCambio = ramas[1].primeraLibre
                    monedas.clear(); monedas.addAll(nuevas); cargando = false; pintar()
                    if (fallo) Toast.makeText(this, "Some addresses could not be checked: the balance may be incomplete.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                RegistroFallos.anotar("CarteraExterna.refrescar", e)
                runOnUiThread { cargando = false; pintar(); Toast.makeText(this, "Could not query the chain.", Toast.LENGTH_LONG).show() }
            } finally { BalanceLookup.terminarMemo() }
        }.start()
    }

    // ── Recibir ────────────────────────────────────────────────────────────────────────────────

    private fun recibir() {
        if (libreRecibir < 0) { Toast.makeText(this, "Refresh first: the next unused address is not known yet.", Toast.LENGTH_LONG).show(); return }
        val dir = motor.direccion(0, libreRecibir) ?: return
        val lado = (resources.displayMetrics.widthPixels * 0.7f).toInt()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(16), dp(16), 0)
            addView(ImageView(context).apply { setImageBitmap(QrAnimado.qr("bitcoin:$dir", lado)) }, LinearLayout.LayoutParams(lado, lado))
            addView(texto(dir, AppTheme.SP_CAPTION).apply { typeface = AppTheme.mono(context); setTextIsSelectable(true) })
            addView(texto("Receive #$libreRecibir. Check it on the signing device before sharing it.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        }
        AlertDialog.Builder(this).setTitle("Receive").setView(v)
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("address", dir))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Close", null).show()
    }

    // ── Enviar: la PSBT sin firmar ─────────────────────────────────────────────────────────────

    private fun enviar() {
        (motor as? MotorHucha)?.let { h ->
            // La red rechazaría el gasto: mejor decirlo aquí que con un error al difundir.
            if (h.bloqueada(System.currentTimeMillis() / 1000, altura ?: 0)) {
                AlertDialog.Builder(this).setTitle("Still locked").setMessage("These coins can be spent from ${h.descripcion.substringAfter("from ")}. " +
                    "Not before: the network enforces it.").setPositiveButton("OK", null).show()
                return
            }
        }
        if (monedas.isEmpty()) { Toast.makeText(this, "No coins (or not refreshed yet).", Toast.LENGTH_LONG).show(); return }
        if (libreCambio < 0) { Toast.makeText(this, "The change address is not known yet: refresh first.", Toast.LENGTH_LONG).show(); return }
        val etTo = Ui.input(this, "Destination address", mono = true)
        val etAmt = Ui.input(this, "Amount (BTC)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val etFee = Ui.input(this, "Fee rate (sat/vB)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0); addView(etTo); addView(etAmt); addView(etFee) }
        val her = motor as? MotorHerencia
        val cbHeredero = her?.let { h -> android.widget.CheckBox(this).apply {
            text = "Spend as the heir (only works for coins unmoved for ${h.bloques} blocks)"
            setTextColor(AppTheme.TXT_PRI); isChecked = h.modoHeredero
            v.addView(this)
        } }
        Thread { ChainInfo.fees(motor.testnet)?.let { f -> runOnUiThread { if (etFee.text.isEmpty()) etFee.setText(f.halfHour.toString()) } } }.start()
        AlertDialog.Builder(this).setTitle("Send").setView(v)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Prepare") { _, _ ->
                her?.modoHeredero = cbHeredero?.isChecked == true
                prepararPsbt(etTo.text.toString().trim(), etAmt.text.toString(), etFee.text.toString())
            }
            .show()
    }

    private fun prepararPsbt(to: String, amtTxt: String, feeTxt: String) {
        val destino = when (val r = BtcAddress.validate(to, motor.testnet)) {
            is BtcAddress.Result.Valid -> BtcAddress.scriptPubKey(r.info)
            is BtcAddress.Result.Invalid -> { Toast.makeText(this, "Invalid address: ${r.reason}", Toast.LENGTH_LONG).show(); return }
        }
        val t = amtTxt.replace(',', '.')
        if (t.substringAfter('.', "").length > 8) { Toast.makeText(this, "Bitcoin has at most 8 decimals.", Toast.LENGTH_LONG).show(); return }
        val importe = t.toBigDecimalOrNull()?.movePointRight(8)?.toLong() ?: 0L
        val tasa = feeTxt.toLongOrNull() ?: 0L
        if (importe <= 0) { Toast.makeText(this, "Enter an amount.", Toast.LENGTH_LONG).show(); return }
        if (tasa !in 1..ChainInfo.TARIFA_MAX.toLong()) { Toast.makeText(this, "Fee rate must be between 1 and ${ChainInfo.TARIFA_MAX} sat/vB.", Toast.LENGTH_LONG).show(); return }
        val (elegidas, plan) = motor.elegir(monedas, to, importe, tasa)
            ?: run { Toast.makeText(this, "Not enough balance for the amount plus the fee.", Toast.LENGTH_LONG).show(); return }
        val resumen = "Pays ${btc(importe)} to\n$to\n\nFee: ${plan.feeSat} sat (~${plan.vsize} vB)\n" +
            (if (plan.changeSat > 0) "Change back to this wallet: ${btc(plan.changeSat)} (change #$libreCambio)\n" else "No change output\n") +
            "Inputs: ${elegidas.size}\n\nThe PSBT is NOT signed: the device with the keys signs it. Check the amounts there too."
        AlertDialog.Builder(this).setTitle("Review").setMessage(resumen)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create PSBT") { _, _ ->
                Thread {
                    val tip = ChainInfo.tipHeight(motor.testnet)?.toLong() ?: 0L
                    val p = try {
                        motor.psbt(elegidas, destino, importe, plan.changeSat, libreCambio, tip) { m ->
                            ChainApi.get("/tx/${m.txid}/hex", motor.testnet)?.trim()?.let { h -> Descriptor.bytes(h) }
                        }
                    } catch (e: Exception) { RegistroFallos.anotar("CarteraExterna.psbt", e); null }
                    runOnUiThread {
                        if (p == null) Toast.makeText(this, "Could not build the PSBT (a previous transaction could not be fetched?).", Toast.LENGTH_LONG).show()
                        else compartir(p)
                    }
                }.start()
            }.show()
    }

    private fun compartir(psbt: ByteArray, firmadasAqui: Int = 0) {
        val multi = motor is MotorMultifirma || motor is MotorHerencia || motor is MotorHucha
        val opciones = arrayOf("Show as animated QR (UR / BBQr)", "Share as file", "Copy as base64") +
            (if (multi) arrayOf("Sign with a wallet on this phone") else emptyArray())
        AlertDialog.Builder(this).setTitle(if (firmadasAqui > 0) "PSBT ready · $firmadasAqui signature(s) added here" else "PSBT ready")
            .setItems(opciones) { _, i ->
                when (i) {
                    3 -> {
                        val d = Psbt.parse(psbt) ?: return@setItems
                        FirmaPropia.firmar(this, d) { n ->
                            when {
                                n < 0 -> {}
                                n == 0 -> { Toast.makeText(this, "None of this PSBT's keys belongs to a wallet on this phone.", Toast.LENGTH_LONG).show(); compartir(psbt, firmadasAqui) }
                                else -> {
                                    val nueva = Psbt.serialize(d)
                                    // Si ya hay bastantes firmas, se puede difundir directamente.
                                    val copia = Psbt.parse(nueva)!!
                                    if (Psbt.finalizar(copia) { s2, f -> motor.testigoWsh(s2, f) } == copia.tx.ins.size) procesarBytes(nueva)
                                    else compartir(nueva, firmadasAqui + n)
                                }
                            }
                        }
                    }
                    0 -> QrAnimado.mostrar(this, psbt, "Scan with the signing device")
                    1 -> try {
                        val f = java.io.File(BackupStore.compartidos(this), "psbt_${System.currentTimeMillis()}.psbt"); f.writeBytes(psbt)
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share PSBT"))
                    } catch (e: Exception) { Toast.makeText(this, "Could not save the PSBT: ${e.message}", Toast.LENGTH_LONG).show() }
                    else -> {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
                            android.content.ClipData.newPlainText("psbt", android.util.Base64.encodeToString(psbt, android.util.Base64.NO_WRAP)))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    // ── Difundir lo firmado ────────────────────────────────────────────────────────────────────

    private fun difundirElegir() {
        AlertDialog.Builder(this).setTitle("Signed PSBT or transaction")
            .setItems(arrayOf("Scan QR (animated or not)", "Open a file", "Paste text")) { _, i ->
                when (i) {
                    0 -> { alLeer = { procesarFirmado(it) }
                           startActivityForResult(Intent(this, QrScanActivity::class.java).putExtra(QrScanActivity.EXTRA_ANIMADO, true), REQ_QR) }
                    1 -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, REQ_FICHERO)
                    else -> {
                        val et = Ui.input(this, "base64 PSBT, hex PSBT or raw transaction hex", mono = true)
                        AlertDialog.Builder(this).setTitle("Paste").setView(et).setNegativeButton("Cancel", null)
                            .setPositiveButton("Continue") { _, _ -> procesarFirmado(et.text.toString().trim()) }.show()
                    }
                }
            }.show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == REQ_QR) data.getStringExtra(QrScanActivity.EXTRA_TEXTO)?.let { alLeer?.invoke(it) }
        if (req == REQ_FICHERO) data.data?.let { uri ->
            val b = try { contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { x -> x.size < 4 * 1024 * 1024 } } } catch (e: Exception) { null }
            if (b == null) { Toast.makeText(this, "Could not read the file.", Toast.LENGTH_LONG).show(); return }
            procesarBytes(b)
        }
    }

    private fun procesarFirmado(t0: String) {
        val t = t0.trim()
        val hexa = t.length % 2 == 0 && t.isNotEmpty() && t.all { it in "0123456789abcdefABCDEF" }
        val b = try { if (hexa) Descriptor.bytes(t.lowercase()) else android.util.Base64.decode(t, android.util.Base64.DEFAULT) } catch (e: Exception) { null }
        if (b == null) { Toast.makeText(this, "Not a PSBT or a transaction.", Toast.LENGTH_LONG).show(); return }
        procesarBytes(b)
    }

    /** Una PSBT (se finaliza y extrae) o una transacción ya completa: se revisa y se difunde. */
    private fun procesarBytes(b: ByteArray) {
        val esPsbt = b.size > 5 && b[0] == 0x70.toByte() && b[1] == 0x73.toByte() && b[2] == 0x62.toByte() && b[3] == 0x74.toByte()
        val raw: ByteArray = if (esPsbt) {
            val d = Psbt.parse(b) ?: run { Toast.makeText(this, "The PSBT could not be read.", Toast.LENGTH_LONG).show(); return }
            val n = Psbt.finalizar(d) { s, f -> motor.testigoWsh(s, f) }
            Psbt.extraer(d) ?: run {
                val dlg = AlertDialog.Builder(this).setTitle("Not fully signed yet")
                    .setMessage("$n of ${d.tx.ins.size} inputs have enough signatures. Sign it on the device(s) that are missing, then try again.")
                    .setPositiveButton("OK", null)
                if (motor is MotorMultifirma || motor is MotorHerencia || motor is MotorHucha) dlg.setNeutralButton("Add this phone's signature") { _, _ ->
                    val orig = Psbt.parse(b)!!
                    FirmaPropia.firmar(this, orig) { k -> if (k > 0) procesarBytes(Psbt.serialize(orig)) else if (k == 0)
                        Toast.makeText(this, "None of its keys belongs to a wallet on this phone.", Toast.LENGTH_LONG).show() }
                }
                dlg.show(); return
            }
        } else b
        val tx = try { Psbt.parseTx(raw) } catch (e: Exception) { null }
            ?: run { Toast.makeText(this, "Not a valid transaction.", Toast.LENGTH_LONG).show(); return }
        val propias = monedas.map { it.txid to it.vout }.toSet()
        val gasta = tx.ins.count { Psbt.hexRev(it.txid) to it.vout.toInt() in propias }
        val salidas = tx.outs.joinToString("\n") { o -> "• ${btc(o.value)} → ${SpkDireccion.de(o.spk, motor.testnet) ?: "script"}" }
        val hex = raw.joinToString("") { "%02x".format(it) }
        AlertDialog.Builder(this).setTitle("Broadcast this transaction?")
            .setMessage("It spends ${tx.ins.size} input(s) ($gasta of this wallet) and pays:\n$salidas\n\nA Bitcoin transaction cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Broadcast") { _, _ ->
                Thread {
                    val r = ChainInfo.broadcast(hex, motor.testnet)
                    runOnUiThread {
                        AlertDialog.Builder(this).setTitle(if (r.startsWith("ERROR")) "Not broadcast" else "Sent")
                            .setMessage(if (r.startsWith("ERROR")) r.removePrefix("ERROR: ") else "Transaction ID:\n$r")
                            .setPositiveButton("OK") { _, _ -> refrescar() }.show()
                    }
                }.start()
            }.show()
    }

    private fun verDescriptor() {
        val d = motor.texto()
        AlertDialog.Builder(this).setTitle("Descriptor").setMessage(d)
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("descriptor", d))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Show as QR") { _, _ -> QrAnimado.mostrar(this, d.toByteArray(), "Descriptor", "bytes", 'U') }
            .setNegativeButton("Close", null).show()
    }
}
