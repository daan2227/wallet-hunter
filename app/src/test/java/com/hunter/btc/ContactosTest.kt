package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactosTest {
    @Test fun tipos() {
        assertEquals(Contactos.Tipo.DIRECCION, Contactos.tipoDe("bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l"))
        assertEquals(Contactos.Tipo.DIRECCION, Contactos.tipoDe("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertEquals(Contactos.Tipo.PETICION, Contactos.tipoDe("bitcoin:bc1qxyz?amount=0.1"))
        assertEquals(Contactos.Tipo.NOMBRE, Contactos.tipoDe("₿ana@example.com"))
        assertEquals(Contactos.Tipo.NOMBRE, Contactos.tipoDe("ana+tips@example.com"))
        assertEquals(Contactos.Tipo.OFERTA, Contactos.tipoDe("lightning:lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzr"))
        assertEquals(Contactos.Tipo.LNURL, Contactos.tipoDe("lnurlp://example.com/pay/ana"))
        assertNull(Contactos.tipoDe("hola")); assertNull(Contactos.tipoDe("bc1qmal")); assertNull(Contactos.tipoDe(""))
        assertTrue(Contactos.Tipo.NOMBRE.enCadena && Contactos.Tipo.NOMBRE.enLightning && !Contactos.Tipo.OFERTA.enCadena)
    }

    @Test fun validarYGuardar() {
        assertNull(Contactos.validar("Ana", "ana@example.com"))
        assertTrue(Contactos.validar(" ", "ana@example.com") != null)
        assertTrue(Contactos.validar("Ana", "nada") != null)
        var l = Contactos.con(emptyList(), Contactos.Contacto("bea", "bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l"))
        l = Contactos.con(l, Contactos.Contacto(" Ana ", " ana@example.com "))
        l = Contactos.con(l, Contactos.Contacto("BEA", "bea@example.com"))          // mismo nombre: sustituye
        assertEquals(listOf("Ana" to "ana@example.com", "BEA" to "bea@example.com"), l.map { it.nombre to it.destino })
        assertEquals(l, Contactos.deJson(Contactos.aJson(l)))
        assertEquals(emptyList<Contactos.Contacto>(), Contactos.deJson("roto"))
    }
}
