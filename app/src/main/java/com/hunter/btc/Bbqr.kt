package com.hunter.btc

/**
 * BBQr (Coinkite): QR animados de Coldcard Q, Sparrow, Nunchuk y Keystone para PSBT y transacciones.
 *
 * Cabecera de 8 caracteres en cada QR: "B$", codificación (H = hex, 2 = base32, Z = deflate + base32),
 * tipo de fichero (P = PSBT, T = transacción, U = texto, J = JSON…), total de partes y número de esta
 * parte, ambos en base 36 con dos cifras. Todo el contenido va en el alfabeto alfanumérico de QR.
 *
 * Al leer se aceptan las tres codificaciones. Al escribir se usa base32 sin comprimir: el deflate de
 * Java no deja fijar la ventana de 1 KiB que exige el estándar para la Z, y una ventana mayor no la
 * leería un Coldcard.
 * Comprobado contra la librería oficial (npm "bbqr" de Coinkite): ver BbqrTest.
 */
object Bbqr {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val B36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun base32(b: ByteArray): String {
        val sb = StringBuilder((b.size * 8 + 4) / 5)
        var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(B32[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun deBase32(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = B32.indexOf(c); if (v < 0) return null
            buf = (buf shl 5) or v; bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xff); bits -= 8 }
            buf = buf and 0xff
        }
        return out.toByteArray()
    }

    private fun b36(n: Int) = "" + B36[n / 36] + B36[n % 36]
    private fun deB36(s: String): Int? {
        if (s.length != 2) return null
        val a = B36.indexOf(s[0]); val b = B36.indexOf(s[1])
        return if (a < 0 || b < 0) null else a * 36 + b
    }

    /**
     * Partes para mostrar en bucle. [porParte] caracteres de datos como mucho por QR (se redondea a
     * múltiplo de 8, que en base32 son 5 bytes enteros: así cada parte se descodifica sola).
     */
    fun partir(datos: ByteArray, tipo: Char = 'P', porParte: Int = 600): List<String> {
        require(datos.isNotEmpty())
        val cuerpo = base32(datos)
        val tam = maxOf(8, porParte - porParte % 8)
        val n = (cuerpo.length + tam - 1) / tam
        require(n in 1..1295) { "demasiado grande para BBQr" }
        return (0 until n).map { i -> "B$" + "2" + tipo + b36(n) + b36(i) + cuerpo.substring(i * tam, minOf(cuerpo.length, (i + 1) * tam)) }
    }

    /** Junta las partes leídas. Las repetidas se toleran; las de otro fichero (otro total o tipo) no se mezclan. */
    class Juntador {
        var tipo: Char? = null; private set
        private var cod: Char? = null
        private var total = 0
        private val partes = HashMap<Int, String>()
        val recibidas get() = partes.size
        val totalPartes get() = total
        val listo get() = total > 0 && partes.size == total

        fun recibir(texto0: String): Boolean {
            val t = texto0.trim()
            if (t.length < 8 || !t.startsWith("B$")) return false
            val c = t[2]; val ty = t[3]
            val n = deB36(t.substring(4, 6)) ?: return false
            val i = deB36(t.substring(6, 8)) ?: return false
            if (c !in "H2Z" || n < 1 || i >= n) return false
            if (total == 0) { total = n; cod = c; tipo = ty }
            else if (n != total || c != cod || ty != tipo) return false
            partes[i] = t.substring(8)
            return true
        }

        /** Los bytes del fichero, o null si falta alguna parte o el contenido no se puede descodificar. */
        fun resultado(): ByteArray? {
            if (!listo) return null
            val cuerpo = (0 until total).joinToString("") { partes[it]!! }
            return when (cod) {
                'H' -> if (cuerpo.length % 2 == 0 && cuerpo.all { it in "0123456789ABCDEFabcdef" })
                           ByteArray(cuerpo.length / 2) { cuerpo.substring(it * 2, it * 2 + 2).toInt(16).toByte() } else null
                '2' -> deBase32(cuerpo)
                'Z' -> deBase32(cuerpo)?.let { inflar(it) }
                else -> null
            }
        }

        private fun inflar(z: ByteArray): ByteArray? = try { inflarO(z) } catch (e: Exception) { null }

        private fun inflarO(z: ByteArray): ByteArray? {
            val inf = java.util.zip.Inflater(true)
            inf.setInput(z)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!inf.finished()) {
                val k = inf.inflate(buf)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, k)
                if (out.size() > 32 * 1024 * 1024) return null
            }
            inf.end()
            return out.toByteArray()
        }
    }
}
