package com.hunter.btc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Busca compilaciones nuevas en el release latest-build-main de GitHub y las
 * instala.
 *
 * Qué se comprueba antes de ofrecer el APK al instalador:
 *  - que el SHA-256 coincide con el de version.json (descarga entera, sin cortes),
 *  - que es este mismo paquete y con un versionCode mayor que el instalado,
 *  - que lo firma el mismo certificado que la app instalada.
 * Lo último es lo que de verdad protege: Android tampoco instala encima un APK
 * con otra firma, pero así se avisa con un mensaje claro en vez de un "App not
 * installed", y nadie que controle la descarga puede colar otro paquete.
 *
 * El repositorio es público: no hace falta ninguna clave para consultar.
 */
object Actualizador {
    private const val BASE = "https://github.com/daan2227/wallet-hunter/releases/download/latest-build-main/"
    private const val URL_VERSION = BASE + "version.json"
    private const val URL_APK = BASE + "app-release.apk"

    private const val PREFS = "actualizador"
    private const val K_AUTO = "auto"
    private const val K_ULTIMA = "ultima"
    private const val K_ANUNCIADA = "anunciada"
    private const val CADA_MS = 12L * 3600 * 1000

    fun autoOn(ctx: Context) = prefs(ctx).getBoolean(K_AUTO, true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_AUTO, on).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun carpeta(ctx: Context) = File(ctx.filesDir, "updates")

