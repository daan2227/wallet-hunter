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
    // Sesión abierta = PIN verificado Y la clave de las semillas en memoria. Con una
    // sola de las dos, una pantalla creía que estaba desbloqueada y luego fallaba al guardar.
    fun isSessionValid(): Boolean = lastAuthTime > 0L && WalletManager.claveSemillaLista()

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
     * El código es ahora un TEXTO de 8 a 24 caracteres con letras, números y símbolos (ver [PoliticaPin]),
     * no seis dígitos en un teclado numérico. Al elegirlo se comprueba la política y se pide dos veces; al
     * desbloquear se acepta cualquier longitud, para que quien tenía uno de seis dígitos pueda entrar, y se le
     * ofrece cambiarlo.
     *
     * Sigue siendo UNA sola pantalla para toda la app (cartera, arranque, perfil, confirmaciones).
     * Comprobar o fijar el código hace PBKDF2 (600 000 vueltas en los nuevos): va en otro hilo, no en el de
     * la interfaz.
     *
     * Tras 5 fallos seguidos hay que esperar (WalletManager.checkPin): la pantalla lo dice con la cuenta atrás
     * y apaga el campo mientras tanto.
     */
    fun pantalla(
        activity: android.app.Activity,
        isSetup: Boolean,
        autoBiometric: Boolean = true,
        permitirHuella: Boolean = false,
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
        // La huella no se ofrece mientras esté vetada por demasiados intentos, ni salvo que quien llama la
        // pida (permitirHuella): la huella no puede dar el código, el código es lo que abre las semillas, y una
        // confirmación que aceptara la huella no exigiría saberlo.
        val hayHuella = permitirHuella && !isSetup && activity is androidx.fragment.app.FragmentActivity &&
            !WalletManager.bioBloqueada(activity) &&
            WalletManager.claveSemillaLista() &&
            bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = AppTheme.fondoApp()
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(48), dp(28), dp(28))
        }
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
        val pistaElegir = "${PoliticaPin.MIN} to ${PoliticaPin.MAX} characters with letters, numbers and symbols.\n" +
            "Write it down somewhere safe: without it the wallet cannot be opened."
        val pistaNormal = when {
            isSetup   -> pistaElegir
            hayHuella -> "Type your code, or use your fingerprint"
            else      -> "Type your code"
        }
        val tvPista = TextView(activity).apply {
            text = pistaNormal
            textSize = AppTheme.SP_BODY; setTextColor(MUTED)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.3f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), 0)
            minLines = 2
        }
        root.addView(tvPista)

        // ── Campo ─────────────────────────────────────────────────────────
        val campo = android.widget.EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(if (isSetup) PoliticaPin.MAX else 64))
            hint = if (isSetup) "${PoliticaPin.MIN}-${PoliticaPin.MAX} characters" else "Code"
            setHintTextColor(AppTheme.TXT_MUTED)
            setTextColor(TXT); textSize = 18f
            typeface = AppTheme.body(context)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                         android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(26) }
        }
        root.addView(campo)

        val fila = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val tvFuerza = TextView(activity).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(MUTED); typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        var visible = false
        val tvVer = TextView(activity).apply {
            text = "Show"
            textSize = AppTheme.SP_CAPTION; setTextColor(ACCENT); typeface = AppTheme.medium(context)
            setPadding(dp(12), dp(8), dp(4), dp(8)); isClickable = true; isFocusable = true
        }
        fila.addView(tvFuerza); fila.addView(tvVer)
        root.addView(fila)
        tvVer.setOnClickListener {
            visible = !visible
            val fin = campo.text.length
            campo.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (visible) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                 else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            campo.setSelection(fin)
            tvVer.text = if (visible) "Hide" else "Show"
        }
        if (isSetup) campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString() ?: ""
                if (t.isEmpty()) { tvFuerza.text = ""; return }
                val n = FuerzaContrasena.evaluar(t).nivel
                tvFuerza.text = "Strength: ${n.texto}"
                tvFuerza.setTextColor(when (n) {
                    FuerzaContrasena.Nivel.DEBIL -> RED
                    FuerzaContrasena.Nivel.JUSTA -> AppTheme.WARN
                    else -> ACCENT })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val boton = TextView(activity).apply {
            text = if (isSetup) "Continue" else "Unlock"
            textSize = AppTheme.SP_BODY + 1f; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) }
        }
        root.addView(boton)
        val botonHuella = TextView(activity).apply {
            text = "Use fingerprint"
            textSize = AppTheme.SP_BODY; setTextColor(ACCENT); typeface = AppTheme.medium(context)
            gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            visibility = if (hayHuella) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) }
        }
        root.addView(botonHuella)
        root.addView(TextView(activity).apply {
            text = "The code never leaves this phone"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.body(context)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        })

        // ── Estado ────────────────────────────────────────────────────────
        var primero = ""
        var dlg: android.app.Dialog? = null
        var ocupado = false

        fun sacudir() {
            campo.animate().cancel()
            campo.translationX = 0f
            val a = android.animation.ObjectAnimator.ofFloat(campo, "translationX",
                0f, dp(14).toFloat(), -dp(12).toFloat(), dp(9).toFloat(), -dp(6).toFloat(), dp(3).toFloat(), 0f)
            a.duration = 380; a.start()
            campo.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        fun entrada(activa: Boolean) {
            campo.isEnabled = activa; boton.isEnabled = activa
            boton.alpha = if (activa) 1f else 0.35f
            campo.alpha = if (activa) 1f else 0.5f
        }
        fun error(msg: String) {
            tvPista.setTextColor(RED); tvPista.text = msg; sacudir()
        }

        // Cuenta atrás mientras dura la espera por fallos.
        val cuenta = object : Runnable {
            override fun run() {
                val s = WalletManager.esperaPin(activity)
                if (s > 0) {
                    entrada(false)
                    tvPista.setTextColor(RED)
                    tvPista.text = "Too many attempts.\nTry again in " +
                        (if (s >= 60) "${(s + 59) / 60} min" else "$s s") + "."
                    handler.postDelayed(this, 1000)
                } else if (!ocupado) {
                    entrada(true)
                    tvPista.setTextColor(MUTED)
                    tvPista.text = if (isSetup && primero.isNotEmpty()) "To make sure it is the one you want" else pistaNormal
                }
            }
        }

        fun cerrar(ok: Boolean) {
            handler.removeCallbacks(cuenta)
            try {
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(campo.windowToken, 0)
            } catch (e: Exception) {}
            campo.setText("")
            dlg?.dismiss(); onResult(ok)
            // Quien aún tiene el código de seis dígitos entra, pero se le avisa (como mucho una vez al día).
            if (ok && !isSetup && WalletManager.pinAntiguo(activity)) avisarCodigoAntiguo(activity)
        }

        fun intentar() {
            if (ocupado) return
            val v = campo.text.toString()
            if (isSetup) {
                if (primero.isEmpty()) {
                    val e = PoliticaPin.error(v)
                    if (e != null) { error(e); return }
                    primero = v; campo.setText("")
                    tvTitulo.text = "Type it again"
                    tvFuerza.text = ""
                    tvPista.setTextColor(MUTED); tvPista.text = "To make sure it is the one you want"
                    boton.text = "Save"
                    return
                }
                if (primero != v) {
                    primero = ""; campo.setText("")
                    tvTitulo.text = "Pick a code"; boton.text = "Continue"
                    error("They did not match. Start again.\n$pistaElegir")
                    return
                }
                ocupado = true; entrada(false)
                val elegido = v
                Thread {
                    val guardado = WalletManager.savePin(activity, elegido)
                    val sinClave = !guardado && WalletManager.hasPin(activity) && !WalletManager.claveSemillaLista()
                    handler.post {
                        ocupado = false; entrada(true)
                        when {
                            guardado -> cerrar(true)
                            sinClave -> {
                                // La app se bloqueó (pantalla apagada) entre el código viejo y el nuevo: sin la clave
                                // de la sesión no se pueden volver a cerrar las semillas. Se sale, sin cambios.
                                android.widget.Toast.makeText(activity,
                                    "The app locked while changing the code. Nothing changed: start again.",
                                    android.widget.Toast.LENGTH_LONG).show()
                                cerrar(false)
                            }
                            else -> {
                                primero = ""; campo.setText("")
                                tvTitulo.text = "Pick a code"; boton.text = "Continue"
                                error("The code could not be saved. Try again.")
                            }
                        }
                    }
                }.start()
                return
            }
            if (v.isEmpty()) return
            ocupado = true; entrada(false)
            Thread {
                val ok = WalletManager.checkPin(activity, v)
                handler.post {
                    ocupado = false
                    if (ok) { cerrar(true); return@post }
                    campo.setText("")
                    entrada(WalletManager.esperaPin(activity) <= 0)
                    error(WalletManager.avisoPinFallido(activity))
                    if (WalletManager.esperaPin(activity) > 0) handler.postDelayed(cuenta, 450)
                }
            }.start()
        }
        boton.setOnClickListener { intentar() }
        campo.setOnEditorActionListener { _, accion, _ ->
            if (accion == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { intentar(); true } else false
        }

        fun huella() {
            // Defensa en vivo: aunque el botón no debería existir, no se lanza la huella si hay espera en curso
            // o sigue vetada.
            if (!hayHuella || WalletManager.esperaPin(activity) > 0 ||
                WalletManager.bioBloqueada(activity)) return
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
        botonHuella.setOnClickListener { huella() }

        // ── Ventana ───────────────────────────────────────────────────────
        // Con el teclado del sistema delante: el contenido va en un ScrollView que se redimensiona.
        val tema = if (AppTheme.isDark) android.R.style.Theme_Material_NoActionBar
                   else android.R.style.Theme_Material_Light_NoActionBar
        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            addView(root, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dlg = android.app.Dialog(activity, tema).apply {
            setContentView(scroll)
            // Fijar el PRIMER código no se puede saltar; cambiarlo sí (atrás = sin cambios).
            val cancelable = !isSetup || WalletManager.hasPin(activity)
            setCancelable(cancelable)
            if (cancelable) setOnCancelListener { handler.removeCallbacks(cuenta); onResult(false) }
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
                setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                          android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                                 android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                statusBarColor = BG
                navigationBarColor = BG
                if (!AppTheme.isDark) {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
        }
        PantallaSegura.aplicarDialogo(dlg, activity)   // la entrada del código no sale en capturas
        dlg!!.show()
        campo.requestFocus()

        if (WalletManager.esperaPin(activity) > 0) cuenta.run()
        else if (hayHuella && autoBiometric) handler.postDelayed({ huella() }, 300)
    }

    /**
     * El código es del formato antiguo (seis dígitos): se entra, pero se ofrece cambiarlo. Como mucho una vez
     * al día, para que no sea una molestia en cada desbloqueo.
     */
    private fun avisarCodigoAntiguo(activity: android.app.Activity) {
        val p = activity.getSharedPreferences("hunter", android.content.Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        if (ahora - p.getLong("aviso_codigo_antiguo", 0L) < 24L * 3600 * 1000) return
        p.edit().putLong("aviso_codigo_antiguo", ahora).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            android.app.AlertDialog.Builder(activity)
                .setTitle("Make your code stronger")
                .setMessage("Your code is the old six-digit kind, which can be guessed quickly by anyone who gets at " +
                    "the files on this phone. Pick one of ${PoliticaPin.MIN} to ${PoliticaPin.MAX} characters with " +
                    "letters, numbers and symbols.")
                .setPositiveButton("Change it now") { _, _ ->
                    pantalla(activity, isSetup = true, autoBiometric = false) { }
                }
                .setNegativeButton("Later", null)
                .show()
        }, 400)
    }
}
