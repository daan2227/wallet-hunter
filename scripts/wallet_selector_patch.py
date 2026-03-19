#!/usr/bin/env python3
"""
wallet_selector_patch.py
Mueve showWalletSelectorDialog de WalletActivity a MainActivity.
WalletActivity recibe modo via Intent y va directo a buildUI().
"""
path_main = "/data/data/com.termux/files/home/wallet-hunter/app/src/main/java/com/hunter/btc/MainActivity.kt"
path_wallet = "/data/data/com.termux/files/home/wallet-hunter/app/src/main/java/com/hunter/btc/WalletActivity.kt"

main = open(path_main).read()
wallet = open(path_wallet).read()

# ── 1. Reemplazar todas las llamadas a WalletActivity en MainActivity ──
OPEN_WALLET = 'startActivity(Intent(this@MainActivity,WalletActivity::class.java))'
OPEN_WALLET2 = 'startActivity(Intent(this, WalletActivity::class.java))'
main = main.replace(OPEN_WALLET, 'showWalletSelector()')
main = main.replace(OPEN_WALLET2, 'showWalletSelector()')
print("1. replaced startActivity calls")

# ── 2. Agregar showWalletSelector() en MainActivity antes de pickCsv ──
selector_method = '''
    private fun showWalletSelector() {
        val ctx = this
        val AMBER   = AppTheme.AMBER
        val GREEN   = AppTheme.GREEN
        val CYAN    = AppTheme.CYAN
        val BG_PANEL= AppTheme.BG_PANEL
        val BG_CARD = AppTheme.BG_CARD
        val BG_ELEV = AppTheme.BG_ELEV
        val TXT_PRI = AppTheme.TXT_PRI
        val TXT_MUTED=AppTheme.TXT_MUTED
        val BORDER_C= AppTheme.BORDER_C
        val RED     = AppTheme.RED

        fun dpL(v:Int)=(v*resources.displayMetrics.density).toInt()

        val hasSeed   = WalletManager.hasSeed(ctx)
        val wallets   = WalletManager.listWallets(ctx)
        val wifList   = WalletManager.listWifs(ctx)
        val watchList = WalletManager.listWatchers(ctx)

        /* Si solo hay una seed, ir directo */
        if (hasSeed && wallets.isEmpty() && wifList.isEmpty() && watchList.isEmpty()) {
            startActivity(Intent(ctx, WalletActivity::class.java)
                .putExtra("MODE","seed").putExtra("WALLET_ID",""))
            return
        }

        val scroll = android.widget.ScrollView(ctx)
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(BG_PANEL); cornerRadius=dpL(16).toFloat(); setStroke(1,BORDER_C) }
            setPadding(dpL(20),dpL(20),dpL(20),dpL(20))
        }
        scroll.addView(sheet)

        sheet.addView(android.widget.TextView(ctx).apply {
            text="Select Wallet"; textSize=17f; setTextColor(AMBER)
            typeface=android.graphics.Typeface.create("sans-serif-black",android.graphics.Typeface.BOLD)
            gravity=android.view.Gravity.CENTER; setPadding(0,0,0,dpL(16))
        })

        var dlg: AlertDialog? = null

        fun walletCard(name:String, subtitle:String, color:Int, onClick:()->Unit) {
            val card = LinearLayout(ctx).apply {
                orientation=LinearLayout.VERTICAL
                background=android.graphics.drawable.GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dpL(10).toFloat()}
                setPadding(dpL(14),dpL(12),dpL(14),dpL(12))
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dpL(8)}
                setOnClickListener{dlg?.dismiss();onClick()}
            }
            card.addView(android.widget.TextView(ctx).apply{text=name;textSize=13f;setTextColor(color);typeface=android.graphics.Typeface.create("sans-serif-black",android.graphics.Typeface.BOLD)})
            card.addView(android.widget.TextView(ctx).apply{text=subtitle;textSize=9f;setTextColor(TXT_MUTED);typeface=android.graphics.Typeface.create("monospace",android.graphics.Typeface.NORMAL)})
            sheet.addView(card)
        }

        if (hasSeed) {
            walletCard("Main Wallet","BIP39 HD Wallet",TXT_PRI) {
                startActivity(Intent(ctx,WalletActivity::class.java).putExtra("MODE","seed").putExtra("WALLET_ID",""))
            }
        }
        wallets.forEach { (id,name) ->
            walletCard(name,"BIP39 HD Wallet",TXT_PRI) {
                startActivity(Intent(ctx,WalletActivity::class.java).putExtra("MODE","seed").putExtra("WALLET_ID",id))
            }
        }
        wifList.forEach { (wid,wkey,wmeta) ->
            val parts=wmeta.split("|"); val waddr=parts.getOrNull(0)?:""; val wname=parts.getOrNull(1)?:"WIF Wallet"
            walletCard(wname,"${wkey.take(8)}...",GREEN) {
                startActivity(Intent(ctx,WalletActivity::class.java).putExtra("MODE","wif").putExtra("WIF_KEY",wkey).putExtra("WIF_ADDR",waddr).putExtra("WALLET_NAME",wname))
            }
        }
        watchList.forEach { (wid,waddr,wlabel) ->
            walletCard(wlabel,"${waddr.take(20)}...",CYAN) {
                startActivity(Intent(ctx,WalletActivity::class.java).putExtra("MODE","watch").putExtra("WIF_ADDR",waddr).putExtra("WALLET_NAME",wlabel))
            }
        }

        val btnRow=LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dpL(8),0,0)}
        fun addBtn(label:String,fg:Int,bg:Int,stroke:Int,click:()->Unit):android.widget.Button {
            return android.widget.Button(ctx).apply{
                text=label;textSize=11f;setTextColor(fg)
                typeface=android.graphics.Typeface.create("sans-serif-black",android.graphics.Typeface.BOLD)
                background=android.graphics.drawable.GradientDrawable().apply{setColor(bg);if(stroke!=0)setStroke(1,stroke);cornerRadius=dpL(7).toFloat()}
                layoutParams=LinearLayout.LayoutParams(0,dpL(42),1f).apply{marginEnd=dpL(6)}
                setOnClickListener{dlg?.dismiss();click()}
            }
        }
        btnRow.addView(addBtn("+ Seed",android.graphics.Color.BLACK,AMBER,0){
            startActivity(Intent(ctx,WalletActivity::class.java).putExtra("MODE","setup"))
        })
        btnRow.addView(addBtn("+ WIF",TXT_PRI,BG_CARD,BORDER_C){
            startActivity(Intent(ctx,WalletActivity::class.java).putExtra("MODE","wif_import"))
        })
        btnRow.addView(addBtn("+ Watch",CYAN,BG_CARD,CYAN){
            startActivity(Intent(ctx,WalletActivity::class.java).putExtra("MODE","watch_import"))
        }.apply{layoutParams=(layoutParams as LinearLayout.LayoutParams).also{it.marginEnd=0}})
        sheet.addView(btnRow)

        dlg = AlertDialog.Builder(ctx).setView(scroll).setCancelable(true).create()
        dlg!!.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels*0.92f).toInt(),android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
            attributes=attributes?.also{it.dimAmount=0.75f}
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg!!.show()
    }

'''

