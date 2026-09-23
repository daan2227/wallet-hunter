package com.hunter.btc

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object PinAuthHelper {

    /**
     * Cuándo se autenticó por última vez, o 0 si la sesión está cerrada.
     *
     * Lo pone a cero [AppLock] cuando la app sale de primer plano o se apaga
     * la pantalla. No es un cero mágico: es "no hay sesión".
     */
    var lastAuthTime: Long = 0L

    /**
     * ¿Hay sesión abierta?
     *
     * Ya NO caduca sola a los treinta segundos. Ese reloj era lo que hacía que
     * el PIN saliera dos veces seguidas por mirar el baúl y volver: nadie
     * había salido de la app, simplemente había pasado medio minuto. Ahora la
     * sesión dura lo que dure el uso de la app, y la cierra [AppLock] en los
     * momentos que de verdad lo son —pantalla apagada, app en segundo plano,
     * proceso nuevo—, que es donde el PIN protege algo.
     */
    fun isSessionValid(): Boolean = lastAuthTime > 0L

    fun markAuthenticated() {
        lastAuthTime = System.currentTimeMillis()
    }

    /**
     * @param autoBiometric lanza la huella nada más abrir. Para desbloquear la
     *   wallet tiene sentido; para acciones menores —exportar un resumen sin
     *   claves— es desproporcionado, así que el teclado sale directo y la huella
     *   queda disponible en su tecla para quien la prefiera.
     */
    fun show(activity: android.app.Activity, autoBiometric: Boolean = true,
             onResult: (Boolean) -> Unit) {
        if (!WalletManager.hasPin(activity)) { onResult(true); return }
        pantalla(activity, isSetup = false, autoBiometric = autoBiometric) { ok ->
            if (ok) markAuthenticated()
            onResult(ok)
        }
    }

    /**
     * La pantalla del código, para pedirlo o para elegirlo (isSetup).
     *
     * Había TRES copias de esta pantalla —esta, la del arranque en
     * WelcomeActivity y la de la cartera en WalletActivity—, cada una con su
     * aspecto: casillas grandes en una, puntos en otra, una hoja flotante en
     * la tercera. Ahora es una sola.
     *
     * Lo que cambia respecto a las de antes:
     *
     *   - Los iconos de la huella y de borrar iban pegados a la izquierda de
     *     su tecla: Ui.icon pone gravedad sólo vertical, y la tecla no
     *     centraba en horizontal.
     *   - Las seis casillas de 52 dp parecían campos de texto a rellenar. Son
     *     puntos, que es lo que son: un contador.
     *   - La tecla de la huella sólo sale si el móvil tiene huella; si no,
     *     era una tecla que no hacía nada.
     *   - Tras 5 fallos seguidos hay que esperar (WalletManager.checkPin). La
     *     pantalla lo dice con la cuenta atrás y apaga el teclado mientras
     *     tanto, en vez de dejar teclear códigos que se rechazan sin mirar.
     *   - En el tema claro la barra de estado salía en negro: el diálogo era
     *     de pantalla completa con fondo negro de serie.
     *   - Vibración corta en cada tecla y sacudida al fallar.
     */
    fun pantalla(
        activity: android.app.Activity,
        isSetup: Boolean,
        autoBiometric: Boolean = true,
        onResult: (Boolean) -> Unit
    ) {
        val ACCENT = AppTheme.ACCENT
        val BG     = AppTheme.BG_DEEP
        val TXT    = AppTheme.TXT_PRI
        val MUTED  = AppTheme.TXT_SEC
        val RED    = AppTheme.RED
        val dm = activity.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        val bm = androidx.biometric.BiometricManager.from(activity)
        val hayHuella = !isSetup && activity is androidx.fragment.app.FragmentActivity &&
            bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun hueco(peso: Float) = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, peso)
        }

        // ── Cabecera ──────────────────────────────────────────────────────
        root.addView(hueco(1f))
        root.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(ACCENT)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        })
        val tvTitulo = TextView(activity).apply {
            text = if (isSetup) "Pick a code" else "Enter your code"
            textSize = 22f; setTextColor(TXT)
            typeface = AppTheme.title(context)
            letterSpacing = -0.02f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) }
        }
        root.addView(tvTitulo)
        val pistaNormal = when {
            isSetup   -> "Six digits. Write it down somewhere safe:\nwithout it the wallet cannot be opened."
            hayHuella -> "Six digits, or use your fingerprint"
            else      -> "Six digits"
        }
        val tvPista = TextView(activity).apply {
            text = pistaNormal
            textSize = AppTheme.SP_BODY; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.3f)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(8), dp(32), 0)
            minLines = 2
        }
        root.addView(tvPista)

        // ── Puntos ────────────────────────────────────────────────────────
        val filaPuntos = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(26) }
        }
        fun fondoPunto(lleno: Boolean, error: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                error -> setColor(RED)
                lleno -> setColor(TXT)
                else  -> { setColor(android.graphics.Color.TRANSPARENT); setStroke(dp(2), AppTheme.TXT_MUTED) }
            }
        }
        val puntos = Array(6) { i ->
            View(activity).apply {
                background = fondoPunto(false, false)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    if (i < 5) marginEnd = dp(18)
                }
            }
        }
        puntos.forEach { filaPuntos.addView(it) }
        root.addView(filaPuntos)
        root.addView(hueco(1.2f))

        // ── Estado ────────────────────────────────────────────────────────
        val pin = StringBuilder()
        var primero = ""
        var dlg: android.app.Dialog? = null
        var ocupado = false
        val teclas = mutableListOf<View>()

        fun pintarPuntos(error: Boolean = false) {
            puntos.forEachIndexed { i, d -> d.background = fondoPunto(i < pin.length, error) }
        }
        fun sacudir() {
            filaPuntos.animate().cancel()
            filaPuntos.translationX = 0f
            val a = android.animation.ObjectAnimator.ofFloat(filaPuntos, "translationX",
                0f, dp(14).toFloat(), -dp(12).toFloat(), dp(9).toFloat(), -dp(6).toFloat(), dp(3).toFloat(), 0f)
            a.duration = 380; a.start()
            filaPuntos.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        fun teclado(activo: Boolean) {
            teclas.forEach { it.isEnabled = activo; it.alpha = if (activo) 1f else 0.35f }
        }

        // Cuenta atrás mientras dura la espera por fallos.
        val cuenta = object : Runnable {
            override fun run() {
                val s = WalletManager.esperaPin(activity)
                if (s > 0) {
                    teclado(false)
                    tvPista.setTextColor(RED)
                    tvPista.text = "Too many attempts.\nTry again in " +
                        (if (s >= 60) "${(s + 59) / 60} min" else "$s s") + "."
                    handler.postDelayed(this, 1000)
                } else {
                    teclado(true)
                    tvPista.setTextColor(MUTED)
                    tvPista.text = pistaNormal
                }
            }
        }

        fun cerrar(ok: Boolean) {
            handler.removeCallbacks(cuenta)
            dlg?.dismiss(); onResult(ok)
        }

        fun digito(k: String) {
            if (ocupado || pin.length >= 6) return
            pin.append(k); pintarPuntos()
            if (pin.length < 6) return
            ocupado = true
            handler.postDelayed({
                ocupado = false
                if (isSetup) {
                    when {
                        primero.isEmpty() -> {
                            primero = pin.toString(); pin.clear(); pintarPuntos()
                            tvTitulo.text = "Type it again"
                            tvPista.setTextColor(MUTED); tvPista.text = "To make sure it is the one you want"
                        }
                        primero == pin.toString() -> {
                            WalletManager.savePin(activity, pin.toString())
                            cerrar(true)
                        }
                        else -> {
                            primero = ""; pintarPuntos(error = true); sacudir()
                            handler.postDelayed({ pin.clear(); pintarPuntos() }, 450)
                            tvTitulo.text = "Pick a code"
                            tvPista.setTextColor(RED); tvPista.text = "They did not match. Start again."
                        }
                    }
                } else if (WalletManager.checkPin(activity, pin.toString())) {
                    cerrar(true)
                } else {
                    pintarPuntos(error = true); sacudir()
                    handler.postDelayed({ pin.clear(); pintarPuntos() }, 450)
                    tvPista.setTextColor(RED)
                    tvPista.text = WalletManager.avisoPinFallido(activity)
                    if (WalletManager.esperaPin(activity) > 0) handler.postDelayed(cuenta, 450)
                }
            }, 120)
        }

        fun huella() {
            if (!hayHuella) return
            androidx.biometric.BiometricPrompt(
                activity as androidx.fragment.app.FragmentActivity,
                androidx.core.content.ContextCompat.getMainExecutor(activity),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        cerrar(true)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {}
                    override fun onAuthenticationFailed() {}
                }
            ).authenticate(
                androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Wallet Hunter")
                    .setSubtitle("Unlock with your fingerprint")
                    .setNegativeButtonText("Use the code")
                    .build()
            )
        }

        // ── Teclado ───────────────────────────────────────────────────────
        // Ancho de tecla según la pantalla: 78 dp como mucho, y menos si no
        // caben tres con sus huecos.
        val sep = dp(14)
        val lado = minOf(dp(78), (dm.widthPixels - dp(48) - 2 * sep) / 3)
        val grid = android.widget.GridLayout(activity).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val orden = listOf("1","2","3","4","5","6","7","8","9", if (hayHuella) "bio" else "", "0", "del")
        orden.forEachIndexed { idx, k ->
            val celda = android.widget.FrameLayout(activity).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = lado; height = lado
                    val col = idx % 3
                    setMargins(if (col == 0) 0 else sep / 2, sep / 2,
                               if (col == 2) 0 else sep / 2, sep / 2)
                }
            }
            if (k.isNotEmpty()) {
                if (k != "del" && k != "bio") celda.background = GradientDrawable().apply {
                    setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_KEY).toFloat()
                }
                celda.foreground = Ui.toque()
                celda.isClickable = true; celda.isFocusable = true
                val centro = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                when (k) {
                    "bio" -> {
                        celda.addView(ImageView(activity).apply {
                            setImageResource(R.drawable.ic_finger); setColorFilter(ACCENT)
                            layoutParams = android.widget.FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
                        })
                        celda.contentDescription = "Use fingerprint"
                        celda.setOnClickListener { huella() }
                    }
                    "del" -> {
                        celda.addView(ImageView(activity).apply {
                            setImageResource(R.drawable.ic_backspace); setColorFilter(MUTED)
                            layoutParams = android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
                        })
                        celda.contentDescription = "Delete"
                        celda.setOnClickListener {
                            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            if (pin.isNotEmpty() && !ocupado) { pin.deleteCharAt(pin.length - 1); pintarPuntos() }
                        }
                        celda.setOnLongClickListener { if (!ocupado) { pin.clear(); pintarPuntos() }; true }
                    }
                    else -> {
                        celda.addView(TextView(activity).apply {
                            text = k; textSize = 26f; setTextColor(TXT)
                            typeface = AppTheme.body(context)
                            layoutParams = centro
                        })
                        celda.setOnClickListener {
                            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            digito(k)
                        }
                    }
                }
                teclas.add(celda)
            }
            grid.addView(celda)
        }
        root.addView(grid)
        root.addView(TextView(activity).apply {
            text = "The code never leaves this phone"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.body(context)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18); bottomMargin = dp(28) }
        })

        // ── Ventana ───────────────────────────────────────────────────────
        // Sin pantalla completa: la barra de estado se queda y se pinta del
        // fondo, con los iconos oscuros en el tema claro.
        val tema = if (AppTheme.isDark) android.R.style.Theme_Material_NoActionBar
                   else android.R.style.Theme_Material_Light_NoActionBar
        dlg = android.app.Dialog(activity, tema).apply {
            setContentView(root)
            setCancelable(!isSetup)
            if (!isSetup) setOnCancelListener { handler.removeCallbacks(cuenta); onResult(false) }
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
                setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                          android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                statusBarColor = BG
                navigationBarColor = BG
                if (!AppTheme.isDark) {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
        }
        dlg!!.show()

        if (WalletManager.esperaPin(activity) > 0) cuenta.run()
        else if (hayHuella && autoBiometric) handler.postDelayed({ huella() }, 300)
    }
}
