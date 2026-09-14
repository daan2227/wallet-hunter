package com.hunter.btc

import java.security.MessageDigest

/**
 * Validación de direcciones Bitcoin con verificación real de checksum.
 *
 * Base58Check.decodePayload y Bech32.decode existentes NO comprueban el
 * checksum: el primero ignora los 4 bytes finales y el segundo descarta los
 * últimos 6 caracteres sin verificarlos. Eso hace que una dirección con un
 * carácter mal tecleado se acepte como válida, y en un envío eso significa
 * perder los fondos de forma irreversible.
 */
object BtcAddress {

    enum class Type { P2PKH, P2SH, P2WPKH, P2WSH, P2TR }

    data class Info(val type: Type, val testnet: Boolean, val program: ByteArray)

    sealed class Result {
        data class Valid(val info: Info) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST  = 1
    private const val BECH32M_CONST = 0x2bc830a3

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    // ── Base58Check ───────────────────────────────────────────────────────────

    /** Decodifica y verifica el doble-SHA256 de los últimos 4 bytes. */
    fun base58CheckDecode(addr: String): ByteArray? {
        if (addr.isEmpty() || addr.length > 100) return null
        val bytes = ArrayList<Int>()
        bytes.add(0)
        for (ch in addr) {
            val idx = B58.indexOf(ch)
            if (idx < 0) return null
            var carry = idx
            for (i in bytes.indices.reversed()) {
                carry += 58 * bytes[i]
                bytes[i] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) { bytes.add(0, carry and 0xff); carry = carry shr 8 }
        }
        // Cada '1' inicial representa un byte 0x00
        var leading = 0
        for (ch in addr) { if (ch == '1') leading++ else break }
        val body = bytes.map { it.toByte() }.toByteArray()
        val trimmed = body.dropWhile { it.toInt() == 0 }.toByteArray()
        val full = ByteArray(leading) { 0 } + trimmed
        if (full.size < 5) return null

        val payload  = full.copyOfRange(0, full.size - 4)
        val checksum = full.copyOfRange(full.size - 4, full.size)
        val expected = sha256(sha256(payload)).copyOfRange(0, 4)
        if (!MessageDigest.isEqual(checksum, expected)) return null
        return payload
    }

    // ── Bech32 / Bech32m ──────────────────────────────────────────────────────

    private fun polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if (((b shr i) and 1) != 0) chk = chk xor gen[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): ByteArray? {
        var acc = 0; var bits = 0
        val out = ArrayList<Byte>()
        val maxv = (1 shl to) - 1
        for (v in data) {
            if (v < 0 || (v shr from) != 0) return null
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) { bits -= to; out.add(((acc shr bits) and maxv).toByte()) }
        }
        if (pad) { if (bits > 0) out.add(((acc shl (to - bits)) and maxv).toByte()) }
        else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) return null
        return out.toByteArray()
    }

    // ── API ───────────────────────────────────────────────────────────────────

    fun validate(addrRaw: String, testnet: Boolean): Result {
        val addr = addrRaw.trim()
        if (addr.isEmpty()) return Result.Invalid("Dirección vacía")

        // Bech32 / Bech32m (bc1..., tb1...)
        val lower = addr.lowercase()
        if (lower.startsWith("bc1") || lower.startsWith("tb1")) {
            if (addr != lower && addr != addr.uppercase())
                return Result.Invalid("Bech32 no admite mayúsculas y minúsculas mezcladas")
            if (lower.length < 14 || lower.length > 90)
                return Result.Invalid("Longitud inválida para una dirección Bech32")
            val sep = lower.lastIndexOf('1')
            val hrp = lower.substring(0, sep)
            val isTestAddr = hrp == "tb"
            if (isTestAddr != testnet)
                return Result.Invalid(
                    if (testnet) "Es una dirección de mainnet y estás en testnet"
                    else "Es una dirección de testnet y estás en mainnet")

            val dataPart = lower.substring(sep + 1).map { BECH32_CHARSET.indexOf(it) }
            if (dataPart.any { it < 0 }) return Result.Invalid("Carácter inválido en la dirección")

            val chk = polymod(hrpExpand(hrp) + dataPart)
            val witVer = dataPart[0]
            val expected = if (witVer == 0) BECH32_CONST else BECH32M_CONST
            if (chk != expected) return Result.Invalid("Checksum incorrecto — revisa la dirección")

            val program = convertBits(dataPart.subList(1, dataPart.size - 6), 5, 8, false)
                ?: return Result.Invalid("Dirección Bech32 mal formada")
            val type = when {
                witVer == 0 && program.size == 20 -> Type.P2WPKH
                witVer == 0 && program.size == 32 -> Type.P2WSH
                witVer == 1 && program.size == 32 -> Type.P2TR
                else -> return Result.Invalid("Tipo de dirección no soportado (witness v$witVer)")
            }
            return Result.Valid(Info(type, isTestAddr, program))
        }

        // Base58Check (1..., 3..., m/n..., 2...)
        val payload = base58CheckDecode(addr)
            ?: return Result.Invalid("Checksum incorrecto — revisa la dirección")
        if (payload.size != 21) return Result.Invalid("Dirección Base58 mal formada")
        val version = payload[0].toInt() and 0xff
        val hash160 = payload.copyOfRange(1, 21)
        val (type, isTestAddr) = when (version) {
            0x00 -> Type.P2PKH to false
            0x05 -> Type.P2SH  to false
            0x6f -> Type.P2PKH to true
            0xc4 -> Type.P2SH  to true
            else -> return Result.Invalid("Prefijo de dirección desconocido (0x%02x)".format(version))
        }
        if (isTestAddr != testnet)
            return Result.Invalid(
                if (testnet) "Es una dirección de mainnet y estás en testnet"
                else "Es una dirección de testnet y estás en mainnet")
        return Result.Valid(Info(type, isTestAddr, hash160))
    }
}
