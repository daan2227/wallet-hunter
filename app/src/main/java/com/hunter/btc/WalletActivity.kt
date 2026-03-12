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

class WalletActivity : FragmentActivity() {

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
    private var balanceVisible = true
    private var isLocked = false
    private var lastInteraction = 0L
    private val AUTO_LOCK_MS = 2 * 60 * 1000L
    private lateinit var tabContent: FrameLayout
    private val labelMap = mapOf(
        "p2pkh_0" to "P2PKH [0]", "p2pkh_1" to "P2PKH [1]", "p2pkh_2" to "P2PKH [2]",
        "p2sh_0"  to "P2SH  [0]",
        "p2wpkh_0" to "WPKH  [0]", "p2wpkh_1" to "WPKH  [1]"
    )

    /* -- LIFECYCLE -- */
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        lastInteraction = System.currentTimeMillis()
        // Check if coming from puzzle match with WIF
        val intentWif = intent.getStringExtra("WIF_KEY") ?: ""
        val intentAddr = intent.getStringExtra("WIF_ADDR") ?: ""
        if (intentWif.isNotEmpty()) {
            wifKey = intentWif; wifAddr = intentAddr; isWifMode = true
            currentWalletName = "Puzzle Match"
            WalletManager.saveWif(this, intentWif, intentAddr)
            loadAddresses(); buildUI(); return
        }
        // Check saved WIF
        val savedWif = WalletManager.loadWif(this)
        val wallets = WalletManager.listWallets(this)
        val hasSeed = WalletManager.hasSeed(this)
        if (!hasSeed && savedWif == null && wallets.isEmpty()) {
            showWalletSelectorDialog(); return
        }
        showWalletSelectorDialog()
    }

    override fun onResume() {
        super.onResume()
        if (isLocked && WalletManager.hasSeed(this)) {
            authenticate {
                mnemonic = WalletManager.loadSeed(this) ?: ""
                isLocked = false
            }
        }
        lastInteraction = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        if (System.currentTimeMillis() - lastInteraction > AUTO_LOCK_MS) isLocked = true
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = System.currentTimeMillis()
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
            addresses = mutableMapOf("wif_0" to wifAddr)
            return
        }
        if (mnemonic.isNotEmpty()) {
            Thread {
                try {
                    val json = HunterEngine.deriveWallet(mnemonic)
                    val inner = json.trim().removePrefix("{").removeSuffix("}")
                    val entries = mutableListOf<Pair<String,String>>()
                    inner.split(",").forEach { part ->
                        val kv = part.trim().split(":")
                        if (kv.size >= 2) {
                            val k = kv[0].trim().trim('"', ' ')
                            val v = kv[1].trim().trim('"', ' ')
                            if (k.isNotEmpty() && v.isNotEmpty()) entries.add(Pair(k, v))
                        }
                    }
                    val map = mutableMapOf<String, String>()
                    entries.forEach { (k, v) -> map[k] = v }
                    runOnUiThread { addresses = map; buildUI() }
                } catch (e: Exception) {
                    runOnUiThread { buildUI() }
                }
            }.start()
        }
    }

    private fun buildUI() {
        title = currentWalletName.ifEmpty { "Wallet" }
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
            val rows = mutableListOf<Triple<String,String,Long>>()
            addresses.forEach { (k, addr) ->
                try {
                    val conn = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/address/$addr" else "https://mempool.space/api/address/$addr").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val js = conn.inputStream.bufferedReader().readText()
                    val funded = Regex("\"funded_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val spent  = Regex("\"spent_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val bal = funded - spent; totalSat += bal
                    rows.add(Triple(labelMap[k] ?: k, addr, bal))
                } catch(e: Exception) { rows.add(Triple(labelMap[k] ?: k, addr, -1L)) }
            }
            var price = 0.0
            try {
                val conn = java.net.URL("https://mempool.space/api/v1/prices").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                val js = conn.inputStream.bufferedReader().readText()
                price = Regex("\"USD\":(\\d+)").find(js)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
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
                rows.forEach { (lbl, addr, bal) ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg()
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                    }
                    card.addView(TextView(this).apply { text = lbl; textSize = 9f; setTextColor(TXT_SEC) })
                    card.addView(TextView(this).apply { text = addr; textSize = 9f; setTextColor(TXT_PRI); typeface = Typeface.MONOSPACE })
                    card.addView(TextView(this).apply {
                        text = if (bal < 0) "error" else "%.8f BTC".format(bal / 1e8)
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

        val queryAddrs = addresses.values.toList().ifEmpty { return }
        Thread {
            try {
                val conn = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/address/${queryAddrs[0]}/txs" else "https://mempool.space/api/address/${queryAddrs[0]}/txs").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
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
        ll.addView(lbl("Fee rate (sat/vB)")); val etFee = fld().apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("5") }; ll.addView(etFee)


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
                    val utxos = JSONArray(conn.inputStream.bufferedReader().readText())
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
                                val total = selectedUtxos.sumOf { it.getLong("value") }
                                btnCoinControl.text = "${selectedUtxos.size} UTXOs selected (%.8f BTC)".format(total/1e8)
                                btnCoinControl.setTextColor(AMBER)
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
            val feeRate = etFee.text.toString().toIntOrNull() ?: 5
            val fromKey = addresses.keys.toList().getOrNull(spinFrom.selectedItemPosition) ?: return@setOnClickListener
            val fromAddr = addresses[fromKey] ?: return@setOnClickListener
            if (toAddr.isEmpty() || amtBtc <= 0) { tvStatus.text = "Fill all fields"; tvStatus.setTextColor(RED); return@setOnClickListener }
            tvStatus.text = "Fetching UTXOs..."; tvStatus.setTextColor(TXT_SEC); btnSend.isEnabled = false
            Thread {
                try {
                    val conn = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/address/$fromAddr/utxo" else "https://mempool.space/api/address/$fromAddr/utxo").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val utxos = JSONArray(conn.inputStream.bufferedReader().readText())
                    if (utxos.length() == 0) { runOnUiThread { tvStatus.text = "No UTXOs - no balance"; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }
                    val amtSat = (amtBtc * 1e8).toLong()
                    val feeSat = (feeRate * (148 * utxos.length() + 34 * 2 + 10)).toLong()
                    var utxoArr = "["; var totalIn = 0L
                    for (i in 0 until utxos.length()) {
                        val u = utxos.getJSONObject(i); val txid = u.getString("txid"); val vout = u.getInt("vout"); val v = u.getLong("value")
                        totalIn += v; if (i > 0) utxoArr += ","; utxoArr += "{\"txid\":\"$txid\",\"vout\":$vout,\"amount\":$v}"
                    }
                    utxoArr += "]"
                    if (totalIn < amtSat + feeSat) { runOnUiThread { tvStatus.text = "Insufficient: have ${totalIn}sat need ${amtSat+feeSat}sat"; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }
                    val pathStr = when { fromKey.startsWith("p2pkh") -> "m/44'/0'/0'/0/${fromKey.last()}"; fromKey.startsWith("p2sh") -> "m/49'/0'/0'/0/0"; else -> "m/84'/0'/0'/0/${fromKey.last()}" }
                    val req = "{\"mnemonic\":\"$mnemonic\",\"path\":\"$pathStr\",\"utxos\":$utxoArr,\"to\":\"$toAddr\",\"amount\":$amtSat,\"fee\":$feeSat}"
                    val rawTx = HunterEngine.buildAndSignTx(req)
                    if (rawTx.startsWith("ERROR")) { runOnUiThread { tvStatus.text = rawTx; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }
                    runOnUiThread { tvStatus.text = "Broadcasting..."; tvStatus.setTextColor(TXT_SEC) }
                    val bc = java.net.URL(if(isTestnet) "https://mempool.space/testnet/api/tx" else "https://mempool.space/api/tx").openConnection() as java.net.HttpURLConnection
                    bc.requestMethod = "POST"; bc.doOutput = true; bc.setRequestProperty("Content-Type","text/plain")
                    bc.outputStream.write(rawTx.toByteArray())
                    val code = bc.responseCode
                    val resp = if (code == 200) bc.inputStream.bufferedReader().readText() else bc.errorStream?.bufferedReader()?.readText() ?: "error"
                    runOnUiThread { tvStatus.text = if (code == 200) "Sent!\nTXID: $resp" else "Error $code:\n$resp"; tvStatus.setTextColor(if (code == 200) GREEN else RED); btnSend.isEnabled = true }
                } catch(e: Exception) { runOnUiThread { tvStatus.text = "Error: ${e.message}"; tvStatus.setTextColor(RED); btnSend.isEnabled = true } }
            }.start()
        }
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

        fun updateQr(addr: String) {
            tvAddr.text = addr
            btnCopy.setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("btc", addr))
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            }
            Thread {
                try {
                    val bm = com.journeyapps.barcodescanner.BarcodeEncoder().createBitmap(
                        com.google.zxing.qrcode.QRCodeWriter().encode("bitcoin:$addr", com.google.zxing.BarcodeFormat.QR_CODE, 512, 512))
                    runOnUiThread { ivQr.setImageBitmap(bm) }
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
    private fun showWalletSelectorDialog() {
        val wallets = WalletManager.listWallets(this).toMutableList()
        val hasSeed = WalletManager.hasSeed(this)
        val wifPair = WalletManager.loadWif(this)

        // Si solo hay una seed y sin WIF extras, ir directo
        if (hasSeed && wallets.isEmpty() && wifPair == null) {
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

        fun walletCard(name: String, subtitle: String, color: Int, onClick: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(10).toFloat() }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                setOnClickListener { onClick() }
            }
            card.addView(TextView(this).apply { text = name; textSize = 13f; setTextColor(c); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) })
            card.addView(TextView(this).apply { text = subtitle; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.NORMAL) })
            sheet.addView(card)
        }

        // Wallet principal BIP39
        var selectorDlg: AlertDialog? = null

        if (hasSeed) {
            walletCard("Main Wallet", "BIP39 HD Wallet") {
                selectorDlg?.dismiss()
                authenticate {
                    mnemonic = WalletManager.loadSeed(this) ?: ""
                    currentWalletName = "Main Wallet"; isWifMode = false
                    loadAddresses()
                }
            }
        }

        wallets.forEach { (id, name) ->
            walletCard(name, "BIP39 HD Wallet") {
                selectorDlg?.dismiss()
                authenticate {
                    mnemonic = WalletManager.loadWalletSeed(this, id) ?: ""
                    currentWalletId = id; currentWalletName = name; isWifMode = false
                    loadAddresses()
                }
            }
        }

        if (wifPair != null) {
            walletCard("Puzzle Match", "WIF Key: ${wifPair.first.take(8)}...", GREEN) {
                selectorDlg?.dismiss()
                wifKey = wifPair.first; wifAddr = wifPair.second
                currentWalletName = "Puzzle Match"; isWifMode = true
                loadAddresses()
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
        val btnWif = Button(this).apply {
            text = "+ WIF"; textSize = 11f; setTextColor(TXT_PRI)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(7).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }
        btnRow.addView(btnNew); btnRow.addView(btnWif)
        sheet.addView(btnRow)

        val dlg = AlertDialog.Builder(this).setView(scroll).create()
        selectorDlg = dlg
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()

        btnNew.setOnClickListener { dlg.dismiss(); showSetupDialog() }
        btnWif.setOnClickListener { dlg.dismiss(); showWifImportDialog() }


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
        val dlg = AlertDialog.Builder(this).setView(sheet).create()
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); showWalletSelectorDialog() }
        btnImport.setOnClickListener {
            val w = etWif.text.toString().trim()
            if (w.length < 50) { Toast.makeText(this, "Invalid WIF key", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dlg.dismiss()
            wifKey = w; wifAddr = ""; isWifMode = true
            currentWalletName = "WIF Wallet"
            WalletManager.saveWif(this, w, "")
            loadAddresses(); buildUI()
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this).setTitle("Options")
            .setItems(arrayOf("Switch Wallet","Show seed / WIF","Change PIN","Toggle Testnet","Delete wallet","Cancel")) { _, pos ->
                when (pos) {
                    0 -> showWalletSelectorDialog()
                    1 -> authenticate {
                        val msg = if (isWifMode) "WIF: $wifKey" else mnemonic
                        AlertDialog.Builder(this).setTitle("Keep Private!").setMessage(msg).setPositiveButton("OK", null).show()
                    }
                    2 -> authenticate { showPinDialog(isSetup = true) {} }
                    3 -> { isTestnet = !isTestnet; Toast.makeText(this, if(isTestnet) "Testnet ON" else "Mainnet", Toast.LENGTH_SHORT).show() }
                    4 -> AlertDialog.Builder(this).setTitle("Delete wallet?").setMessage("Make sure you have your key backed up.")
                            .setPositiveButton("Delete") { _, _ -> WalletManager.clearSeed(this); finish() }
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
        val dlg = AlertDialog.Builder(this).setView(scroll).create()
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                      android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); finish() }
        btnNext.setOnClickListener {
            val mn = etSeed.text.toString().trim()
            if (mn.split(" ").size < 12) { Toast.makeText(this, "Need 12+ words", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dlg.dismiss()
            showPinDialog(isSetup = true) { ok ->
                if (ok) { WalletManager.saveSeed(this, mn); mnemonic = mn; loadAddresses(); buildUI() }
                else finish()
            }
        }
    }
}
