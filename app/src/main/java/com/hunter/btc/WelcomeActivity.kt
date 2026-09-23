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
        showPinScreen(this@WelcomeActivity, isSetup = false) { ok ->
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
                showPinScreen(this@WelcomeActivity, isSetup = true) { ok ->
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



    private fun showPinScreen(
        activity: android.app.Activity,
        isSetup: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val GOLD  = AppTheme.ACCENT
        val BG    = AppTheme.BG_DEEP
        val BG2   = AppTheme.BG_KEY
        val BGKP  = AppTheme.BG_DEEP
        val TXT   = AppTheme.TXT_PRI
        val MUTED = AppTheme.TXT_SEC
        val RED   = AppTheme.RED
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        fun spToPx(sp: Float) = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, sp, activity.resources.displayMetrics).toInt()

        // Root fullscreen
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Top bar ────────────────────────────────────────────────────────
        val topBar = android.widget.FrameLayout(activity).apply {
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        topBar.addView(android.widget.TextView(activity).apply {
            text = "Code"
            textSize = AppTheme.SP_TITLE; setTextColor(TXT)
            typeface = AppTheme.title(context)
            letterSpacing = -0.01f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL
            )
        })
        root.addView(topBar)

        // ── Body ──────────────────────────────────────────────────────────
        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(24), 0, dp(24), dp(16))
        }

        val tvLabel = android.widget.TextView(activity).apply {
            text = if (isSetup) "Pick your code" else "Enter the code"
            textSize = 22f; setTextColor(TXT)
            typeface = AppTheme.display(context)
            letterSpacing = -0.02f; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(32) }
        }
        body.addView(tvLabel)

        // Dots row
        val dotsRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(32) }
        }
        val dotSz = dp(52)
        fun makeDotBg(filled: Boolean, error: Boolean = false) =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                when {
                    error  -> setColor(AppTheme.RED)
                    filled -> setColor(AppTheme.TXT_PRI)
                    else   -> setColor(AppTheme.BG_ELEV)
                }
            }
        val dots = Array(6) { i ->
            android.view.View(activity).apply {
                background = makeDotBg(false)
                layoutParams = android.widget.LinearLayout.LayoutParams(dotSz, dotSz).apply {
                    if (i < 5) marginEnd = dp(10)
                }
            }
        }
        dots.forEach { dotsRow.addView(it) }
        body.addView(dotsRow)

        val tvHint = android.widget.TextView(activity).apply {
            text = if (isSetup)
                "Pick your code. Write it down where you will not lose it:\nwithout it you cannot open the wallet."
            else "Enter the code or use your fingerprint"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            gravity = android.view.Gravity.CENTER
        }
        body.addView(tvHint)
        root.addView(body)

        // ── Keypad ────────────────────────────────────────────────────────
        val keypadWrap = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(BGKP)
            setPadding(dp(10), dp(14), dp(10), dp(32))
        }
        val keypadContainer = android.widget.LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val keypad = android.widget.GridLayout(activity).apply {
            columnCount = 3; rowCount = 4
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val pin = StringBuilder()
        var firstPin = ""
        var dlg: android.app.Dialog? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        fun updateDots(error: Boolean = false) {
            dots.forEachIndexed { i, d ->
                d.background = makeDotBg(filled = i < pin.length, error = error)
            }
        }

        fun shakeAndClear() {
            updateDots(error = true)
            handler.postDelayed({ pin.clear(); updateDots() }, 600)
        }

        fun handleDigit(k: String) {
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return
            handler.postDelayed({
                if (isSetup) {
                    if (firstPin.isEmpty()) {
                        firstPin = pin.toString(); pin.clear(); updateDots()
                        tvLabel.text = "Again"
                        tvHint.text = "Type the same code again"
                        tvHint.setTextColor(MUTED)
                    } else if (firstPin == pin.toString()) {
                        WalletManager.savePin(activity, pin.toString())
                        dlg?.dismiss(); onResult(true)
                    } else {
                        firstPin = ""; shakeAndClear()
                        tvLabel.text = "Try again"
                        tvHint.text = "They do not match"
                        tvHint.setTextColor(RED)
                    }
                } else {
                    if (WalletManager.checkPin(activity, pin.toString())) {
                        dlg?.dismiss(); onResult(true)
                    } else {
                        shakeAndClear()
                        tvHint.text = "Wrong code. Try again."
                        tvHint.setTextColor(RED)
                    }
                }
            }, 150)
        }

        fun launchBiometric() {
            if (activity !is androidx.fragment.app.FragmentActivity) return
            val bm = androidx.biometric.BiometricManager.from(activity)
            val canBio = bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
            if (!canBio) return
            val prompt = androidx.biometric.BiometricPrompt(
                activity as androidx.fragment.app.FragmentActivity,
                androidx.core.content.ContextCompat.getMainExecutor(activity),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        dlg?.dismiss(); onResult(true)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {}
                    override fun onAuthenticationFailed() {}
                }
            )
            prompt.authenticate(
                androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Wallet Hunter")
                    .setSubtitle("Use fingerprint to unlock")
                    .setNegativeButtonText("Use PIN")
                    .build()
            )
        }

        // Build keys: (digit, sublabel, type)  type: "num" | "bio" | "del" | "empty"
        data class Key(val digit: String, val sub: String, val type: String)
        val keys = listOf(
            Key("1","","num"), Key("2","ABC","num"), Key("3","DEF","num"),
            Key("4","GHI","num"), Key("5","JKL","num"), Key("6","MNO","num"),
            Key("7","PQRS","num"), Key("8","TUV","num"), Key("9","WXYZ","num"),
            Key("","","bio"), Key("0","","num"), Key("","","del")
        )

        keys.forEach { key ->
            val sz = dp(76)
            val cell = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sz; height = sz
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                if (key.type != "empty") {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(AppTheme.BG_KEY)
                        cornerRadius = dp(AppTheme.R_KEY).toFloat()
                    }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        when (key.type) {
                            "del" -> { if (pin.isNotEmpty()) { pin.deleteCharAt(pin.length-1); updateDots() } }
                            "bio" -> launchBiometric()
                            "num" -> handleDigit(key.digit)
                        }
                        // Press feedback
                        val pressedBg = android.graphics.drawable.GradientDrawable().apply {
                            setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_KEY).toFloat()
                        }
                        val normalBg = android.graphics.drawable.GradientDrawable().apply {
                            setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_KEY).toFloat()
                        }
                        background = pressedBg
                        handler.postDelayed({ background = normalBg }, 120)
                    }
                }
            }

            when (key.type) {
                "num" -> {
                    cell.addView(android.widget.TextView(activity).apply {
                        text = key.digit; textSize = 26f; setTextColor(TXT)
                        typeface = AppTheme.medium(activity)
                        gravity = android.view.Gravity.CENTER
                    })
                }
                "bio" -> cell.addView(
                    Ui.icon(activity, R.drawable.ic_finger, 26, AppTheme.TXT_SEC))
                "del" -> cell.addView(
                    Ui.icon(activity, R.drawable.ic_back, 24, AppTheme.TXT_SEC))
            }
            keypad.addView(cell)
        }

        keypadContainer.addView(keypad)
        keypadWrap.addView(keypadContainer)
        root.addView(keypadWrap)

        // Dialog fullscreen
        dlg = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg!!.setContentView(root)
        dlg!!.setCancelable(!isSetup)
        if (!isSetup) dlg!!.setOnCancelListener { onResult(false) }
        dlg!!.show()

        // Auto-lanzar biométrico al abrir si no es setup
        if (!isSetup) handler.postDelayed({ launchBiometric() }, 300)
    }

}
