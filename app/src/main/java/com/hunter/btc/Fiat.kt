package com.hunter.btc

import android.content.Context
import org.json.JSONObject

/**
 * El precio de 1 BTC en la moneda elegida (menú "Fiat currency"), para enseñar junto a
 * cada cantidad de bitcoin lo que vale en dinero normal.
 *
 * Una sola consulta compartida: la caché vive 5 minutos y el último precio se guarda, así
 * que sin red se sigue enseñando el último conocido en vez de quitar los importes. Es el
 * precio de AHORA, no el del día de la transacción. Sólo mainnet: en testnet no vale nada.
 */
object Fiat {
    private const val TTL_MS = 5 * 60_000L
    private const val PREFS = "hunter"

    @Volatile private var precio = 0.0
    @Volatile private var codigoCargado = ""
    @Volatile private var cuando = 0L
    private var pidiendo = false
    private val esperando = ArrayList<() -> Unit>()

    fun codigo(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fiat_code", "USD") ?: "USD"

    /** El último precio conocido (de esta sesión o guardado), o 0 si no se sabe. */
    fun precio(ctx: Context): Double {
        val c = codigo(ctx)
        if (c != codigoCargado) {
            // Cambió la moneda: se parte del último precio guardado de ESA moneda.
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            precio = p.getString("fiat_precio_$c", null)?.toDoubleOrNull() ?: 0.0
            codigoCargado = c; cuando = 0L
        }
        return precio
    }

    private fun vencido() = System.currentTimeMillis() - cuando > TTL_MS

    /** Fija el precio sin consultar (lo usa el saldo, que ya lo ha pedido). */
    fun fijar(ctx: Context, codigo: String, valor: Double) {
        if (valor <= 0 || !valor.isFinite()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("fiat_precio_$codigo", valor.toString()).apply()
        if (codigo == codigo(ctx)) { precio = valor; codigoCargado = codigo; cuando = System.currentTimeMillis() }
    }

    /**
     * Pide el precio si hace falta y avisa al terminar (desde un hilo de fondo: quien
     * llama pasa a la interfaz si lo necesita). Varias peticiones a la vez comparten una consulta.
     */
    fun cargar(ctx: Context, alTerminar: () -> Unit) {
        val app = ctx.applicationContext
        precio(app)
        synchronized(this) {
            if (precio > 0 && !vencido()) { Thread { alTerminar() }.start(); return }
            esperando.add(alTerminar)
            if (pidiendo) return
            pidiendo = true
        }
        Thread {
            try {
                val c = codigo(app)
                val js = ChainApi.get("/v1/prices", false)
                val v = if (js != null) JSONObject(js).optDouble(c, 0.0) else 0.0
                fijar(app, c, v)
            } catch (e: Exception) { }
            val cb = synchronized(this) { val c = ArrayList(esperando); esperando.clear(); pidiendo = false; c }
            for (f in cb) try { f() } catch (e: Exception) { }
        }.start()
    }

    fun vencidoONulo(ctx: Context) = precio(ctx) <= 0.0 || vencido()

    /** "1,234.56 USD", o null si no se sabe el precio. */
    fun de(ctx: Context, sat: Long): String? {
        val p = precio(ctx)
        if (p <= 0) return null
        val v = Math.abs(sat) / 1e8 * p
        return (if (v > 0 && v < 0.01) "<0.01" else "%,.2f".format(v)) + " " + codigo(ctx)
    }

    /**
     * Pone en [tv] `base` + el valor en dinero normal de [sat]. Si aún no se sabe el precio
     * lo pide y lo completa al llegar (siempre que el texto siga siendo `base...`).
     */
    fun poner(tv: android.widget.TextView, base: String, sat: Long) {
        val ctx = tv.context
        tv.text = base + entre(ctx, sat)
        if (vencidoONulo(ctx)) cargar(ctx) {
            tv.post { if (tv.text.toString().startsWith(base)) tv.text = base + entre(ctx, sat) }
        }
    }

    /** Pone en [tv] `prefijo` + el valor en dinero normal (también cero); "…" mientras no se sabe el precio. */
    fun ponerSolo(tv: android.widget.TextView, sat: Long, prefijo: String = "≈ ") {
        val ctx = tv.context
        tv.text = prefijo + (de(ctx, sat) ?: "…")
        if (vencidoONulo(ctx)) cargar(ctx) { tv.post { tv.text = prefijo + (de(ctx, sat) ?: "—") } }
    }

    /** "  (≈ 1,234.56 USD)" para pegar a un texto; vacío si no se sabe. */
    fun entre(ctx: Context, sat: Long): String = de(ctx, sat)?.let { "  (≈ $it)" } ?: ""
}
