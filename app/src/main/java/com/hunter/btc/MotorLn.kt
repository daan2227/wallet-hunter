package com.hunter.btc

import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.ElectrumSyncConfig
import org.lightningdevkit.ldknode.EsploraSyncConfig
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Node

/**
 * Lightning con LDK Node (Lightning Dev Kit): un nodo no custodial dentro de la app. Las claves, los
 * canales y su estado viven en el almacén privado de la app ([dir]); la cadena se sigue por Esplora (o el
 * bitcoind propio) y el grafo de rutas por Rapid Gossip Sync.
 *
 * Aquí sólo lo que la pantalla necesita, sin Android: se prueba con la misma librería para JVM contra un
 * bitcoind de regtest (dos nodos, canal, factura, pago; ver PENDIENTES.md).
 */
class MotorLn(val node: Node) {

    data class Canal(val id: String, val usuarioId: String, val par: String, val capacidad: Long,
                     val salienteSat: Long, val entranteSat: Long, val listo: Boolean, val confirmaciones: Int)

    companion object {
        /**
         * Construye el nodo. [chain]: ("esplora", url), ("electrum", "ssl://host:puerto" o "tcp://…") o
         * ("bitcoind", "host:puerto:usuario:clave"); ver [cadena].
         * [lsp]: (nodeId, host:puerto, token o "") para recibir con canal "justo a tiempo" (LSPS2), o null.
         */
        fun construir(dir: String, red: Network, frase: String, chain: Pair<String, String>,
                      rgs: String?, escuchar: List<String> = emptyList(), lsp: Triple<String, String, String>? = null): MotorLn {
            val b = Builder()
            b.setNetwork(red)
            b.setStorageDirPath(dir)
            b.setFilesystemLogger("$dir/ldk.log", LogLevel.INFO)
            b.setEntropyBip39Mnemonic(frase, null)
            when (chain.first) {
                "bitcoind" -> { val p = chain.second.split(":", limit = 4); b.setChainSourceBitcoindRpc(p[0], p[1].toUShort(), p[2], p[3]) }
                "electrum" -> b.setChainSourceElectrum(chain.second, ElectrumSyncConfig(null))
                else -> b.setChainSourceEsplora(chain.second, EsploraSyncConfig(null))
            }
            if (rgs != null) b.setGossipSourceRgs(rgs) else b.setGossipSourceP2p()
            if (escuchar.isNotEmpty()) b.setListeningAddresses(escuchar)
            lsp?.let { (id, dirLsp, token) -> b.setLiquiditySourceLsps2(id, dirLsp, token.ifEmpty { null }) }
            return MotorLn(b.build())
        }

        /**
         * De dónde sigue la cadena el nodo: [fuente] "esplora" (URL https, o la de blockstream.info), "electrum"
         * (un servidor Electrum: "ssl://host:puerto" o "tcp://host:puerto", el propio en casa por ejemplo) o "core"
         * (el Bitcoin Core de la pestaña Core, por RPC en 127.0.0.1 con su cookie "usuario:clave").
         * Lanza IllegalArgumentException con un motivo legible si algo no vale.
         */
        fun cadena(fuente: String, url: String, testnet: Boolean, cookie: String?, puertoCore: Int): Pair<String, String> = when (fuente) {
            "electrum" -> {
                val u = url.trim()
                require(Regex("^(ssl|tcp)://[A-Za-z0-9.-]+:\\d{1,5}$").matches(u)) { "Electrum server: use ssl://host:port (or tcp:// for one on your own network)" }
                "electrum" to u
            }
            "core" -> {
                require(!testnet) { "Your Core node runs on mainnet: choose Esplora or Electrum for testnet" }
                val c = cookie?.trim() ?: throw IllegalArgumentException("Start Bitcoin Core first (Core tab): it has no RPC cookie yet")
                require(c.contains(':') && !c.contains('\n')) { "Unexpected Core cookie" }
                "bitcoind" to "127.0.0.1:$puertoCore:$c"
            }
            else -> {
                val u = url.trim().trimEnd('/').ifEmpty { if (testnet) "https://blockstream.info/testnet/api" else "https://blockstream.info/api" }
                require(u.startsWith("https://") || Regex("^http://(127\\.0\\.0\\.1|localhost|10\\.|192\\.168\\.).*").matches(u)) { "Esplora: use an https:// URL" }
                "esplora" to u
            }
        }

        /** "nodeId@host:puerto" → (nodeId, host:puerto); null si no tiene esa forma. */
        fun leerPar(s: String): Pair<String, String>? {
            val t = s.trim(); val a = t.indexOf('@'); if (a != 66) return null
            val id = t.substring(0, 66).lowercase(); val dir = t.substring(67)
            if (!id.all { it in "0123456789abcdef" } || !(id.startsWith("02") || id.startsWith("03")) || !dir.contains(':')) return null
            return id to dir
        }
    }

