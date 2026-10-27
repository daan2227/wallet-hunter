package com.hunter.btc

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** La conexión NWC guardada, cifrada con la clave del baúl (su secreto permite gastar). */
object NwcGuardado {
    private const val PREFS = "nwc"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val curva = NostrEvento.Curva { op, a, b -> HunterEngine.ec(op, a, b) }

    fun existe(ctx: Context) = p(ctx).contains("uri_e")
    fun guardar(ctx: Context, uri: String): Boolean {
        val dek = WalletManager.claveDeBoveda() ?: return false
        val (e, iv) = SeedEnvelope.sellar(dek, uri.toByteArray())
        return p(ctx).edit().putString("uri_e", android.util.Base64.encodeToString(e, 2)).putString("uri_iv", android.util.Base64.encodeToString(iv, 2)).commit()
    }
    fun leer(ctx: Context): Nwc? {
        val dek = WalletManager.claveDeBoveda() ?: return null
        val e = p(ctx).getString("uri_e", null) ?: return null; val iv = p(ctx).getString("uri_iv", null) ?: return null
        val uri = SeedEnvelope.abrir(dek, android.util.Base64.decode(e, 2), android.util.Base64.decode(iv, 2))?.let { String(it) } ?: return null
        return Nwc.leer(uri, curva)
    }
    fun borrar(ctx: Context) = p(ctx).edit().clear().commit()
}

/**
 * Un nodo Lightning remoto (el tuyo en casa, Alby Hub, LNbits…) manejado por Nostr Wallet Connect: saldo,
 * cobrar con factura, pagar facturas y movimientos. El teléfono no guarda canales; el nodo de casa está
 * siempre en línea. Las peticiones van cifradas por un relay de Nostr (por Tor si está activado).
 */
