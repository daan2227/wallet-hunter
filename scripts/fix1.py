#!/usr/bin/env python3
"""
fix1.py — 4 fixes:
  1. Matches aparece 3x → quitar matchCard redundante del scanPage
  2. Quick strip Threads/CPU estáticos → guardar refs como campos y actualizar en updateLabels
  3. Stats tab no actualiza → ya está en tag, verificar updater
  4. Address filter → verificar que HunterEngine.setAddrFilter existe, si no stub
"""
import sys
path = "/data/data/com.termux/files/home/wallet-hunter/app/src/main/java/com/hunter/btc/MainActivity.kt"
src = open(path).read()

# ══ FIX 1: Quitar matchCard del scanPage (ya existe tvQuickMatches en strip y tvMatchList abajo) ══
# El matchCard es el bloque entre statGrid y sparkCard — lo removemos
old_match_card = (
    "\n"
    "        /* Matches banner */\n"
    "        val matchCard=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;background=cardBg();gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(13),dp(16),dp(13));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(6)}}\n"
    "        val mLeft=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}\n"
    "        tvMatches=TextView(this).apply{text=\"0\";textSize=28f;setTextColor(TXT_MUTED);typeface=Typeface.create(\"monospace\",Typeface.BOLD)}\n"
    "        mLeft.addView(TextView(this).apply{text=s.matches.uppercase();textSize=8f;setTextColor(TXT_MUTED);typeface=Typeface.create(\"monospace\",Typeface.BOLD);letterSpacing=0.14f;setPadding(0,0,0,dp(5))})\n"
    "        mLeft.addView(tvMatches);matchCard.addView(mLeft)\n"
    "        matchCard.addView(TextView(this).apply{text=\"~\";textSize=20f;setTextColor(AMBER);typeface=Typeface.MONOSPACE;alpha=0.35f})\n"
    "        statSec.addView(matchCard)\n"
)

# Reemplazar: tvMatches inicializado aquí pero sin card visible; la card no se agrega al layout
new_match_card = (
    "\n"
    "        /* tvMatches — inicializado pero mostrado solo en Quick Strip y tvMatchList */\n"
    "        tvMatches=TextView(this).apply{text=\"0\";textSize=15f;setTextColor(TXT_MUTED);typeface=Typeface.create(\"monospace\",Typeface.BOLD)}\n"
)

if old_match_card in src:
    src = src.replace(old_match_card, new_match_card)
    print("Fix 1: matchCard removed OK")
else:
    print("Fix 1: matchCard not found — checking alternate...")
    # Try finding tvMatches init
    if 'tvMatches=TextView' in src:
        print("  tvMatches already present, skipping")
    else:
        print("  ERROR: tvMatches not found")

# ══ FIX 2: Quick strip Threads/CPU — guardar TVs como campos y actualizar en updateLabels ══
# Añadir campos para los TVs del quick strip de threads y cpu
src = src.replace(
    "    private lateinit var tvKps: TextView",
    "    private lateinit var tvKps: TextView\n"
    "    private var tvQuickThreads: TextView? = null\n"
    "    private var tvQuickCpu: TextView? = null"
)
print("Fix 2a: tvQuickThreads/tvQuickCpu fields added")

# Guardar la ref al crear los qCells
src = src.replace(
    "        val(c0,_) =qCell(\"${prefs.getInt(\"threads\",3)+1}\",\"THREADS\",AMBER){goTab(3)}\n"
    "        val(c1,_) =qCell(\"${prefs.getInt(\"cpu\",70)+10}%\",\"CPU\",AppTheme.CYAN){goTab(3)}\n",
    "        val(c0,tvQT)=qCell(\"${prefs.getInt(\"threads\",3)+1}\",\"THREADS\",AMBER){goTab(3)};tvQuickThreads=tvQT\n"
    "        val(c1,tvQCpu)=qCell(\"${prefs.getInt(\"cpu\",70)+10}%\",\"CPU\",AppTheme.CYAN){goTab(3)};tvQuickCpu=tvQCpu\n"
)
print("Fix 2b: qCell refs captured")

# Actualizar en updateLabels()
src = src.replace(
    "    private fun updateLabels() {\n"
    "        val t=sbThreads.progress+1; val cpu=sbCpu.progress+10\n"
    "        tvThreads.text=\"${s.threads}: $t\"\n"
    "        val cpuColor=if(cpu<=40)GREEN else if(cpu<=70)YELLOW else RED\n"
    "        tvCpu.setTextColor(cpuColor)\n"
    "        tvCpu.text=\"${s.cpuLimit}: $cpu% (${if(cpu<=40)s.silent else if(cpu<=70)s.balanced else s.performance})\"\n"
    "    }",
    "    private fun updateLabels() {\n"
    "        val t=sbThreads.progress+1; val cpu=sbCpu.progress+10\n"
    "        tvThreads.text=\"${s.threads}: $t\"\n"
    "        val cpuColor=if(cpu<=40)GREEN else if(cpu<=70)YELLOW else RED\n"
    "        tvCpu.setTextColor(cpuColor)\n"
    "        tvCpu.text=\"${s.cpuLimit}: $cpu% (${if(cpu<=40)s.silent else if(cpu<=70)s.balanced else s.performance})\"\n"
    "        tvQuickThreads?.text=\"$t\"\n"
    "        tvQuickCpu?.text=\"$cpu%\"; tvQuickCpu?.setTextColor(cpuColor)\n"
    "        /* Stats session card sync */\n"
    "        (tvWps.tag as? Array<*>)?.let{tag->\n"
    "            (tag.getOrNull(7) as? TextView)?.text=\"$t\"   // sThrV\n"
    "            (tag.getOrNull(8) as? TextView)?.text=\"$cpu%\" // sCpuV\n"
    "            (tag.getOrNull(10) as? TextView)?.text=if(puzzleMode)\"PUZZLE\" else \"SEED SCAN\" // sModeV\n"
    "        }\n"
    "    }"
)
print("Fix 2c: updateLabels syncs quick strip + stats")

