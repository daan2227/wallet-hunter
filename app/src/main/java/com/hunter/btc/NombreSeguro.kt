package com.hunter.btc

/**
 * Nombre de fichero seguro a partir del que da OTRA app (DISPLAY_NAME de un
 * proveedor de documentos).
 *
 * Ese nombre lo controla quien sirve el fichero. Usado tal cual en
 * File(directorio, nombre), un "../../shared_prefs/x.xml" escribía fuera del
 * directorio y podía pisar las preferencias o el baúl de la app ("Dirty Stream").
 * Aquí se queda sólo el último trozo de la ruta, con caracteres corrientes, y
 * nunca "." ni "..". Pura: se prueba en la JVM.
 */
object NombreSeguro {
    fun limpiar(nombre: String?, porDefecto: String = "dataset.csv"): String {
        if (nombre == null) return porDefecto
        val ultimo = nombre.substringAfterLast('/').substringAfterLast('\\')
        val limpio = ultimo.map { c -> if (c.isLetterOrDigit() && c.code < 128 || c in "._-") c else '_' }
            .joinToString("").trimStart('.').take(100)
        return if (limpio.isEmpty() || limpio.all { it == '.' || it == '_' }) porDefecto else limpio
    }
}
