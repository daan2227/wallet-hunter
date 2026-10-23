package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Auditoría de Taproot PROPIAS: fuerza bruta con el tweak de BIP341, la única vía posible.
 *
 * Ni Kangaroo ni BSGS sirven aquí. Los dos resuelven Q = d·G cuando d está en un rango acotado.
 * Lo que hay en una salida Taproot es Q = P + hash(P)·G con P = d·G (BIP341 sin árbol de
 * scripts): despejando, Q = e·G con e = d + hash(P), y hash(P) se comporta como un número al
 * azar, así que aunque d esté en un rango pequeño, e no lo está. No hay atajo: hay que probar
 * cada d, aplicar el tweak y comparar. El motor está en taproot_audit.h (probado en
 * tools/ec-harness/taproot_audit.cpp), aparte del Scanner y de Kangaroo: no comparte estado con
 * ellos, así que pueden correr a la vez.
 *
 * Pantalla aparte, no un tercer botón en Weak: esa pestaña ya tiene bastante (el pegado de
 * claves, el CSV, dos sliders de rango, Kangaroo, BSGS) y un motor que no comparte nada con los
 * otros dos sólo añadiría confusión ahí.
 */
class TaprootAuditActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val h = Handler(Looper.getMainLooper())
    private val REQ_ARCHIVO = 7001

    // 2^8 .. 2^40: con menos de 2^8 el ancho es trivial y con más de 2^40 el aviso del propio
    // selector dice que ya no es razonable en un teléfono (ver la conversación con el usuario).
    private val BITS_MIN = 8
    private val BITS_MAX = 40

    private lateinit var tvEstadoCarga: TextView
    private lateinit var btnCargar: TextView
    private lateinit var etPegadas: EditText
    private lateinit var etInicio: EditText
    private lateinit var sbBits: SeekBar
    private lateinit var tvBits: TextView
    private lateinit var tvAncho: TextView
    private lateinit var barra: ProgressBar
    private lateinit var tvProg: TextView
    private lateinit var btnEmpezar: TextView

    private var inicioCorridaMs = 0L
    private var ultDone = 0L; private var ultMs = 0L

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        PantallaSegura.aplicar(this)
        AppTheme.init(this)

        val scroll = ScrollView(this).apply { background = AppTheme.fondoApp() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root); setContentView(scroll)

        root.addView(Ui.pageTitle(this, "Taproot audit", lados = false))
        texto(root, "Brute force with the BIP341 tweak, against your OWN Taproot addresses. Kangaroo " +
            "and BSGS cannot be used here: the tweak scatters the exponent across the whole field, so there is no " +
            "bounded-range shortcut. Each candidate key costs about two point multiplications, so this is slow — a " +
            "range of 2⁴⁰ keys can take days even with several cores. Pick a width you can actually let run.",
            AppTheme.TXT_SEC, AppTheme.SP_CAPTION)

        // ── Dataset ──────────────────────────────────────────────────────────
        val cDs = Ui.card(this)
        root.addView(cDs)
        titulo(cDs, "Your Taproot addresses")
        texto(cDs, "A file with one entry per line: a bc1p… address, or a bare 64-character hex key " +
            "(x-only, no 02/03/04 prefix — the “Public key” column of this app's own UTXO CSV, set to x-only, is " +
            "exactly this). Any other line is skipped, so you can point it at a file with headers or extra columns. " +
            "There is no size limit worth mentioning: it is read a line at a time.",
            AppTheme.TXT_SEC, AppTheme.SP_CAPTION)
        tvEstadoCarga = texto(cDs, "No file loaded yet.", AppTheme.TXT_PRI)

        etPegadas = Ui.input(this, "one or more, separated by spaces or newlines", mono = true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2; maxLines = 6
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        }
        cDs.addView(etPegadas)
        val btnPegadas = Ui.ghost(this, "Use these addresses").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        cDs.addView(btnPegadas)
        btnPegadas.setOnClickListener { cargarDePegadas(etPegadas.text.toString()) }

        texto(cDs, "Or load them from a file:", AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)
        btnCargar = Ui.ghost(this, "Load addresses from a file…").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        cDs.addView(btnCargar)
        btnCargar.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            try { startActivityForResult(i, REQ_ARCHIVO) }
            catch (e: Exception) { Toast.makeText(this, "No file picker available.", Toast.LENGTH_LONG).show() }
        }

        // ── Rango ────────────────────────────────────────────────────────────
        val cRango = Ui.card(this)
        root.addView(cRango)
        titulo(cRango, "Range")
        texto(cRango, "Start of the range (64 hex characters). Leave the default to start at the " +
            "very first private key.", AppTheme.TXT_SEC, AppTheme.SP_CAPTION)
        etInicio = Ui.input(this, "start (64 hex)", mono = true).apply {
            setText("0".repeat(64)); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        cRango.addView(etInicio)

        val filaBits = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        filaBits.addView(TextView(this).apply {
            text = "Width"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvBits = TextView(this).apply {
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context)
        }
        filaBits.addView(tvBits)
        cRango.addView(filaBits)
        sbBits = SeekBar(this).apply {
            max = BITS_MAX - BITS_MIN; progress = 24 - BITS_MIN
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { pintarBits() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        cRango.addView(sbBits)
        val ejes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ejes.addView(TextView(this).apply {
            text = "2^$BITS_MIN"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ejes.addView(TextView(this).apply {
            text = "2^$BITS_MAX (max)"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        cRango.addView(ejes)
        tvAncho = texto(cRango, "", AppTheme.TXT_SEC, AppTheme.SP_CAPTION)

        // ── Marcha ───────────────────────────────────────────────────────────
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(18) }
        }
        root.addView(barra)
        tvProg = texto(root, "", AppTheme.TXT_PRI)
        btnEmpezar = Ui.primary(this, "Scan range", height = 52)
        root.addView(btnEmpezar)
        btnEmpezar.setOnClickListener { alPulsarEmpezar() }

        pintarBits()
        actualizarBoton()
    }

    private fun titulo(padre: LinearLayout, s: String) = TextView(this).apply {
        text = s; textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context)
        padre.addView(this)
    }
    private fun texto(padre: LinearLayout, s: String, color: Int, size: Float = AppTheme.SP_CAPTION) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = AppTheme.body(context)
        setLineSpacing(0f, 1.25f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        padre.addView(this)
    }

    private fun anchoActual(): Long = 1L shl (BITS_MIN + sbBits.progress)
    private fun pintarBits() {
        val bits = BITS_MIN + sbBits.progress
        tvBits.text = "2^$bits"
        tvAncho.text = "%,d candidate keys.".format(anchoActual())
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_ARCHIVO && res == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            cargarDesde(uri)
        }
    }

    private val reBc1p = Regex("bc1p[a-zA-HJ-NP-Z0-9]{20,90}", RegexOption.IGNORE_CASE)
    private val reHex64 = Regex("\\b[0-9a-fA-F]{64}\\b")

    /** Lo pegado a mano: una o varias direcciones/claves, separadas por lo que sea. Mismo camino que un
     *  fichero, con una lista corta no hace falta otro hilo. */
    private fun cargarDePegadas(pegado: String) {
        val vistas = LinkedHashSet<String>()
        for (l in pegado.lines()) { reBc1p.find(l)?.let { vistas.add(it.value) }; reHex64.find(l)?.let { vistas.add(it.value) } }
        if (vistas.isEmpty()) {
            Toast.makeText(this, "No bc1p… address or x-only key found in that text.", Toast.LENGTH_LONG).show()
            return
        }
        val destino = java.io.File(filesDir, "taproot_audit_targets.txt")
        destino.writeText(vistas.joinToString("\n"))
        tvEstadoCarga.text = "Loading…"
        HunterEngine.taprootCargarObjetivos(destino.absolutePath)
        sondearCarga()
    }

    /** Lee el fichero línea a línea (cualquier tamaño) y se queda con las direcciones bc1p… o claves
     *  x-only de 64 hex, una por línea, en un fichero propio que el motor nativo vuelve a leer. */
    private fun cargarDesde(uri: Uri) {
        tvEstadoCarga.text = "Reading the file…"
        btnCargar.isEnabled = false
        Thread {
            val destino = java.io.File(filesDir, "taproot_audit_targets.txt")
            var lineas = 0L; var encontradas = 0L
            val err = try {
                contentResolver.openInputStream(uri)?.use { ent ->
                    destino.bufferedWriter().use { sal ->
                        ent.bufferedReader().forEachLine { linea ->
                            lineas++
                            reBc1p.find(linea)?.let { sal.write(it.value); sal.write("\n"); encontradas++ }
                                ?: reHex64.find(linea)?.let { sal.write(it.value); sal.write("\n"); encontradas++ }
                        }
                    }
                } ?: "Could not open that file."
                null
            } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
            h.post {
                btnCargar.isEnabled = true
                if (err != null) { tvEstadoCarga.text = err; return@post }
                if (encontradas == 0L) {
                    tvEstadoCarga.text = "No bc1p… address or x-only key found in $lineas line(s)."
                    return@post
                }
                tvEstadoCarga.text = "Loading…"
                HunterEngine.taprootCargarObjetivos(destino.absolutePath)
                sondearCarga()
            }
        }.start()
    }

    private fun sondearCarga() {
        if (!HunterEngine.taprootCargando()) {
            tvEstadoCarga.text = HunterEngine.taprootEstadoCarga()
            actualizarBoton()
            return
        }
        h.postDelayed({ sondearCarga() }, 300)
    }

    private fun actualizarBoton() {
        val listo = HunterEngine.taprootObjetivos() > 0
        btnEmpezar.isEnabled = listo || HunterEngine.taprootCorriendo()
        btnEmpezar.alpha = if (btnEmpezar.isEnabled) 1f else .4f
    }

    private fun alPulsarEmpezar() {
        if (HunterEngine.taprootCorriendo()) {
            HunterEngine.taprootDetener()
            btnEmpezar.isEnabled = false; btnEmpezar.text = "Stopping…"
            return
        }
        val inicio = etInicio.text.toString().trim().lowercase()
        if (inicio.length != 64 || inicio.any { it !in "0123456789abcdef" }) {
            Toast.makeText(this, "The start must be exactly 64 hex characters.", Toast.LENGTH_LONG).show(); return
        }
        val ancho = anchoActual()
        val hilos = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val err = HunterEngine.taprootIniciar(inicio, ancho.toString(), hilos)
        if (err.isNotEmpty()) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return }
        inicioCorridaMs = System.currentTimeMillis(); ultDone = 0L; ultMs = inicioCorridaMs
        barra.visibility = View.VISIBLE; barra.progress = 0
        etInicio.isEnabled = false; sbBits.isEnabled = false; btnCargar.isEnabled = false
        sondearMarcha()
    }

    private fun sondearMarcha() {
        val corre = HunterEngine.taprootCorriendo()
        val hechas = HunterEngine.taprootHechas()
        val ancho = HunterEngine.taprootAncho().coerceAtLeast(1)
        val ahora = System.currentTimeMillis()
        val dt = (ahora - ultMs) / 1000.0
        val ritmo = if (dt > 0.4) ((hechas - ultDone) / dt).also { ultDone = hechas; ultMs = ahora } else null
        val corrido = (ahora - inicioCorridaMs) / 1000
        barra.progress = (hechas * 1000 / ancho).toInt().coerceIn(0, 1000)
        val eta = if (ritmo != null && ritmo > 0) {
            val seg = ((ancho - hechas) / ritmo).toLong()
            " · about ${CoreConfig.duracion(seg)} left"
        } else ""
        val keysPorSeg = if (ritmo != null) " · %,d keys/s".format(ritmo.toLong()) else ""
        tvProg.text = "%,d of %,d (%.4f %%)%s%s\nRunning for %s".format(hechas, ancho, hechas * 100.0 / ancho,
            keysPorSeg, eta, CoreConfig.duracion(corrido))
        if (!corre) {
            btnEmpezar.isEnabled = true; btnEmpezar.text = "Scan range"
            etInicio.isEnabled = true; sbBits.isEnabled = true; btnCargar.isEnabled = true
            tvProg.text = tvProg.text.toString() + "\nStopped. Matches, if any, are already in the vault."
            return
        }
        btnEmpezar.isEnabled = true; btnEmpezar.text = "Stop"
        h.postDelayed({ sondearMarcha() }, 1000)
    }

    override fun onDestroy() {
        // El motor sigue si la pantalla se cierra: es el mismo comportamiento que Kangaroo en Weak
        // (sigue en segundo plano). Sólo se deja de sondear desde aquí.
        super.onDestroy()
    }
}
