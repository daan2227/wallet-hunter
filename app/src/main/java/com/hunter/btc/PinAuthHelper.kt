package com.hunter.btc

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*

/**
 * Diálogo de autenticación PIN reutilizable.
 * Uso:
 *   PinAuthHelper.show(this) { ok ->
 *       if (ok) { /* autenticado */ }
 *   }
 */
object PinAuthHelper {

    private val AMBER = 0xFFFFB300.toInt()
    private val BG    = 0xFF1A1A2E.toInt()
    private val RED   = 0xFFFF4444.toInt()

    fun show(activity: Activity, onResult: (Boolean) -> Unit) {
        if (!WalletManager.hasPin(activity)) {
            // Sin PIN configurado — permitir directamente
            onResult(true)
            return
        }

        val ctx = activity

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(64, 48, 64, 32)
        }

        val tvTitle = TextView(ctx).apply {
            text = "VERIFICAR PIN"
            textSize = 13f
            setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val tvStatus = TextView(ctx).apply {
            text = "Ingresa tu PIN de 6 dígitos"
            textSize = 10f
            setTextColor(Color.LTGRAY)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        // Display de puntos
        val pinDisplay = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        val dots = Array(6) { TextView(ctx).apply {
            text = "○"; textSize = 22f
            setTextColor(Color.DKGRAY)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(12, 0, 12, 0)
        }}
        dots.forEach { pinDisplay.addView(it) }

        val pin = StringBuilder()

        fun updateDots() {
            dots.forEachIndexed { i, dot ->
                dot.text = if (i < pin.length) "●" else "○"
                dot.setTextColor(if (i < pin.length) AMBER else Color.DKGRAY)
            }
        }

        // Teclado numérico
        val keypad = GridLayout(ctx).apply {
            columnCount = 3
            rowCount = 4
            setPadding(0, 0, 0, 8)
        }

        val keys = listOf("1","2","3","4","5","6","7","8","9","←","0","✓")
        keys.forEach { key ->
            val btn = Button(ctx).apply {
                text = key
                textSize = 18f
                typeface = Typeface.create("monospace", Typeface.BOLD)
                setTextColor(if (key == "✓") Color.BLACK else AMBER)
                setBackgroundColor(if (key == "✓") AMBER else 0xFF252540.toInt())
                val size = 96
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size * 3; height = size
                    setMargins(4, 4, 4, 4)
                }
            }
            btn.setOnClickListener {
                when (key) {
                    "←" -> { if (pin.isNotEmpty()) { pin.deleteCharAt(pin.length-1); updateDots() } }
                    "✓" -> {
                        if (pin.length == 6) {
                            if (WalletManager.checkPin(ctx, pin.toString())) {
                                onResult(true)
                                (ctx as? Activity)?.let { /* dialog se cierra solo */ }
                            } else {
                                tvStatus.text = "PIN incorrecto"
                                tvStatus.setTextColor(RED)
                                pin.clear(); updateDots()
                            }
                        }
                    }
                    else -> {
                        if (pin.length < 6) {
                            pin.append(key); updateDots()
                            if (pin.length == 6) {
                                // Auto-verificar al completar 6 dígitos
                                if (WalletManager.checkPin(ctx, pin.toString())) {
                                    onResult(true)
                                } else {
                                    tvStatus.text = "PIN incorrecto"
                                    tvStatus.setTextColor(RED)
                                    pin.clear(); updateDots()
                                }
                            }
                        }
                    }
                }
            }
            keypad.addView(btn)
        }

        root.addView(tvTitle)
        root.addView(tvStatus)
        root.addView(pinDisplay)
        root.addView(keypad)

        val dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .setNegativeButton("Cancelar") { _, _ -> onResult(false) }
            .setCancelable(false)
            .create()

        // Cerrar dialog en éxito
        val originalOnResult = onResult
        // Re-wrap para cerrar el dialog
        keys.forEach { key ->
            // El dialog se cierra automáticamente con el botón Cancelar
            // Para éxito, lo cerramos desde dentro
        }

        dialog.show()

        // Re-conectar botón ✓ con referencia al dialog
        val confirmBtn = keypad.getChildAt(11) as? Button
        confirmBtn?.setOnClickListener {
            if (pin.length == 6) {
                if (WalletManager.checkPin(ctx, pin.toString())) {
                    dialog.dismiss()
                    onResult(true)
                } else {
                    tvStatus.text = "PIN incorrecto"
                    tvStatus.setTextColor(RED)
                    pin.clear(); updateDots()
                }
            }
        }

        // También auto-verificar al completar desde los números
        for (i in 0..9) {
            val numBtn = keypad.getChildAt(if (i == 0) 10 else i - 1) as? Button
            numBtn?.setOnClickListener {
                if (pin.length < 6) {
                    pin.append(i.toString()); updateDots()
                    if (pin.length == 6) {
                        if (WalletManager.checkPin(ctx, pin.toString())) {
                            dialog.dismiss()
                            onResult(true)
                        } else {
                            tvStatus.text = "PIN incorrecto"
                            tvStatus.setTextColor(RED)
                            pin.clear(); updateDots()
                        }
                    }
                }
            }
        }
    }
}
