package com.btcseedrecovery

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.*

class HunterService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastFound: Long = 0
    private var currentTemp: Float = 0f
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryLevel: Int = 100

    companion object {
        const val CHANNEL_FG    = "wh_fg"
        const val CHANNEL_MATCH = "wh_match"
        const val NOTIF_FG      = 10
        const val NOTIF_MATCH   = 1
        var instance: HunterService? = null
        var tempCallback: ((Float) -> Unit)? = null
    }

    private val battReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            currentTemp = raw / 10.0f
            tempCallback?.invoke(currentTemp)
            // Nivel de batería
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100 / scale)
                // Auto-pausa si batería < 15%
                if (batteryLevel < 15 && HunterEngine.isRunning()) {
                    HunterEngine.stopHunting()
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_FG, buildFgNotif(
                            "⚠ Scan pausado — batería baja",
                            "Batería al ${batteryLevel}%. Recarga y reinicia."
                        ))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        registerReceiver(battReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startForeground(NOTIF_FG, buildFgNotif("BTC Hunter activo", "Iniciando..."))
        // WakeLock para mantener CPU activo con pantalla apagada
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WalletHunter::ScanWakeLock"
        ).also { it.acquire() }
        handler.post(statsUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statsUpdater)
        unregisterReceiver(battReceiver)
        // Liberar WakeLock
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_FG,
                "Hunter activo", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
            val alarmAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            nm.createNotificationChannel(NotificationChannel(CHANNEL_MATCH,
                "Match encontrado", NotificationManager.IMPORTANCE_HIGH).apply {
                enableLights(true); lightColor = Color.YELLOW
                enableVibration(true)
                vibrationPattern = longArrayOf(0,300,150,300,150,300)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAttr)
            })
        }
    }

    fun sendMatchNotif(count: Long, details: String) {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NOTIF_MATCH,
            Notification.Builder(this, CHANNEL_MATCH)
                .setContentTitle("WALLET ENCONTRADA ($count total)")
                .setContentText(details.lines().firstOrNull()?.take(80) ?: "")
                .setStyle(Notification.BigTextStyle().bigText(details.take(400)))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun buildFgNotif(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_FG)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private val statsUpdater = object : Runnable {
        override fun run() {
            val running = HunterEngine.isRunning()
            val loaded  = HunterEngine.isCsvLoaded()
            val wps = HunterEngine.getWps()
            val count = HunterEngine.getCount()
            val elapsed = HunterEngine.getElapsed()
            val found = HunterEngine.getFound()
            val h = elapsed/3600; val m = (elapsed%3600)/60; val s = elapsed%60
            val wStr = if(wps>=1000) "${"%.1f".format(wps/1000)}K w/s" else "${wps.toInt()} w/s"
            val cStr = if(count>=1_000_000) "${"%.2f".format(count/1e6)}M seeds" else "$count seeds"

            if (running) {
                val title = "BTC Hunter  $wStr | Matches: $found"
                val text = "$cStr | %02d:%02d:%02d | 🌡${"%.0f".format(currentTemp)}°C | 🔋${batteryLevel}%%".format(h,m,s)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_FG, buildFgNotif(title, text))
            } else if (loaded) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_FG, buildFgNotif("BTC Hunter en espera", "CSV cargado, listo para iniciar"))
            }

            // Detectar nuevo match
            if (found > lastFound) {
                lastFound = found
                sendMatchNotif(found, HunterEngine.getMatches())
            }

            handler.postDelayed(this, 2000L)
        }
    }
}
