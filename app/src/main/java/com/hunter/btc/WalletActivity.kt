package com.hunter.btc

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import android.text.InputType
import org.json.JSONObject
import org.json.JSONArray

class WalletActivity : FragmentActivity() {
    private val AMBER     = Color.parseColor("#f59e0b")
    private val GREEN     = Color.parseColor("#10d97a")
    private val RED       = Color.parseColor("#ef4444")
    private val CYAN      = Color.parseColor("#38bdf8")
    private val BG_DEEP   = Color.parseColor("#080b10")
    private val BG_PANEL  = Color.parseColor("#0d1117")
    private val BG_CARD   = Color.parseColor("#111822")
    private val BG_ELEV   = Color.parseColor("#162030")
    private val TXT_PRI   = Color.parseColor("#e2e8f0")
    private val TXT_SEC   = Color.parseColor("#64748b")
    private val TXT_MUTED = Color.parseColor("#334155")
    private val BORDER_C  = Color.parseColor("#1a2332")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cardBg() = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }

    private var lastInteraction = System.currentTimeMillis()
    private val AUTO_LOCK_MS = 2 * 60 * 1000L // 2 minutos
    private var isLocked = false
    private var balanceVisible = true
    private var mnemonic: String = ""
    private var addresses = mutableMapOf<String, String>()
    private var currentTab = 0
    private lateinit var tabContent: FrameLayout
    private val labelMap = mapOf(
        "p2pkh_0" to "P2PKH [0]","p2pkh_1" to "P2PKH [1]","p2pkh_2" to "P2PKH [2]",
        "p2sh_0"  to "P2SH  [0]",
        "p2wpkh_0" to "WPKH  [0]","p2wpkh_1" to "WPKH  [1]"
    )


    override fun onResume() {
        super.onResume()
        if (isLocked && WalletManager.hasSeed(this)) {
            showBiometricOrPin {
                mnemonic = WalletManager.loadSeed(this) ?: ""
                isLocked = false
            }
        }
        lastInteraction = System.currentTimeMillis()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        val elapsed = System.currentTimeMillis() - lastInteraction
        if (elapsed > AUTO_LOCK_MS) isLocked = true
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (!WalletManager.hasSeed(this)) { showSetupDialog(); return }
        showBiometricOrPin {
            mnemonic = WalletManager.loadSeed(this) ?: ""
            loadAddresses()
            buildUI()
        

    }

    /* ── PIN DIALOG ── */

    private fun showBiometricOrPin(onSuccess: () -> Unit) {
        val bm = BiometricManager.from(this)
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lastInteraction = System.currentTimeMillis()
                    isLocked = false
                    onSuccess()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_USER_CANCELED) {
                        showBiometricOrPin {
            mnemonic = WalletManager.loadSeed(this) ?: ""
            loadAddresses()
            buildUI()
         if (ok) { isLocked = false; onSuccess() } else finish() }
                    } else finish()
                }
                override fun onAuthenticationFailed() {}
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Wallet Hunter")
                .setSubtitle("Verify your identity")
                .setNegativeButtonText("Use PIN")
                .build()
            prompt.authenticate(info)
        } else {
            showBiometricOrPin {
            mnemonic = WalletManager.loadSeed(this) ?: ""
            loadAddresses()
            buildUI()
         if (ok) { isLocked = false; onSuccess() } else finish() }
        }
    }

    private fun showPinDialog(isSetup: Boolean, onResult: (Boolean) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            setBackgroundColor(BG_PANEL)
        }
        val tvTitle = TextView(this).apply {
            text = if (isSetup) "Create PIN" else "Enter PIN"
            textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16))
        }
        layout.addView(tvTitle)

        /* PIN dots display */
        val pinDisplay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        val dots = Array(6) { View(this).apply {
            val size = dp(14)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TXT_MUTED) }
        }}
        dots.forEach { pinDisplay.addView(it) }
        layout.addView(pinDisplay)

        val pin = StringBuilder()
        fun updateDots() {
            dots.forEachIndexed { i, dot ->
                (dot.background as GradientDrawable).setColor(if (i < pin.length) AMBER else TXT_MUTED)
            }
        }

        /* Numpad */
        val numpad = GridLayout(this).apply {
            columnCount = 3; rowCount = 4
            setPadding(dp(8), 0, dp(8), 0)
        }
        val tvStatus = TextView(this).apply {
            text = if (isSetup) "Choose a 6-digit PIN" else "Enter your PIN"
            textSize = 10f; setTextColor(TXT_SEC); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        var firstPin = ""
        var dlgRef: AlertDialog? = null
        fun handleDigit(d: String) {
            if (d == "DEL") { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length-1); updateDots(); return }
            if (pin.length >= 6) return
            pin.append(d); updateDots()
            if (pin.length == 6) {
                if (isSetup) {
                    if (firstPin.isEmpty()) {
                        firstPin = pin.toString(); pin.clear(); updateDots()
                        tvStatus.text = "Confirm PIN"; tvStatus.setTextColor(TXT_SEC)
                    } else if (firstPin == pin.toString()) {
                        WalletManager.savePin(this, pin.toString())
                        dlgRef?.dismiss()
                        onResult(true)
                    } else {
                        firstPin = ""; pin.clear(); updateDots()
                        tvStatus.text = "PINs don't match, try again"
                        tvStatus.setTextColor(RED)
                    }
                } else {
                    if (WalletManager.checkPin(this, pin.toString())) {
                        dlgRef?.dismiss()
                        onResult(true)
                    } else {
                        pin.clear(); updateDots()
                        tvStatus.text = "Wrong PIN"; tvStatus.setTextColor(RED)
                    }
                }
            }
        }

        val keys = listOf("1","2","3","4","5","6","7","8","9","","0","DEL")
        keys.forEach { k ->
            val btn = Button(this).apply {
                text = k; textSize = 20f
                setTextColor(if (k == "DEL") RED else TXT_PRI)
                typeface = Typeface.create("monospace", Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(if (k.isEmpty()) Color.TRANSPARENT else BG_ELEV)
                    setStroke(if (k.isEmpty()) 0 else 1, BORDER_C)
                }
                val sz = dp(64)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = sz; height = sz
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                isEnabled = k.isNotEmpty()
                if (k.isNotEmpty()) setOnClickListener { handleDigit(k) }
            }
            numpad.addView(btn)
        }
        layout.addView(numpad)
        layout.addView(tvStatus)

        val dlg = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
        dlgRef = dlg
        if (!isSetup) {
            dlg.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ -> dlg.dismiss(); onResult(false) }
        }
        dlg.show()
    }

    private fun loadAddresses() {
        val cached = WalletManager.loadAddresses(this)
        if (cached != null) {
            try { val j = JSONObject(cached); j.keys().forEach { k -> addresses[k] = j.getString(k) }; return } catch(e: Exception) {}
        }
        if (mnemonic.isNotEmpty()) {
            Thread {
                val json = HunterEngine.deriveWallet(mnemonic)
                WalletManager.saveAddresses(this, json)
                try { val j = JSONObject(json); j.keys().forEach { k -> addresses[k] = j.getString(k) } } catch(e: Exception) {}
                runOnUiThread { if (currentTab == 0) loadBalanceTab() }
            }.start()
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(16), dp(12), dp(16), dp(12)); gravity = Gravity.CENTER_VERTICAL
        }
        val btnBack = Button(this).apply {
            text = "<"; textSize = 14f; setTextColor(AMBER)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C) }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = "BTC WALLET"; textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        val btnMenu = Button(this).apply {
            text = "..."; textSize = 14f; setTextColor(TXT_SEC)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setOnClickListener { showMenu() }
        }
        header.addView(btnBack); header.addView(tvTitle); header.addView(btnMenu)
        root.addView(header)

        val tabNames = listOf("Balance","History","Send","Receive")
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_CARD) }
        val tabBtns = mutableListOf<Button>()
        tabNames.forEachIndexed { i, name ->
            val btn = Button(this).apply {
                text = name; textSize = 10f
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setTextColor(if(i==0) AMBER else TXT_SEC)
                background = GradientDrawable().apply { setColor(if(i==0) BG_ELEV else BG_CARD) }
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            }
            tabBtns.add(btn); tabs.addView(btn)
        }
        tabBtns.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                tabBtns.forEach { b -> b.setTextColor(TXT_SEC); b.background = GradientDrawable().apply { setColor(BG_CARD) } }
                btn.setTextColor(AMBER); btn.background = GradientDrawable().apply { setColor(BG_ELEV) }
                currentTab = i; tabContent.removeAllViews()
                when(i) { 0->loadBalanceTab(); 1->loadHistoryTab(); 2->loadSendTab(); 3->loadReceiveTab() }
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

    /* ── BALANCE ── */
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
                    val conn = (java.net.URL("https://mempool.space/api/address/$addr").openConnection() as java.net.HttpURLConnection).apply { connectTimeout=5000; readTimeout=5000 }
                    val js = conn.inputStream.bufferedReader().readText()
                    val funded = Regex("\"funded_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val spent  = Regex("\"spent_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val bal = funded - spent; totalSat += bal
                    rows.add(Triple(labelMap[k]?:k, addr, bal))
                } catch(e: Exception) { rows.add(Triple(labelMap[k]?:k, addr, -1L)) }
            }
            var price = 0.0
            try {
                val js = (java.net.URL("https://mempool.space/api/v1/prices").openConnection() as java.net.HttpURLConnection).apply{connectTimeout=3000;readTimeout=3000}.inputStream.bufferedReader().readText()
                price = Regex("\"USD\":(\\d+)").find(js)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            } catch(e: Exception) {}
            val tot = totalSat; val pr = price
            runOnUiThread {
                val btcText = "%.8f BTC".format(tot/1e8)
                val fiatText = if(pr>0) "~ ${"%.2f".format(tot/1e8*pr)} USD" else ""
                tvTotal.text = if(balanceVisible) btcText else "••••••••"
                tvTotal.setTextColor(if(tot>0) GREEN else AMBER)
                if (pr > 0) tvFiat.text = if(balanceVisible) fiatText else "••••••"
                tvTotal.setOnClickListener {
                    balanceVisible = !balanceVisible
                    tvTotal.text = if(balanceVisible) btcText else "••••••••"
                    tvFiat.text  = if(balanceVisible) fiatText else "••••••"
                }
                rows.forEach { (lbl, addr, bal) ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg()
                        setPadding(dp(12),dp(10),dp(12),dp(10))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}
                    }
                    card.addView(TextView(this).apply{text=lbl;textSize=9f;setTextColor(TXT_SEC)})
                    card.addView(TextView(this).apply{text=addr;textSize=9f;setTextColor(TXT_PRI);typeface=Typeface.MONOSPACE})
                    card.addView(TextView(this).apply{
                        text=if(bal<0)"error" else "%.8f BTC".format(bal/1e8)
                        textSize=12f;setTextColor(when{bal>0->GREEN;bal==0L->TXT_MUTED;else->RED})
                        typeface=Typeface.create("monospace",Typeface.BOLD)
                    })
                    ll.addView(card)
                }
            }
        }.start()
    }

    /* ── HISTORY ── */
    private fun loadHistoryTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
        val tvHead = TextView(this).apply { text="Loading...";textSize=11f;setTextColor(TXT_SEC);setPadding(0,dp(12),0,dp(8)) }
        ll.addView(tvHead); scroll.addView(ll); tabContent.addView(scroll)
        val firstAddr = addresses["p2pkh_0"] ?: addresses.values.firstOrNull() ?: return
        Thread {
            try {
                val js = (java.net.URL("https://mempool.space/api/address/$firstAddr/txs").openConnection() as java.net.HttpURLConnection).apply{connectTimeout=5000;readTimeout=5000}.inputStream.bufferedReader().readText()
                val arr = JSONArray(js)
                runOnUiThread {
                    tvHead.text = "${arr.length()} txs  ${firstAddr.take(14)}..."
                    if (arr.length()==0){ll.addView(TextView(this).apply{text="No transactions";setTextColor(TXT_MUTED);textSize=11f});return@runOnUiThread}
                    for (i in 0 until minOf(arr.length(),20)){
                        val tx=arr.getJSONObject(i)
                        val txid=tx.getString("txid")
                        val confirmed=tx.optJSONObject("status")?.optBoolean("confirmed",false)?:false
                        val blockTime=tx.optJSONObject("status")?.optLong("block_time",0)?:0L
                        var received=0L
                        val vout=tx.getJSONArray("vout")
                        for(j in 0 until vout.length()){val o=vout.getJSONObject(j);if(addresses.values.contains(o.optString("scriptpubkey_address","")))received+=o.optLong("value",0)}
                        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();setPadding(dp(12),dp(10),dp(12),dp(10));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}}
                        card.addView(TextView(this).apply{text=txid.take(22)+"...";textSize=9f;setTextColor(CYAN);typeface=Typeface.MONOSPACE})
                        card.addView(TextView(this).apply{text=if(confirmed)"Confirmed" else "Pending";textSize=9f;setTextColor(if(confirmed)GREEN else AMBER)})
                        if(received>0)card.addView(TextView(this).apply{text="+%.8f BTC".format(received/1e8);textSize=12f;setTextColor(GREEN);typeface=Typeface.create("monospace",Typeface.BOLD)})
                        if(blockTime>0)card.addView(TextView(this).apply{text=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.US).format(java.util.Date(blockTime*1000));textSize=9f;setTextColor(TXT_MUTED)})
                        ll.addView(card)
                    }
                }
            } catch(e: Exception){runOnUiThread{tvHead.text="Error: ${e.message}";tvHead.setTextColor(RED)}}
        }.start()
    }

    /* ── SEND ── */
    private fun loadSendTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(16)) }
        fun lbl(t: String) = TextView(this).apply{text=t;textSize=9f;setTextColor(TXT_SEC);setPadding(0,dp(10),0,dp(3))}
        fun fld() = EditText(this).apply{setTextColor(TXT_PRI);textSize=11f;typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,BORDER_C)};setPadding(dp(10),dp(8),dp(10),dp(8))}

        ll.addView(lbl("From address"))
        val spinFrom = Spinner(this).apply {
            adapter=ArrayAdapter(this@WalletActivity,android.R.layout.simple_spinner_item,addresses.keys.map{"$it  ${addresses[it]!!.take(14)}..."}).also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
            background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C)}
        }
        ll.addView(spinFrom)
        ll.addView(lbl("To address")); val etTo=fld(); ll.addView(etTo)
        ll.addView(lbl("Amount (BTC)")); val etAmt=fld().apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}; ll.addView(etAmt)
        ll.addView(lbl("Fee rate (sat/vB)")); val etFee=fld().apply{inputType=InputType.TYPE_CLASS_NUMBER;setText("5")}; ll.addView(etFee)

        val tvStatus = TextView(this).apply{text="";textSize=10f;setTextColor(TXT_SEC);typeface=Typeface.MONOSPACE;setPadding(0,dp(8),0,0);setLineSpacing(0f,1.3f)}

        val btnSend = Button(this).apply {
            text="BUILD & BROADCAST";textSize=12f;setTextColor(Color.BLACK)
            background=GradientDrawable().apply{setColor(AMBER)}
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)).apply{topMargin=dp(12)}
        }
        ll.addView(btnSend); ll.addView(tvStatus)
        scroll.addView(ll); tabContent.addView(scroll)

        btnSend.setOnClickListener {
            val toAddr=etTo.text.toString().trim()
            val amtBtc=etAmt.text.toString().toDoubleOrNull()?:0.0
            val feeRate=etFee.text.toString().toIntOrNull()?:5
            val fromKey=addresses.keys.toList().getOrNull(spinFrom.selectedItemPosition)?:return@setOnClickListener
            val fromAddr=addresses[fromKey]?:return@setOnClickListener
            if(toAddr.isEmpty()||amtBtc<=0){tvStatus.text="Fill all fields";tvStatus.setTextColor(RED);return@setOnClickListener}
            runOnUiThread{tvStatus.text="Fetching UTXOs...";tvStatus.setTextColor(TXT_SEC);btnSend.isEnabled=false}
            Thread {
                try {
                    val js=(java.net.URL("https://mempool.space/api/address/$fromAddr/utxo").openConnection() as java.net.HttpURLConnection).apply{connectTimeout=5000;readTimeout=5000}.inputStream.bufferedReader().readText()
                    val utxos=JSONArray(js)
                    if(utxos.length()==0){runOnUiThread{tvStatus.text="No UTXOs - no balance";tvStatus.setTextColor(RED);btnSend.isEnabled=true};return@Thread}
                    val amtSat=(amtBtc*1e8).toLong()
                    val estSize=148*utxos.length()+34*2+10
                    val feeSat=(feeRate*estSize).toLong()
                    var utxoArr="["; var totalIn=0L
                    for(i in 0 until utxos.length()){
                        val u=utxos.getJSONObject(i); val txid=u.getString("txid"); val vout=u.getInt("vout"); val v=u.getLong("value")
                        totalIn+=v; if(i>0)utxoArr+=","; utxoArr+="{\"txid\":\"$txid\",\"vout\":$vout,\"amount\":$v}"
                    }
                    utxoArr+="]"
                    if(totalIn<amtSat+feeSat){runOnUiThread{tvStatus.text="Insufficient: have ${totalIn}sat need ${amtSat+feeSat}sat";tvStatus.setTextColor(RED);btnSend.isEnabled=true};return@Thread}
                    val pathStr=when{fromKey.startsWith("p2pkh")->"m/44'/0'/0'/0/${fromKey.last()}";fromKey.startsWith("p2sh")->"m/49'/0'/0'/0/0";fromKey.startsWith("p2wpkh")->"m/84'/0'/0'/0/${fromKey.last()}";else->"m/44'/0'/0'/0/0"}
                    val req="{\"mnemonic\":\"$mnemonic\",\"path\":\"$pathStr\",\"utxos\":$utxoArr,\"to\":\"$toAddr\",\"amount\":$amtSat,\"fee\":$feeSat}"
                    val rawTx=HunterEngine.buildAndSignTx(req)
                    if(rawTx.startsWith("ERROR")){runOnUiThread{tvStatus.text=rawTx;tvStatus.setTextColor(RED);btnSend.isEnabled=true};return@Thread}
                    runOnUiThread{tvStatus.text="Broadcasting...";tvStatus.setTextColor(TXT_SEC)}
                    val bc=java.net.URL("https://mempool.space/api/tx").openConnection() as java.net.HttpURLConnection
                    bc.requestMethod="POST";bc.doOutput=true;bc.setRequestProperty("Content-Type","text/plain")
                    bc.outputStream.write(rawTx.toByteArray())
                    val code=bc.responseCode
                    val resp=if(code==200)bc.inputStream.bufferedReader().readText() else bc.errorStream?.bufferedReader()?.readText()?:"error"
                    runOnUiThread{
                        tvStatus.text=if(code==200)"Sent!\nTXID: $resp" else "Error $code:\n$resp"
                        tvStatus.setTextColor(if(code==200)GREEN else RED)
                        btnSend.isEnabled=true
                    }
                } catch(e: Exception){runOnUiThread{tvStatus.text="Error: ${e.message}";tvStatus.setTextColor(RED);btnSend.isEnabled=true}}
            }.start()
        }
    }

    /* ── RECEIVE ── */
    private fun loadReceiveTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));gravity=Gravity.CENTER_HORIZONTAL }
        val spin = Spinner(this).apply {
            adapter=ArrayAdapter(this@WalletActivity,android.R.layout.simple_spinner_item,addresses.keys.map{"$it  ${addresses[it]!!.take(14)}..."}).also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
            background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C)}
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48))
        }
        ll.addView(spin)
        val ivQr = android.widget.ImageView(this).apply {
            layoutParams=LinearLayout.LayoutParams(dp(220),dp(220)).apply{topMargin=dp(16);bottomMargin=dp(12)}
            setBackgroundColor(Color.WHITE);setPadding(dp(8),dp(8),dp(8),dp(8))
        }
        ll.addView(ivQr)
        val tvAddr = TextView(this).apply{text="";textSize=10f;setTextColor(TXT_PRI);typeface=Typeface.MONOSPACE;gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(8))}
        ll.addView(tvAddr)
        val btnCopy = Button(this).apply{text="Copy Address";textSize=11f;setTextColor(Color.BLACK);background=GradientDrawable().apply{setColor(AMBER)};layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(44))}
        ll.addView(btnCopy)

        fun updateQr(addr: String) {
            tvAddr.text = addr
            btnCopy.setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("btc",addr))
                Toast.makeText(this,"Copied!",Toast.LENGTH_SHORT).show()
            }
            Thread {
                try {
                    val bm=com.journeyapps.barcodescanner.BarcodeEncoder().createBitmap(
                        com.google.zxing.qrcode.QRCodeWriter().encode("bitcoin:$addr",com.google.zxing.BarcodeFormat.QR_CODE,512,512))
                    runOnUiThread{ivQr.setImageBitmap(bm)}
                } catch(e: Exception){}
            }.start()
        }
        spin.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(a:AdapterView<*>,v:android.view.View?,pos:Int,id:Long){updateQr(addresses.values.toList().getOrNull(pos)?:return)}
            override fun onNothingSelected(a:AdapterView<*>){}
        }
        if(addresses.isNotEmpty()) updateQr(addresses.values.first())
        scroll.addView(ll); tabContent.addView(scroll)
    }

    private fun showMenu() {
        AlertDialog.Builder(this).setTitle("Options")
            .setItems(arrayOf("Show seed phrase","Change PIN","Delete wallet","Cancel")) { _,pos ->
                when(pos) {
                    0 -> showPinDialog(isSetup=false){ok->if(ok)AlertDialog.Builder(this).setTitle("Seed - Keep Private!").setMessage(mnemonic).setPositiveButton("OK",null).show()}
                    1 -> showPinDialog(isSetup=false){ok->if(ok)showPinDialog(isSetup=true){}}
                    2 -> AlertDialog.Builder(this).setTitle("Delete wallet?").setMessage("Make sure you have your seed backed up.").setPositiveButton("Delete"){_,_->WalletManager.clearSeed(this);finish()}.setNegativeButton("Cancel",null).show()
                }
            }.show()
    }

    private fun showSetupDialog() {
        val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(8));setBackgroundColor(BG_PANEL) }
        val etSeed = EditText(this).apply {
            hint="Enter 12 or 24 word seed phrase";setTextColor(TXT_PRI);setHintTextColor(TXT_MUTED)
            background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,BORDER_C)}
            setPadding(dp(10),dp(8),dp(10),dp(8));textSize=11f;minLines=2;maxLines=4
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(etSeed)
        layout.addView(TextView(this).apply{text="Your seed is encrypted with Android Keystore.";textSize=9f;setTextColor(TXT_SEC);setPadding(0,dp(8),0,0)})
        AlertDialog.Builder(this).setTitle("Import Wallet").setView(layout)
            .setPositiveButton("Next"){_,_->
                val mn=etSeed.text.toString().trim()
                if(mn.split(" ").size<12){Toast.makeText(this,"Need 12+ words",Toast.LENGTH_SHORT).show();finish();return@setPositiveButton}
                showPinDialog(isSetup=true){
                    WalletManager.saveSeed(this,mn)
                    mnemonic=mn; loadAddresses(); buildUI()
                }
            }.setNegativeButton("Cancel"){_,_->finish()}.show()
    }
}
