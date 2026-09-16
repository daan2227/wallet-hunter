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

    /**
     * Los ajustes de la app.
     *
     * Aquí se leía de "hunt_prefs", **un fichero que no escribe nadie**:
     * MainActivity guarda todo en "hunter". O sea que los hilos, la potencia y
     * el rango que se leían aquí eran siempre los valores por defecto, dijeras
     * lo que dijeras en la pantalla de puzzle. Afectaba también al worker de
     * fuerza bruta, que iba siempre a 4 hilos y 80 %.
     */
    private fun ajustes(ctx: android.content.Context = this) =
        ctx.getSharedPreferences("hunter", android.content.Context.MODE_PRIVATE)

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
        val prefs = ajustes()
        val rangeStart = prefs.getString("current_range_start", "400000000000000000") ?: "400000000000000000"
        val rangeEnd   = prefs.getString("current_range_end",   "7fffffffffffffffff") ?: "7fffffffffffffffff"
        val puzzleNum  = prefs.getInt("current_puzzle_num", 71)
        // Si el puzzle elegido tiene clave pública publicada, se reparte
        // Kangaroo en vez de bloques. Y entonces NO se parte el rango: en
        // Kangaroo partirlo empeora la búsqueda, lo que se junta es la tabla
        // de puntos distinguidos.
        val pub = prefs.getString("kangaroo_pub", "") ?: ""
        val kIni = prefs.getString("kangaroo_ini", "") ?: ""
        val kFin = prefs.getString("kangaroo_fin", "") ?: ""
        val conKangaroo = pub.length == 66 && kIni == rangeStart && kFin == rangeEnd
        if (conKangaroo) {
            if (!HunterEngine.kangarooRunning())
                arrancarKangarooDeRed(this, pub, kIni, kFin, puzzleNum)
            NetworkManager.startMasterKangaroo(this, puzzleNum, pub, kIni, kFin)
            NetworkManager.onClave = { dispositivo, claveHex ->
                runOnUiThread { avisarClaveEncontrada(dispositivo, claveHex) }
            }
        } else {
            NetworkManager.startMaster(this, puzzleNum, rangeStart, rangeEnd)
        }
        btnMaster?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        val ip = NetworkManager.getLocalIp(this)
        val code = NetworkManager.authToken
        tvLog?.text = "✓ Master iniciado\nIP: $ip\nCódigo: $code\n" +
                      "Puzzle #$puzzleNum\nRango: ${rangeStart.take(12)}..."
        val aviso = if (conKangaroo)
            "\n\nReparto de Kangaroo: todos los aparatos al mismo rango, " +
            "porque partirlo empeoraría la búsqueda.\n\n" +
            "AVISO: lo que viaja entre los móviles permite reconstruir la " +
            "clave privada. Úsalo sólo en tu propia red."
        else ""
        // El código hay que teclearlo en cada worker; sin él no se aceptan.
        AlertDialog.Builder(this)
            .setTitle("Master activo")
            .setMessage("IP: $ip\n\nCódigo de acceso:\n\n        $code\n\n" +
                        "Introduce este código en cada worker. Sin él, ningún " +
                        "dispositivo de la red puede conectarse." + aviso)
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
                    val prefs = ajustes()
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
        // Encargo de Kangaroo. Aquí NO hay bloque: el rango es el entero y lo
        // mismo para todos, porque en Kangaroo repartir el rango empeora la
        // búsqueda. Lo que se reparte es la tabla de puntos, y de eso se ocupa
        // el bucle de NetworkManager.
        NetworkManager.onKangaroo = { pub, ini, fin, pz ->
            runOnUiThread {
                val ok = arrancarKangarooDeRed(this, pub, ini, fin, pz)
                tvLog?.text = if (ok)
                    "${tvLog?.text}\n▶ Kangaroo puzzle #$pz\n" +
                    "  rango completo (en Kangaroo partirlo empeora la búsqueda)\n" +
                    "  los puntos se mandan al master cada 20 s"
                else "${tvLog?.text}\nNo se pudo arrancar Kangaroo"
            }
        }
        // El master avisa cuando el puzzle se queda sin fondos: alguien lo ha
        // resuelto mientras buscábamos. El bucle ya ha parado la búsqueda.
        NetworkManager.onPuzzleAgotado = {
            runOnUiThread {
                tvLog?.text = "${tvLog?.text}\n\nBúsqueda detenida: el puzzle ya no " +
                              "tiene fondos.\nAlguien lo ha resuelto. El trabajo queda guardado."
            }
        }
        NetworkManager.startWorker(ip, code)
        btnWorker?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        tvLog?.text = "Conectando a master $ip..."
    }

    /**
     * Arranca Kangaroo con el encargo que manda el master.
     *
     * Los hilos y la potencia salen de las mismas preferencias que usa la
     * pantalla de puzzle, para que mover los mandos ahí valga también aquí.
     *
     * El estado se guarda en un fichero por clave pública, igual que en local:
     * si el worker se reinicia, sigue desde donde lo dejó en vez de empezar de
     * cero.
     */
    private fun arrancarKangarooDeRed(ctx: android.content.Context,
                                      pub: String, ini: String, fin: String, pz: Int): Boolean {
        if (pub.length != 66 || ini.isEmpty() || fin.isEmpty()) return false
        // Si ya hay fuerza bruta en marcha, se para: los dos motores compiten
        // por los mismos núcleos y juntos van peor que cualquiera por separado.
        try { if (HunterEngine.isRunning()) HunterEngine.stopHunting() } catch (e: Throwable) {}

        val prefs = ajustes(ctx)
        val nucleos = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val hilos = (prefs.getInt("puzzle_threads", 3) + 1).coerceIn(1, nucleos)
        val cpu   = (prefs.getInt("puzzle_cpu", 70) + 10).coerceIn(10, 100)
        val porHilo = try { HunterEngine.getBatchSize() } catch (e: Throwable) { 512 }
            .coerceIn(256, 4096)
        val ruta = java.io.File(ctx.filesDir, "kangaroo_${pub.take(16)}.dat").absolutePath

        val ok = try {
            HunterEngine.kangarooStart(pub, ini, fin, hilos, porHilo, ruta)
        } catch (e: Throwable) {
            android.util.Log.e("NetworkActivity", "kangarooStart: ${e.message}", e); false
        }
        if (!ok) return false
        try { HunterEngine.kangarooSetCpu(cpu) } catch (e: Throwable) {}
        val svc = android.content.Intent(ctx, com.hunter.btc.HunterService::class.java)
        try { ctx.startForegroundService(svc) } catch (e: Exception) { ctx.startService(svc) }
        return true
    }

    /** Un worker ha encontrado la clave y lo ha avisado. */
    private fun avisarClaveEncontrada(dispositivo: String, claveHex: String) {
        // Guardar antes de tocar la pantalla: si la app muere aquí, la clave no
        // puede perderse.
        try {
            MatchVault.add(this, MatchVault.Entry(
                ts = System.currentTimeMillis(), source = "kangaroo",
                addr = "", wif = "", privHex = claveHex, btc = 0.0,
                extra = "PUZZLE kangaroo (red, desde $dispositivo)", checkedTs = 0L))
        } catch (e: Exception) {
            android.util.Log.e("NetworkActivity", "no se pudo guardar: ${e.message}", e)
        }
        try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
        tvLog?.text = "${tvLog?.text}\n\nCLAVE ENCONTRADA en $dispositivo\n$claveHex\n" +
                      "Guardada en el baúl de hallazgos."
        AlertDialog.Builder(this)
            .setTitle("Clave encontrada")
            .setMessage("La ha encontrado $dispositivo:\n\n$claveHex\n\n" +
                        "Está guardada en el baúl de hallazgos.")
            .setPositiveButton("OK", null)
            .show()
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
        NetworkManager.onClave = null
        NetworkManager.onPuzzleAgotado = null
        val app = applicationContext
        // Igual que onBlock: el encargo puede llegar despues de cerrar esta
        // pantalla, y entonces no puede quedar apuntando a una Activity muerta.
        NetworkManager.onKangaroo = { pub, ini, fin, pz ->
            try { arrancarKangarooDeRed(app, pub, ini, fin, pz) }
            catch (e: Throwable) { android.util.Log.e("NetworkActivity","onKangaroo: ${e.message}", e) }
        }
        NetworkManager.onBlock = { block ->
            try {
                HunterEngine.setRange(block.rangeStart, block.rangeEnd)
                HunterEngine.setMode(1)
                if (!HunterEngine.isRunning()) {
                    val prefs = ajustes(app)
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