insert_before = '    private fun pickCsv()'
if insert_before in main:
    main = main.replace(insert_before, selector_method + '    private fun pickCsv()')
    print("2. showWalletSelector() added to MainActivity")
else:
    print("2. ERROR: insert point not found")

open(path_main,"wb").write(main.encode("utf-8"))

# ── 3. Modificar WalletActivity.onCreate para leer MODE del Intent ──
old_oncreate = '''    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        overridePendingTransition(0, 0)
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
    }'''

new_oncreate = '''    override fun onCreate(s: Bundle?) {
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
                }
            }
            "wif" -> {
                wifKey  = intent.getStringExtra("WIF_KEY") ?: ""
                wifAddr = intent.getStringExtra("WIF_ADDR") ?: ""
                currentWalletName = intent.getStringExtra("WALLET_NAME") ?: "WIF Wallet"
                isWifMode = true
                showPinDialog(isSetup = false) { ok ->
                    if (!ok) { finish(); return@showPinDialog }
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
    }'''

if old_oncreate in wallet:
    wallet = wallet.replace(old_oncreate, new_oncreate)
    print("3. WalletActivity.onCreate updated")
else:
    print("3. ERROR: onCreate pattern not found")
    idx = wallet.find("override fun onCreate")
    print(repr(wallet[idx:idx+300]))

open(path_wallet,"wb").write(wallet.encode("utf-8"))
print("ALL DONE")
