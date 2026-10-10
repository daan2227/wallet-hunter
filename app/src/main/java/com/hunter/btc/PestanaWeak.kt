package com.hunter.btc

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.math.BigInteger

/**
 * Auditoría de claves débiles, como PÁGINA de MainActivity.
 *
 * El estado y los hilos viven en [WeakController], un SINGLETON de proceso, no
 * en la Activity: así la auditoría sobrevive a que el sistema recree la pantalla
 * o mande la app a segundo plano (pasaba que salir a conceder un permiso a mitad
 * de búsqueda la dejaba en un estado inconsistente). Las vistas son anulables y
 * se vuelven a enlazar al reconstruir la pestaña; mientras no hay pantalla, la
 * lógica sigue (Kangaroo, red, guardado) y solo se saltan las actualizaciones de
 * UI. El WakeLock lo pone [WeakService].
 *
 * Kangaroo necesita la clave pública Y un rango donde esté la privada. En una
 * dirección cualquiera la clave puede estar en cualquier punto de [1, 2^256] y
 * encontrarla es justo lo que protege Bitcoin. Lo único auditable es lo MAL
 * generado: carteras cuya privada salió de poca entropía y cayó en un rango
 * pequeño. Si su clave pública está publicada (la dirección gastó), Kangaroo
 * sobre [1, 2^bits) la encuentra en √ del rango. Sirve para revisar TUS propias
 * direcciones, no las de terceros.
 */
internal fun MainActivity.buildWeakTab(): ScrollView = WeakController.construir(this)

object WeakController {

    private const val BITS_MIN = 20
    private const val BITS_MAX = 80
    private const val BITS_SUG = 50
    private const val OPS_POR_SEG = 7_000_000.0
    private const val UMBRAL_LISTA = 2000
    // Cuántas direcciones se resuelven en la red a la vez. Moderado para no
    // toparse con el límite de ritmo del explorador.
    private const val NUCLEOS_RED = 5
    // Por encima de esto, el fichero NO se carga a RAM: se lee desde disco a
    // medida que la auditoría avanza (para ficheros de cientos de MB o GB).
    private const val UMBRAL_STREAM_BYTES = 80L * 1024 * 1024
    private const val FIN = "\u0000FIN"   // centinela de fin de la cola en streaming
    // Valores del deslizador de "max seconds per key": 0 = sin tope, y luego
    // saltos útiles (no una regla lineal con cientos de posiciones inútiles).
    private val TOPES = intArrayOf(0, 1, 2, 3, 5, 10, 15, 30, 60, 120, 300)

    private val h = Handler(Looper.getMainLooper())
    // La Activity actual (para el selector de archivos, que necesita una); el
    // resto usa el contexto de aplicación, que no muere con la pantalla.
    private var act: MainActivity? = null
    private var appCtx: Context? = null
    private fun dp(v: Int) = ((appCtx?.resources?.displayMetrics?.density ?: 3f) * v).toInt()

    private var claves: List<String> = emptyList()
    private var indice = 0
    private var bits = 64
    private var presupuesto = 2.0
    private var topeSeg = 0.0
    private var claveInicioMs = 0L
    private var ritmoDisp = OPS_POR_SEG
    private var corriendo = false
    private var hallados = 0
    // Desglose del resultado de resolver cada entrada: direcciones sin clave
    // pública publicada (nunca gastaron → nada que auditar) y las que no se
    // pudieron comprobar por red. Son AtomicInteger porque los tocan los hilos.
    private val conPubkey = java.util.concurrent.atomic.AtomicInteger(0)
    private val sinPubkey = java.util.concurrent.atomic.AtomicInteger(0)
    private val fallosRed = java.util.concurrent.atomic.AtomicInteger(0)

    private var cargadas: List<String> = emptyList()

    // Prefetch de claves públicas: varios hilos resuelven las direcciones de la
    // cola por adelantado mientras Kangaroo cracea la actual. resueltas[i] =
    // clave comprimida, "" = esa línea no da clave; ausencia = resolviéndose.
    private val resueltas = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val prefetchIdx = java.util.concurrent.atomic.AtomicInteger(0)
    private val generacion = java.util.concurrent.atomic.AtomicInteger(0)

    // Modo streaming (ficheros enormes): las claves no viven en RAM; un hilo
    // productor las lee del disco y las va dejando resueltas en una cola
    // acotada, y el consumidor (el bucle de crackeo) las recoge. Memoria
    // limitada a la cola, sea el fichero de 10 MB o de 10 GB.
    private var streamMode = false
    private var streamUri: Uri? = null
    private var streamTotal = 0   // 0 = desconocido (no se cuenta un fichero enorme)
    private val colaStream = java.util.concurrent.LinkedBlockingQueue<String>(1024)
    // Pipeline del streaming: un lector mete claves CRUDAS en colaRaw, y varios
    // resolutores (NUCLEOS_RED) las resuelven en paralelo a colaStream. Así los
    // lookups de direcciones van en paralelo también en streaming.
    private val colaRaw = java.util.concurrent.LinkedBlockingQueue<String>(1024)
    private val lectorVivo = java.util.concurrent.atomic.AtomicBoolean(false)
    private val resolviendo = java.util.concurrent.atomic.AtomicInteger(0)
    // El productor está vivo: si muere (fin de fichero o error) y la cola se
    // vacía, el consumidor da por terminado en vez de sondear null para siempre.
    private val productorVivo = java.util.concurrent.atomic.AtomicBoolean(false)

    private val reClaves = Regex(
        "0[23][0-9a-fA-F]{64}" +
        "|04[0-9a-fA-F]{128}" +
        "|bc1[a-z0-9]{6,87}" +
        "|[13][a-km-zA-HJ-NP-Z1-9]{25,34}")

