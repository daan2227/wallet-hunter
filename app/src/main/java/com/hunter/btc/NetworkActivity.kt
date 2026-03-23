package com.hunter.btc

import android.os.Bundle
import android.widget.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity

class NetworkActivity : AppCompatActivity() {

    private var tvLog: TextView? = null
    private var tvWorkers: TextView? = null
    private var tvIp: TextView? = null
    private var etMasterIp: EditText? = null
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

        root.addView(TextView(this).apply {
            text = "Red Multi-Dispositivo"
            textSize = 18f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
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
        NetworkManager.startMaster(this, 71, "400000000000000000", "7fffffffffffffffff")
        btnMaster?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "Master iniciado - IP: ${NetworkManager.getLocalIp(this)}", Toast.LENGTH_LONG).show()
    }

    private fun startAsWorker() {
        val ip = etMasterIp?.text?.toString()?.trim() ?: ""
        if (ip.isEmpty()) {
            Toast.makeText(this, "Ingresa la IP del Master", Toast.LENGTH_SHORT).show()
            return
        }
        NetworkManager.onBlock = { block ->
            runOnUiThread {
                Toast.makeText(this, "Bloque: #${block.blockId} - ${block.rangeStart}", Toast.LENGTH_LONG).show()
                HunterEngine.setRange(block.rangeStart, block.rangeEnd)
            }
        }
        NetworkManager.startWorker(ip)
        btnWorker?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
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
        NetworkManager.onLog     = null
        NetworkManager.onWorkers = null
        NetworkManager.onBlock   = null
    }
}
