package com.hunter.btc

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import android.text.InputType
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject

/** Fila de saldo: el Triple anterior no tenía sitio para la fuente del dato. */
private data class BalanceRow(
    val label: String,
    val addr: String,
    val sat: Long,
    val source: String
)

class WalletActivity : FragmentActivity() {
    private val REQ_IMPORT_BACKUP = 1002

    private val AMBER     get() = AppTheme.AMBER
    private val GREEN     get() = AppTheme.GREEN
    private val RED       get() = AppTheme.RED
    private val BG_DEEP   get() = AppTheme.BG_DEEP
    private val BG_PANEL  get() = AppTheme.BG_PANEL
    private val BG_CARD   get() = AppTheme.BG_CARD
    private val BG_ELEV   get() = AppTheme.BG_ELEV
    private val TXT_PRI   get() = AppTheme.TXT_PRI
    private val TXT_SEC   get() = AppTheme.TXT_SEC
    private val TXT_MUTED get() = AppTheme.TXT_MUTED
    private val BORDER_C  get() = AppTheme.BORDER_C

    private var actionBar: LinearLayout? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Traduce una excepción de red al idioma de alguien que no la ha escrito. */
    private fun motivo(e: Exception): String = when {
        e is java.net.UnknownHostException ->
            "No connection: could not resolve the server."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
            "No server answered. Check your connection and try again."
        e.message?.contains("failed to connect", true) == true ->
            "Could not connect. Check your connection."
        else -> e.message ?: e.javaClass.simpleName
    }
    private fun cardBg() = GradientDrawable().apply {
        setColor(BG_CARD)
        cornerRadius = dp(AppTheme.R_CARD).toFloat()
    }

    private var mnemonic = ""
    private var wifKey = ""
    private var wifAddr = ""
    private var isWifMode = false
    private var currentWalletId = ""
    private var currentWalletName = ""
    /**
     * Red de pruebas.
     *
     * Se guarda en preferencias. Antes era una variable suelta: al recrearse la
     * pantalla —girar el móvil, volver atrás, o que Android matara el proceso—
     * volvía sola a la red principal sin avisar, y te encontrabas enviando en
     * mainnet creyendo que estabas en pruebas.
     */
    private var isTestnet: Boolean
        get() = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getBoolean("testnet", false)
        set(v) { getSharedPreferences("app_settings", MODE_PRIVATE)
                    .edit().putBoolean("testnet", v).apply() }
    private var selectedUtxos = mutableListOf<org.json.JSONObject>()
    private var addresses = mutableMapOf<String, String>()
    private var balanceVisible = true
    // Aqui vivian isLocked, lastInteraction y AUTO_LOCK_MS. Los tres estan
    // fuera: el bloqueo lo decide AppLock para toda la app. AUTO_LOCK_MS
    // ademas no se leia en ningun sitio —el cierre por inactividad a los dos
    // minutos nunca llego a existir— y lastInteraction se actualizaba en cada
    // toque para nada. Dejar estado de bloqueo muerto en el fichero cuyo
    // bloqueo se acaba de rehacer es como vuelve el fallo.
    private lateinit var tabContent: FrameLayout
    private val labelMap = mapOf(
        "p2pkh_0" to "P2PKH [0]", "p2pkh_1" to "P2PKH [1]", "p2pkh_2" to "P2PKH [2]",
        "p2sh_0"  to "P2SH  [0]",
        "p2wpkh_0" to "WPKH  [0]", "p2wpkh_1" to "WPKH  [1]",
        "p2tr_0" to "P2TR  [0]", "p2tr_1" to "P2TR  [1]"
    )