    // Vistas anulables: se re-enlazan al reconstruir la pestaña y valen null
    // mientras no hay pantalla (todas las escrituras van con ?. ).
    private var etClaves: EditText? = null
    private var tvCargadas: TextView? = null
    private var sbBits: SeekBar? = null
    private var tvBits: TextView? = null
    private var tvEstim: TextView? = null
    private var sbPresu: SeekBar? = null
    private var tvPresu: TextView? = null
    private var sbTope: SeekBar? = null
    private var tvTope: TextView? = null
    private var tvEstado: TextView? = null
    private var btnCsv: android.view.View? = null
    private var barraProg: BarraProgreso? = null
    private var vacioWeak: android.view.View? = null
    private var btn: Button? = null
    private var statsCard: LinearLayout? = null
    private var tvSpeed: TextView? = null
    private var tvSpeedU: TextView? = null
    private var tvPeak: TextView? = null
    private var chart: SpeedChartView? = null
    private var tvOps: TextView? = null
    private var tvTime: TextView? = null
    private var tvProg: TextView? = null
    private var tvKeys: TextView? = null

    private val tickToken = Any()
    private var intervaloTick = 1000L   // ms entre sondeos; se adapta por clave
    private var inicioMs = 0L
    private var opsPrevias = 0.0
    private var presuActual = 0.0
    private var ultTotalOps = 0.0
    private var ultMs = 0L
    private var pico = 0.0
    private var ultChartMs = 0L

