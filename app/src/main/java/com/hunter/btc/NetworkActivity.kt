package com.hunter.btc

import android.os.Bundle
import android.widget.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class NetworkActivity : AppCompatActivity() {

    private var tvLog: TextView? = null
    private var tvWorkers: TextView? = null
    private var tvIp: TextView? = null
    private var etMasterIp: EditText? = null
    private var etCode: EditText? = null
    private var btnMaster: Button? = null
    private var btnWorker: Button? = null
    private var btnStop: Button? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val BG    = AppTheme.BG_DEEP
        val TXT   = AppTheme.TXT_PRI
        val MUTED = AppTheme.TXT_SEC

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // Header con botón back
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        headerRow.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(AppTheme.TXT_PRI)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
            setOnClickListener { finish() }
        })
        headerRow.addView(TextView(this).apply {
            text = "Red multi-dispositivo"
            textSize = 20f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(headerRow)
        root.addView(TextView(this).apply {
            text = "Reparte el trabajo entre varios móviles de la misma red."
            textSize = AppTheme.SP_BODY; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            setPadding(0, dp(2), 0, dp(20))
        })

        tvIp = TextView(this).apply {
            text = "IP: ${NetworkManager.getLocalIp(this@NetworkActivity)}"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
            typeface = Typeface.MONOSPACE   // es una dirección
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        root.addView(tvIp)

        root.addView(sectionLabel("Como maestro"))
        root.addView(TextView(this).apply {
            text = "Este móvil reparte los bloques y recoge los resultados."
            textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        })
        btnMaster = actionButton("Iniciar como maestro", AppTheme.ACCENT, AppTheme.BG_DEEP).also {
            it.setOnClickListener { startAsMaster() }
            root.addView(it)
        }

        root.addView(sectionLabel("Como trabajador"))
        root.addView(TextView(this).apply {
            text = "IP del maestro"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(10), 0, dp(6))
        })
        etMasterIp = EditText(this).apply {
            hint = "192.168.1.100"
            setTextColor(TXT); setHintTextColor(MUTED)
            textSize = AppTheme.SP_BODY; typeface = Typeface.MONOSPACE
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(etMasterIp)

        root.addView(TextView(this).apply {
            text = "Código de acceso (lo enseña el maestro)"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(10), 0, dp(6))
        })
        etCode = EditText(this).apply {
            hint = "Ej. K7M2PQRT"
            setTextColor(TXT); setHintTextColor(MUTED)
            textSize = AppTheme.SP_BODY; typeface = Typeface.MONOSPACE
            filters = arrayOf(android.text.InputFilter.AllCaps(),
                              android.text.InputFilter.LengthFilter(8))
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(etCode)

        actionButton("Buscar maestros en la red", AppTheme.BG_ELEV, AppTheme.TXT_PRI).also {
            it.setOnClickListener { discoverMasters() }
            root.addView(it)
        }

        btnWorker = actionButton("Conectar como trabajador", AppTheme.ACCENT, AppTheme.BG_DEEP).also {
            it.setOnClickListener { startAsWorker() }
            root.addView(it)
        }

        btnStop = actionButton("Detener la red", AppTheme.BG_ELEV, AppTheme.RED).also {
            it.visibility = android.view.View.GONE
            it.setOnClickListener { stopNetwork() }
            root.addView(it)
        }

        root.addView(sectionLabel("Trabajadores conectados"))
        tvWorkers = TextView(this).apply {
            text = "Ninguno todavía"
            textSize = AppTheme.SP_BODY; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(12) }
        }
        root.addView(tvWorkers)

        root.addView(sectionLabel("Registro"))
        tvLog = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
            typeface = Typeface.MONOSPACE   // es un registro
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        root.addView(tvLog)
        scroll.addView(root)
        setContentView(scroll)

        // Restaurar estado UI si red sigue activa
        if (NetworkManager.isRunning.get()) {
            if (NetworkManager.isMaster) {
                btnMaster?.isEnabled = false
                btnStop?.visibility = android.view.View.VISIBLE
                tvLog?.text = "Master activo en ${NetworkManager.getLocalIp(this)}"
            } else if (NetworkManager.isWorker) {
                btnWorker?.isEnabled = false
                btnStop?.visibility = android.view.View.VISIBLE
                tvLog?.text = "Worker activo"
            }
        }

        NetworkManager.onLog = { msg ->
            runOnUiThread {
                val current = tvLog?.text?.toString() ?: ""
                val lines = current.lines().takeLast(20)
                tvLog?.text = (lines + listOf(msg)).joinToString("\n")
            }
        }
        NetworkManager.onWorkers = { list ->
            runOnUiThread {
                tvWorkers?.text = if (list.isEmpty()) "Sin workers"
                else list.joinToString("\n") { w ->
                    "- ${w.device} (${w.address}) ${w.speed/1000}K/s [${w.status}]"
                }
            }
        }
    }

    private fun startAsMaster() {
        // Leer rango actual desde prefs
        val prefs = getSharedPreferences("hunt_prefs", android.content.Context.MODE_PRIVATE)
        val rangeStart = prefs.getString("current_range_start", "400000000000000000") ?: "400000000000000000"
        val rangeEnd   = prefs.getString("current_range_end",   "7fffffffffffffffff") ?: "7fffffffffffffffff"
        val puzzleNum  = prefs.getInt("current_puzzle_num", 71)
        NetworkManager.startMaster(this, puzzleNum, rangeStart, rangeEnd)
        btnMaster?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        val ip = NetworkManager.getLocalIp(this)
        val code = NetworkManager.authToken
        tvLog?.text = "✓ Master iniciado\nIP: $ip\nCódigo: $code\n" +
                      "Puzzle #$puzzleNum\nRango: ${rangeStart.take(12)}..."
        // El código hay que teclearlo en cada worker; sin él no se aceptan.
        AlertDialog.Builder(this)
            .setTitle("Master activo")
            .setMessage("IP: $ip\n\nCódigo de acceso:\n\n        $code\n\n" +
                        "Introduce este código en cada worker. Sin él, ningún " +
                        "dispositivo de la red puede conectarse.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startAsWorker() {
        val ip = etMasterIp?.text?.toString()?.trim() ?: ""
        if (ip.isEmpty()) {
            Toast.makeText(this, "Ingresa la IP del Master", Toast.LENGTH_SHORT).show()
            return
        }
        val code = etCode?.text?.toString()?.trim() ?: ""
        if (code.isEmpty()) {
            Toast.makeText(this, "Ingresa el código que muestra el Master", Toast.LENGTH_SHORT).show()
            return
        }
        NetworkManager.onBlock = { block ->
            runOnUiThread {
                HunterEngine.setRange(block.rangeStart, block.rangeEnd)
                HunterEngine.setMode(1) // puzzle mode
                if (!HunterEngine.isRunning()) {
                    val prefs = getSharedPreferences("hunt_prefs", android.content.Context.MODE_PRIVATE)
                    val threads = prefs.getInt("puzzle_threads", 3) + 1
                    val cpu = prefs.getInt("puzzle_cpu", 70) + 10
                    HunterEngine.startHunting(threads, cpu)
                    try {
                        startForegroundService(android.content.Intent(this, com.hunter.btc.HunterService::class.java))
                    } catch (e: Exception) {
                        startService(android.content.Intent(this, com.hunter.btc.HunterService::class.java))
                    }
                }
                val log = tvLog?.text?.toString() ?: ""
                tvLog?.text = "$log\n▶ Bloque #${block.blockId}\n  ${block.rangeStart.take(16)}..."
            }
        }
        NetworkManager.startWorker(ip, code)
        btnWorker?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        tvLog?.text = "Conectando a master $ip..."
    }

    private fun discoverMasters() {
        tvLog?.text = "Buscando masters..."
        NetworkManager.discoverMasters(this) { ip, device ->
            runOnUiThread {
                etMasterIp?.setText(ip)
                val current = tvLog?.text?.toString() ?: ""
                tvLog?.text = "$current\nOK Master: $device ($ip)"
            }
        }
    }

    private fun stopNetwork() {
        NetworkManager.stop()
        btnMaster?.isEnabled = true
        btnWorker?.isEnabled = true
        btnStop?.visibility = android.view.View.GONE
        tvWorkers?.text = "Ninguno todavía"
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun actionButton(label: String, color: Int, textColor: Int) = Button(this).apply {
        text = label; textSize = AppTheme.SP_BODY
        setTextColor(textColor)
        typeface = AppTheme.bold(context)
        isAllCaps = false
        stateListAnimator = null
        background = Ui.cardBg(AppTheme.R_INNER, color, context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(4); bottomMargin = dp(8) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // La red sigue activa en background, pero ningún callback puede seguir
        // apuntando a esta Activity destruida. onBlock capturaba `this` y la
        // mantenía viva indefinidamente; se reinstala con el contexto de
        // aplicación para que el worker siga recibiendo bloques.
        NetworkManager.onLog     = null
        NetworkManager.onWorkers = null
        val app = applicationContext
        NetworkManager.onBlock = { block ->
            try {
                HunterEngine.setRange(block.rangeStart, block.rangeEnd)
                HunterEngine.setMode(1)
                if (!HunterEngine.isRunning()) {
                    val prefs = app.getSharedPreferences("hunt_prefs", android.content.Context.MODE_PRIVATE)
                    HunterEngine.startHunting(
                        prefs.getInt("puzzle_threads", 3) + 1,
                        prefs.getInt("puzzle_cpu", 70) + 10)
                }
            } catch (e: Throwable) {
                android.util.Log.e("NetworkActivity", "onBlock: ${e.message}", e)
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
