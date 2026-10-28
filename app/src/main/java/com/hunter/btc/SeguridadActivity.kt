package com.hunter.btc

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Security check": revisa TUS semillas y carteras y da una nota con lo que conviene arreglar. La parte sin
 * red (frase, código, copia, capturas) sale al abrir; "Deep check" mira además la cadena (reutilización de
 * direcciones y nonce de firma repetido). Pide el código: hace falta la sesión abierta para leer las frases.
 *
 * Nada sale del teléfono salvo las consultas de la revisión profunda, y esas van por Tor si está activado.
 */
class SeguridadActivity : AppCompatActivity() {

    private lateinit var caja: LinearLayout
    private val carteras = ArrayList<AuditoriaSeguridad.Entrada>()
    private lateinit var ajustes: AuditoriaSeguridad.Ajustes
    private var profundo = false
    private var ocupado = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppTheme.estilo(this)); super.onCreate(savedInstanceState)
        AppTheme.init(this); AppLock.init(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)   // enseña frases y claves
        val scroll = ScrollView(this).apply { setBackgroundColor(AppTheme.BG_DEEP); isFillViewport = true }
        caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(AppTheme.PAD_SIDE), dp(12), dp(AppTheme.PAD_SIDE), dp(40)) }
        scroll.addView(caja); setContentView(scroll)
        PinAuthHelper.show(this, autoBiometric = false) { ok -> if (!ok) { finish(); return@show }; reunir(); pintar() }
    }

    private fun reunir() {
        ajustes = AuditoriaSeguridad.Ajustes(
            tienePin = WalletManager.hasPin(this), bitsPin = null, pinAntiguo = WalletManager.pinAntiguo(this),
            huella = WalletManager.huellaActivada(this), copiaHecha = BackupStore.count(this) > 0,
            capturasBloqueadas = PantallaSegura.activa(this), numeroCarteras = 0)
        carteras.clear()
        val semillas = LinkedHashMap<String, String>()   // nombre → frase (sin repetir)
        WalletManager.loadSeed(this)?.let { semillas[WalletManager.mainName(this)] = it }
        WalletManager.listWallets(this).forEach { (id, nombre) -> WalletManager.loadWalletSeed(this, id)?.let { semillas.putIfAbsent(nombre, it) } }
        for ((nombre, mn) in semillas) {
            carteras.add(AuditoriaSeguridad.Entrada(
                nombre = nombre,
                palabras = Frase.palabras(mn).trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                passphrase = if (Frase.tienePassphrase(mn)) Frase.passphrase(mn) else null))
        }
        ajustes = AuditoriaSeguridad.Ajustes(ajustes.tienePin, null, ajustes.pinAntiguo, ajustes.huella,
            ajustes.copiaHecha, ajustes.capturasBloqueadas, carteras.size)
    }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(color); typeface = if (negrita) AppTheme.bold(context) else AppTheme.body(context); setPadding(0, dp(3), 0, dp(3)) }

    private fun pintar() {
        caja.removeAllViews()
        caja.addView(Ui.pageTitle(this, "Security check", lados = false))
        if (carteras.isEmpty()) {
            caja.addView(texto("No seed wallets on this phone to check. Create or import one first.", AppTheme.SP_BODY, AppTheme.TXT_SEC))
            return
        }
        val hallazgos = AuditoriaSeguridad.revisar(ajustes, carteras)
        val nota = AuditoriaSeguridad.nota(hallazgos)
        val (palabra, color) = AuditoriaSeguridad.veredicto(nota)

        // Nota grande.
        caja.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(AppTheme.BG_CARD); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
            setPadding(dp(16), dp(18), dp(16), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(texto("$nota / 100", 34f, color, true).apply { gravity = Gravity.CENTER })
            addView(texto(palabra, AppTheme.SP_BODY + 2f, color, true).apply { gravity = Gravity.CENTER })
            addView(texto(if (profundo) "Checked ${carteras.size} wallet(s), seeds and the chain." else "Offline checks done. Run a deep check for address reuse and signature safety.",
                AppTheme.SP_CAPTION, AppTheme.TXT_SEC).apply { gravity = Gravity.CENTER })
        })

        if (!profundo) caja.addView(botonAccion(if (ocupado) "Checking the chain…" else "Deep check (uses the network)") { if (!ocupado) revisarRed() })

        if (hallazgos.isEmpty()) {
            caja.addView(texto("✓ Nothing to fix. Keep your backup safe and off any phone or cloud.", AppTheme.SP_BODY, 0xFF2ECC71.toInt(), true))
        } else {
            for (h in hallazgos) caja.addView(tarjeta(h))
        }
        caja.addView(texto("\nThis review only covers what this app can see. It does not guarantee your coins are safe from every threat.",
            AppTheme.SP_CAPTION, AppTheme.TXT_MUTED))
    }

    private fun colorDe(g: AuditoriaSeguridad.Gravedad) = when (g) {
        AuditoriaSeguridad.Gravedad.CRITICO -> 0xFFE74C3C.toInt(); AuditoriaSeguridad.Gravedad.ALTO -> 0xFFE67E22.toInt()
        AuditoriaSeguridad.Gravedad.MEDIO -> 0xFFF1C40F.toInt(); AuditoriaSeguridad.Gravedad.BAJO -> 0xFF8BC34A.toInt()
        AuditoriaSeguridad.Gravedad.INFO -> AppTheme.TXT_SEC
    }

    private fun tarjeta(h: AuditoriaSeguridad.Hallazgo) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(AppTheme.BG_CARD); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        addView(texto("${h.gravedad.etiqueta.uppercase()} · ${h.titulo}", AppTheme.SP_CAPTION, colorDe(h.gravedad), true))
        addView(texto(h.detalle, AppTheme.SP_CAPTION + 1f, AppTheme.TXT_PRI))
        addView(texto("→ ${h.consejo}", AppTheme.SP_CAPTION, AppTheme.ACCENT))
    }

    private fun botonAccion(t: String, accion: () -> Unit) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
        setTextColor(AppTheme.ON_ACCENT); typeface = AppTheme.bold(context)
        background = GradientDrawable().apply { setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(14) }
        isClickable = true; foreground = Ui.toque(); setOnClickListener { accion() }
    }

    // ── Revisión con red ────────────────────────────────────────────────────────────────────────────

    private fun revisarRed() {
        ocupado = true; pintar()
        val testnet = false
        Thread {
            val nuevas = ArrayList<AuditoriaSeguridad.Entrada>()
            var fallo = false
            BalanceLookup.iniciarMemo()
            try {
                for (c in carteras) nuevas.add(revisarRedDeCartera(c, testnet).also { if (it.direccionesMiradas < 0) fallo = true })
            } catch (e: Exception) { RegistroFallos.anotar("Seguridad.revisarRed", e); fallo = true }
            finally { BalanceLookup.terminarMemo() }
            runOnUiThread {
                ocupado = false
                if (nuevas.size == carteras.size) { carteras.clear(); carteras.addAll(nuevas); profundo = true }
                if (fallo) Toast.makeText(this, "Some chain queries failed; the deep result may be incomplete.", Toast.LENGTH_LONG).show()
                pintar()
            }
        }.start()
    }

    /** Reutilización (direcciones con más de una transacción) y nonce repetido, mirando las ramas de recepción. */
    private fun revisarRedDeCartera(c: AuditoriaSeguridad.Entrada, testnet: Boolean): AuditoriaSeguridad.Entrada {
        val mn = carteraSemilla(c.nombre) ?: return c
        var reutil = 0; var miradas = 0; var fallo = false
        val rawTxs = LinkedHashSet<String>(); val idsVistos = HashSet<String>()
        for (purpose in HdScanner.PURPOSES) {
            val (usadas, _) = HdScanner.scanBranch(mn, purpose, 0, testnet)
            for (f in usadas) {
                miradas++
                val js = ChainApi.get("/address/${f.addr}", testnet)
                if (js == null) { fallo = true; continue }
                val tx = try { JSONObject(js).optJSONObject("chain_stats")?.optInt("tx_count", 0) ?: 0 } catch (e: Exception) { 0 }
                if (tx > 1) reutil++
                if (rawTxs.size < 120) {
                    val lista = ChainApi.get("/address/${f.addr}/txs", testnet)
                    if (lista != null) try {
                        val a = JSONArray(lista)
                        for (i in 0 until minOf(a.length(), 25)) {
                            val id = a.getJSONObject(i).optString("txid"); if (id.isEmpty() || !idsVistos.add(id)) continue
                            if (rawTxs.size >= 120) break
                            ChainApi.get("/tx/$id/hex", testnet)?.trim()?.let { rawTxs.add(it) }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
        val expuesto = AuditoriaNonce.clavesExpuestas(rawTxs.toList()).isNotEmpty()
        return AuditoriaSeguridad.Entrada(c.nombre, c.palabras, c.passphrase, c.bitsContrasenaCopia,
            reutil, if (fallo) -1 else miradas, expuesto, c.claveSinComprimir)
    }

    private fun carteraSemilla(nombre: String): String? {
        if (nombre == WalletManager.mainName(this)) return WalletManager.loadSeed(this)
        return WalletManager.listWallets(this).firstOrNull { it.second == nombre }?.let { WalletManager.loadWalletSeed(this, it.first) }
    }
}
