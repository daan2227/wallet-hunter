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

    /* ── Kangaroo ─────────────────────────────────────────────────────────
       Logaritmo discreto en un intervalo, O(raíz(n)) en vez de O(n).
       Necesita la CLAVE PÚBLICA del objetivo, no la dirección: de una
       dirección no se puede volver atrás. PubKeyFinder averigua si existe.

       Verificado en tools/ec-harness/kang.cpp contra logaritmos conocidos. */

    /**
     * @param pubHex clave pública comprimida, 66 caracteres
     * @param iniHex inicio del rango, hex (se alinea a la derecha)
     * @param finHex fin del rango
     * @return false si la clave pública no es válida o ya hay una búsqueda
     */
    /**
     * @param rutaEstado fichero donde guardar y de donde recuperar el trabajo.
     *   Lo que se conserva es la tabla de puntos distinguidos, que es DONDE
     *   está el progreso: los canguros se vuelven a soltar y eso cuesta nada.
     *   La cabecera lleva la clave pública y el rango, así que un fichero de
     *   otro puzzle se ignora en vez de mezclarse.
     */
    external fun kangarooStart(pubHex: String, iniHex: String, finHex: String,
                               hilos: Int, canguresPorHilo: Int,
                               rutaEstado: String): Boolean
    external fun kangarooStop()
    /** Operaciones de grupo hechas: es lo que se compara con raíz(W). */
    external fun kangarooOps(): Long
    external fun kangarooRunning(): Boolean
    /** Clave privada en hex de 64 caracteres, o "" si todavía no está. */
    external fun kangarooResult(): String
    /** Guarda el trabajo ahora mismo. Android puede matar la app sin avisar. */
    external fun kangarooSave(): Boolean
    /** Puntos distinguidos acumulados: el trabajo que sobrevive a un reinicio. */
    external fun kangarooPoints(): Long
    /** Cambia el límite de CPU con la búsqueda en marcha, sin reiniciarla. */
    external fun kangarooSetCpu(pct: Int)

    /* ── Reparto por red ──────────────────────────────────────────────────────
     *
     * Repartir Kangaroo NO es partir el rango entre los móviles. Partirlo lo
     * empeora: el coste es raíz(W), así que cada trozo cuesta raíz(W/N) pero
     * hay que recorrer varios porque no se sabe en cuál está la clave. Con dos
     * aparatos sale 1,06·raíz(W) frente a 1,00 de uno solo.
     *
     * Lo que sí funciona es que todos caminen el MISMO intervalo y compartan la
     * tabla de puntos distinguidos, igual que hacen los hilos dentro de un
     * móvil. Así el reparto es casi lineal.
     *
     * CUIDADO: lo que viaja son pares (punto, distancia). Dos de rebaños
     * distintos que coincidan dan la clave privada directamente. Es material de
     * clave, y no hay manera de evitarlo sin perder todo el beneficio.
     */

    /**
     * Los puntos distinguidos que todavía no se han mandado.
     *
     * Cada llamada devuelve sólo lo nuevo, así que se puede llamar en bucle sin
     * reenviar la tabla entera.
     *
     * @param maxEntradas tope por envío, para que un mensaje no se haga enorme.
     * @return los bytes a mandar tal cual, o null si no hay nada nuevo.
     */
    external fun kangarooExport(maxEntradas: Int): ByteArray?

    /**
     * Mete puntos que llegan de otro aparato.
     *
     * El bloque lleva dentro el puzzle, el rango y el criterio de distinguido:
     * si no cuadran con los de aquí se rechaza entero, porque mezclar tablas de
     * búsquedas distintas daría colisiones que no significan nada.
     *
     * @return cuántos han entrado, o -1 si el bloque no valía.
     */
    external fun kangarooImport(datos: ByteArray): Int

    /** La clave pública con la que se arrancó, en hex. "" si no hay búsqueda. */
    external fun kangarooPub(): String
}
