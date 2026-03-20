#!/usr/bin/env python3
"""
rebuild_c.py
Reemplaza buildScanTab() y buildPuzzleTab() con estructura
de sub-tabs [Run] [Config] [Logs] independientes.
"""

import os, re
PROJECT_ROOT = os.getcwd()
KT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "hunter", "btc")
path = os.path.join(KT_DIR, "MainActivity.kt")
content = open(path).read()

# ── Nuevas variables para Puzzle independiente ────────────────────────────────
old_vars = "    private lateinit var sbCpu: SeekBar"
new_vars = """    private lateinit var sbCpu: SeekBar
    // Puzzle tiene sus propios sliders independientes
    private lateinit var sbThreadsPuzzle: SeekBar
    private lateinit var sbCpuPuzzle: SeekBar
    private lateinit var tvThreadsPuzzle: TextView
    private lateinit var tvCpuPuzzle: TextView"""

if old_vars in content:
    content = content.replace(old_vars, new_vars)
    print("✓ Variables puzzle independientes agregadas")

# ── Nueva buildScanTab ────────────────────────────────────────────────────────
new_scan = '''    private fun buildScanTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP)
        }

        // ── Sub-tab bar ───────────────────────────────────────────────────────
        val subBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_PANEL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        }
        fun subBtn(lbl: String): TextView = TextView(this).apply {
            text = lbl; textSize = 10f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER; isAllCaps = true; letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val sbRun  = subBtn("▶ Run")
        val sbCfg  = subBtn("⚙ Config")
        val sbLogs = subBtn("◉ Logs")
        listOf(sbRun, sbCfg, sbLogs).forEach { subBar.addView(it) }
        root.addView(subBar)

        // ── Contenedor de sub-páginas ─────────────────────────────────────────
        val subFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // ── RUN PAGE ──────────────────────────────────────────────────────────
        val runPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Speed hero
        val heroBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(18), dp(20), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val heroLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroLeft.addView(TextView(this).apply {
            text = "SPEED"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.18f; setPadding(0,0,0,dp(4))
        })
        tvWps = TextView(this).apply { text = "0"; textSize = 50f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD) }
        heroLeft.addView(tvWps)
        heroLeft.addView(TextView(this).apply { text = "keys / second"; textSize = 11f; setTextColor(TXT_SEC); setPadding(0,dp(3),0,0) })
        val heroRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        tvCount = TextView(this).apply { text = "0"; textSize = 17f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        tvTime  = TextView(this).apply { text = "00:00:00"; textSize = 17f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        heroRight.addView(tvCount)
        heroRight.addView(TextView(this).apply { text = "SCANNED"; textSize = 8f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0,dp(2),0,dp(12)) })
        heroRight.addView(tvTime)
        heroRight.addView(TextView(this).apply { text = "ELAPSED"; textSize = 8f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0,dp(2),0,0) })
        heroBlock.addView(heroLeft); heroBlock.addView(heroRight)
        runPage.addView(heroBlock)
        runPage.addView(View(this).apply { setBackgroundColor(BORDER_C); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })

        // Start/Stop button
        val coinGreen = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(12).toFloat() }
        val coinRed   = GradientDrawable().apply { setColor(RED);   cornerRadius = dp(12).toFloat() }
        btnToggle = Button(this).apply {
            text = s.start; textSize = 15f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.12f; isAllCaps = true
            background = coinGreen
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            setOnClickListener { doToggle() }
        }
        btnToggle.tag = arrayOf(coinGreen, coinRed)
        val actZone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        actZone.addView(btnToggle); runPage.addView(actZone)

        // Speed chart
        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16),0,dp(16),dp(8)) }
        }
        chartCard.addView(TextView(this).apply { text = "SPEED HISTORY"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(0,0,0,dp(10)) })
        chartView = SpeedChartView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)) }
        chartCard.addView(chartView); runPage.addView(chartCard)

        // Matches log
        val matchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16),0,dp(16),dp(8)) }
        }
        matchCard.addView(TextView(this).apply { text = "MATCHES"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(dp(14),dp(10),dp(14),dp(4)) })
        tvMatchList = TextView(this).apply {
            text = "No matches yet"; setTextColor(TXT_MUTED); textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(dp(14),dp(4),dp(14),dp(12))
        }
        matchCard.addView(tvMatchList); runPage.addView(matchCard)

        // System vars init
        tvRam     = TextView(this).apply { text = "RAM --"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE; setPadding(dp(16),dp(4),dp(16),dp(8)) }
        tvBattery = TextView(this).apply { text = "BAT --"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE }
        tvTemp    = TextView(this).apply { text = ""; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE }
        tvMatches = TextView(this).apply { text = "0"; textSize = 9f; setTextColor(TXT_MUTED) }
        tvAddrFeed = TextView(this).apply { text = ""; setTextColor(TXT_SEC); textSize = 10f; typeface = Typeface.MONOSPACE }
        tvFooter  = TextView(this).apply { visibility = android.view.View.GONE; text = "" }
        tvStatus  = TextView(this).apply { visibility = android.view.View.GONE; text = "" }
        tvKps     = tvWps
        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16),0,dp(16),dp(8)) }
        sysRow.addView(tvRam); sysRow.addView(tvBattery)
        runPage.addView(sysRow)
        runPage.addView(tvFooter); runPage.addView(tvStatus)

        // ── CONFIG PAGE ───────────────────────────────────────────────────────
        val cfgPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            visibility = android.view.View.GONE
        }

        // Dataset
        cfgPage.addView(TextView(this).apply { text = "DATASET"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0,0,0,dp(8)) })
        val dataCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); clipToOutline = true; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        val csvRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnCsv = Button(this).apply {
            text = "Load CSV"; textSize = 11f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER) }
            setPadding(dp(18),0,dp(18),0); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(50))
            setOnClickListener { pickCsv() }
        }
        val tvCsvName = TextView(this).apply {
            text = if (csvPath.isNotEmpty() && File(csvPath).exists()) File(csvPath).name else "No file"
            setTextColor(TXT_MUTED); textSize = 10f; typeface = Typeface.MONOSPACE
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14),0,dp(14),0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        csvRow.addView(btnCsv); csvRow.addView(tvCsvName); dataCard.addView(csvRow)
        cfgPage.addView(dataCard)

        // Threads + CPU
        cfgPage.addView(TextView(this).apply { text = "PERFORMANCE"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0,0,0,dp(8)) })
        val perfCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        tvThreads = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16),dp(12),dp(16),dp(4)) }
        sbThreads = SeekBar(this).apply { max = 7; progress = prefs.getInt("threads",3); setPadding(dp(16),0,dp(16),dp(4)); setOnSeekBarChangeListener(mkSbl { updateLabels() }) }
        tvCpu = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16),dp(8),dp(16),dp(4)) }
        sbCpu = SeekBar(this).apply { max = 90; progress = prefs.getInt("cpu",70); setPadding(dp(16),0,dp(16),dp(12)); setOnSeekBarChangeListener(mkSbl { updateLabels(); if(HunterEngine.isRunning()) HunterEngine.setCpuLimit(sbCpu.progress+10) }) }
        perfCard.addView(tvThreads); perfCard.addView(sbThreads); perfCard.addView(tvCpu); perfCard.addView(sbCpu)
        cfgPage.addView(perfCard)

        // Fast mode
        cfgPage.addView(TextView(this).apply { text = "SCAN MODE"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0,0,0,dp(8)) })
        val fastCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        val fastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)) }
        val fastLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        fastLeft.addView(TextView(this).apply { text = "Fast Scan Mode"; textSize = 13f; setTextColor(TXT_PRI) })
        fastLeft.addView(TextView(this).apply { text = "1 iter — 60k/s vs 9k/s standard"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE; setPadding(0,dp(2),0,0) })
        val fastSwitch = android.widget.Switch(this).apply {
            isChecked = fastModeEnabled
            setOnCheckedChangeListener { _, checked ->
                fastModeEnabled = checked
                HunterEngine.setPbkdf2Mode(if(checked) 1 else 0)
                prefs.edit().putBoolean("fastMode", checked).apply()
            }
        }
        fastModeEnabled = prefs.getBoolean("fastMode", false)
        HunterEngine.setPbkdf2Mode(if(fastModeEnabled) 1 else 0)
        fastSwitch.isChecked = fastModeEnabled
        fastRow.addView(fastLeft); fastRow.addView(fastSwitch); fastCard.addView(fastRow)
        cfgPage.addView(fastCard)

        // ── LOGS PAGE ─────────────────────────────────────────────────────────
        val logsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
            visibility = android.view.View.GONE
        }
        logsPage.addView(TextView(this).apply { text = "SCAN LOGS"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0,0,0,dp(10)) })

        fun refreshScanLogs() {
            logsPage.removeViews(1, logsPage.childCount - 1)
            val dir = getExternalFilesDir(null) ?: filesDir
            val files = dir.listFiles { f -> f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (files.isEmpty()) {
                logsPage.addView(TextView(this).apply { text = "No logs yet"; textSize = 11f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE; setPadding(0,dp(8),0,0) })
            }
            files.forEach { f ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = cardBg(); setPadding(dp(12),dp(10),dp(12),dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val tv = TextView(this).apply {
                    text = f.name; textSize = 10f; setTextColor(TXT_PRI); typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnView = Button(this).apply {
                    text = "View"; textSize = 9f; setTextColor(AMBER)
                    background = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(4).toFloat() }
                    setPadding(dp(10),dp(4),dp(10),dp(4))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle(f.name)
                            .setMessage(f.readText()).setPositiveButton("OK", null).show()
                    }
                }
                val btnShare = Button(this).apply {
                    text = "Share"; textSize = 9f; setTextColor(TXT_MUTED)
                    background = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(4).toFloat() }
                    setPadding(dp(10),dp(4),dp(10),dp(4))
                    setOnClickListener {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", f)
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type="text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        startActivity(android.content.Intent.createChooser(i, "Share log"))
                    }
                }
                row.addView(tv); row.addView(btnView); row.addView(btnShare)
                logsPage.addView(row)
            }
        }

        // ── Sub-tab logic ─────────────────────────────────────────────────────
        val scanSubPages = listOf(runPage, cfgPage, logsPage)
        val scanSubBtns  = listOf(sbRun, sbCfg, sbLogs)
        fun goScanSub(idx: Int) {
            scanSubPages.forEachIndexed { i, v -> v.visibility = if (i==idx) android.view.View.VISIBLE else android.view.View.GONE }
            scanSubBtns.forEachIndexed  { i, b -> b.setTextColor(if (i==idx) AMBER else TXT_MUTED) }
            if (idx == 2) refreshScanLogs()
        }
        sbRun.setOnClickListener  { goScanSub(0) }
        sbCfg.setOnClickListener  { goScanSub(1) }
        sbLogs.setOnClickListener { goScanSub(2) }
        goScanSub(0)

        scanSubPages.forEach { subFrame.addView(it) }
        root.addView(subFrame)
        scroll.addView(root)
        return scroll
    }'''

