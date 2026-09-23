package com.hunter.btc

import android.content.Context
import java.io.File

/**
 * Baúl de copias de seguridad.
 *
 * exportBackup() escribía el fichero y la app lo lanzaba directamente al
 * selector de compartir. Si cerrabas ese selector, la copia se quedaba en un
 * directorio interno del que nada volvía a hablar; y como el export borraba
 * todo lo anterior antes de escribir, tampoco había forma de volver a una
 * copia previa.
 *
 * Aquí se conservan las últimas [MAX_KEPT] y se listan para compartir, mirar
 * qué llevan dentro o restaurar sin pasar por el selector de ficheros.
 *
 * El contenido va cifrado con AES-GCM bajo una clave derivada del PIN con
 * PBKDF2; el fichero en sí no aporta protección, así que tampoco conviene
 * acumular copias sin límite.
 */
object BackupStore {

    /** Copias conservadas. Las más viejas se borran al crear una nueva. */
    const val MAX_KEPT = 5

    const val EXT = ".whbak"

    data class Info(val file: File, val createdAt: Long, val bytes: Long) {
        val name: String get() = file.name
    }

    fun dir(ctx: Context): File =
        File(ctx.filesDir, "backups").also { it.mkdirs() }

    /** Copias guardadas, la más reciente primero. */
    fun list(ctx: Context): List<Info> =
        dir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXT) }
            ?.map { Info(it, stampOf(it), it.length()) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    /**
     * Fecha de la copia. El nombre lleva el milisegundo de creación
     * (wh_backup_<ms>.whbak); lastModified() sólo se usa si no se puede leer.
     */
    private fun stampOf(f: File): Long =
        f.name.removePrefix("wh_backup_").removeSuffix(EXT).toLongOrNull()
            ?: f.lastModified()

    /** Deja sólo las [MAX_KEPT] más recientes. */
    fun prune(ctx: Context) {
        list(ctx).drop(MAX_KEPT).forEach { it.file.delete() }
    }

    fun delete(ctx: Context, f: File): Boolean = f.delete()

    fun count(ctx: Context): Int = list(ctx).size

    /** Intent para compartir una copia vía FileProvider. */
    fun shareIntent(ctx: Context, f: File): android.content.Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.provider", f)
        return android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Backup")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share backup")
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024        -> "%.1f KB".format(bytes / 1024.0)
        else                 -> "$bytes B"
    }

    fun humanDate(ms: Long): String =
        java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.US)
            .format(java.util.Date(ms))
}
