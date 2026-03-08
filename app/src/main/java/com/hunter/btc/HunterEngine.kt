package com.hunter.btc

object HunterEngine {
    init { System.loadLibrary("hunter_jni") }

    external fun loadCsv(path: String)
    external fun startHunting(threads: Int, cpuLimit: Int)
    external fun stopHunting()
    external fun setCpuLimit(v: Int)
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
}
