package com.hunter.btc

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de Nostr: NIP-44 v2 (ChaCha20 + HMAC-SHA256 con claves por HKDF; el de NWC actual) y NIP-04
 * (AES-256-CBC; el antiguo, que aún usan muchos servicios). El secreto compartido (la x del ECDH) llega
 * hecho: en la app sale del motor (HunterEngine.ec "ecdh_x"). Puro: probado con los vectores oficiales
 * de NIP-44 (Nip44Test).
 */
object Nip44 {
    private fun hmac(k: ByteArray, vararg d: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")); d.forEach { update(it) } }.doFinal()

    /** Clave de conversación: HKDF-extract(sal "nip44-v2", x compartida). */
    fun claveConversacion(compartidaX: ByteArray): ByteArray = hmac("nip44-v2".toByteArray(), compartidaX)

    /** (clave ChaCha 32, nonce ChaCha 12, clave HMAC 32) = HKDF-expand(clave de conversación, nonce, 76). */
    fun clavesMensaje(conv: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conv.size == 32 && nonce.size == 32)
        val out = java.io.ByteArrayOutputStream(); var t = ByteArray(0); var i = 1
        while (out.size() < 76) { t = hmac(conv, t, nonce, byteArrayOf(i.toByte())); out.write(t); i++ }
        val k = out.toByteArray()
        return Triple(k.copyOfRange(0, 32), k.copyOfRange(32, 44), k.copyOfRange(44, 76))
    }

    /** Longitud con relleno de NIP-44 (potencias de 2 por tramos). */
    fun largoRelleno(n: Int): Int {
        require(n >= 1)
        if (n <= 32) return 32
        val next = Integer.highestOneBit(n - 1) shl 1
        val trozo = if (next <= 256) 32 else next / 8
        return trozo * ((n - 1) / trozo + 1)
    }

    // ── ChaCha20 (RFC 8439), contador desde 0 ────────────────────────────────────────────────────
    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))
    private fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16); s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8); s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    fun chacha20(clave: ByteArray, nonce: ByteArray, datos: ByteArray): ByteArray {
        require(clave.size == 32 && nonce.size == 12)
        val ini = IntArray(16)
        ini[0] = 0x61707865; ini[1] = 0x3320646e; ini[2] = 0x79622d32; ini[3] = 0x6b206574
        for (i in 0 until 8) ini[4 + i] = le32(clave, i * 4)
        for (i in 0 until 3) ini[13 + i] = le32(nonce, i * 4)
        val out = ByteArray(datos.size); var cont = 0; var p = 0
        while (p < datos.size) {
            ini[12] = cont++
            val x = ini.copyOf()
            repeat(10) { qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15)
                         qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14) }
            for (i in 0 until 16) {
                val w = x[i] + ini[i]
                for (j in 0 until 4) { val k = p + i * 4 + j; if (k < datos.size) out[k] = (datos[k].toInt() xor (w ushr (8 * j))).toByte() }
            }
            p += 64
        }
        return out
    }

    /** Cifra [texto] (UTF-8, 1–65535 bytes) con [nonce] de 32 (aleatorio en uso normal): base64 del paquete v2. */
    fun cifrar(texto: String, conv: ByteArray, nonce: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }): String {
        val pt = texto.toByteArray(Charsets.UTF_8)
        require(pt.size in 1..65535) { "plaintext size" }
        val (ck, cn, hk) = clavesMensaje(conv, nonce)
        val rell = ByteArray(2 + largoRelleno(pt.size))
        rell[0] = (pt.size shr 8).toByte(); rell[1] = pt.size.toByte(); System.arraycopy(pt, 0, rell, 2, pt.size)
        val ct = chacha20(ck, cn, rell)
        val mac = hmac(hk, nonce, ct)
        return java.util.Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + ct + mac)
    }

    /** Descifra; lanza IllegalArgumentException si el paquete no es v2 válido o el MAC no cuadra. */
    fun descifrar(payload: String, conv: ByteArray): String {
        require(payload.isNotEmpty() && payload[0] != '#') { "unknown version" }
        require(payload.length in 132..87472) { "invalid payload length" }
        val d = java.util.Base64.getDecoder().decode(payload)
        require(d.size in 99..65603 && d[0].toInt() == 2) { "unknown version" }
        val nonce = d.copyOfRange(1, 33); val ct = d.copyOfRange(33, d.size - 32); val mac = d.copyOfRange(d.size - 32, d.size)
        val (ck, cn, hk) = clavesMensaje(conv, nonce)
        require(java.security.MessageDigest.isEqual(hmac(hk, nonce, ct), mac)) { "invalid MAC" }
        val rell = chacha20(ck, cn, ct)
        val n = ((rell[0].toInt() and 0xff) shl 8) or (rell[1].toInt() and 0xff)
        require(n in 1..65535 && rell.size == 2 + largoRelleno(n)) { "invalid padding" }
        for (i in 2 + n until rell.size) require(rell[i].toInt() == 0) { "invalid padding" }
        return String(rell, 2, n, Charsets.UTF_8)
    }

    // ── NIP-04 (antiguo) ─────────────────────────────────────────────────────────────────────────
    fun cifrar04(texto: String, compartidaX: ByteArray, iv: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }): String {
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(compartidaX, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val b64 = java.util.Base64.getEncoder()
        return b64.encodeToString(c.doFinal(texto.toByteArray(Charsets.UTF_8))) + "?iv=" + b64.encodeToString(iv)
    }

    fun descifrar04(payload: String, compartidaX: ByteArray): String {
        val (ct, iv) = payload.split("?iv=").also { require(it.size == 2) { "not NIP-04" } }.let { it[0] to it[1] }
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        val b = java.util.Base64.getDecoder()
        c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(compartidaX, "AES"), javax.crypto.spec.IvParameterSpec(b.decode(iv)))
        return String(c.doFinal(b.decode(ct)), Charsets.UTF_8)
    }
}
