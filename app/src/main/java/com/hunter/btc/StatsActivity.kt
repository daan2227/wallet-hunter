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

    private val ACCENT  get() = AppTheme.ACCENT
    private val ACCENT2 get() = AppTheme.BLUE
    private val BG      get() = AppTheme.BG_DEEP
    private val SURFACE get() = AppTheme.BG_CARD
    private val BORDER  get() = AppTheme.BORDER_C
    private val TXT     get() = AppTheme.TXT_PRI
    private val MUTED   get() = AppTheme.TXT_SEC
    private val RED     get() = AppTheme.WARN

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

    /**
     * Barras de actividad de los últimos catorce días.
     *
     * La gráfica anterior no llegaba a dibujarse nunca: se pintaba con
     * `setOnDraw {}`, una extensión que al final del fichero está declarada
     * como no-op — "usar CustomChartView en su lugar", decía el comentario, y
     * ese CustomChartView no se llegó a escribir. Así que la tarjeta
     * "VELOCIDAD POR SESIÓN" salía vacía.
     *
     * Esto es una View de verdad con su onDraw.
     */
    private class BarsView(ctx: android.content.Context, val valores: LongArray) :
            android.view.View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = ctx.resources.displayMetrics.density
        override fun onDraw(c: Canvas) {
            if (valores.isEmpty()) return
            val max = valores.max().coerceAtLeast(1L)
            val hueco = 5f * d
            val ancho = (width - hueco * (valores.size - 1)) / valores.size
            val radio = 2f * d
            valores.forEachIndexed { i, v ->
                // Un día sin buscar no es una barra de altura cero invisible:
                // se deja un tocón gris para que el hueco se vea.
                val frac = v.toDouble() / max
                val alto = if (v == 0L) 4f * d else (height * frac).toFloat().coerceAtLeast(4f * d)
                p.color = when {
                    v == 0L      -> AppTheme.BG_ELEV
                    frac > 0.70  -> AppTheme.ACCENT
                    else         -> 0xFF2A4A40.toInt()
                }
                val x = i * (ancho + hueco)
                c.drawRoundRect(x, height - alto, x + ancho, height.toFloat(), radio, radio, p)
            }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        AppTheme.init(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(0, 0, 0, dp(26))
        }
        fun side(v: View, top: Int = 0, bottom: Int = 0) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(top), dp(AppTheme.PAD_SIDE), dp(bottom))
            }
        }
        fun cap(t: String) = TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.medium(context)
        }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
        }

        fun formatKeys(k: Long): Pair<String, String> = when {
            k >= 1_000_000_000_000L -> "%.2f".format(k / 1e12).replace('.', ',') to "T"
            k >= 1_000_000_000L     -> "%.2f".format(k / 1e9).replace('.', ',')  to "B"
            k >= 1_000_000L         -> "%.1f".format(k / 1e6).replace('.', ',')  to "M"
            k >= 1_000L             -> "%.1f".format(k / 1e3).replace('.', ',')  to "K"
            else                    -> k.toString() to ""
        }
        fun formatTime(sec: Long): String {
            val h = sec / 3600; val m = (sec % 3600) / 60; val s2 = sec % 60
            return when {
                h > 0 -> "${h} h ${"%02d".format(m)}"
                m > 0 -> "$m min"
                else  -> "$s2 s"
            }
        }

        // ── CABECERA ──────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(AppTheme.PAD_SIDE) - dp(10), dp(12), dp(AppTheme.PAD_SIDE), dp(10))
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(MUTED)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "History"; textSize = AppTheme.SP_TITLE; setTextColor(TXT)
            typeface = AppTheme.title(context)
            letterSpacing = -0.01f
        })
        root.addView(header)

        val (totalKeys, totalMatches, _) = getTotals(this)
        val sessions = loadSessions(this)

        // ── ACUMULADO ─────────────────────────────────────────────────────
        //
        // Eran cuatro filas de "etiqueta ......... valor" dentro de una tarjeta
        // rotulada "TOTALES GLOBALES": cuatro datos del mismo peso, ninguno
        // destacado. El total de claves revisadas es la cifra de la pantalla;
        // el resto cabe en una línea debajo.
        root.addView(side(cap("Keys checked in total"), top = 6))
        val (tk, tu) = formatKeys(totalKeys)
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        totalRow.addView(TextView(this).apply {
            text = tk; textSize = AppTheme.SP_DISPLAY; setTextColor(TXT)
            typeface = AppTheme.display(context)
            letterSpacing = -0.04f
        })
        if (tu.isNotEmpty()) totalRow.addView(TextView(this).apply {
            text = tu; textSize = 19f; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        })
        root.addView(side(totalRow, top = 8))
        root.addView(side(TextView(this).apply {
            text = "${sessions.size} sessions · " +
                   if (totalMatches > 0) "$totalMatches find(s)" else "no finds yet"
            textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.body(context)
        }, top = 8, bottom = 24))

        // ── ÚLTIMOS 14 DÍAS ───────────────────────────────────────────────
        //
        // La gráfica anterior era la velocidad de las últimas diez sesiones, un
        // dato que no dice nada por sí solo: la velocidad depende del modo y
        // del móvil. Lo que sí se quiere ver de un vistazo es cuánto has estado
        // buscando, y los huecos.
        val hoy = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val porDia = LongArray(14)
        sessions.forEach { s2 ->
            val diasAtras = ((hoy - s2.optLong("ts", 0)) / 86_400_000L).toInt()
            if (diasAtras in 0..13) porDia[13 - diasAtras] += s2.optLong("keys", 0)
        }
        val diasSinBuscar = porDia.reversedArray().takeWhile { it == 0L }.size

        val chartCard = card().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        val chartHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        chartHead.addView(TextView(this).apply {
            text = "Last 14 days"; textSize = AppTheme.SP_BODY; setTextColor(TXT)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        chartHead.addView(TextView(this).apply {
            text = when (diasSinBuscar) {
                0    -> "you searched today"
                1    -> "1 día sin buscar"
                14   -> "no activity"
                else -> "$diasSinBuscar days without searching"
            }
            textSize = AppTheme.SP_MICRO; setTextColor(MUTED)
            typeface = AppTheme.body(context)
        })
        chartCard.addView(chartHead)
        chartCard.addView(BarsView(this, porDia).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(16) }
        })
        root.addView(side(chartCard, bottom = 20))

        // ── SESIONES ──────────────────────────────────────────────────────
        root.addView(side(cap("Sessions"), bottom = 10))
        val histCard = card()
        val df = SimpleDateFormat("d MMM", Locale.getDefault())
        val hf = SimpleDateFormat("HH:mm", Locale.getDefault())

        if (sessions.isEmpty()) {
            histCard.addView(TextView(this).apply {
                text = "None yet. Start a scan and it will show up here."
                textSize = AppTheme.SP_BODY; setTextColor(MUTED)
                typeface = AppTheme.body(context)
                setPadding(dp(17), dp(20), dp(17), dp(20))
                setLineSpacing(0f, 1.4f)
            })
        } else {
            val ultimas = sessions.take(20)
            ultimas.forEachIndexed { i, session ->
                val ts = session.optLong("ts", 0)
                val mode = session.optString("mode", "BIP39")
                val keys = session.optLong("keys", 0)
                val duration = session.optLong("duration", 0)
                // kps se guarda ya dividido entre mil, así que es K/s.
                val kps = session.optDouble("kps", 0.0)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(17), dp(15), dp(17), dp(15))
                }
                // La pastilla del modo: lo que antes era un emoji distinto en
                // cada móvil, y antes de eso nada.
                val esPuzzle = mode == "PUZZLE"
                val esRaw    = mode == "RAW" || mode == "RAWKEY"
                row.addView(TextView(this).apply {
                    text = if (esPuzzle) "PUZZLE" else if (esRaw) "RAW" else "BIP39"
                    textSize = 9f
                    gravity = Gravity.CENTER
                    typeface = AppTheme.bold(context)
                    setTextColor(when { esPuzzle -> ACCENT; esRaw -> ACCENT2; else -> MUTED })
                    background = GradientDrawable().apply {
                        setColor(AppTheme.BG_ELEV); cornerRadius = dp(11).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                        .apply { marginEnd = dp(13) }
                })
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val (kv, ku) = formatKeys(keys)
                col.addView(TextView(this).apply {
                    text = if (ku.isEmpty()) "$kv keys" else "$kv $ku keys"
                    textSize = AppTheme.SP_BODY; setTextColor(TXT)
                    typeface = AppTheme.bold(context)
                    letterSpacing = -0.01f
                })
                val dias = ((hoy - ts) / 86_400_000L).toInt()
                col.addView(TextView(this).apply {
                    text = when (dias) {
                        0    -> "Today ${hf.format(Date(ts))}"
                        1    -> "Yesterday ${hf.format(Date(ts))}"
                        else -> df.format(Date(ts))
                    } + " · lasted ${formatTime(duration)}"
                    textSize = AppTheme.SP_MICRO; setTextColor(MUTED)
                    typeface = AppTheme.body(context)
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(col)
                row.addView(TextView(this).apply {
                    text = if (kps >= 1000) "%.2f M/s".format(kps / 1000).replace('.', ',')
                           else "%.0f K/s".format(kps)
                    textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
                    typeface = AppTheme.medium(context)
                })
                histCard.addView(row)
                if (i < ultimas.size - 1) histCard.addView(View(this).apply {
                    setBackgroundColor(AppTheme.BORDER_C)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = dp(17); marginEnd = dp(17) }
                })
            }
        }
        root.addView(side(histCard, bottom = 18))

        // ── EXPORTAR / VACIAR ─────────────────────────────────────────────
        //
        // "Limpiar" era una palabra en rojo de 9sp pegada al rótulo "Sessions",
        // que es donde menos se espera encontrar algo que borra.
        fun accion(label: String, icon: Int, ancho: Int, rojo: Boolean, click: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_CARD).toFloat()
                }
                isClickable = true; isFocusable = true
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(ancho, dp(50), if (ancho == 0) 1f else 0f)
                addView(android.widget.ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(if (rojo) MUTED else TXT)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        .apply { marginEnd = dp(9) }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = AppTheme.SP_BODY
                    setTextColor(if (rojo) MUTED else TXT)
                    typeface = AppTheme.medium(context)
                })
            }

        val accionesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accionesRow.addView(accion("Export", R.drawable.ic_export, 0, false) {
            exportarSesiones(sessions)
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(AppTheme.GAP) })
        accionesRow.addView(accion("Clear", R.drawable.ic_trash, dp(118), true) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Clear the history?")
                .setMessage("The ${sessions.size} stored sessions are deleted. This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE)
                        .edit().remove(KEY_SESSIONS).apply()
                    finish(); startActivity(intent)
                }
                .setNegativeButton("Cancel", null).show()
        })
        root.addView(side(accionesRow, top = 18))

        scroll.addView(root)
        setContentView(scroll)
    }

    /** Vuelca el historial a un fichero de texto y abre el selector de envío. */
    private fun exportarSesiones(sessions: List<JSONObject>) {
        if (sessions.isEmpty()) {
            Toast.makeText(this, "There is nothing to export", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val txt = buildString {
                appendLine("Wallet Hunter · session history")
                appendLine("date\tmode\tkeys\tfinds\tseconds\tK/s")
                sessions.forEach {
                    appendLine(listOf(
                        df.format(Date(it.optLong("ts", 0))),
                        it.optString("mode", ""),
                        it.optLong("keys", 0),
                        it.optLong("matches", 0),
                        it.optLong("duration", 0),
                        "%.1f".format(it.optDouble("kps", 0.0))
                    ).joinToString("\t"))
                }
            }
            val f = java.io.File(filesDir, "history.txt").apply { writeText(txt) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", f)
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Export the history"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not export: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
