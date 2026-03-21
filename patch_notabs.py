#!/usr/bin/env python3
"""
patch_notabs.py
Elimina los sub-tabs [Run][Config] de Scan y Puzzle.
Todo queda en un solo scroll vertical: Config arriba, Run abajo.
"""

import re, os
path = "app/src/main/java/com/hunter/btc/MainActivity.kt"
content = open(path).read()

# ── Nueva buildScanTab sin sub-tabs ──────────────────────────────────────────
new_scan = '''    private fun buildScanTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
        }

        // ── CONFIG SECTION ────────────────────────────────────────────────
        val cfgSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }

        // Dataset
        cfgSection.addView(TextView(this).apply {
            text = "DATASET"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })
        val dataCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = cardBg(); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        btnCsv = Button(this).apply {
            text = "Load CSV"; textSize = 11f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER) }
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(50))
            setOnClickListener { pickCsv() }
        }
        val tvCsvNameLocal = TextView(this).apply {
            text = if (csvPath.isNotEmpty() && File(csvPath).exists()) File(csvPath).name else "No file"
            setTextColor(TXT_MUTED); textSize = 10f; typeface = Typeface.MONOSPACE
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvCsvName = tvCsvNameLocal
        dataCard.addView(btnCsv); dataCard.addView(tvCsvNameLocal)
        cfgSection.addView(dataCard)

        // Threads + CPU
        cfgSection.addView(TextView(this).apply {
            text = "PERFORMANCE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })
        val perfCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        tvThreads = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(12), dp(16), dp(4)) }
        sbThreads = SeekBar(this).apply {
            max = 7; progress = prefs.getInt("threads", 3)
            setPadding(dp(16), 0, dp(16), dp(4))
            setOnSeekBarChangeListener(mkSbl { updateLabels() })
        }
        tvCpu = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        sbCpu = SeekBar(this).apply {
            max = 90; progress = prefs.getInt("cpu", 70)
            setPadding(dp(16), 0, dp(16), dp(12))
            setOnSeekBarChangeListener(mkSbl {
                updateLabels()
                if (HunterEngine.isRunning()) HunterEngine.setCpuLimit(sbCpu.progress + 10)
            })
        }
        perfCard.addView(tvThreads); perfCard.addView(sbThreads)
        perfCard.addView(tvCpu); perfCard.addView(sbCpu)
        cfgSection.addView(perfCard)

        // Fast mode
        cfgSection.addView(TextView(this).apply {
            text = "SCAN MODE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })
        val fastCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
        val fastRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val fastLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fastLeft.addView(TextView(this).apply { text = "Fast Scan Mode"; textSize = 13f; setTextColor(TXT_PRI) })
        fastLeft.addView(TextView(this).apply {
            text = "1 iter — 60k/s vs 9k/s standard"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.MONOSPACE; setPadding(0, dp(2), 0, 0)
        })
        val fastSwitch = android.widget.Switch(this).apply {
            isChecked = fastModeEnabled
            setOnCheckedChangeListener { _, checked ->
                fastModeEnabled = checked
                HunterEngine.setPbkdf2Mode(if (checked) 1 else 0)
                prefs.edit().putBoolean("fastMode", checked).apply()
            }
        }
        fastModeEnabled = prefs.getBoolean("fastMode", false)
        HunterEngine.setPbkdf2Mode(if (fastModeEnabled) 1 else 0)
        fastSwitch.isChecked = fastModeEnabled
        fastRow.addView(fastLeft); fastRow.addView(fastSwitch); fastCard.addView(fastRow)
        cfgSection.addView(fastCard)
        page.addView(cfgSection)

        // ── DIVIDER ───────────────────────────────────────────────────────
        page.addView(View(this).apply {
            setBackgroundColor(BORDER_C)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
        })

        // ── RUN SECTION ───────────────────────────────────────────────────
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
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.18f; setPadding(0, 0, 0, dp(4))
        })
        tvWps = TextView(this).apply { text = "0"; textSize = 50f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD) }
        heroLeft.addView(tvWps)
        heroLeft.addView(TextView(this).apply { text = "keys / second"; textSize = 11f; setTextColor(TXT_SEC); setPadding(0, dp(3), 0, 0) })
        val heroRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        tvCount = TextView(this).apply { text = "0"; textSize = 17f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        tvTime  = TextView(this).apply { text = "00:00:00"; textSize = 17f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        heroRight.addView(tvCount)
        heroRight.addView(TextView(this).apply { text = "SCANNED"; textSize = 8f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0, dp(2), 0, dp(12)) })
        heroRight.addView(tvTime)
        heroRight.addView(TextView(this).apply { text = "ELAPSED"; textSize = 8f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0, dp(2), 0, 0) })
        heroBlock.addView(heroLeft); heroBlock.addView(heroRight)
        page.addView(heroBlock)
        page.addView(View(this).apply { setBackgroundColor(BORDER_C); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })

        // Start/Stop button
        val coinGreen = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(12).toFloat() }
        val coinRed   = GradientDrawable().apply { setColor(RED);   cornerRadius = dp(12).toFloat() }
        btnToggle = Button(this).apply {
            text = s.start; textSize = 15f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.12f; isAllCaps = true
            background = coinGreen
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            setOnClickListener {
                puzzleMode = false
                HunterEngine.setMode(0)
                doToggle(btnToggle)
            }
        }
        btnToggle.tag = arrayOf(coinGreen, coinRed)
        val actZone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        actZone.addView(btnToggle); page.addView(actZone)

        // Speed chart
        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        chartCard.addView(TextView(this).apply { text = "SPEED HISTORY"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(0, 0, 0, dp(10)) })
        chartView = SpeedChartView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)) }
        chartCard.addView(chartView); page.addView(chartCard)

        // Matches
        val matchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        matchCard.addView(TextView(this).apply { text = "MATCHES"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(dp(14), dp(10), dp(14), dp(4)) })
        tvMatchList = TextView(this).apply {
            text = "No matches yet"; setTextColor(TXT_MUTED); textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }
        matchCard.addView(tvMatchList); page.addView(matchCard)

        // System info
        tvRam      = TextView(this).apply { text = "RAM --"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE }
        tvBattery  = TextView(this).apply { text = "BAT --"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE }
        tvTemp     = TextView(this).apply { text = ""; textSize = 9f; setTextColor(TXT_MUTED) }
        tvMatches  = TextView(this).apply { text = "0"; textSize = 9f; setTextColor(TXT_MUTED) }
        tvAddrFeed = TextView(this).apply { text = ""; setTextColor(TXT_SEC); textSize = 10f; typeface = Typeface.MONOSPACE }
        tvFooter   = TextView(this).apply { visibility = android.view.View.GONE; text = "" }
        tvStatus   = TextView(this).apply { visibility = android.view.View.GONE; text = "" }
        tvKps      = tvWps
        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(4), dp(16), dp(20)) }
        sysRow.addView(tvRam); sysRow.addView(tvBattery)
        page.addView(sysRow)

        // Dataset status
        val tvDatasetStatus = TextView(this).apply {
            text = if (csvPath.isNotEmpty() && File(csvPath).exists()) "Dataset: ${File(csvPath).name}" else "No dataset loaded"
            textSize = 10f; typeface = Typeface.MONOSPACE
            setTextColor(if (csvPath.isNotEmpty() && File(csvPath).exists()) 0xFF00FF88.toInt() else TXT_MUTED)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        page.addView(tvDatasetStatus)
        page.addView(tvFooter); page.addView(tvStatus)
        page.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)) })

        scroll.addView(page)
        return scroll
    }'''

