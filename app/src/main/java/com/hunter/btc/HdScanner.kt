package com.hunter.btc

import org.json.JSONArray

/**
 * Recorrido de las ramas HD de una seed.
 *
 * La app sólo miraba los índices 0..2 de la rama de recepción, así que al
 * restaurar una seed usada en otra cartera no veía nada si los fondos estaban en
 * el índice 3 o el 7: parecía vacía sin estarlo. Y el cambio de cada envío
 * volvía a la misma dirección de la que se gastaba, dejando en la cadena la
 * constancia de qué salida era el cambio.
 *
 * Aquí está lo que arregla las dos cosas: buscar hacia delante hasta acumular
 * [GAP_LIMIT] direcciones seguidas sin estrenar, que es la convención de BIP44 y
 * lo que hacen las demás carteras.
 *
 * Todo hace red: nunca desde el hilo principal.
 */
object HdScanner {

    /** Direcciones seguidas sin usar que dan por terminada una rama (BIP44). */
    const val GAP_LIMIT = 20

    /** Tope duro, por si una respuesta rara mantuviera la búsqueda viva. */
    const val MAX_INDEX = 200
    private const val MAX_DESCONOCIDAS = 6

    private const val BATCH = 20

    val PURPOSES = listOf(44, 49, 84, 86)

    fun purposeLabel(p: Int) = when (p) {
        44   -> "P2PKH"
        49   -> "P2SH"
        84   -> "WPKH"
        86   -> "P2TR"
        else -> "?"
    }

    data class Found(val purpose: Int, val change: Int, val index: Int, val addr: String)

    private fun derive(mnemonic: String, purpose: Int, change: Int,
                       from: Int, count: Int, testnet: Boolean): List<Pair<Int, String>> =
        try {
            val arr = JSONArray(
                HunterEngine.deriveAddresses(mnemonic, purpose, change, from, count, testnet))
            (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val a = o.optString("addr", "")
                if (a.isEmpty()) null else o.optInt("i", -1) to a
            }
        } catch (e: Exception) {
            android.util.Log.e("HdScanner", "deriveAddresses failed: ${e.message}")
            emptyList()
        }

    /**
     * Direcciones usadas de una rama, más la primera sin estrenar.
     *
     * Se para tras [GAP_LIMIT] seguidas sin usar. Una dirección cuyo estado no
     * se pudo averiguar NO cuenta como sin usar: cortar ahí por un fallo de red
     * dejaría fondos fuera y haría reutilizar direcciones de cambio.
     *
     * @return las usadas en orden, y el índice de la primera libre (-1 si la
     *   red no dejó determinarlo).
     */
    fun scanBranch(mnemonic: String, purpose: Int, change: Int,
                   testnet: Boolean = false): Pair<List<Found>, Int> {
        val (usadas, libre, _) = scanBranchInformado(mnemonic, purpose, change, testnet)
        return usadas to libre
    }

    /**
     * Igual que scanBranch, y además dice si el recorrido se quedó a medias
     * (red caída, o una derivación que falló): entonces hay direcciones que no se
     * miraron y el saldo no es completo.
     */
    fun scanBranchInformado(mnemonic: String, purpose: Int, change: Int,
                            testnet: Boolean = false): Triple<List<Found>, Int, Boolean> {
        var incompleto = false
        val usadas = mutableListOf<Found>()
        var primeraLibre = -1
        var seguidasSinUsar = 0
        var desconocidasSeguidas = 0
        var idx = 0
        var cortado = false

        while (idx < MAX_INDEX && seguidasSinUsar < GAP_LIMIT && !cortado) {
            val lote = derive(mnemonic, purpose, change, idx, BATCH, testnet)
            if (lote.isEmpty()) { incompleto = true; break }
            for ((i, addr) in lote) {
                val usada = BalanceLookup.isUsed(addr, testnet)
                when (usada) {
                    true -> {
                        usadas.add(Found(purpose, change, i, addr))
                        seguidasSinUsar = 0
                        desconocidasSeguidas = 0
                        primeraLibre = -1
                    }
                    false -> {
                        if (primeraLibre < 0) primeraLibre = i
                        seguidasSinUsar++
                        desconocidasSeguidas = 0
                    }
                    // null: no se sabe. Ni cuenta como libre ni corta el hueco.
                    // Con la red caída seguirían saliendo null hasta el índice 200
                    // en cada rama: tras seis seguidas se para, y el resultado
                    // queda indeterminado (-1), no libre.
                    null -> {
                        seguidasSinUsar = 0
                        if (++desconocidasSeguidas >= MAX_DESCONOCIDAS) { cortado = true; incompleto = true; break }
                    }
                }
                if (seguidasSinUsar >= GAP_LIMIT) break
            }
            idx += lote.size
        }
        if (cortado) primeraLibre = -1
        return Triple(usadas, primeraLibre, incompleto)
    }

    /** Cuántas ramas se recorren a la vez: las ocho (4 tipos × recepción/cambio) harían ráfagas que los servidores públicos frenan. */
    const val RAMAS_A_LA_VEZ = 4

    /**
     * [scanBranchInformado] de varias ramas (propósito, cambio) a la vez, con el resultado de cada
     * una en el MISMO orden que se pidieron: quien lo use ve exactamente lo que vería uno a uno,
     * sólo que antes. Cada rama sigue siendo secuencial por dentro (su hueco de 20 depende del orden).
     */
    fun escanearRamas(mnemonic: String, ramas: List<Pair<Int, Int>>, testnet: Boolean = false,
                      hilos: Int = RAMAS_A_LA_VEZ): List<Triple<List<Found>, Int, Boolean>> {
        if (ramas.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(hilos.coerceIn(1, ramas.size)) { r ->
            Thread(r, "hd-rama").also { it.isDaemon = true }
        }
        try {
            val futuros = ramas.map { (purpose, change) ->
                pool.submit<Triple<List<Found>, Int, Boolean>> {
                    // Una rama que revienta no tumba a las demás: queda como incompleta.
                    try { scanBranchInformado(mnemonic, purpose, change, testnet) }
                    catch (e: Exception) { Triple(emptyList(), -1, true) }
                }
            }
            return futuros.map { it.get() }
        } finally { pool.shutdown() }
    }

    /**
     * Dirección de cambio sin estrenar para el próximo envío.
     *
     * @return null si la red no permitió saber cuál está libre. Quien llame debe
     *   entonces dejar que el motor use la dirección de origen: peor para la
     *   privacidad, pero el dinero vuelve a una dirección propia, que es lo que
     *   no se puede fallar.
     */
    fun nextChangePath(mnemonic: String, purpose: Int, testnet: Boolean = false): String? {
        val (_, libre) = scanBranch(mnemonic, purpose, change = 1, testnet = testnet)
        if (libre < 0) return null
        // El coin type tiene que coincidir con el que usó la derivación. Estaba
        // clavado a 0', así que en testnet se devolvía una ruta de mainnet y el
        // cambio de la transacción se habría ido a una rama distinta de la que
        // enseña la cartera.
        val coin = if (testnet) 1 else 0
        return "m/$purpose'/$coin'/${Frase.cuenta(mnemonic)}'/1/$libre"
    }
}
