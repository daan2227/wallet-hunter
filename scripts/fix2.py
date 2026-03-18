#!/usr/bin/env python3
"""
fix2.py:
  1. Quitar tab LIVE por completo (3 tabs: Scan, Stats, Config)
  2. Stats: separar Export Log y View Stats con lista de archivos .txt
"""
path = "/data/data/com.termux/files/home/wallet-hunter/app/src/main/java/com/hunter/btc/MainActivity.kt"
src = open(path).read()

# ══ FIX 1: Quitar tab LIVE ══

# Quitar liveScroll del contentFrame
src = src.replace(
    "        tvLiveSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}\n"
    "        liveScroll.addView(livePage);cf.addView(liveScroll)",
    "        tvLiveSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}\n"
    "        liveScroll.addView(livePage) // not added to cf — tab removed"
)

# Quitar tb1 del tabBar y de las listas
src = src.replace(
    "        val tb0=tabBtn(\"⊙\",\"Scan\");val tb1=tabBtn(\"⟳\",\"Live\");val tb2=tabBtn(\"◈\",\"Stats\");val tb3=tabBtn(\"⚙\",\"Config\")\n"
    "        listOf(tb0,tb1,tb2,tb3).forEach{tabBar.addView(it)}",
    "        val tb0=tabBtn(\"⊙\",\"Scan\");val tb2=tabBtn(\"◈\",\"Stats\");val tb3=tabBtn(\"⚙\",\"Config\")\n"
    "        listOf(tb0,tb2,tb3).forEach{tabBar.addView(it)}"
)

src = src.replace(
    "        tabPages=listOf(scanScroll,liveScroll,statsScroll,cfgScroll)\n"
    "        tabBtns =listOf(tb0,tb1,tb2,tb3)\n"
    "        listOf(tb0,tb1,tb2,tb3).forEachIndexed{i,b->b.setOnClickListener{goTab(i)}}",
    "        tabPages=listOf(scanScroll,statsScroll,cfgScroll)\n"
    "        tabBtns =listOf(tb0,tb2,tb3)\n"
    "        listOf(tb0,tb2,tb3).forEachIndexed{i,b->b.setOnClickListener{goTab(i)}}"
)

# goTab(3) → goTab(2) para Config en quick strip
src = src.replace(
    "val(c0,tvQT)=qCell(\"${prefs.getInt(\"threads\",3)+1}\",\"THREADS\",AMBER){goTab(3)};tvQuickThreads=tvQT\n"
    "        val(c1,tvQCpu)=qCell(\"${prefs.getInt(\"cpu\",70)+10}%\",\"CPU\",AppTheme.CYAN){goTab(3)};tvQuickCpu=tvQCpu\n"
    "        val(c2,tvQC)=qCell(csvLbl,\"DATASET\",TXT_MUTED){goTab(3)};tvQuickCsv=tvQC",
    "val(c0,tvQT)=qCell(\"${prefs.getInt(\"threads\",3)+1}\",\"THREADS\",AMBER){goTab(2)};tvQuickThreads=tvQT\n"
    "        val(c1,tvQCpu)=qCell(\"${prefs.getInt(\"cpu\",70)+10}%\",\"CPU\",AppTheme.CYAN){goTab(2)};tvQuickCpu=tvQCpu\n"
    "        val(c2,tvQC)=qCell(csvLbl,\"DATASET\",TXT_MUTED){goTab(2)};tvQuickCsv=tvQC"
)

# goTab(3) en updateLabels → goTab(2)
src = src.replace(
    "            (tag.getOrNull(7) as? TextView)?.text=\"$t\"   // sThrV\n"
    "            (tag.getOrNull(8) as? TextView)?.text=\"$cpu%\" // sCpuV\n"
    "            (tag.getOrNull(10) as? TextView)?.text=if(puzzleMode)\"PUZZLE\" else \"SEED SCAN\" // sModeV\n"
    "        }",
    "            (tag.getOrNull(7) as? TextView)?.text=\"$t\"\n"
    "            (tag.getOrNull(8) as? TextView)?.text=\"$cpu%\"; (tag.getOrNull(8) as? TextView)?.setTextColor(cpuColor)\n"
    "            (tag.getOrNull(10) as? TextView)?.text=if(puzzleMode)\"PUZZLE\" else \"SEED SCAN\"\n"
    "        }"
)

print("Fix 1: LIVE tab removed OK")

# ══ FIX 2: Stats — separar Export Log y View Logs con lista de archivos ══
old_statsAct = (
    "        val statsAct=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}\n"
    "        statsAct.addView(Button(this).apply{text=\"View Stats\";textSize=11f;setTextColor(android.graphics.Color.BLACK);typeface=Typeface.create(\"sans-serif-black\",Typeface.BOLD);background=GradientDrawable().apply{setColor(AMBER);cornerRadius=dp(10).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)};setOnClickListener{startActivity(Intent(this@MainActivity,StatsActivity::class.java))}})\n"
    "        statsAct.addView(Button(this).apply{text=\"Export Log\";textSize=11f;setTextColor(TXT_PRI);typeface=Typeface.create(\"sans-serif-black\",Typeface.BOLD);background=GradientDrawable().apply{setColor(BG_PANEL);setStroke(1,BORDER_C);cornerRadius=dp(10).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f);setOnClickListener{exportLog()}})\n"
    "        statsBody.addView(statsAct);statsPage.addView(statsBody)"
)