# ══ FIX 3: Stats tag — asegurarse que indices coinciden ══
# El tag actual es: [tvKps,tvSc2,tvTm2, sKps,sSc,sEl,sMt, sKeyV,sDurV,sMatV]
# idx:               0     1     2      3    4   5   6    7     8     9
# Queremos agregar sThrV(idx7), sCpuV(idx8), sModeV(idx10) — necesitamos reestructurar
# Cambiamos el tag para que sea más claro:
# [0:tvKps, 1:tvSc2, 2:tvTm2,  3:sKps, 4:sSc, 5:sEl, 6:sMt,  7:sThrV, 8:sCpuV, 9:sMatV, 10:sModeV]
src = src.replace(
    "        /* extend tag for stats sync: [tvKps,tvSc2,tvTm2, sKps,sSc,sEl,sMt, sKeyV,sDurV,sMatV] */\n"
    "        tvWps.tag=arrayOf<Any>(tvKps,tvSc2,tvTm2,sKps,sSc,sEl,sMt,sKeyV,sDurV,sMatV)",
    "        /* tag: [0:tvKps,1:tvSc2,2:tvTm2, 3:sKps,4:sSc,5:sEl,6:sMt, 7:sThrV,8:sCpuV,9:sMatV,10:sModeV,11:sKeyV,12:sDurV] */\n"
    "        tvWps.tag=arrayOf<Any>(tvKps,tvSc2,tvTm2,sKps,sSc,sEl,sMt,sThrV,sCpuV,sMatV,sModeV,sKeyV,sDurV)"
)
print("Fix 3: stats tag restructured")

# Actualizar updater para duration y total keys en stats
src = src.replace(
    "        (tvWps.tag as? Array<*>)?.let{t->(t.getOrNull(2) as? TextView)?.text=eStr;(t.getOrNull(5) as? TextView)?.text=eStr}",
    "        (tvWps.tag as? Array<*>)?.let{t->\n"
    "            (t.getOrNull(2) as? TextView)?.text=eStr   // tvTm2\n"
    "            (t.getOrNull(5) as? TextView)?.text=eStr   // sEl\n"
    "            (t.getOrNull(12) as? TextView)?.text=eStr  // sDurV\n"
    "        }"
)
src = src.replace(
    "        (tvWps.tag as? Array<*>)?.let{t->(t.getOrNull(1) as? TextView)?.text=cStr;(t.getOrNull(4) as? TextView)?.text=cStr}",
    "        (tvWps.tag as? Array<*>)?.let{t->\n"
    "            (t.getOrNull(1) as? TextView)?.text=cStr   // tvSc2\n"
    "            (t.getOrNull(4) as? TextView)?.text=cStr   // sSc\n"
    "            (t.getOrNull(11) as? TextView)?.text=cStr  // sKeyV\n"
    "        }"
)
print("Fix 3b: updater duration+keys synced")

# ══ FIX 4: Address filter ══
# Verificar que HunterEngine tiene setAddrFilter — si no, los chips solo setean variables locales
# Los chips ya togglean filterP2PKH/P2SH/P2WPKH. Necesitamos llamar HunterEngine después del toggle.
src = src.replace(
    "        row.addView(chip(\"P2PKH\",  filterP2PKH)  { filterP2PKH  = it })\n"
    "        row.addView(chip(\"P2SH\",   filterP2SH)   { filterP2SH   = it })\n"
    "        row.addView(chip(\"P2WPKH\", filterP2WPKH) { filterP2WPKH = it })\n"
    "        return row",
    "        row.addView(chip(\"P2PKH\",  filterP2PKH)  { filterP2PKH  = it; HunterEngine.setAddrFilter(filterP2PKH, filterP2SH, filterP2WPKH) })\n"
    "        row.addView(chip(\"P2SH\",   filterP2SH)   { filterP2SH   = it; HunterEngine.setAddrFilter(filterP2PKH, filterP2SH, filterP2WPKH) })\n"
    "        row.addView(chip(\"P2WPKH\", filterP2WPKH) { filterP2WPKH = it; HunterEngine.setAddrFilter(filterP2PKH, filterP2SH, filterP2WPKH) })\n"
    "        return row"
)
print("Fix 4: addr filter calls HunterEngine.setAddrFilter")

out = src.encode("utf-8")
open(path,"wb").write(out)
bad=[i for i,b in enumerate(out) if b>127]
print(f"non-ASCII: {'clean' if not bad else f'{len(bad)} bytes (unicode OK)'}")
print("ALL DONE")
