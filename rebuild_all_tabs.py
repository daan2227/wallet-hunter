#!/usr/bin/env python3
"""
Aplica el nuevo tema dark/neon a:
- buildPuzzleTab
- buildRecoveryTab  
- DebugActivity
"""
import re

path = "app/src/main/java/com/hunter/btc/MainActivity.kt"
content = open(path).read()

# ── REBUILD PUZZLE TAB ────────────────────────────────────────────────────────
start = content.find("    // ── BUILD PUZZLE TAB")
end   = content.find("    // ── BUILD WALLET TAB")

new_puzzle = '''    // ── BUILD PUZZLE TAB ──────────────────────────────────────────────────────
    private fun buildPuzzleTab(): ScrollView {
        val LIME = 0xFF39FF14.toInt()
        suppressPuzzleListener = true
        puzzleTabReady = false

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            setPadding(0, 0, 0, dp(80))
        }

        // ── HEADER MONITOR ────────────────────────────────────────────────
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111411.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        headerCard.addView(TextView(this).apply {
            text = "Puzzle Monitor"
            textSize = 13f; setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })
        val tvWpsP = TextView(this).apply {
            text = "0.0"; textSize = 56f; setTextColor(LIME)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(20f, 0f, 0f, 0x8039FF14.toInt())
        }
        tvWpsPuzzle = tvWpsP
        headerCard.addView(tvWpsP)
        headerCard.addView(TextView(this).apply {
            text = "Velocidad de Escaneo  kKeys / s"
            textSize = 11f; setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        })

        // Stats row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        fun statCol(tv: TextView, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(tv)
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 9f; setTextColor(0xFF777777.toInt())
                gravity = Gravity.CENTER; typeface = Typeface.create("monospace", Typeface.NORMAL)
            })
        }
        fun div() = View(this).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(1, dp(32)).apply { setMargins(0, dp(4), 0, 0) }
        }
        val tvCntP = TextView(this).apply {
            text = "0"; textSize = 18f; setTextColor(0xFFEEEEEE.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }
        tvCountPuzzle = tvCntP
        val tvPctStat = TextView(this).apply {
            text = "0.0%"; textSize = 14f; setTextColor(LIME)
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }
        tvPctPuzzle = tvPctStat
        val tvBlkStat = TextView(this).apply {
            text = "0"; textSize = 18f; setTextColor(0xFFEEEEEE.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }
        tvBlockProgress = tvBlkStat
        tvTimePuzzle = TextView(this).apply {
            text = "00:00:00"; textSize = 16f; setTextColor(0xFFEEEEEE.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }
        statsRow.addView(statCol(tvBlkStat, "Blocks"))
        statsRow.addView(div())
        statsRow.addView(statCol(tvCntP, "Total Keys"))
        statsRow.addView(div())
        statsRow.addView(statCol(tvPctStat, "Progress"))
        statsRow.addView(div())
        statsRow.addView(statCol(tvTimePuzzle!!, "Elapsed Time"))
        headerCard.addView(statsRow)

        // Progress bar
        val progressFill = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 10000; progress = 0
            progressDrawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF006600.toInt(), LIME)
            ).apply { cornerRadius = dp(20).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)
            )
            background = GradientDrawable().apply {
                setColor(0xFF1A2A1A.toInt()); cornerRadius = dp(20).toFloat()
            }
        }
        headerCard.addView(progressFill)
        page.addView(headerCard)

        // Helper collapsible
        fun section(icon: String, title: String, expanded: Boolean = false, build: LinearLayout.() -> Unit): LinearLayout {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF141814.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(1, 0xFF2A3028.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
            }
            val arrow = TextView(this).apply { text = if (expanded) "∧" else "∨"; textSize = 16f; setTextColor(0xFF666666.toInt()) }
            header.addView(TextView(this).apply {
                text = icon; textSize = 18f
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
                gravity = Gravity.CENTER
            })
            header.addView(TextView(this).apply {
                text = title; textSize = 14f; setTextColor(0xFFEEEEEE.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(arrow)
            container.addView(header)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
                setPadding(dp(16), 0, dp(16), dp(14))
            }
            body.build()
            container.addView(body)
            header.setOnClickListener {
                body.visibility = if (body.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                arrow.text = if (body.visibility == android.view.View.VISIBLE) "∧" else "∨"
            }
            return container
        }

        // ── SECTION: Puzzle Config ────────────────────────────────────────
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size

        page.addView(section("🧩", "Puzzle Bitcoin", expanded = true) {
            addView(TextView(this@MainActivity).apply {
                text = "Seleccionar Puzzle"; textSize = 10f; setTextColor(0xFF888888.toInt())
                setPadding(0, dp(4), 0, dp(6))
            })
            puzzleSpinner = Spinner(this@MainActivity).apply {
                adapter = themedAdapter(puzzles.map { "#${it.num}  ${it.btc} BTC  ${it.addr.take(14)}..." })
                background = GradientDrawable().apply {
                    setColor(0xFF1E2A1E.toInt()); setStroke(1, 0xFF2A3A2A.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            addView(puzzleSpinner)

            // Range fields
            val rangeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            fun hexField(label: String): EditText {
                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                }
                col.addView(TextView(this@MainActivity).apply { text = label; textSize = 9f; setTextColor(0xFF888888.toInt()); setPadding(0,0,0,dp(2)) })
                val et = EditText(this@MainActivity).apply {
                    setTextColor(0xFFCCCCCC.toInt()); textSize = 10f; typeface = Typeface.MONOSPACE
                    background = GradientDrawable().apply { setColor(0xFF1A2A1A.toInt()); setStroke(1, 0xFF2A3A2A.toInt()); cornerRadius = dp(6).toFloat() }
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                col.addView(et)
                rangeRow.addView(col)
                return et
            }
            etRangeStart = hexField("Start")
            etRangeEnd   = hexField("End")
            addView(rangeRow)

            addView(TextView(this@MainActivity).apply { text = "Target Address"; textSize = 9f; setTextColor(0xFF888888.toInt()); setPadding(0,0,0,dp(2)) })
            etTarget = EditText(this@MainActivity).apply {
                setTextColor(LIME); textSize = 10f; typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(0xFF1A2A1A.toInt()); setStroke(1, 0xFF2A3A2A.toInt()); cornerRadius = dp(6).toFloat() }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            addView(etTarget)

            // Status
            tvPuzzleStatus = TextView(this@MainActivity).apply {
                text = "Selecciona un puzzle"; textSize = 11f; typeface = Typeface.MONOSPACE
                setTextColor(LIME)
                background = GradientDrawable().apply { setColor(0x1539FF14); setStroke(1, 0x3039FF14); cornerRadius = dp(6).toFloat() }
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            addView(tvPuzzleStatus)

            // Checkpoint
            tvCheckpointLive = TextView(this@MainActivity).apply {
                text = ""; textSize = 9f; setTextColor(0xFF888888.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            }
            val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
            val savedKey  = puzzlePrefs.getString("last_key_${puzzles[defaultIdx].num}", null)
            val savedTime = puzzlePrefs.getLong("last_time_${puzzles[defaultIdx].num}", 0)
            if (savedKey != null && savedTime > 0) {
                val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTime))
                tvCheckpointLive?.text = "Checkpoint: $ts  ${savedKey.take(10)}...${savedKey.takeLast(6)}"
            }
            addView(tvCheckpointLive)
        })

        // ── SECTION: Performance ──────────────────────────────────────────
        page.addView(section("⚡", "Performance") {
            tvThreadsPuzzle = TextView(this@MainActivity).apply { setTextColor(0xFFCCCCCC.toInt()); textSize = 11f }
            addView(tvThreadsPuzzle)
            sbThreadsPuzzle = SeekBar(this@MainActivity).apply {
                max = 7; progress = prefs.getInt("puzzle_threads", 3)
                setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
            }
            addView(sbThreadsPuzzle)
            tvCpuPuzzle = TextView(this@MainActivity).apply { setTextColor(0xFFCCCCCC.toInt()); textSize = 11f; setPadding(0,dp(8),0,dp(2)) }
            addView(tvCpuPuzzle)
            sbCpuPuzzle = SeekBar(this@MainActivity).apply {
                max = 90; progress = prefs.getInt("puzzle_cpu", 70)
                setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
            }
            addView(sbCpuPuzzle)
        })

        // ── SECTION: Herramientas ─────────────────────────────────────────
        page.addView(section("🔧", "Herramientas") {
            listOf(
                Triple("⏰", "Programar Puzzle", { showSchedulerDialog() }),
                Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
                Triple("📤", "Exportar Config", { exportConfig() }),
                Triple("📥", "Importar Config", { importConfig() })
            ).forEach { (ic, lbl, action) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8)); isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }
                row.addView(TextView(this@MainActivity).apply { text = ic; textSize = 16f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginEnd = dp(10) } })
                row.addView(TextView(this@MainActivity).apply { text = lbl; textSize = 12f; setTextColor(0xFFCCCCCC.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                row.addView(TextView(this@MainActivity).apply { text = "›"; textSize = 16f; setTextColor(0xFF555555.toInt()) })
                addView(row)
                addView(View(this@MainActivity).apply { setBackgroundColor(0xFF1E2A1E.toInt()); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })
            }
        })

        // Temperatura
        val tvThermalPuzzle = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(LIME); typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        tvThermal = tvThermalPuzzle
        page.addView(tvThermalPuzzle)

        // Balance
        val tvBalResult = TextView(this).apply {
            text = "Verificando balance..."
            textSize = 11f; setTextColor(0xFF888888.toInt()); typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        page.addView(tvBalResult)

        // ── START BUTTON ──────────────────────────────────────────────────
        val startGreen = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF00CC00.toInt(), LIME)).apply { cornerRadius = dp(16).toFloat() }
        val startRed   = GradientDrawable().apply { setColor(RED); cornerRadius = dp(16).toFloat() }
        btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"
            textSize = 16f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.15f; isAllCaps = true
            background = startGreen
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) }
            setOnClickListener {
                puzzleMode = true; HunterEngine.setMode(1); doToggle(btnPuzzleToggle)
            }
        }
        btnPuzzleToggle?.tag = arrayOf(startGreen, startRed)
        page.addView(btnPuzzleToggle)

        // Init puzzles
        suppressPuzzleListener = true
        puzzleSpinner?.setSelection(defaultIdx)
        applyPuzzle(puzzles[defaultIdx])
        suppressPuzzleListener = false
        updatePuzzleLabels()

        puzzleSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (init) { init = false; return }
                if (!suppressPuzzleListener && puzzleTabReady) applyPuzzle(puzzles[pos])
            }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }

        // Balance en background
        Thread {
            checkPuzzleBalance(puzzles[defaultIdx].addr) { bal ->
                runOnUiThread {
                    if (bal > 0) {
                        tvBalResult.text = "Balance: ${bal / 100_000_000.0} BTC"
                        tvBalResult.setTextColor(LIME)
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
    }

'''

content = content[:start] + new_puzzle + content[end:]
open(path, 'w').write(content)
print(f"✓ buildPuzzleTab rediseñado. Líneas: {len(content.splitlines())}")