# ── Nueva buildPuzzleTab sin sub-tabs ────────────────────────────────────────
new_puzzle = '''    private fun buildPuzzleTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP)
        }

        // ── CONFIG SECTION ────────────────────────────────────────────────
        val cfgSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        cfgSection.addView(TextView(this).apply {
            text = "PUZZLE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })

        val puzzleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size
        puzzleSpinner = Spinner(this).apply {
            adapter = themedAdapter(puzzles.map { "#${it.num}  -  ${it.btc}  -  ${it.addr.take(16)}..." })
            setSelection(defaultIdx)
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
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
            col.addView(TextView(this).apply { text = lbl; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, 0, 0, dp(2)) })
            val et = EditText(this).apply {
                setTextColor(TXT_PRI); textSize = 10f; typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            col.addView(et); return Pair(col, et)
        }
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val (cS, eS) = fld("Range Start"); etRangeStart = eS; rangeRow.addView(cS)
        val (cE, eE) = fld("Range End");   etRangeEnd   = eE; rangeRow.addView(cE)
        puzzleCard.addView(rangeRow)
        puzzleCard.addView(TextView(this).apply { text = "Target Address"; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, dp(4), 0, dp(2)) })
        etTarget = EditText(this).apply {
            setTextColor(AMBER); textSize = 10f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        puzzleCard.addView(etTarget)
        cfgSection.addView(puzzleCard)
        applyPuzzle(puzzles[defaultIdx])
        puzzleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) { if (init) { init = false; return }; applyPuzzle(puzzles[pos]) }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }

        // Threads + CPU puzzle
        cfgSection.addView(TextView(this).apply {
            text = "PERFORMANCE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0, dp(8), 0, dp(8))
        })
        val perfCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
        tvThreadsPuzzle = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(12), dp(16), dp(4)) }
        sbThreadsPuzzle = SeekBar(this).apply { max = 7; progress = prefs.getInt("puzzle_threads", 3); setPadding(dp(16), 0, dp(16), dp(4)); setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() }) }
        tvCpuPuzzle = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        sbCpuPuzzle = SeekBar(this).apply { max = 90; progress = prefs.getInt("puzzle_cpu", 70); setPadding(dp(16), 0, dp(16), dp(12)); setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() }) }
        perfCard.addView(tvThreadsPuzzle); perfCard.addView(sbThreadsPuzzle)
        perfCard.addView(tvCpuPuzzle); perfCard.addView(sbCpuPuzzle)
        cfgSection.addView(perfCard)
        page.addView(cfgSection)
        updatePuzzleLabels()

        // ── DIVIDER ───────────────────────────────────────────────────────
        page.addView(View(this).apply {
            setBackgroundColor(BORDER_C)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(4), 0, dp(4)) }
        })

        // ── RUN SECTION ───────────────────────────────────────────────────
        val runSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // Status
        tvPuzzleStatus = TextView(this).apply {
            text = "Select a puzzle above"; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(GREEN)
            background = GradientDrawable().apply { setColor(0x1510D97A); setStroke(1, 0x2510D97A); cornerRadius = dp(6).toFloat() }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        runSection.addView(tvPuzzleStatus)

        // Speed hero puzzle
        val pHeroBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        val pHeroLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        pHeroLeft.addView(TextView(this).apply { text = "SPEED"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.18f })
        val tvWpsP = TextView(this).apply { text = "0"; textSize = 40f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD) }
        tvWpsPuzzle = tvWpsP; pHeroLeft.addView(tvWpsP)
        pHeroLeft.addView(TextView(this).apply { text = "keys / second"; textSize = 10f; setTextColor(TXT_SEC) })
        val pHeroRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val tvCntP = TextView(this).apply { text = "0"; textSize = 15f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        val tvTmP  = TextView(this).apply { text = "00:00:00"; textSize = 15f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
        pHeroRight.addView(tvCntP)
        pHeroRight.addView(TextView(this).apply { text = "SCANNED"; textSize = 8f; setTextColor(TXT_MUTED); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0, dp(2), 0, dp(8)) })
        pHeroRight.addView(tvTmP)
        pHeroRight.addView(TextView(this).apply { text = "ELAPSED"; textSize = 8f; setTextColor(TXT_MUTED); gravity = Gravity.END; letterSpacing = 0.13f })
        pHeroBlock.addView(pHeroLeft); pHeroBlock.addView(pHeroRight)
        runSection.addView(pHeroBlock)

        // Start/Stop puzzle
        val pCoinGreen = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(12).toFloat() }
        val pCoinRed   = GradientDrawable().apply { setColor(RED);   cornerRadius = dp(12).toFloat() }
        btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"; textSize = 15f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.12f; isAllCaps = true
            background = pCoinGreen
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(12) }
            setOnClickListener {
                puzzleMode = true
                HunterEngine.setMode(1)
                doToggle(btnPuzzleToggle)
            }
        }
        btnPuzzleToggle?.tag = arrayOf(pCoinGreen, pCoinRed)
        runSection.addView(btnPuzzleToggle)

        // Balance checker
        val balCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        balCard.addView(TextView(this).apply { text = "BALANCE CHECKER"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(0, 0, 0, dp(8)) })
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
                    tvBalResult.text = if (bal > 0) "Balance: ${bal / 100_000_000.0} BTC" else "Balance: 0 BTC"
                }
            }
        }
        balCard.addView(tvBalResult); balCard.addView(btnCheckBal)
        runSection.addView(balCard)
        page.addView(runSection)

        scroll.addView(page)
        return scroll
    }'''

# ── Reemplazar funciones ──────────────────────────────────────────────────────
scan_match = re.search(r'    private fun buildScanTab\(\): ScrollView \{.*?^    \}', content, re.DOTALL|re.MULTILINE)
if scan_match:
    content = content[:scan_match.start()] + new_scan + content[scan_match.end():]
    print("✓ buildScanTab reemplazado")
else:
    print("ERROR: buildScanTab no encontrado")

puzzle_match = re.search(r'    private fun buildPuzzleTab\(\): ScrollView \{.*?^    \}', content, re.DOTALL|re.MULTILINE)
if puzzle_match:
    content = content[:puzzle_match.start()] + new_puzzle + content[puzzle_match.end():]
    print("✓ buildPuzzleTab reemplazado")
else:
    print("ERROR: buildPuzzleTab no encontrado")

with open(path, 'w') as f:
    f.write(content)
print(f"✓ Guardado. Líneas: {len(content.splitlines())}")
