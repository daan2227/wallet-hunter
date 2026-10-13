package com.hunter.btc

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * Perfil local y privado. Lo ÚNICO que guarda del usuario es un nombre (cifrado
 * con el Keystore y el PIN), y permite cambiar el PIN. Sin cuenta, sin correo, sin red.
 */
class PerfilActivity : androidx.appcompat.app.AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this); AppLock.init(this)

        val scroll = ScrollView(this).apply { background = AppTheme.fondoApp() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root); setContentView(scroll)

        // Cabecera con avatar (propuesta B).
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@PerfilActivity).apply {
                text = "Profile"; textSize = AppTheme.SP_PAGE; setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.display(context); letterSpacing = -0.02f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Ui.avatarMark(this@PerfilActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
        })
        root.addView(TextView(this).apply {
            text = "Local and private. The only thing stored about you is a name, encrypted on " +
                   "this device. No account, no email, no network."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f)
            setPadding(0, dp(8), 0, dp(20))
        })

        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(ctx = this@PerfilActivity)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        }
        fun rotulo(t: String) = TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context); setPadding(0, 0, 0, dp(8))
        }

        // Nombre de usuario.
        val cUser = card()
        cUser.addView(rotulo("Username"))
        val etUser = EditText(this).apply {
            hint = "your name"; setText(WalletManager.getUsername(this@PerfilActivity))
            isSingleLine = true; textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        cUser.addView(etUser)
        cUser.addView(boton("Save name") {
            WalletManager.setUsername(this, etUser.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) })
        // Olvidar: borra el nombre del dispositivo. Nada más que eso: el PIN y
        // las carteras no se tocan desde aquí.
        cUser.addView(TextView(this).apply {
            text = "Forget name"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.RED)
            typeface = AppTheme.medium(context); gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener {
                android.app.AlertDialog.Builder(this@PerfilActivity)
                    .setTitle("Forget your name?")
                    .setMessage("It is deleted from this device. Your wallets and PIN stay as they are.")
                    .setPositiveButton("Forget") { _, _ ->
                        WalletManager.setUsername(this@PerfilActivity, "")
                        etUser.setText("")
                        Toast.makeText(this@PerfilActivity, "Name forgotten", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        root.addView(cUser)

        // Seguridad: cambiar / fijar PIN.
        val cPin = card()
        cPin.addView(rotulo("Security"))
        cPin.addView(TextView(this).apply {
            text = "The PIN opens your wallet seeds. They stay locked until you enter it in each " +
                   "session, and the device's secure hardware protects them as well. Changing the " +
                   "PIN re-locks them with the new code; the keys themselves do not change."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.3f); setPadding(0, 0, 0, dp(12))
        })
        cPin.addView(boton(if (WalletManager.hasPin(this)) "Change PIN" else "Set PIN") { cambiarPin() })
        root.addView(cPin)
    }

    private fun boton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
        typeface = AppTheme.bold(context); isAllCaps = false; stateListAnimator = null
        background = Ui.botonAccento(context, AppTheme.R_INNER, AppTheme.ACCENT)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        setOnClickListener { Ui.pulso(this); onClick() }
    }

    private fun cambiarPin() {
        if (WalletManager.hasPin(this)) PinAuthHelper.show(this, autoBiometric = false) { ok -> if (ok) nuevoPin() }
        else nuevoPin()
    }
    private fun nuevoPin() {
        PinAuthHelper.pantalla(this, isSetup = true, autoBiometric = false) { ok ->
            if (ok) { Toast.makeText(this, "PIN updated", Toast.LENGTH_SHORT).show(); recreate() }
        }
    }
}
