package com.hunter.btc

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
        // tempCallback se invocaba en cada lectura y NADIE se suscribia nunca:
        // el otro extremo del cable termico que no estaba conectado. La
        // temperatura va ahora a Termico, que si hace algo con ella.
    }

    private val battReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            currentTemp = raw / 10.0f
            // Nivel de batería
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100 / scale)
                // Auto-pausa si batería < 15%
                //
                // Cubría sólo la fuerza bruta. Kangaroo corre en sus propios
                // hilos y no pasa por isRunning(), así que seguía quemando la
                // batería hasta apagar el móvil — y es justo el motor que se
                // deja días encendido, sobre todo en un móvil que trabaja para
                // un cluster y que nadie está mirando.
                //
                // Pararlo no pierde nada: kangarooStop() guarda la tabla de
                // puntos distinguidos, que es donde está todo el trabajo.
                if (batteryLevel < 15) {
                    val bruta = HunterEngine.isRunning()
                    val kang  = try { HunterEngine.kangarooRunning() } catch (e: Throwable) { false }
                    if (bruta || kang) {
                        if (bruta) HunterEngine.stopHunting()
                        if (kang)  try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
                        // Y decir que la parada es a propósito. Sin esto la
                        // pausa no servía de nada: el watchdog de la pantalla
                        // principal ve el motor caído, lee estas dos banderas,
                        // y lo relanza a los dos segundos. Con la pantalla
                        // abierta el móvil seguía buscando hasta apagarse, que
                        // es justo lo que la pausa tenía que evitar — y en un
                        // móvil del cluster, que nadie está mirando, aún peor.
                        //
                        // Se apagan las dos aunque sólo uno estuviera en
                        // marcha: son baratas de escribir y dejar la otra a
                        // medias es como se llega a fallos así.
                        try {
                            getSharedPreferences("hunter",
                                android.content.Context.MODE_PRIVATE).edit()
                                .putBoolean("kangaroo_corriendo", false)
                                .putBoolean("scan_was_running", false).apply()
                        } catch (e: Throwable) {}
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_FG, buildFgNotif(
                                "Paused: battery low",
                                "Battery at ${batteryLevel}%. " +
                                if (kang) "The Kangaroo work is saved."
                                else "Charge it and start again."
                            ))
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // El motor puede estar corriendo sin ninguna pantalla abierta: el baúl
        // tiene que poder abrirse desde aquí.
        HunterEngine.conectarBaul(this)
        createChannels()
        registerReceiver(battReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startForeground(NOTIF_FG, buildFgNotif("BTC Hunter running", "Starting..."))
        // WakeLock para mantener CPU activo con pantalla apagada
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WalletHunter::ScanWakeLock"
        ).also { it.acquire() }
        handler.post(statsUpdater)
        recuperarTrasMorir()
    }

    /**
     * Volver a lo que estábamos haciendo cuando Android nos mató.
     *
     * El servicio es START_STICKY, así que Android lo vuelve a levantar solo.
     * Pero volvía con la notificación puesta y nada más: ni buscando ni
     * conectado al maestro. Por fuera se veía igual que si todo fuera bien,
     * mientras el móvil no aportaba nada y en la lista del maestro desaparecía a
     * los diez minutos sin explicación.
     *
     * El watchdog que relanza Kangaroo vive en la pantalla principal, así que
     * sólo actúa si esa pantalla está viva. Si muere el proceso entero no queda
     * nadie que lo llame — y eso es exactamente lo que pasa en el móvil que se
     * deja días trabajando para un cluster, que es donde más duele.
     *
     * Las dos cosas se recuperan de preferencias, que es donde ya quedan
     * escritas arranque por donde arranque la búsqueda.
     */
    private fun recuperarTrasMorir() {
        try {
            val p = getSharedPreferences("hunter", android.content.Context.MODE_PRIVATE)
            // 1) La búsqueda
            val corriendo = p.getBoolean("kangaroo_corriendo", false)
            val viva = try { HunterEngine.kangarooRunning() } catch (e: Throwable) { false }
            if (corriendo && !viva) {
                val pub = p.getString("kangaroo_pub", "") ?: ""
                val ini = p.getString("kangaroo_ini", "") ?: ""
                val fin = p.getString("kangaroo_fin", "") ?: ""
                if (pub.length == 66 && ini.isNotEmpty() && fin.isNotEmpty()) {
                    val ok = NetworkManager.arrancarMotorKangaroo(this, pub, ini, fin)
                    android.util.Log.i("HunterService",
                        "Kangaroo restarted after the app died: $ok")
                }
            }
            // 2) El sitio en el cluster. Va después: si sólo se recupera la
            //    búsqueda, el móvil trabaja pero para nadie.
            NetworkManager.reanudarSesionDeWorker(this)
        } catch (e: Throwable) {
            android.util.Log.e("HunterService", "recover: ${e.message}", e)
        }
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
                "Hunter running", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
            val alarmAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            nm.createNotificationChannel(NotificationChannel(CHANNEL_MATCH,
                "Match found", NotificationManager.IMPORTANCE_HIGH).apply {
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

        /* ESTO ENSEÑABA LA CLAVE PRIVADA. `details` es la cadena del motor,
         * "MATCH|ADDR:…|BTC:…|WIF:…|HEX:<clave privada>", y iba tal cual al
         * cuerpo de la notificación. Una notificación sale en la pantalla de
         * bloqueo, se copia al reloj y al historial de notificaciones, y la lee
         * cualquier app con acceso a notificaciones. Una clave hallada es
         * dinero.
         *
         * De la cadena sólo se saca la dirección, que es pública. La clave
         * está en el baúl cifrado, que es donde tiene que estar. */
        val dirs = Regex("ADDR:([^|\\s]+)").findAll(details).map { it.groupValues[1] }.take(5).toList()
        val corto = dirs.firstOrNull()?.let { "Address: $it" } ?: "Open the app to see it"
        val largo = (if (dirs.isEmpty()) "" else dirs.joinToString("\n") { "Address: $it" } + "\n\n") +
                    "The key is saved in the finds vault."

        // En la pantalla de bloqueo, sin dirección: decirle a quien coja el
        // móvil que aquí hay una clave hallada es ya decir demasiado.
        val publica = Notification.Builder(this, CHANNEL_MATCH)
            .setContentTitle("Wallet Hunter")
            .setContentText("Match found — unlock to see it")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIF_MATCH,
            Notification.Builder(this, CHANNEL_MATCH)
                .setContentTitle("WALLET FOUND ($count total)")
                .setContentText(corto)
                .setStyle(Notification.BigTextStyle().bigText(largo))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publica)
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

    // Cuándo se le mandó la velocidad al master por última vez.
    private var ultimoProgresoMs = 0L
    /** Última velocidad calculada, en operaciones por segundo. */
    private var ultimaVel = 0.0
    // Para sacar los saltos por segundo de Kangaroo, que sólo da el total.
    private var kangUltOps = 0L
    private var kangUltMs  = System.currentTimeMillis()

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

            // Kangaroo va por su cuenta y no aparece en isRunning(): sin esto
            // la notificación se quedaba con el texto de cuando se creó el
            // servicio, así que un móvil llevaba días buscando y el aviso decía
            // otra cosa. Va primero porque cuando Kangaroo corre es el único
            // motor en marcha.
            val kangOps = try {
                if (HunterEngine.kangarooRunning()) HunterEngine.kangarooOps() else -1L
            } catch (e: Throwable) { -1L }
            if (kangOps >= 0) {
                val dt = (System.currentTimeMillis() - kangUltMs).coerceAtLeast(1L)
                val porSeg = if (kangUltOps > 0) (kangOps - kangUltOps) * 1000.0 / dt else 0.0
                kangUltOps = kangOps; kangUltMs = System.currentTimeMillis()
                ultimaVel = porSeg
                val pts = try { HunterEngine.kangarooPoints() } catch (e: Throwable) { 0L }
                // Cero hilos con la tabla viva es el maestro de un cluster
                // recogiendo los puntos de los trabajadores sin buscar él. Decir
                // "Kangaroo · 0 saltos/s" ahí parece una búsqueda averiada, que
                // es justo lo contrario de lo que pasa.
                val hilos = try { HunterEngine.kangarooHilos() } catch (e: Throwable) { 1 }
                val vStr = when {
                    hilos == 0    -> "collecting from the cluster"
                    porSeg >= 1e6 -> "${"%.2f".format(porSeg/1e6)}M jumps/s"
                    else          -> "${"%.0f".format(porSeg)} jumps/s"
                }
                if (hilos == 0) ultimaVel = 0.0
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_FG, buildFgNotif(
                        "Kangaroo · $vStr",
                        "$pts points · ${"%.0f".format(currentTemp)}°C · ${batteryLevel}%"))
            } else if (running) {
                ultimaVel = wps
                val title = "BTC Hunter · $wStr · $found matches"
                val text = "$cStr · %02d:%02d:%02d · ${"%.0f".format(currentTemp)}°C · ${batteryLevel}%%".format(h,m,s)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_FG, buildFgNotif(title, text))
            } else if (loaded) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_FG, buildFgNotif("BTC Hunter idle", "CSV loaded, ready to start"))
            }

            // Detectar nuevo match.
            //
            // El motor pone su contador a CERO en cada arranque, asi que `found`
            // BAJA al empezar otra busqueda. lastFound era una marca de maximo
            // historico que no se reiniciaba nunca, y el efecto es que solo
            // avisaba el PRIMER hallazgo de la vida de la app: en el segundo
            // puzzle, found valia 1 y lastFound tambien, asi que "1 > 1" era
            // falso y no sonaba nada. Con treinta puzzles seguidos, veintinueve
            // en silencio.
            if (found < lastFound) lastFound = found
            if (found > lastFound) {
                lastFound = found
                // Lo que el motor no pudiera entregar al baúl al encontrarlo.
                try { MatchVault.recoger(this@HunterService) } catch (e: Throwable) {}
                val detalles = HunterEngine.getMatches()
                sendMatchNotif(found, detalles)
                // Si este móvil trabaja para un cluster, avisar al master.
                //
                // reportMatch existía desde el principio y NO LA LLAMABA NADIE:
                // un worker podía encontrar algo y el master no se enteraba
                // jamás. Va aquí y no en la pantalla porque un worker suele
                // estar en segundo plano, que es justo cuando la Activity no
                // está viva para detectarlo.
                //
                // Sólo viaja la dirección. La clave se queda en este aparato:
                // es la regla de reportMatch desde que se quitó el envío del
                // WIF en claro, y no se toca.
                if (NetworkManager.isWorker && NetworkManager.isRunning.get()) {
                    val dir = Regex("[13][a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[a-z0-9]{8,71}")
                        .find(detalles)?.value
                    if (!dir.isNullOrEmpty())
                        NetworkManager.reportMatch(NetworkManager.masterIp, dir)
                }
            }

            // Velocidad al master, para su lista de trabajadores.
            //
            // reportProgress tampoco la llamaba nadie, así que la columna de
            // velocidad marcaba siempre 0 y no había forma de ver si un worker
            // se había quedado parado. Cada 30 s basta.
            if (NetworkManager.isWorker && NetworkManager.isRunning.get() &&
                System.currentTimeMillis() - ultimoProgresoMs > 30_000L) {
                ultimoProgresoMs = System.currentTimeMillis()
                // ESTO MANDABA EL TOTAL ACUMULADO, NO LA VELOCIDAD.
                // kangarooOps() y getCount() son contadores que solo suben, asi
                // que el master pintaba "13800000K/s" en su lista de
                // trabajadores. Se manda la tasa, que es lo que dice el rotulo.
                NetworkManager.reportProgress(NetworkManager.masterIp, ultimaVel.toLong())
            }

            // ── GOBERNADOR TERMICO ────────────────────────────────────
            //
            // Aqui y no en la pantalla: un gobernador que solo funcione con
            // MainActivity delante deja de funcionar justo cuando hace falta
            // —pantalla apagada, movil en el bolsillo, movil trabajando para
            // un cluster—. Esto corre mientras corra el servicio.
            if (Termico.evaluar(currentTemp)) avisarDeCalor()

            handler.postDelayed(this, 2000L)
        }
    }

    /**
     * El escalon termico ha cambiado: decirlo y, si toca, parar.
     *
     * Parar de verdad se hace igual que la pausa por bateria baja, incluido
     * apagar las dos banderas de "estaba corriendo". Sin eso la pausa no sirve
     * de nada: el watchdog de la pantalla principal ve el motor caido, lee las
     * banderas y lo relanza a los dos segundos — y el movil se vuelve a
     * calentar, ahora ademas sin que nadie entienda por que.
     */
    private fun avisarDeCalor() {
        val bruta = HunterEngine.isRunning()
        val kang  = try { HunterEngine.kangarooRunning() } catch (e: Throwable) { false }
        // Sin nada buscando no hay nada que frenar ni nada que contar. El
        // escalon sigue subiendo y bajando por dentro —tiene que hacerlo, o no
        // podria enfriarse—, pero callado: el movil puede estar caliente por
        // otra app, y avisar de que "se para por calor" lo que ya estaba parado
        // solo sirve para pisar la notificacion de reposo con un susto.
        if (!bruta && !kang) return
        if (Termico.parado) {
            if (bruta) HunterEngine.stopHunting()
            if (kang)  try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
            try {
                getSharedPreferences("hunter", android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean("kangaroo_corriendo", false)
                    .putBoolean("scan_was_running", false).apply()
            } catch (e: Throwable) {}
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_FG, buildFgNotif(
                    "Paused: too hot",
                    "${"%.0f".format(Termico.tempC)} \u00b0C. " +
                    if (kang) "The Kangaroo work is saved."
                    else "It will not restart on its own: let it cool down first."))
        } else if (Termico.limitando) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_FG, buildFgNotif(
                    "Slowing down: ${"%.0f".format(Termico.tempC)} \u00b0C",
                    "CPU cut to ${Termico.efectivo()} % so the phone cools off."))
        }
    }
}
