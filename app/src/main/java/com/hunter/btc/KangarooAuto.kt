package com.hunter.btc

import java.math.BigInteger

/**
 * Kangaroo con auto-avance: el rango se parte en K trozos y cada uno recibe
 * un presupuesto de c·√(trozo) operaciones; si en ese presupuesto no aparece
 * la clave, se pasa al siguiente.
 *
 * Kangaroo no "acaba" un rango como la fuerza bruta: nunca puede decir "aquí
 * no está". Con un presupuesto finito hay una probabilidad de pasar por
 * encima de la clave sin verla, y la pantalla la enseña antes de empezar.
 *
 * El modelo: el tiempo hasta la colisión de un paseo aleatorio sigue, con
 * buena aproximación, una distribución de Rayleigh (como la paradoja del
 * cumpleaños). Con la media que mide el motor, COSTE_KANGAROO·√W, su
 * parámetro es σ = media/√(π/2), y P(encontrar con c·√W) = 1 − e^(−c²/2σ²).
 * Es una estimación: en trozos pequeños pesa el coste fijo de llegar a los
 * primeros puntos distinguidos y la probabilidad real es algo menor.
 *
 * El estado vive en preferencias, así que sobrevive a cerrar la app: al volver
 * a la pestaña, la vigilancia sigue desde el trozo en el que estaba.
 */
internal object KgAuto {
    const val ON = "kga_on"; const val PUB = "kga_pub"; const val INI = "kga_ini"; const val FIN = "kga_fin"
    const val K = "kga_k"; const val C = "kga_c"; const val I = "kga_i"

    /** Probabilidad de encontrar la clave en un trozo que la contiene. */
    fun probabilidad(c: Double, coste: Double): Double {
        val sigma = coste / Math.sqrt(Math.PI / 2)
        return 1.0 - Math.exp(-(c * c) / (2 * sigma * sigma))
    }

    /** Límites del trozo i (0..k-1) de [a, b]; el último se queda el resto. */
    fun trozo(a: BigInteger, b: BigInteger, k: Int, i: Int): Pair<BigInteger, BigInteger> {
        val w = b.subtract(a).add(BigInteger.ONE).divide(BigInteger.valueOf(k.toLong()))
        val ini = a.add(w.multiply(BigInteger.valueOf(i.toLong())))
        val fin = if (i == k - 1) b else ini.add(w).subtract(BigInteger.ONE)
        return ini to fin
    }

    /** Texto con lo que va a costar y lo que se arriesga. */
    fun resumen(a: BigInteger, b: BigInteger, k: Int, c: Double, coste: Double): String {
        val w = b.subtract(a).add(BigInteger.ONE)
        val parte = w.divide(BigInteger.valueOf(k.toLong()))
        val p = probabilidad(c, coste)
        val porParte = c * Math.sqrt(parte.toDouble())
        val total = porParte * k
        val entero = coste * Math.sqrt(w.toDouble())
        fun log2(x: Double) = "2^%.1f".format(Math.log(x) / Math.log(2.0))
        return "$k parts of ${log2(parte.toDouble())} keys · budget ${"%.1f".format(c)}×√ = ${log2(porParte)} ops each\n" +
               "Chance of finding the key if it is in a part: ${"%.1f".format(p * 100)} % " +
               "(so ${"%.1f".format((1 - p) * 100)} % of passing it by)\n" +
               "All parts: ${log2(total)} ops, against ${log2(entero)} on average for the whole range at once"
    }
}

/** Empieza el auto-avance desde el primer trozo. */
internal fun MainActivity.kgAutoIniciar(pub: String, a: BigInteger, b: BigInteger, k: Int, c: Double) {
    prefs.edit().putBoolean(KgAuto.ON, true).putString(KgAuto.PUB, pub)
        .putString(KgAuto.INI, a.toString(16)).putString(KgAuto.FIN, b.toString(16))
        .putInt(KgAuto.K, k).putFloat(KgAuto.C, c.toFloat()).putInt(KgAuto.I, 0).apply()
    kgAutoLanzarParte()
}

internal fun MainActivity.kgAutoParar(motivo: String? = null) {
    prefs.edit().putBoolean(KgAuto.ON, false).apply()
    if (motivo != null) { tvPuzzleAtajo?.text = motivo; tvPuzzleAtajo?.setTextColor(AppTheme.TXT_SEC) }
}

