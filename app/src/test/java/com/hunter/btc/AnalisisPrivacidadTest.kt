package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalisisPrivacidadTest {
    private val Q = BtcAddress.Type.P2WPKH; private val TR = BtcAddress.Type.P2TR

    private fun envio(importe: Long = 123_456, cambio: Long = 50_000, tipoCambio: BtcAddress.Type = Q, destinos: List<BtcAddress.Type> = listOf(Q),
                      entradas: List<Long> = listOf(200_000), enOrigen: Int = 1, alOrigen: Boolean = false, datos: Boolean = false) =
        AnalisisPrivacidad.revisar(AnalisisPrivacidad.Envio(importe, cambio, tipoCambio, destinos, entradas, enOrigen, alOrigen, datos))

    @Test fun envioLimpioNoAvisa() {
        assertEquals(0, envio().size)
        // Sin cambio no hay cambio que delatar, aunque el importe sea redondo y el destino de otro tipo.
        assertEquals(0, envio(importe = 1_000_000, cambio = 0, destinos = listOf(TR)).size)
    }

    @Test fun cadaPista() {
        assertTrue(envio(entradas = listOf(200_000, 700)).first().grave)                                    // polvo
        assertTrue(envio(alOrigen = true).single().grave)                                                  // cambio al origen
        assertEquals(1, envio(importe = 1_000_000, cambio = 23_456).size)                                  // importe redondo
        assertEquals(0, envio(importe = 1_000_000, cambio = 2_000_000).size)                               // los dos redondos: no se sabe
        assertTrue(envio(destinos = listOf(TR)).single().texto.contains("Taproot"))                        // tipos distintos
        assertEquals(1, envio(enOrigen = 3).size); assertEquals(1, envio(datos = true).size)
        // Los graves van primero.
        val todos = envio(importe = 1_000_000, cambio = 1, destinos = listOf(TR), entradas = listOf(500), enOrigen = 2, alOrigen = true, datos = true)
        assertEquals(6, todos.size); assertTrue(todos[0].grave && todos[1].grave && !todos[2].grave)
    }

    @Test fun tipos() {
        assertEquals(TR, AnalisisPrivacidad.tipoDeClave("p2tr_ch_3")); assertEquals(Q, AnalisisPrivacidad.tipoDeClave("p2wpkh_0"))
        assertEquals(BtcAddress.Type.P2SH, AnalisisPrivacidad.tipoDeClave("p2sh_1")); assertEquals(null, AnalisisPrivacidad.tipoDeClave("wif_0"))
    }
}
