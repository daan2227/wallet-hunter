package com.hunter.btc

import android.app.*
import android.content.*
import android.graphics.Color
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
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var sbThreads: SeekBar
    private lateinit var sbCpu: SeekBar
    private lateinit var tvThreads: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvMatchList: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvTemp: TextView
    private var csvPath: String = ""
    private var lastFoundCount: Long = 0
    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            tvTemp.text = "Temp: %.1f C".format(temp / 10.0)
            val color = when {
                temp < 350 -> Color.WHITE
                temp < 420 -> Color.parseColor("#FFFF00")
                else -> Color.parseColor("#FF4444")
            }
            tvTemp.setTextColor(color)
        }
    }

    companion object {
        const val REQ_CSV = 1001
        const val NOTIF_CHANNEL = "wallet_hunter_matches"
        const val NOTIF_ID = 1
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
        checkStoragePermission()
        createNotificationChannel()
        registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startUpdater()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updater)
        unregisterReceiver(battReceiver)
        if (HunterEngine.isRunning()) HunterEngine.stopHunting()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                NOTIF_CHANNEL, "Matches encontrados",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifica cuando se encuentra una wallet con fondos"
                enableLights(true)
                lightColor = Color.YELLOW
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(soundUri, audioAttr)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun sendMatchNotification(count: Long, details: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
                return
            }
        }
        val intent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("WALLET ENCONTRADA! ($count total)")
            .setContentText(details.take(100))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Permiso necesario")
                    .setMessage("Para leer el CSV de 3GB necesitas dar permiso de 'Acceso a todos los archivos'. Toca OK para abrir Ajustes.")
                    .setPositiveButton("OK") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }.show()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }
    }

    private fun buildUI() {
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }

        main.addView(TextView(this).apply { text="Bitcoin Wallet Hunter"; textSize=22f; setTextColor(ORANGE); gravity=Gravity.CENTER })
        main.addView(TextView(this).apply { text="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh"; textSize=11f; setTextColor(DIM); gravity=Gravity.CENTER; setPadding(0,0,0,16) })

        // Panel sistema
        val sysPanel = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setBackgroundColor(PANEL); setPadding(12,8,12,8) }
        tvRam  = TextView(this).apply { text="RAM: --"; setTextColor(Color.WHITE); textSize=12f; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f) }
        tvTemp = TextView(this).apply { text="Temp: --"; setTextColor(Color.WHITE); textSize=12f }
        sysPanel.addView(tvRam); sysPanel.addView(tvTemp)
        main.addView(sysPanel)

        main.addView(sectionLabel("ARCHIVO CSV"))
        val btnCsv = Button(this).apply { text="SELECCIONAR CSV..."; setBackgroundColor(ORANGE); setTextColor(Color.BLACK); setOnClickListener { pickCsv() } }
        main.addView(btnCsv)
        tvStatus = TextView(this).apply { text="Ningun archivo cargado"; setTextColor(DIM); textSize=12f; setPadding(0,8,0,16) }
        main.addView(tvStatus)

        main.addView(sectionLabel("CONFIGURACION"))
        tvThreads = TextView(this).apply { setTextColor(Color.WHITE); textSize=13f }
        main.addView(tvThreads)
        sbThreads = SeekBar(this).apply { max=7; progress=3
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(sb:SeekBar,p:Int,u:Boolean){updateLabels()}
                override fun onStartTrackingTouch(sb:SeekBar){}
                override fun onStopTrackingTouch(sb:SeekBar){}})
        }
        main.addView(sbThreads)
        tvCpu = TextView(this).apply { setTextColor(Color.WHITE); textSize=13f; setPadding(0,12,0,0) }
        main.addView(tvCpu)
        sbCpu = SeekBar(this).apply { max=90; progress=70
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(sb:SeekBar,p:Int,u:Boolean){updateLabels(); if(HunterEngine.isRunning()) HunterEngine.setCpuLimit(p+10)}
                override fun onStartTrackingTouch(sb:SeekBar){}
                override fun onStopTrackingTouch(sb:SeekBar){}})
        }
        main.addView(sbCpu)
        updateLabels()

        val btnRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,20,0,20) }
        btnStart = Button(this).apply { text="INICIAR"; setBackgroundColor(GREEN); setTextColor(Color.WHITE); layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f); setOnClickListener{doStart()} }
        btnStop  = Button(this).apply { text="DETENER"; setBackgroundColor(RED);   setTextColor(Color.WHITE); layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f); setOnClickListener{doStop()} }
        btnRow.addView(btnStart); btnRow.addView(btnStop); main.addView(btnRow)

        main.addView(sectionLabel("ESTADISTICAS"))
        val sp = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(PANEL); setPadding(16,16,16,16) }
        tvWps=TextView(this).apply{text="0 w/s";setTextColor(Color.WHITE);textSize=14f}
        tvCount=TextView(this).apply{text="0 seeds";setTextColor(Color.WHITE);textSize=14f}
        tvTime=TextView(this).apply{text="00:00:00";setTextColor(Color.WHITE);textSize=14f}
        tvMatches=TextView(this).apply{text="Matches: 0";setTextColor(Color.WHITE);textSize=14f}
        sp.addView(tvWps);sp.addView(tvCount);sp.addView(tvTime);sp.addView(tvMatches);main.addView(sp)

        main.addView(sectionLabel("COINCIDENCIAS").apply{setPadding(0,20,0,0)})
        tvMatchList=TextView(this).apply{text="Ninguna aun...";setTextColor(YELLOW);textSize=12f;setBackgroundColor(PANEL);setPadding(12,12,12,12)}
        main.addView(tvMatchList)

        main.addView(sectionLabel("LOG").apply{setPadding(0,20,0,0)})
        tvLog=TextView(this).apply{text="";setTextColor(DIM);textSize=11f;setBackgroundColor(PANEL);setPadding(12,12,12,12)}
        main.addView(tvLog)

        root.addView(main); setContentView(root)
    }

    private fun sectionLabel(t:String)=TextView(this).apply{text=t;textSize=13f;setTextColor(ORANGE);setPadding(0,12,0,6)}

    private fun updateLabels() {
        tvThreads.text="Threads: ${sbThreads.progress+1}"
        val cpu=sbCpu.progress+10
        tvCpu.setTextColor(if(cpu<=40)GREEN else if(cpu<=70)YELLOW else RED)
        tvCpu.text="Limite CPU: $cpu% (${if(cpu<=40)"silencioso" else if(cpu<=70)"balanceado" else "rendimiento"})"
    }

    private fun updateRam() {
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val usedMB = (mi.totalMem - mi.availMem) / 1048576
        val totalMB = mi.totalMem / 1048576
        val pct = usedMB * 100 / totalMB
        tvRam.text = "RAM: ${usedMB}MB / ${totalMB}MB (${pct}%)"
        tvRam.setTextColor(if(pct<70) Color.WHITE else if(pct<85) YELLOW else RED)
    }

    private fun pickCsv() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE); type="*/*"
        }, REQ_CSV)
    }

    override fun onActivityResult(requestCode:Int, resultCode:Int, data:Intent?) {
        if(requestCode==REQ_CSV && resultCode==RESULT_OK) {
            data?.data?.let { uri ->
                val path = getRealPath(uri)
                if (path != null) {
                    csvPath = path
                    tvStatus.text = "Cargando: ${File(csvPath).name}"
                    tvStatus.setTextColor(YELLOW)
                    HunterEngine.loadCsv(csvPath)
                } else {
                    tvStatus.text = "Copiando CSV... (puede tardar varios minutos)"
                    tvStatus.setTextColor(YELLOW)
                    Thread {
                        try {
                            val dest = File(getExternalFilesDir(null), "utxos.csv")
                            contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(dest).use { out -> input.copyTo(out, 65536) }
                            }
                            runOnUiThread {
                                csvPath = dest.absolutePath
                                tvStatus.text = "Cargando: utxos.csv"
                                HunterEngine.loadCsv(csvPath)
                            }
                        } catch(e: Exception) {
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
        val path = uri.path ?: return null
        if (path.startsWith("/storage") || path.startsWith("/sdcard")) {
            val f = File(path); if (f.exists()) return f.absolutePath
        }
        return null
    }

    private fun doStart() {
        if (!HunterEngine.isCsvLoaded()) { Toast.makeText(this,"Carga el CSV primero",Toast.LENGTH_SHORT).show(); return }
        lastFoundCount = 0
        HunterEngine.startHunting(sbThreads.progress+1, sbCpu.progress+10)
    }
    private fun doStop() { HunterEngine.stopHunting() }

    private var logBuf = StringBuilder()
    private val updater = object:Runnable { override fun run() {
        var msg: String
        while(true){msg=HunterEngine.popLog();if(msg.isEmpty())break;logBuf.insert(0,msg+"\n");if(logBuf.length>4000)logBuf.setLength(4000)}
        tvLog.text=logBuf.toString()
        val loading=HunterEngine.isLoading(); val loaded=HunterEngine.isCsvLoaded(); val running=HunterEngine.isRunning()
        if(loading||loaded){tvStatus.text=HunterEngine.getLoadStatus();tvStatus.setTextColor(if(loading)YELLOW else GREEN)}
        btnStart.isEnabled=loaded&&!running&&!loading; btnStop.isEnabled=running
        val wps=HunterEngine.getWps()
        tvWps.text=if(wps>=1e6)"%.2f M w/s".format(wps/1e6) else if(wps>=1000)"%.1f K w/s".format(wps/1000) else "%.0f w/s".format(wps)
        tvWps.setTextColor(if(running)ORANGE else DIM)
        val count=HunterEngine.getCount(); tvCount.text=if(count>=1_000_000)"%.2f M seeds".format(count/1e6) else "$count seeds"
        val e=HunterEngine.getElapsed(); tvTime.text="%02d:%02d:%02d".format(e/3600,(e%3600)/60,e%60)
        val found=HunterEngine.getFound(); tvMatches.text="Matches: $found"; tvMatches.setTextColor(if(found>0)YELLOW else Color.WHITE)
        val m=HunterEngine.getMatches()
        if(m.isNotEmpty()){tvMatchList.text=m;tvMatchList.setTextColor(YELLOW)}
        // Notificacion cuando hay nuevo match
        if(found > lastFoundCount) {
            lastFoundCount = found
            sendMatchNotification(found, m)
        }
        updateRam()
        handler.postDelayed(this,1000L)
    }}
    private fun startUpdater(){handler.post(updater)}
}
