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
    private val GOLD  get() = AppTheme.AMBER
    private val TXT   get() = AppTheme.TXT_PRI
    private val TXT3  get() = AppTheme.TXT_MUTED
    private val BORDER get() = AppTheme.BORDER_C
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        AppTheme.init(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = BG

        if (WalletManager.hasPin(this)) {
            showPinEntry()
        } else {
            showPinSetup()
        }
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

        // Logo
        root.addView(TextView(this).apply {
            text = "WALLET\nHUNTER"
            textSize = 42f; setTextColor(GOLD)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "BITCOIN SEED SCANNER"
            textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.2f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(48))
        })

        // Descripción
        root.addView(TextView(this).apply {
            text = "Crea un PIN de seguridad\npara proteger tus wallets"
            textSize = 14f; setTextColor(TXT)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })

        // Botón crear PIN
        root.addView(Button(this).apply {
            text = "⚷  CREAR PIN DE SEGURIDAD"
            textSize = 13f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.06f
            background = GradientDrawable().apply {
                setColor(GOLD); cornerRadius = dp(6).toFloat()
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



        isSetup: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val GOLD  = AppTheme.AMBER          // #a8ff00
        val BG    = 0xFF0E0F0E.toInt()      // #0e0f0e
        val BG2   = 0xFF1A1C19.toInt()      // #1a1c19
        val BGKP  = 0xFF0D1018.toInt()      // #0d1018 keypad bg
        val TXT   = 0xFFE6EAD8.toInt()      // #e6ead8
        val MUTED = 0xFF556050.toInt()       // #556050
        val SUBL  = 0xFF7A8A70.toInt()       // #7a8a70 sub-letters
        val RED   = 0xFFFF4D4D.toInt()
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
            text = "Passcode"
            textSize = 17f; setTextColor(TXT)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
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
            text = if (isSetup) "Create passcode" else "Enter passcode"
            textSize = 22f; setTextColor(TXT)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
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
                    error  -> { setColor(0x22FF4D4D); setStroke(dp(2), RED) }
                    filled -> { setColor(0x22A8FF00); setStroke(dp(2), GOLD) }
                    else   -> { setColor(BG2); setStroke(dp(2), 0xFF2A2E25.toInt()) }
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
                "Enter your passcode. Be sure to remember it\nso you can unlock your wallet."
            else "Use your passcode or fingerprint to unlock"
            textSize = 13f; setTextColor(MUTED)
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
                        tvLabel.text = "Confirm passcode"
                        tvHint.text = "Enter the same passcode again"
                        tvHint.setTextColor(MUTED)
                    } else if (firstPin == pin.toString()) {
                        WalletManager.savePin(activity, pin.toString())
                        dlg?.dismiss(); onResult(true)
                    } else {
                        firstPin = ""; shakeAndClear()
                        tvLabel.text = "Try again"
                        tvHint.text = "Passcodes did not match"
                        tvHint.setTextColor(RED)
                    }
                } else {
                    if (WalletManager.checkPin(activity, pin.toString())) {
                        dlg?.dismiss(); onResult(true)
                    } else {
                        shakeAndClear()
                        tvHint.text = "Incorrect passcode. Try again."
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
                        setColor(BG2); cornerRadius = dp(12).toFloat()
                        setStroke(1, 0x0EFFFFFF)
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
                            setColor(0xFF222521.toInt()); cornerRadius = dp(12).toFloat(); setStroke(1, 0x0EFFFFFF)
                        }
                        val normalBg = android.graphics.drawable.GradientDrawable().apply {
                            setColor(BG2); cornerRadius = dp(12).toFloat(); setStroke(1, 0x0EFFFFFF)
                        }
                        background = pressedBg
                        handler.postDelayed({ background = normalBg }, 120)
                    }
                }
            }

            when (key.type) {
                "num" -> {
                    cell.addView(android.widget.TextView(activity).apply {
                        text = key.digit; textSize = 28f; setTextColor(TXT)
                        typeface = android.graphics.Typeface.DEFAULT
                        gravity = android.view.Gravity.CENTER
                    })
                    if (key.sub.isNotEmpty()) {
                        cell.addView(android.widget.TextView(activity).apply {
                            text = key.sub; textSize = 9f; setTextColor(SUBL)
                            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                            letterSpacing = 0.12f; gravity = android.view.Gravity.CENTER
                        })
                    }
                }
                "bio" -> {
                    // Ícono de huella usando texto unicode
                    cell.addView(android.widget.TextView(activity).apply {
                        text = "◉"; textSize = 30f
                        setTextColor(GOLD)
                        gravity = android.view.Gravity.CENTER
                    })
                }
                "del" -> {
                    cell.addView(android.widget.TextView(activity).apply {
                        text = "⌫"; textSize = 24f; setTextColor(SUBL)
                        gravity = android.view.Gravity.CENTER
                    })
                }
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