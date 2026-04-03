#!/usr/bin/env python3
"""
Paso 2: Rediseño visual del Scan tab (mantiene toda la lógica)
"""

path = "app/src/main/java/com/hunter/btc/MainActivity.kt"
content = open(path).read()

OLD_SCAN = '''    private fun buildScanTab(): ScrollView {
        val LIME = 0xFF39FF14.toInt()   // neon green
        val LIME2 = 0xFF00E600.toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            setPadding(0, 0, 0, dp(80))
        }

        // ── STICKY MONITOR HEADER ─────────────────────────────────────────
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111411.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // Title
        headerCard.addView(TextView(this).apply {
            text = "Sticky Monitor"
            textSize = 13f; setTextColor(0xFFCCCCCC.toInt())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })

        // Big speed number
        tvWps = TextView(this).apply {
            text = "0.0"
            textSize = 56f
            setTextColor(LIME)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(20f, 0f, 0f, 0x8039FF14.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerCard.addView(tvWps)

        // Unit label
        headerCard.addView(TextView(this).apply {
            text = "Velocidad de Escaneo  kKeys / s"
            textSize = 11f; setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }.also { tv ->
            // Color parcial en kKeys/s
            val span = android.text.SpannableString(tv.text)
            val idx = tv.text.indexOf("kKeys")
            if (idx >= 0) {
                span.setSpan(android.text.style.ForegroundColorSpan(LIME), idx, tv.text.length, 0)
                tv.text = span
            }
        })

        // Stats row: Blocks | Total Keys | Progress% | Elapsed
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        fun statBlock(valueView: TextView?, label: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                valueView?.let { addView(it) }
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 9f
                    setTextColor(0xFF777777.toInt())
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                })
            }
        }

        fun divider() = View(this).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(1, dp(32)).apply { setMargins(0, dp(4), 0, 0) }
        }

        tvCount = TextView(this).apply {
            text = "0"; textSize = 18f; setTextColor(0xFFEEEEEE.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }
        val tvTotalKeys = tvCount

        val tvBlocksStat = TextView(this).apply {
            text = "0"; textSize = 18f; setTextColor(0xFFEEEEEE.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }

        val tvProgressStat = TextView(this).apply {
            text = "0.0000%"; textSize = 16f; setTextColor(LIME)
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }

        tvTime = TextView(this).apply {
            text = "00:00:00"; textSize = 16f; setTextColor(0xFFEEEEEE.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        }

        statsRow.addView(statBlock(tvBlocksStat, "Blocks"))
        statsRow.addView(divider())
        statsRow.addView(statBlock(tvTotalKeys, "Total Keys"))
        statsRow.addView(divider())
        statsRow.addView(statBlock(tvProgressStat, "Progress"))
        statsRow.addView(divider())
        tvTime = TextView(this).apply { text = "00:00:00"; textSize = 16f; setTextColor(0xFFEEEEEE.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER }
        statsRow.addView(statBlock(tvTime!!, "Elapsed Time"))
        headerCard.addView(statsRow)

        // Progress bar with glow
        val progressBg = GradientDrawable().apply {
            setColor(0xFF1A2A1A.toInt()); cornerRadius = dp(20).toFloat()
        }
        val progressBarContainer = FrameLayout(this).apply {
            background = progressBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)
            )
        }
        chartView = SpeedChartView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Barra de progreso real
        val progressFill = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 10000
            progress = 0
            progressDrawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF006600.toInt(), LIME)
            ).apply { cornerRadius = dp(20).toFloat() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progressBarContainer.addView(progressFill)
        headerCard.addView(progressBarContainer)
        page.addView(headerCard)

        // Invisible views para compatibilidad
        tvKps      = tvWps
        tvRam      = TextView(this).apply { visibility = android.view.View.GONE }
        tvBattery  = TextView(this).apply { visibility = android.view.View.GONE }
        tvTemp     = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatches  = TextView(this).apply { visibility = android.view.View.GONE }
        tvFooter   = TextView(this).apply { visibility = android.view.View.GONE }
        tvStatus   = TextView(this).apply { visibility = android.view.View.GONE }
        tvAddrFeed = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatchList = TextView(this).apply { visibility = android.view.View.GONE }
        page.addView(tvRam); page.addView(tvBattery); page.addView(tvFooter); page.addView(tvStatus)

        // ── HELPER: Collapsible Section ───────────────────────────────────
        fun collapsibleSection(icon: String, title: String, build: LinearLayout.() -> Unit): LinearLayout {
            val sectionBg = GradientDrawable().apply {
                setColor(0xFF141814.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(1, 0xFF2A3028.toInt())
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = sectionBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
            }

            val iconTv = TextView(this).apply {
                text = icon; textSize = 18f
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
                gravity = Gravity.CENTER
            }
            val titleTv = TextView(this).apply {
                text = title; textSize = 14f
                setTextColor(0xFFEEEEEE.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrowTv = TextView(this).apply {
                text = "∨"; textSize = 16f; setTextColor(0xFF666666.toInt())
            }

            header.addView(iconTv); header.addView(titleTv); header.addView(arrowTv)
            container.addView(header)

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(dp(16), 0, dp(16), dp(14))
            }
            body.build()
            container.addView(body)

            header.setOnClickListener {
                if (body.visibility == android.view.View.GONE) {
                    body.visibility = android.view.View.VISIBLE
                    arrowTv.text = "∧"
                } else {
                    body.visibility = android.view.View.GONE
                    arrowTv.text = "∨"
                }
            }
            return container
        }

        // ── SECTION: Config Hardware ──────────────────────────────────────
        page.addView(collapsibleSection("⚙", "Configuración del Motor (Hardware)") {
            addView(TextView(this@MainActivity).apply {
                text = "Dataset"; textSize = 10f; setTextColor(0xFF888888.toInt())
                setPadding(0, dp(4), 0, dp(4))
            })
            val dataRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            btnCsv = Button(this@MainActivity).apply {
                text = "Load CSV"; textSize = 10f
                setTextColor(android.graphics.Color.BLACK)
                background = GradientDrawable().apply { setColor(LIME); cornerRadius = dp(6).toFloat() }
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                setOnClickListener { pickCsv() }
            }
            val tvCsvLocal = TextView(this@MainActivity).apply {
                text = if (csvPath.isNotEmpty() && java.io.File(csvPath).exists())
                    java.io.File(csvPath).name else "Sin archivo"
                setTextColor(0xFF888888.toInt()); textSize = 10f; typeface = Typeface.MONOSPACE
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvCsvName = tvCsvLocal
            dataRow.addView(btnCsv); dataRow.addView(tvCsvLocal)
            addView(dataRow)

            addView(TextView(this@MainActivity).apply {
                text = "Threads"; textSize = 10f; setTextColor(0xFF888888.toInt())
                setPadding(0, dp(10), 0, dp(2))
            })
            tvThreads = TextView(this@MainActivity).apply {
                setTextColor(0xFFCCCCCC.toInt()); textSize = 11f
            }
            addView(tvThreads)
            sbThreads = SeekBar(this@MainActivity).apply {
                max = 7; progress = prefs.getInt("threads", 3)
                setOnSeekBarChangeListener(mkSbl { updateLabels() })
            }
            addView(sbThreads)

            addView(TextView(this@MainActivity).apply {
                text = "CPU Limit"; textSize = 10f; setTextColor(0xFF888888.toInt())
                setPadding(0, dp(8), 0, dp(2))
            })
            tvCpu = TextView(this@MainActivity).apply { setTextColor(0xFFCCCCCC.toInt()); textSize = 11f }
            addView(tvCpu)
            sbCpu = SeekBar(this@MainActivity).apply {
                max = 90; progress = prefs.getInt("cpu", 70)
                setOnSeekBarChangeListener(mkSbl {
                    updateLabels()
                    if (HunterEngine.isRunning()) HunterEngine.setCpuLimit((sbCpu?.progress ?: 70) + 10)
                })
            }
            addView(sbCpu)

            // Fast mode switch
            val fastRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            fastRow.addView(TextView(this@MainActivity).apply {
                text = "Fast Scan Mode"; textSize = 12f; setTextColor(0xFFCCCCCC.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val fastSwitch = android.widget.Switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean("fastMode", false)
                setOnCheckedChangeListener { _, c ->
                    fastModeEnabled = c
                    HunterEngine.setPbkdf2Mode(if (c) 1 else 0)
                    prefs.edit().putBoolean("fastMode", c).apply()
                }
            }
            fastModeEnabled = prefs.getBoolean("fastMode", false)
            fastRow.addView(fastSwitch)
            addView(fastRow)

            // Tools
            listOf(
                Triple("⏰", "Programar Scan", { showSchedulerDialog() }),
                Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
                Triple("📤", "Exportar Config", { exportConfig() }),
                Triple("📥", "Importar Config", { importConfig() })
            ).forEach { (ic, lbl, action) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0); isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = ic; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
                })
                row.addView(TextView(this@MainActivity).apply {
                    text = lbl; textSize = 12f; setTextColor(0xFFCCCCCC.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@MainActivity).apply { text = "›"; textSize = 16f; setTextColor(0xFF555555.toInt()) })
                addView(row)
            }
        })

        // ── SECTION: Red Multi-Dispositivo ────────────────────────────────
        page.addView(collapsibleSection("🌐", "Red Multi-Dispositivo (Cluster)") {
            addView(TextView(this@MainActivity).apply {
                text = "MASTER_IP: ${NetworkManager.getLocalIp(this@MainActivity)}"
                textSize = 11f; setTextColor(0xFF888888.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(10))
            })

            val row1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val netBtn = { txt: String, action: () -> Unit ->
                Button(this@MainActivity).apply {
                    text = txt; textSize = 11f; setTextColor(0xFFCCCCCC.toInt())
                    background = GradientDrawable().apply {
                        setColor(0xFF1E2A1E.toInt()); setStroke(1, 0xFF2A3A2A.toInt())
                        cornerRadius = dp(8).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
                    setOnClickListener { action() }
                }
            }
            row1.addView(netBtn("Mode: Master") { NetworkManager.startMaster(this@MainActivity, 71, "400000000000000000", "7fffffffffffffffff") })
            row1.addView(netBtn("Search Masters") {
                NetworkManager.discoverMasters(this@MainActivity) { ip, _ ->
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Master: $ip", android.widget.Toast.LENGTH_SHORT).show() }
                }
            })
            addView(row1)

            val row2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row2.addView(netBtn("Mode: Worker") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            row2.addView(netBtn("Connect") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            addView(row2)

            // Log box
            val tvNetLog = TextView(this@MainActivity).apply {
                text = "Log:"
                textSize = 10f; setTextColor(0xFF888888.toInt()); typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(0xFF0D110D.toInt()); cornerRadius = dp(8).toFloat() }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)).apply { topMargin = dp(8) }
            }
            NetworkManager.onLog = { msg ->
                runOnUiThread {
                    val cur = tvNetLog.text.toString().lines().takeLast(5)
                    tvNetLog.text = (cur + listOf(msg)).joinToString("\\n")
                }
            }
            addView(tvNetLog)
        })

        // ── SECTION: Modo Scan ────────────────────────────────────────────
        page.addView(collapsibleSection("🧩", "Modo Scan") {
            addView(TextView(this@MainActivity).apply {
                text = "Seed Phrase Scanner activo"; textSize = 11f
                setTextColor(0xFF666666.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(4))
            })
        })

        // ── SECTION: Recovery ─────────────────────────────────────────────
        page.addView(collapsibleSection("🩹", "Recuperación de Semilla (Recovery)") {
            addView(TextView(this@MainActivity).apply {
                text = "Recupera seeds con palabras faltantes"; textSize = 11f
                setTextColor(0xFF666666.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(Button(this@MainActivity).apply {
                text = "Abrir Recovery"
                textSize = 12f; setTextColor(android.graphics.Color.BLACK)
                background = GradientDrawable().apply { setColor(LIME); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
                setOnClickListener { goTab(3) }
            })
        })

        // ── START / STOP BUTTON ───────────────────────────────────────────
        val startGreen = GradientDrawable().apply {
            colors = intArrayOf(0xFF00CC00.toInt(), LIME)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = dp(16).toFloat()
        }
        val stopRed = GradientDrawable().apply {
            setColor(RED); cornerRadius = dp(16).toFloat()
        }

        btnToggle = Button(this).apply {
            text = s.start
            textSize = 16f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.15f; isAllCaps = true
            background = startGreen
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { setMargins(dp(12), dp(14), dp(12), dp(8)) }
            setOnClickListener {
                puzzleMode = false
                HunterEngine.setMode(0)
                doToggle(btnToggle)
            }
        }
        btnToggle?.tag = arrayOf(startGreen, stopRed)
        page.addView(btnToggle)

        scroll.addView(page)
        return scroll
    }'''

