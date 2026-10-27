package com.hunter.btc

/**
 * La lógica de una cartera vigilada desde una xpub/descriptor, sin pantalla ni red: qué direcciones
 * tiene, hasta dónde mirar (hueco de 20, BIP44), qué monedas gastar y la PSBT sin firmar con todo lo
 * que necesita un firmador externo (UTXO, derivación BIP32 de entradas y del cambio).
 *
 * La curva y la red se inyectan: así se prueba en la JVM contra Bitcoin Core (ver MotorVigiladaTest).
 */
class MotorVigilada(val v: Descriptor.Vigilada, private val der: Descriptor.Derivador, private val tw: Descriptor.Tweaker,
                    override val avisos: List<String> = emptyList()) : MotorCartera {

    override val testnet get() = v.testnet
    override val descripcion get() = when (v.tipo) {
        Descriptor.Tipo.PKH -> "Watch-only · legacy (1…)"; Descriptor.Tipo.SH_WPKH -> "Watch-only · nested SegWit (3…)"
        Descriptor.Tipo.WPKH -> "Watch-only · native SegWit (bc1q…)"; Descriptor.Tipo.TR -> "Watch-only · Taproot (bc1p…)"
    }
    override fun texto() = v.texto()

    private val cacheSpk = HashMap<Long, ByteArray>()

    override fun spk(c: Int, i: Int): ByteArray? {
        val k = (c.toLong() shl 32) or i.toLong()
        cacheSpk[k]?.let { return it }
        return Descriptor.spk(v, c, i, der, tw)?.also { cacheSpk[k] = it }
    }

    override fun vbEntrada() = when (v.tipo) { Descriptor.Tipo.PKH -> 148; Descriptor.Tipo.SH_WPKH -> 91; Descriptor.Tipo.WPKH -> 68; Descriptor.Tipo.TR -> 58 }

    private fun derivacion(c: Int, i: Int): Psbt.Derivacion? {
        val (huella, ruta) = v.clave.origenDe(c, i) ?: return null
        val pub = Descriptor.pubDe(v, c, i, der) ?: return null
        return Psbt.Derivacion(pub33 = pub, huella = huella, ruta = ruta,
            rescate = if (v.tipo == Descriptor.Tipo.SH_WPKH) byteArrayOf(0x00, 0x14) + Ripemd160.hash160(pub) else null,
            tapInterna = if (v.tipo == Descriptor.Tipo.TR) pub.copyOfRange(1, 33) else null)
    }

    /**
     * La PSBT sin firmar. [anteriores] da la transacción previa (bytes) de una moneda pkh, que su firmador
     * necesita para comprobar el importe; en los demás tipos basta el UTXO. null si falta algo.
     */
    override val necesitaAnterior get() = v.tipo == Descriptor.Tipo.PKH

    override fun psbt(elegidas: List<Moneda>, destinoSpk: ByteArray, importe: Long, cambio: Long, cambioIndice: Int,
             locktime: Long, anteriores: (Moneda) -> ByteArray?): ByteArray? {
        val salidas = ArrayList<Pair<ByteArray, Long>>()
        salidas.add(destinoSpk to importe)
        val propias = HashMap<Int, Psbt.Derivacion>()
        if (cambio > 0) {
            salidas.add((spk(1, cambioIndice) ?: return null) to cambio)
            derivacion(1, cambioIndice)?.let { propias[salidas.size - 1] = it }
        }
        val txHex = Psbt.sinFirmar(elegidas.map { it.txid to it.vout }, salidas, locktime)
        val entradas = elegidas.map { m ->
            val s = spk(m.c, m.i) ?: return null
            val prev = if (v.tipo == Descriptor.Tipo.PKH) (anteriores(m) ?: return null) else null
            Psbt.EntradaPsbt(m.valor, s, prev, derivacion(m.c, m.i))
        }
        return Psbt.crear(txHex, entradas, propias)
    }
}
