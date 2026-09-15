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
    private val CYAN      get() = AppTheme.CYAN
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
            "Sin conexión: no se pudo resolver mempool.space."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
            "mempool.space no respondió. Revisa la conexión y vuelve a intentarlo."
        e.message?.contains("failed to connect", true) == true ->
            "No se pudo conectar con mempool.space. Revisa la conexión."
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
    private var isTestnet = false
    private var selectedUtxos = mutableListOf<org.json.JSONObject>()
    private var addresses = mutableMapOf<String, String>()
    private var currentTab = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var balanceVisible = true
    private var isLocked = false
    private var lastInteraction = 0L
    private val AUTO_LOCK_MS = 2 * 60 * 1000L
    private lateinit var tabContent: FrameLayout
    private val labelMap = mapOf(
        "p2pkh_0" to "P2PKH [0]", "p2pkh_1" to "P2PKH [1]", "p2pkh_2" to "P2PKH [2]",
        "p2sh_0"  to "P2SH  [0]",
        "p2wpkh_0" to "WPKH  [0]", "p2wpkh_1" to "WPKH  [1]",
        "p2tr_0" to "P2TR  [0]", "p2tr_1" to "P2TR  [1]"
    )

    /* -- LIFECYCLE -- */
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        overridePendingTransition(0, 0)
        lastInteraction = System.currentTimeMillis()
        val mode = intent.getStringExtra("MODE") ?: ""
        when (mode) {
            "seed" -> {
                val walletId = intent.getStringExtra("WALLET_ID") ?: ""
                currentWalletName = if (walletId.isEmpty()) "Main Wallet" else walletId
                isWifMode = false
                authenticate {
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
                authenticate {
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
        if (isLocked && (mnemonic.isNotEmpty() || wifKey.isNotEmpty())) {
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
                isLocked = false
                (window.decorView as? android.view.ViewGroup)?.removeView(overlay)
            }
        }
        lastInteraction = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        isLocked = true  // bloquear siempre al salir
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = System.currentTimeMillis()
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

    /* -- AUTH: biometria con fallback a PIN -- */
    private fun authenticate(onSuccess: () -> Unit) {
        val bm = BiometricManager.from(this)
        val canBio = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (canBio) {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        lastInteraction = System.currentTimeMillis(); isLocked = false; onSuccess()
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            code == BiometricPrompt.ERROR_USER_CANCELED) {
                            showPinDialog(isSetup = false) { ok -> if (ok) { isLocked = false; onSuccess() } else finish() }
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
            showPinDialog(isSetup = false) { ok -> if (ok) { isLocked = false; onSuccess() } else finish() }
        }
    }

    /* -- PIN DIALOG -- */

    private fun themedAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(pos, cv, parent)
                (v as? TextView)?.setTextColor(AppTheme.TXT_PRI)
                (v as? TextView)?.setBackgroundColor(AppTheme.BG_CARD)
                return v
            }
            override fun getDropDownView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(pos, cv, parent)
                (v as? TextView)?.setTextColor(AppTheme.TXT_PRI)
                (v as? TextView)?.setBackgroundColor(AppTheme.BG_CARD)
                (v as? TextView)?.setPadding(32, 20, 32, 20)
                return v
            }
        }
        return adapter
    }
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
            text = if (isSetup) "Elige un PIN" else "Introduce el PIN"
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
            text = if (isSetup) "Seis cifras" else ""
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
            text = "Cancelar"
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
                    val raw = HunterEngine.deriveWallet(mnemonic)
                    // Si el motor devuelve JSON mal formado, un JSONObject pelado
                    // deja la wallet sin ninguna dirección. Se rescatan las
                    // entradas bien formadas antes de rendirse: mejor una wallet
                    // parcial que una vacía sin explicación.
                    val obj = try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        android.util.Log.e("WalletActivity", "deriveWallet devolvió JSON inválido: ${e.message}")
                        val salvaged = JSONObject()
                        Regex("\"([a-z0-9_]+)\"\\s*:\\s*\"?([a-zA-Z0-9]+)\"?")
                            .findAll(raw)
                            .forEach { m -> salvaged.put(m.groupValues[1], m.groupValues[2]) }
                        if (salvaged.length() == 0) throw e
                        runOnUiThread {
                            Toast.makeText(this@WalletActivity,
                                "Aviso: respuesta del motor mal formada, se recuperaron ${salvaged.length()} direcciones",
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
                            "No se pudieron derivar las direcciones: ${e.message}",
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
            text = currentWalletName.ifEmpty { "Cartera" }
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
                startActivity(Intent(this@WalletActivity, MainActivity::class.java))
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
        listOf("Saldo","Historial","Enviar","Recibir").forEachIndexed { i, name ->
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
                btn.setTextColor(AppTheme.BG_DEEP)
                btn.typeface = AppTheme.bold(btn.context)
                btn.background = GradientDrawable().apply {
                    setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                }
                currentTab = i; tabContent.removeAllViews()
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
        actionBar?.addView(actionBtn("Enviar", R.drawable.ic_send, primary = false, last = false)
            .apply { setOnClickListener { tabBtns[2].performClick() } })
        actionBar?.addView(actionBtn("Recibir", R.drawable.ic_receive, primary = true, last = true)
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
            addresses.forEach { (k, addr) ->
                val label = if (k == "wif_0") currentWalletName else (labelMap[k] ?: k)
                if (addr.isEmpty()) { rows.add(BalanceRow("Error", "empty address", -1L, "")); return@forEach }

                val res = BalanceLookup.query(addr, isTestnet)
                if (res == null) { rows.add(BalanceRow(label, addr, -1L, "")); return@forEach }
                val bal = res.sat
                val src = res.source
                if (src == "electrum") usedFallback = true
                totalSat += bal
                rows.add(BalanceRow(label, addr, bal, src))
            }

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
            if (!isWifMode && mnemonic.isNotEmpty()) {
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
                        rows.add(BalanceRow("${HdScanner.purposeLabel(purpose)} cambio [${f.index}]",
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
                val conn = java.net.URL("https://mempool.space/api/v1/prices").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                val js = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                // "USD":(\d+) no captura decimales: con 67432.5 leía 67432.
                price = JSONObject(js).optDouble("USD", 0.0)
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
                if (usedFallback) {
                    ll.addView(TextView(this).apply {
                        text = "mempool.space no respondió; el saldo viene de Electrum"
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                        typeface = AppTheme.body(context)
                        setPadding(0, dp(8), 0, 0)
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
                        text = (if (bal < 0) "sin respuesta" else "%.8f BTC".format(bal / 1e8)) +
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
            text = "Cargando…"; textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
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
            tvHead.text = "Sin direcciones que consultar todavía"
            tvHead.setTextColor(AppTheme.WARN)
            return
        }
        Thread {
            try {
                val conn = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/address/${queryAddrs[0]}/txs" else "https://mempool.space/api/address/${queryAddrs[0]}/txs").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val arr = JSONArray(try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() })
                runOnUiThread {
                    tvHead.text = "${arr.length()} transacciones · ${queryAddrs[0].take(14)}…"
                    if (arr.length() == 0) {
                        ll.addView(TextView(this).apply {
                            text = "Ninguna transacción todavía"
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
                            text = (if (confirmed) "Confirmada" else "Pendiente") +
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
                            sheet.addView(TextView(this).apply{text="Transacción";textSize=AppTheme.SP_TITLE;setTextColor(TXT_PRI);typeface=AppTheme.title(context);setPadding(0,0,0,dp(18))})
                            fun row(k:String,v:String,vc:Int=TXT_PRI){
                                val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(10))}
                                r.addView(TextView(this).apply{text=k;textSize=AppTheme.SP_CAPTION;setTextColor(TXT_SEC);typeface=AppTheme.medium(context);setPadding(0,0,0,dp(5))})
                                val tv=TextView(this).apply{text=v;textSize=AppTheme.SP_CAPTION;setTextColor(vc);typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);cornerRadius=dp(AppTheme.R_INNER).toFloat()};setPadding(dp(14),dp(12),dp(14),dp(12))}
                                r.addView(tv);sheet.addView(r)
                                tv.setOnLongClickListener{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("tx",v));Toast.makeText(this,"Copiado",Toast.LENGTH_SHORT).show();true}
                            }
                            row("Identificador", txidCopy)
                            row("Estado", if(confirmedCopy)"Confirmada" else "Pendiente", if(confirmedCopy)GREEN else AppTheme.WARN)
                            if(receivedCopy>0) row("Recibido","%.8f BTC".format(receivedCopy/1e8),GREEN)
                            if(blockTimeCopy>0) row("Fecha",java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date(blockTimeCopy*1000)))
                            val voutArr=txCopy.getJSONArray("vout")
                            var totalOut=0L; for(j in 0 until voutArr.length()) totalOut+=voutArr.getJSONObject(j).optLong("value",0)
                            row("Total de salida","%.8f BTC".format(totalOut/1e8))
                            val btnRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,0)}
                            val btnExplorer=android.widget.Button(this).apply{text="Ver en el explorador";textSize=AppTheme.SP_BODY;setTextColor(BG_DEEP);typeface=AppTheme.bold(context);isAllCaps=false;stateListAnimator=null;background=GradientDrawable().apply{setColor(AppTheme.ACCENT);cornerRadius=dp(AppTheme.R_INNER).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(8)}}
                            val btnClose=android.widget.Button(this).apply{text="Cerrar";textSize=AppTheme.SP_BODY;setTextColor(TXT_PRI);typeface=AppTheme.medium(context);isAllCaps=false;stateListAnimator=null;background=GradientDrawable().apply{setColor(BG_ELEV);cornerRadius=dp(AppTheme.R_INNER).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(48),1f)}
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
    private fun loadSendTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
        // 9sp para una etiqueta de formulario es ilegible, y el campo a 11sp con
        // 8dp de alto interior no llega ni de lejos al blanco de toque mínimo.
        fun lbl(t: String) = TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(16), 0, dp(7))
        }
        fun fld() = EditText(this).apply {
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = Typeface.MONOSPACE          // es dato: dirección, importe, hex
            setHintTextColor(AppTheme.TXT_MUTED)
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            minHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        ll.addView(lbl("Desde"))
        val spinFrom = Spinner(this).apply {
            adapter = themedAdapter(addresses.keys.map { "$it  ${addresses[it]!!.take(14)}..." })
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            minimumHeight = dp(48)
        }
        ll.addView(spinFrom)
        ll.addView(lbl("Hacia")); val etTo = fld(); ll.addView(etTo)
        ll.addView(lbl("Importe (BTC)")); val etAmt = fld().apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }; ll.addView(etAmt)
        // Venía con "5" escrito, así que todo el mundo enviaba a 5 sat/vB pasara
        // lo que pasara en la mempool. Vacío significa "la que recomiende la red".
        ll.addView(lbl("Comisión en sat/vB — en blanco, la que recomiende la red"))
        val etFee = fld().apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "automática" }
        ll.addView(etFee)


        val btnCoinControl = Button(this).apply {
            text = "Elegir monedas (automático)"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(16) }
        }
        ll.addView(btnCoinControl)

        val tvStatus = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = Typeface.MONOSPACE   // lleva hex y cifras
            setPadding(0, dp(14), 0, 0); setLineSpacing(0f, 1.35f)
        }
        // Era un rectángulo sin esquinas con "BUILD & BROADCAST" dentro, en
        // inglés y en mayúsculas, en el botón que manda el dinero.
        val btnSend = Button(this).apply {
            text = "Revisar y enviar"
            textSize = AppTheme.SP_TITLE; setTextColor(BG_DEEP)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_KEY).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(24) }
        }
        ll.addView(btnSend); ll.addView(tvStatus)
        scroll.addView(ll); tabContent.addView(scroll)


        btnCoinControl.setOnClickListener {
            val fromKey = addresses.keys.toList().getOrNull(spinFrom.selectedItemPosition) ?: return@setOnClickListener
            val fromAddr = addresses[fromKey] ?: return@setOnClickListener
            tvStatus.text = "Consultando las monedas disponibles…"; tvStatus.setTextColor(TXT_SEC)
            Thread {
                try {
                    val url = if(isTestnet) "https://mempool.space/testnet/api/address/$fromAddr/utxo" else "https://mempool.space/api/address/$fromAddr/utxo"
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val utxos = JSONArray(try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() })
                    if (utxos.length() == 0) { runOnUiThread { tvStatus.text = "No UTXOs available"; tvStatus.setTextColor(RED) }; return@Thread }
                    runOnUiThread {
                        val items = Array(utxos.length()) { i ->
                            val u = utxos.getJSONObject(i)
                            val sat = u.getLong("value")
                            "%.8f BTC  ${u.getString("txid").take(12)}...".format(sat/1e8)
                        }
                        val checked = BooleanArray(items.size) { true }
                        selectedUtxos.clear()
                        for (i in 0 until utxos.length()) selectedUtxos.add(utxos.getJSONObject(i))
                        AlertDialog.Builder(this)
                            .setTitle("Select UTXOs (Coin Control)")
                            .setMultiChoiceItems(items, checked) { _, idx, isChecked ->
                                if (isChecked) { if (!selectedUtxos.contains(utxos.getJSONObject(idx))) selectedUtxos.add(utxos.getJSONObject(idx)) }
                                else selectedUtxos.remove(utxos.getJSONObject(idx))
                            }
                            .setPositiveButton("OK") { _, _ ->
                                if (selectedUtxos.isEmpty()) {
                                    btnCoinControl.text = "Coin Control (auto)"
                                    btnCoinControl.setTextColor(CYAN)
                                } else {
                                    val total = selectedUtxos.sumOf { it.getLong("value") }
                                    btnCoinControl.text =
                                        "${selectedUtxos.size} UTXOs seleccionados (%.8f BTC)".format(total/1e8)
                                    btnCoinControl.setTextColor(AMBER)
                                }
                            }
                            .setNeutralButton("Usar todos") { _, _ ->
                                selectedUtxos.clear()
                                btnCoinControl.text = "Coin Control (auto)"
                                btnCoinControl.setTextColor(CYAN)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                } catch(e: Exception) { runOnUiThread { tvStatus.text = motivo(e); tvStatus.setTextColor(RED) } }
            }.start()
        }
        btnSend.setOnClickListener {
            val toAddr = etTo.text.toString().trim()
            val amtBtc = etAmt.text.toString().toDoubleOrNull() ?: 0.0
            // -1 = el usuario no ha puesto nada: que decida la red.
            val feeRate = etFee.text.toString().trim().toIntOrNull()?.takeIf { it > 0 } ?: -1
            val fromKey = addresses.keys.toList().getOrNull(spinFrom.selectedItemPosition) ?: return@setOnClickListener
            val fromAddr = addresses[fromKey] ?: return@setOnClickListener
            if (toAddr.isEmpty() || amtBtc <= 0) { tvStatus.text = "Fill all fields"; tvStatus.setTextColor(RED); return@setOnClickListener }

            // Antes sólo se comprobaba que el campo no estuviera vacío. Un envío a
            // una dirección con un carácter mal tecleado es irreversible.
            val check = BtcAddress.validate(toAddr, isTestnet)
            if (check is BtcAddress.Result.Invalid) {
                tvStatus.text = "Dirección inválida: ${check.reason}"
                tvStatus.setTextColor(RED)
                return@setOnClickListener
            }
            val addrType = (check as BtcAddress.Result.Valid).info.type

            // La confirmación mostraba el importe y "%d sat/vB", que es la tarifa
            // pero no lo que se va a pagar: la comisión real depende de cuántas
            // entradas acabe usando, y eso no se sabía hasta después de aceptar.
            // Ahora se consulta y se calcula TODO antes de preguntar.
            doSend(toAddr, amtBtc, feeRate, fromKey, fromAddr, tvStatus, btnSend, addrType.toString())
        }
    }

    private fun doSend(toAddr: String, amtBtc: Double, feeRateManual: Int,
                       fromKey: String, fromAddr: String,
                       tvStatus: TextView, btnSend: Button, addrType: String = "") {
            tvStatus.text = "Preparando el envío…"; tvStatus.setTextColor(TXT_SEC); btnSend.isEnabled = false
            Thread {
                try {
                    val conn = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/address/$fromAddr/utxo" else "https://mempool.space/api/address/$fromAddr/utxo").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val fetched = JSONArray(try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() })
                    if (fetched.length() == 0) { runOnUiThread { tvStatus.text = "No UTXOs - no balance"; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }

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
                    if (chosen.isEmpty()) { runOnUiThread { tvStatus.text = "Ningún UTXO seleccionado sigue disponible"; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }

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
                        feeRateManual > 0 -> "la que pusiste"
                        red != null       -> "recomendada, ~30 min"
                        else              -> "respaldo: no se pudo consultar"
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
                        CoinSelector.Plan(chosen, f, (tin - amtSat - f).coerceAtLeast(0L), vs, "elegidas a mano")
                    } else {
                        CoinSelector.select(chosen, amtSat, feeRate, inVBytes, outVBytes, chgVBytes)
                    }
                    if (plan == null || plan.chosen.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "Saldo insuficiente para el importe más la comisión"
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
                            tvStatus.text = "Saldo insuficiente: hay $totalIn sat, hacen falta ${amtSat + feeSat}"
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
                                tvStatus.text = "Tipo de dirección no soportado: $fromKey"
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
                            "Cambio a dirección nueva (${changePath.substringAfterLast('/')})"
                        else "Cambio a la dirección de origen (no se pudo consultar la rama)"
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
                        appendLine("Enviar %.8f BTC".format(amtBtc))
                        appendLine("a $toAddr" + if (addrType.isNotEmpty()) " ($addrType)" else "")
                        appendLine()
                        appendLine("Comisión   %,d sat  ·  %d sat/vB (%s)".format(feeSat, feeRate, fuenteTarifa))
                        appendLine("Tamaño     ~%d vB con %d entrada(s) (%s)".format(vsize, plan.chosen.size, plan.reason))
                        appendLine("TOTAL      %.8f BTC".format(totalSat / 1e8))
                        appendLine()
                        appendLine(if (plan.changeSat == 0L)
                            "Sin salida de cambio: el sobrante va a la comisión."
                        else if (changePath != null)
                            "Cambio %.8f BTC a una dirección nueva (%s).".format(plan.changeSat / 1e8, changePath.substringAfterLast('/'))
                        else
                            "Cambio %.8f BTC a la dirección de origen: no se pudo consultar la rama.".format(plan.changeSat / 1e8))
                        appendLine()
                        appendLine("Se envía como reemplazable: podrás subir la comisión si se atasca.")
                        appendLine()
                        append("Una transacción de Bitcoin NO se puede deshacer.")
                    }
                    val seguir = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Revisar envío")
                            .setMessage(resumen)
                            .setCancelable(false)
                            .setNegativeButton("Cancelar") { _, _ -> seguir.offer(false) }
                            .setPositiveButton("Enviar")   { _, _ -> seguir.offer(true) }
                            .show()
                    }
                    if (!seguir.take()) {
                        runOnUiThread {
                            tvStatus.text = "Envío cancelado"; tvStatus.setTextColor(TXT_SEC)
                            btnSend.isEnabled = true
                        }
                        return@Thread
                    }

                    runOnUiThread { tvStatus.text = "Firmando…"; tvStatus.setTextColor(TXT_SEC) }
                    val rawTx = HunterEngine.buildAndSignTx(req)
                    if (rawTx.startsWith("ERROR")) { runOnUiThread { tvStatus.text = rawTx; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }
                    runOnUiThread { tvStatus.text = "Difundiendo…"; tvStatus.setTextColor(TXT_SEC) }
                    val bc = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/tx" else "https://mempool.space/api/tx").openConnection() as java.net.HttpURLConnection
                    bc.requestMethod = "POST"; bc.doOutput = true; bc.setRequestProperty("Content-Type","text/plain")
                    bc.outputStream.write(rawTx.toByteArray())
                    val code = bc.responseCode
                    val resp = try {
                        if (code == 200) bc.inputStream.bufferedReader().readText() else bc.errorStream?.bufferedReader()?.readText() ?: "error"
                    } finally { bc.disconnect() }
                    runOnUiThread { tvStatus.text = if (code == 200) "Sent!\nTXID: $resp" else "Error $code:\n$resp"; tvStatus.setTextColor(if (code == 200) GREEN else RED); btnSend.isEnabled = true }
                } catch(e: Exception) { runOnUiThread { tvStatus.text = motivo(e); tvStatus.setTextColor(RED); btnSend.isEnabled = true } }
            }.start()
    }

    /* -- RECEIVE -- */
    private fun loadReceiveTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); gravity = Gravity.CENTER_HORIZONTAL }
        val spin = Spinner(this).apply {
            adapter = themedAdapter(addresses.keys.map { "$it  ${addresses[it]!!.take(14)}..." })
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        }
        ll.addView(spin)
        // El QR iba sobre blanco puro a hueso, sin margen: un QR necesita zona
        // de silencio alrededor para que las cámaras lo lean con holgura.
        val ivQr = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(236), dp(236)).apply {
                topMargin = dp(28); bottomMargin = dp(20)
            }
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        ll.addView(ivQr)
        val tvAddr = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = Typeface.MONOSPACE   // es una dirección
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(24))
            setLineSpacing(0f, 1.3f)
        }
        ll.addView(tvAddr)
        val btnCopy = Button(this).apply {
            text = "Copiar la dirección"
            textSize = AppTheme.SP_TITLE; setTextColor(BG_DEEP)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_KEY).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        }
        ll.addView(btnCopy)

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

        fun updateQr(addr: String) {
            tvAddr.text = addr
            btnCopy.setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("btc", addr))
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            }
            Thread {
                try {
                    // La única cosa que se usaba de zxing-android-embedded era
                    // BarcodeEncoder.createBitmap(), que es este bucle. Esa
                    // dependencia trae además toda la interfaz de escaneo por
                    // cámara, que la app no usa: se cambia por el bucle y se
                    // queda sólo zxing core, que es quien genera la matriz.
                    runOnUiThread { ivQr.setImageBitmap(qrBitmap("bitcoin:$addr", 512)) }
                } catch(e: Exception) {}
            }.start()
        }
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>, v: View?, pos: Int, id: Long) { updateQr(addresses.values.toList().getOrNull(pos) ?: return) }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }
        if (addresses.isNotEmpty()) updateQr(addresses.values.first())
        scroll.addView(ll); tabContent.addView(scroll)
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
        currentTab = 0
        selectedUtxos.clear()
        // Ejecutar la carga de la nueva wallet
        onReady()
    }

    private fun showWalletSelectorDialog(forceShow: Boolean = false) {
        val wallets = WalletManager.listWallets(this).toMutableList()
        val hasSeed = WalletManager.hasSeed(this)
        val wifPair = WalletManager.loadWif(this)

        // Si solo hay una seed y sin WIF extras, ir directo (solo si no se fuerza el selector)
        val wifList2 = WalletManager.listWifs(this)
        val watchList2 = WalletManager.listWatchers(this)
        if (!forceShow && hasSeed && wallets.isEmpty() && wifList2.isEmpty() && watchList2.isEmpty()) {
            authenticate {
                mnemonic = WalletManager.loadSeed(this) ?: ""
                currentWalletName = "Main Wallet"
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
            text = "Elegir cartera"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0, 0, 0, dp(18))
        })

        fun walletCard(name: String, subtitle: String, color: Int = -1, onClick: () -> Unit) {
            val resolvedColor = if (color == -1) TXT_PRI else color
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(BG_CARD); cornerRadius = dp(AppTheme.R_INNER).toFloat()
                }
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                setOnClickListener { onClick() }
            }
            card.addView(TextView(this).apply {
                text = name; textSize = AppTheme.SP_BODY; setTextColor(resolvedColor)
                typeface = AppTheme.bold(context)
            })
            card.addView(TextView(this).apply {
                text = subtitle; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(0, dp(3), 0, 0)
            })
            sheet.addView(card)
        }

        // Wallet principal BIP39
        var selectorDlg: AlertDialog? = null

        if (hasSeed) {
            walletCard("Cartera principal", "Semilla BIP39, derivación HD", TXT_PRI) {
                selectorDlg?.dismiss()
                switchToWallet {
                    authenticate {
                        mnemonic = WalletManager.loadSeed(this) ?: ""
                        currentWalletName = "Main Wallet"; isWifMode = false
                        loadAddresses()
                    }
                }
            }
        }

        wallets.forEach { (id, name) ->
            walletCard(name, "BIP39 HD Wallet", TXT_PRI) {
                selectorDlg?.dismiss()
                switchToWallet {
                    authenticate {
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
            walletCard(wname, "${wkey.take(8)}...", GREEN) {
                selectorDlg?.dismiss()
                showPinDialog(isSetup = false) { ok ->
                    if (!ok) { showWalletSelectorDialog(); return@showPinDialog }
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
            walletCard(wlabel, waddr.take(20) + "...", CYAN) {
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
        val btnNew   = addBtn("Semilla")
        val btnWif   = addBtn("Clave WIF")
        val btnWatch = addBtn("Observar", last = true)
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
            text = "Importar una clave WIF"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0,0,0,dp(6))
        })
        sheet.addView(TextView(this).apply {
            text = "Pega la clave privada en formato WIF. Empieza por 5, K o L."
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
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        val btnImport = Button(this).apply {
            text = "Importar"; textSize = AppTheme.SP_BODY; setTextColor(BG_DEEP)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancelar"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
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
            showPinDialog(isSetup = true) { ok ->
                if (!ok) return@showPinDialog
                val derivedAddr = try { HunterEngine.wifToAddr(w) } catch(e: Exception) { "" }
                wifKey = w; wifAddr = derivedAddr; isWifMode = true
                currentWalletName = "WIF Wallet"
                WalletManager.saveWif(this, w, derivedAddr)
                loadAddresses(); buildUI()
            }
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this).setTitle("Options")
            .setItems(arrayOf("Switch Wallet","Show seed / WIF","Change PIN","Toggle Testnet","Baúl de copias","Restaurar desde archivo","Delete wallet","Cancel")) { _, pos ->
                when (pos) {
                    0 -> showWalletSelectorDialog(forceShow = true)
                    1 -> authenticate {
                        val msg = if (isWifMode) "WIF: $wifKey" else mnemonic
                        AlertDialog.Builder(this).setTitle("No se la enseñes a nadie").setMessage(msg).setPositiveButton("Entendido", null).show()
                    }
                    2 -> authenticate { showPinDialog(isSetup = true) {} }
                    3 -> { isTestnet = !isTestnet; Toast.makeText(this, if(isTestnet) "Red de pruebas activada" else "Red principal", Toast.LENGTH_SHORT).show() }
                    4 -> showBackupVault()
                    5 -> showRestoreDialog()
                    6 -> AlertDialog.Builder(this).setTitle("¿Borrar la cartera?").setMessage("Asegúrate de tener una copia de la clave: esto no se puede deshacer.")
                            .setPositiveButton("Borrar") { _, _ ->
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
                            .setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    /* -- SETUP DIALOG -- */
    /* -- SETUP DIALOG -- */
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
            text = "Importar una cartera"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0, 0, 0, dp(6))
        })
        layout.addView(TextView(this).apply {
            text = "Escribe las 12 o 24 palabras de tu semilla BIP39."
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(22)); setLineSpacing(0f, 1.3f)
        })
        val tvCount = TextView(this).apply {
            text = "0 / 24 palabras"; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
            gravity = Gravity.END; setPadding(0, 0, 0, dp(4))
        }
        layout.addView(tvCount)
        val etSeed = EditText(this).apply {
            hint = "palabra1 palabra2 palabra3 …"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
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
                tvCount.text = "$cnt / 24 palabras"
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
            text = "Importar"; textSize = AppTheme.SP_BODY; setTextColor(BG_DEEP)
            typeface = AppTheme.display(context)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancelar"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
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
            showPinDialog(isSetup = true) { ok ->
                if (ok) { WalletManager.saveSeed(this, mn); mnemonic = mn; loadAddresses(); buildUI() }
                else finish()
            }
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
            text = "Observar"; textSize = AppTheme.SP_BODY; setTextColor(BG_DEEP)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancelar"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
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
            hint = "Ingresa tu PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(AppTheme.TXT_PRI)
            setHintTextColor(AppTheme.TXT_MUTED)
        }
        root.addView(android.widget.TextView(this).apply {
            text = "PIN para cifrar el backup:"
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("Nueva copia de seguridad")
            .setView(root)
            .setPositiveButton("Crear") { _, _ ->
                val pin = etPin.text.toString()
                if (pin.length < 4) {
                    android.widget.Toast.makeText(this, "PIN muy corto", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Antes se lanzaba el selector de compartir aquí mismo: si lo
                // cerrabas, la copia quedaba en un directorio interno del que
                // nada volvía a hablar. Ahora se guarda en el baúl y desde ahí
                // se comparte, se mira o se restaura.
                val file = WalletManager.exportBackup(this, pin)
                if (file != null) {
                    android.widget.Toast.makeText(this,
                        "Copia guardada en el baúl", android.widget.Toast.LENGTH_SHORT).show()
                    showBackupVault()
                } else {
                    android.widget.Toast.makeText(this,
                        "No hay nada que exportar: ni wallets, ni WIF, ni hallazgos",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Baúl de copias ────────────────────────────────────────────────────────

    /** Lista las copias guardadas. Cada una se comparte, se mira o se restaura. */
    private fun showBackupVault() {
        val copias = BackupStore.list(this)
        val b = AlertDialog.Builder(this)
            .setTitle("Baúl de copias (${copias.size}/${BackupStore.MAX_KEPT})")

        if (copias.isEmpty()) {
            b.setMessage("Todavía no hay ninguna copia.\n\nUna copia lleva las seeds, " +
                         "los WIF, los watchers y los hallazgos del baúl, cifrados con " +
                         "tu PIN. Se conservan las ${BackupStore.MAX_KEPT} más recientes.")
        } else {
            val items = copias.map {
                "${BackupStore.humanDate(it.createdAt)}  ·  ${BackupStore.humanSize(it.bytes)}"
            }.toTypedArray()
            b.setItems(items) { _, i -> showBackupActions(copias[i]) }
        }

        b.setPositiveButton("Crear copia") { _, _ -> showBackupDialog() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Qué hacer con una copia concreta. */
    private fun showBackupActions(info: BackupStore.Info) {
        val acciones = arrayOf(
            "Compartir",
            "Ver contenido",
            "Restaurar esta copia",
            "Borrar")
        AlertDialog.Builder(this)
            .setTitle(BackupStore.humanDate(info.createdAt))
            .setItems(acciones) { _, which ->
                when (which) {
                    0 -> try {
                        startActivity(BackupStore.shareIntent(this, info.file))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this, "No se pudo compartir: ${e.message}",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    1 -> askPinFor("Ver contenido") { pin -> inspectBackupFile(info, pin) }
                    2 -> askPinFor("Restaurar copia") { pin -> restoreFromVault(info, pin) }
                    3 -> confirmDeleteBackup(info)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Pide el PIN con el que se cifró la copia. */
    private fun askPinFor(titulo: String, onPin: (String) -> Unit) {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val etPin = android.widget.EditText(this).apply {
            hint = "PIN de la copia"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(AppTheme.TXT_PRI)
            setHintTextColor(AppTheme.TXT_MUTED)
        }
        root.addView(android.widget.TextView(this).apply {
            // Una copia vieja se abre con el PIN que tuvieras entonces: la clave
            // se deriva del PIN en el momento de crearla, no del PIN actual.
            text = "PIN con el que se creó esta copia:"
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(etPin)
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(root)
            .setPositiveButton("OK") { _, _ -> onPin(etPin.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Descifra y enseña qué trae la copia, sin mostrar ningún secreto. */
    private fun inspectBackupFile(info: BackupStore.Info, pin: String) {
        val resumen = try {
            WalletManager.inspectBackup(pin, info.file.readBytes())
        } catch (e: Exception) { null }

        if (resumen == null) {
            AlertDialog.Builder(this)
                .setTitle("No se pudo abrir")
                .setMessage("PIN incorrecto, o el fichero no es una copia válida.\n\n" +
                            "Recuerda que una copia se abre con el PIN que tenías cuando " +
                            "la creaste.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }

        val detalle = buildString {
            appendLine("Creada: ${BackupStore.humanDate(resumen.createdAt)}")
            appendLine("Formato: v${resumen.version}")
            appendLine("Tamaño: ${BackupStore.humanSize(info.bytes)}")
            appendLine()
            appendLine("Seed principal: ${if (resumen.hasMainSeed) "sí" else "no"}")
            appendLine("Wallets: ${resumen.wallets}")
            appendLine("Claves WIF: ${resumen.wifs}")
            appendLine("Watch-only: ${resumen.watchers}")
            appendLine("Hallazgos: ${resumen.matches}")
            if (resumen.version < 2) {
                appendLine()
                appendLine("Copia antigua: sólo trae wallets. La seed principal, " +
                           "los WIF, los watchers y los hallazgos no se guardaban.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Contenido de la copia")
            .setMessage(detalle)
            .setPositiveButton("Restaurar") { _, _ -> restoreFromVault(info, pin) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Restaura sin pasar por el selector de ficheros. Añade, no reemplaza. */
    private fun restoreFromVault(info: BackupStore.Info, pin: String) {
        val resumen = try {
            WalletManager.inspectBackup(pin, info.file.readBytes())
        } catch (e: Exception) { null }
        if (resumen == null) {
            android.widget.Toast.makeText(this, "PIN incorrecto o copia inválida",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("¿Restaurar esta copia?")
            .setMessage("Se añadirá a lo que ya tienes: ${resumen.wallets} wallet(s), " +
                        "${resumen.wifs} WIF, ${resumen.watchers} watch-only y " +
                        "${resumen.matches} hallazgo(s).\n\n" +
                        (if (resumen.hasMainSeed)
                            "La seed principal de la copia SUSTITUYE a la actual. "
                         else "") +
                        "Si la actual no está en ninguna copia, guárdala antes.")
            .setPositiveButton("Restaurar") { _, _ ->
                val count = WalletManager.importBackup(this, pin, info.file.readBytes())
                if (count >= 0) {
                    android.widget.Toast.makeText(this,
                        "$count elemento(s) restaurados", android.widget.Toast.LENGTH_SHORT).show()
                    buildUI()
                } else {
                    android.widget.Toast.makeText(this,
                        "Error al restaurar", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeleteBackup(info: BackupStore.Info) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar esta copia?")
            .setMessage("${BackupStore.humanDate(info.createdAt)}\n\n" +
                        "No se puede deshacer. Si es la única que tiene tus seeds, " +
                        "se van con ella.")
            .setPositiveButton("Borrar") { _, _ ->
                BackupStore.delete(this, info.file)
                android.widget.Toast.makeText(this, "Copia borrada",
                    android.widget.Toast.LENGTH_SHORT).show()
                showBackupVault()
            }
            .setNegativeButton("Cancelar", null)
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
            hint = "PIN del backup"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(AppTheme.TXT_PRI)
        }
        root.addView(android.widget.TextView(this).apply {
            text = "PIN usado al crear el backup:"
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("Restaurar copia")
            .setView(root)
            .setPositiveButton("Restaurar") { _, _ ->
                val pin = etPin.text.toString()
                val data = contentResolver.openInputStream(uri)?.readBytes() ?: return@setPositiveButton
                val count = WalletManager.importBackup(this, pin, data)
                if (count >= 0) {
                    // Cuenta wallets, seed principal, WIF, watchers y hallazgos:
                    // decir "wallet(s)" a secas confundía cuando el backup
                    // traía sobre todo claves sueltas.
                    android.widget.Toast.makeText(this,
                        "$count elemento(s) restaurados", android.widget.Toast.LENGTH_SHORT).show()
                    buildUI()
                } else {
                    android.widget.Toast.makeText(this,
                        "Error: PIN incorrecto o archivo inválido", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
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
