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
    private var tvTailscale: TextView? = null
    private var btnTsAbrir: Button? = null
    private var btnNodoPropio: Button? = null
    /** Una fila por trabajador, cada una con su boton de pausa. */
    private var contenedorWorkers: LinearLayout? = null
    /** Que puzzle repartira este movil si se inicia como maestro. */
    private var tvQueReparte: TextView? = null
    /** El nombre MagicDNS de este móvil. Se resuelve por DNS —o sea, red— así
     *  que se saca una vez en segundo plano y se guarda, en vez de pedirlo en
     *  cada refresco de pantalla. */
    @Volatile private var miNombreTs: String = ""
    @Volatile private var nombreTsPedido = false
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
        // El tema es un objeto de proceso: lo fija la primera pantalla que
        // arranca. Si Android mata el proceso y lo revive directamente aqui
        // —desde una notificacion, o al volver a la tarea—, esa primera
        // pantalla es esta, y sin esto se pintaria en oscuro aunque el
        // usuario tenga elegido el claro.
        AppTheme.init(this)
        // Cualquier pantalla puede ser la primera si Android revive el
        // proceso por una notificacion, asi que todas lo llaman. Es
        // idempotente: engancha los avisos del sistema una sola vez.
        AppLock.init(this)
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
            text = "Multi-device network"
            textSize = 20f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(headerRow)
        root.addView(TextView(this).apply {
            text = "Shares the work between several phones on the same network."
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

        root.addView(sectionLabel("As master"))
        // QUE se va a repartir, y donde se cambia.
        //
        // El maestro coge el puzzle que este elegido en la pantalla de Puzzle
        // —de ahi que saliera siempre el #108 sin que nadie lo hubiera pedido
        // aqui— pero esta pantalla no lo decia por ningun lado. Se elegia a
        // ciegas y se descubria al leer el registro.
        //
        // Se ensena aqui en vez de poner un selector propio a proposito: la
        // lista de puzzles vive en la pantalla de Puzzle, y tenerla en dos
        // sitios es como acaban divergiendo.
        tvQueReparte = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(tvQueReparte)
        btnMaster = actionButton("Start as master", AppTheme.ACCENT, AppTheme.BG_DEEP).also {
            it.setOnClickListener { startAsMaster() }
            root.addView(it)
        }

        root.addView(sectionLabel("As worker"))
        // ── Tailscale ─────────────────────────────────────────────────────
        // Va aquí, encima del campo de la dirección, porque es lo que decide
        // QUÉ se escribe en él. Con el CGNAT de la operadora delante no hay
        // manera de que un móvil de fuera llame al de casa —ni abriendo
        // puertos, porque el NAT que estorba no es el del router—, así que para
        // un cluster que salga de la WiFi esto no es un extra: es el camino.
        root.addView(sectionLabel("Tailscale"))
        tvTailscale = TextView(this).apply {
            text = "Checking..."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(8) }
        }
        root.addView(tvTailscale)

        val filaTs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        btnTsAbrir = actionButton("Open Tailscale", AppTheme.BG_ELEV, AppTheme.TXT_PRI).also {
            it.setOnClickListener {
                if (!Tailscale.abrir(this)) Toast.makeText(this,
                    "Could not open Tailscale nor its store page",
                    Toast.LENGTH_LONG).show()
            }
            (it.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.width = 0; lp.weight = 1f; lp.marginEnd = dp(8)
            }
            filaTs.addView(it)
        }
        actionButton("Pick a device", AppTheme.BG_ELEV, AppTheme.ACCENT).also {
            it.setOnClickListener { elegirDeTailnet() }
            (it.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.width = 0; lp.weight = 1f
            }
            filaTs.addView(it)
        }
        root.addView(filaTs)

        // El nodo empotrado. Sólo aparece si esta compilación lo lleva —
        // libtailscale.so es opcional— para no ofrecer un botón que no puede
        // hacer nada.
        btnNodoPropio = actionButton("Use built-in node", AppTheme.BG_ELEV, AppTheme.ACCENT).also {
            it.visibility = android.view.View.GONE
            it.setOnClickListener { alternarNodoPropio() }
            root.addView(it)
        }

        root.addView(TextView(this).apply {
            // Antes ponía "IP del maestro" y el ejemplo era una IP de WiFi. Con
            // Tailscale lo que conviene escribir es el NOMBRE: la dirección
            // también vale, pero el nombre se teclea sin equivocarse y no
            // cambia. El campo nunca ha validado nada, así que ya aceptaba
            // nombres — sólo que nada lo decía.
            text = "Master address or name"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(10), 0, dp(6))
        })
        etMasterIp = EditText(this).apply {
            hint = "192.168.1.100  ·  100.x.y.z  ·  a34"
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
            text = "Access code (the master shows it)"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(10), 0, dp(6))
        })
        etCode = EditText(this).apply {
            hint = "E.g. K7M2PQRT"
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

        actionButton("Find masters on the network", AppTheme.BG_ELEV, AppTheme.TXT_PRI).also {
            it.setOnClickListener { discoverMasters() }
            root.addView(it)
        }

        btnWorker = actionButton("Connect as worker", AppTheme.ACCENT, AppTheme.BG_DEEP).also {
            it.setOnClickListener { startAsWorker() }
            root.addView(it)
        }

        btnStop = actionButton("Stop the network", AppTheme.BG_ELEV, AppTheme.RED).also {
            it.visibility = android.view.View.GONE
            it.setOnClickListener { stopNetwork() }
            root.addView(it)
        }

        tvWorkersLbl = sectionLabel("Connected workers").also { root.addView(it) }
        tvWorkers = TextView(this).apply {
            text = "None yet"
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

        // Una fila por trabajador, con su boton.
        //
        // Antes habia que tocar la lista para abrir un dialogo, elegir de una
        // lista de texto y confirmar: tres pasos y una pantalla entera para
        // pausar un movil. Con un boton por fila se ve de un vistazo quien esta
        // parado y se cambia de un toque.
        contenedorWorkers = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(contenedorWorkers)

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
        actionButton("Pause all", AppTheme.BG_ELEV, AppTheme.TXT_PRI).also {
            it.setOnClickListener { mandarATodos(true) }
            (it.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.width = 0; lp.weight = 1f; lp.marginEnd = dp(8)
            }
            filaMando?.addView(it)
        }
        actionButton("Resume all", AppTheme.BG_ELEV, AppTheme.ACCENT).also {
            it.setOnClickListener { mandarATodos(false) }
            (it.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.width = 0; lp.weight = 1f
            }
            filaMando?.addView(it)
        }
        root.addView(filaMando)

        root.addView(sectionLabel("Log"))
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
                tvLog?.text = "Master running at ${NetworkManager.getLocalIp(this)}"
            } else if (NetworkManager.isWorker) {
                btnWorker?.isEnabled = false
                btnStop?.visibility = android.view.View.VISIBLE
                tvLog?.text = "Worker running"
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
                // La tarjeta de Tailscale SIEMPRE, la red esté en marcha o no.
                //
                // Estaba dentro del "if (isRunning)" y eso era justo al revés de
                // lo que hace falta: esa tarjeta es lo que miras ANTES de
                // arrancar nada —para saber qué dirección teclear, y para
                // encender el nodo propio—. Con la red parada nunca se pintaba,
                // así que se quedaba en "Checking..." para siempre y el botón
                // del nodo no llegaba a aparecer nunca.
                //
                // No cuesta: mirar las interfaces no hace red.
                pintarTailscale()
                // Y qué se va a repartir, que es justo lo que hay que mirar
                // ANTES de iniciar — o sea con la red parada.
                pintarQueReparte()
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
        if (ms == 0L) return "never"
        val s = (System.currentTimeMillis() - ms) / 1000
        return if (s < 60) "${s}s ago" else "${s / 60}min ago"
    }

    /** El estado del cluster, distinto según este móvil sea maestro o
     *  trabajador.
     *
     *  Antes enseñaba SIEMPRE la lista de trabajadores. En un trabajador esa
     *  lista está vacía por definición, así que ponía "ningún trabajador
     *  todavía" y parecía que la conexión había fallado cuando en realidad
     *  estaba buscando y mandando puntos. */
    /**
     * El recuadro de Tailscale. Se llama desde el refresco de cada 5 s.
     *
     * [Tailscale.estado] no hace red —sólo mira las interfaces— así que se puede
     * llamar aquí. El nombre MagicDNS sí resuelve por DNS, así que se pide una
     * sola vez en un hilo aparte y se guarda.
     */
    /**
     * El nodo empotrado: encenderlo o apagarlo.
     *
     * La clave de autorización hace falta UNA vez. Después la identidad vive en
     * los ficheros de la app, así que arrancar con clave vacía funciona — por
     * eso aquí sólo se pide si no consta que ya se hizo.
     */
    private fun alternarNodoPropio() {
        if (NetworkManager.usarTsnet || TsNet.arrancado) {
            AlertDialog.Builder(this)
                .setTitle("Turn off the built-in node")
                .setMessage("The cluster will go back to the plain network, which " +
                            "only works if both phones are on the same WiFi.")
                // El transporte se apaga AQUÍ, en el acto: lo demás va a un hilo
                // porque cerrar el nodo para el servidor de Go y borrar la
                // identidad toca disco, y las dos cosas desde el hilo de la
                // pantalla la congelan.
                .setPositiveButton("Turn off") { _, _ ->
                    NetworkManager.activarTsnet(false)
                    Thread { try { TsNet.parar() } catch (e: Throwable) {} }
                        .apply { isDaemon = true }.start()
                    pintarTailscale()
                }
                .setNeutralButton("Remove this node") { _, _ ->
                    // Borra también la identidad: dejar la marca a false con los
                    // ficheros ahí daría un nodo duplicado en el tailnet.
                    NetworkManager.activarTsnet(false)
                    val app = applicationContext
                    Thread { try { TsNet.olvidarNodo(app) } catch (e: Throwable) {} }
                        .apply { isDaemon = true }.start()
                    Toast.makeText(this, "Node removed", Toast.LENGTH_SHORT).show()
                    pintarTailscale()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (TsNet.autorizado(this)) { encenderNodo("") ; return }
        pedirClaveDeAlta()
    }

    private fun pedirClaveDeAlta() {
        val campo = EditText(this).apply {
            hint = "tskey-auth-..."
            setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_SEC)
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Register this phone")
            .setMessage(
                "The app will have its OWN node on your tailnet, with no need for the " +
                "Tailscale app and without routing the whole phone.\n\n" +
                "An auth key is needed, which you get at login.tailscale.com " +
                "→ Settings → Keys → Generate auth key.\n\n" +
                "Used only this once: afterwards the identity is stored in the " +
                "app and the key is not kept.")
            .setView(campo)
            .setPositiveButton("Register") { _, _ ->
                val k = campo.text?.toString()?.trim() ?: ""
                if (k.isEmpty()) return@setPositiveButton
                encenderNodo(k)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun encenderNodo(clave: String) {
        tvTailscale?.text = "Bringing up the built-in node...\n\n" +
                            "The first time can take a while."
        btnNodoPropio?.isEnabled = false
        val nombre = android.os.Build.MODEL.replace(" ", "-").lowercase()
        TsNet.arrancarEnHilo(this, clave, nombre) { ok, msg ->
            runOnUiThread {
                btnNodoPropio?.isEnabled = true
                if (ok) {
                    // Sólo se enciende el transporte si el nodo está arriba de
                    // verdad. activarTsnet lo vuelve a comprobar por su cuenta.
                    if (NetworkManager.activarTsnet(true))
                        Toast.makeText(this, "Built-in node running",
                                       Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Could not bring up the node")
                        .setMessage(msg.ifEmpty { "No detail" })
                        .setPositiveButton("OK", null)
                        .show()
                }
                pintarTailscale()
            }
        }
    }

    private fun pintarTailscale() {
        val hayNodo = try { TsNet.disponible() } catch (e: Throwable) { false }

        // ¿Murió la app la última vez intentando levantar el nodo?
        //
        // Esto se enseña ANTES que nada y manda sobre el resto: si el proceso se
        // muere dentro de Go, no hay excepción, no hay crash_log.txt y lo único
        // que ve el usuario es que "la app se sale al escáner". Sin decirlo
        // aquí, no hay forma de saber que eso fue un fallo y no un despiste.
        if (hayNodo && TsNet.murioLevantando(this)) {
            val motivo = TsNet.motivoUltimaMuerte(this)
            tvTailscale?.text = "THE APP DIED bringing up the built-in node.\n\n" +
                "This is not a catchable error: the failure happens inside the " +
                "Go code and takes the whole process down with it, which is why " +
                "the app closed and you came back to the scanner." +
                (if (motivo.isNotEmpty()) "\n\nThe system says: $motivo" else "") +
                // Lo ultimo que escribio Go antes de morir. Suele ser la linea
                // que explica el fallo, y sin esto habria que conectar un
                // ordenador por adb para verla.
                (TsNet.registro(this).let {
                    if (it.isEmpty()) "" else "\n\nLast tsnet log:\n$it"
                }) +
                "\n\nThe cluster over the plain network still works. Tap here " +
                "to dismiss this warning."
            tvTailscale?.setOnClickListener {
                TsNet.olvidarIntento(this); pintarTailscale()
            }
            btnNodoPropio?.visibility = android.view.View.VISIBLE
            btnNodoPropio?.text = "Retry the built-in node"
            return
        }
        tvTailscale?.setOnClickListener(null)
        btnNodoPropio?.visibility =
            if (hayNodo) android.view.View.VISIBLE else android.view.View.GONE
        btnNodoPropio?.text =
            if (NetworkManager.usarTsnet) "Turn off the built-in node" else "Use built-in node"

        // El nodo propio manda sobre lo demás: si está en marcha, es por donde
        // va el cluster, y enseñar el estado de la app de Tailscale ahí sería
        // hablar de otra cosa.
        if (NetworkManager.usarTsnet) {
            val dirs = try { TsNet.direcciones() } catch (e: Throwable) { "" }
            tvTailscale?.text = "Built-in node running.\n" +
                (if (dirs.isNotEmpty()) "Addresses: $dirs\n" else "") +
                "\nThe cluster goes through here: it gets past the carrier CGNAT and " +
                "is encrypted, with no Tailscale app and without routing the whole phone."
            return
        }
        if (TsNet.arrancando) {
            tvTailscale?.text = "Bringing up the built-in node...\n\n" +
                                "The first time can take a while."
            return
        }

        val ts = Tailscale.estado()
        btnTsAbrir?.text = if (Tailscale.instalado(this)) "Open Tailscale"
                           else "Install Tailscale"
        // Decir SIEMPRE si el nodo propio está o no. Callárselo es lo que hace
        // que la pantalla parezca estar pidiendo la app de Tailscale cuando se
        // supone que va integrada: sin esta línea, el botón simplemente no
        // aparece y no hay forma de saber si es que no está o es que falla.
        val notaNodo = if (hayNodo)
            "\n\nThis phone CAN be its own node, without installing Tailscale: " +
            "press \u0027Use built-in node\u0027 below."
        else
            "\n\nThe built-in node is not available, so the Tailscale app is " +
            "Tailscale." +
            // El motivo, si se sabe. Sin esto lo único que queda es "no está
            // disponible", que no da un solo dato con el que averiguar nada. El
            // mensaje del enlazador suele decir exactamente qué pasa.
            (TsNet.errorCarga.let { if (it.isEmpty()) "" else "\n\nReason: $it" })
        if (!ts.activo) {
            miNombreTs = ""; nombreTsPedido = false
            tvTailscale?.text = (if (Tailscale.instalado(this))
                "Tailscale installed but not connected.\n\n" +
                "Open it and flip the switch. While it is off, the two " +
                "phones only see each other on the same WiFi."
            else
                "The Tailscale app is not installed.\n\n" +
                "It is needed for the phones to see each other outside the same WiFi: " +
                "your carrier uses CGNAT and no port forwarding can fix that."
            ) + notaNodo
            return
        }
        if (!nombreTsPedido) {
            nombreTsPedido = true
            Thread {
                val n = Tailscale.nombreDe(ts.direccion)
                if (n.isNotEmpty()) runOnUiThread { miNombreTs = n; pintarTailscale() }
            }.apply { isDaemon = true }.start()
        }
        val comoMeLlamo = if (miNombreTs.isNotEmpty())
            "\nName: $miNombreTs   ← this is what to give the other phone"
        else ""
        tvTailscale?.text = "Connected through the Tailscale app.\n" +
            "Address: ${ts.direccion}$comoMeLlamo\n\n" +
            "Works the same from any network and is encrypted end to end." +
            notaNodo
    }

    /**
     * Elegir el maestro de entre los aparatos del tailnet, sin teclear nada.
     *
     * "Find masters on the network" no sirve aquí y no puede servir: va por
     * difusión UDP y la difusión no cruza VPNs. Lo que sí hay es la API de
     * Tailscale, que es HTTPS normal.
     */
    private fun elegirDeTailnet() {
        val k = Tailscale.clave(this)
        if (k.isEmpty()) { pedirClaveTailscale(); return }
        Toast.makeText(this, "Querying your tailnet...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val lista = Tailscale.aparatos(k)
                runOnUiThread {
                    if (lista.isEmpty()) {
                        Toast.makeText(this, "Your tailnet has no devices",
                                       Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val etiquetas = lista.map {
                        "${it.nombre}  ${it.direccion}" +
                        (if (it.so.isNotEmpty()) "  (${it.so})" else "") +
                        (if (!it.enLinea) "  · offline" else "")
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Which one is the master?")
                        .setItems(etiquetas) { _, i ->
                            // El nombre antes que la dirección: la dirección de
                            // Tailscale es estable, pero si algún día cambia el
                            // nombre sigue resolviendo y esto no se entera.
                            val a = lista[i]
                            etMasterIp?.setText(
                                if (a.nombre.isNotEmpty()) a.nombre else a.direccion)
                        }
                        .setNegativeButton("Close", null)
                        .setNeutralButton("Forget my key") { _, _ ->
                            Tailscale.olvidarClave(this)
                            Toast.makeText(this, "Key deleted", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                // El mensaje de Tailscale.aparatos ya está escrito para leerse
                // tal cual, incluido el caso de la clave caducada.
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Could not query")
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pedirClaveTailscale() {
        // Los colores salen de AppTheme y no de TXT/MUTED: aquellos son
        // variables locales de onCreate y desde aquí no se ven.
        val campo = EditText(this).apply {
            hint = "tskey-api-..."
            setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_SEC)
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Tailscale API key")
            .setMessage(
                "Listing your devices needs an API key.\n\n" +
                "Get it at login.tailscale.com → Settings → Keys → Generate " +
                "API key.\n\n" +
                "WARNING: that key lets anyone who has it READ your tailnet inventory " +
                "—names, addresses, systems—. It is stored " +
                "only on this phone and expires in 90 days.\n\n" +
                "It is not needed to use the cluster: you can also type the " +
                "master address or name by hand.")
            .setView(campo)
            .setPositiveButton("Save") { _, _ ->
                val k = campo.text?.toString()?.trim() ?: ""
                if (k.isEmpty()) return@setPositiveButton
                Tailscale.guardarClave(this, k)
                elegirDeTailnet()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pintarEstado() {
        pintarTailscale()
        if (NetworkManager.isWorker) {
            tvWorkersLbl?.text = "This phone"
            filaMando?.visibility = android.view.View.GONE
            val env = NetworkManager.puntosEnviados.get()
            // Una pausa mandada desde el maestro tiene que decirse aquí. Si no,
            // el dueño de este móvil ve que se ha parado y no sabe por qué: lo
            // primero que piensa es que se ha roto.
            tvWorkers?.text = if (NetworkManager.pausadoPorMaestro)
                "PAUSED\n" +
                "The master (${NetworkManager.masterIp}) asked to stop.\n" +
                "The work is saved and resumes from there when it asks to continue.\n\n" +
                "Points sent before stopping: $env"
            else
                "Working for ${NetworkManager.masterIp}\n" +
                "Points sent: $env\n" +
                "Last send: ${haceCuanto(NetworkManager.ultimoEnvioMs)}" +
                (if (env == 0L)
                    "\n\nThe first send takes up to 20 s, and only happens when there are " +
                    "new points to send."
                 else "")
            return
        }
        tvWorkersLbl?.text = "Connected workers"
        val list = NetworkManager.listaWorkers()
        // Los mandos, en LOS DOS modos. Estaban sólo en Kangaroo, y era
        // coherente mientras la pausa tampoco llegaba en modo bloques; ahora que
        // llega, esconderlos seria quitar algo que funciona.
        filaMando?.visibility =
            if (NetworkManager.isMaster && list.isNotEmpty())
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
                "Table: $guardados of $tope (${"%.1f".format(pct)} %)" +
                (if (guardados >= tope) "  FULL, nothing new is stored!"
                 else if (pct >= 80) "  filling up" else "") + "\n"
            } else ""
            "Points received: ${NetworkManager.puntosRecibidos.get()}\n" +
            (if (hilos > 0) "This phone: searching with $hilos threads\n"
             else "This phone: only collecting, not searching\n") + tabla + "\n"
        } else ""
        tvWorkers?.text = cab + (if (list.isEmpty()) "No workers yet"
                                 else "${list.size} worker(s)")
        pintarFilasDeWorkers(list)
    }

    /**
     * Una fila por trabajador, con su botón de pausa.
     *
     * Se reconstruyen enteras en cada refresco —cada cinco segundos— en vez de
     * ir actualizando las que hay. Con dos o tres móviles eso no se nota, y
     * evita el enredo de emparejar filas con trabajadores que entran y salen.
     */
    /** "Repartira el puzzle #108" y donde se cambia. */
    /**
     * Fija la direccion contra la que va a comparar el motor.
     *
     * @return false si no se puede, y entonces NO se arranca. Buscar sin
     *   objetivo no es "buscar peor": es no buscar, gastando la bateria igual.
     *   Mas vale un trabajador parado que se ve, que uno a toda velocidad que
     *   no puede encontrar nada.
     */
    private fun fijarObjetivo(block: NetworkManager.NetBlock): Boolean {
        val a = block.addr.trim()
        if (a.isEmpty()) {
            val m = "The master did not send the address for puzzle #${block.puzzleNum}. " +
                    "Not starting: without it the search cannot find anything. " +
                    "Update the app on the master."
            android.util.Log.w("NetworkActivity", m)
            runOnUiThread { tvLog?.text = "${tvLog?.text}\n$m" }
            return false
        }
        try {
            HunterEngine.setTarget(a)
            if (!HunterEngine.hasTarget()) {
                val m = "Invalid address from the master: $a. Not starting."
                android.util.Log.w("NetworkActivity", m)
                runOnUiThread { tvLog?.text = "${tvLog?.text}\n$m" }
                return false
            }
        } catch (e: Throwable) {
            android.util.Log.e("NetworkActivity", "setTarget: ${e.message}", e)
            return false
        }
        return true
    }

    private fun pintarQueReparte() {
        val p = ajustes()
        val num = p.getInt("current_puzzle_num", 71)
        val ini = p.getString("current_range_start", "") ?: ""
        val pub = p.getString("kangaroo_pub", "") ?: ""
        val kIni = p.getString("kangaroo_ini", "") ?: ""
        val kFin = p.getString("kangaroo_fin", "") ?: ""
        val conKangaroo = pub.length == 66 && kIni == ini &&
                          kFin == (p.getString("current_range_end", "") ?: "")
        tvQueReparte?.text =
            "Will share puzzle #$num" +
            (if (conKangaroo) " with Kangaroo." else " in blocks.") +
            "\nTo change it, pick another on the Puzzle screen before starting."
    }

    private fun pintarFilasDeWorkers(list: List<NetworkManager.NetWorker>) {
        val cont = contenedorWorkers ?: return
        cont.removeAllViews()
        if (!NetworkManager.isMaster) return
        for (w in list) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
                setPadding(dp(14), dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            // El estado que se enseña es el que el maestro QUIERE, no el último
            // que dijo el trabajador: entre que se pulsa el botón y llega la
            // orden pasan hasta treinta segundos, y durante ese rato la lista
            // diría "trabajando" con la pausa ya pedida.
            val est = if (w.pausado) "paused" else w.status
            fila.addView(TextView(this).apply {
                text = "${w.device}\n${velocidad(w.speed)} · $est · ${haceCuanto(w.vistoMs)}"
                textSize = AppTheme.SP_CAPTION
                setTextColor(if (w.pausado) AppTheme.TXT_SEC else AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            fila.addView(TextView(this).apply {
                text = if (w.pausado) "\u25B6" else "\u23F8"
                textSize = 22f
                setTextColor(if (w.pausado) AppTheme.ACCENT else AppTheme.TXT_PRI)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(6), dp(16), dp(6))
                isClickable = true; isFocusable = true
                background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
                setOnClickListener {
                    val nuevo = !w.pausado
                    NetworkManager.mandarPausa(w.id, nuevo)
                    Toast.makeText(this@NetworkActivity,
                        if (nuevo) "${w.device} will stop in a few seconds"
                        else "${w.device} will continue in a few seconds",
                        Toast.LENGTH_SHORT).show()
                    pintarEstado()
                }
            })
            cont.addView(fila)
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
        // La direccion del puzzle, que es contra lo que compara el trabajador.
        // Sin ella su busqueda no puede encontrar nada: el motor sin objetivo y
        // sin lista cargada ni siquiera hace la comparacion.
        val addr = prefs.getString("current_puzzle_addr", "") ?: ""
        if (!conKangaroo && addr.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("The puzzle address is missing")
                .setMessage("Open the Puzzle screen and pick puzzle #$puzzleNum " +
                            "once. That stores the address the workers " +
                            "have to compare against.\n\n" +
                            "Without it you would share work that cannot find " +
                            "anything.")
                .setPositiveButton("Got it", null)
                .show()
            return
        }
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
            NetworkManager.startMasterKangaroo(this, puzzleNum, pub, kIni, kFin, addr)
            NetworkManager.onClave = { dispositivo, claveHex ->
                runOnUiThread { avisarClaveEncontrada(dispositivo, claveHex) }
            }
        } else {
            NetworkManager.startMaster(this, puzzleNum, rangeStart, rangeEnd, addr)
        }
        btnMaster?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        val ip = NetworkManager.getLocalIp(this)
        val code = NetworkManager.authToken
        tvIp?.text = textoDeMisDirecciones()
        tvLog?.text = "✓ Master started\nIP: $ip\nCode: $code\n" +
                      "Puzzle #$puzzleNum\nRange: ${rangeStart.take(12)}..."
        val aviso = if (conKangaroo)
            "\n\nKangaroo sharing: every device on the same range, " +
            "because splitting it would make the search worse.\n\n" +
            "WARNING: what travels between the phones allows the private key " +
            "to be rebuilt, and it travels UNENCRYPTED. On your own WiFi that is one thing; " +
            "out on the Internet, anyone along the way sees the same as you."
        else ""
        // Todas, no sólo la primera IPv4: desde fuera de la WiFi la que sirve es
        // la IPv6, y sin verla no hay forma de saber qué teclear en el otro.
        //
        // Y con el nodo empotrado en marcha, la PRIMERA es la del tailnet: no
        // sale en la enumeración de interfaces porque tsnet vive en espacio de
        // usuario, y es justo la única que vale desde otra red.
        val ts = if (NetworkManager.usarTsnet)
                     try { TsNet.direcciones() } catch (e: Throwable) { "" } else ""
        val dirs = (if (ts.isNotEmpty())
                        ts.split(",").joinToString("\n") { "  tailnet  ${it.trim()}" } + "\n"
                    else "") +
            NetworkManager.direccionesLocales()
                .joinToString("\n") { "  ${it.first}  ${it.second}" }
        // El código hay que teclearlo en cada worker; sin él no se aceptan.
        AlertDialog.Builder(this)
            .setTitle("Master running")
            .setMessage("Addresses of this phone:\n$dirs\n\n" +
                        "Access code:\n\n        $code\n\n" +
                        "Enter the address and this code on each worker. " +
                        "Without the code, no device on the network can " +
                        "connect." + aviso)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startAsWorker() {
        val ip = etMasterIp?.text?.toString()?.trim() ?: ""
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter the master IP", Toast.LENGTH_SHORT).show()
            return
        }
        val code = etCode?.text?.toString()?.trim() ?: ""
        if (code.isEmpty()) {
            Toast.makeText(this, "Enter the code shown by the master", Toast.LENGTH_SHORT).show()
            return
        }
        NetworkManager.onBlock = { block ->
            runOnUiThread {
                // LA DIRECCION PRIMERO. Sin ella el motor no puede encontrar
                // nada: con g_has_target a 0 y sin lista cargada, la
                // comparacion no se hace y la busqueda entera es humo. Estuvo
                // asi y el trabajador se paso horas calculando hashes contra
                // nada, a toda velocidad y con su grafica.
                if (!fijarObjetivo(block)) return@runOnUiThread
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
                tvLog?.text = "$log\n▶ Block #${block.blockId}\n  ${block.rangeStart.take(16)}..."
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
                    "  full range (splitting it makes Kangaroo worse)\n" +
                    "  points are sent to the master every 20 s"
                else "${tvLog?.text}\nCould not start Kangaroo"
            }
        }
        // El master avisa cuando el puzzle se queda sin fondos: alguien lo ha
        // resuelto mientras buscábamos. El bucle ya ha parado la búsqueda.
        NetworkManager.onPuzzleAgotado = {
            runOnUiThread {
                tvLog?.text = "${tvLog?.text}\n\nSearch stopped: the puzzle no longer " +
                              "has funds.\nSomebody solved it. The work is saved."
            }
        }
        // Con el contexto: es lo que deja la sesión guardada en disco para que
        // el servicio pueda volver a conectarse solo si Android mata la app.
        NetworkManager.startWorker(ip, code, this)
        btnWorker?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        tvLog?.text = "Connecting to master $ip..."
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
                extra = "PUZZLE kangaroo (network, from $dispositivo)", checkedTs = 0L))
        } catch (e: Exception) {
            android.util.Log.e("NetworkActivity", "could not save: ${e.message}", e)
        }
        try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
        tvLog?.text = "${tvLog?.text}\n\nKEY FOUND on $dispositivo\n$claveHex\n" +
                      "Saved to the finds vault."
        AlertDialog.Builder(this)
            .setTitle("Key found")
            .setMessage("$dispositivo found it:\n\n$claveHex\n\n" +
                        "It is saved in the finds vault.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun discoverMasters() {
        tvLog?.text = "Looking for masters on this WiFi..."
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
                tvLog?.text = "No master was found on this WiFi.\n\n" +
                    "This search only sees devices on the SAME network: it does not cross " +
                    "routers, VPNs or mobile data.\n\n" +
                    "If the master is on another network or behind a VPN, " +
                    "type its address by hand above — it shows it on its own " +
                    "own screen."
            }
        })
    }

    private fun stopNetwork() {
        NetworkManager.stop()
        btnMaster?.isEnabled = true
        btnWorker?.isEnabled = true
        btnStop?.visibility = android.view.View.GONE
        tvWorkers?.text = "None yet"
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
        // La del nodo empotrado va PRIMERA y aparte.
        //
        // direccionesLocales() enumera las interfaces del sistema, y la de tsnet
        // NO está ahí: tsnet vive en espacio de usuario, así que Java no la ve.
        // Sin esto, la caja enseña la IP de la WiFi y esa es justo la que NO
        // sirve cuando el cluster va por el tailnet — se teclearía la
        // equivocada sin que nada avisara.
        if (NetworkManager.usarTsnet) {
            val ts = try { TsNet.direcciones() } catch (e: Throwable) { "" }
            if (ts.isNotEmpty()) {
                return "Address on your tailnet (the one that works from any network):\n" +
                       "  " + ts.split(",").joinToString("\n  ") { it.trim() } +
                       "\n\nAnd on this WiFi:\n" +
                       NetworkManager.direccionesLocales()
                           .joinToString("\n") { "  ${it.first}  ${it.second}" }
            }
        }
        val d = NetworkManager.direccionesLocales()
        if (d.isEmpty()) return "No network"
        if (d.size == 1) return "IP: ${d[0].second}"
        val hayVpn = d.any { it.second.contains("VPN") }
        return "Addresses of this phone:\n" +
               d.joinToString("\n") { "  ${it.first}  ${it.second}" } +
               if (hayVpn)
                   "\n\nUse the VPN one: it works the same from any network, " +
                   "with no ports to open."
               else
                   "\n\nOn the same WiFi the IPv4 one is used. From outside you need " +
                   "the IPv6 one, a forwarded port on the router, or a VPN."
    }

    private fun mandarATodos(pausar: Boolean) {
        val n = NetworkManager.mandarPausaATodos(pausar)
        if (n == 0) {
            Toast.makeText(this, "No workers connected",
                           Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this,
            if (pausar) "$n worker(s) will stop in a few seconds"
            else "$n worker(s) will continue in a few seconds",
            Toast.LENGTH_SHORT).show()
        pintarEstado()
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
                // Un if y no un "return@onBlock": las etiquetas implicitas de
                // Kotlin salen del NOMBRE DE LA FUNCION a la que se pasa la
                // lambda, no de la propiedad a la que se asigna. onBlock es una
                // propiedad, asi que esa etiqueta no existe y no compila.
                if (fijarObjetivo(block)) {
                    HunterEngine.setRange(block.rangeStart, block.rangeEnd)
                    HunterEngine.setMode(1)
                    if (!HunterEngine.isRunning()) {
                        val prefs = ajustes(app)
                        HunterEngine.startHunting(
                            prefs.getInt("puzzle_threads", 3) + 1,
                            prefs.getInt("puzzle_cpu", 70) + 10)
                    }
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
