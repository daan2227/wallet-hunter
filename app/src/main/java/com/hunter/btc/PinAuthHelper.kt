package com.hunter.btc

import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hunter.btc.AppTheme.AMBER
import com.hunter.btc.AppTheme.BG_CARD
import com.hunter.btc.AppTheme.BG_PANEL
import com.hunter.btc.AppTheme.BORDER_C
import com.hunter.btc.AppTheme.TXT_MUTED
import com.hunter.btc.AppTheme.TXT_PRI
import com.hunter.btc.AppTheme.TXT_SEC

object PinAuthHelper {

    // Timestamp de la última autenticación exitosa
    var lastAuthTime: Long = 0L
    // Tiempo máximo sin re-autenticar (30 segundos en background)
    private const val AUTH_TIMEOUT_MS = 30_000L

    fun isSessionValid(): Boolean {
        return System.currentTimeMillis() - lastAuthTime < AUTH_TIMEOUT_MS
    }

    fun markAuthenticated() {
        lastAuthTime = System.currentTimeMillis()
    }

    private val RED get() = AppTheme.RED

    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/**
     * @param autoBiometric lanza la huella nada más abrir. Para desbloquear la
     *   wallet tiene sentido; para acciones menores —exportar un resumen sin
     *   claves— es desproporcionado, así que el teclado sale directo y la huella
     *   queda disponible en la tecla ◉ para quien la prefiera.
     */
    fun show(activity: android.app.Activity, autoBiometric: Boolean = true,
             onResult: (Boolean) -> Unit) {
        if (!WalletManager.hasPin(activity)) { onResult(true); return }
        showPinScreen(activity, isSetup = false, autoBiometric = autoBiometric) { ok ->
            if (ok) markAuthenticated()
            onResult(ok)
        }
    }



    private fun showPinScreen(
        activity: android.app.Activity,
        isSetup: Boolean,
        autoBiometric: Boolean = true,
        onResult: (Boolean) -> Unit
    ) {
        // Esta pantalla tenía su propia paleta verdosa (#0e0f0e, #1a1c19,
        // #7a8a70…), heredada de otro diseño. Ahora sale del tema, como todo.
        val GOLD  = AppTheme.ACCENT
        val BG    = AppTheme.BG_DEEP
        val BG2   = AppTheme.BG_KEY
        val BGKP  = AppTheme.BG_DEEP
        val TXT   = AppTheme.TXT_PRI
        val MUTED = AppTheme.TXT_SEC
        val SUBL  = AppTheme.TXT_SEC
        val RED   = AppTheme.RED
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        fun spToPx(sp: Float) = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, sp, activity.resources.displayMetrics).toInt()

