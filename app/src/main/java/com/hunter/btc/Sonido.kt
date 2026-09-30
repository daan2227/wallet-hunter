package com.hunter.btc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Sonido de "moneda encontrada", sintetizado en el momento (sin fichero de
 * audio: nada de assets ni de licencias de terceros).
 *
 * Es el clásico ding de dos notas ascendentes: una corta (B5) y otra más aguda
 * y larga (E6) que se apaga sola. Ondas senoidales con envolvente de caída
 * exponencial y una micro-rampa de entrada para que no chasquee.
 */
object Sonido {

    private const val SR = 44100

    /** Reproduce el ding. No bloquea: monta el PCM y suena en otro hilo. */
    fun moneda(ctx: Context) {
        Thread {
            try {
                val pcm = construir()
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                at.write(pcm, 0, pcm.size)
                at.play()
                // Static: suena una vez. Se espera a que acabe y se libera.
                Thread.sleep((pcm.size * 1000L / SR) + 120)
                try { at.stop() } catch (e: Throwable) {}
                at.release()
            } catch (e: Throwable) {}
        }.start()
    }

    private fun construir(): ShortArray {
        val n1 = (SR * 0.075).toInt()   // primera nota, corta
        val n2 = (SR * 0.50).toInt()    // segunda nota, con caída
        val out = ShortArray(n1 + n2)
        val f1 = 987.77   // B5
        val f2 = 1318.51  // E6
        val amp = 0.38 * Short.MAX_VALUE
        val ramp = (SR * 0.003).toInt()   // 3 ms de entrada, para no chasquear
        for (i in 0 until n1) {
            val env = if (i < ramp) i.toDouble() / ramp else 1.0
            out[i] = (Math.sin(2 * Math.PI * f1 * i / SR) * env * amp).toInt().toShort()
        }
        for (i in 0 until n2) {
            val env = Math.exp(-3.2 * i / n2) * (if (i < ramp) i.toDouble() / ramp else 1.0)
            out[n1 + i] = (Math.sin(2 * Math.PI * f2 * i / SR) * env * amp).toInt().toShort()
        }
        return out
    }
}
