package com.hunter.btc

import java.io.File

/**
 * "Export summary": un texto con los hallazgos, sin claves, para compartir.
 *
 * Estaba dentro de MainActivity, que pasaba de 6.000 líneas; va aparte porque
 * no depende de nada de esa pantalla.
 */
object ExportarResumen {

    /**
     * Exporta un resumen de los hallazgos para compartir.
     *
     * Iba sin PIN, al contrario que los dos botones de debajo, y metía dentro
     * los últimos 2 KB de crash_log.txt: el handler guarda e.message y el stack
     * trace tal cual, y el mensaje de una excepción suele arrastrar el dato que
     * la provocó. Ese fichero sale por ACTION_SEND hacia mensajería o correo.
     *
     * Ahora pide PIN, el registro de fallos es opt-in explícito y lo que se
     * incluye va con las cadenas que parecen clave tapadas.
     */
    fun exportar(act: android.app.Activity) {
        if (!PinAuthHelper.isSessionValid()) {
            // Sin huella automática: esto exporta un resumen sin claves privadas,
            // no vale interrumpir con el lector. El teclado sale directo y la
            // tecla ◉ sigue ahí para quien prefiera la huella.
            PinAuthHelper.show(act, autoBiometric = false) { ok -> if (ok) askExportLogOptions(act) }
        } else {
            askExportLogOptions(act)
        }
    }

    private fun askExportLogOptions(act: android.app.Activity) {
        val crashLog = File(act.filesDir, "crash_log.txt")
        if (!crashLog.exists() || crashLog.length() == 0L) { writeAndShareLog(act, false); return }
        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle("Include the crash log?")
            .setMessage("There is a saved crash log. It helps diagnose " +
                        "problems, but an error may carry inside the very data that " +
                        "caused it. The file is shared by messaging or email.")
            .setPositiveButton("Without the log") { _, _ -> writeAndShareLog(act, false) }
            .setNeutralButton("Include it") { _, _ -> writeAndShareLog(act, true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Tapa lo que tenga forma de clave privada: 64 hex seguidos, WIF
     * (5/K/L + Base58) y claves extendidas. Es una red de seguridad sobre el
     * registro de fallos, no una garantía — por eso incluirlo se pregunta.
     */
    // Vive en Secretos para que el botón "Copy" del debug tache lo mismo.
    private fun redactSecrets(text: String): String = Secretos.tachar(text)

    private fun writeAndShareLog(act: android.app.Activity, includeCrashLog: Boolean) {
        // Interno y no externo: en Android 8 y 9 el externo lo lee cualquier
        // app con READ_EXTERNAL_STORAGE. Se borran también los que dejaron
        // versiones anteriores ahí fuera.
        val dir = BackupStore.compartidos(act)
        listOfNotNull(dir, act.getExternalFilesDir(null)).forEach { d ->
            d.listFiles()?.filter { it.name.startsWith("wallet_hunter_export_") ||
                                    it.name.startsWith("wh_progress_") }?.forEach { it.delete() }
        }

        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(dir, "wallet_hunter_export_$ts.txt")
        val sb = StringBuilder()
        sb.appendLine("=== WALLET HUNTER EXPORT ===")
        sb.appendLine("Date: ${java.util.Date()}")
        sb.appendLine("Device: ${android.os.Build.MODEL}")
        sb.appendLine()

        // Los hallazgos van SIN claves privadas: para llevarse las claves está la
        // copia de seguridad, que cifra con el PIN.
        MatchVault.recoger(act)
        val hallazgos = MatchVault.list(act)
        if (hallazgos.isNotEmpty()) {
            sb.appendLine("=== MATCHES FOUND ===")
            sb.appendLine("(private keys omitted — use the backup)")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            hallazgos.forEach { e ->
                sb.appendLine("[${fmt.format(java.util.Date(e.ts))}] ${e.source}  " +
                              "ADDR:${e.addr}  BTC:${"%.8f".format(e.btc)}")
            }
        } else {
            sb.appendLine("=== NO MATCHES YET ===")
        }

        if (includeCrashLog) {
            val crashLog = File(act.filesDir, "crash_log.txt")
            if (crashLog.exists()) {
                sb.appendLine()
                sb.appendLine("=== CRASH LOG ===")
                sb.appendLine(redactSecrets(crashLog.readText().takeLast(4000)))
            }
        }

        f.writeText(sb.toString())

        val uri = androidx.core.content.FileProvider.getUriForFile(
            act, "${act.packageName}.provider", f
        )
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Export")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(android.content.Intent.createChooser(share, "Export log"))
        android.widget.Toast.makeText(act, "Log exported: ${f.name}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
