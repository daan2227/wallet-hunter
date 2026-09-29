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
    private lateinit var etBits: EditText
    private lateinit var etPresu: EditText
    private lateinit var tvEstado: TextView
    private lateinit var btn: Button

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

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(6))
        }
        fun campo(rot: String, et: EditText, ultimo: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (!ultimo) marginEnd = dp(10) }
            addView(rotulo(rot)); addView(et)
        }
        etBits = entrada("64")
        etPresu = entrada("2")
        fila.addView(campo("Range bits (≤ 80)", etBits, false))
        fila.addView(campo("Budget × √", etPresu, true))
        root.addView(fila)

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
        bits = etBits.text.toString().trim().toIntOrNull() ?: 0
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
