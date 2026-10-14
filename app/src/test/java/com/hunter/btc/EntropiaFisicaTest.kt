package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Test

class EntropiaFisicaTest {
    @Test fun monedasUnBitCadaUna() = assertEquals(50.0, EntropiaFisica.bits("01".repeat(25)), 1e-9)
    @Test fun dadosLog2De6() = assertEquals(50 * 2.584962500721156, EntropiaFisica.bits("123456".repeat(8) + "12"), 1e-6)
    @Test fun soloUnosSonMonedas() = assertEquals(10.0, EntropiaFisica.bits("1111111111"), 1e-9)
    @Test fun mezclaInvalidaNoCuenta() {
        assertEquals(0.0, EntropiaFisica.bits("0123456"), 1e-9)
        assertEquals(0.0, EntropiaFisica.bits("789"), 1e-9)
        assertEquals(0.0, EntropiaFisica.bits(""), 1e-9)
    }
}
