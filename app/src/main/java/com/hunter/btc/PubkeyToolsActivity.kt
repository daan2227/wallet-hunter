package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * Herramientas de clave pública, cada una una "función" de More:
 *   0 · Addresses   — todas las direcciones de una clave (pública o privada).
 *   1 · Brainwallet — prueba frases (SHA256→privada) contra una dirección.
 *   2 · Nonce audit — busca nonce (k) reutilizado en las firmas de una dirección.
 *
 * El extra "tool" elige cuál se muestra. Todas comparten el marco (título,
 * ScrollView, nota honesta) y viven aquí para no multiplicar actividades.
 */
class PubkeyToolsActivity : Activity() {

    companion object { const val EXTRA_TOOL = "tool" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var root: LinearLayout

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)
        AppLock.init(this)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        when (intent.getIntExtra(EXTRA_TOOL, 0)) {
            1 -> construirBrainwallet()
            2 -> construirNonce()
            else -> construirDirecciones()
        }
    }

    // ── Marco común ───────────────────────────────────────────────────────
    private fun titulo(t: String, sub: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = 24f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
        })
        root.addView(TextView(this).apply {
            text = sub; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context); setPadding(0, dp(4), 0, dp(16))
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun rotulo(t: String) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context); setPadding(0, dp(6), 0, dp(6))
    }

    private fun entrada(hint: String, varias: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHorizontallyScrolling(!varias)
        if (varias) { maxLines = 8; minLines = 4; gravity = Gravity.TOP or Gravity.START }
        else maxLines = 2
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
        typeface = AppTheme.mono(context)
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun boton(t: String, click: () -> Unit) = Button(this).apply {
        text = t; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
        typeface = AppTheme.bold(context); isAllCaps = false; stateListAnimator = null
        background = Ui.botonAccento(this@PubkeyToolsActivity, AppTheme.R_INNER, AppTheme.ACCENT)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            .apply { topMargin = dp(12) }
        setOnClickListener { Ui.pulso(this); click() }
    }

    private fun nota(color: Int, t: String) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        fila.addView(android.view.View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(14) }
        })
        fila.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION + 1f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.4f)
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(fila)
    }

    private fun copiar(texto: String) {
        try {
            val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("btc", texto))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {}
    }

    /** Una fila de resultado "etiqueta / valor" que copia el valor al tocarla. */
    private fun filaResultado(etiqueta: String, valor: String, destacado: Boolean = false): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            isClickable = true; isFocusable = true; foreground = Ui.toque()
            setOnClickListener { copiar(valor) }
        }
        col.addView(TextView(this@PubkeyToolsActivity).apply {
            text = etiqueta; textSize = AppTheme.SP_CAPTION
            setTextColor(if (destacado) AppTheme.ACCENT else AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
        })
        col.addView(TextView(this@PubkeyToolsActivity).apply {
            text = valor; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.mono(context)
            setPadding(0, dp(3), 0, 0)
        })
        return col
    }

    // ── 0 · Addresses ───────────────────────────────────────────────────────
    private fun construirDirecciones() {
        titulo("Addresses from a key",
            "Every address a key maps to — compressed and uncompressed. Paste a public " +
            "key (02/03/04) or a private key (64 hex).")
        root.addView(rotulo("Public or private key (hex)"))
        val et = entrada("02… / 03… / 04… or 64-hex private key", varias = true)
        root.addView(et)
        val salida = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        root.addView(boton("Derive addresses") {
            salida.removeAllViews()
            val txt = try { HunterEngine.direccionesDe(et.text.toString().trim()) }
                      catch (e: Throwable) { "" }
            if (txt.isBlank()) {
                Toast.makeText(this, "Not a valid public or private key", Toast.LENGTH_SHORT).show()
            } else {
                txt.trim().split('\n').forEach { linea ->
                    val i = linea.indexOf('=')
                    if (i > 0) salida.addView(filaResultado(linea.substring(0, i), linea.substring(i + 1)))
                }
                salida.addView(TextView(this).apply {
                    text = "Tap any address to copy it."
                    textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
                    typeface = AppTheme.body(context); setPadding(dp(2), dp(10), 0, 0)
                })
            }
        })
        root.addView(salida)
        nota(AppTheme.BLUE,
            "A key is not one address: compressed and uncompressed public keys hash to " +
            "different ones, and each has P2PKH, P2SH-P2WPKH, bech32 and taproot forms. " +
            "Funds could sit on any of them.")
    }

    // ── 1 · Brainwallet (siguiente etapa) ─────────────────────────────────
    private fun construirBrainwallet() {
        titulo("Brainwallet check", "")
    }

    // ── 2 · Nonce audit (siguiente etapa) ─────────────────────────────────
    private fun construirNonce() {
        titulo("Nonce-reuse audit", "")
    }
}
