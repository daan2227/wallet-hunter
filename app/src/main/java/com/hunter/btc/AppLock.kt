package com.hunter.btc

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle

/**
 * Cuándo se vuelve a pedir el PIN.
 *
 * Antes lo decidían tres relojes distintos que no se hablaban entre sí:
 *
 *   - PinAuthHelper daba la sesión por caducada a los 30 s de autenticarse.
 *   - MainActivity.onResume la pedía si habían pasado 15 s desde su onPause.
 *   - WalletActivity ponía isLocked = true en CADA onPause, sin condición.
 *
 * Los tres medían lo mismo mal. onPause no significa "el usuario ha dejado la
 * app": salta también cuando otra pantalla NUESTRA se pone delante. Abrir la
 * cartera pausa el hunter; volver del baúl pausa la cartera; el selector de
 * ficheros, el diálogo de compartir y la propia huella pausan lo que haya
 * debajo. Con eso, cambiar de pestaña y volver veinte segundos después pedía
 * el PIN, y entrar y salir del baúl lo pedía dos veces. La protección no
 * mejoraba —quien tiene el móvil en la mano ya está dentro— y el PIN acababa
 * tecleándose de memoria, que es como se aprende de espalda ajena.
 *
 * Lo que sí significa "el usuario ha dejado la app" es que NINGUNA actividad
 * nuestra esté arrancada. Al ir de una pantalla a otra, Android arranca la
 * nueva ANTES de parar la vieja, así que la cuenta pasa por 2 y nunca llega a
 * cero: cambiar de pantalla no cuenta, y no hay que enumerar los casos.
 *
 * Se bloquea entonces en los tres momentos que pidió el usuario:
 *
 *   - Al ARRANCAR: el proceso empieza con lastAuthTime a cero.
 *   - Al APAGARSE LA PANTALLA: inmediato, por ACTION_SCREEN_OFF. Es el caso
 *     que de verdad importa —el móvil encima de la mesa— y no espera margen.
 *   - Al SALIR de la app más de [GRACIA_MS].
 *
 * El margen existe por el selector de ficheros y el diálogo de compartir, que
 * son apps ajenas: exportar una copia de seguridad sale de la nuestra y vuelve
 * a los pocos segundos, y pedir el PIN al volver de elegir carpeta sería
 * repetir el mismo error con otro nombre. Si la pantalla se apaga mientras
 * tanto, el receptor bloquea igual, así que el margen no abre ningún hueco
 * real: cubre el ir y volver, no el dejar el móvil.
 */
object AppLock {

    /**
     * Cuánto se puede estar fuera de la app sin perder la sesión.
     *
     * Treinta segundos es tiempo de sobra para elegir una carpeta o compartir
     * un fichero, y muy poco para dejar el móvil a nadie. Quien quiera menos
     * tiene el botón de apagar pantalla, que bloquea al instante.
     */
    private const val GRACIA_MS = 30_000L

    private var registrado = false

    /** Actividades nuestras arrancadas. Cero es "la app no se está viendo". */
    private var vivas = 0

    /** Cuándo se fue la última actividad. 0 si nunca. */
    private var fondoDesde = 0L

    /**
     * Engancha los avisos del sistema. Idempotente: la llaman todas las
     * pantallas en su onCreate porque cualquiera de ellas puede ser la primera
     * si Android revive el proceso por una notificación.
     */
    fun init(act: Activity) {
        if (registrado) return
        registrado = true
        val app = act.application

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                val volviendo = vivas == 0
                vivas++
                // Un recreate() —el cambio de tema— también pasa por cero, y
                // vuelve en milisegundos. Por eso la condición es el tiempo
                // fuera y no el hecho de haber salido: así no hay que
                // distinguir un recreate de una salida de verdad.
                if (volviendo && fondoDesde > 0L &&
                    System.currentTimeMillis() - fondoDesde > GRACIA_MS) bloquear()
            }

            override fun onActivityStopped(a: Activity) {
                vivas--
                if (vivas <= 0) {
                    vivas = 0
                    fondoDesde = System.currentTimeMillis()
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        // ACTION_SCREEN_OFF no se puede declarar en el manifiesto desde
        // Android 8: hay que registrarlo en marcha. NOT_EXPORTED porque sólo
        // lo manda el sistema; targetSdk 34 exige decirlo.
        androidx.core.content.ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { bloquear() }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Cierra la sesión: la siguiente pantalla que la mire pedirá el PIN.
     *
     * Es lo único que toca [PinAuthHelper.lastAuthTime] aparte de
     * autenticarse, así que no hay dos ideas de "sesión abierta" que puedan
     * discrepar.
     */
    fun bloquear() {
        PinAuthHelper.lastAuthTime = 0L
    }
}
