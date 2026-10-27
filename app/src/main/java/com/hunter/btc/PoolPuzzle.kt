package com.hunter.btc

import android.content.Context
import android.content.Intent

/**
 * Minar para un pool del puzzle: pedir un trozo, barrerlo con el motor (secuencial, con la dirección del
 * puzzle como objetivo y los testigos del servidor), devolver los testigos y pedir el siguiente.
 *
 * Usa el mismo motor que la pestaña Puzzle: sólo trabaja si está parado, y si el usuario arranca otra
 * cosa, el trozo en curso se abandona (el servidor lo vuelve a repartir pasadas unas horas).
 * La clave del puzzle, si sale, va además al baúl como cualquier hallazgo.
 */
object PoolPuzzle {
    private const val PREFS = "pool_puzzle"
    @Volatile var estado: String = "Off"; private set
    @Volatile private var hilo: Thread? = null
    @Volatile private var parar = false

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun url(ctx: Context) = p(ctx).getString("url", "") ?: ""
    fun ponerUrl(ctx: Context, u: String) = p(ctx).edit().putString("url", u.trim().trimEnd('/')).apply()
    private fun cliente(ctx: Context) = p(ctx).getString("cliente", null) ?: java.util.UUID.randomUUID().toString().take(12).also { p(ctx).edit().putString("cliente", it).apply() }
    fun activo() = hilo?.isAlive == true

    /** https, o http a un .onion (por Tor). */
    fun urlValida(u: String) = u.startsWith("https://") || (u.startsWith("http://") && (java.net.URI(u).host ?: "").endsWith(".onion"))

    fun empezar(ctx: Context, hilos: Int, cpu: Int) {
        if (activo()) return
        val app = ctx.applicationContext
        val base = url(app)
        if (!urlValida(base)) { estado = "The pool URL must be https:// (or http:// to a .onion)"; return }
        parar = false
        hilo = Thread({ bucle(app, base, hilos, cpu) }, "pool-puzzle").also { it.isDaemon = true; it.start() }
    }

    fun detener() {
        parar = true
        try { if (HunterEngine.isRunning()) HunterEngine.stopHunting() } catch (e: Throwable) {}
    }

    private fun http(url: String, post: String? = null): String? = try {
        val c = RedPrivada.abrir(url)
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        if (post != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(post.toByteArray()) } }
        try { if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().takeIf { b -> b.size < 100_000 }?.let { b -> String(b) } } }
        finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun bucle(ctx: Context, base: String, hilos: Int, cpu: Int) {
        var hechos = 0
        while (!parar) {
            if (HunterEngine.isRunning()) { estado = "Waiting: the engine is busy with another scan"; Thread.sleep(10_000); continue }
            estado = "Asking the pool for a range…"
            val t = http("$base/trabajo?cliente=${cliente(ctx)}")?.let { PoolProtocolo.leerTrabajo(it) }
            if (t == null) { estado = "No answer (or an invalid job) from the pool; retrying in 1 min"; Thread.sleep(60_000); continue }
            if (!HunterEngine.poolCanarios(t.testigos.joinToString(","))) { estado = "Could not set the proof addresses"; Thread.sleep(10_000); continue }
            HunterEngine.setTarget(t.objetivo)
            HunterEngine.setRange(t.inicio, t.fin)
            HunterEngine.setSequential(true)
            HunterEngine.setMode(1)
            HunterEngine.startHunting(hilos, cpu)
            try { ctx.startForegroundService(Intent(ctx, HunterService::class.java)) } catch (e: Exception) {}
            val desde = System.currentTimeMillis()
            while (HunterEngine.isRunning() && !parar) {
                val seg = (System.currentTimeMillis() - desde) / 1000
                estado = "Scanning range #${t.id} of puzzle ${t.puzzle} · ${seg}s · %,d keys/s".format(HunterEngine.getWps().toLong())
                Thread.sleep(2000)
            }
            val completo = HunterEngine.rangoCompleto() || HunterEngine.objetivoHallado()
            val testigos = PoolProtocolo.testigosHallados(HunterEngine.poolCanariosHallados(), t)
            HunterEngine.poolCanarios("")
            if (!completo || parar) { estado = if (parar) "Off" else "Range interrupted; it will be handed out again"; continue }
            val hallada = if (HunterEngine.objetivoHallado()) PoolProtocolo.claveDe(HunterEngine.getMatches(), t.objetivo) else null
            val r = http("$base/resultado", PoolProtocolo.resultado(t, cliente(ctx), testigos, hallada))
            hechos++
            estado = if (r?.contains("\"ok\": true") == true || r?.contains("\"ok\":true") == true) "Range #${t.id} accepted · $hechos done this session"
                     else "The pool did not accept range #${t.id}: ${r ?: "no answer"}"
            if (hallada != null) { estado = "PUZZLE KEY FOUND and sent to the pool (also in your vault)"; parar = true }
        }
        estado = "Off"
    }
}
