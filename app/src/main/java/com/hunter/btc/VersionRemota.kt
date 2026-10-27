package com.hunter.btc

import org.json.JSONObject

/**
 * La última compilación publicada, tal como la describe version.json en el
 * release latest-build-main (lo escribe la CI, paso "Publish APK").
 *
 * Aparte de Actualizador para poder probarla sin Android: aquí no hay red
 * ni ficheros, sólo leer y comparar.
 */
data class VersionRemota(
    val codigo: Long,
    val nombre: String,
    val sha256: String,
    val bytes: Long,
    val commit: String,
) {
    fun masNuevaQue(instalada: Long) = codigo > instalada

    companion object {
        /** Tope del APK aceptado: el actual ronda los 20 MB. */
        const val MAX_BYTES = 200L * 1024 * 1024

        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** null si el JSON no es una versión utilizable: mejor no ofrecer nada que ofrecer algo roto. */
        fun leer(json: String): VersionRemota? = try {
            val o = JSONObject(json)
            val codigo = o.getLong("versionCode")
            val sha = o.getString("sha256").lowercase()
            val bytes = o.optLong("size", -1)
            if (codigo <= 0 || !HEX64.matches(sha) || bytes <= 0 || bytes > MAX_BYTES) null
            else VersionRemota(
                codigo = codigo,
                nombre = o.optString("versionName", "").ifBlank { "1.0.$codigo" },
                sha256 = sha,
                bytes = bytes,
                commit = o.optString("commit", ""),
            )
        } catch (_: Exception) { null }
    }
}
