package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*

class WelcomeActivity : Activity() {
    private val BG      = Color.parseColor("#070910")
    private val S1      = Color.parseColor("#0c0f18")
    private val S2      = Color.parseColor("#111520")
    private val S3      = Color.parseColor("#181d2e")
    private val S4      = Color.parseColor("#1f2640")
    private val GOLD    = Color.parseColor("#f0a500")
    private val GOLD2   = Color.parseColor("#ffc53d")
    private val GREEN   = Color.parseColor("#2dd4a0")
    private val TXT     = Color.parseColor("#e2e6f0")
    private val TXT2    = Color.parseColor("#7a8299")
    private val TXT3    = Color.parseColor("#3d4560")
    private val BORDER  = 0x0fffffff
    private val BORDER2 = 0x1affffff
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = BG

        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // ── TOP SECTION ──
        val topSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(56), dp(28), dp(0))
        }

        // Eyebrow
        val eyebrow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val eyebrowLine = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(1)).apply { marginEnd = dp(8) }
            setBackgroundColor(GOLD); alpha = 0.4f
        }
        eyebrow.addView(eyebrowLine)
        eyebrow.addView(TextView(this).apply {
            text = "BITCOIN SEED SCANNER"
            textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.22f
        })
        topSection.addView(eyebrow)

        // Main title
        topSection.addView(TextView(this).apply {
            text = "WALLET"; textSize = 48f; setTextColor(TXT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f; lineHeight = (textSize * 0.95f).toInt()
        })
        topSection.addView(TextView(this).apply {
            text = "HUNTER"; textSize = 48f; setTextColor(GOLD)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f
        })

        // Pills
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(4))
        }
        fun pill(txt: String) = TextView(this).apply {
            text = txt; textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, BORDER2)
                cornerRadius = dp(100).toFloat()
            }
            setPadding(dp(9), dp(4), dp(9), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        pillRow.addView(pill("libsecp256k1"))
        pillRow.addView(pill("PBKDF2:1"))
        pillRow.addView(pill("arm64"))
        topSection.addView(pillRow)
        root.addView(topSection)

        // ── STATS GRID ──
        val totalKeys    = SessionStats.totalKeys(this)
        val totalSess    = SessionStats.load(this).size
        val totalMatches = SessionStats.totalMatches(this)
        val bestKps      = SessionStats.bestKps(this)

        val statsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BORDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(28)
            }
        }

        fun keysStr(v: Long) = when {
            v >= 1_000_000_000L -> "%.1fB".format(v/1e9)
            v >= 1_000_000L -> "%.1fM".format(v/1e6)
            v >= 1000L -> "%.1fK".format(v/1e3)
            else -> "$v"
        }

        fun statCell(value: String, label: String, color: Int = GOLD): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(S1)
                setPadding(dp(12), dp(16), dp(12), dp(16))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(1, 1, 0, 0)
                }
            }
            cell.addView(TextView(this).apply {
                text = value; textSize = 15f; setTextColor(color)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = -0.02f
            })
            cell.addView(TextView(this).apply {
                text = label; textSize = 8f; setTextColor(TXT3)
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                letterSpacing = 0.12f
                setPadding(0, dp(4), 0, 0)
            })
            return cell
        }

        statsGrid.addView(statCell(keysStr(totalKeys), "KEYS"))
        statsGrid.addView(statCell("$totalSess", "SESSIONS"))
        statsGrid.addView(statCell("$totalMatches", "MATCHES", if(totalMatches > 0) GREEN else TXT3))
        statsGrid.addView(statCell("%.1f".format(bestKps), "BEST K/S"))
        root.addView(statsGrid)

        // ── CTA SECTION ──
        val ctaSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(48))
        }

        // Primary button - Start Hunting
        val btnStart = Button(this).apply {
            text = "> Start Hunting"
            textSize = 13f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                setColor(GOLD)
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener {
                startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }
        ctaSection.addView(btnStart)

        // Secondary button - Open Wallet
        val btnWallet = Button(this).apply {
            text = "> Open Wallet"
            textSize = 12f; setTextColor(GOLD)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, Color.parseColor("#3d2800"))
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(10)
            }
            setOnClickListener {
                startActivity(Intent(this@WelcomeActivity, WalletActivity::class.java))
            }
        }
        ctaSection.addView(btnWallet)

        // Version
        ctaSection.addView(TextView(this).apply {
            text = "v1.0 · libsecp256k1 · arm64"; textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER; letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            }
        })

        root.addView(ctaSection)
        scroll.addView(root)
        setContentView(scroll)

        // Fade in
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(400).setStartDelay(100).start()
    }
}