NEW_SCAN = '''    private fun buildScanTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF0087FF.toInt()
        val LIME    = 0xFF39FF14.toInt()  // kept for engine compat

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
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

        // ── HERO SPEED CARD ───────────────────────────────────────────────
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF111520.toInt())
                cornerRadius = dp(0).toFloat()
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Label
        heroCard.addView(TextView(this).apply {
            text = "VELOCIDAD DE ESCANEO"
            textSize = 10f
            setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })

        // Big speed number
        tvWps = TextView(this).apply {
            text = "0.0"
            textSize = 52f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        heroCard.addView(tvWps)

        heroCard.addView(TextView(this).apply {
            text = "kKeys / segundo"
            textSize = 11f
            setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        })

        // ── STAT GRID 2x2 ─────────────────────────────────────────────────
        fun statCard(accentColor: Int, build: LinearLayout.() -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF111520.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setPadding(dp(14), dp(14), dp(14), dp(14))
                // top accent line via foreground would need API23+, use inner view
                build()
            }
        }

        fun statLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 9f
            setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.1f
        }

        fun statValue(initial: String, color: Int) = TextView(this).apply {
            text = initial
            textSize = 22f
            setTextColor(color)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f
        }

        tvCount = statValue("0", 0xFF0087FF.toInt())
        val tvBlocksStat = statValue("0", 0xFFE8EAF0.toInt())
        val tvProgressStat = statValue("0.00%", ACCENT)
        tvTime = statValue("00:00", 0xFFE8EAF0.toInt())

        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        gridRow1.addView(statCard(ACCENT2) {
            addView(statLabel("TOTAL KEYS"))
            addView(tvCount)
        })
        gridRow1.addView(statCard(0xFF1E2540.toInt()) {
            addView(statLabel("BLOQUES"))
            addView(tvBlocksStat)
        })
        gridRow2.addView(statCard(ACCENT) {
            addView(statLabel("PROGRESO"))
            addView(tvProgressStat)
        })
        gridRow2.addView(statCard(0xFF1E2540.toInt()) {
            addView(statLabel("TIEMPO"))
            addView(tvTime)
        })

        heroCard.addView(gridRow1)
        heroCard.addView(gridRow2)

        // ── PROGRESS BAR ──────────────────────────────────────────────────
        val progressBarContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF1A2030.toInt()); cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { setMargins(0, dp(16), 0, 0) }
        }
        chartView = SpeedChartView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val progressFill = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 10000; progress = 0
            progressDrawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ACCENT2, ACCENT)
            ).apply { cornerRadius = dp(4).toFloat() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progressBarContainer.addView(progressFill)
        heroCard.addView(progressBarContainer)
        page.addView(heroCard)

        // Invisible views para compatibilidad
        tvKps       = tvWps
        tvRam       = TextView(this).apply { visibility = android.view.View.GONE }
        tvBattery   = TextView(this).apply { visibility = android.view.View.GONE }
        tvTemp      = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatches   = TextView(this).apply { visibility = android.view.View.GONE }
        tvFooter    = TextView(this).apply { visibility = android.view.View.GONE }
        tvStatus    = TextView(this).apply { visibility = android.view.View.GONE }
        tvAddrFeed  = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatchList = TextView(this).apply { visibility = android.view.View.GONE }
        page.addView(tvRam); page.addView(tvBattery); page.addView(tvFooter); page.addView(tvStatus)

        // ── HELPER: Collapsible Section ───────────────────────────────────
        fun collapsibleSection(icon: String, title: String, build: LinearLayout.() -> Unit): LinearLayout {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF111520.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
            }
            val iconTv = TextView(this).apply {
                text = icon; textSize = 17f
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
                gravity = Gravity.CENTER
            }
            val titleTv = TextView(this).apply {
                text = title; textSize = 13f
                setTextColor(0xFFE8EAF0.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrowTv = TextView(this).apply {
                text = "›"; textSize = 18f; setTextColor(0xFF3A4060.toInt())
            }
            header.addView(iconTv); header.addView(titleTv); header.addView(arrowTv)
            container.addView(header)

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(dp(16), 0, dp(16), dp(14))
            }
            body.build()
            container.addView(body)

            header.setOnClickListener {
                if (body.visibility == android.view.View.GONE) {
                    body.visibility = android.view.View.VISIBLE
                    arrowTv.text = "∨"
                } else {
                    body.visibility = android.view.View.GONE
                    arrowTv.text = "›"
                }
            }
            return container
        }

        // ── SECTION: Config Hardware ──────────────────────────────────────
        page.addView(collapsibleSection("⚙", "Configuración del Motor (Hardware)") {
            addView(TextView(this@MainActivity).apply {
                text = "Dataset"; textSize = 10f; setTextColor(0xFF5A607A.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(4), 0, dp(4))
            })
            val dataRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            btnCsv = Button(this@MainActivity).apply {
                text = "Load CSV"; textSize = 10f
                setTextColor(android.graphics.Color.BLACK)
                background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(8).toFloat() }
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                setOnClickListener { pickCsv() }
            }
            val tvCsvLocal = TextView(this@MainActivity).apply {
                text = if (csvPath.isNotEmpty() && java.io.File(csvPath).exists())
                    java.io.File(csvPath).name else "Sin archivo"
                setTextColor(0xFF5A607A.toInt()); textSize = 10f; typeface = Typeface.MONOSPACE
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvCsvName = tvCsvLocal
            dataRow.addView(btnCsv); dataRow.addView(tvCsvLocal)
            addView(dataRow)

            addView(TextView(this@MainActivity).apply {
                text = "Threads"; textSize = 10f; setTextColor(0xFF5A607A.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(10), 0, dp(2))
            })
            tvThreads = TextView(this@MainActivity).apply { setTextColor(0xFFE8EAF0.toInt()); textSize = 11f }
            addView(tvThreads)
            sbThreads = SeekBar(this@MainActivity).apply {
                max = 7; progress = prefs.getInt("threads", 3)
                setOnSeekBarChangeListener(mkSbl { updateLabels() })
            }
            addView(sbThreads)

            addView(TextView(this@MainActivity).apply {
                text = "CPU Limit"; textSize = 10f; setTextColor(0xFF5A607A.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(8), 0, dp(2))
            })
            tvCpu = TextView(this@MainActivity).apply { setTextColor(0xFFE8EAF0.toInt()); textSize = 11f }
            addView(tvCpu)
            sbCpu = SeekBar(this@MainActivity).apply {
                max = 90; progress = prefs.getInt("cpu", 70)
                setOnSeekBarChangeListener(mkSbl {
                    updateLabels()
                    if (HunterEngine.isRunning()) HunterEngine.setCpuLimit((sbCpu?.progress ?: 70) + 10)
                })
            }
            addView(sbCpu)

            val fastRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            fastRow.addView(TextView(this@MainActivity).apply {
                text = "Fast Scan Mode"; textSize = 12f; setTextColor(0xFFE8EAF0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val fastSwitch = android.widget.Switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean("fastMode", false)
                setOnCheckedChangeListener { _, c ->
                    fastModeEnabled = c
                    HunterEngine.setPbkdf2Mode(if (c) 1 else 0)
                    prefs.edit().putBoolean("fastMode", c).apply()
                }
            }
            fastModeEnabled = prefs.getBoolean("fastMode", false)
            fastRow.addView(fastSwitch)
            addView(fastRow)

            listOf(
                Triple("⏰", "Programar Scan", { showSchedulerDialog() }),
                Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
                Triple("📤", "Exportar Config", { exportConfig() }),
                Triple("📥", "Importar Config", { importConfig() })
            ).forEach { (ic, lbl, action) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0); isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = ic; textSize = 15f; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
                })
                row.addView(TextView(this@MainActivity).apply {
                    text = lbl; textSize = 12f; setTextColor(0xFFE8EAF0.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@MainActivity).apply { text = "›"; textSize = 16f; setTextColor(0xFF3A4060.toInt()) })
                addView(row)
            }
        })

        // ── SECTION: Red Multi-Dispositivo ────────────────────────────────
        page.addView(collapsibleSection("🌐", "Red Multi-Dispositivo (Cluster)") {
            addView(TextView(this@MainActivity).apply {
                text = "MASTER_IP: ${NetworkManager.getLocalIp(this@MainActivity)}"
                textSize = 11f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(10))
            })
            val row1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val netBtn = { txt: String, action: () -> Unit ->
                Button(this@MainActivity).apply {
                    text = txt; textSize = 11f; setTextColor(0xFFE8EAF0.toInt())
                    background = GradientDrawable().apply {
                        setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
                    setOnClickListener { action() }
                }
            }
            row1.addView(netBtn("Mode: Master") { NetworkManager.startMaster(this@MainActivity, 71, "400000000000000000", "7fffffffffffffffff") })
            row1.addView(netBtn("Search Masters") {
                NetworkManager.discoverMasters(this@MainActivity) { ip, _ ->
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Master: $ip", android.widget.Toast.LENGTH_SHORT).show() }
                }
            })
            addView(row1)
            val row2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row2.addView(netBtn("Mode: Worker") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            row2.addView(netBtn("Connect") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            addView(row2)
            val tvNetLog = TextView(this@MainActivity).apply {
                text = "Log:"
                textSize = 10f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(0xFF0D1020.toInt()); cornerRadius = dp(8).toFloat() }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)).apply { topMargin = dp(8) }
            }
            NetworkManager.onLog = { msg ->
                runOnUiThread {
                    val cur = tvNetLog.text.toString().lines().takeLast(5)
                    tvNetLog.text = (cur + listOf(msg)).joinToString("\\n")
                }
            }
            addView(tvNetLog)
        })

        // ── SECTION: Modo Scan ────────────────────────────────────────────
        page.addView(collapsibleSection("🧩", "Modo Scan") {
            addView(TextView(this@MainActivity).apply {
                text = "Seed Phrase Scanner activo"; textSize = 11f
                setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(4))
            })
        })

        // ── SECTION: Recovery ─────────────────────────────────────────────
        page.addView(collapsibleSection("🩹", "Recuperación de Semilla (Recovery)") {
            addView(TextView(this@MainActivity).apply {
                text = "Recupera seeds con palabras faltantes"; textSize = 11f
                setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(Button(this@MainActivity).apply {
                text = "Abrir Recovery"
                textSize = 12f; setTextColor(android.graphics.Color.BLACK)
                background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(10).toFloat() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
                setOnClickListener { goTab(3) }
            })
        })

        // ── START / STOP BUTTON ───────────────────────────────────────────
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

        btnToggle = Button(this).apply {
            text = s.start
            textSize = 16f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.1f; isAllCaps = true
            background = startBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { setMargins(dp(12), dp(16), dp(12), dp(8)) }
            setOnClickListener {
                puzzleMode = false
                HunterEngine.setMode(0)
                doToggle(btnToggle)
            }
        }
        btnToggle?.tag = arrayOf(startBg, stopRed)
        page.addView(btnToggle)

        scroll.addView(page)
        return scroll
    }'''

if OLD_SCAN in content:
    content = content.replace(OLD_SCAN, NEW_SCAN)
    print("✓ Scan tab rediseñado correctamente")
    open(path, 'w').write(content)
    print("✅ Paso 2 completado. Ejecuta: git add -A && git commit -m 'Redesign Step 2: Scan tab dashboard style' && git push")
else:
    print("✗ ERROR: No se encontró el bloque exacto del Scan tab")
    print("  Verificando fragmentos clave...")
    checks = ["buildScanTab(): ScrollView", "tvWps = TextView(this)", "btnToggle?.tag = arrayOf(startGreen", "collapsibleSection"]
    for c in checks:
        print(f"  {'✓' if c in content else '✗'} '{c}'")
