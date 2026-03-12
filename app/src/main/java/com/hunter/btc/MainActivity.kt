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
        color = 0xFFF59E0B.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF59E0B.toInt() }
    private val paintLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt(); textSize = 18f; typeface = Typeface.MONOSPACE
    }
    fun addPoint(wps: Float) { wpsPoints.addLast(wps); if(wpsPoints.size>maxPoints) wpsPoints.removeFirst(); postInvalidate() }
    fun reset() { wpsPoints.clear(); postInvalidate() }
    override fun onDraw(canvas: Canvas) {
        val w=width.toFloat(); val h=height.toFloat()
        if(w<=0||h<=0||wpsPoints.size<2) return
        val pad=4f; val mx=wpsPoints.max().coerceAtLeast(1f)
        val pts=wpsPoints.mapIndexed{i,v->PointF(pad+(w-pad*2)*i/(maxPoints-1),h-pad-(h-pad*2)*(v/mx))}
        val fill=Path(); fill.moveTo(pts[0].x,h); pts.forEach{fill.lineTo(it.x,it.y)}
        fill.lineTo(pts.last().x,h); fill.close()
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply{
            shader=LinearGradient(0f,0f,0f,h,0x40F59E0B,0x00F59E0B,Shader.TileMode.CLAMP)
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
    private lateinit var tvWps: TextView
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

    private fun buildUI() {
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        val root = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }

        /* HEADER */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        val hTop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        brand.addView(TextView(this).apply {
            text = s.title; textSize = 16f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.08f
        })

        val btnLang = Button(this).apply {
            text = prefs.getString("lang","EN") ?: "EN"
            textSize = 9f; setTextColor(TXT_SEC)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(AppTheme.BG_CARD); setStroke(1, AppTheme.BORDER_C); cornerRadius = dp(3).toFloat() }
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginEnd = dp(6) }
            setOnClickListener {
                val langKeys = Strings.ALL.keys.toList()
                val langNames = mapOf("ES" to "Espanol","EN" to "English","JA" to "Japanese","KO" to "Korean","DE" to "Deutsch","FR" to "Francais","RU" to "Russian","PT" to "Portugues")
                val items = langKeys.map { "${langNames[it]?:it} ($it)" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle(s.language)
                    .setItems(items) { _, pos -> val k=langKeys[pos]; applyLang(k); text=k }.show()
            }
        }
        tvLangLbl = TextView(this).apply { text = "" }

        val btnStats = Button(this).apply {
            text = "Stats"; textSize = 9f; setTextColor(TXT_SEC)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(AppTheme.BG_CARD); setStroke(1, AppTheme.BORDER_C); cornerRadius = dp(3).toFloat() }
            setPadding(dp(9), dp(3), dp(9), dp(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply { marginEnd = dp(6) }
            setOnClickListener { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
        }
        val btnExport = Button(this).apply {
            text = "Export"; textSize = 9f; setTextColor(TXT_SEC)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(AppTheme.BG_CARD); setStroke(1, AppTheme.BORDER_C); cornerRadius = dp(3).toFloat() }
            setPadding(dp(9), dp(3), dp(9), dp(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply { marginEnd = dp(6) }
            setOnClickListener { exportLog() }
        }
        // Fila 1: titulo + wallet + lang
        btnSwitch = Button(this).apply {
            text = "Wallet"; textSize = 9f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.06f
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_CARD)
                setStroke(1, Color.parseColor("#3d2800"))
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginEnd = dp(6) }
            setOnClickListener { startActivity(Intent(this@MainActivity, WalletActivity::class.java)) }
        }
        hRow1.addView(brand)
        hRow1.addView(btnSwitch)
        hRow1.addView(btnLang)
        // Fila 2: botones pequenos
        val hRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        val btnTheme = Button(this).apply {
            text = if (AppTheme.isDark) "Day" else "Night"
            textSize = 9f; setTextColor(TXT_SEC)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(BG_CARD); setStroke(1, BORDER_C)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(9), dp(3), dp(9), dp(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply { marginEnd = dp(6) }
            setOnClickListener {
                // Guardar estado antes de recrear
                val sp = getSharedPreferences("ui_state", MODE_PRIVATE).edit()
                sp.putBoolean("puzzleMode", puzzleMode)
                sp.putInt("threads", sbThreads.progress)
                sp.putInt("cpu", sbCpu.progress)
                sp.putString("rangeStart", etRangeStart.text.toString())
                sp.putString("rangeEnd", etRangeEnd.text.toString())
                sp.putString("target", if(::etTarget.isInitialized) etTarget.text.toString() else "")
                sp.putBoolean("wasRunning", HunterEngine.isRunning())
                sp.putString("logBuf", logBuf.toString().take(4000))
                sp.putInt("puzzleIdx", puzzleSpinner.selectedItemPosition)
                sp.putString("activeWallet", WalletManager.getActiveWalletId(this@MainActivity) ?: "")
                sp.putBoolean("isRunning", HunterEngine.isRunning())
                sp.apply()
                AppTheme.toggle(this@MainActivity)
                recreate()
            }
        }
        hRow2.addView(btnTheme)
        hRow2.addView(btnStats)
        hRow2.addView(btnExport)
        hTop.addView(hRow1)
        hTop.addView(hRow2)
        header.addView(hTop)
        // Bottom border
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(BORDER_C)
        })
        main.addView(header)

        /* STATUS BAR */
        val sb2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_CARD); setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        tvRam = TextView(this).apply {
            text = "RAM --"; textSize = 9f; setTextColor(TXT_SEC)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tempRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tempRow.addView(TextView(this).apply { text = "TEMP "; textSize = 9f; setTextColor(TXT_MUTED) })
        tvTemp = TextView(this).apply { text = "--"; textSize = 9f; setTextColor(AMBER); typeface = Typeface.create("monospace", Typeface.BOLD) }
        tempRow.addView(tvTemp); sb2.addView(tvRam); sb2.addView(tempRow); main.addView(sb2)

        fun pad(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0)
        }

        /* CSV */
        val csvSec = pad().also { main.addView(it) }; csvSecView = csvSec
        tvCsvSec = sectionHdr(s.csvSection).also { csvSec.addView(it) }
        val csvPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }
        btnCsv = Button(this).apply {
            text = s.csvBtn; textSize = 11f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(AMBER) }
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { pickCsv() }
        }
        tvStatus = TextView(this).apply {
            text = s.noFile; setTextColor(TXT_MUTED); textSize = 10f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), 0, dp(8), 0); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        csvPanel.addView(btnCsv); csvPanel.addView(tvStatus); csvSec.addView(csvPanel)

        /* SETTINGS */
        val cfgSec = pad().also { main.addView(it) }
        tvConfigSec = sectionHdr(s.configSection).also { cfgSec.addView(it) }
        val cfgGrid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun miniCard(lbl: String, valTv: TextView): LinearLayout {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = cardBg()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            }
            c.addView(TextView(this).apply { text = lbl; textSize = 9f; setTextColor(TXT_SEC) })
            c.addView(valTv); return c
        }
        val tvThreadVal = TextView(this).apply { text = "4"; textSize = 18f; setTextColor(AMBER); typeface = Typeface.create("monospace", Typeface.BOLD) }
        val tvCpuVal    = TextView(this).apply { text = "80%"; textSize = 18f; setTextColor(GREEN); typeface = Typeface.create("monospace", Typeface.BOLD) }
        cfgGrid.addView(miniCard(s.threads, tvThreadVal)); cfgGrid.addView(miniCard(s.cpuLimit, tvCpuVal))
        cfgSec.addView(cfgGrid)

        fun sliderCard(nameTv: TextView, bar: SeekBar): LinearLayout {
            val w = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = cardBg()
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            }
            w.addView(nameTv); w.addView(bar); return w
        }
        tvThreads = TextView(this).apply { setTextColor(TXT_PRI); textSize = 10f }
        sbThreads = SeekBar(this).apply {
            max = 7; progress = 3
            setOnSeekBarChangeListener(mkSbl { updateLabels(); tvThreadVal.text = "${sbThreads.progress+1}" })
        }
        cfgSec.addView(sliderCard(tvThreads, sbThreads))
        tvCpu = TextView(this).apply { setTextColor(TXT_PRI); textSize = 10f }
        sbCpu = SeekBar(this).apply {
            max = 90; progress = 70
            setOnSeekBarChangeListener(mkSbl {
                updateLabels()
                tvCpuVal.text = "${sbCpu.progress+10}%"
                tvCpuVal.setTextColor(if(sbCpu.progress+10<=40) GREEN else if(sbCpu.progress+10<=70) YELLOW else RED)
                if(HunterEngine.isRunning()) HunterEngine.setCpuLimit(sbCpu.progress+10)
            })
        }
        cfgSec.addView(sliderCard(tvCpu, sbCpu))
        updateLabels()

        /* SEARCH MODE */
        val modeSec = pad().also { main.addView(it) }
        modeSec.addView(sectionHdr(s.modeSection))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rbBip39  = modeTabBtn(s.modeBip39)
        rbPuzzle = modeTabBtn(s.modePuzzle)
        if(puzzleMode) setTabActive(rbPuzzle) else setTabActive(rbBip39)
        modeRow.addView(rbBip39); modeRow.addView(rbPuzzle); modeSec.addView(modeRow)

        layoutPuzzle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if(puzzleMode) View.VISIBLE else View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size
        layoutPuzzle.addView(TextView(this).apply { text = s.puzzleSelect; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, 0, 0, dp(4)) })
        puzzleSpinner = Spinner(this).apply {
            adapter = themedAdapter(puzzles.map { "#${it.num}  -  ${it.btc}  -  ${it.addr.take(16)}..." })
            setSelection(defaultIdx)
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C) }
        }
        layoutPuzzle.addView(puzzleSpinner)

        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun fieldCol(lbl: String): Pair<LinearLayout, EditText> {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            }
            col.addView(TextView(this).apply { text = lbl; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, 0, 0, dp(2)) })
            val et = EditText(this).apply {
                setTextColor(TXT_PRI); textSize = 10f; typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C) }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            col.addView(et); return Pair(col, et)
        }
        val (colS, etS) = fieldCol(s.rangeStart); etRangeStart = etS; rangeRow.addView(colS)
        val (colE, etE) = fieldCol(s.rangeEnd);   etRangeEnd   = etE; rangeRow.addView(colE)
        layoutPuzzle.addView(rangeRow)

        layoutPuzzle.addView(TextView(this).apply { text = s.targetAddr; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, dp(8), 0, dp(2)) })
        etTarget = EditText(this).apply {
            setTextColor(AMBER); textSize = 10f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C) }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layoutPuzzle.addView(etTarget)
        tvPuzzleStatus = TextView(this).apply {
            text = ""; textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(GREEN)
            background = GradientDrawable().apply { setColor(0x1510D97A); setStroke(1, 0x2510D97A) }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        layoutPuzzle.addView(tvPuzzleStatus)
        applyPuzzle(puzzles[defaultIdx])
        puzzleSpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) { if(init){init=false;return}; applyPuzzle(puzzles[pos]) }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }
        modeSec.addView(layoutPuzzle)

        val modeToggle = { isPuzzle: Boolean ->
            puzzleMode = isPuzzle
            layoutPuzzle.visibility = if(isPuzzle) View.VISIBLE else View.GONE
            csvSecView.visibility   = if(isPuzzle) View.GONE else View.VISIBLE
            HunterEngine.setMode(if(isPuzzle) 1 else 0)
            if(isPuzzle) setTabActive(rbPuzzle) else setTabActive(rbBip39)
        }
        rbBip39.setOnClickListener  { modeToggle(false) }
        rbPuzzle.setOnClickListener { modeToggle(true) }

        /* ACTION BUTTON */
        val coinGreen = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(AppTheme.GREEN)
            cornerRadius = dp(6).toFloat()
        }
        val coinRed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(AppTheme.RED)
            cornerRadius = dp(6).toFloat()
        }
        btnToggle = Button(this).apply {
            text = s.start; background = coinGreen; setTextColor(Color.BLACK)
            textSize = 13f; typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.12f; isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(28); bottomMargin = dp(4)
            }
            setOnClickListener { doToggle() }
        }
        btnToggle.tag = arrayOf(coinGreen, coinRed)
        val btnWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnWrap.addView(btnToggle); main.addView(btnWrap)

        /* STATISTICS */
        val statSec = pad().also { main.addView(it) }
        tvStatsSec = sectionHdr(s.statsSection).also { statSec.addView(it) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvWps   = TextView(this).apply { text="0"; textSize=22f; setTextColor(AMBER); typeface=Typeface.create("monospace",Typeface.BOLD) }
        tvCount = TextView(this).apply { text="0"; textSize=22f; setTextColor(TXT_PRI); typeface=Typeface.create("monospace",Typeface.BOLD) }
        tvTime  = TextView(this).apply { text="00:00:00"; textSize=18f; setTextColor(TXT_PRI); typeface=Typeface.create("monospace",Typeface.BOLD) }
        fun wrapStat(tv: TextView, lbl: String, color: Int = TXT_PRI): LinearLayout {
            val c = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; background=cardBg()
                setPadding(dp(10),dp(10),dp(10),dp(10))
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{marginEnd=dp(2)}
            }
            c.addView(tv)
            c.addView(TextView(this).apply{text=lbl;textSize=9f;setTextColor(TXT_SEC)})
            return c
        }
        row1.addView(wrapStat(tvWps,"Keys/sec",AMBER))
        row1.addView(wrapStat(tvCount,"Scanned"))
        row1.addView(wrapStat(tvTime,"Elapsed"))
        statSec.addView(row1)

        val matchRow = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; background=cardBg()
            setPadding(dp(14),dp(10),dp(14),dp(10))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)}
        }
        val mLeft = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f) }
        tvMatches = TextView(this).apply { text="0"; textSize=24f; setTextColor(TXT_MUTED); typeface=Typeface.create("monospace",Typeface.BOLD) }
        mLeft.addView(TextView(this).apply{text=s.matches;textSize=9f;setTextColor(TXT_SEC)})
        mLeft.addView(tvMatches)
        matchRow.addView(mLeft)
        matchRow.addView(TextView(this).apply{text="~";textSize=18f;setTextColor(AMBER);typeface=Typeface.MONOSPACE})
        statSec.addView(matchRow)

        val sparkWrap = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; background=cardBg()
            setPadding(dp(12),dp(8),dp(12),dp(8))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)}
        }
        val sparkHdr = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,0,0,dp(4)) }
        sparkHdr.addView(TextView(this).apply{text="Speed History";textSize=9f;setTextColor(TXT_SEC);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        sparkWrap.addView(sparkHdr)
        chartView = SpeedChartView(this).apply { layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(52)) }
        sparkWrap.addView(chartView); statSec.addView(sparkWrap)

        /* LIVE SCAN */
        val liveSec = pad().also { main.addView(it) }
        tvLiveSec = sectionHdr(s.liveSection).also { liveSec.addView(it) }
        val livePanel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=cardBg() }
        val liveHdr = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_ELEV); setPadding(dp(12),dp(6),dp(12),dp(6))
        }
        val liveDot = View(this).apply {
            background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(GREEN)}
            layoutParams=LinearLayout.LayoutParams(dp(6),dp(6)).apply{marginEnd=dp(6)}
        }
        liveHdr.addView(liveDot)
        liveHdr.addView(TextView(this).apply{text="Streaming";textSize=9f;setTextColor(TXT_PRI);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        liveHdr.addView(TextView(this).apply{text="last 3";textSize=9f;setTextColor(TXT_MUTED)})
        livePanel.addView(liveHdr)
        tvAddrFeed = TextView(this).apply {
            text=s.waitingStart; setTextColor(GREEN); textSize=10f; typeface=Typeface.MONOSPACE
            setPadding(dp(12),dp(8),dp(12),dp(8)); setLineSpacing(0f,1.4f)
        }
        livePanel.addView(tvAddrFeed); liveSec.addView(livePanel)

        /* MATCHES */
        val matchSec = pad().also { main.addView(it) }
        tvMatchSec = sectionHdr(s.matchSection).also { matchSec.addView(it) }
        tvMatchList = TextView(this).apply {
            text=s.noMatch; setTextColor(TXT_MUTED); textSize=11f; typeface=Typeface.MONOSPACE
            background=cardBg(); setPadding(dp(14),dp(12),dp(14),dp(12))
        }
        matchSec.addView(tvMatchList)

        /* LOG */
        val logSec = pad().also { main.addView(it) }
        tvLogSec = sectionHdr(s.logSection).also { logSec.addView(it) }
        val logPanel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=cardBg() }
        val logHdr = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_ELEV); setPadding(dp(12),dp(6),dp(12),dp(6))
        }
        logHdr.addView(TextView(this).apply{text="System Events";textSize=9f;setTextColor(TXT_PRI);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        logHdr.addView(TextView(this).apply{text="last session";textSize=9f;setTextColor(TXT_MUTED)})
        logPanel.addView(logHdr)
        tvLog = TextView(this).apply {
            text=""; setTextColor(TXT_SEC); textSize=9f; typeface=Typeface.MONOSPACE
            setPadding(dp(12),dp(8),dp(12),dp(8)); setLineSpacing(0f,1.3f)
        }
        logPanel.addView(tvLog); logSec.addView(logPanel)

        /* FOOTER */
        tvFooter = TextView(this).apply {
            text="Propiedad de Dax2201  |  Optimizado por Claude"
            textSize=9f; setTextColor(TXT_MUTED); gravity=Gravity.CENTER; setPadding(0,dp(20),0,dp(4))
        }
        main.addView(tvFooter)
        root.addView(main); setContentView(root)

        // Restaurar estado si viene de cambio de tema
        val uiSp = getSharedPreferences("ui_state", MODE_PRIVATE)
        if (uiSp.contains("puzzleMode")) {
            val wasPuzzle = uiSp.getBoolean("puzzleMode", false)
            sbThreads.progress = uiSp.getInt("threads", 3)
            sbCpu.progress = uiSp.getInt("cpu", 70)
            // Siempre restaurar modo
            puzzleMode = wasPuzzle
            layoutPuzzle.visibility = if(wasPuzzle) View.VISIBLE else View.GONE
            csvSecView.visibility   = if(wasPuzzle) View.GONE else View.VISIBLE
            HunterEngine.setMode(if(wasPuzzle) 1 else 0)
            if(wasPuzzle) setTabActive(rbPuzzle) else setTabActive(rbBip39)
            // Puzzle spinner
            val savedPuzzleIdx = uiSp.getInt("puzzleIdx", 0)
            puzzleSpinner.setSelection(savedPuzzleIdx)
            // Campos de texto
            val rs = uiSp.getString("rangeStart", "") ?: ""
            val re = uiSp.getString("rangeEnd", "") ?: ""
            val tg = uiSp.getString("target", "") ?: ""
            if (rs.isNotEmpty()) etRangeStart.setText(rs)
            if (re.isNotEmpty()) etRangeEnd.setText(re)
            if (tg.isNotEmpty() && ::etTarget.isInitialized) etTarget.setText(tg)
            // Wallet activo
            val savedWallet = uiSp.getString("activeWallet", "") ?: ""
            if (savedWallet.isNotEmpty()) {
                WalletManager.setActiveWallet(this, savedWallet)
                val wName = WalletManager.listWallets(this).firstOrNull { pair -> pair.first == savedWallet }?.second ?: "Wallet"
                btnSwitch.text = wName.take(10)
            }
            // Estado START/STOP
            val wasRunning = uiSp.getBoolean("isRunning", false)
            btnToggle.text = if (wasRunning) Strings.get(this).stop else Strings.get(this).start
            // Log
            val savedLog = uiSp.getString("logBuf", "") ?: ""
            if (savedLog.isNotEmpty()) { logBuf.clear(); logBuf.append(savedLog); tvLog.text = logBuf.toString() }
            updateLabels()
            uiSp.edit().clear().apply()
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
                                        val re = Regex("\"([^\"]+)\":[\"\'](.*?)[\"\']")
                    re.findAll(inner).forEach { m: MatchResult -> entries.add(Pair(m.groupValues[1], m.groupValues[2])) }
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
        try {
            val f = File(getExternalFilesDir(null), "hunter_log_${System.currentTimeMillis()}.txt")
            f.writeText(log.toString())
            Toast.makeText(this, "Log saved: ${f.name}", Toast.LENGTH_LONG).show()
        } catch(e: Exception) {
            Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
            btnToggle.text=s.start
            btnToggle.background=(btnToggle.tag as? Array<*>)?.get(0) as? GradientDrawable ?: btnToggle.background
        }
        btnToggle.isEnabled=(loaded&&!loading)||puzzleMode
        val wps=HunterEngine.getWps()
        tvWps.text=if(wps>=1e6)"%.2fM".format(wps/1e6) else if(wps>=1000)"%.1fK".format(wps/1000) else "%.0f".format(wps)
        if(running) chartView.addPoint(wps.toFloat())
        tvWps.setTextColor(if(running)AMBER else TXT_MUTED)
        val count=HunterEngine.getCount(); tvCount.text=if(count>=1_000_000)"%.2fM".format(count/1e6) else if(count>=1000)"%.1fK".format(count/1000.0) else "$count"
        val e=HunterEngine.getElapsed(); tvTime.text="%02d:%02d:%02d".format(e/3600,(e%3600)/60,e%60)
        val found=HunterEngine.getFound(); tvMatches.text="$found"; tvMatches.setTextColor(if(found>0)YELLOW else TXT_MUTED)
        val m=HunterEngine.getMatches(); if(m.isNotEmpty()){tvMatchList.text=m;tvMatchList.setTextColor(YELLOW)}
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
}
