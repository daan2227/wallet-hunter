package com.hunter.btc

import android.app.*
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.hunter.btc.recovery.RecoveryEngine
import com.hunter.btc.recovery.RecoveryParser
import com.hunter.btc.recovery.ParseResult

class SpeedChartView(context: android.content.Context) : android.view.View(context) {
    private val maxPoints = 60
    private val wpsPoints = ArrayDeque<Float>()
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.TXT_PRI; strokeWidth = 1.5f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val paintLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.TXT_MUTED; textSize = 18f; typeface = Typeface.MONOSPACE
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
            shader=LinearGradient(0f,0f,0f,h,0x14FFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP)
            style=Paint.Style.FILL })
        val lp=Path(); pts.forEachIndexed{i,p->if(i==0)lp.moveTo(p.x,p.y) else lp.lineTo(p.x,p.y)}
        canvas.drawPath(lp,paintLine)
        canvas.drawCircle(pts.last().x,pts.last().y,4f,paintDot)
        val wpsMax=wpsPoints.maxOrNull()?:0f
        val lbl=if(wpsMax>=1e6)"%.1fM".format(wpsMax/1e6) else if(wpsMax>=1000)"%.0fK".format(wpsMax/1000) else "%.0f".format(wpsMax)
        canvas.drawText(lbl,pad+2,22f,paintLbl)
    }
}

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    // ── Tema ──────────────────────────────────────────────────────────────────
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── Tab system ────────────────────────────────────────────────────────────
    private var tabPages:    List<android.view.View>          = emptyList()
    private var tabBtns:     List<android.widget.TextView>    = emptyList()
    private var contentFrame: android.widget.FrameLayout?     = null
    private var drawerOpen:   Boolean                         = false
    private var menuBtn:      android.view.View?              = null
    private var drawerView:   android.view.View?              = null
    private var overlayView:  android.view.View?              = null
    private fun goTab(idx: Int) {
        tvHeaderTitle?.text = when (idx) {
            0 -> "Escáner"; 1 -> "Puzzle"; 2 -> "Cartera"; else -> "Recuperar seed"
        }
        tabPages.forEachIndexed { i, v ->
            v.visibility = if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
        }
        updateDrawerSelection(idx)
    }

    // ── Variables ─────────────────────────────────────────────────────────────
    private var sessionStartTime = 0L
    private var sessionStartCount = 0L
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var lastFoundCount = 0L
    private val NOTIF_CHANNEL = "hunter_match"
    private val NOTIF_ID = 42
    private val handler = Handler(Looper.getMainLooper())
    private var tvStatus: TextView? = null
    private var tvCsvName: TextView? = null
    private var tvQuickCsv: TextView? = null
    private var tvQuickMatches: TextView? = null
    private var tvWps: TextView? = null
    private var tvKps: TextView? = null
    private var tvQuickThreads: TextView? = null
    private var tvQuickCpu: TextView? = null
    private var fastModeEnabled = false
    private var filterP2PKH  = true
    private var filterP2SH   = true
    private var filterP2WPKH = true
    private var lblDataset: TextView? = null
    private var lblPerformance: TextView? = null
    private var lblMode: TextView? = null
    private var lblWallet: TextView? = null
    private var lblLog: TextView? = null
    private var lblStats: TextView? = null
    private var tvLiveSec: LinearLayout? = null
    private var tvMatchSec: LinearLayout? = null
    private var tvLogSec: LinearLayout? = null
    private var tvLangLbl: TextView? = null
    private var tvCount: TextView? = null
    private var chartView: SpeedChartView? = null
    private var tvPuzzleStatus: TextView? = null
    private var tvTime: TextView? = null
    private var tvMatches: TextView? = null
    private var tvMatchList: TextView? = null
    private var tvAddrFeed: TextView? = null
    private var tvRam: TextView? = null
    private var tvTemp: TextView? = null
    private var tvBattery: TextView? = null
    private var tvLog: TextView? = null
    private var puzzleTabReady = false
    private var tvFooter: TextView? = null
    private var btnToggle: Button? = null
    /** Línea de estado del escáner: "Buscando · 51 s". */
    private var tvScanState: TextView? = null
    private var scanStateDot: android.view.View? = null
    /** Resumen a la derecha de las filas de ajuste: "8 hilos · 100 %". */
    private var tvEngineSummary: TextView? = null
    private var tvClusterSummary: TextView? = null
    /** "Sin atajo: fuerza bruta" / "Admite Kangaroo". */
    private var tvPuzzleAtajo: TextView? = null
    /** Botón de Kangaroo: sólo aparece si la clave pública es conocida. */
    private var btnKangaroo: Button? = null
    /** Clave pública del puzzle elegido, si está publicada. */
    private var puzzlePubHex: String = ""
    private var puzzleIniHex: String = ""
    private var puzzleFinHex: String = ""
    private var ultimoGuardadoKg = 0L
    private var kangarooReinicios = 0
    /** A qué puzzle pertenece la respuesta que estamos esperando. */
    private var puzzleSeleccionado = -1
    /** Título de la pantalla en la cabecera, que cambia con la pestaña. */
    private var tvHeaderTitle: TextView? = null
    private var btnSwitch: Button? = null
    private var sbThreads: SeekBar? = null
    private var sbCpu: SeekBar? = null
    // Puzzle tiene sus propios sliders independientes
    private var sbThreadsPuzzle: SeekBar? = null
    private var sbCpuPuzzle: SeekBar? = null
    private var tvThreadsPuzzle: TextView? = null
    private var tvCpuPuzzle: TextView? = null
    private var tvThreads: TextView? = null
    private var tvCpu: TextView? = null
    private var tvCsvSec: LinearLayout? = null
    private var tvConfigSec: LinearLayout? = null
    private var tvStatsSec: LinearLayout? = null
    private var btnCsv: Button? = null
    private var csvSecView: LinearLayout? = null
    private var etRangeStart: EditText? = null
    private var etRangeEnd: EditText? = null
    private var currentRangeStart: String = ""
    private var currentRangeEnd: String = ""
    private val BLOCK_SIZE = java.math.BigInteger("1000000000") // 1B keys por bloque
    private val REQ_IMPORT_PROGRESS = 1003
    private var currentBlockId: String = ""
    private var tvBlockProgress: TextView? = null
    private var etTarget: EditText? = null
    private var layoutPuzzle: LinearLayout? = null
    private var rbBip39: Button? = null
    private var rbPuzzle: Button? = null
    private var csvPath: String = ""
    private var s = Strings.EN
    private var puzzleMode = false
    private var selectedScanMode = 0 // 0=BIP39, 2=RawKey
    private var fastScanRow: android.view.View? = null
    private var tvBinInfoRef: TextView? = null
    private var tvDatasetStat: TextView? = null
    private var peakWps: Double = 0.0
    private var avgWpsSum: Double = 0.0
    private var avgWpsCount: Long = 0
    private val numberFmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
    private var cachedPuzzleLabel: String = ""
    private var cachedPuzzleLabelForStart: String = ""
    private var tvAvgWps: TextView? = null
    private var tvPeakWps: TextView? = null
    private var tvPeakWpsPuzzle: TextView? = null
    private var watchdogEnabled = false
    private var lastKnownRunning = false
    private var tvRandomJump: TextView? = null
    private var tvCurrentBlock: TextView? = null
    /** Bloque elegido a mano con "Saltar a un punto aleatorio"; lo usa el próximo START. */
    private var pendingBlockIdx: java.math.BigInteger? = null
    /** Reinicios hechos por el watchdog en esta sesión; se muestra en su etiqueta. */
    private var watchdogRestarts = 0
    private var activeToggleBtn: Button? = null
    private var tvWpsPuzzle: TextView? = null
    private var tvPctPuzzle: TextView? = null
    private var tvSpeedUnitPuzzle: TextView? = null
    private var tvSpeedUnitScan: TextView? = null
    /* Rango completo del puzzle. currentRangeStart/End apuntan al BLOQUE en
       curso (BLOCK_SIZE claves), así que usarlos para el progreso global
       comparaba la sesión entera contra un bloque y daba "1 de 0". */
    private var puzzleProgressUpdater: ((Int, String, String) -> Unit)? = null
    private var lastProgressTick = 0L
    /** Último getFound() visto, para saber cuándo hay aciertos nuevos que guardar. */
    private var lastFoundSeen = -1L
    private var puzzleFullStart: String = ""
    private var puzzleFullEnd: String = ""

    /** Velocidad escalada + unidad, para no volver a mentir con la etiqueta. */
    /**
     * Número del puzzle seleccionado.
     *
     * Se resolvía con `puzzles.firstOrNull { it.start == currentRangeStart }`,
     * pero en cuanto se pulsa START currentRangeStart pasa a ser el inicio del
     * BLOQUE en curso, que no coincide con el start de ningún puzzle. A partir
     * de ahí esa búsqueda devolvía null siempre y cada sitio hacía algo distinto
     * con el fallo — ver los tres sitios que la usaban.
     *
     * puzzleFullStart sí guarda el rango del puzzle y no lo pisa la lógica de
     * bloques; prefs es el respaldo, que applyPuzzle() ya deja escrito.
     */
    private fun currentPuzzleNum(): Int =
        puzzles.firstOrNull { it.start == puzzleFullStart }?.num
            ?: prefs.getInt("current_puzzle_num", 0)

    private fun watchdogLabel() = when {
        // watchdogRestarts sólo se incrementaba y no se leía en ningún sitio.
        // Puesto aquí sirve para saber si de verdad está haciendo algo.
        watchdogEnabled && watchdogRestarts > 0 ->
            "Watchdog ON — $watchdogRestarts reinicio(s) esta sesión"
        watchdogEnabled -> "Watchdog ON — reinicia el scan si se detiene"
        else            -> "Watchdog OFF — no reinicia el scan"
    }

    private fun scaleSpeed(keysPerSec: Double): Pair<String, String> = when {
        keysPerSec >= 1e9 -> "%.2f".format(keysPerSec / 1e9) to "GKeys"
        keysPerSec >= 1e6 -> "%.2f".format(keysPerSec / 1e6) to "MKeys"
        keysPerSec >= 1e3 -> "%.1f".format(keysPerSec / 1e3) to "kKeys"
        else              -> numberFmt.format(keysPerSec.toLong()) to "Keys"
    }

    /**
     * Progreso sobre el rango del puzzle. Son fracciones del orden de 1e-11, así
     * que un porcentaje con decimales sólo muestra ceros — antes era además un
     * literal fijo que no se calculaba nunca. Se expresa como "1 de cada N".
     */
    /**
     * ETA legible. Antes se hacía BigInteger.toLong() sobre el número de
     * segundos, que para puzzles grandes (2.8e29 s en el 120) no cabe en Long y
     * devolvía los 64 bits bajos, es decir, un valor arbitrario.
     */
    private fun formatEta(secs: java.math.BigInteger): String {
        if (secs.signum() <= 0) return "—"
        val min  = java.math.BigInteger.valueOf(60)
        val hour = java.math.BigInteger.valueOf(3600)
        val day  = java.math.BigInteger.valueOf(86400)
        val year = java.math.BigInteger.valueOf(86400L * 365)
        return when {
            secs < min  -> "${secs}s"
            secs < hour -> "${secs.divide(min)}m"
            secs < day  -> "${secs.divide(hour)}h"
            secs < year -> "${secs.divide(day)}d"
            else -> {
                val years = secs.divide(year)
                val d = years.toString().length
                when {
                    d <= 3 -> "$years años"
                    d <= 6 -> "${years.divide(java.math.BigInteger.valueOf(1000))}k años"
                    d <= 9 -> "${years.divide(java.math.BigInteger.valueOf(1_000_000))}M años"
                    else   -> "10^${d - 1} años"
                }
            }
        }
    }

    private fun formatPuzzleProgress(scanned: Long, start: String, end: String): String {
        return try {
            if (start.isEmpty() || end.isEmpty() || scanned <= 0) return "—"
            val s = java.math.BigInteger(start.trimStart('0').ifEmpty { "0" }, 16)
            val e = java.math.BigInteger(end.trimStart('0').ifEmpty { "0" }, 16)
            val total = e.subtract(s)
            if (total.signum() <= 0) return "—"
            val ratio = total.divide(java.math.BigInteger.valueOf(scanned))
            if (ratio.signum() <= 0) return "rango cubierto"
            val digits = ratio.toString().length
            if (digits <= 6) "1 de ${numberFmt.format(ratio.toLong())}"
            else "1 de 10^${digits - 1}"
        } catch (e: Exception) { "—" }
    }
    private var tvCheckpointLive: TextView? = null
    private var tvCountPuzzle: TextView? = null
    private var tvTimePuzzle: TextView? = null
    private var btnPuzzleToggle: Button? = null
    private val recentAddrs = mutableListOf<String>()
    private var recoveryEngine: RecoveryEngine? = null
    private val prefs get() = getSharedPreferences("hunter", MODE_PRIVATE)

    data class PuzzleInfo(val num: Int, val addr: String, val start: String, val end: String, val btc: String)
    private val puzzles = listOf(
        // Los 77 puzzles SIN RESOLVER, a 2026-09-16.
        //
        // La tabla anterior estaba mal de tres formas a la vez: doce entradas
        // no eran ni direcciones de Bitcoin —dos llevaban una 'l', que no
        // existe en Base58, y diez fallaban el checksum—, las direcciones que
        // sí valían estaban en el número equivocado, y había trece puzzles ya
        // resueltos ofreciéndose como objetivo.
        //
        // Los RANGOS no se transcriben: se calculan. El puzzle N va de 2^(N-1)
        // a 2^N - 1 por definición, así que generarlos quita de en medio la
        // única parte donde un dedo puede equivocarse sin que se note.
        //
        // Cada dirección está comprobada contra su checksum Base58Check antes
        // de entrar aquí, y buildPuzzleTab vuelve a comprobarlas al arrancar.
        PuzzleInfo(71, "1PWo3JeB9jrGwfHDNpdGK54CRas7fsVzXU", "400000000000000000", "7fffffffffffffffff", "7.10099385 BTC"),
        PuzzleInfo(72, "1JTK7s9YVYywfm5XUH7RNhHJH1LshCaRFR", "800000000000000000", "ffffffffffffffffff", "7.20003779 BTC"),
        PuzzleInfo(73, "12VVRNPi4SJqUTsp6FmqDqY5sGosDtysn4", "1000000000000000000", "1ffffffffffffffffff", "7.30003777 BTC"),
        PuzzleInfo(74, "1FWGcVDK3JGzCC3WtkYetULPszMaK2Jksv", "2000000000000000000", "3ffffffffffffffffff", "7.40003777 BTC"),
        PuzzleInfo(76, "1DJh2eHFYQfACPmrvpyWc8MSTYKh7w9eRF", "8000000000000000000", "fffffffffffffffffff", "7.6 BTC"),
        PuzzleInfo(77, "1Bxk4CQdqL9p22JEtDfdXMsng1XacifUtE", "10000000000000000000", "1fffffffffffffffffff", "7.70001826 BTC"),
        PuzzleInfo(78, "15qF6X51huDjqTmF9BJgxXdt1xcj46Jmhb", "20000000000000000000", "3fffffffffffffffffff", "7.8 BTC"),
        PuzzleInfo(79, "1ARk8HWJMn8js8tQmGUJeQHjSE7KRkn2t8", "40000000000000000000", "7fffffffffffffffffff", "7.9 BTC"),
        PuzzleInfo(81, "15qsCm78whspNQFydGJQk5rexzxTQopnHZ", "100000000000000000000", "1ffffffffffffffffffff", "8.1 BTC"),
        PuzzleInfo(82, "13zYrYhhJxp6Ui1VV7pqa5WDhNWM45ARAC", "200000000000000000000", "3ffffffffffffffffffff", "8.2 BTC"),
        PuzzleInfo(83, "14MdEb4eFcT3MVG5sPFG4jGLuHJSnt1Dk2", "400000000000000000000", "7ffffffffffffffffffff", "8.30000546 BTC"),
        PuzzleInfo(84, "1CMq3SvFcVEcpLMuuH8PUcNiqsK1oicG2D", "800000000000000000000", "fffffffffffffffffffff", "8.4 BTC"),
        PuzzleInfo(86, "1K3x5L6G57Y494fDqBfrojD28UJv4s5JcK", "2000000000000000000000", "3fffffffffffffffffffff", "8.6 BTC"),
        PuzzleInfo(87, "1PxH3K1Shdjb7gSEoTX7UPDZ6SH4qGPrvq", "4000000000000000000000", "7fffffffffffffffffffff", "8.7 BTC"),
        PuzzleInfo(88, "16AbnZjZZipwHMkYKBSfswGWKDmXHjEpSf", "8000000000000000000000", "ffffffffffffffffffffff", "8.8 BTC"),
        PuzzleInfo(89, "19QciEHbGVNY4hrhfKXmcBBCrJSBZ6TaVt", "10000000000000000000000", "1ffffffffffffffffffffff", "8.9 BTC"),
        PuzzleInfo(91, "1EzVHtmbN4fs4MiNk3ppEnKKhsmXYJ4s74", "40000000000000000000000", "7ffffffffffffffffffffff", "9.1 BTC"),
        PuzzleInfo(92, "1AE8NzzgKE7Yhz7BWtAcAAxiFMbPo82NB5", "80000000000000000000000", "fffffffffffffffffffffff", "9.2 BTC"),
        PuzzleInfo(93, "17Q7tuG2JwFFU9rXVj3uZqRtioH3mx2Jad", "100000000000000000000000", "1fffffffffffffffffffffff", "9.3 BTC"),
        PuzzleInfo(94, "1K6xGMUbs6ZTXBnhw1pippqwK6wjBWtNpL", "200000000000000000000000", "3fffffffffffffffffffffff", "9.4 BTC"),
        PuzzleInfo(96, "15ANYzzCp5BFHcCnVFzXqyibpzgPLWaD8b", "800000000000000000000000", "ffffffffffffffffffffffff", "9.6 BTC"),
        PuzzleInfo(97, "18ywPwj39nGjqBrQJSzZVq2izR12MDpDr8", "1000000000000000000000000", "1ffffffffffffffffffffffff", "9.7 BTC"),
        PuzzleInfo(98, "1CaBVPrwUxbQYYswu32w7Mj4HR4maNoJSX", "2000000000000000000000000", "3ffffffffffffffffffffffff", "9.8 BTC"),
        PuzzleInfo(99, "1JWnE6p6UN7ZJBN7TtcbNDoRcjFtuDWoNL", "4000000000000000000000000", "7ffffffffffffffffffffffff", "9.91257338 BTC"),
        PuzzleInfo(101, "1CKCVdbDJasYmhswB6HKZHEAnNaDpK7W4n", "10000000000000000000000000", "1fffffffffffffffffffffffff", "10.1 BTC"),
        PuzzleInfo(102, "1PXv28YxmYMaB8zxrKeZBW8dt2HK7RkRPX", "20000000000000000000000000", "3fffffffffffffffffffffffff", "10.2 BTC"),
        PuzzleInfo(103, "1AcAmB6jmtU6AiEcXkmiNE9TNVPsj9DULf", "40000000000000000000000000", "7fffffffffffffffffffffffff", "10.3 BTC"),
        PuzzleInfo(104, "1EQJvpsmhazYCcKX5Au6AZmZKRnzarMVZu", "80000000000000000000000000", "ffffffffffffffffffffffffff", "10.400016 BTC"),
        PuzzleInfo(106, "18KsfuHuzQaBTNLASyj15hy4LuqPUo1FNB", "200000000000000000000000000", "3ffffffffffffffffffffffffff", "10.6 BTC"),
        PuzzleInfo(107, "15EJFC5ZTs9nhsdvSUeBXjLAuYq3SWaxTc", "400000000000000000000000000", "7ffffffffffffffffffffffffff", "10.7 BTC"),
        PuzzleInfo(108, "1HB1iKUqeffnVsvQsbpC6dNi1XKbyNuqao", "800000000000000000000000000", "fffffffffffffffffffffffffff", "10.8 BTC"),
        PuzzleInfo(109, "1GvgAXVCbA8FBjXfWiAms4ytFeJcKsoyhL", "1000000000000000000000000000", "1fffffffffffffffffffffffffff", "10.9 BTC"),
        PuzzleInfo(111, "1824ZJQ7nKJ9QFTRBqn7z7dHV5EGpzUpH3", "4000000000000000000000000000", "7fffffffffffffffffffffffffff", "11.1001 BTC"),
        PuzzleInfo(112, "18A7NA9FTsnJxWgkoFfPAFbQzuQxpRtCos", "8000000000000000000000000000", "ffffffffffffffffffffffffffff", "11.2 BTC"),
        PuzzleInfo(113, "1NeGn21dUDDeqFQ63xb2SpgUuXuBLA4WT4", "10000000000000000000000000000", "1ffffffffffffffffffffffffffff", "11.3 BTC"),
        PuzzleInfo(114, "174SNxfqpdMGYy5YQcfLbSTK3MRNZEePoy", "20000000000000000000000000000", "3ffffffffffffffffffffffffffff", "11.4 BTC"),
        PuzzleInfo(116, "1MnJ6hdhvK37VLmqcdEwqC3iFxyWH2PHUV", "80000000000000000000000000000", "fffffffffffffffffffffffffffff", "11.6 BTC"),
        PuzzleInfo(117, "1KNRfGWw7Q9Rmwsc6NT5zsdvEb9M2Wkj5Z", "100000000000000000000000000000", "1fffffffffffffffffffffffffffff", "11.7 BTC"),
        PuzzleInfo(118, "1PJZPzvGX19a7twf5HyD2VvNiPdHLzm9F6", "200000000000000000000000000000", "3fffffffffffffffffffffffffffff", "11.80000661 BTC"),
        PuzzleInfo(119, "1GuBBhf61rnvRe4K8zu8vdQB3kHzwFqSy7", "400000000000000000000000000000", "7fffffffffffffffffffffffffffff", "11.9 BTC"),
        PuzzleInfo(121, "1GDSuiThEV64c166LUFC9uDcVdGjqkxKyh", "1000000000000000000000000000000", "1ffffffffffffffffffffffffffffff", "12.1 BTC"),
        PuzzleInfo(122, "1Me3ASYt5JCTAK2XaC32RMeH34PdprrfDx", "2000000000000000000000000000000", "3ffffffffffffffffffffffffffffff", "12.2 BTC"),
        PuzzleInfo(123, "1CdufMQL892A69KXgv6UNBD17ywWqYpKut", "4000000000000000000000000000000", "7ffffffffffffffffffffffffffffff", "12.3 BTC"),
        PuzzleInfo(124, "1BkkGsX9ZM6iwL3zbqs7HWBV7SvosR6m8N", "8000000000000000000000000000000", "fffffffffffffffffffffffffffffff", "12.4 BTC"),
        PuzzleInfo(126, "1AWCLZAjKbV1P7AHvaPNCKiB7ZWVDMxFiz", "20000000000000000000000000000000", "3fffffffffffffffffffffffffffffff", "12.6 BTC"),
        PuzzleInfo(127, "1G6EFyBRU86sThN3SSt3GrHu1sA7w7nzi4", "40000000000000000000000000000000", "7fffffffffffffffffffffffffffffff", "12.7 BTC"),
        PuzzleInfo(128, "1MZ2L1gFrCtkkn6DnTT2e4PFUTHw9gNwaj", "80000000000000000000000000000000", "ffffffffffffffffffffffffffffffff", "12.8 BTC"),
        PuzzleInfo(129, "1Hz3uv3nNZzBVMXLGadCucgjiCs5W9vaGz", "100000000000000000000000000000000", "1ffffffffffffffffffffffffffffffff", "12.9 BTC"),
        PuzzleInfo(131, "16zRPnT8znwq42q7XeMkZUhb1bKqgRogyy", "400000000000000000000000000000000", "7ffffffffffffffffffffffffffffffff", "13.1 BTC"),
        PuzzleInfo(132, "1KrU4dHE5WrW8rhWDsTRjR21r8t3dsrS3R", "800000000000000000000000000000000", "fffffffffffffffffffffffffffffffff", "13.2 BTC"),
        PuzzleInfo(133, "17uDfp5r4n441xkgLFmhNoSW1KWp6xVLD", "1000000000000000000000000000000000", "1fffffffffffffffffffffffffffffffff", "13.3 BTC"),
        PuzzleInfo(134, "13A3JrvXmvg5w9XGvyyR4JEJqiLz8ZySY3", "2000000000000000000000000000000000", "3fffffffffffffffffffffffffffffffff", "13.4 BTC"),
        PuzzleInfo(136, "1UDHPdovvR985NrWSkdWQDEQ1xuRiTALq", "8000000000000000000000000000000000", "ffffffffffffffffffffffffffffffffff", "13.6 BTC"),
        PuzzleInfo(137, "15nf31J46iLuK1ZkTnqHo7WgN5cARFK3RA", "10000000000000000000000000000000000", "1ffffffffffffffffffffffffffffffffff", "13.7 BTC"),
        PuzzleInfo(138, "1Ab4vzG6wEQBDNQM1B2bvUz4fqXXdFk2WT", "20000000000000000000000000000000000", "3ffffffffffffffffffffffffffffffffff", "13.8 BTC"),
        PuzzleInfo(139, "1Fz63c775VV9fNyj25d9Xfw3YHE6sKCxbt", "40000000000000000000000000000000000", "7ffffffffffffffffffffffffffffffffff", "13.9 BTC"),
        PuzzleInfo(140, "1QKBaU6WAeycb3DbKbLBkX7vJiaS8r42Xo", "80000000000000000000000000000000000", "fffffffffffffffffffffffffffffffffff", "14.00001 BTC"),
        PuzzleInfo(141, "1CD91Vm97mLQvXhrnoMChhJx4TP9MaQkJo", "100000000000000000000000000000000000", "1fffffffffffffffffffffffffffffffffff", "14.10014846 BTC"),
        PuzzleInfo(142, "15MnK2jXPqTMURX4xC3h4mAZxyCcaWWEDD", "200000000000000000000000000000000000", "3fffffffffffffffffffffffffffffffffff", "14.2 BTC"),
        PuzzleInfo(143, "13N66gCzWWHEZBxhVxG18P8wyjEWF9Yoi1", "400000000000000000000000000000000000", "7fffffffffffffffffffffffffffffffffff", "14.3 BTC"),
        PuzzleInfo(144, "1NevxKDYuDcCh1ZMMi6ftmWwGrZKC6j7Ux", "800000000000000000000000000000000000", "ffffffffffffffffffffffffffffffffffff", "14.4 BTC"),
        PuzzleInfo(145, "19GpszRNUej5yYqxXoLnbZWKew3KdVLkXg", "1000000000000000000000000000000000000", "1ffffffffffffffffffffffffffffffffffff", "14.5 BTC"),
        PuzzleInfo(146, "1M7ipcdYHey2Y5RZM34MBbpugghmjaV89P", "2000000000000000000000000000000000000", "3ffffffffffffffffffffffffffffffffffff", "14.6 BTC"),
        PuzzleInfo(147, "18aNhurEAJsw6BAgtANpexk5ob1aGTwSeL", "4000000000000000000000000000000000000", "7ffffffffffffffffffffffffffffffffffff", "14.7 BTC"),
        PuzzleInfo(148, "1FwZXt6EpRT7Fkndzv6K4b4DFoT4trbMrV", "8000000000000000000000000000000000000", "fffffffffffffffffffffffffffffffffffff", "14.8 BTC"),
        PuzzleInfo(149, "1CXvTzR6qv8wJ7eprzUKeWxyGcHwDYP1i2", "10000000000000000000000000000000000000", "1fffffffffffffffffffffffffffffffffffff", "14.90001 BTC"),
        PuzzleInfo(150, "1MUJSJYtGPVGkBCTqGspnxyHahpt5Te8jy", "20000000000000000000000000000000000000", "3fffffffffffffffffffffffffffffffffffff", "15.00001 BTC"),
        PuzzleInfo(151, "13Q84TNNvgcL3HJiqQPvyBb9m4hxjS3jkV", "40000000000000000000000000000000000000", "7fffffffffffffffffffffffffffffffffffff", "15.10001 BTC"),
        PuzzleInfo(152, "1LuUHyrQr8PKSvbcY1v1PiuGuqFjWpDumN", "80000000000000000000000000000000000000", "ffffffffffffffffffffffffffffffffffffff", "15.20001 BTC"),
        PuzzleInfo(153, "18192XpzzdDi2K11QVHR7td2HcPS6Qs5vg", "100000000000000000000000000000000000000", "1ffffffffffffffffffffffffffffffffffffff", "15.30001 BTC"),
        PuzzleInfo(154, "1NgVmsCCJaKLzGyKLFJfVequnFW9ZvnMLN", "200000000000000000000000000000000000000", "3ffffffffffffffffffffffffffffffffffffff", "15.40001 BTC"),
        PuzzleInfo(155, "1AoeP37TmHdFh8uN72fu9AqgtLrUwcv2wJ", "400000000000000000000000000000000000000", "7ffffffffffffffffffffffffffffffffffffff", "15.50001 BTC"),
        PuzzleInfo(156, "1FTpAbQa4h8trvhQXjXnmNhqdiGBd1oraE", "800000000000000000000000000000000000000", "fffffffffffffffffffffffffffffffffffffff", "15.60001 BTC"),
        PuzzleInfo(157, "14JHoRAdmJg3XR4RjMDh6Wed6ft6hzbQe9", "1000000000000000000000000000000000000000", "1fffffffffffffffffffffffffffffffffffffff", "15.70001 BTC"),
        PuzzleInfo(158, "19z6waranEf8CcP8FqNgdwUe1QRxvUNKBG", "2000000000000000000000000000000000000000", "3fffffffffffffffffffffffffffffffffffffff", "15.80001 BTC"),
        PuzzleInfo(159, "14u4nA5sugaswb6SZgn5av2vuChdMnD9E5", "4000000000000000000000000000000000000000", "7fffffffffffffffffffffffffffffffffffffff", "15.90002 BTC"),
        PuzzleInfo(160, "1NBC8uXJy1GiJ6drkiZa1WuKn51ps7EPTv", "8000000000000000000000000000000000000000", "ffffffffffffffffffffffffffffffffffffffff", "16.00019082 BTC")
    )

    private val updater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        // Capturar crashes globales.
        //
        // Se escribía además una copia en getExternalFilesDir(). Con minSdk 26 y
        // requestLegacyExternalStorage, en Android 8 y 9 cualquier app con
        // READ_EXTERNAL_STORAGE puede leer ese directorio, y un stack trace
        // arrastra el mensaje de la excepción, que suele llevar el dato que la
        // provocó. Ahora sólo va a almacenamiento interno, que es privado de la
        // app, y se acota para que no crezca sin fin.
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val msg = "\n=== $ts ===\n${e.javaClass.name}\n${e.message}\n${e.stackTraceToString()}\n"
                val f = java.io.File(filesDir, "crash_log.txt")
                if (f.length() > 256 * 1024) f.writeText("")   // no crecer sin límite
                f.appendText(msg)
                // Arrastra el que dejaron las versiones anteriores fuera.
                getExternalFilesDir(null)?.let { java.io.File(it, "crash_log.txt").delete() }
            } catch (ex: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        AppTheme.init(this)
        migrarTablaPuzzles()
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = BG_DEEP
        s = Strings.EN
        csvPath = prefs.getString("csvPath", "") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val cf = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        var scanScroll: ScrollView? = null
        var puzzleScroll: ScrollView? = null
        var walletScroll: ScrollView? = null
        var recoveryScroll: ScrollView? = null
        try {
            android.widget.Toast.makeText(this, "Building Scan...", android.widget.Toast.LENGTH_SHORT).show()
            scanScroll = buildScanTab()
            android.widget.Toast.makeText(this, "Building Puzzle...", android.widget.Toast.LENGTH_SHORT).show()
            try {
                puzzleScroll = buildPuzzleTab()
            } catch (e: Exception) {
                val msg = "PUZZLE_BUILD: ${e.javaClass.simpleName}: ${e.message}"
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                java.io.File(filesDir, "crash_log.txt").appendText("\n$msg\n${e.stackTraceToString()}\n")
            }
            android.widget.Toast.makeText(this, "Building Wallet...", android.widget.Toast.LENGTH_SHORT).show()
            walletScroll = buildWalletTab()
            android.widget.Toast.makeText(this, "Building Recovery...", android.widget.Toast.LENGTH_SHORT).show()
            recoveryScroll = buildRecoveryTab()

        } catch (e: Exception) {
            // Escribir error a archivo para diagnóstico
            try {
                val errFile = java.io.File(filesDir, "crash_log.txt")
                errFile.writeText("CRASH: ${e.javaClass.simpleName}\n${e.message}\n${e.stackTraceToString()}")
            } catch (ex: Exception) {}
            android.widget.Toast.makeText(this, "CRASH guardado en crash_log.txt", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }

        scanScroll?.let { cf.addView(it) }
        puzzleScroll?.let { cf.addView(it) }
        walletScroll?.let { cf.addView(it) }
        recoveryScroll?.let { cf.addView(it) }
        contentFrame = cf

        // ── Header + Drawer ───────────────────────────────────────────────────
        val header = buildHeader()
        root.addView(header)

        val drawerLayout = buildDrawerLayout()
        root.addView(drawerLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        tabPages = listOfNotNull(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
        tabBtns  = listOf<TextView>()
        goTab(0)

        // El motor escribe coincidencias.txt con los WIF en claro. Sin esto los
        // deja junto al CSV, normalmente en almacenamiento externo.
        try {
            HunterEngine.setMatchDir(filesDir.absolutePath)
            migrateLegacyMatchFile()
            // Lo que quedara en claro de la sesión anterior pasa al baúl cifrado.
            // Sólo se mueve el fichero: aquí NO se consulta ningún saldo. Hacerlo
            // en cada arranque mandaba todas las direcciones encontradas a
            // mempool.space sin que nadie lo pidiera. La consulta está en
            // "Actualizar Balance" y en el botón del baúl.
            MatchVault.ingestPlaintextFile(this)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "setMatchDir: ${e.message}", e)
        }

        // Init
        try {
            if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded())
                HunterEngine.loadCsv(csvPath)
            setupNotificationChannel()
            registerBatteryReceiver()
            // Auto-detectar hardware en primera ejecución
            selectedScanMode = prefs.getInt("scan_mode", 0)
            watchdogEnabled = prefs.getBoolean("watchdog", false)

            if (!prefs.getBoolean("hw_detected", false)) {
                val profile = detectHardware()
                applyHardwareProfile(profile)
                prefs.edit().putBoolean("hw_detected", true).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    showHardwareInfo()
                }, 1000)
            }
            updateLabels()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Init error: ${e.message}", e)
        }

        val uiSp = getSharedPreferences("ui_state", MODE_PRIVATE)
        if (uiSp.contains("puzzleMode")) {
            sbThreads?.progress = uiSp.getInt("threads", 3)
            sbCpu?.progress = uiSp.getInt("cpu", 70)
        }
    }


    // ── HEADER ───────────────────────────────────────────────────────────────────
    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(AppTheme.BG_DEEP)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            setPadding(dp(16), 0, dp(16), 0)
            elevation = dp(4).toFloat()
        }

        // El símbolo iba dentro de una cajita con borde, en monoespaciada, al
        // lado de un rótulo del mismo tamaño: dos elementos compitiendo por ser
        // el título. La caja sobra — el glifo en el acento ya identifica.
        header.addView(TextView(this).apply {
            text = "\u20BF"
            textSize = 15f
            setTextColor(AppTheme.BG_DEEP)
            typeface = AppTheme.display(context)
            gravity = Gravity.CENTER
            background = Ui.cardBg(9, AppTheme.ACCENT, context)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }
        })

        // El título decía "Wallet Hunter" en las cuatro pestañas, así que no
        // decía dónde estabas.
        tvHeaderTitle = TextView(this).apply {
            text = "Escáner"
            textSize = AppTheme.SP_TITLE
            typeface = AppTheme.title(context)
            letterSpacing = -0.01f
            setTextColor(AppTheme.TXT_PRI)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginStart = dp(12)
            }
        }
        header.addView(tvHeaderTitle)



        // Las tres barras se dibujaban con tres Views de 16x2dp dentro de un
        // botón con borde. Es un icono: ic_menu lo dibuja con el mismo trazo
        // que los demás, y sin caja alrededor.
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginEnd = -dp(8)   // el icono ya trae aire; alinea el trazo
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleDrawer() }
            addView(Ui.icon(this@MainActivity, R.drawable.ic_menu, 22, AppTheme.TXT_PRI))
        }
        menuBtn = menu
        header.addView(menu)

        return header
    }

    // ── DRAWER ───────────────────────────────────────────────────────────────────
    private fun buildDrawerLayout(): FrameLayout {
        val ACCENT = AppTheme.ACCENT
        val frame = FrameLayout(this)

        // Content frame (tabs go here)
        val cf = contentFrame ?: FrameLayout(this).also { contentFrame = it }
        frame.addView(cf, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Overlay
        val overlay = android.view.View(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            alpha = 0f
            visibility = android.view.View.GONE
            setOnClickListener { closeDrawer() }
        }
        overlayView = overlay
        frame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Drawer panel
        val drawerWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_PANEL)
            translationX = -drawerWidth.toFloat()
            elevation = dp(16).toFloat()
        }
        drawerView = drawer
        frame.addView(drawer, FrameLayout.LayoutParams(drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT))

        // Drawer header
        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        val dTitle = TextView(this).apply {
            text = android.text.SpannableString("WalletHunter").also { sp ->
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(ACCENT),
                    6, 12,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 20f
            typeface = AppTheme.title(context)
            setTextColor(AppTheme.TXT_PRI)
        }
        val dSub = TextView(this).apply {
            text = "com.hunter.btc · ARM64"
            textSize = AppTheme.SP_MICRO
            typeface = Typeface.MONOSPACE   // es un identificador, va monoespaciado
            setTextColor(AppTheme.TXT_MUTED)
            setPadding(0, dp(5), 0, 0)
        }
        drawerHeader.addView(dTitle)
        drawerHeader.addView(dSub)

        // Divider
        val divider = android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
        drawer.addView(drawerHeader)
        drawer.addView(divider)

        // Nav items
        val navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // El icono era un emoji dentro de un TextView, así que lo dibujaba la
        // fuente del sistema: distinto en cada móvil, en color, y sin poder
        // teñirlo para marcar la pestaña activa.
        data class NavItem(val icon: Int, val label: String, val idx: Int, val special: Boolean = false)
        val items = listOf(
            NavItem(R.drawable.ic_scan,     "Escáner",  0),
            NavItem(R.drawable.ic_puzzle,   "Puzzle",   1),
            NavItem(R.drawable.ic_wallet,   "Cartera",  2),
            NavItem(R.drawable.ic_recovery, "Recovery", 3),
            NavItem(R.drawable.ic_stats,    "Historial", -3, true),
            NavItem(R.drawable.ic_network,  "Cluster",  -1, true),
            NavItem(R.drawable.ic_debug,    "Debug",    -2, true)
        )

        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(4)) }
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(AppTheme.R_INNER).toFloat()
                    setColor(if (item.idx == 0) AppTheme.BG_ELEV else android.graphics.Color.TRANSPARENT)
                }
                tag = "nav_${item.idx}"
                setOnClickListener {
                    when {
                        item.idx == -3 -> {
                            startActivity(android.content.Intent(this@MainActivity, StatsActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == -1 -> {
                            startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == -2 -> {
                            startActivity(android.content.Intent(this@MainActivity, DebugActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == 2 -> {
                            if (WalletManager.hasPin(this@MainActivity) && !PinAuthHelper.isSessionValid()) {
                                PinAuthHelper.show(this@MainActivity) { ok -> if (ok) { goTab(2); closeDrawer() } }
                            } else { goTab(item.idx); closeDrawer() }
                        }
                        else -> { goTab(item.idx); closeDrawer() }
                    }
                }
            }

            val iconTv = android.widget.ImageView(this).apply {
                setImageResource(item.icon)
                // Se tiñe para poder marcar la pestaña activa, que con el emoji
                // era imposible.
                setColorFilter(if (item.idx == 0) AppTheme.ACCENT else AppTheme.TXT_SEC)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also {
                    it.gravity = Gravity.CENTER_VERTICAL
                }
            }
            val labelTv = TextView(this).apply {
                text = item.label
                textSize = AppTheme.SP_BODY
                typeface = if (item.idx == 0) AppTheme.bold(context) else AppTheme.medium(context)
                setTextColor(if (item.idx == 0) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.gravity = Gravity.CENTER_VERTICAL
                    it.marginStart = dp(14)
                }
            }
            row.addView(iconTv)
            row.addView(labelTv)
            navContainer.addView(row)
        }

        drawer.addView(navContainer)

        // Drawer footer
        val footerDiv = android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        val footer = TextView(this).apply {
            text = "v2.4 · Wallet Hunter"
            textSize = AppTheme.SP_MICRO
            typeface = AppTheme.body(context)
            setTextColor(AppTheme.TXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        }
        drawer.addView(footerDiv)
        drawer.addView(footer)

        return frame
    }

    // ── DRAWER CONTROLS ──────────────────────────────────────────────────────────
    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        val d = drawerView ?: return
        val o = overlayView ?: return
        drawerOpen = true
        o.visibility = android.view.View.VISIBLE
        d.animate().translationX(0f).setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        o.animate().alpha(1f).setDuration(300).start()
    }

    fun closeDrawer() {
        val d = drawerView ?: return
        val o = overlayView ?: return
        drawerOpen = false
        val w = (resources.displayMetrics.widthPixels * 0.72f)
        d.animate().translationX(-w).setDuration(280)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
        o.animate().alpha(0f).setDuration(280).withEndAction {
            o.visibility = android.view.View.GONE
        }.start()
    }

    private fun updateDrawerSelection(idx: Int) {
        val drawer = drawerView as? LinearLayout ?: return
        // Find navContainer (3rd child: header, divider, navContainer)
        val navContainer = drawer.getChildAt(2) as? LinearLayout ?: return
        for (i in 0 until navContainer.childCount) {
            val row = navContainer.getChildAt(i) as? LinearLayout ?: continue
            val tag = row.tag as? String ?: continue
            val rowIdx = tag.removePrefix("nav_").toIntOrNull() ?: continue
            val isActive = rowIdx == idx
            val bg = row.background as? android.graphics.drawable.GradientDrawable
            bg?.setColor(if (isActive) AppTheme.BG_ELEV else android.graphics.Color.TRANSPARENT)
            val label = row.getChildAt(1) as? TextView
            label?.setTextColor(if (isActive) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
            label?.typeface =
                if (isActive) AppTheme.bold(this) else AppTheme.medium(this)
            // El icono acompaña al rótulo. Con el emoji no se podía: lo pintaba
            // la fuente del sistema con sus propios colores.
            (row.getChildAt(0) as? android.widget.ImageView)
                ?.setColorFilter(if (isActive) AppTheme.ACCENT else AppTheme.TXT_SEC)
        }
    }

    // ── BUILD SCAN TAB ────────────────────────────────────────────────────────
    private fun buildScanTab(): ScrollView {
        val ACCENT  = AppTheme.ACCENT
        val ACCENT2 = AppTheme.BLUE

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppTheme.BG_DEEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(0, 0, 0, dp(80))
        }

        fun side(v: android.view.View, top: Int = 0, bottom: Int = 0) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(top), dp(AppTheme.PAD_SIDE), dp(bottom))
            }
        }

        // ── ESTADO ────────────────────────────────────────────────────────
        //
        // El estado vivía en un punto gris de 8dp en la cabecera, sin texto:
        // había que saberse que el punto significaba algo. Aquí es una frase
        // con el tiempo que lleva corriendo, que es lo primero que quieres
        // saber al abrir la app.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusDot = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(AppTheme.TXT_MUTED)
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) }
        }
        val stateLabel = TextView(this).apply {
            text = "En espera"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
        }
        tvScanState = stateLabel
        scanStateDot = statusDot
        statusRow.addView(statusDot); statusRow.addView(stateLabel)
        page.addView(side(statusRow, top = 6, bottom = 18))

        // ── CIFRA PRINCIPAL ───────────────────────────────────────────────
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        val wpsFigure = TextView(this).apply {
            text = "0,0"
            textSize = AppTheme.SP_HERO
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.display(context)
            letterSpacing = -0.045f
        }
        tvWps = wpsFigure
        // La unidad era el literal fijo "K KEYS / SEG" sobre una cifra que
        // getWps() da en claves por segundo sin escalar: 1 843 200 se leía como
        // 1.8 G/s, mil veces la velocidad real. La escala scaleSpeed().
        val wpsUnit = TextView(this).apply {
            text = "K/s"
            textSize = AppTheme.SP_FIGURE
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        tvSpeedUnitScan = wpsUnit
        speedRow.addView(wpsFigure); speedRow.addView(wpsUnit)
        page.addView(side(speedRow))

        // Pico y media: una sola línea. Antes el pico iba alineado a la derecha
        // y la media al centro, ambos a 9sp.
        val peakLine = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
        }
        tvPeakWps = peakLine
        val avgHidden = TextView(this).apply { visibility = android.view.View.GONE }
        tvAvgWps = avgHidden
        page.addView(side(peakLine, top = 12, bottom = 24))
        page.addView(avgHidden)

        // ── DOS TARJETAS, NO CUATRO ───────────────────────────────────────
        //
        // Eran cuatro: TOTAL KEYS, RITMO, DATASET y TIEMPO, en una rejilla 2x2
        // con cuatro tratamientos de color distintos. De esas cuatro, el ritmo
        // se entiende mucho mejor dicho en una frase (abajo) y el tiempo ya
        // está en la línea de estado. Quedan las dos que son cifras de verdad.
        fun statCard(label: String, accent: Boolean, last: Boolean = false):
                Pair<LinearLayout, TextView> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.cardBg(ctx = this@MainActivity)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (!last) marginEnd = dp(AppTheme.GAP) }
            }
            card.addView(TextView(this).apply {
                text = label
                textSize = AppTheme.SP_MICRO
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.medium(context)
            })
            val value = TextView(this).apply {
                text = "0"
                textSize = 24f
                setTextColor(if (accent) AppTheme.ACCENT else AppTheme.TXT_PRI)
                typeface = AppTheme.title(context)
                letterSpacing = -0.02f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(7) }
            }
            card.addView(value)
            return card to value
        }

        val (cardKeys, vKeys) = statCard("Claves revisadas", accent = false)
        val (cardList, vList) = statCard("Lista cargada", accent = true, last = true)
        tvCount = vKeys
        tvDatasetStat = vList
        vList.text = run {
            val f = if (csvPath.isNotEmpty()) java.io.File(csvPath) else null
            if (f != null && f.exists()) {
                val h = f.length() / 20
                if (h >= 1_000_000) "%.1f M".format(h / 1e6) else "${h / 1000} K"
            } else "—"
        }
        val statRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cardKeys); addView(cardList)
        }
        page.addView(side(statRow, bottom = 14))

        // ── RITMO, EN LENGUAJE LLANO ──────────────────────────────────────
        //
        // Decía "159B/día" bajo un rótulo que ponía "SESIÓN". Ni era de la
        // sesión ni hay forma de leer "159B" sin pararse a pensar.
        val rateCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.cardBg(ctx = this@MainActivity)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        rateCard.addView(Ui.icon(this, R.drawable.ic_play, 20).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
        })
        val rateCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rateCol.addView(TextView(this).apply {
            text = "A este ritmo"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context)
        })
        val tvRate = TextView(this).apply {
            text = "—"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(2), 0, 0)
        }
        tvBinInfoRef = tvRate
        rateCol.addView(tvRate)
        rateCard.addView(rateCol)
        page.addView(side(rateCard, bottom = 22))

        // Vistas que el motor sigue actualizando pero que ya no se enseñan:
        // el tiempo está en la línea de estado y el resto nunca se leía.
        tvTime      = TextView(this).apply { visibility = android.view.View.GONE }
        tvKps       = tvWps
        tvRam       = TextView(this).apply { visibility = android.view.View.GONE }
        tvBattery   = TextView(this).apply { visibility = android.view.View.GONE }
        tvTemp      = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatches   = TextView(this).apply { visibility = android.view.View.GONE }
        tvFooter    = TextView(this).apply { visibility = android.view.View.GONE }
        tvStatus    = TextView(this).apply { visibility = android.view.View.GONE }
        tvAddrFeed  = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatchList = TextView(this).apply { visibility = android.view.View.GONE }
        listOf(tvTime, tvRam, tvBattery, tvFooter, tvStatus).forEach {
            it?.let { v -> page.addView(v) }
        }

        // ── AJUSTES: dos filas en UNA tarjeta ─────────────────────────────
        //
        // Eran dos tarjetas plegables sueltas, cada una ocupando su franja
        // entera. En el diseño son dos filas dentro de una tarjeta, con su
        // valor actual a la derecha: se ve de un vistazo cómo está configurado
        // el motor sin tener que abrir nada.
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(ctx = this@MainActivity)
        }
        fun settingRow(icon: Int, title: String, primero: Boolean,
                       build: LinearLayout.() -> Unit): TextView {
            if (!primero) settingsCard.addView(android.view.View(this).apply {
                setBackgroundColor(AppTheme.BORDER_C)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = dp(18); marginEnd = dp(18) }
            })
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(15), dp(18), dp(15))
                isClickable = true; isFocusable = true
            }
            head.addView(Ui.icon(this, icon, 19).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
            head.addView(TextView(this).apply {
                text = title
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val value = TextView(this).apply {
                text = ""
                textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context)
            }
            head.addView(value)
            val chevron = Ui.icon(this, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            }
            head.addView(chevron)
            settingsCard.addView(head)

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(dp(18), 0, dp(18), dp(18))
            }
            body.build()
            settingsCard.addView(body)
            head.setOnClickListener {
                val abriendo = body.visibility == android.view.View.GONE
                body.visibility = if (abriendo) android.view.View.VISIBLE else android.view.View.GONE
                chevron.animate().rotation(if (abriendo) 90f else 0f).setDuration(140).start()
            }
            return value
        }

        // ── SECTION: Config Hardware ──────────────────────────────────────
        tvEngineSummary = settingRow(R.drawable.ic_gear, "Motor", primero = true) {
            addView(TextView(this@MainActivity).apply {
                text = "Dataset"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.medium(context)
                setPadding(0, dp(4), 0, dp(10))
            })
            val dataRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            btnCsv = Button(this@MainActivity).apply {
                text = "Cargar"
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.medium(context)
                isAllCaps = false
                stateListAnimator = null
                background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
                setPadding(dp(18), 0, dp(18), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
                setOnClickListener { pickCsv() }
            }
            val tvCsvLocal = TextView(this@MainActivity).apply {
                text = if (csvPath.isNotEmpty() && java.io.File(csvPath).exists())
                    java.io.File(csvPath).name else "Sin archivo"
                setTextColor(AppTheme.TXT_SEC)
                textSize = AppTheme.SP_CAPTION
                typeface = Typeface.MONOSPACE   // es un nombre de fichero
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvCsvName = tvCsvLocal
            dataRow.addView(btnCsv); dataRow.addView(tvCsvLocal)
            addView(dataRow)

            // Aquí había una segunda caja con "📦 utxos.bin · N hashes · N MB",
            // repitiendo lo que ya dicen el nombre de fichero de arriba y la
            // tarjeta DATASET. Peor: hacía "tvDatasetStat = tvBinInfo", pisando
            // la referencia a la tarjeta, así que updateUI() escribía el recuento
            // en esta caja y la tarjeta se quedaba con su texto inicial —de ahí
            // el "—" que se veía arriba con el dataset cargado—.

            addView(TextView(this@MainActivity).apply {
                text = "Hilos"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.medium(context)
                setPadding(0, dp(18), 0, dp(4))
            })
            tvThreads = TextView(this@MainActivity).apply {
                setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
                typeface = AppTheme.medium(context)
            }
            addView(tvThreads)
            sbThreads = SeekBar(this@MainActivity).apply {
                max = 7; progress = prefs.getInt("threads", 3)
                setOnSeekBarChangeListener(mkSbl { updateLabels() })
            }
            addView(sbThreads)

            addView(TextView(this@MainActivity).apply {
                text = "Límite de CPU"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.medium(context)
                setPadding(0, dp(18), 0, dp(4))
            })
            tvCpu = TextView(this@MainActivity).apply {
                setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
                typeface = AppTheme.medium(context)
            }
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
                visibility = if (selectedScanMode == 2) android.view.View.GONE else android.view.View.VISIBLE
            }
            fastScanRow = fastRow
            val fastLabels = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            fastLabels.addView(TextView(this@MainActivity).apply {
                text = "Escaneo rápido"
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
            })
            // Este modo baja PBKDF2 de 2048 iteraciones a 1. El contador sube
            // muchísimo, pero las seeds resultantes no son las de ningún
            // mnemónico BIP39: es velocidad sin ninguna posibilidad de acierto.
            val tvFastWarn = TextView(this@MainActivity).apply {
                text = "Sólo para medir velocidad: con una iteración las seeds no son BIP39, así que no puede encontrar nada."
                textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.WARN)
                typeface = AppTheme.body(context)
                visibility = if (prefs.getBoolean("fastMode", false))
                    android.view.View.VISIBLE else android.view.View.GONE
            }
            fastLabels.addView(tvFastWarn)
            fastRow.addView(fastLabels)
            val fastSwitch = android.widget.Switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean("fastMode", false)
                setOnCheckedChangeListener { _, c ->
                    fastModeEnabled = c
                    HunterEngine.setPbkdf2Mode(if (c) 1 else 0)
                    prefs.edit().putBoolean("fastMode", c).apply()
                    tvFastWarn.visibility = if (c) android.view.View.VISIBLE
                                            else android.view.View.GONE
                    if (c) Toast.makeText(this@MainActivity,
                        "Escaneo rápido: sólo mide velocidad, no encuentra carteras",
                        Toast.LENGTH_LONG).show()
                }
            }
            fastModeEnabled = prefs.getBoolean("fastMode", false)
            fastRow.addView(fastSwitch)
            addView(fastRow)

            // Selector de rutas de derivación. Derivar ambas duplica las
            // derivaciones y los hash160 por candidato; PBKDF2 domina, así que
            // el ahorro es del 2-5%, pero si el dataset sólo tiene un tipo de
            // dirección la mitad del trabajo no sirve para nada.
            addView(TextView(this@MainActivity).apply {
                text = "Rutas de derivación"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.medium(context)
                setPadding(0, dp(20), 0, dp(4))
            })
            val pathRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            var pathMask = prefs.getInt("bip39_paths", 3)
            val cb44 = android.widget.CheckBox(this@MainActivity).apply {
                text = "BIP44 (1…)"; textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
                isChecked = (pathMask and 1) != 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val cb84 = android.widget.CheckBox(this@MainActivity).apply {
                text = "BIP84 (bc1q…)"; textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
                isChecked = (pathMask and 2) != 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            fun applyPaths(from: android.widget.CheckBox) {
                var m = (if (cb44.isChecked) 1 else 0) or (if (cb84.isChecked) 2 else 0)
                if (m == 0) {           // no dejar desmarcar las dos
                    from.isChecked = true
                    m = if (from === cb44) 1 else 2
                }
                pathMask = m
                HunterEngine.setBip39Paths(m)
                prefs.edit().putInt("bip39_paths", m).apply()
            }
            cb44.setOnCheckedChangeListener { _, _ -> applyPaths(cb44) }
            cb84.setOnCheckedChangeListener { _, _ -> applyPaths(cb84) }
            pathRow.addView(cb44); pathRow.addView(cb84)
            addView(pathRow)
            try { HunterEngine.setBip39Paths(pathMask) } catch (e: Throwable) {}

            // Actualizar visibilidad del fastRow cuando cambia el modo


            listOf(
                // El icono va a un TextView de 26dp: el texto entero se recortaba
                // a "🐕/Wat". El estado va en la etiqueta, que se reescribe al
                // pulsar en vez de esperar a que se reconstruya la pestaña.
                Triple(R.drawable.ic_clock, watchdogLabel(), { lbl: TextView ->
                    watchdogEnabled = !watchdogEnabled
                    prefs.edit().putBoolean("watchdog", watchdogEnabled).apply()
                    lbl.text = watchdogLabel()
                    android.widget.Toast.makeText(this@MainActivity,
                        if (watchdogEnabled) "Watchdog activado" else "Watchdog desactivado",
                        android.widget.Toast.LENGTH_SHORT).show()
                }),
                Triple(R.drawable.ic_gear, "Configurar según el hardware", { _: TextView -> showHardwareInfo() })
            ).forEach { (ic, lbl, action) ->
                val tvLabel = TextView(this@MainActivity).apply {
                    text = lbl
                    textSize = AppTheme.SP_BODY
                    setTextColor(AppTheme.TXT_PRI)
                    typeface = AppTheme.body(context)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(14), 0, dp(14)); isClickable = true; isFocusable = true
                    setOnClickListener { action(tvLabel) }
                }
                row.addView(Ui.icon(this@MainActivity, ic).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
                })
                row.addView(tvLabel)
                row.addView(Ui.icon(this@MainActivity, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED))
                addView(row)
            }
        }

        // ── SECTION: Red Multi-Dispositivo ────────────────────────────────
        tvClusterSummary = settingRow(R.drawable.ic_network, "Varios dispositivos", primero = false) {
            addView(TextView(this@MainActivity).apply {
                text = "Esta IP: ${NetworkManager.getLocalIp(this@MainActivity)}"
                textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC)
                typeface = Typeface.MONOSPACE   // es una dirección
                setPadding(0, dp(4), 0, dp(14))
            })
            val row1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val netBtn = { txt: String, action: () -> Unit ->
                Button(this@MainActivity).apply {
                    text = txt
                    textSize = AppTheme.SP_BODY
                    setTextColor(AppTheme.TXT_PRI)
                    typeface = AppTheme.medium(context)
                    isAllCaps = false
                    stateListAnimator = null
                    background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
                    setOnClickListener { action() }
                }
            }
            row1.addView(netBtn("Ser maestro") { startClusterMaster() })
            row1.addView(netBtn("Buscar maestro") {
                NetworkManager.discoverMasters(this@MainActivity) { ip, _ ->
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Maestro encontrado: $ip", android.widget.Toast.LENGTH_SHORT).show() }
                }
            })
            addView(row1)
            val row2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row2.addView(netBtn("Ser trabajador") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            row2.addView(netBtn("Conectar") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            addView(row2)
            val tvNetLog = TextView(this@MainActivity).apply {
                text = ""
                textSize = AppTheme.SP_MICRO
                setTextColor(AppTheme.TXT_SEC)
                typeface = Typeface.MONOSPACE   // es un registro
                background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_DEEP, context)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(84)
                ).apply { topMargin = dp(12) }
            }
            NetworkManager.onLog = { msg ->
                runOnUiThread {
                    val cur = tvNetLog.text.toString().lines().takeLast(5)
                    tvNetLog.text = (cur + listOf(msg)).joinToString("\n")
                }
            }
            addView(tvNetLog)
        }



        // La tarjeta de ajustes va aquí, entre el ritmo y el modo.
        page.addView(side(settingsCard, bottom = 22))

        // ── MODO DE ESCANEO ───────────────────────────────────────────────
        //
        // Eran dos tarjetas con borde y tres colores de acento repartidos entre
        // ellas. Y tenía los listeners DUPLICADOS: se asignaban en el bucle que
        // construye las pastillas y otra vez en un segundo bucle justo después,
        // que pisaba al primero, así que el código de arriba no se ejecutaba.
        page.addView(side(Ui.sectionLabel(this, "Modo"), bottom = 0))

        val scanModeValues = listOf(0, 2)
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeCards = mutableListOf<LinearLayout>()

        fun paintModes(sel: Int) {
            modeCards.forEachIndexed { i, c ->
                val on = i == sel
                c.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (on) AppTheme.BG_ELEV else AppTheme.BG_CARD)
                    cornerRadius = dp(AppTheme.R_INNER).toFloat()
                    if (on) setStroke(dp(2), AppTheme.ACCENT)
                }
                (c.getChildAt(0) as TextView).apply {
                    setTextColor(if (on) AppTheme.ACCENT else AppTheme.TXT_SEC)
                    typeface = if (on) AppTheme.bold(context) else AppTheme.medium(context)
                }
                (c.getChildAt(1) as TextView).setTextColor(
                    if (on) AppTheme.ACCENT else AppTheme.TXT_SEC)
            }
        }
        listOf("BIP39" to "Frases semilla", "Clave directa" to "10× más rápido")
            .forEachIndexed { i, (name, sub2) ->
                val c = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    isClickable = true; isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i == 0) marginEnd = dp(10) }
                }
                c.addView(TextView(this).apply { text = name; textSize = AppTheme.SP_BODY })
                c.addView(TextView(this).apply {
                    text = sub2
                    textSize = AppTheme.SP_MICRO
                    typeface = AppTheme.body(context)
                    setPadding(0, dp(3), 0, 0)
                })
                c.setOnClickListener {
                    selectedScanMode = scanModeValues[i]
                    paintModes(i)
                    // El escaneo rápido sólo aplica a BIP39: en clave directa no
                    // hay derivación que saltarse.
                    try {
                        fastScanRow?.visibility =
                            if (selectedScanMode == 2) android.view.View.GONE
                            else android.view.View.VISIBLE
                    } catch (e: Exception) {}
                }
                modeCards.add(c); modeRow.addView(c)
            }
        paintModes(scanModeValues.indexOf(selectedScanMode).coerceAtLeast(0))
        page.addView(side(modeRow, top = 10, bottom = 18))

        // ── INICIAR / DETENER ─────────────────────────────────────────────
        //
        // Era un rectángulo de #1A1A1A con un filo blanco: exactamente el mismo
        // peso visual que las tarjetas que tiene encima. El único botón que
        // pone la app en marcha tiene que ser lo más sólido de la pantalla.
        val startBg = GradientDrawable().apply {
            setColor(AppTheme.ACCENT)
            cornerRadius = dp(AppTheme.R_CARD).toFloat()
        }
        // Parar no es la acción principal, es la destructiva: fondo tenue y
        // texto rojo. Antes era un rectángulo rojo entero, que pide que lo
        // pulses.
        val stopRed = GradientDrawable().apply {
            setColor(AppTheme.BG_STOP)
            cornerRadius = dp(AppTheme.R_CARD).toFloat()
        }

        btnToggle = Button(this).apply {
            text = s.start
            textSize = AppTheme.SP_BODY + 1f
            setTextColor(AppTheme.BG_DEEP)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null   // sin la sombra de Material sobre el plano
            background = startBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(18), dp(AppTheme.PAD_SIDE), dp(8))
            }
            setOnClickListener {
                puzzleMode = false
                HunterEngine.setMode(selectedScanMode)
                doToggle(btnToggle)
            }
        }
        btnToggle?.tag = arrayOf(startBg, stopRed)
        page.addView(btnToggle)

        scroll.addView(page)
        return scroll
    }

    // ── BUILD PUZZLE TAB ──────────────────────────────────────────────────────
    private fun buildPuzzleTab(): ScrollView {
        val ACCENT  = AppTheme.ACCENT
        val ACCENT2 = AppTheme.BLUE

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppTheme.BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            // El margen lateral lo ponen ahora las tarjetas, no la página: así
            // una tarjeta mide lo mismo aquí que en la pestaña de escaneo.
            setPadding(0, dp(8), 0, dp(80))
        }

        // ── HELPERS ───────────────────────────────────────────────────────
        fun pCard(marginTop: Int = AppTheme.GAP): LinearLayout = Ui.card(this, marginTop)

        fun sectionLabel(text: String) = Ui.sectionLabel(this, text)

        fun styledInput(hint: String, color: Int = AppTheme.TXT_PRI): EditText =
            Ui.input(this, hint, mono = true).apply {
                setTextColor(color)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }

        fun collapsibleSection(icon: Int, title: String, build: LinearLayout.() -> Unit) =
            Ui.section(this, icon, title, build)

        // ── CABECERA ──────────────────────────────────────────────────────
        page.addView(TextView(this).apply {
            text = "Puzzle"
            textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.01f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(8), dp(AppTheme.PAD_SIDE), dp(16))
            }
        })
        // "Selecciona el puzzle objetivo" explicaba a quien ya está mirando la
        // lista de puzzles lo que hace la lista de puzzles.

        // ── PUZZLE CHIP SELECTOR ──────────────────────────────────────────
        val hiddenPuzzles = getSharedPreferences("hidden_puzzles", MODE_PRIVATE)
        // Se descartan las entradas cuya dirección NO es una dirección de
        // Bitcoin. Buscar una clave cuyo hash160 dé una dirección con el
        // checksum roto es buscar algo que no puede existir: el contador
        // subiría igual, para siempre, sin ninguna posibilidad.
        val invalidas = puzzles.filter {
            BtcAddress.validate(it.addr, false) !is BtcAddress.Result.Valid
        }
        if (invalidas.isNotEmpty())
            android.util.Log.e("MainActivity",
                "Tabla de puzzles: ${invalidas.size} direcciones inválidas: " +
                invalidas.joinToString { "#${it.num}" })
        val visiblePuzzles = puzzles.filter { p ->
            BtcAddress.validate(p.addr, false) is BtcAddress.Result.Valid &&
            !hiddenPuzzles.getBoolean("hidden_${p.num}", false)
        }.toMutableList()

        // Track selected puzzle
        var selectedPuzzleIdx = 0

        // Container for chip rows
        val chipSection = pCard(0)
        chipSection.addView(sectionLabel("Seleccionar puzzle"))

        // Horizontal scroll for group chips
        val groupScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val groupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        groupScroll.addView(groupRow)
        chipSection.addView(groupScroll)

        // Individual chips container (shown below group)
        val indivScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val indivRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        indivScroll.addView(indivRow)
        chipSection.addView(indivScroll)
        page.addView(chipSection)

        // ── STATUS ────────────────────────────────────────────────────────
        // Era texto verde a 11sp en monoespaciada dentro de un recuadro
        // translúcido con filo verde. El acento estaba diciendo "hay un puzzle
        // seleccionado", que no es ni una acción, ni un saldo, ni que algo esté
        // corriendo: es sólo información.
        tvPuzzleStatus = TextView(this).apply {
            text = "Selecciona un puzzle"
            textSize = AppTheme.SP_BODY
            typeface = AppTheme.medium(context)
            setTextColor(AppTheme.TXT_SEC)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(AppTheme.GAP), dp(AppTheme.PAD_SIDE), 0)
            }
        }
        page.addView(tvPuzzleStatus)

        // ── PROGRESO VISUAL ───────────────────────────────────────────────
        val progressCard = pCard()
        val progressHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val tvProgressPct = TextView(this).apply {
            text = "0,00 %"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
        }
        progressHeader.addView(TextView(this).apply {
            text = "Cobertura del rango"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        progressHeader.addView(tvProgressPct)
        progressCard.addView(progressHeader)

        val progressTrack = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { bottomMargin = dp(8) }
        }
        val progressBarPuzzle = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 10000; progress = 0
            // Se asignaba un GradientDrawable pelado como progressDrawable, y
            // ProgressBar lo pintaba entero sin recortarlo: la barra se veía
            // siempre llena independientemente del valor. El drawable de
            // progreso tiene que ir envuelto en ClipDrawable dentro de un
            // LayerDrawable con los ids que ProgressBar espera.
            val fill = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ACCENT2, ACCENT)
            ).apply { cornerRadius = dp(4).toFloat() }
            val track = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(4).toFloat()
            }
            progressDrawable = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    track,
                    android.graphics.drawable.ClipDrawable(
                        fill, Gravity.START,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL)
                )
            ).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progressTrack.addView(progressBarPuzzle)
        progressCard.addView(progressTrack)

        val tvProgressDetail = TextView(this).apply {
            text = "Bloques: —"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(10), 0, 0)
        }
        progressCard.addView(tvProgressDetail)

        // Salto aleatorio dentro del rango del puzzle.
        tvRandomJump = TextView(this).apply {
            text = "Saltar a un punto aleatorio del rango"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.medium(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minHeight = dp(48)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            setOnClickListener { pickRandomJump() }
        }
        progressCard.addView(tvRandomJump)

        // En qué bloque se está (o se va a empezar) y dónde cae en el rango.
        // Esto estaba en tvPuzzleStatus, pero updateUI() reescribe esa línea con
        // el ETA cada 800 ms, así que la posición se borraba antes de poder
        // leerla. Aquí no la pisa nadie.
        tvCurrentBlock = TextView(this).apply {
            text = "Bloque actual: —"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = Typeface.MONOSPACE   // lleva el índice en hex
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        progressCard.addView(tvCurrentBlock)

        progressCard.addView(TextView(this).apply {
            // 9sp en gris #555 sobre fondo casi negro es ilegible y demasiado
            // pequeño para acertar con el dedo, siendo además destructivo.
            text = "Reiniciar progreso"; textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.RED)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            typeface = AppTheme.medium(context)
            minHeight = dp(48)
            gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                // Con el scan en marcha esto era null y el botón salía por el
                // return sin decir nada: pulsar "Reiniciar progreso" no hacía
                // absolutamente nada mientras estabas buscando.
                val puzzleNum = currentPuzzleNum()
                if (puzzleNum == 0) return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reiniciar progreso")
                    .setMessage("¿Borrar el progreso del puzzle #$puzzleNum?")
                    .setPositiveButton("Reiniciar") { _, _ ->
                        getBlockPrefs().edit().remove("scanned_$puzzleNum").apply()
                        progressBarPuzzle.progress = 0
                        tvProgressPct.text = "0.00%"
                        tvProgressDetail.text = "Bloques: 0 / —"
                        android.widget.Toast.makeText(this@MainActivity, "Progreso reiniciado", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        })
        page.addView(progressCard)

        /* Estaba definida aquí dentro y no se llamaba desde ningún sitio, así
           que la barra y el detalle se quedaban en sus valores iniciales
           ("0.00%" y "Bloques: —"). Se expone como campo para poder
           dispararla al seleccionar puzzle y desde updateUI. */
        puzzleProgressUpdater = { puzzleNum: Int, rangeStart: String, rangeEnd: String ->
            Thread {
                try {
                    // Mismo truncado que en getBlockProgressText: con BigInteger
                    // el total es correcto para cualquier puzzle.
                    val total = totalBlocksOf(rangeStart, rangeEnd)
                    val scanned = getBlockPrefs().getStringSet("scanned_$puzzleNum", emptySet())?.size ?: 0
                    val pctD = blockPercent(java.math.BigInteger.valueOf(scanned.toLong()), total)
                    val pct = (pctD * 100.0).toInt().coerceIn(0, 10000)
                    val pctStr = "%.4f%%".format(pctD)
                    runOnUiThread {
                        progressBarPuzzle.progress = pct
                        tvProgressPct.text = pctStr
                        tvProgressDetail.text = "Bloques: $scanned / $total"
                    }
                } catch (e: Exception) {}
            }.start()
            Unit
        }

        // El botón "Ver QR de dirección" mostraba un QR de la dirección del
        // puzzle. Nada lo escanea: la dirección es pública y conocida, y no hay
        // ningún flujo que la reciba por cámara. Retirado.

        // Balance indicator - debajo del puzzle seleccionado
        val tvBalResult = TextView(this).apply {
            text = "Verificando saldo…"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(8), dp(AppTheme.PAD_SIDE), 0)
            }
        }
        page.addView(tvBalResult)

        // ── ¿TIENE ATAJO ESTE PUZZLE? ─────────────────────────────────────
        //
        // Es la información que decide si merece la pena dejar el móvil
        // corriendo. Una dirección que nunca ha gastado no ha revelado su clave
        // pública, y sin clave pública lo único que queda es probar claves de
        // una en una: para el rango del #70 son 2^69, millones de años. Si la
        // clave está publicada, sirve Pollard's Kangaroo, que es O(raiz(n)):
        // el mismo rango baja a unas 2^35 operaciones.
        //
        // Kangaroo no está implementado todavía. Esto dice si sería posible,
        // que es lo que hay que saber ANTES de dedicarle el móvil a algo.
        tvPuzzleAtajo = TextView(this).apply {
            text = "Comprobando si la clave pública está publicada…"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.4f)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(8), dp(AppTheme.PAD_SIDE), 0)
            }
        }
        page.addView(tvPuzzleAtajo)

        btnKangaroo = Button(this).apply {
            text = "Buscar con Kangaroo"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.BG_DEEP)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.ACCENT, this@MainActivity)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(8), dp(AppTheme.PAD_SIDE), 0)
            }
            setOnClickListener { alternarKangaroo() }
        }
        page.addView(btnKangaroo)

        // ── RANGE CONFIG ──────────────────────────────────────────────────
        page.addView(collapsibleSection(R.drawable.ic_target, "Rango hexadecimal") {
            val rangeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            }
            val colStart = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            }
            colStart.addView(TextView(this@MainActivity).apply { text = "Desde"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
            etRangeStart = styledInput("0x...")
            colStart.addView(etRangeStart)
            val colEnd = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            colEnd.addView(TextView(this@MainActivity).apply { text = "Hasta"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
            etRangeEnd = styledInput("0x...")
            colEnd.addView(etRangeEnd)
            rangeRow.addView(colStart); rangeRow.addView(colEnd)
            addView(rangeRow)
            addView(TextView(this@MainActivity).apply { text = "Dirección objetivo"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
            etTarget = styledInput("1A2B3C…")
            addView(etTarget)
        })

        // ── CHECKPOINT ────────────────────────────────────────────────────
        tvCheckpointLive = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            // Sin checkpoint el texto queda vacío, pero el fondo, el borde y el
            // padding seguían dibujándose: un rectángulo hueco de ~40dp bajo
            // "Rango Hex". Se oculta mientras no tenga contenido.
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(AppTheme.GAP), dp(AppTheme.PAD_SIDE), 0)
            }
        }
        page.addView(tvCheckpointLive)

        // ── STATS ─────────────────────────────────────────────────────────
        val statsCard = pCard()
        statsCard.addView(sectionLabel("Rendimiento"))
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        // Misma corrección que en el escáner: la unidad iba apilada en dos
        // líneas ("Keys" sobre "por seg") a un lado de la cifra, y el pico
        // flotaba abajo a la derecha a 9sp. Ahora la unidad va junto a la
        // cifra y el pico baja a su propia línea.
        val tvWpsP = TextView(this).apply {
            text = "0"; textSize = AppTheme.SP_DISPLAY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.display(context)
            letterSpacing = -0.04f
        }
        tvWpsPuzzle = tvWpsP
        speedRow.addView(tvWpsP)
        // getWps() devuelve claves/s directas; la etiqueta decía "kKeys", lo que
        // multiplicaba por mil la lectura. La unidad la fija el escalado.
        tvSpeedUnitPuzzle = TextView(this).apply {
            text = "Keys/s"; textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        speedRow.addView(tvSpeedUnitPuzzle)
        statsCard.addView(speedRow)

        tvPeakWpsPuzzle = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        statsCard.addView(tvPeakWpsPuzzle)

        fun miniStat(label: String, tv: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.medium(context)
            })
            addView(tv)
        }

        // Eran 16sp blanco, 16sp blanco, 13sp azul y 13sp blanco: cuatro datos
        // del mismo rango con tres tratamientos. Uno solo.
        fun miniValue(initial: String) = TextView(this).apply {
            text = initial; textSize = AppTheme.SP_FIGURE
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.02f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val tvCntP = miniValue("0")
        val tvTmP  = miniValue("00:00:00")
        tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
        tvPctPuzzle = miniValue("—")
        tvBlockProgress = miniValue("—")

        val miniRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val miniRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        miniRow1.addView(miniStat("Escaneadas", tvCntP).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        miniRow1.addView(miniStat("Tiempo", tvTmP))
        val pctLocal = tvPctPuzzle!!
        val blkLocal = tvBlockProgress!!
        miniRow2.addView(miniStat("Progreso", pctLocal).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        miniRow2.addView(miniStat("Bloques restantes", blkLocal))
        statsCard.addView(miniRow1); statsCard.addView(miniRow2)
        page.addView(statsCard)

        // ── POTENCIA: LOW / MEDIUM / HIGH ─────────────────────────────────
        val powerCard = pCard()
        powerCard.addView(sectionLabel("Potencia"))

        val powerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Hidden sliders (kept for logic compatibility)
        tvThreadsPuzzle = TextView(this).apply { visibility = android.view.View.GONE }
        tvCpuPuzzle     = TextView(this).apply { visibility = android.view.View.GONE }
        sbThreadsPuzzle = SeekBar(this).apply {
            visibility = android.view.View.GONE; max = 7
            progress = prefs.getInt("puzzle_threads", 3)
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        sbCpuPuzzle = SeekBar(this).apply {
            visibility = android.view.View.GONE; max = 90
            progress = prefs.getInt("puzzle_cpu", 70)
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        powerCard.addView(tvThreadsPuzzle); powerCard.addView(sbThreadsPuzzle)
        powerCard.addView(tvCpuPuzzle); powerCard.addView(sbCpuPuzzle)

        data class PowerLevel(val label: String, val threads: Int, val cpu: Int)
        val levels = listOf(
            PowerLevel("Baja",  1, 30),
            PowerLevel("Media", 3, 60),
            PowerLevel("Alta",  7, 90)
        )

        // Eran tres TextViews con su borde, repintados a mano en un
        // forEachIndexed: el mismo patrón que el selector de modo del escáner y
        // el de aleatorio/secuencial de aquí abajo, escrito tres veces.
        powerRow.addView(Ui.segmented(
            this, levels.map { it.label to null }, initial = 1
        ) { idx ->
            val level = levels[idx]
            sbThreadsPuzzle?.progress = level.threads - 1
            sbCpuPuzzle?.progress = level.cpu - 10
            prefs.edit()
                .putInt("puzzle_threads", level.threads - 1)
                .putInt("puzzle_cpu", level.cpu - 10)
                .apply()
            updatePuzzleLabels()
        })
        sbThreadsPuzzle?.progress = 3
        sbCpuPuzzle?.progress = 50
        powerCard.addView(powerRow)

        // ── RECORRIDO DEL RANGO ───────────────────────────────────────────
        powerCard.addView(Ui.sectionLabel(this, "Recorrido del rango", topGap = 18))
        powerCard.addView(Ui.segmented(
            this, listOf("Aleatorio" to null, "Secuencial" to null), initial = 0
        ) { idx -> HunterEngine.setSequential(idx == 1) })

        // ── BATCH SIZE SLIDER ─────────────────────────────────────────────
        // JAC_BATCH en jac_batch.h permite hasta 16000 y el worker ya acota a
        // ese máximo; el slider se quedaba en 4096, la cuarta parte. Lotes
        // mayores amortizan mejor la única inversión modular por lote.
        val batchLabels = listOf(64, 128, 256, 512, 1024, 2048, 4096, 8192, 16000)

        val batchHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        batchHeaderRow.addView(TextView(this).apply {
            text = "Tamaño de lote"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // El valor iba en azul: un número de ajuste no es información de otro
        // tipo que el resto, sólo es el valor de la fila.
        val tvBatchVal = TextView(this).apply {
            text = "256 claves"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
        }
        batchHeaderRow.addView(tvBatchVal)
        powerCard.addView(batchHeaderRow)

        val sbBatch = SeekBar(this).apply {
            max = batchLabels.size - 1
            progress = 2 // default 1000
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    val size = batchLabels[p]
                    tvBatchVal.text = "$size claves"
                    HunterEngine.setBatchSize(size)
                    prefs.edit().putInt("puzzle_batch", p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        // Restaurar valor guardado
        sbBatch.progress = prefs.getInt("puzzle_batch", 2)
        HunterEngine.setBatchSize(batchLabels[sbBatch.progress])
        powerCard.addView(sbBatch)

        // Labels del slider
        val batchLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        listOf("64", "", "256", "", "1K", "", "4K", "", "16K").forEach { lbl ->
            batchLabelRow.addView(TextView(this).apply {
                // 8sp: por debajo del mínimo legible de Android, que son 12.
                text = lbl; textSize = AppTheme.SP_MICRO
                setTextColor(AppTheme.TXT_MUTED)
                typeface = AppTheme.body(context)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        powerCard.addView(batchLabelRow)

        page.addView(powerCard)
        updatePuzzleLabels()

        // ── HERRAMIENTAS ──────────────────────────────────────────────────
        page.addView(collapsibleSection(R.drawable.ic_gear, "Herramientas") {
            listOf(
                Triple(R.drawable.ic_search, "Buscar claves públicas",      { auditarClavesPublicas() }),
                Triple(R.drawable.ic_gear,   "Configurar según el hardware", { showHardwareInfo() }),
                Triple(R.drawable.ic_export, "Exportar progreso",            { exportPuzzleProgress() }),
                Triple(R.drawable.ic_import, "Importar progreso",            { importPuzzleProgress() })
            ).forEach { (icon, label, action) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(14), 0, dp(14)); isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }
                row.addView(Ui.icon(this@MainActivity, icon).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
                })
                row.addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = AppTheme.SP_BODY
                    setTextColor(AppTheme.TXT_PRI)
                    typeface = AppTheme.body(context)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Ui.icon(this@MainActivity, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED))
                addView(row)
            }
        })

        // ── THERMAL & BALANCE ─────────────────────────────────────────────
        val tvThermalPuzzle = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            visibility = android.view.View.GONE
        }
        tvThermal = tvThermalPuzzle
        page.addView(tvThermalPuzzle)

        // ── START BUTTON ──────────────────────────────────────────────────
        val startBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_KEY).toFloat()
        }
        val stopRed = android.graphics.drawable.GradientDrawable().apply {
            setColor(AppTheme.RED); cornerRadius = dp(AppTheme.R_KEY).toFloat()
        }
        btnPuzzleToggle = Button(this).apply {
            text = "Iniciar puzzle"
            textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.BG_DEEP)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = startBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(20), dp(AppTheme.PAD_SIDE), dp(8))
            }
            setOnClickListener {
                try {
                    puzzleMode = true
                    HunterEngine.setMode(1)
                    doToggle(btnPuzzleToggle)
                } catch (e: Exception) {
                    val msg = "${e.javaClass.simpleName}: ${e.message}"
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                    java.io.File(filesDir, "crash_log.txt").appendText("\nPUZZLE_BTN: $msg\n${e.stackTraceToString()}\n")
                }
            }
        }
        btnPuzzleToggle?.tag = arrayOf(startBg, stopRed)
        page.addView(btnPuzzleToggle)

        // ── BUILD CHIP GROUPS ─────────────────────────────────────────────
        // Group puzzles by ranges of 10
        val groupSize = 10
        val groups = visiblePuzzles.chunked(groupSize)
        var activeGroupIdx = 0
        val groupChips = mutableListOf<TextView>()

        fun applyPuzzleAndCheckBalance(p: PuzzleInfo, chipView: TextView? = null) {
            applyPuzzle(p)
            val puzzlePrefs2 = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
            val savedKey = puzzlePrefs2.getString("last_key_${p.num}", null)
            val savedTime = puzzlePrefs2.getLong("last_time_${p.num}", 0)
            if (savedKey != null && savedTime > 0) {
                val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTime))
                tvCheckpointLive?.text = "Checkpoint #${p.num} · $ts · ${savedKey.take(12)}…${savedKey.takeLast(6)}"
                tvCheckpointLive?.visibility = android.view.View.VISIBLE
            } else {
                tvCheckpointLive?.text = ""
                tvCheckpointLive?.visibility = android.view.View.GONE
            }
            puzzleSeleccionado = p.num
            tvBalResult.text = "Consultando el saldo de #${p.num}…"
            comprobarAtajo(p)
            checkPuzzleBalance(p.addr) { bal ->
                runOnUiThread {
                    when {
                        bal > 0L -> {
                            if (puzzleSeleccionado != p.num) return@runOnUiThread
                            tvBalResult.text = "${bal / 100_000_000.0} BTC disponibles"
                            tvBalResult.setTextColor(AppTheme.ACCENT)
                        }
                        bal == 0L -> {
                            hiddenPuzzles.edit().putBoolean("hidden_${p.num}", true).apply()
                            tvBalResult.text = "Sin fondos — #${p.num} ocultado"
                            tvBalResult.setTextColor(AppTheme.WARN)
                            // Ocultar chip visualmente
                            chipView?.visibility = android.view.View.GONE
                            // Seleccionar el siguiente chip visible
                            var nextSelected = false
                            for (k in 0 until indivRow.childCount) {
                                val c = indivRow.getChildAt(k) as? TextView ?: continue
                                if (c.visibility == android.view.View.VISIBLE && c != chipView) {
                                    c.performClick()
                                    nextSelected = true
                                    break
                                }
                            }
                            if (!nextSelected) {
                                tvPuzzleStatus?.text = "No hay puzzles disponibles en este grupo"
                            }
                        }
                        else -> {
                            tvBalResult.text = "Sin conexión — reintenta"
                            tvBalResult.setTextColor(AppTheme.TXT_SEC)
                        }
                    }
                }
            }
        }

        fun buildIndivChips(groupIdx: Int) {
            indivRow.removeAllViews()
            val group = groups.getOrNull(groupIdx) ?: return
            group.forEachIndexed { i, p ->
                val chip = TextView(this).apply {
                    text = "#${p.num}"
                    textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
                    typeface = if (i == 0) AppTheme.bold(context) else AppTheme.medium(context)
                    background = Ui.cardBg(AppTheme.R_CHIP,
                        if (i == 0) AppTheme.TXT_PRI else AppTheme.BG_CARD, context)
                    setTextColor(if (i == 0) AppTheme.BG_DEEP else AppTheme.TXT_SEC)
                    layoutParams = LinearLayout.LayoutParams(dp(68), dp(44)).apply { marginEnd = dp(8) }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        for (j in 0 until indivRow.childCount) {
                            val c = indivRow.getChildAt(j) as? TextView ?: continue
                            c.background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_CARD, context)
                            c.setTextColor(AppTheme.TXT_SEC)
                            c.typeface = AppTheme.medium(context)
                        }
                        background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.TXT_PRI, context)
                        setTextColor(AppTheme.BG_DEEP)
                        typeface = AppTheme.bold(context)
                        applyPuzzleAndCheckBalance(p, this)
                    }
                }
                indivRow.addView(chip)
            }
            // Auto-select first
            groups.getOrNull(groupIdx)?.firstOrNull()?.let { applyPuzzleAndCheckBalance(it) }
        }

        fun buildGroupChips() {
            groupRow.removeAllViews()
            groupChips.clear()
            groups.forEachIndexed { idx, group ->
                val first = group.first().num
                val last  = group.last().num
                val chip = TextView(this).apply {
                    text = "$first–$last"
                    textSize = AppTheme.SP_CAPTION; gravity = Gravity.CENTER
                    typeface = if (idx == 0) AppTheme.bold(context) else AppTheme.medium(context)
                    background = Ui.cardBg(AppTheme.R_CHIP,
                        if (idx == 0) AppTheme.BG_ELEV else AppTheme.BG_CARD, context)
                    setTextColor(if (idx == 0) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
                    ).apply { marginEnd = dp(8) }
                    setPadding(dp(14), 0, dp(14), 0)
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        activeGroupIdx = idx
                        groupChips.forEachIndexed { i, c ->
                            val active = i == idx
                            c.background = Ui.cardBg(AppTheme.R_CHIP,
                                if (active) AppTheme.BG_ELEV else AppTheme.BG_CARD, context)
                            c.setTextColor(if (active) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
                            c.typeface = if (active) AppTheme.bold(context) else AppTheme.medium(context)
                        }
                        buildIndivChips(idx)
                    }
                }
                groupChips.add(chip)
                groupRow.addView(chip)
            }
        }

        buildGroupChips()
        buildIndivChips(0)

        // Default puzzle setup
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % visiblePuzzles.size
        applyPuzzle(visiblePuzzles.getOrElse(defaultIdx) { visiblePuzzles.first() })

        // Load checkpoint for default
        val defaultPuzzle = visiblePuzzles.getOrElse(defaultIdx) { visiblePuzzles.first() }
        val puzzlePrefsInit = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
        val savedKeyInit = puzzlePrefsInit.getString("last_key_${defaultPuzzle.num}", null)
        val savedTimeInit = puzzlePrefsInit.getLong("last_time_${defaultPuzzle.num}", 0)
        if (savedKeyInit != null && savedTimeInit > 0) {
            val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTimeInit))
            tvCheckpointLive?.text = "Checkpoint #${defaultPuzzle.num} · $ts · ${savedKeyInit.take(12)}…${savedKeyInit.takeLast(6)}"
            tvCheckpointLive?.visibility = android.view.View.VISIBLE
        }

        Thread {
            checkPuzzleBalance(defaultPuzzle.addr) { bal ->
                runOnUiThread {
                    // Consultar el saldo tarda, y en ese rato el usuario ya suele
                    // haber tocado otro chip. Escribir aquí sin comprobarlo pisaba
                    // la etiqueta del puzzle que sí había elegido.
                    // Dos consultas escriben este mismo TextView: la del chip que
                    // pulsas y ésta, la del puzzle por defecto. Sin una marca
                    // de a quién pertenece la respuesta, gana la que termine
                    // última y acabas viendo el saldo de OTRO puzzle bajo el
                    // que tienes seleccionado. Comparar el rango no basta: no
                    // cambia hasta que applyPuzzle() lo escribe.
                    if (puzzleSeleccionado != defaultPuzzle.num) return@runOnUiThread
                    if (puzzleFullStart != defaultPuzzle.start) return@runOnUiThread
                    if (bal > 0) {
                        tvBalResult.text = "${bal / 100_000_000.0} BTC disponibles"
                        tvBalResult.setTextColor(ACCENT)
                    } else {
                        // Antes ponía "Buscando puzzle con fondos..." y llamaba a
                        // autoSelectPuzzle(), que no busca nada: la autoselección
                        // está desactivada y lo único que hacía era dejar el
                        // estado en "Selecciona un puzzle" con uno ya elegido.
                        tvBalResult.text = "Sin fondos confirmados en #${defaultPuzzle.num}"
                        tvBalResult.setTextColor(AppTheme.WARN)
                    }
                }
            }
        }.start()

        scroll.addView(page)
        puzzleTabReady = true
        return scroll
    }

    private fun buildWalletTab(): ScrollView {
        val ACCENT  = AppTheme.ACCENT
        val ACCENT2 = AppTheme.BLUE

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppTheme.BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), dp(16), dp(AppTheme.PAD_SIDE), dp(80))
        }

        // ── SALDO ─────────────────────────────────────────────────────────
        //
        // Estaba metido en una tarjeta con borde y centrado. La tarjeta no
        // separaba nada de nada —era lo único en su zona— y el centrado
        // rompía la columna de lectura con todo lo de abajo alineado a la
        // izquierda. Aquí la cifra va suelta sobre el fondo, como en el resto
        // del sistema.
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(26) }
        }

        heroCard.addView(TextView(this).apply {
            text = "Saldo en hallazgos"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
        // El verde estaba fijo, así que un saldo de cero se pintaba igual que
        // uno con fondos. Ahora el acento significa "hay algo"; lo pone
        // refreshWallet según el total.
        val tvTotalBtc = TextView(this).apply {
            text = "0,00000000"
            textSize = AppTheme.SP_DISPLAY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.display(context)
            letterSpacing = -0.04f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvTotalUsd = TextView(this).apply {
            text = "Aún no ha encontrado ninguna clave"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        heroCard.addView(tvTotalBtc)
        heroCard.addView(tvTotalUsd)
        page.addView(heroCard)

        // Leer los hallazgos del baúl y calcular total.
        //
        // Antes esto leía coincidencias.txt filtrando por líneas que empezaran
        // con "MATCH|", pero save_match() nunca escribe ese prefijo en el
        // fichero —lo usa sólo para la lista en memoria—: las líneas empiezan
        // por SEED:, PRIV: o RAW:. Así que el total salía siempre en 0 aunque
        // hubiera aciertos guardados.
        // Corre siempre en segundo plano (refreshWallet la llama desde un Thread).
        //
        // consultarRed sólo va a true cuando el usuario pulsa "Actualizar
        // Balance". Al construir la pestaña iba a true sin más, así que abrir
        // Wallet mandaba todas las direcciones encontradas a mempool.space sin
        // que nadie lo hubiera pedido: preguntar por una dirección se la revela
        // a quien responde, y eso delata que este dispositivo tiene la clave.
        fun loadCoincidencias(consultarRed: Boolean): Pair<Double, List<Triple<String,Double,String>>> {
            MatchVault.ingestPlaintextFile(this@MainActivity)
            if (consultarRed) {
                try { MatchVault.resolvePendingBalances(this@MainActivity) } catch (e: Exception) {}
            }
            val entries = MatchVault.list(this@MainActivity)
            return Pair(entries.sumOf { it.btc },
                        entries.map { Triple(it.addr, it.btc, it.wif) })
        }

        fun refreshWallet(consultarRed: Boolean = false) {
            Thread {
                val (total, matches) = loadCoincidencias(consultarRed)
                val pendientes = MatchVault.pendingBalance(this@MainActivity)
                // Si no queda ninguno por consultar, es que la consulta llegó.
                // Si quedan Y se había pedido red, es que no hubo respuesta:
                // decirlo es la diferencia entre "está vacío" y "no lo sé".
                val sinRed = consultarRed && pendientes > 0
                runOnUiThread {
                    tvTotalBtc.text = "%.8f".format(total).replace('.', ',')
                    // El acento sólo cuando de verdad hay saldo. Pintar de verde
                    // un cero es lo mismo que no pintar nada.
                    tvTotalBtc.setTextColor(
                        if (total > 0.0) AppTheme.ACCENT else AppTheme.TXT_PRI)
                    tvTotalUsd.text = when {
                        matches.isEmpty()  -> "Aún no ha encontrado ninguna clave"
                        sinRed             -> "${matches.size} hallazgo(s) · sin conexión, " +
                                              "$pendientes sin consultar"
                        // Un total que suma ceros sin consultar no es un saldo:
                        // decir "0,00000000" a secas afirma que están vacías.
                        pendientes > 0     -> "${matches.size} hallazgo(s) · $pendientes sin consultar"
                        else               -> "${matches.size} hallazgo(s)"
                    }
                }
            }.start()
        }
        // Consulta automática al abrir la pestaña.
        //
        // Ojo con lo que implica, porque antes era justo al revés a propósito:
        // preguntar por un saldo revela esa dirección al servidor que responde,
        // y en un hallazgo eso delata que este dispositivo tiene la clave. Se
        // hace automático porque lo has pedido; el aviso de abajo se ha
        // reescrito para que diga la verdad de lo que pasa ahora.
        refreshWallet(consultarRed = true)

        // ── DOS ACCIONES PRIMARIAS, GRANDES ───────────────────────────────
        //
        // Eran seis filas idénticas en fila india: nada decía cuáles son las
        // dos que vas a usar siempre y cuáles el resguardo que miras una vez al
        // mes. Las dos primeras pasan a tarjetas grandes, y "Ver cartera" va
        // rellena con el acento porque es la que abres el 90 % de las veces.
        fun bigCard(icon: Int, label: String, sub: String, primary: Boolean,
                    last: Boolean, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_CARD,
                if (primary) AppTheme.ACCENT else AppTheme.BG_CARD, context)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (!last) marginEnd = dp(AppTheme.GAP) }
            addView(android.widget.ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(if (primary) AppTheme.BG_DEEP else AppTheme.ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
            addView(TextView(context).apply {
                text = label
                textSize = AppTheme.SP_BODY
                setTextColor(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                typeface = AppTheme.bold(context)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(24) }
            })
            addView(TextView(context).apply {
                text = sub
                textSize = AppTheme.SP_MICRO
                setTextColor(if (primary) 0xA8000000.toInt() else AppTheme.TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(0, dp(2), 0, 0)
            })
        }

        fun abrirCartera() {
            val hasSeed = WalletManager.hasPin(this) && WalletManager.loadSeed(this) != null
            val hasWif  = WalletManager.listWifs(this).isNotEmpty()
            if (hasSeed || hasWif) {
                val ir = {
                    startActivity(Intent(this, WalletActivity::class.java).apply {
                        putExtra("MODE", "seed")
                    })
                }
                if (!PinAuthHelper.isSessionValid())
                    PinAuthHelper.show(this) { ok -> if (ok) ir() }
                else ir()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Todavía no hay ninguna cartera")
                    .setMessage("¿Quieres añadir una?")
                    .setPositiveButton("Añadir") { _, _ ->
                        startActivity(Intent(this, WalletActivity::class.java).apply {
                            putExtra("MODE", "setup")
                        })
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
            }
        }
        fun anadirCartera() {
            val opciones = arrayOf("Frase semilla (BIP39)", "Clave WIF", "Sólo observación (dirección)")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("¿Qué quieres añadir?")
                .setItems(opciones) { _, which ->
                    startActivity(Intent(this, WalletActivity::class.java).apply {
                        putExtra("MODE", when (which) {
                            1 -> "wif_import"; 2 -> "watch_import"; else -> "setup"
                        })
                    })
                }
                .show()
        }

        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
            addView(bigCard(R.drawable.ic_wallet, "Ver cartera", "Saldos y direcciones",
                primary = true, last = false) { abrirCartera() })
            addView(bigCard(R.drawable.ic_add, "Añadir", "Seed, WIF o dirección",
                primary = false, last = true) { anadirCartera() })
        })

        // ── RESGUARDO ─────────────────────────────────────────────────────
        page.addView(TextView(this).apply {
            text = "Resguardo"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        val guardCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(ctx = this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(22) }
        }
        fun guardRow(icon: Int, label: String, sub: String, primero: Boolean,
                     click: () -> Unit): Pair<TextView, TextView> {
            if (!primero) guardCard.addView(android.view.View(this).apply {
                setBackgroundColor(AppTheme.BORDER_C)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = dp(18); marginEnd = dp(18) }
            })
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                isClickable = true; isFocusable = true
                setOnClickListener { click() }
            }
            r.addView(Ui.icon(this, icon, 20).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = label; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
            })
            val subTv = TextView(this).apply {
                text = sub; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(subTv)
            r.addView(col)
            // El estado a la derecha: cuántos hay, de cuándo es la última. Sin
            // esto hay que entrar en cada uno para saber si tienes algo.
            val estado = TextView(this).apply {
                text = ""
                textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context)
            }
            r.addView(estado)
            r.addView(Ui.icon(this, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            })
            guardCard.addView(r)
            return estado to subTv
        }

        val (estVault, _) = guardRow(R.drawable.ic_vault, "Baúl de hallazgos",
                                "Cifrado, aparte de tus carteras", primero = true) {
            if (!PinAuthHelper.isSessionValid()) PinAuthHelper.show(this) { ok -> if (ok) showVault() }
            else showVault()
        }
        val (estBackup, subBackup) = guardRow(R.drawable.ic_lock, "Copias de seguridad",
                                 "Crear, ver, compartir o restaurar", primero = false) {
            if (!PinAuthHelper.isSessionValid()) PinAuthHelper.show(this) { ok -> if (ok) exportEncryptedBackup() }
            else exportEncryptedBackup()
        }
        guardRow(R.drawable.ic_export, "Exportar resumen",
                 "Sin claves privadas", primero = false) { exportLog() }
        page.addView(guardCard)

        // El estado de las dos filas, en segundo plano: leer el baúl y listar
        // los ficheros de copia es I/O, y esto corre al construir la pestaña.
        Thread {
            val hallazgos = try { MatchVault.list(this@MainActivity).size } catch (e: Exception) { 0 }
            val copias = try { BackupStore.list(this@MainActivity) } catch (e: Exception) { emptyList() }
            val ultima = copias.firstOrNull()?.createdAt ?: 0L
            runOnUiThread {
                estVault.text = if (hallazgos == 0) "Vacío" else "$hallazgos"
                estBackup.text = if (copias.isEmpty()) "Ninguna" else "${copias.size}"
                if (ultima > 0) {
                    val dias = ((System.currentTimeMillis() - ultima) / 86_400_000L).toInt()
                    subBackup.text = when (dias) {
                        0    -> "Última hoy"
                        1    -> "Última ayer"
                        else -> "Última hace $dias días"
                    }
                }
            }
        }.start()

        // ── POR QUÉ NO SE CONSULTAN SOLOS ─────────────────────────────────
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x12FF6B35); cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_warning)
                setColorFilter(AppTheme.WARN)
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                    marginEnd = dp(11)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Los saldos se consultan solos al abrir esta pantalla. " +
                       "Eso revela tus direcciones al servidor que responde: es el " +
                       "precio de verlos sin pedirlo."
                textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context)
                setLineSpacing(0f, 1.55f)
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })

        // ── CONSULTAR SALDOS ──────────────────────────────────────────────
        //
        // Era una fila más entre las seis, indistinguible de "Exportar
        // resumen". Es la única acción de la pantalla que sale a la red, y va
        // sola abajo, después del aviso que explica por qué no es automática.
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_KEY, context)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { topMargin = dp(18) }
            setOnClickListener {
                refreshWallet(consultarRed = true)
                android.widget.Toast.makeText(this@MainActivity, "Consultando la cadena…",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            addView(Ui.icon(this@MainActivity, R.drawable.ic_refresh, 16).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Consultar saldos"
                textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.medium(context)
            })
        })

        scroll.addView(page)
        return scroll
    }

    // ── BUILD RECOVERY TAB ────────────────────────────────────────────────────
    private fun buildRecoveryTab(): ScrollView {
        val ACCENT  = AppTheme.ACCENT
        val ACCENT2 = AppTheme.BLUE

        val recoveryScroll = ScrollView(this).apply {
            setBackgroundColor(AppTheme.BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val recoveryPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), dp(16), dp(AppTheme.PAD_SIDE), dp(80))
        }

        // ── HELPER ────────────────────────────────────────────────────────
        fun rCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.BG_CARD)
                cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(AppTheme.GAP) }
            setPadding(dp(AppTheme.PAD_CARD), dp(AppTheme.PAD_CARD),
                       dp(AppTheme.PAD_CARD), dp(AppTheme.PAD_CARD))
        }

        fun fieldLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // ── CABECERA ──────────────────────────────────────────────────────
        recoveryPage.addView(TextView(this).apply {
            text = "Recuperar seed"
            textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.01f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
        // El subtítulo repetía el título en otras palabras. En su lugar, lo que
        // de verdad hay que saber para usar la pantalla.
        recoveryPage.addView(TextView(this).apply {
            text = "Escribe las palabras que recuerdes y marca los huecos con ?"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(dp(4).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        })

        // ── LAS PALABRAS ──────────────────────────────────────────────────
        //
        // Era un cuadro de texto donde escribías la frase entera separada por
        // espacios y ponías "???" en los huecos. Eso obliga a llevar la cuenta
        // mental de en qué posición vas, a no equivocarte con los espacios, y a
        // saberse una convención que no está escrita en ninguna parte salvo en
        // la línea de ayuda de encima.
        //
        // Son doce (o veinticuatro) casillas numeradas. Tocas una y escribes la
        // palabra, con las sugerencias del diccionario BIP39 debajo; o la
        // marcas como hueco. El estado se ve sin leer nada: los huecos van en
        // el acento y con su borde.
        val palabras = MutableList(12) { "" }

        /** Lo que espera el motor: las palabras separadas por espacios, "???" en los huecos. */
        fun seedText() = palabras.joinToString(" ") { it.ifEmpty { "???" } }

        val wordGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }

        val tvRecoveryInfo = TextView(this).apply {
            text = "Toca una casilla para escribirla"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvRecoveryEta = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.title(context)
        }
        val tvRecoveryCombos = TextView(this).apply {
            text = "Faltan palabras por poner"
            textSize = AppTheme.SP_MICRO
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(6), 0, 0)
        }

        lateinit var pintarPalabras: () -> Unit

        /** Recalcula cuánto va a costar el intento, antes de empezarlo. */
        fun refrescarCoste() {
            val huecos = palabras.count { it.isEmpty() }
            val puestas = palabras.size - huecos
            when {
                huecos == 0 && puestas == palabras.size -> {
                    tvRecoveryInfo.text = "No falta ninguna"
                    tvRecoveryEta.text = ""
                    tvRecoveryCombos.text = "Sin huecos no hay nada que probar: " +
                                            "marca las que no recuerdes."
                }
                huecos == palabras.size -> {
                    tvRecoveryInfo.text = "Toca una casilla para escribirla"
                    tvRecoveryEta.text = ""
                    tvRecoveryCombos.text = "Escribe al menos las que recuerdes."
                }
                else -> {
                    // 2048^huecos. Por encima de 4 huecos se sale de Long, así
                    // que la cuenta va en Double y se dice en texto.
                    val combos = Math.pow(2048.0, huecos.toDouble())
                    val porSeg = 6_300_000.0   // orden de magnitud de un móvil
                    val secs = combos / porSeg
                    tvRecoveryInfo.text =
                        if (huecos == 1) "1 palabra por adivinar"
                        else "$huecos palabras por adivinar"
                    tvRecoveryEta.text = when {
                        secs < 60        -> "~ ${secs.toInt()} s"
                        secs < 3600      -> "~ ${(secs / 60).toInt()} min"
                        secs < 86_400    -> "~ ${(secs / 3600).toInt()} h"
                        secs < 31_536_000-> "~ ${(secs / 86_400).toInt()} días"
                        else             -> "más de un año"
                    }
                    tvRecoveryEta.setTextColor(
                        if (secs > 86_400) AppTheme.WARN else AppTheme.ACCENT)
                    val combosTxt = when {
                        combos >= 1e12 -> "%.1f billones".format(combos / 1e12)
                        combos >= 1e9  -> "%.1f mil millones".format(combos / 1e9)
                        combos >= 1e6  -> "%.1f millones".format(combos / 1e6)
                        else           -> numberFmt.format(combos.toLong())
                    }
                    tvRecoveryCombos.text = "$combosTxt de combinaciones"
                }
            }
        }

        /** Pide la palabra de una casilla, con el diccionario delante. */
        fun pedirPalabra(idx: Int) {
            val cont = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(16), dp(22), dp(8))
            }
            val campo = android.widget.AutoCompleteTextView(this).apply {
                setText(palabras[idx])
                hint = "palabra ${idx + 1}"
                setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
                textSize = AppTheme.SP_BODY
                typeface = AppTheme.body(context)
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                // Las 2048 del diccionario: teclear una que no esté hace que la
                // búsqueda no pueda encontrar nada, y antes no avisaba nadie.
                setAdapter(android.widget.ArrayAdapter(
                    this@MainActivity, android.R.layout.simple_list_item_1,
                    Bip39Words.WORDS))
                threshold = 1
                setSelection(text.length)
            }
            cont.addView(campo)
            AlertDialog.Builder(this)
                .setTitle("Palabra ${idx + 1}")
                .setView(cont)
                .setPositiveButton("Guardar") { _, _ ->
                    val w = campo.text.toString().trim().lowercase()
                    palabras[idx] = if (w.isNotEmpty() && Bip39Words.WORDS.contains(w)) w else ""
                    if (w.isNotEmpty() && !Bip39Words.WORDS.contains(w))
                        Toast.makeText(this, "\"$w\" no está en el diccionario BIP39",
                            Toast.LENGTH_LONG).show()
                    pintarPalabras(); refrescarCoste()
                }
                .setNeutralButton("No la recuerdo") { _, _ ->
                    palabras[idx] = ""
                    pintarPalabras(); refrescarCoste()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        pintarPalabras = {
            wordGrid.removeAllViews()
            var fila: LinearLayout? = null
            palabras.forEachIndexed { i, w ->
                if (i % 3 == 0) {
                    fila = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { if (i > 0) topMargin = dp(8) }
                    }
                    wordGrid.addView(fila)
                }
                val hueco = w.isEmpty()
                val chip = TextView(this).apply {
                    val sp = android.text.SpannableStringBuilder("${i + 1}  ")
                    sp.setSpan(android.text.style.ForegroundColorSpan(AppTheme.TXT_MUTED),
                        0, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.setSpan(android.text.style.AbsoluteSizeSpan(
                        (11 * resources.displayMetrics.scaledDensity).toInt()),
                        0, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.append(if (hueco) "falta" else w)
                    text = sp
                    textSize = AppTheme.SP_BODY
                    gravity = Gravity.CENTER
                    setTextColor(if (hueco) AppTheme.ACCENT else AppTheme.TXT_PRI)
                    typeface = if (hueco) AppTheme.bold(context) else AppTheme.body(context)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (hueco) AppTheme.BG_ELEV else AppTheme.BG_KEY)
                        cornerRadius = dp(AppTheme.R_INNER).toFloat()
                        if (hueco) setStroke(dp(2), AppTheme.ACCENT)
                    }
                    setPadding(dp(6), dp(14), dp(6), dp(14))
                    isClickable = true; isFocusable = true
                    setOnClickListener { pedirPalabra(i) }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i % 3 < 2) marginEnd = dp(8) }
                }
                fila?.addView(chip)
            }
        }

        // Selector de 12 o 24 palabras. Antes se deducía de cuántas escribías,
        // así que una frase de 24 a medio poner se trataba como de 12.
        val largoRow = Ui.segmented(this,
            listOf("12 palabras" to null, "24 palabras" to null), initial = 0) { idx ->
            val nuevo = if (idx == 0) 12 else 24
            while (palabras.size < nuevo) palabras.add("")
            while (palabras.size > nuevo) palabras.removeAt(palabras.size - 1)
            pintarPalabras(); refrescarCoste()
        }
        recoveryPage.addView(largoRow.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        })
        pintarPalabras()
        recoveryPage.addView(wordGrid)

        // ── LO QUE CUESTA EL INTENTO ──────────────────────────────────────
        val costeCard = rCard()
        val costeHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        costeHead.addView(tvRecoveryInfo); costeHead.addView(tvRecoveryEta)
        costeCard.addView(costeHead)
        costeCard.addView(tvRecoveryCombos)
        refrescarCoste()

        recoveryPage.addView(costeCard)

        // ── TARGET ADDRESS CARD ───────────────────────────────────────────
        val targetCard = rCard()
        targetCard.addView(fieldLabel("Dirección conocida · opcional"))
        val etTarget = android.widget.EditText(this).apply {
            hint = "1A2B3C... o bc1q..."
            setHintTextColor(AppTheme.TXT_MUTED); setTextColor(AppTheme.TXT_PRI)
            textSize = AppTheme.SP_BODY; typeface = Typeface.MONOSPACE
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            minHeight = dp(48)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        targetCard.addView(etTarget)
        recoveryPage.addView(targetCard)

        // ── PROGRESS CARD ─────────────────────────────────────────────────
        // El coste del intento está arriba, en costeCard; aquí sólo va la barra
        // y lo que está probando ahora mismo.
        val progressCard = rCard()
        val pbRecovery = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000; progress = 0
            // Mismo fallo que tenía la barra del puzzle: un GradientDrawable
            // pelado como progressDrawable se pinta entero, sin recortarse,
            // así que la barra aparecía llena desde el primer instante.
            progressDrawable = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(AppTheme.BG_ELEV); cornerRadius = dp(4).toFloat()
                    },
                    android.graphics.drawable.ClipDrawable(
                        android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(ACCENT2, ACCENT)
                        ).apply { cornerRadius = dp(4).toFloat() },
                        Gravity.START,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL)
                )
            ).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { bottomMargin = dp(8) }
            visibility = android.view.View.GONE
        }
        val tvRecoveryStatus = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            visibility = android.view.View.GONE
        }
        progressCard.addView(pbRecovery)
        progressCard.addView(tvRecoveryStatus)

        val tvRecoveryResult = TextView(this).apply {
            // Un resultado encontrado SÍ merece el acento: es el hallazgo.
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = Typeface.MONOSPACE   // lleva la seed entera
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setLineSpacing(0f, 1.35f)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        progressCard.addView(tvRecoveryResult)
        recoveryPage.addView(progressCard)

        // ── BUTTONS ───────────────────────────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val btnStartRecovery = Button(this).apply {
            text = "Iniciar recuperación"; textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.BG_DEEP)
            background = Ui.cardBg(AppTheme.R_KEY, AppTheme.ACCENT, context)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancelRecovery = Button(this).apply {
            text = "Cancelar"; textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.RED)
            background = Ui.cardBg(AppTheme.R_KEY, AppTheme.BG_ELEV, context)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            visibility = android.view.View.GONE
        }
        btnRow.addView(btnStartRecovery); btnRow.addView(btnCancelRecovery)
        recoveryPage.addView(btnRow)

        val btnSaveWallet = Button(this).apply {
            text = "Guardar en la cartera"; textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.BG_DEEP)
            background = Ui.cardBg(AppTheme.R_KEY, AppTheme.ACCENT, context)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
        }
        recoveryPage.addView(btnSaveWallet)

        // ── LISTENERS ─────────────────────────────────────────────────────

        btnSaveWallet.setOnClickListener {
            val foundMnemonic = it.tag as? String ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Guardar en Wallet")
                .setMessage("¿Guardar esta seed phrase en tu wallet principal?\n\n$foundMnemonic")
                .setPositiveButton("Guardar") { _, _ ->
                    if (PinAuthHelper.isSessionValid()) {
                        WalletManager.saveSeed(this, foundMnemonic)
                        btnSaveWallet.visibility = android.view.View.GONE
                        tvRecoveryStatus.text = "Seed guardada en la cartera principal"
                        tvRecoveryStatus.visibility = android.view.View.VISIBLE
                    } else {
                        PinAuthHelper.show(this) { ok ->
                            if (ok) {
                                WalletManager.saveSeed(this, foundMnemonic)
                                btnSaveWallet.visibility = android.view.View.GONE
                                tvRecoveryStatus.text = "Seed guardada en la cartera principal"
                                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        recoveryEngine = RecoveryEngine(this)
        val wordlistLoaded = recoveryEngine?.loadWordlist() ?: false

        recoveryEngine?.listener = object : com.hunter.btc.recovery.RecoveryEngine.ProgressListener {
            override fun onProgress(attempts: Long, total: Long, currentWord: String) {
                runOnUiThread {
                    // attempts.toFloat() pierde precisión por encima de ~16.7M,
                    // y con 3-4 palabras faltantes el total llega a 1e13.
                    val pct = if (total > 0)
                        ((attempts.toDouble() / total) * 1000).toInt().coerceIn(0, 1000)
                    else 0
                    pbRecovery.progress = pct
                    tvRecoveryStatus.text = "Probando: $currentWord  ($attempts / $total)"
                }
            }
            override fun onFoundWithAddress(mnemonic: String, address: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "DIRECCION DERIVADA:\n$address\n\nFRASE:\n$mnemonic"
                    tvRecoveryResult.visibility = android.view.View.VISIBLE
                }
            }
            override fun onFound(mnemonic: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "✓ ENCONTRADO\n\n$mnemonic"
                    tvRecoveryResult.visibility = android.view.View.VISIBLE
                    btnSaveWallet.tag = mnemonic
                    btnSaveWallet.visibility = android.view.View.VISIBLE
                    // La seed NO se escribe en disco. Antes se volcaba en claro a
                    // getExternalFilesDir()/recovery_<ts>.txt, legible por cualquier
                    // app con MANAGE_EXTERNAL_STORAGE y visible por USB, lo que
                    // anulaba el cifrado del resto de la app. Para conservarla, el
                    // usuario pulsa "Guardar wallet", que la cifra con el Keystore.
                    // Borramos también los ficheros que dejaron versiones anteriores.
                    purgeLegacyRecoveryFiles()
                    sendMatchNotification("Seed recuperada", "RECOVERY")
                }
            }
            override fun onNotFound() {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.text = "No encontrado. Verifica las palabras conocidas."
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                }
            }
            override fun onCancelled() {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.text = "Cancelado."
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                }
            }
        }

        btnStartRecovery.setOnClickListener {
            val input = seedText()
            if (palabras.all { it.isEmpty() }) {
                tvRecoveryStatus.text = "Escribe al menos las palabras que recuerdes."
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (palabras.none { it.isEmpty() }) {
                tvRecoveryStatus.text = "No hay ningún hueco que probar: marca las que no recuerdes."
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (!wordlistLoaded) {
                tvRecoveryStatus.text = "No se pudo cargar el diccionario BIP39."
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            val wl = recoveryEngine?.getWordlistSet() ?: emptySet()
            val parseResult = com.hunter.btc.recovery.RecoveryParser.parse(input, wl)
            when (parseResult) {
                is com.hunter.btc.recovery.ParseResult.Error -> {
                    tvRecoveryStatus.text = parseResult.message
                    tvRecoveryStatus.visibility = android.view.View.VISIBLE
                }
                is com.hunter.btc.recovery.ParseResult.Success -> {
                    tvRecoveryResult.visibility = android.view.View.GONE
                    pbRecovery.progress = 0
                    pbRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryStatus.visibility = android.view.View.VISIBLE
                    tvRecoveryStatus.text = "Iniciando..."
                    btnStartRecovery.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.VISIBLE
                    recoveryEngine?.startRecovery(parseResult.parsed, etTarget?.text.toString().trim() ?: "")
                }
            }
        }

        btnCancelRecovery.setOnClickListener { recoveryEngine?.cancel() }

        recoveryScroll.addView(recoveryPage)
        return recoveryScroll
    }

    // ── FUNCIONES AUXILIARES ──────────────────────────────────────────────────

    private fun mkSbl(cb: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { cb() }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }


    private fun updateLabels() {
                val t = (sbThreads?.progress ?: 3) + 1
        val c = (sbCpu?.progress ?: 70) + 10
        tvThreads?.text = "Threads: $t"
        tvCpu?.text = "CPU limit: $c%"
        tvQuickThreads?.text = "$t"
        tvQuickCpu?.text = "$c%"
        prefs.edit().putInt("threads", sbThreads?.progress ?: 3).putInt("cpu", sbCpu?.progress ?: 70).apply()
    }

    private fun updatePuzzleLabels() {
        val t = (sbThreadsPuzzle?.progress ?: 3) + 1
        val c = (sbCpuPuzzle?.progress ?: 70) + 10
        // Mostrar también los núcleos disponibles: sin esa referencia no hay
        // forma de saber si el número de hilos elegido tiene sentido.
        tvThreadsPuzzle?.text = "Threads: $t / ${Runtime.getRuntime().availableProcessors()} cores"
        tvCpuPuzzle?.text = "CPU limit: $c%"
        prefs.edit().putInt("puzzle_threads", sbThreadsPuzzle?.progress ?: 3)
                    .putInt("puzzle_cpu",     sbCpuPuzzle?.progress ?: 70).apply()
    }



    private fun getBatteryTemp(): Float {
        return try {
            val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10f
        } catch (e: Exception) { 0f }
    }

    private fun getCpuTemp(): Float {
        // Buscar en todas las zonas térmicas disponibles
        try {
            val base = java.io.File("/sys/class/thermal")
            if (base.exists()) {
                val temps = base.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.mapNotNull {
                        try {
                            val t = java.io.File(it, "temp").readText().trim().toFloatOrNull()
                            if (t != null && t > 0) if (t > 1000) t / 1000f else t else null
                        } catch (e: Exception) { null }
                    } ?: emptyList()
                if (temps.isNotEmpty()) return temps.max()
            }
        } catch (e: Exception) {}
        // Fallback paths Samsung
        for (p in listOf(
            "/sys/class/thermal/thermal_zone4/temp",
            "/sys/class/thermal/thermal_zone7/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/kernel/debug/spmi/spmi-0/address"
        )) {
            try {
                val raw = java.io.File(p).readText().trim().toFloatOrNull() ?: continue
                if (raw > 0) return if (raw > 1000) raw / 1000f else raw
            } catch (e: Exception) {}
        }
        return 0f
    }

    // ── Auto-detección de hardware ───────────────────────────────────────────
    data class HardwareProfile(
        val cores: Int,
        val recommendedThreads: Int,
        val recommendedCpu: Int,
        val chipName: String,
        val ramMB: Long,
        val archInfo: String
    )

    private fun detectHardware(): HardwareProfile {
        val cores = Runtime.getRuntime().availableProcessors()

        // Leer info del chip desde /proc/cpuinfo
        val chipName = try {
            val cpuinfo = java.io.File("/proc/cpuinfo").readText()
            val hardware = cpuinfo.lines()
                .firstOrNull { it.startsWith("Hardware") }
                ?.substringAfter(":")?.trim() ?: ""
            val model = cpuinfo.lines()
                .firstOrNull { it.startsWith("model name") || it.startsWith("Model name") }
                ?.substringAfter(":")?.trim() ?: ""
            when {
                hardware.contains("Snapdragon", true) -> hardware
                model.contains("Snapdragon", true)    -> model
                hardware.contains("Exynos", true)     -> hardware
                hardware.contains("Dimensity", true)  -> hardware
                hardware.isNotEmpty()                 -> hardware
                else -> {
                    // Fallback: usar Build.MODEL y SOC info
                    val soc = if (android.os.Build.VERSION.SDK_INT >= 31)
                        android.os.Build.SOC_MODEL
                    else ""
                    val model = android.os.Build.MODEL
                    when {
                        soc.isNotEmpty() && soc != "unknown" -> "$soc ($cores cores)"
                        model.contains("SM-S9", true) -> "Snapdragon 8 Gen 2 ($cores cores)"
                        model.contains("SM-S8", true) -> "Snapdragon 8 Gen 1 ($cores cores)"
                        model.contains("SM-A5", true) -> "Snapdragon 778G ($cores cores)"
                        model.contains("SM-A3", true) -> "Snapdragon 680 ($cores cores)"
                        else -> "ARM64 · ${model} ($cores cores)"
                    }
                }
            }
        } catch (e: Exception) { "ARM64 (${cores} cores)" }

        // RAM disponible
        val ramMB = try {
            val rt = Runtime.getRuntime()
            val actManager = getSystemService(android.app.ActivityManager::class.java)
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (e: Exception) { 0L }

        // Arquitectura
        val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        // Calcular threads óptimos:
        // - Dejar 2 cores para sistema y UI
        // - Máximo 8 threads útiles para este tipo de workload
        val recommendedThreads = (cores - 2).coerceIn(2, 8)

        // CPU limit según RAM (menos RAM = más conservador)
        val recommendedCpu = when {
            ramMB > 3000 -> 90
            ramMB > 1500 -> 75
            ramMB > 800  -> 60
            else         -> 50
        }

        return HardwareProfile(
            cores = cores,
            recommendedThreads = recommendedThreads,
            recommendedCpu = recommendedCpu,
            chipName = chipName,
            ramMB = ramMB,
            archInfo = arch
        )
    }

    private fun applyHardwareProfile(profile: HardwareProfile) {
        val threadProgress = (profile.recommendedThreads - 1).coerceIn(0, 7)
        sbThreads?.progress = threadProgress
        sbThreadsPuzzle?.progress = threadProgress

        val cpuProgress = (profile.recommendedCpu - 10).coerceIn(0, 90)
        sbCpu?.progress = cpuProgress
        sbCpuPuzzle?.progress = cpuProgress

        updateLabels()
        updatePuzzleLabels()

        // CPU Affinity: big cores en Exynos/Snapdragon big.LITTLE
        val totalCores = profile.cores
        val bigCores = if (totalCores >= 8) {
            // Últimos 4 cores suelen ser los big (A78/Kryo)
            intArrayOf(4, 5, 6, 7)
        } else if (totalCores >= 6) {
            intArrayOf(4, 5)
        } else {
            intArrayOf(0, 1, 2, 3)
        }
        try { HunterEngine.setBigCores(bigCores, true) } catch (e: Exception) {}

        // Batch dinámico según RAM
        val batchSize = when {
            profile.ramMB > 3000 -> 32000  // RAM alta → batch grande
            profile.ramMB > 1500 -> 16000  // normal
            profile.ramMB > 800  -> 8000   // conservador
            else                 -> 4000
        }
        try { HunterEngine.setBatchSize(batchSize) } catch (e: Exception) {}

        prefs.edit()
            .putInt("threads", threadProgress)
            .putInt("cpu", cpuProgress)
            .putInt("puzzle_threads", threadProgress)
            .putInt("puzzle_cpu", cpuProgress)
            .putString("big_cores", bigCores.joinToString(","))
            .putInt("batch_size", batchSize)
            .apply()

        // Forzar actualización visual de sliders
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            sbThreads?.progress     = threadProgress
            sbCpu?.progress         = cpuProgress
            sbThreadsPuzzle?.progress = threadProgress
            sbCpuPuzzle?.progress   = cpuProgress
            updateLabels()
            updatePuzzleLabels()
        }
    }

    private fun showHardwareInfo() {
        val profile = detectHardware()
        val msg = """
            🔧 Hardware detectado:
            
            Chip: ${profile.chipName}
            Cores: ${profile.cores}
            RAM libre: ${profile.ramMB} MB
            Arch: ${profile.archInfo}
            
            Configuración recomendada:
            • Threads: ${profile.recommendedThreads}
            • CPU limit: ${profile.recommendedCpu}%
            
            Batch size: ${if (profile.ramMB > 3000) 32000 else if (profile.ramMB > 1500) 16000 else 8000} keys
            Big cores: ${if (profile.cores >= 8) "4-7" else "auto"}
            
            ¿Aplicar configuración óptima?
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Auto-configuración")
            .setMessage(msg)
            .setPositiveButton("Aplicar") { _, _ ->
                applyHardwareProfile(profile)
                // Forzar redibujado inmediato
                val t = profile.recommendedThreads
                val c = profile.recommendedCpu
                runOnUiThread {
                    sbThreads?.progress       = (t - 1).coerceIn(0, 7)
                    sbCpu?.progress           = (c - 10).coerceIn(0, 90)
                    sbThreadsPuzzle?.progress = (t - 1).coerceIn(0, 7)
                    sbCpuPuzzle?.progress     = (c - 10).coerceIn(0, 90)
                    tvThreads?.text     = "Threads: $t"
                    tvCpu?.text         = "CPU limit: $c%"
                    // Mostrar también los núcleos disponibles: sin esa referencia no hay
        // forma de saber si el número de hilos elegido tiene sentido.
        tvThreadsPuzzle?.text = "Threads: $t / ${Runtime.getRuntime().availableProcessors()} cores"
                    tvCpuPuzzle?.text     = "CPU limit: $c%"
                }
                Toast.makeText(this,
                    "Aplicado: $t threads / $c% CPU",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun formatCount(v: Long): String = numberFmt.format(v)

    // ── Registro local de bloques escaneados ─────────────────────────────────
    /**
     * Limpieza obligada por la corrección de la tabla de puzzles.
     *
     * La tabla anterior tenía las direcciones en el número equivocado, así que
     * TODO lo que hay guardado por número de puzzle se hizo contra una
     * dirección que no era la de ese puzzle:
     *
     *  - El progreso por bloques cuenta claves probadas, pero comparadas
     *    contra el objetivo equivocado: son claves que nunca se han comprobado
     *    de verdad contra este puzzle. Dejarlo diría que hay un 12 % recorrido
     *    de algo que no se ha empezado.
     *  - Los checkpoints apuntan a dónde se quedó esa búsqueda inútil.
     *  - Los puzzles ocultados por "sin fondos" se ocultaron mirando el saldo
     *    de otra dirección, así que hay puzzles con premio escondidos.
     *
     * Se hace una sola vez y queda anotado.
     */
    private fun migrarTablaPuzzles() {
        val p = getSharedPreferences("app_settings", MODE_PRIVATE)
        if (p.getInt("tabla_puzzles_ver", 0) >= 2) return
        listOf("puzzle_blocks", "puzzle_checkpoint", "hidden_puzzles").forEach {
            getSharedPreferences(it, MODE_PRIVATE).edit().clear().apply()
        }
        // La caché de claves públicas va por DIRECCIÓN, no por número, así que
        // lo que tenga guardado sigue siendo cierto y no hay que tirarlo.
        p.edit().putInt("tabla_puzzles_ver", 2).apply()
        android.util.Log.i("MainActivity",
            "Tabla de puzzles corregida: progreso y ocultos reiniciados")
    }

    private fun getBlockPrefs() = getSharedPreferences("puzzle_blocks", MODE_PRIVATE)

    /** Entero aleatorio uniforme entre 0 y bound-1. Por rechazo: <2 intentos de media. */
    private fun randomBelow(bound: java.math.BigInteger): java.math.BigInteger {
        if (bound <= java.math.BigInteger.ONE) return java.math.BigInteger.ZERO
        val rnd = java.security.SecureRandom()
        val bits = bound.bitLength()
        var r: java.math.BigInteger
        do { r = java.math.BigInteger(bits, rnd) } while (r >= bound)
        return r
    }

    /** Número total de bloques del rango de un puzzle. */
    private fun totalBlocksOf(rangeStart: String, rangeEnd: String): java.math.BigInteger {
        val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
        val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
        return end.subtract(start).divide(BLOCK_SIZE).max(java.math.BigInteger.ONE)
    }

    /** Traduce un índice de bloque al rango hexadecimal que entiende el motor. */
    private fun blockRange(rangeStart: String, rangeEnd: String,
                           blockIdx: java.math.BigInteger): Pair<String, String> {
        val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
        val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
        val bStart = start.add(BLOCK_SIZE.multiply(blockIdx))
        val bEnd   = bStart.add(BLOCK_SIZE).min(end)
        return Pair(bStart.toString(16).padStart(18, '0'),
                    bEnd.toString(16).padStart(18, '0'))
    }

    /** Posición del bloque dentro del rango, en porcentaje. */
    private fun blockPercent(blockIdx: java.math.BigInteger,
                             totalBlocks: java.math.BigInteger): Double =
        if (totalBlocks.signum() <= 0) 0.0
        else blockIdx.toBigDecimal()
            .divide(totalBlocks.toBigDecimal(), 8, java.math.RoundingMode.HALF_UP)
            .toDouble() * 100.0

    private fun getNextUnscannedBlock(puzzleNum: Int, rangeStart: String, rangeEnd: String): Pair<String, String>? {
        return try {
            // totalBlocks estaba topado a 100.000 y el índice se sacaba con
            // Math.random()*totalBlocks sobre un Long. El puzzle 70 tiene
            // 590.295.810.358 bloques, así que el "bloque aleatorio" nunca salía
            // de los primeros 100.000: el 0,0000169 % inicial del rango, una y
            // otra vez. Ahora el índice es un BigInteger uniforme sobre el rango
            // entero.
            val totalBlocks = totalBlocksOf(rangeStart, rangeEnd)
            val scanned = getBlockPrefs().getStringSet("scanned_$puzzleNum", emptySet()) ?: emptySet()

            var attempts = 0
            var blockIdx: java.math.BigInteger
            do {
                blockIdx = randomBelow(totalBlocks)
                attempts++
            } while (scanned.contains(blockIdx.toString()) && attempts < 100)

            if (attempts >= 100) return null  // todo escaneado

            currentBlockId = blockIdx.toString()
            blockRange(rangeStart, rangeEnd, blockIdx)
        } catch (e: Exception) { null }
    }

    /**
     * Elige un punto al azar del rango del puzzle para el siguiente arranque.
     *
     * Cada START ya escoge un bloque aleatorio, pero el rango es tan grande que
     * no hay forma de ver dónde ha caído. Esto lo elige y lo enseña: "arrancará
     * en el 46,32 %".
     */
    /**
     * Escribe en qué bloque estamos y en qué punto del rango cae.
     *
     * Un id de bloque de doce cifras no dice nada solo; el porcentaje sí.
     */
    private fun setCurrentBlockLabel(prefix: String, blockIdx: java.math.BigInteger) {
        val rs = puzzleFullStart.ifEmpty { etRangeStart?.text?.toString()?.trim() ?: "" }
        val re = puzzleFullEnd.ifEmpty  { etRangeEnd?.text?.toString()?.trim() ?: "" }
        if (rs.isEmpty() || re.isEmpty()) return
        try {
            val total = totalBlocksOf(rs, re)
            val pct   = blockPercent(blockIdx, total)
            val txt = "$prefix #%s  ·  %.4f%% del rango".format(
                numberFmt.format(blockIdx), pct)
            if (tvCurrentBlock?.text?.toString() != txt) tvCurrentBlock?.text = txt
        } catch (e: Exception) {}
    }

    private fun pickRandomJump() {
        val rs = puzzleFullStart.ifEmpty { etRangeStart?.text?.toString()?.trim() ?: "" }
        val re = puzzleFullEnd.ifEmpty  { etRangeEnd?.text?.toString()?.trim() ?: "" }
        if (rs.isEmpty() || re.isEmpty()) {
            Toast.makeText(this, "Selecciona un puzzle primero", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val total = totalBlocksOf(rs, re)
            val idx   = randomBelow(total)
            pendingBlockIdx = idx
            val pct = blockPercent(idx, total)
            tvRandomJump?.text = "Otro punto al azar"
            setCurrentBlockLabel("Arrancará en el bloque", idx)
            val (bStart, _) = blockRange(rs, re, idx)
            if (HunterEngine.isRunning()) {
                Toast.makeText(this,
                    "Se aplicará al reiniciar el escaneo (%.2f%%)".format(pct),
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this,
                    "Bloque #$idx  ·  %.2f%%\nDesde 0x${bStart.trimStart('0')}".format(pct),
                    Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo calcular el salto: ${e.message}",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun markBlockScanned(puzzleNum: Int) {
        if (currentBlockId.isEmpty()) return
        val prefs = getBlockPrefs()
        val scanned = prefs.getStringSet("scanned_$puzzleNum", emptySet())?.toMutableSet() ?: mutableSetOf()
        scanned.add(currentBlockId)
        prefs.edit().putStringSet("scanned_$puzzleNum", scanned).apply()

        // Si hay red activa como worker, reportar al master
        if (NetworkManager.isWorker && NetworkManager.isRunning.get()) {
            val masterIp = getSharedPreferences("net_prefs", MODE_PRIVATE)
                .getString("master_ip", null) ?: return
            NetworkManager.reportBlockDone(masterIp, currentBlockId)
        }
        // Agregar al registro global local
        NetworkManager.globalScannedBlocks.add(currentBlockId)
    }

    private fun getBlockProgressText(puzzleNum: Int, rangeStart: String, rangeEnd: String): String {
        // Si hay red activa, mostrar progreso global
        if (NetworkManager.isRunning.get() && NetworkManager.globalScannedBlocks.isNotEmpty()) {
            return NetworkManager.getGlobalProgress(rangeStart, rangeEnd) + " [RED]"
        }
        return try {
            // .toLong() sobre el BigInteger truncaba en silencio: el puzzle 160
            // tiene 7,3e38 bloques y el total salía como un número sin sentido.
            val total = totalBlocksOf(rangeStart, rangeEnd)
            val scanned = getBlockPrefs().getStringSet("scanned_$puzzleNum", emptySet())?.size ?: 0
            val pct = blockPercent(java.math.BigInteger.valueOf(scanned.toLong()), total)
            "Bloques: $scanned / $total (%.4f%%)".format(pct)
        } catch (e: Exception) { "" }
    }



    private fun formatElapsed(startTimeMs: Long): String {
        if (startTimeMs <= 0) return "00:00:00"
        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
        if (elapsed < 0) return "00:00:00"
        val d  = elapsed / 86400
        val h  = (elapsed % 86400) / 3600
        val m  = (elapsed % 3600) / 60
        val sc = elapsed % 60
        return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, sc)
               else "%02d:%02d:%02d".format(h, m, sc)
    }

    private var peakLabel = ""

    /** "Buscando · 51 s" o "En espera", con su punto. */
    private fun paintScanState(running: Boolean) {
        tvScanState?.text =
            if (running) "Buscando · ${formatElapsed(sessionStartTime)}" else "En espera"
        tvScanState?.setTextColor(if (running) AppTheme.ACCENT else AppTheme.TXT_SEC)
        (scanStateDot?.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(if (running) AppTheme.ACCENT else AppTheme.TXT_MUTED)
    }

    /** Resumen de las filas de ajuste, con el valor que tienen ahora mismo. */
    private fun paintSettingSummaries() {
        val hilos = (sbThreads?.progress ?: 3) + 1
        val cpu   = (sbCpu?.progress ?: 70) + 10
        tvEngineSummary?.text = "$hilos hilos · $cpu %"
        tvClusterSummary?.text = when {
            NetworkManager.isRunning.get() && NetworkManager.isMaster -> "Maestro"
            NetworkManager.isRunning.get() -> "Trabajador"
            else -> "Inactivo"
        }
    }

    private fun updateUI() {
        try {
            paintScanState(HunterEngine.isRunning())
            paintSettingSummaries()
            refrescarKangaroo()
            if (HunterEngine.isRunning()) {
                val wps = HunterEngine.getWps()
                // Actualizar peak y promedio
                if (wps > peakWps) {
                    peakWps = wps
                    // Se mostraba sin escalar junto a un valor ya escalado:
                    // "1.76 MKeys" al lado de "peak 4,816,000" es ilegible.
                    val (pv, pu) = scaleSpeed(wps)
                    peakLabel = "Pico $pv $pu/s"
                    tvPeakWpsPuzzle?.text = peakLabel
                }
                if (wps > 0) {
                    avgWpsSum += wps
                    avgWpsCount++
                    val avg = avgWpsSum / avgWpsCount
                    val (av, au) = scaleSpeed(avg)
                    tvPeakWps?.text =
                        if (peakLabel.isEmpty()) "Media $av $au/s"
                        else "$peakLabel · media $av $au/s"
                }

                if (puzzleMode) {
                    val (spdTxt, spdUnit) = scaleSpeed(wps)
                    tvWpsPuzzle?.text = spdTxt.replace('.', ',')
                    tvSpeedUnitPuzzle?.text = "$spdUnit/s"
                    val scannedNow = HunterEngine.getCount()
                    tvCountPuzzle?.text = formatCount(scannedNow)
                    tvTimePuzzle?.text = formatElapsed(sessionStartTime)
                    // PROGRESO era un literal fijo que nunca se recalculaba.
                    tvPctPuzzle?.text = formatPuzzleProgress(
                        scannedNow, puzzleFullStart, puzzleFullEnd)
                    // Refresca barra y recuento de bloques. Lee prefs y opera con
                    // BigInteger, así que no en cada tick de 800ms.
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastProgressTick > 5000 && puzzleFullStart.isNotEmpty()) {
                        lastProgressTick = nowMs
                        val pnum = puzzles.firstOrNull { it.start == puzzleFullStart }?.num
                        if (pnum != null)
                            puzzleProgressUpdater?.invoke(pnum, puzzleFullStart, puzzleFullEnd)
                    }
                    // Tiempo estimado para completar el rango
                    if (wps > 0 && currentRangeStart.isNotEmpty() && currentRangeEnd.isNotEmpty()) {
                        try {
                            // getWps() ya devuelve claves/s: el *1000 hacía que
                            // el ETA mostrado fuese mil veces más optimista.
                            val kps = java.math.BigInteger.valueOf(
                                wps.toLong().coerceAtLeast(1))
                            fun etaOf(a: String, b: String): String {
                                if (a.isEmpty() || b.isEmpty()) return "—"
                                val s0 = java.math.BigInteger(a.trimStart('0').ifEmpty{"0"}, 16)
                                val e0 = java.math.BigInteger(b.trimStart('0').ifEmpty{"0"}, 16)
                                val size = e0.subtract(s0)
                                if (size.signum() <= 0) return "—"
                                return formatEta(size.divide(kps))
                            }
                            // Los dos ETA medían lo mismo — el bloque — porque
                            // ambos usaban currentRangeStart/End. Ahora la línea
                            // de estado informa del bloque en curso y la
                            // mini-stat del puzzle completo, que es lo que de
                            // verdad interesa para dimensionar el intento.
                            val etaBlock  = etaOf(currentRangeStart, currentRangeEnd)
                            val etaPuzzle = etaOf(puzzleFullStart, puzzleFullEnd)
                            if (currentRangeStart != cachedPuzzleLabelForStart) {
                                cachedPuzzleLabelForStart = currentRangeStart
                                cachedPuzzleLabel = puzzles.firstOrNull { it.start == puzzleFullStart }?.num?.let { "#$it" } ?: ""
                            }
                            tvPuzzleStatus?.text =
                                "Bloque: $etaBlock · Puzzle $cachedPuzzleLabel: $etaPuzzle"
                            tvBlockProgress?.text = etaPuzzle
                            // Se reafirma cada ciclo para que sobreviva a que se
                            // reconstruya la pestaña o se vuelva desde otra.
                            if (currentBlockId.isNotEmpty())
                                setCurrentBlockLabel("Escaneando bloque",
                                    java.math.BigInteger(currentBlockId))
                        } catch (e: Exception) {}
                    }
                } else {
                    val (sv, su) = scaleSpeed(wps)
                    tvWps?.text = sv.replace('.', ',')
                    // "MKeys/s" y no "MKEYS / SEG": la unidad va junto a la
                    // cifra, no de rótulo debajo, así que se lee como una frase.
                    tvSpeedUnitScan?.text = "$su/s"
                    tvCount?.text = formatCount(HunterEngine.getCount())
                    tvTime?.text = formatElapsed(sessionStartTime)
                    chartView?.addPoint(wps.toFloat())
                    val found = HunterEngine.getCount() - sessionStartCount
                    tvMatches?.text = "$found"
                    tvQuickMatches?.text = "$found"
                    // Stats adicionales para Raw Key
                    if (wps > 0) {
                        // Mismo error que en el ETA: wps ya viene en claves/s, así
                        // que el *1000 inflaba las claves/día por mil.
                        val keysPerSec = wps
                        val perDay = (keysPerSec * 86400).toLong()
                        val perDayStr = when {
                            perDay >= 1_000_000_000_000L ->
                                "${numberFmt.format(perDay/1_000_000_000_000L)} billones"
                            perDay >= 1_000_000_000 ->
                                "${numberFmt.format(perDay/1_000_000_000)} mil millones"
                            perDay >= 1_000_000 -> "${numberFmt.format(perDay/1_000_000)} millones"
                            else                -> numberFmt.format(perDay)
                        }
                        tvBinInfoRef?.text = "$perDayStr de claves al día"
                        tvBinInfoRef?.setTextColor(AppTheme.TXT_SEC)
                    }
                }
            }
            // El motor sigue escribiendo los aciertos en claro —el Keystore es de
            // la capa Java y C++ no llega a él—, así que se recogen en cuanto
            // aparecen. Sólo cuando el contador sube: leer el fichero en cada
            // tick de 800 ms sería I/O para nada.
            val foundNow = HunterEngine.getFound()
            if (foundNow != lastFoundSeen) {
                lastFoundSeen = foundNow
                Thread {
                    try {
                        // Aquí sí se consulta, y sólo aquí de forma automática:
                        // acaba de aparecer un acierto y lo primero que se
                        // quiere saber es si esa dirección tiene fondos. Es una
                        // dirección, no el baúl entero.
                        if (MatchVault.ingestPlaintextFile(this@MainActivity) > 0)
                            MatchVault.resolvePendingBalances(this@MainActivity)
                    } catch (e: Exception) {}
                }.start()
            }

            val rt = Runtime.getRuntime()
            tvRam?.text = "RAM ${(rt.totalMemory()-rt.freeMemory())/1048576}MB"

        // ── WATCHDOG ─────────────────────────────────────────────────────
        // Si Android se lleva por delante el escaneo en segundo plano, lo
        // relanza. Sólo cuenta como caída si antes estaba corriendo de verdad
        // (lastKnownRunning), para no reaccionar a una parada del usuario.
        val wasRunning = prefs.getBoolean("scan_was_running", false)
        val isNowRunning = HunterEngine.isRunning()
        if (watchdogEnabled && wasRunning && !isNowRunning && lastKnownRunning) {
            // Sin esto, tras perder el dataset —se va con la app al
            // desinstalar— el reintento entraría en el guardia de doToggle() y
            // sacaría un diálogo cada dos segundos sin que nadie lo hubiera
            // pedido. Si no hay con qué comparar, no hay nada que reanudar.
            if (!engineHasSomethingToMatch()) {
                prefs.edit().putBoolean("scan_was_running", false).apply()
            } else {
                watchdogRestarts++
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!HunterEngine.isRunning() && prefs.getBoolean("scan_was_running", false)) {
                        doToggle(if (puzzleMode) btnPuzzleToggle else btnToggle)
                    }
                }, 2000)
            }
        }
        lastKnownRunning = isNowRunning

        // El botón seguía el estado que dábamos por supuesto al pulsarlo, no el
        // del motor. Si éste paraba por su cuenta —Android matando el proceso de
        // trabajo, o un arranque que no prosperó— el botón se quedaba en STOP con
        // nada corriendo, y la siguiente pulsación parecía no hacer nada porque
        // en realidad estaba arrancando. Cada ciclo se reconcilia con la verdad.
        // Durante la parada isRunning() sigue true; contarlo como "corriendo"
        // devolvería el botón a STOP justo después de que el usuario lo pulsara.
        val uiRunning = isNowRunning && !HunterEngine.isStopping()
        syncToggleButton(btnToggle, uiRunning && !puzzleMode, s.start)
        syncToggleButton(btnPuzzleToggle, uiRunning && puzzleMode, "Iniciar puzzle")
        if (!uiRunning) activeToggleBtn = null

        // Checkpoint puzzle - guardar cada ~30 seg (cada ~37 ciclos de 800ms)
        if (puzzleMode && HunterEngine.isRunning()) {
            val cycleCount = (System.currentTimeMillis() / 800).toInt()
            if (cycleCount % 37 == 0) {
                try {
                    val lastKey = HunterEngine.getLastKey()
                    if (lastKey.isNotEmpty() && lastKey != "0".repeat(64)) {
                        val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
                        val puzzleNum = currentPuzzleNum()
                        puzzlePrefs.edit()
                            .putString("last_key_$puzzleNum", lastKey)
                            .putLong("last_time_$puzzleNum", System.currentTimeMillis())
                            .apply()
                    }
                } catch (e: Exception) {}
            }
        }
        // Actualizar dataset status si está cargando
        if (HunterEngine.isLoading()) {
            val status = HunterEngine.getLoadStatus()
            tvCsvName?.text = status; tvCsvName?.setTextColor(AppTheme.CYAN)
        } else if (HunterEngine.isCsvLoaded()) {
            // La tarjeta DATASET sólo se refrescaba si csvPath seguía apuntando a
            // un fichero, así que tras reinstalar o mover el .bin mostraba "—"
            // con el dataset cargado y buscando. Lo que importa es lo que el
            // motor tiene en memoria, y eso lo da getCsvCount().
            if (csvPath.isNotEmpty()) {
                tvCsvName?.text = File(csvPath).name
                tvCsvName?.setTextColor(AppTheme.ACCENT)
            }
            val total = HunterEngine.getCsvCount()
            if (total > 0) {
                val fmt = if (total >= 1_000_000) "${"%.1f".format(total/1e6)}M"
                          else "${total/1000}K"
                tvDatasetStat?.text = fmt
                tvDatasetStat?.textSize = AppTheme.SP_FIGURE  // vuelve de "sin cargar"
                tvDatasetStat?.setTextColor(AppTheme.TXT_PRI)
            }
        } else {
            // Un "—" verde no dice nada, y aquí decía algo importante: sin
            // dataset, los modos BIP39 y RAW KEY no tienen contra qué comparar.
            tvDatasetStat?.text = "sin cargar"
            tvDatasetStat?.textSize = AppTheme.SP_BODY
            tvDatasetStat?.setTextColor(AppTheme.WARN)
        }
        } catch (e: Exception) {
            // vars no inicializadas aún
        }
    }

    /**
     * ¿Tiene el motor contra qué comparar?
     *
     * En C++, tanto worker_rawkey_fn como worker_bip39_fn deciden el acierto
     * con `if(g_has_target) ... else if(g_csv_loaded) ...`. Sin dirección
     * objetivo y sin dataset, esa condición no se cumple nunca: el escaneo
     * genera claves a toda velocidad y no puede encontrar nada jamás.
     *
     * startHunting() sólo bloquea el arranque en modo BIP39; los modos puzzle y
     * raw arrancan igual. El puzzle siempre lleva dirección objetivo, así que
     * el caso que quedaba suelto era RAW KEY sin dataset —justo lo que pasa
     * después de reinstalar, porque el .bin vive en getExternalFilesDir() y se
     * borra con la app, dejando csvPath apuntando a un fichero que ya no está.
     */
    /**
     * Pone el botón en el estado que corresponde al motor.
     *
     * El tag lleva [fondoStart, fondoStop]; se comprueba el texto antes de
     * tocar nada para no reasignar el drawable en cada ciclo de 800 ms.
     */
    private fun syncToggleButton(btn: Button?, running: Boolean, textoStart: String) {
        if (btn == null) return
        val deseado = if (running) s.stop else textoStart
        if (btn.text.toString() == deseado) return
        btn.text = deseado
        @Suppress("UNCHECKED_CAST")
        val bg = btn.tag as? Array<GradientDrawable> ?: return
        if (bg.size > 1) btn.background = if (running) bg[1] else bg[0]
        // Los dos fondos son sólidos, así que el texto tiene que cambiar con
        // ellos: antes se quedaba en gris claro y sobre el verde no se leía.
        btn.setTextColor(if (running) AppTheme.RED else AppTheme.BG_DEEP)
    }

    /** Dirección objetivo del puzzle, tal y como está en el campo. */
    private fun puzzleTargetAddr(): String = etTarget?.text?.toString()?.trim() ?: ""

    private fun engineHasSomethingToMatch(): Boolean =
        // El puzzle busca UNA dirección y no necesita dataset ninguno; el
        // escáner compara contra la lista. Antes esto era
        // `isCsvLoaded() || hasTarget()`, y como nadie llamaba nunca a
        // setTarget(), hasTarget() era siempre false: el puzzle acababa pidiendo
        // el dataset igual que el escáner.
        if (puzzleMode) puzzleTargetAddr().isNotEmpty()
        else            HunterEngine.isCsvLoaded()

    private fun doToggle(callerBtn: Button? = null) {
        try {
            // Entre pulsar STOP y que los workers mueran hay una ventana en la
            // que isRunning() sigue devolviendo true. Pulsar ahí hacía que el
            // botón de START ejecutase la rama de STOP: parecía que "a veces no
            // funciona". Mejor decirlo que fingir.
            if (HunterEngine.isStopping()) {
                Toast.makeText(this, "Deteniendo el escaneo anterior… espera un momento",
                    Toast.LENGTH_SHORT).show()
                return
            }
            // El dataset se carga en segundo plano: durante ese rato
            // isCsvLoaded() es false, y decir "sin dataset" sería mentira.
            if (!HunterEngine.isRunning() && HunterEngine.isLoading()) {
                Toast.makeText(this, "Cargando el dataset… ${HunterEngine.getLoadStatus()}",
                    Toast.LENGTH_SHORT).show()
                return
            }
            if (!HunterEngine.isRunning() && !engineHasSomethingToMatch()) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(if (puzzleMode) "Sin dirección objetivo" else "Sin dataset cargado")
                    .setMessage(
                        if (puzzleMode)
                            "El campo Target Address está vacío, así que no hay nada " +
                            "que buscar.\n\nSelecciona un puzzle en la lista de arriba " +
                            "o escribe una dirección."
                        else
                            "No hay ninguna lista de direcciones cargada, así que el " +
                            "motor no tendría con qué comparar: escanearía a toda " +
                            "velocidad sin poder encontrar nada.\n\nCarga el .bin con " +
                            "LOAD CSV.")
                    .setPositiveButton("Entendido", null)
                    .show()
                prefs.edit().putBoolean("scan_was_running", false).apply()
                return
            }
            if (HunterEngine.isRunning()) {
                HunterEngine.stopHunting()
                stopService(Intent(this, HunterService::class.java))
                prefs.edit().putBoolean("scan_was_running", false).apply()
                peakWps = 0.0
                avgWpsSum = 0.0
                avgWpsCount = 0
                peakLabel = ""
                tvPeakWps?.text = ""
                tvPeakWpsPuzzle?.text = ""
                paintScanState(false)
                // Guardar sesión en historial
                val sessionKeys = HunterEngine.getCount() - sessionStartCount
                val sessionDur = if (sessionStartTime > 0)
                    (System.currentTimeMillis() - sessionStartTime) / 1000 else 0
                val sessionKps = if (sessionDur > 0) sessionKeys / sessionDur.toDouble() else 0.0
                val sessionMode = if (puzzleMode) "PUZZLE" else "BIP39"
                StatsActivity.saveSession(this, sessionMode, sessionKeys,
                    HunterEngine.getFound(), sessionDur, sessionKps / 1000.0)
                // Marcar bloque como escaneado al detener
                if (puzzleMode) {
                    // El respaldo era puzzles[puzzleSpinner.selectedItemPosition],
                    // pero puzzleSpinner se fija a null ("no spinner in new
                    // design"), así que caía en puzzles[0] = #70: parar un scan
                    // del #80 apuntaba el bloque como escaneado en el #70.
                    val pNum = currentPuzzleNum()
                    if (pNum != 0) markBlockScanned(pNum)
                }
                val btn = activeToggleBtn
                if (btn != null) {
                    val bg = btn.tag as? Array<*>
                    btn.text = if (puzzleMode) "Iniciar puzzle" else s.start
                    btn.background = bg?.get(0) as? GradientDrawable
                }
                activeToggleBtn = null
            } else {
                if (!HunterEngine.isCsvLoaded() && !puzzleMode) {
                    Toast.makeText(this, "Load a dataset first (Config tab)", Toast.LENGTH_SHORT).show()
                    return
                }
                sessionStartTime = System.currentTimeMillis()
                sessionStartCount = HunterEngine.getCount()
                val threads: Int
                val cpu: Int
                if (puzzleMode) {
                    threads = (sbThreadsPuzzle?.progress ?: 3) + 1
                    cpu     = (sbCpuPuzzle?.progress ?: 70) + 10
                    if (etRangeStart != null && etRangeEnd != null) {
                        // Cargar checkpoint si existe
                        val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
                        val puzzleNum = currentPuzzleNum()
                        val savedKey = puzzlePrefs.getString("last_key_$puzzleNum", null)
                        val rangeEnd = etRangeEnd?.text.toString() ?: ""
                        // Elegir bloque no escaneado
                        val fullStart = etRangeStart?.text.toString()?.trim() ?: ""
                        if (fullStart.isEmpty()) {
                            Toast.makeText(this, "Error: rango no configurado", Toast.LENGTH_SHORT).show()
                            return
                        }
                        // Si el usuario pulsó "Saltar a un punto aleatorio",
                        // se arranca ahí en vez de sortear otro bloque.
                        val pend = pendingBlockIdx
                        val block = if (pend != null) {
                            currentBlockId = pend.toString()
                            blockRange(fullStart, rangeEnd, pend)
                        } else {
                            getNextUnscannedBlock(puzzleNum, fullStart, rangeEnd)
                        }
                        pendingBlockIdx = null
                        tvRandomJump?.text = "Saltar a un punto aleatorio del rango"
                        if (block != null) {
                            val (bStart, bEnd) = block
                            HunterEngine.setRange(bStart, bEnd)
                            currentRangeStart = bStart
                            currentRangeEnd = bEnd
                            setCurrentBlockLabel("Escaneando bloque",
                                java.math.BigInteger(currentBlockId))
                            tvPuzzleStatus?.setTextColor(AppTheme.CYAN)
                        } else if (savedKey != null && savedKey.isNotEmpty()) {
                            HunterEngine.setRange(savedKey, rangeEnd)
                            currentRangeStart = savedKey
                            currentRangeEnd = rangeEnd
                        } else {
                            HunterEngine.setRange(fullStart, rangeEnd)
                            currentRangeStart = fullStart
                            currentRangeEnd = rangeEnd
                        }
                    }
                } else {
                    threads = (sbThreads?.progress ?: 3) + 1
                    cpu     = (sbCpu?.progress ?: 70) + 10
                }
                // Fija la dirección objetivo antes de arrancar.
                //
                // setTarget() existía en el JNI y funcionaba, pero NADIE lo
                // llamaba: g_has_target era siempre 0. En puzzle_on_key el C++
                // hace `if(g_has_target) memcmp contra la dirección; else if
                // (g_csv_loaded) búsqueda en el dataset`, así que el modo puzzle
                // nunca comparaba contra su dirección — caía al dataset. O sea:
                // sólo encontraba algo si el .bin resultaba contener la
                // dirección del puzzle, y haciendo bloom+bsearch sobre 10.3M
                // entradas por clave en vez de un memcmp de 20 bytes.
                if (puzzleMode) {
                    val target = puzzleTargetAddr()
                    HunterEngine.setTarget(target)
                    if (!HunterEngine.hasTarget()) {
                        Toast.makeText(this,
                            "Dirección objetivo no válida: $target",
                            Toast.LENGTH_LONG).show()
                        return
                    }
                } else {
                    // Sin esto, tras haber tocado un puzzle el escáner heredaba
                    // su dirección y comparaba contra ella en lugar del dataset.
                    HunterEngine.setTarget("")
                }
                HunterEngine.setMode(if (puzzleMode) 1 else selectedScanMode)
                HunterEngine.startHunting(threads, cpu)

                // startHunting() en C++ vuelve sin hacer nada en varios casos
                // —ya corriendo, parada a medias, sin dataset en modo BIP39— y
                // no devuelve nada. Aquí se daba por hecho que había arrancado:
                // se guardaba scan_was_running, el botón pasaba a STOP y se
                // lanzaba el servicio. Resultado: el botón decía STOP con el
                // motor parado, y la siguiente pulsación volvía a intentar
                // arrancar. Se comprueba antes de tocar nada.
                if (!HunterEngine.isRunning()) {
                    Toast.makeText(this, "El motor no arrancó. Revisa el dataset y el modo.",
                        Toast.LENGTH_LONG).show()
                    prefs.edit().putBoolean("scan_was_running", false).apply()
                    return
                }

                val batchNow = HunterEngine.getBatchSize()
                Toast.makeText(this, "threads=$threads · cpu=$cpu% · batch=$batchNow",
                    Toast.LENGTH_SHORT).show()

                // Guardar estado para auto-reinicio
                prefs.edit()
                    .putBoolean("scan_was_running", true)
                    .putBoolean("scan_was_puzzle", puzzleMode)
                    .apply()
                activeToggleBtn = callerBtn
                @Suppress("UNCHECKED_CAST")
                val bg2 = callerBtn?.tag as? Array<GradientDrawable>
                callerBtn?.text = s.stop
                if (bg2 != null && bg2.size > 1) callerBtn?.background = bg2[1]

                try {
                    startForegroundService(Intent(this, HunterService::class.java))
                } catch (ex: Exception) {
                    try { startService(Intent(this, HunterService::class.java)) } catch (ex2: Exception) {}
                }


            }
        } catch (e: Exception) {
            val errMsg = "${e.javaClass.simpleName}: ${e.message}"
            Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
            java.io.File(filesDir, "crash_log.txt").appendText("\ndoToggle: $errMsg\n${e.stackTraceToString()}\n")
        }
    }

    private fun pickCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, 1001)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_IMPORT_PROGRESS && res == RESULT_OK) {
            data?.data?.let { processImportedProgress(it) }
            return
        }
        if (req == 1001 && res == RESULT_OK) {
            val uri = data?.data ?: return
            // Obtener nombre original para preservar extensión .bin o .csv
            val cursor = contentResolver.query(uri, null, null, null, null)
            val origName = cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst(); if (idx >= 0) it.getString(idx) else "dataset.csv"
            } ?: "dataset.csv"
            cursor?.close()
            val dest = File(getExternalFilesDir(null), origName)
            contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
            csvPath = dest.absolutePath
            prefs.edit().putString("csvPath", csvPath).apply()
            HunterEngine.loadCsv(csvPath)
            tvStatus?.text = dest.name
            tvCsvName?.text = dest.name
            tvCsvName?.setTextColor(AppTheme.ACCENT)
            tvQuickCsv?.text = dest.nameWithoutExtension.take(7)
            val hashes = dest.length() / 20
            // Aquí se escribía "📦 nombre · N hashes · N MB" en la misma tarjeta
            // que luego muestra el ritmo en claves/día: dos significados en un
            // solo hueco, y el segundo pisaba al primero en cuanto arrancaba el
            // scan. El nombre ya está en tvCsvName y el recuento en DATASET.
            // Actualizar stat card con conteo de hashes
            tvDatasetStat?.text = if (hashes >= 1_000_000) "${"%.1f".format(hashes/1e6)}M" else "${hashes/1000}K"
            tvDatasetStat?.textSize = AppTheme.SP_FIGURE
            tvDatasetStat?.setTextColor(AppTheme.TXT_PRI)
            Toast.makeText(this, "Dataset cargado: ${dest.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportPuzzleProgress() {
        try {
            val prefs = getBlockPrefs()
            val json = org.json.JSONObject()
            json.put("version", 1)
            json.put("exported", System.currentTimeMillis())
            json.put("device", android.os.Build.MODEL)
            val puzzlesJson = org.json.JSONObject()
            puzzles.forEach { p ->
                val scanned = prefs.getStringSet("scanned_${p.num}", emptySet()) ?: emptySet()
                if (scanned.isNotEmpty()) {
                    val arr = org.json.JSONArray()
                    scanned.forEach { arr.put(it) }
                    puzzlesJson.put("puzzle_${p.num}", arr)
                }
            }
            json.put("puzzles", puzzlesJson)
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(getExternalFilesDir(null), "wh_progress_$ts.json")
            file.writeText(json.toString(2))
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Progress")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, "Exportar progreso"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun importPuzzleProgress() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQ_IMPORT_PROGRESS)
    }

    private fun processImportedProgress(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val json = org.json.JSONObject(text)
            val puzzlesJson = json.optJSONObject("puzzles") ?: run {
                android.widget.Toast.makeText(this, "Formato inválido", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val prefs = getBlockPrefs()
            val editor = prefs.edit()
            var totalImported = 0
            puzzles.forEach { p ->
                val arr = puzzlesJson.optJSONArray("puzzle_${p.num}") ?: return@forEach
                val existing = prefs.getStringSet("scanned_${p.num}", emptySet())?.toMutableSet() ?: mutableSetOf()
                val before = existing.size
                for (i in 0 until arr.length()) existing.add(arr.getString(i))
                editor.putStringSet("scanned_${p.num}", existing)
                totalImported += existing.size - before
            }
            editor.apply()
            android.widget.Toast.makeText(this,
                "Progreso importado: $totalImported bloques nuevos",
                android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error importando: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Baúl de hallazgos: lo que han encontrado el puzzle y el escáner.
     *
     * Llega aquí tras PIN. Las claves se muestran tapadas y sólo se revelan al
     * pulsar una entrada: la pantalla puede quedar a la vista de cualquiera, y
     * quien vea un WIF se lleva el saldo.
     */
    private fun showVault() {
        MatchVault.ingestPlaintextFile(this)
        val entries = MatchVault.list(this)
        if (entries.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Baúl vacío")
                .setMessage("Todavía no hay hallazgos. Cuando el puzzle o el escáner " +
                            "encuentren una clave se guardará aquí cifrada, y entrará " +
                            "en el backup.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val fmt = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.US)
        val items = entries.map { e ->
            val etiqueta = when (e.source) {
                "puzzle"   -> "Puzzle"
                "scanner"  -> "Escáner"
                "recovery" -> "Recovery"
                else       -> e.source
            }
            // Un "0.00000000 BTC" a secas se lee como "vacía", cuando puede ser
            // sólo que aún no se ha preguntado a la cadena.
            val saldo = if (e.checkedTs == 0L) "saldo sin consultar"
                        else "${"%.8f".format(e.btc)} BTC"
            "$etiqueta · ${fmt.format(java.util.Date(e.ts))}\n${e.addr}\n$saldo"
        }.toTypedArray()

        val pendientes = entries.count { it.checkedTs == 0L }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Baúl · ${entries.size} hallazgo(s)")
            .setItems(items) { _, which -> showVaultEntry(entries[which]) }
            .setPositiveButton(
                if (pendientes > 0) "Consultar saldos ($pendientes)" else "Refrescar saldos"
            ) { _, _ -> resolveVaultBalances() }
            .setNeutralButton("Copia de seguridad") { _, _ -> exportEncryptedBackup() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Pregunta a la cadena por el saldo de los hallazgos sin comprobar.
     *
     * Consultar una dirección se la revela al servidor: para las del puzzle da
     * casi igual —están vigiladas por medio mundo—, pero es una acción del
     * usuario, no algo que la app deba hacer a sus espaldas.
     */
    private fun resolveVaultBalances() {
        android.widget.Toast.makeText(this, "Consultando saldos…",
            android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val n = try { MatchVault.resolvePendingBalances(this) } catch (e: Exception) { 0 }
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    if (n > 0) "$n saldo(s) actualizados"
                    else "Ninguna fuente respondió — inténtalo más tarde",
                    android.widget.Toast.LENGTH_SHORT).show()
                if (n > 0) showVault()
            }
        }.start()
    }

    private fun showVaultEntry(e: MatchVault.Entry) {
        val detalle = buildString {
            appendLine("Origen: ${e.source}")
            appendLine("Fecha: ${java.util.Date(e.ts)}")
            appendLine()
            appendLine("Dirección:")
            appendLine(e.addr)
            appendLine()
            if (e.checkedTs == 0L) {
                appendLine("Saldo: sin consultar todavía")
            } else {
                appendLine("Saldo: ${"%.8f".format(e.btc)} BTC")
                appendLine("Consultado: ${java.util.Date(e.checkedTs)}")
            }
            if (e.extra.contains("SEED:")) {
                appendLine()
                appendLine("Seed: " + (Regex("""SEED:(.+?)\s+PATH:""")
                    .find(e.extra)?.groupValues?.get(1) ?: "—"))
                appendLine("Ruta: " + (Regex("""PATH:(\S+)""")
                    .find(e.extra)?.groupValues?.get(1) ?: "—"))
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hallazgo")
            .setMessage(detalle)
            .setPositiveButton("Copiar WIF") { _, _ ->
                if (e.wif.isEmpty()) {
                    android.widget.Toast.makeText(this, "Esta entrada no tiene WIF",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("wif", e.wif))
                    android.widget.Toast.makeText(this,
                        "WIF copiado — pégalo y borra el portapapeles",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Copiar HEX") { _, _ ->
                if (e.privHex.isEmpty()) {
                    android.widget.Toast.makeText(this, "Esta entrada no tiene clave hex",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("hex", e.privHex))
                    android.widget.Toast.makeText(this, "Clave hex copiada",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Crea una copia de seguridad. Lleva al baúl de copias de WalletActivity.
     *
     * Antes esto era un exportador aparte: cifraba sólo los hallazgos con
     * encryptData(), escribía wh_backup_<fecha>.enc en almacenamiento externo y
     * lo lanzaba al selector de compartir. Ese fichero no se podía restaurar —
     * decryptData() existe pero no lo llamaba nadie, así que el formato era de
     * ida—. Los hallazgos van ahora dentro de la copia normal (campo "matches"),
     * que sí tiene importador, así que no hacen falta dos mecanismos.
     */
    private fun exportEncryptedBackup() {
        MatchVault.ingestPlaintextFile(this)
        startActivity(Intent(this, WalletActivity::class.java).apply {
            putExtra("MODE", "seed")
            putExtra("OPEN_BACKUP_VAULT", true)
        })
    }

    /**
     * Exporta un resumen de los hallazgos para compartir.
     *
     * Iba sin PIN, al contrario que los dos botones de debajo, y metía dentro
     * los últimos 2 KB de crash_log.txt: el handler guarda e.message y el stack
     * trace tal cual, y el mensaje de una excepción suele arrastrar el dato que
     * la provocó. Ese fichero sale por ACTION_SEND hacia mensajería o correo.
     *
     * Ahora pide PIN, el registro de fallos es opt-in explícito y lo que se
     * incluye va con las cadenas que parecen clave tapadas.
     */
    private fun exportLog() {
        if (!PinAuthHelper.isSessionValid()) {
            // Sin huella automática: esto exporta un resumen sin claves privadas,
            // no vale interrumpir con el lector. El teclado sale directo y la
            // tecla ◉ sigue ahí para quien prefiera la huella.
            PinAuthHelper.show(this, autoBiometric = false) { ok -> if (ok) askExportLogOptions() }
        } else {
            askExportLogOptions()
        }
    }

    private fun askExportLogOptions() {
        val crashLog = File(filesDir, "crash_log.txt")
        if (!crashLog.exists() || crashLog.length() == 0L) { writeAndShareLog(false); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¿Incluir el registro de fallos?")
            .setMessage("Hay un registro de fallos guardado. Ayuda a diagnosticar " +
                        "problemas, pero un error puede llevar dentro el dato que lo " +
                        "causó. El fichero se comparte por mensajería o correo.")
            .setPositiveButton("Sin el registro") { _, _ -> writeAndShareLog(false) }
            .setNeutralButton("Incluirlo") { _, _ -> writeAndShareLog(true) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Tapa lo que tenga forma de clave privada: 64 hex seguidos, WIF
     * (5/K/L + Base58) y claves extendidas. Es una red de seguridad sobre el
     * registro de fallos, no una garantía — por eso incluirlo se pregunta.
     */
    private fun redactSecrets(text: String): String =
        text.replace(Regex("""\b[0-9a-fA-F]{64}\b"""), "[hex-oculto]")
            .replace(Regex("""\b[5KL][1-9A-HJ-NP-Za-km-z]{50,51}\b"""), "[wif-oculto]")
            .replace(Regex("""\b(xprv|yprv|zprv|tprv)[1-9A-HJ-NP-Za-km-z]{50,}"""), "[xprv-oculto]")

    private fun writeAndShareLog(includeCrashLog: Boolean) {
        val dir = getExternalFilesDir(null) ?: filesDir
        // Los exports anteriores se quedaban ahí para siempre. En Android 8 y 9
        // este directorio lo lee cualquier app con READ_EXTERNAL_STORAGE.
        dir.listFiles()?.filter { it.name.startsWith("wallet_hunter_export_") }
            ?.forEach { it.delete() }

        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(dir, "wallet_hunter_export_$ts.txt")
        val sb = StringBuilder()
        sb.appendLine("=== WALLET HUNTER EXPORT ===")
        sb.appendLine("Fecha: ${java.util.Date()}")
        sb.appendLine("Dispositivo: ${android.os.Build.MODEL}")
        sb.appendLine()

        // Los hallazgos van SIN claves privadas: para llevarse las claves está la
        // copia de seguridad, que cifra con el PIN.
        MatchVault.ingestPlaintextFile(this)
        val hallazgos = MatchVault.list(this)
        if (hallazgos.isNotEmpty()) {
            sb.appendLine("=== MATCHES ENCONTRADOS ===")
            sb.appendLine("(claves privadas omitidas — usa la copia de seguridad)")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            hallazgos.forEach { e ->
                sb.appendLine("[${fmt.format(java.util.Date(e.ts))}] ${e.source}  " +
                              "ADDR:${e.addr}  BTC:${"%.8f".format(e.btc)}")
            }
        } else {
            sb.appendLine("=== SIN MATCHES AÚN ===")
        }

        if (includeCrashLog) {
            val crashLog = File(filesDir, "crash_log.txt")
            if (crashLog.exists()) {
                sb.appendLine()
                sb.appendLine("=== CRASH LOG ===")
                sb.appendLine(redactSecrets(crashLog.readText().takeLast(4000)))
            }
        }

        f.writeText(sb.toString())

        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", f
        )
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Export")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, "Exportar log"))
        Toast.makeText(this, "Log exportado: ${f.name}", Toast.LENGTH_SHORT).show()
    }


    /**
     * Saldo de la dirección de un puzzle.
     *
     * Iba sólo por Electrum y, peor, cuando Electrum no respondía hacía
     * `bal?.confirmed ?: 0L` y devolvía CERO. Quien llama trata el cero como
     * "sin fondos" y oculta el puzzle de la lista de forma permanente, así que
     * un rato sin cobertura te borraba puzzles que sí tienen premio.
     *
     * @param onResult saldo en satoshis, o -1 si no respondió nadie. Los dos
     *   casos NO son lo mismo y quien llama tiene que distinguirlos.
     */
    /**
     * Mira si la dirección del puzzle ha revelado alguna vez su clave pública,
     * y traduce el resultado a lo único que importa: cuánto se tardaría.
     *
     * Los dos números salen del tamaño del rango, que es 2^(n-1) claves para el
     * puzzle n. La fuerza bruta las recorre todas; Kangaroo necesita del orden
     * de la raíz cuadrada, unas 2,2 veces.
     */
    private fun comprobarAtajo(p: PuzzleInfo) {
        val tv = tvPuzzleAtajo ?: return
        tv.text = "Comprobando si la clave pública está publicada…"
        tv.setTextColor(AppTheme.TXT_SEC)
        Thread {
            val r = try { PubKeyFinder.buscar(this@MainActivity, p.addr, false) }
                    catch (e: Exception) { PubKeyFinder.Resultado.SinRed }
            // Ritmo medido del propio motor si está corriendo; si no, un valor
            // del orden del que da este móvil, para no prometer de más.
            val ritmo = HunterEngine.getWps().takeIf { it > 1000 } ?: 4_000_000.0
            val bits = (p.num - 1).coerceAtLeast(1)
            val clavesBrutas = Math.pow(2.0, bits.toDouble())
            val opsKangaroo = 2.2 * Math.pow(2.0, bits / 2.0)
            fun humano(segundos: Double): String = when {
                segundos < 90            -> "${segundos.toInt()} segundos"
                segundos < 5400          -> "${(segundos / 60).toInt()} minutos"
                segundos < 172_800       -> "${(segundos / 3600).toInt()} horas"
                segundos < 63_072_000    -> "${(segundos / 86_400).toInt()} días"
                segundos < 3.15e10       -> "${(segundos / 3.15e7).toInt()} años"
                segundos < 3.15e13       -> "%.0f mil años".format(segundos / 3.15e10)
                else                     -> "%.0f millones de años".format(segundos / 3.15e13)
            }
            runOnUiThread {
                puzzleIniHex = p.start; puzzleFinHex = p.end
                puzzlePubHex = (r as? PubKeyFinder.Resultado.Encontrada)?.pubHex ?: ""
                btnKangaroo?.visibility =
                    if (puzzlePubHex.length == 66) android.view.View.VISIBLE
                    else android.view.View.GONE
                when (r) {
                    is PubKeyFinder.Resultado.Encontrada -> {
                        tv.text = "Clave pública publicada — admite Kangaroo.\n" +
                                  "Fuerza bruta: ${humano(clavesBrutas / ritmo)}. " +
                                  "Con Kangaroo: ${humano(opsKangaroo / ritmo)}."
                        tv.setTextColor(AppTheme.ACCENT)
                    }
                    PubKeyFinder.Resultado.NoRevelada -> {
                        tv.text = "Esta dirección no ha gastado nunca, así que su clave " +
                                  "pública no es conocida. No hay atajo: sólo fuerza " +
                                  "bruta, ${humano(clavesBrutas / ritmo)} a este ritmo."
                        tv.setTextColor(AppTheme.WARN)
                    }
                    is PubKeyFinder.Resultado.Publicada -> {
                        // Ha gastado, o sea que la clave ESTÁ publicada; lo que
                        // no se ha podido es dar con la transacción. Decir "no
                        // hay atajo" aquí sería mentir.
                        tv.text = "Esta dirección ha gastado ${r.gastos} vez/veces, así que " +
                                  "su clave pública está publicada — pero no se ha " +
                                  "encontrado en el historial reciente. Vuelve a " +
                                  "intentarlo; el atajo existe."
                        tv.setTextColor(AppTheme.TXT_SEC)
                    }
                    PubKeyFinder.Resultado.SinRed -> {
                        tv.text = "No se pudo comprobar si la clave pública está publicada."
                        tv.setTextColor(AppTheme.TXT_SEC)
                    }
                }
            }
        }.start()
    }

    /**
     * Pregunta a la cadena, puzzle por puzzle, si alguno ha revelado su clave
     * pública.
     *
     * Kangaroo sólo sirve con la clave pública, y ésta sólo aparece cuando la
     * dirección GASTA. Los puzzles sin resolver nunca han gastado — por eso
     * siguen sin resolver — así que hoy previsiblemente no hay ninguno. Pero
     * eso es una afirmación sobre el mundo, y conviene comprobarla contra la
     * cadena en vez de darla por buena:
     *
     *  - Si mañana alguien gasta desde una de esas direcciones, deja de ser
     *    cierta en ese instante.
     *  - Y si resulta que alguna sí la tiene, es justo la que hay que atacar.
     *
     * Una consulta por puzzle, la barata: /address/{addr} y mirar cuántas veces
     * ha gastado. Las respuestas quedan en caché, así que repetirlo es gratis.
     */
    private fun auditarClavesPublicas() {
        val lista = puzzles.filter {
            BtcAddress.validate(it.addr, false) is BtcAddress.Result.Valid
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Buscando claves públicas")
            .setMessage("Consultando ${lista.size} direcciones…")
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            val con = mutableListOf<Pair<Int, String>>()
            var sin = 0; var sinRed = 0; var publicadas = 0
            for ((i, p) in lista.withIndex()) {
                if (isFinishing || isDestroyed) return@Thread
                when (val r = try { PubKeyFinder.buscar(this, p.addr, false) }
                              catch (e: Exception) { PubKeyFinder.Resultado.SinRed }) {
                    is PubKeyFinder.Resultado.Encontrada -> con.add(p.num to r.pubHex)
                    PubKeyFinder.Resultado.NoRevelada    -> sin++
                    is PubKeyFinder.Resultado.Publicada  -> publicadas++
                    PubKeyFinder.Resultado.SinRed        -> {
                        sinRed++
                        // Si la cadena no contesta, seguir preguntando 77 veces
                        // es gastar minutos para no saber nada. Tres seguidas
                        // basta para concluir que no hay red.
                        if (sinRed >= 3) break
                    }
                }
                runOnUiThread {
                    dlg.setMessage("Consultando… ${i + 1} de ${lista.size}")
                }
            }
            runOnUiThread {
                dlg.dismiss()
                val texto = buildString {
                    if (con.isEmpty() && publicadas == 0) {
                        append("Ninguno de los ${lista.size} ha revelado su clave ")
                        append("pública: ninguno ha gastado nunca.\n\n")
                        append("Kangaroo no sirve para ninguno. La única vía es ")
                        append("fuerza bruta, y para estos rangos eso son ")
                        append("millones de años.\n\n")
                        append("Si algún día alguien gasta desde una de esas ")
                        append("direcciones, la clave quedará publicada y ")
                        append("aparecerá aquí.")
                    } else {
                        if (con.isNotEmpty()) {
                            append("Admiten Kangaroo:\n")
                            con.forEach { (n, pk) -> append("  #$n  ${pk.take(20)}…\n") }
                            append("\n")
                        }
                        if (publicadas > 0)
                            append("$publicadas han gastado —o sea, su clave está " +
                                   "publicada— pero no se encontró en el historial " +
                                   "reciente.\n\n")
                        append("$sin sin revelar.")
                    }
                    if (sinRed > 0) append("\n\nNo se pudo consultar: sin conexión.")
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Claves públicas")
                    .setMessage(texto)
                    .setPositiveButton("Entendido", null)
                    .show()
            }
        }.start()
    }

    /** Arranca o para la búsqueda por Kangaroo del puzzle elegido. */
    private fun alternarKangaroo() {
        if (HunterEngine.kangarooRunning()) {
            // kangarooStop() guarda antes de liberar: parar no tira el trabajo.
            HunterEngine.kangarooStop()
            prefs.edit().putBoolean("kangaroo_corriendo", false).apply()
            btnKangaroo?.text = "Buscar con Kangaroo"
            tvPuzzleAtajo?.text = "Detenida. El trabajo queda guardado; " +
                                  "al volver a darle sigue desde ahí."
            tvPuzzleAtajo?.setTextColor(AppTheme.TXT_SEC)
            return
        }
        if (puzzlePubHex.length != 66) return
        // Un hilo por núcleo menos uno, para que el móvil siga respondiendo.
        val hilos = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
        // Un fichero por puzzle: la clave pública lo identifica sin ambigüedad
        // y así cambiar de puzzle y volver no pierde nada.
        val ruta = java.io.File(filesDir, "kangaroo_${puzzlePubHex.take(16)}.dat").absolutePath
        val ok = try {
            HunterEngine.kangarooStart(puzzlePubHex, puzzleIniHex, puzzleFinHex,
                                       hilos, 512, ruta)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "kangarooStart: ${e.message}", e); false
        }
        if (!ok) {
            tvPuzzleAtajo?.text = "No se pudo arrancar la búsqueda."
            tvPuzzleAtajo?.setTextColor(AppTheme.RED)
            return
        }
        btnKangaroo?.text = "Detener Kangaroo"
        tvPuzzleAtajo?.text = "Buscando con Kangaroo en $hilos hilos…"
        tvPuzzleAtajo?.setTextColor(AppTheme.ACCENT)
        // El watchdog lo relanza si Android se lo lleva por delante. Con el
        // trabajo guardado, relanzar continúa donde estaba en vez de empezar.
        prefs.edit().putBoolean("kangaroo_corriendo", true)
            .putString("kangaroo_pub", puzzlePubHex)
            .putString("kangaroo_ini", puzzleIniHex)
            .putString("kangaroo_fin", puzzleFinHex).apply()
    }

    /** Se llama desde updateUI(): progreso y resultado. */
    private fun refrescarKangaroo() {
        val tv = tvPuzzleAtajo ?: return
        val clave = try { HunterEngine.kangarooResult() } catch (e: Throwable) { "" }
        if (clave.length == 64) {
            // Guardar ANTES de tocar la interfaz: si la app muere aquí, la
            // clave no puede perderse.
            try {
                MatchVault.add(this, MatchVault.Entry(
                    ts = System.currentTimeMillis(), source = "kangaroo",
                    addr = "", wif = "", privHex = clave, btc = 0.0,
                    extra = "PUZZLE kangaroo", checkedTs = 0L))
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "no se pudo guardar: ${e.message}", e)
            }
            HunterEngine.kangarooStop()
            prefs.edit().putBoolean("kangaroo_corriendo", false).apply()
            btnKangaroo?.text = "Buscar con Kangaroo"
            tv.text = "CLAVE ENCONTRADA\n$clave\nGuardada en el baúl de hallazgos."
            tv.setTextColor(AppTheme.ACCENT)
            return
        }
        if (!HunterEngine.kangarooRunning()) {
            // ── WATCHDOG DE KANGAROO ──────────────────────────────────────
            // El del escáner ya cubre el puzzle por fuerza bruta, porque
            // doToggle es el mismo para los dos. Kangaroo corre en sus propios
            // hilos y no pasa por ahí: si Android se los lleva, nadie los
            // relanzaba. Con el trabajo guardado, relanzar continúa.
            if (watchdogEnabled && prefs.getBoolean("kangaroo_corriendo", false)
                && puzzlePubHex.length == 66) {
                kangarooReinicios++
                alternarKangaroo()
            }
            return
        }
        val ops = try { HunterEngine.kangarooOps() } catch (e: Throwable) { 0L }
        val dps = try { HunterEngine.kangarooPoints() } catch (e: Throwable) { 0L }
        // Guardar cada pocos minutos: si el sistema mata la app no hay ocasión
        // de guardar al parar, y se perdería todo lo de esta sesión.
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoGuardadoKg > 120_000L) {
            ultimoGuardadoKg = ahora
            Thread { try { HunterEngine.kangarooSave() } catch (e: Throwable) {} }.start()
        }
        tv.text = "Buscando con Kangaroo · ${numberFmt.format(ops)} operaciones · " +
                  "${numberFmt.format(dps)} puntos guardados" +
                  (if (kangarooReinicios > 0) " · $kangarooReinicios reinicios" else "")
    }

    private fun checkPuzzleBalance(addr: String, onResult: (Long) -> Unit) {
        Thread {
            val r = try { BalanceLookup.query(addr, false) } catch (e: Exception) { null }
            runOnUiThread { onResult(r?.sat ?: -1L) }
        }.start()
    }

    /**
     * Arranca el master del cluster con el puzzle que está seleccionado.
     *
     * El botón llamaba a startMaster(..., 71, "400000000000000000",
     * "7fffffffffffffffff"): el puzzle 71 fijo en el código, sin relación con el
     * que tuvieras elegido. Si estabas con el #70, los workers recibían bloques
     * del rango del #71 y buscaban donde no estaba la clave — repartiendo
     * trabajo inútil sin que nada lo indicara. applyPuzzle() ya deja el rango en
     * prefs, así que se lee de ahí.
     *
     * Además muestra el código de acceso en un diálogo: lo generaba
     * startMaster() y sólo aparecía en un log de cinco líneas, así que el master
     * quedaba escuchando en 0.0.0.0:7771 sin que supieras el código que hay que
     * dar a los workers.
     */
    private fun startClusterMaster() {
        val pnum  = prefs.getInt("current_puzzle_num", 0)
        val start = prefs.getString("current_range_start", "") ?: ""
        val end   = prefs.getString("current_range_end", "") ?: ""
        if (pnum == 0 || start.isEmpty() || end.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sin puzzle seleccionado")
                .setMessage("Elige un puzzle en la pestaña Puzzle antes de arrancar " +
                            "el master: es el rango que se reparte entre los dispositivos.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        NetworkManager.startMaster(this, pnum, start, end)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Master activo — Puzzle #$pnum")
            .setMessage("Código de acceso:\n\n${NetworkManager.authToken}\n\n" +
                        "Introdúcelo en cada worker. Sin él el master rechaza la " +
                        "conexión.\n\nEscucha en el puerto ${NetworkManager.TCP_PORT} " +
                        "de esta red. Úsalo sólo en una red de confianza.")
            .setPositiveButton("Copiar código") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText(
                        "cluster", NetworkManager.authToken))
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun applyPuzzle(p: PuzzleInfo) {
        etRangeStart?.setText(p.start)
        etRangeEnd?.setText(p.end)
        currentRangeStart = p.start
        currentRangeEnd = p.end
        puzzleFullStart = p.start
        puzzleFullEnd = p.end
        puzzleProgressUpdater?.invoke(p.num, p.start, p.end)
        etTarget?.setText(p.addr)
        // p.btc ya viene con la unidad dentro ("7.9 BTC"), así que añadirla
        // otra vez daba "7.9 BTC BTC".
        tvPuzzleStatus?.text = "Puzzle #${p.num} — ${p.btc}"
        // El bloque que hubiera era de otro rango: su porcentaje aquí no vale.
        pendingBlockIdx = null
        currentBlockId = ""
        tvCurrentBlock?.text = "Bloque actual: —"
        tvRandomJump?.text = "Saltar a un punto aleatorio del rango"
        // Guardar rango para modo distribuido
        prefs.edit()
            .putString("current_range_start", p.start)
            .putString("current_range_end", p.end)
            .putInt("current_puzzle_num", p.num)
            .apply()
        // Resetear contadores al cambiar puzzle
        sessionStartTime = 0L
        sessionStartCount = 0L
        tvCountPuzzle?.text = "0"
        tvTimePuzzle?.text  = "00:00:00"
        tvPctPuzzle?.text   = "—"
        tvBlockProgress?.text = "—"
        // No llamar setRange durante construcción — solo cuando engine está corriendo
    }




    // ── Export / Import Configuración ────────────────────────────────────────
    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal normal
            val ch = android.app.NotificationChannel(
                "hunter", "Hunter", android.app.NotificationManager.IMPORTANCE_LOW
            )
            // Canal de match — alta prioridad con sonido
            val matchCh = android.app.NotificationChannel(
                "hunter_match", "Match Found!",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                enableLights(true)
                lightColor = AppTheme.AMBER
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(ch)
            nm.createNotificationChannel(matchCh)
        }
    }

    private fun sendMatchNotification(addr: String, wif: String) {
        try {
            // Vibración
            val vib = getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 200, 500, 200, 500), -1
                ))
            }
            // Notificación
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val intent = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = androidx.core.app.NotificationCompat.Builder(this, "hunter_match")
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("Coincidencia encontrada")
                .setContentText("Addr: ${addr.take(20)}...")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText("Dirección: $addr\nWIF: $wif"))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .setColor(AppTheme.AMBER)
                .build()
            nm.notify(NOTIF_ID, notif)
        } catch (e: Exception) {}
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                val pct = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                tvBattery?.text = "BAT $pct%"
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private var appPausedTime = 0L
    private var thermalThrottleEnabled = true
    private var lastThermalCheck = 0L
    private var originalCpuLimit = 70
    private var isThrottled = false
    private var tvThermal: TextView? = null
    private val LOCK_TIMEOUT_MS = 15_000L // 15 seg en background

    override fun onResume() {
        super.onResume()
        handler.post(updater)
        val elapsed = System.currentTimeMillis() - appPausedTime
        if (appPausedTime > 0 && elapsed > LOCK_TIMEOUT_MS && WalletManager.hasPin(this)) {
            PinAuthHelper.show(this) { ok -> if (!ok) finish() }
        }
        // Auto-reinicio: si el engine estaba corriendo pero el servicio fue matado
        checkAndRestartScan()
    }

    private fun checkAndRestartScan() {
        val wasRunning = prefs.getBoolean("scan_was_running", false)
        if (!wasRunning) return
        if (HunterEngine.isRunning()) return // ya está corriendo

        // El scan estaba activo pero fue matado — preguntar si reiniciar
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (HunterEngine.isRunning()) return@postDelayed // doble check
            val mode = if (prefs.getBoolean("scan_was_puzzle", false)) "Puzzle" else "BIP39"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Scan interrumpido")
                .setMessage("El scan en modo $mode fue interrumpido. ¿Reiniciar?")
                .setPositiveButton("Reiniciar") { _, _ ->
                    if (prefs.getBoolean("scan_was_puzzle", false)) {
                        puzzleMode = true
                        HunterEngine.setMode(1)
                    } else {
                        puzzleMode = false
                        HunterEngine.setMode(0)
                    }
                    doToggle(if (puzzleMode) btnPuzzleToggle else btnToggle)
                }
                .setNegativeButton("No") { _, _ ->
                    prefs.edit().putBoolean("scan_was_running", false).apply()
                }
                .show()
        }, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updater)
        appPausedTime = System.currentTimeMillis()
        savePuzzleCheckpoint()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePuzzleCheckpoint()
        batteryReceiver?.let { unregisterReceiver(it) }
    }

    /** Fichero de matches, ahora siempre en almacenamiento interno. */
    private fun matchesFile() = java.io.File(filesDir, "coincidencias.txt")

    /**
     * Traslada el coincidencias.txt que las versiones anteriores dejaron en
     * almacenamiento externo. Contiene claves privadas en claro, así que se
     * concatena al interno y se borra el original.
     */
    private fun migrateLegacyMatchFile() {
        try {
            val ext = getExternalFilesDir(null) ?: return
            val old = java.io.File(ext, "coincidencias.txt")
            if (!old.exists()) return
            matchesFile().appendText(old.readText())
            old.delete()
            android.util.Log.i("MainActivity", "coincidencias.txt migrado a interno")
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "migrate matches: ${e.message}")
        }
    }

    /**
     * Versiones anteriores volcaban la seed recuperada en claro a
     * getExternalFilesDir()/recovery_<ts>.txt. Actualizar la app no borra esos
     * ficheros, así que se eliminan aquí en cuanto se ejecuta un recovery.
     */
    private fun purgeLegacyRecoveryFiles() {
        try {
            getExternalFilesDir(null)
                ?.listFiles { f -> f.name.startsWith("recovery_") && f.name.endsWith(".txt") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "purge recovery files: ${e.message}")
        }
    }

    private fun savePuzzleCheckpoint() {
        if (!puzzleMode) return
        try {
            val lastKey = HunterEngine.getLastKey()
            if (lastKey.isEmpty() || lastKey == "0".repeat(64)) return
            val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
            // Mismo fallo: el checkpoint de cualquier puzzle se guardaba bajo
            // el #70, así que al volver al puzzle real se reanudaba desde una
            // clave de otro rango — o desde el principio.
            val puzzleNum = currentPuzzleNum()
            if (puzzleNum == 0) return
            puzzlePrefs.edit()
                .putString("last_key_$puzzleNum", lastKey)
                .putLong("last_time_$puzzleNum", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }


}
