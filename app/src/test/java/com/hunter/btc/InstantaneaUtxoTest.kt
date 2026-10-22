package com.hunter.btc

import org.junit.Assert.*
import org.junit.Test

class InstantaneaUtxoTest {
    @Test fun importesSinComaFlotante() {
        assertEquals("0.00000005", InstantaneaUtxo.btc(5)); assertEquals("1.23456789", InstantaneaUtxo.btc(123_456_789))
        assertEquals("21000000.00000000", InstantaneaUtxo.btc(2_100_000_000_000_000L)); assertEquals("0.00000000", InstantaneaUtxo.btc(0))
    }
    @Test fun celdasSeguras() {
        assertEquals("\"Mi, \"\"x\"\"\"", InstantaneaUtxo.celda("Mi, \"x\""))
        assertEquals("'=HYPERLINK(1)", InstantaneaUtxo.celda("=HYPERLINK(1)"))
        assertEquals("a b", InstantaneaUtxo.celda("a\nb"))
    }
}
