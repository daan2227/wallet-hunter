package com.hunter.btc

object HunterEngine {
    init { System.loadLibrary("hunter_jni") }
    external fun loadCsv(path: String)
    external fun setMode(mode: Int)
    external fun setRange(start: String, end: String)
    external fun setTarget(addr: String)
    external fun hasTarget(): Boolean
    external fun startHunting(threads: Int, cpuLimit: Int)
    external fun stopHunting()
    external fun setCpuLimit(v: Int)
    external fun setPbkdf2Mode(fast: Int)
    external fun isCsvLoaded(): Boolean
    external fun isLoading(): Boolean
    external fun isRunning(): Boolean
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
    external fun buildAndSignTx(requestJson: String): String
    external fun getLastKey(): String
}
