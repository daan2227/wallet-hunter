package com.hunter.btc

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.io.*

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private lateinit var tvWps: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvMatches: TextView
    private lateinit var tvMatchList: TextView
    private lateinit var tvAddrFeed: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var sbThreads: SeekBar
    private lateinit var sbCpu: SeekBar
    private lateinit var tvThreads: TextView
    private lateinit var tvCpu: TextView
    private var csvPath: String = ""
    private var lastFoundCount: Long = 0
    private var currentTemp: Float = 0f
    private val recentAddrs = mutableListOf<String>()
    private var addrTick = 0

    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            currentTemp = raw / 10.0f
            tvTemp.text = "Temp: ${"%.1f".format(currentTemp)}C"
            tvTemp.setTextColor(when {
                currentTemp < 35f -> Color.WHITE
                currentTemp < 42f -> YELLOW
                else -> RED
            })
        }
    }

    companion object {
        const val REQ_CSV = 1001
        const val CHANNEL_MATCH = "wh_match"
        const val CHANNEL_STATUS = "wh_status"
        const val NOTIF_MATCH = 1
        const val NOTIF_STATUS = 2
        val ORANGE = Color.parseColor("#FF8C00")
        val GREEN  = Color.parseColor("#33CC33")
        val RED    = Color.parseColor("#CC2222")
        val YELLOW = Color.parseColor("#FFFF00")
        val DIM    = Color.parseColor("#AAAAAA")
        val BG     = Color.parseColor("#1A1A1E")
        val PANEL  = Color.parseColor("#242428")
        val DARK   = Color.parseColor("#111114")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        checkStoragePermission()
        createChannels()
        registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startUpdater()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updater)
        unregisterReceiver(battReceiver)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_STATUS)
        if (HunterEngine.isRunning()) HunterEngine.stopHunting()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val alarmAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            nm.createNotificationChannel(NotificationChannel(CHANNEL_MATCH,
                "Match encontrado", NotificationManager.IMPORTANCE_HIGH).apply {
                enableLights(true); lightColor = Color.YELLOW
                enableVibration(true)
                vibrationPattern = longArrayOf(0,300,150,300,150,300)
                setSound(alarmSound, alarmAttr)
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS,
                "Estado hunter", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
        }
    }

    private fun hasPerm() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun sendMatchNotification(count: Long, details: String) {
        if (!hasPerm()) { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200); return }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NOTIF_MATCH,
            Notification.Builder(this, CHANNEL_MATCH)
                .setContentTitle("WALLET ENCONTRADA ($count total)")
                .setContentText(details.lines().firstOrNull()?.take(80) ?: "")
                .setStyle(Notification.BigTextStyle().bigText(details.take(400)))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun updateStatusNotif(wps: Double, count: Long, elapsed: Long, found: Long, running: Boolean) {
        if (!running) { getSystemService(NotificationManager::class.java).cancel(NOTIF_STATUS); return }
        if (!hasPerm()) return
        val h = elapsed/3600; val m = (elapsed%3600)/60; val s = elapsed%60
        val wStr = if(wps>=1000) "${"%.1f".format(wps/1000)}K w/s" else "${wps.toInt()} w/s"
        val cStr = if(count>=1_000_000) "${"%.2f".format(count/1e6)}M" else "$count"
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val ramPct = (mi.totalMem-mi.availMem)*100/mi.totalMem
        val big = "Velocidad : $wStr\nSeeds     : $cStr\nTiempo    : %02d:%02d:%02d\nMatches   : $found\nRAM       : $ramPct%%\nTemp      : ${"%.1f".format(currentTemp)}C".format(h,m,s)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS,
            Notification.Builder(this, CHANNEL_STATUS)
                .setContentTitle("BTC Hunter  $wStr | Match: $found")
                .setContentText("$cStr seeds | %02d:%02d:%02d | ${currentTemp.toInt()}C".format(h,m,s))
                .setStyle(Notification.BigTextStyle().bigText(big))
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build())
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager())
                AlertDialog.Builder(this).setTitle("Permiso necesario")
                    .setMessage("Para leer el CSV necesitas 'Acceso a todos los archivos'.")
                    .setPositiveButton("OK") { _,_ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }.show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
    }

    private fun buildUI() {
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val main = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,20,20,20) }

        main.addView(TextView(this).apply {
            text="Bitcoin Wallet Hunter"; textSize=20f; setTextColor(ORANGE)
            gravity=Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        })
        main.addView(TextView(this).apply {
            text="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh"
            textSize=10f; setTextColor(DIM); gravity=Gravity.CENTER; setPadding(0,2,0,8)
        })

        // RAM + Temp barra
        val sysRow = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; setBackgroundColor(PANEL); setPadding(12,6,12,6)
        }
        tvRam  = TextView(this).apply { text="RAM: --"; setTextColor(Color.WHITE); textSize=11f
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f) }
        tvTemp = TextView(this).apply { text="Temp: --"; setTextColor(Color.WHITE); textSize=11f }
        sysRow.addView(tvRam); sysRow.addView(tvTemp); main.addView(sysRow)

        // CSV row
        main.addView(sectionLabel("ARCHIVO CSV"))
        val csvRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val btnCsv = Button(this).apply {
            text="CSV"; setBackgroundColor(ORANGE); setTextColor(Color.BLACK)
            textSize=11f; setPadding(24,0,24,0)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 80)
            setOnClickListener { pickCsv() }
        }
        tvStatus = TextView(this).apply {
            text="Sin archivo"; setTextColor(DIM); textSize=11f; setPadding(12,0,0,0)
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
        }
        csvRow.addView(btnCsv); csvRow.addView(tvStatus); main.addView(csvRow)

        // Config
        main.addView(sectionLabel("CONFIGURACION"))
        tvThreads = TextView(this).apply { setTextColor(Color.WHITE); textSize=12f }
        main.addView(tvThreads)
        sbThreads = SeekBar(this).apply { max=7; progress=3
            setOnSeekBarChangeListener(mkListener { updateLabels() }) }
        main.addView(sbThreads)
        tvCpu = TextView(this).apply { setTextColor(Color.WHITE); textSize=12f; setPadding(0,8,0,0) }
        main.addView(tvCpu)
        sbCpu = SeekBar(this).apply { max=90; progress=70
            setOnSeekBarChangeListener(mkListener {
                updateLabels()
                if (HunterEngine.isRunning()) HunterEngine.setCpuLimit(sbCpu.progress+10)
            }) }
        main.addView(sbCpu)
        updateLabels()

        // Boton toggle unico
        btnToggle = Button(this).apply {
            text="INICIAR"; setBackgroundColor(GREEN); setTextColor(Color.WHITE)
            textSize=16f; setTypeface(null, Typeface.BOLD)
            layoutParams=LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120).apply { topMargin=20; bottomMargin=8 }
            setOnClickListener { doToggle() }
        }
        main.addView(btnToggle)

        // Stats
        main.addView(sectionLabel("ESTADISTICAS"))
        val sp = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(PANEL); setPadding(12,10,12,10) }
        tvWps     = mkStat("0 w/s", true)
        tvCount   = mkStat("0 seeds", false)
        tvTime    = mkStat("00:00:00", false)
        tvMatches = mkStat("Matches: 0", false)
        sp.addView(tvWps); sp.addView(tvCount); sp.addView(tvTime); sp.addView(tvMatches)
        main.addView(sp)

        // Feed en vivo
        main.addView(sectionLabel("CONSULTANDO EN VIVO"))
        tvAddrFeed = TextView(this).apply {
            text="Esperando inicio..."
            setTextColor(Color.parseColor("#44BB44"))
            textSize=10f; typeface=Typeface.MONOSPACE
            setBackgroundColor(DARK); setPadding(10,8,10,8)
            lineSpacingMultiplier=1.3f
        }
        main.addView(tvAddrFeed)

        // Coincidencias
        main.addView(sectionLabel("COINCIDENCIAS").apply { setPadding(0,12,0,0) })
        tvMatchList = TextView(this).apply {
            text="Ninguna aun..."; setTextColor(YELLOW); textSize=11f
            setBackgroundColor(PANEL); setPadding(12,10,12,10)
        }
        main.addView(tvMatchList)

        // Log
        main.addView(sectionLabel("LOG").apply { setPadding(0,12,0,0) })
        tvLog = TextView(this).apply {
            text=""; setTextColor(DIM); textSize=10f
            setBackgroundColor(PANEL); setPadding(10,8,10,8)
        }
        main.addView(tvLog)

        root.addView(main); setContentView(root)
    }

    private fun mkStat(init: String, bold: Boolean) = TextView(this).apply {
        text=init; setTextColor(if(bold) DIM else Color.WHITE)
        textSize=if(bold) 15f else 13f
        if(bold) setTypeface(null, Typeface.BOLD)
    }

    private fun mkListener(block: () -> Unit) = object: SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb:SeekBar, p:Int, u:Boolean) { block() }
        override fun onStartTrackingTouch(sb:SeekBar) {}
        override fun onStopTrackingTouch(sb:SeekBar) {}
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text=t; textSize=12f; setTextColor(ORANGE)
        setPadding(0,12,0,4); setTypeface(null, Typeface.BOLD)
    }

    private fun updateLabels() {
        tvThreads.text = "Threads: ${sbThreads.progress+1}"
        val cpu = sbCpu.progress+10
        tvCpu.setTextColor(if(cpu<=40) GREEN else if(cpu<=70) YELLOW else RED)
        tvCpu.text = "Limite CPU: $cpu% (${if(cpu<=40)"silencioso" else if(cpu<=70)"balanceado" else "rendimiento"})"
    }

    private fun updateRam() {
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val used = (mi.totalMem-mi.availMem)/1048576
        val total = mi.totalMem/1048576
        val pct = used*100/total
        tvRam.text = "RAM: ${used}MB/${total}MB (${pct}%)"
        tvRam.setTextColor(if(pct<70) Color.WHITE else if(pct<85) YELLOW else RED)
    }

    private fun pickCsv() = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="*/*"
        }, REQ_CSV)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode==REQ_CSV && resultCode==RESULT_OK) {
            data?.data?.let { uri ->
                val path = getRealPath(uri)
                if (path != null) {
                    csvPath = path
                    tvStatus.text = "Cargando: ${File(csvPath).name}"
                    tvStatus.setTextColor(YELLOW)
                    HunterEngine.loadCsv(csvPath)
                } else {
                    tvStatus.text = "Copiando CSV..."; tvStatus.setTextColor(YELLOW)
                    Thread {
                        try {
                            val dest = File(getExternalFilesDir(null), "utxos.csv")
                            contentResolver.openInputStream(uri)?.use { i ->
                                FileOutputStream(dest).use { o -> i.copyTo(o, 65536) }
                            }
                            runOnUiThread {
                                csvPath = dest.absolutePath
                                tvStatus.text = "Cargando: utxos.csv"
                                HunterEngine.loadCsv(csvPath)
                            }
                        } catch (e: Exception) {
                            runOnUiThread { tvStatus.text = "Error: ${e.message}" }
                        }
                    }.start()
                }
            }
        }
    }

    private fun getRealPath(uri: Uri): String? {
        uri.lastPathSegment?.let { seg ->
            if (seg.startsWith("primary:")) {
                val f = File("/storage/emulated/0/${seg.removePrefix("primary:")}")
                if (f.exists()) return f.absolutePath
            }
        }
        val p = uri.path ?: return null
        if (p.startsWith("/storage") || p.startsWith("/sdcard")) {
            val f = File(p); if (f.exists()) return f.absolutePath
        }
        return null
    }

    private fun doToggle() {
        if (HunterEngine.isRunning()) {
            HunterEngine.stopHunting()
            btnToggle.text = "INICIAR"; btnToggle.setBackgroundColor(GREEN)
        } else {
            if (!HunterEngine.isCsvLoaded()) {
                Toast.makeText(this, "Carga el CSV primero", Toast.LENGTH_SHORT).show(); return
            }
            lastFoundCount = 0
            HunterEngine.startHunting(sbThreads.progress+1, sbCpu.progress+10)
            btnToggle.text = "DETENER"; btnToggle.setBackgroundColor(RED)
        }
    }

    private val logBuf = StringBuilder()

    private val updater = object : Runnable {
        override fun run() {
            // Drenar log
            var msg: String
            while (true) {
                msg = HunterEngine.popLog()
                if (msg.isEmpty()) break
                logBuf.insert(0, msg+"\n")
                if (logBuf.length > 3000) logBuf.setLength(3000)
            }
            tvLog.text = logBuf.toString()

            val loading = HunterEngine.isLoading()
            val loaded  = HunterEngine.isCsvLoaded()
            val running = HunterEngine.isRunning()

            if (loading || loaded) {
                tvStatus.text = HunterEngine.getLoadStatus()
                tvStatus.setTextColor(if(loading) YELLOW else GREEN)
            }

            if (!running && btnToggle.text == "DETENER") {
                btnToggle.text = "INICIAR"; btnToggle.setBackgroundColor(GREEN)
            }
            btnToggle.isEnabled = loaded && !loading

            val wps = HunterEngine.getWps()
            tvWps.text = if(wps>=1e6) "%.2f M w/s".format(wps/1e6)
                         else if(wps>=1000) "%.1f K w/s".format(wps/1000)
                         else "%.0f w/s".format(wps)
            tvWps.setTextColor(if(running) ORANGE else DIM)

            val count = HunterEngine.getCount()
            tvCount.text = if(count>=1_000_000) "%.2f M seeds".format(count/1e6) else "$count seeds"

            val e = HunterEngine.getElapsed()
            tvTime.text = "%02d:%02d:%02d".format(e/3600,(e%3600)/60,e%60)

            val found = HunterEngine.getFound()
            tvMatches.text = "Matches: $found"
            tvMatches.setTextColor(if(found>0) YELLOW else Color.WHITE)

            val matches = HunterEngine.getMatches()
            if (matches.isNotEmpty()) {
                tvMatchList.text = matches; tvMatchList.setTextColor(YELLOW)
            }
            if (found > lastFoundCount) {
                lastFoundCount = found; sendMatchNotification(found, matches)
            }

            // Feed de addresses en vivo - drenar y mostrar 3 cada segundo
            if (running) {
                var addr: String
                while (true) {
                    addr = HunterEngine.popRecentAddr()
                    if (addr.isEmpty()) break
                    recentAddrs.add(addr)
                    if (recentAddrs.size > 6) recentAddrs.removeAt(0)
                }
                addrTick++
                if (addrTick >= 3) {
                    addrTick = 0
                    if (recentAddrs.isNotEmpty()) {
                        tvAddrFeed.text = recentAddrs.takeLast(3).joinToString("\n")
                    }
                }
            } else if (!loaded) {
                tvAddrFeed.text = "Esperando inicio..."
            }

            updateRam()
            updateStatusNotif(wps, count, e, found, running)
            handler.postDelayed(this, 333L)
        }
    }

    private fun startUpdater() { handler.post(updater) }
}