    fun instalada(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (_: Exception) { 0L }

    fun nombreInstalada(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    // ── Al abrir la app ──────────────────────────────────────────────────────

    /**
     * Como mucho una vez cada 12 h, en segundo plano y sin decir nada si falla
     * (sin red, GitHub caído). Cada versión nueva se anuncia una sola vez: si
     * se pulsa "Later" no vuelve a salir hasta la siguiente. Desde More →
     * Updates se puede buscar a mano cuando se quiera.
     */
    fun comprobarSiToca(act: Activity) {
        limpiarViejos(act)
        if (!autoOn(act)) return
        val p = prefs(act)
        val ahora = System.currentTimeMillis()
        val ultima = p.getLong(K_ULTIMA, 0)
        if (ultima in (ahora - CADA_MS + 1)..ahora) return
        p.edit().putLong(K_ULTIMA, ahora).apply()
        Thread {
            val v = try { consultar() } catch (_: Exception) { null } ?: return@Thread
            if (!v.masNuevaQue(instalada(act)) || p.getLong(K_ANUNCIADA, 0) == v.codigo) return@Thread
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                p.edit().putLong(K_ANUNCIADA, v.codigo).apply()
                ofrecer(act, v)
            }
        }.apply { isDaemon = true; name = "actualizador" }.start()
    }

    // ── A mano (More → Updates) ──────────────────────────────────────────────

    fun comprobarAhora(act: Activity) {
        val dlg = AlertDialog.Builder(act)
            .setTitle("Updates")
            .setMessage("Checking for a new build…")
            .setNegativeButton("Cancel", null)
            .show()
        Thread {
            val r = runCatching { consultar() }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed || !dlg.isShowing) return@runOnUiThread
                dlg.dismiss()
                val v = r.getOrNull()
                when {
                    r.isFailure -> aviso(act, "Couldn't check",
                        "No answer from GitHub (${r.exceptionOrNull()?.javaClass?.simpleName}). Check the connection and try again.")
                    v == null -> aviso(act, "Couldn't check",
                        "The published build has no valid version info yet. It appears after the next build finishes.")
                    !v.masNuevaQue(instalada(act)) -> aviso(act, "Up to date",
                        "You have the latest build: ${nombreInstalada(act)}.")
                    else -> {
                        prefs(act).edit().putLong(K_ANUNCIADA, v.codigo).apply()
                        ofrecer(act, v)
                    }
                }
            }
        }.apply { isDaemon = true; name = "actualizador" }.start()
    }

    // ── Red ──────────────────────────────────────────────────────────────────

    private fun abrir(url: String, timeoutLectura: Int): HttpURLConnection =
        RedPrivada.abrir(url).apply {
            connectTimeout = 15_000
            readTimeout = timeoutLectura
            instanceFollowRedirects = true   // github.com → objects.githubusercontent.com, ambos https
            useCaches = false
            setRequestProperty("User-Agent", "HunterSuite-updater")
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** null si version.json falta o no es válido; excepción si no hay red. */
    private fun consultar(): VersionRemota? {
        val c = abrir(URL_VERSION, 15_000)
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { leerHasta(it, 64 * 1024) }
            return VersionRemota.leer(String(bytes, Charsets.UTF_8))
        } finally { c.disconnect() }
    }

    private fun leerHasta(ins: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = ins.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw java.io.IOException("too big")
        }
        return out.toByteArray()
    }

    // ── Diálogos ─────────────────────────────────────────────────────────────

    private fun aviso(act: Activity, titulo: String, msg: String) {
        AlertDialog.Builder(act).setTitle(titulo).setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)

    private fun ofrecer(act: Activity, v: VersionRemota) {
        val commit = if (v.commit.length >= 7) "\nCommit ${v.commit.take(7)}" else ""
        AlertDialog.Builder(act)
            .setTitle("New build available")
            .setMessage("${nombreInstalada(act)} → ${v.nombre} (${mb(v.bytes)})$commit\n\n" +
                "It is checked (checksum and signing key) before Android asks you to install it. " +
                "Your wallets and settings stay as they are.")
            .setPositiveButton("Download") { _, _ -> descargar(act, v) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun descargar(act: Activity, v: VersionRemota) {
        val dir = carpeta(act).apply { mkdirs() }
        val destino = File(dir, "hunter-${v.codigo}.apk")
        // Ya bajado y bueno de una vez anterior: directo al instalador.
        if (destino.length() == v.bytes && sha256(destino) == v.sha256) {
            verificarEInstalar(act, destino, v); return
        }
        val barra = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = false
        }
        val texto = TextView(act).apply {
            text = "0 / ${mb(v.bytes)}"; gravity = Gravity.END
            setTextColor(AppTheme.TXT_SEC)
        }
        val d = (24 * act.resources.displayMetrics.density).toInt()
        val caja = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d, d / 2, d, 0)
            addView(barra); addView(texto)
        }
        val cancelar = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(act)
            .setTitle("Downloading ${v.nombre}")
            .setView(caja)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelar.set(true) }
            .show()
        Thread {
            val parte = File(dir, destino.name + ".part")
            val error: String? = try {
                val c = abrir(URL_APK, 30_000)
                try {
                    if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                    val md = MessageDigest.getInstance("SHA-256")
                    var leidos = 0L
                    var ultimoPinte = 0L
                    c.inputStream.use { ins -> parte.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (!cancelar.get()) {
                            val n = ins.read(buf); if (n < 0) break
                            leidos += n
                            if (leidos > v.bytes) throw java.io.IOException("bigger than announced")
                            md.update(buf, 0, n); out.write(buf, 0, n)
                            val t = System.currentTimeMillis()
                            if (t - ultimoPinte > 150) {
                                ultimoPinte = t
                                val l = leidos
                                act.runOnUiThread {
                                    barra.progress = (l * 1000 / v.bytes).toInt()
                                    texto.text = "${mb(l)} / ${mb(v.bytes)}"
                                }
                            }
                        }
                    } }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    when {
                        cancelar.get() -> ""
                        leidos != v.bytes -> "The download was cut short (${mb(leidos)} of ${mb(v.bytes)})."
                        hex != v.sha256 ->
                            "The file doesn't match the published checksum. Maybe a newer build was published " +
                            "during the download: try again in a few minutes."
                        !parte.renameTo(destino) -> "Couldn't save the file."
                        else -> null
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) {
                if (cancelar.get()) "" else "Download failed (${e.javaClass.simpleName}). Check the connection and try again."
            }
            parte.delete()
            act.runOnUiThread {
                if (dlg.isShowing) dlg.dismiss()
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                when {
                    error == null -> verificarEInstalar(act, destino, v)
                    error.isNotEmpty() -> { destino.delete(); aviso(act, "Update", error) }
                }
            }
        }.apply { isDaemon = true; name = "actualizador-apk" }.start()
    }

    // ── Comprobación del APK e instalación ───────────────────────────────────

    private fun verificarEInstalar(act: Activity, apk: File, v: VersionRemota) {
        val problema = comprobarApk(act, apk, v)
        if (problema != null) {
            apk.delete()
            aviso(act, "Update refused", problema)
            return
        }
        instalar(act, apk)
    }

    /** null si se puede instalar; si no, el motivo. */
    private fun comprobarApk(ctx: Context, apk: File, v: VersionRemota): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val nuevo = pm.getPackageArchiveInfo(apk.path, flags)
            ?: return "The downloaded file isn't a valid app package."
        if (nuevo.packageName != ctx.packageName)
            return "The downloaded package is ${nuevo.packageName}, not this app."
        val codigo = PackageInfoCompat.getLongVersionCode(nuevo)
        if (codigo != v.codigo || codigo <= instalada(ctx))
            return "The downloaded build ($codigo) isn't newer than the installed one (${instalada(ctx)})."
        val actual = try { pm.getPackageInfo(ctx.packageName, flags) } catch (_: Exception) { null }
        val firmasNuevo = huellas(nuevo)
        val firmasActual = actual?.let { huellas(it) }.orEmpty()
        // Si alguna de las dos no se puede leer se deja decidir al instalador,
        // que también rechaza una firma distinta.
        if (firmasNuevo.isNotEmpty() && firmasActual.isNotEmpty() && firmasNuevo != firmasActual)
            return "The new build is signed with a different key than the installed app, so Android " +
                   "can't install it on top. This happens if the installed copy came from another build " +
                   "(for example a debug build). Uninstalling loses the app's data: make a backup first."
        return null
    }

    private fun huellas(pi: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else @Suppress("DEPRECATION") pi.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return certs.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun instalar(act: Activity, apk: File) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("Allow installing updates")
                .setMessage("Android needs permission for Hunter Suite to install its own updates. " +
                    "Turn on \"Allow from this source\", come back and tap Download again.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + act.packageName)))
                    } catch (_: Exception) {
                        Toast.makeText(act, "Open Settings → Apps → Hunter Suite → Install unknown apps",
                            Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(act, act.packageName + ".provider", apk)
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            aviso(act, "Update", "Couldn't open the installer (${e.javaClass.simpleName}).")
        }
    }

    // ── Limpieza ─────────────────────────────────────────────────────────────

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Borra los APK ya instalados (o más viejos) y las descargas a medias. */
    private fun limpiarViejos(ctx: Context) {
        val actual = instalada(ctx)
        carpeta(ctx).listFiles()?.forEach { f ->
            val cod = Regex("^hunter-(\\d+)\\.apk$").find(f.name)?.groupValues?.get(1)?.toLongOrNull()
            // Las .part sólo si son viejas: puede haber una descarga en marcha.
            val parteVieja = f.name.endsWith(".part") &&
                System.currentTimeMillis() - f.lastModified() > 3600_000
            if (parteVieja || (!f.name.endsWith(".part") && (cod == null || cod <= actual))) f.delete()
        }
    }
}
