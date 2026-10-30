package com.hunter.btc

import android.app.Activity
import android.content.Context
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recibir con Silent Payments (BIP352): una dirección sp1… fija que se puede publicar sin que los pagos queden
 * enlazados en la cadena. Cada pago llega a una dirección Taproot distinta que sólo esta semilla reconoce, así
 * que no aparece en el saldo normal: hay que escanear.
 *
 * Escanear cada transacción del bloque en el teléfono sería bajarse la cadena entera. Se usa un servidor de
 * tweaks (BlindBit Oracle): da por bloque los "tweaks" (input_hash · A de cada transacción candidata) y un
 * filtro BIP158 de las salidas Taproot nuevas. Con la clave de ESCANEO (nunca sale del teléfono) se calcula
 * qué salida sería nuestra y sólo se bajan las salidas de los bloques en que el filtro dice que puede haberla.
 * El servidor ve qué bloques se piden, como cualquier servidor de bloques, no las claves ni las direcciones.
 *
 * Sin etiquetas (sólo la dirección base). Lo encontrado se gasta barriéndolo a la cartera: P2TR por la clave
 * con la privada gasto + t, firmado aquí (SpRecibirTest comprueba claves y firmas con los vectores de BIP352).
 */
object SpRecibir {
    private const val PREFS = "sp_recibir"
    const val SERVIDOR_MAIN = "https://silentpayments.dev/blindbit/mainnet"
    private val ec = SilentPayments.Ec { op, a, b -> HunterEngine.ec(op, a, b) }

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun red(t: Boolean) = if (t) "test" else "main"
    fun servidor(ctx: Context, t: Boolean) = p(ctx).getString("srv_${red(t)}", if (t) "" else SERVIDOR_MAIN) ?: ""

    /** Un pago encontrado. [t] es el ajuste de su privada. */
    class Pago(val txid: String, val vout: Int, val valor: Long, val x: String, val t: String, val altura: Long, var gastada: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("txid", txid).put("vout", vout).put("valor", valor).put("x", x).put("t", t).put("h", altura).put("g", gastada)
        companion object { fun de(o: JSONObject) = Pago(o.getString("txid"), o.getInt("vout"), o.getLong("valor"), o.getString("x"), o.getString("t"), o.optLong("h"), o.optBoolean("g")) }
    }

