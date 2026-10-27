package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoolConexionesTest {
    private var ahora = 0L
    private val cerradas = ArrayList<String>()
    private fun pool(max: Int = 2, inactivo: Long = 1000) =
        PoolConexiones<String>(max, inactivo, { ahora }) { cerradas.add(it) }

    @Test fun devuelveLaMasRecienteDeLaMismaClave() {
        val p = pool()
        p.devolver("a:1", "x"); ahora = 10; p.devolver("a:1", "y"); p.devolver("b:1", "z")
        assertEquals("y", p.tomar("a:1")); assertEquals("x", p.tomar("a:1")); assertNull(p.tomar("a:1"))
        assertEquals("z", p.tomar("b:1"))
    }

    @Test fun lasQueLlevanMuchoParadasSeCierran() {
        val p = pool(inactivo = 1000)
        p.devolver("a:1", "vieja"); ahora = 999; p.devolver("a:1", "nueva")
        ahora = 1000
        assertEquals("nueva", p.tomar("a:1"))
        assertEquals(listOf("vieja"), cerradas)
        ahora = 5000
        assertNull(p.tomar("a:1"))
    }

    @Test fun conMasDeLasPermitidasSeCierraLaMasAntigua() {
        val p = pool(max = 2)
        p.devolver("a:1", "1"); p.devolver("a:1", "2"); p.devolver("a:1", "3")
        assertEquals(listOf("1"), cerradas); assertEquals(2, p.libres())
    }

    @Test fun vaciarLasCierraTodas() {
        val p = pool()
        p.devolver("a:1", "1"); p.devolver("b:1", "2")
        p.vaciar()
        assertEquals(setOf("1", "2"), cerradas.toSet()); assertEquals(0, p.libres()); assertNull(p.tomar("a:1"))
    }
}
