package com.hunter.btc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Lo que hay que hacer con una clave privada antes de que salga de la app.
 *
 * Está aparte porque son varias pantallas las que tocan claves —el baúl, el
 * debug, la exportación— y cada una lo resolvía a su manera o no lo resolvía.
 * La exportación tachaba las claves; el botón "Copy" del debug copiaba el
 * mismo log sin tachar.
 */
object Secretos {

    /**
     * El texto con las claves privadas tachadas: hex de 64, WIF y xprv.
     *
     * Para lo que va a salir de la app —portapapeles, ficheros compartidos—
     * y puede llevar una clave sin querer: un log, un informe.
     */
    fun tachar(text: String): String =
        text.replace(Regex("""\b[0-9a-fA-F]{64}\b"""), "[hex-hidden]")
            .replace(Regex("""\b[5KL][1-9A-HJ-NP-Za-km-z]{50,51}\b"""), "[wif-hidden]")
            .replace(Regex("""\b(xprv|yprv|zprv|tprv)[1-9A-HJ-NP-Za-km-z]{50,}"""), "[xprv-hidden]")

    /** Lo que tarda en borrarse del portapapeles una clave copiada. */
    private const val BORRAR_MS = 60_000L

    /**
     * Copia una clave privada al portapapeles, y la quita a los 60 segundos.
     *
     * Antes iba como texto normal. Dos consecuencias:
     *
     *   - Los teclados con historial de portapapeles (Gboard, SwiftKey…) la
     *     guardaban, y ahí se queda mucho después de pegarla.
     *   - Android 13+ enseña lo copiado en una vista previa en pantalla.
     *
     * La marca IS_SENSITIVE es la que esos teclados y Android miran para no
     * guardar ni enseñar el contenido. Se pone como cadena y no con la
     * constante de ClipDescription porque la constante es de API 33 y el valor
     * es el mismo: así también la ven los teclados en móviles más viejos.
     *
     * Y a los 60 segundos se borra, si lo que hay sigue siendo la clave. Con la
     * app en segundo plano Android no deja LEER el portapapeles —devuelve
     * null—: entonces se borra igual, porque no se puede saber si sigue ahí y
     * dejar una clave privada es peor que borrar algo que se copió después.
     */
    fun copiarClave(ctx: Context, etiqueta: String, secreto: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(etiqueta, secreto)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val actual = try {
                    cm.primaryClip?.getItemAt(0)?.text?.toString()
                } catch (e: Exception) { null }
                if (actual == null || actual == secreto) {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                    else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (e: Exception) {}
        }, BORRAR_MS)
    }
}
