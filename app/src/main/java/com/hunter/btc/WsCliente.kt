package com.hunter.btc

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Cliente WebSocket mínimo (RFC 6455) para hablar con relays de Nostr: texto, ping/pong y cierre. Sin
 * librerías: la conexión sale por RedPrivada (Tor si está activado) y, en wss, con TLS comprobando el
 * nombre del servidor. Un solo hilo lee ([recibir]); enviar está sincronizado.
 */
class WsCliente private constructor(private val s: Socket, private val ins: InputStream, private val out: OutputStream) : AutoCloseable {

    companion object {
        private const val MAX_MENSAJE = 2 * 1024 * 1024

        fun conectar(url: String, timeoutMs: Int = 20_000): WsCliente {
            val u = java.net.URI(url)
            val tls = when (u.scheme) { "wss" -> true; "ws" -> false; else -> throw IllegalArgumentException("ws:// or wss:// expected") }
            val host = u.host ?: throw IllegalArgumentException("no host")
            val puerto = if (u.port > 0) u.port else if (tls) 443 else 80
            // ws:// sin cifrar sólo a la propia red o a un .onion (Tor ya cifra).
            if (!tls) require(host == "127.0.0.1" || host == "localhost" || host.endsWith(".onion") || host.startsWith("192.168.") || host.startsWith("10.")) { "use wss://" }
            val plano = RedPrivada.socket(host)
            plano.connect(RedPrivada.destino(host, puerto), timeoutMs)
            val s: Socket = if (tls) {
                val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(plano, host, puerto, true) as javax.net.ssl.SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake(); ssl
            } else plano
            s.soTimeout = timeoutMs
            val ins = s.getInputStream().buffered(); val out = s.getOutputStream()
            val clave = java.util.Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
            val ruta = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            out.write(("GET $ruta HTTP/1.1\r\nHost: $host${if (u.port > 0) ":$puerto" else ""}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $clave\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray()); out.flush()
            val cabeceras = StringBuilder()
            while (!cabeceras.endsWith("\r\n\r\n")) { val c = ins.read(); if (c < 0 || cabeceras.length > 8192) throw java.io.IOException("bad handshake"); cabeceras.append(c.toChar()) }
            val lineas = cabeceras.lines()
            if (!lineas[0].contains(" 101 ")) { s.close(); throw java.io.IOException("relay refused: ${lineas[0]}") }
            val acepta = lineas.firstOrNull { it.lowercase().startsWith("sec-websocket-accept:") }?.substringAfter(':')?.trim()
            val esperado = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                .digest((clave + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            if (acepta != esperado) { s.close(); throw java.io.IOException("bad Sec-WebSocket-Accept") }
            return WsCliente(s, ins, out)
        }
    }

    @Synchronized
    private fun marco(op: Int, datos: ByteArray) {
        val o = java.io.ByteArrayOutputStream()
        o.write(0x80 or op)
        val n = datos.size
        when { n < 126 -> o.write(0x80 or n); n < 65536 -> { o.write(0x80 or 126); o.write(n shr 8); o.write(n and 0xff) }
               else -> { o.write(0x80 or 127); for (i in 7 downTo 0) o.write(((n.toLong() shr (8 * i)) and 0xff).toInt()) } }
        val m = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        o.write(m)
        for (i in 0 until n) o.write(datos[i].toInt() xor m[i % 4].toInt())
        out.write(o.toByteArray()); out.flush()
    }

    fun enviar(texto: String) = marco(1, texto.toByteArray(Charsets.UTF_8))

    private fun leerN(n: Int): ByteArray { val b = ByteArray(n); var p = 0; while (p < n) { val k = ins.read(b, p, n - p); if (k < 0) throw java.io.EOFException(); p += k }; return b }

    /** El siguiente mensaje de texto (contesta los ping por el camino); null si el otro cerró. Lanza SocketTimeoutException si no llega nada. */
    fun recibir(): String? {
        val acum = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = ins.read(); if (b0 < 0) return null
            val b1 = ins.read(); if (b1 < 0) return null
            val op = b0 and 0x0f; val fin = (b0 and 0x80) != 0
            var n = (b1 and 0x7f).toLong()
            if (n == 126L) n = leerN(2).let { ((it[0].toLong() and 0xff) shl 8) or (it[1].toLong() and 0xff) }
            else if (n == 127L) n = leerN(8).fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xff) }
            if (n < 0 || n + acum.size() > MAX_MENSAJE) throw java.io.IOException("message too large")
            val mascara = if ((b1 and 0x80) != 0) leerN(4) else null
            val datos = leerN(n.toInt()); if (mascara != null) for (i in datos.indices) datos[i] = (datos[i].toInt() xor mascara[i % 4].toInt()).toByte()
            when (op) {
                0x8 -> { try { marco(0x8, ByteArray(0)) } catch (e: Exception) {}; return null }
                0x9 -> marco(0xA, datos)
                0xA -> {}
                0x1, 0x0 -> { acum.write(datos); if (fin) return String(acum.toByteArray(), Charsets.UTF_8) }
                else -> {}
            }
        }
    }

    fun ponerTimeout(ms: Int) { s.soTimeout = ms }

    override fun close() { try { marco(0x8, ByteArray(0)) } catch (e: Exception) {}; try { s.close() } catch (e: Exception) {} }
}
