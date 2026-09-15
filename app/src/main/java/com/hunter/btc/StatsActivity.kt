package com.hunter.btc

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class StatsActivity : Activity() {

    private val ACCENT  = 0xFF00C896.toInt()
    private val ACCENT2 = 0xFF6EA8FE.toInt()
    private val BG      = 0xFF0E0E0E.toInt()
    private val SURFACE = 0xFF161616.toInt()
    private val BORDER  = 0xFF1D1D1D.toInt()
    private val TXT     = 0xFFF2F2F2.toInt()
    private val MUTED   = 0xFF8A8A8A.toInt()
    private val RED     = 0xFFFF6B35.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_HISTORY = "session_history"
        const val KEY_SESSIONS  = "sessions_json"
        const val MAX_SESSIONS  = 50

        fun saveSession(ctx: android.content.Context, 
                        mode: String, keys: Long, matches: Long, 
                        durationSec: Long, kps: Double) {
            val prefs = ctx.getSharedPreferences(PREFS_HISTORY, android.content.Context.MODE_PRIVATE)
            val arr = try {
                JSONArray(prefs.getString(KEY_SESSIONS, "[]") ?: "[]")
            } catch (e: Exception) { JSONArray() }

            val session = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("mode", mode)
                put("keys", keys)
                put("matches", matches)
                put("duration", durationSec)
                put("kps", kps)
            }

            // Insertar al inicio
            val newArr = JSONArray()
            newArr.put(session)
            for (i in 0 until minOf(arr.length(), MAX_SESSIONS - 1)) {
                newArr.put(arr.getJSONObject(i))
            }

            prefs.edit().putString(KEY_SESSIONS, newArr.toString()).apply()
        }

        fun loadSessions(ctx: android.content.Context): List<JSONObject> {
            val prefs = ctx.getSharedPreferences(PREFS_HISTORY, android.content.Context.MODE_PRIVATE)
            return try {
                val arr = JSONArray(prefs.getString(KEY_SESSIONS, "[]") ?: "[]")
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } catch (e: Exception) { emptyList() }
        }

        fun getTotals(ctx: android.content.Context): Triple<Long, Long, Long> {
            val sessions = loadSessions(ctx)
            val totalKeys = sessions.sumOf { it.optLong("keys", 0) }
            val totalMatches = sessions.sumOf { it.optLong("matches", 0) }
            val totalTime = sessions.sumOf { it.optLong("duration", 0) }
            return Triple(totalKeys, totalMatches, totalTime)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(0), dp(12), dp(40))
        }

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = dp(14).toFloat(); setStroke(1, BORDER)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 9f; setTextColor(MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // ── HEADER ────────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(16))
        }
        header.addView(Button(this).apply {
            text = "←"; textSize = 16f; setTextColor(ACCENT)
            background = GradientDrawable().apply {
                setColor(SURFACE); setStroke(1, BORDER); cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Estadísticas"; textSize = 20f; setTextColor(TXT)
            typeface = AppTheme.title(context)
        })
        root.addView(header)

        // ── TOTALES GLOBALES ──────────────────────────────────────────────
        val (totalKeys, totalMatches, totalTime) = getTotals(this)
        val sessions = loadSessions(this)

        val globalCard = card()
        globalCard.addView(label("TOTALES GLOBALES"))

        fun statRow(lbl: String, value: String, color: Int = TXT): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                addView(TextView(this@StatsActivity).apply {
                    text = lbl; textSize = 11f; setTextColor(MUTED)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@StatsActivity).apply {
                    text = value; textSize = 13f; setTextColor(color)
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                })
            }
        }

        fun formatKeys(k: Long): String = when {
            k >= 1_000_000_000 -> "${"%.2f".format(k/1e9)}B"
            k >= 1_000_000 -> "${"%.2f".format(k/1e6)}M"
            k >= 1_000 -> "${"%.1f".format(k/1e3)}K"
            else -> k.toString()
        }

        fun formatTime(sec: Long): String {
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
        }

        globalCard.addView(statRow("Keys escaneadas", formatKeys(totalKeys), ACCENT))
        globalCard.addView(statRow("Matches encontrados", totalMatches.toString(),
            if (totalMatches > 0) ACCENT else MUTED))
        globalCard.addView(statRow("Tiempo total", formatTime(totalTime)))
        globalCard.addView(statRow("Sesiones totales", sessions.size.toString()))

        // El guard miraba `sessions`, pero el filtro descarta las de kps<=0:
        // con sesiones registradas y todas a cero, .average() sobre la lista
        // vacía devuelve NaN.
        val kpsValues = sessions.mapNotNull { it.optDouble("kps").takeIf { v -> v > 0 } }
        val avgKps = if (kpsValues.isNotEmpty()) kpsValues.average() else 0.0
        globalCard.addView(statRow("Velocidad promedio",
            if (avgKps > 0) "${"%.0f".format(avgKps)} k/s" else "—"))

        root.addView(globalCard)

        // ── GRÁFICA DE VELOCIDAD (últimas 10 sesiones) ────────────────────
        if (sessions.size >= 2) {
            val chartCard = card()
            chartCard.addView(label("VELOCIDAD POR SESIÓN (últimas 10)"))

            val recent = sessions.take(10).reversed()
            val maxKps = recent.mapNotNull { it.optDouble("kps").takeIf { v -> v > 0 } }
                .maxOrNull() ?: 1.0

            val chartHeight = dp(80)
            val chartView = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, chartHeight
                )
                setOnDraw { canvas ->
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val barW = w / (recent.size * 1.5f)
                    val gap = barW * 0.5f
                    val paintBar = Paint().apply { isAntiAlias = true }
                    val paintLine = Paint().apply {
                        color = 0xFF1D1D1D.toInt(); strokeWidth = 1f; isAntiAlias = true
                    }
                    // Grid line
                    canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, paintLine)

                    recent.forEachIndexed { i, session ->
                        val kps = session.optDouble("kps", 0.0)
                        val barH = if (maxKps > 0) (kps / maxKps * h * 0.9f).toFloat() else 0f
                        val x = i * (barW + gap) + gap
                        val alpha = (155 + (100 * i / recent.size)).coerceIn(0, 255)
                        paintBar.color = android.graphics.Color.argb(alpha, 0, 200, 150)
                        canvas.drawRoundRect(x, h - barH, x + barW, h, dp(3).toFloat(), dp(3).toFloat(), paintBar)
                    }
                }
            }
            chartCard.addView(chartView)
            root.addView(chartCard)
        }

        // ── HISTORIAL DE SESIONES ─────────────────────────────────────────
        val histCard = card()
        val histHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        histHeader.addView(TextView(this).apply {
            text = "HISTORIAL DE SESIONES"; textSize = 9f; setTextColor(MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        histHeader.addView(TextView(this).apply {
            text = "limpiar"; textSize = 9f; setTextColor(RED)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            isClickable = true; isFocusable = true
            setOnClickListener {
                android.app.AlertDialog.Builder(this@StatsActivity)
                    .setTitle("Limpiar historial")
                    .setMessage("¿Borrar todas las sesiones guardadas?")
                    .setPositiveButton("Borrar") { _, _ ->
                        getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE)
                            .edit().remove(KEY_SESSIONS).apply()
                        finish()
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        })
        histCard.addView(histHeader)

        val df = SimpleDateFormat("dd/MM HH:mm", Locale.US)

        if (sessions.isEmpty()) {
            histCard.addView(TextView(this).apply {
                text = "Sin sesiones aun. Inicia un scan para registrar actividad."
                textSize = 11f; setTextColor(MUTED)
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            })
        } else {
            sessions.take(20).forEach { session ->
                val ts = session.optLong("ts", 0)
                val mode = session.optString("mode", "BIP39")
                val keys = session.optLong("keys", 0)
                val matches = session.optLong("matches", 0)
                val duration = session.optLong("duration", 0)
                val kps = session.optDouble("kps", 0.0)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(0xFF161616.toInt()); cornerRadius = dp(10).toFloat()
                        setStroke(1, BORDER)
                        if (matches > 0) setStroke(1, 0xFF00C896.toInt())
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }

                val modeIcon = when (mode) { "PUZZLE" -> "🧩"; else -> "⚡" }
                val left = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                left.addView(TextView(this).apply {
                    text = "$modeIcon  ${df.format(Date(ts))}  ·  ${formatTime(duration)}"
                    textSize = 11f; setTextColor(TXT)
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                })
                left.addView(TextView(this).apply {
                    text = "${formatKeys(keys)} keys  ·  ${"%.0f".format(kps)} k/s"
                    textSize = 10f; setTextColor(MUTED)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
                row.addView(left)

                if (matches > 0) {
                    row.addView(TextView(this).apply {
                        text = "✓ $matches"
                        textSize = 13f; setTextColor(ACCENT)
                        typeface = Typeface.create("monospace", Typeface.BOLD)
                    })
                }
                histCard.addView(row)
            }
        }
        root.addView(histCard)

        scroll.addView(root)
        setContentView(scroll)
    }
}

// Extension para onDraw sin subclase
private fun android.view.View.setOnDraw(block: android.view.View.(Canvas) -> Unit) {
    // No-op placeholder — usar CustomChartView en su lugar
}