class NwcActivity : AppCompatActivity() {
    private companion object { const val REQ_QR = 95 }
    private lateinit var caja: LinearLayout
    private var nwc: Nwc? = null
    private var saldo: Long? = null
    private var ocupado = false
    private var alLeer: ((String) -> Unit)? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppTheme.estilo(this)); super.onCreate(savedInstanceState)
        AppTheme.init(this); AppLock.init(this)
        Fiat.cargar(this) { }   // el precio, para el valor junto a los importes
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply { setBackgroundColor(AppTheme.BG_DEEP); isFillViewport = true }
        caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(AppTheme.PAD_SIDE), dp(12), dp(AppTheme.PAD_SIDE), dp(40)) }
        scroll.addView(caja); setContentView(scroll)
        PinAuthHelper.show(this, autoBiometric = false) { ok ->
            if (!ok) { finish(); return@show }
            nwc = NwcGuardado.leer(this); pintar(); if (nwc != null) refrescar()
        }
    }

    private fun texto(t: String, tam: Float = AppTheme.SP_BODY, color: Int = AppTheme.TXT_PRI, negrita: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(color); typeface = if (negrita) AppTheme.bold(context) else AppTheme.body(context); setPadding(0, dp(4), 0, dp(4)) }
    private fun boton(t: String, principal: Boolean = false, accion: () -> Unit) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
        setTextColor(if (principal) AppTheme.ON_ACCENT else AppTheme.TXT_PRI); typeface = if (principal) AppTheme.bold(context) else AppTheme.medium(context)
        background = GradientDrawable().apply { setColor(if (principal) AppTheme.ACCENT else AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        isClickable = true; foreground = Ui.toque(); setOnClickListener { accion() } }

    private fun enHilo(que: String, f: () -> Unit) {
        if (ocupado) return
        ocupado = true; pintar()
        Thread {
            val err = try { f(); null } catch (e: Nwc.Error) { e.message } catch (e: Exception) { RegistroFallos.anotar("NWC.$que", e); e.message ?: e.javaClass.simpleName }
            runOnUiThread { ocupado = false; pintar(); err?.let { AlertDialog.Builder(this).setTitle(que).setMessage(it).setPositiveButton("OK", null).show() } }
        }.start()
    }

    private fun refrescar() { val n = nwc ?: return; enHilo("Balance") { saldo = n.saldoSat() } }

    private fun pintar() {
        caja.removeAllViews()
        caja.addView(Ui.pageTitle(this, "Remote Lightning", lados = false))
        val n = nwc
        if (n == null) {
            caja.addView(texto("Control your own Lightning node (or Alby Hub, LNbits…) with Nostr Wallet Connect. In your node, " +
                "create a connection (a nostr+walletconnect:// link), ideally with a spending limit, and paste or scan it here.",
                AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
            caja.addView(boton("Scan connection QR", principal = true) { alLeer = { conectar(it) }; startActivityForResult(Intent(this, QrScanActivity::class.java), REQ_QR) })
            caja.addView(boton("Paste connection link") {
                val et = Ui.input(this, "nostr+walletconnect://…", mono = true)
                AlertDialog.Builder(this).setTitle("Connection").setView(LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(et) })
                    .setNegativeButton("Cancel", null).setPositiveButton("Connect") { _, _ -> conectar(et.text.toString()) }.show()
            })
            return
        }
        caja.addView(texto(if (ocupado) "Asking your node…" else saldo?.let { "%,d sat".format(it) } ?: "—", 26f, AppTheme.ACCENT, true))
        saldo?.let { Fiat.de(this, it) }?.let { caja.addView(texto("≈ $it", AppTheme.SP_CAPTION, AppTheme.TXT_SEC)) }
        caja.addView(texto("via ${n.relays.first()}${if (RedPrivada.activa()) " · through Tor" else ""}", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        caja.addView(boton("Receive (invoice)", principal = true) { recibir() })
        caja.addView(boton("Pay an invoice") { pagarElegir() })
        caja.addView(boton("Recent payments") { enHilo("Payments") {
            val t = n.movimientos(20)
            val txt = (0 until t.length()).joinToString("\n") { i -> t.getJSONObject(i).let { o ->
                "${if (o.optString("type") == "incoming") "+" else "−"}%,d sat  %s".format(o.optLong("amount") / 1000, o.optString("description").take(30)) } }
            runOnUiThread { AlertDialog.Builder(this).setTitle("Recent payments").setMessage(txt.ifEmpty { "None" }).setPositiveButton("OK", null).show() }
        } })
        caja.addView(boton("Refresh") { refrescar() })
        caja.addView(boton("Disconnect (forget this link)") {
            AlertDialog.Builder(this).setTitle("Forget the connection?").setMessage("Remove it also in your node to revoke it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> NwcGuardado.borrar(this); nwc = null; saldo = null; pintar() }.show()
        })
    }

    private fun conectar(uri: String) {
        val n = Nwc.leer(uri, NwcGuardado.curva) ?: run { Toast.makeText(this, "Not a valid nostr+walletconnect:// link.", Toast.LENGTH_LONG).show(); return }
        enHilo("Connect") {
            val (metodos, _) = n.info()
            val s = n.saldoSat()
            if (!NwcGuardado.guardar(this, uri)) throw IllegalStateException("Unlock the app first")
            nwc = n; saldo = s
            if (metodos.isNotEmpty() && "pay_invoice" !in metodos)
                runOnUiThread { Toast.makeText(this, "This connection cannot pay (receive-only).", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_QR && res == RESULT_OK) data?.getStringExtra(QrScanActivity.EXTRA_TEXTO)?.let { alLeer?.invoke(it) }
    }

    private fun recibir() {
        val n = nwc ?: return
        val etSat = Ui.input(this, "Amount in sat").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etDesc = Ui.input(this, "Description")
        AlertDialog.Builder(this).setTitle("Receive").setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0); addView(etSat); addView(etDesc) })
            .setNegativeButton("Cancel", null).setPositiveButton("Create invoice") { _, _ ->
                val s = etSat.text.toString().toLongOrNull() ?: 0
                if (s <= 0) { Toast.makeText(this, "Enter an amount.", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                enHilo("Invoice") {
                    val f = n.factura(s, etDesc.text.toString().ifBlank { "Hunter Suite" })
                    runOnUiThread {
                        val lado = (resources.displayMetrics.widthPixels * 0.75f).toInt()
                        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(16), dp(16), 0)
                            addView(ImageView(context).apply { setImageBitmap(QrAnimado.qr(f.uppercase(), lado)) }, LinearLayout.LayoutParams(lado, lado)) }
                        AlertDialog.Builder(this).setTitle("Invoice · %,d sat".format(s)).setView(v)
                            .setPositiveButton("Copy") { _, _ -> (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("invoice", f)); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() }
                            .setNegativeButton("Close", null).show()
                    }
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

    private fun pagarElegir() {
        val et = Ui.input(this, "lnbc… invoice", mono = true)
        AlertDialog.Builder(this).setTitle("Pay").setView(LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(et) })
            .setNeutralButton("Scan QR") { _, _ -> alLeer = { pagar(it) }; startActivityForResult(Intent(this, QrScanActivity::class.java), REQ_QR) }
            .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> pagar(et.text.toString()) }.show()
    }

    private fun pagar(f0: String) {
        val n = nwc ?: return
        val f = f0.trim().removePrefix("lightning:").removePrefix("LIGHTNING:")
        if (!f.lowercase().startsWith("lnbc") && !f.lowercase().startsWith("lntb")) { Toast.makeText(this, "Not a Lightning invoice.", Toast.LENGTH_LONG).show(); return }
        // El importe lo dice la factura (la comprueba el nodo): aquí se enseña lo que pone su parte legible.
        val importe = Regex("^ln(?:bc|tb|bcrt)(\\d+)([munp]?)1", RegexOption.IGNORE_CASE).find(f)?.let { m ->
            val v = m.groupValues[1].toLong(); when (m.groupValues[2].lowercase()) { "m" -> v * 100_000; "u" -> v * 100; "n" -> v / 10; "p" -> v / 10_000; else -> v * 100_000_000 } }
        AlertDialog.Builder(this).setTitle("Pay with your remote node?")
            .setMessage((importe?.let { "%,d sat".format(it) + Fiat.entre(this, it) } ?: "Amount set by the invoice") + "\n\n${f.take(40)}…")
            .setNegativeButton("Cancel", null).setPositiveButton("Pay") { _, _ ->
                conCodigoSiToca(importe) { enHilo("Pay") { n.pagar(f); saldo = try { n.saldoSat() } catch (e: Exception) { saldo }
                    runOnUiThread { Toast.makeText(this, "Paid", Toast.LENGTH_LONG).show() } } }
            }.show()
    }
}
