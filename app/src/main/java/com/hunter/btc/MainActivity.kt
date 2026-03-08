package com.hunter.btc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 1000L

    /* Views */
    private lateinit var tvStatus:    TextView
    private lateinit var tvWps:       TextView
    private lateinit var tvCount:     TextView
    private lateinit var tvTime:      TextView
    private lateinit var tvMatches:   TextView
    private lateinit var tvLog:       TextView
    private lateinit var btnStart:    Button
    private lateinit var btnStop:     Button
    private lateinit var btnCsv:      Button
    private lateinit var sbThreads:   SeekBar
    private lateinit var sbCpu:       SeekBar
    private lateinit var tvThreads:   TextView
    private lateinit var tvCpu:       TextView
    private lateinit var tvMatchList: TextView

    private var csvPath: String = ""

    companion object {
        const val REQ_CSV = 1001
        val ORANGE = Color.parseColor("#FF8C00")
        val GREEN  = Color.parseColor("#33CC33")
        val RED    = Color.parseColor("#CC2222")
        val YELLOW = Color.parseColor("#FFFF00")
        val DIM    = Color.parseColor("#AAAAAA")
        val BG     = Color.parseColor("#1A1A1E")
        val PANEL  = Color.parseColor("#242428")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        startUpdater()
    }

    private fun buildUI() {
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        /* Titulo */
        main.addView(TextView(this).apply {
            text = "Bitcoin Wallet Hunter"
            textSize = 22f
            setTextColor(ORANGE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        })
        main.addView(TextView(this).apply {
            text = "libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh"
            textSize = 11f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        /* Panel CSV */
        main.addView(sectionLabel("ARCHIVO CSV"))
        btnCsv = Button(this).apply {
            text = "Seleccionar CSV..."
            setBackgroundColor(ORANGE)
            setTextColor(Color.BLACK)
            setOnClickListener { pickCsv() }
        }
        main.addView(btnCsv)
        tvStatus = TextView(this).apply {
            text = "Ningun archivo cargado"
            setTextColor(DIM); textSize = 12f; setPadding(0, 8, 0, 16)
        }
        main.addView(tvStatus)

        /* Panel config */
        main.addView(sectionLabel("CONFIGURACION"))

        tvThreads = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        main.addView(tvThreads)
        sbThreads = SeekBar(this).apply {
            max = 7; progress = 3 /* 4 threads default */
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { updateLabels() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        main.addView(sbThreads)

        tvCpu = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(0, 12, 0, 0) }
        main.addView(tvCpu)
        sbCpu = SeekBar(this).apply {
            max = 90; progress = 70 /* 80% default */
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                    updateLabels()
                    if (HunterEngine.isRunning()) HunterEngine.setCpuLimit(p + 10)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        main.addView(sbCpu)
        updateLabels()

        /* Botones */
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }
        btnStart = Button(this).apply {
            text = "INICIAR"
            setBackgroundColor(GREEN)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { doStart() }
        }
        btnStop = Button(this).apply {
            text = "DETENER"
            setBackgroundColor(RED)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
            setOnClickListener { doStop() }
        }
        btnRow.addView(btnStart)
        btnRow.addView(btnStop)
        main.addView(btnRow)

        /* Estadisticas */
        main.addView(sectionLabel("ESTADISTICAS"))
        val statsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(16, 16, 16, 16)
        }
        tvWps = statRow("0 w/s", "Velocidad")
        tvCount = statRow("0 seeds", "Total procesados")
        tvTime = statRow("00:00:00", "Tiempo activo")
        tvMatches = statRow("Matches: 0", "Coincidencias")
        statsPanel.addView(tvWps)
        statsPanel.addView(tvCount)
        statsPanel.addView(tvTime)
        statsPanel.addView(tvMatches)
        main.addView(statsPanel)

        /* Matches */
        main.addView(sectionLabel("COINCIDENCIAS").apply { setPadding(0, 20, 0, 0) })
        tvMatchList = TextView(this).apply {
            text = "Ninguna aun..."
            setTextColor(YELLOW); textSize = 12f
            setBackgroundColor(PANEL); setPadding(12, 12, 12, 12)
        }
        main.addView(tvMatchList)

        /* Log */
        main.addView(sectionLabel("LOG").apply { setPadding(0, 20, 0, 0) })
        tvLog = TextView(this).apply {
            text = ""
            setTextColor(DIM); textSize = 11f
            setBackgroundColor(PANEL); setPadding(12, 12, 12, 12)
        }
        main.addView(tvLog)

        root.addView(main)
        setContentView(root)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(ORANGE)
        setPadding(0, 12, 0, 6)
    }

    private fun statRow(initial: String, hint: String): TextView {
        return TextView(this).apply {
            text = "$initial\n"
            setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 4, 0, 4)
        }
    }

    private fun updateLabels() {
        val threads = sbThreads.progress + 1
        tvThreads.text = "Threads: $threads"
        val cpu = sbCpu.progress + 10
        val cpuColor = when {
            cpu <= 40 -> GREEN
            cpu <= 70 -> YELLOW
            else -> RED
        }
        tvCpu.setTextColor(cpuColor)
        val cpuLabel = when {
            cpu <= 40 -> "silencioso"
            cpu <= 70 -> "balanceado"
            cpu < 100 -> "rendimiento"
            else -> "maximo"
        }
        tvCpu.text = "Limite CPU: $cpu% ($cpuLabel)"
    }

    private fun pickCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_CSV)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_CSV && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val realPath = getRealPath(uri)
                if (realPath != null) {
                    csvPath = realPath
                    tvStatus.text = "Cargando: ${File(csvPath).name}"
                    tvStatus.setTextColor(YELLOW)
                    HunterEngine.loadCsv(csvPath)
                } else {
                    Toast.makeText(this, "Error: copia el CSV al almacenamiento interno", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getRealPath(uri: Uri): String? {
        /* Para archivos en almacenamiento interno/externo accesibles directamente */
        val path = uri.path ?: return null
        /* Intenta como ruta directa primero */
        if (path.startsWith("/storage") || path.startsWith("/sdcard") || path.startsWith("/data")) {
            val f = File(path)
            if (f.exists()) return f.absolutePath
        }
        /* Intenta extraer de uri de tipo /document/primary:... */
        uri.lastPathSegment?.let { seg ->
            if (seg.startsWith("primary:")) {
                val rel = seg.removePrefix("primary:")
                val f = File("/storage/emulated/0/$rel")
                if (f.exists()) return f.absolutePath
            }
        }
        return null
    }

    private fun doStart() {
        if (!HunterEngine.isCsvLoaded()) {
            Toast.makeText(this, "Carga el CSV primero", Toast.LENGTH_SHORT).show(); return
        }
        val threads = sbThreads.progress + 1
        val cpu = sbCpu.progress + 10
        HunterEngine.startHunting(threads, cpu)
    }

    private fun doStop() {
        HunterEngine.stopHunting()
    }

    private var logBuffer = StringBuilder()

    private val updater = object : Runnable {
        override fun run() {
            /* Drena log */
            var msg: String
            while (true) {
                msg = HunterEngine.popLog()
                if (msg.isEmpty()) break
                logBuffer.insert(0, msg + "\n")
                if (logBuffer.length > 4000) logBuffer.setLength(4000)
            }
            tvLog.text = logBuffer.toString()

            /* Status CSV */
            val loading = HunterEngine.isLoading()
            val loaded  = HunterEngine.isCsvLoaded()
            val running = HunterEngine.isRunning()

            if (loading) {
                tvStatus.text = HunterEngine.getLoadStatus()
                tvStatus.setTextColor(YELLOW)
            } else if (loaded) {
                tvStatus.text = HunterEngine.getLoadStatus()
                tvStatus.setTextColor(GREEN)
            }

            /* Botones */
            btnStart.isEnabled = loaded && !running && !loading
            btnStop.isEnabled  = running

            /* Stats */
            val wps = HunterEngine.getWps()
            tvWps.text = when {
                wps >= 1e6 -> "%.2f M w/s".format(wps / 1e6)
                wps >= 1000 -> "%.1f K w/s".format(wps / 1000)
                else -> "%.0f w/s".format(wps)
            }
            tvWps.setTextColor(if (running) ORANGE else DIM)

            val count = HunterEngine.getCount()
            tvCount.text = if (count >= 1_000_000) "%.2f M seeds".format(count / 1e6) else "$count seeds"

            val elapsed = HunterEngine.getElapsed()
            val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
            tvTime.text = "%02d:%02d:%02d".format(h, m, s)

            val found = HunterEngine.getFound()
            tvMatches.text = "Matches: $found"
            tvMatches.setTextColor(if (found > 0) YELLOW else Color.WHITE)

            /* Matches */
            val matchStr = HunterEngine.getMatches()
            if (matchStr.isNotEmpty()) {
                tvMatchList.text = matchStr
                tvMatchList.setTextColor(YELLOW)
            }

            handler.postDelayed(this, updateInterval)
        }
    }

    private fun startUpdater() { handler.post(updater) }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updater)
        if (HunterEngine.isRunning()) HunterEngine.stopHunting()
    }
}