    /* -- LIFECYCLE -- */
    override fun onCreate(s: Bundle?) {
        // Antes de super.onCreate: es el único momento en que el tema se
        // puede cambiar, y de él salen los colores de todos los diálogos.
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        // El tema es un objeto de proceso: lo fija la primera pantalla que
        // arranca. Si Android mata el proceso y lo revive directamente aqui
        // —desde una notificacion, o al volver a la tarea—, esa primera
        // pantalla es esta, y sin esto se pintaria en oscuro aunque el
        // usuario tenga elegido el claro.
        AppTheme.init(this)
        AppLock.init(this)
        overridePendingTransition(0, 0)
        val mode = intent.getStringExtra("MODE") ?: ""
        when (mode) {
            "seed" -> {
                val walletId = intent.getStringExtra("WALLET_ID") ?: ""
                currentWalletName = if (walletId.isEmpty()) WalletManager.mainName(this) else walletId
                isWifMode = false
                conSesion {
                    mnemonic = if (walletId.isEmpty()) WalletManager.loadSeed(this) ?: ""
                               else WalletManager.loadWalletSeed(this, walletId) ?: ""
                    currentWalletId = walletId
                    loadAddresses(); buildUI()
                    // Entrada directa al baúl desde la pestaña Wallet del hunter,
                    // para no obligar a buscarlo dentro del menú "...".
                    if (intent.getBooleanExtra("OPEN_BACKUP_VAULT", false)) showBackupVault()
                }
            }
            "wif" -> {
                wifKey  = intent.getStringExtra("WIF_KEY") ?: ""
                wifAddr = intent.getStringExtra("WIF_ADDR") ?: ""
                currentWalletName = intent.getStringExtra("WALLET_NAME") ?: "WIF Wallet"
                isWifMode = true
                conSesion {
                    loadAddresses(); buildUI()
                }
            }
            "watch" -> {
                wifKey  = ""
                wifAddr = intent.getStringExtra("WIF_ADDR") ?: ""
                currentWalletName = intent.getStringExtra("WALLET_NAME") ?: "Watch"
                isWifMode = true
                loadAddresses(); buildUI()
            }
            "setup" -> {
                showSetupDialog()
            }
            "wif_import" -> {
                showWifImportDialog()
            }
            "watch_import" -> {
                showWatcherImportDialog()
            }
            else -> {
                /* Puzzle match o legacy */
                val intentWif = intent.getStringExtra("WIF_KEY") ?: ""
                val intentAddr = intent.getStringExtra("WIF_ADDR") ?: ""
                if (intentWif.isNotEmpty()) {
                    wifKey = intentWif; wifAddr = intentAddr; isWifMode = true
                    currentWalletName = "Puzzle Match"
                    WalletManager.saveWif(this, intentWif, intentAddr)
                    loadAddresses(); buildUI()
                } else {
                    showWalletSelectorDialog()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // isLocked se ponia en CADA onPause. onPause salta cuando se pone
        // delante cualquier otra pantalla: el baul, el selector de ficheros,
        // el dialogo de compartir. Volver de cualquiera de ellos re-pedia la
        // huella o el PIN, aunque hubieran pasado dos segundos y el usuario
        // no se hubiera movido de la app. Ahora la sesion la cierra AppLock
        // —pantalla apagada, app en segundo plano, proceso nuevo— y aqui solo
        // se pregunta si sigue abierta.
        val hayQueAbrir = WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid()
        if (hayQueAbrir && (mnemonic.isNotEmpty() || wifKey.isNotEmpty())) {
            /* Mostrar overlay oscuro mientras autentica */
            val overlay = android.widget.FrameLayout(this).apply {
                setBackgroundColor(AppTheme.BG_DEEP)
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val lockIcon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_lock)
                setColorFilter(AppTheme.TXT_MUTED)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                    gravity = Gravity.CENTER
                }
            }
            overlay.addView(lockIcon)
            (window.decorView as? android.view.ViewGroup)?.addView(overlay)
            authenticate {
                (window.decorView as? android.view.ViewGroup)?.removeView(overlay)
            }
        }
    }

    override fun onBackPressed() {
        /* Si no hay wallet cargada cerrar directamente sin mostrar fondo */
        if (mnemonic.isEmpty() && wifKey.isEmpty()) {
            finish()
            overridePendingTransition(0, 0)
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Entrar en la cartera con la sesion ya abierta.
     *
     * Abrir la cartera desde el cajon pedia el PIN DOS veces seguidas por una
     * sola navegacion: una en MainActivity, que comprueba la sesion antes de
     * cambiar de pestana, y otra aqui nada mas crearse la pantalla, que
     * llamaba a authenticate sin mirar nada. Dos candados en fila en el mismo
     * paso no protegen el doble; solo ensenan a teclear el PIN sin pensar.
     *
     * Si no hay PIN configurado, isSessionValid es false para siempre y
     * authenticate se encarga: su showPinDialog no tiene nada que pedir.
     */
    private fun conSesion(onSuccess: () -> Unit) {
        if (WalletManager.hasPin(this) && PinAuthHelper.isSessionValid()) onSuccess()
        else authenticate(onSuccess)
    }

    /**
     * Guardar una cartera nueva, con el PIN que haga falta.
     *
     * Añadir una cartera pedía SIEMPRE "Choose a PIN" —showPinDialog con
     * isSetup = true— y al confirmarlo llamaba a savePin: REEMPLAZABA el PIN de
     * la app por lo que se tecleara ahí. Es el mismo PIN que abre la app al
     * arrancar y el que cifra las copias de seguridad, así que teclear otro
     * distinto lo cambiaba sin avisar. De ahí el "¿para qué es este PIN, si ya
     * tengo uno?".
     *
     * Ahora: si ya hay PIN, se usa ese —y sólo se pide si la sesión está
     * cerrada—; si no hay ninguno, entonces sí se elige, porque una cartera
     * sin PIN no la protege nada.
     */
    private fun guardarConPin(onOk: () -> Unit, onCancel: () -> Unit) {
        if (WalletManager.hasPin(this)) conSesion(onOk)
        else showPinDialog(isSetup = true) { ok -> if (ok) onOk() else onCancel() }
    }

    /** El campo "Name" de los diálogos de añadir y de renombrar. */
    private fun campoNombre(): EditText = EditText(this).apply {
        hint = "Name (optional)"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
        background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12)); textSize = AppTheme.SP_BODY
        minHeight = dp(48); isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(32))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    /** Pide un nombre nuevo y se lo pasa, ya limpio, a [alGuardar]. */
    private fun renombrar(actual: String, alGuardar: (String) -> Unit) {
        val et = campoNombre().apply { hint = "Name"; setText(actual); setSelection(text.length) }
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename wallet")
            .setView(caja)
            .setPositiveButton("Save") { _, _ ->
                val n = WalletManager.limpiarNombre(et.text.toString())
                if (n.isNotEmpty()) alGuardar(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* -- AUTH: biometria con fallback a PIN -- */
    /**
     * Esta pantalla no pasa por PinAuthHelper: tiene su propia huella con
     * caida a PIN, porque abre la cartera y quiere la huella directa.
     *
     * Pero la sesion es UNA para toda la app, asi que abrirla por aqui tiene
     * que contar igual. Sin esto, desbloquear la cartera con la huella dejaba
     * lastAuthTime a cero: el baul de dentro volveria a pedir el PIN, y peor,
     * cada onResume de esta misma pantalla veria la sesion cerrada y pediria
     * la huella otra vez — un bucle, y justo el fallo que se viene a quitar.
     */
    private fun authenticate(onSuccess: () -> Unit) {
        val exito = { PinAuthHelper.markAuthenticated(); onSuccess() }
        val bm = BiometricManager.from(this)
        val canBio = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (canBio) {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        exito()
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            code == BiometricPrompt.ERROR_USER_CANCELED) {
                            showPinDialog(isSetup = false) { ok -> if (ok) exito() else finish() }
                        } else finish()
                    }
                    override fun onAuthenticationFailed() {}
                })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Wallet Hunter")
                .setSubtitle("Verify your identity")
                .setNegativeButtonText("Use PIN")
                .build())
        } else {
            showPinDialog(isSetup = false) { ok -> if (ok) exito() else finish() }
        }
    }

    /* -- PIN DIALOG -- */

    private fun showPinDialog(isSetup: Boolean, onResult: (Boolean) -> Unit) {
        // Sheet container
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_PANEL)
                cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            setPadding(dp(24), dp(20), dp(24), dp(32))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(24), 0, dp(24), 0) }
        }

        // Handle bar
        sheet.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(AppTheme.BORDER_C); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22) }
        })

        // Title
        sheet.addView(TextView(this).apply {
            text = if (isSetup) "Choose a PIN" else "Enter the PIN"
            textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(24))
        })

        // Dots row
        val pinDisplay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        val dots = Array(6) {
            View(this).apply {
                val sz = dp(12)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AppTheme.BG_ELEV)
                }
            }
        }
        dots.forEach { pinDisplay.addView(it) }
        sheet.addView(pinDisplay)

        val tvStatus = TextView(this).apply {
            text = if (isSetup) "Six digits" else ""
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }

        val pin = StringBuilder()
        var firstPin = ""
        var dlg: AlertDialog? = null

        fun updateDots() = dots.forEachIndexed { i, d ->
            val bg = d.background as GradientDrawable
            bg.setColor(if (i < pin.length) AppTheme.TXT_PRI else AppTheme.BG_ELEV)
        }

        fun handleDigit(k: String) {
            if (k == "DEL") { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1); updateDots(); return }
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return
            if (isSetup) {
                if (firstPin.isEmpty()) {
                    firstPin = pin.toString(); pin.clear(); updateDots()
                    tvStatus.text = "Confirm PIN"; tvStatus.setTextColor(TXT_SEC)
                } else if (firstPin == pin.toString()) {
                    WalletManager.savePin(this, pin.toString())
                    dlg?.dismiss(); onResult(true)
                } else {
                    firstPin = ""; pin.clear(); updateDots()
                    tvStatus.text = "PINs don't match"; tvStatus.setTextColor(RED)
                }
            } else {
                if (WalletManager.checkPin(this, pin.toString())) {
                    dlg?.dismiss(); onResult(true)
                } else {
                    pin.clear(); updateDots()
                    tvStatus.text = "Wrong PIN"; tvStatus.setTextColor(RED)
                }
            }
        }

        // Numpad grid
        val numpad = GridLayout(this).apply {
            columnCount = 3; rowCount = 4
            setPadding(0, 0, 0, dp(10))
        }
        listOf("1","2","3","4","5","6","7","8","9","","0","DEL").forEach { k ->
            numpad.addView(Button(this).apply {
                text = k
                if (k == "DEL") {
                    text = "\u232B"   // el símbolo de borrar, no la palabra
                    textSize = 20f; setTextColor(TXT_SEC)
                } else {
                    textSize = 24f; setTextColor(TXT_PRI)
                }
                typeface = AppTheme.medium(context)
                isAllCaps = false
                stateListAnimator = null
                background = GradientDrawable().apply {
                    setColor(if (k.isEmpty()) Color.TRANSPARENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_KEY).toFloat()
                }
                val sz = dp(76)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = sz; height = sz
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                isEnabled = k.isNotEmpty()
                if (k.isNotEmpty()) setOnClickListener { handleDigit(k) }
            })
        }
        sheet.addView(numpad)
        sheet.addView(tvStatus)

        // Cancel button
        val btnCancel = Button(this).apply {
            text = "Cancel"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        }
        sheet.addView(btnCancel)

        dlg = AlertDialog.Builder(this)
            .setView(sheet).setCancelable(false).create()
        dlg!!.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                      android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        btnCancel.setOnClickListener { dlg?.dismiss(); onResult(false) }
        if (isSetup) btnCancel.visibility = View.GONE
        dlg!!.show()
    }

    /* -- LOAD ADDRESSES -- */
    private fun loadAddresses() {
        if (isWifMode) {
            // Derivar addr sincrono si falta, luego buildUI
            if (wifAddr.isEmpty() && wifKey.isNotEmpty()) {
                Thread {
                    try {
                        val derived = HunterEngine.wifToAddr(wifKey)
                        if (derived.isNotEmpty()) wifAddr = derived
                    } catch(e: Exception) {}
                    runOnUiThread {
                        if (wifAddr.isNotEmpty()) {
                            addresses = mutableMapOf("wif_0" to wifAddr)
                            buildUI()
                        } else {
                            // WIF decode failed - show error
                            Toast.makeText(this@WalletActivity, "Could not derive address from WIF key", Toast.LENGTH_LONG).show()
                            showWifImportDialog()
                        }
                    }
                }.start()
            } else {
                addresses = mutableMapOf("wif_0" to wifAddr)
                buildUI()
            }
            return
        }
        if (mnemonic.isNotEmpty()) {
            Thread {
                try {
                    // Se troceaba el JSON a mano con removePrefix/split(",")/
                    // split(":"). Funcionaba porque las direcciones no llevan esos
                    // caracteres, pero basta un campo nuevo con una coma para que
                    // el mapa de direcciones salga corrupto — y de ahí se firman
                    // transacciones.
                    val raw = HunterEngine.deriveWallet(mnemonic, isTestnet)
                    // Si el motor devuelve JSON mal formado, un JSONObject pelado
                    // deja la wallet sin ninguna dirección. Se rescatan las
                    // entradas bien formadas antes de rendirse: mejor una wallet
                    // parcial que una vacía sin explicación.
                    val obj = try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        android.util.Log.e("WalletActivity", "deriveWallet returned invalid JSON: ${e.message}")
                        val salvaged = JSONObject()
                        Regex("\"([a-z0-9_]+)\"\\s*:\\s*\"?([a-zA-Z0-9]+)\"?")
                            .findAll(raw)
                            .forEach { m -> salvaged.put(m.groupValues[1], m.groupValues[2]) }
                        if (salvaged.length() == 0) throw e
                        runOnUiThread {
                            Toast.makeText(this@WalletActivity,
                                "Warning: malformed engine response, ${salvaged.length()} addresses recovered",
                                Toast.LENGTH_LONG).show()
                        }
                        salvaged
                    }
                    // JSONObject.keys() no garantiza orden. El desplegable "From
                    // address" se ordena por esta secuencia, así que un orden
                    // inestable podría llevar a enviar desde otra dirección.
                    val order = listOf("p2pkh", "p2sh", "p2wpkh", "p2tr")
                    val map = LinkedHashMap<String, String>()
                    obj.keys().asSequence().toList()
                        .sortedWith(compareBy(
                            { k -> order.indexOfFirst { k.startsWith(it) }.let { if (it < 0) order.size else it } },
                            { k -> k.substringAfterLast('_').toIntOrNull() ?: 0 }
                        ))
                        .forEach { k ->
                            val v = obj.optString(k)
                            if (v.isNotEmpty()) map[k] = v
                        }
                    runOnUiThread { addresses = map; buildUI() }
                } catch (e: Exception) {
                    // Silencioso: la wallet quedaba sin direcciones y las
                    // pestañas sin explicación.
                    android.util.Log.e("WalletActivity", "deriveWallet: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this@WalletActivity,
                            "Could not derive the addresses: ${e.message}",
                            Toast.LENGTH_LONG).show()
                        buildUI()
                    }
                }
            }.start()
        }
    }

    private fun buildUI() {
        title = currentWalletName.ifEmpty { "Wallet" }
        window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
        setContentView(android.widget.FrameLayout(this)) // clear before rebuild
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP) }

        // La cabecera era una barra de otro color con tres botones de texto:
        // "<" con borde, "BTC WALLET" en mayúscula monoespaciada verde, y
        // ">> Hunter" con su propio recuadro. Tres estilos de botón en una
        // franja de 60dp, y el título gritando en el color del acento.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), dp(16), dp(AppTheme.PAD_SIDE), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(AppTheme.TXT_SEC)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) }
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = currentWalletName.ifEmpty { "Wallet" }
            textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.01f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_scan)
            setColorFilter(AppTheme.TXT_SEC)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(18) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                // Sin banderas esto creaba una SEGUNDA MainActivity encima de
                // la que ya había debajo —singleTop sólo evita el duplicado si
                // la de debajo está arriba del todo, y aquí arriba estaba esta—.
                // Dos pantallas principales con sus relojes de refresco
                // corriendo a la vez, y "atrás" llevaba de una a la otra.
                startActivity(Intent(this@WalletActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(BottomBar.EXTRA_TAB, BottomBar.SCANNER))
                finish()
            }
        })
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            setColorFilter(AppTheme.TXT_SEC)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { showMenu() }
        })
        root.addView(header)

        // Las pestañas eran botones a ras, distinguidos sólo por un cambio de
        // fondo casi invisible entre #161616 y #1D1D1D. Ahora la activa se
        // rellena con el acento, que es lo que hace el sistema en el resto.
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(18))
        }
        val tabBtns = mutableListOf<Button>()
        listOf("Balance","History","Send","Receive").forEachIndexed { i, name ->
            val btn = Button(this).apply {
                text = name; textSize = AppTheme.SP_CAPTION + 1f
                isAllCaps = false
                typeface = if (i == 0) AppTheme.bold(context) else AppTheme.medium(context)
                setTextColor(if (i == 0) AppTheme.BG_DEEP else AppTheme.TXT_SEC)
                background = GradientDrawable().apply {
                    setColor(if (i == 0) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                }
                stateListAnimator = null
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (i < 3) marginEnd = dp(6)
                }
            }
            tabBtns.add(btn); tabs.addView(btn)
        }
        tabBtns.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                tabBtns.forEach { b ->
                    b.setTextColor(AppTheme.TXT_SEC)
                    b.typeface = AppTheme.medium(b.context)
                    b.background = GradientDrawable().apply {
                        setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                    }
                }
                btn.setTextColor(AppTheme.ON_ACCENT)
                btn.typeface = AppTheme.bold(btn.context)
                btn.background = GradientDrawable().apply {
                    setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                }
                tabContent.removeAllViews()
                actionBar?.visibility = if (i >= 2) View.GONE else View.VISIBLE
                when (i) { 0 -> loadBalanceTab(); 1 -> loadHistoryTab(); 2 -> loadSendTab(); 3 -> loadReceiveTab() }
            }
        }
        root.addView(tabs)

        tabContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(tabContent)

        fun actionBtn(label: String, iconRes: Int, primary: Boolean, last: Boolean) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(if (primary) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CARD).toFloat()
                }
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    if (!last) marginEnd = dp(AppTheme.GAP)
                }
                addView(android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                        marginEnd = dp(9)
                    }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = AppTheme.SP_BODY + 1f
                    setTextColor(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    typeface = if (primary) AppTheme.bold(context) else AppTheme.medium(context)
                })
            }

        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), dp(18), dp(AppTheme.PAD_SIDE), dp(26))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        actionBar?.addView(actionBtn("Send", R.drawable.ic_send, primary = false, last = false)
            .apply { setOnClickListener { tabBtns[2].performClick() } })
        actionBar?.addView(actionBtn("Receive", R.drawable.ic_receive, primary = true, last = true)
            .apply { setOnClickListener { tabBtns[3].performClick() } })
        root.addView(actionBar)

        setContentView(root)
        loadBalanceTab()
    }

    /* -- BALANCE -- */
    private fun loadBalanceTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        // Centrado y en verde fijo, igual que el saldo de la pestaña Cartera
        // antes del rediseño: el acento pintaba también un cero.
        val tvTotal = TextView(this).apply {
            text = "—"; textSize = AppTheme.SP_DISPLAY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.display(context)
            letterSpacing = -0.04f
            setPadding(0, dp(12), 0, dp(6))
        }
        val tvFiat = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, 0, 0, dp(20))
        }
        ll.addView(tvTotal); ll.addView(tvFiat)
        scroll.addView(ll); tabContent.addView(scroll)

        Thread {
            var totalSat = 0L
            // label, dirección, saldo, fuente ("" = mempool.space, "electrum" = respaldo)
            val rows = mutableListOf<BalanceRow>()
            var usedFallback = false

            // mempool.space con respaldo Electrum. La consulta vive en
            // BalanceLookup porque el baúl de hallazgos necesita la misma.
            // Con doce direcciones y sin cobertura, preguntar una por una son
            // doce esperas encadenadas —cada una contra dos APIs web y diez
            // servidores Electrum— antes de poder decir nada. Se comprueba una
            // vez si hay alguna ruta a la cadena.
            var sinRed = !ChainApi.hayRed(isTestnet)
            var fallosSeguidos = 0
            addresses.forEach { (k, addr) ->
                val label = if (k == "wif_0") currentWalletName else (labelMap[k] ?: k)
                if (addr.isEmpty()) { rows.add(BalanceRow("Error", "empty address", -1L, "")); return@forEach }
                if (sinRed) { rows.add(BalanceRow(label, addr, -1L, "")); return@forEach }

                val res = BalanceLookup.query(addr, isTestnet)
                if (res == null) {
                    // Si se cae a mitad, no seguir intentándolo con el resto.
                    if (++fallosSeguidos >= 3) sinRed = true
                    rows.add(BalanceRow(label, addr, -1L, "")); return@forEach
                }
                fallosSeguidos = 0
                val bal = res.sat
                val src = res.source
                if (src == "electrum") usedFallback = true
                totalSat += bal
                rows.add(BalanceRow(label, addr, bal, src))
            }
            val huboFallo = sinRed || rows.any { it.sat < 0 }

            /* Descubrimiento HD.
             *
             * Hasta aquí sólo se miran las direcciones fijas que emite
             * deriveWallet: índices 0..2 de recepción y poco más. Una seed usada
             * en otra cartera puede tener los fondos en el índice 3 o el 7, y
             * entonces esta pantalla decía 0,00000000 sobre una wallet que no
             * está vacía — el peor error posible aquí.
             *
             * Se recorren las cuatro ramas hasta 20 direcciones seguidas sin
             * estrenar (BIP44) y se añade lo que aparezca por encima de lo ya
             * listado. Va después del primer repaso para que el total salga
             * rápido y esto lo complete.
             */
            if (!isWifMode && mnemonic.isNotEmpty() && !sinRed) {
                val yaListadas = addresses.values.toHashSet()
                // Las de recepción se suman al mapa con la clave canónica
                // (p2pkh_3, p2wpkh_5...). Así la pestaña Enviar las ofrece y
                // pathStr les calcula su ruta sola: encontrarlas y no poder
                // gastarlas sería peor que no encontrarlas.
                val nuevasGastables = linkedMapOf<String, String>()
                val clavePorProposito = mapOf(44 to "p2pkh", 49 to "p2sh",
                                              84 to "p2wpkh", 86 to "p2tr")
                for (purpose in HdScanner.PURPOSES) {
                    val (usadas, _) = HdScanner.scanBranch(mnemonic, purpose, change = 0, testnet = isTestnet)
                    for (f in usadas) {
                        if (f.addr in yaListadas) continue
                        yaListadas.add(f.addr)
                        val r = BalanceLookup.query(f.addr, isTestnet) ?: continue
                        if (r.source == "electrum") usedFallback = true
                        totalSat += r.sat
                        rows.add(BalanceRow("${HdScanner.purposeLabel(purpose)} [${f.index}]",
                                            f.addr, r.sat, r.source))
                        clavePorProposito[purpose]?.let { nuevasGastables["${it}_${f.index}"] = f.addr }
                    }
                    // El cambio también guarda fondos entre envíos.
                    val (camb, _) = HdScanner.scanBranch(mnemonic, purpose, change = 1, testnet = isTestnet)
                    for (f in camb) {
                        if (f.addr in yaListadas) continue
                        yaListadas.add(f.addr)
                        val r = BalanceLookup.query(f.addr, isTestnet) ?: continue
                        if (r.sat <= 0L) continue   // cambio ya gastado: no ensuciar la lista
                        if (r.source == "electrum") usedFallback = true
                        totalSat += r.sat
                        rows.add(BalanceRow("${HdScanner.purposeLabel(purpose)} change [${f.index}]",
                                            f.addr, r.sat, r.source))
                    }
                }
                if (nuevasGastables.isNotEmpty()) {
                    // Las pestañas se reconstruyen al cambiar de pestaña, así
                    // que basta con dejarlas en el mapa: Enviar las verá.
                    runOnUiThread { addresses.putAll(nuevasGastables) }
                }
            }

            var price = 0.0
            try {
                // "USD":(\d+) no captura decimales: con 67432.5 leía 67432.
                val js = ChainApi.get("/v1/prices", false)
                if (js != null) price = JSONObject(js).optDouble("USD", 0.0)
            } catch(e: Exception) {}
            val tot = totalSat; val pr = price
            runOnUiThread {
                val btcText = "%.8f".format(tot / 1e8).replace('.', ',')
                val fiatText = if (pr > 0) "BTC  ·  ≈ ${"%.2f".format(tot / 1e8 * pr)} USD" else "BTC"
                tvTotal.text = if (balanceVisible) btcText else "••••••••"
                tvTotal.setTextColor(if (tot > 0) AppTheme.ACCENT else AppTheme.TXT_PRI)
                if (pr > 0) tvFiat.text = if (balanceVisible) fiatText else "******"
                tvTotal.setOnClickListener {
                    balanceVisible = !balanceVisible
                    tvTotal.text = if (balanceVisible) btcText else "********"
                    tvFiat.text  = if (balanceVisible) fiatText else "******"
                }
                // Un fallo de red se decía antes fila a fila ("no answer"
                // doce veces) sin explicar nunca que el problema era el mismo.
                if (huboFallo) {
                    ll.addView(TextView(this).apply {
                        text = "Could not query the chain. What you see may be " +
                               "out of date; come back when you have a connection."
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                        typeface = AppTheme.body(context)
                        setLineSpacing(0f, 1.4f)
                        setPadding(0, dp(10), 0, 0)
                    })
                } else if (usedFallback) {
                    ll.addView(TextView(this).apply {
                        text = "The web APIs did not answer; the balance comes from Electrum"
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
                        typeface = AppTheme.body(context)
                        setPadding(0, dp(10), 0, 0)
                    })
                }
                rows.forEach { (lbl, addr, bal, src) ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg()
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(AppTheme.GAP) }
                    }
                    card.addView(TextView(this).apply {
                        text = lbl; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
                        typeface = AppTheme.medium(context)
                    })
                    card.addView(TextView(this).apply {
                        text = addr; textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
                        typeface = Typeface.MONOSPACE
                        setPadding(0, dp(3), 0, dp(7))
                    })
                    card.addView(TextView(this).apply {
                        text = (if (bal < 0) "no answer" else "%.8f BTC".format(bal / 1e8)) +
                               (if (src == "electrum") "  · electrum" else "")
                        textSize = AppTheme.SP_BODY
                        setTextColor(when { bal > 0 -> GREEN; bal == 0L -> TXT_SEC; else -> RED })
                        typeface = AppTheme.bold(context)
                    })
                    ll.addView(card)
                }
            }
        }.start()
    }

    /* -- HISTORY -- */
    private fun loadHistoryTab() {

        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
        val tvHead = TextView(this).apply {
            text = "Loading…"; textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(12), 0, dp(8))
        }
        ll.addView(tvHead); scroll.addView(ll); tabContent.addView(scroll)

        // ifEmpty { return } salía dejando el "Loading..." puesto para siempre,
        // sin mensaje ni error. Ocurre si deriveWallet falló (su catch llama a
        // buildUI con el mapa vacío) o si se abre la pestaña antes de que la
        // derivación asíncrona haya terminado.
        val queryAddrs = addresses.values.toList()
        if (queryAddrs.isEmpty()) {
            tvHead.text = "No addresses to check yet"
            tvHead.setTextColor(AppTheme.WARN)
            return
        }
        Thread {
            try {
                val cuerpo = ChainApi.get("/address/${queryAddrs[0]}/txs", isTestnet)
                if (cuerpo == null) {
                    // Electrum da la lista de identificadores pero no el detalle
                    // de cada transacción; reconstruirlo serían N llamadas más.
                    // Al menos se puede decir CUÁNTAS hay, que es más que nada.
                    val hist = ElectrumClient.getHistory(queryAddrs[0], isTestnet)
                    runOnUiThread {
                        tvHead.text = if (hist.isEmpty())
                            "Could not fetch the history. Check your connection."
                        else "${hist.size} transactions · details need mempool.space"
                        tvHead.setTextColor(AppTheme.WARN)
                    }
                    return@Thread
                }
                val arr = JSONArray(cuerpo)
                runOnUiThread {
                    tvHead.text = "${arr.length()} transactions · ${queryAddrs[0].take(14)}…"
                    if (arr.length() == 0) {
                        ll.addView(TextView(this).apply {
                            text = "No transactions yet"
                            setTextColor(TXT_SEC); textSize = AppTheme.SP_BODY
                            typeface = AppTheme.body(context)
                        })
                        return@runOnUiThread
                    }
                    for (i in 0 until minOf(arr.length(), 20)) {
                        val tx = arr.getJSONObject(i)
                        val txid = tx.getString("txid")
                        val confirmed = tx.optJSONObject("status")?.optBoolean("confirmed", false) ?: false
                        val blockTime = tx.optJSONObject("status")?.optLong("block_time", 0) ?: 0L
                        var received = 0L
                        val vout = tx.getJSONArray("vout")
                        for (j in 0 until vout.length()) {
                            val o = vout.getJSONObject(j)
                            if (addresses.values.contains(o.optString("scriptpubkey_address",""))) received += o.optLong("value", 0)
                        }
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL; background = cardBg()
                            setPadding(dp(16),dp(14),dp(16),dp(14))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(AppTheme.GAP) }
                        }
                        card.addView(TextView(this).apply {
                            text = txid.take(22)+"…"; textSize = AppTheme.SP_MICRO
                            setTextColor(TXT_SEC); typeface = Typeface.MONOSPACE
                        })
                        if (received > 0) card.addView(TextView(this).apply {
                            text = "+%.8f BTC".format(received/1e8); textSize = AppTheme.SP_BODY
                            setTextColor(GREEN); typeface = AppTheme.bold(context)
                            setPadding(0, dp(6), 0, dp(4))
                        })
                        card.addView(TextView(this).apply {
                            // Estado y fecha en una línea: eran dos, y ninguna
                            // de las dos llenaba la suya.
                            val fecha = if (blockTime > 0)
                                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(blockTime*1000))
                            else ""
                            text = (if (confirmed) "Confirmed" else "Pending") +
                                   (if (fecha.isNotEmpty()) " · $fecha" else "")
                            textSize = AppTheme.SP_CAPTION
                            setTextColor(if (confirmed) TXT_SEC else AppTheme.WARN)
                            typeface = AppTheme.body(context)
                        })
                        /* Click -> detalle de transaccion */
                        val txCopy = tx; val txidCopy = txid; val receivedCopy = received; val confirmedCopy = confirmed; val blockTimeCopy = blockTime
                        card.isClickable = true
                        card.setOnClickListener {
                            val sheet = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=GradientDrawable().apply{setColor(BG_PANEL);cornerRadius=dp(AppTheme.R_CARD).toFloat()}; setPadding(dp(20),dp(20),dp(20),dp(24)) }
                            sheet.addView(TextView(this).apply{text="Transaction";textSize=AppTheme.SP_TITLE;setTextColor(TXT_PRI);typeface=AppTheme.title(context);setPadding(0,0,0,dp(18))})
                            fun row(k:String,v:String,vc:Int=TXT_PRI){
                                val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(10))}
                                r.addView(TextView(this).apply{text=k;textSize=AppTheme.SP_CAPTION;setTextColor(TXT_SEC);typeface=AppTheme.medium(context);setPadding(0,0,0,dp(5))})
                                val tv=TextView(this).apply{text=v;textSize=AppTheme.SP_CAPTION;setTextColor(vc);typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);cornerRadius=dp(AppTheme.R_INNER).toFloat()};setPadding(dp(14),dp(12),dp(14),dp(12))}
                                r.addView(tv);sheet.addView(r)
                                tv.setOnLongClickListener{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("tx",v));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();true}
                            }
                            row("Transaction ID", txidCopy)
                            row("Status", if(confirmedCopy)"Confirmed" else "Pending", if(confirmedCopy)GREEN else AppTheme.WARN)
                            if(receivedCopy>0) row("Received","%.8f BTC".format(receivedCopy/1e8),GREEN)
                            if(blockTimeCopy>0) row("Date",java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date(blockTimeCopy*1000)))
                            val voutArr=txCopy.getJSONArray("vout")
                            var totalOut=0L; for(j in 0 until voutArr.length()) totalOut+=voutArr.getJSONObject(j).optLong("value",0)
                            row("Total out","%.8f BTC".format(totalOut/1e8))
                            val btnRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,0)}
                            val btnExplorer=android.widget.Button(this).apply{text="View in the explorer";textSize=AppTheme.SP_BODY;setTextColor(AppTheme.ON_ACCENT);typeface=AppTheme.bold(context);isAllCaps=false;stateListAnimator=null;background=GradientDrawable().apply{setColor(AppTheme.ACCENT);cornerRadius=dp(AppTheme.R_INNER).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(8)}}
                            val btnClose=android.widget.Button(this).apply{text="Close";textSize=AppTheme.SP_BODY;setTextColor(TXT_PRI);typeface=AppTheme.medium(context);isAllCaps=false;stateListAnimator=null;background=GradientDrawable().apply{setColor(BG_ELEV);cornerRadius=dp(AppTheme.R_INNER).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(48),1f)}
                            btnRow.addView(btnExplorer);btnRow.addView(btnClose);sheet.addView(btnRow)
                            val txDlg=AlertDialog.Builder(this).setView(sheet).setCancelable(true).create()
                            txDlg.window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setLayout((resources.displayMetrics.widthPixels*0.93f).toInt(),android.view.WindowManager.LayoutParams.WRAP_CONTENT);setGravity(Gravity.CENTER);attributes=attributes?.also{it.dimAmount=0.7f};addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)}
                            txDlg.show()
                            btnClose.setOnClickListener{txDlg.dismiss()}
                            btnExplorer.setOnClickListener{val url=if(isTestnet)"https://mempool.space/testnet/tx/$txidCopy" else "https://mempool.space/tx/$txidCopy";startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}
                        }
                        ll.addView(card)
                    }
                }
            } catch(e: Exception) { runOnUiThread { tvHead.text = motivo(e); tvHead.setTextColor(RED) } }
        }.start()
    }

    /* -- SEND -- */
    //
    // Era un formulario: cinco campos apilados del mismo tamaño —origen,
    // destino, importe, comisión, monedas— y un botón. Todos pesaban lo mismo,
    // así que la pantalla no decía qué estabas a punto de hacer; había que
    // leerla entera para enterarte.
    //
    // El mockup la ordena por lo que importa. El importe es la cifra
    // protagonista y va arriba. Debajo, de dónde sale y a dónde va, cada uno en
    // su tarjeta y con lo que hace falta saber de él: el tipo y el saldo de la
    // dirección de origen, y si la de destino es válida y de qué clase. Luego
    // la comisión como tres opciones con su tiempo estimado en vez de un número
    // que hay que saberse. Y antes del botón, escrito, lo que va a pasar: a
    // dónde vuelve el cambio y que se podrá subir la comisión después.
    private fun loadSendTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(28))
        }

        val keys = addresses.keys.toList()
        if (keys.isEmpty()) {
            ll.addView(TextView(this).apply {
                text = "There are no addresses to send from yet."
                textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(dp(AppTheme.PAD_SIDE), dp(24), dp(AppTheme.PAD_SIDE), 0)
            })
            scroll.addView(ll); tabContent.addView(scroll); return
        }
        var fromIdx = 0

        fun side(v: View, top: Int = 0, bottom: Int = 0) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(top), dp(AppTheme.PAD_SIDE), dp(bottom))
            }
        }
        fun cap(t: String) = TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
        }

        /* ── IMPORTE ───────────────────────────────────────────────────── */
        val amountBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        amountBlock.addView(cap("Amount"))

        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        // Un EditText sin fondo: la cifra se escribe donde se lee, sin un campo
        // aparte que la repita más pequeña.
        val etAmt = EditText(this).apply {
            hint = "0,00000000"
            textSize = 40f
            setTextColor(TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.display(context)
            letterSpacing = -0.04f
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        amountRow.addView(etAmt)
        amountRow.addView(TextView(this).apply {
            text = "BTC"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        amountBlock.addView(amountRow)

        val underAmount = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val tvFromBal = TextView(this).apply {
            text = "Checking the balance…"
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // "All" vacía la dirección entera. No pone el saldo tal cual: hay que
        // dejar la comisión dentro, y eso sólo se sabe al seleccionar monedas,
        // así que resta una estimación y doSend ajusta el resto.
        val chipMax = TextView(this).apply {
            text = "All"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.bold(context)
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true; isFocusable = true
        }
        underAmount.addView(tvFromBal); underAmount.addView(chipMax)
        amountBlock.addView(underAmount)
        ll.addView(side(amountBlock, top = 8, bottom = 22))

        /* ── SALE DE ───────────────────────────────────────────────────── */
        val fromCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(17), dp(15), dp(17), dp(15))
            isClickable = true; isFocusable = true
        }
        val tvFromType = TextView(this).apply {
            textSize = AppTheme.SP_MICRO - 1f; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.bold(context)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(11).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(38)).apply { marginEnd = dp(13) }
        }
        val fromCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fromCol.addView(cap("From").apply { textSize = AppTheme.SP_MICRO })
        val tvFromAddr = TextView(this).apply {
            textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
            typeface = Typeface.MONOSPACE   // es una dirección
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(3), 0, 0)
        }
        fromCol.addView(tvFromAddr)
        fromCard.addView(tvFromType); fromCard.addView(fromCol)
        fromCard.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(10) }
        })
        ll.addView(side(fromCard, bottom = AppTheme.GAP))

        /* ── VA A ──────────────────────────────────────────────────────── */
        val toCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(17), dp(15), dp(17), dp(15))
        }
        val toHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toHead.addView(cap("Goes to").apply {
            textSize = AppTheme.SP_MICRO
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // El veredicto de la dirección, ahí mismo mientras se escribe. Antes
        // sólo se comprobaba al pulsar enviar, y un envío a una dirección con
        // un carácter cambiado no tiene vuelta atrás.
        val tvToCheck = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_MICRO
            typeface = AppTheme.bold(context)
        }
        toHead.addView(tvToCheck)
        toCard.addView(toHead)

        val etTo = EditText(this).apply {
            hint = "bc1… o 1… o 3…"
            textSize = AppTheme.SP_CAPTION
            setTextColor(TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            typeface = Typeface.MONOSPACE
            background = null
            setPadding(0, dp(8), 0, 0)
            minHeight = dp(44)
            setLineSpacing(0f, 1.45f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        toCard.addView(etTo)
        ll.addView(side(toCard, bottom = 20))

        /* ── COMISIÓN ──────────────────────────────────────────────────── */
        ll.addView(side(cap("Fee"), bottom = 10))

        // -1 = automática. Se mantiene el contrato de doSend: quien decide es
        // la red salvo que aquí se elija otra cosa.
        var feeRate = -1
        val feeNames = listOf("Slow", "Normal", "Fast")
        val feeRates = intArrayOf(-1, -1, -1)
        val feePills = mutableListOf<LinearLayout>()
        val feeEtas  = mutableListOf<TextView>()
        var feeSel = 1

        val feeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tvTotal = TextView(this).apply {
            text = "—"
            textSize = 18f; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.02f
        }
        val tvFeeLine = TextView(this).apply {
            text = "—"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.bold(context)
        }

        /** Estimación honesta: una entrada, salida al destino y cambio. */
        fun estimateFeeSat(): Long {
            val r = if (feeRate > 0) feeRate.toLong() else feeRates[feeSel].toLong()
            if (r <= 0) return -1L
            val fromKey = keys[fromIdx]
            val vb = CoinSelector.inputVBytes(fromKey) +
                     CoinSelector.outputVBytes(etTo.text.toString().trim().ifEmpty { "1" }) +
                     CoinSelector.outputVBytes(addresses[fromKey] ?: "1") + 11
            return r * vb
        }
        fun refreshTotals() {
            val fee = estimateFeeSat()
            val amt = etAmt.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            tvFeeLine.text = if (fee < 0) "whatever the network suggests"
                             else "≈ %,d sat".format(fee)
            tvTotal.text = if (amt <= 0) "—"
                           else "%.8f".format(amt + (if (fee > 0) fee / 1e8 else 0.0))
        }

        fun paintFees() {
            feePills.forEachIndexed { i, pill ->
                val on = i == feeSel && feeRate <= 0
                pill.background = GradientDrawable().apply {
                    setColor(if (on) AppTheme.BG_ELEV else AppTheme.BG_CARD)
                    cornerRadius = dp(AppTheme.R_INNER).toFloat()
                }
                (pill.getChildAt(0) as TextView).apply {
                    setTextColor(if (on) AppTheme.ACCENT else TXT_SEC)
                    typeface = if (on) AppTheme.bold(context) else AppTheme.medium(context)
                }
            }
            refreshTotals()
        }

        feeNames.forEachIndexed { i, name ->
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i < 2) marginEnd = dp(8) }
            }
            pill.addView(TextView(this).apply {
                text = name; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
            })
            val eta = TextView(this).apply {
                text = "—"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
                typeface = AppTheme.body(context); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            pill.addView(eta)
            pill.setOnClickListener { feeSel = i; feeRate = -1; paintFees() }
            feePills.add(pill); feeEtas.add(eta); feeRow.addView(pill)
        }
        ll.addView(side(feeRow, bottom = AppTheme.GAP))

        /* ── LO QUE VA A PASAR ─────────────────────────────────────────── */
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
        }
        fun infoRow(iconRes: Int, tint: Int, title: String, sub: String?, right: View?): LinearLayout {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(17), dp(14), dp(17), dp(14))
            }
            r.addView(android.widget.ImageView(this).apply {
                setImageResource(iconRes); setColorFilter(tint)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) }
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = title; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
                typeface = AppTheme.body(context)
            })
            if (sub != null) col.addView(TextView(this).apply {
                text = sub; textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(0, dp(2), 0, 0)
            })
            r.addView(col)
            if (right != null) r.addView(right)
            return r
        }
        fun sep() = View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = dp(17); marginEnd = dp(17) }
        }

        infoCard.addView(infoRow(R.drawable.ic_send, TXT_SEC, "Fee", null, tvFeeLine))
        infoCard.addView(sep())
        infoCard.addView(infoRow(R.drawable.ic_refresh, AppTheme.ACCENT,
            "Change goes back to an address of yours", "It is not left as fee", null))
        infoCard.addView(sep())
        infoCard.addView(infoRow(R.drawable.ic_clock, TXT_SEC,
            "You can raise the fee later", "Sent as replaceable (RBF)", null))
        infoCard.addView(sep())

        // Coin Control estaba suelto como un botón más; es un detalle de esta
        // misma lista: de qué monedas sale.
        val tvCoinCtl = TextView(this).apply {
            text = "automatic"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
        }
        val rowCoinCtl = infoRow(R.drawable.ic_wallet, TXT_SEC, "Coins being spent", null, tvCoinCtl)
        rowCoinCtl.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) }
        })
        rowCoinCtl.isClickable = true; rowCoinCtl.isFocusable = true
        infoCard.addView(rowCoinCtl)
        ll.addView(side(infoCard, bottom = 20))

        /* ── TOTAL Y ACCIÓN ────────────────────────────────────────────── */
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            setPadding(dp(2), 0, dp(2), dp(14))
        }
        totalRow.addView(TextView(this).apply {
            text = "Total to deduct"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        totalRow.addView(tvTotal)
        totalRow.addView(TextView(this).apply {
            text = "BTC"; textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5) }
        })
        ll.addView(side(totalRow))

        val btnSend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
            ).apply { setMargins(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), 0) }
        }
        btnSend.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(AppTheme.ON_ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(10) }
        })
        btnSend.addView(TextView(this).apply {
            text = "Review the send"
            textSize = AppTheme.SP_BODY + 1f; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context)
        })
        ll.addView(btnSend)

        val tvStatus = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.35f)
        }
        ll.addView(side(tvStatus, top = 14))
        scroll.addView(ll); tabContent.addView(scroll)

        /* ── ESTADO ────────────────────────────────────────────────────── */
        var fromSat = -1L

        fun loadFromBalance() {
            val addr = addresses[keys[fromIdx]] ?: return
            fromSat = -1L
            tvFromBal.text = "Checking the balance…"
            Thread {
                val res = BalanceLookup.query(addr, isTestnet)
                runOnUiThread {
                    fromSat = res?.sat ?: -1L
                    tvFromBal.text = if (fromSat < 0) "Could not check the balance"
                                     else "Available %.8f BTC".format(fromSat / 1e8)
                }
            }.start()
        }
        fun paintFrom() {
            val k = keys[fromIdx]
            tvFromType.text = when {
                k.startsWith("p2pkh")  -> "P2PKH"
                k.startsWith("p2sh")   -> "P2SH"
                k.startsWith("p2wpkh") -> "SegWit"
                k.startsWith("p2tr")   -> "Taproot"
                else                   -> (labelMap[k] ?: k).take(6)
            }
            tvFromAddr.text = addresses[k] ?: ""
            selectedUtxos.clear()
            tvCoinCtl.text = "automatic"
            loadFromBalance()
            refreshTotals()
        }
        paintFrom()

        fromCard.setOnClickListener {
            val items = keys.map { k ->
                "${labelMap[k] ?: k}\n${(addresses[k] ?: "").take(22)}…"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Which address does it come from?")
                .setItems(items) { _, i -> fromIdx = i; paintFrom() }
                .show()
        }

        chipMax.setOnClickListener {
            if (fromSat <= 0) {
                tvStatus.text = "The balance of that address is not known yet."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            val fee = estimateFeeSat().coerceAtLeast(0L)
            val max = fromSat - fee
            if (max <= CoinSelector.DUST) {
                tvStatus.text = "The balance does not even cover the fee."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            etAmt.setText("%.8f".format(max / 1e8))
            tvStatus.text = ""
        }

        // Veredicto en vivo de la dirección de destino.
        etTo.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val a = s?.toString()?.trim() ?: ""
                if (a.isEmpty()) { tvToCheck.text = ""; refreshTotals(); return }
                when (val r = BtcAddress.validate(a, isTestnet)) {
                    is BtcAddress.Result.Valid -> {
                        tvToCheck.text = when (r.info.type) {
                            BtcAddress.Type.P2TR   -> "Taproot, valid"
                            BtcAddress.Type.P2WPKH -> "SegWit, valid"
                            BtcAddress.Type.P2WSH  -> "SegWit script, valid"
                            BtcAddress.Type.P2SH   -> "P2SH, valid"
                            BtcAddress.Type.P2PKH  -> "Legacy, valid"
                        }
                        tvToCheck.setTextColor(AppTheme.ACCENT)
                    }
                    is BtcAddress.Result.Invalid -> {
                        tvToCheck.text = r.reason
                        tvToCheck.setTextColor(AppTheme.RED)
                    }
                }
                refreshTotals()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etAmt.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refreshTotals() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Las comisiones reales, de la mempool. Hasta que respondan, las tres
        // opciones enseñan "—" y el envío va con la que recomiende la red, que
        // es lo que hacía antes el campo vacío.
        Thread {
            val f = ChainInfo.fees(isTestnet)
            runOnUiThread {
                if (f == null) {
                    feeEtas.forEach { it.text = "no data" }
                } else {
                    feeRates[0] = f.economy; feeRates[1] = f.halfHour; feeRates[2] = f.fastest
                    listOf("~2 h" to f.economy, "~30 min" to f.halfHour, "~10 min" to f.fastest)
                        .forEachIndexed { i, (eta, r) -> feeEtas[i].text = "$eta · $r sat/vB" }
                }
                paintFees()
            }
        }.start()
        paintFees()

        /* ── MONEDAS (Coin Control) ────────────────────────────────────── */
        rowCoinCtl.setOnClickListener {
            val fromAddr = addresses[keys[fromIdx]] ?: return@setOnClickListener
            tvStatus.text = "Checking the available coins…"
            tvStatus.setTextColor(TXT_SEC)
            Thread {
                try {
                    val lista = ChainInfo.utxos(fromAddr, isTestnet)
                    if (lista == null) {
                        runOnUiThread {
                            tvStatus.text = "Could not query the chain. Check your connection."
                            tvStatus.setTextColor(RED)
                        }
                        return@Thread
                    }
                    if (lista.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "That address has no coins to spend."
                            tvStatus.setTextColor(AppTheme.WARN)
                        }
                        return@Thread
                    }
                    val utxos = JSONArray().also { a -> lista.forEach { a.put(it) } }
                    runOnUiThread {
                        tvStatus.text = ""
                        val items = Array(utxos.length()) { i ->
                            val u = utxos.getJSONObject(i)
                            "%.8f BTC · ${u.getString("txid").take(12)}…".format(u.getLong("value") / 1e8)
                        }
                        val checked = BooleanArray(items.size) { true }
                        selectedUtxos.clear()
                        for (i in 0 until utxos.length()) selectedUtxos.add(utxos.getJSONObject(i))
                        AlertDialog.Builder(this)
                            .setTitle("Which coins are spent?")
                            .setMultiChoiceItems(items, checked) { _, idx, isChecked ->
                                val u = utxos.getJSONObject(idx)
                                if (isChecked) { if (!selectedUtxos.contains(u)) selectedUtxos.add(u) }
                                else selectedUtxos.remove(u)
                            }
                            .setPositiveButton("Use these") { _, _ ->
                                tvCoinCtl.text = if (selectedUtxos.isEmpty()) "automatic"
                                    else "%d · %.8f BTC".format(selectedUtxos.size,
                                        selectedUtxos.sumOf { it.getLong("value") } / 1e8)
                            }
                            .setNeutralButton("Automatic") { _, _ ->
                                selectedUtxos.clear(); tvCoinCtl.text = "automatic"
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { tvStatus.text = motivo(e); tvStatus.setTextColor(RED) }
                }
            }.start()
        }

        /* ── ENVIAR ────────────────────────────────────────────────────── */
        btnSend.setOnClickListener {
            val toAddr = etTo.text.toString().trim()
            val amtBtc = etAmt.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val fromKey = keys.getOrNull(fromIdx) ?: return@setOnClickListener
            val fromAddr = addresses[fromKey] ?: return@setOnClickListener
            if (toAddr.isEmpty()) {
                tvStatus.text = "The destination address is missing."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            if (amtBtc <= 0) {
                tvStatus.text = "The amount is missing."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            val check = BtcAddress.validate(toAddr, isTestnet)
            if (check is BtcAddress.Result.Invalid) {
                tvStatus.text = "Invalid address: ${check.reason}"
                tvStatus.setTextColor(RED); return@setOnClickListener
            }
            val addrType = (check as BtcAddress.Result.Valid).info.type
            // El contrato de doSend no cambia: -1 sigue queriendo decir "la que
            // recomiende la red". Lo que cambia es que ahora se elige viendo el
            // tiempo estimado en vez de escribiendo un número a ciegas.
            val rate = if (feeRate > 0) feeRate else feeRates[feeSel].takeIf { it > 0 } ?: -1
            doSend(toAddr, amtBtc, rate, fromKey, fromAddr, tvStatus, btnSend, addrType.toString())
        }
    }

    private fun doSend(toAddr: String, amtBtc: Double, feeRateManual: Int,
                       fromKey: String, fromAddr: String,
                       tvStatus: TextView, btnSend: View, addrType: String = "") {
            tvStatus.text = "Preparing the send…"; tvStatus.setTextColor(TXT_SEC); btnSend.isEnabled = false
            Thread {
                try {
                    // Iba directo a mempool.space sin respaldo. Hay redes e ISP
                    // que no lo alcanzan —lo resuelven a una IP que no es suya—
                    // y entonces no se podía enviar aunque Electrum sí llegara.
                    val lista = ChainInfo.utxos(fromAddr, isTestnet)
                    if (lista == null) {
                        runOnUiThread {
                            tvStatus.text = "Could not query the chain. Check your connection."
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    // Lista vacía es "esta dirección no tiene nada", que es
                    // distinto de "no hubo respuesta".
                    if (lista.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "That address has no coins to spend."
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    val fetched = JSONArray().also { a -> lista.forEach { a.put(it) } }

                    // Coin Control rellenaba selectedUtxos y el envío lo ignoraba,
                    // gastando siempre todos los UTXOs: la función era decorativa.
                    val chosen: List<org.json.JSONObject> =
                        if (selectedUtxos.isNotEmpty())
                            selectedUtxos.filter { sel ->
                                (0 until fetched.length()).any {
                                    val f = fetched.getJSONObject(it)
                                    f.optString("txid") == sel.optString("txid") &&
                                    f.optInt("vout") == sel.optInt("vout")
                                }
                            }
                        else (0 until fetched.length()).map { fetched.getJSONObject(it) }
                    if (chosen.isEmpty()) { runOnUiThread { tvStatus.text = "None of the selected UTXOs is still available"; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }

                    val amtSat = (amtBtc * 1e8).toLong()

                    // La tarifa era un campo con 5 sat/vB por defecto: un número
                    // fijo que no sabe cómo está la mempool. Se pregunta, y el
                    // campo manda sólo si el usuario escribió algo distinto.
                    val red = if (feeRateManual > 0) null else ChainInfo.fees(isTestnet)
                    val feeRate = when {
                        feeRateManual > 0 -> feeRateManual.toLong()
                        red != null       -> red.halfHour.toLong()
                        // Sin red y sin valor escrito: 5 sat/vB era el antiguo
                        // valor por defecto y sigue siendo un respaldo razonable.
                        else              -> 5L
                    }
                    val fuenteTarifa = when {
                        feeRateManual > 0 -> "the one you set"
                        red != null       -> "recommended, ~30 min"
                        else              -> "fallback: could not query"
                    }

                    // El tamaño de un input depende del tipo: ~148 vB en P2PKH,
                    // ~91 en P2SH-P2WPKH, ~68 en P2WPKH y ~58 en Taproot.
                    val inVBytes  = CoinSelector.inputVBytes(fromKey)
                    val outVBytes = CoinSelector.outputVBytes(toAddr)
                    val chgVBytes = CoinSelector.outputVBytes(fromAddr)

                    // Sin Coin Control manual se gastaban TODOS los UTXOs, con lo
                    // que la comisión crecía con cada entrada innecesaria.
                    val plan = if (selectedUtxos.isNotEmpty()) {
                        val vs = inVBytes * chosen.size + outVBytes + chgVBytes + 11
                        val f  = feeRate * vs
                        val tin = chosen.sumOf { it.getLong("value") }
                        CoinSelector.Plan(chosen, f, (tin - amtSat - f).coerceAtLeast(0L), vs, "hand-picked")
                    } else {
                        CoinSelector.select(chosen, amtSat, feeRate, inVBytes, outVBytes, chgVBytes)
                    }
                    if (plan == null || plan.chosen.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "Not enough balance for the amount plus the fee"
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }
                        return@Thread
                    }
                    val feeSat = plan.feeSat
                    val vsize  = plan.vsize
                    val utxoArr = JSONArray(); var totalIn = 0L
                    for (u in plan.chosen) {
                        val v = u.getLong("value"); totalIn += v
                        utxoArr.put(org.json.JSONObject()
                            .put("txid",   u.getString("txid"))
                            .put("vout",   u.getInt("vout"))
                            .put("amount", v))
                    }
                    if (totalIn < amtSat + feeSat) {
                        runOnUiThread {
                            tvStatus.text = "Not enough funds: $totalIn sat available, ${amtSat + feeSat} needed"
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }
                        return@Thread
                    }
                    // deriveWallet emite p2pkh_N, p2sh_0, p2wpkh_N y p2tr_N. El
                    // `else` anterior mandaba también las Taproot por m/84', así
                    // que enviar desde una bc1p firmaba con la clave de OTRA
                    // dirección. Además fromKey.last() tomaba un solo carácter:
                    // con índices de dos cifras habría derivado el índice 0.
                    val addrIdx = fromKey.substringAfterLast('_').toIntOrNull() ?: 0

                    // Los cuatro tipos que la wallet deriva se pueden gastar:
                    // P2PKH heredado, P2SH-P2WPKH (BIP49), P2WPKH nativo y
                    // Taproot por gasto de clave (BIP86 + BIP341 con Schnorr).
                    val pathStr = when {
                        fromKey.startsWith("p2pkh")  -> "m/44'/0'/0'/0/$addrIdx"
                        fromKey.startsWith("p2sh")   -> "m/49'/0'/0'/0/$addrIdx"
                        fromKey.startsWith("p2wpkh") -> "m/84'/0'/0'/0/$addrIdx"
                        fromKey.startsWith("p2tr")   -> "m/86'/0'/0'/0/$addrIdx"
                        else -> {
                            runOnUiThread {
                                tvStatus.text = "Unsupported address type: $fromKey"
                                tvStatus.setTextColor(RED); btnSend.isEnabled = true
                            }
                            return@Thread
                        }
                    }
                    // El cambio volvía a la misma dirección de la que se
                    // gastaba, dejando claro en la cadena cuál de las dos
                    // salidas era el cambio y encadenando el historial. Se busca
                    // la primera dirección sin estrenar de la rama .../1/k.
                    //
                    // Si la red no deja averiguarlo se manda sin change_path y
                    // el motor usa la dirección de origen: peor para la
                    // privacidad, pero el dinero vuelve a una dirección propia,
                    // que es lo que no puede fallar.
                    val purpose = when {
                        fromKey.startsWith("p2pkh")  -> 44
                        fromKey.startsWith("p2sh")   -> 49
                        fromKey.startsWith("p2wpkh") -> 84
                        else                         -> 86
                    }
                    val changePath = if (isWifMode) null
                                     else HdScanner.nextChangePath(mnemonic, purpose, isTestnet)
                    runOnUiThread {
                        tvStatus.text = if (changePath != null)
                            "Change to a new address (${changePath.substringAfterLast('/')})"
                        else "Change back to the source address (could not query the branch)"
                        tvStatus.setTextColor(TXT_SEC)
                    }

                    // Se construía concatenando strings: la dirección venía del
                    // EditText sin escapar, así que unas comillas permitían alterar
                    // los campos amount/fee del JSON que firma el motor.
                    // nLockTime a la altura actual: con 0 cualquiera puede
                    // reminar el último bloque e incluir esta transacción. Sólo
                    // surte efecto porque las entradas van con nSequence por
                    // debajo de 0xFFFFFFFF, cosa que ya hacen por RBF.
                    val tip = ChainInfo.tipHeight(isTestnet)

                    val req = org.json.JSONObject()
                        .put("mnemonic", mnemonic)
                        .put("path",     pathStr)
                        .put("utxos",    utxoArr)
                        .put("to",       toAddr)
                        .put("amount",   amtSat)
                        .put("fee",      feeSat)
                        .apply {
                            if (changePath != null) put("change_path", changePath)
                            if (tip != null) put("locktime", tip)
                        }
                        .toString()

                    // ── Confirmación, ya con las cifras de verdad ──────────
                    //
                    // Antes se preguntaba antes de consultar nada, así que sólo
                    // podía enseñar el importe y la tarifa en sat/vB. Lo que le
                    // importa a quien pulsa es cuánto se le descuenta en total,
                    // y eso depende de cuántas entradas hagan falta.
                    val totalSat = amtSat + feeSat
                    val resumen = buildString {
                        appendLine("Send %.8f BTC".format(amtBtc))
                        appendLine("to $toAddr" + if (addrType.isNotEmpty()) " ($addrType)" else "")
                        appendLine()
                        appendLine("Fee        %,d sat  ·  %d sat/vB (%s)".format(feeSat, feeRate, fuenteTarifa))
                        appendLine("Size       ~%d vB with %d input(s) (%s)".format(vsize, plan.chosen.size, plan.reason))
                        appendLine("TOTAL      %.8f BTC".format(totalSat / 1e8))
                        appendLine()
                        appendLine(if (plan.changeSat == 0L)
                            "No change output: the remainder goes to the fee."
                        else if (changePath != null)
                            "Change %.8f BTC to a new address (%s).".format(plan.changeSat / 1e8, changePath.substringAfterLast('/'))
                        else
                            "Change %.8f BTC to the source address: could not query the branch.".format(plan.changeSat / 1e8))
                        appendLine()
                        appendLine("Sent as replaceable: you can raise the fee if it gets stuck.")
                        appendLine()
                        append("A Bitcoin transaction CANNOT be undone.")
                    }
                    val seguir = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Review the send")
                            .setMessage(resumen)
                            .setCancelable(false)
                            .setNegativeButton("Cancel") { _, _ -> seguir.offer(false) }
                            .setPositiveButton("Send")   { _, _ -> seguir.offer(true) }
                            .show()
                    }
                    if (!seguir.take()) {
                        runOnUiThread {
                            tvStatus.text = "Send cancelled"; tvStatus.setTextColor(TXT_SEC)
                            btnSend.isEnabled = true
                        }
                        return@Thread
                    }

                    runOnUiThread { tvStatus.text = "Signing…"; tvStatus.setTextColor(TXT_SEC) }
                    val rawTx = HunterEngine.buildAndSignTx(req)
                    if (rawTx.startsWith("ERROR")) { runOnUiThread { tvStatus.text = rawTx; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }
                    runOnUiThread { tvStatus.text = "Broadcasting…"; tvStatus.setTextColor(TXT_SEC) }
                    // Difundir también tenía un único camino. Si la firma salió
                    // bien y no se puede difundir, la transacción se pierde sin
                    // más: mejor que lo intenten los dos.
                    val resp = ChainInfo.broadcast(rawTx, isTestnet)
                    val ok = !resp.startsWith("ERROR")
                    runOnUiThread {
                        tvStatus.text = if (ok) "Sent.\nTransaction ID: $resp"
                                        else resp.removePrefix("ERROR: ")
                        tvStatus.setTextColor(if (ok) GREEN else RED)
                        btnSend.isEnabled = true
                    }
                } catch(e: Exception) { runOnUiThread { tvStatus.text = motivo(e); tvStatus.setTextColor(RED); btnSend.isEnabled = true } }
            }.start()
    }

    /* -- RECEIVE -- */
    //
    // Era un desplegable con "p2pkh_1  19WiY3ZLfNaLGa…", un QR, la dirección en
    // una línea y "Copy Address". Lo que le faltaba no era estilo:
    //
    //  - El desplegable obligaba a saberse los nombres internos de las claves
    //    para elegir tipo de dirección. Ahora son cuatro pastillas con el
    //    prefijo que vas a ver: bc1q, bc1p, 3…, 1….
    //  - La dirección iba de corrido a 10sp. Va troceada en grupos de cuatro,
    //    alternando tono, que es lo que permite compararla de un vistazo con la
    //    que tienes en la otra pantalla, o leerla en voz alta sin perderte.
    //  - No se podía compartir, sólo copiar.
    //  - Y no decía en ningún sitio que reutilizar una dirección deja tu
    //    historial a la vista de cualquiera, que es la única razón por la que
    //    una cartera va cambiándolas.
    private fun loadReceiveTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(26))
        }
        fun side(v: View, top: Int = 0, bottom: Int = 0) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(top), dp(AppTheme.PAD_SIDE), dp(bottom))
            }
        }

        val keys = addresses.keys.toList()
        if (keys.isEmpty()) {
            ll.addView(side(TextView(this).apply {
                text = "There are no addresses to show yet."
                textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
            }, top = 24))
            scroll.addView(ll); tabContent.addView(scroll); return
        }

        /* ── TIPO DE DIRECCIÓN ─────────────────────────────────────────── */
        // Se agrupan por prefijo, que es lo que el otro va a ver pegado en su
        // cartera. Si la cartera no tiene un tipo, su pastilla no sale.
        data class Grupo(val etiqueta: String, val clave: String)
        val grupos = listOf(
            Grupo("bc1q", "p2wpkh"), Grupo("bc1p", "p2tr"),
            Grupo("3…",   "p2sh"),   Grupo("1…",   "p2pkh")
        ).mapNotNull { g -> keys.firstOrNull { it.startsWith(g.clave) }?.let { g to it } }
        // Una cartera de sólo observación o de WIF puede no encajar en ninguno:
        // en ese caso se enseña lo que haya, sin selector.
        val efectivos = grupos.ifEmpty { listOf(Grupo(labelMap[keys[0]] ?: "Address", keys[0]) to keys[0]) }
        var tipoSel = 0

        val tipoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tipoPills = mutableListOf<TextView>()

        /* ── QR ────────────────────────────────────────────────────────── */
        val qrWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(AppTheme.TXT_PRI)   // el QR necesita claro, sea cual sea el tema
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val ivQr = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(216), dp(216))
        }
        qrWrap.addView(ivQr)
        val qrCenter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(qrWrap)
        }

        /* ── LA DIRECCIÓN ──────────────────────────────────────────────── */
        val addrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(19), dp(17), dp(19), dp(17))
        }
        val addrHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addrHead.addView(TextView(this).apply {
            text = "Your address"
            textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvEstreno = TextView(this).apply {
            text = "checking…"
            textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.bold(context)
        }
        addrHead.addView(tvEstreno)
        addrCard.addView(addrHead)

        val tvAddr = TextView(this).apply {
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.display(context)
            letterSpacing = 0.02f
            setLineSpacing(0f, 1.75f)
            setPadding(0, dp(10), 0, 0)
        }
        addrCard.addView(tvAddr)

        /* ── COPIAR Y COMPARTIR ────────────────────────────────────────── */
        fun accion(label: String, iconRes: Int, primary: Boolean, last: Boolean) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(if (primary) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CARD).toFloat()
                }
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                    if (!last) marginEnd = dp(AppTheme.GAP)
                }
                addView(android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(9) }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = AppTheme.SP_BODY
                    setTextColor(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    typeface = if (primary) AppTheme.bold(context) else AppTheme.medium(context)
                })
            }
        val btnCopiar    = accion("Copy", R.drawable.ic_copy, primary = true, last = false)
        val btnCompartir = accion("Share", R.drawable.ic_send, primary = false, last = true)
        val accionesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnCopiar); addView(btnCompartir)
        }

        /* ── POR QUÉ CAMBIA ────────────────────────────────────────────── */
        val notaCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg()
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        notaCard.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(TXT_SEC)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) }
        })
        val notaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        notaCol.addView(TextView(this).apply {
            text = "Best to use a new address for each payment"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.body(context)
        })
        notaCol.addView(TextView(this).apply {
            text = "The previous ones are still yours and show up in Balance. " +
                   "Reusing one leaves your history in plain sight."
            textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.55f)
            setPadding(0, dp(3), 0, 0)
        })
        notaCard.addView(notaCol)

        if (efectivos.size > 1) ll.addView(side(tipoRow, bottom = 18))
        ll.addView(side(qrCenter, bottom = 18))
        ll.addView(side(addrCard, bottom = 14))
        ll.addView(side(accionesRow, bottom = 22))
        ll.addView(side(notaCard))
        scroll.addView(ll); tabContent.addView(scroll)

        /* ── LÓGICA ────────────────────────────────────────────────────── */
        fun qrBitmap(content: String, size: Int): Bitmap {
            val m = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val w = m.width; val h = m.height
            val px = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) px[row + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565)
        }

        /**
         * La dirección en grupos de cuatro, alternando tono.
         *
         * De corrido, una bech32 de 62 caracteres es imposible de comparar con
         * otra sin ir carácter a carácter. En grupos se compara por bloques, y
         * se puede leer en voz alta sin perder el sitio.
         */
        fun troceada(addr: String): CharSequence {
            val sb = android.text.SpannableStringBuilder()
            var i = 0
            var bloque = 0
            while (i < addr.length) {
                val fin = minOf(i + 4, addr.length)
                val desde = sb.length
                sb.append(addr, i, fin)
                if (bloque % 2 == 1) sb.setSpan(
                    android.text.style.ForegroundColorSpan(TXT_SEC),
                    desde, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (fin < addr.length) sb.append(' ')
                i = fin; bloque++
            }
            return sb
        }

        var addrActual = ""

        fun mostrar(addr: String) {
            addrActual = addr
            tvAddr.text = troceada(addr)
            Thread {
                try { val bm = qrBitmap("bitcoin:$addr", 512); runOnUiThread { ivQr.setImageBitmap(bm) } }
                catch (e: Exception) { android.util.Log.w("WalletActivity", "QR: ${e.message}") }
            }.start()
            // "Sin estrenar" no es decorativo: si ya ha recibido algo, decirlo
            // aquí es lo único que evita reutilizarla sin querer.
            tvEstreno.text = "checking…"
            tvEstreno.setTextColor(AppTheme.TXT_MUTED)
            Thread {
                val r = BalanceLookup.query(addr, isTestnet)
                runOnUiThread {
                    if (addrActual != addr) return@runOnUiThread
                    when {
                        r == null     -> { tvEstreno.text = ""; }
                        r.sat > 0L    -> { tvEstreno.text = "already has funds"; tvEstreno.setTextColor(AppTheme.WARN) }
                        else          -> { tvEstreno.text = "unused";    tvEstreno.setTextColor(AppTheme.ACCENT) }
                    }
                }
            }.start()
        }

        fun pintarTipos() {
            tipoPills.forEachIndexed { i, p ->
                val on = i == tipoSel
                p.background = GradientDrawable().apply {
                    setColor(if (on) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                }
                p.setTextColor(if (on) AppTheme.BG_DEEP else TXT_SEC)
                p.typeface = if (on) AppTheme.bold(p.context) else AppTheme.medium(p.context)
            }
        }
        efectivos.forEachIndexed { i, (g, k) ->
            val pill = TextView(this).apply {
                text = g.etiqueta
                textSize = AppTheme.SP_CAPTION
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i < efectivos.size - 1) marginEnd = dp(7) }
                setOnClickListener {
                    tipoSel = i; pintarTipos(); mostrar(addresses[k] ?: return@setOnClickListener)
                }
            }
            tipoPills.add(pill); tipoRow.addView(pill)
        }
        pintarTipos()
        mostrar(addresses[efectivos[0].second] ?: addresses.values.first())

        btnCopiar.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Bitcoin address", addrActual))
            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
        }
        btnCompartir.setOnClickListener {
            // Sólo la dirección: nada de claves. Va como texto plano para que
            // valga en cualquier aplicación.
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, addrActual)
            }, "Share the address"))
        }
    }

    /* -- MENU -- */
    /* -- WALLET SELECTOR -- */

    private fun switchToWallet(onReady: () -> Unit) {
        // Limpiar estado anterior
        addresses.clear()
        mnemonic = ""
        wifKey = ""
        wifAddr = ""
        isWifMode = false
        selectedUtxos.clear()
        // Ejecutar la carga de la nueva wallet
        onReady()
    }

    /* Cada entrada abre su cartera con conSesion y no con authenticate ni
     * showPinDialog. Esos dos piden la huella o el PIN SIEMPRE, y a esta lista
     * se llega desde la pestaña Wallet, que acaba de pedirlo: elegir una
     * cartera costaba dos PIN seguidos. conSesion sólo lo pide si la sesión
     * está cerrada — pantalla apagada o app en segundo plano —, que es cuando
     * protege algo. */
    private fun showWalletSelectorDialog(forceShow: Boolean = false) {
        val wallets = WalletManager.listWallets(this).toMutableList()
        val hasSeed = WalletManager.hasSeed(this)
        val wifPair = WalletManager.loadWif(this)

        // Si solo hay una seed y sin WIF extras, ir directo (solo si no se fuerza el selector)
        val wifList2 = WalletManager.listWifs(this)
        val watchList2 = WalletManager.listWatchers(this)
        if (!forceShow && hasSeed && wallets.isEmpty() && wifList2.isEmpty() && watchList2.isEmpty()) {
            conSesion {
                mnemonic = WalletManager.loadSeed(this) ?: ""
                currentWalletName = WalletManager.mainName(this)
                loadAddresses(); buildUI()
            }
            return
        }

        val scroll = android.widget.ScrollView(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(sheet)

        sheet.addView(TextView(this).apply {
            text = "Pick a wallet"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0, 0, 0, dp(18))
        })

        /* Cada entrada: su nombre, qué tipo es y su dirección.
         *
         * Antes enseñaba "WIF Wallet" y los ocho primeros caracteres de la
         * CLAVE PRIVADA. Todas las WIF se llamaban igual, así que dos entradas
         * distintas eran indistinguibles; y una lista no es sitio para enseñar
         * trozos de una clave. La dirección identifica la cartera igual de bien
         * y es pública.
         *
         * La tarjeta iba en BG_CARD sobre una hoja BG_PANEL, que son el mismo
         * color: no se veía dónde acababa una entrada y empezaba otra. */
        fun corta(a: String) = if (a.length > 18) a.take(8) + "…" + a.takeLast(6) else a

        fun walletCard(name: String, subtitle: String, onRename: () -> Unit, onClick: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
                }
                clipToOutline = true
                minimumHeight = dp(64)
                setPadding(dp(16), dp(10), dp(4), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                isClickable = true; isFocusable = true
                foreground = Ui.toque()
                contentDescription = "$name. $subtitle"
                setOnClickListener { onClick() }
            }
            val textos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textos.addView(TextView(this).apply {
                text = name; textSize = AppTheme.SP_BODY + 1f; setTextColor(TXT_PRI)
                typeface = AppTheme.bold(context)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textos.addView(TextView(this).apply {
                text = subtitle; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(textos)
            // El lápiz: renombrar sin tener que abrir la cartera. 48dp de
            // blanco de toque, separado del resto de la tarjeta para que
            // tocar el nombre abra la cartera y tocar el lápiz no.
            card.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_edit)
                setColorFilter(TXT_SEC)
                setPadding(dp(13), dp(13), dp(13), dp(13))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                isClickable = true; isFocusable = true
                foreground = Ui.toque()
                contentDescription = "Rename $name"
                setOnClickListener { onRename() }
            })
            sheet.addView(card)
        }

        // Wallet principal BIP39
        var selectorDlg: AlertDialog? = null

        if (hasSeed) {
            val nombreMain = WalletManager.mainName(this)
            walletCard(nombreMain, "Main seed · BIP39", onRename = {
                renombrar(nombreMain) { n ->
                    WalletManager.renameMain(this, n)
                    selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                }
            }) {
                selectorDlg?.dismiss()
                switchToWallet {
                    conSesion {
                        mnemonic = WalletManager.loadSeed(this) ?: ""
                        currentWalletName = nombreMain; isWifMode = false
                        loadAddresses()
                    }
                }
            }
        }

        wallets.forEach { (id, name) ->
            walletCard(name, "Seed · BIP39", onRename = {
                renombrar(name) { n ->
                    WalletManager.renameWallet(this, id, n)
                    selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                }
            }) {
                selectorDlg?.dismiss()
                switchToWallet {
                    conSesion {
                        mnemonic = WalletManager.loadWalletSeed(this, id) ?: ""
                        currentWalletId = id; currentWalletName = name; isWifMode = false
                        loadAddresses()
                    }
                }
            }
        }

        // Multiples WIF wallets
        val wifList = WalletManager.listWifs(this)
        wifList.forEach { (wid, wkey, wmeta) ->
            val parts = wmeta.split("|")
            val waddr = parts.getOrNull(0) ?: ""
            val wname = parts.getOrNull(1) ?: "WIF Wallet"
            walletCard(wname, if (waddr.isNotEmpty()) "WIF key · ${corta(waddr)}" else "WIF key",
                onRename = {
                    renombrar(wname) { n ->
                        WalletManager.renameWif(this, wid, n)
                        selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                    }
                }) {
                selectorDlg?.dismiss()
                conSesion {
                    switchToWallet {
                        wifKey = wkey
                        wifAddr = if (waddr.isNotEmpty()) waddr
                                  else try { HunterEngine.wifToAddr(wkey) } catch(e: Exception) { "" }
                        currentWalletName = wname; isWifMode = true
                        loadAddresses(); buildUI()
                    }
                }
            }
        }

        // Watcher wallets (solo lectura)
        val watchList = WalletManager.listWatchers(this)
        watchList.forEach { (wid, waddr, wlabel) ->
            walletCard(wlabel, "Watch only · ${corta(waddr)}", onRename = {
                renombrar(wlabel) { n ->
                    WalletManager.renameWatcher(this, wid, n)
                    selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                }
            }) {
                selectorDlg?.dismiss()
                switchToWallet {
                    wifKey = ""; wifAddr = waddr; isWifMode = true
                    currentWalletName = wlabel
                    loadAddresses(); buildUI()
                }
            }
        }

        // Botones agregar
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun addBtn(label: String, last: Boolean = false) = Button(this).apply {
            text = label; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (!last) marginEnd = dp(6)
            }
        }
        val btnNew   = addBtn("Seed")
        val btnWif   = addBtn("WIF key")
        val btnWatch = addBtn("Watch", last = true)
        btnRow.addView(btnNew); btnRow.addView(btnWif); btnRow.addView(btnWatch)
        sheet.addView(btnRow)

        val dlg = AlertDialog.Builder(this).setView(scroll).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        selectorDlg = dlg
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setTitle(null)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()

        btnNew.setOnClickListener { dlg.dismiss(); showSetupDialog() }
        btnWif.setOnClickListener { dlg.dismiss(); showWifImportDialog() }
        btnWatch.setOnClickListener { dlg.dismiss(); showWatcherImportDialog() }


    }

    private fun showWifImportDialog() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
            setPadding(dp(22), dp(22), dp(22), dp(24))
        }
        sheet.addView(TextView(this).apply {
            text = "Import a WIF key"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0,0,0,dp(6))
        })
        sheet.addView(TextView(this).apply {
            text = "Paste the private key in WIF format. It starts with 5, K or L."
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(18)); setLineSpacing(0f, 1.3f)
        })
        val etWif = EditText(this).apply {
            hint = "5HueCGU8..."; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY
            minHeight = dp(48)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        sheet.addView(etWif)
        val etNombre = campoNombre()
        sheet.addView(etNombre)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        val btnImport = Button(this).apply {
            text = "Import"; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        }
        btnRow.addView(btnImport); btnRow.addView(btnCancel)
        sheet.addView(btnRow)
        val dlg = AlertDialog.Builder(this).setView(sheet).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); finish(); overridePendingTransition(0, 0) }
        btnImport.setOnClickListener {
            val w = etWif.text.toString().trim()
            if (w.length < 50) { Toast.makeText(this, "Invalid WIF key", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dlg.dismiss()
            guardarConPin(onOk = {
                val derivedAddr = try { HunterEngine.wifToAddr(w) } catch(e: Exception) { "" }
                val nombre = WalletManager.limpiarNombre(etNombre.text.toString()).ifEmpty { "WIF Wallet" }
                if (WalletManager.listWifs(this).any { it.second == w })
                    Toast.makeText(this, "That key is already saved", Toast.LENGTH_SHORT).show()
                wifKey = w; wifAddr = derivedAddr; isWifMode = true
                currentWalletName = nombre
                WalletManager.saveWif(this, w, derivedAddr, nombre)
                loadAddresses(); buildUI()
            }, onCancel = { finish() })
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this).setTitle("Options")
            .setItems(arrayOf("Switch Wallet","Show seed / WIF","Change PIN","Toggle Testnet","Backup vault","Restore from file","Delete wallet","Cancel")) { _, pos ->
                when (pos) {
                    0 -> showWalletSelectorDialog(forceShow = true)
                    1 -> authenticate {
                        val msg = if (isWifMode) "WIF: $wifKey" else mnemonic
                        AlertDialog.Builder(this).setTitle("Do not show it to anyone").setMessage(msg).setPositiveButton("Got it", null).show()
                    }
                    2 -> authenticate { showPinDialog(isSetup = true) {} }
                    3 -> {
                        isTestnet = !isTestnet
                        // Hay que volver a derivar: en testnet la rama del árbol
                        // es otra (coin type 1'), así que las direcciones que se
                        // están enseñando no son las de esta red. Sin esto el
                        // interruptor cambiaba a qué explorador se preguntaba
                        // pero seguías viendo —y usando— las de mainnet.
                        loadAddresses()
                        Toast.makeText(this,
                            if (isTestnet) "Testnet enabled — addresses reloaded"
                            else "Mainnet — addresses reloaded",
                            Toast.LENGTH_SHORT).show()
                    }
                    4 -> showBackupVault()
                    5 -> showRestoreDialog()
                    6 -> AlertDialog.Builder(this).setTitle("Delete the wallet?").setMessage("Make sure you have a copy of the key: this cannot be undone.")
                            .setPositiveButton("Delete") { _, _ ->
                                when {
                                    isWifMode && wifAddr.isNotEmpty() -> {
                                        /* Borrar WIF o Watcher */
                                        val wifList = WalletManager.listWifs(this)
                                        val match = wifList.firstOrNull { it.second == wifKey || it.third.startsWith(wifAddr) }
                                        if (match != null) WalletManager.removeWif(this, match.first)
                                        val watchList = WalletManager.listWatchers(this)
                                        val watchMatch = watchList.firstOrNull { it.second == wifAddr }
                                        if (watchMatch != null) WalletManager.removeWatcher(this, watchMatch.first)
                                    }
                                    currentWalletId.isNotEmpty() -> WalletManager.deleteWallet(this, currentWalletId)
                                    else -> WalletManager.clearSeedOnly(this)
                                }
                                finish()
                            }
                            .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    /* -- SETUP DIALOG -- */
    /* -- SETUP DIALOG -- */
    /**
     * Guarda una seed recién escrita y la abre.
     *
     * Esto llamaba siempre a saveSeed, que escribe la seed PRINCIPAL. Con una
     * principal ya guardada, "añadir" otra la SUSTITUÍA: la anterior se perdía,
     * y si no había copia de seguridad, con ella el acceso a sus fondos. Las
     * seeds adicionales ya existían —saveWallet, que usa la restauración de
     * copias—, sólo que añadir no las usaba.
     *
     * Ahora la primera va de principal y las siguientes, al lado. Y si la seed
     * ya está guardada, se abre la que hay en vez de duplicarla.
     */
    private fun guardarSeedNueva(mn: String, nombreEscrito: String) {
        val nombre = WalletManager.limpiarNombre(nombreEscrito)
        val principal = if (WalletManager.hasSeed(this)) WalletManager.loadSeed(this) else null
        val existente = WalletManager.listWallets(this)
            .firstOrNull { WalletManager.loadWalletSeed(this, it.first) == mn }
        when {
            !WalletManager.hasSeed(this) -> {
                WalletManager.saveSeed(this, mn)
                if (nombre.isNotEmpty()) WalletManager.renameMain(this, nombre)
                currentWalletId = ""; currentWalletName = WalletManager.mainName(this)
            }
            principal == mn -> {
                Toast.makeText(this, "That seed is already saved", Toast.LENGTH_SHORT).show()
                currentWalletId = ""; currentWalletName = WalletManager.mainName(this)
            }
            existente != null -> {
                Toast.makeText(this, "That seed is already saved", Toast.LENGTH_SHORT).show()
                currentWalletId = existente.first; currentWalletName = existente.second
            }
            else -> {
                val id = "w${System.currentTimeMillis()}"
                val n = nombre.ifEmpty { "Wallet ${WalletManager.listWallets(this).size + 2}" }
                WalletManager.saveWallet(this, id, n, mn)
                currentWalletId = id; currentWalletName = n
            }
        }
        mnemonic = mn; isWifMode = false
        loadAddresses(); buildUI()
    }

    private fun showSetupDialog() {
        val scroll = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        scroll.addView(layout)
        layout.addView(TextView(this).apply {
            text = "Import a wallet"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0, 0, 0, dp(6))
        })
        layout.addView(TextView(this).apply {
            text = "Type the 12 or 24 words of your BIP39 seed."
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(22)); setLineSpacing(0f, 1.3f)
        })
        val tvCount = TextView(this).apply {
            text = "0 / 24 words"; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
            gravity = Gravity.END; setPadding(0, 0, 0, dp(4))
        }
        layout.addView(tvCount)
        val etSeed = EditText(this).apply {
            hint = "word1 word2 word3 …"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply {
                setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY; minLines = 3; maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        layout.addView(etSeed)
        val suggestScroll = android.widget.HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(BG_DEEP); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
        }
        val suggestInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6),dp(6),dp(6),dp(6)) }
        suggestScroll.addView(suggestInner)
        layout.addView(suggestScroll)
        val etNombre = campoNombre()
        layout.addView(etNombre)
        val bip39 = Bip39Words.WORDS
        fun updateSuggestions(cur: String) {
            suggestInner.removeAllViews()
            if (cur.length < 2) return
            bip39.filter { it.startsWith(cur) }.take(7).forEach { word ->
                suggestInner.addView(Button(this@WalletActivity).apply {
                    text = word; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(6) }
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener {
                        val t = etSeed.text.toString()
                        val sp = t.lastIndexOf(' ')
                        val nt = if (sp >= 0) t.substring(0, sp + 1) + word + " " else "$word "
                        etSeed.setText(nt); etSeed.setSelection(nt.length)
                        suggestInner.removeAllViews()
                    }
                })
            }
        }
        etSeed.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val txt = s.toString()
                val words = txt.trim().split(" +".toRegex()).filter { it.isNotEmpty() }
                val cnt = words.size
                tvCount.text = "$cnt / 24 words"
                tvCount.setTextColor(when { cnt == 12 || cnt == 24 -> GREEN; cnt > 24 -> RED; else -> TXT_MUTED })
                val lastWord = if (txt.endsWith(" ")) "" else words.lastOrNull() ?: ""
                updateSuggestions(lastWord)
            }
        })
        layout.addView(TextView(this).apply {
            text = "Seed encrypted with Android Keystore"
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0, dp(16), 0, dp(4))
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        val btnNext = Button(this).apply {
            text = "Import"; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.display(context)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.display(context)
            background = GradientDrawable().apply { setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        btnRow.addView(btnNext); btnRow.addView(btnCancel); layout.addView(btnRow)
        val dlg = AlertDialog.Builder(this).setView(scroll).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                      android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); finish(); overridePendingTransition(0, 0) }
        btnNext.setOnClickListener {
            val mn = Bip39.normalize(etSeed.text.toString()).joinToString(" ")
            // Sólo se contaban palabras: una seed mal tecleada se guardaba igual
            // y la wallet derivaba direcciones ajenas, mostrando saldo cero.
            when (val v = Bip39.validate(mn)) {
                is Bip39.Result.Invalid -> {
                    Toast.makeText(this, v.reason, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                else -> {}
            }
            dlg.dismiss()
            guardarConPin(onOk = { guardarSeedNueva(mn, etNombre.text.toString()) },
                          onCancel = { finish() })
        }
    }

    private fun showWatcherImportDialog() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
            setPadding(dp(22), dp(22), dp(22), dp(24))
        }
        sheet.addView(TextView(this).apply {
            text = "Watch Address"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0,0,0,dp(6))
        })
        sheet.addView(TextView(this).apply {
            text = "Monitor any Bitcoin address (read-only, no private key needed)"
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(18)); setLineSpacing(0f, 1.3f)
        })
        val etAddr = EditText(this).apply {
            hint = "bc1q... or 1... or 3..."; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY
            minHeight = dp(48)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val etLabel = EditText(this).apply {
            hint = "Label (e.g. Puzzle #71)"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        sheet.addView(etAddr); sheet.addView(etLabel)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        val btnAdd = Button(this).apply {
            text = "Watch"; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        }
        btnRow.addView(btnAdd); btnRow.addView(btnCancel); sheet.addView(btnRow)
        val dlg = AlertDialog.Builder(this).setView(sheet).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); finish(); overridePendingTransition(0, 0) }
        btnAdd.setOnClickListener {
            val addr = etAddr.text.toString().trim()
            val label = etLabel.text.toString().trim().ifEmpty { "Watcher" }
            if (addr.length < 26) { Toast.makeText(this, "Invalid address", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dlg.dismiss()
            WalletManager.saveWatcher(this, addr, label)
            wifKey = ""; wifAddr = addr; isWifMode = true
            currentWalletName = label
            loadAddresses(); buildUI()
        }
    }

    private fun showBackupDialog() {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val etPin = android.widget.EditText(this).apply {
            hint = "Enter your PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(AppTheme.TXT_PRI)
            setHintTextColor(AppTheme.TXT_MUTED)
        }
        root.addView(android.widget.TextView(this).apply {
            text = "PIN to encrypt the backup:"
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("New backup")
            .setView(root)
            .setPositiveButton("Create") { _, _ ->
                val pin = etPin.text.toString()
                if (pin.length < 4) {
                    android.widget.Toast.makeText(this, "PIN too short", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Antes se lanzaba el selector de compartir aquí mismo: si lo
                // cerrabas, la copia quedaba en un directorio interno del que
                // nada volvía a hablar. Ahora se guarda en el baúl y desde ahí
                // se comparte, se mira o se restaura.
                val file = WalletManager.exportBackup(this, pin)
                if (file != null) {
                    android.widget.Toast.makeText(this,
                        "Backup saved to the vault", android.widget.Toast.LENGTH_SHORT).show()
                    showBackupVault()
                } else {
                    android.widget.Toast.makeText(this,
                        "Nothing to export: no wallets, no WIFs, no finds",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Baúl de copias ────────────────────────────────────────────────────────

    /** Lista las copias guardadas. Cada una se comparte, se mira o se restaura. */
    private fun showBackupVault() {
        val copias = BackupStore.list(this)
        val b = AlertDialog.Builder(this)
            .setTitle("Backup vault (${copias.size}/${BackupStore.MAX_KEPT})")

        if (copias.isEmpty()) {
            b.setMessage("No backup yet.\n\nA backup holds the seeds, " +
                         "the WIFs, the watchers and the vault finds, encrypted with " +
                         "your PIN. The ${BackupStore.MAX_KEPT} most recent ones are kept.")
        } else {
            val items = copias.map {
                "${BackupStore.humanDate(it.createdAt)}  ·  ${BackupStore.humanSize(it.bytes)}"
            }.toTypedArray()
            b.setItems(items) { _, i -> showBackupActions(copias[i]) }
        }

        b.setPositiveButton("Create backup") { _, _ -> showBackupDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Qué hacer con una copia concreta. */
    private fun showBackupActions(info: BackupStore.Info) {
        val acciones = arrayOf(
            "Share",
            "View contents",
            "Restore this backup",
            "Delete")
        AlertDialog.Builder(this)
            .setTitle(BackupStore.humanDate(info.createdAt))
            .setItems(acciones) { _, which ->
                when (which) {
                    0 -> try {
                        startActivity(BackupStore.shareIntent(this, info.file))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this, "Could not share: ${e.message}",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    1 -> askPinFor("View contents") { pin -> inspectBackupFile(info, pin) }
                    2 -> askPinFor("Restore backup") { pin -> restoreFromVault(info, pin) }
                    3 -> confirmDeleteBackup(info)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Pide el PIN con el que se cifró la copia. */
    private fun askPinFor(titulo: String, onPin: (String) -> Unit) {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val etPin = android.widget.EditText(this).apply {
            hint = "Backup PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(AppTheme.TXT_PRI)
            setHintTextColor(AppTheme.TXT_MUTED)
        }
        root.addView(android.widget.TextView(this).apply {
            // Una copia vieja se abre con el PIN que tuvieras entonces: la clave
            // se deriva del PIN en el momento de crearla, no del PIN actual.
            text = "The PIN this backup was created with:"
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(etPin)
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(root)
            .setPositiveButton("OK") { _, _ -> onPin(etPin.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Descifra y enseña qué trae la copia, sin mostrar ningún secreto. */
    private fun inspectBackupFile(info: BackupStore.Info, pin: String) {
        val resumen = try {
            WalletManager.inspectBackup(pin, info.file.readBytes())
        } catch (e: Exception) { null }

        if (resumen == null) {
            AlertDialog.Builder(this)
                .setTitle("Could not open it")
                .setMessage("Wrong PIN, or the file is not a valid backup.\n\n" +
                            "Remember a backup opens with the PIN you had when " +
                            "you created it.")
                .setPositiveButton("Got it", null)
                .show()
            return
        }

        val detalle = buildString {
            appendLine("Created: ${BackupStore.humanDate(resumen.createdAt)}")
            appendLine("Format: v${resumen.version}")
            appendLine("Size: ${BackupStore.humanSize(info.bytes)}")
            appendLine()
            appendLine("Main seed: ${if (resumen.hasMainSeed) "yes" else "no"}")
            appendLine("Wallets: ${resumen.wallets}")
            appendLine("WIF keys: ${resumen.wifs}")
            appendLine("Watch-only: ${resumen.watchers}")
            appendLine("Finds: ${resumen.matches}")
            if (resumen.version < 2) {
                appendLine()
                appendLine("Old backup: wallets only. The main seed, " +
                           "the WIFs, the watchers and the finds were not stored.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Backup contents")
            .setMessage(detalle)
            .setPositiveButton("Restore") { _, _ -> restoreFromVault(info, pin) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Restaura sin pasar por el selector de ficheros. Añade, no reemplaza. */
    private fun restoreFromVault(info: BackupStore.Info, pin: String) {
        val resumen = try {
            WalletManager.inspectBackup(pin, info.file.readBytes())
        } catch (e: Exception) { null }
        if (resumen == null) {
            android.widget.Toast.makeText(this, "Wrong PIN or invalid backup",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Restore this backup?")
            .setMessage("It will be added to what you have: ${resumen.wallets} wallet(s), " +
                        "${resumen.wifs} WIF, ${resumen.watchers} watch-only and " +
                        "${resumen.matches} find(s).\n\n" +
                        (if (resumen.hasMainSeed)
                            "The main seed in the backup REPLACES the current one. "
                         else "") +
                        "If the current one is in no backup, save it first.")
            .setPositiveButton("Restore") { _, _ ->
                val count = WalletManager.importBackup(this, pin, info.file.readBytes())
                if (count >= 0) {
                    android.widget.Toast.makeText(this,
                        "$count item(s) restored", android.widget.Toast.LENGTH_SHORT).show()
                    buildUI()
                } else {
                    android.widget.Toast.makeText(this,
                        "Restore failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteBackup(info: BackupStore.Info) {
        AlertDialog.Builder(this)
            .setTitle("Delete this backup?")
            .setMessage("${BackupStore.humanDate(info.createdAt)}\n\n" +
                        "It cannot be undone. If it is the only one holding your seeds, " +
                        "they go with it.")
            .setPositiveButton("Delete") { _, _ ->
                BackupStore.delete(this, info.file)
                android.widget.Toast.makeText(this, "Backup deleted",
                    android.widget.Toast.LENGTH_SHORT).show()
                showBackupVault()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRestoreDialog() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_IMPORT_BACKUP)
    }

    private fun doRestore(uri: android.net.Uri) {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val etPin = android.widget.EditText(this).apply {
            hint = "Backup PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(AppTheme.TXT_PRI)
        }
        root.addView(android.widget.TextView(this).apply {
            text = "The PIN the backup was created with:"
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setView(root)
            .setPositiveButton("Restore") { _, _ ->
                val pin = etPin.text.toString()
                val data = contentResolver.openInputStream(uri)?.readBytes() ?: return@setPositiveButton
                val count = WalletManager.importBackup(this, pin, data)
                if (count >= 0) {
                    // Cuenta wallets, seed principal, WIF, watchers y hallazgos:
                    // decir "wallet(s)" a secas confundía cuando el backup
                    // traía sobre todo claves sueltas.
                    android.widget.Toast.makeText(this,
                        "$count item(s) restored", android.widget.Toast.LENGTH_SHORT).show()
                    buildUI()
                } else {
                    android.widget.Toast.makeText(this,
                        "Error: wrong PIN or invalid file", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }



    /**
     * Faltaba por completo: showRestoreDialog() lanzaba el selector de ficheros
     * con REQ_IMPORT_BACKUP, pero esta clase no tenía onActivityResult, así que
     * doRestore() nunca llegaba a ejecutarse. "Restaurar Backup" abría el
     * selector y descartaba el fichero en silencio — sin forma de recuperar las
     * wallets desde una copia de seguridad.
     */
    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_IMPORT_BACKUP && res == RESULT_OK) {
            data?.data?.let { doRestore(it) }
        }
    }
}
