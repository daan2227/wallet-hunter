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

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    // ── Tema ──────────────────────────────────────────────────────────────────
    private val BG_DEEP   get() = AppTheme.BG_DEEP
    private val BG_CARD   get() = AppTheme.BG_CARD
    private val BG_ELEV   get() = AppTheme.BG_ELEV
    private val AMBER     get() = AppTheme.AMBER
    private val RED       get() = AppTheme.RED
    private val CYAN      get() = AppTheme.CYAN
    private val TXT_PRI   get() = AppTheme.TXT_PRI
    private val TXT_SEC   get() = AppTheme.TXT_SEC
    private val TXT_MUTED get() = AppTheme.TXT_MUTED
    private val BORDER_C  get() = AppTheme.BORDER_C
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── Páginas y barra de pestañas ─────────────────────────────────────────
    //
    // Posiciones FIJAS. Antes la lista se hacía con listOfNotNull(...), así que
    // si una página no se podía montar —Puzzle tiene su propio try/catch para
    // eso— las de detrás se corrían un puesto: tocar "Wallet" enseñaba
    // Recovery. Ahora una página que falta deja su hueco y las demás siguen
    // donde estaban.
    private val PAG_SCANNER  = 0
    private val PAG_PUZZLE   = 1
    private val PAG_WALLET   = 2
    private val PAG_RECOVERY = 3
    private val PAG_MORE     = 4
    private var tabPages: List<android.view.View?> = emptyList()
    private var barra: BottomBar? = null
    private var paginaActual = PAG_SCANNER

    private fun goTab(idx: Int) {
        tabPages.forEachIndexed { i, v ->
            v?.visibility = if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
        }
        paginaActual = idx
        // Recovery no tiene pestaña propia: se entra desde More, así que es
        // More la que se queda marcada. Si no, la barra no marcaría ninguna y
        // no se sabría dónde se está.
        barra?.seleccionar(when (idx) {
            PAG_PUZZLE -> BottomBar.PUZZLE
            PAG_WALLET -> BottomBar.WALLET
            PAG_RECOVERY, PAG_MORE -> BottomBar.MORE
            else -> BottomBar.SCANNER
        })
    }

    /**
     * Lo que hace tocar una pestaña.
     *
     * Wallet sigue pidiendo el PIN si la sesión está cerrada, igual que lo
     * pedía desde el menú. Cluster no es una página de aquí sino su propia
     * pantalla, NetworkActivity, que lleva la misma barra con Cluster marcado:
     * se abre sin animación para que se sienta como cambiar de pestaña y no
     * como entrar en otra sección.
     */
    private fun pestana(tab: Int) {
        when (tab) {
            BottomBar.SCANNER -> goTab(PAG_SCANNER)
            BottomBar.PUZZLE  -> goTab(PAG_PUZZLE)
            BottomBar.WALLET  -> {
                if (WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid())
                    PinAuthHelper.show(this) { ok -> if (ok) goTab(PAG_WALLET) }
                else goTab(PAG_WALLET)
            }
            BottomBar.CLUSTER -> {
                startActivity(Intent(this, NetworkActivity::class.java))
                overridePendingTransition(0, 0)
            }
            BottomBar.MORE -> goTab(PAG_MORE)
        }
    }

    /**
     * Otra pantalla ha pedido una pestaña: NetworkActivity, cuando desde
     * Cluster se toca Scanner, Puzzle, Wallet o More. Pasa por [pestana] para
     * que Wallet pida el PIN igual que si se hubiera tocado aquí.
     *
     * Se quita el extra después de usarlo. Si no, un recreate() —el cambio de
     * tema— reutiliza el mismo Intent y volvería a saltar a esa pestaña.
     */
    private fun abrirPestanaPedida(i: Intent?) {
        val tab = i?.getIntExtra(BottomBar.EXTRA_TAB, -1) ?: -1
        if (tab < 0) return
        i?.removeExtra(BottomBar.EXTRA_TAB)
        pestana(tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirPestanaPedida(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // El cambio de tema hace recreate(): sin esto, tocar "Appearance" en
        // More te devolvía al Scanner.
        outState.putInt("pagina", paginaActual)
    }

    /**
     * Atrás: de Recovery a More, que es de donde se entra; de cualquier otra
     * pestaña al Scanner; y desde el Scanner, salir. Es lo que hacen las apps
     * con pestañas abajo. Sin esto, atrás desde Puzzle cerraba la app entera.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (paginaActual) {
            PAG_RECOVERY -> goTab(PAG_MORE)
            PAG_SCANNER  -> super.onBackPressed()
            else         -> goTab(PAG_SCANNER)
        }
    }

    // ── Variables ─────────────────────────────────────────────────────────────
    private var sessionStartTime = 0L
    private var sessionStartCount = 0L
    private var batteryReceiver: android.content.BroadcastReceiver? = null
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
    private var tvCount: TextView? = null
    private var chartView: SpeedChartView? = null
    private var tvMediaPuzzle: TextView? = null
    /** Cuándo se tomó la última muestra de la gráfica, y con qué contador. */
    private var kgMuestraMs = 0L
    private var kgMuestraOps = 0L
    private var kgMuestrasSinGuardar = 0
    private var tvPuzzleStatus: TextView? = null
    private var tvTime: TextView? = null
    private var tvMatches: TextView? = null
    private var tvRam: TextView? = null
    private var tvTemp: TextView? = null
    private var tvBattery: TextView? = null
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
    /** Etiquetas de las tarjetas de estadística: cambian según el modo. */
    private var lblEscaneadas: TextView? = null
    private var lblRestantes: TextView? = null
    /** Para calcular la velocidad de Kangaroo, que no pasa por HunterEngine. */
    private var kgInicio = 0L
    private var kgUltOps = 0L
    private var kgUltMs = 0L
    private var kgOpsSeg = 0.0
    /** Operaciones que se esperan: ~2,2·raíz(W). */
    private var kgOpsEsperadas = 0.0
    /** Segundos de búsqueda de sesiones anteriores, para que el reloj cuadre
     *  con el contador de operaciones, que también es acumulado. */
    private var kgSegPrevios = 0L
    /** Título de la pantalla en la cabecera, que cambia con la pestaña. */
    private var sbThreads: SeekBar? = null
    private var sbCpu: SeekBar? = null
    // Puzzle tiene sus propios sliders independientes
    private var sbThreadsPuzzle: SeekBar? = null
    private var sbCpuPuzzle: SeekBar? = null
    private var tvThreadsPuzzle: TextView? = null
    private var tvCpuPuzzle: TextView? = null
    private var tvThreads: TextView? = null
    private var tvCpu: TextView? = null
    private var btnCsv: Button? = null
    private var etRangeStart: EditText? = null
    private var etRangeEnd: EditText? = null
    private var currentRangeStart: String = ""
    private var currentRangeEnd: String = ""
    private val BLOCK_SIZE = java.math.BigInteger("1000000000") // 1B keys por bloque
    private val REQ_IMPORT_PROGRESS = 1003
    private var currentBlockId: String = ""
    private var tvBlockProgress: TextView? = null
    /**
     * La tarjeta de "Range coverage". Se esconde mientras corre Kangaroo:
     * Kangaroo NO recorre el rango bloque a bloque, da saltos por él, así que
     * esa cobertura se queda clavada en 0,0000 % para siempre por bien que vaya
     * la búsqueda. Enseñar un 0 % junto a una búsqueda sana es peor que no
     * enseñar nada.
     */
    private var cardCobertura: android.view.View? = null
    private var etTarget: EditText? = null
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
            "Watchdog ON — $watchdogRestarts restart(s) this session"
        watchdogEnabled -> "Watchdog ON — restarts the scan if it stops"
        else            -> "Watchdog OFF — will not restart the scan"
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
                    d <= 3 -> "$years years"
                    d <= 6 -> "${years.divide(java.math.BigInteger.valueOf(1000))}k years"
                    d <= 9 -> "${years.divide(java.math.BigInteger.valueOf(1_000_000))}M years"
                    else   -> "10^${d - 1} years"
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
            if (ratio.signum() <= 0) return "range covered"
            val digits = ratio.toString().length
            if (digits <= 6) "1 in ${numberFmt.format(ratio.toLong())}"
            else "1 in 10^${digits - 1}"
        } catch (e: Exception) { "—" }
    }
    private var tvCheckpointLive: TextView? = null
    private var tvCountPuzzle: TextView? = null
    private var tvTimePuzzle: TextView? = null
    private var btnPuzzleToggle: Button? = null
    private var recoveryEngine: RecoveryEngine? = null
    private val prefs get() = getSharedPreferences("hunter", MODE_PRIVATE)

    /**
     * Un puzzle.
     *
     * @param pub clave pública comprimida, si se conoce. Los resueltos la tienen
     *   porque gastar las monedas la publica; los que siguen en pie sólo la
     *   revelan si su dirección ha gastado alguna vez, y eso lo averigua
     *   [PubKeyFinder] por la red. Kangaroo NO puede trabajar sin ella.
     * @param clave clave privada, sólo en los ya resueltos. Es lo que convierte
     *   ese puzzle en una PRUEBA: si el motor la encuentra, se puede comparar
     *   con la que ya se sabía y decir si acertó. Sin eso, "no ha encontrado
     *   nada todavía" y "no puede encontrar nada" se ven igual.
     */
    data class PuzzleInfo(val num: Int, val addr: String, val start: String,
                          val end: String, val btc: String,
                          val pub: String = "", val clave: String = "")
    private val puzzles = listOf(
        // Los 160 puzzles, resueltos y sin resolver.
        //
        // Los RANGOS no se transcriben: se calculan. El puzzle N va de 2^(N-1)
        // a 2^N - 1 por definición, así que generarlos quita de en medio la
        // única parte donde un dedo puede equivocarse sin que se note.
        //
        // Los 83 RESUELTOS llevan además su clave privada y su clave pública, y
        // no están copiados a mano: tools/ec-harness/resueltos deriva de cada
        // clave privada la pública y de ahí la dirección, con el código del
        // propio motor, y comprueba que las tres cuadran y que la clave cae en
        // su rango. Esa prueba corre en cada compilación.
        //
        // Hace falta ese cuidado porque esta tabla ya se equivocó una vez: doce
        // entradas que ni siquiera eran direcciones de Bitcoin, las válidas en
        // el número equivocado, y trece puzzles ya resueltos ofreciéndose como
        // objetivo. Una dirección mal copiada da una búsqueda que no puede
        // terminar nunca y que por fuera se ve igual que una que aún no ha
        // terminado.
        PuzzleInfo(1, "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH", "1", "1", "0", "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", "1"),
        PuzzleInfo(2, "1CUNEBjYrCn2y1SdiUMohaKUi4wpP326Lb", "2", "3", "0", "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", "3"),
        PuzzleInfo(3, "19ZewH8Kk1PDbSNdJ97FP4EiCjTRaZMZQA", "4", "7", "0", "025cbdf0646e5db4eaa398f365f2ea7a0e3d419b7e0330e39ce92bddedcac4f9bc", "7"),
        PuzzleInfo(4, "1EhqbyUMvvs7BfL8goY6qcPbD6YKfPqb7e", "8", "f", "0", "022f01e5e15cca351daff3843fb70f3c2f0a1bdd05e5af888a67784ef3e10a2a01", "8"),
        PuzzleInfo(5, "1E6NuFjCi27W5zoXg8TRdcSRq84zJeBW3k", "10", "1f", "0", "02352bbf4a4cdd12564f93fa332ce333301d9ad40271f8107181340aef25be59d5", "15"),
        PuzzleInfo(6, "1PitScNLyp2HCygzadCh7FveTnfmpPbfp8", "20", "3f", "0", "03f2dac991cc4ce4b9ea44887e5c7c0bce58c80074ab9d4dbaeb28531b7739f530", "31"),
        PuzzleInfo(7, "1McVt1vMtCC7yn5b9wgX1833yCcLXzueeC", "40", "7f", "0", "0296516a8f65774275278d0d7420a88df0ac44bd64c7bae07c3fe397c5b3300b23", "4c"),
        PuzzleInfo(8, "1M92tSqNmQLYw33fuBvjmeadirh1ysMBxK", "80", "ff", "0", "0308bc89c2f919ed158885c35600844d49890905c79b357322609c45706ce6b514", "e0"),
        PuzzleInfo(9, "1CQFwcjw1dwhtkVWBttNLDtqL7ivBonGPV", "100", "1ff", "0", "0243601d61c836387485e9514ab5c8924dd2cfd466af34ac95002727e1659d60f7", "1d3"),
        PuzzleInfo(10, "1LeBZP5QCwwgXRtmVUvTVrraqPUokyLHqe", "200", "3ff", "0", "03a7a4c30291ac1db24b4ab00c442aa832f7794b5a0959bec6e8d7fee802289dcd", "202"),
        PuzzleInfo(11, "1PgQVLmst3Z314JrQn5TNiys8Hc38TcXJu", "400", "7ff", "0", "038b05b0603abd75b0c57489e451f811e1afe54a8715045cdf4888333f3ebc6e8b", "483"),
        PuzzleInfo(12, "1DBaumZxUkM4qMQRt2LVWyFJq5kDtSZQot", "800", "fff", "0", "038b00fcbfc1a203f44bf123fc7f4c91c10a85c8eae9187f9d22242b4600ce781c", "a7b"),
        PuzzleInfo(13, "1Pie8JkxBT6MGPz9Nvi3fsPkr2D8q3GBc1", "1000", "1fff", "0", "03aadaaab1db8d5d450b511789c37e7cfeb0eb8b3e61a57a34166c5edc9a4b869d", "1460"),
        PuzzleInfo(14, "1ErZWg5cFCe4Vw5BzgfzB74VNLaXEiEkhk", "2000", "3fff", "0", "03b4f1de58b8b41afe9fd4e5ffbdafaeab86c5db4769c15d6e6011ae7351e54759", "2930"),
        PuzzleInfo(15, "1QCbW9HWnwQWiQqVo5exhAnmfqKRrCRsvW", "4000", "7fff", "0", "02fea58ffcf49566f6e9e9350cf5bca2861312f422966e8db16094beb14dc3df2c", "68f3"),
        PuzzleInfo(16, "1BDyrQ6WoF8VN3g9SAS1iKZcPzFfnDVieY", "8000", "ffff", "0", "029d8c5d35231d75eb87fd2c5f05f65281ed9573dc41853288c62ee94eb2590b7a", "c936"),
        PuzzleInfo(17, "1HduPEXZRdG26SUT5Yk83mLkPyjnZuJ7Bm", "10000", "1ffff", "0", "033f688bae8321b8e02b7e6c0a55c2515fb25ab97d85fda842449f7bfa04e128c3", "1764f"),
        PuzzleInfo(18, "1GnNTmTVLZiqQfLbAdp9DVdicEnB5GoERE", "20000", "3ffff", "0", "020ce4a3291b19d2e1a7bf73ee87d30a6bdbc72b20771e7dfff40d0db755cd4af1", "3080d"),
        PuzzleInfo(19, "1NWmZRpHH4XSPwsW6dsS3nrNWfL1yrJj4w", "40000", "7ffff", "0", "0385663c8b2f90659e1ccab201694f4f8ec24b3749cfe5030c7c3646a709408e19", "5749f"),
        PuzzleInfo(20, "1HsMJxNiV7TLxmoF6uJNkydxPFDog4NQum", "80000", "fffff", "0", "033c4a45cbd643ff97d77f41ea37e843648d50fd894b864b0d52febc62f6454f7c", "d2c55"),
        PuzzleInfo(21, "14oFNXucftsHiUMY8uctg6N487riuyXs4h", "100000", "1fffff", "0", "031a746c78f72754e0be046186df8a20cdce5c79b2eda76013c647af08d306e49e", "1ba534"),
        PuzzleInfo(22, "1CfZWK1QTQE3eS9qn61dQjV89KDjZzfNcv", "200000", "3fffff", "0", "023ed96b524db5ff4fe007ce730366052b7c511dc566227d929070b9ce917abb43", "2de40f"),
        PuzzleInfo(23, "1L2GM8eE7mJWLdo3HZS6su1832NX2txaac", "400000", "7fffff", "0", "03f82710361b8b81bdedb16994f30c80db522450a93e8e87eeb07f7903cf28d04b", "556e52"),
        PuzzleInfo(24, "1rSnXMr63jdCuegJFuidJqWxUPV7AtUf7", "800000", "ffffff", "0", "036ea839d22847ee1dce3bfc5b11f6cf785b0682db58c35b63d1342eb221c3490c", "dc2a04"),
        PuzzleInfo(25, "15JhYXn6Mx3oF4Y7PcTAv2wVVAuCFFQNiP", "1000000", "1ffffff", "0", "03057fbea3a2623382628dde556b2a0698e32428d3cd225f3bd034dca82dd7455a", "1fa5ee5"),
        PuzzleInfo(26, "1JVnST957hGztonaWK6FougdtjxzHzRMMg", "2000000", "3ffffff", "0", "024e4f50a2a3eccdb368988ae37cd4b611697b26b29696e42e06d71368b4f3840f", "340326e"),
        PuzzleInfo(27, "128z5d7nN7PkCuX5qoA4Ys6pmxUYnEy86k", "4000000", "7ffffff", "0", "031a864bae3922f351f1b57cfdd827c25b7e093cb9c88a72c1cd893d9f90f44ece", "6ac3875"),
        PuzzleInfo(28, "12jbtzBb54r97TCwW3G1gCFoumpckRAPdY", "8000000", "fffffff", "0", "03e9e661838a96a65331637e2a3e948dc0756e5009e7cb5c36664d9b72dd18c0a7", "d916ce8"),
        PuzzleInfo(29, "19EEC52krRUK1RkUAEZmQdjTyHT7Gp1TYT", "10000000", "1fffffff", "0", "026caad634382d34691e3bef43ed4a124d8909a8a3362f91f1d20abaaf7e917b36", "17e2551e"),
        PuzzleInfo(30, "1LHtnpd8nU5VHEMkG2TMYYNUjjLc992bps", "20000000", "3fffffff", "0", "030d282cf2ff536d2c42f105d0b8588821a915dc3f9a05bd98bb23af67a2e92a5b", "3d94cd64"),
        PuzzleInfo(31, "1LhE6sCTuGae42Axu1L1ZB7L96yi9irEBE", "40000000", "7fffffff", "0", "0387dc70db1806cd9a9a76637412ec11dd998be666584849b3185f7f9313c8fd28", "7d4fe747"),
        PuzzleInfo(32, "1FRoHA9xewq7DjrZ1psWJVeTer8gHRqEvR", "80000000", "ffffffff", "0", "0209c58240e50e3ba3f833c82655e8725c037a2294e14cf5d73a5df8d56159de69", "b862a62e"),
        PuzzleInfo(33, "187swFMjz1G54ycVU56B7jZFHFTNVQFDiu", "100000000", "1ffffffff", "0", "03a355aa5e2e09dd44bb46a4722e9336e9e3ee4ee4e7b7a0cf5785b283bf2ab579", "1a96ca8d8"),
        PuzzleInfo(34, "1PWABE7oUahG2AFFQhhvViQovnCr4rEv7Q", "200000000", "3ffffffff", "0", "033cdd9d6d97cbfe7c26f902faf6a435780fe652e159ec953650ec7b1004082790", "34a65911d"),
        PuzzleInfo(35, "1PWCx5fovoEaoBowAvF5k91m2Xat9bMgwb", "400000000", "7ffffffff", "0", "02f6a8148a62320e149cb15c544fe8a25ab483a0095d2280d03b8a00a7feada13d", "4aed21170"),
        PuzzleInfo(36, "1Be2UF9NLfyLFbtm3TCbmuocc9N1Kduci1", "800000000", "fffffffff", "0", "02b3e772216695845fa9dda419fb5daca28154d8aa59ea302f05e916635e47b9f6", "9de820a7c"),
        PuzzleInfo(37, "14iXhn8bGajVWegZHJ18vJLHhntcpL4dex", "1000000000", "1fffffffff", "0", "027d2c03c3ef0aec70f2c7e1e75454a5dfdd0e1adea670c1b3a4643c48ad0f1255", "1757756a93"),
        PuzzleInfo(38, "1HBtApAFA9B2YZw3G2YKSMCtb3dVnjuNe2", "2000000000", "3fffffffff", "0", "03c060e1e3771cbeccb38e119c2414702f3f5181a89652538851d2e3886bdd70c6", "22382facd0"),
        PuzzleInfo(39, "122AJhKLEfkFBaGAd84pLp1kfE7xK3GdT8", "4000000000", "7fffffffff", "0", "022d77cd1467019a6bf28f7375d0949ce30e6b5815c2758b98a74c2700bc006543", "4b5f8303e9"),
        PuzzleInfo(40, "1EeAxcprB2PpCnr34VfZdFrkUWuxyiNEFv", "8000000000", "ffffffffff", "0", "03a2efa402fd5268400c77c20e574ba86409ededee7c4020e4b9f0edbee53de0d4", "e9ae4933d6"),
        PuzzleInfo(41, "1L5sU9qvJeuwQUdt4y1eiLmquFxKjtHr3E", "10000000000", "1ffffffffff", "0", "03b357e68437da273dcf995a474a524439faad86fc9effc300183f714b0903468b", "153869acc5b"),
        PuzzleInfo(42, "1E32GPWgDyeyQac4aJxm9HVoLrrEYPnM4N", "20000000000", "3ffffffffff", "0", "03eec88385be9da803a0d6579798d977a5d0c7f80917dab49cb73c9e3927142cb6", "2a221c58d8f"),
        PuzzleInfo(43, "1PiFuqGpG8yGM5v6rNHWS3TjsG6awgEGA1", "40000000000", "7ffffffffff", "0", "02a631f9ba0f28511614904df80d7f97a4f43f02249c8909dac92276ccf0bcdaed", "6bd3b27c591"),
        PuzzleInfo(44, "1CkR2uS7LmFwc3T2jV8C1BhWb5mQaoxedF", "80000000000", "fffffffffff", "0", "025e466e97ed0e7910d3d90ceb0332df48ddf67d456b9e7303b50a3d89de357336", "e02b35a358f"),
        PuzzleInfo(45, "1NtiLNGegHWE3Mp9g2JPkgx6wUg4TW7bbk", "100000000000", "1fffffffffff", "0", "026ecabd2d22fdb737be21975ce9a694e108eb94f3649c586cc7461c8abf5da71a", "122fca143c05"),
        PuzzleInfo(46, "1F3JRMWudBaj48EhwcHDdpeuy2jwACNxjP", "200000000000", "3fffffffffff", "0", "03fd5487722d2576cb6d7081426b66a3e2986c1ce8358d479063fb5f2bb6dd5849", "2ec18388d544"),
        PuzzleInfo(47, "1Pd8VvT49sHKsmqrQiP61RsVwmXCZ6ay7Z", "400000000000", "7fffffffffff", "0", "023a12bd3caf0b0f77bf4eea8e7a40dbe27932bf80b19ac72f5f5a64925a594196", "6cd610b53cba"),
        PuzzleInfo(48, "1DFYhaB2J9q1LLZJWKTnscPWos9VBqDHzv", "800000000000", "ffffffffffff", "0", "0291bee5cf4b14c291c650732faa166040e4c18a14731f9a930c1e87d3ec12debb", "ade6d7ce3b9b"),
        PuzzleInfo(49, "12CiUhYVTTH33w3SPUBqcpMoqnApAV4WCF", "1000000000000", "1ffffffffffff", "0", "02591d682c3da4a2a698633bf5751738b67c343285ebdc3492645cb44658911484", "174176b015f4d"),
        PuzzleInfo(50, "1MEzite4ReNuWaL5Ds17ePKt2dCxWEofwk", "2000000000000", "3ffffffffffff", "0", "03f46f41027bbf44fafd6b059091b900dad41e6845b2241dc3254c7cdd3c5a16c6", "22bd43c2e9354"),
        PuzzleInfo(51, "1NpnQyZ7x24ud82b7WiRNvPm6N8bqGQnaS", "4000000000000", "7ffffffffffff", "0", "028c6c67bef9e9eebe6a513272e50c230f0f91ed560c37bc9b033241ff6c3be78f", "75070a1a009d4"),
        PuzzleInfo(52, "15z9c9sVpu6fwNiK7dMAFgMYSK4GqsGZim", "8000000000000", "fffffffffffff", "0", "0374c33bd548ef02667d61341892134fcf216640bc2201ae61928cd0874f6314a7", "efae164cb9e3c"),
        PuzzleInfo(53, "15K1YKJMiJ4fpesTVUcByoz334rHmknxmT", "10000000000000", "1fffffffffffff", "0", "020faaf5f3afe58300a335874c80681cf66933e2a7aeb28387c0d28bb048bc6349", "180788e47e326c"),
        PuzzleInfo(54, "1KYUv7nSvXx4642TKeuC2SNdTk326uUpFy", "20000000000000", "3fffffffffffff", "0", "034af4b81f8c450c2c870ce1df184aff1297e5fcd54944d98d81e1a545ffb22596", "236fb6d5ad1f43"),
        PuzzleInfo(55, "1LzhS3k3e9Ub8i2W1V8xQFdB8n2MYCHPCa", "40000000000000", "7fffffffffffff", "0", "0385a30d8413af4f8f9e6312400f2d194fe14f02e719b24c3f83bf1fd233a8f963", "6abe1f9b67e114"),
        PuzzleInfo(56, "17aPYR1m6pVAacXg1PTDDU7XafvK1dxvhi", "80000000000000", "ffffffffffffff", "0", "033f2db2074e3217b3e5ee305301eeebb1160c4fa1e993ee280112f6348637999a", "9d18b63ac4ffdf"),
        PuzzleInfo(57, "15c9mPGLku1HuW9LRtBf4jcHVpBUt8txKz", "100000000000000", "1ffffffffffffff", "0", "02a521a07e98f78b03fc1e039bc3a51408cd73119b5eb116e583fe57dc8db07aea", "1eb25c90795d61c"),
        PuzzleInfo(58, "1Dn8NF8qDyyfHMktmuoQLGyjWmZXgvosXf", "200000000000000", "3ffffffffffffff", "0", "0311569442e870326ceec0de24eb5478c19e146ecd9d15e4666440f2f638875f42", "2c675b852189a21"),
        PuzzleInfo(59, "1HAX2n9Uruu9YDt4cqRgYcvtGvZj1rbUyt", "400000000000000", "7ffffffffffffff", "0", "0241267d2d7ee1a8e76f8d1546d0d30aefb2892d231cee0dde7776daf9f8021485", "7496cbb87cab44f"),
        PuzzleInfo(60, "1Kn5h2qpgw9mWE5jKpk8PP4qvvJ1QVy8su", "800000000000000", "fffffffffffffff", "0", "0348e843dc5b1bd246e6309b4924b81543d02b16c8083df973a89ce2c7eb89a10d", "fc07a1825367bbe"),
        PuzzleInfo(61, "1AVJKwzs9AskraJLGHAZPiaZcrpDr1U6AB", "1000000000000000", "1fffffffffffffff", "0", "0249a43860d115143c35c09454863d6f82a95e47c1162fb9b2ebe0186eb26f453f", "13c96a3742f64906"),
        PuzzleInfo(62, "1Me6EfpwZK5kQziBwBfvLiHjaPGxCKLoJi", "2000000000000000", "3fffffffffffffff", "0", "03231a67e424caf7d01a00d5cd49b0464942255b8e48766f96602bdfa4ea14fea8", "363d541eb611abee"),
        PuzzleInfo(63, "1NpYjtLira16LfGbGwZJ5JbDPh3ai9bjf4", "4000000000000000", "7fffffffffffffff", "0", "0365ec2994b8cc0a20d40dd69edfe55ca32a54bcbbaa6b0ddcff36049301a54579", "7cce5efdaccf6808"),
        PuzzleInfo(64, "16jY7qLJnxb7CHZyqBP8qca9d51gAjyXQN", "8000000000000000", "ffffffffffffffff", "0", "03100611c54dfef604163b8358f7b7fac13ce478e02cb224ae16d45526b25d9d4d", "f7051f27b09112d4"),
        PuzzleInfo(65, "18ZMbwUFLMHoZBbfpCjUJQTCMCbktshgpe", "10000000000000000", "1ffffffffffffffff", "0", "0230210c23b1a047bc9bdbb13448e67deddc108946de6de639bcc75d47c0216b1b", "1a838b13505b26867"),
        PuzzleInfo(66, "13zb1hQbWVsc2S7ZTZnP2G4undNNpdh5so", "20000000000000000", "3ffffffffffffffff", "0", "024ee2be2d4e9f92d2f5a4a03058617dc45befe22938feed5b7a6b7282dd74cbdd", "2832ed74f2b5e35ee"),
        PuzzleInfo(67, "1BY8GQbnueYofwSuFAT3USAhGjPrkxDdW9", "40000000000000000", "7ffffffffffffffff", "0", "0212209f5ec514a1580a2937bd833979d933199fc230e204c6cdc58872b7d46f75", "730fc235c1942c1ae"),
        PuzzleInfo(68, "1MVDYgVaSN6iKKEsbzRUAYFrYJadLYZvvZ", "80000000000000000", "fffffffffffffffff", "0", "031fe02f1d740637a7127cdfe8a77a8a0cfc6435f85e7ec3282cb6243c0a93ba1b", "bebb3940cd0fc1491"),
        PuzzleInfo(69, "19vkiEajfhuZ8bs8Zu2jgmC6oqZbWqhxhG", "100000000000000000", "1fffffffffffffffff", "0", "024babadccc6cfd5f0e5e7fd2a50aa7d677ce0aa16fdce26a0d0882eed03e7ba53", "101d83275fb2bc7e0c"),
        PuzzleInfo(70, "19YZECXj3SxEZMoUeJ1yiPsw8xANe7M7QR", "200000000000000000", "3fffffffffffffffff", "0", "0290e6900a58d33393bc1097b5aed31f2e4e7cbd3e5466af958665bc0121248483", "349b84b6431a6c4ef1"),
        PuzzleInfo(71, "1PWo3JeB9jrGwfHDNpdGK54CRas7fsVzXU", "400000000000000000", "7fffffffffffffffff", "7.10099385 BTC"),
        PuzzleInfo(72, "1JTK7s9YVYywfm5XUH7RNhHJH1LshCaRFR", "800000000000000000", "ffffffffffffffffff", "7.20003779 BTC"),
        PuzzleInfo(73, "12VVRNPi4SJqUTsp6FmqDqY5sGosDtysn4", "1000000000000000000", "1ffffffffffffffffff", "7.30003777 BTC"),
        PuzzleInfo(74, "1FWGcVDK3JGzCC3WtkYetULPszMaK2Jksv", "2000000000000000000", "3ffffffffffffffffff", "7.40003777 BTC"),
        PuzzleInfo(75, "1J36UjUByGroXcCvmj13U6uwaVv9caEeAt", "4000000000000000000", "7ffffffffffffffffff", "0", "03726b574f193e374686d8e12bc6e4142adeb06770e0a2856f5e4ad89f66044755", "4c5ce114686a1336e07"),
        PuzzleInfo(76, "1DJh2eHFYQfACPmrvpyWc8MSTYKh7w9eRF", "8000000000000000000", "fffffffffffffffffff", "7.6 BTC"),
        PuzzleInfo(77, "1Bxk4CQdqL9p22JEtDfdXMsng1XacifUtE", "10000000000000000000", "1fffffffffffffffffff", "7.70001826 BTC"),
        PuzzleInfo(78, "15qF6X51huDjqTmF9BJgxXdt1xcj46Jmhb", "20000000000000000000", "3fffffffffffffffffff", "7.8 BTC"),
        PuzzleInfo(79, "1ARk8HWJMn8js8tQmGUJeQHjSE7KRkn2t8", "40000000000000000000", "7fffffffffffffffffff", "7.9 BTC"),
        PuzzleInfo(80, "1BCf6rHUW6m3iH2ptsvnjgLruAiPQQepLe", "80000000000000000000", "ffffffffffffffffffff", "0", "037e1238f7b1ce757df94faa9a2eb261bf0aeb9f84dbf81212104e78931c2a19dc", "ea1a5c66dcc11b5ad180"),
        PuzzleInfo(81, "15qsCm78whspNQFydGJQk5rexzxTQopnHZ", "100000000000000000000", "1ffffffffffffffffffff", "8.1 BTC"),
        PuzzleInfo(82, "13zYrYhhJxp6Ui1VV7pqa5WDhNWM45ARAC", "200000000000000000000", "3ffffffffffffffffffff", "8.2 BTC"),
        PuzzleInfo(83, "14MdEb4eFcT3MVG5sPFG4jGLuHJSnt1Dk2", "400000000000000000000", "7ffffffffffffffffffff", "8.30000546 BTC"),
        PuzzleInfo(84, "1CMq3SvFcVEcpLMuuH8PUcNiqsK1oicG2D", "800000000000000000000", "fffffffffffffffffffff", "8.4 BTC"),
        PuzzleInfo(85, "1Kh22PvXERd2xpTQk3ur6pPEqFeckCJfAr", "1000000000000000000000", "1fffffffffffffffffffff", "0", "0329c4574a4fd8c810b7e42a4b398882b381bcd85e40c6883712912d167c83e73a", "11720c4f018d51b8cebba8"),
        PuzzleInfo(86, "1K3x5L6G57Y494fDqBfrojD28UJv4s5JcK", "2000000000000000000000", "3fffffffffffffffffffff", "8.6 BTC"),
        PuzzleInfo(87, "1PxH3K1Shdjb7gSEoTX7UPDZ6SH4qGPrvq", "4000000000000000000000", "7fffffffffffffffffffff", "8.7 BTC"),
        PuzzleInfo(88, "16AbnZjZZipwHMkYKBSfswGWKDmXHjEpSf", "8000000000000000000000", "ffffffffffffffffffffff", "8.8 BTC"),
        PuzzleInfo(89, "19QciEHbGVNY4hrhfKXmcBBCrJSBZ6TaVt", "10000000000000000000000", "1ffffffffffffffffffffff", "8.9 BTC"),
        PuzzleInfo(90, "1L12FHH2FHjvTviyanuiFVfmzCy46RRATU", "20000000000000000000000", "3ffffffffffffffffffffff", "0", "035c38bd9ae4b10e8a250857006f3cfd98ab15a6196d9f4dfd25bc7ecc77d788d5", "2ce00bb2136a445c71e85bf"),
        PuzzleInfo(91, "1EzVHtmbN4fs4MiNk3ppEnKKhsmXYJ4s74", "40000000000000000000000", "7ffffffffffffffffffffff", "9.1 BTC"),
        PuzzleInfo(92, "1AE8NzzgKE7Yhz7BWtAcAAxiFMbPo82NB5", "80000000000000000000000", "fffffffffffffffffffffff", "9.2 BTC"),
        PuzzleInfo(93, "17Q7tuG2JwFFU9rXVj3uZqRtioH3mx2Jad", "100000000000000000000000", "1fffffffffffffffffffffff", "9.3 BTC"),
        PuzzleInfo(94, "1K6xGMUbs6ZTXBnhw1pippqwK6wjBWtNpL", "200000000000000000000000", "3fffffffffffffffffffffff", "9.4 BTC"),
        PuzzleInfo(95, "19eVSDuizydXxhohGh8Ki9WY9KsHdSwoQC", "400000000000000000000000", "7fffffffffffffffffffffff", "0", "02967a5905d6f3b420959a02789f96ab4c3223a2c4d2762f817b7895c5bc88a045", "527a792b183c7f64a0e8b1f4"),
        PuzzleInfo(96, "15ANYzzCp5BFHcCnVFzXqyibpzgPLWaD8b", "800000000000000000000000", "ffffffffffffffffffffffff", "9.6 BTC"),
        PuzzleInfo(97, "18ywPwj39nGjqBrQJSzZVq2izR12MDpDr8", "1000000000000000000000000", "1ffffffffffffffffffffffff", "9.7 BTC"),
        PuzzleInfo(98, "1CaBVPrwUxbQYYswu32w7Mj4HR4maNoJSX", "2000000000000000000000000", "3ffffffffffffffffffffffff", "9.8 BTC"),
        PuzzleInfo(99, "1JWnE6p6UN7ZJBN7TtcbNDoRcjFtuDWoNL", "4000000000000000000000000", "7ffffffffffffffffffffffff", "9.91257338 BTC"),
        PuzzleInfo(100, "1KCgMv8fo2TPBpddVi9jqmMmcne9uSNJ5F", "8000000000000000000000000", "fffffffffffffffffffffffff", "0", "03d2063d40402f030d4cc71331468827aa41a8a09bd6fd801ba77fb64f8e67e617", "af55fc59c335c8ec67ed24826"),
        PuzzleInfo(101, "1CKCVdbDJasYmhswB6HKZHEAnNaDpK7W4n", "10000000000000000000000000", "1fffffffffffffffffffffffff", "10.1 BTC"),
        PuzzleInfo(102, "1PXv28YxmYMaB8zxrKeZBW8dt2HK7RkRPX", "20000000000000000000000000", "3fffffffffffffffffffffffff", "10.2 BTC"),
        PuzzleInfo(103, "1AcAmB6jmtU6AiEcXkmiNE9TNVPsj9DULf", "40000000000000000000000000", "7fffffffffffffffffffffffff", "10.3 BTC"),
        PuzzleInfo(104, "1EQJvpsmhazYCcKX5Au6AZmZKRnzarMVZu", "80000000000000000000000000", "ffffffffffffffffffffffffff", "10.400016 BTC"),
        PuzzleInfo(105, "1CMjscKB3QW7SDyQ4c3C3DEUHiHRhiZVib", "100000000000000000000000000", "1ffffffffffffffffffffffffff", "0", "03bcf7ce887ffca5e62c9cabbdb7ffa71dc183c52c04ff4ee5ee82e0c55c39d77b", "16f14fc2054cd87ee6396b33df3"),
        PuzzleInfo(106, "18KsfuHuzQaBTNLASyj15hy4LuqPUo1FNB", "200000000000000000000000000", "3ffffffffffffffffffffffffff", "10.6 BTC"),
        PuzzleInfo(107, "15EJFC5ZTs9nhsdvSUeBXjLAuYq3SWaxTc", "400000000000000000000000000", "7ffffffffffffffffffffffffff", "10.7 BTC"),
        PuzzleInfo(108, "1HB1iKUqeffnVsvQsbpC6dNi1XKbyNuqao", "800000000000000000000000000", "fffffffffffffffffffffffffff", "10.8 BTC"),
        PuzzleInfo(109, "1GvgAXVCbA8FBjXfWiAms4ytFeJcKsoyhL", "1000000000000000000000000000", "1fffffffffffffffffffffffffff", "10.9 BTC"),
        PuzzleInfo(110, "12JzYkkN76xkwvcPT6AWKZtGX6w2LAgsJg", "2000000000000000000000000000", "3fffffffffffffffffffffffffff", "0", "0309976ba5570966bf889196b7fdf5a0f9a1e9ab340556ec29f8bb60599616167d", "35c0d7234df7deb0f20cf7062444"),
        PuzzleInfo(111, "1824ZJQ7nKJ9QFTRBqn7z7dHV5EGpzUpH3", "4000000000000000000000000000", "7fffffffffffffffffffffffffff", "11.1001 BTC"),
        PuzzleInfo(112, "18A7NA9FTsnJxWgkoFfPAFbQzuQxpRtCos", "8000000000000000000000000000", "ffffffffffffffffffffffffffff", "11.2 BTC"),
        PuzzleInfo(113, "1NeGn21dUDDeqFQ63xb2SpgUuXuBLA4WT4", "10000000000000000000000000000", "1ffffffffffffffffffffffffffff", "11.3 BTC"),
        PuzzleInfo(114, "174SNxfqpdMGYy5YQcfLbSTK3MRNZEePoy", "20000000000000000000000000000", "3ffffffffffffffffffffffffffff", "11.4 BTC"),
        PuzzleInfo(115, "1NLbHuJebVwUZ1XqDjsAyfTRUPwDQbemfv", "40000000000000000000000000000", "7ffffffffffffffffffffffffffff", "0", "0248d313b0398d4923cdca73b8cfa6532b91b96703902fc8b32fd438a3b7cd7f55", "60f4d11574f5deee49961d9609ac6"),
        PuzzleInfo(116, "1MnJ6hdhvK37VLmqcdEwqC3iFxyWH2PHUV", "80000000000000000000000000000", "fffffffffffffffffffffffffffff", "11.6 BTC"),
        PuzzleInfo(117, "1KNRfGWw7Q9Rmwsc6NT5zsdvEb9M2Wkj5Z", "100000000000000000000000000000", "1fffffffffffffffffffffffffffff", "11.7 BTC"),
        PuzzleInfo(118, "1PJZPzvGX19a7twf5HyD2VvNiPdHLzm9F6", "200000000000000000000000000000", "3fffffffffffffffffffffffffffff", "11.80000661 BTC"),
        PuzzleInfo(119, "1GuBBhf61rnvRe4K8zu8vdQB3kHzwFqSy7", "400000000000000000000000000000", "7fffffffffffffffffffffffffffff", "11.9 BTC"),
        PuzzleInfo(120, "17s2b9ksz5y7abUm92cHwG8jEPCzK3dLnT", "800000000000000000000000000000", "ffffffffffffffffffffffffffffff", "0", "02ceb6cbbcdbdf5ef7150682150f4ce2c6f4807b349827dcdbdd1f2efa885a2630", "b10f22572c497a836ea187f2e1fc23"),
        PuzzleInfo(121, "1GDSuiThEV64c166LUFC9uDcVdGjqkxKyh", "1000000000000000000000000000000", "1ffffffffffffffffffffffffffffff", "12.1 BTC"),
        PuzzleInfo(122, "1Me3ASYt5JCTAK2XaC32RMeH34PdprrfDx", "2000000000000000000000000000000", "3ffffffffffffffffffffffffffffff", "12.2 BTC"),
        PuzzleInfo(123, "1CdufMQL892A69KXgv6UNBD17ywWqYpKut", "4000000000000000000000000000000", "7ffffffffffffffffffffffffffffff", "12.3 BTC"),
        PuzzleInfo(124, "1BkkGsX9ZM6iwL3zbqs7HWBV7SvosR6m8N", "8000000000000000000000000000000", "fffffffffffffffffffffffffffffff", "12.4 BTC"),
        PuzzleInfo(125, "1PXAyUB8ZoH3WD8n5zoAthYjN15yN5CVq5", "10000000000000000000000000000000", "1fffffffffffffffffffffffffffffff", "0", "0233709eb11e0d4439a729f21c2c443dedb727528229713f0065721ba8fa46f00e", "1c533b6bb7f0804e09960225e44877ac"),
        PuzzleInfo(126, "1AWCLZAjKbV1P7AHvaPNCKiB7ZWVDMxFiz", "20000000000000000000000000000000", "3fffffffffffffffffffffffffffffff", "12.6 BTC"),
        PuzzleInfo(127, "1G6EFyBRU86sThN3SSt3GrHu1sA7w7nzi4", "40000000000000000000000000000000", "7fffffffffffffffffffffffffffffff", "12.7 BTC"),
        PuzzleInfo(128, "1MZ2L1gFrCtkkn6DnTT2e4PFUTHw9gNwaj", "80000000000000000000000000000000", "ffffffffffffffffffffffffffffffff", "12.8 BTC"),
        PuzzleInfo(129, "1Hz3uv3nNZzBVMXLGadCucgjiCs5W9vaGz", "100000000000000000000000000000000", "1ffffffffffffffffffffffffffffffff", "12.9 BTC"),
        PuzzleInfo(130, "1Fo65aKq8s8iquMt6weF1rku1moWVEd5Ua", "200000000000000000000000000000000", "3ffffffffffffffffffffffffffffffff", "0", "03633cbe3ec02b9401c5effa144c5b4d22f87940259634858fc7e59b1c09937852", "33e7665705359f04f28b88cf897c603c9"),
        PuzzleInfo(131, "16zRPnT8znwq42q7XeMkZUhb1bKqgRogyy", "400000000000000000000000000000000", "7ffffffffffffffffffffffffffffffff", "13.1 BTC"),
        PuzzleInfo(132, "1KrU4dHE5WrW8rhWDsTRjR21r8t3dsrS3R", "800000000000000000000000000000000", "fffffffffffffffffffffffffffffffff", "13.2 BTC"),
        PuzzleInfo(133, "17uDfp5r4n441xkgLFmhNoSW1KWp6xVLD", "1000000000000000000000000000000000", "1fffffffffffffffffffffffffffffffff", "13.3 BTC"),
        PuzzleInfo(134, "13A3JrvXmvg5w9XGvyyR4JEJqiLz8ZySY3", "2000000000000000000000000000000000", "3fffffffffffffffffffffffffffffffff", "13.4 BTC"),
        PuzzleInfo(135, "16RGFo6hjq9ym6Pj7N5H7L1NR1rVPJyw2v", "4000000000000000000000000000000000", "7fffffffffffffffffffffffffffffffff", "0", "02145d2611c823a396ef6712ce0f712f09b9b4f3135e3e0aa3230fb9b6d08d1e16", "6d9392a16883f90903d5f78da57af07eb2"),
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
        // Antes de super.onCreate: es el único momento en que el tema se
        // puede cambiar, y de él salen los colores de todos los diálogos.
        setTheme(AppTheme.estilo(this))
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
        AppLock.init(this)
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
        var moreScroll: ScrollView? = null
        // Qué pestaña se está montando. Antes esto se decía con un Toast por
        // pestaña —cuatro avisos encadenados en cada arranque, que el usuario
        // ve SIEMPRE aunque no falle nada—. Lo que hacía falta de verdad era
        // saber dónde murió si muere, y para eso basta una variable que el
        // catch de abajo mete en el mensaje y en crash_log.txt. El diagnóstico
        // queda igual de completo y el arranque queda limpio.
        var fase = "scan"
        try {
            scanScroll = buildScanTab()
            fase = "puzzle"
            try {
                puzzleScroll = buildPuzzleTab()
            } catch (e: Throwable) {
                val msg = "PUZZLE_BUILD: ${e.javaClass.simpleName}: ${e.message}"
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                java.io.File(filesDir, "crash_log.txt").appendText("\n$msg\n${e.stackTraceToString()}\n")
            }
            fase = "wallet"
            walletScroll = buildWalletTab()
            fase = "recovery"
            recoveryScroll = buildRecoveryTab()
            fase = "more"
            moreScroll = buildMoreTab()

        // Throwable y no Exception. Esto no es puntillismo: un
        // UnsatisfiedLinkError —la librería nativa que no carga— es un Error, no
        // una Exception, asi que se colaba por encima de este catch y mataba la
        // app SIN ESCRIBIR NADA. Lo que se veía era una pantalla negra con el
        // último toast colgado y ni un mensaje ni una línea en crash_log.txt.
        //
        // Con librerías nativas de por medio, atrapar sólo Exception es dejar
        // fuera justo la familia de fallos que más cuesta diagnosticar.
        } catch (e: Throwable) {
            // Escribir error a archivo para diagnóstico
            try {
                val errFile = java.io.File(filesDir, "crash_log.txt")
                errFile.writeText("CRASH building $fase: ${e.javaClass.simpleName}\n${e.message}\n${e.stackTraceToString()}")
            } catch (ex: Throwable) {}
            android.widget.Toast.makeText(this,
                "CRASH building $fase: ${e.javaClass.simpleName}: ${e.message}",
                android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }

        scanScroll?.let { cf.addView(it) }
        puzzleScroll?.let { cf.addView(it) }
        walletScroll?.let { cf.addView(it) }
        recoveryScroll?.let { cf.addView(it) }
        moreScroll?.let { cf.addView(it) }

        // ── Contenido y barra de pestañas ─────────────────────────────────────
        //
        // Aquí iban una cabecera con el menú de hamburguesa y el cajón lateral.
        // Las siete secciones vivían detrás de ese icono: no se veía dónde
        // estabas hasta abrirlo y cambiar costaba dos toques. Ahora el título
        // lo pone cada página y la navegación va abajo, siempre a la vista.
        root.addView(cf, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val bb = BottomBar(this, BottomBar.SCANNER) { tab -> pestana(tab) }
        barra = bb
        root.addView(bb.vista, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        tabPages = listOf(scanScroll, puzzleScroll, walletScroll, recoveryScroll, moreScroll)
        goTab(savedState?.getInt("pagina", PAG_SCANNER) ?: PAG_SCANNER)
        abrirPestanaPedida(intent)

        // El motor entrega cada hallazgo al baúl cifrado en el momento; esto le
        // da con qué abrirlo. Y lo que dejaran en claro versiones anteriores
        // pasa al baúl y se borra. Aquí NO se consulta ningún saldo: hacerlo en
        // cada arranque mandaba todas las direcciones encontradas a
        // mempool.space sin que nadie lo pidiera. La consulta está en
        // "Actualizar Balance" y en el botón del baúl.
        try {
            HunterEngine.conectarBaul(this)
            MatchVault.recoger(this)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "vault: ${e.message}", e)
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


    // ── MORE ─────────────────────────────────────────────────────────────────
    /**
     * Lo que se usa menos: Recovery, History, Debug y el tema.
     *
     * Estaba todo en el menú lateral, mezclado con las secciones de todos los
     * días. Aquí va agrupado —herramientas por un lado, la app por otro— y
     * cada fila dice para qué sirve, que en el menú no lo decía.
     */
    private fun buildMoreTab(): ScrollView {
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
            setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(32))
        }
        page.addView(Ui.pageTitle(this, "More", lados = false))

        fun grupo(titulo: String): LinearLayout {
            page.addView(TextView(this).apply {
                text = titulo
                textSize = AppTheme.SP_CAPTION + 1f
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.medium(context)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(6), 0, dp(8)) }
            })
            val caja = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.cardBg(AppTheme.R_CARD + 2, AppTheme.BG_CARD, context)
                // Para que el toque de la primera y la última fila no se salga
                // de las esquinas redondeadas.
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(20) }
            }
            page.addView(caja)
            return caja
        }

        fun fila(caja: LinearLayout, icono: Int, titulo: String, detalle: String?,
                 valor: String?, accion: () -> Unit) {
            if (caja.childCount > 0) caja.addView(android.view.View(this).apply {
                setBackgroundColor(AppTheme.BG_ELEV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = dp(70) }
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(12), dp(14), dp(12))
                isClickable = true; isFocusable = true
                foreground = Ui.toque()
                contentDescription = if (detalle != null) "$titulo. $detalle" else titulo
                setOnClickListener { accion() }
            }
            row.addView(FrameLayout(this).apply {
                background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(icono)
                    setColorFilter(AppTheme.TXT_PRI)
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                })
            })
            val textos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
            }
            textos.addView(TextView(this).apply {
                text = titulo
                textSize = AppTheme.SP_BODY + 2f
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.bold(context)
            })
            if (detalle != null) textos.addView(TextView(this).apply {
                text = detalle
                textSize = AppTheme.SP_CAPTION + 1f
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(textos)
            if (valor != null) row.addView(TextView(this).apply {
                text = valor
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(0, 0, dp(6), 0)
            })
            row.addView(Ui.icon(this, R.drawable.ic_chevron, 18, AppTheme.TXT_SEC))
            caja.addView(row)
        }

        val tools = grupo("Tools")
        fila(tools, R.drawable.ic_recovery, "Recovery",
             "Recover a seed with missing words", null) { goTab(PAG_RECOVERY) }
        fila(tools, R.drawable.ic_stats, "History",
             "Sessions and keys checked", null) {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        val app = grupo("App")
        /* Tema claro / oscuro.
         *
         * La paleta clara estaba ENTERA en AppTheme —fondos, textos, bordes— y
         * AppTheme.toggle() no lo llamaba nadie: no habia forma de llegar a
         * ella. Un tema que existe y no se puede elegir es lo mismo que no
         * tenerlo. Estaba en el menu lateral; con el menu fuera, vive aqui.
         *
         * Hace falta recrear la pantalla: los colores se leen al construir cada
         * vista, asi que las que ya estan puestas no cambian solas. La pagina
         * se conserva por onSaveInstanceState, asi que se vuelve a More. */
        fila(app, R.drawable.ic_gear, "Appearance", null,
             if (AppTheme.isDark) "Dark" else "Light") {
            AppTheme.toggle(this)
            recreate()
        }
        fila(app, R.drawable.ic_debug, "Debug", "Engine log and files", null) {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        page.addView(TextView(this).apply {
            text = "v2.4 · Wallet Hunter"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        scroll.addView(page)
        return scroll
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
            text = "Idle"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
        }
        tvScanState = stateLabel
        scanStateDot = statusDot
        statusRow.addView(statusDot); statusRow.addView(stateLabel)
        // El título lo decía la cabecera, que ya no existe: ahora lo dice la
        // página, como todas.
        page.addView(Ui.pageTitle(this, "Scanner", lados = true))
        page.addView(side(statusRow, top = 0, bottom = 18))

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

        val (cardKeys, vKeys) = statCard("Keys checked", accent = false)
        val (cardList, vList) = statCard("List loaded", accent = true, last = true)
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
            text = "At this rate"
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
        tvEngineSummary = settingRow(R.drawable.ic_gear, "Engine", primero = true) {
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
                text = "Load"
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
                    java.io.File(csvPath).name else "No file"
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
                text = "Threads"; textSize = AppTheme.SP_CAPTION
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
                text = "CPU limit"; textSize = AppTheme.SP_CAPTION
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
                    // Por Termico y no directo al motor: el gobernador tiene que
                    // saber lo que ha pedido el usuario para poder devolverselo
                    // cuando el movil se enfrie. Sin el guardia de isRunning
                    // porque setCpuLimit con el motor parado solo guarda un
                    // atomico, y asi el ajuste vale tambien para el siguiente
                    // arranque.
                    Termico.pedir((sbCpu?.progress ?: 70) + 10)
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
                text = "Fast scan"
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
            })
            // Este modo baja PBKDF2 de 2048 iteraciones a 1. El contador sube
            // muchísimo, pero las seeds resultantes no son las de ningún
            // mnemónico BIP39: es velocidad sin ninguna posibilidad de acierto.
            val tvFastWarn = TextView(this@MainActivity).apply {
                text = "Benchmark only: with one iteration the seeds are not BIP39, so it cannot find anything."
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
                        "Fast scan: measures speed only, finds no wallets",
                        Toast.LENGTH_LONG).show()
                }
            }
            fastModeEnabled = prefs.getBoolean("fastMode", false)
            // Y DECÍRSELO AL MOTOR. setPbkdf2Mode sólo se llamaba al tocar el
            // interruptor, así que al reabrir la app el interruptor salía en
            // "rápido" —lo lee de preferencias— y el motor seguía en 2048
            // iteraciones. La pantalla decía una cosa y el motor hacía otra, que
            // es justo lo que este ajuste NO se puede permitir: su razón de ser
            // es medir velocidad, y con el motor en normal el número que sale no
            // es el que se cree estar midiendo.
            try { HunterEngine.setPbkdf2Mode(if (fastModeEnabled) 1 else 0) }
            catch (e: Throwable) {}
            fastRow.addView(fastSwitch)
            addView(fastRow)

            // Selector de rutas de derivación. Derivar ambas duplica las
            // derivaciones y los hash160 por candidato; PBKDF2 domina, así que
            // el ahorro es del 2-5%, pero si el dataset sólo tiene un tipo de
            // dirección la mitad del trabajo no sirve para nada.
            addView(TextView(this@MainActivity).apply {
                text = "Derivation paths"; textSize = AppTheme.SP_CAPTION
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
                        if (watchdogEnabled) "Watchdog on" else "Watchdog off",
                        android.widget.Toast.LENGTH_SHORT).show()
                }),
                Triple(R.drawable.ic_gear, "Set up for this hardware", { _: TextView -> showHardwareInfo() })
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
        tvClusterSummary = settingRow(R.drawable.ic_network, "Multiple devices", primero = false) {
            addView(TextView(this@MainActivity).apply {
                text = "This IP: ${NetworkManager.getLocalIp(this@MainActivity)}"
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
            row1.addView(netBtn("Be master") { startClusterMaster() })
            row1.addView(netBtn("Find master") {
                // Los dos avisos van como argumentos con nombre y no como
                // lambda suelta al final: con dos parámetros de función
                // seguidos, la lambda suelta se engancha al ÚLTIMO, que no es
                // el que uno cree al leerlo.
                NetworkManager.discoverMasters(this@MainActivity,
                    onFound = { ip, _ ->
                        runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Master found: $ip", android.widget.Toast.LENGTH_SHORT).show() }
                    },
                    // Sin esto, no encontrar nada no decía nada: el botón se
                    // quedaba mudo y parecía que no hacía nada. Y no encontrar
                    // nada es lo NORMAL salvo en la misma WiFi, porque va por
                    // difusión y eso no cruza routers, VPN ni datos móviles.
                    onFin = { n ->
                        if (n == 0) runOnUiThread {
                            android.widget.Toast.makeText(this@MainActivity,
                                "No master on this WiFi. From another network, " +
                                "type its address on the network screen.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    })
            })
            addView(row1)
            val row2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row2.addView(netBtn("Be worker") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            row2.addView(netBtn("Connect") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
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
        page.addView(side(Ui.sectionLabel(this, "Mode"), bottom = 0))

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
        // El subtítulo de "Direct key" decía "10× más rápido". Medido en el
        // banco de pruebas (tools/ec-harness), por candidato:
        //
        //   BIP39         2.074 µs   (PBKDF2 2048 + derivación BIP32 + 5 hash160)
        //   Clave directa     1,45 µs
        //   proporción       1.431×
        //
        // O sea que la etiqueta se quedaba corta 143 veces. No es un detalle de
        // presentación: con "10×" alguien puede pensar que BIP39 sale a cuenta,
        // y en realidad cada frase semilla cuesta lo que mil cuatrocientas
        // claves. Casi todo se va en el PBKDF2 de 2048 vueltas, que es
        // deliberadamente lento por diseño del propio BIP39.
        listOf("BIP39" to "Seed phrases", "Direct key" to "~1,400× faster")
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
            setTextColor(AppTheme.ON_ACCENT)
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
            setPadding(0, 0, 0, dp(80))
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
        page.addView(Ui.pageTitle(this, "Puzzle", lados = true))
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
                "Puzzle table: ${invalidas.size} invalid addresses: " +
                invalidas.joinToString { "#${it.num}" })
        // Los que valen como objetivo, sin mirar todavia si tienen fondos.
        val todosPuzzles = puzzles.filter {
            BtcAddress.validate(it.addr, false) is BtcAddress.Result.Valid
        }
        // "Sin fondos" = alguien ya lo resolvio y se llevo el premio. La marca la
        // pone la consulta de saldo, aqui solo se lee.
        // Un puzzle del que se conoce la clave esta resuelto por definicion: no
        // hace falta preguntarle el saldo a nadie para saber que no paga.
        val resueltos = todosPuzzles.filter { it.clave.isNotEmpty() }.map { it.num }.toSet()
        fun sinFondos(n: Int) =
            n in resueltos || hiddenPuzzles.getBoolean("hidden_$n", false)

        /* MODO PRUEBA: enseñar tambien los ya resueltos.
         *
         * Un puzzle resuelto no da dinero, pero da algo que ninguna prueba del
         * banco puede dar: un objetivo REAL, con clave conocida y rango pequeño,
         * que este movil puede llegar a encontrar de verdad. Es la unica forma
         * de comprobar de punta a punta —motor, tabla, cluster y aviso— en el
         * aparato de uno y no en un ordenador de escritorio.
         *
         * Viene apagado: lo normal es querer los que pagan. */
        fun listaVisible(): List<PuzzleInfo> {
            if (prefs.getBoolean("mostrar_sin_fondos", false)) return todosPuzzles
            val conFondos = todosPuzzles.filter { !sinFondos(it.num) }
            // Si se hubieran ocultado todos, mas vale enseñarlos que dejar la
            // pestaña vacia y sin forma de salir de ahi.
            return if (conFondos.isEmpty()) todosPuzzles else conFondos
        }
        var visiblePuzzles = listaVisible().toMutableList()

        // Track selected puzzle
        var selectedPuzzleIdx = 0

        // Container for chip rows
        val chipSection = pCard(0)
        chipSection.addView(sectionLabel("Select puzzle"))

        // Interruptor de "enseñar tambien los ya resueltos". Se crea aqui para
        // que quede en su sitio en la pantalla; lo que hace se le cuelga mas
        // abajo, cuando ya existen las funciones que repintan los chips.
        val btnSinFondos = TextView(this).apply {
            textSize = AppTheme.SP_CAPTION
            gravity = Gravity.CENTER
            typeface = AppTheme.medium(context)
            isClickable = true; isFocusable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        fun pintarBotonSinFondos() {
            val ver = prefs.getBoolean("mostrar_sin_fondos", false)
            btnSinFondos.text = if (ver) "Hide the ones with no funds left"
                                else     "Also show the already solved ones (test)"
            btnSinFondos.background = Ui.cardBg(AppTheme.R_CHIP,
                if (ver) AppTheme.BG_ELEV else AppTheme.BG_CARD, this@MainActivity)
            btnSinFondos.setTextColor(if (ver) AppTheme.WARN else AppTheme.TXT_SEC)
        }
        pintarBotonSinFondos()
        chipSection.addView(btnSinFondos)

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
            text = "Select a puzzle"
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
            text = "Range coverage"
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
            text = "Blocks: —"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(10), 0, 0)
        }
        progressCard.addView(tvProgressDetail)

        // Salto aleatorio dentro del rango del puzzle.
        tvRandomJump = TextView(this).apply {
            text = "Jump to a random point in the range"
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
            text = "Current block: —"
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
            text = "Reset progress"; textSize = AppTheme.SP_BODY
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
                // return sin decir nada: pulsar "Reset progress" no hacía
                // absolutamente nada mientras estabas buscando.
                val puzzleNum = currentPuzzleNum()
                if (puzzleNum == 0) return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset progress")
                    .setMessage("Delete the progress of puzzle #$puzzleNum?")
                    .setPositiveButton("Reset") { _, _ ->
                        getBlockPrefs().edit().remove("scanned_$puzzleNum").apply()
                        progressBarPuzzle.progress = 0
                        tvProgressPct.text = "0.00%"
                        tvProgressDetail.text = "Blocks: 0 / —"
                        android.widget.Toast.makeText(this@MainActivity, "Progress reset", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        page.addView(progressCard)
        cardCobertura = progressCard

        /* Estaba definida aquí dentro y no se llamaba desde ningún sitio, así
           que la barra y el detalle se quedaban en sus valores iniciales
           ("0.00%" y "Blocks: —"). Se expone como campo para poder
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
                        tvProgressDetail.text = "Blocks: $scanned / $total"
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
            text = "Checking balance…"
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
            text = "Checking whether the public key is published…"
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
            text = "Search with Kangaroo"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.ON_ACCENT)
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
        page.addView(collapsibleSection(R.drawable.ic_target, "Hex range") {
            val rangeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            }
            val colStart = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            }
            colStart.addView(TextView(this@MainActivity).apply { text = "From"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
            etRangeStart = styledInput("0x...")
            colStart.addView(etRangeStart)
            val colEnd = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            colEnd.addView(TextView(this@MainActivity).apply { text = "To"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
            etRangeEnd = styledInput("0x...")
            colEnd.addView(etRangeEnd)
            rangeRow.addView(colStart); rangeRow.addView(colEnd)
            addView(rangeRow)
            addView(TextView(this@MainActivity).apply { text = "Target address"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
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
        statsCard.addView(sectionLabel("Performance"))
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

        // Gráfica de velocidad: una muestra cada 10 s, la última hora.
        //
        // SpeedChartView existía desde hacía tiempo y `chartView` NUNCA se
        // asignaba: era siempre null, así que addPoint() no hacía nada y la
        // gráfica no aparecía en ninguna pantalla.
        //
        // Cada diez segundos, y no cada segundo, porque lo que interesa de una
        // búsqueda que dura días es si el móvil mantiene el ritmo o se está
        // frenando por calor o por batería, y eso no se ve en un segundo. 360
        // puntos son una hora de historia.
        tvMediaPuzzle = TextView(this).apply {
            text = "Average speed · one sample every 10 s, last hour"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        statsCard.addView(tvMediaPuzzle)
        chartView = SpeedChartView(this).apply {
            // 110 y no 90: ahora hay una fila de etiquetas arriba (máx/mín) y
            // otra abajo (tiempo), y con 90 la línea se quedaba sin sitio.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110)
            ).apply { topMargin = dp(8) }
        }
        statsCard.addView(chartView)

        fun miniStat(label: String, tv: TextView, guardarEtiqueta: ((TextView)->Unit)? = null):
                LinearLayout = LinearLayout(this).apply {
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
                guardarEtiqueta?.invoke(this)
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
        miniRow1.addView(miniStat("Scanned", tvCntP) { lblEscaneadas = it }
            .also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        miniRow1.addView(miniStat("Time", tvTmP))
        val pctLocal = tvPctPuzzle!!
        val blkLocal = tvBlockProgress!!
        miniRow2.addView(miniStat("Progress", pctLocal).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        miniRow2.addView(miniStat("Blocks left", blkLocal) { lblRestantes = it })
        statsCard.addView(miniRow1); statsCard.addView(miniRow2)
        page.addView(statsCard)

        // ── POTENCIA: LOW / MEDIUM / HIGH ─────────────────────────────────
        val powerCard = pCard()
        powerCard.addView(sectionLabel("Power"))

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
        // "High" eran 7 hilos fijos. En un móvil de 8 núcleos eso deja uno sin
        // usar, y si el móvil tiene menos de 8 pide más hilos que núcleos.
        // Ahora sale del hardware: Alta = todos, Media = la mitad, Baja = 1.
        //
        // No se reserva núcleo para la interfaz porque el freno de CPU ya deja
        // aire: al 90 % los hilos duermen un 11 % del tiempo.
        val nuc = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val levels = listOf(
            PowerLevel("Low",  1, 30),
            PowerLevel("Medium", (nuc / 2).coerceAtLeast(1), 60),
            PowerLevel("High",  nuc, 90)
        )

        // Eran tres TextViews con su borde, repintados a mano en un
        // forEachIndexed: el mismo patrón que el selector de modo del escáner y
        // el de aleatorio/secuencial de aquí abajo, escrito tres veces.
        // Cual de los tres esta guardado. Iba fijo a 1 (Media), asi que al
        // reabrir la app el selector mentia: marcaba Media aunque hubieras
        // elegido Alta.
        val nivelGuardado = prefs.getInt("puzzle_threads", 3).let { g ->
            levels.indexOfFirst { it.threads - 1 == g }.let { if (it < 0) 1 else it }
        }
        powerRow.addView(Ui.segmented(
            this, levels.map { it.label to null }, initial = nivelGuardado
        ) { idx ->
            val level = levels[idx]
            sbThreadsPuzzle?.progress = level.threads - 1
            sbCpuPuzzle?.progress = level.cpu - 10
            // Si Kangaroo está corriendo, el freno cambia al momento. El número
            // de hilos no: eso sí obligaría a reiniciar la búsqueda.
            Termico.pedir(level.cpu)
            prefs.edit()
                .putInt("puzzle_threads", level.threads - 1)
                .putInt("puzzle_cpu", level.cpu - 10)
                .apply()
            updatePuzzleLabels()
        })
        // ESTO TIRABA LA PREFERENCIA. Los dos deslizadores se cargan de prefs
        // unas lineas mas arriba y aqui se pisaban con 3 y 50 fijos, pasara lo
        // que pasara. Efecto: elegias "High" (7 hilos, 90 % de CPU), cerrabas
        // la app, y al volver buscaba con 4 hilos al 60 % sin avisar de nada.
        // En una busqueda que dura dias eso es casi la mitad del trabajo tirado.
        sbThreadsPuzzle?.progress = prefs.getInt("puzzle_threads", 3)
        sbCpuPuzzle?.progress    = prefs.getInt("puzzle_cpu", 50)
        powerCard.addView(powerRow)

        // Bajo la potencia: que se aplica al momento y que no.
        //
        // El % de CPU si cambia en caliente, pero el numero de hilos no: eso
        // obliga a reiniciar la busqueda. Sin decirlo, mover "Power" con
        // Kangaroo en marcha parece que hace mas de lo que hace.
        powerCard.addView(TextView(this).apply {
            text = "The CPU % applies right away. Threads, when the search restarts."
            textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })


        // ── AJUSTE FINO ───────────────────────────────────────────────────
        // Cómo se recorre el rango y el tamaño de lote se tocan una vez y ya:
        // uno sólo afecta a la fuerza bruta y el otro tiene un óptimo medido
        // que ya viene puesto. Ocupaban media pantalla en medio de la tarjeta
        // de Potencia, entre el selector que sí se usa a diario y el botón de
        // arrancar. Van a una sección plegada, con el mismo mecanismo que ya
        // usan "Hex range" y "Tools" en esta misma pestaña.
        //
        // Potencia NO se pliega: es lo que se mira cuando el móvil se calienta
        // o la velocidad baja, y esconderlo detrás de un toque sería cambiar
        // una pantalla cargada por una pantalla incómoda.
        // MATCH_PARENT explícito: el cuerpo de una sección plegable no lo
        // impone, y sin esto el contenedor mide a lo que ocupe su hijo más
        // ancho — los textos de ayuda se partirían a media frase.
        val tuningCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // ── RECORRIDO DEL RANGO ───────────────────────────────────────────
        tuningCard.addView(Ui.sectionLabel(this, "How the range is walked", topGap = 0))
        tuningCard.addView(Ui.segmented(
            this, listOf("Random" to null, "Sequential" to null), initial = 0
        ) { idx ->
            HunterEngine.setSequential(idx == 1)
            if (HunterEngine.kangarooRunning())
                android.widget.Toast.makeText(this,
                    "This only affects brute force: in Kangaroo the path " +
                    "is decided by the jump function.",
                    android.widget.Toast.LENGTH_LONG).show()
        })
        // El aviso de arriba sólo salía SI tocabas el control Y Kangaroo estaba
        // en marcha. Si lo dejabas puesto y arrancabas, nada te decía que no
        // hace nada. Va fijo en la pantalla.
        tuningCard.addView(TextView(this).apply {
            text = "Brute force only. Kangaroo does not walk the range: " +
                   "it hops along the curve using the jump function."
            textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        // ── BATCH SIZE SLIDER ─────────────────────────────────────────────
        //
        // OJO CON EL COMENTARIO QUE HABÍA AQUÍ. Decía que "lotes mayores
        // amortizan mejor la única inversión modular por lote", y por eso se
        // subió el tope del deslizador a 16000. Medido en el banco, en Kangaroo
        // eso es FALSO por encima de ~2048: el conjunto de trabajo se sale de
        // la caché y va peor.
        //
        //     1024  4,21 M saltos/s      8192  4,08 M
        //     2048  4,33 M  <- óptimo   16000  3,69 M   (16 % peor)
        //     4096  4,28 M              32000  3,45 M
        //
        // Kangaroo acota a 256..4096 por eso. El deslizador sigue llegando a
        // 16000 porque el motor de fuerza bruta es otro bucle y ahí no se ha
        // medido lo mismo.
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
            text = "Batch size"
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
        tuningCard.addView(batchHeaderRow)

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
                    tvBatchVal.text = "$size keys"
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
        tuningCard.addView(sbBatch)

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
        tuningCard.addView(batchLabelRow)
        // Medido: en Kangaroo por encima de ~2048 va peor, no mejor. Y el valor
        // se lee al arrancar, asi que moverlo con la busqueda en marcha no hace
        // nada hasta reiniciarla. Las dos cosas son invisibles sin decirlas.
        tuningCard.addView(TextView(this).apply {
            text = "Kangaroo caps it at 4096 and the measured optimum is 2048: " +
                   "bigger is worse. Applies when the search restarts."
            textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        page.addView(powerCard)
        page.addView(collapsibleSection(R.drawable.ic_dice, "Fine tuning") {
            addView(tuningCard)
        })
        updatePuzzleLabels()

        // ── HERRAMIENTAS ──────────────────────────────────────────────────
        page.addView(collapsibleSection(R.drawable.ic_gear, "Tools") {
            listOf(
                Triple(R.drawable.ic_search, "Look for public keys",      { auditarClavesPublicas() }),
                Triple(R.drawable.ic_gear,   "Set up for this hardware", { showHardwareInfo() }),
                Triple(R.drawable.ic_export, "Export progress",            { exportPuzzleProgress() }),
                Triple(R.drawable.ic_import, "Import progress",            { importPuzzleProgress() })
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
            text = "Start puzzle"
            textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.ON_ACCENT)
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
        var groups = visiblePuzzles.chunked(groupSize)
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
            tvBalResult.text = "Checking the balance of #${p.num}…"
            comprobarAtajo(p)
            checkPuzzleBalance(p.addr) { bal ->
                runOnUiThread {
                    when {
                        bal > 0L -> {
                            if (puzzleSeleccionado != p.num) return@runOnUiThread
                            tvBalResult.text = "${bal / 100_000_000.0} BTC available"
                            tvBalResult.setTextColor(AppTheme.ACCENT)
                        }
                        bal == 0L -> {
                            // La marca se pone siempre: es un hecho sobre el
                            // puzzle, no una preferencia de quien mira.
                            hiddenPuzzles.edit().putBoolean("hidden_${p.num}", true).apply()
                            // En modo prueba se ha elegido a proposito uno ya
                            // resuelto. Esconderlo y saltar a otro seria pelearse
                            // con lo que acaba de pedir el usuario.
                            if (prefs.getBoolean("mostrar_sin_fondos", false)) {
                                tvBalResult.text =
                                    "No funds — #${p.num} already solved. Good as a test: " +
                                    "the key exists and can be found."
                                tvBalResult.setTextColor(AppTheme.WARN)
                                return@runOnUiThread
                            }
                            tvBalResult.text = "No funds — #${p.num} hidden"
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
                                tvPuzzleStatus?.text = "No puzzles available in this group"
                            }
                        }
                        else -> {
                            tvBalResult.text = "No connection — try again"
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
                    // El numero del puzzle viaja en el tag para que al repintar
                    // se pueda saber cual de ellos esta vacio. Sin esto, el
                    // primer clic borraba la marca de todos los demas.
                    tag = p.num
                    setTextColor(when {
                        i == 0            -> AppTheme.BG_DEEP
                        sinFondos(p.num)  -> AppTheme.WARN
                        else              -> AppTheme.TXT_SEC
                    })
                    layoutParams = LinearLayout.LayoutParams(dp(68), dp(44)).apply { marginEnd = dp(8) }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        for (j in 0 until indivRow.childCount) {
                            val c = indivRow.getChildAt(j) as? TextView ?: continue
                            c.background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_CARD, context)
                            val n = c.tag as? Int
                            c.setTextColor(
                                if (n != null && sinFondos(n)) AppTheme.WARN
                                else AppTheme.TXT_SEC)
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

        btnSinFondos.setOnClickListener {
            prefs.edit().putBoolean("mostrar_sin_fondos",
                !prefs.getBoolean("mostrar_sin_fondos", false)).apply()
            visiblePuzzles = listaVisible().toMutableList()
            groups = visiblePuzzles.chunked(groupSize)
            activeGroupIdx = 0
            pintarBotonSinFondos()
            buildGroupChips()
            buildIndivChips(0)
        }

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
                        tvBalResult.text = "${bal / 100_000_000.0} BTC available"
                        tvBalResult.setTextColor(ACCENT)
                    } else {
                        // Antes ponía "Buscando puzzle con fondos..." y llamaba a
                        // autoSelectPuzzle(), que no busca nada: la autoselección
                        // está desactivada y lo único que hacía era dejar el
                        // estado en "Select a puzzle" con uno ya elegido.
                        tvBalResult.text = "No confirmed funds in #${defaultPuzzle.num}"
                        tvBalResult.setTextColor(AppTheme.WARN)
                    }
                }
            }
        }.start()

        scroll.addView(page)
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
            setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(80))
        }
        // El título lo decía la cabecera, que ya no existe.
        page.addView(Ui.pageTitle(this, "Wallet", lados = false))

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

        // El total real, para destaparlo con el ojo sin volver a leer el baúl.
        var totalReal = "0,00000000"
        val filaRotulo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filaRotulo.addView(TextView(this).apply {
            text = "Balance in finds"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        heroCard.addView(filaRotulo)
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
            text = "No key found yet"
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
        // Ocultar saldos: el mismo ajuste que en la cartera. Dentro del baúl
        // no se aplica —ver Privacidad—.
        filaRotulo.addView(Privacidad.ojo(this) {
            tvTotalBtc.text = Privacidad.monto(this, totalReal)
        })
        tvTotalBtc.text = Privacidad.monto(this, totalReal)

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
            MatchVault.recoger(this@MainActivity)
            // Los hallazgos de Kangaroo de antes del arreglo se guardaron sólo
            // con la clave, y el baúl lista por dirección: salían en blanco.
            // Se completan al abrirlo, que es cuando importa verlos.
            try { MatchVault.completarClaves(this@MainActivity) } catch (e: Exception) {}
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
                    totalReal = "%.8f".format(total).replace('.', ',')
                    tvTotalBtc.text = Privacidad.monto(this@MainActivity, totalReal)
                    // El acento sólo cuando de verdad hay saldo. Pintar de verde
                    // un cero es lo mismo que no pintar nada.
                    tvTotalBtc.setTextColor(
                        if (total > 0.0) AppTheme.ACCENT else AppTheme.TXT_PRI)
                    tvTotalUsd.text = when {
                        matches.isEmpty()  -> "No key found yet"
                        sinRed             -> "${matches.size} find(s) · offline, " +
                                              "$pendientes not checked"
                        // Un total que suma ceros sin consultar no es un saldo:
                        // decir "0,00000000" a secas afirma que están vacías.
                        pendientes > 0     -> "${matches.size} find(s) · $pendientes not checked"
                        else               -> "${matches.size} find(s)"
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
        // mes. Las dos primeras pasan a tarjetas grandes, y "View wallet" va
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

        // Va ANTES que abrirCartera porque ésta la llama: en Kotlin una función
        // local sólo ve las que se han declarado por encima.
        fun anadirCartera() {
            // "Create" primero: es lo que busca quien no tiene ninguna. Antes
            // sólo se podía importar una seed que ya existiera.
            val opciones = arrayOf(
                "Create a new wallet",
                "Import a seed phrase (BIP39)",
                "Import a WIF key",
                "Watch an address (read-only)")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add a wallet")
                .setItems(opciones) { _, which ->
                    startActivity(Intent(this, WalletActivity::class.java).apply {
                        putExtra("MODE", when (which) {
                            0 -> "create"; 2 -> "wif_import"; 3 -> "watch_import"; else -> "setup"
                        })
                    })
                }
                .show()
        }

        fun abrirCartera() {
            // Cualquier cartera cuenta. Antes sólo miraba la seed principal y
            // los WIF, así que quien sólo tuviera otra seed o una dirección
            // vigilada recibía "No wallet yet" teniéndolas.
            val hayAlguna = WalletManager.hasSeed(this) ||
                            WalletManager.listWallets(this).isNotEmpty() ||
                            WalletManager.listWifs(this).isNotEmpty() ||
                            WalletManager.listWatchers(this).isNotEmpty()
            if (hayAlguna) {
                // SIN "MODE". Con MODE="seed" se abría siempre la cartera
                // principal y el selector no llegaba a salir: no había forma
                // de elegir otra desde aquí. Sin modo, WalletActivity enseña
                // la lista, y si sólo hay una entra directo a ella.
                val ir = {
                    startActivity(Intent(this, WalletActivity::class.java))
                }
                if (!PinAuthHelper.isSessionValid())
                    PinAuthHelper.show(this) { ok -> if (ok) ir() }
                else ir()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("No wallet yet")
                    .setMessage("Want to add one?")
                    // "Add" iba directo a escribir una seed. Ahora abre la misma
                    // elección que la tarjeta "Add": crear, seed, WIF o vigilar.
                    .setPositiveButton("Add") { _, _ -> anadirCartera() }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }

        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
            addView(bigCard(R.drawable.ic_wallet, "View wallet", "Balances and addresses",
                primary = true, last = false) { abrirCartera() })
            addView(bigCard(R.drawable.ic_add, "Add", "Seed, WIF or address",
                primary = false, last = true) { anadirCartera() })
        })

        // ── RESGUARDO ─────────────────────────────────────────────────────
        page.addView(TextView(this).apply {
            text = "Safekeeping"
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

        val (estVault, _) = guardRow(R.drawable.ic_vault, "Finds vault",
                                "Encrypted, separate from your wallets", primero = true) {
            // El baúl pide el PIN él mismo, siempre: ver VaultActivity.
            startActivity(Intent(this, VaultActivity::class.java))
        }
        val (estBackup, subBackup) = guardRow(R.drawable.ic_lock, "Backups",
                                 "Create, view, share or restore", primero = false) {
            if (!PinAuthHelper.isSessionValid()) PinAuthHelper.show(this) { ok -> if (ok) exportEncryptedBackup() }
            else exportEncryptedBackup()
        }
        guardRow(R.drawable.ic_export, "Export summary",
                 "No private keys", primero = false) { exportLog() }
        page.addView(guardCard)

        // El estado de las dos filas, en segundo plano: leer el baúl y listar
        // los ficheros de copia es I/O, y esto corre al construir la pestaña.
        Thread {
            val hallazgos = try { MatchVault.list(this@MainActivity).size } catch (e: Exception) { 0 }
            val copias = try { BackupStore.list(this@MainActivity) } catch (e: Exception) { emptyList() }
            val ultima = copias.firstOrNull()?.createdAt ?: 0L
            runOnUiThread {
                estVault.text = if (hallazgos == 0) "Empty" else "$hallazgos"
                estBackup.text = if (copias.isEmpty()) "None" else "${copias.size}"
                if (ultima > 0) {
                    val dias = ((System.currentTimeMillis() - ultima) / 86_400_000L).toInt()
                    subBackup.text = when (dias) {
                        0    -> "Last today"
                        1    -> "Last yesterday"
                        else -> "Last $dias days ago"
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
                text = "Balances are checked automatically when this screen opens. " +
                       "That reveals your addresses to whichever server answers: it is the " +
                       "price of seeing them without asking."
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
                android.widget.Toast.makeText(this@MainActivity, "Querying the chain…",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            addView(Ui.icon(this@MainActivity, R.drawable.ic_refresh, 16).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Check balances"
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
            setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(80))
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
        recoveryPage.addView(Ui.pageTitle(this, "Recover seed", lados = false))
        // El subtítulo repetía el título en otras palabras. En su lugar, lo que
        // de verdad hay que saber para usar la pantalla.
        recoveryPage.addView(TextView(this).apply {
            text = "Type the words you remember and mark the gaps with ?"
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
            text = "Tap a box to type it"
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
            text = "Some words are still missing"
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
                    tvRecoveryInfo.text = "None missing"
                    tvRecoveryEta.text = ""
                    tvRecoveryCombos.text = "With no gaps there is nothing to try: " +
                                            "mark the ones you do not remember."
                }
                huecos == palabras.size -> {
                    tvRecoveryInfo.text = "Tap a box to type it"
                    tvRecoveryEta.text = ""
                    tvRecoveryCombos.text = "Type at least the ones you remember."
                }
                else -> {
                    // 2048^huecos. Por encima de 4 huecos se sale de Long, así
                    // que la cuenta va en Double y se dice en texto.
                    val combos = Math.pow(2048.0, huecos.toDouble())
                    val porSeg = 6_300_000.0   // orden de magnitud de un móvil
                    val secs = combos / porSeg
                    tvRecoveryInfo.text =
                        if (huecos == 1) "1 word to guess"
                        else "$huecos words to guess"
                    tvRecoveryEta.text = when {
                        secs < 60        -> "~ ${secs.toInt()} s"
                        secs < 3600      -> "~ ${(secs / 60).toInt()} min"
                        secs < 86_400    -> "~ ${(secs / 3600).toInt()} h"
                        secs < 31_536_000-> "~ ${(secs / 86_400).toInt()} days"
                        else             -> "over a year"
                    }
                    tvRecoveryEta.setTextColor(
                        if (secs > 86_400) AppTheme.WARN else AppTheme.ACCENT)
                    val combosTxt = when {
                        combos >= 1e12 -> "%.1f trillion".format(combos / 1e12)
                        combos >= 1e9  -> "%.1f billion".format(combos / 1e9)
                        combos >= 1e6  -> "%.1f million".format(combos / 1e6)
                        else           -> numberFmt.format(combos.toLong())
                    }
                    tvRecoveryCombos.text = "$combosTxt combinations"
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
                hint = "word ${idx + 1}"
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
                .setTitle("Word ${idx + 1}")
                .setView(cont)
                .setPositiveButton("Save") { _, _ ->
                    val w = campo.text.toString().trim().lowercase()
                    palabras[idx] = if (w.isNotEmpty() && Bip39Words.WORDS.contains(w)) w else ""
                    if (w.isNotEmpty() && !Bip39Words.WORDS.contains(w))
                        Toast.makeText(this, "\"$w\" is not in the BIP39 word list",
                            Toast.LENGTH_LONG).show()
                    pintarPalabras(); refrescarCoste()
                }
                .setNeutralButton("I do not remember it") { _, _ ->
                    palabras[idx] = ""
                    pintarPalabras(); refrescarCoste()
                }
                .setNegativeButton("Cancel", null)
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
        targetCard.addView(fieldLabel("Known address · optional"))
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
            text = "Start recovery"; textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.ON_ACCENT)
            background = Ui.cardBg(AppTheme.R_KEY, AppTheme.ACCENT, context)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancelRecovery = Button(this).apply {
            text = "Cancel"; textSize = AppTheme.SP_BODY
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
            text = "Add to your wallets"; textSize = AppTheme.SP_TITLE
            setTextColor(AppTheme.ON_ACCENT)
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
            // Con agregarSeed y no saveSeed: saveSeed escribe la seed PRINCIPAL,
            // así que con una cartera ya guardada la recuperada la SUSTITUÍA y
            // la anterior se perdía. Ahora va al lado si ya hay una.
            fun guardar() {
                val g = WalletManager.agregarSeed(this, foundMnemonic, "Recovered", "recovery")
                btnSaveWallet.visibility = android.view.View.GONE
                tvRecoveryStatus.text = if (g.yaEstaba) "That seed was already in your wallets (${g.nombre})"
                                        else "Seed saved to your wallets as \"${g.nombre}\""
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
            }
            val d = AlertDialog.Builder(this)
                .setTitle("Save to your wallets")
                .setMessage("It is already kept in the finds vault. Also add it to " +
                            "your wallets, to see its balance and use it?")
                .setPositiveButton("Add") { _, _ ->
                    if (PinAuthHelper.isSessionValid()) guardar()
                    else PinAuthHelper.show(this) { ok -> if (ok) guardar() }
                }
                .setNegativeButton("Cancel", null)
                .create()
            d.show()
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
                    tvRecoveryStatus.text = "Trying: $currentWord  ($attempts / $total)"
                }
            }
            override fun onFoundWithAddress(mnemonic: String, address: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "DERIVED ADDRESS:\n$address\n\nPHRASE:\n$mnemonic"
                    tvRecoveryResult.visibility = android.view.View.VISIBLE
                }
            }
            override fun onFound(mnemonic: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "✓ FOUND\n\n$mnemonic"
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
                    // Al baúl en el momento: antes, salir de la app sin pulsar
                    // "Save wallet" perdía la frase, y había que repetir una
                    // búsqueda de horas.
                    Thread {
                        val ok = try { MatchVault.guardarRecuperada(this@MainActivity, mnemonic) }
                                 catch (t: Throwable) { false }
                        runOnUiThread {
                            tvRecoveryStatus.text = if (ok) "Kept in the finds vault, encrypted."
                                else "Could not keep it in the vault: save it to your wallets now."
                            tvRecoveryStatus.visibility = android.view.View.VISIBLE
                        }
                    }.start()
                    sendMatchNotification("Seed recovered", "It is kept in the finds vault.")
                }
            }
            override fun onNotFound() {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.text = "Not found. Check the words you entered."
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                }
            }
            override fun onCancelled() {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.text = "Cancelled."
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                }
            }
        }

        btnStartRecovery.setOnClickListener {
            val input = seedText()
            if (palabras.all { it.isEmpty() }) {
                tvRecoveryStatus.text = "Type at least the words you remember."
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (palabras.none { it.isEmpty() }) {
                tvRecoveryStatus.text = "There is no gap to try: mark the ones you do not remember."
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (!wordlistLoaded) {
                tvRecoveryStatus.text = "Could not load the BIP39 word list."
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
                    tvRecoveryStatus.text = "Starting..."
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



    // Aqui estaban getBatteryTemp() y getCpuTemp(), las dos definidas y sin
    // una sola llamada en toda la app. La proteccion termica las usa ahora,
    // pero desde el servicio y con la temperatura que ya llegaba por
    // ACTION_BATTERY_CHANGED, asi que estas dos sobran.
    //
    // getCpuTemp() rastreaba /sys/class/thermal con cuatro rutas de reserva
    // para Samsung. Da la del SoC, que sube antes que la de la bateria, y
    // habria sido tentador usarla: pero esas rutas cambian con cada
    // fabricante y cada version, devuelven la zona mas caliente del chip
    // —que pica y baja en segundos— y no dicen nada del calor acumulado.
    // Lo que aqui se quiere evitar no es un pico de un segundo: es la
    // bateria a 45 grados durante tres dias. Para eso la de la bateria no
    // es un sucedaneo, es la buena. Si algun dia hace falta la del SoC,
    // esta en el historial.

    // La detección del hardware y la afinidad de núcleos viven en Hardware.kt.
    private fun aplicarAfinidad(hilos: Int) = Hardware.aplicarAfinidad(hilos)
    private fun detectHardware(): HardwareProfile = Hardware.detectar(this)
    private fun nucleosRapidos(): IntArray = Hardware.nucleosRapidos()

    private fun applyHardwareProfile(profile: HardwareProfile) {
        val threadProgress = (profile.recommendedThreads - 1).coerceIn(0, 7)
        sbThreads?.progress = threadProgress
        sbThreadsPuzzle?.progress = threadProgress

        val cpuProgress = (profile.recommendedCpu - 10).coerceIn(0, 90)
        sbCpu?.progress = cpuProgress
        sbCpuPuzzle?.progress = cpuProgress

        updateLabels()
        updatePuzzleLabels()

        // Fijar hilos a núcleos concretos, pero sólo cuando tenga sentido.
        //
        // Antes se activaba SIEMPRE con una lista adivinada. Dos problemas:
        // la lista podía estar mal (ver nucleosRapidos) y, aunque estuviera
        // bien, fijar más hilos que núcleos grandes es contraproducente. En un
        // Dimensity 1080 hay 2 núcleos grandes: con 7 hilos fijados ahí, seis
        // núcleos se quedan sin usar y los hilos se amontonan de tres en tres.
        // Repartido entre los ocho rinde bastante más, aunque los A55 sean
        // lentos, porque suman.
        //
        // Regla: se fija sólo si los hilos caben en los núcleos rápidos. Si no,
        // se deja al planificador, que ya lleva los hilos pesados a los
        // grandes por su cuenta.
        aplicarAfinidad((sbThreadsPuzzle?.progress ?: 3) + 1)

        // Tamaño de lote: 2048, y no "según la RAM".
        //
        // Esto elegía entre 4000 y 32000 mirando la memoria del móvil. Dos
        // cosas lo hacen inútil, las dos medidas:
        //
        //  - La RAM no pinta nada. El lote más grande de esa tabla ocupa 4 MB;
        //    cualquier móvil que ejecute la app los tiene. No hay nada que
        //    decidir por memoria.
        //
        //  - En el bucle de fuerza bruta el rendimiento es PLANO a partir de
        //    unos 256, porque el hash160 se lleva el 56 % del coste por clave y
        //    eso no depende del lote. De 256 en adelante todo cae entre 0,76 y
        //    0,79 M claves/s, que es ruido de medida; lo unico claro es que 64
        //    es peor (0,71). Ver tools/ec-harness/lote.cpp.
        //
        //  - Y en Kangaroo el óptimo está en 2048, con 16000 un 16 % peor.
        //
        // 2048 es lo mejor para Kangaroo y está en la zona plana del escáner,
        // así que sirve para los tres motores que comparten este ajuste.
        val batchSize = 2048
        try { HunterEngine.setBatchSize(batchSize) } catch (e: Exception) {}

        prefs.edit()
            .putInt("threads", threadProgress)
            .putInt("cpu", cpuProgress)
            .putInt("puzzle_threads", threadProgress)
            .putInt("puzzle_cpu", cpuProgress)
            .putString("big_cores", nucleosRapidos().joinToString(","))
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
            
            Tamaño de lote: 2048 (medido: el mejor para Kangaroo y
            en la zona plana del escáner)
            Núcleos rápidos: ${nucleosRapidos().let {
                if (it.isEmpty()) "not detected (not pinned)" else it.joinToString(",")
            }}
            
            ¿Aplicar configuración óptima?
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Auto setup")
            .setMessage(msg)
            .setPositiveButton("Apply") { _, _ ->
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
                    "Applied: $t threads / $c% CPU",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatCount(v: Long): String = numberFmt.format(v)

    /**
     * El mismo número, pero que quepa.
     *
     * "10,500,411,904" ya se sale de la tarjeta, y eso son cuatro minutos de
     * Kangaroo: en un día son quince dígitos y en una semana dieciocho. Con
     * separadores de millar el texto crece sin techo, así que para los
     * contadores que van en las tarjetas pequeñas se abrevia.
     *
     * Se conserva una cifra decimal porque sin ella "10 G" y "10,9 G" se ven
     * iguales durante horas y parece que no avanza.
     */
    private fun formatCorto(v: Long): String = when {
        v >= 1_000_000_000_000_000L -> "%.1f P".format(v / 1e15).replace('.', ',')
        v >= 1_000_000_000_000L     -> "%.1f T".format(v / 1e12).replace('.', ',')
        v >= 1_000_000_000L         -> "%.1f G".format(v / 1e9).replace('.', ',')
        v >= 1_000_000L             -> "%.1f M".format(v / 1e6).replace('.', ',')
        else                        -> numberFmt.format(v)
    }

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
            "Puzzle table fixed: progress and hidden ones reset")
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
            val txt = "$prefix #%s  ·  %.4f%% of the range".format(
                numberFmt.format(blockIdx), pct)
            if (tvCurrentBlock?.text?.toString() != txt) tvCurrentBlock?.text = txt
        } catch (e: Exception) {}
    }

    private fun pickRandomJump() {
        val rs = puzzleFullStart.ifEmpty { etRangeStart?.text?.toString()?.trim() ?: "" }
        val re = puzzleFullEnd.ifEmpty  { etRangeEnd?.text?.toString()?.trim() ?: "" }
        if (rs.isEmpty() || re.isEmpty()) {
            Toast.makeText(this, "Select a puzzle first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val total = totalBlocksOf(rs, re)
            val idx   = randomBelow(total)
            pendingBlockIdx = idx
            val pct = blockPercent(idx, total)
            tvRandomJump?.text = "Another random point"
            setCurrentBlockLabel("Will start at block", idx)
            val (bStart, _) = blockRange(rs, re, idx)
            if (HunterEngine.isRunning()) {
                Toast.makeText(this,
                    "Applies when the scan restarts (%.2f%%)".format(pct),
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this,
                    "Block #$idx  ·  %.2f%%\nFrom 0x${bStart.trimStart('0')}".format(pct),
                    Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not compute the jump: ${e.message}",
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
            return NetworkManager.getGlobalProgress(rangeStart, rangeEnd) + " [NET]"
        }
        return try {
            // .toLong() sobre el BigInteger truncaba en silencio: el puzzle 160
            // tiene 7,3e38 bloques y el total salía como un número sin sentido.
            val total = totalBlocksOf(rangeStart, rangeEnd)
            val scanned = getBlockPrefs().getStringSet("scanned_$puzzleNum", emptySet())?.size ?: 0
            val pct = blockPercent(java.math.BigInteger.valueOf(scanned.toLong()), total)
            "Blocks: $scanned / $total (%.4f%%)".format(pct)
        } catch (e: Exception) { "" }
    }



    /** Un total de segundos a texto. formatElapsed sólo sabe de un instante de
     *  inicio, y aquí hace falta sumar lo de sesiones anteriores. */
    private fun formatSegundos(elapsed: Long): String {
        if (elapsed <= 0) return "00:00:00"
        val d  = elapsed / 86400
        val h  = (elapsed % 86400) / 3600
        val m  = (elapsed % 3600) / 60
        val sc = elapsed % 60
        return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, sc)
               else "%02d:%02d:%02d".format(h, m, sc)
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

    /** "Buscando · 51 s" o "Idle", con su punto. */
    private fun paintScanState(running: Boolean) {
        // Adoptar una búsqueda que arrancó otro.
        //
        // sessionStartTime sólo se ponía al pulsar el botón de esta pantalla,
        // pero el motor NO se arranca sólo desde aquí: cuando este móvil trabaja
        // para un cluster, el bloque llega por la red y la pantalla de red
        // arranca la búsqueda por su cuenta. Entonces esto se quedaba a cero y
        // salía "Buscando · 00:00:00" con cientos de millones de claves ya
        // revisadas.
        //
        // Es el mismo fallo que tenía Kangaroo con kgInicio, y se arregla igual:
        // el que encuentra una búsqueda en marcha que no puso él, la adopta.
        // Y si el trabajo viene de la red, es de un PUZZLE, no del escáner.
        //
        // onBlock hace setMode(1) —el motor sí está en modo puzzle— pero
        // puzzleMode es una bandera de Kotlin que sólo se ponía al pulsar la
        // pestaña. Así que el trabajo de un bloque asignado por red salía en la
        // pantalla de Escáner, con su "Modo BIP39" y su "Lista cargada: sin
        // cargar", mientras la de Puzzle enseñaba ceros.
        if (running) NetworkManager.bloqueActual?.let { b ->
            if (!puzzleMode || currentBlockId != b.blockId) {
                puzzleMode = true
                currentRangeStart = b.rangeStart
                currentRangeEnd   = b.rangeEnd
                currentBlockId    = b.blockId
                puzzles.firstOrNull { it.num == b.puzzleNum }?.let { p ->
                    puzzleFullStart = p.start
                    puzzleFullEnd   = p.end
                }
            }
        }
        if (running && sessionStartTime == 0L) {
            sessionStartTime = System.currentTimeMillis()
            // El contador es acumulado, así que la cuenta de ESTA sesión parte
            // de lo que ya hubiera. Sin esto, la primera medida contaría todo lo
            // anterior como hecho en un instante.
            sessionStartCount = try { HunterEngine.getCount() } catch (e: Throwable) { 0L }
        }
        if (!running) sessionStartTime = 0L
        // "Idle" vale para una búsqueda parada, pero no para una que ha
        // TERMINADO porque encontró lo que buscaba. Sin distinguirlo, el único
        // rastro de un hallazgo era una línea más en el baúl.
        val hallado = !running &&
            (try { HunterEngine.objetivoHallado() } catch (e: Throwable) { false })
        tvScanState?.text = when {
            running -> "Searching · ${formatElapsed(sessionStartTime)}"
            hallado -> "KEY FOUND! — see it in the vault"
            else    -> "Idle"
        }
        tvScanState?.setTextColor(
            if (running || hallado) AppTheme.ACCENT else AppTheme.TXT_SEC)
        (scanStateDot?.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(if (running || hallado) AppTheme.ACCENT else AppTheme.TXT_MUTED)
    }

    /** Resumen de las filas de ajuste, con el valor que tienen ahora mismo. */
    private fun paintSettingSummaries() {
        val hilos = (sbThreads?.progress ?: 3) + 1
        val cpu   = (sbCpu?.progress ?: 70) + 10
        tvEngineSummary?.text = "$hilos threads · $cpu %"
        tvClusterSummary?.text = when {
            NetworkManager.isRunning.get() && NetworkManager.isMaster -> "Master"
            NetworkManager.isRunning.get() -> "Worker"
            else -> "Idle"
        }
    }

    /**
     * La temperatura y, si la hay, la limitacion.
     *
     * tvThermal llevaba desde que se creo en visibility = GONE y sin que nadie
     * le pusiera texto nunca: la fila existia en el arbol de vistas y no se
     * dibujaba jamas. Ahora dice lo que pasa, y solo cuando pasa algo — un
     * numero de grados fijo en pantalla es ruido; "45 \u00b0C \u00b7 throttled to
     * 35 % CPU" es la respuesta a "por que ha bajado la velocidad".
     */
    private fun pintarTermico() {
        val tv = tvThermal ?: return
        val txt = Termico.resumen()
        // Solo se ensena cuando el gobernador esta haciendo algo. Si el movil
        // va fresco no hay nada que contar, y la temperatura a secas ya sale en
        // la notificacion para quien la quiera.
        if (txt.isEmpty() || !Termico.limitando) {
            tv.visibility = android.view.View.GONE
            return
        }
        tv.visibility = android.view.View.VISIBLE
        tv.text = txt
        tv.setTextColor(if (Termico.parado) AppTheme.RED else AppTheme.WARN)
    }

    private fun updateUI() {
        try {
            paintScanState(HunterEngine.isRunning())
            paintSettingSummaries()
            refrescarKangaroo()
            pintarTermico()
            if (HunterEngine.isRunning()) {
                val wps = HunterEngine.getWps()
                // Actualizar peak y promedio
                if (wps > peakWps) {
                    peakWps = wps
                    // Se mostraba sin escalar junto a un valor ya escalado:
                    // "1.76 MKeys" al lado de "peak 4,816,000" es ilegible.
                    val (pv, pu) = scaleSpeed(wps)
                    peakLabel = "Peak $pv $pu/s"
                    tvPeakWpsPuzzle?.text = peakLabel
                }
                if (wps > 0) {
                    avgWpsSum += wps
                    avgWpsCount++
                    val avg = avgWpsSum / avgWpsCount
                    val (av, au) = scaleSpeed(avg)
                    tvPeakWps?.text =
                        if (peakLabel.isEmpty()) "Avg $av $au/s"
                        else "$peakLabel · avg $av $au/s"
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
                                "Block: $etaBlock · Puzzle $cachedPuzzleLabel: $etaPuzzle"
                            tvBlockProgress?.text = etaPuzzle
                            // Se reafirma cada ciclo para que sobreviva a que se
                            // reconstruya la pestaña o se vuelva desde otra.
                            if (currentBlockId.isNotEmpty())
                                setCurrentBlockLabel("Scanning block",
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
                                "${numberFmt.format(perDay/1_000_000_000_000L)} trillion"
                            perDay >= 1_000_000_000 ->
                                "${numberFmt.format(perDay/1_000_000_000)} billion"
                            perDay >= 1_000_000 -> "${numberFmt.format(perDay/1_000_000)} million"
                            else                -> numberFmt.format(perDay)
                        }
                        tvBinInfoRef?.text = "$perDayStr keys per day"
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
                        MatchVault.recoger(this@MainActivity)
                        if (MatchVault.pendingBalance(this@MainActivity) > 0)
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
        // Si el motor se ha parado porque ENCONTRÓ lo que buscaba, la búsqueda
        // está terminada y la marca de "estaba corriendo" sobra. Esto va fuera
        // del watchdog a propósito: con el watchdog apagado nadie la limpiaba y
        // al reabrir la app, checkAndRestartScan ofrecía reanudar una búsqueda
        // ya resuelta.
        if (wasRunning && !isNowRunning &&
            (try { HunterEngine.objetivoHallado() } catch (e: Throwable) { false })) {
            prefs.edit().putBoolean("scan_was_running", false).apply()
        }
        if (watchdogEnabled && wasRunning && !isNowRunning && lastKnownRunning) {
            // Sin esto, tras perder el dataset —se va con la app al
            // desinstalar— el reintento entraría en el guardia de doToggle() y
            // sacaría un diálogo cada dos segundos sin que nadie lo hubiera
            // pedido. Si no hay con qué comparar, no hay nada que reanudar.
            if (try { HunterEngine.objetivoHallado() } catch (e: Throwable) { false }) {
                // Se ha parado porque ha ENCONTRADO lo que buscaba. Relanzar
                // aquí sería volver a buscar algo que ya está, que es justo lo
                // que hacía antes de que el motor supiera pararse: con el
                // puzzle #1 salían cientos de miles de "PUZZLE SOLVED" de la
                // misma clave.
                prefs.edit().putBoolean("scan_was_running", false).apply()
            } else if (!engineHasSomethingToMatch()) {
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
        syncToggleButton(btnPuzzleToggle, uiRunning && puzzleMode, "Start puzzle")
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
                tvDatasetStat?.textSize = AppTheme.SP_FIGURE  // vuelve de "not loaded"
                tvDatasetStat?.setTextColor(AppTheme.TXT_PRI)
            }
        } else {
            // Un "—" verde no dice nada, y aquí decía algo importante: sin
            // dataset, los modos BIP39 y RAW KEY no tienen contra qué comparar.
            tvDatasetStat?.text = "not loaded"
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
                Toast.makeText(this, "Stopping the previous scan… one moment",
                    Toast.LENGTH_SHORT).show()
                return
            }
            // El dataset se carga en segundo plano: durante ese rato
            // isCsvLoaded() es false, y decir "sin dataset" sería mentira.
            if (!HunterEngine.isRunning() && HunterEngine.isLoading()) {
                Toast.makeText(this, "Loading the dataset… ${HunterEngine.getLoadStatus()}",
                    Toast.LENGTH_SHORT).show()
                return
            }
            if (!HunterEngine.isRunning() && !engineHasSomethingToMatch()) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(if (puzzleMode) "No target address" else "No dataset loaded")
                    .setMessage(
                        if (puzzleMode)
                            "The Target Address field is empty, so there is nothing " +
                            "to search for.\n\nPick a puzzle from the list above " +
                            "or type an address."
                        else
                            "No address list is loaded, so the " +
                            "engine would have nothing to compare against: it would scan at full " +
                            "speed without being able to find anything.\n\nLoad the .bin with " +
                            "LOAD CSV.")
                    .setPositiveButton("Got it", null)
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
                    btn.text = if (puzzleMode) "Start puzzle" else s.start
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
                            Toast.makeText(this, "Error: range not set", Toast.LENGTH_SHORT).show()
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
                        tvRandomJump?.text = "Jump to a random point in the range"
                        if (block != null) {
                            val (bStart, bEnd) = block
                            HunterEngine.setRange(bStart, bEnd)
                            currentRangeStart = bStart
                            currentRangeEnd = bEnd
                            setCurrentBlockLabel("Scanning block",
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
                            "Invalid target address: $target",
                            Toast.LENGTH_LONG).show()
                        return
                    }
                } else {
                    // Sin esto, tras haber tocado un puzzle el escáner heredaba
                    // su dirección y comparaba contra ella en lugar del dataset.
                    HunterEngine.setTarget("")
                }
                HunterEngine.setMode(if (puzzleMode) 1 else selectedScanMode)
                aplicarAfinidad(threads)
                HunterEngine.startHunting(threads, cpu)
                // startHunting pisa g_cpu_limit con lo que se le pasa, asi que
                // el gobernador tiene que volver a decir la suya DESPUES: si no,
                // arrancar una busqueda con el movil ya caliente le devolvia el
                // ritmo entero.
                Termico.pedir(cpu)

                // startHunting() en C++ vuelve sin hacer nada en varios casos
                // —ya corriendo, parada a medias, sin dataset en modo BIP39— y
                // no devuelve nada. Aquí se daba por hecho que había arrancado:
                // se guardaba scan_was_running, el botón pasaba a STOP y se
                // lanzaba el servicio. Resultado: el botón decía STOP con el
                // motor parado, y la siguiente pulsación volvía a intentar
                // arrancar. Se comprueba antes de tocar nada.
                if (!HunterEngine.isRunning()) {
                    Toast.makeText(this, "The engine did not start. Check the dataset and the mode.",
                        Toast.LENGTH_LONG).show()
                    prefs.edit().putBoolean("scan_was_running", false).apply()
                    return
                }

                // Aquí salía un toast con "threads=… · cpu=… · batch=…" en cada
                // arranque. Son los tres ajustes que el usuario acaba de poner
                // él mismo en la pestaña de Config, escritos en jerga, encima
                // de una pantalla que ya cambia el botón a STOP y empieza a
                // contar. El dato sigue estando —y con más— en la pantalla de
                // Debug, que es donde se va a mirar cuando importe.

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
            Toast.makeText(this, "Dataset loaded: ${dest.name}", Toast.LENGTH_SHORT).show()
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
            BackupStore.compartidos(this).listFiles()?.filter { it.name.startsWith("wh_progress_") }?.forEach { it.delete() }
            val file = java.io.File(BackupStore.compartidos(this), "wh_progress_$ts.json")
            file.writeText(json.toString(2))
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Progress")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, "Export progress"))
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
                android.widget.Toast.makeText(this, "Invalid format", android.widget.Toast.LENGTH_SHORT).show()
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
                "Progress imported: $totalImported new blocks",
                android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Import error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
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
        MatchVault.recoger(this)
        startActivity(Intent(this, WalletActivity::class.java).apply {
            putExtra("MODE", "seed")
            putExtra("OPEN_BACKUP_VAULT", true)
        })
    }

    /** El resumen de hallazgos para compartir: ver ExportarResumen. */
    private fun exportLog() = ExportarResumen.exportar(this)


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
        tv.text = "Checking whether the public key is published…"
        tv.setTextColor(AppTheme.TXT_SEC)
        Thread {
            // Los puzzles ya resueltos traen la clave pública en la tabla —
            // gastar las monedas la publica— y está comprobada contra la clave
            // privada por tools/ec-harness/resueltos. Salir a la red a buscar
            // algo que ya se tiene sólo añade una forma de fallar.
            val r = if (p.pub.length == 66)
                        PubKeyFinder.Resultado.Encontrada(p.pub, "")
                    else try { PubKeyFinder.buscar(this@MainActivity, p.addr, false) }
                         catch (e: Exception) { PubKeyFinder.Resultado.SinRed }
            // Ritmo medido del propio motor si está corriendo; si no, un valor
            // del orden del que da este móvil, para no prometer de más.
            val ritmo = HunterEngine.getWps().takeIf { it > 1000 } ?: 4_000_000.0
            val bits = (p.num - 1).coerceAtLeast(1)
            val clavesBrutas = Math.pow(2.0, bits.toDouble())
            // 2,4 medido, no estimado: tools/ec-harness/constante. Ver el
            // comentario de kgOpsEsperadas.
            val opsKangaroo = 2.4 * Math.pow(2.0, bits / 2.0)
            fun humano(segundos: Double): String = when {
                segundos < 90            -> "${segundos.toInt()} seconds"
                segundos < 5400          -> "${(segundos / 60).toInt()} minutes"
                segundos < 172_800       -> "${(segundos / 3600).toInt()} hours"
                segundos < 63_072_000    -> "${(segundos / 86_400).toInt()} days"
                segundos < 3.15e10       -> "${(segundos / 3.15e7).toInt()} years"
                segundos < 3.15e13       -> "%.0f thousand years".format(segundos / 3.15e10)
                else                     -> "%.0f million years".format(segundos / 3.15e13)
            }
            runOnUiThread {
                /* Si mientras tanto se ha elegido OTRO puzzle, esto ya no vale.
                 *
                 * Cada chip lanza su consulta y no todas tardan lo mismo: los
                 * resueltos contestan al instante —la clave pública está en la
                 * tabla— y los demás esperan a la red. Así que al ir pulsando
                 * chips, una consulta lenta de un puzzle anterior aterriza
                 * DESPUÉS y pisa la del actual. Si aquella no tenía clave
                 * pública, deja puzzlePubHex vacío: el botón de Kangaroo
                 * desaparece y, si aún se llega a pulsar, alternarKangaroo se
                 * vuelve sin hacer nada.
                 *
                 * checkPuzzleBalance ya se guardaba de esto; esta no, y no se
                 * notaba porque antes TODAS las consultas iban por la red y
                 * tardaban parecido. */
                if (puzzleSeleccionado != p.num) return@runOnUiThread
                puzzleIniHex = p.start; puzzleFinHex = p.end
                puzzlePubHex = (r as? PubKeyFinder.Resultado.Encontrada)?.pubHex ?: ""
                // A preferencias EN CUANTO se sabe, no sólo al arrancar
                // Kangaroo. Hay dos sitios desde donde se puede ser maestro:
                // "Be master" en esta pantalla, que usa el campo de memoria,
                // y el de la pantalla Cluster, que lee de aquí. Si sólo se
                // escribía al arrancar Kangaroo, entrar por Cluster sin haber
                // pasado por aquí repartía bloques de fuerza bruta en vez de
                // Kangaroo, sin decir nada.
                prefs.edit()
                    .putString("kangaroo_pub", puzzlePubHex)
                    .putString("kangaroo_ini", puzzleIniHex)
                    .putString("kangaroo_fin", puzzleFinHex).apply()
                btnKangaroo?.visibility =
                    if (puzzlePubHex.length == 66) android.view.View.VISIBLE
                    else android.view.View.GONE
                when (r) {
                    is PubKeyFinder.Resultado.Encontrada -> {
                        tv.text = if (p.clave.isNotEmpty())
                            "TEST — #${p.num} is already solved and its key is known, " +
                            "so there is no prize here: it is for checking that the " +
                            "engine finds it.\nWith Kangaroo: " +
                            "${humano(opsKangaroo / ritmo)}."
                        else
                            "Public key published — Kangaroo works here.\n" +
                            "Brute force: ${humano(clavesBrutas / ritmo)}. " +
                            "With Kangaroo: ${humano(opsKangaroo / ritmo)}."
                        tv.setTextColor(
                            if (p.clave.isNotEmpty()) AppTheme.WARN else AppTheme.ACCENT)
                    }
                    PubKeyFinder.Resultado.NoRevelada -> {
                        tv.text = "This address has never spent, so its public " +
                                  "key is not known. There is no shortcut: brute force " +
                                  "only, ${humano(clavesBrutas / ritmo)} at this rate."
                        tv.setTextColor(AppTheme.WARN)
                    }
                    is PubKeyFinder.Resultado.Publicada -> {
                        // Ha gastado, o sea que la clave ESTÁ publicada; lo que
                        // no se ha podido es dar con la transacción. Decir "no
                        // hay atajo" aquí sería mentir.
                        tv.text = "This address has spent ${r.gastos} time(s), so " +
                                  "its public key is published — but it was not " +
                                  "found in the recent history. Try " +
                                  "again; the shortcut exists."
                        tv.setTextColor(AppTheme.TXT_SEC)
                    }
                    PubKeyFinder.Resultado.SinRed -> {
                        tv.text = "Could not check whether the public key is published."
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
            .setTitle("Looking for public keys")
            .setMessage("Querying ${lista.size} addresses…")
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
                    dlg.setMessage("Querying… ${i + 1} of ${lista.size}")
                }
            }
            runOnUiThread {
                dlg.dismiss()
                val texto = buildString {
                    if (con.isEmpty() && publicadas == 0) {
                        append("None of the ${lista.size} has revealed its public ")
                        append("key: none has ever spent.\n\n")
                        append("Kangaroo is no use for any of them. The only way is ")
                        append("brute force, and for these ranges that means ")
                        append("millions of years.\n\n")
                        append("If one day somebody spends from one of those ")
                        append("addresses, the key becomes published and ")
                        append("will show up here.")
                    } else {
                        if (con.isNotEmpty()) {
                            append("Kangaroo works for:\n")
                            con.forEach { (n, pk) -> append("  #$n  ${pk.take(20)}…\n") }
                            append("\n")
                        }
                        if (publicadas > 0)
                            append("$publicadas have spent —so their key is " +
                                   "published— but it was not found in the recent " +
                                   "history.\n\n")
                        append("$sin not revealed.")
                    }
                    if (sinRed > 0) append("\n\nCould not query: no connection.")
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Public keys")
                    .setMessage(texto)
                    .setPositiveButton("Got it", null)
                    .show()
            }
        }.start()
    }

    /** Arranca o para la búsqueda por Kangaroo del puzzle elegido. */
    private fun alternarKangaroo() {
        // Recolectando (maestro de un cluster, tabla viva y cero hilos): esto no
        // es "parar", es "ponerse a buscar TAMBIÉN". El motor no deja añadir
        // hilos a un contexto vivo, así que se para y se vuelve a arrancar; la
        // tabla no se pierde porque kangarooStop() la guarda y kangarooStart()
        // la recupera del mismo fichero. Lo comprueba la prueba "persist".
        val recolectando = HunterEngine.kangarooRunning() &&
            (try { HunterEngine.kangarooHilos() } catch (e: Throwable) { 1 }) == 0
        if (recolectando) {
            try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
            // y sigue abajo, arrancando de verdad
        } else if (HunterEngine.kangarooRunning()) {
            // kangarooStop() guarda antes de liberar: parar no tira el trabajo.
            HunterEngine.kangarooStop()
            prefs.edit().putBoolean("kangaroo_corriendo", false).apply()
            lblEscaneadas?.text = "Scanned"
            lblRestantes?.text = "Blocks left"
            btnKangaroo?.text = "Search with Kangaroo"
            // Si este móvil es el maestro del cluster, volver a recoger: dejarlo
            // sin tabla haría que rechazara los puntos de los trabajadores y el
            // cluster se quedaría en N búsquedas sueltas sin enterarse nadie.
            val vuelveARecoger = NetworkManager.isMaster &&
                NetworkManager.modo == NetworkManager.Modo.KANGAROO &&
                puzzlePubHex.length == 66
            if (vuelveARecoger) {
                val ruta = java.io.File(filesDir,
                    "kangaroo_${puzzlePubHex.take(16)}.dat").absolutePath
                val ok = try {
                    HunterEngine.kangarooStart(puzzlePubHex, puzzleIniHex,
                                               puzzleFinHex, 0, 256, ruta,
                                               HunterEngine.topeTablaBits(this))
                } catch (e: Throwable) { false }
                tvPuzzleAtajo?.text = if (ok)
                    "This phone stops searching, but keeps collecting the " +
                    "workers\u0027 points."
                else "Stopped. The work is saved."
            } else {
                tvPuzzleAtajo?.text = "Stopped. The work is saved; " +
                                      "pressing again continues from there."
            }
            tvPuzzleAtajo?.setTextColor(AppTheme.TXT_SEC)
            kgInicio = 0L
            return
        }
        if (puzzlePubHex.length != 66) {
            // Volverse en silencio deja el botón pulsado y la pantalla igual:
            // no hay forma de saber si no ha hecho nada o si ha fallado.
            tvPuzzleAtajo?.text = "Kangaroo needs the public key and it is not " +
                                  "available yet. Tap the puzzle again to request it."
            tvPuzzleAtajo?.setTextColor(AppTheme.WARN)
            return
        }
        // Un hilo por núcleo menos uno, para que el móvil siga respondiendo.
        // Antes iban fijos: "núcleos - 1" hilos y 512 canguros, ignorando la
        // potencia y el tamaño de lote que tienes puestos justo debajo. Ahora
        // salen de los mismos controles que la fuerza bruta, que es lo que
        // esperas al moverlos.
        val nucleos = Runtime.getRuntime().availableProcessors()
        val hilos = ((sbThreadsPuzzle?.progress ?: 3) + 1).coerceIn(1, nucleos.coerceAtLeast(1))
        val cpu = ((sbCpuPuzzle?.progress ?: 50) + 10).coerceIn(10, 100)
        // El "tamaño de lote" es literalmente cuántos canguros comparten una
        // inversión modular: el mismo papel que en el motor de fuerza bruta.
        // Se acota porque cada canguro ocupa unos 256 bytes por hilo.
        //
        // El suelo son 256 y no 64 porque la inversión del lote se reparte
        // entre los canguros que haya: con 64 sale a bastante más por salto que
        // con 256. Medido en el banco de pruebas, mismo equipo y mismo rango:
        //
        //     lote  64  -> 2,50 M saltos/s
        //     lote 256  -> 3,43 M saltos/s   (+37%)
        //     lote 512  -> 3,63 M saltos/s
        //     lote 1024 -> 3,75 M saltos/s
        //
        // De 256 en adelante la curva ya casi no sube, así que bajar de ahí es
        // regalar un tercio de la velocidad sin ahorrar nada que se note: 256
        // canguros por hilo son 64 KB.
        val porHilo = HunterEngine.getBatchSize().coerceIn(256, 4096)
        // Un fichero por puzzle: la clave pública lo identifica sin ambigüedad
        // y así cambiar de puzzle y volver no pierde nada.
        val ruta = java.io.File(filesDir, "kangaroo_${puzzlePubHex.take(16)}.dat").absolutePath
        val ok = try {
            HunterEngine.kangarooStart(puzzlePubHex, puzzleIniHex, puzzleFinHex,
                                       hilos, porHilo, ruta,
                                       HunterEngine.topeTablaBits(this))
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "kangarooStart: ${e.message}", e); false
        }
        if (!ok) {
            /* kangarooStart exige que el extremo superior del rango tenga al
             * menos 8 bits (`if(bits<8) return JNI_FALSE` en hunter_jni.cpp).
             * Para el puzzle N ese extremo es 2^N-1, o sea N bits: del #1 al #7
             * Kangaroo NO puede arrancar nunca. Y son justo los primeros que uno
             * prueba, así que decir sólo "no se pudo" manda a buscar una avería
             * que no existe.
             *
             * El límite no es capricho: por debajo de ahí el criterio de punto
             * distinguido (dbits, mínimo 6) sería más grande que el rango entero
             * y los canguros no llegarían a apuntar nada. */
            tvPuzzleAtajo?.text = if (puzzleSeleccionado in 1..7)
                "Kangaroo needs a range of at least 8 bits and " +
                "#$puzzleSeleccionado has $puzzleSeleccionado. For one this " +
                "small use \u0027Start puzzle\u0027: it sweeps the whole range instantly."
            else "Could not start the search."
            tvPuzzleAtajo?.setTextColor(AppTheme.RED)
            return
        }
        // ESTE `cpu` SOLO SE PINTABA. kangarooStart no lo recibe, y el motor
        // arranca Kangaroo con lo que hubiera en g_cpu_limit —el limite del
        // ESCANER, o 100 si nadie lo habia tocado—. O sea que el deslizador de
        // potencia del puzzle mostraba un numero que no era el que se estaba
        // usando. Decirselo aqui lo hace verdad y ademas le da al gobernador su
        // punto de partida.
        Termico.pedir(cpu)
        prepararContadoresKangaroo(puzzlePubHex, puzzleIniHex, puzzleFinHex)
        tvPuzzleAtajo?.text = "Searching with Kangaroo · $hilos threads · $cpu % CPU · " +
                             "$porHilo kangaroos per thread"
        tvPuzzleAtajo?.setTextColor(AppTheme.ACCENT)
    }

    /**
     * Pone en hora todo lo que la pantalla necesita para seguir una búsqueda de
     * Kangaroo. Está aparte de arrancarKangaroo() porque el motor NO se arranca
     * sólo desde aquí: la pantalla de red lo arranca por su cuenta, tanto en el
     * maestro como en el trabajador. Cuando eso pasa, esta pantalla se encuentra
     * un Kangaroo corriendo que ella no puso en marcha, y sin esto se quedaba
     * con los contadores a cero. Se veía así, y en los dos móviles a la vez:
     *
     *   Tiempo   20713d 12:17:15      <- kgInicio valía 0, así que el "tiempo
     *                                    transcurrido" era la hora de Unix
     *                                    entera: 56 años.
     *   Progreso            —         <- kgOpsEsperadas a 0
     *   Bloques restantes   —         <- y encima con los rótulos de la fuerza
     *                                    bruta, que en Kangaroo no significan
     *                                    nada: Kangaroo no recorre el rango.
     *
     * Y lo que no se veía: sin kangaroo_corriendo el watchdog no lo relanzaba,
     * y sin kgUltOps volvía el pico de 2 G/s de la primera muestra.
     */
    private fun prepararContadoresKangaroo(pub: String, ini: String, fin: String) {
        kgInicio = System.currentTimeMillis()
        // La velocidad se saca de (ops - kgUltOps) / dt. Y kangarooOps() NO es
        // el trabajo de esta sesión: devuelve el acumulado, con lo recuperado
        // del fichero de guardado incluido.
        //
        // Arrancar kgUltOps en CERO hacía que la primera muestra contara todo
        // ese trabajo previo como si se hubiera hecho en el último segundo. Con
        // 2.870 millones de operaciones recuperadas salía "2,05 GKeys/s", y
        // como el suavizado es 0,7·anterior + 0,3·nueva, tardaba unos quince
        // ticks en lavarse: 2,05 G -> 355 M -> 13,9 M, bajando sin parar. El
        // usuario veía el móvil "frenándose" y un tiempo estimado que se
        // multiplicaba por diez en cada refresco (29 mil -> 164 mil -> 4 M años).
        //
        // Se parte del valor real, así que la primera diferencia es la de esta
        // sesión y no hay nada que lavar.
        kgUltOps = try { HunterEngine.kangarooOps() } catch (e: Throwable) { 0L }
        kgUltMs = kgInicio; kgOpsSeg = 0.0
        // El tiempo también tiene que ser acumulado, porque "Operations" lo es:
        // 2.870 millones de operaciones junto a 00:00:01 no significa nada. Se
        // guarda lo llevado y se sigue contando desde ahí.
        kgSegPrevios = prefs.getLong("kangaroo_seg_${pub.take(16)}", 0L)
        kgMuestraMs = 0L; kgMuestraOps = 0L
        recuperarMuestras()
        // El trabajo que hace falta, en operaciones de grupo: 2,4 veces la raíz
        // del ancho del rango.
        //
        // No es una estimación: lo mide tools/ec-harness/constante resolviendo
        // logaritmos discretos de verdad y dividiendo entre √W, y el banco falla
        // si el motor se sale del techo. Antes ponía 2,2 de memoria, que resultó
        // ser casualmente parecido al valor bueno mientras el motor real costaba
        // 4,16 — o sea que el "Estimated" llevaba meses siendo el doble de
        // optimista sin que nada lo dijera.
        //
        // El 2,4 es lo medido SIN mapa de negación, que es como corre ahora: la
        // negación daba 1,7 pero no encuentra la clave con el dbits real, así
        // que está apagada. Ver el comentario de kg_negacion en kangaroo.h.
        kgOpsEsperadas = try {
            val a = java.math.BigInteger(ini, 16)
            val b = java.math.BigInteger(fin, 16)
            2.4 * Math.pow(2.0, (b.subtract(a).bitLength()) / 2.0)
        } catch (e: Exception) { 0.0 }
        lblEscaneadas?.text = "Operations"
        lblRestantes?.text = "Estimated"
        btnKangaroo?.text = "Stop Kangaroo"
        cardCobertura?.visibility = android.view.View.GONE
        // El watchdog lo relanza si Android se lo lleva por delante. Con el
        // trabajo guardado, relanzar continúa donde estaba en vez de empezar.
        prefs.edit().putBoolean("kangaroo_corriendo", true)
            .putString("kangaroo_pub", pub)
            .putString("kangaroo_ini", ini)
            .putString("kangaroo_fin", fin).apply()
    }

    /**
     * Un worker del cluster ha encontrado la clave y lo ha avisado.
     *
     * Hace falta este camino además del intercambio de tablas. Cuando un
     * aparato cierra la colisión en su propia tabla, la entrada que la cierra no
     * llega a guardarse —dp_insert avisa y sale sin escribirla—, así que por
     * muchos puntos que mande, aquí sólo llega media pareja y la cuenta no se
     * puede repetir. Está comprobado en tools/ec-harness/reparte.cpp, prueba 4.
     *
     * Se guarda antes de tocar nada de la pantalla: si la app muere justo aquí,
     * la clave no puede perderse.
     */
    private fun mostrarClaveDeWorker(dispositivo: String, claveHex: String) {
        guardarHallazgoKangaroo(claveHex, "PUZZLE kangaroo (network, from $dispositivo)")
        try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
        prefs.edit().putBoolean("kangaroo_corriendo", false).apply()
        btnKangaroo?.text = "Search with Kangaroo"
        tvPuzzleAtajo?.text = "KEY FOUND on $dispositivo\n$claveHex\n" +
                              "Saved to the finds vault."
        tvPuzzleAtajo?.setTextColor(AppTheme.ACCENT)
        try { sendMatchNotification("Key found over the network", "Saved to the finds vault.") } catch (e: Throwable) {}
    }

    /* ── ¿Sigue habiendo premio? ──────────────────────────────────────────────
     *
     * El saldo del puzzle sólo se consultaba al pulsar su chip y al abrir la
     * app. Nunca mientras la búsqueda corría.
     *
     * Con fuerza bruta daba igual, porque no ibas a terminar nunca de todas
     * formas. Con Kangaroo no: una búsqueda dura semanas o meses, y desde que
     * se reparte entre varios móviles hay aparatos enteros dedicados a esto sin
     * que nadie mire la pantalla. Si alguien resuelve el puzzle entre medias,
     * la app seguiría quemando batería contra una dirección vacía, y sin nada
     * que lo indicara.
     *
     * No es un caso hipotético: el #135 se barrió el 28 de julio de 2026
     * mientras las listas publicadas seguían dándolo por pendiente.
     *
     * Cada seis horas es de sobra —esto tarda meses— y son cuatro llamadas al
     * día. Y es seguro pararlo por saldo cero porque checkPuzzleBalance
     * devuelve -1, no 0, cuando la red falla: un corte de conexión no puede
     * detener la búsqueda por error.
     */
    private var kgUltSaldoMs = 0L
    private var kgSaldoPedido = false

    private fun vigilarSaldoDelPuzzle() {
        val ahora = System.currentTimeMillis()
        if (kgSaldoPedido) return
        if (kgUltSaldoMs != 0L && ahora - kgUltSaldoMs < 6L * 3600_000L) return
        val p = puzzles.firstOrNull { it.num == puzzleSeleccionado } ?: return
        kgSaldoPedido = true
        kgUltSaldoMs = ahora
        checkPuzzleBalance(p.addr) { bal ->
            runOnUiThread {
                kgSaldoPedido = false
                // -1 = no se pudo consultar. No se toca nada: se reintenta a
                // las seis horas.
                if (bal != 0L) return@runOnUiThread
                // La marca se pone siempre, corra o no la busqueda.
                getSharedPreferences("hidden_puzzles", MODE_PRIVATE)
                    .edit().putBoolean("hidden_${p.num}", true).apply()
                if (!HunterEngine.kangarooRunning()) return@runOnUiThread
                /* MODO PRUEBA: no pararla.
                 *
                 * Esta comprobacion existe para no gastar dias de movil en un
                 * puzzle que alguien acaba de vaciar. Pero si se ha encendido
                 * "mostrar los ya resueltos" es justo lo contrario: se ha
                 * elegido uno sin fondos A PROPOSITO, para ver si el motor lo
                 * encuentra. Pararla a las seis horas seria tirar la prueba
                 * abajo sin que se entendiera por que. */
                if (prefs.getBoolean("mostrar_sin_fondos", false)) {
                    tvPuzzleAtajo?.text = "Puzzle #${p.num} has no funds —it is already " +
                                          "solved— but the search goes on: it was chosen " +
                                          "as a test. If the key shows up, the engine works."
                    tvPuzzleAtajo?.setTextColor(AppTheme.WARN)
                    return@runOnUiThread
                }
                try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
                prefs.edit().putBoolean("kangaroo_corriendo", false).apply()
                btnKangaroo?.text = "Search with Kangaroo"
                tvPuzzleAtajo?.text = "Search stopped: puzzle #${p.num} no longer " +
                                      "has funds, somebody solved it. The work " +
                                      "is kept in case it is useful."
                tvPuzzleAtajo?.setTextColor(AppTheme.WARN)
                // Que los workers del cluster paren también.
                NetworkManager.marcarPuzzleVacio()
            }
        }
    }

    /**
     * Las muestras de la gráfica, guardadas por clave pública.
     *
     * Una búsqueda de Kangaroo dura días y la app se abre y se cierra. Sin
     * guardarlas, cada vez que vuelves la gráfica empieza vacía y tarda una
     * hora en volver a tener algo que mirar — justo cuando lo que quieres saber
     * es si el móvil ha mantenido el ritmo mientras no mirabas.
     */
    private fun guardarMuestras() {
        val pts = chartView?.getPoints() ?: return
        prefs.edit().putString("kangaroo_grafica_${puzzlePubHex.take(16)}",
            pts.joinToString(",") { it.toLong().toString() }).apply()
    }

    private fun recuperarMuestras() {
        chartView?.reset()
        val txt = prefs.getString("kangaroo_grafica_${puzzlePubHex.take(16)}", "") ?: ""
        if (txt.isEmpty()) return
        for (t in txt.split(",")) {
            val v = t.trim().toFloatOrNull() ?: continue
            chartView?.addPoint(v)
        }
    }

    /**
     * Se encontró un Kangaroo corriendo que esta pantalla no arrancó. Pasa
     * siempre que el cluster se pone en marcha desde la pantalla de red.
     *
     * Quién manda aquí es el motor, no las preferencias: kangarooPub() dice qué
     * clave pública se está atacando de verdad. El rango sí sale de las
     * preferencias, pero sólo si la clave coincide — si no coincidiera, el
     * watchdog acabaría relanzando un puzzle distinto del que estaba corriendo,
     * que es peor que no relanzar nada.
     */
    private fun adoptarKangaroo() {
        val pub = try { HunterEngine.kangarooPub() } catch (e: Throwable) { "" }
        if (pub.length != 66) { kgInicio = System.currentTimeMillis(); return }
        val ini = prefs.getString("kangaroo_ini", "") ?: ""
        val fin = prefs.getString("kangaroo_fin", "") ?: ""
        val mismo = prefs.getString("kangaroo_pub", "") == pub &&
                    ini.isNotEmpty() && fin.isNotEmpty()
        // La pantalla tiene que hablar del puzzle que se está buscando, no del
        // que hubiera elegido antes: el trabajador recibe el encargo del
        // maestro y puede no ser el mismo. Y si el rango guardado NO es el de
        // esta clave, se tira: quedarse con el que hubiera antes daría un
        // "Estimated" calculado sobre un rango que no es, y dejaría escrito un
        // par clave/rango incoherente que el watchdog relanzaría tal cual. Vale
        // más un guion que un número inventado.
        puzzlePubHex = pub
        puzzleIniHex = if (mismo) ini else ""
        puzzleFinHex = if (mismo) fin else ""
        prepararContadoresKangaroo(pub, puzzleIniHex, puzzleFinHex)
        tvPuzzleAtajo?.setTextColor(AppTheme.ACCENT)
    }

    /**
     * "123.456 puntos distinguidos guardados", y cuánto queda de tabla.
     *
     * La tabla tiene un tope y al llegar el motor DEJA DE GUARDAR. La búsqueda
     * sigue corriendo y gastando batería, pero ya no acumula nada nuevo: deja de
     * avanzar. Antes lo único que lo delataba era que este número se quedase
     * clavado, y con un punto cada veinte segundos eso no lo nota nadie.
     *
     * El aviso se enseña a partir del 80 %, que a ese ritmo son semanas de
     * margen: tiempo de sobra para hacer algo, y suficientemente tarde para no
     * estar dando la lata desde el primer día.
     */
    private fun textoDeLaTabla(dps: Long): String {
        val tope = try { HunterEngine.kangarooTope() } catch (e: Throwable) { 0L }
        if (tope <= 0) return "${numberFmt.format(dps)} distinguished points stored"
        val pct = dps * 100.0 / tope
        val base = "${numberFmt.format(dps)} of ${numberFmt.format(tope)} " +
                   "points (${"%.1f".format(pct)} % of the table)"
        return when {
            dps >= tope -> "$base\nTABLE FULL: no new points are stored. " +
                           "The search makes no progress even though it keeps running."
            pct >= 80   -> "$base\nThe table is filling up. Once it is full " +
                           "it stops storing and the search stops advancing."
            else        -> base
        }
    }

    /** Se llama desde updateUI(): progreso y resultado. */
    private fun refrescarKangaroo() {
        val tv = tvPuzzleAtajo ?: return
        val clave = try { HunterEngine.kangarooResult() } catch (e: Throwable) { "" }
        if (clave.length == 64) {
            // Guardar ANTES de tocar la interfaz: si la app muere aquí, la
            // clave no puede perderse.
            guardarHallazgoKangaroo(clave, "PUZZLE kangaroo")
            HunterEngine.kangarooStop()
            prefs.edit().putBoolean("kangaroo_corriendo", false).apply()
            btnKangaroo?.text = "Search with Kangaroo"
            /* Si el puzzle era uno de los resueltos, la respuesta se sabía de
             * antemano: se puede COMPARAR. Eso es lo que convierte la búsqueda
             * en una prueba de verdad del motor entero —saltos, tabla, colisión
             * y resta— sobre este aparato y no sobre un banco de pruebas.
             *
             * Y si no coincidiera, hay que decirlo alto: significaría que el
             * motor canta una clave que no es, que es mucho peor que no
             * encontrar ninguna. */
            val esperada = puzzles.firstOrNull { it.num == puzzleSeleccionado }
                                  ?.clave?.lowercase()?.padStart(64, '0')
            tv.text = when {
                esperada.isNullOrEmpty() ->
                    "KEY FOUND\n$clave\nSaved to the finds vault."
                esperada == clave.lowercase() ->
                    "TEST PASSED — #$puzzleSeleccionado\n$clave\n" +
                    "Matches the known key: the engine works end to end."
                else ->
                    "WRONG — #$puzzleSeleccionado\nfound: $clave\n" +
                    "expected: $esperada\nThe engine reported a key that is not the right one."
            }
            tv.setTextColor(
                if (!esperada.isNullOrEmpty() && esperada != clave.lowercase())
                    AppTheme.RED else AppTheme.ACCENT)
            return
        }
        if (!HunterEngine.kangarooRunning()) {
            // Parado. Se marca como "no adoptado" para que, si vuelve a
            // arrancar por cualquier camino —el botón, el watchdog o la
            // pantalla de red—, los contadores se pongan en hora otra vez. Se
            // hace aquí, en el único sitio por donde pasan todas las paradas,
            // y no en cada botón de parar: parar desde la pantalla de red no
            // llama a ninguno de ellos.
            kgInicio = 0L
            cardCobertura?.visibility = android.view.View.VISIBLE
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
        // Va ANTES de mirar si sólo se recoge. El que vigila si el puzzle sigue
        // teniendo fondos es el maestro, y es el que avisa a los trabajadores
        // para que no sigan quemando batería contra una dirección vacía. Si el
        // maestro está recolectando y esto quedara detrás del return, nadie
        // vigilaría nada y el cluster entero seguiría buscando algo ya resuelto.
        vigilarSaldoDelPuzzle()

        // ── ¿Buscando, o sólo recogiendo? ────────────────────────────────
        //
        // El maestro de un cluster tiene la tabla viva para juntar los puntos
        // de los trabajadores, pero sin ningún hilo caminando. kangarooRunning()
        // dice que sí —y tiene que decirlo, porque de eso depende que acepte los
        // puntos que le mandan—, así que sin esto la pantalla enseñaría una
        // búsqueda a 0 op/s, con su gráfica plana y su "Estimated" absurdo.
        val hilosKg = try { HunterEngine.kangarooHilos() } catch (e: Throwable) { 1 }
        if (hilosKg == 0) {
            val pts = try { HunterEngine.kangarooPoints() } catch (e: Throwable) { 0L }
            cardCobertura?.visibility = android.view.View.GONE
            btnKangaroo?.text = "Search with Kangaroo"
            tv.text = "Collecting points from the cluster · $pts in the table\n" +
                      "This phone is not searching. Press \u0027Search with " +
                      "Kangaroo\u0027 if you want it to contribute too."
            tv.setTextColor(AppTheme.TXT_SEC)
            return
        }
        // Hay un Kangaroo en marcha. Si kgInicio sigue a cero es que lo arrancó
        // otro (la pantalla de red, como maestro o como trabajador) y esta
        // pantalla no se ha enterado. Se adopta: los contadores se ponen en hora
        // y a partir de aquí se sigue igual que si el botón lo hubiera pulsado
        // el usuario. Se hace aquí y no duplicando el arranque en la pantalla de
        // red porque así queda cubierto cualquier otro camino que aparezca.
        if (kgInicio == 0L) adoptarKangaroo()

        val ops = try { HunterEngine.kangarooOps() } catch (e: Throwable) { 0L }
        val dps = try { HunterEngine.kangarooPoints() } catch (e: Throwable) { 0L }

        // ── Las estadísticas de la pantalla ───────────────────────────────
        //
        // Todas esas tarjetas leían de HunterEngine, que durante Kangaroo está
        // parado: por eso salía 0 Keys/s y 00:00:00 con la búsqueda en marcha.
        // Kangaroo corre en sus propios hilos y lleva su propia cuenta.
        val ahoraMs = System.currentTimeMillis()
        val dtMs = ahoraMs - kgUltMs
        if (dtMs >= 500) {
            // Media móvil corta: el número salta menos y se lee mejor.
            val inst = (ops - kgUltOps) * 1000.0 / dtMs
            kgOpsSeg = if (kgOpsSeg <= 0) inst else kgOpsSeg * 0.7 + inst * 0.3
            kgUltOps = ops; kgUltMs = ahoraMs
        }
        // ── Una muestra cada diez segundos para la gráfica ───────────────
        //
        // No se usa kgOpsSeg: eso es una media móvil corta, pensada para que el
        // número grande no dé saltos. Para la gráfica interesa el promedio REAL
        // del intervalo, que sale de dividir el trabajo hecho entre el tiempo
        // que ha costado. Así un bajón por calor se ve tal cual y no suavizado.
        if (kgMuestraMs == 0L) { kgMuestraMs = ahoraMs; kgMuestraOps = ops }
        else if (ahoraMs - kgMuestraMs >= SpeedChartView.SEG_MUESTRA * 1000L) {
            val media = (ops - kgMuestraOps) * 1000.0 / (ahoraMs - kgMuestraMs)
            kgMuestraMs = ahoraMs; kgMuestraOps = ops
            chartView?.addPoint(media.toFloat())
            kgMuestrasSinGuardar++
            // Guardar en disco las 360 muestras cada diez segundos seria
            // escribir en preferencias seis veces por minuto durante dias. Se
            // hace una vez por minuto: lo que se puede perder si Android mata
            // la app son cinco muestras, o sea menos de un minuto de grafica.
            if (kgMuestrasSinGuardar >= 6) { kgMuestrasSinGuardar = 0; guardarMuestras() }
        }
        chartView?.let { ch ->
            val pts = ch.getPoints()
            val m = ch.media()
            tvMediaPuzzle?.text = if (m > 0 && pts.size >= 2) {
                val (mv, mu) = scaleSpeed(m.toDouble())
                // Lo que la gráfica no puede decir por sí sola: si el móvil
                // aguanta el ritmo. Se comparan las últimas cinco muestras con
                // la media de todo. En una búsqueda de días esto es el aviso de
                // que se está calentando o de que la batería ha entrado en
                // ahorro, mucho antes de que se note en el número grande.
                // La tendencia se mira sobre los ultimos cinco minutos, no
                // sobre las ultimas muestras: a diez segundos, cinco muestras
                // son menos de un minuto y el aviso saltaria con cualquier
                // ruido.
                val nVent = 5 * 60 / SpeedChartView.SEG_MUESTRA      // 30 muestras
                val ultimas = pts.takeLast(nVent)
                val reciente = ultimas.sum() / ultimas.size
                val desvio = (reciente - m) / m * 100.0
                val estado = when {
                    pts.size < nVent      -> ""
                    desvio >  8           -> " · rising ${"%.0f".format(desvio)} %"
                    desvio < -8           -> " · FALLING ${"%.0f".format(-desvio)} %"
                    else                  -> " · steady"
                }
                val seg = pts.size * SpeedChartView.SEG_MUESTRA
                val span = if (seg >= 60) "${seg / 60} min" else "$seg s"
                "$span average: $mv $mu/s$estado"
            } else "Average speed · first sample in 10 s"
        }

        val (v, u) = scaleSpeed(kgOpsSeg)
        tvWpsPuzzle?.text = v
        tvSpeedUnitPuzzle?.text = "$u op/s"
        tvPeakWpsPuzzle?.text = textoDeLaTabla(dps)
        tvCountPuzzle?.text = formatCorto(ops)
        val segTotal = kgSegPrevios + (System.currentTimeMillis() - kgInicio) / 1000
        tvTimePuzzle?.text = formatSegundos(segTotal)
        // Se guarda sobre la marcha y no sólo al parar: Android puede matar la
        // app sin darle ocasión de despedirse, igual que pasa con la tabla de
        // puntos distinguidos.
        if (segTotal % 15 == 0L)
            prefs.edit().putLong("kangaroo_seg_${puzzlePubHex.take(16)}", segTotal).apply()

        if (kgOpsEsperadas > 0) {
            val pct = ops / kgOpsEsperadas * 100.0
            // Con rangos de 2^139 el porcentaje es un cero con muchos decimales:
            // decir "0,00 %" durante meses no informa de nada. Por debajo de la
            // milésima se enseña en notación científica, que al menos cambia.
            tvPctPuzzle?.text = when {
                pct >= 0.01 -> "%.2f %%".format(pct)
                pct > 0     -> "%.1e %%".format(pct)
                else        -> "—"
            }
            tvBlockProgress?.text = if (kgOpsSeg > 1000) {
                val seg = (kgOpsEsperadas - ops) / kgOpsSeg
                when {
                    seg < 5400        -> "${(seg / 60).toInt()} min"
                    seg < 172_800     -> "${(seg / 3600).toInt()} h"
                    seg < 6.3e7       -> "${(seg / 86_400).toInt()} days"
                    seg < 3.15e10     -> "${(seg / 3.15e7).toInt()} years"
                    seg < 3.15e13     -> "%.0f thousand years".format(seg / 3.15e10)
                    else              -> "%.0f M years".format(seg / 3.15e13)
                }
            } else "—"
        }
        // Guardar cada pocos minutos: si el sistema mata la app no hay ocasión
        // de guardar al parar, y se perdería todo lo de esta sesión.
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoGuardadoKg > 120_000L) {
            ultimoGuardadoKg = ahora
            Thread { try { HunterEngine.kangarooSave() } catch (e: Throwable) {} }.start()
        }
        tv.text = "Searching with Kangaroo · ${numberFmt.format(ops)} operations" +
                  (if (kangarooReinicios > 0) " · $kangarooReinicios restarts" else "")
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
        // La dirección va con cada bloque: es contra lo que compara el
        // trabajador. Se saca de la tabla si las preferencias aún no la tienen
        // —un puzzle elegido antes de que esto existiera— para no obligar a
        // volver a elegirlo.
        val addr  = (prefs.getString("current_puzzle_addr", "") ?: "")
            .ifEmpty { puzzles.firstOrNull { it.num == pnum }?.addr ?: "" }
        if (pnum == 0 || start.isEmpty() || end.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("No puzzle selected")
                .setMessage("Pick a puzzle on the Puzzle tab before starting " +
                            "the master: that range is what gets shared between devices.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // Si este puzzle tiene la clave pública publicada, se reparte Kangaroo,
        // que es seis órdenes de magnitud mejor que la fuerza bruta. Y entonces
        // NO se parte el rango: con Kangaroo partirlo empeora la búsqueda. Lo
        // que se reparte es la tabla de puntos distinguidos.
        val conKangaroo = puzzlePubHex.length == 66 &&
                          puzzleIniHex.isNotEmpty() && puzzleFinHex.isNotEmpty()
        if (conKangaroo) {
            // El master tiene que estar buscando él también: los puntos que
            // llegan de los workers se meten en SU tabla, y es ahí donde
            // aparece la colisión entre dos móviles.
            if (!HunterEngine.kangarooRunning()) alternarKangaroo()
            NetworkManager.startMasterKangaroo(this, pnum, puzzlePubHex,
                                               puzzleIniHex, puzzleFinHex, addr)
            NetworkManager.onClave = { dispositivo, claveHex ->
                runOnUiThread { mostrarClaveDeWorker(dispositivo, claveHex) }
            }
        } else {
            if (addr.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("The puzzle address is missing")
                    .setMessage("Pick puzzle #$pnum again on the " +
                                "Puzzle tab. Without its address, the workers " +
                                "would search with nothing to compare against.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            NetworkManager.startMaster(this, pnum, start, end, addr)
        }

        val explicacion = if (conKangaroo)
            "Kangaroo sharing: every device searches the SAME range and " +
            "pools its points here. Splitting the range would make it worse.\n\n" +
            "WARNING: what travels allows the private key to be rebuilt. Use it " +
            "only on your own network."
        else
            "Block sharing: each device gets a slice of the range."

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Master running — Puzzle #$pnum")
            .setMessage("Access code:\n\n${NetworkManager.authToken}\n\n" +
                        "Enter it on each worker. Without it the master refuses the " +
                        "connection.\n\nListening on port ${NetworkManager.TCP_PORT} " +
                        "on this network.\n\n$explicacion")
            .setPositiveButton("Copy code") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText(
                        "cluster", NetworkManager.authToken))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Guarda un hallazgo de Kangaroo en el baúl, con su dirección y su WIF.
     *
     * Kangaroo devuelve la clave privada y nada más, y así se guardaba: con
     * `addr` y `wif` vacíos. La entrada quedaba en el baúl pero sin nada que la
     * identificara —el baúl lista por dirección— ni nada con lo que gastar. O
     * sea que encontrar una clave y no verla por ningún lado.
     *
     * Si la derivación fallara se guarda igual con lo que haya: perder la clave
     * por no poder adornarla sería mucho peor que una entrada incompleta.
     */
    private fun guardarHallazgoKangaroo(claveHex: String, deDonde: String) {
        var addr = ""; var wif = ""
        try {
            val d = HunterEngine.datosDeClave(claveHex)
            if (d.contains("|")) { wif = d.substringBefore("|"); addr = d.substringAfter("|") }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "datosDeClave: ${e.message}")
        }
        try {
            MatchVault.add(this, MatchVault.Entry(
                ts = System.currentTimeMillis(), source = "kangaroo",
                addr = addr, wif = wif, privHex = claveHex, btc = 0.0,
                extra = deDonde, checkedTs = 0L))
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "could not save: ${e.message}", e)
        }
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
        // otra vez daba "7.9 BTC BTC". Los resueltos llevan "0", que a secas no
        // dice nada: lo que hay que ver es que son objetivos de prueba.
        tvPuzzleStatus?.text = if (p.clave.isNotEmpty())
            "Puzzle #${p.num} — already solved, good as a test"
        else "Puzzle #${p.num} — ${p.btc}"
        // El bloque que hubiera era de otro rango: su porcentaje aquí no vale.
        pendingBlockIdx = null
        currentBlockId = ""
        // La gráfica es de la búsqueda anterior: mezclar velocidades de dos
        // puzzles en la misma línea no significa nada.
        chartView?.reset()
        tvMediaPuzzle?.text = "Average speed · one sample every 10 s"
        kgMuestraMs = 0L; kgMuestraOps = 0L
        tvCurrentBlock?.text = "Current block: —"
        tvRandomJump?.text = "Jump to a random point in the range"
        // Guardar rango para modo distribuido.
        //
        // Y la DIRECCION, que faltaba. El maestro reparte bloques con el rango
        // dentro, pero sin decir contra que hay que comparar; el trabajador
        // arrancaba en modo puzzle sin objetivo, y el motor con
        // g_has_target=0 y sin lista cargada NO PUEDE encontrar nada:
        //
        //     if(g_has_target){ ...compara... }
        //     else if(g_csv_loaded){ ...busca... }
        //
        // O sea que el trabajador calculaba hashes y los comparaba contra nada.
        // A toda velocidad, con su grafica y sus millones de claves revisadas.
        prefs.edit()
            .putString("current_range_start", p.start)
            .putString("current_range_end", p.end)
            .putString("current_puzzle_addr", p.addr)
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

    /**
     * Aviso de hallazgo.
     *
     * Recibía la clave —"WIF: $wif" en el cuerpo— y el puzzle resuelto por
     * red le pasaba la clave privada en hex por ese mismo parámetro. Una
     * notificación sale en la pantalla de bloqueo, en el reloj y en el
     * historial de notificaciones, y la puede leer cualquier app con acceso a
     * ellas. Ahora recibe sólo lo que se puede enseñar: la clave está en el
     * baúl cifrado.
     */
    private fun sendMatchNotification(titulo: String, texto: String) {
        try {
            // Vibración
            val vib = getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 200, 500, 200, 500), -1
                ))
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val intent = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            // En la pantalla de bloqueo, sólo esto.
            val publica = androidx.core.app.NotificationCompat.Builder(this, "hunter_match")
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("Wallet Hunter")
                .setContentText("Match found — unlock to see it")
                .build()
            val notif = androidx.core.app.NotificationCompat.Builder(this, "hunter_match")
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publica)
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

    // Aqui vivian thermalThrottleEnabled, lastThermalCheck, originalCpuLimit e
    // isThrottled: cuatro variables declaradas y jamas leidas ni escritas, o
    // sea la proteccion termica entera sin conectar. Ahora existe de verdad y
    // vive en Termico, dentro del servicio, que es el unico sitio donde sigue
    // funcionando con la pantalla apagada.
    private var tvThermal: TextView? = null

    override fun onResume() {
        super.onResume()
        handler.post(updater)
        // Antes esto miraba cuanto habia pasado desde su propio onPause, con
        // un limite de 15 s. Pero onPause salta al abrir la cartera, el
        // cluster, las estadisticas o el selector de ficheros, asi que volver
        // de cualquiera de ellas pasados quince segundos pedia el PIN sin que
        // nadie hubiera salido de la app. Ahora lo decide AppLock, que sabe la
        // diferencia entre cambiar de pantalla y dejar la app.
        if (WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid()) {
            PinAuthHelper.show(this) { ok -> if (!ok) finish() }
        }
        // Auto-reinicio: si el engine estaba corriendo pero el servicio fue matado
        checkAndRestartScan()
    }

    private fun checkAndRestartScan() {
        val wasRunning = prefs.getBoolean("scan_was_running", false)
        if (!wasRunning) return
        if (HunterEngine.isRunning()) return // ya está corriendo
        // Y no se ofrece reanudar lo que ya se encontró. Es un segundo cierre
        // del mismo agujero: si por cualquier camino la marca se quedara
        // puesta, aquí no se puede acabar preguntando si reanudar una búsqueda
        // terminada.
        if (try { HunterEngine.objetivoHallado() } catch (e: Throwable) { false }) {
            prefs.edit().putBoolean("scan_was_running", false).apply()
            return
        }

        // El scan estaba activo pero fue matado — preguntar si reiniciar
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (HunterEngine.isRunning()) return@postDelayed // doble check
            val mode = if (prefs.getBoolean("scan_was_puzzle", false)) "Puzzle" else "BIP39"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Scan interrupted")
                .setMessage("The $mode scan was interrupted. Restart?")
                .setPositiveButton("Reset") { _, _ ->
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
        savePuzzleCheckpoint()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePuzzleCheckpoint()
        batteryReceiver?.let { unregisterReceiver(it) }
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
