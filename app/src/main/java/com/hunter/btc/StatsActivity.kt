package com.hunter.btc

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*

class StatsActivity : Activity() {
    private val AMBER   = Color.parseColor("#f59e0b")
    private val GREEN   = Color.parseColor("#10d97a")
    private val CYAN    = Color.parseColor("#38bdf8")
    private val BG_DEEP = Color.parseColor("#080b10")
    private val BG_CARD = Color.parseColor("#111822")
    private val BG_ELEV = Color.parseColor("#162030")
    private val TXT_PRI = Color.parseColor("#e2e8f0")
    private val TXT_SEC = Color.parseColor("#64748b")
    private val TXT_MUTED = Color.parseColor("#334155")
    private val BORDER_C = Color.parseColor("#1a2332")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0d1117"))
            setPadding(dp(16), dp(12), dp(16), dp(12)); gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "<"; textSize = 14f; setTextColor(AMBER)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C) }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "SESSION HISTORY"; textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }

        val sessions = SessionStats.load(this)
        val totalKeys = SessionStats.totalKeys(this)
        val totalMatches = SessionStats.totalMatches(this)
        val bestKps = SessionStats.bestKps(this)

        // Summary cards row
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        fun summaryCard(label: String, value: String, color: Int): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
                setPadding(dp(8), dp(10), dp(8), dp(10))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                addView(TextView(this@StatsActivity).apply { text = value; textSize = 14f; setTextColor(color); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER })
                addView(TextView(this@StatsActivity).apply { text = label; textSize = 8f; setTextColor(TXT_SEC); gravity = Gravity.CENTER })
            }
        }
        val totalKeysStr = if (totalKeys >= 1_000_000_000) "%.1fB".format(totalKeys/1e9)
                           else if (totalKeys >= 1_000_000) "%.1fM".format(totalKeys/1e6)
                           else if (totalKeys >= 1000) "%.1fK".format(totalKeys/1e3)
                           else "$totalKeys"
        row.addView(summaryCard("Total Keys", totalKeysStr, AMBER))
        row.addView(summaryCard("Sessions", "${sessions.size}", CYAN))
        row.addView(summaryCard("Matches", "$totalMatches", GREEN))
        row.addView(summaryCard("Best K/s", "%.1f".format(bestKps), AMBER))
        ll.addView(row)

        // Speed chart from last 20 sessions
        if (sessions.isNotEmpty()) {
            ll.addView(TextView(this).apply { text = "SPEED HISTORY"; textSize = 9f; setTextColor(AMBER); typeface = Typeface.create("monospace", Typeface.BOLD); setPadding(0, dp(14), 0, dp(4)) })
            val chartData = sessions.take(20).map { it.avgKps.toFloat() }.reversed()
            ll.addView(SpeedHistoryChart(this, chartData).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)).apply { bottomMargin = dp(8) }
            })
        }

        // Session list
        ll.addView(TextView(this).apply { text = "RECENT SESSIONS"; textSize = 9f; setTextColor(AMBER); typeface = Typeface.create("monospace", Typeface.BOLD); setPadding(0, dp(8), 0, dp(4)) })

        if (sessions.isEmpty()) {
            ll.addView(TextView(this).apply { text = "No sessions yet. Start the hunter!"; textSize = 11f; setTextColor(TXT_MUTED); setPadding(0, dp(16), 0, 0) })
        } else {
            sessions.forEach { s ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                }
                val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                topRow.addView(TextView(this).apply {
                    text = s.date; textSize = 10f; setTextColor(TXT_PRI); typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (s.matches > 0) topRow.addView(TextView(this).apply {
                    text = "MATCH x${s.matches}"; textSize = 9f; setTextColor(GREEN)
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#0d3320")); setStroke(1, Color.parseColor("#0d5c2e")) }
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                })
                card.addView(topRow)

                val keysStr = if (s.keysScanned >= 1_000_000) "%.2fM keys".format(s.keysScanned/1e6)
                              else if (s.keysScanned >= 1000) "%.1fK keys".format(s.keysScanned/1e3)
                              else "${s.keysScanned} keys"
                val durStr = "%02d:%02d:%02d".format(s.durationSec/3600, (s.durationSec%3600)/60, s.durationSec%60)
                card.addView(TextView(this).apply {
                    text = "$keysStr  |  %.1f k/s  |  $durStr".format(s.avgKps)
                    textSize = 9f; setTextColor(TXT_SEC); typeface = Typeface.MONOSPACE
                    setPadding(0, dp(3), 0, 0)
                })
                ll.addView(card)
            }
        }

        scroll.addView(ll); root.addView(scroll)
        setContentView(root)
    }
}

class SpeedHistoryChart(ctx: android.content.Context, private val data: List<Float>) : android.view.View(ctx) {
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#f59e0b"); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintDot  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#f59e0b"); style = Paint.Style.FILL }
    private val paintGrid = Paint().apply { color = Color.parseColor("#1a2332"); strokeWidth = 1f }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val pad = 12f
        val maxVal = data.max().coerceAtLeast(0.1f)
        val minVal = 0f
        val range = maxVal - minVal

        // Grid lines
        for (i in 0..3) { val y = pad + (h - 2*pad) * i / 3; canvas.drawLine(pad, y, w - pad, y, paintGrid) }

        if (data.size < 2) return
        val step = (w - 2*pad) / (data.size - 1)

        fun xOf(i: Int) = pad + i * step
        fun yOf(v: Float) = pad + (h - 2*pad) * (1f - (v - minVal) / range)

        // Fill gradient
        val path = Path()
        path.moveTo(xOf(0), yOf(data[0]))
        for (i in 1 until data.size) path.lineTo(xOf(i), yOf(data[i]))
        path.lineTo(xOf(data.size - 1), h - pad)
        path.lineTo(xOf(0), h - pad)
        path.close()
        val grad = LinearGradient(0f, pad, 0f, h, Color.parseColor("#4df59e0b"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        paintFill.shader = grad
        canvas.drawPath(path, paintFill)

        // Line
        val linePath = Path()
        linePath.moveTo(xOf(0), yOf(data[0]))
        for (i in 1 until data.size) linePath.lineTo(xOf(i), yOf(data[i]))
        canvas.drawPath(linePath, paintLine)

        // Last dot
        canvas.drawCircle(xOf(data.size - 1), yOf(data.last()), 5f, paintDot)
    }
}
