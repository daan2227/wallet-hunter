package com.hunter.btc

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Enseña unos bytes (una PSBT, una transacción) como QR animado, para que los lea un aparato sin
 * conexión: Keystone, SeedSigner, Passport, Jade (UR) o Coldcard Q (BBQr). Sparrow y Nunchuk leen
 * los dos. Un botón cambia de formato; el QR sigue dando vueltas hasta que se cierra.
 */
object QrAnimado {
    private const val MS_POR_FOTOGRAMA = 280L

    fun qr(texto: String, lado: Int): Bitmap {
        val pistas = mapOf(com.google.zxing.EncodeHintType.MARGIN to 2,
                           com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(texto, com.google.zxing.BarcodeFormat.QR_CODE, lado, lado, pistas)
        val w = m.width; val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565)
    }

    /** [tipoUr]: "crypto-psbt" para una PSBT, "bytes" para lo demás; [tipoBbqr]: 'P' PSBT, 'T' transacción. */
    fun mostrar(act: Activity, datos: ByteArray, titulo: String, tipoUr: String = "crypto-psbt", tipoBbqr: Char = 'P') {
        val lado = (act.resources.displayMetrics.widthPixels * 0.8f).toInt()
        val img = ImageView(act).apply { setBackgroundColor(Color.WHITE); adjustViewBounds = true }
        val info = TextView(act).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); gravity = Gravity.CENTER
            setPadding(0, (8 * act.resources.displayMetrics.density).toInt(), 0, 0)
        }
        val caja = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val p = (16 * act.resources.displayMetrics.density).toInt(); setPadding(p, p, p, 0)
            addView(img, LinearLayout.LayoutParams(lado, lado)); addView(info)
        }
        // En mayúsculas: el QR va en modo alfanumérico, más denso (UR lo permite expresamente).
        var formatoUr = true
        var ur = Ur.Codificador(tipoUr, Ur.cborBytes(datos), maxFragmento = 180)
        var bb = Bbqr.partir(datos, tipoBbqr, 500)
        var i = 0
        val h = Handler(Looper.getMainLooper())
        var vivo = true
        val paso = object : Runnable {
            override fun run() {
                if (!vivo) return
                val texto = if (formatoUr) ur.siguiente().uppercase() else bb[i++ % bb.size]
                try { img.setImageBitmap(qr(texto, lado)) } catch (e: Exception) { info.text = "Could not draw the QR: ${e.message}" ; return }
                info.text = if (formatoUr) "UR (Keystone, SeedSigner, Passport, Jade, Sparrow) · ${ur.partes} parts"
                            else "BBQr (Coldcard Q, Sparrow, Nunchuk) · ${bb.size} parts"
                val unico = if (formatoUr) ur.unaSola() else bb.size == 1
                if (!unico) h.postDelayed(this, MS_POR_FOTOGRAMA)
            }
        }
        val dlg = AlertDialog.Builder(act).setTitle(titulo).setView(caja)
            .setPositiveButton("Close", null)
            .setNeutralButton("Switch to BBQr", null)
            .create()
        dlg.setOnDismissListener { vivo = false; h.removeCallbacksAndMessages(null) }
        dlg.setOnShowListener {
            val b = dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
            b.setOnClickListener {
                formatoUr = !formatoUr; i = 0
                ur = Ur.Codificador(tipoUr, Ur.cborBytes(datos), maxFragmento = 180); bb = Bbqr.partir(datos, tipoBbqr, 500)
                b.text = if (formatoUr) "Switch to BBQr" else "Switch to UR"
                h.removeCallbacksAndMessages(null); h.post(paso)
            }
            h.post(paso)
        }
        // Ni capturas: una PSBT sin firmar no es secreta, pero dice qué monedas tienes.
        dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dlg.show()
    }
}
