package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*

class WelcomeActivity : Activity() {
    private val BG     get() = AppTheme.BG_DEEP
    private val S1     get() = AppTheme.BG_PANEL
    private val GOLD   get() = AppTheme.AMBER
    private val GREEN  get() = AppTheme.GREEN
    private val TXT    get() = AppTheme.TXT_PRI
    private val TXT2   get() = AppTheme.TXT_SEC
    private val TXT3   get() = AppTheme.TXT_MUTED
    private val BORDER get() = AppTheme.BORDER_C
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        AppTheme.init(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = BG

        if (WalletManager.hasPin(this)) {
            // Ya tiene PIN → pedir antes de entrar
            showPinEntry()
        } else {
            // Sin PIN → mostrar splash con opción de crear PIN
            showSplash(pinCreated = false)
        }
    }

    // ── PIN ENTRY (ya tiene PIN) ──────────────────────────────────────────────
    private fun showPinEntry() {
        PinAuthHelper.show(this) { ok ->
            if (ok) showSplash(pinCreated = true)
            else finish()
        }
    }

    // ── SPLASH ────────────────────────────────────────────────────────────────
    private fun showSplash(pinCreated: Boolean) {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG); isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // -- TOP --
        val topSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(56), dp(28), dp(0))
        }
        val eyebrow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        eyebrow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(1)).apply { marginEnd = dp(8) }
            setBackgroundColor(GOLD); alpha = 0.4f
        })
        eyebrow.addView(TextView(this).apply {
            text = "BITCOIN SEED SCANNER"; textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL); letterSpacing = 0.22f
        })
        topSection.addView(eyebrow)
        topSection.addView(TextView(this).apply {
            text = "WALLET"; textSize = 48f; setTextColor(TXT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f; lineHeight = (textSize * 0.95f).toInt()
        })
        topSection.addView(TextView(this).apply {
            text = "HUNTER"; textSize = 48f; setTextColor(GOLD)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = -0.02f
        })
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(4))
        }
        fun pill(txt: String) = TextView(this).apply {
            text = txt; textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL); letterSpacing = 0.08f
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); setStroke(1, BORDER); cornerRadius = dp(100).toFloat()
            }
            setPadding(dp(9), dp(4), dp(9), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        pillRow.addView(pill("libsecp256k1")); pillRow.addView(pill("PBKDF2:1")); pillRow.addView(pill("arm64"))
        topSection.addView(pillRow)
        root.addView(topSection)

        // -- STATS --
        val totalKeys    = SessionStats.totalKeys(this)
        val totalSess    = SessionStats.load(this).size
        val totalMatches = SessionStats.totalMatches(this)
        val bestKps      = SessionStats.bestKps(this)
        val statsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BORDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) }
        }
        fun keysStr(v: Long) = when {
            v >= 1_000_000_000L -> "%.1fB".format(v/1e9)
            v >= 1_000_000L     -> "%.1fM".format(v/1e6)
            v >= 1000L          -> "%.1fK".format(v/1e3)
            else                -> "$v"
        }
        fun statCell(value: String, label: String, color: Int = GOLD): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(S1)
                setPadding(dp(12), dp(16), dp(12), dp(16))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(1,1,0,0) }
            }
            cell.addView(TextView(this).apply {
                text = value; textSize = 15f; setTextColor(color)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = -0.02f
            })
            cell.addView(TextView(this).apply {
                text = label; textSize = 8f; setTextColor(TXT3)
                typeface = Typeface.create("monospace", Typeface.NORMAL); letterSpacing = 0.12f; setPadding(0, dp(4), 0, 0)
            })
            return cell
        }
        statsGrid.addView(statCell(keysStr(totalKeys), "KEYS"))
        statsGrid.addView(statCell("$totalSess", "SESSIONS"))
        statsGrid.addView(statCell("$totalMatches", "MATCHES", if(totalMatches > 0) GREEN else TXT3))
        statsGrid.addView(statCell("%.1f".format(bestKps), "BEST K/S"))
        root.addView(statsGrid)

        // -- CTA --
        val ctaSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(48))
        }

        if (!pinCreated) {
            // Sin PIN: mostrar botón crear PIN primero
            val btnCreatePin = Button(this).apply {
                text = "⚷  Crear PIN de Seguridad"
                textSize = 13f; setTextColor(Color.BLACK)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.06f
                background = GradientDrawable().apply { setColor(GOLD); cornerRadius = dp(4).toFloat() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
                setOnClickListener { showCreatePin() }
            }
            ctaSection.addView(btnCreatePin)

            // Botón saltar (secundario)
            val btnSkip = Button(this).apply {
                text = "> Continuar sin PIN"
                textSize = 11f; setTextColor(TXT2)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.06f
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); setStroke(1, BORDER); cornerRadius = dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
                setOnClickListener {
                    startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }
            }
            ctaSection.addView(btnSkip)

        } else {
            // Con PIN: botones normales
            val btnStart = Button(this).apply {
                text = "> Start Hunting"
                textSize = 13f; setTextColor(Color.BLACK)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.08f
                background = GradientDrawable().apply { setColor(GOLD); cornerRadius = dp(4).toFloat() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
                setOnClickListener {
                    startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }
            }
            ctaSection.addView(btnStart)

            val btnWallet = Button(this).apply {
                text = "> Open Wallet"
                textSize = 12f; setTextColor(GOLD)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.08f
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); setStroke(1, Color.parseColor("#3d2800")); cornerRadius = dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
                setOnClickListener { startActivity(Intent(this@WelcomeActivity, WalletActivity::class.java)) }
            }
            ctaSection.addView(btnWallet)
        }

        ctaSection.addView(TextView(this).apply {
            text = "v1.0 · libsecp256k1 · arm64"; textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL); gravity = Gravity.CENTER; letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        })

        root.addView(ctaSection)
        scroll.addView(root)
        setContentView(scroll)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(400).setStartDelay(100).start()
    }

    // ── CREAR PIN ─────────────────────────────────────────────────────────────
    private fun showCreatePin() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_PANEL); cornerRadius = dp(16).toFloat(); setStroke(1, BORDER)
            }
            setPadding(dp(24), dp(20), dp(24), dp(32))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(24), 0, dp(24), 0) }
        }

        sheet.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(BORDER); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22) }
        })
        sheet.addView(TextView(this).apply {
            text = "Crear PIN"; textSize = 16f; setTextColor(TXT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.04f; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(22))
        })

        val pinDisplay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(28))
        }
        val dots = Array(6) {
            View(this).apply {
                val sz = dp(12)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), BORDER)
                }
            }
        }
        dots.forEach { pinDisplay.addView(it) }
        sheet.addView(pinDisplay)

        val tvStatus = TextView(this).apply {
            text = "Elige un PIN de 6 dígitos"; textSize = 10f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.05f; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16))
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
            if (k == "DEL") { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length-1); updateDots(); return }
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return
            if (firstPin.isEmpty()) {
                firstPin = pin.toString(); pin.clear(); updateDots()
                tvStatus.text = "Confirma tu PIN"; tvStatus.setTextColor(AppTheme.CYAN)
            } else if (firstPin == pin.toString()) {
                WalletManager.savePin(this, pin.toString())
                dlg?.dismiss()
                showSplash(pinCreated = true)
            } else {
                firstPin = ""; pin.clear(); updateDots()
                tvStatus.text = "PINs no coinciden"; tvStatus.setTextColor(0xFFFF4444.toInt())
            }
        }

        val numpad = android.widget.GridLayout(this).apply {
            columnCount = 3; rowCount = 4; setPadding(0, 0, 0, dp(10))
        }
        listOf("1","2","3","4","5","6","7","8","9","","0","DEL").forEach { k ->
            numpad.addView(Button(this).apply {
                text = k
                if (k == "DEL") { textSize = 12f; setTextColor(0xFFFF4444.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL) }
                else { textSize = 22f; setTextColor(TXT); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) }
                background = GradientDrawable().apply {
                    setColor(if (k.isEmpty()) Color.TRANSPARENT else AppTheme.BG_CARD)
                    if (k.isNotEmpty()) setStroke(1, BORDER)
                    cornerRadius = dp(10).toFloat()
                }
                val sz = dp(76)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sz; height = sz; setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                isEnabled = k.isNotEmpty()
                if (k.isNotEmpty()) setOnClickListener { handleDigit(k) }
            })
        }
        sheet.addView(numpad)
        sheet.addView(tvStatus)

        dlg = android.app.AlertDialog.Builder(this).setView(sheet).setCancelable(false).create()
        dlg!!.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg!!.show()
    }
}
