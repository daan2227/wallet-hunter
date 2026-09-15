package com.hunter.btc

object HunterEngine {
    init { System.loadLibrary("hunter_jni") }
    external fun loadCsv(path: String)
    /** Directorio donde el motor escribe coincidencias.txt (contiene WIF en claro). */
    external fun setMatchDir(dir: String)
    external fun setMode(mode: Int)
    external fun setRange(start: String, end: String)
    external fun setTarget(addr: String)
    external fun hasTarget(): Boolean
    external fun startHunting(threads: Int, cpuLimit: Int)
    external fun stopHunting()
    external fun setCpuLimit(v: Int)
    external fun setPbkdf2Mode(fast: Int)
    /** Rutas a derivar en modo BIP39: bit0=BIP44 (1...), bit1=BIP84 (bc1q...). */
    external fun setBip39Paths(mask: Int)
    external fun isCsvLoaded(): Boolean
    external fun isLoading(): Boolean
    external fun isRunning(): Boolean
    /** Parada en curso: los workers aún no han terminado. */
    external fun isStopping(): Boolean
    external fun getLoadStatus(): String
    external fun getWps(): Double
    external fun getCount(): Long
    external fun getFound(): Long
    external fun getElapsed(): Long
    external fun popLog(): String
    external fun getMatches(): String
    external fun wifToAddr(wif: String): String
    external fun popMatch(): String
    external fun popRecentAddr(): String
    external fun deriveWallet(mnemonic: String): String
    /**
     * Direcciones de una rama BIP32 concreta.
     * @param purpose 44, 49, 84 u 86
     * @param change 0 recepción, 1 cambio
     * @return JSON [{"i":n,"addr":"..."},...]
     */
    external fun deriveAddresses(mnemonic: String, purpose: Int, change: Int,
                                 from: Int, count: Int): String
    external fun buildAndSignTx(requestJson: String): String
    external fun setBigCores(cores: IntArray, enable: Boolean)
    external fun setBatchSize(size: Int)
    external fun getBatchSize(): Int
    external fun setSequential(seq: Boolean)
    external fun getCsvCount(): Long
    external fun isSequential(): Boolean
    external fun getSeqProgress(): String
    external fun getLastKey(): String
}
