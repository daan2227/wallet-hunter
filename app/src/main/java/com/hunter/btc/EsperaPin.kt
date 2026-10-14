package com.hunter.btc

/**
 * Cuánto queda de la espera por demasiados intentos de PIN. Pura: se prueba en
 * la JVM; WalletManager pone el reloj y guarda el resultado.
 *
 * Se mide con el reloj monotónico (tiempo desde el arranque), que no cambia al
 * tocar la fecha del móvil. Pero ese reloj vuelve a cero al reiniciar: guardar
 * "acaba en el instante X" de ese reloj hacía que, tras reiniciar, la espera se
 * quedara congelada hasta que el móvil volviera a llevar X encendido — días, si
 * llevaba días encendido —, con la app entera bloqueada.
 *
 * Ahora se guarda CUÁNDO empezó (en ese reloj, y en qué arranque) y cuánto dura.
 * Si el arranque ya no es el mismo, la espera se vuelve a contar entera desde
 * ahora. Así:
 *   - cambiar la fecha no la acorta (no se usa la hora de pared);
 *   - reiniciar no la acorta (vuelve a empezar);
 *   - y nunca dura más que la espera completa, pase lo que pase.
 */
object EsperaPin {

    /**
     * @param duracionMs espera completa; <= 0 = no hay espera.
     * @param inicio     reloj monotónico al empezar, o null si no se sabe.
     * @param arranqueInicio número de arranque al empezar (-1 = desconocido).
     * @param ahora      reloj monotónico ahora.
     * @param arranque   número de arranque ahora (-1 = desconocido).
     * @return (ms que quedan, nuevo inicio si hay que volver a anclar o null).
     */
    fun resto(duracionMs: Long, inicio: Long?, arranqueInicio: Int,
              ahora: Long, arranque: Int): Pair<Long, Long?> {
        if (duracionMs <= 0) return 0L to null
        val otroArranque = inicio == null || ahora < inicio ||
            (arranque >= 0 && arranqueInicio >= 0 && arranque != arranqueInicio)
        if (otroArranque) return duracionMs to ahora
        val quedan = duracionMs - (ahora - inicio!!)
        return quedan.coerceIn(0L, duracionMs) to null
    }

    /** Milisegundos a segundos, redondeando hacia arriba (1 ms -> 1 s). */
    fun segundos(ms: Long): Long = if (ms > 0) (ms + 999) / 1000 else 0
}
