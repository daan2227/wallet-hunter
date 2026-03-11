package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*

class WelcomeActivity : Activity() {
    private val AMBER     = Color.parseColor("#f59e0b")
    private val GREEN     = Color.parseColor("#10d97a")
    private val CYAN      = Color.parseColor("#38bdf8")
    private val BG_DEEP   = Color.parseColor("#080b10")
    private val BG_CARD   = Color.parseColor("#111822")
    private val BG_ELEV   = Color.parseColor("#162030")
    private val TXT_PRI   = Color.parseColor("#e2e8f0")
    private val TXT_SEC   = Color.parseColor("#64748b")
    private val TXT_MUTED = Color.parseColor("#334155")
    private val BORDER_C  = Color.parseColor("#1a2332")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        // Animated title
        val tvTitle = TextView(this).apply {
            text = "WALLET\nHUNTER"
            textSize = 42f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER; letterSpacing = 0.15f
            alpha = 0f
        }
        root.addView(tvTitle)

        val tvSub = TextView(this).apply {
            text = "Bitcoin Seed Scanner"; textSize = 13f; setTextColor(TXT_SEC)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(32))
            alpha = 0f
        }
        root.addView(tvSub)

        // Stats cards
        val totalKeys    = SessionStats.totalKeys(this)
        val totalSess    = SessionStats.load(this).size
        val totalMatches = SessionStats.totalMatches(this)
        val bestKps      = SessionStats.bestKps(this)

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            alpha = 0f
        }

        fun statCard(value: String, label: String, color: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
            setPadding(dp(8), dp(12), dp(8), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            addView(TextView(this@WelcomeActivity).apply { text = value; textSize = 16f; setTextColor(color); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER })
            addView(TextView(this@WelcomeActivity).apply { text = label; textSize = 8f; setTextColor(TXT_MUTED); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
        }

        val keysStr = when {
            totalKeys >= 1_000_000_000L -> "%.1fB".format(totalKeys/1e9)
            totalKeys >= 1_000_000L -> "%.1fM".format(totalKeys/1e6)
            totalKeys >= 1000L -> "%.1fK".format(totalKeys/1e3)
            else -> "$totalKeys"
        }
        statsRow.addView(statCard(keysStr, "KEYS", AMBER))
        statsRow.addView(statCard("$totalSess", "SESSIONS", CYAN))
        statsRow.addView(statCard("$totalMatches", "MATCHES", GREEN))
        statsRow.addView(statCard("%.1f".format(bestKps), "BEST K/S", AMBER))
        root.addView(statsRow)

        // Divider
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(28); bottomMargin = dp(28) }
            setBackgroundColor(BORDER_C)
            alpha = 0f
        })

        // Start button
        val btnStart = Button(this).apply {
            text = "START HUNTING"; textSize = 14f; setTextColor(Color.BLACK)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            alpha = 0f
            setOnClickListener {
                startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }
        root.addView(btnStart)

        val btnWallet = Button(this).apply {
            text = "Open Wallet"; textSize = 11f; setTextColor(GREEN)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, Color.parseColor("#0d5c2e")); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
            alpha = 0f
            setOnClickListener {
                startActivity(Intent(this@WelcomeActivity, WalletActivity::class.java))
            }
        }
        root.addView(btnWallet)

        val tvVersion = TextView(this).apply {
            text = "v1.0 | libsecp256k1 | arm64"; textSize = 8f; setTextColor(TXT_MUTED)
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0); alpha = 0f
        }
        root.addView(tvVersion)

        setContentView(root)

        // Animate in
        val views = listOf(tvTitle, tvSub, statsRow, btnStart, btnWallet, tvVersion)
        views.forEachIndexed { i, v ->
            v.animate().alpha(1f).setStartDelay((i * 120 + 200).toLong()).setDuration(400).start()
        }
    }
}
