package com.hunter.btc

import android.content.Context
import org.lightningdevkit.ldknode.Network

/**
 * El nodo Lightning de la app (uno, vivo mientras se use). Tiene su PROPIA frase de 24 palabras, distinta
 * de las carteras: LDK deriva su cartera en cadena por su cuenta y, con la misma frase, las dos se
 * pisarían las monedas. La frase va cifrada con la clave del baúl (hace falta el código para arrancar).
 *
 * OJO: la frase sola no recupera el dinero de los canales; el estado de los canales está en el almacén de
 * la app. Por eso es experimental y para importes pequeños, y la pantalla lo dice.
 */
object Ln {
    private const val PREFS = "lightning"
    @Volatile var motor: MotorLn? = null; private set
    @Volatile var testnet = false; private set

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Fuente de la cadena: "esplora", "electrum" o "core", y la URL (Esplora propio o servidor Electrum). */
    fun fuente(ctx: Context) = (p(ctx).getString("fuente", "esplora") ?: "esplora") to (p(ctx).getString("fuente_url", "") ?: "")
    fun ponerFuente(ctx: Context, f: String, url: String) = p(ctx).edit().putString("fuente", f).putString("fuente_url", url.trim()).apply()

    fun lsp(ctx: Context): Triple<String, String, String>? {
        val id = p(ctx).getString("lsp_id", "") ?: ""; val dir = p(ctx).getString("lsp_dir", "") ?: ""
        return if (id.length == 66 && dir.contains(':')) Triple(id, dir, p(ctx).getString("lsp_token", "") ?: "") else null
    }
    fun ponerLsp(ctx: Context, id: String, dir: String, token: String) =
        p(ctx).edit().putString("lsp_id", id.trim()).putString("lsp_dir", dir.trim()).putString("lsp_token", token.trim()).apply()

    /** La frase del nodo: la crea la primera vez. null si el baúl está cerrado. */
    fun frase(ctx: Context): String? {
        val dek = WalletManager.claveDeBoveda() ?: return null
        val pr = p(ctx)
        val e = pr.getString("frase_e", null); val iv = pr.getString("frase_iv", null)
        if (e != null && iv != null) return SeedEnvelope.abrir(dek, android.util.Base64.decode(e, 2), android.util.Base64.decode(iv, 2))?.let { String(it) }
        val nueva = Bip39.generate(24)
        val (se, si) = SeedEnvelope.sellar(dek, nueva.toByteArray())
        pr.edit().putString("frase_e", android.util.Base64.encodeToString(se, 2)).putString("frase_iv", android.util.Base64.encodeToString(si, 2)).commit()
        return nueva
    }

    fun existe(ctx: Context) = p(ctx).contains("frase_e")

    /** Arranca el nodo (en un hilo: tarda). Lanza si algo falla. */
    @Synchronized
    fun arrancar(ctx: Context, testnet: Boolean): MotorLn {
        motor?.let { if (this.testnet == testnet) return it else parar() }
        val f = frase(ctx) ?: throw IllegalStateException("Unlock the app first")
        val red = if (testnet) "testnet" else "mainnet"
        val dir = java.io.File(ctx.filesDir, "ln/$red").apply { mkdirs() }.path
        val (f0, url) = fuente(ctx)
        val cookie = if (f0 == "core") CoreNodo.cookie(ctx).takeIf { it.exists() }?.readText() else null
        val cadena = MotorLn.cadena(f0, url, testnet, cookie, CoreConfig.RPC_PORT)
        val rgs = if (testnet) "https://rapidsync.lightningdevkit.org/testnet/snapshot" else "https://rapidsync.lightningdevkit.org/snapshot"
        val m = MotorLn.construir(dir, if (testnet) Network.TESTNET else Network.BITCOIN, f, cadena, rgs, lsp = lsp(ctx))
        m.arrancar()
        this.testnet = testnet
        motor = m
        return m
    }

    @Synchronized
    fun parar() { try { motor?.parar() } catch (e: Exception) { RegistroFallos.anotar("Ln.parar", e) }; motor = null }
}