new_statsAct = (
    "        /* Action buttons */\n"
    "        val statsAct=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(12)}}\n"
    "        statsAct.addView(Button(this).apply{text=\"Session Stats\";textSize=11f;setTextColor(android.graphics.Color.BLACK);typeface=Typeface.create(\"sans-serif-black\",Typeface.BOLD);background=GradientDrawable().apply{setColor(AMBER);cornerRadius=dp(10).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)};setOnClickListener{startActivity(Intent(this@MainActivity,StatsActivity::class.java))}})\n"
    "        statsAct.addView(Button(this).apply{text=\"Export Log\";textSize=11f;setTextColor(TXT_PRI);typeface=Typeface.create(\"sans-serif-black\",Typeface.BOLD);background=GradientDrawable().apply{setColor(BG_PANEL);setStroke(1,BORDER_C);cornerRadius=dp(10).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f);setOnClickListener{exportLog()}})\n"
    "        statsBody.addView(statsAct)\n"
    "\n"
    "        /* Log files list */\n"
    "        statsBody.addView(secLbl(\"Saved Logs\"))\n"
    "        val logsCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(8)}}\n"
    "        val logsContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}\n"
    "        fun refreshLogs() {\n"
    "            logsContainer.removeAllViews()\n"
    "            val logDir = getExternalFilesDir(null) ?: filesDir\n"
    "            val logs = logDir.listFiles{f->f.extension==\"txt\"}?.sortedByDescending{it.lastModified()} ?: emptyList()\n"
    "            if(logs.isEmpty()) {\n"
    "                logsContainer.addView(TextView(this).apply{text=\"No log files yet\";textSize=11f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE;setPadding(dp(16),dp(14),dp(16),dp(14))})\n"
    "            } else {\n"
    "                logs.forEach { f ->\n"
    "                    val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(12),dp(12));isClickable=true}\n"
    "                    val lc=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}\n"
    "                    lc.addView(TextView(this).apply{text=f.name;textSize=12f;setTextColor(TXT_PRI);typeface=Typeface.create(\"monospace\",Typeface.NORMAL)})\n"
    "                    val kb = f.length()/1024\n"
    "                    val date = java.text.SimpleDateFormat(\"dd MMM HH:mm\",java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))\n"
    "                    lc.addView(TextView(this).apply{text=\"${kb}KB  \u00b7  $date\";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE;setPadding(0,dp(2),0,0)})\n"
    "                    val btnView=Button(this).apply{\n"
    "                        text=\"View\";textSize=9f;setTextColor(AMBER)\n"
    "                        typeface=Typeface.create(\"monospace\",Typeface.BOLD)\n"
    "                        background=GradientDrawable().apply{setColor(0x1AA8FF00);setStroke(1,0x33A8FF00);cornerRadius=dp(6).toFloat()}\n"
    "                        setPadding(dp(10),dp(4),dp(10),dp(4))\n"
    "                        layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(32)).apply{marginEnd=dp(6)}\n"
    "                        setOnClickListener{\n"
    "                            val txt = try { f.readText() } catch(e:Exception){ \"Error: ${e.message}\" }\n"
    "                            val scroll = android.widget.ScrollView(this@MainActivity)\n"
    "                            val tv = TextView(this@MainActivity).apply{text=txt;textSize=10f;setTextColor(TXT_PRI);typeface=Typeface.MONOSPACE;setPadding(dp(16),dp(16),dp(16),dp(16))}\n"
    "                            scroll.addView(tv)\n"
    "                            AlertDialog.Builder(this@MainActivity)\n"
    "                                .setTitle(f.name)\n"
    "                                .setView(scroll)\n"
    "                                .setPositiveButton(\"Close\",null)\n"
    "                                .setNeutralButton(\"Share\"){_,_->\n"
    "                                    val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,\"${packageName}.provider\",f)\n"
    "                                    val i=Intent(Intent.ACTION_SEND).apply{type=\"text/plain\";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}\n"
    "                                    startActivity(Intent.createChooser(i,\"Share log\"))\n"
    "                                }\n"
    "                                .show()\n"
    "                        }\n"
    "                    }\n"
    "                    row.addView(lc); row.addView(btnView)\n"
    "                    logsContainer.addView(row)\n"
    "                    logsContainer.addView(View(this).apply{setBackgroundColor(0x08FFFFFF);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})\n"
    "                }\n"
    "            }\n"
    "        }\n"
    "        refreshLogs()\n"
    "        logsCard.addView(logsContainer);statsBody.addView(logsCard)\n"
    "        statsPage.addView(statsBody)"
)

if old_statsAct in src:
    src = src.replace(old_statsAct, new_statsAct)
    print("Fix 2: stats log viewer OK")
else:
    print("Fix 2: ERROR — old_statsAct not found")

open(path,"wb").write(src.encode("utf-8"))
bad=[i for i,b in enumerate(src.encode("utf-8")) if b>127]
print(f"non-ASCII: {len(bad)} bytes (unicode OK)")
print("ALL DONE")
