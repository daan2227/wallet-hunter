package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * Pantalla de ayuda: explica el flujo de la auditoría de claves débiles.
 *
 * Se entra desde More → Help. Es solo texto (sin motor ni permisos), así que
 * no hace falta más que pintar secciones sobre un ScrollView.
 */
class HelpActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)
        AppLock.init(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Help"
            textSize = 24f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
        })
        root.addView(TextView(this).apply {
            text = "Weak-key audit — how it works"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(4), 0, dp(4))
        })

        // La idea de fondo, en la tarjeta de aviso (naranja) porque es el
        // matiz que más se malinterpreta: esto NO rompe claves normales.
        tarjeta(root, AppTheme.WARN,
            "Kangaroo needs the public key AND a range where the private key lives. " +
            "For a normal address the range is all of [1, 2^256] — impossible. The only " +
            "thing you can audit is a badly generated key: one whose private value came " +
            "from too little entropy and landed in a small range. A properly generated " +
            "key will never show up here. Use it on your own addresses, or a system you audit.")

        seccion(root, "1 · Input")
        parrafo(root, "Paste public keys (02…/03…/04…) or spent addresses (1…/3…/bc1…), " +
            "one per line — or tap Load CSV / text file. The file is read line by line and " +
            "every key/address in any column is pulled out (headers, quotes and extra " +
            "columns are ignored), without duplicates.")
        parrafo(root, "• Up to 2,000 entries go into the editor.\n" +
            "• More than that switch to large-list mode: kept in memory with just a summary " +
            "(“file · N keys loaded”), so the editor stays responsive.")

        seccion(root, "2 · Settings")
        parrafo(root, "• Search range (20–80 bits, suggested 50): how far it looks. It only " +
            "finds keys whose private value is below 2^bits.\n" +
            "• Budget × √ (2–4): margin over √(2^bits). Higher = surer but slower.\n" +
            "• Max seconds per key (0 = no limit): cuts a key that drags on and moves to the next.")
        parrafo(root, "The orange line shows, live: operations per key, time per key at your " +
            "device’s measured speed, and the batch ETA (number of keys × time per key).")

        seccion(root, "3 · Run")
        parrafo(root, "Start audit builds the queue (editor + large list, deduped) and works " +
            "one key at a time:")
        parrafo(root, "• A public key (02/03/04) is used directly.\n" +
            "• An address is resolved to its public key over the network — which only exists " +
            "if the address has spent; if it can’t be found, it’s skipped.\n" +
            "• Kangaroo then runs over [1, 2^bits) with budget c·√(2^bits) on all cores.")
        parrafo(root, "Every second it checks whether the key surfaced, the budget ran out, " +
            "or the per-key time cap was hit — then moves on.")

        seccion(root, "4 · Performance card")
        parrafo(root, "While it runs: live speed with its peak, a trend chart, and four figures " +
            "(Operations · Time · Key budget % · Keys · found). It also measures and remembers " +
            "your device’s speed to make future ETAs accurate.")

        seccion(root, "5 · Results")
        parrafo(root, "Each hit is saved to the finds vault (with its WIF and address) and a " +
            "notification pops up. At the end: “Done: N checked · M found → in the finds vault.” " +
            "Review or delete finds from the vault.")

        // Resumen de una línea, en tarjeta de acento.
        tarjeta(root, AppTheme.ACCENT,
            "In short: paste or load keys → set range, budget and cap → Kangaroo runs key by " +
            "key over a small range → whatever it finds (badly generated keys) goes to the vault.")

        setContentView(scroll)
    }

    private fun seccion(root: LinearLayout, t: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
            setPadding(0, dp(22), 0, dp(8))
        })
    }

    private fun parrafo(root: LinearLayout, t: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(8))
        })
    }

    /** Bloque destacado con una barra de color a la izquierda. */
    private fun tarjeta(root: LinearLayout, color: Int, t: String) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
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
}
