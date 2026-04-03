#!/usr/bin/env python3
"""
Paso 3: Rediseño visual del Puzzle tab (mantiene toda la lógica)
"""

path = "app/src/main/java/com/hunter/btc/MainActivity.kt"
content = open(path).read()

OLD_PUZZLE = '''    private fun buildPuzzleTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
        }

        // ── PUZZLE SELECTOR ───────────────────────────────────────────────
        val selCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(14), dp(16), dp(8)) }
        }
        selCard.addView(TextView(this).apply {
            text = "BITCOIN PUZZLE"
            textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })

        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size

        try {
            puzzleSpinner = Spinner(this).apply {
                adapter = themedAdapter(puzzles.map { "#${it.num}  ${it.btc} BTC  ${it.addr.take(14)}..." })
                background = GradientDrawable().apply {
                    setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            selCard.addView(puzzleSpinner)
        } catch (e: Exception) {
            android.util.Log.e("PuzzleTab", "Spinner crash: ${e.message}", e)
            java.io.File((getExternalFilesDir(null)?.absolutePath ?: "/sdcard") + "/crash_log.txt")
                .appendText("\\nSPINNER: ${e.javaClass.name}\\n${e.message}\\n${e.stackTraceToString()}\\n")
        }
        page.addView(selCard)

        // ── RANGE CONFIG ──────────────────────────────────────────────────
        val rangeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        rangeCard.addView(TextView(this).apply {
            text = "RANGO HEX"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })

        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val colStart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }
        colStart.addView(TextView(this).apply { text = "Start"; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, 0, 0, dp(2)) })
        etRangeStart = EditText(this).apply {
            setTextColor(TXT_PRI); textSize = 10f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        colStart.addView(etRangeStart)

        val colEnd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        colEnd.addView(TextView(this).apply { text = "End"; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, 0, 0, dp(2)) })
        etRangeEnd = EditText(this).apply {
            setTextColor(TXT_PRI); textSize = 10f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        colEnd.addView(etRangeEnd)
        rangeRow.addView(colStart); rangeRow.addView(colEnd)
        rangeCard.addView(rangeRow)

        rangeCard.addView(TextView(this).apply { text = "Target Address"; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, dp(2), 0, dp(2)) })
        etTarget = EditText(this).apply {
            setTextColor(AMBER); textSize = 10f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rangeCard.addView(etTarget)
        page.addView(rangeCard)

        // Aplicar puzzle por defecto DESPUÉS de que los EditText estén listos
        suppressPuzzleListener = true
        puzzleSpinner?.setSelection(defaultIdx)
        applyPuzzle(puzzles[defaultIdx])
        suppressPuzzleListener = false

        // Listener del spinner
        puzzleSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (init) { init = false; return }
                if (!suppressPuzzleListener && puzzleTabReady) applyPuzzle(puzzles[pos])
            }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }

        // ── PERFORMANCE ───────────────────────────────────────────────────
        val perfCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        perfCard.addView(TextView(this).apply {
            text = "PERFORMANCE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(dp(14), dp(12), dp(14), dp(4))
        })
        tvThreadsPuzzle = TextView(this).apply {
            setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(14), dp(4), dp(14), dp(2))
        }
        sbThreadsPuzzle = SeekBar(this).apply {
            max = 7; progress = prefs.getInt("puzzle_threads", 3)
            setPadding(dp(14), 0, dp(14), dp(4))
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        tvCpuPuzzle = TextView(this).apply {
            setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(14), dp(4), dp(14), dp(2))
        }
        sbCpuPuzzle = SeekBar(this).apply {
            max = 90; progress = prefs.getInt("puzzle_cpu", 70)
            setPadding(dp(14), 0, dp(14), dp(12))
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        perfCard.addView(tvThreadsPuzzle)
        perfCard.addView(sbThreadsPuzzle)
        perfCard.addView(tvCpuPuzzle)
        perfCard.addView(sbCpuPuzzle)
        page.addView(perfCard)
        updatePuzzleLabels()

        // ── HERRAMIENTAS ──────────────────────────────────────────────────
        val toolsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        toolsCard.addView(TextView(this).apply {
            text = "HERRAMIENTAS"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(dp(14), dp(12), dp(14), dp(4))
        })
        listOf(
            Triple("⏰", "Programar Puzzle", { showSchedulerDialog() }),
            Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
            Triple("📤", "Exportar Config", { exportConfig() }),
            Triple("📥", "Importar Config", { importConfig() })
        ).forEach { (icon, label, action) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(TextView(this).apply {
                text = icon; textSize = 18f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            })
            row.addView(TextView(this).apply {
                text = label; textSize = 12f; setTextColor(TXT_PRI)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply { text = "›"; textSize = 18f; setTextColor(TXT_MUTED) })
            toolsCard.addView(row)
            toolsCard.addView(View(this).apply {
                setBackgroundColor(BORDER_C)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(dp(14), 0, dp(14), 0) }
            })
        }
        page.addView(toolsCard)

        // ── DIVIDER ───────────────────────────────────────────────────────
        page.addView(View(this).apply {
            setBackgroundColor(BORDER_C)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
        })

        // ── STATUS ────────────────────────────────────────────────────────
        tvPuzzleStatus = TextView(this).apply {
            text = "Selecciona un puzzle"
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(GREEN)
            background = GradientDrawable().apply {
                setColor(0x1510D97A); setStroke(1, 0x2510D97A); cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }
        }
        page.addView(tvPuzzleStatus)

        // ── HERO STATS ────────────────────────────────────────────────────
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(BG_PANEL) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        val heroLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroLeft.addView(TextView(this).apply {
            text = "VELOCIDAD"; textSize = 8f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f
        })
        val tvWpsP = TextView(this).apply {
            text = "0"; textSize = 38f; setTextColor(TXT_PRI)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        tvWpsPuzzle = tvWpsP
        heroLeft.addView(tvWpsP)
        heroLeft.addView(TextView(this).apply { text = "keys/sec"; textSize = 9f; setTextColor(TXT_SEC) })

        val heroRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
        }
        val tvCntP = TextView(this).apply {
            text = "0"; textSize = 14f; setTextColor(TXT_PRI)
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END
        }
        val tvTmP = TextView(this).apply {
            text = "00:00:00"; textSize = 14f; setTextColor(TXT_PRI)
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END
        }
        tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
        tvPctPuzzle = TextView(this).apply {
            text = "0.000000000000000000%"; textSize = 9f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END
        }
        tvBlockProgress = TextView(this).apply {
            text = "Bloques: 0/—"; textSize = 8f; setTextColor(TXT_MUTED)
            typeface = Typeface.MONOSPACE; gravity = Gravity.END
        }
        heroRight.addView(tvCntP)
        heroRight.addView(TextView(this).apply { text = "SCANNED"; textSize = 7f; setTextColor(TXT_MUTED); gravity = Gravity.END; letterSpacing = 0.12f; setPadding(0, dp(2), 0, dp(6)) })
        heroRight.addView(tvTmP)
        heroRight.addView(TextView(this).apply { text = "ELAPSED"; textSize = 7f; setTextColor(TXT_MUTED); gravity = Gravity.END; letterSpacing = 0.12f; setPadding(0, dp(2), 0, dp(6)) })
        heroRight.addView(tvPctPuzzle)
        heroRight.addView(tvBlockProgress)
        heroCard.addView(heroLeft); heroCard.addView(heroRight)
        page.addView(heroCard)

        // ── CHECKPOINT ────────────────────────────────────────────────────
        tvCheckpointLive = TextView(this).apply {
            text = ""
            textSize = 9f; setTextColor(AppTheme.CYAN); typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(2), dp(16), dp(4))
        }
        val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
        val savedKey  = puzzlePrefs.getString("last_key_${puzzles[defaultIdx].num}", null)
        val savedTime = puzzlePrefs.getLong("last_time_${puzzles[defaultIdx].num}", 0)
        if (savedKey != null && savedTime > 0) {
            val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTime))
            tvCheckpointLive?.text = "Checkpoint #${puzzles[defaultIdx].num}: $ts  ${savedKey.take(12)}...${savedKey.takeLast(6)}"
        }
        page.addView(tvCheckpointLive)

        // ── START / STOP ──────────────────────────────────────────────────
        val startGreen = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(12).toFloat() }
        val startRed   = GradientDrawable().apply { setColor(RED);   cornerRadius = dp(12).toFloat() }
        btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"
            textSize = 15f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.10f; isAllCaps = true
            background = startGreen
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }
            setOnClickListener {
                puzzleMode = true
                HunterEngine.setMode(1)
                doToggle(btnPuzzleToggle)
            }
        }
        btnPuzzleToggle?.tag = arrayOf(startGreen, startRed)
        page.addView(btnPuzzleToggle)

        // ── TEMPERATURA ───────────────────────────────────────────────────
        val tvThermalPuzzle = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(AppTheme.GREEN)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(2), dp(16), dp(4))
        }
        tvThermal = tvThermalPuzzle
        page.addView(tvThermalPuzzle)

        // ── BALANCE ───────────────────────────────────────────────────────
        val tvBalResult = TextView(this).apply {
            text = "Verificando balance..."
            textSize = 11f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        page.addView(tvBalResult)

        // Verificar balance en background
        Thread {
            checkPuzzleBalance(puzzles[defaultIdx].addr) { bal ->
                runOnUiThread {
                    if (bal > 0) {
                        tvBalResult.text = "Balance: ${bal / 100_000_000.0} BTC"
                        tvBalResult.setTextColor(GREEN)
                    } else {
                        autoSelectPuzzle()
                        tvBalResult.text = "Buscando puzzle con fondos..."
                    }
                }
            }
        }.start()

        scroll.addView(page)
        puzzleTabReady = true
        return scroll
    }'''

