package com.hunter.btc

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cliente WebSocket mínimo (RFC 6455) para los streams públicos de Binance, sin
 * dependencias: TLS con verificación de nombre, por la red privada (Tor) si está
 * activa, respuesta a ping, mensajes fragmentados y reconexión con espera creciente.
 * Solo lee (nunca envía datos propios salvo pong/close). Los mensajes llegan al
 * hilo principal como (stream, data) del formato combinado de Binance.
 */
class StreamBinance(
    private val streams: List<String>,
    private val alMensaje: (String, JSONObject) -> Unit,
    private val alEstado: (Boolean) -> Unit,
) {
    private val host = "stream.binance.com"
    private val principal = android.os.Handler(android.os.Looper.getMainLooper())
    private val rnd = SecureRandom()
    @Volatile private var vivo = true
    @Volatile private var socket: java.net.Socket? = null
    private val hilo = Thread({ bucle() }, "stream-binance").apply { isDaemon = true }

    init { hilo.start() }

    fun cerrar() { vivo = false; try { socket?.close() } catch (e: Exception) { } }

    private fun bucle() {
        var espera = 2_000L
        while (vivo) {
            try {
                conectarYLeer()
                espera = 2_000L
            } catch (e: Exception) { }
            principal.post { if (vivo) alEstado(false) }
            if (!vivo) break
            try { Thread.sleep(espera) } catch (e: InterruptedException) { }
            espera = (espera * 2).coerceAtMost(30_000L)
        }
    }

    private fun conectarYLeer() {
        val crudo = RedPrivada.socket(host)
        crudo.connect(RedPrivada.destino(host, 443), 15_000)
        crudo.soTimeout = 75_000   // Binance hace ping cada ~20 s; sin nada en 75 s se reconecta
        val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
            .createSocket(crudo, host, 443, true) as javax.net.ssl.SSLSocket
        socket = ssl
        ssl.startHandshake()
        // SSLSocket no comprueba el nombre del certificado: se hace aquí, como HTTPS.
        if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session))
            throw javax.net.ssl.SSLPeerUnverifiedException("certificate does not match $host")
        val ent = ssl.inputStream.buffered(); val sal = ssl.outputStream
        // apretón de manos HTTP → 101
        val clave = android.util.Base64.encodeToString(ByteArray(16).also { rnd.nextBytes(it) }, android.util.Base64.NO_WRAP)
        val ruta = "/stream?streams=" + streams.joinToString("/")
        sal.write(("GET $ruta HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $clave\r\nSec-WebSocket-Version: 13\r\nUser-Agent: hunter\r\n\r\n").toByteArray())
        sal.flush()
        val cab = leerCabecera(ent)
        if (!cab.startsWith("HTTP/1.1 101")) throw java.io.IOException("handshake: ${cab.lineSequence().firstOrNull()}")
        val esperado = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((clave + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), android.util.Base64.NO_WRAP)
        val acepta = cab.lineSequence().firstOrNull { it.lowercase().startsWith("sec-websocket-accept:") }?.substringAfter(':')?.trim()
        if (acepta != esperado) throw java.io.IOException("bad Sec-WebSocket-Accept")
        principal.post { if (vivo) alEstado(true) }

        val mensaje = ByteArrayOutputStream()
        var opMensaje = 0
        while (vivo) {
            val b0 = ent.read(); val b1 = ent.read()
            if (b0 < 0 || b1 < 0) throw java.io.EOFException()
            val fin = b0 and 0x80 != 0; val op = b0 and 0x0F
            var largo = (b1 and 0x7F).toLong()
            if ((b1 and 0x7F) == 126) { val x = leer(ent, 2); largo = ((x[0].toInt() and 0xFF) shl 8 or (x[1].toInt() and 0xFF)).toLong() }
            else if ((b1 and 0x7F) == 127) { val x = leer(ent, 8); largo = 0; for (v in x) largo = (largo shl 8) or (v.toLong() and 0xFF) }
            if (largo > 4_000_000) throw java.io.IOException("frame too large")
            val mascara = if (b1 and 0x80 != 0) leer(ent, 4) else null
            val datos = leer(ent, largo.toInt())
            if (mascara != null) for (i in datos.indices) datos[i] = (datos[i].toInt() xor mascara[i % 4].toInt()).toByte()
            when (op) {
                0x9 -> enviar(sal, 0xA, datos)                                     // ping → pong
                0xA -> { }
                0x8 -> { try { enviar(sal, 0x8, ByteArray(0)) } catch (e: Exception) { }; throw java.io.EOFException("closed") }
                0x1, 0x2, 0x0 -> {
                    if (op != 0) { opMensaje = op; mensaje.reset() }
                    mensaje.write(datos)
                    if (mensaje.size() > 4_000_000) throw java.io.IOException("message too large")
                    if (fin && opMensaje == 0x1) {
                        val txt = mensaje.toString("UTF-8"); mensaje.reset()
                        try {
                            val o = JSONObject(txt)
                            val st = o.optString("stream"); val d = o.optJSONObject("data")
                            if (d != null) principal.post { if (vivo) alMensaje(st, d) }
                        } catch (e: Exception) { }
                    }
                }
            }
        }
    }

    private fun leerCabecera(ent: InputStream): String {
        val b = StringBuilder()
        while (!b.endsWith("\r\n\r\n")) { val c = ent.read(); if (c < 0 || b.length > 8192) throw java.io.EOFException(); b.append(c.toChar()) }
        return b.toString()
    }

    private fun leer(ent: InputStream, n: Int): ByteArray {
        val out = ByteArray(n); var hecho = 0
        while (hecho < n) { val r = ent.read(out, hecho, n - hecho); if (r < 0) throw java.io.EOFException(); hecho += r }
        return out
    }

    /** Trama del cliente: siempre enmascarada (obligatorio en RFC 6455). */
    @Synchronized private fun enviar(sal: OutputStream, op: Int, datos: ByteArray) {
        val mascara = ByteArray(4).also { rnd.nextBytes(it) }
        val f = ByteArrayOutputStream()
        f.write(0x80 or op)
        if (datos.size < 126) f.write(0x80 or datos.size) else { f.write(0x80 or 126); f.write(datos.size shr 8 and 0xFF); f.write(datos.size and 0xFF) }
        f.write(mascara)
        for (i in datos.indices) f.write(datos[i].toInt() xor mascara[i % 4].toInt())
        sal.write(f.toByteArray()); sal.flush()
    }
}
