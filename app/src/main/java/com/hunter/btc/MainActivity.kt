package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private var csvPath: String? = null
    private val engine = HunterEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestStoragePermission()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        val title = TextView(this).apply {
            text = "Bitcoin Wallet Hunter"
            textSize = 22f
            setTextColor(0xFFFFAA00.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val csvLabel = TextView(this).apply {
            text = "ARCHIVO CSV"
            setTextColor(0xFFFFAA00.toInt())
            setPadding(0, 16, 0, 4)
        }

        val btnCsv = Button(this).apply {
            text = "SELECCIONAR CSV..."
            setBackgroundColor(0xFFFF8800.toInt())
            setTextColor(0xFF000000.toInt())
        }

        val csvStatus = TextView(this).apply {
            text = "Ningun archivo cargado"
            setTextColor(0xFF888888.toInt())
        }

        val configLabel = TextView(this).apply {
            text = "CONFIGURACION"
            setTextColor(0xFFFFAA00.toInt())
            setPadding(0, 16, 0, 4)
        }

        val threadsLabel = TextView(this).apply {
            text = "Threads: 4"
            setTextColor(0xFFFFFFFF.toInt())
        }

        val threadsSlider = SeekBar(this).apply {
            max = 15
            progress = 3
        }

        val cpuLabel = TextView(this).apply {
            text = "Limite CPU: 80% (rendimiento)"
            setTextColor(0xFFFFAA00.toInt())
        }

        val cpuSlider = SeekBar(this).apply {
            max = 90
            progress = 70
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnStart = Button(this).apply {
            text = "INICIAR"
            setBackgroundColor(0xFF44BB44.toInt())
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnStop = Button(this).apply {
            text = "DETENER"
            setBackgroundColor(0xFFBB3333.toInt())
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val statsLabel = TextView(this).apply {
            text = "ESTADISTICAS"
            setTextColor(0xFFFFAA00.toInt())
            setPadding(0, 16, 0, 4)
        }

        val statsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF2A2A2A.toInt())
            setPadding(12, 12, 12, 12)
        }

        val tvWps = TextView(this).apply { text = "0 w/s"; setTextColor(0xFFFFFFFF.toInt()) }
        val tvSeeds = TextView(this).apply { text = "0 seeds"; setTextColor(0xFFFFFFFF.toInt()) }
        val tvTime = TextView(this).apply { text = "00:00:00"; setTextColor(0xFFFFFFFF.toInt()) }
        val tvMatches = TextView(this).apply { text = "Matches: 0"; setTextColor(0xFFFFFFFF.toInt()) }

        val logLabel = TextView(this).apply {
            text = "LOG"
            setTextColor(0xFFFFAA00.toInt())
            setPadding(0, 16, 0, 4)
        }

        val tvLog = TextView(this).apply {
            text = ""
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0xFF111111.toInt())
            setPadding(8, 8, 8, 8)
            textSize = 11f
        }

        threadsSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                threadsLabel.text = "Threads: ${p + 1}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        cpuSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                val pct = p + 10
                val color = when {
                    pct <= 40 -> 0xFF44BB44.toInt()
                    pct <= 70 -> 0xFFFFAA00.toInt()
                    else -> 0xFFBB3333.toInt()
                }
                cpuLabel.setTextColor(color)
                cpuLabel.text = "Limite CPU: $pct%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnCsv.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, 42)
        }

        btnStart.setOnClickListener {
            val path = csvPath
            if (path == null) {
                Toast.makeText(this, "Selecciona el CSV primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val threads = threadsSlider.progress + 1
            engine.start(path, threads) { wps, seeds, elapsed, matches, log ->
                runOnUiThread {
                    tvWps.text = "$wps w/s"
                    tvSeeds.text = "$seeds seeds"
                    val h = elapsed / 3600
                    val m = (elapsed % 3600) / 60
                    val s = elapsed % 60
                    tvTime.text = String.format("%02d:%02d:%02d", h, m, s)
                    tvMatches.text = "Matches: $matches"
                    if (log.isNotEmpty()) tvLog.text = log
                }
            }
        }

        btnStop.setOnClickListener { engine.stop() }

        statsBox.addView(tvWps)
        statsBox.addView(tvSeeds)
        statsBox.addView(tvTime)
        statsBox.addView(tvMatches)

        btnRow.addView(btnStart)
        btnRow.addView(btnStop)

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(csvLabel)
        layout.addView(btnCsv)
        layout.addView(csvStatus)
        layout.addView(configLabel)
        layout.addView(threadsLabel)
        layout.addView(threadsSlider)
        layout.addView(cpuLabel)
        layout.addView(cpuSlider)
        layout.addView(btnRow)
        layout.addView(statsLabel)
        layout.addView(statsBox)
        layout.addView(logLabel)
        layout.addView(tvLog)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val path = getRealPath(uri)
            if (path != null) {
                csvPath = path
                findViewById<TextView>(android.R.id.content)
                Toast.makeText(this, "CSV: $path", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getRealPath(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf("_data"), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex("_data")
                if (idx >= 0) return it.getString(idx)
            }
        }
        // fallback: copy to cache
        val file = File(cacheDir, "utxos.csv")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        }
        return file.absolutePath
    }
}
