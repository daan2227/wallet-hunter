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
        btnMaster = actionButton("Iniciar como maestro", AppTheme.ACCENT, AppTheme.BG_DEEP).also {
            it.setOnClickListener { startAsMaster() }
            root.addView(it)
        }

        root.addView(sectionLabel("Como trabajador"))
        // ── Tailscale ─────────────────────────────────────────────────────
        // Va aquí, encima del campo de la dirección, porque es lo que decide
        // QUÉ se escribe en él. Con el CGNAT de la operadora delante no hay
        // manera de que un móvil de fuera llame al de casa —ni abriendo
        // puertos, porque el NAT que estorba no es el del router—, así que para
        // un cluster que salga de la WiFi esto no es un extra: es el camino.
        root.addView(sectionLabel("Tailscale"))
        tvTailscale = TextView(this).apply {
            text = "Comprobando..."
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
        btnTsAbrir = actionButton("Abrir Tailscale", AppTheme.BG_ELEV, AppTheme.TXT_PRI).also {
            it.setOnClickListener {
                if (!Tailscale.abrir(this)) Toast.makeText(this,
                    "No se ha podido abrir Tailscale ni su ficha en la tienda",
                    Toast.LENGTH_LONG).show()
            }
            (it.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.width = 0; lp.weight = 1f; lp.marginEnd = dp(8)
            }
            filaTs.addView(it)
        }
        actionButton("Elegir aparato", AppTheme.BG_ELEV, AppTheme.ACCENT).also {
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
        btnNodoPropio = actionButton("Usar nodo propio", AppTheme.BG_ELEV, AppTheme.ACCENT).also {
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
            text = "Dirección o nombre del maestro"
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
                // La tarjeta de Tailscale SIEMPRE, la red esté en marcha o no.
                //
                // Estaba dentro del "if (isRunning)" y eso era justo al revés de
                // lo que hace falta: esa tarjeta es lo que miras ANTES de
                // arrancar nada —para saber qué dirección teclear, y para
                // encender el nodo propio—. Con la red parada nunca se pintaba,
                // así que se quedaba en "Comprobando..." para siempre y el botón
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
                .setTitle("Apagar el nodo propio")
                .setMessage("El cluster volverá a hablar por la red normal, que " +
                            "sólo llega si los dos móviles están en la misma WiFi.")
                // El transporte se apaga AQUÍ, en el acto: lo demás va a un hilo
                // porque cerrar el nodo para el servidor de Go y borrar la
                // identidad toca disco, y las dos cosas desde el hilo de la
                // pantalla la congelan.
                .setPositiveButton("Apagar") { _, _ ->
                    NetworkManager.activarTsnet(false)
                    Thread { try { TsNet.parar() } catch (e: Throwable) {} }
                        .apply { isDaemon = true }.start()
                    pintarTailscale()
                }
                .setNeutralButton("Dar de baja este nodo") { _, _ ->
                    // Borra también la identidad: dejar la marca a false con los
                    // ficheros ahí daría un nodo duplicado en el tailnet.
                    NetworkManager.activarTsnet(false)
                    val app = applicationContext
                    Thread { try { TsNet.olvidarNodo(app) } catch (e: Throwable) {} }
                        .apply { isDaemon = true }.start()
                    Toast.makeText(this, "Nodo dado de baja", Toast.LENGTH_SHORT).show()
                    pintarTailscale()
                }
                .setNegativeButton("Cancelar", null)
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
            .setTitle("Dar de alta este móvil")
            .setMessage(
                "La app tendrá su PROPIO nodo en tu tailnet, sin necesidad de la " +
                "app de Tailscale y sin enrutar el móvil entero.\n\n" +
                "Hace falta una clave de alta, que se saca en login.tailscale.com " +
                "→ Settings → Keys → Generate auth key.\n\n" +
                "Sólo se usa esta vez: después la identidad queda guardada en la " +
                "app y la clave no se conserva.")
            .setView(campo)
            .setPositiveButton("Dar de alta") { _, _ ->
                val k = campo.text?.toString()?.trim() ?: ""
                if (k.isEmpty()) return@setPositiveButton
                encenderNodo(k)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun encenderNodo(clave: String) {
        tvTailscale?.text = "Levantando el nodo propio...\n\n" +
                            "La primera vez puede tardar un rato."
        btnNodoPropio?.isEnabled = false
        val nombre = android.os.Build.MODEL.replace(" ", "-").lowercase()
        TsNet.arrancarEnHilo(this, clave, nombre) { ok, msg ->
            runOnUiThread {
                btnNodoPropio?.isEnabled = true
                if (ok) {
                    // Sólo se enciende el transporte si el nodo está arriba de
                    // verdad. activarTsnet lo vuelve a comprobar por su cuenta.
                    if (NetworkManager.activarTsnet(true))
                        Toast.makeText(this, "Nodo propio en marcha",
                                       Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("No se pudo levantar el nodo")
                        .setMessage(msg.ifEmpty { "Sin detalle" })
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
            tvTailscale?.text = "LA APP MURIÓ levantando el nodo propio.\n\n" +
                "No es un error que se pueda capturar: el fallo ocurre dentro del " +
                "código de Go y se lleva el proceso entero por delante, por eso " +
                "la app se cerró y volviste al escáner." +
                (if (motivo.isNotEmpty()) "\n\nEl sistema dice: $motivo" else "") +
                // Lo ultimo que escribio Go antes de morir. Suele ser la linea
                // que explica el fallo, y sin esto habria que conectar un
                // ordenador por adb para verla.
                (TsNet.registro(this).let {
                    if (it.isEmpty()) "" else "\n\nÚltimo registro de tsnet:\n$it"
                }) +
                "\n\nEl cluster por la red normal sigue funcionando. Toca aquí " +
                "para olvidar este aviso."
            tvTailscale?.setOnClickListener {
                TsNet.olvidarIntento(this); pintarTailscale()
            }
            btnNodoPropio?.visibility = android.view.View.VISIBLE
            btnNodoPropio?.text = "Reintentar el nodo propio"
            return
        }
        tvTailscale?.setOnClickListener(null)
        btnNodoPropio?.visibility =
            if (hayNodo) android.view.View.VISIBLE else android.view.View.GONE
        btnNodoPropio?.text =
            if (NetworkManager.usarTsnet) "Apagar el nodo propio" else "Usar nodo propio"

        // El nodo propio manda sobre lo demás: si está en marcha, es por donde
        // va el cluster, y enseñar el estado de la app de Tailscale ahí sería
        // hablar de otra cosa.
        if (NetworkManager.usarTsnet) {
            val dirs = try { TsNet.direcciones() } catch (e: Throwable) { "" }
            tvTailscale?.text = "Nodo propio en marcha.\n" +
                (if (dirs.isNotEmpty()) "Direcciones: $dirs\n" else "") +
                "\nEl cluster va por aquí: atraviesa el CGNAT de la operadora y " +
                "va cifrado, sin la app de Tailscale y sin enrutar el móvil entero."
            return
        }
        if (TsNet.arrancando) {
            tvTailscale?.text = "Levantando el nodo propio...\n\n" +
                                "La primera vez puede tardar un rato."
            return
        }

        val ts = Tailscale.estado()
        btnTsAbrir?.text = if (Tailscale.instalado(this)) "Abrir Tailscale"
                           else "Instalar Tailscale"
        // Decir SIEMPRE si el nodo propio está o no. Callárselo es lo que hace
        // que la pantalla parezca estar pidiendo la app de Tailscale cuando se
        // supone que va integrada: sin esta línea, el botón simplemente no
        // aparece y no hay forma de saber si es que no está o es que falla.
        val notaNodo = if (hayNodo)
            "\n\nEste móvil PUEDE ser su propio nodo, sin instalar Tailscale: " +
            "dale a «Usar nodo propio» aquí abajo."
        else
            "\n\nEl nodo propio no está disponible, así que hace falta la app de " +
            "Tailscale." +
            // El motivo, si se sabe. Sin esto lo único que queda es "no está
            // disponible", que no da un solo dato con el que averiguar nada. El
            // mensaje del enlazador suele decir exactamente qué pasa.
            (TsNet.errorCarga.let { if (it.isEmpty()) "" else "\n\nMotivo: $it" })
        if (!ts.activo) {
            miNombreTs = ""; nombreTsPedido = false
            tvTailscale?.text = (if (Tailscale.instalado(this))
                "Tailscale instalado pero sin conectar.\n\n" +
                "Ábrelo y activa el interruptor. Mientras esté apagado, los dos " +
                "móviles sólo se ven si están en la misma WiFi."
            else
                "La app de Tailscale no está instalada.\n\n" +
                "Hace falta para que los móviles se vean fuera de la misma WiFi: " +
                "tu operadora usa CGNAT y no hay puerto que abrir que lo arregle."
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
            "\nNombre: $miNombreTs   ← esto es lo que conviene dar al otro móvil"
        else ""
        tvTailscale?.text = "Conectado por la app de Tailscale.\n" +
            "Dirección: ${ts.direccion}$comoMeLlamo\n\n" +
            "Funciona igual desde cualquier red y va cifrado de punta a punta." +
            notaNodo
    }

    /**
     * Elegir el maestro de entre los aparatos del tailnet, sin teclear nada.
     *
     * "Buscar maestros en la red" no sirve aquí y no puede servir: va por
     * difusión UDP y la difusión no cruza VPNs. Lo que sí hay es la API de
     * Tailscale, que es HTTPS normal.
     */
    private fun elegirDeTailnet() {
        val k = Tailscale.clave(this)
        if (k.isEmpty()) { pedirClaveTailscale(); return }
        Toast.makeText(this, "Consultando tu tailnet...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val lista = Tailscale.aparatos(k)
                runOnUiThread {
                    if (lista.isEmpty()) {
                        Toast.makeText(this, "Tu tailnet no tiene aparatos",
                                       Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val etiquetas = lista.map {
                        "${it.nombre}  ${it.direccion}" +
                        (if (it.so.isNotEmpty()) "  (${it.so})" else "") +
                        (if (!it.enLinea) "  · desconectado" else "")
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("¿Cuál es el maestro?")
                        .setItems(etiquetas) { _, i ->
                            // El nombre antes que la dirección: la dirección de
                            // Tailscale es estable, pero si algún día cambia el
                            // nombre sigue resolviendo y esto no se entera.
                            val a = lista[i]
                            etMasterIp?.setText(
                                if (a.nombre.isNotEmpty()) a.nombre else a.direccion)
                        }
                        .setNegativeButton("Cerrar", null)
                        .setNeutralButton("Olvidar mi clave") { _, _ ->
                            Tailscale.olvidarClave(this)
                            Toast.makeText(this, "Clave borrada", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                // El mensaje de Tailscale.aparatos ya está escrito para leerse
                // tal cual, incluido el caso de la clave caducada.
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("No se ha podido consultar")
                        .setMessage(e.message ?: "Error desconocido")
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
            .setTitle("Clave de API de Tailscale")
            .setMessage(
                "Para listar tus aparatos hace falta una clave de API.\n\n" +
                "Se saca en login.tailscale.com → Settings → Keys → Generate " +
                "API key.\n\n" +
                "AVISO: esa clave permite LEER el inventario de tu tailnet " +
                "—nombres, direcciones, sistemas— a quien la tenga. Se guarda " +
                "sólo en este móvil y caduca a los 90 días.\n\n" +
                "No hace falta para usar el cluster: también puedes escribir la " +
                "dirección o el nombre del maestro a mano.")
            .setView(campo)
            .setPositiveButton("Guardar") { _, _ ->
                val k = campo.text?.toString()?.trim() ?: ""
                if (k.isEmpty()) return@setPositiveButton
                Tailscale.guardarClave(this, k)
                elegirDeTailnet()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pintarEstado() {
        pintarTailscale()
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
                "Tabla: $guardados de $tope (${"%.1f".format(pct)} %)" +
                (if (guardados >= tope) "  ¡LLENA, no se guarda nada nuevo!"
                 else if (pct >= 80) "  se está llenando" else "") + "\n"
            } else ""
            "Puntos recibidos: ${NetworkManager.puntosRecibidos.get()}\n" +
            (if (hilos > 0) "Este móvil: buscando con $hilos hilos\n"
             else "Este móvil: sólo recoge, no busca\n") + tabla + "\n"
        } else ""
        tvWorkers?.text = cab + (if (list.isEmpty()) "Ningún trabajador todavía"
                                 else "${list.size} trabajador(es)")
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
            "Repartirá el puzzle #$num" +
            (if (conKangaroo) " con Kangaroo." else " por bloques.") +
            "\nPara cambiarlo, elige otro en la pantalla Puzzle antes de iniciar."
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
            val est = if (w.pausado) "en pausa" else w.status
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
                        if (nuevo) "${w.device} parará en unos segundos"
                        else "${w.device} seguirá en unos segundos",
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
                return "Dirección en tu tailnet (la que vale desde cualquier red):\n" +
                       "  " + ts.split(",").joinToString("\n  ") { it.trim() } +
                       "\n\nY en esta WiFi:\n" +
                       NetworkManager.direccionesLocales()
                           .joinToString("\n") { "  ${it.first}  ${it.second}" }
            }
        }
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
