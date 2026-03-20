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
        showPinDialog(isSetup = false) { ok ->
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
                showPinDialog(isSetup = true) { ok ->
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

    // ── PIN DIALOG (mismo diseño que WalletActivity) ──────────────────────────
    private fun showPinDialog(isSetup: Boolean, onResult: (Boolean) -> Unit) {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_PANEL)
                cornerRadius = dp(16).toFloat()
                setStroke(1, BORDER)
            }
            setPadding(dp(24), dp(20), dp(24), dp(32))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(24), 0, dp(24), 0) }
        }

        sheet.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(BORDER); cornerRadius = dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22)
            }
        })

        sheet.addView(TextView(this).apply {
            text = if (isSetup) "Crear PIN" else "Ingresar PIN"
            textSize = 16f; setTextColor(TXT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.04f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(22))
        })

        val pinDisplay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        val dots = Array(6) {
            View(this).apply {
                val sz = dp(12)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), BORDER)
                }
            }
        }
        dots.forEach { pinDisplay.addView(it) }
        sheet.addView(pinDisplay)

        val tvStatus = TextView(this).apply {
            text = if (isSetup) "Elige un PIN de 6 dígitos" else "Ingresa tu PIN"
            textSize = 10f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.05f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        val pin = StringBuilder()
        var firstPin = ""
        var dlg: android.app.AlertDialog? = null

        fun updateDots() = dots.forEachIndexed { i, d ->
            val bg = d.background as GradientDrawable
            if (i < pin.length) { bg.setColor(GOLD); bg.setStroke(0, Color.TRANSPARENT) }
            else { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(2), BORDER) }
        }

        fun handleDigit(k: String) {
            if (k == "DEL") {
                if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1)
                updateDots(); return
            }
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return

            if (isSetup) {
                if (firstPin.isEmpty()) {
                    firstPin = pin.toString(); pin.clear(); updateDots()
                    tvStatus.text = "Confirma tu PIN"
                    tvStatus.setTextColor(AppTheme.CYAN)
                } else if (firstPin == pin.toString()) {
                    WalletManager.savePin(this, pin.toString())
                    dlg?.dismiss(); onResult(true)
                } else {
                    firstPin = ""; pin.clear(); updateDots()
                    tvStatus.text = "PINs no coinciden"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                }
            } else {
                if (WalletManager.checkPin(this, pin.toString())) {
                    dlg?.dismiss(); onResult(true)
                } else {
                    pin.clear(); updateDots()
                    tvStatus.text = "PIN incorrecto"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                }
            }
        }

        val numpad = android.widget.GridLayout(this).apply {
            columnCount = 3; rowCount = 4; setPadding(0, 0, 0, dp(10))
        }
        listOf("1","2","3","4","5","6","7","8","9","","0","DEL").forEach { k ->
            numpad.addView(Button(this).apply {
                text = k
                if (k == "DEL") {
                    textSize = 12f; setTextColor(0xFFFF4444.toInt())
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                } else {
                    textSize = 22f; setTextColor(TXT)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                }
                background = GradientDrawable().apply {
                    setColor(if (k.isEmpty()) Color.TRANSPARENT else AppTheme.BG_CARD)
                    if (k.isNotEmpty()) setStroke(1, BORDER)
                    cornerRadius = dp(10).toFloat()
                }
                val sz = dp(76)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sz; height = sz
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                isEnabled = k.isNotEmpty()
                if (k.isNotEmpty()) setOnClickListener { handleDigit(k) }
            })
        }
        sheet.addView(numpad)
        sheet.addView(tvStatus)

        if (!isSetup) {
            val btnCancel = Button(this).apply {
                text = "Cancelar"; textSize = 12f; setTextColor(AppTheme.TXT_SEC)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = 0.1f; isAllCaps = true
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); setStroke(1, BORDER)
                    cornerRadius = dp(6).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                ).apply { topMargin = dp(8) }
            }
            sheet.addView(btnCancel)
            dlg = android.app.AlertDialog.Builder(this)
                .setView(sheet).setCancelable(false).create()
            btnCancel.setOnClickListener { dlg?.dismiss(); onResult(false) }
        } else {
            dlg = android.app.AlertDialog.Builder(this)
                .setView(sheet).setCancelable(false).create()
        }

        dlg!!.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg!!.show()
    }
}
