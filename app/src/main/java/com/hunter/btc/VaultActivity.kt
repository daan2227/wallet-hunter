package com.hunter.btc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * El baúl de hallazgos, en su propia pantalla.
 *
 * Antes era un diálogo de lista dentro de la pantalla principal: tres líneas de
 * texto por hallazgo, sin forma de ver de un vistazo cuál tenía saldo, y
 * protegido por la sesión general — con la app desbloqueada, se abría sin
 * pedir nada.
 *
 * Aquí se guardan las claves que valen dinero, así que el PIN se pide SIEMPRE:
 *
 *   - Al ENTRAR, aunque la app ya esté desbloqueada.
 *   - Al VOLVER, en cuanto la pantalla deja de verse (otra app, el botón de
 *     inicio, la pantalla apagada, otra pantalla nuestra). Al irse se tapa el
 *     contenido y se sueltan las entradas, así que ni la vista de recientes
 *     ni quien coja el móvil después ven nada.
 *
 * Y FLAG_SECURE en la pantalla y en sus diálogos: sin capturas y en negro en
 * la vista de recientes.
 */
class VaultActivity : AppCompatActivity() {

    private lateinit var contenido: LinearLayout
    /** Hay que pedir el PIN antes de enseñar nada. */
    private var bloqueado = true
    /** El teclado del PIN (o la huella) está en pantalla. */
    private var pidiendo = false
    private var dialogo: AlertDialog? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(savedInstanceState)
        AppTheme.init(this)
        AppLock.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppTheme.BG_DEEP)
            isFillViewport = true
        }
        contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(40))
        }
        scroll.addView(contenido)
        setContentView(scroll)
        tapar()
    }

    override fun onStart() {
        super.onStart()
        if (bloqueado && !pidiendo) pedirPin()
    }

    override fun onStop() {
        super.onStop()
        // Mientras se teclea el PIN no: la huella del sistema puede parar la
        // pantalla un instante, y volver a pedirlo sería un bucle.
        if (!pidiendo && !isChangingConfigurations) {
            bloqueado = true
            tapar()
        }
    }

    private fun pedirPin() {
        if (!WalletManager.hasPin(this)) { bloqueado = false; mostrar(); return }
        pidiendo = true
        PinAuthHelper.show(this) { ok ->
            pidiendo = false
            if (ok) { bloqueado = false; mostrar() } else finish()
        }
    }

    /** Sólo la cabecera y un candado: lo que queda a la vista al irse. */
    private fun tapar() {
        dialogo?.dismiss(); dialogo = null
        contenido.removeAllViews()
        contenido.addView(cabecera(null))
        contenido.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(80), 0, 0)
            addView(Ui.icon(this@VaultActivity, R.drawable.ic_lock, 40, AppTheme.TXT_MUTED))
            addView(texto("Locked", AppTheme.SP_BODY, AppTheme.TXT_SEC).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
            })
        })
    }

    private fun texto(t: String, sp: Float, color: Int, tf: Typeface? = null) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color)
            typeface = tf ?: AppTheme.body(context)
        }

    private fun cabecera(sub: String?): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(AppTheme.PAD_SIDE), 0)
        }
        h.addView(Ui.icon(this, R.drawable.ic_back, 24, AppTheme.TXT_PRI).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            isClickable = true; isFocusable = true
            contentDescription = "Back"
            setOnClickListener { finish() }
        })
        h.addView(Ui.pageTitle(this, "Finds vault", lados = false).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(14); topMargin = dp(8); bottomMargin = dp(4)
            }
        })
        if (sub != null) h.addView(texto(sub, AppTheme.SP_CAPTION, AppTheme.TXT_SEC).apply {
            setPadding(dp(14), 0, 0, dp(6))
        })
        return h
    }

    /** Lee el baúl fuera del hilo principal —descifrar es I/O y Keystore— y pinta. */
    private fun mostrar() {
        Thread {
            val entradas = try {
                MatchVault.recoger(this)
                MatchVault.list(this)
            } catch (e: Exception) { emptyList() }
            val wifs = try {
                WalletManager.listWifs(this).mapTo(HashSet()) { it.second }
            } catch (e: Exception) { HashSet<String>() }
            runOnUiThread { if (!bloqueado && !isFinishing) pintar(entradas, wifs) }
        }.start()
    }

    private fun etiquetaOrigen(s: String) = when (s) {
        "puzzle"   -> "Puzzle"
        "scanner"  -> "Scanner"
        "recovery" -> "Recovery"
        "kangaroo" -> "Kangaroo"
        else       -> s.replaceFirstChar { it.uppercase() }
    }

    private fun pintar(entradas: List<MatchVault.Entry>, wifs: Set<String>) {
        contenido.removeAllViews()
        contenido.addView(cabecera(
            "Encrypted on this phone · ${entradas.size} find" + if (entradas.size == 1) "" else "s"))

        if (entradas.isEmpty()) {
            contenido.addView(Ui.card(this, topGap = 18).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(Ui.icon(this@VaultActivity, R.drawable.ic_vault, 32, AppTheme.TXT_MUTED))
                addView(texto("No finds yet", AppTheme.SP_TITLE, AppTheme.TXT_PRI,
                              AppTheme.title(this@VaultActivity)).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(6))
                })
                addView(texto("When the puzzle or the scanner finds a key it is " +
                              "stored here straight away, encrypted, and included " +
                              "in the backup.", AppTheme.SP_BODY, AppTheme.TXT_SEC).apply {
                    gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
                })
            })
            contenido.addView(Ui.card(this, topGap = 12).apply { addView(interruptorCartera(emptyList(), wifs)) })
            return
        }

        // ── Resumen ───────────────────────────────────────────────────────
        val comprobadas = entradas.filter { it.checkedTs > 0L }
        val pendientes = entradas.size - comprobadas.size
        val total = comprobadas.sumOf { it.btc }
        val resumen = Ui.card(this, topGap = 14)
        resumen.addView(texto("Known balance", AppTheme.SP_CAPTION, AppTheme.TXT_SEC,
                              AppTheme.medium(this)))
        resumen.addView(texto("%.8f BTC".format(total), AppTheme.SP_FIGURE,
                              if (total > 0) AppTheme.ACCENT else AppTheme.TXT_PRI,
                              AppTheme.display(this)).apply { setPadding(0, dp(4), 0, dp(2)) })
        resumen.addView(texto(
            if (pendientes == 0) "All balances checked"
            else "$pendientes not checked yet", AppTheme.SP_CAPTION, AppTheme.TXT_MUTED))

        val botones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        val bSaldo = Ui.ghost(this, if (pendientes > 0) "Check balances" else "Refresh balances").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
        }
        bSaldo.setOnClickListener { comprobarSaldos(bSaldo) }
        val bCopia = Ui.ghost(this, "Backup").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                startActivity(Intent(this@VaultActivity, WalletActivity::class.java).apply {
                    putExtra("MODE", "seed")
                    putExtra("OPEN_BACKUP_VAULT", true)
                })
            }
        }
        botones.addView(bSaldo); botones.addView(bCopia)
        resumen.addView(botones)
        resumen.addView(Ui.divider(this, 16))
        resumen.addView(interruptorCartera(entradas, wifs))
        contenido.addView(resumen)

        // ── Lista ─────────────────────────────────────────────────────────
        contenido.addView(Ui.sectionLabel(this, "Finds", topGap = 22).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(AppTheme.PAD_SIDE); bottomMargin = 0
            }
        })
        val fmt = java.text.SimpleDateFormat("dd MMM yyyy · HH:mm", java.util.Locale.US)
        entradas.forEach { e -> contenido.addView(fila(e, fmt, MatchVault.enCartera(e, wifs))) }
    }

    /**
     * "Añadir a la cartera automáticamente".
     *
     * Apagado por defecto: un hallazgo del escáner puede ser una dirección
     * vacía o de otro, y meterla en la cartera sin preguntar llenaría la lista
     * de claves que no son del usuario. Quien lo encienda, que lo decida.
     *
     * Al encenderlo se ofrece añadir también los que ya hay: si no, el ajuste
     * sólo afectaría a lo que se encuentre de ahí en adelante y parecería que
     * no hace nada.
     */
    private fun interruptorCartera(entradas: List<MatchVault.Entry>, wifs: Set<String>): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(texto("Add finds to the wallet", AppTheme.SP_BODY, AppTheme.TXT_PRI, AppTheme.medium(this)))
        col.addView(texto("Each new find also appears in Wallet as a WIF key",
                          AppTheme.SP_CAPTION, AppTheme.TXT_SEC).apply { setPadding(0, dp(2), dp(8), 0) })
        fila.addView(col)
        val sw = android.widget.Switch(this).apply {
            isChecked = MatchVault.autoCartera(this@VaultActivity)
            val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(estados,
                intArrayOf(AppTheme.ACCENT, AppTheme.TXT_SEC))
            trackTintList = android.content.res.ColorStateList(estados,
                intArrayOf((AppTheme.ACCENT and 0x00FFFFFF) or (0x66 shl 24), AppTheme.BG_ELEV))
        }
        sw.setOnCheckedChangeListener { _, on ->
            MatchVault.setAutoCartera(this, on)
            val fuera = entradas.count { !MatchVault.enCartera(it, wifs) }
            if (on && fuera > 0) {
                val d = AlertDialog.Builder(this)
                    .setTitle("Add the current finds too?")
                    .setMessage("From now on new finds go to the wallet. There " +
                                (if (fuera == 1) "is 1 find" else "are $fuera finds") +
                                " in the vault that are not in it yet.")
                    .setPositiveButton("Add them") { _, _ -> anadirTodos() }
                    .setNegativeButton("Only new ones", null)
                    .create()
                d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialogo = d
                d.show()
            }
        }
        fila.addView(sw)
        return fila
    }

    private fun anadirTodos() {
        Thread {
            val n = try { MatchVault.todosACartera(this) } catch (e: Exception) { 0 }
            runOnUiThread {
                if (bloqueado || isFinishing) return@runOnUiThread
                Toast.makeText(this,
                    if (n > 0) "$n find(s) are now in the wallet" else "Could not add them to the wallet",
                    Toast.LENGTH_SHORT).show()
                mostrar()
            }
        }.start()
    }

    private fun fila(e: MatchVault.Entry, fmt: java.text.SimpleDateFormat, enCartera: Boolean): View {
        val c = Ui.card(this, topGap = 10, pad = 16)
        c.foreground = Ui.toque()
        c.isClickable = true; c.isFocusable = true
        c.setOnClickListener { detalle(e, enCartera) }

        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        arriba.addView(texto(etiquetaOrigen(e.source), AppTheme.SP_MICRO, AppTheme.TXT_PRI,
                             AppTheme.bold(this)).apply {
            background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_ELEV, this@VaultActivity)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        })
        if (enCartera) arriba.addView(texto("In wallet", AppTheme.SP_MICRO, AppTheme.ACCENT,
                                            AppTheme.bold(this)).apply {
            background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_ELEV, this@VaultActivity)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(6) }
        })
        arriba.addView(texto(if (e.ts > 0) fmt.format(java.util.Date(e.ts)) else "",
                             AppTheme.SP_CAPTION, AppTheme.TXT_MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        })
        arriba.addView(Ui.icon(this, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED))
        c.addView(arriba)

        c.addView(texto(e.addr, AppTheme.SP_CAPTION, AppTheme.TXT_PRI, Typeface.MONOSPACE).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        // "0.00000000 BTC" a secas se lee como "vacía", cuando puede ser sólo
        // que aún no se ha preguntado a la cadena.
        c.addView(if (e.checkedTs == 0L)
            texto("Balance not checked", AppTheme.SP_CAPTION, AppTheme.TXT_MUTED)
        else
            texto("%.8f BTC".format(e.btc), AppTheme.SP_BODY,
                  if (e.btc > 0) AppTheme.ACCENT else AppTheme.TXT_SEC, AppTheme.bold(this)))
        return c
    }

    /**
     * Pregunta a la cadena por el saldo de los hallazgos sin comprobar.
     *
     * Consultar una dirección se la revela al servidor: es una acción del
     * usuario, no algo que la app deba hacer a sus espaldas.
     */
    private fun comprobarSaldos(boton: TextView) {
        boton.isEnabled = false
        boton.text = "Checking…"
        Thread {
            val n = try { MatchVault.resolvePendingBalances(this) } catch (e: Exception) { 0 }
            runOnUiThread {
                if (bloqueado || isFinishing) return@runOnUiThread
                Toast.makeText(this,
                    if (n > 0) "$n balance(s) updated" else "No source answered — try again later",
                    Toast.LENGTH_SHORT).show()
                mostrar()
            }
        }.start()
    }

    private fun detalle(e: MatchVault.Entry, enCartera: Boolean) {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        fun campo(etiqueta: String, valor: String, mono: Boolean = false): TextView {
            v.addView(texto(etiqueta, AppTheme.SP_CAPTION, AppTheme.TXT_SEC, AppTheme.medium(this)).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            val t = texto(valor, if (mono) AppTheme.SP_CAPTION else AppTheme.SP_BODY, AppTheme.TXT_PRI,
                          if (mono) Typeface.MONOSPACE else null).apply { setTextIsSelectable(false) }
            v.addView(t)
            return t
        }

        campo("Source", etiquetaOrigen(e.source) +
              if (e.ts > 0) " · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.ts)) else "")
        campo("Address", e.addr, mono = true).apply {
            // La dirección es pública: se copia normal, sin borrado.
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("address", e.addr))
                Toast.makeText(this@VaultActivity, "Address copied", Toast.LENGTH_SHORT).show()
            }
        }
        campo("Balance", if (e.checkedTs == 0L) "Not checked yet"
                         else "%.8f BTC · checked %s".format(e.btc,
                             java.text.DateFormat.getDateInstance().format(java.util.Date(e.checkedTs))))
        if (e.extra.contains("SEED:")) {
            campo("Path", Regex("""PATH:(\S+)""").find(e.extra)?.groupValues?.get(1) ?: "—", mono = true)
        }

        // Las claves, tapadas hasta que se pidan: la pantalla puede quedar a la
        // vista, y quien vea un WIF se lleva el saldo.
        val seed = if (e.extra.contains("SEED:"))
            Regex("""SEED:(.+?)\s+PATH:""").find(e.extra)?.groupValues?.get(1) ?: "" else ""
        val secretos = buildString {
            if (seed.isNotEmpty()) append("Seed\n$seed\n\n")
            if (e.wif.isNotEmpty()) append("WIF\n${e.wif}\n\n")
            if (e.privHex.isNotEmpty()) append("HEX\n${e.privHex}")
        }.trim()
        if (secretos.isNotEmpty()) {
            val tvClave = campo("Private key", "•••••••• hidden — tap to show", mono = true)
            var visible = false
            tvClave.setOnClickListener {
                visible = !visible
                tvClave.text = if (visible) secretos else "•••••••• hidden — tap to show"
            }
        }

        // Añadir a la cartera a mano, hallazgo a hallazgo, con el automático
        // apagado o para los que ya estaban.
        if (secretos.isNotEmpty()) {
            val bCartera = if (enCartera)
                Ui.ghost(this, "In the wallet", AppTheme.ACCENT, 44).apply { isEnabled = false }
            else Ui.ghost(this, "Add to wallet", AppTheme.TXT_PRI, 44)
            bCartera.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(18) }
            if (!enCartera) bCartera.setOnClickListener {
                bCartera.isEnabled = false
                Thread {
                    val ok = try { MatchVault.aCartera(this, e) } catch (t: Throwable) { false }
                    runOnUiThread {
                        if (bloqueado || isFinishing) return@runOnUiThread
                        if (ok) {
                            bCartera.text = "In the wallet"
                            bCartera.setTextColor(AppTheme.ACCENT)
                            Toast.makeText(this, "Added to the wallet", Toast.LENGTH_SHORT).show()
                            mostrar()
                        } else {
                            bCartera.isEnabled = true
                            Toast.makeText(this, "Could not add it to the wallet", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            v.addView(bCartera)
        }

        val b = AlertDialog.Builder(this)
            .setTitle("Find")
            .setView(ScrollView(this).apply { addView(v) })
            .setNegativeButton("Close", null)
        if (e.wif.isNotEmpty()) b.setPositiveButton("Copy WIF") { _, _ ->
            // Marcada como sensible y borrada a los 60 s: ver Secretos.
            Secretos.copiarClave(this, "wif", e.wif)
            Toast.makeText(this, "WIF copied. It will be cleared from the clipboard in 60 s.",
                Toast.LENGTH_LONG).show()
        }
        if (e.privHex.isNotEmpty()) b.setNeutralButton("Copy HEX") { _, _ ->
            Secretos.copiarClave(this, "hex", e.privHex)
            Toast.makeText(this, "Hex key copied. It will be cleared from the clipboard in 60 s.",
                Toast.LENGTH_LONG).show()
        }
        val d = b.create()
        // Un diálogo es otra ventana: el FLAG_SECURE de la pantalla no lo cubre.
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.setOnDismissListener { if (dialogo === d) dialogo = null }
        dialogo = d
        d.show()
    }
}
