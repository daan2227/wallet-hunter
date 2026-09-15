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
        val AMBER = AppTheme.AMBER
        val TXT   = AppTheme.TXT_PRI
        val MUTED = AppTheme.TXT_MUTED
        val CARD  = AppTheme.BG_CARD

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
        headerRow.addView(Button(this).apply {
            text = "<"
            textSize = 14f; setTextColor(AMBER)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(1, AppTheme.BORDER_C); cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)).apply { marginEnd = dp(12) }
            setOnClickListener { finish() }
        })
        headerRow.addView(TextView(this).apply {
            text = "Red Multi-Dispositivo"
            textSize = 18f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(headerRow)
        root.addView(TextView(this).apply {
            text = "Coordina dispositivos en red local WiFi"
            textSize = 11f; setTextColor(MUTED)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(16))
        })

        tvIp = TextView(this).apply {
            text = "IP: ${NetworkManager.getLocalIp(this@NetworkActivity)}"
            textSize = 12f; setTextColor(AppTheme.CYAN)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(0x1100C8D4.toInt()); setStroke(1, AppTheme.CYAN)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        root.addView(tvIp)

        root.addView(sectionLabel("MODO MASTER"))
        root.addView(TextView(this).apply {
            text = "Este dispositivo asigna bloques a los workers"
            textSize = 10f; setTextColor(MUTED); typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        })
        btnMaster = actionButton("Iniciar como Master", AMBER, android.graphics.Color.BLACK).also {
            it.setOnClickListener { startAsMaster() }
            root.addView(it)
        }

        root.addView(sectionLabel("MODO WORKER"))
        root.addView(TextView(this).apply {
            text = "IP del Master:"
            textSize = 11f; setTextColor(TXT)
            setPadding(0, dp(8), 0, dp(4))
        })
        etMasterIp = EditText(this).apply {
            hint = "192.168.1.100"
            setTextColor(TXT); setHintTextColor(MUTED)
            textSize = 13f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(CARD); setStroke(1, 0xFF2A3028.toInt())
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(etMasterIp)

        root.addView(TextView(this).apply {
            text = "Código de acceso (lo muestra el Master):"
            textSize = 11f; setTextColor(TXT)
            setPadding(0, dp(8), 0, dp(4))
        })
        etCode = EditText(this).apply {
            hint = "Ej. K7M2PQRT"
            setTextColor(TXT); setHintTextColor(MUTED)
            textSize = 13f; typeface = Typeface.MONOSPACE
            filters = arrayOf(android.text.InputFilter.AllCaps(),
                              android.text.InputFilter.LengthFilter(8))
            background = GradientDrawable().apply {
                setColor(CARD); setStroke(1, 0xFF2A3028.toInt())
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(etCode)

        actionButton("Buscar Masters en red", AppTheme.CYAN, android.graphics.Color.BLACK).also {
            it.setOnClickListener { discoverMasters() }
            root.addView(it)
        }

        btnWorker = actionButton("Conectar como Worker", 0xFF60A5FA.toInt(), android.graphics.Color.WHITE).also {
            it.setOnClickListener { startAsWorker() }
            root.addView(it)
        }

        btnStop = actionButton("Detener Red", AppTheme.RED, android.graphics.Color.WHITE).also {
            it.visibility = android.view.View.GONE
            it.setOnClickListener { stopNetwork() }
            root.addView(it)
        }

        root.addView(sectionLabel("WORKERS CONECTADOS"))
        tvWorkers = TextView(this).apply {
            text = "Sin workers"
            textSize = 11f; setTextColor(MUTED); typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(6).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(12) }
        }
        root.addView(tvWorkers)

        root.addView(sectionLabel("LOG"))
        tvLog = TextView(this).apply {
            text = "---"
            textSize = 10f; setTextColor(TXT); typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(6).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
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
        tvWorkers?.text = "Sin workers"
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 9f; setTextColor(AppTheme.TXT_MUTED)
        typeface = Typeface.create("monospace", Typeface.BOLD)
        letterSpacing = 0.16f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun actionButton(label: String, color: Int, textColor: Int) = Button(this).apply {
        text = label; textSize = 12f
        setTextColor(textColor)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(8) }
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