NEW_PUZZLE = '''    private fun buildPuzzleTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF0087FF.toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E14.toInt())
            setPadding(0, 0, 0, dp(80))
        }

        // ── HELPER card bg ────────────────────────────────────────────────
        fun pCard(marginTop: Int = 10): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF111520.toInt()); cornerRadius = dp(16).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(12), dp(marginTop), dp(12), 0) }
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
        }

        fun sectionLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        fun styledInput(hint: String, color: Int = 0xFFE8EAF0.toInt()): EditText {
            return EditText(this).apply {
                this.hint = hint
                setTextColor(color)
                setHintTextColor(0xFF3A4060.toInt())
                textSize = 11f; typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply {
                    setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }

        // ── PUZZLE SELECTOR ───────────────────────────────────────────────
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size

        val selCard = pCard(14)
        selCard.addView(sectionLabel("BITCOIN PUZZLE"))

        try {
            puzzleSpinner = Spinner(this).apply {
                adapter = themedAdapter(puzzles.map { "#${it.num}  ${it.btc} BTC  ${it.addr.take(14)}..." })
                background = GradientDrawable().apply {
                    setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            selCard.addView(puzzleSpinner)
        } catch (e: Exception) {
            android.util.Log.e("PuzzleTab", "Spinner crash: ${e.message}", e)
            java.io.File((getExternalFilesDir(null)?.absolutePath ?: "/sdcard") + "/crash_log.txt")
                .appendText("\\nSPINNER: ${e.javaClass.name}\\n${e.message}\\n${e.stackTraceToString()}\\n")
        }
        page.addView(selCard)

        // ── RANGE CONFIG ──────────────────────────────────────────────────
        val rangeCard = pCard()
        rangeCard.addView(sectionLabel("RANGO HEX"))

        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val colStart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        colStart.addView(TextView(this).apply {
            text = "Start"; textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(4))
        })
        etRangeStart = styledInput("0x...")
        colStart.addView(etRangeStart)

        val colEnd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        colEnd.addView(TextView(this).apply {
            text = "End"; textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(4))
        })
        etRangeEnd = styledInput("0x...")
        colEnd.addView(etRangeEnd)

        rangeRow.addView(colStart); rangeRow.addView(colEnd)
        rangeCard.addView(rangeRow)

        rangeCard.addView(TextView(this).apply {
            text = "Target Address"; textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(4))
        })
        etTarget = styledInput("1A2B3C...", 0xFF00C896.toInt())
        rangeCard.addView(etTarget)
        page.addView(rangeCard)

        // Aplicar puzzle por defecto
        suppressPuzzleListener = true
        puzzleSpinner?.setSelection(defaultIdx)
        applyPuzzle(puzzles[defaultIdx])
        suppressPuzzleListener = false

        // Listener del spinner
        puzzleSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (init) { init = false; return }
                if (!suppressPuzzleListener && puzzleTabReady) applyPuzzle(puzzles[pos])
            }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }

        // ── STATUS ────────────────────────────────────────────────────────
        tvPuzzleStatus = TextView(this).apply {
            text = "Selecciona un puzzle"
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(ACCENT)
            background = GradientDrawable().apply {
                setColor(0x1400C896.toInt()); setStroke(1, 0x2A00C896.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
        }
        page.addView(tvPuzzleStatus)

        // ── STATS GRID ────────────────────────────────────────────────────
        val statsCard = pCard()
        statsCard.addView(sectionLabel("RENDIMIENTO EN VIVO"))

        // Speed hero
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val tvWpsP = TextView(this).apply {
            text = "0"; textSize = 40f; setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvWpsPuzzle = tvWpsP
        speedRow.addView(tvWpsP)
        val speedUnit = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        }
        speedUnit.addView(TextView(this).apply {
            text = "kKeys"; textSize = 11f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        })
        speedUnit.addView(TextView(this).apply {
            text = "por seg"; textSize = 10f; setTextColor(0xFF3A4060.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        })
        speedRow.addView(speedUnit)
        statsCard.addView(speedRow)

        // Stats 2x2
        fun miniStat(label: String, tv: TextView): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = GradientDrawable().apply {
                    setColor(0xFF171C2C.toInt()); cornerRadius = dp(10).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 8f; setTextColor(0xFF5A607A.toInt())
                    typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
                })
                addView(tv)
            }
        }

        val tvCntP = TextView(this).apply {
            text = "0"; textSize = 16f; setTextColor(0xFFE8EAF0.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        val tvTmP = TextView(this).apply {
            text = "00:00:00"; textSize = 16f; setTextColor(0xFFE8EAF0.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
        tvPctPuzzle = TextView(this).apply {
            text = "0.000%"; textSize = 13f; setTextColor(ACCENT2)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        tvBlockProgress = TextView(this).apply {
            text = "0/—"; textSize = 13f; setTextColor(0xFFE8EAF0.toInt())
            typeface = Typeface.MONOSPACE
        }

        val miniRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val miniRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        miniRow1.addView(miniStat("SCANNED", tvCntP).also {
            it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = dp(8) }
        })
        miniRow1.addView(miniStat("ELAPSED", tvTmP))
        miniRow2.addView(miniStat("PROGRESO", tvPctPuzzle).also {
            it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = dp(8) }
        })
        miniRow2.addView(miniStat("BLOQUES", tvBlockProgress))
        statsCard.addView(miniRow1); statsCard.addView(miniRow2)
        page.addView(statsCard)

        // ── CHECKPOINT ────────────────────────────────────────────────────
        tvCheckpointLive = TextView(this).apply {
            text = ""
            textSize = 10f; setTextColor(0xFF00C896.toInt()); typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(0x0A00C896.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0x1500C896.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
        }
        val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
        val savedKey  = puzzlePrefs.getString("last_key_${puzzles[defaultIdx].num}", null)
        val savedTime = puzzlePrefs.getLong("last_time_${puzzles[defaultIdx].num}", 0)
        if (savedKey != null && savedTime > 0) {
            val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTime))
            tvCheckpointLive?.text = "✓ Checkpoint #${puzzles[defaultIdx].num}: $ts  ${savedKey.take(12)}...${savedKey.takeLast(6)}"
        }
        page.addView(tvCheckpointLive)

        // ── PERFORMANCE ───────────────────────────────────────────────────
        val perfCard = pCard()
        perfCard.addView(sectionLabel("CONFIGURACIÓN"))
        tvThreadsPuzzle = TextView(this).apply {
            setTextColor(0xFFE8EAF0.toInt()); textSize = 12f
        }
        sbThreadsPuzzle = SeekBar(this).apply {
            max = 7; progress = prefs.getInt("puzzle_threads", 3)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        tvCpuPuzzle = TextView(this).apply {
            setTextColor(0xFFE8EAF0.toInt()); textSize = 12f
        }
        sbCpuPuzzle = SeekBar(this).apply {
            max = 90; progress = prefs.getInt("puzzle_cpu", 70)
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        perfCard.addView(tvThreadsPuzzle); perfCard.addView(sbThreadsPuzzle)
        perfCard.addView(tvCpuPuzzle); perfCard.addView(sbCpuPuzzle)
        page.addView(perfCard)
        updatePuzzleLabels()

        // ── HERRAMIENTAS ──────────────────────────────────────────────────
        val toolsCard = pCard()
        toolsCard.addView(sectionLabel("HERRAMIENTAS"))
        listOf(
            Triple("⏰", "Programar Puzzle", { showSchedulerDialog() }),
            Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
            Triple("📤", "Exportar Config", { exportConfig() }),
            Triple("📥", "Importar Config", { importConfig() })
        ).forEach { (icon, label, action) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(TextView(this).apply {
                text = icon; textSize = 16f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
            })
            row.addView(TextView(this).apply {
                text = label; textSize = 12f; setTextColor(0xFFE8EAF0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply { text = "›"; textSize = 16f; setTextColor(0xFF3A4060.toInt()) })
            toolsCard.addView(row)
            toolsCard.addView(android.view.View(this).apply {
                setBackgroundColor(0xFF1E2540.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
        page.addView(toolsCard)

        // ── THERMAL ───────────────────────────────────────────────────────
        val tvThermalPuzzle = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(ACCENT)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(6), dp(12), dp(4))
        }
        tvThermal = tvThermalPuzzle
        page.addView(tvThermalPuzzle)

        // ── BALANCE ───────────────────────────────────────────────────────
        val tvBalResult = TextView(this).apply {
            text = "Verificando balance..."
            textSize = 11f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        page.addView(tvBalResult)

        Thread {
            checkPuzzleBalance(puzzles[defaultIdx].addr) { bal ->
                runOnUiThread {
                    if (bal > 0) {
                        tvBalResult.text = "Balance: ${bal / 100_000_000.0} BTC"
                        tvBalResult.setTextColor(ACCENT)
                    } else {
                        autoSelectPuzzle()
                        tvBalResult.text = "Buscando puzzle con fondos..."
                    }
                }
            }
        }.start()

        // ── START / STOP ──────────────────────────────────────────────────
        val startBg = GradientDrawable().apply {
            colors = intArrayOf(ACCENT, ACCENT2)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = dp(16).toFloat()
        }
        val stopRed = GradientDrawable().apply {
            colors = intArrayOf(0xFFFF6B35.toInt(), 0xFFFF3B6B.toInt())
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = dp(16).toFloat()
        }
        btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"
            textSize = 16f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.1f; isAllCaps = true
            background = startBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { setMargins(dp(12), dp(12), dp(12), dp(8)) }
            setOnClickListener {
                puzzleMode = true
                HunterEngine.setMode(1)
                doToggle(btnPuzzleToggle)
            }
        }
        btnPuzzleToggle?.tag = arrayOf(startBg, stopRed)
        page.addView(btnPuzzleToggle)

        scroll.addView(page)
        puzzleTabReady = true
        return scroll
    }'''

if OLD_PUZZLE in content:
    content = content.replace(OLD_PUZZLE, NEW_PUZZLE)
    print("✓ Puzzle tab rediseñado correctamente")
    open(path, 'w').write(content)
    print("✅ Paso 3 completado. Ejecuta: git add -A && git commit -m 'Redesign Step 3: Puzzle tab dashboard style' && git push")
else:
    print("✗ ERROR: No se encontró el bloque exacto del Puzzle tab")
    checks = [
        "private fun buildPuzzleTab(): ScrollView",
        "puzzleSpinner?.onItemSelectedListener",
        "btnPuzzleToggle?.tag = arrayOf(startGreen",
        "puzzleTabReady = true"
    ]
    for c in checks:
        print(f"  {'✓' if c in content else '✗'} '{c}'")
