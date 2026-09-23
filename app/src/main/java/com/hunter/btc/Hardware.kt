package com.hunter.btc

/** Lo que se sabe del móvil para decidir hilos y límite de CPU. */
data class HardwareProfile(
    val cores: Int,
    val recommendedThreads: Int,
    val recommendedCpu: Int,
    val chipName: String,
    val ramMB: Long,
    val archInfo: String
)

/**
 * Detección del hardware y afinidad de núcleos.
 *
 * Estaba dentro de MainActivity, que pasaba de 6.000 líneas; no depende de
 * nada de esa pantalla salvo un Context para preguntar la memoria.
 */
object Hardware {

    /**
     * Los núcleos rápidos de verdad, leídos del sistema.
     *
     * Estaban ADIVINADOS: "los últimos 4 suelen ser los big", o sea
     * intArrayOf(4,5,6,7). En un Dimensity 1080 eso es falso: la topología es
     * 2 Cortex-A78 + 6 Cortex-A55, así que los núcleos 4 y 5 son PEQUEÑOS y
     * sólo el 6 y el 7 son grandes. Fijar hilos en 4 y 5 creyendo que son
     * rápidos es peor que no fijar nada, porque además deja los demás libres.
     *
     * Se lee del sistema, sin suposiciones sobre el modelo: primero
     * cpu_capacity —la capacidad relativa que asigna el kernel, que es lo que
     * usa el planificador— y si no está, la frecuencia máxima. Si no hay
     * ninguna de las dos, se devuelve vacío: mejor sin afinidad que con una
     * inventada.
     */
    /**
     * Decide la afinidad JUSTO ANTES de arrancar, con los hilos que se van a
     * usar de verdad.
     *
     * Estaba sólo dentro del botón de "configuración óptima", y con el valor
     * que tuviera el deslizador en ese momento. Dos consecuencias:
     *
     *  - Si nunca pulsabas ese botón, la afinidad no se activaba jamás.
     *  - Y si lo pulsabas con "Medium" y luego cambiabas a "High", quedaban ocho
     *    hilos clavados en cuatro núcleos, que es el caso malo: cuatro núcleos
     *    sin usar y los hilos amontonados de dos en dos.
     *
     * Fijar sólo tiene sentido si los hilos CABEN en los núcleos rápidos. Si no,
     * se deja al planificador. En un Exynos 1580 —4 A720 rápidos— con "Medium"
     * (4 hilos) sí se fija; con "High" (8) no. En un Dimensity 1080 —2 A78— no
     * se fija nunca salvo en "Low".
     */
    fun aplicarAfinidad(hilos: Int) {
        val rapidos = nucleosRapidos()
        val fijar = rapidos.isNotEmpty() && hilos <= rapidos.size
        try {
            HunterEngine.setBigCores(
                if (rapidos.isEmpty()) intArrayOf(-1) else rapidos, fijar)
        } catch (e: Exception) {}
    }

    private fun nucleosRapidos(): IntArray {
        val n = Runtime.getRuntime().availableProcessors()
        if (n <= 1) return IntArray(0)

        fun leer(ruta: String): Int = try {
            java.io.File(ruta).readText().trim().toInt()
        } catch (e: Exception) { 0 }

        // 1) cpu_capacity es lo que hay que mirar: es la capacidad RELATIVA que
        //    el propio kernel asigna a cada núcleo, y es justo lo que el
        //    planificador usa para repartir. No todos los móviles lo exponen.
        var peso = IntArray(n) { leer("/sys/devices/system/cpu/cpu$it/cpu_capacity") }
        // 2) Si no está, la frecuencia máxima. Es un sustituto: casi siempre el
        //    orden coincide, pero un A55 a 2,0 GHz no rinde como un A78 a 2,0.
        if (peso.any { it <= 0 })
            peso = IntArray(n) { leer("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
        // 3) Ni una cosa ni otra: sin afinidad. Mejor eso que una inventada.
        if (peso.any { it <= 0 }) return IntArray(0)

        val max = peso.max()
        // Todos iguales: no hay big.LITTLE que aprovechar.
        if (peso.all { it == max }) return IntArray(0)

        // Se toman los núcleos "del grupo de arriba", no sólo los del máximo
        // exacto. En un chip de tres clústeres (1 prime + 3 grandes + 4
        // pequeños) quedarse con el máximo exacto devolvía UN solo núcleo y
        // descartaba los tres grandes. Con el umbral al 85 % del máximo entran
        // el prime y los grandes, y quedan fuera los pequeños, que están muy
        // por debajo.
        val umbral = max * 85 / 100
        val rapidos = (0 until n).filter { peso[it] >= umbral }
        return if (rapidos.size == n) IntArray(0) else rapidos.toIntArray()
    }

    fun detectar(ctx: android.content.Context): HardwareProfile {
        val cores = Runtime.getRuntime().availableProcessors()

        // Leer info del chip desde /proc/cpuinfo
        val chipName = try {
            val cpuinfo = java.io.File("/proc/cpuinfo").readText()
            val hardware = cpuinfo.lines()
                .firstOrNull { it.startsWith("Hardware") }
                ?.substringAfter(":")?.trim() ?: ""
            val model = cpuinfo.lines()
                .firstOrNull { it.startsWith("model name") || it.startsWith("Model name") }
                ?.substringAfter(":")?.trim() ?: ""
            when {
                hardware.contains("Snapdragon", true) -> hardware
                model.contains("Snapdragon", true)    -> model
                hardware.contains("Exynos", true)     -> hardware
                hardware.contains("Dimensity", true)  -> hardware
                hardware.isNotEmpty()                 -> hardware
                else -> {
                    // Fallback: usar Build.MODEL y SOC info
                    val soc = if (android.os.Build.VERSION.SDK_INT >= 31)
                        android.os.Build.SOC_MODEL
                    else ""
                    val model = android.os.Build.MODEL
                    when {
                        soc.isNotEmpty() && soc != "unknown" -> "$soc ($cores cores)"
                        model.contains("SM-S9", true) -> "Snapdragon 8 Gen 2 ($cores cores)"
                        model.contains("SM-S8", true) -> "Snapdragon 8 Gen 1 ($cores cores)"
                        model.contains("SM-A5", true) -> "Snapdragon 778G ($cores cores)"
                        model.contains("SM-A3", true) -> "Snapdragon 680 ($cores cores)"
                        else -> "ARM64 · ${model} ($cores cores)"
                    }
                }
            }
        } catch (e: Exception) { "ARM64 (${cores} cores)" }

        // RAM disponible
        val ramMB = try {
            val rt = Runtime.getRuntime()
            val actManager = ctx.getSystemService(android.app.ActivityManager::class.java)
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (e: Exception) { 0L }

        // Arquitectura
        val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        // Calcular threads óptimos:
        // - Dejar 2 cores para sistema y UI
        // - Máximo 8 threads útiles para este tipo de workload
        val recommendedThreads = (cores - 2).coerceIn(2, 8)

        // CPU limit según RAM (menos RAM = más conservador)
        val recommendedCpu = when {
            ramMB > 3000 -> 90
            ramMB > 1500 -> 75
            ramMB > 800  -> 60
            else         -> 50
        }

        return HardwareProfile(
            cores = cores,
            recommendedThreads = recommendedThreads,
            recommendedCpu = recommendedCpu,
            chipName = chipName,
            ramMB = ramMB,
            archInfo = arch
        )
    }
}
