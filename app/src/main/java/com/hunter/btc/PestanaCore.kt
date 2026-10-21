package com.hunter.btc

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/*
 * La pestaña Core: un Bitcoin Core (bitcoind) funcionando en el propio teléfono, podado, con la opción de
 * arrancarlo desde un snapshot de UTXO (assumeutxo) en vez de bajar y comprobar toda la historia primero.
 *
 * Todo lo que habla con el nodo va en hilos aparte; la pantalla sólo pinta. La lógica sin Android está en
 * CoreConfig (con pruebas en la JVM); el proceso, en CoreNodo y CoreService.
 */
internal fun MainActivity.buildCoreTab(): ScrollView {
    val act = this
    val h = Handler(Looper.getMainLooper())
    val scroll = ScrollView(this).apply {
        background = AppTheme.fondoApp()
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }
    scroll.addView(page)
    page.addView(Ui.pageTitle(this, "Core", lados = true))

    fun texto(padre: LinearLayout, s: String, color: Int = AppTheme.TXT_SEC, size: Float = AppTheme.SP_BODY,
              mono: Boolean = false) = TextView(act).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = if (mono) AppTheme.mono(act) else AppTheme.body(act)
        setLineSpacing(0f, 1.25f)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        padre.addView(this)
    }
    fun etiqueta(padre: LinearLayout, s: String) = TextView(act).apply {
        text = s; textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(act)
        padre.addView(this)
    }
    fun barra(padre: LinearLayout) = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progress = 0; visibility = View.GONE
        progressTintList = ColorStateList.valueOf(AppTheme.ACCENT)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(10) }
        padre.addView(this)
    }
    fun boton(padre: LinearLayout, s: String, alto: Int = 46) = Ui.ghost(act, s, height = alto).apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10); padre.addView(this)
    }

    // ── Estado ────────────────────────────────────────────────────────────
    val cEstado = Ui.card(this)
    page.addView(cEstado)
    etiqueta(cEstado, "Bitcoin Core on this phone")
    val tvEstado = texto(cEstado, "…", AppTheme.ACCENT, AppTheme.SP_TITLE)
    val barraSync = barra(cEstado)
    val vAltura  = Ui.row(this, cEstado, "Blocks / headers", "–")
    val vPares   = Ui.row(this, cEstado, "Peers", "–")
    val vDisco   = Ui.row(this, cEstado, "Chain on disk", "–")
    val vPoda    = Ui.row(this, cEstado, "Pruned up to", "–")
    val vLibre   = Ui.row(this, cEstado, "Free space", "–")
    val tvAtras  = texto(cEstado, "", AppTheme.WARN, AppTheme.SP_CAPTION)
    val btnNodo  = Ui.primary(this, "Start node", height = 52).apply {
        (layoutParams as LinearLayout.LayoutParams).apply { leftMargin = 0; rightMargin = 0 }
    }
    cEstado.addView(btnNodo)
    texto(cEstado, "The node checks the Bitcoin chain itself, without asking anyone. The wallet in this app still " +
        "uses its own light client for now. It downloads a lot of data: Wi-Fi and a charger are recommended.",
        AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)

    // ── Red ───────────────────────────────────────────────────────────────
    val cRed = Ui.card(this)
    page.addView(cRed)
    etiqueta(cRed, "Bitcoin network")
    val rBloque = Ui.row(this, cRed, "Latest block", "–")
    val rUltimo = Ui.row(this, cRed, "Its transactions", "–")
    val rDif    = Ui.row(this, cRed, "Difficulty", "–")
    val rHash   = Ui.row(this, cRed, "Hashrate", "–")
    val rHalv   = Ui.row(this, cRed, "Next halving", "–")
    val rMem    = Ui.row(this, cRed, "Mempool", "–")
    val tvUtxo  = texto(cRed, "", AppTheme.TXT_PRI)
    val btnUtxo = boton(cRed, "Count all the coins (takes a few minutes)")
    texto(cRed, "Read from your own node. The mempool is empty in “Blocks only” mode.", AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)

    // ── Herramientas ──────────────────────────────────────────────────────
    val cHer = Ui.card(this)
    page.addView(cHer)
    etiqueta(cHer, "Node tools")
    texto(cHer, "Check an address, turn a descriptor into addresses, verify a signed message, and read, " +
        "combine or finalize PSBTs. Nothing here needs a server or a private key.", AppTheme.TXT_SEC, AppTheme.SP_CAPTION)
    boton(cHer, "Open the tools").setOnClickListener { HerramientasNodo.mostrar(act) }

    // ── Ajustes y poda ────────────────────────────────────────────────────
    val cAj = Ui.card(this)
    page.addView(cAj)
    etiqueta(cAj, "Pruning")
    texto(cAj, "A pruned node keeps only the latest blocks (the chain state is always complete). " +
        "Smaller means less storage and less history to serve; it cannot be turned back into a full node " +
        "without downloading everything again. Changes apply the next time the node starts.",
        AppTheme.TXT_SEC, AppTheme.SP_CAPTION)
    var aj = CoreNodo.ajustes(this)
    val nota = texto(cAj, "", AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)
    fun guardar(n: CoreConfig.Ajustes) {
        aj = n.normalizado(); CoreNodo.guardarAjustes(act, aj)
        nota.text = if (CoreNodo.procesoVivo() || CoreService.activo) "Saved. Restart the node to apply." else "Saved."
    }
    texto(cAj, "Space for recent blocks", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("0.5 GB" to null, "2 GB" to null, "5 GB" to null, "10 GB" to null),
        initial = CoreConfig.PRUNE_OPCIONES.indexOf(aj.pruneMiB).coerceAtLeast(0)) { guardar(aj.copy(pruneMiB = CoreConfig.PRUNE_OPCIONES[it])) })
    texto(cAj, "Memory for the database", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("100 MB" to null, "450 MB" to null, "1 GB" to null),
        initial = CoreConfig.DBCACHE_OPCIONES.indexOf(aj.dbcacheMiB).coerceAtLeast(0)) { guardar(aj.copy(dbcacheMiB = CoreConfig.DBCACHE_OPCIONES[it])) })
    texto(cAj, "Network", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("Wi-Fi only" to null, "Any network" to null),
        initial = if (aj.soloWifi) 0 else 1) { guardar(aj.copy(soloWifi = it == 0)) })
    texto(cAj, "Traffic", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("Blocks only" to null, "Blocks + mempool" to null),
        initial = if (aj.soloBloques) 0 else 1) { guardar(aj.copy(soloBloques = it == 0)) })
    texto(cAj, "Wallet", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("Servers only" to null, "Use my node to send" to null),
        initial = if (CoreBackend.activado(this)) 1 else 0) { CoreBackend.activar(act, it == 1) })
    texto(cAj, "With “Use my node to send”, the wallet broadcasts transactions and reads the block height from " +
        "this node when it is running and synced, with the usual servers as a fallback. Fee estimates also come " +
        "from the node, but only if the traffic setting above is “Blocks + mempool”. The node can scan your " +
        "balance from the wallet menu (Advanced options → Verify with my node).",
        AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)
    val btnPodar = boton(cAj, "Prune now (keep the latest ${CoreConfig.BLOQUES_QUE_QUEDAN} blocks)")

    // ── Snapshot ──────────────────────────────────────────────────────────
    val cSn = Ui.card(this)
    page.addView(cSn)
    etiqueta(cSn, "UTXO snapshot")
    texto(cSn, "With a snapshot the node starts from a recent set of coins and checks the old history in the " +
        "background, so it is usable in hours instead of days. Core only accepts snapshots whose hash is built " +
        "into its code (blocks 840,000, 880,000, 910,000, 935,000 and 965,000 of mainnet), so it does not matter where the " +
        "file comes from. A snapshot taken at any other block is rejected. " +
        "Create one with `dumptxoutset` on a node you trust, or download one. The node must be running and " +
        "must already have the headers up to the snapshot's block. Loading cannot be resumed: if the node " +
        "stops halfway it starts again from zero.", AppTheme.TXT_SEC, AppTheme.SP_CAPTION)
    val tvSnap = texto(cSn, "", AppTheme.TXT_PRI)
    val barraSnap = barra(cSn)
    val tvProg = texto(cSn, "", AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)
    val btnElegir = boton(cSn, "Pick a snapshot file…")
    val btnUrl = boton(cSn, "Download from a link…")
    val btnCargar = boton(cSn, "Load snapshot into the node")
    val btnBorrar = boton(cSn, "Delete the snapshot file", 42)
    val btnCancelar = boton(cSn, "Cancel", 42).apply { visibility = View.GONE }

    // ── Registro ──────────────────────────────────────────────────────────
    val cLog = Ui.card(this)
    page.addView(cLog)
    etiqueta(cLog, "Node log")
    val tvLog = texto(cLog, "", AppTheme.TXT_SEC, AppTheme.SP_MICRO, mono = true)

    // ── Estado interno ────────────────────────────────────────────────────
    var tarea: CoreSnapshot.Cancelar? = null
    var ocupado = false          // copia, descarga o carga en curso
    var ultimo: CoreConfig.Estado? = null
    val refrescando = AtomicBoolean(false)

    fun enMarcha() = CoreService.activo || CoreNodo.procesoVivo()

    fun fijarOcupado(si: Boolean, texto: String = "") {
        ocupado = si
        runOnUiThread {
            btnElegir.isEnabled = !si; btnUrl.isEnabled = !si; btnCargar.isEnabled = !si; btnBorrar.isEnabled = !si
            btnElegir.alpha = if (si) .4f else 1f; btnUrl.alpha = btnElegir.alpha
            btnCargar.alpha = btnElegir.alpha; btnBorrar.alpha = btnElegir.alpha
            barraSnap.visibility = if (si) View.VISIBLE else View.GONE
            btnCancelar.visibility = if (si && tarea != null) View.VISIBLE else View.GONE
            tvProg.text = texto
        }
    }

    fun progreso(hecho: Long, total: Long) {
        runOnUiThread {
            barraSnap.isIndeterminate = total <= 0
            if (total > 0) barraSnap.progress = (hecho * 1000 / total).toInt().coerceIn(0, 1000)
            tvProg.text = if (total > 0) "${CoreConfig.bytes(hecho)} of ${CoreConfig.bytes(total)}" else CoreConfig.bytes(hecho)
        }
    }

    /** La carga del snapshot en curso, según el registro: barra, porcentaje, monedas y tiempo. */
    fun pintarCarga(carga: CoreConfig.CargaSnapshot?) {
        val cargando = carga != null && carga.enCurso
        if (cargando) {
            barraSnap.visibility = View.VISIBLE; barraSnap.isIndeterminate = false
            barraSnap.progress = (carga!!.porcentaje * 10).toInt().coerceIn(0, 1000)
            val eta = carga.etaS?.let { " · about ${CoreConfig.duracion(it)} left" } ?: " · estimating…"
            tvProg.text = "%.1f %% · %,d of %,d coins%s\nRunning for %s".format(carga.porcentaje, carga.cargados, carga.total,
                eta, if (carga.transcurridoS >= 0) CoreConfig.duracion(carga.transcurridoS) else "?")
            btnCargar.isEnabled = false; btnCargar.alpha = .4f
            if (ultimo == null) {
                tvEstado.setTextColor(AppTheme.TXT_SEC)
                tvEstado.text = "Loading the snapshot… ${"%.1f".format(carga.porcentaje)} %"
            }
        } else if (ocupado) {
            // La llamada de carga sigue esperando pero el registro ya no se mueve: no dejar la barra congelada
            // con el último valor como si fuera el actual.
            barraSnap.visibility = View.VISIBLE; barraSnap.isIndeterminate = true
            tvProg.text = "Waiting for the node… (the log shows no progress right now)"
        } else {
            btnCargar.isEnabled = true; btnCargar.alpha = 1f
            barraSnap.visibility = View.GONE
        }
    }

    /** Sólo lee el registro (no hace RPC, que se queda sin respuesta durante la carga): se puede repetir a menudo. */
    fun refrescarCarga() {
        Thread {
            val c = CoreNodo.cargaSnapshot(act)
            runOnUiThread { pintarCarga(c) }
        }.start()
    }

    fun pintar(est: CoreConfig.Estado?, bin: Boolean, arrancando: Boolean, carga: CoreConfig.CargaSnapshot?) {
        val corre = enMarcha()
        val libre = CoreNodo.libre(act)
        vLibre.text = CoreConfig.bytes(libre)
        if (!bin) {
            tvEstado.text = "Not included in this build"
            tvEstado.setTextColor(AppTheme.WARN)
            btnNodo.text = "Start node"; btnNodo.alpha = .4f
            tvAtras.text = "This APK was built before the Core binary was published. Build the " +
                "\"Compilar Bitcoin Core\" workflow once and then build the app again."
            return
        }
        btnNodo.alpha = 1f
        btnNodo.text = if (corre || est != null) "Stop node" else "Start node"
        tvAtras.text = ""
        if (est != null) {
            ultimo = est
            val atras = est.descargaInicial || est.bloques < est.cabeceras
            tvEstado.text = when {
                est.validandoAtras -> "Running from the snapshot"
                atras -> "Syncing… ${"%.1f".format(est.progreso * 100)} %"
                else -> "Synced"
            }
            tvEstado.setTextColor(if (atras || est.validandoAtras) AppTheme.WARN else AppTheme.ACCENT)
            barraSync.visibility = if (atras) View.VISIBLE else View.GONE
            barraSync.progress = (est.progreso * 1000).toInt()
            vAltura.text = "%,d / %,d".format(est.bloques, est.cabeceras)
            vPares.text = "${est.pares}"
            vDisco.text = CoreConfig.bytes(est.bytesEnDisco)
            vPoda.text = if (est.podado) "block %,d".format(est.alturaPoda) else "not pruned"
            if (est.validandoAtras)
                tvAtras.text = "Checking the history in the background: ${"%.1f".format(est.progresoAtras * 100)} %. " +
                    "The wallet data is usable already; this only re-verifies the past."
        } else {
            barraSync.visibility = View.GONE
            vAltura.text = "–"; vPares.text = "–"
            tvEstado.setTextColor(AppTheme.TXT_SEC)
            tvEstado.text = when {
                carga != null && carga.enCurso -> "Loading the snapshot… ${"%.1f".format(carga.porcentaje)} %"
                arrancando -> "Starting… (loading the block index)"
                corre -> "Starting…"
                else -> {
                    val p = act.getSharedPreferences("core", android.content.Context.MODE_PRIVATE)
                    val code = p.getInt("ultima_salida", 0)
                    if (code != 0 && p.getLong("ultima_salida_ts", 0L) > 0L) "Stopped (last exit code $code)" else "Stopped"
                }
            }
        }
        val f = CoreNodo.snapshot(act)
        pintarCarga(carga)
        tvSnap.text = if (f.isFile) {
            val c = CoreSnapshot.cabecera(f)
            val h = c?.let { CoreConfig.alturaAceptada(it.base) }
            "File ready: ${CoreConfig.bytes(f.length())}" +
                (if (c != null) "\nBase block ${c.base.take(16)}…\n${"%,d".format(c.monedas)} coins" else "") +
                (if (c == null) ""
                 else if (h != null) "\nAccepted by this Core (block %,d).".format(h)
                 else "\nNOT accepted by this Core: it only loads snapshots at blocks " +
                      CoreConfig.SNAPSHOTS_ACEPTADOS.values.joinToString(", ") { "%,d".format(it) } + ".")
        } else if (snapshotParcial(act)) "A partial download is waiting: press “Download from a link” to continue."
        else "No snapshot file yet."
        val aceptado = f.isFile && CoreSnapshot.cabecera(f)?.let { CoreConfig.alturaAceptada(it.base) } != null
        btnCargar.visibility = if (aceptado) View.VISIBLE else View.GONE
        btnBorrar.visibility = if (f.isFile || snapshotParcial(act)) View.VISIBLE else View.GONE
        tvLog.text = CoreNodo.ultimasLineas(act, 10).ifEmpty { "(empty)" }
    }

    fun refrescar() {
        if (!refrescando.compareAndSet(false, true)) return
        Thread {
            try {
                val bin = CoreNodo.binario(act) != null
                var est: CoreConfig.Estado? = null; var arrancando = false
                val carga = if (bin) CoreNodo.cargaSnapshot(act) else null
                if (bin) try {
                    val r = CoreNodo.rpc(act)
                    val ch = r.objeto("getblockchaininfo")
                    val red = try { r.objeto("getnetworkinfo") } catch (e: Exception) { null }
                    val cs = try { r.objeto("getchainstates") } catch (e: Exception) { null }
                    est = CoreConfig.leerEstado(ch, red, cs)
                } catch (e: CoreRpcError) { arrancando = e.arrancando }
                  catch (e: java.io.IOException) { /* parado */ }
                runOnUiThread { pintar(est, bin, arrancando, carga) }
            } finally { refrescando.set(false) }
        }.start()
    }

    var redMs = 0L
    /** El panel de la red: unas pocas consultas RPC, como mucho cada 30 s. */
    fun refrescarRed() {
        val ahora = System.currentTimeMillis()
        if (ultimo == null || ahora - redMs < 30_000) return
        redMs = ahora
        Thread {
            try {
                val r = CoreNodo.rpc(act)
                val ch = r.objeto("getblockchaininfo", lecturaMs = 8_000)
                val alt = ch.optLong("blocks", 0L)
                val hps = (r.llamar("getnetworkhashps", JSONArray(), 8_000) as? Number)?.toDouble() ?: 0.0
                val mem = try { CoreConfig.leerMempool(r.objeto("getmempoolinfo", lecturaMs = 8_000)) } catch (e: Exception) { null }
                val bs = try { r.objeto("getblockstats", JSONArray().put(alt).put(JSONArray().put("txs").put("avgfeerate")), 8_000) }
                         catch (e: Exception) { null }
                val hv = CoreConfig.proximoHalving(alt)
                val edad = System.currentTimeMillis() / 1000 - ch.optLong("time", 0L)
                runOnUiThread {
                    rBloque.text = "%,d · %s".format(alt, CoreConfig.hace(edad))
                    rUltimo.text = if (bs != null) "%,d · avg fee %d sat/vB".format(bs.optLong("txs"), bs.optLong("avgfeerate")) else "–"
                    rDif.text = CoreConfig.dificultad(ch.optDouble("difficulty", 0.0))
                    rHash.text = CoreConfig.hashrate(hps)
                    rHalv.text = "block %,d · in %,d blocks (~%,d days)".format(hv.altura, hv.faltan, hv.dias)
                    rMem.text = if (mem != null) "%,d tx · %s · min %.1f sat/vB".format(mem.tx, CoreConfig.bytes(mem.bytes), mem.minimaSatVb) else "–"
                }
            } catch (e: Exception) { redMs = 0L }
        }.start()
    }

    btnUtxo.setOnClickListener {
        if (ultimo == null) { Toast.makeText(act, "Start the node and let it sync first.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
        btnUtxo.isEnabled = false; btnUtxo.alpha = .4f; tvUtxo.text = "Counting the whole set of coins…"
        Thread {
            val txt = try {
                val o = CoreNodo.rpc(act).llamar("gettxoutsetinfo", JSONArray().put("none"), 30 * 60_000) as? org.json.JSONObject
                val u = o?.let { CoreConfig.leerUtxoStats(it) }
                if (u != null) "%,d coins · %,.2f BTC in existence\nAt block %,d".format(u.monedas, u.btc, u.altura) else "No answer."
            } catch (e: CoreRpcError) { "Core: ${e.message}" } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
            runOnUiThread { tvUtxo.text = txt; btnUtxo.isEnabled = true; btnUtxo.alpha = 1f }
        }.start()
    }

    fun aviso(s: String) = runOnUiThread { Toast.makeText(act, s, Toast.LENGTH_LONG).show() }
    /** Un mensaje largo (el de Core) en un diálogo, completo y seleccionable, no cortado en un aviso. */
    fun detalle(titulo: String, s: String) = runOnUiThread {
        android.app.AlertDialog.Builder(act).setTitle(titulo).setMessage(s)
            .setPositiveButton("OK", null).show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    // ── Acciones ──────────────────────────────────────────────────────────
    btnNodo.setOnClickListener {
        if (CoreNodo.binario(act) == null) { aviso("This build does not include Bitcoin Core."); return@setOnClickListener }
        if (enMarcha() || ultimo != null) {
            btnNodo.alpha = .4f; tvEstado.text = "Stopping…"
            Thread { CoreNodo.parar(act); CoreService.parar(act); ultimo = null; runOnUiThread { refrescar() } }.start()
        } else {
            CoreService.arrancar(act)
            h.postDelayed({ refrescar() }, 1500)
        }
    }

    btnPodar.setOnClickListener {
        val est = ultimo
        val altura = if (est == null) -1 else CoreConfig.alturaPoda(est.bloques.toInt())
        if (altura < 0) { aviso("Start the node and let it sync first."); return@setOnClickListener }
        android.app.AlertDialog.Builder(act).setTitle("Prune now?")
            .setMessage(("Deletes block files below height %,d to free space. The chain state is untouched. " +
                "The deleted blocks can only come back by downloading them again.").format(altura))
            .setPositiveButton("Prune") { _, _ ->
                Thread {
                    try {
                        val r = CoreNodo.rpc(act).llamar("pruneblockchain", JSONArray().put(altura), 120_000)
                        aviso("Pruned up to block $r")
                    } catch (e: CoreRpcError) { aviso("Core: ${e.message}") }
                      catch (e: Exception) { aviso("Could not prune: ${e.javaClass.simpleName}") }
                    runOnUiThread { refrescar() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    fun correr(texto: String, trabajo: (CoreSnapshot.Cancelar) -> String?) {
        if (ocupado) return
        val c = CoreSnapshot.Cancelar(); tarea = c
        barraSnap.progress = 0; fijarOcupado(true, texto)
        Thread {
            val err = try { trabajo(c) } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }
            tarea = null; fijarOcupado(false)
            aviso(err ?: "Snapshot file ready.")
            runOnUiThread { refrescar() }
        }.start()
    }

    alElegirSnapshot = { uri ->
        correr("Copying…") { c -> CoreSnapshot.copiar(act, uri, CoreNodo.snapshot(act), c) { a, b -> progreso(a, b) } }
    }
    btnElegir.setOnClickListener {
        if (ocupado) return@setOnClickListener
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, REQ_CORE_SNAPSHOT) } catch (e: Exception) { aviso("No file picker available.") }
    }

    btnUrl.setOnClickListener {
        if (ocupado) return@setOnClickListener
        val campo = Ui.input(act, "https://…/utxo-880000.dat", mono = true)
        android.app.AlertDialog.Builder(act).setTitle("Download a snapshot")
            .setMessage("Only https links. Core checks the snapshot's hash against its own, so a wrong or " +
                "tampered file is rejected. It is a large file (several GB).")
            .setView(campo)
            .setPositiveButton("Download") { _, _ ->
                val u = campo.text.toString().trim()
                if (!CoreConfig.urlValida(u)) { aviso("That is not a valid https link."); return@setPositiveButton }
                correr("Downloading…") { c -> CoreSnapshot.descargar(u, CoreNodo.snapshot(act), c) { a, b -> progreso(a, b) } }
            }.setNegativeButton("Cancel", null).show()
    }

    btnCancelar.setOnClickListener { tarea?.cancelado = true }

    btnBorrar.setOnClickListener {
        if (ocupado) return@setOnClickListener
        CoreNodo.snapshot(act).delete(); java.io.File(CoreNodo.snapshot(act).path + ".part").delete()
        refrescar()
    }

    btnCargar.setOnClickListener {
        if (ocupado) return@setOnClickListener
        if (!enMarcha() || ultimo == null) { aviso("Start the node first and let it download the headers."); return@setOnClickListener }
        val f = CoreNodo.snapshot(act)
        android.app.AlertDialog.Builder(act).setTitle("Load the snapshot?")
            .setMessage("This can take several minutes and cannot be undone except by deleting the node's data. " +
                "If the snapshot's block is not in the node's headers yet, Core will refuse; wait and try again.")
            .setPositiveButton("Load") { _, _ ->
                if (ocupado) return@setPositiveButton
                tarea = null; barraSnap.isIndeterminate = true; fijarOcupado(true, "Loading into Core… this takes a while")
                Thread {
                    val msg = try {
                        val r = CoreNodo.rpc(act).llamar("loadtxoutset", JSONArray().put(f.absolutePath), 3 * 3600_000)
                        val o = r as? org.json.JSONObject
                        "Loaded ${o?.optLong("coins_loaded", 0L) ?: 0} coins at block ${o?.optLong("base_height", 0L) ?: 0}"
                    } catch (e: CoreRpcError) { "Core refused: ${e.message}" }
                      catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
                    fijarOcupado(false)
                    if (msg.startsWith("Loaded")) aviso(msg) else detalle("Snapshot not loaded", msg)
                    runOnUiThread { refrescar() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    // Sondeo mientras la pestaña está a la vista y la pantalla montada.
    val sondeo = object : Runnable {
        override fun run() {
            if (!scroll.isAttachedToWindow) return
            if (scroll.visibility == View.VISIBLE) { refrescar(); refrescarCarga(); refrescarRed() }
            h.postDelayed(this, 3_000)
        }
    }
    scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { h.removeCallbacks(sondeo); h.post(sondeo) }
        override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(sondeo) }
    })
    return scroll
}

private fun snapshotParcial(ctx: android.content.Context): Boolean =
    java.io.File(CoreNodo.snapshot(ctx).path + ".part").isFile
