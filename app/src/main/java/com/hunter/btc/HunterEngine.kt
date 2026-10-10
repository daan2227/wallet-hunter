package com.hunter.btc

object HunterEngine {
    init { System.loadLibrary("hunter_jni") }

    /**
     * Contexto de la app, para que [alHallar] pueda abrir el baúl. Lo fijan
     * [conectarBaul] la pantalla principal y el servicio: el motor corre en
     * cualquiera de los dos.
     */
    @Volatile private var appCtx: android.content.Context? = null

    fun conectarBaul(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    /**
     * La llama el motor, desde su propio hilo, en cuanto encuentra algo: la
     * clave va cifrada al baúl sin pasar por disco en claro. Antes se escribía
     * en coincidencias.txt y ahí esperaba hasta la siguiente vez que se abría
     * la app.
     *
     * @return false si no se ha podido guardar; el motor la conserva en
     *   memoria y [MatchVault.recoger] la vuelve a intentar.
     */
    @JvmStatic
    fun alHallar(linea: String): Boolean {
        val ctx = appCtx ?: return false
        return MatchVault.guardarHallazgo(ctx, linea)
    }

    /** Un hallazgo que el motor no pudo entregar al baúl, o "" si no queda. */
    external fun popPorGuardar(): String
    /** Devuelve al motor uno que tampoco se pudo guardar ahora. */
    external fun devolverPorGuardar(linea: String)

    external fun loadCsv(path: String)
    external fun setMode(mode: Int)
    external fun setRange(start: String, end: String)
    external fun setTarget(addr: String)
    external fun hasTarget(): Boolean
    external fun startHunting(threads: Int, cpuLimit: Int)
    external fun stopHunting()
    external fun setCpuLimit(v: Int)
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
    /**
     * De una clave privada en hexadecimal, su WIF y su dirección: `"WIF|addr"`,
     * o `""` si no vale.
     *
     * Kangaroo devuelve la clave y nada más. Sin esto se guardaba en el baúl con
     * la dirección y el WIF vacíos: la entrada estaba, pero sin nada que la
     * identificara ni nada con lo que gastar, así que parecía que no se había
     * guardado nada.
     */
    external fun datosDeClave(privHex: String): String
    /**
     * Todas las direcciones de una clave privada (64 hex) o pública
     * (02/03 + 64, o 04 + 128): P2PKH comprimida y sin comprimir, P2SH-P2WPKH,
     * bech32 y taproot. Líneas "etiqueta=direccion"; vacío si la entrada no vale.
     */
    external fun direccionesDe(entrada: String): String
    /** De una privada hex (64) a sus dos WIF: "comprimida|sin_comprimir", o "". */
    external fun wifDeHex(privHex: String): String
    /** De un WIF a su privada en hex (64), o "" si no vale. */
    external fun hexDeWif(wif: String): String
    /**
     * Recupera la privada de dos firmas con el MISMO nonce: r común, sus s y
     * los hashes de mensaje z. Devuelve la privada en 64 hex, o "" si no puede.
     */
    external fun recuperarNonce(r: String, s1: String, z1: String,
                                s2: String, z2: String): String
    /** Firma un mensaje (estilo Bitcoin) con una privada hex: firma compacta
     *  recuperable de 65 bytes en hex, o "". */
    external fun firmarMensaje(privHex: String, msg: String): String
    /** Verifica una firma (65 bytes hex) sobre un mensaje: devuelve la dirección
     *  P2PKH que la firmó, o "". */
    external fun verificarMensaje(msg: String, sigHex: String): String
    /** Primeras [n] direcciones de recepción (m/0/i) de un xpub/ypub/zpub.
     *  Líneas "i=direccion", o "" si el extended key no vale. */
    external fun xpubDirecciones(xpub: String, n: Int): String
    /** Privada (64 hex) de una ruta BIP32 concreta desde una frase semilla, o "". */
    external fun deriveRuta(mnemonic: String, path: String): String
    /**
     * Las direcciones principales de la cartera.
     *
     * @param testnet cambia la rama del árbol (coin type 1' en vez de 0') y los
     *   prefijos. Son claves distintas, no la misma dirección repintada.
     */
    external fun deriveWallet(mnemonic: String, testnet: Boolean): String
    /**
     * Direcciones de una rama BIP32 concreta.
     *
     * @param purpose 44, 49, 84 u 86
     * @param change 0 recepción, 1 cambio
     * @param testnet coin type 1' y prefijos de la red de pruebas.
     *
     *   Antes no existía este parámetro, y ahí estaba el fallo: el buscador de
     *   huecos preguntaba al explorador de testnet por direcciones derivadas en
     *   mainnet. Una dirección de mainnet nunca aparece en la cadena de
     *   pruebas, así que la respuesta era siempre "sin usar", el primer hueco
     *   salía siempre en el índice 0, y cada envío reutilizaba la misma
     *   dirección de cambio.
     *
     * @return JSON [{"i":n,"addr":"..."},...]
     */
    external fun deriveAddresses(mnemonic: String, purpose: Int, change: Int,
                                 from: Int, count: Int,
                                 testnet: Boolean): String
    external fun buildAndSignTx(requestJson: String): String
    external fun setBigCores(cores: IntArray, enable: Boolean)
    external fun setBatchSize(size: Int)
    external fun getBatchSize(): Int
    external fun setSequential(seq: Boolean)
    /** El secuencial recorrió el rango entero y el motor se paró solo. */
    external fun rangoCompleto(): Boolean
    external fun getCsvCount(): Long
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
    /**
     * @param topeTablaBits techo del tamaño de la tabla de distinguidos, en
     *   potencias de dos. Sale de la RAM del aparato — ver [topeTablaBits] —
     *   porque la tabla es lo único que crece sin parar y lo que decide cuánto
     *   puede durar una búsqueda antes de atascarse. El motor coge el menor
     *   entre esto y lo que pida el tamaño del rango: para un puzzle pequeño no
     *   tiene sentido reservar cientos de megas.
     */
    external fun kangarooStart(pubHex: String, iniHex: String, finHex: String,
                               hilos: Int, canguresPorHilo: Int,
                               rutaEstado: String, topeTablaBits: Int): Boolean
    external fun kangarooStop()


    /**
     * Cuántos puntos caben ANTES de que el motor deje de guardar.
     *
     * No es la capacidad: dp_insert para de guardar al 90 % para no dar vueltas
     * eternamente buscando hueco. Pasado ese punto la búsqueda sigue corriendo y
     * gastando batería, pero ya no acumula nada nuevo — o sea que deja de
     * avanzar sin que nada lo diga. Por eso hay que poder enseñarlo.
     */
    external fun kangarooTope(): Long
    /**
     * Prueba de rendimiento del motor en este móvil: multiplicación de cuerpo
     * en C frente a ensamblador ARM64, y saltos por segundo de Kangaroo en un
     * hilo. Tarda unos cuatro segundos; llamar fuera del hilo principal.
     */
    external fun benchCampo(): String
    /**
     * Prueba de la GPU con Vulkan: multiplicaciones de cuerpo por segundo,
     * comprobadas antes contra la CPU. Fuera del hilo principal.
     */
    /**
     * La GPU de este móvil, sin medir: "nombre|fabricante|vulkan|driver|MB|hilos
     * por grupo", o "" si no hay Vulkan.
     */
    external fun gpuInfo(): String
    /** Usar la GPU como trabajador más de Kangaroo, en la próxima búsqueda. */
    external fun setUsarGpu(v: Boolean)
    /** Qué hace la GPU: off, starting, running on X, error: … */
    external fun gpuEstado(): String
    /** Saltos hechos por la GPU en esta búsqueda (ya van en el total). */
    external fun gpuSaltos(): Long
    /** Kangaroo en la GPU, medido con varios repartos. Unos 15 s; motor parado. */
    external fun benchGpuKangaroo(): String

    /**
     * El techo de tabla que aguanta este aparato, en potencias de dos.
     *
     * Cada hueco son 56 bytes — `sizeof(DP)` en kangaroo.h, donde hay un
     * `static_assert` que salta si cambia para que nadie tenga que acordarse de
     * venir aquí —, así que 2^22 son 235 MB. Se reserva como mucho el
     * 4 % de la RAM total: en un móvil de 8 GB eso da el tope de 2^22, y en uno
     * de 3 GB baja solo a 2^21. El 4 % es un juicio, no una medida: por encima
     * empieza a ser probable que Android mate la app por memoria, y una app
     * muerta pierde mucho más que una tabla pequeña.
     *
     * Sólo pesa en el MAESTRO, que es donde se juntan los puntos de todos.
     */
    fun topeTablaBits(ctx: android.content.Context): Int {
        val ram = try {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                     as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem
        } catch (e: Throwable) { 3L * 1024 * 1024 * 1024 }
        val presupuesto = ram / 25          // 4 %
        var t = 14
        while (t < 22 && (1L shl (t + 1)) * 56L <= presupuesto) t++
        return t
    }
    /** Operaciones de grupo hechas: es lo que se compara con raíz(W). */
    external fun kangarooOps(): Long
    external fun kangarooRunning(): Boolean
    /**
     * ¿Ha aparecido ya el objetivo único?
     *
     * En modo puzzle hay UNA dirección que buscar, así que encontrarla es el
     * final del trabajo y el motor se para solo. Esto distingue esa parada de
     * una inesperada, que es lo que el watchdog necesita saber para no volver a
     * lanzar una búsqueda ya terminada.
     */
    external fun objetivoHallado(): Boolean
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



    /** La clave pública con la que se arrancó, en hex. "" si no hay búsqueda. */
    external fun kangarooPub(): String

}
