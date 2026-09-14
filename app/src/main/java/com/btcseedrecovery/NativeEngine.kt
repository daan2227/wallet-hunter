package com.btcseedrecovery

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

    fun getBinaryPath(ctx: Context): String {
        // Primero buscar en filesDir (interno)
        val internal = File(ctx.filesDir, BINARY_NAME)
        if (internal.exists() && internal.canExecute()) return internal.absolutePath
        // Luego en externalFilesDir
        val external = File(ctx.getExternalFilesDir(null), BINARY_NAME)
        if (external.exists()) {
            // Copiar a filesDir para tener permisos de ejecución
            external.copyTo(internal, overwrite = true)
            internal.setExecutable(true)
            return internal.absolutePath
        }
        return ""
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

                val pb = ProcessBuilder(cmd).apply {
                    redirectErrorStream(true)
                    environment()["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
                }
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
