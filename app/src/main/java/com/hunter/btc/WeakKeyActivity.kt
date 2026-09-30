package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.math.BigInteger

/**
 * Auditoría de claves débiles.
 *
 * Kangaroo necesita la clave pública Y un rango donde esté la privada. En los
 * puzzles el rango se conoce; en una dirección cualquiera, no — la clave puede
 * estar en cualquier punto de [1, 2^256] y encontrarla es justo lo que protege
 * Bitcoin.
 *
 * Lo único que sí se puede auditar es lo MAL generado: carteras cuya clave
 * privada salió de poca entropía y cayó en un rango pequeño (un `rand()` de 32
 * bits, un timestamp, una frase corta pasada por SHA-256 truncada…). Para esas,
 * si la clave pública está publicada —la dirección ha gastado— Kangaroo sobre
 * [1, 2^bits) las encuentra en √ del rango.
 *
 * Esto toma una lista de claves públicas y prueba cada una en ese rango con un
 * presupuesto fijo; si no aparece dentro del presupuesto, pasa a la siguiente.
 * Una clave bien generada (256 bits de entropía) NO va a caer aquí: el rango es
 * una porción ínfima del espacio. Sirve para revisar TUS propias direcciones, o
 * las de un sistema que audites, no para barrer las de terceros.
 */
class WeakKeyActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // La cola de trabajo y el estado de la ejecución en curso.
    private var claves: List<String> = emptyList()
    private var indice = 0
    private var bits = 64
    private var presupuesto = 2.0
    private var corriendo = false
    private var hallados = 0

    private lateinit var etClaves: EditText
    private lateinit var sbBits: SeekBar
    private lateinit var tvBits: TextView
    private lateinit var tvEstim: TextView
    private lateinit var etPresu: EditText
    private lateinit var tvEstado: TextView
    private lateinit var btn: Button

    // Tarjeta de rendimiento, como la del puzzle.
    private lateinit var statsCard: LinearLayout
    private lateinit var tvSpeed: TextView
    private lateinit var tvSpeedU: TextView
    private lateinit var tvPeak: TextView
    private lateinit var chart: SpeedChartView
    private lateinit var tvOps: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvProg: TextView
    private lateinit var tvKeys: TextView

    private val tickToken = Any()
    private var inicioMs = 0L
    private var opsPrevias = 0.0     // operaciones de las claves ya terminadas
    private var presuActual = 0.0    // presupuesto de la clave en curso
    private var ultTotalOps = 0.0
    private var ultMs = 0L
    private var pico = 0.0
    private var ultChartMs = 0L

    companion object {
        private const val BITS_MIN = 20
        private const val BITS_MAX = 80
        private const val BITS_SUG = 50
        // Ritmo típico medido en estos móviles, para la estimación de tiempo.
        private const val OPS_POR_SEG = 7_000_000.0
        private const val REQ_CSV = 4021
    }

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)
        AppLock.init(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Weak-key audit"
            textSize = 24f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
        })
        root.addView(TextView(this).apply {
            text = "Kangaroo over a small range for public keys that have spent. " +
                   "Finds only keys generated with too little entropy (a private key " +
                   "that landed in [1, 2^bits)). A properly generated key will never " +
                   "show up here. Use it to check your own addresses."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f)
            setPadding(0, dp(8), 0, dp(16))
        })

        root.addView(rotulo("Public keys (02…/03…/04…) or spent addresses, one per line"))
        etClaves = EditText(this).apply {
            hint = "02abc…\n03def…\n1Address…"
            setHorizontallyScrolling(false); maxLines = 8; minLines = 4
            gravity = Gravity.TOP or Gravity.START
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            typeface = android.graphics.Typeface.MONOSPACE
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(etClaves)

        // Además de pegar, se puede cargar un CSV/TXT: se sacan de él todas las
        // claves públicas y direcciones que aparezcan, en cualquier columna,
        // saltando cabeceras y comillas, y se añaden a la lista sin duplicar.
        root.addView(Ui.ghost(this, "Load CSV / text file", AppTheme.TXT_PRI).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            setOnClickListener { elegirCsv() }
        })

        // Selector de rango: un deslizador de 20 a 80 bits con su valor a la
        // vista y una estimación viva de coste y tiempo por clave. 50 bits es
        // la sugerencia: cubre las claves mal generadas más habituales (rand()
        // de 32 bits, IDs de 40-48 bits) y a ~7 M op/s tarda segundos.
        val cabRango = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(2))
        }
        cabRango.addView(rotulo("Search range").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvBits = TextView(this).apply {
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
        }
        cabRango.addView(tvBits)
        root.addView(cabRango)

        sbBits = SeekBar(this).apply {
            max = BITS_MAX - BITS_MIN
            progress = BITS_SUG - BITS_MIN
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { estimar() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(sbBits)
        // Extremos del deslizador, para dar referencia sin tener que arrastrar.
        val ejes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, 0)
        }
        ejes.addView(TextView(this).apply {
            text = "$BITS_MIN"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ejes.addView(TextView(this).apply {
            text = "suggested $BITS_SUG"; textSize = AppTheme.SP_MICRO
            setTextColor(AppTheme.TXT_MUTED); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ejes.addView(TextView(this).apply {
            text = "$BITS_MAX"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(ejes)

        tvEstim = TextView(this).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.3f)
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(tvEstim)

        root.addView(rotulo("Budget × √ (2–4; higher = surer, slower)").apply {
            setPadding(0, dp(16), 0, dp(6))
        })
        etPresu = entrada("2")
        root.addView(etPresu)
        etPresu.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { estimar() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        tvEstado = TextView(this).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f)
            setPadding(0, dp(16), 0, dp(16))
        }
        root.addView(tvEstado)

        btn = Button(this).apply {
            text = "Start audit"; textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.ON_ACCENT); typeface = AppTheme.bold(context)
            isAllCaps = false; stateListAnimator = null
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.ACCENT, context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { if (corriendo) parar("Stopped.") else arrancar() }
        }
        root.addView(btn)

        // ── Tarjeta de rendimiento (oculta hasta arrancar) ────────────────
        statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        }
        statsCard.addView(TextView(this).apply {
            text = "Performance"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context)
            setPadding(0, 0, 0, dp(12))
        })
        val filaVel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        }
        tvSpeed = TextView(this).apply {
            text = "0"; textSize = AppTheme.SP_DISPLAY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.display(context); letterSpacing = -0.04f
        }
        filaVel.addView(tvSpeed)
        tvSpeedU = TextView(this).apply {
            text = "keys/s"; textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); bottomMargin = dp(4) }
        }
        filaVel.addView(tvSpeedU)
        statsCard.addView(filaVel)
        tvPeak = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(10), 0, dp(4))
        }
        statsCard.addView(tvPeak)
        chart = SpeedChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
                .apply { topMargin = dp(8) }
        }
        statsCard.addView(chart)

        fun mini(label: String, tv: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(this@WeakKeyActivity).apply {
                text = label; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context)
            })
            addView(tv)
        }
        fun valor(ini: String) = TextView(this).apply {
            text = ini; textSize = AppTheme.SP_FIGURE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context); letterSpacing = -0.02f
            setPadding(0, dp(6), 0, 0)
        }
        tvOps = valor("0"); tvTime = valor("00:00:00"); tvProg = valor("—"); tvKeys = valor("—")
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) } }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        r1.addView(mini("Operations", tvOps).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        r1.addView(mini("Time", tvTime))
        r2.addView(mini("Key budget", tvProg).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        r2.addView(mini("Keys · found", tvKeys))
        statsCard.addView(r1); statsCard.addView(r2)
        root.addView(statsCard)

        setContentView(scroll)
        estimar()
    }

    /** Cifra + unidad escaladas para una velocidad en operaciones/s. */
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

    /** El valor del deslizador y la estimación de coste/tiempo por clave. */
    private fun estimar() {
        val b = sbBits.progress + BITS_MIN
        tvBits.text = "$b bits"
        val c = etPresu.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 2.0
        // Kangaroo cuesta del orden de c·√(2^b) operaciones.
        val ops = c * Math.pow(2.0, b / 2.0)
        val seg = ops / OPS_POR_SEG
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
        tvEstim.text = "≈ ${log2(ops)} operations per key · ${tiempo(seg)} at ~7 M op/s.\n" +
            "Only finds keys whose private value is below 2^$b."
    }

    private fun rotulo(t: String) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context); setPadding(0, 0, 0, dp(6))
    }
    private fun entrada(hintTxt: String) = EditText(this).apply {
        hint = hintTxt; inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
        textSize = AppTheme.SP_BODY
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Clave pública comprimida (02/03) a partir de comprimida o sin comprimir. */
    private fun comprimida(k: String): String? = when {
        Regex("^0[23][0-9a-fA-F]{64}$").matches(k) -> k.lowercase()
        Regex("^04[0-9a-fA-F]{128}$").matches(k) ->
            (if (BigInteger(k.substring(66), 16).testBit(0)) "03" else "02") +
            k.substring(2, 66).lowercase()
        else -> null
    }

    /** Abre el selector de archivos del sistema para elegir un CSV o TXT. */
    private fun elegirCsv() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"   // muchos CSV llegan como text/comma-separated o application/octet-stream
        }
        try { startActivityForResult(i, REQ_CSV) }
        catch (e: Exception) { aviso("No file picker available on this device.") }
    }

    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_CSV || res != RESULT_OK) return
        val uri = data?.data ?: return
        aviso("Reading file…")
        // En otro hilo: un CSV puede ser grande y leerlo en el de la pantalla la cuelga.
        Thread {
            val texto = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            val encontrados = if (texto != null) extraerClaves(texto) else emptyList()
            runOnUiThread {
                if (texto == null) { aviso("Could not read the file."); return@runOnUiThread }
                if (encontrados.isEmpty()) {
                    aviso("No public keys or addresses found in the file."); return@runOnUiThread
                }
                // Fundir con lo que ya haya escrito, en orden y sin duplicar.
                val previas = etClaves.text.toString().split('\n')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val todas = LinkedHashSet<String>(previas).apply { addAll(encontrados) }
                etClaves.setText(todas.joinToString("\n"))
                aviso("Loaded ${encontrados.size} from file · ${todas.size} in the list.")
            }
        }.start()
    }

    /** Saca de un texto (CSV/TXT, cualquier columna) toda clave pública o
     *  dirección Bitcoin que aparezca, en orden y sin repetir. Tolera cabeceras,
     *  comillas y columnas extra: no parsea el CSV, busca los patrones sueltos. */
    private fun extraerClaves(texto: String): List<String> {
        val re = Regex(
            "0[23][0-9a-fA-F]{64}" +               // clave pública comprimida
            "|04[0-9a-fA-F]{128}" +                // clave pública sin comprimir
            "|bc1[a-z0-9]{6,87}" +                 // bech32 (siempre minúsculas)
            "|[13][a-km-zA-HJ-NP-Z1-9]{25,34}")    // base58 (P2PKH/P2SH)
        val vistos = LinkedHashSet<String>()
        for (m in re.findAll(texto)) vistos.add(m.value)
        return vistos.toList()
    }

    private fun arrancar() {
        bits = sbBits.progress + BITS_MIN
        presupuesto = etPresu.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        if (bits !in 8..80) { aviso("Range bits: between 8 and 80."); return }
        if (presupuesto <= 0) { aviso("Budget: a number above 0 (2 is a good start)."); return }
        // Cada línea: una clave pública directa, o una dirección que se resolverá
        // en la red al llegarle el turno.
        val lineas = etClaves.text.toString().split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (lineas.isEmpty()) { aviso("Paste at least one public key or address."); return }
        claves = lineas
        indice = 0; hallados = 0; corriendo = true
        inicioMs = System.currentTimeMillis()
        opsPrevias = 0.0; ultTotalOps = 0.0; ultMs = inicioMs; pico = 0.0; ultChartMs = 0L
        chart.reset()
        tvSpeed.text = "0"; tvPeak.text = ""; tvOps.text = "0"; tvTime.text = "00:00:00"
        tvProg.text = "—"; tvKeys.text = "0/${claves.size} · 0"
        statsCard.visibility = android.view.View.VISIBLE
        btn.text = "Stop"
        siguiente()
    }

    private fun parar(motivo: String) {
        corriendo = false
        h.removeCallbacksAndMessages(this)
        h.removeCallbacksAndMessages(tickToken)
        try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
        btn.text = "Start audit"
        aviso(motivo + (if (hallados > 0) "  ·  $hallados key(s) found → in the finds vault." else ""))
    }

    private fun siguiente() {
        if (!corriendo) return
        if (indice >= claves.size) {
            parar("Done: ${claves.size} key(s) checked.")
            return
        }
        val bruta = claves[indice].removePrefix("0x")
        val directa = comprimida(bruta)
        if (directa != null) { lanzar(directa); return }
        // Dirección: buscar su clave pública en la red (solo existe si gastó).
        if (bruta.startsWith("1") || bruta.startsWith("3") || bruta.startsWith("bc1", true)) {
            aviso("Key ${indice + 1}/${claves.size}: looking up its public key…")
            Thread {
                val r = try { PubKeyFinder.buscar(this, bruta, false) }
                        catch (e: Exception) { PubKeyFinder.Resultado.SinRed }
                runOnUiThread {
                    if (!corriendo) return@runOnUiThread
                    val pub = (r as? PubKeyFinder.Resultado.Encontrada)?.pubHex?.let { comprimida(it) }
                    if (pub != null) lanzar(pub) else { indice++; siguiente() }
                }
            }.start()
            return
        }
        indice++; siguiente()   // línea que no es clave ni dirección
    }

    private fun lanzar(pub: String) {
        val fin = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val ini = BigInteger.ONE
        try { if (HunterEngine.kangarooRunning()) HunterEngine.kangarooStop() } catch (e: Throwable) {}
        val ruta = java.io.File(filesDir, "weakkey.dat").absolutePath   // una sola, se pisa por clave
        java.io.File(ruta).delete()
        val nucleos = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val ok = try {
            HunterEngine.kangarooStart(pub, ini.toString(16), fin.toString(16),
                nucleos, 1024, ruta, HunterEngine.topeTablaBits(this))
        } catch (e: Throwable) { false }
        if (!ok) { indice++; siguiente(); return }   // rango imposible para esa clave
        presuActual = presupuesto * Math.sqrt(Math.pow(2.0, bits.toDouble()))
        programarTick()
    }

    private fun programarTick() {
        h.removeCallbacksAndMessages(tickToken)
        h.postAtTime({ tick() }, tickToken, android.os.SystemClock.uptimeMillis() + 1000)
    }

    /** Cada segundo: mira si apareció la clave o se agotó el presupuesto, y
     *  refresca la tarjeta de rendimiento. */
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
        if (!HunterEngine.kangarooRunning() || ops >= presuActual) {
            opsPrevias += ops
            try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
            indice++; siguiente(); return
        }
        val pct = if (presuActual > 0) (100 * ops / presuActual).toInt() else 0
        refresco(ops, pct)
        aviso("Key ${indice + 1}/${claves.size} · $bits-bit range · $pct % of its budget" +
              (if (hallados > 0) "\n$hallados weak key(s) found so far" else ""))
        programarTick()
    }

    /** Actualiza velocidad, gráfica y las cuatro cifras. */
    private fun refresco(opsClave: Double, pct: Int) {
        val ahora = System.currentTimeMillis()
        val total = opsPrevias + opsClave
        val dt = (ahora - ultMs) / 1000.0
        if (dt > 0.2) {
            val vel = ((total - ultTotalOps) / dt).coerceAtLeast(0.0)
            ultTotalOps = total; ultMs = ahora
            val (sv, su) = escala(vel)
            tvSpeed.text = sv; tvSpeedU.text = "$su/s"
            if (vel > pico) { pico = vel; val (pv, pu) = escala(pico); tvPeak.text = "Peak $pv $pu/s" }
            // Una muestra cada 5 s: la búsqueda dura y lo que importa es la
            // tendencia, no el segundo a segundo.
            if (ahora - ultChartMs > 5000) { ultChartMs = ahora; chart.addPoint((vel / 1e6).toFloat()) }
        }
        val (ov, ou) = escala(total)
        tvOps.text = "$ov $ou"
        tvTime.text = reloj((ahora - inicioMs) / 1000)
        tvProg.text = "$pct %"
        tvKeys.text = "${(indice + 1).coerceAtMost(claves.size)}/${claves.size} · $hallados"
    }

    private fun guardar(claveHex: String) {
        var addr = ""; var wif = ""
        try {
            val d = HunterEngine.datosDeClave(claveHex)
            if (d.contains("|")) { wif = d.substringBefore("|"); addr = d.substringAfter("|") }
        } catch (e: Throwable) {}
        try {
            MatchVault.add(this, MatchVault.Entry(
                ts = System.currentTimeMillis(), source = "weak-key",
                addr = addr, wif = wif, privHex = claveHex, btc = 0.0,
                extra = "WEAKKEY audit ($bits-bit range)", checkedTs = 0L))
        } catch (e: Exception) {}
        try {
            Avisos.hallazgo(this, "Weak key found!", "A key was in the audited range. In the finds vault.")
        } catch (e: Throwable) {}
    }

    private fun aviso(t: String) { tvEstado.text = t }

    override fun onDestroy() {
        super.onDestroy()
        if (corriendo) { corriendo = false; try { HunterEngine.kangarooStop() } catch (e: Throwable) {} }
        h.removeCallbacksAndMessages(this)
    }
}