        // Root fullscreen
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }



        // ── Body ──────────────────────────────────────────────────────────
        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(24), 0, dp(24), dp(16))
        }

        body.addView(android.widget.ImageView(activity).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(GOLD)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(17).toFloat()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(56))
        })

        val tvLabel = android.widget.TextView(activity).apply {
            text = if (isSetup) "Elige tu clave" else "Introduce tu clave"
            textSize = 21f; setTextColor(TXT)
            typeface = AppTheme.title(context)
            letterSpacing = -0.02f; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(26) }
        }
        body.addView(tvLabel)
        body.addView(android.widget.TextView(activity).apply {
            text = "Seis dígitos"
            textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })

        // Dots row
        val dotsRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(38); bottomMargin = dp(38) }
        }
        // Eran cuadrados redondeados de 52dp: del tamaño de una tecla y con su
        // misma forma, así que la fila de progreso parecía otra fila de
        // botones. Un punto tiene que parecer un punto.
        val dotSz = dp(14)
        fun makeDotBg(filled: Boolean, error: Boolean = false) =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                when {
                    error  -> setColor(RED)
                    filled -> setColor(AppTheme.TXT_PRI)
                    else   -> setColor(AppTheme.BG_ELEV)
                }
            }
        val dots = Array(6) { i ->
            android.view.View(activity).apply {
                background = makeDotBg(false)
                layoutParams = android.widget.LinearLayout.LayoutParams(dotSz, dotSz).apply {
                    if (i < 5) marginEnd = dp(14)
                }
            }
        }
        dots.forEach { dotsRow.addView(it) }
        body.addView(dotsRow)

        val tvHint = android.widget.TextView(activity).apply {
            text = if (isSetup)
                "Apúntalo donde no se te pierda:\nsin él no puedes abrir la cartera."
            else if (autoBiometric) "Introduce el código o usa la huella"
            else "Introduce el código"
            textSize = AppTheme.SP_BODY; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.35f)
            gravity = android.view.Gravity.CENTER
        }
        body.addView(tvHint)
        root.addView(body)

        // ── Keypad ────────────────────────────────────────────────────────
        val keypadWrap = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(BGKP)
            setPadding(dp(10), dp(14), dp(10), dp(32))
        }
        val keypadContainer = android.widget.LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val keypad = android.widget.GridLayout(activity).apply {
            columnCount = 3; rowCount = 4
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val pin = StringBuilder()
        var firstPin = ""
        var dlg: android.app.Dialog? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        fun updateDots(error: Boolean = false) {
            dots.forEachIndexed { i, d ->
                d.background = makeDotBg(filled = i < pin.length, error = error)
            }
        }

        fun shakeAndClear() {
            updateDots(error = true)
            handler.postDelayed({ pin.clear(); updateDots() }, 600)
        }

        fun handleDigit(k: String) {
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return
            handler.postDelayed({
                if (isSetup) {
                    if (firstPin.isEmpty()) {
                        firstPin = pin.toString(); pin.clear(); updateDots()
                        tvLabel.text = "Repítelo"
                        tvHint.text = "Escribe el mismo código otra vez"
                        tvHint.setTextColor(MUTED)
                    } else if (firstPin == pin.toString()) {
                        WalletManager.savePin(activity, pin.toString())
                        dlg?.dismiss(); onResult(true)
                    } else {
                        firstPin = ""; shakeAndClear()
                        tvLabel.text = "Try again"
                        tvHint.text = "No coinciden"
                        tvHint.setTextColor(RED)
                    }
                } else {
                    if (WalletManager.checkPin(activity, pin.toString())) {
                        dlg?.dismiss(); onResult(true)
                    } else {
                        shakeAndClear()
                        tvHint.text = "Código incorrecto. Inténtalo otra vez."
                        tvHint.setTextColor(RED)
                    }
                }
            }, 150)
        }

        fun launchBiometric() {
            if (activity !is androidx.fragment.app.FragmentActivity) return
            val bm = androidx.biometric.BiometricManager.from(activity)
            val canBio = bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
            if (!canBio) return
            val prompt = androidx.biometric.BiometricPrompt(
                activity as androidx.fragment.app.FragmentActivity,
                androidx.core.content.ContextCompat.getMainExecutor(activity),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        dlg?.dismiss(); onResult(true)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {}
                    override fun onAuthenticationFailed() {}
                }
            )
            prompt.authenticate(
                androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Wallet Hunter")
                    .setSubtitle("Use fingerprint to unlock")
                    .setNegativeButtonText("Use PIN")
                    .build()
            )
        }

        // Build keys: (digit, sublabel, type)  type: "num" | "bio" | "del" | "empty"
        data class Key(val digit: String, val sub: String, val type: String)
        val keys = listOf(
            Key("1","","num"), Key("2","ABC","num"), Key("3","DEF","num"),
            Key("4","GHI","num"), Key("5","JKL","num"), Key("6","MNO","num"),
            Key("7","PQRS","num"), Key("8","TUV","num"), Key("9","WXYZ","num"),
            Key("","","bio"), Key("0","","num"), Key("","","del")
        )

        keys.forEach { key ->
            val sz = dp(72)
            val cell = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sz; height = sz
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                if (key.type != "empty") {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(BG2); cornerRadius = dp(AppTheme.R_KEY).toFloat()
                    }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        when (key.type) {
                            "del" -> { if (pin.isNotEmpty()) { pin.deleteCharAt(pin.length-1); updateDots() } }
                            "bio" -> launchBiometric()
                            "num" -> handleDigit(key.digit)
                        }
                        // Press feedback
                        val pressedBg = android.graphics.drawable.GradientDrawable().apply {
                            setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_KEY).toFloat()
                        }
                        val normalBg = android.graphics.drawable.GradientDrawable().apply {
                            setColor(BG2); cornerRadius = dp(AppTheme.R_KEY).toFloat()
                        }
                        background = pressedBg
                        handler.postDelayed({ background = normalBg }, 120)
                    }
                }
            }

            when (key.type) {
                "num" -> {
                    cell.addView(android.widget.TextView(activity).apply {
                        text = key.digit; textSize = 24f; setTextColor(TXT)
                        typeface = AppTheme.body(context)
                        gravity = android.view.Gravity.CENTER
                    })
                    // Las subletras ABC/DEF son de un teclado telefónico: aquí
                    // no se marca ningún número, se teclea una clave. Fuera.
                }
                "bio" -> {
                    // Era el carácter ◉, que cada fuente dibuja a su manera y en
                    // algunas ni existe. Ahora es el icono de huella.
                    cell.addView(android.widget.ImageView(activity).apply {
                        setImageResource(R.drawable.ic_finger)
                        setColorFilter(GOLD)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            dp(26), dp(26))
                    })
                }
                "del" -> {
                    cell.addView(android.widget.ImageView(activity).apply {
                        setImageResource(R.drawable.ic_back)
                        setColorFilter(SUBL)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            dp(24), dp(24))
                    })
                }
            }
            keypad.addView(cell)
        }

        keypadContainer.addView(keypad)
        keypadWrap.addView(keypadContainer)
        keypadWrap.addView(android.widget.TextView(activity).apply {
            text = "La clave no sale de este dispositivo"
            textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        })
        root.addView(keypadWrap)

        // Dialog fullscreen
        dlg = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg!!.setContentView(root)
        dlg!!.setCancelable(!isSetup)
        if (!isSetup) dlg!!.setOnCancelListener { onResult(false) }
        dlg!!.show()

        // Auto-lanzar biométrico al abrir si no es setup y quien llama lo quiere
        if (!isSetup && autoBiometric) handler.postDelayed({ launchBiometric() }, 300)
    }

}
