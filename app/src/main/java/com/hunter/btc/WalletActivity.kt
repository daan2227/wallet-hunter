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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cardBg() = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }

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
            val lockIcon = android.widget.TextView(this).apply {
                text = "\uD83D\uDD12"; textSize = 48f; gravity = Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
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
                cornerRadius = dp(16).toFloat()
                setStroke(1, AppTheme.BORDER_C)
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
            text = if (isSetup) "Create PIN" else "Enter PIN"
            textSize = 16f; setTextColor(TXT_PRI)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(22))
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
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), AppTheme.BORDER_C)
                }
            }
        }
        dots.forEach { pinDisplay.addView(it) }
        sheet.addView(pinDisplay)

        val tvStatus = TextView(this).apply {
            text = if (isSetup) "Choose a 6-digit PIN" else "Enter your PIN"
            textSize = 10f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        val pin = StringBuilder()
        var firstPin = ""
        var dlg: AlertDialog? = null

        fun updateDots() = dots.forEachIndexed { i, d ->
            val bg = d.background as GradientDrawable
            if (i < pin.length) {
                bg.setColor(AMBER)
                bg.setStroke(0, Color.TRANSPARENT)
            } else {
                bg.setColor(Color.TRANSPARENT)
                bg.setStroke(dp(2), AppTheme.BORDER_C)
            }
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
                    textSize = 12f; setTextColor(RED)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    letterSpacing = 0.05f
                } else {
                    textSize = 22f; setTextColor(TXT_PRI)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                }
                background = GradientDrawable().apply {
                    setColor(if (k.isEmpty()) Color.TRANSPARENT else AppTheme.BG_CARD)
                    if (k.isNotEmpty()) setStroke(1, AppTheme.BORDER_C)
                    cornerRadius = dp(10).toFloat()
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
            textSize = 12f; setTextColor(TXT_SEC)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.1f; isAllCaps = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, AppTheme.BORDER_C)
                cornerRadius = dp(6).toFloat()
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

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(16), dp(12), dp(16), dp(12)); gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "<"; textSize = 14f; setTextColor(AMBER)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C) }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "BTC WALLET"; textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        })
        header.addView(Button(this).apply {
            text = ">> Hunter"; textSize = 9f; setTextColor(AMBER)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, AppTheme.BORDER_C)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(4) }
            setOnClickListener {
                startActivity(Intent(this@WalletActivity, MainActivity::class.java))
                finish()
            }
        })
        header.addView(Button(this).apply {
            text = "..."; textSize = 14f; setTextColor(TXT_SEC)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setOnClickListener { showMenu() }
        })
        root.addView(header)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_CARD) }
        val tabBtns = mutableListOf<Button>()
        listOf("Balance","History","Send","Receive").forEachIndexed { i, name ->
            val btn = Button(this).apply {
                text = name; textSize = 10f
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setTextColor(if (i == 0) AMBER else TXT_SEC)
                background = GradientDrawable().apply { setColor(if (i == 0) BG_ELEV else BG_CARD) }
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            }
            tabBtns.add(btn); tabs.addView(btn)
        }
        tabBtns.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                tabBtns.forEach { b -> b.setTextColor(TXT_SEC); b.background = GradientDrawable().apply { setColor(BG_CARD) } }
                btn.setTextColor(AMBER); btn.background = GradientDrawable().apply { setColor(BG_ELEV) }
                currentTab = i; tabContent.removeAllViews()
                when (i) { 0 -> loadBalanceTab(); 1 -> loadHistoryTab(); 2 -> loadSendTab(); 3 -> loadReceiveTab() }
            }
        }
        root.addView(tabs)

        tabContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(tabContent)
        setContentView(root)
        loadBalanceTab()
    }

    /* -- BALANCE -- */
    private fun loadBalanceTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        val tvTotal = TextView(this).apply {
            text = "Loading..."; textSize = 28f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(4))
        }
        val tvFiat = TextView(this).apply { text = ""; textSize = 12f; setTextColor(TXT_SEC); gravity = Gravity.CENTER; setPadding(0,0,0,dp(16)) }
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
                val btcText = "%.8f BTC".format(tot / 1e8)
                val fiatText = if (pr > 0) "~ ${"%.2f".format(tot / 1e8 * pr)} USD" else ""
                tvTotal.text = if (balanceVisible) btcText else "********"
                tvTotal.setTextColor(if (tot > 0) GREEN else AMBER)
                if (pr > 0) tvFiat.text = if (balanceVisible) fiatText else "******"
                tvTotal.setOnClickListener {
                    balanceVisible = !balanceVisible
                    tvTotal.text = if (balanceVisible) btcText else "********"
                    tvFiat.text  = if (balanceVisible) fiatText else "******"
                }
                if (usedFallback) {
                    ll.addView(TextView(this).apply {
                        text = "⚠ mempool.space no respondió; saldo obtenido vía Electrum"
                        textSize = 9f; setTextColor(AMBER)
                        typeface = Typeface.create("monospace", Typeface.NORMAL)
                        setPadding(0, dp(6), 0, 0)
                    })
                }
                rows.forEach { (lbl, addr, bal, src) ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg()
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                    }
                    card.addView(TextView(this).apply { text = lbl; textSize = 9f; setTextColor(TXT_SEC) })
                    card.addView(TextView(this).apply { text = addr; textSize = 9f; setTextColor(TXT_PRI); typeface = Typeface.MONOSPACE })
                    card.addView(TextView(this).apply {
                        text = (if (bal < 0) "error" else "%.8f BTC".format(bal / 1e8)) +
                               (if (src == "electrum") "  · electrum" else "")
                        textSize = 12f
                        setTextColor(when { bal > 0 -> GREEN; bal == 0L -> TXT_MUTED; else -> RED })
                        typeface = Typeface.create("monospace", Typeface.BOLD)
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
        val tvHead = TextView(this).apply { text = "Loading..."; textSize = 11f; setTextColor(TXT_SEC); setPadding(0,dp(12),0,dp(8)) }
        ll.addView(tvHead); scroll.addView(ll); tabContent.addView(scroll)

        // ifEmpty { return } salía dejando el "Loading..." puesto para siempre,
        // sin mensaje ni error. Ocurre si deriveWallet falló (su catch llama a
        // buildUI con el mapa vacío) o si se abre la pestaña antes de que la
        // derivación asíncrona haya terminado.
        val queryAddrs = addresses.values.toList()
        if (queryAddrs.isEmpty()) {
            tvHead.text = "Sin direcciones que consultar todavía"
            tvHead.setTextColor(AMBER)
            return
        }
        Thread {
            try {
                val conn = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/address/${queryAddrs[0]}/txs" else "https://mempool.space/api/address/${queryAddrs[0]}/txs").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val arr = JSONArray(try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() })
                runOnUiThread {
                    tvHead.text = "${arr.length()} txs  (${queryAddrs[0].take(14)}...)"
                    if (arr.length() == 0) { ll.addView(TextView(this).apply { text = "No transactions"; setTextColor(TXT_MUTED); textSize = 11f }); return@runOnUiThread }
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
                            setPadding(dp(12),dp(10),dp(12),dp(10))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                        }
                        card.addView(TextView(this).apply { text = txid.take(22)+"..."; textSize = 9f; setTextColor(CYAN); typeface = Typeface.MONOSPACE })
                        card.addView(TextView(this).apply { text = if (confirmed) "Confirmed" else "Pending"; textSize = 9f; setTextColor(if (confirmed) GREEN else AMBER) })
                        if (received > 0) card.addView(TextView(this).apply { text = "+%.8f BTC".format(received/1e8); textSize = 12f; setTextColor(GREEN); typeface = Typeface.create("monospace",Typeface.BOLD) })
                        if (blockTime > 0) card.addView(TextView(this).apply { text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(blockTime*1000)); textSize = 9f; setTextColor(TXT_MUTED) })
                        /* Click -> detalle de transaccion */
                        val txCopy = tx; val txidCopy = txid; val receivedCopy = received; val confirmedCopy = confirmed; val blockTimeCopy = blockTime
                        card.isClickable = true
                        card.setOnClickListener {
                            val sheet = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=GradientDrawable().apply{setColor(BG_PANEL);cornerRadius=dp(16).toFloat();setStroke(1,BORDER_C)}; setPadding(dp(20),dp(20),dp(20),dp(24)) }
                            sheet.addView(TextView(this).apply{text="Transaction";textSize=14f;setTextColor(AMBER);typeface=Typeface.create("sans-serif-black",Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,0,0,dp(14))})
                            fun row(k:String,v:String,vc:Int=TXT_PRI){
                                val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(10))}
                                r.addView(TextView(this).apply{text=k;textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.1f})
                                val tv=TextView(this).apply{text=v;textSize=11f;setTextColor(vc);typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,BORDER_C);cornerRadius=dp(6).toFloat()};setPadding(dp(10),dp(7),dp(10),dp(7))}
                                r.addView(tv);sheet.addView(r)
                                tv.setOnLongClickListener{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("tx",v));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();true}
                            }
                            row("TXID", txidCopy, CYAN)
                            row("STATUS", if(confirmedCopy)"Confirmed" else "Pending", if(confirmedCopy)GREEN else AMBER)
                            if(receivedCopy>0) row("RECEIVED","%.8f BTC".format(receivedCopy/1e8),GREEN)
                            if(blockTimeCopy>0) row("DATE",java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.US).format(java.util.Date(blockTimeCopy*1000)))
                            val voutArr=txCopy.getJSONArray("vout")
                            var totalOut=0L; for(j in 0 until voutArr.length()) totalOut+=voutArr.getJSONObject(j).optLong("value",0)
                            row("TOTAL OUT","%.8f BTC".format(totalOut/1e8))
                            val btnRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,0)}
                            val btnExplorer=android.widget.Button(this).apply{text="View on Explorer";textSize=11f;setTextColor(android.graphics.Color.BLACK);typeface=Typeface.create("sans-serif-black",Typeface.BOLD);background=GradientDrawable().apply{setColor(AMBER);cornerRadius=dp(8).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)}}
                            val btnClose=android.widget.Button(this).apply{text="Close";textSize=11f;setTextColor(TXT_SEC);typeface=Typeface.create("sans-serif-black",Typeface.BOLD);background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(8).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f)}
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
            } catch(e: Exception) { runOnUiThread { tvHead.text = "Error: ${e.message}"; tvHead.setTextColor(RED) } }
        }.start()
    }

    /* -- SEND -- */
    private fun loadSendTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
        fun lbl(t: String) = TextView(this).apply { text = t; textSize = 9f; setTextColor(TXT_SEC); setPadding(0,dp(10),0,dp(3)) }
        fun fld() = EditText(this).apply { setTextColor(TXT_PRI); textSize = 11f; typeface = Typeface.MONOSPACE; background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1,BORDER_C) }; setPadding(dp(10),dp(8),dp(10),dp(8)) }

        ll.addView(lbl("From address"))
        val spinFrom = Spinner(this).apply {
            adapter = themedAdapter(addresses.keys.map { "$it  ${addresses[it]!!.take(14)}..." })
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
        }
        ll.addView(spinFrom)
        ll.addView(lbl("To address")); val etTo = fld(); ll.addView(etTo)
        ll.addView(lbl("Amount (BTC)")); val etAmt = fld().apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }; ll.addView(etAmt)
        // Venía con "5" escrito, así que todo el mundo enviaba a 5 sat/vB pasara
        // lo que pasara en la mempool. Vacío significa "la que recomiende la red".
        ll.addView(lbl("Comisión (sat/vB) — vacío = automática"))
        val etFee = fld().apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "automática" }
        ll.addView(etFee)


        val btnCoinControl = Button(this).apply {
            text = "Coin Control (auto)"; textSize = 9f; setTextColor(CYAN)
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, AppTheme.BORDER_C) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(8) }
        }
        ll.addView(btnCoinControl)

        val tvStatus = TextView(this).apply { text = ""; textSize = 10f; setTextColor(TXT_SEC); typeface = Typeface.MONOSPACE; setPadding(0,dp(8),0,0); setLineSpacing(0f,1.3f) }
        val btnSend = Button(this).apply {
            text = "BUILD & BROADCAST"; textSize = 12f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(AMBER) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) }
        }
        ll.addView(btnSend); ll.addView(tvStatus)
        scroll.addView(ll); tabContent.addView(scroll)


        btnCoinControl.setOnClickListener {
            val fromKey = addresses.keys.toList().getOrNull(spinFrom.selectedItemPosition) ?: return@setOnClickListener
            val fromAddr = addresses[fromKey] ?: return@setOnClickListener
            tvStatus.text = "Loading UTXOs..."; tvStatus.setTextColor(TXT_SEC)
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
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } catch(e: Exception) { runOnUiThread { tvStatus.text = "Error: ${e.message}"; tvStatus.setTextColor(RED) } }
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
                } catch(e: Exception) { runOnUiThread { tvStatus.text = "Error: ${e.message}"; tvStatus.setTextColor(RED); btnSend.isEnabled = true } }
            }.start()
    }

    /* -- RECEIVE -- */
    private fun loadReceiveTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); gravity = Gravity.CENTER_HORIZONTAL }
        val spin = Spinner(this).apply {
            adapter = themedAdapter(addresses.keys.map { "$it  ${addresses[it]!!.take(14)}..." })
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }
        ll.addView(spin)
        val ivQr = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220),dp(220)).apply { topMargin = dp(16); bottomMargin = dp(12) }
            setBackgroundColor(Color.WHITE); setPadding(dp(8),dp(8),dp(8),dp(8))
        }
        ll.addView(ivQr)
        val tvAddr = TextView(this).apply { text = ""; textSize = 10f; setTextColor(TXT_PRI); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setPadding(0,dp(8),0,dp(8)) }
        ll.addView(tvAddr)
        val btnCopy = Button(this).apply { text = "Copy Address"; textSize = 11f; setTextColor(Color.BLACK); background = GradientDrawable().apply { setColor(AMBER) }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)) }
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
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(16).toFloat(); setStroke(1, BORDER_C) }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(sheet)

        sheet.addView(TextView(this).apply {
            text = "Select Wallet"; textSize = 17f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16))
        })

        fun walletCard(name: String, subtitle: String, color: Int = -1, onClick: () -> Unit) {
            val resolvedColor = if (color == -1) TXT_PRI else color
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(10).toFloat() }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                setOnClickListener { onClick() }
            }
            card.addView(TextView(this).apply { text = name; textSize = 13f; setTextColor(resolvedColor); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) })
            card.addView(TextView(this).apply { text = subtitle; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.NORMAL) })
            sheet.addView(card)
        }

        // Wallet principal BIP39
        var selectorDlg: AlertDialog? = null

        if (hasSeed) {
            walletCard("Main Wallet", "BIP39 HD Wallet", TXT_PRI) {
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
        val btnNew = Button(this).apply {
            text = "+ Seed"; textSize = 11f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(7).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        }
        val btnWatch = Button(this).apply {
            text = "+ Watch"; textSize = 11f; setTextColor(CYAN)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, CYAN); cornerRadius = dp(7).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        }
        val btnWif = Button(this).apply {
            text = "+ WIF"; textSize = 11f; setTextColor(TXT_PRI)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(7).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }
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
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(16).toFloat(); setStroke(1, BORDER_C) }
            setPadding(dp(22), dp(22), dp(22), dp(24))
        }
        sheet.addView(TextView(this).apply {
            text = "Import WIF Key"; textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0,0,0,dp(6))
        })
        sheet.addView(TextView(this).apply {
            text = "Paste your WIF private key (starts with 5, K or L)"
            textSize = 9f; setTextColor(TXT_MUTED); gravity = Gravity.CENTER
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0,0,0,dp(14))
        })
        val etWif = EditText(this).apply {
            hint = "5HueCGU8..."; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(10).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12)); textSize = 12f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        sheet.addView(etWif)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        val btnImport = Button(this).apply {
            text = "Import"; textSize = 12f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = 12f; setTextColor(TXT_SEC)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
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
            .setItems(arrayOf("Switch Wallet","Show seed / WIF","Change PIN","Toggle Testnet","🗄 Baúl de copias","Restaurar desde archivo","Delete wallet","Cancel")) { _, pos ->
                when (pos) {
                    0 -> showWalletSelectorDialog(forceShow = true)
                    1 -> authenticate {
                        val msg = if (isWifMode) "WIF: $wifKey" else mnemonic
                        AlertDialog.Builder(this).setTitle("Keep Private!").setMessage(msg).setPositiveButton("OK", null).show()
                    }
                    2 -> authenticate { showPinDialog(isSetup = true) {} }
                    3 -> { isTestnet = !isTestnet; Toast.makeText(this, if(isTestnet) "Testnet ON" else "Mainnet", Toast.LENGTH_SHORT).show() }
                    4 -> showBackupVault()
                    5 -> showRestoreDialog()
                    6 -> AlertDialog.Builder(this).setTitle("Delete wallet?").setMessage("Make sure you have your key backed up.")
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
    private fun showSetupDialog() {
        val scroll = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG_PANEL); cornerRadius = dp(16).toFloat(); setStroke(1, BORDER_C)
            }
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        scroll.addView(layout)
        layout.addView(TextView(this).apply {
            text = "Import Wallet"; textSize = 18f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6))
        })
        layout.addView(TextView(this).apply {
            text = "Enter your 12 or 24 word BIP39 seed phrase"
            textSize = 10f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20))
        })
        val tvCount = TextView(this).apply {
            text = "0 / 24 words"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.END; setPadding(0, 0, 0, dp(4))
        }
        layout.addView(tvCount)
        val etSeed = EditText(this).apply {
            hint = "word1 word2 word3 ..."; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply {
                setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12)); textSize = 13f; minLines = 3; maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        layout.addView(etSeed)
        val suggestScroll = android.widget.HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(BG_DEEP); cornerRadius = dp(8).toFloat(); setStroke(1, BORDER_C)
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
                    text = word; textSize = 10f; setTextColor(TXT_PRI)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        setColor(BG_CARD); setStroke(1, AMBER); cornerRadius = dp(6).toFloat()
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
            textSize = 9f; setTextColor(TXT_MUTED); gravity = Gravity.CENTER
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0, dp(16), 0, dp(4))
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        val btnNext = Button(this).apply {
            text = "Import"; textSize = 13f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = 13f; setTextColor(TXT_SEC)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(8).toFloat() }
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
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(16).toFloat(); setStroke(1, AMBER) }
            setPadding(dp(22), dp(22), dp(22), dp(24))
        }
        sheet.addView(TextView(this).apply {
            text = "Watch Address"; textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0,0,0,dp(6))
        })
        sheet.addView(TextView(this).apply {
            text = "Monitor any Bitcoin address (read-only, no private key needed)"
            textSize = 9f; setTextColor(TXT_MUTED); gravity = Gravity.CENTER
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0,0,0,dp(14))
        })
        val etAddr = EditText(this).apply {
            hint = "bc1q... or 1... or 3..."; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(10).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12)); textSize = 12f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val etLabel = EditText(this).apply {
            hint = "Label (e.g. Puzzle #71)"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(10).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12)); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        sheet.addView(etAddr); sheet.addView(etLabel)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        val btnAdd = Button(this).apply {
            text = "Watch"; textSize = 12f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = 12f; setTextColor(TXT_SEC)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
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
            setTextColor(0xFFF2F2F2.toInt())
            setHintTextColor(0xFF8A8A8A.toInt())
        }
        root.addView(android.widget.TextView(this).apply {
            text = "PIN para cifrar el backup:"
            setTextColor(0xFFF2F2F2.toInt()); textSize = 13f
            setPadding(0, 0, 0, 8)
        })
        root.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("📦 Nueva copia de seguridad")
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
                        "✓ Copia guardada en el baúl", android.widget.Toast.LENGTH_SHORT).show()
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
            .setTitle("🗄 Baúl de copias (${copias.size}/${BackupStore.MAX_KEPT})")

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
            "📤 Compartir",
            "🔍 Ver contenido",
            "📥 Restaurar esta copia",
            "🗑 Borrar")
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
            setTextColor(0xFFF2F2F2.toInt())
            setHintTextColor(0xFF8A8A8A.toInt())
        }
        root.addView(android.widget.TextView(this).apply {
            // Una copia vieja se abre con el PIN que tuvieras entonces: la clave
            // se deriva del PIN en el momento de crearla, no del PIN actual.
            text = "PIN con el que se creó esta copia:"
            setTextColor(0xFFF2F2F2.toInt()); textSize = 13f
            setPadding(0, 0, 0, 8)
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
                .setPositiveButton("OK", null)
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
                        "✓ $count elemento(s) restaurados", android.widget.Toast.LENGTH_SHORT).show()
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
            setTextColor(0xFFF2F2F2.toInt())
        }
        root.addView(android.widget.TextView(this).apply {
            text = "PIN usado al crear el backup:"
            setTextColor(0xFFF2F2F2.toInt()); textSize = 13f
            setPadding(0, 0, 0, 8)
        })
        root.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("📥 Restaurar Backup")
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
                        "✓ $count elemento(s) restaurados", android.widget.Toast.LENGTH_SHORT).show()
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
