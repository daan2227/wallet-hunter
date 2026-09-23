package com.hunter.btc

/**
 * Bajar el ritmo cuando el móvil se calienta.
 *
 * Esto no existía. Estaba TODO escrito y desconectado en cada juntura:
 * getCpuTemp() y getBatteryTemp() definidas y sin una sola llamada,
 * tempCallback invocado por el servicio sin que nadie se suscribiera,
 * tvThermal creado en GONE y sin texto nunca, y thermalThrottleEnabled,
 * lastThermalCheck, originalCpuLimit e isThrottled declarados y jamás leídos
 * ni escritos. En el motor de C++ tampoco había nada: `grep -i thermal` sobre
 * cpp/ no devolvía una línea.
 *
 * O sea que la app corría al porcentaje que le pusieras, durante días, sin
 * bajar nunca. La única temperatura que había era el número de la
 * notificación, decorativo.
 *
 * DÓNDE VIVE. En el servicio, no en la pantalla. Un gobernador que sólo
 * funcione con MainActivity delante deja de funcionar justo cuando hace falta:
 * pantalla apagada, móvil en el bolsillo o trabajando para un cluster sin que
 * nadie mire. El servicio ya recibe ACTION_BATTERY_CHANGED y ya tiene un tick
 * cada dos segundos, así que el gobernador va donde ya está el dato.
 *
 * QUÉ TEMPERATURA. La de la BATERÍA, que es la que llega en el broadcast y la
 * única que da todo aparato sin permisos ni rutas de /sys que cambian con cada
 * fabricante. Va por detrás de la del SoC y no sirve para perseguir picos de
 * milisegundos — pero es que lo que aquí se quiere evitar no es un pico: es
 * tener la batería a 45° durante tres días, que es lo que se come su capacidad
 * para siempre. Para eso la de la batería no es un sucedáneo, es la buena.
 *
 * QUÉ NO HACE. No pelea con el ajuste del usuario. El usuario pide un
 * porcentaje con [pedir] y eso queda como [deseado]; el gobernador sólo elige
 * un escalón y aplica el producto. Cuando se enfría, [deseado] vuelve solo: no
 * hay que acordarse de guardar y restaurar nada, que es donde estas cosas se
 * rompen.
 */
object Termico {

    /**
     * Escalones. Subir de escalón es enfriar, no castigar: el trabajo no se
     * pierde —la fuerza bruta va por bloques y Kangaroo guarda su tabla—, sólo
     * se hace más despacio.
     *
     * Los cortes son de temperatura de BATERÍA, que no es la del SoC: 39° ya
     * es un móvil trabajando de verdad, 45° es un móvil que quema al tocarlo, y
     * a partir de 48° se está pagando con vida de batería un rato de búsqueda
     * que no vale eso.
     */
    private val CORTES = floatArrayOf(39f, 42f, 45f, 48f)

    /** Qué fracción del límite del usuario se deja en cada escalón, en %. */
    private val FACTOR = intArrayOf(100, 75, 50, 25, 0)

    /**
     * Cuánto hay que enfriarse para volver a subir el ritmo.
     *
     * Sin esto el escalón bailaría en cada lectura alrededor del corte, y cada
     * baile es un setCpuLimit y un salto visible en la velocidad. Dos grados es
     * más que el ruido del sensor y menos que lo que tarda en calentarse otra
     * vez.
     */
    private const val HISTERESIS = 2f

    /** El porcentaje que ha pedido el usuario. Lo que se respeta en frío. */
    @Volatile var deseado: Int = 70
        private set

    /** Escalón actual, 0 = sin limitar, [FACTOR].size-1 = parado. */
    @Volatile var paso: Int = 0
        private set

    /** Última temperatura vista, en °C. 0 si todavía no ha llegado ninguna. */
    @Volatile var tempC: Float = 0f
        private set

    /** ¿Está el gobernador recortando ahora mismo? */
    val limitando: Boolean get() = paso > 0

    /** ¿Se ha parado la búsqueda por calor? */
    val parado: Boolean get() = paso >= FACTOR.size - 1

    /** El porcentaje que toca aplicar de verdad. */
    fun efectivo(): Int = (deseado * FACTOR[paso] / 100).coerceIn(0, 100)

    /**
     * El usuario ha pedido un límite. Se guarda y se aplica ya, recortado si
     * el móvil está caliente.
     */
    fun pedir(pct: Int) {
        deseado = pct.coerceIn(1, 100)
        aplicar()
    }

    /**
     * Una lectura nueva de temperatura. La llama el servicio.
     *
     * @return true si el escalón ha cambiado, para que quien llame avise.
     */
    fun evaluar(t: Float): Boolean {
        // Una lectura de cero es "todavía no ha llegado el broadcast", no un
        // móvil a cero grados. Actuar sobre ella devolvería el ritmo entero a
        // un móvil caliente justo al arrancar el servicio.
        if (t <= 0f) return false
        tempC = t
        val antes = paso
        // Sube de uno en uno aunque el salto sea grande: así el aviso y la
        // notificación cuentan el camino en vez de saltar de "normal" a
        // "parado" sin nada en medio.
        if (paso < CORTES.size && t >= CORTES[paso]) paso++
        else if (paso > 0 && t < CORTES[paso - 1] - HISTERESIS) paso--
        if (paso != antes) aplicar()
        return paso != antes
    }

    private fun aplicar() {
        val pct = efectivo()
        // Las dos: un móvil puede estar en fuerza bruta o en Kangaroo, y las
        // dos llamadas son baratas y no molestan si ese motor no corre. Dejar
        // una sin tocar es como se llega a "bajé el límite y siguió a tope".
        try { HunterEngine.setCpuLimit(pct.coerceAtLeast(1)) } catch (e: Throwable) {}
        try { HunterEngine.kangarooSetCpu(pct.coerceAtLeast(1)) } catch (e: Throwable) {}
    }

    /** Para la pantalla: "48 °C · paused to cool down". */
    fun resumen(): String {
        val t = "${"%.0f".format(tempC)} °C"
        return when {
            tempC <= 0f -> ""
            parado      -> "$t · paused to cool down"
            limitando   -> "$t · throttled to ${efectivo()} % CPU"
            else        -> t
        }
    }
}
