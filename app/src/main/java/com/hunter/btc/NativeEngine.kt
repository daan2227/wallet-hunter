package com.hunter.btc

import android.content.Context
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object NativeEngine {
    private const val BINARY_NAME = "hunter_master"
    private val SPEED_REGEX = Regex("Speed:\\s*([\\d.]+)\\s*K/s")
    private val TOTAL_REGEX = Regex("Total:\\s*(\\d+)")
    private var process: Process? = null
    private val running = AtomicBoolean(false)
    val speed = AtomicLong(0)
    val total = AtomicLong(0)
    var onMatch: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    /**
     * Sólo se ejecuta el binario de almacenamiento interno, que llega ahí
     * mediante el selector de ficheros (el usuario elige conscientemente qué
     * instalar).
     *
     * Antes, si no existía el interno, se copiaba automáticamente cualquier
     * fichero llamado "hunter_master" desde getExternalFilesDir(), se marcaba
     * ejecutable y se lanzaba, sin hash ni firma. Cualquiera que pudiera
     * escribir ahí —por USB, o una app con MANAGE_EXTERNAL_STORAGE— conseguía
     * ejecución de código con los permisos de esta app, que incluyen acceso a
     * filesDir, donde viven las seeds cifradas.
     */
    fun getBinaryPath(ctx: Context): String {
        val internal = File(ctx.filesDir, BINARY_NAME)
        return if (internal.exists() && internal.canExecute()) internal.absolutePath else ""
    }

    /** SHA-256 del binario instalado, para que el usuario pueda verificarlo. */
    fun binarySha256(ctx: Context): String? {
        val f = File(ctx.filesDir, BINARY_NAME)
        if (!f.exists()) return null
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { null }
    }

    fun isAvailable(ctx: Context) = getBinaryPath(ctx).isNotEmpty()

    fun start(ctx: Context, dbPath: String, threads: Int, mode: String = "LEGACY") {
        if (running.get()) return
        val binPath = getBinaryPath(ctx)
        if (binPath.isEmpty()) {
            onLog?.invoke("ERROR: binario hunter_master no encontrado")
            return
        }

        running.set(true)
        speed.set(0); total.set(0)

        Thread {
            try {
                val cmd = listOf(binPath, mode, dbPath, threads.toString())
                onLog?.invoke("Iniciando: ${cmd.joinToString(" ")}")

                // Sin LD_LIBRARY_PATH: apuntaba a /data/data/com.termux/files/usr/lib,
                // es decir, cargaba librerías desde OTRA aplicación. Resto de
                // desarrollo y un vector para inyectar código en el proceso hijo.
                val pb = ProcessBuilder(cmd).apply { redirectErrorStream(true) }
                process = pb.start()

                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) parseLine(line)
                }
            } catch (e: Exception) {
                onLog?.invoke("Error proceso nativo: ${e.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun parseLine(line: String) {
        onLog?.invoke(line)
        val speedMatch = SPEED_REGEX.find(line)
        val totalMatch = TOTAL_REGEX.find(line)
        speedMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            speed.set(it.toLong())
        }
        totalMatch?.groupValues?.get(1)?.toLongOrNull()?.let {
            total.set(it)
        }
        // Detectar match
        if (line.contains("MATCH") || line.contains("found_keys.txt")) {
            onMatch?.invoke(line)
        }
    }

    fun stop() {
        running.set(false)
        process?.destroy()
        process = null
        speed.set(0)
    }

    fun isRunning() = running.get()
}
