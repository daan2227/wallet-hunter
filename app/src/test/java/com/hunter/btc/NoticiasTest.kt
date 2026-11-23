package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticiasTest {
    private val rss = """<?xml version="1.0"?><rss><channel><title>Feed</title>
        <item><title><![CDATA[Bitcoin tops ${'$'}100K as ETF inflows &amp; demand rise]]></title>
          <link>https://example.com/a</link><pubDate>Mon, 23 Nov 2026 07:10:00 +0000</pubDate></item>
        <item><title>Ether &#8217;s upgrade <b>ships</b></title><link>https://example.com/b</link><pubDate>Sun, 22 Nov 2026 10:00:00 GMT</pubDate></item>
        <item><title>Insecure link</title><link>http://example.com/c</link></item>
        <item><title></title><link>https://example.com/d</link></item>
        </channel></rss>"""

    @Test fun rss() {
        val l = Noticias.parsearRss(rss, "Test")
        assertEquals(2, l.size)
        assertEquals("Bitcoin tops \$100K as ETF inflows & demand rise", l[0].titulo)
        assertEquals("Ether ’s upgrade ships", l[1].titulo)
        assertEquals("https://example.com/a", l[0].enlace)
        assertEquals(1_795_417_800L, l[0].t)                         // 2026-11-23T07:10:00Z
        assertEquals(1_795_341_600L, l[1].t)                         // GMT
    }

    @Test fun filtroPorActivo() {
        val l = Noticias.parsearRss(rss, "Test")
        assertEquals(1, Noticias.filtrar(l, "BTC", "Bitcoin").size)
        assertEquals(1, Noticias.filtrar(l, "ETH", "Ether").size)
        assertTrue(Noticias.filtrar(l, "SOL", "Solana").isEmpty())
    }

    @Test fun calendario() {
        val js = """[{"title":"CPI m/m","country":"USD","date":"2026-11-25T08:30:00-05:00","impact":"High","forecast":"0.3%","previous":"0.2%"},
            {"title":"German PMI","country":"EUR","date":"2026-11-24T03:30:00-05:00","impact":"High","forecast":"","previous":""},
            {"title":"Crude Oil Inventories","country":"USD","date":"2026-11-24T10:30:00-05:00","impact":"Medium","forecast":"","previous":"1.2M"},
            {"title":"Bank Holiday","country":"USD","date":"2026-11-26T00:00:00-05:00","impact":"Holiday","forecast":"","previous":""}]"""
        val e = Noticias.parsearCalendario(js)
        assertEquals(listOf("Crude Oil Inventories", "CPI m/m"), e.map { it.titulo })   // solo USD alto/medio, por fecha
        assertEquals(1_795_613_400L, e[1].t)                                            // 13:30 UTC
        assertTrue(Noticias.parsearCalendario("not json").isEmpty())
    }
}
