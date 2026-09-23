package com.hunter.btc

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*

class WelcomeActivity : androidx.appcompat.app.AppCompatActivity() {

    private val BG    get() = AppTheme.BG_DEEP
    private val TXT   get() = AppTheme.TXT_PRI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        // Antes de super.onCreate: es el único momento en que el tema se
        // puede cambiar, y de él salen los colores de todos los diálogos.
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)
        // Cualquier pantalla puede ser la primera si Android revive el
        // proceso por una notificacion, asi que todas lo llaman. Es
        // idempotente: engancha los avisos del sistema una sola vez.
        AppLock.init(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = BG

        if (WalletManager.hasPin(this)) {
            showSplashThenPin()
        } else {
            showPinSetup()
        }
    }

    // ── SPLASH ───────────────────────────────────────────────────────────────
    private fun showSplashThenPin() {
        val ACCENT  = AppTheme.ACCENT
        val TXT     = AppTheme.TXT_PRI
        val MUTED   = AppTheme.TXT_SEC

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(24), dp(60), dp(24), dp(60))
        }

        // Logo
        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val logoIcon = android.widget.TextView(this).apply {
            text = "\u20BF"; textSize = 26f
            setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.display(context)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(10); gravity = android.view.Gravity.CENTER_VERTICAL
            }
        }
        val logoText = android.widget.TextView(this).apply {
            text = android.text.SpannableString("WalletHunter").also { sp ->
                sp.setSpan(android.text.style.ForegroundColorSpan(ACCENT), 6, 12,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 28f; setTextColor(TXT)
            typeface = AppTheme.title(context)
        }
        logoRow.addView(logoIcon); logoRow.addView(logoText)
        root.addView(logoRow)

        root.addView(android.widget.TextView(this).apply {
            text = "Bitcoin seed scanner"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(40) }
        })

        // Stats cards
        val (totalKeys, totalMatches, totalTime) = StatsActivity.getTotals(this)
        val sessions = StatsActivity.loadSessions(this)

        fun statCard(icon: Int, value: String, label: String, color: Int): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
                layoutParams = LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                addView(Ui.icon(this@WelcomeActivity, icon, 20, AppTheme.TXT_SEC).apply {
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
                })
                addView(android.widget.TextView(this@WelcomeActivity).apply {
                    text = value; textSize = AppTheme.SP_FIGURE; setTextColor(color)
                    typeface = AppTheme.title(context)
                    gravity = android.view.Gravity.CENTER; letterSpacing = -0.02f
                })
                // 8sp: la mitad del mínimo legible que da Android.
                addView(android.widget.TextView(this@WelcomeActivity).apply {
                    text = label; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
                    typeface = AppTheme.body(context)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                })
            }
        }

        fun formatKeys(k: Long): String = when {
            k >= 1_000_000_000 -> "${"%.1f".format(k/1e9)}B"
            k >= 1_000_000 -> "${"%.1f".format(k/1e6)}M"
            k >= 1_000 -> "${"%.0f".format(k/1e3)}K"
            else -> k.toString()
        }

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(40) }
        }
        statsRow.addView(statCard(R.drawable.ic_play, formatKeys(totalKeys), "Keys", AppTheme.TXT_PRI))
        statsRow.addView(statCard(R.drawable.ic_target, totalMatches.toString(), "Matches",
            if (totalMatches > 0) AppTheme.ACCENT else AppTheme.TXT_PRI))
        statsRow.addView(statCard(R.drawable.ic_stats, sessions.size.toString(), "Sessions", AppTheme.TXT_PRI))
        root.addView(statsRow)

        // Mensaje de bienvenida
        root.addView(android.widget.TextView(this).apply {
            text = "Verifying identity…"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            gravity = android.view.Gravity.CENTER
        })

        setContentView(root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(300).start()

        // Mostrar PIN después de 1.5 segundos
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            showPinEntry()
        }, 1500)
    }

    // ── PIN ENTRY ─────────────────────────────────────────────────────────────
    private fun showPinEntry() {
        PinAuthHelper.pantalla(this@WelcomeActivity, isSetup = false) { ok ->
            if (ok) {
                PinAuthHelper.markAuthenticated()
                goMain()
            } else {
                finish()
            }
        }
    }

    // ── PIN SETUP ─────────────────────────────────────────────────────────────
    private fun showPinSetup() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(80), dp(32), dp(48))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Logo icon
        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val logoIcon = TextView(this).apply {
            text = "\u20BF"
            textSize = 24f; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.display(context)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(10); gravity = Gravity.CENTER_VERTICAL
            }
        }
        val logoText = TextView(this).apply {
            text = android.text.SpannableString("WalletHunter").also { sp ->
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(AppTheme.ACCENT),
                    6, 12, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 28f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
        }
        logoRow.addView(logoIcon); logoRow.addView(logoText)
        root.addView(logoRow)

        root.addView(TextView(this).apply {
            text = "Bitcoin seed scanner"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(48))
        })

        // Descripción
        root.addView(TextView(this).apply {
            text = "Pick a PIN to protect your wallets."
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })

        // Botón crear PIN
        root.addView(Button(this).apply {
            text = "Create the PIN"
            textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(AppTheme.ACCENT)
                cornerRadius = dp(AppTheme.R_KEY).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            setOnClickListener {
                PinAuthHelper.pantalla(this@WelcomeActivity, isSetup = true) { ok ->
                    if (ok) {
                        PinAuthHelper.markAuthenticated()
                        goMain()
                    }
                }
            }
        })

        setContentView(root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(400).setStartDelay(100).start()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    // La pantalla del código vive en PinAuthHelper.pantalla: había una copia
    // aquí, con otro aspecto que la del resto de la app.
}
