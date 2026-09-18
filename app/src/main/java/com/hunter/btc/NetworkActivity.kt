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
    private var tvWorkersLbl: TextView? = null
    /** Los botones de pausar/reanudar. Sólo se enseñan en el maestro. */
    private var filaMando: LinearLayout? = null
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

    /**
     * Fijar hilos a los núcleos rápidos sólo si CABEN. Misma regla que en la
     * pantalla principal, y por el mismo motivo: con más hilos que núcleos
     * rápidos se amontonan y el resto del chip se queda sin usar.
     *
     * Aquí hace falta porque el worker de fuerza bruta arranca desde esta
     * pantalla, sin pasar por la otra.
     */
    private fun aplicarAfinidad(hilos: Int) {
        val n = Runtime.getRuntime().availableProcessors()
        if (n <= 1) return
        fun leer(r: String) = try { java.io.File(r).readText().trim().toInt() } catch (e: Exception) { 0 }
        var peso = IntArray(n) { leer("/sys/devices/system/cpu/cpu$it/cpu_capacity") }
        if (peso.any { it <= 0 })
            peso = IntArray(n) { leer("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
        if (peso.any { it <= 0 }) return
        val mx = peso.max()
        if (peso.all { it == mx }) return
        val rapidos = (0 until n).filter { peso[it] >= mx * 85 / 100 }
        if (rapidos.size == n) return
        try { HunterEngine.setBigCores(rapidos.toIntArray(), hilos <= rapidos.size) }
        catch (e: Exception) {}
    }

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
            text = textoDeMisDirecciones()
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

        tvWorkersLbl = sectionLabel("Trabajadores conectados").also { root.addView(it) }
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
            // Tocar la lista abre el mando de UNO. Los botones de abajo son para
            // todos a la vez, que es lo más frecuente.
            setOnClickListener { if (NetworkManager.isMaster) elegirTrabajador() }
        }
        root.addView(tvWorkers)

        // ── Mando de los trabajadores ─────────────────────────────────────
        // Sólo tiene sentido en el maestro: es él quien puede mandar, porque la
        // orden viaja colgada de la respuesta a lo que le manden ellos.
        filaMando = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        actionButton("Pausar todos", AppTheme.BG_ELEV, AppTheme.TXT_PRI).also {
            it.setOnClickListener { mandarATodos(true) }
            (it.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.width = 0; lp.weight = 1f; lp.marginEnd = dp(8)
            }
            filaMando?.addView(it)
        }
        actionButton("Reanudar todos", AppTheme.BG_ELEV, AppTheme.ACCENT).also {
            it.setOnClickListener { mandarATodos(false) }
            (it.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.width = 0; lp.weight = 1f
            }
            filaMando?.addView(it)
        }
        root.addView(filaMando)

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
        NetworkManager.onWorkers = { runOnUiThread { pintarEstado() } }
        // La lista sólo se repintaba cuando cambiaba algo. Pero "hace cuánto se
        // supo de este worker" y "cuántos puntos llevo recibidos" cambian solos
        // con el tiempo, así que sin un refresco periódico se quedaban clavados.
        refresco = object : Runnable {
            override fun run() {
                if (NetworkManager.isRunning.get()) pintarEstado()
                handler.postDelayed(this, 5000L)
            }
        }
        handler.post(refresco!!)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var refresco: Runnable? = null

    private fun velocidad(v: Long): String = when {
        v >= 1_000_000L -> "%.1f M/s".format(v / 1e6)
        v >= 1_000L     -> "%.0f K/s".format(v / 1e3)
        v > 0           -> "$v /s"
        else            -> "—"
    }

    private fun haceCuanto(ms: Long): String {
        if (ms == 0L) return "nunca"
        val s = (System.currentTimeMillis() - ms) / 1000
        return if (s < 60) "hace ${s}s" else "hace ${s / 60}min"
    }

    /** El estado del cluster, distinto según este móvil sea maestro o
     *  trabajador.
     *
     *  Antes enseñaba SIEMPRE la lista de trabajadores. En un trabajador esa
     *  lista está vacía por definición, así que ponía "ningún trabajador
     *  todavía" y parecía que la conexión había fallado cuando en realidad
     *  estaba buscando y mandando puntos. */
    private fun pintarEstado() {
        if (NetworkManager.isWorker) {
            tvWorkersLbl?.text = "Este móvil"
            filaMando?.visibility = android.view.View.GONE
            val env = NetworkManager.puntosEnviados.get()
            // Una pausa mandada desde el maestro tiene que decirse aquí. Si no,
            // el dueño de este móvil ve que se ha parado y no sabe por qué: lo
            // primero que piensa es que se ha roto.
            tvWorkers?.text = if (NetworkManager.pausadoPorMaestro)
                "EN PAUSA\n" +
                "El maestro (${NetworkManager.masterIp}) ha mandado parar.\n" +
                "El trabajo está guardado y sigue desde ahí cuando mande seguir.\n\n" +
                "Puntos enviados antes de parar: $env"
            else
                "Trabajando para ${NetworkManager.masterIp}\n" +
                "Puntos enviados: $env\n" +
                "Último envío: ${haceCuanto(NetworkManager.ultimoEnvioMs)}" +
                (if (env == 0L)
                    "\n\nEl primer envío tarda hasta 20 s, y sólo va cuando hay " +
                    "puntos nuevos que mandar."
                 else "")
            return
        }
        tvWorkersLbl?.text = "Trabajadores conectados"
        val list = NetworkManager.listaWorkers()
        filaMando?.visibility =
            if (NetworkManager.isMaster && NetworkManager.modo == NetworkManager.Modo.KANGAROO)
                android.view.View.VISIBLE else android.view.View.GONE
        val cab = if (NetworkManager.isMaster && NetworkManager.modo == NetworkManager.Modo.KANGAROO) {
            // puntosRecibidos se contaba desde el principio y NO SE ENSEÑABA EN
            // NINGÚN SITIO: el maestro no tenía forma de ver si el cluster
            // estaba aportando algo o si los workers hablaban al vacío.
            //
            // Y qué está haciendo ESTE móvil, que ya no es evidente: de maestro
            // arranca recogiendo, sin buscar, hasta que se lo pidas.
            val hilos = try { HunterEngine.kangarooHilos() } catch (e: Throwable) { 0 }
            // La tabla que se llena es LA DEL MAESTRO, porque es donde se juntan
            // los puntos de todos. Y cuantos más trabajadores haya antes pasa,
            // que es justo lo contrario de lo que uno espera al añadir aparatos.
            // Aquí es donde tiene que verse.
            val guardados = try { HunterEngine.kangarooPoints() } catch (e: Throwable) { 0L }
            val tope = try { HunterEngine.kangarooTope() } catch (e: Throwable) { 0L }
            val tabla = if (tope > 0) {
                val pct = guardados * 100.0 / tope
                "Tabla: $guardados de $tope (${"%.1f".format(pct)} %)" +
                (if (guardados >= tope) "  ¡LLENA, no se guarda nada nuevo!"
                 else if (pct >= 80) "  se está llenando" else "") + "\n"
            } else ""
            "Puntos recibidos: ${NetworkManager.puntosRecibidos.get()}\n" +
            (if (hilos > 0) "Este móvil: buscando con $hilos hilos\n"
             else "Este móvil: sólo recoge, no busca\n") + tabla + "\n"
        } else ""
        tvWorkers?.text = cab + if (list.isEmpty()) "Ningún trabajador todavía"
        else list.joinToString("\n") { w ->
            // El estado que se enseña es el que el maestro QUIERE, no el último
            // que dijo el trabajador: entre que se pulsa el botón y llega la
            // orden pasan hasta veinte segundos, y durante ese rato la lista
            // diría "kangaroo" con la pausa ya pedida.
            val est = if (w.pausado) "en pausa" else w.status
            "· ${w.device}  ${velocidad(w.speed)}  [$est]  ${haceCuanto(w.vistoMs)}"
        } + (if (list.isNotEmpty()) "\n\nToca aquí para pausar uno solo." else "")
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
            // El maestro arranca como RECOLECTOR, no buscando.
            //
            // Antes se ponía a buscar solo al pulsar "iniciar como maestro", y
            // eso no es lo que se le pide a un maestro: puede que el móvil que
            // coordina sea el que quieres dejar en paz —el que usas, el que no
            // quieres que se caliente— y los que trabajan sean los otros.
            //
            // Recolector NO es "no hacer nada": mantiene la tabla de puntos
            // distinguidos, que es donde se juntan los de todos y donde aparece
            // la colisión. Sin tabla, los puntos de los trabajadores se
            // rechazan, cada móvil busca por su cuenta y el cluster pierde
            // justo lo que lo hace valer: con la tabla compartida N móviles van
            // N veces más rápido, sin compartirla sólo raíz(N). Con dos móviles
            // eso es 2x contra 1,41x.
            //
            // De hecho el maestro puede encontrar la clave sin dar un salto,
            // juntando dos mitades que vengan de móviles distintos.
            if (!HunterEngine.kangarooRunning())
                arrancarKangarooDeRed(this, pub, kIni, kFin, puzzleNum, hilos = 0)
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
        tvIp?.text = textoDeMisDirecciones()
        tvLog?.text = "✓ Master iniciado\nIP: $ip\nCódigo: $code\n" +
                      "Puzzle #$puzzleNum\nRango: ${rangeStart.take(12)}..."
        val aviso = if (conKangaroo)
            "\n\nReparto de Kangaroo: todos los aparatos al mismo rango, " +
            "porque partirlo empeoraría la búsqueda.\n\n" +
            "AVISO: lo que viaja entre los móviles permite reconstruir la " +
            "clave privada, y viaja SIN CIFRAR. En tu propia WiFi es una cosa; " +
            "si lo sacas a Internet, cualquiera por el camino ve lo mismo que tú."
        else ""
        // Todas, no sólo la primera IPv4: desde fuera de la WiFi la que sirve es
        // la IPv6, y sin verla no hay forma de saber qué teclear en el otro.
        val dirs = NetworkManager.direccionesLocales()
            .joinToString("\n") { "  ${it.first}  ${it.second}" }
        // El código hay que teclearlo en cada worker; sin él no se aceptan.
        AlertDialog.Builder(this)
            .setTitle("Master activo")
            .setMessage("Direcciones de este móvil:\n$dirs\n\n" +
                        "Código de acceso:\n\n        $code\n\n" +
                        "Introduce la dirección y este código en cada worker. " +
                        "Sin el código, ningún dispositivo de la red puede " +
                        "conectarse." + aviso)
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
                    aplicarAfinidad(threads)
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
        // Con el contexto: es lo que deja la sesión guardada en disco para que
        // el servicio pueda volver a conectarse solo si Android mata la app.
        NetworkManager.startWorker(ip, code, this)
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
    /**
     * @param hilos  −1 usa los que tenga puestos el usuario. CERO es el modo
     *   RECOLECTOR: la tabla queda viva y recoge los puntos que manden los
     *   trabajadores, pero aquí no camina ningún canguro y no se gasta batería.
     */
    private fun arrancarKangarooDeRed(ctx: android.content.Context,
                                      pub: String, ini: String, fin: String, pz: Int,
                                      hilos: Int = -1): Boolean =
        NetworkManager.arrancarMotorKangaroo(ctx, pub, ini, fin, hilos)

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
        tvLog?.text = "Buscando masters en esta WiFi..."
        NetworkManager.discoverMasters(this, onFound = { ip, device ->
            runOnUiThread {
                etMasterIp?.setText(ip)
                val current = tvLog?.text?.toString() ?: ""
                tvLog?.text = "$current\nOK Master: $device ($ip)"
            }
        }, onFin = { n ->
            // Sin esto la pantalla se quedaba en "Buscando..." para siempre
            // cuando no encontraba nada, que es SIEMPRE si el maestro no está
            // en esta misma WiFi: la difusión no cruza routers, ni VPNs, ni
            // datos móviles. Quedarse esperando algo imposible sin que nada lo
            // diga es peor que no tener el botón.
            if (n == 0) runOnUiThread {
                tvLog?.text = "No se ha encontrado ningún maestro en esta WiFi.\n\n" +
                    "Esta búsqueda sólo ve aparatos de la MISMA red: no cruza " +
                    "routers, ni VPN, ni datos móviles.\n\n" +
                    "Si el maestro está en otra red o al otro lado de una VPN, " +
                    "escribe su dirección a mano arriba — la enseña él en su " +
                    "propia pantalla."
            }
        })
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

    /* ── Mando a distancia de los trabajadores ────────────────────────────────
     *
     * El maestro no puede llamar al trabajador: es el trabajador quien abre la
     * conexión, manda una cosa, lee la respuesta y cierra. Así que la orden no
     * se envía, se cuelga de la respuesta al siguiente mensaje que llegue de él
     * —sus puntos, cada veinte segundos, o su latido si está parado.
     *
     * Por eso todos los avisos de aquí dicen "en unos segundos" en vez de dar
     * la orden por hecha: prometer que ya está parado cuando aún no lo está es
     * peor que decir la verdad, porque entonces el usuario cree que el botón no
     * funciona y lo pulsa otra vez.
     */
    /**
     * Las direcciones de este móvil, para que el trabajador sepa cuál teclear.
     *
     * Antes salía sólo la primera IPv4 encontrada, que es la buena mientras el
     * cluster viva en una WiFi. Fuera de ahí no vale: por datos móviles la IPv4
     * está detrás del CGNAT de la operadora y no sirve para que te llamen,
     * mientras que la IPv6 sí, porque en IPv6 no hay NAT. Sin verlas todas no
     * había forma de saber qué poner en el otro móvil.
     */
    private fun textoDeMisDirecciones(): String {
        val d = NetworkManager.direccionesLocales()
        if (d.isEmpty()) return "Sin red"
        if (d.size == 1) return "IP: ${d[0].second}"
        val hayVpn = d.any { it.second.contains("VPN") }
        return "Direcciones de este móvil:\n" +
               d.joinToString("\n") { "  ${it.first}  ${it.second}" } +
               if (hayVpn)
                   "\n\nUsa la de la VPN: funciona igual desde cualquier red, " +
                   "sin abrir puertos."
               else
                   "\n\nEn la misma WiFi se usa la IPv4. Desde fuera hace falta " +
                   "la IPv6, abrir el puerto en el router, o una VPN."
    }

    private fun mandarATodos(pausar: Boolean) {
        val n = NetworkManager.mandarPausaATodos(pausar)
        if (n == 0) {
            Toast.makeText(this, "No hay trabajadores conectados",
                           Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this,
            if (pausar) "$n trabajador(es) pararán en unos segundos"
            else "$n trabajador(es) seguirán en unos segundos",
            Toast.LENGTH_SHORT).show()
        pintarEstado()
    }

    /** Tocar la lista: elegir un trabajador y pausarlo o reanudarlo él solo. */
    private fun elegirTrabajador() {
        val lista = NetworkManager.listaWorkers()
        if (lista.isEmpty()) {
            Toast.makeText(this, "No hay trabajadores conectados",
                           Toast.LENGTH_SHORT).show()
            return
        }
        val nombres = lista.map {
            "${it.device}  ${if (it.pausado) "· en pausa" else "· trabajando"}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pausar o reanudar")
            .setItems(nombres) { _, i ->
                val w = lista[i]
                val nuevo = !w.pausado
                NetworkManager.mandarPausa(w.id, nuevo)
                Toast.makeText(this,
                    if (nuevo) "${w.device} parará en unos segundos"
                    else "${w.device} seguirá en unos segundos",
                    Toast.LENGTH_SHORT).show()
                pintarEstado()
            }
            .setNegativeButton("Cerrar", null)
            .show()
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
        refresco?.let { handler.removeCallbacks(it) }
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
