package com.hunter.btc

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import android.text.InputType
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
    private lateinit var tvFooter: TextView
    private lateinit var btnToggle: Button
    private lateinit var sbThreads: SeekBar
    private lateinit var sbCpu: SeekBar
    private lateinit var tvThreads: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvCsvSec: TextView
    private lateinit var tvConfigSec: TextView
    private lateinit var tvStatsSec: TextView
    private lateinit var tvLiveSec: TextView
    private lateinit var tvMatchSec: TextView
    private lateinit var tvLogSec: TextView
    private lateinit var tvLangLbl: TextView
    private lateinit var btnCsv: Button
    private var csvPath: String = ""
    private var s = Strings.ES
    private val recentAddrs = mutableListOf<String>()
    private var puzzleMode = false
    private lateinit var etRangeStart: EditText
    private lateinit var etRangeEnd: EditText
    private lateinit var layoutPuzzle: LinearLayout
    private lateinit var etTarget: EditText
    private var addrTick = 0
    private val logBuf = StringBuilder()

    data class PuzzleInfo(val num:Int, val addr:String, val start:String, val end:String, val btc:String)
    val puzzles = listOf(
        PuzzleInfo(67,"1BY8GQbnueYofwSuFAT3USAhGjPrkxDdW9","0000000000000000","ffffffffffffffff","6.7 BTC"),
        PuzzleInfo(68,"1MVDYgVaSN6iKKEsbzRUAYFrYJadLYZvvZ","0000000000000000","1ffffffffffffffff","6.8 BTC"),
        PuzzleInfo(69,"19vkiEajfhuZ8bs8Zu2jgmC6oqZbWqhxhG","0000000000000000","3ffffffffffffffff","6.9 BTC"),
        PuzzleInfo(70,"1PWo3JeB9jrGwfHDNpdGK54CRas7fsVzXU","400000000000000000","7fffffffffffffffff","7.0 BTC"),
        PuzzleInfo(71,"1PWo3JeB9jrGwfHDNpdGK54CRas7fsVzXU","400000000000000000","7fffffffffffffffff","7.1 BTC"),
        PuzzleInfo(72,"1tBmmAuwdPXXnBKBDFBgGHvCbX4jxDdwt","800000000000000000","ffffffffffffffffff","7.2 BTC"),
        PuzzleInfo(73,"1NAeBPJaAVPPUMkXzGrHKb7YAmxsLoKFGM","1000000000000000000","1ffffffffffffffffff","7.3 BTC"),
        PuzzleInfo(74,"1Me6EfpwZK5kQziBwBfvLiHjaPGxCKLoJi","2000000000000000000","3ffffffffffffffffff","7.4 BTC"),
        PuzzleInfo(75,"1NpnQyZ7x24ud82b7WiRNvPm6N8bqGQnaS","4000000000000000000","7ffffffffffffffffff","7.5 BTC")
    )

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
        // Bloquear capturas de pantalla
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        s = Strings.ALL[prefs.getString("lang","ES")] ?: Strings.ES
        csvPath = prefs.getString("csvPath","") ?: ""
        buildUI()
        checkStoragePermission()
        startService(Intent(this, HunterService::class.java))
        HunterService.tempCallback = { temp ->
            runOnUiThread {
                tvTemp.text = "${s.temp}: ${"%.1f".format(temp)}C"
                tvTemp.setTextColor(when { temp<35f->Color.WHITE; temp<42f->YELLOW; else->RED })
            }
        }
        // Auto-cargar CSV guardado
        if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded()) {
            tvStatus.text = "Cargando: ${File(csvPath).name}"
            tvStatus.setTextColor(YELLOW)
            HunterEngine.loadCsv(csvPath)
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
    }

    private fun applyLang(key: String) {
        s = Strings.ALL[key] ?: Strings.ES
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        prefs.edit().putString("lang", key).apply()
        // Actualizar todos los textos sin recrear
        tvLangLbl.text = "${s.language}: "
        tvCsvSec.text = s.csvSection
        btnCsv.text = s.csvBtn
        tvConfigSec.text = s.configSection
        tvStatsSec.text = s.statsSection
        tvLiveSec.text = s.liveSection
        tvMatchSec.text = s.matchSection
        tvLogSec.text = s.logSection
        btnToggle.text = if (HunterEngine.isRunning()) s.stop else s.start
        tvMatchList.text = s.noMatch
        tvAddrFeed.text = s.waitingStart
        updateLabels()
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
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)

        main.addView(TextView(this).apply {
            text=s.title; textSize=20f; setTextColor(ORANGE)
            gravity=Gravity.CENTER; setTypeface(null,Typeface.BOLD)
        })
        main.addView(TextView(this).apply {
            text=s.subtitle; textSize=10f; setTextColor(DIM)
            gravity=Gravity.CENTER; setPadding(0,2,0,4)
        })

        // Selector idioma - sin recreate()
        val langRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,0,0,8) }
        tvLangLbl = TextView(this).apply { text="${s.language}: "; setTextColor(DIM); textSize=11f }
        val langKeys = Strings.ALL.keys.toList()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langKeys)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(langKeys.indexOf(prefs.getString("lang","ES")).coerceAtLeast(0))
        spinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if(init){init=false;return}
                applyLang(langKeys[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        langRow.addView(tvLangLbl); langRow.addView(spinner); main.addView(langRow)

        // RAM + Temp
        val sysRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setBackgroundColor(PANEL); setPadding(12,6,12,6) }
        tvRam  = TextView(this).apply { text="${s.ram}: --"; setTextColor(Color.WHITE); textSize=11f
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f) }
        tvTemp = TextView(this).apply { text="${s.temp}: --"; setTextColor(Color.WHITE); textSize=11f }
        sysRow.addView(tvRam); sysRow.addView(tvTemp); main.addView(sysRow)

        // CSV
        tvCsvSec = sectionLabel(s.csvSection); main.addView(tvCsvSec)
        val csvRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        btnCsv = Button(this).apply {
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
        tvConfigSec = sectionLabel(s.configSection); main.addView(tvConfigSec)
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

        // Boton redondo estilo moneda Bitcoin
        val btnSize = (resources.displayMetrics.widthPixels * 0.42).toInt()
        val coinGreen = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(GREEN)
            setStroke(8, Color.parseColor("#22FF22"))
        }
        val coinRed = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(RED)
            setStroke(8, Color.parseColor("#FF4444"))
        }
        // Selector de modo
        main.addView(sectionLabel("MODO DE BUSQUEDA"))
        val modeRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,4,0,4) }
        val rbBip39 = RadioButton(this).apply { text="BIP39 (seeds)"; setTextColor(Color.WHITE); textSize=13f; isChecked=!puzzleMode }
        val rbPuzzle = RadioButton(this).apply { text="Puzzle (rango hex)"; setTextColor(Color.WHITE); textSize=13f; isChecked=puzzleMode; setPadding(20,0,0,0) }
        modeRow.addView(rbBip39); modeRow.addView(rbPuzzle); main.addView(modeRow)

        // Panel rango puzzle
        layoutPuzzle = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=if(puzzleMode) android.view.View.VISIBLE else android.view.View.GONE }
        layoutPuzzle.addView(TextView(this).apply { text="Range Start (hex):"; setTextColor(DIM); textSize=11f; setPadding(0,8,0,2) })
        etRangeStart = EditText(this).apply {
            setText("4000000000000000"); setTextColor(Color.WHITE); textSize=11f
            setBackgroundColor(DARK); setPadding(8,8,8,8); typeface=Typeface.MONOSPACE
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layoutPuzzle.addView(etRangeStart)
        layoutPuzzle.addView(TextView(this).apply { text="Range End (hex):"; setTextColor(DIM); textSize=11f; setPadding(0,8,0,2) })
        etRangeEnd = EditText(this).apply {
            setText("7fffffffffffffff"); setTextColor(Color.WHITE); textSize=11f
            setBackgroundColor(DARK); setPadding(8,8,8,8); typeface=Typeface.MONOSPACE
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layoutPuzzle.addView(etRangeEnd)

        // Selector de puzzle por numero
        layoutPuzzle.addView(TextView(this).apply {
            text = "Seleccionar Puzzle:"; setTextColor(DIM); textSize=11f; setPadding(0,12,0,4)
        })
        )

        // Dia del año determina puzzle por defecto
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size

        val puzzleLabels = puzzles.map { "#${it.num} - ${it.btc} - ${it.addr.take(16)}..." }
        val puzzleSpinner = Spinner(this)
        puzzleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, puzzleLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        puzzleSpinner.setSelection(defaultIdx)
        layoutPuzzle.addView(puzzleSpinner)

        // Aplicar puzzle seleccionado
        fun applyPuzzle(p: PuzzleInfo) {
            etRangeStart.setText(p.start)
            etRangeEnd.setText(p.end)
            etTarget.setText(p.addr)
        }
        applyPuzzle(puzzles[defaultIdx])

        puzzleSpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                applyPuzzle(puzzles[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Separador
        layoutPuzzle.addView(TextView(this).apply {
            text = "- o edita manualmente -"; setTextColor(Color.parseColor("#444448"))
            textSize=10f; gravity=Gravity.CENTER; setPadding(0,8,0,4)
        })

        layoutPuzzle.addView(TextView(this).apply {
            text = "Target Address:"; setTextColor(DIM); textSize=11f; setPadding(0,4,0,2)
        })
        etTarget = EditText(this).apply {
            setTextColor(YELLOW); textSize=11f
            setBackgroundColor(DARK); setPadding(8,8,8,8)
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layoutPuzzle.addView(etTarget)
        applyPuzzle(puzzles[defaultIdx])

        main.addView(layoutPuzzle)

        val modeToggle = { isPuzzle: Boolean ->
            puzzleMode = isPuzzle
            rbBip39.isChecked = !isPuzzle
            rbPuzzle.isChecked = isPuzzle
            layoutPuzzle.visibility = if(isPuzzle) android.view.View.VISIBLE else android.view.View.GONE
            HunterEngine.setMode(if(isPuzzle) 1 else 0)
        }
        rbBip39.setOnClickListener  { modeToggle(false) }
        rbPuzzle.setOnClickListener { modeToggle(true)  }

                btnToggle = Button(this).apply {
            text = s.start
            background = coinGreen
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(btnSize/2, btnSize/2).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = 20; bottomMargin = 8
            }
            setOnClickListener { doToggle() }
        }
        // guardar drawables para toggle
        btnToggle.tag = arrayOf(coinGreen, coinRed)
        val btnWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        btnWrap.addView(btnToggle)
        main.addView(btnWrap)

        tvStatsSec = sectionLabel(s.statsSection); main.addView(tvStatsSec)
        val sp = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(PANEL); setPadding(12,10,12,10) }
        tvWps     = mkStat("0 w/s",true)
        tvCount   = mkStat("0 ${s.seeds}",false)
        tvTime    = mkStat("00:00:00",false)
        tvMatches = mkStat("${s.matches}: 0",false)
        sp.addView(tvWps);sp.addView(tvCount);sp.addView(tvTime);sp.addView(tvMatches)
        main.addView(sp)

        tvLiveSec = sectionLabel(s.liveSection); main.addView(tvLiveSec)
        tvAddrFeed = TextView(this).apply {
            text=s.waitingStart; setTextColor(Color.parseColor("#44BB44"))
            textSize=10f; typeface=Typeface.MONOSPACE
            setBackgroundColor(DARK); setPadding(10,8,10,8)
            setLineSpacing(0f,1.3f)
        }
        main.addView(tvAddrFeed)

        tvMatchSec = sectionLabel(s.matchSection).apply{setPadding(0,12,0,0)}; main.addView(tvMatchSec)
        tvMatchList = TextView(this).apply {
            text=s.noMatch; setTextColor(YELLOW); textSize=11f
            setBackgroundColor(PANEL); setPadding(12,10,12,10)
        }
        main.addView(tvMatchList)

        tvLogSec = sectionLabel(s.logSection).apply{setPadding(0,12,0,0)}; main.addView(tvLogSec)
        tvLog = TextView(this).apply {
            text=""; setTextColor(DIM); textSize=10f
            setBackgroundColor(PANEL); setPadding(10,8,10,8)
        }
        main.addView(tvLog)

        // Footer firma
        tvFooter = TextView(this).apply {
            text="Propiedad de Dax2201 | Optimizado por Claude"
            textSize=9f; setTextColor(Color.parseColor("#444448"))
            gravity=Gravity.CENTER; setPadding(0,20,0,4)
            setTypeface(null,Typeface.ITALIC)
        }
        main.addView(tvFooter)

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
                    csvPath=path
                    getSharedPreferences("hunter",MODE_PRIVATE).edit().putString("csvPath",csvPath).apply()
                    tvStatus.text="Cargando: ${File(csvPath).name}"
                    tvStatus.setTextColor(YELLOW); HunterEngine.loadCsv(csvPath)
                } else {
                    tvStatus.text=s.copying; tvStatus.setTextColor(YELLOW)
                    Thread {
                        try {
                            val dest=File(getExternalFilesDir(null),"utxos.csv")
                            contentResolver.openInputStream(uri)?.use{i->FileOutputStream(dest).use{o->i.copyTo(o,65536)}}
                            runOnUiThread{
                                csvPath=dest.absolutePath
                                getSharedPreferences("hunter",MODE_PRIVATE).edit().putString("csvPath",csvPath).apply()
                                tvStatus.text="Cargando: utxos.csv"; HunterEngine.loadCsv(csvPath)
                            }
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
        val drawables = btnToggle.tag as? Array<*>
        val coinGreen = drawables?.get(0) as? android.graphics.drawable.GradientDrawable
        val coinRed   = drawables?.get(1) as? android.graphics.drawable.GradientDrawable
        if(HunterEngine.isRunning()){
            HunterEngine.stopHunting()
            btnToggle.text=s.start; btnToggle.background=coinGreen
        } else {
            if(!HunterEngine.isCsvLoaded() && !puzzleMode){
                Toast.makeText(this,s.loadFirst,Toast.LENGTH_SHORT).show(); return
            }
            if(puzzleMode) {
                val rs = etRangeStart.text.toString().trim()
                val re = etRangeEnd.text.toString().trim()
                if(rs.isEmpty()||re.isEmpty()){Toast.makeText(this,"Ingresa el rango hex",Toast.LENGTH_SHORT).show();return}
                HunterEngine.setRange(rs, re)
                val tgt = etTarget.text.toString().trim()
                HunterEngine.setTarget(tgt)
                if(tgt.isEmpty() && !HunterEngine.isCsvLoaded()){
                    Toast.makeText(this,"Ingresa una direccion objetivo o carga el CSV",Toast.LENGTH_LONG).show()
                    return
                }
            }
            HunterEngine.startHunting(sbThreads.progress+1, sbCpu.progress+10)
            btnToggle.text=s.stop; btnToggle.background=coinRed
        }
    }

    private val updater = object:Runnable { override fun run() {
        var msg:String
        while(true){msg=HunterEngine.popLog();if(msg.isEmpty())break;logBuf.insert(0,msg+"\n");if(logBuf.length>3000)logBuf.setLength(3000)}
        tvLog.text=logBuf.toString()
        val loading=HunterEngine.isLoading(); val loaded=HunterEngine.isCsvLoaded(); val running=HunterEngine.isRunning()
        if(loading||loaded){tvStatus.text=HunterEngine.getLoadStatus();tvStatus.setTextColor(if(loading)YELLOW else GREEN)}
        if(!running && btnToggle.text==s.stop){btnToggle.text=s.start;btnToggle.background=(btnToggle.tag as? Array<*>)?.get(0) as? android.graphics.drawable.GradientDrawable ?: btnToggle.background}
        btnToggle.isEnabled=(loaded&&!loading)||puzzleMode
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

            if(recentAddrs.isNotEmpty()) tvAddrFeed.text = recentAddrs.takeLast(3).map { "$it  -> 0.00000000 BTC" }.joinToString("\n")
        } else if(!loaded) tvAddrFeed.text=s.waitingStart
        updateRam()
        handler.postDelayed(this,333L)
    }}
}