    /** Construye (o reconstruye) la vista y la enlaza al estado actual. Si hay
     *  una auditoría en curso, la restaura sin reiniciarla. */
    fun construir(a: MainActivity): ScrollView {
        act = a
        appCtx = a.applicationContext
        if (!corriendo) ritmoDisp = try {
            a.getSharedPreferences("weakkey", Context.MODE_PRIVATE)
                .getFloat("ritmo", OPS_POR_SEG.toFloat()).toDouble()
        } catch (e: Throwable) { OPS_POR_SEG }

        fun rotulo(t: String) = TextView(a).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context); setPadding(0, 0, 0, dp(6))
        }

        val scroll = ScrollView(a)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(a).apply {
            text = "Weak-key audit"
            textSize = 24f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
        })
        root.addView(TextView(a).apply {
            text = "Kangaroo over a small range for public keys that have spent. " +
                   "Finds only keys generated with too little entropy (a private key " +
                   "that landed in [1, 2^bits)). A properly generated key will never " +
                   "show up here. Use it to check your own addresses."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f)
            setPadding(0, dp(8), 0, dp(16))
        })

        root.addView(rotulo("Public keys (02…/03…/04…) or spent addresses, one per line"))
        val vClaves = EditText(a).apply {
            hint = "02abc…\n03def…\n1Address…"
            setHorizontallyScrolling(false); maxLines = 8; minLines = 4
            gravity = Gravity.TOP or Gravity.START
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.mono(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        etClaves = vClaves
        root.addView(vClaves)

        root.addView(Ui.ghost(a, "Load CSV / text file", AppTheme.TXT_PRI).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            setOnClickListener { elegirCsv() }
            btnCsv = this
        })
        val vCargadas = TextView(a).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.medium(context); visibility = android.view.View.GONE
            setPadding(dp(2), dp(10), dp(2), 0)
            setOnClickListener {
                cargadas = emptyList(); streamMode = false; streamUri = null
                mostrarResumenCargadas()
                aviso("List cleared.")
            }
        }
        tvCargadas = vCargadas
        root.addView(vCargadas)

        val cabRango = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(2))
        }
        cabRango.addView(rotulo("Search range").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val vBits = TextView(a).apply {
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
        }
        tvBits = vBits
        cabRango.addView(vBits)
        root.addView(cabRango)

        val vSb = SeekBar(a).apply {
            max = BITS_MAX - BITS_MIN
            progress = (if (corriendo) bits else BITS_SUG) - BITS_MIN
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { estimar() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sbBits = vSb
        root.addView(vSb)
        val ejes = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, 0)
        }
        ejes.addView(TextView(a).apply {
            text = "$BITS_MIN"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ejes.addView(TextView(a).apply {
            text = "suggested $BITS_SUG"; textSize = AppTheme.SP_MICRO
            setTextColor(AppTheme.TXT_MUTED); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ejes.addView(TextView(a).apply {
            text = "$BITS_MAX"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(ejes)

        val vEstim = TextView(a).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.3f)
            setPadding(0, dp(10), 0, 0)
        }
        tvEstim = vEstim
        root.addView(vEstim)

        // Deslizadores de presupuesto y de tope por clave, como el del rango.
        fun bloqueSlider(titulo: String, maxProg: Int, prog0: Int,
                         minTxt: String, maxTxt: String): Pair<SeekBar, TextView> {
            val cab = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, dp(2))
            }
            cab.addView(rotulo(titulo).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val tv = TextView(a).apply {
                textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context)
            }
            cab.addView(tv)
            root.addView(cab)
            val sb = SeekBar(a).apply {
                max = maxProg; progress = prog0
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { estimar() }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            root.addView(sb)
            val ax = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, 0)
            }
            ax.addView(TextView(a).apply {
                text = minTxt; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            ax.addView(TextView(a).apply {
                text = maxTxt; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            root.addView(ax)
            return sb to tv
        }

        val presuProg0 = if (corriendo) Math.round((presupuesto - 2.0) / 0.5).toInt().coerceIn(0, 4) else 0
        val (vSbPresu, vTvPresu) = bloqueSlider("Budget × √ (surer, slower →)", 4, presuProg0, "2.0", "4.0")
        sbPresu = vSbPresu; tvPresu = vTvPresu

        val topeProg0 = if (corriendo && topeSeg > 0)
            TOPES.indexOfFirst { it.toDouble() >= topeSeg }.let { if (it < 0) TOPES.size - 1 else it } else 0
        val (vSbTope, vTvTope) = bloqueSlider("Max seconds per key", TOPES.size - 1, topeProg0, "no limit", "${TOPES.last()} s")
        sbTope = vSbTope; tvTope = vTvTope

        val alEscribir = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { estimar() }
            override fun beforeTextChanged(s: CharSequence?, a2: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a2: Int, b: Int, c: Int) {}
        }
        vClaves.addTextChangedListener(alEscribir)

        val vEstado = TextView(a).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f)
            setPadding(0, dp(16), 0, dp(16))
        }
        tvEstado = vEstado
        root.addView(vEstado)

        val vBtn = Button(a).apply {
            text = if (corriendo) "Stop" else "Start audit"; textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.ON_ACCENT); typeface = AppTheme.bold(context)
            isAllCaps = false; stateListAnimator = null
            background = Ui.botonAccento(context, AppTheme.R_INNER, AppTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { Ui.pulso(this); if (corriendo) parar("Stopped.") else arrancar() }
        }
        btn = vBtn
        root.addView(vBtn)

        val vStats = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            visibility = if (corriendo) android.view.View.VISIBLE else android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        }
        statsCard = vStats
        vStats.addView(TextView(a).apply {
            text = "Performance"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context)
            setPadding(0, 0, 0, dp(12))
        })
        val filaVel = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        }
        val vSpeed = TextView(a).apply {
            text = "0"; textSize = AppTheme.SP_DISPLAY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.display(context); letterSpacing = -0.04f
        }
        tvSpeed = vSpeed
        filaVel.addView(vSpeed)
        val vSpeedU = TextView(a).apply {
            text = "keys/s"; textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); bottomMargin = dp(4) }
        }
        tvSpeedU = vSpeedU
        filaVel.addView(vSpeedU)
        vStats.addView(filaVel)
        val vPeak = TextView(a).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(10), 0, dp(4))
        }
        tvPeak = vPeak
        vStats.addView(vPeak)
        val vChart = SpeedChartView(a).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
                .apply { topMargin = dp(8) }
        }
        chart = vChart
        vStats.addView(vChart)

        fun mini(label: String, tv: TextView) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(a).apply {
                text = label; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context)
            })
            addView(tv)
        }
        fun valor(ini: String) = TextView(a).apply {
            text = ini; textSize = AppTheme.SP_FIGURE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context); letterSpacing = -0.02f
            setPadding(0, dp(6), 0, 0)
        }
        val vOps = valor("0"); tvOps = vOps
        val vTime = valor("00:00:00"); tvTime = vTime
        val vProg = valor("—"); tvProg = vProg
        val vKeys = valor("—"); tvKeys = vKeys
        if (corriendo) vKeys.text = cuentaTexto()
        val r1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) } }
        val r2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        r1.addView(mini("Operations", vOps).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        r1.addView(mini("Time", vTime))
        r2.addView(mini("Key budget", vProg).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        r2.addView(mini("Keys · found", vKeys))
        vStats.addView(r1); vStats.addView(r2)
        // Barra de progreso general: claves hechas / total. Sólo tiene sentido
        // aquí, donde hay un total real (una lista finita).
        val vBarra = BarraProgreso(a).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5))
                .apply { topMargin = dp(16) }
        }
        barraProg = vBarra
        vStats.addView(vBarra)
        if (corriendo) actualizarBarra()
        root.addView(vStats)

        // Estado vacío: cuando aún no se ha auditado nada, en vez de dejar un
        // hueco bajo el botón. Al arrancar se oculta y aparece la tarjeta de
        // estadísticas en su lugar.
        val vVacio = Ui.estadoVacio(a, R.drawable.ic_target,
            "Nothing audited yet",
            "Paste a public key (02/03/04) or a spent address above, or load a " +
            "file. Then press Start audit.").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            visibility = if (corriendo) android.view.View.GONE else android.view.View.VISIBLE
        }
        vacioWeak = vVacio
        root.addView(vVacio)

        mostrarResumenCargadas()   // restaura el chip de lista grande si la hay
        bloquear(corriendo)        // si vuelve corriendo, deja los parámetros bloqueados
        // Reanudar tras cerrar la app: si hay un checkpoint con su lista y el
        // editor está vacío, la recarga y avisa de que puede continuar.
        if (!corriendo && cargadas.isEmpty() && !streamMode) {
            val p = prefsWeak()
            val ckClaves = p?.getString("ck_claves", "") ?: ""
            val ckIdx = p?.getInt("ck_indice", 0) ?: 0
            val ckTot = p?.getInt("ck_total", 0) ?: 0
            if (ckClaves.isNotBlank() && ckIdx > 0 && (etClaves?.text?.isEmpty() != false)) {
                etClaves?.setText(ckClaves)
                tvEstado?.text = "Paused audit: $ckIdx/$ckTot checked. Press Start audit to resume " +
                                 "from key ${ckIdx + 1}, or clear the list to start over."
            }
        }
        estimar()
        return scroll
    }

    /** MainActivity.onDestroy: suelta las vistas de esa Activity (para no
     *  filtrarla) pero NO para la auditoría — sigue viva, la mantiene
     *  WeakService y sus ticks corren en el handler del proceso. */
    fun desmontar(a: MainActivity) {
        if (act !== a) return
        act = null
        etClaves = null; tvCargadas = null; sbBits = null; tvBits = null; tvEstim = null
        sbPresu = null; tvPresu = null; sbTope = null; tvTope = null; tvEstado = null; btn = null
        btnCsv = null; barraProg = null; vacioWeak = null
        statsCard = null; tvSpeed = null; tvSpeedU = null; tvPeak = null; chart = null
        tvOps = null; tvTime = null; tvProg = null; tvKeys = null
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun escala(v: Double): Pair<String, String> = when {
        v >= 1e9 -> "%.2f".format(v / 1e9) to "G op"
        v >= 1e6 -> "%.2f".format(v / 1e6) to "M op"
        v >= 1e3 -> "%.1f".format(v / 1e3) to "K op"
        else     -> "%.0f".format(v) to "op"
    }
    private fun reloj(seg: Long): String {
        val h1 = seg / 3600; val m = (seg % 3600) / 60; val s = seg % 60
        return "%02d:%02d:%02d".format(h1, m, s)
    }

    /** Presupuesto del deslizador: de 2,0 a 4,0 en pasos de 0,5. */
    private fun budgetActual(): Double = 2.0 + (sbPresu?.progress ?: 0) * 0.5
    /** Tope por clave del deslizador, en segundos (0 = sin tope). */
    private fun topeActual(): Double =
        TOPES[(sbTope?.progress ?: 0).coerceIn(0, TOPES.size - 1)].toDouble()

    private fun estimar() {
        val sb = sbBits ?: return   // sin pantalla no hay nada que estimar
        val b = sb.progress + BITS_MIN
        tvBits?.text = "$b bits"
        val c = budgetActual()
        tvPresu?.text = "×${fmt(c)}"
        val ops = c * Math.pow(2.0, b / 2.0)
        val seg = ops / ritmoDisp
        fun log2(x: Double) = "2^%.1f".format(Math.log(x) / Math.log(2.0))
        fun tiempo(s: Double) = when {
            s < 1     -> "under a second"
            s < 60    -> "~${s.toInt()} s"
            s < 3600  -> "~${(s / 60).toInt()} min"
            s < 86400 -> "~${(s / 3600).toInt()} h"
            s < 3.15e7 -> "~${(s / 86400).toInt()} days"
            s < 3.15e10 -> "~${(s / 3.15e7).toInt()} years"
            else -> "millennia"
        }
        val tope = topeActual()
        tvTope?.text = if (tope <= 0) "no limit" else "${tope.toInt()} s"
        val porClave = if (tope > 0) Math.min(seg, tope) else seg
        val (rv, ru) = escala(ritmoDisp)
        var txt = "≈ ${log2(ops)} operations per key · ${tiempo(seg)} at ~$rv $ru/s.\n" +
            "Only finds keys whose private value is below 2^$b."
        val n = contarClaves()
        if (n > 1) {
            // Cada clave tiene un suelo de ~50 ms de sondeo aunque el crackeo sea
            // instantáneo; en lotes grandes de rango bajo eso es lo que manda.
            val porClaveReal = Math.max(porClave, 0.05)
            txt += "\nBatch: ${"%,d".format(n)} keys" +
                (if (tope > 0) " · ${tope.toInt()} s cap each" else "") +
                " ≈ ${tiempo(porClaveReal * n)} total."
        }
        tvEstim?.text = txt
    }

    private fun contarClaves(): Int {
        val lineas = (etClaves?.text?.toString() ?: "").split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        return LinkedHashSet<String>(lineas).apply { addAll(cargadas) }.size
    }

    private fun comprimida(k: String): String? = when {
        Regex("^0[23][0-9a-fA-F]{64}$").matches(k) -> k.lowercase()
        Regex("^04[0-9a-fA-F]{128}$").matches(k) ->
            (if (BigInteger(k.substring(66), 16).testBit(0)) "03" else "02") +
            k.substring(2, 66).lowercase()
        else -> null
    }

    /** Resuelve una entrada a su clave pública comprimida, o "" si no da clave.
     *  De paso lleva la cuenta de por qué no la da: dirección sin clave pública
     *  publicada (nunca gastó) o fallo de red. Lo llaman los hilos resolutores. */
    private fun resolver(bruta: String): String {
        comprimida(bruta)?.let { conPubkey.incrementAndGet(); return it }   // ya es una clave pública
        if (bruta.startsWith("1") || bruta.startsWith("3") || bruta.startsWith("bc1", true)) {
            val r = try { PubKeyFinder.buscar(appCtx, bruta, false) }
                    catch (e: Throwable) { PubKeyFinder.Resultado.SinRed }
            return when (r) {
                is PubKeyFinder.Resultado.Encontrada ->
                    comprimida(r.pubHex)?.also { conPubkey.incrementAndGet() }
                        ?: run { sinPubkey.incrementAndGet(); "" }
                is PubKeyFinder.Resultado.SinRed -> { fallosRed.incrementAndGet(); "" }
                else -> { sinPubkey.incrementAndGet(); "" }   // NoRevelada / Publicada
            }
        }
        return ""   // ni clave ni dirección
    }

    /** Resumen del desglose para los avisos, solo las partes que importan. */
    private fun desglose(): String {
        val cp = conPubkey.get(); val sp = sinPubkey.get(); val fr = fallosRed.get()
        return (if (cp > 0) " · ${"%,d".format(cp)} with pubkey" else "") +
               (if (sp > 0) " · ${"%,d".format(sp)} no pubkey" else "") +
               (if (fr > 0) " · ${"%,d".format(fr)} unreachable" else "")
    }

    private fun elegirCsv() {
        val a = act ?: return
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try { a.startActivityForResult(i, a.REQ_WEAK_CSV) }
        catch (e: Exception) { aviso("No file picker available on this device.") }
    }

    /** Tamaño del fichero de una URI, o -1 si no se sabe. */
    private fun tamano(uri: Uri): Long = try {
        appCtx?.contentResolver?.query(uri, arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null)?.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (it.moveToFirst() && idx >= 0 && !it.isNull(idx)) it.getLong(idx) else -1L
        } ?: -1L
    } catch (e: Throwable) { -1L }

    /** Lo llama MainActivity.onActivityResult cuando vuelve el selector. */
    fun onCsvResult(uri: Uri) {
        val ctx = appCtx ?: return
        val bytes = tamano(uri)
        if (bytes > UMBRAL_STREAM_BYTES) {
            // Fichero enorme: NO se carga a RAM. Se leerá desde disco durante la
            // auditoría. Se persiste el permiso por si la Activity se recrea.
            try {
                ctx.contentResolver.takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Throwable) {}
            streamMode = true; streamUri = uri
            // Total APROXIMADO por tamaño ÷ longitud media de línea (muestra de 50
            // líneas). Solo para el progreso/%, no hace falta que sea exacto.
            streamTotal = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { br ->
                    var suma = 0L; var n = 0; var l = br.readLine()
                    while (l != null && n < 50) { suma += l.length + 1; n++; l = br.readLine() }
                    if (n > 0 && suma > 0) (bytes / (suma / n)).toInt() else 0
                } ?: 0
            } catch (e: Throwable) { 0 }
            cargadas = emptyList()
            mostrarResumenCargadas()
            aviso("Huge file (${"%,d".format(bytes / 1024 / 1024)} MB" +
                  (if (streamTotal > 0) ", ~${"%,d".format(streamTotal)} keys" else "") +
                  "): keys will be read from disk as the audit runs. Start when ready.")
            return
        }
        streamMode = false; streamUri = null   // fichero pequeño: modo normal
        aviso("Reading file…")
        Thread {
            val vistos = LinkedHashSet<String>()
            var error: String? = null
            var lineas = 0L
            try {
                val ins = ctx.contentResolver.openInputStream(uri)
                if (ins == null) error = "Could not open the file."
                else ins.bufferedReader().use { br ->
                    br.forEachLine { linea ->
                        for (m in reClaves.findAll(linea)) vistos.add(m.value)
                        if (++lineas % 20000L == 0L) {
                            val n = vistos.size
                            h.post { aviso("Reading file… ${"%,d".format(n)} found") }
                        }
                    }
                }
            } catch (e: Throwable) {
                error = "Could not read the file (${e.javaClass.simpleName})."
            }
            val encontrados = vistos.toList()
            h.post {
                if (error != null) { aviso(error!!); return@post }
                if (encontrados.isEmpty()) {
                    aviso("No public keys or addresses found in the file."); return@post
                }
                val previas = (etClaves?.text?.toString() ?: "").split('\n')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val visibles = LinkedHashSet<String>(previas).apply { addAll(encontrados) }
                if (visibles.size <= UMBRAL_LISTA) {
                    etClaves?.setText(visibles.joinToString("\n"))
                    aviso("Loaded ${encontrados.size} from file · ${visibles.size} in the list.")
                } else {
                    cargadas = LinkedHashSet<String>(cargadas).apply { addAll(encontrados) }.toList()
                    mostrarResumenCargadas()
                    aviso("Large list: ${"%,d".format(encontrados.size)} loaded, kept out of the editor to stay responsive.")
                }
            }
        }.start()
    }

    private fun mostrarResumenCargadas() {
        when {
            streamMode -> {
                tvCargadas?.text = "file · streamed from disk during the audit · tap to clear"
                tvCargadas?.visibility = android.view.View.VISIBLE
            }
            cargadas.isEmpty() -> tvCargadas?.visibility = android.view.View.GONE
            else -> {
                tvCargadas?.text = "file · ${"%,d".format(cargadas.size)} keys loaded, kept out of the editor · tap to clear"
                tvCargadas?.visibility = android.view.View.VISIBLE
            }
        }
        estimar()
    }

    /** Bloquea (o desbloquea) los parámetros de configuración mientras corre la
     *  auditoría: no se puede tocar nada hasta pulsar Stop. */
    private fun bloquear(corriendo: Boolean) {
        val activo = !corriendo
        etClaves?.isEnabled = activo
        sbBits?.isEnabled = activo
        sbPresu?.isEnabled = activo
        sbTope?.isEnabled = activo
        btnCsv?.isEnabled = activo
        btnCsv?.alpha = if (activo) 1f else 0.4f
        tvCargadas?.isEnabled = activo   // el chip que limpia la lista
    }

    /** Firma de la lista actual, para no reanudar un checkpoint de OTRA lista. */
    private fun firmaLista(): String =
        if (streamMode) "stream:${streamUri}"
        else "list:${claves.size}:${claves.joinToString("\n").hashCode()}"

    private fun prefsWeak() = appCtx?.getSharedPreferences("weakkey", Context.MODE_PRIVATE)

    /** Guarda dónde va la auditoría (sólo en modo lista; el streaming no reanuda). */
    private fun guardarCheckpoint() {
        if (streamMode) return
        prefsWeak()?.edit()
            ?.putString("ck_firma", firmaLista())
            ?.putInt("ck_indice", indice)
            ?.putInt("ck_hallados", hallados)
            ?.putInt("ck_total", claves.size)
            // La lista entera sólo si es manejable, para reanudar tras cerrar la app.
            ?.putString("ck_claves", if (claves.size in 1..3000) claves.joinToString("\n") else "")
            ?.apply()
    }
    private fun limpiarCheckpoint() {
        prefsWeak()?.edit()?.remove("ck_firma")?.remove("ck_indice")
            ?.remove("ck_hallados")?.remove("ck_total")?.remove("ck_claves")?.apply()
    }
    /** Índice guardado si el checkpoint es de ESTA misma lista; 0 si no. */
    private fun checkpointDe(): Int {
        val p = prefsWeak() ?: return 0
        if (p.getString("ck_firma", "") != firmaLista()) return 0
        return p.getInt("ck_indice", 0)
    }

    private fun arrancar() {
        val sb = sbBits ?: return
        bits = sb.progress + BITS_MIN
        presupuesto = budgetActual()
        topeSeg = topeActual()
        if (bits !in 8..80) { aviso("Range bits: between 8 and 80."); return }
        if (streamMode && streamUri != null) {
            // Fichero enorme: las claves se leen del disco, no hay lista en RAM.
            claves = emptyList()
            resetContadores()
            programarServicio()
            arrancarProductorStream()
            siguiente()
            return
        }
        val lineas = (etClaves?.text?.toString() ?: "").split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        val cola = LinkedHashSet<String>(lineas).apply { addAll(cargadas) }
        if (cola.isEmpty()) { aviso("Paste a key/address, or load a file."); return }
        claves = cola.toList()
        // Reanudar: si hay un checkpoint de ESTA lista, seguir desde donde quedó.
        val ck = checkpointDe()
        val reanudar = ck in 1 until claves.size
        val h0 = if (reanudar) (prefsWeak()?.getInt("ck_hallados", 0) ?: 0) else 0
        resetContadores(if (reanudar) ck else 0, h0)
        tvKeys?.text = cuentaTexto()
        programarServicio()
        arrancarPrefetch()
        if (reanudar) aviso("Resuming from key ${ck + 1}/${claves.size} · $h0 found so far.")
        siguiente()
    }

    /** Arranca el servicio de primer plano (keep-alive) SOLO si la auditoría
     *  sigue viva tras un momento. Una auditoría que termina al instante (p. ej.
     *  un fichero de direcciones sin red: todas se saltan) no lo arranca, y así
     *  no hay carrera entre arrancar el servicio y pararlo —que tumbaba la app
     *  con ForegroundServiceDidNotStartInTimeException—. */
    private fun programarServicio() {
        h.postDelayed({ if (corriendo) appCtx?.let { WeakService.iniciar(it) } }, 1500)
    }

    private fun resetContadores(desdeIndice: Int = 0, desdeHallados: Int = 0) {
        indice = desdeIndice; hallados = desdeHallados; corriendo = true
        conPubkey.set(0); sinPubkey.set(0); fallosRed.set(0)
        inicioMs = System.currentTimeMillis()
        opsPrevias = 0.0; ultTotalOps = 0.0; ultMs = inicioMs; pico = 0.0; ultChartMs = 0L
        chart?.reset()
        tvSpeed?.text = "0"; tvPeak?.text = ""; tvOps?.text = "0"; tvTime?.text = "00:00:00"
        tvProg?.text = "—"; tvKeys?.text = "0 · 0"
        statsCard?.visibility = android.view.View.VISIBLE
        vacioWeak?.visibility = android.view.View.GONE
        btn?.text = "Stop"
        barraProg?.set(0f)
        bloquear(true)
    }

    /** Productor del modo streaming, en pipeline: un LECTOR lee el fichero y mete
     *  claves crudas en colaRaw; NUCLEOS_RED RESOLUTORES las resuelven en paralelo
     *  (los lookups de direcciones ya no van de una en una) y dejan la clave
     *  pública en colaStream. Todo con colas acotadas (backpressure), así la
     *  memoria queda limitada por grande que sea el fichero. */
    private fun arrancarProductorStream() {
        colaStream.clear(); colaRaw.clear()
        val gen = generacion.incrementAndGet()
        productorVivo.set(true); lectorVivo.set(true); resolviendo.set(NUCLEOS_RED)
        val ctx = appCtx; val uri = streamUri
        val ms = java.util.concurrent.TimeUnit.MILLISECONDS
        // Lector.
        Thread {
            try {
                // Buffer de 1 MB: menos viajes al proveedor de archivos (SAF) que
                // el de 8 KB por defecto, que en ficheros de GB puede atascarse.
                ctx?.contentResolver?.openInputStream(uri!!)?.reader()?.buffered(1 shl 20)?.use { br ->
                    var linea = br.readLine()
                    while (linea != null && corriendo && generacion.get() == gen) {
                        for (m in reClaves.findAll(linea)) {
                            if (!corriendo || generacion.get() != gen) break
                            var puesto = false
                            while (corriendo && generacion.get() == gen && !puesto)
                                puesto = colaRaw.offer(m.value, 200, ms)
                        }
                        linea = br.readLine()
                    }
                }
            } catch (e: Throwable) {}
            lectorVivo.set(false)
        }.apply { isDaemon = true; start() }
        // Resolutores en paralelo.
        repeat(NUCLEOS_RED) {
            Thread {
                try {
                    while (corriendo && generacion.get() == gen) {
                        val bruta = try { colaRaw.poll(200, ms) } catch (e: Throwable) { null }
                        if (bruta == null) {
                            if (!lectorVivo.get() && colaRaw.isEmpty()) break
                            continue
                        }
                        val pub = resolver(bruta.removePrefix("0x"))
                        var puesto = false
                        while (corriendo && generacion.get() == gen && !puesto)
                            puesto = colaStream.offer(pub, 200, ms)
                    }
                } catch (e: Throwable) {}
                // El último resolutor marca el fin del productor y pone el centinela.
                if (resolviendo.decrementAndGet() == 0) {
                    productorVivo.set(false)
                    try { if (corriendo && generacion.get() == gen) colaStream.offer(FIN) } catch (e: Throwable) {}
                }
            }.apply { isDaemon = true; start() }
        }
    }

    /** Lanza [NUCLEOS_RED] hilos que resuelven la cola por adelantado. */
    private fun arrancarPrefetch() {
        resueltas.clear()
        prefetchIdx.set(indice)   // al reanudar, no re-resolver las ya hechas
        val gen = generacion.incrementAndGet()
        val total = claves.size
        val cola = claves
        repeat(NUCLEOS_RED) {
            Thread {
                while (corriendo && generacion.get() == gen) {
                    val i = prefetchIdx.getAndIncrement()
                    if (i >= total) break
                    val pub = resolver(cola[i].removePrefix("0x"))
                    if (generacion.get() == gen) resueltas[i] = pub
                }
            }.apply { isDaemon = true; start() }
        }
    }

    private fun parar(motivo: String, terminado: Boolean = false) {
        corriendo = false
        h.removeCallbacksAndMessages(null)
        appCtx?.let { WeakService.parar(it) }   // suelta el primer plano y el WakeLock
        try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
        try {
            appCtx?.getSharedPreferences("weakkey", Context.MODE_PRIVATE)?.edit()
                ?.putFloat("ritmo", ritmoDisp.toFloat())?.apply()
        } catch (e: Throwable) {}
        // Al terminar la lista, borra el checkpoint; al parar a mano, lo guarda
        // para poder continuar desde la misma clave.
        if (terminado) limpiarCheckpoint() else guardarCheckpoint()
        estimar()
        btn?.text = "Start audit"
        bloquear(false)
        aviso(motivo + desglose() +
              (if (hallados > 0) "  ·  $hallados key(s) found → in the finds vault." else ""))
    }

    private fun siguiente() {
        if (!corriendo) return
        if (!streamMode && indice >= claves.size) {
            parar("Done: ${claves.size} key(s) checked.", terminado = true)
            return
        }
        if (!streamMode && indice % 5 == 0) guardarCheckpoint()   // por si se corta
        tvTime?.text = reloj((System.currentTimeMillis() - inicioMs) / 1000)
        recoger()
    }

    /** Recoge la siguiente clave pública ya resuelta (de la prefetch en modo
     *  lista, o de la cola del productor en modo streaming) y la manda a crackear.
     *  Las que no dan clave ("") se saltan en un BUCLE (no recursión: una lista
     *  de miles de saltos seguidos reventaría la pila). "" = sin clave. */
    private fun recoger() {
        if (!corriendo) return
        // Se procesan como mucho unos cuantos saltos por llamada y luego se cede
        // el hilo de pantalla (h.post): si no, con un fichero lleno de líneas que
        // no dan clave el bucle no soltaba nunca el hilo y la app se congelaba.
        var procesados = 0
        while (true) {
            if (!streamMode && indice >= claves.size) {   // fin de la lista
                parar("Done: ${claves.size} key(s) checked.", terminado = true); return
            }
            val pub: String? = if (streamMode) colaStream.poll() else resueltas[indice]
            if (pub == null) {
                // En streaming, si el productor ya terminó y la cola está vacía,
                // hemos acabado; si no, es que va por detrás: reintentar.
                if (streamMode && !productorVivo.get() && colaStream.isEmpty()) {
                    parar("Done: ${"%,d".format(indice)} key(s) checked.", terminado = true); return
                }
                val msg = if (streamMode) "Streaming from disk… ${"%,d".format(indice)} checked"
                          else "Key ${indice + 1}/${claves.size}: looking up its public key…"
                aviso(msg + desglose() +
                      (if (hallados > 0) "\n$hallados weak key(s) found so far" else ""))
                tvTime?.text = reloj((System.currentTimeMillis() - inicioMs) / 1000)
                h.postDelayed({ recoger() }, if (streamMode) 40 else 80)
                return
            }
            if (streamMode && pub == FIN) { parar("Done: ${"%,d".format(indice)} key(s) checked."); return }
            if (pub.isEmpty()) {   // esta línea no da clave: saltar
                indice++
                if (++procesados >= 512) {   // ceder el hilo y continuar
                    tvKeys?.text = cuentaTexto()
                    actualizarBarra()
                    tvTime?.text = reloj((System.currentTimeMillis() - inicioMs) / 1000)
                    h.post { recoger() }; return
                }
                continue
            }
            tvKeys?.text = cuentaTexto()
            actualizarBarra()
            lanzar(pub)
            return
        }
    }

    private fun lanzar(pub: String) {
        val ctx = appCtx ?: return
        val fin = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val ini = BigInteger.ONE
        try { if (HunterEngine.kangarooRunning()) HunterEngine.kangarooStop() } catch (e: Throwable) {}
        val nucleos = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // Canguros por hilo según el rango: cada canguro cuesta una
        // multiplicación EC de arranque (~75 µs). En rangos pequeños la búsqueda
        // es cortísima, así que 1024 (≈8192 canguros) hacía que el arranque
        // costara más que la búsqueda entera. Se escala al rango (el motor
        // clampa a ≥16). Menos canguros también mejoran la detección de colisión.
        val porHilo = when {
            bits <= 36 -> 16
            bits <= 44 -> 64
            bits <= 52 -> 256
            else       -> 1024
        }
        // Ruta vacía: el motor NO carga ni guarda la tabla en disco. El weak no
        // reanuda clave a clave (cada una es independiente y rápida), así que
        // ese I/O por clave era pura sobrecarga.
        val ok = try {
            HunterEngine.kangarooStart(pub, ini.toString(16), fin.toString(16),
                nucleos, porHilo, "", HunterEngine.topeTablaBits(ctx))
        } catch (e: Throwable) { false }
        if (!ok) { indice++; siguiente(); return }
        // El weak arranca Kangaroo de cero por cada clave. Si el modo de segundo
        // plano está puesto (o el calor ya está recortando), se aplica el techo
        // de CPU en el acto; si no, se deja al ritmo normal.
        if (Termico.ahorro || Termico.limitando)
            try { HunterEngine.kangarooSetCpu(Termico.efectivo().coerceAtLeast(1)) } catch (e: Throwable) {}
        presuActual = presupuesto * Math.sqrt(Math.pow(2.0, bits.toDouble()))
        claveInicioMs = System.currentTimeMillis()
        // Ritmo de sondeo adaptado a lo que se espera que tarde la clave: en
        // rangos bajos termina en milisegundos, así que un tick fijo de 1 s
        // hacía que cada clave costara 1 s (y el lote, horas). ~4 sondeos por
        // clave, entre 50 ms y 1 s.
        intervaloTick = ((presuActual / ritmoDisp) * 250.0).toLong().coerceIn(50L, 1000L)
        programarTick()
    }

    private fun programarTick() {
        h.removeCallbacksAndMessages(tickToken)
        h.postAtTime({ tick() }, tickToken, android.os.SystemClock.uptimeMillis() + intervaloTick)
    }

    private fun tick() {
        if (!corriendo) return
        val clave = try { HunterEngine.kangarooResult() } catch (e: Throwable) { "" }
        val ops = try { HunterEngine.kangarooOps().toDouble() } catch (e: Throwable) { 0.0 }
        if (clave.length == 64) {
            hallados++
            guardar(clave)
            opsPrevias += ops
            try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
            refresco(0.0, 100)
            indice++; siguiente(); return
        }
        val segClave = (System.currentTimeMillis() - claveInicioMs) / 1000.0
        val topeAlcanzado = topeSeg > 0 && segClave >= topeSeg
        if (!HunterEngine.kangarooRunning() || ops >= presuActual || topeAlcanzado) {
            // Refresca ANTES de pasar: en claves rápidas (rango bajo) el
            // presupuesto se agota en el primer sondeo y sin esto la tarjeta se
            // quedaba en 0 aunque el motor sí estuviera trabajando.
            refresco(ops, 100)
            opsPrevias += ops
            try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
            indice++; siguiente(); return
        }
        val pct = if (presuActual > 0) (100 * ops / presuActual).toInt() else 0
        refresco(ops, pct)
        aviso("Key ${indice + 1}/${claves.size} · $bits-bit range · $pct % of its budget" +
              (if (topeSeg > 0) " · ${segClave.toInt()}/${topeSeg.toInt()} s" else "") + desglose() +
              (if (hallados > 0) "\n$hallados weak key(s) found so far" else ""))
        programarTick()
    }

    private fun refresco(opsClave: Double, pct: Int) {
        val ahora = System.currentTimeMillis()
        val total = opsPrevias + opsClave
        val dt = (ahora - ultMs) / 1000.0
        if (dt > 0.2) {
            val vel = ((total - ultTotalOps) / dt).coerceAtLeast(0.0)
            ultTotalOps = total; ultMs = ahora
            val (sv, su) = escala(vel)
            tvSpeed?.text = sv; tvSpeedU?.text = "$su/s"
            if (vel > pico) { pico = vel; val (pv, pu) = escala(pico); tvPeak?.text = "Peak $pv $pu/s" }
            if (opsClave > 0 && vel > 1e5) ritmoDisp = ritmoDisp * 0.8 + vel * 0.2
            if (ahora - ultChartMs > 5000) { ultChartMs = ahora; chart?.addPoint((vel / 1e6).toFloat()) }
        }
        val (ov, ou) = escala(total)
        tvOps?.text = "$ov $ou"
        tvTime?.text = reloj((ahora - inicioMs) / 1000)
        tvProg?.text = "$pct %"
        tvKeys?.text = cuentaTexto()
        actualizarBarra()
    }

    /** Texto del contador de claves. En streaming no hay lista en RAM, así que
     *  mostramos las procesadas frente al total aproximado (si se pudo estimar). */
    private fun cuentaTexto(): String = if (streamMode) {
        val tot = if (streamTotal > 0) "~${"%,d".format(streamTotal)}" else "?"
        "${"%,d".format(indice)}/$tot · $hallados"
    } else {
        "${(indice + 1).coerceAtMost(claves.size)}/${claves.size} · $hallados"
    }

    /** Barra general: claves hechas / total (aprox. en streaming). */
    private fun actualizarBarra() {
        val total = if (streamMode) streamTotal else claves.size
        barraProg?.set(if (total > 0) indice.toFloat() / total else 0f)
    }

    private fun guardar(claveHex: String) {
        val ctx = appCtx ?: return
        var addr = ""; var wif = ""
        try {
            val d = HunterEngine.datosDeClave(claveHex)
            if (d.contains("|")) { wif = d.substringBefore("|"); addr = d.substringAfter("|") }
        } catch (e: Throwable) {}
        try {
            MatchVault.add(ctx, MatchVault.Entry(
                ts = System.currentTimeMillis(), source = "weak-key",
                addr = addr, wif = wif, privHex = claveHex, btc = 0.0,
                extra = "WEAKKEY audit ($bits-bit range)", checkedTs = 0L))
        } catch (e: Exception) {}
        try {
            Avisos.hallazgo(ctx, "Weak key found!", "A key was in the audited range. In the finds vault.")
        } catch (e: Throwable) {}
    }

    private fun aviso(t: String) { tvEstado?.text = t }
}