private fun MainActivity.kgAutoLanzarParte() {
    val pub = prefs.getString(KgAuto.PUB, "") ?: ""
    val a = BigInteger(prefs.getString(KgAuto.INI, "0") ?: "0", 16)
    val b = BigInteger(prefs.getString(KgAuto.FIN, "0") ?: "0", 16)
    val k = prefs.getInt(KgAuto.K, 1); val i = prefs.getInt(KgAuto.I, 0)
    val (pi, pf) = KgAuto.trozo(a, b, k, i)
    if (HunterEngine.kangarooRunning()) try { HunterEngine.kangarooStop() } catch (t: Throwable) {}
    puzzleSeleccionado = 0
    puzzlePubHex = pub; puzzleIniHex = pi.toString(16); puzzleFinHex = pf.toString(16)
    prefs.edit().putString("kangaroo_pub", pub).putString("kangaroo_ini", puzzleIniHex)
        .putString("kangaroo_fin", puzzleFinHex).apply()
    btnKangaroo?.visibility = android.view.View.VISIBLE
    alternarKangaroo()                     // no está corriendo: arranca
    if (!HunterEngine.kangarooRunning()) { kgAutoParar(null); return }
    kgAutoVigilar()
}

/** Cada 3 s: ¿apareció la clave?, ¿se acabó el presupuesto del trozo? */
internal fun MainActivity.kgAutoVigilar() {
    handler.removeCallbacksAndMessages(KgAuto)
    handler.postAtTime({ kgAutoPaso() }, KgAuto, android.os.SystemClock.uptimeMillis() + 3000)
}

private fun MainActivity.kgAutoPaso() {
    if (!prefs.getBoolean(KgAuto.ON, false)) return
    val clave = try { HunterEngine.kangarooResult() } catch (t: Throwable) { "" }
    if (clave.length == 64) { kgAutoParar(null); return }       // refrescarKangaroo la guarda y avisa
    /* refrescarKangaroo guarda la clave y PARA el motor, y después de parar
       kangarooResult() ya no la devuelve. Si esta vigilancia llegaba justo
       entonces, veía "no corre y no hay clave" y tapaba el "KEY FOUND" con
       "Auto-advance stopped.". kgClavePintada dice que sí la hubo. */
    if (kgClavePintada.isNotEmpty()) { kgAutoParar(null); return }
    if (!HunterEngine.kangarooRunning()) { kgAutoParar("Auto-advance stopped."); return }
    val a = BigInteger(prefs.getString(KgAuto.INI, "0") ?: "0", 16)
    val b = BigInteger(prefs.getString(KgAuto.FIN, "0") ?: "0", 16)
    val k = prefs.getInt(KgAuto.K, 1); val i = prefs.getInt(KgAuto.I, 0)
    val c = prefs.getFloat(KgAuto.C, 4f).toDouble()
    val (pi, pf) = KgAuto.trozo(a, b, k, i)
    val presupuesto = c * Math.sqrt(pf.subtract(pi).add(BigInteger.ONE).toDouble())
    val ops = try { HunterEngine.kangarooOps().toDouble() } catch (t: Throwable) { 0.0 }
    if (ops >= presupuesto) {
        if (i + 1 >= k) {
            try { HunterEngine.kangarooStop() } catch (t: Throwable) {}
            prefs.edit().putBoolean("kangaroo_corriendo", false).apply()
            btnKangaroo?.text = "Search with Kangaroo"
            val p = KgAuto.probabilidad(c, COSTE_KANGAROO)
            kgAutoParar("All $k parts done without finding the key. If it was in the range, " +
                        "the chance of having passed it by was ${"%.1f".format((1 - p) * 100)} %.")
            return
        }
        prefs.edit().putInt(KgAuto.I, i + 1).apply()
        kgAutoLanzarParte()
        return
    }
    tvPuzzleAtajo?.text = "Auto-advance · part ${i + 1} of $k · " +
        "${"%.0f".format(100 * ops / presupuesto)} % of its budget\n" +
        "0x${pi.toString(16)} → 0x${pf.toString(16)}"
    tvPuzzleAtajo?.setTextColor(AppTheme.ACCENT)
    kgAutoVigilar()
}

/** Presupuesto del trozo en curso, en operaciones; 0 si no hay auto-avance. */
internal fun MainActivity.kgAutoPresupuesto(): Double {
    if (!prefs.getBoolean(KgAuto.ON, false)) return 0.0
    return try {
        val a = BigInteger(prefs.getString(KgAuto.INI, "0") ?: "0", 16)
        val b = BigInteger(prefs.getString(KgAuto.FIN, "0") ?: "0", 16)
        val (pi, pf) = KgAuto.trozo(a, b, prefs.getInt(KgAuto.K, 1), prefs.getInt(KgAuto.I, 0))
        prefs.getFloat(KgAuto.C, 4f) * Math.sqrt(pf.subtract(pi).add(BigInteger.ONE).toDouble())
    } catch (e: Exception) { 0.0 }
}