    fun id(): String = node.nodeId()
    fun arrancar() = node.start()
    fun parar() = node.stop()
    fun sincronizar() = node.syncWallets()

    /** (en cadena gastable, en canales). */
    fun saldos(): Pair<Long, Long> = node.listBalances().let { it.spendableOnchainBalanceSats.toLong() to it.totalLightningBalanceSats.toLong() }

    fun canales(): List<Canal> = node.listChannels().map {
        Canal(it.channelId, it.userChannelId, it.counterpartyNodeId, it.channelValueSats.toLong(),
              it.outboundCapacityMsat.toLong() / 1000, it.inboundCapacityMsat.toLong() / 1000, it.isChannelReady,
              it.confirmations?.toInt() ?: 0)
    }

    fun direccionCadena(): String = node.onchainPayment().newAddress()

    /** Abre un canal (no anunciado) con [par] = "nodeId@host:puerto" de [sats]. Devuelve el id de usuario del canal. */
    fun abrirCanal(par: String, sats: Long): String {
        val (id, dir) = leerPar(par) ?: throw IllegalArgumentException("Use nodeId@host:port")
        return node.openChannel(id, dir, sats.toULong(), null, null)
    }

    fun cerrarCanal(c: Canal) = node.closeChannel(c.usuarioId, c.par)

    /** Factura BOLT11 de [sats] (null = importe libre) que caduca en [segundos]. */
    fun factura(sats: Long?, descripcion: String, segundos: Int = 3600, jit: Boolean = false): String {
        val d = Bolt11InvoiceDescription.Direct(descripcion.take(120))
        val b = node.bolt11Payment()
        // jit: sin capacidad de entrada, el LSP (LSPS2) abre el canal al cobrar, descontando su comisión.
        val inv = when {
            jit && sats == null -> b.receiveVariableAmountViaJitChannel(d, segundos.toUInt(), null)
            jit -> b.receiveViaJitChannel((sats!! * 1000).toULong(), d, segundos.toUInt(), null)
            sats == null -> b.receiveVariableAmount(d, segundos.toUInt())
            else -> b.receive((sats * 1000).toULong(), d, segundos.toUInt())
        }
        return inv.toString()
    }

    /**
     * QR unificado (BIP21 con "lightning="): "bitcoin:<dirección>?amount=…&lightning=<factura>". Quien paga
     * elige en cadena o Lightning; las dos llegan a este nodo.
     */
    fun qrUnificado(sats: Long, descripcion: String, segundos: Int = 3600): String =
        node.unifiedQrPayment().receive(sats.toULong(), descripcion.take(120), segundos.toUInt())

    /** Capacidad de entrada total de los canales listos (sat). */
    fun entrante(): Long = canales().filter { it.listo }.sumOf { it.entranteSat }

    /** Lo que dice una factura antes de pagarla: (sat o null si es de importe libre, descripción, red). */
    fun leerFactura(s: String): Triple<Long?, String, Network> {
        val inv = Bolt11Invoice.fromStr(s.trim().removePrefix("lightning:").removePrefix("LIGHTNING:"))
        val desc = when (val d = inv.invoiceDescription()) { is Bolt11InvoiceDescription.Direct -> d.description; else -> "" }
        return Triple(inv.amountMilliSatoshis()?.toLong()?.div(1000), desc, inv.network())
    }

    /** Paga una factura; [satsSiLibre] si es de importe libre. Devuelve el id del pago (el resultado llega por eventos). */
    fun pagar(s: String, satsSiLibre: Long? = null): String {
        val inv = Bolt11Invoice.fromStr(s.trim().removePrefix("lightning:").removePrefix("LIGHTNING:"))
        return if (inv.amountMilliSatoshis() == null) node.bolt11Payment().sendUsingAmount(inv, ((satsSiLibre ?: 0) * 1000).toULong(), null)
               else node.bolt11Payment().send(inv, null)
    }

    /** Todo lo de la cartera en cadena del nodo a [direccion] (sin tocar la reserva de los canales). */
    fun vaciarCadena(direccion: String): String = node.onchainPayment().sendAllToAddress(direccion, true, null)

    /** El siguiente evento como texto para enseñarlo, o null si no hay. Lo marca como atendido. */
    fun siguienteEvento(): String? {
        val e = node.nextEvent() ?: return null
        val t = when (e) {
            is Event.PaymentSuccessful -> "Payment sent (fee ${(e.feePaidMsat?.toLong() ?: 0) / 1000} sat)"
            is Event.PaymentFailed -> "Payment failed: ${e.reason ?: "unknown"}"
            is Event.PaymentReceived -> "Received ${e.amountMsat.toLong() / 1000} sat"
            is Event.ChannelReady -> "Channel ready"
            is Event.ChannelPending -> "Channel opening (waiting for confirmations)"
            is Event.ChannelClosed -> "Channel closed"
            else -> e.javaClass.simpleName
        }
        node.eventHandled()
        return t
    }
}
