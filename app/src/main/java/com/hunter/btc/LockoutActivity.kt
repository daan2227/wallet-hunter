package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloqueo total por demasiados intentos de PIN.
 *
 * Mientras WalletManager.esperaPin() > 0, esta pantalla cubre TODA la app: la
 * lanza AppLock sobre cualquier actividad que se reanude, bloquea el botón de
 * atrás y muestra la cuenta atrás. Se cierra sola cuando el tiempo llega a 0.
 */
class LockoutActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private var tvCount: TextView? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = AppTheme.fondoApp()
            setPadding(dp(32), dp(32), dp(32), dp(32))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lock); setColorFilter(AppTheme.WARN)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        root.addView(TextView(this).apply {
            text = "Too many attempts"; textSize = AppTheme.SP_TITLE + 4f
            setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context)
            setPadding(0, dp(20), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "The app is locked. Try again in"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context)
            gravity = Gravity.CENTER
        })
        tvCount = TextView(this).apply {
            text = "--:--"; textSize = AppTheme.SP_DISPLAY; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.display(context); letterSpacing = -0.03f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(tvCount)
        setContentView(root)
    }

    private fun tick() {
        val s = WalletManager.esperaPin(this)
        if (s <= 0) { finish(); return }
        tvCount?.text = "%02d:%02d".format(s / 60, s % 60)
        h.postDelayed({ tick() }, 500)
    }

    override fun onResume() {
        super.onResume()
        if (WalletManager.esperaPin(this) <= 0) { finish(); return }
        h.removeCallbacksAndMessages(null); tick()
    }

    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null) }

    // Bloqueado: no se sale del lockout con atrás.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* no-op */ }
}