# ── Nueva buildPuzzleTab ───────────────────────────────────────────────────────
new_puzzle = '''    private fun buildPuzzleTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP) }

        // Sub-tab bar
        val subBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        }
        fun subBtn(lbl: String): TextView = TextView(this).apply {
            text = lbl; textSize = 10f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER; isAllCaps = true; letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val pbRun  = subBtn("▶ Run")
        val pbCfg  = subBtn("⚙ Config")
        val pbLogs = subBtn("◉ Logs")
        listOf(pbRun, pbCfg, pbLogs).forEach { subBar.addView(it) }
        root.addView(subBar)

        val subFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // ── RUN PAGE ──────────────────────────────────────────────────────────
        val runPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(24)) }

        runPage.addView(TextView(this).apply { text = "⬡  PUZZLE HUNT"; textSize = 13f; setTextColor(AMBER); typeface = Typeface.create("monospace", Typeface.BOLD); setPadding(0,dp(4),0,dp(12)) })

        tvPuzzleStatus = TextView(this).apply {
            text = "Select a puzzle in Config tab"; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(GREEN)
            background = GradientDrawable().apply { setColor(0x1510D97A); setStroke(1,0x2510D97A); cornerRadius = dp(6).toFloat() }
            setPadding(dp(10),dp(8),dp(10),dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        runPage.addView(tvPuzzleStatus)

        // Start/Stop puzzle
        val pCoinGreen = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(12).toFloat() }
        val pCoinRed   = GradientDrawable().apply { setColor(RED);   cornerRadius = dp(12).toFloat() }
        val btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"; textSize = 15f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.12f; isAllCaps = true
            background = pCoinGreen
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(12) }
            setOnClickListener { doToggle() }
        }
        btnPuzzleToggle.tag = arrayOf(pCoinGreen, pCoinRed)
        runPage.addView(btnPuzzleToggle)

        // Balance checker
        val balCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            setPadding(dp(14),dp(12),dp(14),dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        balCard.addView(TextView(this).apply { text = "BALANCE CHECKER"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(0,0,0,dp(8)) })
        val tvBalResult = TextView(this).apply { text = "—"; textSize = 13f; setTextColor(TXT_PRI); typeface = Typeface.MONOSPACE }
        val btnCheckBal = Button(this).apply {
            text = "Check Balance"; textSize = 11f; setTextColor(android.graphics.Color.BLACK)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
            setOnClickListener {
                val addr = etTarget.text.toString().trim()
                if (addr.isEmpty()) { tvBalResult.text = "No address set"; return@setOnClickListener }
                tvBalResult.text = "Checking..."
                checkPuzzleBalance(addr) { bal ->
                    tvBalResult.text = if (bal > 0) "Balance: ${bal/100_000_000.0} BTC" else "Balance: 0 BTC"
                }
            }
        }
        balCard.addView(tvBalResult); balCard.addView(btnCheckBal)
        runPage.addView(balCard)

        // ── CONFIG PAGE ───────────────────────────────────────────────────────
        val cfgPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16),dp(14),dp(16),dp(24))
            visibility = android.view.View.GONE
        }

        cfgPage.addView(TextView(this).apply { text = "PUZZLE"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0,0,0,dp(8)) })
        val puzzleCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); setPadding(dp(14),dp(12),dp(14),dp(14)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }

        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size
        puzzleSpinner = Spinner(this).apply {
            adapter = themedAdapter(puzzles.map { "#${it.num}  -  ${it.btc}  -  ${it.addr.take(16)}..." })
            setSelection(defaultIdx)
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1,BORDER_C); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        val btnAutoSelect = Button(this).apply {
            text = "⚡ Auto-select best puzzle"; textSize = 11f; setTextColor(AMBER)
            background = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) }
            setOnClickListener { autoSelectPuzzle() }
        }
        puzzleCard.addView(puzzleSpinner); puzzleCard.addView(btnAutoSelect)

        fun fld(lbl: String): Pair<LinearLayout, EditText> {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) } }
            col.addView(TextView(this).apply { text = lbl; textSize = 9f; setTextColor(TXT_SEC); setPadding(0,0,0,dp(2)) })
            val et = EditText(this).apply {
                setTextColor(TXT_PRI); textSize = 10f; typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1,BORDER_C); cornerRadius = dp(6).toFloat() }
                setPadding(dp(8),dp(6),dp(8),dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            col.addView(et); return Pair(col, et)
        }
        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
        val (cS, eS) = fld("Range Start"); etRangeStart = eS; rangeRow.addView(cS)
        val (cE, eE) = fld("Range End");   etRangeEnd   = eE; rangeRow.addView(cE)
        puzzleCard.addView(rangeRow)

        puzzleCard.addView(TextView(this).apply { text = "Target Address"; textSize = 9f; setTextColor(TXT_SEC); setPadding(0,dp(4),0,dp(2)) })
        etTarget = EditText(this).apply {
            setTextColor(AMBER); textSize = 10f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1,BORDER_C); cornerRadius = dp(6).toFloat() }
            setPadding(dp(8),dp(6),dp(8),dp(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        puzzleCard.addView(etTarget)
        cfgPage.addView(puzzleCard)

        applyPuzzle(puzzles[defaultIdx])
        puzzleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) { if(init){init=false;return}; applyPuzzle(puzzles[pos]) }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }

        // Threads + CPU propios del puzzle
        cfgPage.addView(TextView(this).apply { text = "PERFORMANCE"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0,dp(12),0,dp(8)) })
        val perfCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        tvThreadsPuzzle = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16),dp(12),dp(16),dp(4)) }
        sbThreadsPuzzle = SeekBar(this).apply { max = 7; progress = prefs.getInt("puzzle_threads",3); setPadding(dp(16),0,dp(16),dp(4)); setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() }) }
        tvCpuPuzzle = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16),dp(8),dp(16),dp(4)) }
        sbCpuPuzzle = SeekBar(this).apply { max = 90; progress = prefs.getInt("puzzle_cpu",70); setPadding(dp(16),0,dp(16),dp(12)); setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() }) }
        perfCard.addView(tvThreadsPuzzle); perfCard.addView(sbThreadsPuzzle)
        perfCard.addView(tvCpuPuzzle); perfCard.addView(sbCpuPuzzle)
        cfgPage.addView(perfCard)
        updatePuzzleLabels()

        // ── LOGS PAGE ─────────────────────────────────────────────────────────
        val logsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(24))
            visibility = android.view.View.GONE
        }
        logsPage.addView(TextView(this).apply { text = "PUZZLE LOGS"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0,0,0,dp(10)) })
        logsPage.addView(TextView(this).apply { text = "No puzzle logs yet"; textSize = 11f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE })

        // Sub-tab logic
        val subPages = listOf(runPage, cfgPage, logsPage)
        val subBtns  = listOf(pbRun, pbCfg, pbLogs)
        fun goPuzzleSub(idx: Int) {
            subPages.forEachIndexed { i, v -> v.visibility = if(i==idx) android.view.View.VISIBLE else android.view.View.GONE }
            subBtns.forEachIndexed  { i, b -> b.setTextColor(if(i==idx) AMBER else TXT_MUTED) }
        }
        pbRun.setOnClickListener  { goPuzzleSub(0) }
        pbCfg.setOnClickListener  { goPuzzleSub(1) }
        pbLogs.setOnClickListener { goPuzzleSub(2) }
        goPuzzleSub(0)

        subPages.forEach { subFrame.addView(it) }
        root.addView(subFrame)
        scroll.addView(root)
        return scroll
    }

    private fun updatePuzzleLabels() {
        val t = sbThreadsPuzzle.progress + 1
        val c = sbCpuPuzzle.progress + 10
        tvThreadsPuzzle.text = "Threads: $t"
        tvCpuPuzzle.text = "CPU limit: $c%"
        prefs.edit().putInt("puzzle_threads", sbThreadsPuzzle.progress).putInt("puzzle_cpu", sbCpuPuzzle.progress).apply()
    }'''

