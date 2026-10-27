package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Bip329Test {
    private val tx = "f91d0a8a78462bc59398f2c5d7a84fcff491c26ba54c4833478b202796c8aafd"

    @Test fun ejemploDelEstandar() {
        // Líneas del ejemplo de BIP329 (las de tipos que importa la app).
        val txt = """
            {"type":"tx","ref":"$tx","label":"Transaction","origin":"wpkh([d34db33f/84'/0'/0'])"}
            {"type":"addr","ref":"bc1q34aq5drpuwy3wgl9lhup9892qp6svr8ldzyy7c","label":"Address"}
            {"type":"output","ref":"$tx:1","label":"Output","spendable":false}
            {"type":"xpub","ref":"xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8","label":"Extended Public Key"}
        """.trimIndent()
        val r = Bip329.importar(txt)
        assertEquals(0, r.ignoradas); assertEquals(4, r.etiquetas.size)
        assertEquals("wpkh([d34db33f/84'/0'/0'])", r.etiquetas[0].origen)
        assertEquals(false, r.etiquetas[2].gastable)
        assertNull(r.etiquetas[1].gastable)
    }

    @Test fun idaYVuelta() {
        val l = listOf(Bip329.Etiqueta("addr", "bc1q34aq5drpuwy3wgl9lhup9892qp6svr8ldzyy7c", "Ahorro \"largo\" ñ"),
                       Bip329.Etiqueta("output", "$tx:0", "", gastable = true))
        assertEquals(l, Bip329.importar(Bip329.exportar(l)).etiquetas)
    }

    @Test fun lineasMalasSeIgnoranSinPerderLasBuenas() {
        val txt = "no json\n{\"type\":\"raro\",\"ref\":\"x\",\"label\":\"y\"}\n{\"type\":\"tx\",\"ref\":\"abc\",\"label\":\"y\"}\n" +
                  "{\"type\":\"tx\",\"ref\":\"$tx\",\"label\":\"${"a".repeat(400)}\"}\n"
        val r = Bip329.importar(txt)
        assertEquals(3, r.ignoradas); assertEquals(1, r.etiquetas.size)
        assertEquals(Bip329.MAX_ETIQUETA, r.etiquetas[0].texto.length)
    }
}
