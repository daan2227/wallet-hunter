#!/usr/bin/env python3
"""
rebuild_pin.py
Reemplaza showPinDialog() en WelcomeActivity y PinAuthHelper
con el diseño del HTML: pantalla completa, dots animados, teclado con sub-letras.
"""

import os
PROJECT_ROOT = os.getcwd()
KT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "hunter", "btc")

# ── Función PIN reutilizable ──────────────────────────────────────────────────
PIN_FUNCTION = '''
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
'''

# ── WelcomeActivity ───────────────────────────────────────────────────────────
welcome_path = os.path.join(KT_DIR, "WelcomeActivity.kt")
welcome = open(welcome_path).read()

# Reemplazar showPinDialog con showPinScreen
import re

# Eliminar el método showPinDialog existente
welcome = re.sub(
    r'    // ── PIN DIALOG.*?^    \}',
    '',
    welcome, flags=re.DOTALL | re.MULTILINE
)

# Agregar showPinScreen antes del último }
welcome = welcome.rstrip().rstrip('}').rstrip() + '\n' + PIN_FUNCTION + '\n}\n'

# Reemplazar llamadas
welcome = welcome.replace(
    'showPinDialog(isSetup = false)',
    'showPinScreen(this, isSetup = false)'
)
welcome = welcome.replace(
    'showPinDialog(isSetup = true)',
    'showPinScreen(this, isSetup = true)'
)

with open(welcome_path, 'w') as f:
    f.write(welcome)
print("✓ WelcomeActivity.kt actualizado")

# ── PinAuthHelper ─────────────────────────────────────────────────────────────
helper_path = os.path.join(KT_DIR, "PinAuthHelper.kt")
helper_content = open(helper_path).read()

# Reemplazar el método show() con uno que use showPinScreen via Activity
new_show = '''    fun show(activity: android.app.Activity, onResult: (Boolean) -> Unit) {
        if (!WalletManager.hasPin(activity)) { onResult(true); return }
        showPinScreen(activity, isSetup = false) { ok ->
            if (ok) markAuthenticated()
            onResult(ok)
        }
    }
'''

# Reemplazar el show existente
helper_content = re.sub(
    r'    fun show\(activity.*?^    \}',
    new_show.strip(),
    helper_content, flags=re.DOTALL | re.MULTILINE
)

# Agregar showPinScreen antes del último }
helper_content = helper_content.rstrip().rstrip('}').rstrip() + '\n' + PIN_FUNCTION + '\n}\n'

with open(helper_path, 'w') as f:
    f.write(helper_content)
print("✓ PinAuthHelper.kt actualizado")

print("""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PIN UI reconstruida:

  - Pantalla completa (fullscreen dialog)
  - Dots animados con glow verde/rojo
  - Teclado con sub-letras (ABC, DEF...)
  - Feedback táctil en cada tecla
  - Shake animation en PIN incorrecto

Siguiente: git add + push
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