# ── Aplicar al archivo ────────────────────────────────────────────────────────
# Reemplazar buildScanTab
scan_match = re.search(r'    private fun buildScanTab\(\): ScrollView \{.*?^    \}', content, re.DOTALL | re.MULTILINE)
if scan_match:
    content = content[:scan_match.start()] + new_scan + content[scan_match.end():]
    print("✓ buildScanTab reemplazado")
else:
    print("ERROR: buildScanTab no encontrado")

# Reemplazar buildPuzzleTab
puzzle_match = re.search(r'    private fun buildPuzzleTab\(\): ScrollView \{.*?^    \}', content, re.DOTALL | re.MULTILINE)
if puzzle_match:
    content = content[:puzzle_match.start()] + new_puzzle + content[puzzle_match.end():]
    print("✓ buildPuzzleTab reemplazado")
else:
    print("ERROR: buildPuzzleTab no encontrado")

# Agregar updatePuzzleLabels si no existe
if "fun updatePuzzleLabels" not in content:
    content = content.replace(
        "    private fun updateLabels()",
        "    private fun updatePuzzleLabels() {\n        if (!::sbThreadsPuzzle.isInitialized) return\n        val t = sbThreadsPuzzle.progress + 1\n        val c = sbCpuPuzzle.progress + 10\n        tvThreadsPuzzle.text = \"Threads: $t\"\n        tvCpuPuzzle.text = \"CPU limit: $c%\"\n        prefs.edit().putInt(\"puzzle_threads\", sbThreadsPuzzle.progress).putInt(\"puzzle_cpu\", sbCpuPuzzle.progress).apply()\n    }\n\n    private fun updateLabels()"
    )

with open(path, 'w') as f:
    f.write(content)

print(f"✓ Guardado. Líneas: {len(content.splitlines())}")
print("""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Rebuild C completado:

  Tab Scan:   [Run] [Config] [Logs]
  Tab Puzzle: [Run] [Config] [Logs]

Cada uno con sus propios threads/CPU.
Siguiente: git add + push
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