    fun monedas(ctx: Context, t: Boolean): List<Pago> = try {
        val a = JSONArray(p(ctx).getString("monedas_${red(t)}", "[]")); List(a.length()) { Pago.de(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
    private fun guardar(ctx: Context, t: Boolean, l: List<Pago>) =
        p(ctx).edit().putString("monedas_${red(t)}", JSONArray().apply { l.forEach { put(it.json()) } }.toString()).apply()

    // ── Servidor ──

    private fun get(url: String): String? = try {
        val c = RedPrivada.abrir(url); c.connectTimeout = 15_000; c.readTimeout = 30_000
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    } catch (e: Exception) { null }

    /** Los tweaks de un bloque; lista vacía si no hay; null si el servidor no responde. */
    private fun tweaks(srv: String, h: Long): List<String>? {
        val s = get("$srv/tweaks/$h") ?: return null
        val t = s.trim(); if (t.isEmpty() || t == "null") return emptyList()
        val a = if (t.startsWith("{")) JSONObject(t).optJSONArray("tweaks") ?: JSONArray() else JSONArray(t)
        return List(a.length()) { a.getString(it) }.filter { it.length == 66 }
    }

    /** Las salidas Taproot nuevas del bloque: (txid, vout, valor, x-only). */
    private fun utxos(srv: String, h: Long): List<Pago>? {
        val s = get("$srv/utxos/$h") ?: return null
        val a = JSONArray(s.trim().ifEmpty { "[]" }.let { if (it == "null") "[]" else it })
        return List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
            val spk = o.optString("scriptpubkey", o.optString("script_pub_key"))
            if (spk.length != 68 || !spk.startsWith("5120")) null
            else Pago(o.getString("txid"), o.getInt("vout"), o.optLong("value", o.optLong("amount")), spk.substring(4), "", h, o.optBoolean("spent"))
        }
    }

    /** (datos, hash del bloque) del filtro de salidas Taproot nuevas; null si no lo hay. */
    private fun filtro(srv: String, h: Long): Pair<String, String>? = try {
        val o = JSONObject(get("$srv/filter/new-utxos/$h") ?: "")
        val d = o.optString("data"); val b = o.optString("block_hash")
        if (d.isEmpty() || d.length % 2 != 0 || b.length != 64) null else d to b
    } catch (e: Exception) { null }

    // ── Escaneo ──

    class Claves(val escaneo: String, val gastoPub: String, val gasto: String)

    fun claves(mnemonic: String, testnet: Boolean): Claves? {
        val e = HunterEngine.deriveRuta(mnemonic, SilentPayments.rutaEscaneo(testnet)); val g = HunterEngine.deriveRuta(mnemonic, SilentPayments.rutaGasto(testnet))
        if (e.length != 64 || g.length != 64) return null
        val gp = HunterEngine.ec("pub", g, "").ifEmpty { return null }
        return Claves(e, gp, g)
    }

    fun direccion(k: Claves, testnet: Boolean): String =
        SilentPayments.codificar(Descriptor.bytes(HunterEngine.ec("pub", k.escaneo, "")), Descriptor.bytes(k.gastoPub), testnet)

    /**
     * Escanea de [desde] a [hasta] (incluidos). [progreso] recibe la altura en curso; [seguir] false para parar.
     * Devuelve las nuevas encontradas y un error si se cortó (y en qué altura se quedó, para seguir luego).
     */
    fun escanear(ctx: Context, k: Claves, testnet: Boolean, desde: Long, hasta: Long,
                 progreso: (Long) -> Unit, seguir: () -> Boolean): Pair<List<Pago>, String?> {
        val srv = servidor(ctx, testnet).trimEnd('/')
        if (srv.isEmpty()) return emptyList<Pago>() to "No tweak server set"
        val nuevas = ArrayList<Pago>()
        val ya = monedas(ctx, testnet).map { it.txid to it.vout }.toHashSet()
        // El filtro se comprueba con el primer bloque en que se bajan las salidas: si no contiene ninguna de
        // ellas (otro formato de servidor), se deja de usar y se bajan siempre.
        var filtroFiable: Boolean? = null
        var h = desde
        while (h <= hasta) {
            if (!seguir()) break
            progreso(h)
            val tw = tweaks(srv, h) ?: return nuevas.also { anotar(ctx, testnet, it, h - 1) } to "The server did not answer at block $h"
            if (tw.isNotEmpty()) {
                val secretos = tw.mapNotNull { SilentPayments.secreto(ec, k.escaneo, it) }
                val primeras = secretos.mapNotNull { SilentPayments.salidaK(ec, it, k.gastoPub, 0)?.first }
                // Con el filtro ya comprobado, sólo se bajan las salidas si puede haber alguna nuestra.
                var bajar = true
                if (filtroFiable == true) {
                    val f = filtro(srv, h)
                    val el = primeras.flatMap { x -> listOf(Descriptor.bytes(x), Descriptor.bytes("5120$x")) }
                    bajar = f == null || Gcs.contieneAlguno(Descriptor.bytes(f.first), f.second, el) != false
                }
                if (bajar) {
                    val us = utxos(srv, h) ?: return nuevas.also { anotar(ctx, testnet, it, h - 1) } to "The server did not answer at block $h"
                    if (filtroFiable == null && us.isNotEmpty()) {
                        val f = filtro(srv, h)
                        val muestra = us.take(8).flatMap { listOf(Descriptor.bytes(it.x), Descriptor.bytes("5120${it.x}")) }
                        filtroFiable = f != null && Gcs.contieneAlguno(Descriptor.bytes(f.first), f.second, muestra) == true
                    }
                    val porX = us.associateBy { it.x }
                    val xs = porX.keys
                    for (s in tw) for (r in SilentPayments.escanear(ec, k.escaneo, k.gastoPub, s, xs)) {
                        val u = porX[r.x] ?: continue
                        if (u.txid to u.vout in ya) continue
                        nuevas.add(Pago(u.txid, u.vout, u.valor, r.x, r.t, h, u.gastada)); ya.add(u.txid to u.vout)
                    }
                }
            }
            h++
        }
        anotar(ctx, testnet, nuevas, h - 1)
        return nuevas to null
    }

    private fun anotar(ctx: Context, t: Boolean, nuevas: List<Pago>, ultima: Long) {
        if (nuevas.isNotEmpty()) guardar(ctx, t, monedas(ctx, t) + nuevas)
        if (ultima >= 0) p(ctx).edit().putLong("ultima_${red(t)}", ultima).apply()
    }

    /** Marca como gastadas las que ya lo están en la cadena. */
    private fun refrescarGastadas(ctx: Context, t: Boolean) {
        val l = monedas(ctx, t)
        for (m in l.filter { !it.gastada }) {
            val o = ChainApi.get("/tx/${m.txid}/outspend/${m.vout}", t)?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: continue
            if (o.optBoolean("spent")) m.gastada = true
        }
        guardar(ctx, t, l)
    }

    // ── Barrer ──

    /** Transacción firmada (hex) que lleva [ms] a [destinoSpk] a [tasa] sat/vB; null si no da para la comisión. */
    fun barrido(k: Claves, ms: List<Pago>, destinoSpk: ByteArray, tasa: Long): Pair<String, Long>? {
        val vb = 11 + 58L * ms.size + 9 + destinoSpk.size
        val total = ms.sumOf { it.valor }; val comision = vb * tasa
        if (total - comision <= CoinSelector.DUST) return null
        val ent = ms.map { Bip322.Entrada(Descriptor.bytes(it.txid).reversedArray(), it.vout.toLong(), ByteArray(0), 0xfffffffdL, emptyList()) }
        val tx = Bip322.Tx(2, ent, listOf(Bip322.Salida(total - comision, destinoSpk)), 0)
        val previas = ms.map { Bip322.Previa(it.valor, Descriptor.bytes("5120${it.x}")) }
        val firmadas = ms.mapIndexed { i, m ->
            val d = SilentPayments.privada(ec, k.gasto, m.t) ?: return null
            if (HunterEngine.ec("pub", d, "").substring(2) != m.x) return null
            val aux = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val sig = HunterEngine.ec("schnorr", d, Descriptor.hex(Bip322.sighashTaproot(tx, i, previas, 0)) + "|" + Descriptor.hex(aux))
            if (sig.length != 128) return null
            Bip322.Entrada(ent[i].txid, ent[i].vout, ByteArray(0), ent[i].secuencia, listOf(Descriptor.bytes(sig)))
        }
        return Descriptor.hex(Bip322.serializar(Bip322.Tx(2, firmadas, tx.salidas, 0))) to comision
    }

    // ── Pantalla ──

    fun mostrar(act: Activity, mnemonic: String, testnet: Boolean) {
        val k = claves(mnemonic, testnet) ?: run { Toast.makeText(act, "Could not derive the Silent Payments keys.", Toast.LENGTH_LONG).show(); return }
        val dir = direccion(k, testnet)
        val d = (16 * act.resources.displayMetrics.density).toInt()
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d, d / 2, d, 0) }
        fun t(s: String, c: Int = AppTheme.TXT_SEC) = TextView(act).apply { text = s; textSize = AppTheme.SP_CAPTION; setTextColor(c); setPadding(0, d / 3, 0, d / 4) }
        caja.addView(t("Share this address once and reuse it forever: each payment lands on a different, unlinkable address " +
            "that only this wallet can find. Payments do NOT show in the normal balance: scan for them here."))
        caja.addView(ImageView(act).apply {
            setImageBitmap(QrAnimado.qr(dir.uppercase(), 600)); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(d * 14, d * 14).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        })
        caja.addView(TextView(act).apply {
            text = dir; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.mono(act); setTextIsSelectable(true)
            setOnClickListener {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("sp", dir))
                Toast.makeText(act, "Address copied", Toast.LENGTH_SHORT).show()
            }
        })
        val tvSaldo = t("", AppTheme.TXT_PRI)
        val tvEstado = t("")
        caja.addView(tvSaldo); caja.addView(tvEstado)
        fun pintar() {
            val l = monedas(act, testnet).filter { !it.gastada }
            tvSaldo.text = if (l.isEmpty()) "Nothing found yet." else "Found: %,d sat in %d payment(s)".format(l.sumOf { it.valor }, l.size) + Fiat.entre(act, l.sumOf { it.valor })
            val ult = p(act).getLong("ultima_${red(testnet)}", -1)
            tvEstado.text = (if (ult >= 0) "Scanned up to block %,d".format(ult) else "Not scanned yet") +
                " · server: ${servidor(act, testnet).ifEmpty { "none" }}"
        }
        pintar()
        val dlg = AlertDialog.Builder(act).setTitle("Silent Payments").setView(android.widget.ScrollView(act).apply { addView(caja) })
            .setNegativeButton("Close", null).setPositiveButton("Scan", null).setNeutralButton("More…", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { escanearUi(act, k, testnet, null) { pintar() } }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(act).setItems(arrayOf("Sweep found coins to this wallet", "Scan from a block…", "Tweak server…")) { _, i ->
                    when (i) {
                        0 -> barrerUi(act, k, mnemonic, testnet) { pintar() }
                        1 -> {
                            val et = Ui.input(act, "Block height (e.g. when you first shared the address)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                            AlertDialog.Builder(act).setTitle("Scan from block").setView(et).setNegativeButton("Cancel", null)
                                .setPositiveButton("Scan") { _, _ -> et.text.toString().toLongOrNull()?.let { h -> escanearUi(act, k, testnet, h) { pintar() } } }.show()
                        }
                        else -> {
                            val et = Ui.input(act, "https://…", mono = true).apply { setText(servidor(act, testnet)) }
                            AlertDialog.Builder(act).setTitle("Tweak server (BlindBit Oracle)")
                                .setMessage("It serves per-block tweaks and filters. It sees which blocks you ask for, not your keys. You can run your own next to your Bitcoin Core node.")
                                .setView(et).setNegativeButton("Cancel", null)
                                .setPositiveButton("Save") { _, _ -> p(act).edit().putString("srv_${red(testnet)}", et.text.toString().trim().trimEnd('/')).apply(); pintar() }.show()
                        }
                    }
                }.show()
            }
        }
        dlg.show()
        // La primera vez, la "fecha de nacimiento": desde aquí, no antes (la dirección acaba de enseñarse).
        if (!p(act).contains("desde_${red(testnet)}")) Thread {
            ChainInfo.tipHeight(testnet)?.let { h -> p(act).edit().putLong("desde_${red(testnet)}", h.toLong()).putLong("ultima_${red(testnet)}", h.toLong() - 1).apply(); act.runOnUiThread { pintar() } }
        }.start()
    }

    private fun escanearUi(act: Activity, k: Claves, testnet: Boolean, desde0: Long?, listo: () -> Unit) {
        val seguir = java.util.concurrent.atomic.AtomicBoolean(true)
        val tv = TextView(act).apply { text = "Starting…"; setPadding(48, 32, 48, 16); setTextColor(AppTheme.TXT_PRI) }
        val espera = AlertDialog.Builder(act).setTitle("Scanning").setView(tv).setNegativeButton("Stop") { _, _ -> seguir.set(false) }.setCancelable(false).create()
        espera.show()
        Thread {
            val tip = ChainInfo.tipHeight(testnet)?.toLong()
            if (tip == null) { act.runOnUiThread { espera.dismiss(); Toast.makeText(act, "No network.", Toast.LENGTH_LONG).show() }; return@Thread }
            val desde = desde0 ?: (p(act).getLong("ultima_${red(testnet)}", tip - 1) + 1)
            val (nuevas, error) = try {
                escanear(act, k, testnet, desde, tip, { h -> act.runOnUiThread { tv.text = "Block %,d of %,d (%d left)".format(h, tip, tip - h) } }, { seguir.get() })
            } catch (e: Exception) { RegistroFallos.anotar("SpRecibir.escanear", e); emptyList<Pago>() to (e.message ?: "error") }
            refrescarGastadas(act, testnet)
            act.runOnUiThread {
                espera.dismiss(); listo()
                val msg = (if (nuevas.isEmpty()) "No new payments." else "Found %d new payment(s): %,d sat.".format(nuevas.size, nuevas.sumOf { it.valor })) +
                    (error?.let { "\n\nStopped: $it. Scan again to continue." } ?: "")
                AlertDialog.Builder(act).setTitle("Scan finished").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun barrerUi(act: Activity, k: Claves, mnemonic: String, testnet: Boolean, listo: () -> Unit) {
        Thread {
            refrescarGastadas(act, testnet)
            val ms = monedas(act, testnet).filter { !it.gastada }
            val tasa = ChainInfo.fees(testnet)?.halfHour?.toLong()?.coerceAtLeast(1) ?: 2L
            val destino = try {
                val libre = HdScanner.scanBranch(mnemonic, 84, 0, testnet).second
                if (libre < 0) null else JSONArray(HunterEngine.deriveAddresses(mnemonic, 84, 0, libre, 1, testnet)).getJSONObject(0).getString("addr")
            } catch (e: Exception) { null }
            val spk = destino?.let { (BtcAddress.validate(it, testnet) as? BtcAddress.Result.Valid)?.let { v -> BtcAddress.scriptPubKey(v.info) } }
            val tx = if (ms.isEmpty() || spk == null) null else barrido(k, ms, spk, tasa)
            act.runOnUiThread {
                when {
                    ms.isEmpty() -> Toast.makeText(act, "Nothing to sweep.", Toast.LENGTH_LONG).show()
                    spk == null -> Toast.makeText(act, "Could not get an address of this wallet.", Toast.LENGTH_LONG).show()
                    tx == null -> Toast.makeText(act, "The coins do not cover the fee at $tasa sat/vB.", Toast.LENGTH_LONG).show()
                    else -> AlertDialog.Builder(act).setTitle("Sweep to this wallet?")
                        .setMessage("%,d sat from %d payment(s) to\n%s\n\nFee: %,d sat (%d sat/vB). A Bitcoin transaction cannot be undone."
                            .format(ms.sumOf { it.valor } - tx.second, ms.size, destino, tx.second, tasa))
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Sweep") { _, _ ->
                            Thread {
                                val r = ChainInfo.broadcast(tx.first, testnet)
                                if (!r.startsWith("ERROR")) refrescarGastadas(act, testnet)
                                act.runOnUiThread {
                                    listo()
                                    AlertDialog.Builder(act).setTitle(if (r.startsWith("ERROR")) "Not sent" else "Sent")
                                        .setMessage(if (r.startsWith("ERROR")) r.removePrefix("ERROR: ") else "Transaction ID:\n$r").setPositiveButton("OK", null).show()
                                }
                            }.start()
                        }.show()
                }
            }
        }.start()
    }
}
