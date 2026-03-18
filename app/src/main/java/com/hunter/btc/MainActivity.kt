package com.hunter.btc

import android.app.*
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import java.io.File

import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.io.*

class SpeedChartView(context: android.content.Context) : android.view.View(context) {
    private val maxPoints = 60
    private val wpsPoints = ArrayDeque<Float>()
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8FF00.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA8FF00.toInt() }
    private val paintLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF556050.toInt(); textSize = 18f; typeface = Typeface.MONOSPACE
    }
    fun addPoint(wps: Float) { wpsPoints.addLast(wps); if(wpsPoints.size>maxPoints) wpsPoints.removeFirst(); postInvalidate() }
    fun reset() { wpsPoints.clear(); postInvalidate() }
    fun getPoints(): List<Float> = wpsPoints.toList()
    override fun onDraw(canvas: Canvas) {
        val w=width.toFloat(); val h=height.toFloat()
        if(w<=0||h<=0||wpsPoints.size<2) return
        val pad=4f; val mx=wpsPoints.max().coerceAtLeast(1f)
        val pts=wpsPoints.mapIndexed{i,v->PointF(pad+(w-pad*2)*i/(maxPoints-1),h-pad-(h-pad*2)*(v/mx))}
        val fill=Path(); fill.moveTo(pts[0].x,h); pts.forEach{fill.lineTo(it.x,it.y)}
        fill.lineTo(pts.last().x,h); fill.close()
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply{
            shader=LinearGradient(0f,0f,0f,h,0x40A8FF00,0x00A8FF00,Shader.TileMode.CLAMP)
            style=Paint.Style.FILL })
        val lp=Path(); pts.forEachIndexed{i,p->if(i==0)lp.moveTo(p.x,p.y) else lp.lineTo(p.x,p.y)}
        canvas.drawPath(lp,paintLine)
        canvas.drawCircle(pts.last().x,pts.last().y,4f,paintDot)
        val wpsMax=wpsPoints.maxOrNull()?:0f
        val lbl=if(wpsMax>=1e6)"%.1fM".format(wpsMax/1e6) else if(wpsMax>=1000)"%.0fK".format(wpsMax/1000) else "%.0f".format(wpsMax)
        canvas.drawText(lbl,pad+2,22f,paintLbl)
    }
}

class MainActivity : Activity() {
    private val BG_DEEP   get() = AppTheme.BG_DEEP
    private val BG_PANEL  get() = AppTheme.BG_PANEL
    private val BG_CARD   get() = AppTheme.BG_CARD
    private val BG_ELEV   get() = AppTheme.BG_ELEV
    private val AMBER     get() = AppTheme.AMBER
    private val GREEN     get() = AppTheme.GREEN
    private val RED       get() = AppTheme.RED
    private val CYAN      get() = AppTheme.CYAN
    private val TXT_PRI   get() = AppTheme.TXT_PRI
    private val TXT_SEC   get() = AppTheme.TXT_SEC
    private val TXT_MUTED get() = AppTheme.TXT_MUTED
    private val BORDER_C  get() = AppTheme.BORDER_C
    private val YELLOW    get() = AppTheme.YELLOW
    private val ORANGE    get() = AppTheme.ORANGE
    private var filterP2PKH  = true
    private var filterP2SH   = true
    private var filterP2WPKH = true
    private var sessionStartTime = 0L
    private var sessionStartCount = 0L
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var lastFoundCount = 0L
    private val NOTIF_CHANNEL = "hunter_match"
    private val NOTIF_ID = 42
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeedBig: TextView
    private var tvQuickCsv: TextView? = null
    private var tvQuickMatches: TextView? = null
    private lateinit var tvWps: TextView
    private lateinit var tvKps: TextView
    private var tvQuickThreads: TextView? = null
    private var tvQuickCpu: TextView? = null
    private lateinit var tvCount: TextView
    private lateinit var chartView: SpeedChartView
    private lateinit var tvPuzzleStatus: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvMatches: TextView
    private lateinit var tvMatchList: TextView
    private lateinit var tvAddrFeed: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvLog: TextView
    private lateinit var puzzleSpinner: Spinner
    private var suppressPuzzleListener = false
    private lateinit var tvFooter: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSwitch: Button
    private lateinit var sbThreads: SeekBar
    private lateinit var sbCpu: SeekBar
    private lateinit var tvThreads: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvCsvSec: LinearLayout
    private lateinit var tvConfigSec: LinearLayout
    private lateinit var tvStatsSec: LinearLayout
    private lateinit var tvLiveSec: LinearLayout
    private lateinit var tvMatchSec: LinearLayout
    private lateinit var tvLogSec: LinearLayout
    private lateinit var tvLangLbl: TextView
    private lateinit var btnCsv: Button
    private lateinit var csvSecView: LinearLayout
    private lateinit var etRangeStart: EditText
    private lateinit var etRangeEnd: EditText
    private lateinit var etTarget: EditText
    private lateinit var layoutPuzzle: LinearLayout
    private lateinit var rbBip39: Button
    private lateinit var rbPuzzle: Button
    private var csvPath: String = ""
    private var s = Strings.ES
    private var puzzleMode = false
    private val recentAddrs = mutableListOf<String>()
    private val logBuf = StringBuilder()

    data class PuzzleInfo(val num: Int, val addr: String, val start: String, val end: String, val btc: String)
    private val puzzles = listOf(
        PuzzleInfo(71,"1PWo3JeB9jrGwfHDNpdGK54CRas7fsVzXU","400000000000000000","7fffffffffffffffff","7.1 BTC"),
        PuzzleInfo(72,"1JTK7s9YVYywfm5XUH7RNhHJH1LshCaRFR","800000000000000000","ffffffffffffffffff","7.2 BTC"),
        PuzzleInfo(73,"12VVRNPi4SJqUTsp6FmqDqY5sGosDtysn4","1000000000000000000","1ffffffffffffffffff","7.3 BTC"),
        PuzzleInfo(74,"1FWGcVDK3JGzCC3WtkYetULPszMaK2Jksv","2000000000000000000","3ffffffffffffffffff","7.4 BTC"),
        PuzzleInfo(76,"1DJh2eHFYQfACPmrvpyWc8MSTYKh7w9eRF","8000000000000000000","fffffffffffffffffff","7.6 BTC"),
        PuzzleInfo(77,"1Bxk4CQdqL9p22JEtDfdXMsng1XacifUtE","10000000000000000000","1fffffffffffffffffff","7.7 BTC"),
        PuzzleInfo(78,"15qF6X51huDjqTmF9BJgxXdt1xcj46Jmhb","20000000000000000000","3fffffffffffffffffff","7.8 BTC"),
        PuzzleInfo(79,"1ARk8HWJMn8js8tQmGUJeQHjSE7KRkn2t8","40000000000000000000","7fffffffffffffffffff","7.9 BTC"),
        PuzzleInfo(81,"15qsCm78whspNQFydGJQk5rexzxTQopnHZ","100000000000000000000","1fffffffffffffffffff","8.1 BTC")
    )

