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
    private var pubActual = ""

    private lateinit var etClaves: EditText
    private lateinit var sbBits: SeekBar
    private lateinit var tvBits: TextView
    private lateinit var tvEstim: TextView
    private lateinit var etPresu: EditText
    private lateinit var tvEstado: TextView
    private lateinit var btn: Button

    companion object {
        private const val BITS_MIN = 20
        private const val BITS_MAX = 80
        private const val BITS_SUG = 50
        // Ritmo típico medido en estos móviles, para la estimación de tiempo.
        private const val OPS_POR_SEG = 7_000_000.0
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

        setContentView(scroll)
        estimar()
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
        btn.text = "Stop"
        siguiente()
    }

    private fun parar(motivo: String) {
        corriendo = false
        h.removeCallbacksAndMessages(this)
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
        pubActual = pub
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
        val presuOps = presupuesto * Math.sqrt(Math.pow(2.0, bits.toDouble()))
        vigilar(presuOps)
    }

    private fun vigilar(presuOps: Double) {
        h.postAtTime({ paso(presuOps) }, this, android.os.SystemClock.uptimeMillis() + 2000)
    }

    private fun paso(presuOps: Double) {
        if (!corriendo) return
        val clave = try { HunterEngine.kangarooResult() } catch (e: Throwable) { "" }
        if (clave.length == 64) {
            hallados++
            guardar(clave)
            try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
            indice++; siguiente(); return
        }
        val ops = try { HunterEngine.kangarooOps().toDouble() } catch (e: Throwable) { 0.0 }
        if (!HunterEngine.kangarooRunning() || ops >= presuOps) {
            try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
            indice++; siguiente(); return
        }
        val pct = if (presuOps > 0) (100 * ops / presuOps).toInt() else 0
        aviso("Key ${indice + 1}/${claves.size} · $bits-bit range · $pct % of its budget" +
              (if (hallados > 0) "\n$hallados weak key(s) found so far" else ""))
        vigilar(presuOps)
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
