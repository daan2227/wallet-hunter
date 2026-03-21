package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*

class WelcomeActivity : Activity() {

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

    private fun showPinScreen(
        activity: android.app.Activity,
        isSetup: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val GOLD  = AppTheme.AMBER
        val BG    = AppTheme.BG_DEEP
        val BG2   = AppTheme.BG_CARD
        val TXT   = AppTheme.TXT_PRI
        val MUTED = AppTheme.TXT_MUTED
        val RED   = 0xFFFF4D4D.toInt()
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        // Root - pantalla completa
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top bar
        val topBar = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        topBar.addView(android.widget.TextView(activity).apply {
            text = "Passcode"
            textSize = 17f; setTextColor(TXT)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            letterSpacing = -0.01f
        })
        root.addView(topBar)

        // Body - centrado verticalmente
        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(24), 0, dp(24), dp(32))
        }

        // Label
        val tvLabel = android.widget.TextView(activity).apply {
            text = if (isSetup) "Create passcode" else "Enter passcode"
            textSize = 22f; setTextColor(TXT)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            letterSpacing = -0.02f; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(28) }
        }
        body.addView(tvLabel)

        // Dots row
        val dotsRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(28) }
        }
        val dotSize = dp(52)
        val dots = Array(6) { i ->
            android.view.View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(AppTheme.BG_CARD)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(2), 0xFF2A2E25.toInt())
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    if (i < 5) marginEnd = dp(10)
                }
            }
        }
        dots.forEach { dotsRow.addView(it) }
        body.addView(dotsRow)

        // Hint
        val tvHint = android.widget.TextView(activity).apply {
            text = if (isSetup)
                "Enter your passcode. Be sure to remember it so you can unlock your wallet."
            else
                "Enter your PIN to continue"
            textSize = 13f; setTextColor(MUTED)
            gravity = android.view.Gravity.CENTER
            lineHeight = (textSize * 1.55f).toInt()
        }
        body.addView(tvHint)
        root.addView(body)

        // Keypad wrap
        val keypadWrap = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1018.toInt())
            setPadding(dp(10), dp(14), dp(10), dp(48))
        }
        val keypad = android.widget.GridLayout(activity).apply {
            columnCount = 3; rowCount = 4
        }

        val pin = StringBuilder()
        var firstPin = ""
        var dlg: android.app.Dialog? = null

        fun setDotFilled(i: Int, filled: Boolean, error: Boolean = false) {
            val bg = dots[i].background as android.graphics.drawable.GradientDrawable
            when {
                error  -> { bg.setColor(0x33FF4D4D); bg.setStroke(dp(2), RED) }
                filled -> { bg.setColor(0x33A8FF00); bg.setStroke(dp(2), GOLD) }
                else   -> { bg.setColor(AppTheme.BG_CARD); bg.setStroke(dp(2), 0xFF2A2E25.toInt()) }
            }
        }

        fun updateDots() = (0 until 6).forEach { setDotFilled(it, it < pin.length) }

        fun shakeError() {
            (0 until 6).forEach { setDotFilled(it, filled = true, error = true) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                pin.clear(); updateDots()
            }, 600)
        }

        fun handleDigit(k: String) {
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return

            if (isSetup) {
                if (firstPin.isEmpty()) {
                    firstPin = pin.toString(); pin.clear(); updateDots()
                    tvLabel.text = "Confirm passcode"
                    tvHint.text = "Enter the same passcode again"
                } else if (firstPin == pin.toString()) {
                    WalletManager.savePin(activity, pin.toString())
                    dlg?.dismiss(); onResult(true)
                } else {
                    firstPin = ""; shakeError()
                    tvLabel.text = "Try again"
                    tvHint.text = "Passcodes did not match"
                }
            } else {
                if (WalletManager.checkPin(activity, pin.toString())) {
                    dlg?.dismiss(); onResult(true)
                } else {
                    shakeError()
                    tvHint.text = "Incorrect passcode. Try again."
                    tvHint.setTextColor(RED)
                }
            }
        }

        // Keys: num, sub-label
        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "" to "", "0" to "", "DEL" to ""
        )

        keys.forEach { (key, sub) ->
            val cell = android.widget.FrameLayout(activity).apply {
                val sz = dp(76)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sz; height = sz
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                if (key.isNotEmpty()) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(AppTheme.BG_CARD)
                        cornerRadius = dp(12).toFloat()
                        setStroke(1, 0x0FFFFFFF)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (key == "DEL") {
                            if (pin.isNotEmpty()) { pin.deleteCharAt(pin.length - 1); updateDots() }
                        } else {
                            handleDigit(key)
                        }
                        // Feedback táctil
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(0xFF222521.toInt()); cornerRadius = dp(12).toFloat(); setStroke(1, 0x0FFFFFFF)
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(AppTheme.BG_CARD); cornerRadius = dp(12).toFloat(); setStroke(1, 0x0FFFFFFF)
                            }
                        }, 120)
                    }
                }
            }

            if (key.isNotEmpty() && key != "DEL") {
                val inner = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                inner.addView(android.widget.TextView(activity).apply {
                    text = key; textSize = 28f; setTextColor(TXT)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                    gravity = android.view.Gravity.CENTER
                })
                if (sub.isNotEmpty()) {
                    inner.addView(android.widget.TextView(activity).apply {
                        text = sub; textSize = 9f; setTextColor(MUTED)
                        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                        letterSpacing = 0.12f; gravity = android.view.Gravity.CENTER
                    })
                }
                cell.addView(inner)
            } else if (key == "DEL") {
                cell.addView(android.widget.TextView(activity).apply {
                    text = "⌫"; textSize = 22f; setTextColor(MUTED)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            }
            keypad.addView(cell)
        }

        keypadWrap.addView(keypad)
        root.addView(keypadWrap)

        // Mostrar como dialog fullscreen
        dlg = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg!!.setContentView(root)
        dlg!!.setCancelable(!isSetup)
        if (!isSetup) {
            dlg!!.setOnCancelListener { onResult(false) }
        }
        dlg!!.show()
    }

}
