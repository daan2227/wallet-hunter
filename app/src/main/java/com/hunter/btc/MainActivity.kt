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
    internal val BG_DEEP   get() = AppTheme.BG_DEEP
    internal val BG_CARD   get() = AppTheme.BG_CARD
    internal val BG_ELEV   get() = AppTheme.BG_ELEV
    internal val AMBER     get() = AppTheme.AMBER
    internal val RED       get() = AppTheme.RED
    internal val CYAN      get() = AppTheme.CYAN
    internal val TXT_PRI   get() = AppTheme.TXT_PRI
    internal val TXT_SEC   get() = AppTheme.TXT_SEC
    internal val TXT_MUTED get() = AppTheme.TXT_MUTED
    internal val BORDER_C  get() = AppTheme.BORDER_C
    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── Páginas y barra de pestañas ─────────────────────────────────────────
    //
    // Posiciones FIJAS. Antes la lista se hacía con listOfNotNull(...), así que
    // si una página no se podía montar —Puzzle tiene su propio try/catch para
    // eso— las de detrás se corrían un puesto: tocar "Wallet" enseñaba
    // Recovery. Ahora una página que falta deja su hueco y las demás siguen
    // donde estaban.
    internal val PAG_SCANNER  = 0
    internal val PAG_PUZZLE   = 1
    internal val PAG_WALLET   = 2
    internal val PAG_RECOVERY = 3
    internal val PAG_MORE     = 4
    internal var tabPages: List<android.view.View?> = emptyList()
    internal var barra: BottomBar? = null
    internal var paginaActual = PAG_SCANNER

    internal fun goTab(idx: Int) {
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
    internal fun pestana(tab: Int) {
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
    internal fun abrirPestanaPedida(i: Intent?) {
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
    internal var sessionStartTime = 0L
    internal var sessionStartCount = 0L
    internal var batteryReceiver: android.content.BroadcastReceiver? = null
    internal val NOTIF_ID = 42
    internal val handler = Handler(Looper.getMainLooper())
    internal var tvStatus: TextView? = null
    internal var tvCsvName: TextView? = null
    internal var tvQuickCsv: TextView? = null
    internal var tvQuickMatches: TextView? = null
    internal var tvWps: TextView? = null
    internal var tvKps: TextView? = null
    internal var tvQuickThreads: TextView? = null
    internal var tvQuickCpu: TextView? = null
    internal var fastModeEnabled = false
    internal var tvCount: TextView? = null
    internal var chartView: SpeedChartView? = null
    internal var tvMediaPuzzle: TextView? = null
    /** Cuándo se tomó la última muestra de la gráfica, y con qué contador. */
    internal var kgMuestraMs = 0L
    internal var kgMuestraOps = 0L
    internal var kgMuestrasSinGuardar = 0
    internal var tvPuzzleStatus: TextView? = null
    internal var tvTime: TextView? = null
    internal var tvMatches: TextView? = null
    internal var tvRam: TextView? = null
    internal var tvTemp: TextView? = null
    internal var tvBattery: TextView? = null
    internal var tvFooter: TextView? = null
    internal var btnToggle: Button? = null
    /** Línea de estado del escáner: "Buscando · 51 s". */
    internal var tvScanState: TextView? = null
    internal var scanStateDot: android.view.View? = null
    /** Resumen a la derecha de las filas de ajuste: "8 hilos · 100 %". */
    internal var tvEngineSummary: TextView? = null
    internal var tvClusterSummary: TextView? = null
    /** "Sin atajo: fuerza bruta" / "Admite Kangaroo". */
    internal var tvPuzzleAtajo: TextView? = null
    /** Botón de Kangaroo: sólo aparece si la clave pública es conocida. */
    internal var btnKangaroo: Button? = null
    /** Clave pública del puzzle elegido, si está publicada. */
    internal var puzzlePubHex: String = ""
    internal var puzzleIniHex: String = ""
    internal var puzzleFinHex: String = ""
    internal var ultimoGuardadoKg = 0L
    internal var kangarooReinicios = 0
    /** A qué puzzle pertenece la respuesta que estamos esperando. */
    internal var puzzleSeleccionado = -1
    /** Etiquetas de las tarjetas de estadística: cambian según el modo. */
    internal var lblEscaneadas: TextView? = null
    internal var lblRestantes: TextView? = null
    /** Para calcular la velocidad de Kangaroo, que no pasa por HunterEngine. */
    internal var kgInicio = 0L
    internal var kgUltOps = 0L
    internal var kgUltMs = 0L
    internal var kgOpsSeg = 0.0
    /** Operaciones que se esperan: ~2,2·raíz(W). */
    internal var kgOpsEsperadas = 0.0
    /** Segundos de búsqueda de sesiones anteriores, para que el reloj cuadre
     *  con el contador de operaciones, que también es acumulado. */
    internal var kgSegPrevios = 0L
    /** La clave cuyo resultado ya se ha pintado, para pintarlo una sola vez. */
    internal var kgClavePintada = ""
    /** Título de la pantalla en la cabecera, que cambia con la pestaña. */
    internal var sbThreads: SeekBar? = null
    internal var sbCpu: SeekBar? = null
    // Puzzle tiene sus propios sliders independientes
    internal var sbThreadsPuzzle: SeekBar? = null
    internal var sbCpuPuzzle: SeekBar? = null
    internal var tvThreadsPuzzle: TextView? = null
    internal var tvCpuPuzzle: TextView? = null
    internal var tvThreads: TextView? = null
    internal var tvCpu: TextView? = null
    internal var btnCsv: Button? = null
    internal var etRangeStart: EditText? = null
    internal var etRangeEnd: EditText? = null
    internal var currentRangeStart: String = ""
    internal var currentRangeEnd: String = ""
    internal val BLOCK_SIZE = java.math.BigInteger("1000000000") // 1B keys por bloque
    internal val REQ_IMPORT_PROGRESS = 1003
    internal var currentBlockId: String = ""
    internal var tvBlockProgress: TextView? = null
    /**
     * La tarjeta de "Range coverage". Se esconde mientras corre Kangaroo:
     * Kangaroo NO recorre el rango bloque a bloque, da saltos por él, así que
     * esa cobertura se queda clavada en 0,0000 % para siempre por bien que vaya
     * la búsqueda. Enseñar un 0 % junto a una búsqueda sana es peor que no
     * enseñar nada.
     */
    internal var cardCobertura: android.view.View? = null
    internal var etTarget: EditText? = null
    internal var csvPath: String = ""
    internal var s = Strings.EN
    internal var puzzleMode = false
    internal var selectedScanMode = 0 // 0=BIP39, 2=RawKey
    internal var fastScanRow: android.view.View? = null
    internal var tvBinInfoRef: TextView? = null
    internal var tvDatasetStat: TextView? = null
    internal var peakWps: Double = 0.0
    internal var avgWpsSum: Double = 0.0
    internal var avgWpsCount: Long = 0
    internal val numberFmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
    internal var cachedPuzzleLabel: String = ""
    internal var cachedPuzzleLabelForStart: String = ""
    internal var tvPeakWps: TextView? = null
    internal var tvPeakWpsPuzzle: TextView? = null
    internal var watchdogEnabled = false
    internal var lastKnownRunning = false
    internal var tvRandomJump: TextView? = null
    internal var tvCurrentBlock: TextView? = null
    /** Bloque elegido a mano con "Saltar a un punto aleatorio"; lo usa el próximo START. */
    internal var pendingBlockIdx: java.math.BigInteger? = null
    /** Reinicios hechos por el watchdog en esta sesión; se muestra en su etiqueta. */
    internal var watchdogRestarts = 0
    internal var activeToggleBtn: Button? = null
    internal var tvWpsPuzzle: TextView? = null
    internal var tvPctPuzzle: TextView? = null
    internal var tvSpeedUnitPuzzle: TextView? = null
    internal var tvSpeedUnitScan: TextView? = null
    /* Rango completo del puzzle. currentRangeStart/End apuntan al BLOQUE en
       curso (BLOCK_SIZE claves), así que usarlos para el progreso global
       comparaba la sesión entera contra un bloque y daba "1 de 0". */
    internal var puzzleProgressUpdater: ((Int, String, String) -> Unit)? = null
    internal var lastProgressTick = 0L
    /** Último getFound() visto, para saber cuándo hay aciertos nuevos que guardar. */
    internal var lastFoundSeen = -1L
    internal var puzzleFullStart: String = ""
    internal var puzzleFullEnd: String = ""

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
    internal fun currentPuzzleNum(): Int =
        puzzles.firstOrNull { it.start == puzzleFullStart }?.num
            ?: prefs.getInt("current_puzzle_num", 0)

    internal fun watchdogLabel() = when {
        // watchdogRestarts sólo se incrementaba y no se leía en ningún sitio.
        // Puesto aquí sirve para saber si de verdad está haciendo algo.
        watchdogEnabled && watchdogRestarts > 0 ->
            "Watchdog ON — $watchdogRestarts restart(s) this session"
        watchdogEnabled -> "Watchdog ON — restarts the scan if it stops"
        else            -> "Watchdog OFF — will not restart the scan"
    }

    internal fun scaleSpeed(keysPerSec: Double): Pair<String, String> = when {
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
    internal fun formatEta(secs: java.math.BigInteger): String {
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

    internal fun formatPuzzleProgress(scanned: Long, start: String, end: String): String {
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
    /**
     * Operaciones que cuesta resolver un rango de ancho W, en raíces de W.
     *
     * Era 2,4: lo medido con Kangaroo. El motor usa ahora Gaudry-Schost con
     * mapa de negación (ver kg_negacion en kangaroo.h), que medido con
     * tools/ec-harness/negacion da 1,4-1,6 cuando el dbits es pequeño frente
     * al rango, que es lo que pasa en los puzzles donde se usa (#135 en
     * adelante), y 1,38 con los terrenos y saltos ajustados (KG_VER_GS 6).
     * La teoría dice 1,36.
     */
    internal val COSTE_KANGAROO = 1.4

    internal var tvCheckpointLive: TextView? = null
    internal var tvCountPuzzle: TextView? = null
    internal var tvTimePuzzle: TextView? = null
    internal var btnPuzzleToggle: Button? = null
    internal var recoveryEngine: RecoveryEngine? = null
    internal val prefs get() = getSharedPreferences("hunter", MODE_PRIVATE)

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
    internal val puzzles = listOf(
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

    internal val updater = object : Runnable {
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


    // buildMoreTab(): en PestanaMore.kt

    // buildScanTab(): en PestanaScanner.kt

    // buildPuzzleTab(): en PestanaPuzzle.kt

    // buildWalletTab(): en PestanaWallet.kt

    // buildRecoveryTab(): en PestanaRecovery.kt

    // ── FUNCIONES AUXILIARES ──────────────────────────────────────────────────

    internal fun mkSbl(cb: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { cb() }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }


    internal fun updateLabels() {
                val t = (sbThreads?.progress ?: 3) + 1
        val c = (sbCpu?.progress ?: 70) + 10
        tvThreads?.text = "Threads: $t"
        tvCpu?.text = "CPU limit: $c%"
        tvQuickThreads?.text = "$t"
        tvQuickCpu?.text = "$c%"
        prefs.edit().putInt("threads", sbThreads?.progress ?: 3).putInt("cpu", sbCpu?.progress ?: 70).apply()
    }

    internal fun updatePuzzleLabels() {
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
    internal fun aplicarAfinidad(hilos: Int) = Hardware.aplicarAfinidad(hilos)
    internal fun detectHardware(): HardwareProfile = Hardware.detectar(this)
    internal fun nucleosRapidos(): IntArray = Hardware.nucleosRapidos()

    internal fun applyHardwareProfile(profile: HardwareProfile) {
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

    internal fun showHardwareInfo() {
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

    internal fun formatCount(v: Long): String = numberFmt.format(v)

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
    internal fun formatCorto(v: Long): String = when {
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
    internal fun migrarTablaPuzzles() {
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

    internal fun getBlockPrefs() = getSharedPreferences("puzzle_blocks", MODE_PRIVATE)

    /** Entero aleatorio uniforme entre 0 y bound-1. Por rechazo: <2 intentos de media. */
    internal fun randomBelow(bound: java.math.BigInteger): java.math.BigInteger {
        if (bound <= java.math.BigInteger.ONE) return java.math.BigInteger.ZERO
        val rnd = java.security.SecureRandom()
        val bits = bound.bitLength()
        var r: java.math.BigInteger
        do { r = java.math.BigInteger(bits, rnd) } while (r >= bound)
        return r
    }

    /** Número total de bloques del rango de un puzzle. */
    internal fun totalBlocksOf(rangeStart: String, rangeEnd: String): java.math.BigInteger {
        val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
        val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
        return end.subtract(start).divide(BLOCK_SIZE).max(java.math.BigInteger.ONE)
    }

    /** Traduce un índice de bloque al rango hexadecimal que entiende el motor. */
    internal fun blockRange(rangeStart: String, rangeEnd: String,
                           blockIdx: java.math.BigInteger): Pair<String, String> {
        val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
        val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
        val bStart = start.add(BLOCK_SIZE.multiply(blockIdx))
        val bEnd   = bStart.add(BLOCK_SIZE).min(end)
        return Pair(bStart.toString(16).padStart(18, '0'),
                    bEnd.toString(16).padStart(18, '0'))
    }

    /** Posición del bloque dentro del rango, en porcentaje. */
    internal fun blockPercent(blockIdx: java.math.BigInteger,
                             totalBlocks: java.math.BigInteger): Double =
        if (totalBlocks.signum() <= 0) 0.0
        else blockIdx.toBigDecimal()
            .divide(totalBlocks.toBigDecimal(), 8, java.math.RoundingMode.HALF_UP)
            .toDouble() * 100.0

    internal fun getNextUnscannedBlock(puzzleNum: Int, rangeStart: String, rangeEnd: String): Pair<String, String>? {
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
    internal fun setCurrentBlockLabel(prefix: String, blockIdx: java.math.BigInteger) {
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

    internal fun pickRandomJump() {
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

    internal fun markBlockScanned(puzzleNum: Int) {
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

    internal fun getBlockProgressText(puzzleNum: Int, rangeStart: String, rangeEnd: String): String {
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
    internal fun formatSegundos(elapsed: Long): String {
        if (elapsed <= 0) return "00:00:00"
        val d  = elapsed / 86400
        val h  = (elapsed % 86400) / 3600
        val m  = (elapsed % 3600) / 60
        val sc = elapsed % 60
        return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, sc)
               else "%02d:%02d:%02d".format(h, m, sc)
    }

    internal fun formatElapsed(startTimeMs: Long): String {
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

    internal var peakLabel = ""

    /** "Buscando · 51 s" o "Idle", con su punto. */
    internal fun paintScanState(running: Boolean) {
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
    internal fun paintSettingSummaries() {
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
    internal fun pintarTermico() {
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

    internal fun updateUI() {
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
    internal fun syncToggleButton(btn: Button?, running: Boolean, textoStart: String) {
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
    internal fun puzzleTargetAddr(): String = etTarget?.text?.toString()?.trim() ?: ""

    internal fun engineHasSomethingToMatch(): Boolean =
        // El puzzle busca UNA dirección y no necesita dataset ninguno; el
        // escáner compara contra la lista. Antes esto era
        // `isCsvLoaded() || hasTarget()`, y como nadie llamaba nunca a
        // setTarget(), hasTarget() era siempre false: el puzzle acababa pidiendo
        // el dataset igual que el escáner.
        if (puzzleMode) puzzleTargetAddr().isNotEmpty()
        else            HunterEngine.isCsvLoaded()

    internal fun doToggle(callerBtn: Button? = null) {
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

    internal fun pickCsv() {
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

    internal fun exportPuzzleProgress() {
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

    internal fun importPuzzleProgress() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQ_IMPORT_PROGRESS)
    }

    internal fun processImportedProgress(uri: android.net.Uri) {
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
    internal fun exportEncryptedBackup() {
        MatchVault.recoger(this)
        startActivity(Intent(this, WalletActivity::class.java).apply {
            putExtra("MODE", "seed")
            putExtra("OPEN_BACKUP_VAULT", true)
        })
    }

    /** El resumen de hallazgos para compartir: ver ExportarResumen. */
    internal fun exportLog() = ExportarResumen.exportar(this)


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
     * de la raíz cuadrada, unas 1,5 veces (ver COSTE_KANGAROO).
     */
    internal fun comprobarAtajo(p: PuzzleInfo) {
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
            // Medido, no estimado. Ver COSTE_KANGAROO.
            val opsKangaroo = COSTE_KANGAROO * Math.pow(2.0, bits / 2.0)
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
    internal fun auditarClavesPublicas() {
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
    internal fun alternarKangaroo() {
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
    internal fun prepararContadoresKangaroo(pub: String, ini: String, fin: String) {
        kgInicio = System.currentTimeMillis()
        kgClavePintada = ""       // una búsqueda nueva pinta su resultado otra vez
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
        // Ver COSTE_KANGAROO.
        kgOpsEsperadas = try {
            val a = java.math.BigInteger(ini, 16)
            val b = java.math.BigInteger(fin, 16)
            COSTE_KANGAROO * Math.pow(2.0, (b.subtract(a).bitLength()) / 2.0)
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
    internal fun mostrarClaveDeWorker(dispositivo: String, claveHex: String) {
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
    internal var kgUltSaldoMs = 0L
    internal var kgSaldoPedido = false

    internal fun vigilarSaldoDelPuzzle() {
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
    internal fun guardarMuestras() {
        val pts = chartView?.getPoints() ?: return
        prefs.edit().putString("kangaroo_grafica_${puzzlePubHex.take(16)}",
            pts.joinToString(",") { it.toLong().toString() }).apply()
    }

    internal fun recuperarMuestras() {
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
    internal fun adoptarKangaroo() {
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
    internal fun textoDeLaTabla(dps: Long): String {
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

    // Ritmo de la GPU, aparte: los saltos de la GPU ya van en el total, esto
    // dice cuántos son suyos.
    internal var kgGpuUlt = 0L
    internal var kgGpuUltMs = 0L
    internal var kgGpuSeg = 0.0
    internal fun lineaGpu(ahoraMs: Long): String {
        val estado = try { HunterEngine.gpuEstado() } catch (e: Throwable) { "" }
        if (estado.isEmpty() || estado == "off") return ""
        val n = try { HunterEngine.gpuSaltos() } catch (e: Throwable) { 0L }
        if (n < kgGpuUlt) { kgGpuUlt = 0L; kgGpuUltMs = 0L; kgGpuSeg = 0.0 }
        if (kgGpuUltMs > 0 && ahoraMs - kgGpuUltMs >= 1000) {
            val inst = (n - kgGpuUlt) * 1000.0 / (ahoraMs - kgGpuUltMs)
            kgGpuSeg = if (kgGpuSeg <= 0) inst else kgGpuSeg * 0.7 + inst * 0.3
            kgGpuUlt = n; kgGpuUltMs = ahoraMs
        } else if (kgGpuUltMs == 0L) { kgGpuUlt = n; kgGpuUltMs = ahoraMs }
        return when {
            estado.startsWith("running") -> {
                val (v, u) = scaleSpeed(kgGpuSeg)
                "\nGPU: $v $u op/s (${estado.removePrefix("running on ")})"
            }
            else -> "\nGPU: $estado"
        }
    }

    /** Se llama desde updateUI(): progreso y resultado. */
    internal fun refrescarKangaroo() {
        val tv = tvPuzzleAtajo ?: return
        val clave = try { HunterEngine.kangarooResult() } catch (e: Throwable) { "" }
        if (clave.length == 64) {
            // Esto se repite en cada refresco mientras el motor siga teniendo
            // el resultado. Las cifras finales se leen la PRIMERA vez, antes de
            // parar: después el contador puede volver a cero.
            val primera = kgClavePintada != clave
            val opsFin = if (primera) (try { HunterEngine.kangarooOps() } catch (e: Throwable) { 0L }) else 0L
            val segFin = if (primera && kgInicio > 0)
                kgSegPrevios + (System.currentTimeMillis() - kgInicio) / 1000 else 0L
            // Guardar ANTES de tocar la interfaz: si la app muere aquí, la
            // clave no puede perderse.
            guardarHallazgoKangaroo(clave, "PUZZLE kangaroo")
            HunterEngine.kangarooStop()
            if (!primera) return
            kgClavePintada = clave
            // Un puzzle pequeño se resuelve en menos de un segundo, antes del
            // primer refresco: las tarjetas se quedaban en 0 y 00:00:00 con la
            // clave ya encontrada. Se dejan puestas las cifras finales.
            tvCountPuzzle?.text = formatCorto(opsFin)
            // Un puzzle de prueba se resuelve en décimas: "00:00:00" parecía
            // que no había contado.
            tvTimePuzzle?.text = if (segFin < 1) "< 1 s" else formatSegundos(segFin)
            tvPctPuzzle?.text = "done"
            // Cuánto ha costado frente a lo esperado: es la cifra que dice si
            // el motor rinde (1,5 raíces de W de media; una sola búsqueda
            // puede salir bastante por encima o por debajo).
            val raices = if (kgOpsEsperadas > 0 && opsFin > 0)
                opsFin / (kgOpsEsperadas / COSTE_KANGAROO) else 0.0
            val coste = if (raices > 0) "\n${formatCorto(opsFin)} operations · " +
                "%.2f × √W".format(raices) +
                // La media de 1,5 es la de los rangos grandes. En uno pequeño
                // pesa el coste fijo de llegar al primer distinguido, que ahí
                // es del orden del problema entero, y sale bastante más.
                (if (raices > 3 * COSTE_KANGAROO)
                    "\n(the average is %.1f on large ranges; a small test ".format(COSTE_KANGAROO) +
                    "pays the fixed cost of reaching the first distinguished points)"
                 else " (average %.1f)".format(COSTE_KANGAROO)) else ""
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
                    "KEY FOUND\n$clave\nSaved to the finds vault." + coste
                esperada == clave.lowercase() ->
                    "TEST PASSED — #$puzzleSeleccionado\n$clave\n" +
                    "Matches the known key: the engine works end to end." + coste
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
        tvPeakWpsPuzzle?.text = textoDeLaTabla(dps) + lineaGpu(ahoraMs)
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

    internal fun checkPuzzleBalance(addr: String, onResult: (Long) -> Unit) {
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
    internal fun startClusterMaster() {
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
    internal fun guardarHallazgoKangaroo(claveHex: String, deDonde: String) {
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

    internal fun applyPuzzle(p: PuzzleInfo) {
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
    internal fun setupNotificationChannel() {
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
    internal fun sendMatchNotification(titulo: String, texto: String) {
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

    internal fun registerBatteryReceiver() {
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
    internal var tvThermal: TextView? = null

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

    internal fun checkAndRestartScan() {
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
    internal fun purgeLegacyRecoveryFiles() {
        try {
            getExternalFilesDir(null)
                ?.listFiles { f -> f.name.startsWith("recovery_") && f.name.endsWith(".txt") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "purge recovery files: ${e.message}")
        }
    }

    internal fun savePuzzleCheckpoint() {
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
