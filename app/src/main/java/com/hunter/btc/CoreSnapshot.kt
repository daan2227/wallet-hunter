package com.hunter.btc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Traer un snapshot de UTXO (assumeutxo) al directorio del nodo: desde un fichero del teléfono o por https.
 *
 * El snapshot no hace falta que venga de nadie de fiar: Core compara su hash con el que lleva fijado en
 * el código para unas pocas alturas de la red principal y lo rechaza si no coincide. Aquí sólo se
 * comprueba la cabecera para avisar pronto de un fichero equivocado.
 */
object CoreSnapshot {

    class Cancelar { @Volatile var cancelado = false }

    private const val MARGEN = 2L shl 30   // 2 GB de holgura para el índice y el estado de la cadena

    /** @return null si el fichero queda listo en [destino], o el motivo del fallo. */
    fun copiar(ctx: Context, uri: Uri, destino: File, c: Cancelar, progreso: (Long, Long) -> Unit): String? {
        val total = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
        } catch (e: Exception) { -1L }
        if (total > 0 && destino.parentFile!!.usableSpace < total + MARGEN)
            return "Not enough space: needs ${CoreConfig.bytes(total + MARGEN)}, free ${CoreConfig.bytes(destino.parentFile!!.usableSpace)}."
        val entrada = try { ctx.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: return "Could not open that file."
        return entrada.use { volcar(it, destino, 0L, total, c, progreso) }
    }

    /** Descarga por https; si ya hay un .part, sigue donde se quedó (si el servidor admite rangos). */
    fun descargar(url: String, destino: File, c: Cancelar, progreso: (Long, Long) -> Unit): String? {
        if (!CoreConfig.urlValida(url)) return "Only https:// links are accepted."
        val parte = File(destino.path + ".part")
        val ya = if (parte.isFile) parte.length() else 0L
        val con = try { URL(url.trim()).openConnection() as HttpURLConnection } catch (e: Exception) { return "Bad link." }
        try {
            con.connectTimeout = 15_000; con.readTimeout = 30_000
            if (ya > 0) con.setRequestProperty("Range", "bytes=$ya-")
            val code = con.responseCode
            if (code != 200 && code != 206) return "The server answered HTTP $code."
            val resto = con.contentLengthLong
            val total = if (resto > 0) resto + (if (code == 206) ya else 0L) else -1L
            if (total > 0 && destino.parentFile!!.usableSpace < (total - (if (code == 206) ya else 0L)) + MARGEN)
                return "Not enough space for ${CoreConfig.bytes(total)}."
            return con.inputStream.use { volcar(it, destino, if (code == 206) ya else 0L, total, c, progreso) }
        } catch (e: java.io.IOException) {
            return "Download interrupted (${e.javaClass.simpleName}). Press Download again to resume."
        } finally { con.disconnect() }
    }

    private fun volcar(entrada: InputStream, destino: File, desde: Long, total: Long,
                       c: Cancelar, progreso: (Long, Long) -> Unit): String? {
        val parte = File(destino.path + ".part")
        if (desde == 0L) parte.delete()
        var hecho = desde
        try {
            java.io.FileOutputStream(parte, desde > 0).use { sal ->
                val buf = ByteArray(1 shl 16)
                var ultimo = 0L
                while (true) {
                    if (c.cancelado) return "Cancelled."
                    val n = entrada.read(buf); if (n < 0) break
                    sal.write(buf, 0, n); hecho += n
                    val ahora = System.currentTimeMillis()
                    if (ahora - ultimo > 400) { progreso(hecho, total); ultimo = ahora }
                }
                sal.fd.sync()
            }
        } catch (e: java.io.IOException) {
            return "Write failed: ${e.message}"
        }
        progreso(hecho, total)
        val cab = cabecera(parte) ?: run { parte.delete(); return "That file is not a UTXO snapshot." }
        if (cab.red != "main") { parte.delete(); return "That snapshot is for '${cab.red}', not Bitcoin mainnet." }
        destino.delete()
        return if (parte.renameTo(destino)) null else "Could not move the snapshot into place."
    }

    fun cabecera(f: File): CoreConfig.CabeceraSnapshot? = try {
        f.inputStream().use { val b = ByteArray(51); var n = 0
            while (n < 51) { val k = it.read(b, n, 51 - n); if (k < 0) break; n += k }
            if (n < 51) null else CoreConfig.leerCabeceraSnapshot(b) }
    } catch (e: Exception) { null }
}