    companion object {
        const val REQ_CSV = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.init(this)
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        val savedLang = prefs.getString("lang", null)
        val langKey = savedLang ?: Strings.fromSystem()
        if (savedLang == null) prefs.edit().putString("lang", langKey).apply()
        s = Strings.ALL[langKey] ?: Strings.EN
        csvPath = prefs.getString("csvPath","") ?: ""
        buildUI()
        checkStoragePermission()
        startService(Intent(this, HunterService::class.java))
        HunterService.tempCallback = { temp ->
            runOnUiThread {
                tvTemp.text = "${"%.1f".format(temp)}C"
                tvTemp.setTextColor(when { temp<35f->TXT_PRI; temp<42f->YELLOW; else->RED })
            }
        }
        if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded()) {
            tvStatus.text = "${s.loading}: ${File(csvPath).name}"
            tvStatus.setTextColor(YELLOW)
            HunterEngine.loadCsv(csvPath)
        }
    }

    override fun onResume()  { super.onResume();  handler.post(updater) }
    override fun onPause()   { super.onPause();   handler.removeCallbacks(updater) }
    override fun onDestroy() { batteryReceiver?.let { unregisterReceiver(it) }; super.onDestroy() }

    private lateinit var btnLangRef: Button

    private fun checkPuzzleBalance(addr: String, onResult: (Long) -> Unit) {
        Thread {
            try {
                val url = java.net.URL("https://mempool.space/api/address/$addr")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val json = conn.inputStream.bufferedReader().readText()
                val funded = Regex("\"funded_txo_sum\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val spent  = Regex("\"spent_txo_sum\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                onResult(funded - spent)
            } catch(e: Exception) { onResult(-1L) }
        }.start()
    }

    private fun applyLang(key: String) {
        s = Strings.ALL[key] ?: Strings.ES
        getSharedPreferences("hunter", MODE_PRIVATE).edit().putString("lang", key).apply()
        tvLangLbl.text = ""
        (tvCsvSec.getChildAt(0) as? android.widget.TextView)?.text = s.csvSection
        btnCsv.text = s.csvBtn
        (tvConfigSec.getChildAt(0) as? android.widget.TextView)?.text = s.configSection
        (tvStatsSec.getChildAt(0) as? android.widget.TextView)?.text = s.statsSection
        (tvLiveSec.getChildAt(0) as? android.widget.TextView)?.text = s.liveSection
        (tvMatchSec.getChildAt(0) as? android.widget.TextView)?.text = s.matchSection
        (tvLogSec.getChildAt(0) as? android.widget.TextView)?.text = s.logSection
        btnToggle.text = if (HunterEngine.isRunning()) s.stop else s.start
        tvMatchList.text = s.noMatch
        tvAddrFeed.text = s.waitingStart
        rbBip39.text = s.modeBip39
        rbPuzzle.text = s.modePuzzle
        updateLabels()
    }

    private fun applyPuzzle(p: PuzzleInfo) {
        etRangeStart.setText(p.start); etRangeEnd.setText(p.end); etTarget.setText(p.addr)
        tvPuzzleStatus.text = "Checking..."; tvPuzzleStatus.setTextColor(TXT_SEC)
        checkPuzzleBalance(p.addr) { sats ->
            runOnUiThread {
                when {
                    sats < 0   -> { tvPuzzleStatus.text = "Network error"; tvPuzzleStatus.setTextColor(TXT_SEC) }
                    sats == 0L -> { tvPuzzleStatus.text = "Already solved - 0 BTC"; tvPuzzleStatus.setTextColor(RED) }
                    else       -> { tvPuzzleStatus.text = "Active: %.8f BTC".format(sats/1e8); tvPuzzleStatus.setTextColor(GREEN) }
                }
            }
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager())
                AlertDialog.Builder(this).setTitle(s.permTitle).setMessage(s.permMsg)
                    .setPositiveButton("OK") { _,_ -> startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }.show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()


    private fun themedAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(pos, cv, parent)
                (v as? TextView)?.setTextColor(AppTheme.TXT_PRI)
                (v as? TextView)?.setBackgroundColor(AppTheme.BG_CARD)
                return v
            }
            override fun getDropDownView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(pos, cv, parent)
                (v as? TextView)?.setTextColor(AppTheme.TXT_PRI)
                (v as? TextView)?.setBackgroundColor(AppTheme.BG_CARD)
                (v as? TextView)?.setPadding(32, 20, 32, 20)
                return v
            }
        }
        return adapter
    }
    private fun cardBg() = GradientDrawable().apply { setColor(AppTheme.BG_CARD); setStroke(1, AppTheme.BORDER_C); cornerRadius = dp(6).toFloat() }

    private fun sectionHdr(label: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        row.addView(TextView(this).apply {
            text = label.uppercase(); textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        })
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            setBackgroundColor(BORDER_C)
        })
        return row
    }

    private fun modeTabBtn(label: String) = Button(this).apply {
        text = label; textSize = 11f
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        setTextColor(TXT_SEC)
        background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) }
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun setTabActive(btn: Button) {
        listOf(rbBip39, rbPuzzle).forEach {
            it.setTextColor(TXT_SEC)
            it.background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
        }
        btn.setTextColor(AMBER)
        btn.background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, Color.parseColor("#3d2800")) }
    }


    private var tabPages = listOf<android.view.View>()
    private var tabBtns  = listOf<android.widget.TextView>()
    private var runStripe: View? = null

    private fun goTab(idx: Int) {
        tabPages.forEachIndexed { i, v -> v.visibility = if(i==idx) android.view.View.VISIBLE else android.view.View.GONE }
        tabBtns.forEachIndexed  { i, b -> b.setTextColor(if(i==idx) AMBER else TXT_MUTED) }
        runStripe?.setBackgroundColor(if(HunterEngine.isRunning()) AMBER else BORDER_C)
    }

    private fun buildUI() {
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        val root  = android.widget.FrameLayout(this).apply { setBackgroundColor(BG_DEEP) }
        val col   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }

        /* ══ HEADER ══ */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(16),dp(11),dp(16),dp(10)); gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
        }
        val mark = TextView(this).apply {
            text = "₿"; textSize = 14f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(30),dp(30)).apply { marginEnd = dp(9) }
        }
        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        brandCol.addView(TextView(this).apply {
            text = s.title; textSize = 14f; setTextColor(TXT_PRI)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        brandCol.addView(TextView(this).apply {
            text = "libsecp256k1 · arm64"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        })
        val hRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sysCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.END; setPadding(0,0,dp(7),0) }
        tvRam  = TextView(this).apply { text="RAM --"; textSize=9f; setTextColor(TXT_MUTED); typeface=Typeface.create("monospace",Typeface.NORMAL); gravity=android.view.Gravity.END }
        tvTemp = TextView(this).apply { text="--°C"; textSize=10f; setTextColor(AMBER); typeface=Typeface.create("monospace",Typeface.BOLD); gravity=android.view.Gravity.END }
        sysCol.addView(tvRam); sysCol.addView(tvTemp)
        val btnLang = Button(this).apply {
            text = prefs.getString("lang","EN") ?: "EN"; textSize=9f; setTextColor(TXT_MUTED)
            typeface=Typeface.create("monospace",Typeface.NORMAL)
            background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(3).toFloat()}
            setPadding(dp(8),dp(3),dp(8),dp(3))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(26)).apply{marginEnd=dp(6)}
            setOnClickListener {
                val langKeys=Strings.ALL.keys.toList()
                val langNames=mapOf("ES" to "Espanol","EN" to "English","JA" to "Japanese","KO" to "Korean","DE" to "Deutsch","FR" to "Francais","RU" to "Russian","PT" to "Portugues")
                val items=langKeys.map{"${langNames[it]?:it} ($it)"}.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle(s.language).setItems(items){_,pos->val k=langKeys[pos];applyLang(k);text=k}.show()
            }
        }
        tvLangLbl = TextView(this).apply { text="" }
        btnSwitch = Button(this).apply {
            text="⊞ Wallet"; textSize=9f; setTextColor(AMBER)
            typeface=Typeface.create("monospace",Typeface.BOLD)
            background=GradientDrawable().apply{setColor(0x1AA8FF00);setStroke(1,0x33A8FF00);cornerRadius=dp(7).toFloat()}
            setPadding(dp(11),dp(6),dp(11),dp(6))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(32))
            setOnClickListener{startActivity(Intent(this@MainActivity,WalletActivity::class.java))}
        }
        hRight.addView(sysCol); hRight.addView(btnLang); hRight.addView(btnSwitch)
        header.addView(mark); header.addView(brandCol); header.addView(hRight)
        col.addView(header)
        val stripe = View(this).apply { setBackgroundColor(BORDER_C); layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(2)) }
        runStripe = stripe; col.addView(stripe)

        /* ══ CONTENT FRAME ══ */
        val cf = android.widget.FrameLayout(this).apply {
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f)
        }
        fun cardBg() = GradientDrawable().apply{setColor(BG_PANEL);setStroke(1,BORDER_C);cornerRadius=dp(10).toFloat()}
        fun secLbl(t:String) = TextView(this).apply{text=t.uppercase();textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.16f;setPadding(0,0,0,dp(10))}

        /* ╔═════════════════╗
           ║  TAB 0 — SCAN  ║
           ╚═════════════════╝ */
        val scanScroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            layoutParams=android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val scanPage = LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}

        /* Speed hero */
        val heroBlock = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(18),dp(20),dp(18),dp(18))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val heroLeft = LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
        heroLeft.addView(TextView(this).apply{text="SPEED";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.18f;setPadding(0,0,0,dp(4))})
        tvWps = TextView(this).apply{text="0";textSize=50f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD)}
        heroLeft.addView(tvWps)
        heroLeft.addView(TextView(this).apply{text="keys / second";textSize=11f;setTextColor(TXT_SEC);setPadding(0,dp(3),0,0)})
        val heroRight = LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.END}
        tvCount = TextView(this).apply{text="0";textSize=17f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END}
        tvTime  = TextView(this).apply{text="00:00:00";textSize=17f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END}
        heroRight.addView(tvCount)
        heroRight.addView(TextView(this).apply{text="SCANNED";textSize=8f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END;letterSpacing=0.13f;setPadding(0,dp(2),0,dp(12))})
        heroRight.addView(tvTime)
        heroRight.addView(TextView(this).apply{text="ELAPSED";textSize=8f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END;letterSpacing=0.13f;setPadding(0,dp(2),0,0)})
        heroBlock.addView(heroLeft); heroBlock.addView(heroRight)
        scanPage.addView(heroBlock)
        scanPage.addView(View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})

        /* Quick strip */
        val qStrip = LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setBackgroundColor(BG_PANEL);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(58))}
        fun qCell(value:String,label:String,color:Int,click:(()->Unit)?=null):Pair<LinearLayout,TextView>{
            val tv=TextView(this).apply{text=value;textSize=15f;setTextColor(color);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=Gravity.CENTER}
            val lb=TextView(this).apply{text=label;textSize=8f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=Gravity.CENTER;letterSpacing=0.12f}
            val cell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f);if(click!=null){setOnClickListener{click()};isClickable=true}}
            cell.addView(tv);cell.addView(lb);return Pair(cell,tv)
        }
        fun vDiv()=View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(1,LinearLayout.LayoutParams.MATCH_PARENT)}
        val csvLbl=if(csvPath.isNotEmpty()&&File(csvPath).exists())File(csvPath).nameWithoutExtension.take(7) else "No file"
        val(c0,tvQT)=qCell("${prefs.getInt("threads",3)+1}","THREADS",AMBER){goTab(2)};tvQuickThreads=tvQT
        val(c1,tvQCpu)=qCell("${prefs.getInt("cpu",70)+10}%","CPU",AppTheme.CYAN){goTab(2)};tvQuickCpu=tvQCpu
        val(c2,tvQC)=qCell(csvLbl,"DATASET",TXT_MUTED){goTab(2)};tvQuickCsv=tvQC
        val(c3,tvQM)=qCell("0","MATCHES",TXT_MUTED);tvQuickMatches=tvQM
        qStrip.addView(c0);qStrip.addView(vDiv());qStrip.addView(c1);qStrip.addView(vDiv());qStrip.addView(c2);qStrip.addView(vDiv());qStrip.addView(c3)
        scanPage.addView(qStrip)
        scanPage.addView(View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})

        /* Big action button */
        val coinGreen=GradientDrawable().apply{setColor(AppTheme.AMBER);cornerRadius=dp(12).toFloat()}
        val coinRed  =GradientDrawable().apply{setColor(AppTheme.RED);cornerRadius=dp(12).toFloat()}
        btnToggle=Button(this).apply{
            text=s.start;textSize=15f;setTextColor(android.graphics.Color.BLACK)
            typeface=Typeface.create("sans-serif-black",Typeface.BOLD);letterSpacing=0.12f;isAllCaps=true
            background=coinGreen
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(56))
            setOnClickListener{doToggle()}
        }
        btnToggle.tag=arrayOf(coinGreen,coinRed)
        val actZone=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14))}
        actZone.addView(btnToggle);scanPage.addView(actZone)

        /* Stat cards: tvKps apunta al mismo texto que tvWps (sin duplicar UI) */
        val statSec=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        tvKps=tvWps  // alias — no card duplicada
        val tvSc2=tvCount
        val tvTm2=tvTime

        /* tvMatches — inicializado pero mostrado solo en Quick Strip y tvMatchList */
        tvMatches=TextView(this).apply{text="0";textSize=15f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD)}

        /* Sparkline */
        val sparkCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();setPadding(dp(14),dp(12),dp(14),dp(12));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(6)}}
        sparkCard.addView(TextView(this).apply{text="SPEED HISTORY";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.14f;setPadding(0,0,0,dp(10))})
        chartView=SpeedChartView(this).apply{layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(52))}
        sparkCard.addView(chartView);statSec.addView(sparkCard);scanPage.addView(statSec)

        /* Live feed */
        val feedSec=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        val feedCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true}
        val feedHdr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(9),dp(14),dp(9));setBackgroundColor(0x2D000000)}
        feedHdr.addView(View(this).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(AMBER)};layoutParams=LinearLayout.LayoutParams(dp(6),dp(6)).apply{marginEnd=dp(6)}})
        feedHdr.addView(TextView(this).apply{text="STREAMING";textSize=9f;setTextColor(AMBER);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.1f;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        feedHdr.addView(TextView(this).apply{text="last 3";textSize=9f;setTextColor(TXT_MUTED)})
        feedCard.addView(feedHdr)
        tvAddrFeed=TextView(this).apply{text=s.waitingStart;setTextColor(TXT_SEC);textSize=10f;typeface=Typeface.MONOSPACE;setPadding(dp(14),dp(10),dp(14),dp(12));setLineSpacing(0f,1.5f)}
        feedCard.addView(tvAddrFeed);feedSec.addView(feedCard)
        tvMatchList=TextView(this).apply{text=s.noMatch;setTextColor(TXT_MUTED);textSize=11f;typeface=Typeface.MONOSPACE;background=cardBg();setPadding(dp(14),dp(12),dp(14),dp(12));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}}
        feedSec.addView(tvMatchList);scanPage.addView(feedSec)
        tvStatus=TextView(this).apply{visibility=android.view.View.GONE;text=""}
        tvFooter=TextView(this).apply{visibility=android.view.View.GONE;text=""}
        scanPage.addView(tvStatus);scanPage.addView(tvFooter)
        scanPage.addView(View(this).apply{layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(20))})
        scanScroll.addView(scanPage);cf.addView(scanScroll)

        /* ╔═════════════════╗
           ║  TAB 1 — LIVE  ║
           ╚═════════════════╝ */
        val liveScroll=android.widget.ScrollView(this).apply{setBackgroundColor(BG_DEEP);visibility=android.view.View.GONE;layoutParams=android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,android.widget.FrameLayout.LayoutParams.MATCH_PARENT)}
        val livePage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val lsHdr=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG_PANEL);setPadding(dp(16),dp(16),dp(16),dp(14))}
        lsHdr.addView(TextView(this).apply{text="SPEED HISTORY";textSize=10f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.14f;setPadding(0,0,0,dp(12))})
        lsHdr.addView(TextView(this).apply{text="See Scan tab for chart";textSize=10f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE})
        livePage.addView(lsHdr)
        livePage.addView(View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        val lfSec=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(20))}
        val lfCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true}
        val lfHdr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(9),dp(14),dp(9));setBackgroundColor(0x2D000000)}
        lfHdr.addView(View(this).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(AMBER)};layoutParams=LinearLayout.LayoutParams(dp(6),dp(6)).apply{marginEnd=dp(6)}})
        lfHdr.addView(TextView(this).apply{text="STREAMING";textSize=9f;setTextColor(AMBER);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.1f})
        lfCard.addView(lfHdr)
        lfCard.addView(TextView(this).apply{text=s.waitingStart;setTextColor(TXT_SEC);textSize=10f;typeface=Typeface.MONOSPACE;setPadding(dp(14),dp(10),dp(14),dp(12))})
        lfSec.addView(lfCard)
        lfSec.addView(TextView(this).apply{text=s.noMatch;setTextColor(TXT_MUTED);textSize=11f;typeface=Typeface.MONOSPACE;background=cardBg();setPadding(dp(14),dp(12),dp(14),dp(12));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}})
        livePage.addView(lfSec)
        tvLiveSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}
        liveScroll.addView(livePage) // not added to cf — tab removed

        /* ╔══════════════════╗
           ║  TAB 2 — STATS  ║
           ╚══════════════════╝ */
        val statsScroll=android.widget.ScrollView(this).apply{setBackgroundColor(BG_DEEP);visibility=android.view.View.GONE;layoutParams=android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,android.widget.FrameLayout.LayoutParams.MATCH_PARENT)}
        val statsPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val sHero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG_PANEL);setPadding(dp(20),dp(24),dp(20),dp(20));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)}
        fun heroItem(tv:TextView,lbl:String):LinearLayout{val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)};c.addView(tv);c.addView(TextView(this).apply{text=lbl;textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.14f;setPadding(0,dp(4),0,0)});return c}
        val sg1=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)}
        val sg2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(16)}}
        val sKps=TextView(this).apply{text="0";textSize=24f;setTextColor(AMBER);typeface=Typeface.create("monospace",Typeface.BOLD)}
        val sSc =TextView(this).apply{text="0";textSize=24f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD)}
        val sEl =TextView(this).apply{text="00:00:00";textSize=24f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD)}
        val sMt =TextView(this).apply{text="0";textSize=24f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD)}
        sg1.addView(heroItem(sKps,"KEYS/SEC"));sg1.addView(heroItem(sSc,"SCANNED"))
        sg2.addView(heroItem(sEl,"ELAPSED"));sg2.addView(heroItem(sMt,"MATCHES"))
        sHero.addView(sg1);sHero.addView(sg2)
        statsPage.addView(sHero)
        statsPage.addView(View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        val statsBody=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(20))}
        statsBody.addView(secLbl(s.statsSection))
        val sessCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(8)}}
        fun sessRow(key:String,tv:TextView){val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(11),dp(16),dp(11))};r.addView(TextView(this).apply{text=key;textSize=11f;setTextColor(TXT_SEC);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)});r.addView(tv);sessCard.addView(r);sessCard.addView(View(this).apply{setBackgroundColor(0x08FFFFFF);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})}
        val sModeV=TextView(this).apply{text=if(puzzleMode)"PUZZLE" else "SEED SCAN";textSize=11f;setTextColor(AMBER);typeface=Typeface.create("monospace",Typeface.BOLD)}
        val sThrV =TextView(this).apply{text="${prefs.getInt("threads",3)+1}";textSize=11f;setTextColor(TXT_PRI)}
        val sCpuV =TextView(this).apply{text="${prefs.getInt("cpu",70)+10}%";textSize=11f;setTextColor(TXT_PRI)}
        val sKeyV =TextView(this).apply{text="0";textSize=11f;setTextColor(TXT_PRI)}
        val sDurV =TextView(this).apply{text="00:00:00";textSize=11f;setTextColor(TXT_PRI)}
        val sMatV =TextView(this).apply{text="0";textSize=11f;setTextColor(AppTheme.GREEN);typeface=Typeface.create("monospace",Typeface.BOLD)}
        sessRow("Mode",sModeV);sessRow("Threads",sThrV);sessRow("CPU Limit",sCpuV)
        sessRow("Total Keys",sKeyV);sessRow("Duration",sDurV);sessRow("Matches",sMatV)
        statsBody.addView(sessCard)
        /* Action buttons */
        val statsAct=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(12)}}
        statsAct.addView(Button(this).apply{text="Session Stats";textSize=11f;setTextColor(android.graphics.Color.BLACK);typeface=Typeface.create("sans-serif-black",Typeface.BOLD);background=GradientDrawable().apply{setColor(AMBER);cornerRadius=dp(10).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)};setOnClickListener{startActivity(Intent(this@MainActivity,StatsActivity::class.java))}})
        statsAct.addView(Button(this).apply{text="Export Log";textSize=11f;setTextColor(TXT_PRI);typeface=Typeface.create("sans-serif-black",Typeface.BOLD);background=GradientDrawable().apply{setColor(BG_PANEL);setStroke(1,BORDER_C);cornerRadius=dp(10).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(44),1f);setOnClickListener{exportLog()}})
        statsBody.addView(statsAct)

        /* Log files list */
        statsBody.addView(secLbl("Saved Logs"))
        val logsCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(8)}}
        val logsContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun refreshLogs() {
            logsContainer.removeAllViews()
            val logDir = getExternalFilesDir(null) ?: filesDir
            val logs = logDir.listFiles{f->f.extension=="txt"}?.sortedByDescending{it.lastModified()} ?: emptyList()
            if(logs.isEmpty()) {
                logsContainer.addView(TextView(this).apply{text="No log files yet";textSize=11f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE;setPadding(dp(16),dp(14),dp(16),dp(14))})
            } else {
                logs.forEach { f ->
                    val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(12),dp(12));isClickable=true}
                    val lc=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
                    lc.addView(TextView(this).apply{text=f.name;textSize=12f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.NORMAL)})
                    val kb = f.length()/1024
                    val date = java.text.SimpleDateFormat("dd MMM HH:mm",java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))
                    lc.addView(TextView(this).apply{text="${kb}KB  ·  $date";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE;setPadding(0,dp(2),0,0)})
                    val btnView=Button(this).apply{
                        text="View";textSize=9f;setTextColor(AMBER)
                        typeface=Typeface.create("monospace",Typeface.BOLD)
                        background=GradientDrawable().apply{setColor(0x1AA8FF00);setStroke(1,0x33A8FF00);cornerRadius=dp(6).toFloat()}
                        setPadding(dp(10),dp(4),dp(10),dp(4))
                        layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(32)).apply{marginEnd=dp(6)}
                        setOnClickListener{
                            val txt = try { f.readText() } catch(e:Exception){ "Error: ${e.message}" }
                            val scroll = android.widget.ScrollView(this@MainActivity)
                            val tv = TextView(this@MainActivity).apply{text=txt;textSize=10f;setTextColor(TXT_PRI);typeface=Typeface.MONOSPACE;setPadding(dp(16),dp(16),dp(16),dp(16))}
                            scroll.addView(tv)
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(f.name)
                                .setView(scroll)
                                .setPositiveButton("Close",null)
                                .setNeutralButton("Share"){_,_->
                                    val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,"${packageName}.provider",f)
                                    val i=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                                    startActivity(Intent.createChooser(i,"Share log"))
                                }
                                .show()
                        }
                    }
                    row.addView(lc); row.addView(btnView)
                    logsContainer.addView(row)
                    logsContainer.addView(View(this).apply{setBackgroundColor(0x08FFFFFF);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
                }
            }
        }
        refreshLogs()
        logsCard.addView(logsContainer);statsBody.addView(logsCard)
        statsPage.addView(statsBody)
        /* tag: [0:tvKps,1:tvSc2,2:tvTm2, 3:sKps,4:sSc,5:sEl,6:sMt, 7:sThrV,8:sCpuV,9:sMatV,10:sModeV,11:sKeyV,12:sDurV] */
        tvWps.tag=arrayOf<Any>(tvKps,tvSc2,tvTm2,sKps,sSc,sEl,sMt,sThrV,sCpuV,sMatV,sModeV,sKeyV,sDurV)
        tvStatsSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}
        tvMatchSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}
        tvLogSec  =LinearLayout(this).also{it.visibility=android.view.View.GONE}
        statsScroll.addView(statsPage);cf.addView(statsScroll)

        /* ╔═══════════════════╗
           ║  TAB 3 — CONFIG  ║
           ╚═══════════════════╝ */
        val cfgScroll=android.widget.ScrollView(this).apply{setBackgroundColor(BG_DEEP);visibility=android.view.View.GONE;layoutParams=android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,android.widget.FrameLayout.LayoutParams.MATCH_PARENT)}
        val cfgPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(24))}
        fun cfgCard()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(8)}}

        /* Dataset */
        cfgPage.addView(secLbl(s.csvSection))
        val dataCard=cfgCard()
        val csvRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;clipToOutline=true}
        btnCsv=Button(this).apply{text=s.csvBtn;textSize=11f;setTextColor(android.graphics.Color.BLACK);typeface=Typeface.create("sans-serif-black",Typeface.BOLD);background=GradientDrawable().apply{setColor(AMBER)};setPadding(dp(18),0,dp(18),0);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(50));setOnClickListener{pickCsv()}}
        val csvInfo=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(14),0);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f)}
        val tvCsvName=TextView(this).apply{text=if(csvPath.isNotEmpty()&&File(csvPath).exists())File(csvPath).name else s.noFile;setTextColor(TXT_MUTED);textSize=10f;typeface=Typeface.MONOSPACE;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
        tvStatus=tvCsvName
        if(csvPath.isNotEmpty()&&File(csvPath).exists())csvInfo.addView(TextView(this).apply{text="●";textSize=8f;setTextColor(AppTheme.GREEN);setPadding(dp(6),0,0,0)})
        csvInfo.addView(tvCsvName);csvRow.addView(btnCsv);csvRow.addView(csvInfo);dataCard.addView(csvRow)
        csvSecView=dataCard;tvCsvSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}
        cfgPage.addView(dataCard)

        /* Performance sliders */
        cfgPage.addView(secLbl(s.configSection))
        val perfCard=cfgCard()
        fun sliderWrap(tv:TextView,bar:SeekBar):LinearLayout{val w=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(14))};w.addView(tv);w.addView(bar);return w}
        tvThreads=TextView(this).apply{setTextColor(TXT_PRI);textSize=12f;setPadding(0,0,0,dp(10))}
        sbThreads=SeekBar(this).apply{max=7;progress=prefs.getInt("threads",3);setOnSeekBarChangeListener(mkSbl{updateLabels()})}
        tvCpu    =TextView(this).apply{setTextColor(TXT_PRI);textSize=12f;setPadding(0,0,0,dp(10))}
        sbCpu    =SeekBar(this).apply{max=90;progress=prefs.getInt("cpu",70);setOnSeekBarChangeListener(mkSbl{updateLabels();if(HunterEngine.isRunning())HunterEngine.setCpuLimit(sbCpu.progress+10)})}
        perfCard.addView(sliderWrap(tvThreads,sbThreads))
        perfCard.addView(View(this).apply{setBackgroundColor(0x08FFFFFF);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        perfCard.addView(sliderWrap(tvCpu,sbCpu))
        cfgPage.addView(perfCard);updateLabels()

        /* Search mode */
        cfgPage.addView(secLbl(s.modeSection))
        val modeCard=cfgCard()
        val modeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(12),dp(12),dp(12),dp(12));gravity=Gravity.CENTER_VERTICAL}
        rbBip39 =Button(this).apply{text=s.modeBip39;textSize=11f;setTextColor(AMBER);typeface=Typeface.create("monospace",Typeface.BOLD);background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,0x33A8FF00);cornerRadius=dp(8).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(4)};setPadding(dp(4),0,dp(4),0)}
        rbPuzzle=Button(this).apply{text=s.modePuzzle;textSize=11f;setTextColor(TXT_SEC);typeface=Typeface.create("monospace",Typeface.NORMAL);background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(8).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(40),1f);setPadding(dp(4),0,dp(4),0)}
        modeRow.addView(rbBip39);modeRow.addView(rbPuzzle);modeCard.addView(modeRow)
        layoutPuzzle=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=android.view.View.GONE;setPadding(dp(14),0,dp(14),dp(14))}
        val dayOfYear=java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx=dayOfYear%puzzles.size
        layoutPuzzle.addView(TextView(this).apply{text=s.puzzleSelect;textSize=9f;setTextColor(TXT_SEC);setPadding(0,0,0,dp(4))})
        puzzleSpinner=Spinner(this).apply{adapter=themedAdapter(puzzles.map{"#${it.num}  -  ${it.btc}  -  ${it.addr.take(16)}..."});setSelection(defaultIdx);background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(6).toFloat()}}
        layoutPuzzle.addView(puzzleSpinner)
        val rangeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,0)}
        fun fld(lbl:String):Pair<LinearLayout,EditText>{val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{marginEnd=dp(4)}};col.addView(TextView(this).apply{text=lbl;textSize=9f;setTextColor(TXT_SEC);setPadding(0,0,0,dp(2))});val et=EditText(this).apply{setTextColor(TXT_PRI);textSize=10f;typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,BORDER_C);cornerRadius=dp(6).toFloat()};setPadding(dp(8),dp(6),dp(8),dp(6));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS};col.addView(et);return Pair(col,et)}
        val(cS,eS)=fld(s.rangeStart);etRangeStart=eS;rangeRow.addView(cS)
        val(cE,eE)=fld(s.rangeEnd);etRangeEnd=eE;rangeRow.addView(cE)
        layoutPuzzle.addView(rangeRow)
        layoutPuzzle.addView(TextView(this).apply{text=s.targetAddr;textSize=9f;setTextColor(TXT_SEC);setPadding(0,dp(8),0,dp(2))})
        etTarget=EditText(this).apply{setTextColor(AMBER);textSize=10f;typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,BORDER_C);cornerRadius=dp(6).toFloat()};setPadding(dp(8),dp(6),dp(8),dp(6));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS}
        layoutPuzzle.addView(etTarget)
        tvPuzzleStatus=TextView(this).apply{text="";textSize=10f;typeface=Typeface.MONOSPACE;setTextColor(AppTheme.GREEN);background=GradientDrawable().apply{setColor(0x1510D97A);setStroke(1,0x2510D97A);cornerRadius=dp(6).toFloat()};setPadding(dp(10),dp(6),dp(10),dp(6));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}}
        layoutPuzzle.addView(tvPuzzleStatus);applyPuzzle(puzzles[defaultIdx])
        puzzleSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{var init=true;override fun onItemSelected(a:AdapterView<*>,v:android.view.View?,pos:Int,id:Long){if(init){init=false;return};applyPuzzle(puzzles[pos])};override fun onNothingSelected(a:AdapterView<*>){}}
        modeCard.addView(layoutPuzzle);cfgPage.addView(modeCard)
        tvConfigSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}
        val modeToggle={isPuzzle:Boolean->
            puzzleMode=isPuzzle
            layoutPuzzle.visibility=if(isPuzzle)android.view.View.VISIBLE else android.view.View.GONE
            HunterEngine.setMode(if(isPuzzle)1 else 0)
            if(isPuzzle){rbPuzzle.setTextColor(AMBER);rbPuzzle.background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,0x33A8FF00);cornerRadius=dp(8).toFloat()};rbBip39.setTextColor(TXT_SEC);rbBip39.background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(8).toFloat()}}
            else{rbBip39.setTextColor(AMBER);rbBip39.background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,0x33A8FF00);cornerRadius=dp(8).toFloat()};rbPuzzle.setTextColor(TXT_SEC);rbPuzzle.background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(8).toFloat()}}
        }
        rbBip39.setOnClickListener{modeToggle(false)};rbPuzzle.setOnClickListener{modeToggle(true)}

        /* Wallet + export */
        cfgPage.addView(secLbl("Wallet"))
        val walletCard=cfgCard()
        fun navRow(title:String,sub:String,click:()->Unit):LinearLayout{val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(13),dp(16),dp(13));isClickable=true;setOnClickListener{click()}};val lc=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)};lc.addView(TextView(this).apply{text=title;textSize=13f;setTextColor(TXT_PRI)});lc.addView(TextView(this).apply{text=sub;textSize=10f;setTextColor(TXT_SEC);typeface=Typeface.MONOSPACE;setPadding(0,dp(2),0,0)});r.addView(lc);r.addView(TextView(this).apply{text="›";textSize=18f;setTextColor(TXT_MUTED)});return r}
        walletCard.addView(navRow("Open Wallet","View balances & addresses"){startActivity(Intent(this@MainActivity,WalletActivity::class.java))})
        walletCard.addView(View(this).apply{setBackgroundColor(0x08FFFFFF);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        walletCard.addView(navRow("Export Log","Save matches to file"){exportLog()})
        cfgPage.addView(walletCard)

        /* Address filter */
        cfgPage.addView(secLbl("Addr. filter"))
        cfgPage.addView(buildFilterRow().apply{background=cardBg();layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(8)}})

        /* System log */
        cfgPage.addView(secLbl(s.logSection))
        val logCard=cfgCard()
        val logHdr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(9),dp(14),dp(9));setBackgroundColor(0x2D000000)}
        logHdr.addView(TextView(this).apply{text="System Events";textSize=9f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        logHdr.addView(TextView(this).apply{text="last session";textSize=9f;setTextColor(TXT_MUTED)})
        logCard.addView(logHdr)
        tvLog=TextView(this).apply{text="";setTextColor(TXT_SEC);textSize=9f;typeface=Typeface.MONOSPACE;setPadding(dp(14),dp(8),dp(14),dp(12));setLineSpacing(0f,1.3f);maxLines=15}
        logCard.addView(tvLog);cfgPage.addView(logCard)
        cfgScroll.addView(cfgPage);cf.addView(cfgScroll)
        col.addView(cf)

        /* ══ TABBAR ══ */
        val tabBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setBackgroundColor(BG_PANEL);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(62))}
        fun tabBtn(ico:String,lbl:String):android.widget.TextView=TextView(this).apply{
            text="$ico\n$lbl";textSize=9f;setTextColor(TXT_MUTED)
            typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.1f
            gravity=Gravity.CENTER;isAllCaps=true
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f)
        }
        val tb0=tabBtn("⊙","Scan");val tb2=tabBtn("◈","Stats");val tb3=tabBtn("⚙","Config")
        listOf(tb0,tb2,tb3).forEach{tabBar.addView(it)}
        col.addView(tabBar);root.addView(col);setContentView(root)

        tabPages=listOf(scanScroll,statsScroll,cfgScroll)
        tabBtns =listOf(tb0,tb2,tb3)
        listOf(tb0,tb2,tb3).forEachIndexed{i,b->b.setOnClickListener{goTab(i)}}
        goTab(0)

        /* Restore state */
        val uiSp=getSharedPreferences("ui_state",MODE_PRIVATE)
        if(uiSp.contains("puzzleMode")){
            val wasPuzzle=uiSp.getBoolean("puzzleMode",false)
            sbThreads.progress=uiSp.getInt("threads",3);sbCpu.progress=uiSp.getInt("cpu",70)
            modeToggle(wasPuzzle)
            val savedPuzzleIdx=uiSp.getInt("puzzleIdx",0);suppressPuzzleListener=true;puzzleSpinner.setSelection(savedPuzzleIdx)
            val rs=uiSp.getString("rangeStart","")?:"";val re=uiSp.getString("rangeEnd","")?:"";val tg=uiSp.getString("target","")?:""
            if(rs.isNotEmpty())etRangeStart.setText(rs);if(re.isNotEmpty())etRangeEnd.setText(re)
            if(tg.isNotEmpty()&&::etTarget.isInitialized)etTarget.setText(tg)
            val savedWallet=uiSp.getString("activeWallet","")?:""
            if(savedWallet.isNotEmpty()){WalletManager.setActiveWallet(this,savedWallet);val wName=WalletManager.listWallets(this).firstOrNull{p->p.first==savedWallet}?.second?:"Wallet";btnSwitch.text=wName.take(10)}
            val wasRunning=uiSp.getBoolean("isRunning",false)
            if(wasRunning){btnToggle.text=s.stop;btnToggle.background=coinRed;btnToggle.setTextColor(android.graphics.Color.WHITE)}else{btnToggle.text=s.start;btnToggle.background=coinGreen;btnToggle.setTextColor(android.graphics.Color.BLACK)}
            val cPts=uiSp.getString("chartPts","")?:"";if(cPts.isNotEmpty())cPts.split(",").mapNotNull{it.toFloatOrNull()}.forEach{chartView.addPoint(it)}
            val sMatch=uiSp.getString("matchText","")?:"";if(sMatch.isNotEmpty()&&sMatch!=s.noMatch){tvMatchList.text=sMatch;tvMatchList.setTextColor(AMBER)}
            val sFeed=uiSp.getString("addrFeed","")?:"";if(sFeed.isNotEmpty()&&sFeed!=s.waitingStart)tvAddrFeed.text=sFeed
            val sPSt=uiSp.getString("puzzleStatus","")?:"";val sPCol=uiSp.getInt("puzzleStatusColor",AppTheme.TXT_SEC)
            if(sPSt.isNotEmpty()&&sPSt!="Checking...")handler.postDelayed({tvPuzzleStatus.text=sPSt;tvPuzzleStatus.setTextColor(sPCol)},300)
            val sLog=uiSp.getString("logBuf","")?:"";if(sLog.isNotEmpty()){logBuf.clear();logBuf.append(sLog);tvLog.text=logBuf.toString()}
            updateLabels();uiSp.edit().clear().apply()
        }
    }
    private fun showWalletDialog() {
        val ctx = this
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_PANEL)
            setPadding(dp(22), dp(20), dp(22), dp(24))
        }
        // Title
        sheet.addView(TextView(ctx).apply {
            text = "BTC Wallet"; textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        })
        val etSeed = EditText(ctx).apply {
            hint = "12 or 24 word seed phrase"
            setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10)); textSize = 11f
            minLines = 2; maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        sheet.addView(etSeed)
        val tvResult = TextView(ctx).apply {
            text = ""; textSize = 9f; typeface = Typeface.MONOSPACE
            setTextColor(TXT_SEC)
            setPadding(0, dp(10), 0, 0); setLineSpacing(0f, 1.4f)
        }
        sheet.addView(tvResult)
        // Buttons row
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val btnDerive = Button(ctx).apply {
            text = "Derive"; textSize = 12f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
        }
        val btnClose = Button(ctx).apply {
            text = "Close"; textSize = 12f; setTextColor(TXT_SEC)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        btnRow.addView(btnDerive); btnRow.addView(btnClose)
        sheet.addView(btnRow)
        val dlg = AlertDialog.Builder(ctx)
            .setView(sheet)
            .create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
        btnClose.setOnClickListener { dlg.dismiss() }
        btnDerive.setOnClickListener {
            val mn = etSeed.text.toString().trim()
            if (mn.split(" ").size < 12) {
                tvResult.text = "Enter at least 12 words"; return@setOnClickListener
            }
            tvResult.text = "Deriving..."; tvResult.setTextColor(TXT_SEC)
            Thread {
                try {
                    val json = HunterEngine.deriveWallet(mn)
                    val inner = json.trim().removePrefix("{").removeSuffix("}")
                    val entries = mutableListOf<Pair<String,String>>()
                    // parse JSON keys
                    inner.split(",").forEach { part -> val kv = part.trim().split(":"); if(kv.size>=2) { val k=kv[0].trim().trim('"',' '); val v=kv[1].trim().trim('"',' '); if(k.isNotEmpty()&&v.isNotEmpty()) entries.add(Pair(k,v)) } }
                    val sb = StringBuilder()
                    val labels = mapOf(
                        "p2pkh_0" to "P2PKH  [0]", "p2pkh_1" to "P2PKH  [1]", "p2pkh_2" to "P2PKH  [2]",
                        "p2sh_0"  to "P2SH   [0]",
                        "p2wpkh_0" to "P2WPKH [0]", "p2wpkh_1" to "P2WPKH [1]"
                    )
                    for ((k, addr) in entries) {
                        val lbl = labels[k] ?: k
                        sb.append("$lbl\n$addr\n")
                        runOnUiThread { tvResult.text = sb.toString(); tvResult.setTextColor(TXT_PRI) }
                        try {
                            val url = java.net.URL("https://mempool.space/api/address/$addr")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 4000; conn.readTimeout = 4000
                            val js = conn.inputStream.bufferedReader().readText()
                            val funded = Regex("\"funded_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                            val spent  = Regex("\"spent_txo_sum\":(\\d+)").find(js)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                            val bal = (funded - spent) / 1e8
                            val balStr = if (funded - spent > 0) "%.8f BTC".format(bal) else "0 BTC"
                            sb.append("  $balStr\n\n")
                            runOnUiThread {
                                tvResult.text = sb.toString()
                                tvResult.setTextColor(if (funded - spent > 0) GREEN else TXT_PRI)
                            }
                        } catch(ex: Exception) {
                            sb.append("  balance: error\n\n")
                            runOnUiThread { tvResult.text = sb.toString() }
                        }
                    }
                } catch(ex: Exception) {
                    runOnUiThread { tvResult.text = "Error: ${ex.message}"; tvResult.setTextColor(RED) }
                }
            }.start()
        }
    }


    private fun createNotifChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
        val ch = NotificationChannel(NOTIF_CHANNEL, "Match Found", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Notifica cuando se encuentra una wallet con fondos"
        ch.enableVibration(true)
        nm.createNotificationChannel(ch)
    }

    private fun notifyMatch(addr: String, btc: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("MATCH FOUND!")
            .setContentText("$addr  $btc")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
        val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0,300,100,300,100,600), -1))
    }


    private fun exportLog() {
        val log = StringBuilder()
        log.append("=== Wallet Hunter Log ===\n")
        log.append("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
        log.append("Total scanned: ${HunterEngine.getCount()}\n")
        log.append("Matches found: ${HunterEngine.getFound()}\n")
        log.append("Speed: ${"%.0f".format(HunterEngine.getWps())} k/s\n\n")
        log.append("=== Matches ===\n")
        log.append(HunterEngine.getMatches().ifEmpty { "None" })
        log.append("\n\n=== Recent Log ===\n")
        log.append(logBuf.toString())
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "hunter_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.txt")
            f.writeText(log.toString())
            // Mostrar dialogo con ruta y opcion de ver
            runOnUiThread {
                val sheet = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(14).toFloat(); setStroke(1, BORDER_C) }
                    setPadding(dp(22), dp(20), dp(22), dp(20))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(20),0,dp(20),0) }
                }
                sheet.addView(TextView(this).apply {
                    text = "Export saved"; textSize = 15f; setTextColor(GREEN)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    setPadding(0, 0, 0, dp(10))
                })
                sheet.addView(TextView(this).apply {
                    text = f.absolutePath; textSize = 9f; setTextColor(TXT_MUTED)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    setPadding(0, 0, 0, dp(14))
                })
                val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val btnView = Button(this).apply {
                    text = "View Log"; textSize = 11f; setTextColor(Color.BLACK)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(7).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
                }
                val btnFiles = Button(this).apply {
                    text = "All Logs"; textSize = 11f; setTextColor(TXT_PRI)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(7).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                }
                btnRow.addView(btnView); btnRow.addView(btnFiles)
                sheet.addView(btnRow)
                val dlg = AlertDialog.Builder(this).setView(sheet).create()
                dlg.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                    setGravity(Gravity.CENTER)
                }
                dlg.show()
                btnView.setOnClickListener { dlg.dismiss(); showLogViewer(f) }
                btnFiles.setOnClickListener { dlg.dismiss(); showAllLogs() }
            }
        } catch(e: Exception) {
            Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogViewer(f: File) {
        val content = try { f.readText() } catch(e: Exception) { "Error reading file: ${e.message}" }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(14).toFloat(); setStroke(1, BORDER_C) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        sheet.addView(TextView(this).apply {
            text = f.name; textSize = 11f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })
        val sv = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400))
        }
        sv.addView(TextView(this).apply {
            text = content; textSize = 9f; setTextColor(TXT_PRI)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setLineSpacing(0f, 1.4f)
        })
        sheet.addView(sv)
        sheet.addView(Button(this).apply {
            text = "Close"; textSize = 11f; setTextColor(TXT_SEC)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = dp(7).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10) }
            setOnClickListener { (parent as? android.app.Dialog)?.dismiss() }
        })
        val dlg = AlertDialog.Builder(this).setView(sheet).create()
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.95f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        // Fix close button reference
        val closeBtn = sheet.getChildAt(2) as? Button
        dlg.show()
        closeBtn?.setOnClickListener { dlg.dismiss() }
    }

    private fun showAllLogs() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val files = dir.listFiles { f -> f.name.startsWith("hunter_log") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) { Toast.makeText(this, "No logs found", Toast.LENGTH_SHORT).show(); return }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(14).toFloat(); setStroke(1, BORDER_C) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        sheet.addView(TextView(this).apply {
            text = "Saved Logs (${files.size})"; textSize = 14f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        val sv = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(350))
        }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        files.forEach { logFile ->
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(8).toFloat() }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(TextView(this).apply { text = logFile.name; textSize = 9f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.NORMAL) })
            info.addView(TextView(this).apply { text = sdf.format(java.util.Date(logFile.lastModified())) + " | ${"%.1f".format(logFile.length()/1024f)} KB"; textSize = 8f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.NORMAL) })
            card.addView(info)
            card.addView(Button(this).apply {
                text = "View"; textSize = 9f; setTextColor(Color.BLACK)
                background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(5).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(30))
                setOnClickListener { showLogViewer(logFile) }
            })
            ll.addView(card)
        }
        sv.addView(ll); sheet.addView(sv)
        val dlg = AlertDialog.Builder(this).setView(sheet).create()
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.95f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dlg.show()
    }


    private fun registerBattery() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct    = (level * 100 / scale)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                               status == BatteryManager.BATTERY_STATUS_FULL
                if (!charging && pct <= 20 && HunterEngine.isRunning()) {
                    val cur = sbThreads.progress + 1
                    if (cur > 1) {
                        sbThreads.progress = 0
                        HunterEngine.setCpuLimit(30)
                        Toast.makeText(ctx, "Battery low ($pct%) - reduced to 1 thread, 30% CPU", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }


    private fun buildFilterRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            setBackgroundColor(BG_PANEL)
        }
        row.addView(TextView(this).apply { text = "Addr:"; textSize = 8f; setTextColor(TXT_MUTED); gravity = Gravity.CENTER_VERTICAL; setPadding(0,0,dp(6),0) })
        fun chip(label: String, active: Boolean, toggle: (Boolean)->Unit): Button {
            val btn = Button(this).apply {
                text = label; textSize = 8f
                setTextColor(if(active) Color.BLACK else TXT_SEC)
                background = GradientDrawable().apply {
                    setColor(if(active) AMBER else BG_ELEV)
                    setStroke(1, BORDER_C)
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply { marginEnd = dp(4) }
            }
            btn.setOnClickListener {
                val newState = btn.currentTextColor == TXT_SEC
                btn.setTextColor(if(newState) Color.BLACK else TXT_SEC)
                (btn.background as GradientDrawable).setColor(if(newState) AMBER else BG_ELEV)
                toggle(newState)
            }
            return btn
        }
        row.addView(chip("P2PKH",  filterP2PKH)  { filterP2PKH  = it })
        row.addView(chip("P2SH",   filterP2SH)   { filterP2SH   = it })
        row.addView(chip("P2WPKH", filterP2WPKH) { filterP2WPKH = it })
        return row
    }


    private fun saveCheckpoint() {
        if (!puzzleMode) return
        val prefs = getSharedPreferences("hunter_checkpoint", MODE_PRIVATE).edit()
        prefs.putLong("puzzle_count", HunterEngine.getCount())
        prefs.putString("puzzle_target", if(::etTarget.isInitialized) etTarget.text.toString() else "")
        prefs.putString("checkpoint_time", SessionStats.nowStr())
        prefs.apply()
    }

    private fun loadCheckpoint(): Long {
        val prefs = getSharedPreferences("hunter_checkpoint", MODE_PRIVATE)
        return prefs.getLong("puzzle_count", 0L)
    }

    private fun clearCheckpoint() {
        getSharedPreferences("hunter_checkpoint", MODE_PRIVATE).edit().clear().apply()
    }

    private fun mkSbl(block:()->Unit) = object:SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb:SeekBar,p:Int,u:Boolean){block()}
        override fun onStartTrackingTouch(sb:SeekBar){}
        override fun onStopTrackingTouch(sb:SeekBar){}
    }
    private fun sectionLabel(t:String) = TextView(this).apply {
        text=t; textSize=9f; setTextColor(AMBER); typeface=Typeface.create("monospace",Typeface.BOLD); letterSpacing=0.2f
    }
    private fun updateLabels() {
        val t=sbThreads.progress+1; val cpu=sbCpu.progress+10
        tvThreads.text="${s.threads}: $t"
        val cpuColor=if(cpu<=40)GREEN else if(cpu<=70)YELLOW else RED
        tvCpu.setTextColor(cpuColor)
        tvCpu.text="${s.cpuLimit}: $cpu% (${if(cpu<=40)s.silent else if(cpu<=70)s.balanced else s.performance})"
        tvQuickThreads?.text="$t"
        tvQuickCpu?.text="$cpu%"; tvQuickCpu?.setTextColor(cpuColor)
        /* Stats session card sync */
        (tvWps.tag as? Array<*>)?.let{tag->
            (tag.getOrNull(7) as? TextView)?.text="$t"
            (tag.getOrNull(8) as? TextView)?.text="$cpu%"; (tag.getOrNull(8) as? TextView)?.setTextColor(cpuColor)
            (tag.getOrNull(10) as? TextView)?.text=if(puzzleMode)"PUZZLE" else "SEED SCAN"
        }
    }
    private fun updateRam() {
        val mi=ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val used=(mi.totalMem-mi.availMem)/1048576; val total=mi.totalMem/1048576; val pct=used*100/total
        tvRam.text="RAM ${used}M/${total}M (${pct}%)"
        tvRam.setTextColor(if(pct<70)TXT_SEC else if(pct<85)YELLOW else RED)
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
                    tvStatus.text="${s.loading}: ${File(csvPath).name}"; tvStatus.setTextColor(YELLOW)
                    HunterEngine.loadCsv(csvPath)
                } else {
                    tvStatus.text=s.copying; tvStatus.setTextColor(YELLOW)
                    Thread {
                        try {
                            val dest=File(getExternalFilesDir(null),"utxos.csv")
                            contentResolver.openInputStream(uri)?.use{i->FileOutputStream(dest).use{o->i.copyTo(o,65536)}}
                            runOnUiThread{
                                csvPath=dest.absolutePath
                                getSharedPreferences("hunter",MODE_PRIVATE).edit().putString("csvPath",csvPath).apply()
                                tvStatus.text="${s.loading}: utxos.csv"; HunterEngine.loadCsv(csvPath)
                            }
                        } catch(e:Exception){runOnUiThread{tvStatus.text="${s.errorPrefix}: ${e.message}"}}
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
        val drawables=btnToggle.tag as? Array<*>
        val coinGreen=drawables?.get(0) as? GradientDrawable
        val coinRed=drawables?.get(1) as? GradientDrawable
        if(HunterEngine.isRunning()){
            HunterEngine.stopHunting()

                // Save session stats
                val dur = (System.currentTimeMillis() - sessionStartTime) / 1000
                val keys = HunterEngine.getCount() - sessionStartCount
                val kps = if (dur > 0) keys.toDouble() / dur / 1000.0 else 0.0
                SessionStats.save(this@MainActivity, Session(
                    SessionStats.nowStr(),
                    if (puzzleMode) "puzzle" else "bip39",
                    keys, kps, dur, HunterEngine.getFound().toInt()
                ))
; btnToggle.text=s.start; btnToggle.background=coinGreen
        } else {
            if(puzzleMode){
                val rs=etRangeStart.text.toString().trim(); val re=etRangeEnd.text.toString().trim(); val tgt=etTarget.text.toString().trim()
                if(rs.isEmpty()||re.isEmpty()){Toast.makeText(this,s.enterRange,Toast.LENGTH_SHORT).show();return}
                if(tgt.isEmpty()){Toast.makeText(this,s.enterTarget,Toast.LENGTH_SHORT).show();return}
                HunterEngine.setRange(rs,re); HunterEngine.setTarget(tgt)
            } else { if(!HunterEngine.isCsvLoaded()){Toast.makeText(this,s.loadFirst,Toast.LENGTH_SHORT).show();return} }
            sessionStartTime = System.currentTimeMillis(); sessionStartCount = HunterEngine.getCount(); HunterEngine.startHunting(sbThreads.progress+1,sbCpu.progress+10)
            btnToggle.text=s.stop; btnToggle.background=coinRed
        }
    }

    private val updater = object:Runnable { override fun run() {
        var msg:String
        while(true){msg=HunterEngine.popLog();if(msg.isEmpty())break;logBuf.insert(0,msg+"\n");if(logBuf.length>3000)logBuf.setLength(3000)}
        tvLog.text=logBuf.toString()
        val loading=HunterEngine.isLoading(); val loaded=HunterEngine.isCsvLoaded(); val running=HunterEngine.isRunning()
        if(loading||loaded){tvStatus.text=HunterEngine.getLoadStatus();tvStatus.setTextColor(if(loading)YELLOW else GREEN)}
        if(!running&&btnToggle.text==s.stop){
            tvTime.text="00:00:00"
            btnToggle.text=s.start
            btnToggle.background=(btnToggle.tag as? Array<*>)?.get(0) as? GradientDrawable ?: btnToggle.background
        }
        btnToggle.isEnabled=(loaded&&!loading)||puzzleMode
        val wps=HunterEngine.getWps()
        val wpsStr=if(wps>=1e6)"%.2fM".format(wps/1e6) else if(wps>=1000)"%.1fK".format(wps/1000) else "%.0f".format(wps)
        tvWps.text=wpsStr
        (tvWps.tag as? Array<*>)?.let{t->(t.getOrNull(0) as? TextView)?.also{it.text=wpsStr;it.setTextColor(if(running)AMBER else TXT_MUTED)};(t.getOrNull(3) as? TextView)?.also{it.text=wpsStr;it.setTextColor(if(running)AMBER else TXT_MUTED)}}
        if(running) chartView.addPoint(wps.toFloat())
        val count=HunterEngine.getCount(); val cStr=if(count>=1_000_000)"%.2fM".format(count/1e6) else if(count>=1000)"%.1fK".format(count/1000.0) else "$count"
        tvCount.text=cStr
        (tvWps.tag as? Array<*>)?.let{t->
            (t.getOrNull(1) as? TextView)?.text=cStr   // tvSc2
            (t.getOrNull(4) as? TextView)?.text=cStr   // sSc
            (t.getOrNull(11) as? TextView)?.text=cStr  // sKeyV
        }
        val e=HunterEngine.getElapsed(); val eStr="%02d:%02d:%02d".format(e/3600,(e%3600)/60,e%60)
        if(running) {
            tvTime.text=eStr
            (tvWps.tag as? Array<*>)?.let{t->
                (t.getOrNull(5) as? TextView)?.text=eStr   // sEl
                (t.getOrNull(12) as? TextView)?.text=eStr  // sDurV
            }
        }
        val found=HunterEngine.getFound(); tvMatches.text="$found"; tvMatches.setTextColor(if(found>0)AMBER else TXT_MUTED)
        tvQuickMatches?.text="$found"; tvQuickMatches?.setTextColor(if(found>0)AMBER else TXT_MUTED)
        (tvWps.tag as? Array<*>)?.let{t->(t.getOrNull(6) as? TextView)?.also{it.text="$found";it.setTextColor(if(found>0)AMBER else TXT_MUTED)}}
        val m=HunterEngine.getMatches(); if(m.isNotEmpty()){tvMatchList.text=m;tvMatchList.setTextColor(YELLOW)}
        // Auto-import puzzle match
        val newMatch = HunterEngine.popMatch()
        if (newMatch.isNotEmpty() && newMatch.contains("MATCH|ADDR:")) {
            val addr = Regex("ADDR:([^|]+)").find(newMatch)?.groupValues?.getOrNull(1) ?: ""
            val wif  = Regex("WIF:([^|]+)").find(newMatch)?.groupValues?.getOrNull(1) ?: ""
            val btc  = Regex("BTC:([^|]+)").find(newMatch)?.groupValues?.getOrNull(1) ?: "?"
            if (wif.isNotEmpty() && addr.isNotEmpty()) {
                val matchLabel = "Puzzle Match ${addr.take(8)}..."
                WalletManager.saveWif(this@MainActivity, wif, addr, matchLabel)
                showMatchImportDialog(addr, wif, btc)
            }
        }
        if(running){
            var addr:String
            while(true){addr=HunterEngine.popRecentAddr();if(addr.isEmpty())break;recentAddrs.add(addr);if(recentAddrs.size>6)recentAddrs.removeAt(0)}
            if(recentAddrs.isNotEmpty()) tvAddrFeed.text=recentAddrs.takeLast(3).map{"$it  -> 0.00 BTC"}.joinToString("\n")
        } else if(!loaded&&!puzzleMode) tvAddrFeed.text=s.waitingStart

                // Check new match
                val curFound = HunterEngine.getFound()
                if (curFound > lastFoundCount) {
                    lastFoundCount = curFound
                    val matchText = HunterEngine.getMatches()
                    val lastLine = matchText.trim().lines().lastOrNull() ?: ""
                    notifyMatch(lastLine.take(34), "")
                }

                HunterWidget.pushStats(this@MainActivity,
                    HunterEngine.getWps().toFloat(),
                    HunterEngine.getCount(),
                    HunterEngine.isRunning(),
                    HunterEngine.getFound().toInt()
                )
        updateRam()
        handler.postDelayed(this,333L)
    }}

    private fun showMatchImportDialog(addr: String, wif: String, btc: String) {
        runOnUiThread {
            val sheet = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(16).toFloat(); setStroke(2, GREEN) }
                setPadding(dp(22), dp(22), dp(22), dp(24))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16),0,dp(16),0) }
            }
            sheet.addView(TextView(this).apply {
                text = "WALLET FOUND!"; textSize = 20f; setTextColor(GREEN)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER; setPadding(0,0,0,dp(6))
            })
            sheet.addView(TextView(this).apply {
                text = "$btc BTC"; textSize = 28f; setTextColor(AMBER)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER; setPadding(0,0,0,dp(14))
            })
            fun row(label: String, value: String) {
                sheet.addView(TextView(this).apply { text = label; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.NORMAL) })
                sheet.addView(TextView(this).apply {
                    text = value; textSize = 10f; setTextColor(TXT_PRI)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(6).toFloat() }
                    setPadding(dp(10),dp(6),dp(10),dp(6))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                })
            }
            row("Address", addr)
            row("WIF Private Key", wif)
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(8),0,0) }
            val btnImport = Button(this).apply {
                text = "Open Wallet"; textSize = 12f; setTextColor(Color.BLACK)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                background = GradientDrawable().apply { setColor(GREEN); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
            }
            val btnCopy = Button(this).apply {
                text = "Copy WIF"; textSize = 12f; setTextColor(TXT_PRI)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            }
            btnRow.addView(btnImport); btnRow.addView(btnCopy)
            sheet.addView(btnRow)
            val dlg = AlertDialog.Builder(this).setView(sheet).setCancelable(false).create()
            dlg.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
                attributes = attributes?.also { it.dimAmount = 0.85f }
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            dlg.show()
            btnCopy.setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("WIF", wif))
                Toast.makeText(this, "WIF copied!", Toast.LENGTH_SHORT).show()
            }
            btnImport.setOnClickListener {
                dlg.dismiss()
                startActivity(Intent(this, WalletActivity::class.java))
            }
        }
    }
}