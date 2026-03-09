package com.hunter.btc

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.io.*

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private lateinit var tvWps: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvMatches: TextView
    private lateinit var tvMatchList: TextView
    private lateinit var tvAddrFeed: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var sbThreads: SeekBar
    private lateinit var sbCpu: SeekBar
    private lateinit var tvThreads: TextView
    private lateinit var tvCpu: TextView
    private var csvPath: String = ""
    private var s = Strings.ES
    private val recentAddrs = mutableListOf<String>()
    private var addrTick = 0
    private val logBuf = StringBuilder()

    companion object {
        val ORANGE = Color.parseColor("#FF8C00")
        val GREEN  = Color.parseColor("#33CC33")
        val RED    = Color.parseColor("#CC2222")
        val YELLOW = Color.parseColor("#FFFF00")
        val DIM    = Color.parseColor("#AAAAAA")
        val BG     = Color.parseColor("#1A1A1E")
        val PANEL  = Color.parseColor("#242428")
        val DARK   = Color.parseColor("#111114")
        const val REQ_CSV = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        s = Strings.ALL[prefs.getString("lang","ES")] ?: Strings.ES
        buildUI()
        checkStoragePermission()
        // Arrancar servicio en segundo plano
        startService(Intent(this, HunterService::class.java))
        // Recibir temperatura del servicio
        HunterService.tempCallback = { temp ->
            runOnUiThread {
                tvTemp.text = "${s.temp}: ${"%.1f".format(temp)}C"
                tvTemp.setTextColor(when { temp<35f->Color.WHITE; temp<42f->YELLOW; else->RED })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updater)
    }

    override fun onDestroy() {
        super.onDestroy()
        HunterService.tempCallback = null
        // NO detener servicio ni hunting al cerrar Activity
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager())
                AlertDialog.Builder(this).setTitle(s.permTitle).setMessage(s.permMsg)
                    .setPositiveButton("OK") { _,_ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }.show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
    }

    private fun buildUI() {
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val main = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,20,20,20) }

        main.addView(TextView(this).apply {
            text=s.title; textSize=20f; setTextColor(ORANGE)
            gravity=Gravity.CENTER; setTypeface(null,Typeface.BOLD)
        })
        main.addView(TextView(this).apply {
            text=s.subtitle; textSize=10f; setTextColor(DIM)
            gravity=Gravity.CENTER; setPadding(0,2,0,4)
        })

        // Selector idioma
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        val langRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,0,0,8) }
        langRow.addView(TextView(this).apply { text="${s.language}: "; setTextColor(DIM); textSize=11f })
        val langKeys = Strings.ALL.keys.toList()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langKeys)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(langKeys.indexOf(prefs.getString("lang","ES")).coerceAtLeast(0))
        spinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if(init){init=false;return}
                prefs.edit().putString("lang",langKeys[pos]).apply()
                recreate()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        langRow.addView(spinner); main.addView(langRow)

        // RAM + Temp
        val sysRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setBackgroundColor(PANEL); setPadding(12,6,12,6) }
        tvRam  = TextView(this).apply { text="${s.ram}: --"; setTextColor(Color.WHITE); textSize=11f
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f) }
        tvTemp = TextView(this).apply { text="${s.temp}: --"; setTextColor(Color.WHITE); textSize=11f }
        sysRow.addView(tvRam); sysRow.addView(tvTemp); main.addView(sysRow)

        // CSV
        main.addView(sectionLabel(s.csvSection))
        val csvRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val btnCsv = Button(this).apply {
            text=s.csvBtn; setBackgroundColor(ORANGE); setTextColor(Color.BLACK)
            textSize=11f; setPadding(24,0,24,0)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,80)
            setOnClickListener { pickCsv() }
        }
        tvStatus = TextView(this).apply {
            text=s.noFile; setTextColor(DIM); textSize=11f; setPadding(12,0,0,0)
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
        }
        csvRow.addView(btnCsv); csvRow.addView(tvStatus); main.addView(csvRow)

        // Config
        main.addView(sectionLabel(s.configSection))
        tvThreads = TextView(this).apply { setTextColor(Color.WHITE); textSize=12f }
        main.addView(tvThreads)
        sbThreads = SeekBar(this).apply { max=7; progress=3
            setOnSeekBarChangeListener(mkSbl { updateLabels() }) }
        main.addView(sbThreads)
        tvCpu = TextView(this).apply { setTextColor(Color.WHITE); textSize=12f; setPadding(0,8,0,0) }
        main.addView(tvCpu)
        sbCpu = SeekBar(this).apply { max=90; progress=70
            setOnSeekBarChangeListener(mkSbl {
                updateLabels()
                if(HunterEngine.isRunning()) HunterEngine.setCpuLimit(sbCpu.progress+10)
            }) }
        main.addView(sbCpu)
        updateLabels()

        btnToggle = Button(this).apply {
            text=s.start; setBackgroundColor(GREEN); setTextColor(Color.WHITE)
            textSize=16f; setTypeface(null,Typeface.BOLD)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,120)
                .apply{topMargin=20;bottomMargin=8}
            setOnClickListener { doToggle() }
        }
        main.addView(btnToggle)

        main.addView(sectionLabel(s.statsSection))
        val sp = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(PANEL); setPadding(12,10,12,10) }
        tvWps     = mkStat("0 w/s",true)
        tvCount   = mkStat("0 ${s.seeds}",false)
        tvTime    = mkStat("00:00:00",false)
        tvMatches = mkStat("${s.matches}: 0",false)
        sp.addView(tvWps);sp.addView(tvCount);sp.addView(tvTime);sp.addView(tvMatches)
        main.addView(sp)

        main.addView(sectionLabel(s.liveSection))
        tvAddrFeed = TextView(this).apply {
            text=s.waitingStart; setTextColor(Color.parseColor("#44BB44"))
            textSize=10f; typeface=Typeface.MONOSPACE
            setBackgroundColor(DARK); setPadding(10,8,10,8)
            setLineSpacing(0f,1.3f)
        }
        main.addView(tvAddrFeed)

        main.addView(sectionLabel(s.matchSection).apply{setPadding(0,12,0,0)})
        tvMatchList = TextView(this).apply {
            text=s.noMatch; setTextColor(YELLOW); textSize=11f
            setBackgroundColor(PANEL); setPadding(12,10,12,10)
        }
        main.addView(tvMatchList)

        main.addView(sectionLabel(s.logSection).apply{setPadding(0,12,0,0)})
        tvLog = TextView(this).apply {
            text=""; setTextColor(DIM); textSize=10f
            setBackgroundColor(PANEL); setPadding(10,8,10,8)
        }
        main.addView(tvLog)

        root.addView(main); setContentView(root)
    }

    private fun mkStat(init:String, bold:Boolean) = TextView(this).apply {
        text=init; setTextColor(if(bold)DIM else Color.WHITE)
        textSize=if(bold)15f else 13f; if(bold)setTypeface(null,Typeface.BOLD)
    }
    private fun mkSbl(block:()->Unit) = object:SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb:SeekBar,p:Int,u:Boolean){block()}
        override fun onStartTrackingTouch(sb:SeekBar){}
        override fun onStopTrackingTouch(sb:SeekBar){}
    }
    private fun sectionLabel(t:String) = TextView(this).apply {
        text=t; textSize=12f; setTextColor(ORANGE); setPadding(0,12,0,4); setTypeface(null,Typeface.BOLD)
    }
    private fun updateLabels() {
        tvThreads.text="${s.threads}: ${sbThreads.progress+1}"
        val cpu=sbCpu.progress+10
        tvCpu.setTextColor(if(cpu<=40)GREEN else if(cpu<=70)YELLOW else RED)
        tvCpu.text="${s.cpuLimit}: $cpu% (${if(cpu<=40)s.silent else if(cpu<=70)s.balanced else s.performance})"
    }
    private fun updateRam() {
        val mi=ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val used=(mi.totalMem-mi.availMem)/1048576; val total=mi.totalMem/1048576; val pct=used*100/total
        tvRam.text="${s.ram}: ${used}MB/${total}MB (${pct}%)"
        tvRam.setTextColor(if(pct<70)Color.WHITE else if(pct<85)YELLOW else RED)
    }
    private fun pickCsv() = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"}, REQ_CSV)

    override fun onActivityResult(requestCode:Int, resultCode:Int, data:Intent?) {
        if(requestCode==REQ_CSV && resultCode==RESULT_OK) {
            data?.data?.let { uri ->
                val path=getRealPath(uri)
                if(path!=null){
                    csvPath=path; tvStatus.text="Cargando: ${File(csvPath).name}"
                    tvStatus.setTextColor(YELLOW); HunterEngine.loadCsv(csvPath)
                } else {
                    tvStatus.text=s.copying; tvStatus.setTextColor(YELLOW)
                    Thread {
                        try {
                            val dest=File(getExternalFilesDir(null),"utxos.csv")
                            contentResolver.openInputStream(uri)?.use{i->FileOutputStream(dest).use{o->i.copyTo(o,65536)}}
                            runOnUiThread{csvPath=dest.absolutePath;tvStatus.text="Cargando: utxos.csv";HunterEngine.loadCsv(csvPath)}
                        } catch(e:Exception){runOnUiThread{tvStatus.text="Error: ${e.message}"}}
                    }.start()
                }
            }
        }
    }

    private fun getRealPath(uri:Uri):String? {
        uri.lastPathSegment?.let{seg->if(seg.startsWith("primary:")){val f=File("/storage/emulated/0/${seg.removePrefix("primary:")}");if(f.exists())return f.absolutePath}}
        val p=uri.path?:return null
        if(p.startsWith("/storage")||p.startsWith("/sdcard")){val f=File(p);if(f.exists())return f.absolutePath}
        return null
    }

    private fun doToggle() {
        if(HunterEngine.isRunning()){
            HunterEngine.stopHunting()
            btnToggle.text=s.start; btnToggle.setBackgroundColor(GREEN)
        } else {
            if(!HunterEngine.isCsvLoaded()){Toast.makeText(this,s.loadFirst,Toast.LENGTH_SHORT).show();return}
            HunterEngine.startHunting(sbThreads.progress+1, sbCpu.progress+10)
            btnToggle.text=s.stop; btnToggle.setBackgroundColor(RED)
        }
    }

    private val updater = object:Runnable { override fun run() {
        var msg:String
        while(true){msg=HunterEngine.popLog();if(msg.isEmpty())break;logBuf.insert(0,msg+"\n");if(logBuf.length>3000)logBuf.setLength(3000)}
        tvLog.text=logBuf.toString()
        val loading=HunterEngine.isLoading(); val loaded=HunterEngine.isCsvLoaded(); val running=HunterEngine.isRunning()
        if(loading||loaded){tvStatus.text=HunterEngine.getLoadStatus();tvStatus.setTextColor(if(loading)YELLOW else GREEN)}
        if(!running && btnToggle.text==s.stop){btnToggle.text=s.start;btnToggle.setBackgroundColor(GREEN)}
        btnToggle.isEnabled=loaded&&!loading
        val wps=HunterEngine.getWps()
        tvWps.text=if(wps>=1e6)"%.2f M w/s".format(wps/1e6) else if(wps>=1000)"%.1f K w/s".format(wps/1000) else "%.0f w/s".format(wps)
        tvWps.setTextColor(if(running)ORANGE else DIM)
        val count=HunterEngine.getCount(); tvCount.text=if(count>=1_000_000)"%.2f M ${s.seeds}".format(count/1e6) else "$count ${s.seeds}"
        val e=HunterEngine.getElapsed(); tvTime.text="%02d:%02d:%02d".format(e/3600,(e%3600)/60,e%60)
        val found=HunterEngine.getFound(); tvMatches.text="${s.matches}: $found"; tvMatches.setTextColor(if(found>0)YELLOW else Color.WHITE)
        val m=HunterEngine.getMatches(); if(m.isNotEmpty()){tvMatchList.text=m;tvMatchList.setTextColor(YELLOW)}
        if(running){
            var addr:String
            while(true){addr=HunterEngine.popRecentAddr();if(addr.isEmpty())break;recentAddrs.add(addr);if(recentAddrs.size>6)recentAddrs.removeAt(0)}
            addrTick++
            if(addrTick>=3){addrTick=0;if(recentAddrs.isNotEmpty())tvAddrFeed.text=recentAddrs.takeLast(3).joinToString("\n")}
        } else if(!loaded) tvAddrFeed.text=s.waitingStart
        updateRam()
        handler.postDelayed(this,333L)
    }}

    private fun startUpdater(){handler.post(updater)}
}
