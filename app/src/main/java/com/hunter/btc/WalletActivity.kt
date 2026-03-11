package com.hunter.btc

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

class WalletActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
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
    private fun mono(size: Float) = Typeface.create("monospace", Typeface.NORMAL).let { size }

    private var mnemonic: String = ""
    private var addresses = mutableMapOf<String, String>()
    private var currentTab = 0

    private lateinit var tabContent: FrameLayout
    private lateinit var tvBalance: TextView
    private lateinit var tvTxList: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        mnemonic = WalletManager.loadSeed(this) ?: ""
        if (mnemonic.isEmpty()) { showSetupDialog(); return }
        loadAddresses()
        buildUI()
    }

    private fun loadAddresses() {
        val cached = WalletManager.loadAddresses(this)
        if (cached != null) {
            try {
                val j = JSONObject(cached)
                j.keys().forEach { k -> addresses[k] = j.getString(k) }
                return
            } catch(e: Exception) {}
        }
        if (mnemonic.isNotEmpty()) {
            Thread {
                val json = HunterEngine.deriveWallet(mnemonic)
                WalletManager.saveAddresses(this, json)
                try {
                    val j = JSONObject(json)
                    j.keys().forEach { k -> addresses[k] = j.getString(k) }
                } catch(e: Exception) {}
                runOnUiThread { if (currentTab == 0) loadBalanceTab() }
            }.start()
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
        }
        /* Header */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_PANEL)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
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

        /* Tabs */
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_CARD)
        }
        val tabNames = listOf("Balance", "History", "Send", "Receive")
        val tabBtns = tabNames.mapIndexed { i, name ->
            Button(this).apply {
                text = name; textSize = 10f
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setTextColor(if(i==0) AMBER else TXT_SEC)
                background = GradientDrawable().apply {
                    setColor(if(i==0) BG_ELEV else BG_CARD)
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                setOnClickListener { switchTab(i, this) }
            }
        }
        tabBtns.forEach { tabs.addView(it) }
        root.addView(tabs)

        tabContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(tabContent)
        setContentView(root)

        var tabIdx = 0
        tabBtns.forEach { btn ->
            val idx = tabIdx++
            btn.setOnClickListener {
                tabBtns.forEach { b ->
                    b.setTextColor(TXT_SEC)
                    b.background = GradientDrawable().apply { setColor(BG_CARD) }
                }
                btn.setTextColor(AMBER)
                btn.background = GradientDrawable().apply { setColor(BG_ELEV) }
                currentTab = idx
                switchTab(idx, btn)
            }
        }
        switchTab(0, tabBtns[0])
    }

    private fun switchTab(idx: Int, btn: Button) {
        currentTab = idx
        tabContent.removeAllViews()
        when(idx) {
            0 -> loadBalanceTab()
            1 -> loadHistoryTab()
            2 -> loadSendTab()
            3 -> loadReceiveTab()
        }
    }

    /* ── BALANCE TAB ── */
    private fun loadBalanceTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }

        val tvTotal = TextView(this).apply {
            text = "Loading..."; textSize = 28f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(4))
        }
        val tvFiat = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(TXT_SEC)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16))
        }
        ll.addView(tvTotal); ll.addView(tvFiat)

        if (addresses.isEmpty()) {
            tvTotal.text = "No wallet"; ll.addView(tvTotal); scroll.addView(ll); tabContent.addView(scroll); return
        }

        Thread {
            var totalSat = 0L
            val addrRows = mutableListOf<Triple<String,String,Long>>()
            val labelMap = mapOf(
                "p2pkh_0" to "P2PKH [0]","p2pkh_1" to "P2PKH [1]","p2pkh_2" to "P2PKH [2]",
                "p2sh_0"  to "P2SH  [0]",
                "p2wpkh_0" to "WPKH  [0]","p2wpkh_1" to "WPKH  [1]"
            )
            addresses.forEach { (k, addr) ->
                try {
                    val url = java.net.URL("https://mempool.space/api/address/$addr")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val js = conn.inputStream.bufferedReader().readText()
                    val funded = Regex("\"funded_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val spent  = Regex("\"spent_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val bal = funded - spent
                    totalSat += bal
                    addrRows.add(Triple(labelMap[k] ?: k, addr, bal))
                } catch(e: Exception) { addrRows.add(Triple(labelMap[k] ?: k, addr, -1L)) }
            }
            // BTC price
            var price = 0.0
            try {
                val purl = java.net.URL("https://mempool.space/api/v1/prices")
                val pc = purl.openConnection() as java.net.HttpURLConnection
                pc.connectTimeout = 3000; pc.readTimeout = 3000
                val pjs = pc.inputStream.bufferedReader().readText()
                price = Regex("\"USD\":(\\d+)").find(pjs)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            } catch(e: Exception) {}

            runOnUiThread {
                tvTotal.text = "%.8f BTC".format(totalSat / 1e8)
                tvTotal.setTextColor(if(totalSat > 0) GREEN else AMBER)
                if (price > 0) tvFiat.text = "~ ${"%.2f".format(totalSat / 1e8 * price)} USD"

                addrRows.forEach { (lbl, addr, bal) ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg()
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                    }
                    card.addView(TextView(this).apply {
                        text = lbl; textSize = 9f; setTextColor(TXT_SEC)
                    })
                    card.addView(TextView(this).apply {
                        text = addr; textSize = 9f; setTextColor(TXT_PRI)
                        typeface = Typeface.MONOSPACE
                    })
                    card.addView(TextView(this).apply {
                        text = when { bal < 0 -> "error"; else -> "%.8f BTC".format(bal/1e8) }
                        textSize = 12f
                        setTextColor(when { bal > 0 -> GREEN; bal == 0L -> TXT_MUTED; else -> RED })
                        typeface = Typeface.create("monospace", Typeface.BOLD)
                    })
                    ll.addView(card)
                }
            }
        }.start()

        scroll.addView(ll); tabContent.addView(scroll)
    }

    /* ── HISTORY TAB ── */
    private fun loadHistoryTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        val tvHead = TextView(this).apply {
            text = "Loading transactions..."; textSize = 11f; setTextColor(TXT_SEC)
            setPadding(0, dp(12), 0, dp(8))
        }
        ll.addView(tvHead)
        scroll.addView(ll); tabContent.addView(scroll)

        val firstAddr = addresses["p2pkh_0"] ?: addresses.values.firstOrNull() ?: return
        Thread {
            try {
                val url = java.net.URL("https://mempool.space/api/address/$firstAddr/txs")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val js = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(js)
                runOnUiThread {
                    tvHead.text = "${arr.length()} transactions (${firstAddr.take(12)}...)"
                    if (arr.length() == 0) {
                        ll.addView(TextView(this).apply { text = "No transactions"; setTextColor(TXT_MUTED); textSize = 11f })
                        return@runOnUiThread
                    }
                    for (i in 0 until minOf(arr.length(), 20)) {
                        val tx = arr.getJSONObject(i)
                        val txid = tx.getString("txid")
                        val status = tx.optJSONObject("status")
                        val confirmed = status?.optBoolean("confirmed", false) ?: false
                        val blockTime = status?.optLong("block_time", 0) ?: 0L
                        val vout = tx.getJSONArray("vout")
                        var received = 0L
                        for (j in 0 until vout.length()) {
                            val o = vout.getJSONObject(j)
                            val scriptAddr = o.optString("scriptpubkey_address","")
                            if (addresses.values.contains(scriptAddr))
                                received += o.optLong("value",0)
                        }
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL; background = cardBg()
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                        }
                        card.addView(TextView(this).apply {
                            text = txid.take(20) + "..."; textSize = 9f
                            setTextColor(CYAN); typeface = Typeface.MONOSPACE
                        })
                        card.addView(TextView(this).apply {
                            text = if(confirmed) "Confirmed" else "Pending"
                            textSize = 9f; setTextColor(if(confirmed) GREEN else AMBER)
                        })
                        if (received > 0) card.addView(TextView(this).apply {
                            text = "+%.8f BTC".format(received/1e8)
                            textSize = 12f; setTextColor(GREEN)
                            typeface = Typeface.create("monospace", Typeface.BOLD)
                        })
                        if (blockTime > 0) card.addView(TextView(this).apply {
                            val dt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            text = dt.format(java.util.Date(blockTime * 1000))
                            textSize = 9f; setTextColor(TXT_MUTED)
                        })
                        ll.addView(card)
                    }
                }
            } catch(e: Exception) {
                runOnUiThread { tvHead.text = "Error: ${e.message}"; tvHead.setTextColor(RED) }
            }
        }.start()
    }

    /* ── SEND TAB ── */
    private fun loadSendTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, dp(10), 0, dp(3))
        }
        fun field() = EditText(this).apply {
            setTextColor(TXT_PRI); textSize = 11f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        ll.addView(label("From address"))
        val addrLabels = addresses.keys.map { k -> "$k — ${addresses[k]!!.take(14)}..." }
        val spinFrom = Spinner(this).apply {
            adapter = ArrayAdapter(this@WalletActivity, android.R.layout.simple_spinner_item, addrLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
        }
        ll.addView(spinFrom)

        ll.addView(label("To address"))
        val etTo = field(); ll.addView(etTo)

        ll.addView(label("Amount (BTC)"))
        val etAmt = field().apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        ll.addView(etAmt)

        ll.addView(label("Fee (sat/vB) — recommended: 5"))
        val etFee = field().apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText("5")
        }
        ll.addView(etFee)

        val tvStatus = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(TXT_SEC)
            typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.3f)
        }
        ll.addView(tvStatus)

        val btnSend = Button(this).apply {
            text = "BUILD & BROADCAST"; textSize = 12f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(AMBER) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) }
            setOnClickListener {
                val toAddr = etTo.text.toString().trim()
                val amtBtc = etAmt.text.toString().toDoubleOrNull() ?: 0.0
                val feeRate = etFee.text.toString().toIntOrNull() ?: 5
                val fromKey = addresses.keys.toList().getOrNull(spinFrom.selectedItemPosition) ?: return@setOnClickListener
                val fromAddr = addresses[fromKey] ?: return@setOnClickListener
                if (toAddr.isEmpty() || amtBtc <= 0) { tvStatus.text = "Fill all fields"; return@setOnClickListener }
                tvStatus.text = "Fetching UTXOs..."; tvStatus.setTextColor(TXT_SEC)
                isEnabled = false
                Thread {
                    try {
                        val url = java.net.URL("https://mempool.space/api/address/$fromAddr/utxo")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000; conn.readTimeout = 5000
                        val js = conn.inputStream.bufferedReader().readText()
                        val utxos = JSONArray(js)
                        if (utxos.length() == 0) { runOnUiThread { tvStatus.text = "No UTXOs — address has no balance"; tvStatus.setTextColor(RED); isEnabled=true }; return@Thread }
                        val amtSat = (amtBtc * 1e8).toLong()
                        val estSize = 148 * utxos.length() + 34 * 2 + 10
                        val feeSat = (feeRate * estSize).toLong()
                        var utxoArr = "["
                        var totalIn = 0L
                        for (i in 0 until utxos.length()) {
                            val u = utxos.getJSONObject(i)
                            val txid = u.getString("txid"); val vout = u.getInt("vout"); val v = u.getLong("value")
                            totalIn += v
                            if (i > 0) utxoArr += ","
                            utxoArr += "{\"txid\":\"$txid\",\"vout\":$vout,\"amount\":$v}"
                        }
                        utxoArr += "]"
                        if (totalIn < amtSat + feeSat) {
                            runOnUiThread { tvStatus.text = "Insufficient balance: have ${totalIn}sat need ${amtSat+feeSat}sat"; tvStatus.setTextColor(RED); isEnabled=true }
                            return@Thread
                        }
                        val path = when {
                            fromKey.startsWith("p2pkh")  -> "m/44'/0'/0'/0/${fromKey.last()}"
                            fromKey.startsWith("p2sh")   -> "m/49'/0'/0'/0/0"
                            fromKey.startsWith("p2wpkh") -> "m/84'/0'/0'/0/${fromKey.last()}"
                            else -> "m/44'/0'/0'/0/0"
                        }
                        val req = "{\"mnemonic\":\"$mnemonic\",\"path\":\"$path\",\"utxos\":$utxoArr,\"to\":\"$toAddr\",\"amount\":$amtSat,\"fee\":$feeSat}"
                        val rawTx = HunterEngine.buildAndSignTx(req)
                        if (rawTx.startsWith("ERROR")) {
                            runOnUiThread { tvStatus.text = rawTx; tvStatus.setTextColor(RED); isEnabled=true }
                            return@Thread
                        }
                        // Broadcast
                        runOnUiThread { tvStatus.text = "Broadcasting...\n${rawTx.take(40)}..." }
                        val burl = java.net.URL("https://mempool.space/api/tx")
                        val bc = burl.openConnection() as java.net.HttpURLConnection
                        bc.requestMethod = "POST"; bc.doOutput = true
                        bc.setRequestProperty("Content-Type","text/plain")
                        bc.outputStream.write(rawTx.toByteArray())
                        val code = bc.responseCode
                        val resp = if(code==200) bc.inputStream.bufferedReader().readText()
                                   else bc.errorStream?.bufferedReader()?.readText() ?: "error"
                        runOnUiThread {
                            if (code == 200) {
                                tvStatus.text = "Sent!\nTXID: $resp"; tvStatus.setTextColor(GREEN)
                            } else {
                                tvStatus.text = "Broadcast error $code:\n$resp"; tvStatus.setTextColor(RED)
                            }
                            isEnabled = true
                        }
                    } catch(e: Exception) {
                        runOnUiThread { tvStatus.text = "Error: ${e.message}"; tvStatus.setTextColor(RED); isEnabled=true }
                    }
                }.start()
            }
        }
        ll.addView(btnSend)
        ll.addView(tvStatus)
        scroll.addView(ll); tabContent.addView(scroll)
    }

    /* ── RECEIVE TAB ── */
    private fun loadReceiveTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val addrLabels = addresses.keys.map { k -> "$k — ${addresses[k]!!.take(14)}..." }
        val spin = Spinner(this).apply {
            adapter = ArrayAdapter(this@WalletActivity, android.R.layout.simple_spinner_item, addrLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }
        ll.addView(spin)

        val ivQr = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(16); bottomMargin = dp(12) }
            setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        ll.addView(ivQr)

        val tvAddr = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(TXT_PRI)
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        ll.addView(tvAddr)

        val btnCopy = Button(this).apply {
            text = "Copy Address"; textSize = 11f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(AMBER) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        }
        ll.addView(btnCopy)

        fun updateQr(addr: String) {
            tvAddr.text = addr
            btnCopy.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("btc_address", addr))
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            }
            Thread {
                try {
                    val w = com.google.zxing.qrcode.QRCodeWriter()
                    val bm = com.journeyapps.barcodescanner.BarcodeEncoder()
                        .createBitmap(w.encode("bitcoin:$addr", com.google.zxing.BarcodeFormat.QR_CODE, 512, 512))
                    runOnUiThread { ivQr.setImageBitmap(bm) }
                } catch(e: Exception) {}
            }.start()
        }

        spin.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                val key = addresses.keys.toList().getOrNull(pos) ?: return
                updateQr(addresses[key] ?: return)
            }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }
        if (addresses.isNotEmpty()) updateQr(addresses.values.first())
        scroll.addView(ll); tabContent.addView(scroll)
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle("Wallet Options")
            .setItems(arrayOf("Export seed (WARNING)", "Delete wallet", "Cancel")) { _, pos ->
                when(pos) {
                    0 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Seed Phrase - Keep Private!")
                            .setMessage(mnemonic)
                            .setPositiveButton("OK", null).show()
                    }
                    1 -> AlertDialog.Builder(this)
                        .setTitle("Delete wallet?")
                        .setMessage("This cannot be undone. Make sure you have your seed.")
                        .setPositiveButton("Delete") { _,_ -> WalletManager.clearSeed(this); finish() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun showSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8))
            setBackgroundColor(BG_PANEL)
        }
        val etSeed = EditText(this).apply {
            hint = "Enter 12 or 24 word seed phrase"
            setTextColor(Color.parseColor("#e2e8f0")); setHintTextColor(Color.parseColor("#334155"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#162030")); setStroke(1, Color.parseColor("#1a2332")) }
            setPadding(dp(10), dp(8), dp(10), dp(8)); textSize = 11f
            minLines = 2; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val tvInfo = TextView(this).apply {
            text = "Your seed is encrypted with the Android Keystore and stored only on this device."
            textSize = 9f; setTextColor(Color.parseColor("#64748b")); setPadding(0, dp(8), 0, 0)
        }
        layout.addView(etSeed); layout.addView(tvInfo)
        AlertDialog.Builder(this)
            .setTitle("Import Wallet")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val mn = etSeed.text.toString().trim()
                if (mn.split(" ").size < 12) { Toast.makeText(this, "Need 12+ words", Toast.LENGTH_SHORT).show(); finish(); return@setPositiveButton }
                WalletManager.saveSeed(this, mn)
                mnemonic = mn
                loadAddresses()
                buildUI()
            }
            .setNegativeButton("Cancel") { _,_ -> finish() }
            .show()
    }
}
