package com.hunter.btc.recovery

import android.content.Context
import android.content.res.AssetManager

/**
 * Wrapper Kotlin para el motor nativo de recovery.
 * Carga el wordlist BIP39 y llama al JNI bruteForceSeeds().
 */
class RecoveryEngine(private val context: Context) {

    // ── Callback de progreso ──────────────────────────────────────────────────
    interface ProgressListener {
        fun onProgress(attempts: Long, total: Long, currentWord: String)
        fun onFound(mnemonic: String)
        fun onFoundWithAddress(mnemonic: String, address: String)
        fun onNotFound()
        fun onCancelled()
    }

    var listener: ProgressListener? = null
    private var wordlist: Array<String>? = null

    // ── Cargar wordlist desde assets ──────────────────────────────────────────
    fun getWordlistSet(): Set<String> = wordlist?.toSet() ?: emptySet()

    fun loadWordlist(): Boolean {
        return try {
            val words = context.assets
                .open("bip39_english.txt")
                .bufferedReader()
                .use { it.readLines() }
                .filter { it.isNotBlank() }
                .toTypedArray()
            wordlist = words
            words.size == 2048
        } catch (e: Exception) {
            false
        }
    }

    // ── Iniciar recovery en background ────────────────────────────────────────
    fun startRecovery(parsed: ParsedPhrase, targetAddress: String) {
        g_cancelled = false
        val wl = wordlist ?: return

        // Convertir slots a String[] para JNI (string vacío = faltante)
        val slotsArray: Array<String> = parsed.slots
            .map { it ?: "" }
            .toTypedArray()

        val missingArray: IntArray = parsed.missingIndices.toIntArray()

        Thread {
            val result = bruteForceSeeds(
                slotsArray,
                wl,
                missingArray,
                targetAddress
            )
            if (result != null) {
                // Si contiene |ADDR: es modo sin target
                if (result.contains("|ADDR:")) {
                    val parts = result.split("|ADDR:")
                    listener?.onFoundWithAddress(parts[0], parts[1])
                } else {
                    listener?.onFound(result)
                }
            } else {
                if (g_cancelled) {
                    listener?.onCancelled()
                } else {
                    listener?.onNotFound()
                }
            }
        }.start()
    }

    fun cancel() {
        g_cancelled = true
        cancelRecovery()
    }

    // Llamado desde C++ cada 5000 intentos
    fun onProgress(attempts: Long, total: Long, currentWord: String) {
        listener?.onProgress(attempts, total, currentWord)
    }

    // ── JNI ───────────────────────────────────────────────────────────────────
    private external fun bruteForceSeeds(
        slots: Array<String>,
        wordlist: Array<String>,
        missingIndices: IntArray,
        targetAddress: String
    ): String?

    private external fun cancelRecovery()

    companion object {
        @Volatile private var g_cancelled = false
    }
}
