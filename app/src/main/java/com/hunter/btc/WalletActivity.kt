package com.hunter.btc

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import android.text.InputType
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject

/** Fila de saldo: el Triple anterior no tenía sitio para la fuente del dato. */
private data class BalanceRow(
    val label: String,
    val addr: String,
    val sat: Long,
    val source: String
)

class WalletActivity : FragmentActivity() {
    private val REQ_IMPORT_BACKUP = 1002
    private val REQ_QR = 1003
    /** Qué hacer con el texto de un QR leído: lo pone la pestaña Enviar. */
    private var alLeerQr: ((String) -> Unit)? = null

    private val AMBER     get() = AppTheme.AMBER
    private val GREEN     get() = AppTheme.GREEN
    private val RED       get() = AppTheme.RED
    private val BG_DEEP   get() = AppTheme.BG_DEEP
    private val BG_PANEL  get() = AppTheme.BG_PANEL
    private val BG_CARD   get() = AppTheme.BG_CARD
    private val BG_ELEV   get() = AppTheme.BG_ELEV
    private val TXT_PRI   get() = AppTheme.TXT_PRI
    private val TXT_SEC   get() = AppTheme.TXT_SEC
    private val TXT_MUTED get() = AppTheme.TXT_MUTED
    private val BORDER_C  get() = AppTheme.BORDER_C

    private var actionBar: LinearLayout? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * La cartera enseña por defecto lo que usa casi todo el mundo: crear o importar una
     * seed, enviar, recibir, ver el historial y el saldo, el PIN y la copia de seguridad.
     * Lo demás (varias salidas, OP_RETURN, PSBT, control de monedas, tarifa a mano, CPFP,
     * CSV, xpub, cuentas, testnet, WIF, cartera vigilada, verificación con la red…) está
     * detrás de "Advanced options" en el menú. Nada de eso se ha quitado: sólo se esconde.
     */
    private fun avanzado(): Boolean = getSharedPreferences("hunter", MODE_PRIVATE).getBoolean("modo_avanzado", false)

    /** "  (≈ 12.34 USD)" para pegar a una cantidad en BTC o sat; vacío en testnet o sin precio. */
    private fun fiatEntre(sat: Long): String = if (isTestnet) "" else Fiat.entre(this, sat)

    /**
     * Pone en [tv] el valor en dinero normal de [sat]. Si aún no se sabe el precio lo pide
     * (una sola consulta para toda la pantalla) y lo rellena al llegar.
     */
    private fun ponerFiat(tv: TextView, sat: Long, prefijo: String = "≈ ") {
        if (isTestnet) return
        fiatViews.add(Triple(tv, sat, prefijo))
        if (fiatViews.size > 500) fiatViews.removeAt(0)
        // Con los saldos ocultos se tapa igual que la cifra en BTC (Privacidad.monto).
        val pintar = { Fiat.valor(this, sat)?.let { tv.text = Privacidad.monto(this, prefijo + it) } }
        pintar()
        if (Fiat.vencidoONulo(this)) Fiat.cargar(this) { runOnUiThread { pintar() } }
    }

    /** Traduce una excepción de red al idioma de alguien que no la ha escrito. */
    private fun motivo(e: Exception): String = when {
        e is java.net.UnknownHostException ->
            "No connection: could not resolve the server."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
            "No server answered. Check your connection and try again."
        e.message?.contains("failed to connect", true) == true ->
            "Could not connect. Check your connection."
        else -> e.message ?: e.javaClass.simpleName
    }
    private fun cardBg() = GradientDrawable().apply {
        setColor(BG_CARD)
        cornerRadius = dp(AppTheme.R_CARD).toFloat()
    }

    private var mnemonic = ""
    private var wifKey = ""
    private var wifAddr = ""
    private var isWifMode = false
    private var currentWalletId = ""
    private var currentWalletName = ""
    /** Último saldo consultado, en sats. -1 = aún no se sabe. Lo usa el aviso
     *  de borrado para no dejar tirar una cartera con dinero dentro. */
    private var ultimoSaldoSat = -1L
    /** Sube al cambiar de cartera: lo que acaba después ya no es de la pantalla. */
    private var generacion = 0
    /** Direcciones que la búsqueda ha visto usadas, también las de cambio ya gastado. */
    private val historialExtra = linkedMapOf<String, String>()
    /** Mientras la búsqueda de direcciones corre, el historial puede estar incompleto. */
    private var descubriendo = false
    /** Páginas de 25 transacciones que se piden por dirección en el historial. */
    private val MAX_PAGINAS_HISTORIAL = 40
    /**
     * Las otras direcciones de la clave WIF (P2WPKH, P2SH-P2WPKH, P2TR). Entran
     * en el saldo y en el historial, pero NO en `addresses`: esa lista es la de
     * Enviar, y firmar desde estos tipos todavía no está soportado.
     */
    private val wifExtras = linkedMapOf<String, String>()
    /**
     * Red de pruebas.
     *
     * Se guarda en preferencias. Antes era una variable suelta: al recrearse la
     * pantalla —girar el móvil, volver atrás, o que Android matara el proceso—
     * volvía sola a la red principal sin avisar, y te encontrabas enviando en
     * mainnet creyendo que estabas en pruebas.
     */
    private var isTestnet: Boolean
        get() = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getBoolean("testnet", false)
        set(v) { getSharedPreferences("app_settings", MODE_PRIVATE)
                    .edit().putBoolean("testnet", v).apply() }
    private var selectedUtxos = mutableListOf<org.json.JSONObject>()
    private var addresses = mutableMapOf<String, String>()
    /**
     * Cantidades de la pestaña Balance con su texto real, para taparlas y
     * destaparlas con el ojo sin volver a preguntar a la red.
     */
    private val montos = mutableListOf<Pair<TextView, String>>()

    private fun monto(tv: TextView, real: String) {
        montos.add(tv to real)
        tv.text = Privacidad.monto(this, real)
    }

    private fun repintarMontos() {
        montos.forEach { (tv, real) -> tv.text = Privacidad.monto(this, real) }
        fiatViews.forEach { (tv, sat, pre) -> Fiat.valor(this, sat)?.let { tv.text = Privacidad.monto(this, pre + it) } }
    }

    /** Valores en dinero normal ya pintados, para taparlos y destaparlos con el ojo. */
    private val fiatViews = ArrayList<Triple<TextView, Long, String>>()
    // Aqui vivian isLocked, lastInteraction y AUTO_LOCK_MS. Los tres estan
    // fuera: el bloqueo lo decide AppLock para toda la app. AUTO_LOCK_MS
    // ademas no se leia en ningun sitio —el cierre por inactividad a los dos
    // minutos nunca llego a existir— y lastInteraction se actualizaba en cada
    // toque para nada. Dejar estado de bloqueo muerto en el fichero cuyo
    // bloqueo se acaba de rehacer es como vuelve el fallo.
    private lateinit var tabContent: FrameLayout
    private val labelMap = mapOf(
        "p2pkh_0" to "P2PKH [0]", "p2pkh_1" to "P2PKH [1]", "p2pkh_2" to "P2PKH [2]",
        "p2sh_0"  to "P2SH  [0]",
        "p2wpkh_0" to "WPKH  [0]", "p2wpkh_1" to "WPKH  [1]",
        "p2tr_0" to "P2TR  [0]", "p2tr_1" to "P2TR  [1]"
    )

    /* -- LIFECYCLE -- */
    override fun onCreate(s: Bundle?) {
        // Antes de super.onCreate: es el único momento en que el tema se
        // puede cambiar, y de él salen los colores de todos los diálogos.
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        PantallaSegura.aplicar(this)
        // El tema es un objeto de proceso: lo fija la primera pantalla que
        // arranca. Si Android mata el proceso y lo revive directamente aqui
        // —desde una notificacion, o al volver a la tarea—, esa primera
        // pantalla es esta, y sin esto se pintaria en oscuro aunque el
        // usuario tenga elegido el claro.
        AppTheme.init(this)
        AppLock.init(this)
        overridePendingTransition(0, 0)
        val mode = intent.getStringExtra("MODE") ?: ""
        when (mode) {
            "seed" -> {
                val walletId = intent.getStringExtra("WALLET_ID") ?: ""
                currentWalletName = if (walletId.isEmpty()) WalletManager.mainName(this) else walletId
                isWifMode = false
                conSesion {
                    mnemonic = if (walletId.isEmpty()) WalletManager.loadSeed(this) ?: ""
                               else WalletManager.loadWalletSeed(this, walletId) ?: ""
                    currentWalletId = walletId
                    loadAddresses(); buildUI()
                    // Entrada directa al baúl desde la pestaña Wallet del hunter,
                    // para no obligar a buscarlo dentro del menú "...".
                    if (intent.getBooleanExtra("OPEN_BACKUP_VAULT", false)) showBackupVault()
                }
            }
            "wif" -> {
                wifKey  = intent.getStringExtra("WIF_KEY") ?: ""
                wifAddr = intent.getStringExtra("WIF_ADDR") ?: ""
                currentWalletName = intent.getStringExtra("WALLET_NAME") ?: "WIF Wallet"
                isWifMode = true
                conSesion {
                    loadAddresses(); buildUI()
                }
            }
            "watch" -> {
                wifKey  = ""
                wifAddr = intent.getStringExtra("WIF_ADDR") ?: ""
                currentWalletName = intent.getStringExtra("WALLET_NAME") ?: "Watch"
                isWifMode = true
                loadAddresses(); buildUI()
            }
            "create" -> {
                showCreateDialog()
            }
            "setup" -> {
                showSetupDialog()
            }
            "wif_import" -> {
                showWifImportDialog()
            }
            "watch_import" -> {
                showWatcherImportDialog()
            }
            else -> {
                /* Puzzle match o legacy */
                val intentWif = intent.getStringExtra("WIF_KEY") ?: ""
                val intentAddr = intent.getStringExtra("WIF_ADDR") ?: ""
                if (intentWif.isNotEmpty()) {
                    wifKey = intentWif; wifAddr = intentAddr; isWifMode = true
                    currentWalletName = "Puzzle Match"
                    WalletManager.saveWif(this, intentWif, intentAddr, origen = "puzzle")
                    loadAddresses(); buildUI()
                } else {
                    showWalletSelectorDialog()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // isLocked se ponia en CADA onPause. onPause salta cuando se pone
        // delante cualquier otra pantalla: el baul, el selector de ficheros,
        // el dialogo de compartir. Volver de cualquiera de ellos re-pedia la
        // huella o el PIN, aunque hubieran pasado dos segundos y el usuario
        // no se hubiera movido de la app. Ahora la sesion la cierra AppLock
        // —pantalla apagada, app en segundo plano, proceso nuevo— y aqui solo
        // se pregunta si sigue abierta.
        val hayQueAbrir = WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid()
        if (hayQueAbrir && (mnemonic.isNotEmpty() || wifKey.isNotEmpty())) {
            /* Mostrar overlay oscuro mientras autentica */
            val overlay = android.widget.FrameLayout(this).apply {
                setBackgroundColor(AppTheme.BG_DEEP)
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val lockIcon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_lock)
                setColorFilter(AppTheme.TXT_MUTED)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                    gravity = Gravity.CENTER
                }
            }
            overlay.addView(lockIcon)
            (window.decorView as? android.view.ViewGroup)?.addView(overlay)
            authenticate(huella = true) {
                (window.decorView as? android.view.ViewGroup)?.removeView(overlay)
            }
        }
    }

    override fun onBackPressed() {
        /* Si no hay wallet cargada cerrar directamente sin mostrar fondo */
        if (mnemonic.isEmpty() && wifKey.isEmpty()) {
            finish()
            overridePendingTransition(0, 0)
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Entrar en la cartera con la sesion ya abierta.
     *
     * Abrir la cartera desde el cajon pedia el PIN DOS veces seguidas por una
     * sola navegacion: una en MainActivity, que comprueba la sesion antes de
     * cambiar de pestana, y otra aqui nada mas crearse la pantalla, que
     * llamaba a authenticate sin mirar nada. Dos candados en fila en el mismo
     * paso no protegen el doble; solo ensenan a teclear el PIN sin pensar.
     *
     * Si no hay PIN configurado, isSessionValid es false para siempre y
     * authenticate se encarga: su showPinDialog no tiene nada que pedir.
     */
    private fun conSesion(onSuccess: () -> Unit) {
        if (WalletManager.hasPin(this) && PinAuthHelper.isSessionValid()) onSuccess()
        else authenticate(huella = true, onSuccess = onSuccess)
    }

    /**
     * Guardar una cartera nueva, con el PIN que haga falta.
     *
     * Añadir una cartera pedía SIEMPRE "Choose a PIN" —showPinDialog con
     * isSetup = true— y al confirmarlo llamaba a savePin: REEMPLAZABA el PIN de
     * la app por lo que se tecleara ahí. Es el mismo PIN que abre la app al
     * arrancar y el que cifra las copias de seguridad, así que teclear otro
     * distinto lo cambiaba sin avisar. De ahí el "¿para qué es este PIN, si ya
     * tengo uno?".
     *
     * Ahora: si ya hay PIN, se usa ese —y sólo se pide si la sesión está
     * cerrada—; si no hay ninguno, entonces sí se elige, porque una cartera
     * sin PIN no la protege nada.
     */
    private fun guardarConPin(onOk: () -> Unit, onCancel: () -> Unit) {
        // sinPinDeliberado: se quitó el código a propósito (Perfil, "entornos seguros"); eso no es
        // "sin ninguno todavía" — conSesion() ya sabe abrir sin pedir nada en ese caso. Sin esto,
        // guardar una cartera nueva después de quitar el código volvía a forzar "elige uno".
        if (WalletManager.hasPin(this) || WalletManager.sinPinDeliberado(this)) conSesion(onOk)
        else showPinDialog(isSetup = true) { ok -> if (ok) onOk() else onCancel() }
    }

    /** El campo "Name" de los diálogos de añadir y de renombrar. */
    private fun campoNombre(): EditText = EditText(this).apply {
        hint = "Name (optional)"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
        background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12)); textSize = AppTheme.SP_BODY
        minHeight = dp(48); isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(32))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    /** Pide un nombre nuevo y se lo pasa, ya limpio, a [alGuardar]. */
    private fun renombrar(actual: String, alGuardar: (String) -> Unit) {
        val et = campoNombre().apply { hint = "Name"; setText(actual); setSelection(text.length) }
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename wallet")
            .setView(caja)
            .setPositiveButton("Save") { _, _ ->
                val n = WalletManager.limpiarNombre(et.text.toString())
                if (n.isNotEmpty()) alGuardar(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* -- AUTH: biometria con fallback a PIN -- */
    /**
     * Esta pantalla no pasa por PinAuthHelper: tiene su propia huella con
     * caida a PIN, porque abre la cartera y quiere la huella directa.
     *
     * Pero la sesion es UNA para toda la app, asi que abrirla por aqui tiene
     * que contar igual. Sin esto, desbloquear la cartera con la huella dejaba
     * lastAuthTime a cero: el baul de dentro volveria a pedir el PIN, y peor,
     * cada onResume de esta misma pantalla veria la sesion cerrada y pediria
     * la huella otra vez — un bucle, y justo el fallo que se viene a quitar.
     */
    private fun authenticate(huella: Boolean = false, onSuccess: () -> Unit) {
        // Mismo camino que el resto de la app: PIN, con la huella como tecla y
        // lanzamiento automático. Antes había una biometría propia aquí, y dos
        // sitios que decidían cuándo ofrecer la huella se desincronizaban.
        PinAuthHelper.show(this, autoBiometric = true, permitirHuella = huella) { ok -> if (ok) onSuccess() else finish() }
    }

    /* -- PIN DIALOG -- */

    /**
     * El código, con la pantalla de toda la app (PinAuthHelper.pantalla).
     * Aquí había una tercera copia, en hoja flotante, con su propio teclado.
     * Sin huella automática: a esto se llega justo después de rechazarla.
     */
    private fun showPinDialog(isSetup: Boolean, onResult: (Boolean) -> Unit) {
        PinAuthHelper.pantalla(this, isSetup, autoBiometric = false, onResult = onResult)
    }

    /**
     * El tipo de una clave de la lista de direcciones: "p2pkh", "p2sh", "p2wpkh"
     * o "p2tr". Las de una WIF se llaman wif_0, wif_p2wpkh… y se miran igual que
     * las de semilla.
     */
    private fun tipoDeClave(k: String): String = when {
        k == "wif_p2wpkh" -> "p2wpkh"
        k == "wif_p2sh"   -> "p2sh"
        k == "wif_p2tr"   -> "p2tr"
        k.startsWith("wif") -> "p2pkh"
        else -> k
    }

    /** Tamaño en vB de una entrada de esta cartera; una WIF sin comprimir es más grande. */
    private fun inputVBytesDe(k: String): Int =
        CoinSelector.inputVBytes(k, isWifMode && wifKey.isNotEmpty() && (wifKey[0] == '5' || wifKey[0] == '9'))

    /** Cómo se llama una clave de la lista en pantalla (saldo, origen de Enviar). */
    private fun etiquetaClave(k: String): String = etiquetaDeDireccion(addresses[k] ?: "") ?: when {
        k == "wif_0" -> currentWalletName
        k.startsWith("wif_p") -> "WIF " + k.removePrefix("wif_").uppercase()
        else -> labelMap[k] ?: k
    }

    /** El tipo que la petición de firma necesita para una clave WIF. */
    private fun tipoWif(k: String): String = tipoDeClave(k)

    /** Nombre que el usuario le ha puesto a una dirección, o null. Por dirección, no por clave. */
    private fun etiquetaDeDireccion(addr: String): String? =
        if (addr.isEmpty()) null
        else getSharedPreferences("address_labels", MODE_PRIVATE).getString(addr, null)?.takeIf { it.isNotBlank() }

    private fun guardarEtiquetaDireccion(addr: String, texto: String) {
        val e = getSharedPreferences("address_labels", MODE_PRIVATE).edit()
        if (texto.isBlank()) e.remove(addr) else e.putString(addr, texto.take(32))
        e.apply()
    }

    /** Moneda para el equivalente en pantalla: la que haya elegido el usuario (USD por defecto). */
    private fun fiatCode(): String =
        getSharedPreferences("hunter", MODE_PRIVATE).getString("fiat_code", "USD") ?: "USD"

    /** La P2PKH de una WIF, en la red de la cartera. Vacío si no vale. */
    private fun direccionDeWif(w: String): String =
        try { HunterEngine.importarClave(w, isTestnet, true).split('|').getOrNull(1) ?: "" }
        catch (e: Throwable) { "" }

    /** Rellena [wifExtras] con las direcciones de la clave WIF que no son la P2PKH. */
    private fun cargarWifExtras() {
        wifExtras.clear()
        if (!isWifMode || wifKey.isEmpty()) return
        val lineas = try { HunterEngine.wifDirecciones(wifKey, isTestnet) } catch (e: Throwable) { RegistroFallos.anotar("WalletActivity.cargarWifExtras", e); "" }
        for (linea in lineas.lines()) {
            val partes = linea.trim().split(' ', limit = 2)
            if (partes.size != 2) continue
            when (partes[0]) {
                "P2WPKH" -> wifExtras["wif_p2wpkh"] = partes[1]
                "P2SH-P2WPKH" -> wifExtras["wif_p2sh"] = partes[1]
                "P2TR" -> wifExtras["wif_p2tr"] = partes[1]
            }
        }
        // Enviar elige el origen entre las de la lista: ahí entran también.
        addresses.putAll(wifExtras)
    }

    /**
     * Lo que pertenece a la cartera que se abre y no puede pasar a la siguiente. Sólo switchToWallet lo
     * hacía: crear o importar una seed, cambiar de cuenta, importar una WIF o una vigilada y cambiar
     * de red dejaban vivo el escaneo de la cartera anterior (que al terminar escribía sus direcciones y
     * su saldo en la nueva), sus direcciones extra de WIF, las monedas elegidas a mano y el saldo con
     * que "Delete wallet" avisa («Its balance is 0» siendo de otra cartera).
     */
    private fun reiniciarEstadoDeCartera() {
        generacion++
        descubriendo = false
        historialExtra.clear()
        wifExtras.clear()
        selectedUtxos.clear()
        salidasExtraEnvio.clear()
        ultimoSaldoSat = -1L
        montos.clear(); fiatViews.clear()
    }

    private fun loadAddresses() {
        reiniciarEstadoDeCartera()
        if (isWifMode) {
            // Derivar addr sincrono si falta, luego buildUI
            if (wifAddr.isEmpty() && wifKey.isNotEmpty()) {
                Thread {
                    try {
                        val derived = direccionDeWif(wifKey)
                        if (derived.isNotEmpty()) wifAddr = derived
                    } catch(e: Exception) {}
                    runOnUiThread {
                        if (wifAddr.isNotEmpty()) {
                            addresses = mutableMapOf("wif_0" to wifAddr)
                            cargarWifExtras()
                            buildUI()
                        } else {
                            // WIF decode failed - show error
                            Toast.makeText(this@WalletActivity, "Could not derive address from WIF key", Toast.LENGTH_LONG).show()
                            showWifImportDialog()
                        }
                    }
                }.start()
            } else {
                addresses = mutableMapOf("wif_0" to wifAddr)
                cargarWifExtras()
                buildUI()
            }
            return
        }
        if (mnemonic.isNotEmpty()) {
            Thread {
                try {
                    // Se troceaba el JSON a mano con removePrefix/split(",")/
                    // split(":"). Funcionaba porque las direcciones no llevan esos
                    // caracteres, pero basta un campo nuevo con una coma para que
                    // el mapa de direcciones salga corrupto — y de ahí se firman
                    // transacciones.
                    val raw = HunterEngine.deriveWallet(mnemonic, isTestnet)
                    // Si el motor devuelve JSON mal formado, un JSONObject pelado
                    // deja la wallet sin ninguna dirección. Se rescatan las
                    // entradas bien formadas antes de rendirse: mejor una wallet
                    // parcial que una vacía sin explicación.
                    val obj = try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        android.util.Log.e("WalletActivity", "deriveWallet returned invalid JSON: ${e.message}")
                        val salvaged = JSONObject()
                        Regex("\"([a-z0-9_]+)\"\\s*:\\s*\"?([a-zA-Z0-9]+)\"?")
                            .findAll(raw)
                            .forEach { m -> salvaged.put(m.groupValues[1], m.groupValues[2]) }
                        if (salvaged.length() == 0) throw e
                        runOnUiThread {
                            Toast.makeText(this@WalletActivity,
                                "Warning: malformed engine response, ${salvaged.length()} addresses recovered",
                                Toast.LENGTH_LONG).show()
                        }
                        salvaged
                    }
                    // JSONObject.keys() no garantiza orden. El desplegable "From
                    // address" se ordena por esta secuencia, así que un orden
                    // inestable podría llevar a enviar desde otra dirección.
                    val order = listOf("p2pkh", "p2sh", "p2wpkh", "p2tr")
                    val map = LinkedHashMap<String, String>()
                    obj.keys().asSequence().toList()
                        .sortedWith(compareBy(
                            { k -> order.indexOfFirst { k.startsWith(it) }.let { if (it < 0) order.size else it } },
                            { k -> k.substringAfterLast('_').toIntOrNull() ?: 0 }
                        ))
                        .forEach { k ->
                            val v = obj.optString(k)
                            if (v.isNotEmpty()) map[k] = v
                        }
                    runOnUiThread { addresses = map; buildUI() }
                } catch (e: Exception) {
                    // Silencioso: la wallet quedaba sin direcciones y las
                    // pestañas sin explicación.
                    android.util.Log.e("WalletActivity", "deriveWallet: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this@WalletActivity,
                            "Could not derive the addresses: ${e.message}",
                            Toast.LENGTH_LONG).show()
                        buildUI()
                    }
                }
            }.start()
        }
    }

    private fun buildUI() {
        title = currentWalletName.ifEmpty { "Wallet" }
        window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
        setContentView(android.widget.FrameLayout(this)) // clear before rebuild
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP) }

        // La cabecera era una barra de otro color con tres botones de texto:
        // "<" con borde, "BTC WALLET" en mayúscula monoespaciada verde, y
        // ">> Hunter" con su propio recuadro. Tres estilos de botón en una
        // franja de 60dp, y el título gritando en el color del acento.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), dp(16), dp(AppTheme.PAD_SIDE), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(AppTheme.TXT_SEC)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) }
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = currentWalletName.ifEmpty { "Wallet" }
            textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.01f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_scan)
            setColorFilter(AppTheme.TXT_SEC)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(18) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                // Sin banderas esto creaba una SEGUNDA MainActivity encima de
                // la que ya había debajo —singleTop sólo evita el duplicado si
                // la de debajo está arriba del todo, y aquí arriba estaba esta—.
                // Dos pantallas principales con sus relojes de refresco
                // corriendo a la vez, y "atrás" llevaba de una a la otra.
                startActivity(Intent(this@WalletActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(BottomBar.EXTRA_TAB, BottomBar.SCANNER))
                finish()
            }
        })
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            setColorFilter(AppTheme.TXT_SEC)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { showMenu() }
        })
        root.addView(header)

        // Las pestañas eran botones a ras, distinguidos sólo por un cambio de
        // fondo casi invisible entre #161616 y #1D1D1D. Ahora la activa se
        // rellena con el acento, que es lo que hace el sistema en el resto.
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(18))
        }
        val tabBtns = mutableListOf<Button>()
        listOf("Balance","History","Send","Receive").forEachIndexed { i, name ->
            val btn = Button(this).apply {
                text = name; textSize = AppTheme.SP_CAPTION + 1f
                isAllCaps = false
                typeface = if (i == 0) AppTheme.bold(context) else AppTheme.medium(context)
                setTextColor(if (i == 0) AppTheme.BG_DEEP else AppTheme.TXT_SEC)
                background = GradientDrawable().apply {
                    setColor(if (i == 0) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                }
                stateListAnimator = null
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (i < 3) marginEnd = dp(6)
                }
            }
            tabBtns.add(btn); tabs.addView(btn)
        }
        tabBtns.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                tabBtns.forEach { b ->
                    b.setTextColor(AppTheme.TXT_SEC)
                    b.typeface = AppTheme.medium(b.context)
                    b.background = GradientDrawable().apply {
                        setColor(AppTheme.BG_KEY); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                    }
                }
                btn.setTextColor(AppTheme.ON_ACCENT)
                btn.typeface = AppTheme.bold(btn.context)
                btn.background = GradientDrawable().apply {
                    setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                }
                tabContent.removeAllViews()
                actionBar?.visibility = if (i >= 2) View.GONE else View.VISIBLE
                when (i) { 0 -> loadBalanceTab(); 1 -> loadHistoryTab(); 2 -> loadSendTab(); 3 -> loadReceiveTab() }
            }
        }
        root.addView(tabs)

        tabContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(tabContent)

        fun actionBtn(label: String, iconRes: Int, primary: Boolean, last: Boolean) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(if (primary) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CARD).toFloat()
                }
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    if (!last) marginEnd = dp(AppTheme.GAP)
                }
                addView(android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                        marginEnd = dp(9)
                    }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = AppTheme.SP_BODY + 1f
                    setTextColor(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    typeface = if (primary) AppTheme.bold(context) else AppTheme.medium(context)
                })
            }

        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(AppTheme.PAD_SIDE), dp(18), dp(AppTheme.PAD_SIDE), dp(26))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        actionBar?.addView(actionBtn("Send", R.drawable.ic_send, primary = false, last = false)
            .apply { setOnClickListener { tabBtns[2].performClick() } })
        actionBar?.addView(actionBtn("Receive", R.drawable.ic_receive, primary = true, last = true)
            .apply { setOnClickListener { tabBtns[3].performClick() } })
        root.addView(actionBar)

        setContentView(root)
        loadBalanceTab()
    }

    /* -- BALANCE -- */
    private fun loadBalanceTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        // Centrado y en verde fijo, igual que el saldo de la pestaña Cartera
        // antes del rediseño: el acento pintaba también un cero.
        montos.clear()
        val tvTotal = TextView(this).apply {
            text = "—"; textSize = AppTheme.SP_DISPLAY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.display(context)
            letterSpacing = -0.04f
            setPadding(0, dp(12), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvFiat = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, 0, 0, dp(20))
        }
        // El ojo, al lado de la cifra: tapa el total, el equivalente en
        // dólares y el saldo de cada dirección. Queda guardado.
        val filaTotal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filaTotal.addView(tvTotal)
        filaTotal.addView(Privacidad.ojo(this) { repintarMontos() })
        ll.addView(filaTotal); ll.addView(tvFiat)
        // Volver a buscar direcciones: si la primera búsqueda se hizo sin red o no
        // llegó a una dirección usada, aquí se repite sin cerrar la pantalla.
        ll.addView(TextView(this).apply {
            text = "Search for addresses again"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.medium(context); gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
            isClickable = true; isFocusable = true
            setOnClickListener { tabContent.removeAllViews(); loadBalanceTab() }
        })
        scroll.addView(ll); tabContent.addView(scroll)

        // Foto de la cartera tomada aquí, en el hilo de la pantalla: el hilo de
        // abajo no lee campos que otro hilo pueda estar cambiando.
        val gen = generacion
        val snapshot = addresses.toMap() + wifExtras
        val mnemonicSnap = mnemonic
        val wifSnap = isWifMode
        descubriendo = !wifSnap && mnemonicSnap.isNotEmpty()
        BalanceLookup.iniciarMemo()   // una consulta por dirección durante esta búsqueda
        Thread {
            var totalSat = 0L
            // Lo que aún no está confirmado (mempool), dentro del total.
            var pendienteTotal = 0L
            // label, dirección, saldo, fuente ("" = mempool.space, "electrum" = respaldo)
            val rows = mutableListOf<BalanceRow>()
            var usedFallback = false

            // mempool.space con respaldo Electrum. La consulta vive en
            // BalanceLookup porque el baúl de hallazgos necesita la misma.
            // Con doce direcciones y sin cobertura, preguntar una por una son
            // doce esperas encadenadas —cada una contra dos APIs web y diez
            // servidores Electrum— antes de poder decir nada. Se comprueba una
            // vez si hay alguna ruta a la cadena.
            var sinRed = !ChainApi.hayRed(isTestnet)
            var fallosSeguidos = 0
            snapshot.forEach { (k, addr) ->
                val label = when {
                    k == "wif_0" -> currentWalletName
                    k.startsWith("wif_p") -> "WIF " + k.removePrefix("wif_").uppercase()
                    else -> labelMap[k] ?: k
                }
                if (addr.isEmpty()) { rows.add(BalanceRow("Error", "empty address", -1L, "")); return@forEach }
                if (sinRed) { rows.add(BalanceRow(label, addr, -1L, "")); return@forEach }

                val res = BalanceLookup.query(addr, isTestnet)
                if (res == null) {
                    // Si se cae a mitad, no seguir intentándolo con el resto.
                    if (++fallosSeguidos >= 3) sinRed = true
                    rows.add(BalanceRow(label, addr, -1L, "")); return@forEach
                }
                fallosSeguidos = 0
                val bal = res.sat
                val src = res.source
                if (src == "electrum") usedFallback = true
                totalSat += bal
                pendienteTotal += res.pendiente
                rows.add(BalanceRow(etiquetaDeDireccion(addr) ?: label, addr, bal, src))
            }

            /* Descubrimiento HD.
             *
             * Hasta aquí sólo se miran las direcciones fijas que emite
             * deriveWallet: índices 0..2 de recepción y poco más. Una seed usada
             * en otra cartera puede tener los fondos en el índice 3 o el 7, y
             * entonces esta pantalla decía 0,00000000 sobre una wallet que no
             * está vacía — el peor error posible aquí.
             *
             * Se recorren las cuatro ramas hasta 20 direcciones seguidas sin
             * estrenar (BIP44) y se añade lo que aparezca por encima de lo ya
             * listado. Va después del primer repaso para que el total salga
             * rápido y esto lo complete.
             */
            var fallosHD = 0
            val hdUsadas = linkedMapOf<String, String>()
            if (!wifSnap && mnemonicSnap.isNotEmpty() && !sinRed) {
                val yaListadas = snapshot.values.toHashSet()
                // Las de recepción se suman al mapa con la clave canónica
                // (p2pkh_3, p2wpkh_5...). Así la pestaña Enviar las ofrece y
                // pathStr les calcula su ruta sola: encontrarlas y no poder
                // gastarlas sería peor que no encontrarlas.
                val nuevasGastables = linkedMapOf<String, String>()
                val clavePorProposito = mapOf(44 to "p2pkh", 49 to "p2sh",
                                              84 to "p2wpkh", 86 to "p2tr")
                for (purpose in HdScanner.PURPOSES) {
                    val (usadas, _, incompletoRec) = HdScanner.scanBranchInformado(mnemonicSnap, purpose, change = 0, testnet = isTestnet)
                    if (incompletoRec) fallosHD++
                    for (f in usadas) {
                        hdUsadas[f.addr] = "${HdScanner.purposeLabel(purpose)} [${f.index}]"
                        if (f.addr in yaListadas) continue
                        yaListadas.add(f.addr)
                        val r = BalanceLookup.query(f.addr, isTestnet)
                        if (r == null) { fallosHD++; continue }
                        if (r.source == "electrum") usedFallback = true
                        totalSat += r.sat
                        pendienteTotal += r.pendiente
                        rows.add(BalanceRow("${HdScanner.purposeLabel(purpose)} [${f.index}]",
                                            f.addr, r.sat, r.source))
                        clavePorProposito[purpose]?.let { nuevasGastables["${it}_${f.index}"] = f.addr }
                    }
                    // El cambio también guarda fondos entre envíos. Un cambio ya
                    // gastado no sale en el saldo, pero sí en el historial: sin él
                    // el envío que lo gastó no aparecía.
                    val (camb, _, incompletoCam) = HdScanner.scanBranchInformado(mnemonicSnap, purpose, change = 1, testnet = isTestnet)
                    if (incompletoCam) fallosHD++
                    for (f in camb) {
                        hdUsadas[f.addr] = "${HdScanner.purposeLabel(purpose)} change [${f.index}]"
                        if (f.addr in yaListadas) continue
                        yaListadas.add(f.addr)
                        val r = BalanceLookup.query(f.addr, isTestnet)
                        if (r == null) { fallosHD++; continue }
                        if (r.sat <= 0L) continue   // cambio ya gastado: no ensuciar la lista
                        if (r.source == "electrum") usedFallback = true
                        totalSat += r.sat
                        pendienteTotal += r.pendiente
                        rows.add(BalanceRow("${HdScanner.purposeLabel(purpose)} change [${f.index}]",
                                            f.addr, r.sat, r.source))
                        // Gastable también: antes el cambio se veía en el saldo pero
                        // ninguna dirección de Enviar podía moverlo.
                        clavePorProposito[purpose]?.let { nuevasGastables[RutaClave.clave(it, f.index, true)] = f.addr }
                    }
                }
                if (nuevasGastables.isNotEmpty()) {
                    // Sólo si la cartera sigue siendo la misma: si no, esto
                    // metería las direcciones de una seed en otra cartera.
                    runOnUiThread { if (gen == generacion) addresses.putAll(nuevasGastables) }
                }
            }
            // Un fallo de consulta (en el primer repaso o en la búsqueda) hace
            // que el total no sea completo: se avisa y el diálogo de borrado no
            // lo da por cero.
            val huboFallo = sinRed || rows.any { it.sat < 0 } || fallosHD > 0
            val hallados = hdUsadas.toMap()
            runOnUiThread {
                if (gen == generacion) {
                    historialExtra.clear(); historialExtra.putAll(hallados)
                    descubriendo = false
                }
            }

            BalanceLookup.terminarMemo()
            var price = 0.0
            // El precio es de la red principal: en testnet no tiene sentido.
            if (!isTestnet) try {
                // "USD":(\d+) no captura decimales: con 67432.5 leía 67432.
                val js = ChainApi.get("/v1/prices", false)
                if (js != null) price = JSONObject(js).optDouble(fiatCode(), 0.0)
            } catch(e: Exception) {}
            if (price > 0) Fiat.fijar(this, fiatCode(), price)
            val tot = totalSat; val pr = price; val pend = pendienteTotal
            if (gen == generacion) ultimoSaldoSat = if (huboFallo) -1L else tot
            runOnUiThread {
                if (gen != generacion) return@runOnUiThread
                val btcText = "%.8f".format(tot / 1e8).replace('.', ',')
                val fiatText = if (pr > 0) "BTC  ·  ≈ ${"%,.2f".format(tot / 1e8 * pr)} ${fiatCode()}" else "BTC"
                monto(tvTotal, btcText)
                tvTotal.setTextColor(if (tot > 0) AppTheme.ACCENT else AppTheme.TXT_PRI)
                if (pr > 0) monto(tvFiat, fiatText)
                if (pend != 0L) ll.addView(TextView(this).apply {
                    text = "of which pending (unconfirmed): %.8f BTC".format(pend / 1e8) + fiatEntre(pend)
                    textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                    typeface = AppTheme.body(context)
                    setPadding(0, 0, 0, dp(12))
                })
                // Un fallo de red se decía antes fila a fila ("no answer"
                // doce veces) sin explicar nunca que el problema era el mismo.
                if (huboFallo) {
                    ll.addView(TextView(this).apply {
                        text = "Could not query the chain. What you see may be " +
                               "out of date; come back when you have a connection."
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                        typeface = AppTheme.body(context)
                        setLineSpacing(0f, 1.4f)
                        setPadding(0, dp(10), 0, 0)
                    })
                } else if (usedFallback) {
                    ll.addView(TextView(this).apply {
                        text = "The web APIs did not answer; the balance comes from Electrum"
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
                        typeface = AppTheme.body(context)
                        setPadding(0, dp(10), 0, 0)
                    })
                }
                rows.forEach { (lbl, addr, bal, src) ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg()
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(AppTheme.GAP) }
                    }
                    card.addView(TextView(this).apply {
                        text = lbl; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
                        typeface = AppTheme.medium(context)
                    })
                    card.addView(TextView(this).apply {
                        text = addr; textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
                        typeface = AppTheme.mono(context)
                        setPadding(0, dp(3), 0, dp(7))
                    })
                    card.addView(TextView(this).apply {
                        val real = (if (bal < 0) "no answer" else "%.8f BTC".format(bal / 1e8)) +
                                   (if (src == "electrum") "  · electrum" else "")
                        if (bal < 0) text = real else monto(this, real)
                        textSize = AppTheme.SP_BODY
                        setTextColor(when { bal > 0 -> GREEN; bal == 0L -> TXT_SEC; else -> RED })
                        typeface = AppTheme.bold(context)
                    })
                    if (bal > 0) card.addView(TextView(this).apply {
                        textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC); typeface = AppTheme.body(context)
                        ponerFiat(this, bal)
                    })
                    ll.addView(card)
                }
            }
        }.start()
    }

    /* -- HISTORY -- */

    /** Una transacción vista desde esta cartera. neto > 0 entra, < 0 sale. */
    private class MovTx(val tx: JSONObject, val txid: String, val confirmado: Boolean,
                        val tiempo: Long, val neto: Long, val fee: Long)

    /**
     * Las direcciones de CAMBIO de la seed (rama .../1/k de los cuatro
     * propósitos), con su ruta. No salen en la lista de direcciones, pero el
     * dinero que vuelve a ellas es de la cartera: sin contarlas, un envío
     * parecía gastar también el cambio.
     */
    private fun direccionesDeCambio(mn: String = mnemonic): Map<String, String> {
        if (isWifMode || mn.isEmpty()) return emptyMap()
        val coin = if (isTestnet) 1 else 0
        val r = HashMap<String, String>()
        for (purpose in listOf(44, 49, 84, 86)) {
            try {
                // Mismo rango que la búsqueda: con 30 se perdía el cambio 30 en adelante.
                val arr = JSONArray(HunterEngine.deriveAddresses(mn, purpose, 1, 0, HdScanner.MAX_INDEX, isTestnet))
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    val a = o.optString("addr"); if (a.isNotEmpty())
                        r[a] = "m/$purpose'/$coin'/${Frase.cuenta(mn)}'/1/${o.optInt("i")}"
                }
            } catch (e: Exception) { RegistroFallos.anotar("WalletActivity.direccionesDeCambio: purpose $purpose", e) }
        }
        return r
    }

    /**
     * Historial de TODAS las direcciones de la cartera.
     *
     * Antes sólo preguntaba por la primera (queryAddrs[0]): lo recibido en la
     * SegWit, la Taproot o cualquier otra no salía, y la pantalla decía "no
     * transactions yet" sobre una cartera con movimientos. Ahora se consulta
     * cada una, se juntan por txid y cada transacción dice lo que de verdad
     * hizo con esta cartera: entrado menos salido, contando el cambio.
     */
    private fun loadHistoryTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
        val tvHead = TextView(this).apply {
            text = "Loading…"; textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(12), 0, dp(8))
        }
        ll.addView(tvHead); scroll.addView(ll); tabContent.addView(scroll)

        // Las de la lista y las que la búsqueda ha visto usadas (también el cambio
        // ya gastado). Si la búsqueda sigue, se dice en la cabecera.
        val consultar = (addresses.values + historialExtra.keys + wifExtras.values)
            .filter { it.isNotEmpty() }.distinct()
        val buscando = descubriendo
        if (consultar.isEmpty()) {
            tvHead.text = if (buscando) "Still searching for addresses…" else "No addresses to check yet"
            tvHead.setTextColor(AppTheme.WARN)
            return
        }
        val mnHist = mnemonic
        Thread {
            try {
                val porTx = LinkedHashMap<String, JSONObject>()
                var respondidas = 0; var fallos = 0
                val sinResponder = mutableListOf<String>()
                for ((idx, a) in consultar.withIndex()) {
                    val cuerpo = ChainApi.get("/address/$a/txs", isTestnet)
                    if (cuerpo == null) {
                        sinResponder.add(a)
                        // Sin red no tiene sentido seguir probando una a una.
                        if (++fallos >= 3 && respondidas == 0) {
                            sinResponder.addAll(consultar.drop(idx + 1)); break
                        }
                        continue
                    }
                    fallos = 0; respondidas++
                    // La API devuelve una página (las pendientes y unas 25 confirmadas).
                    // Lo más antiguo sale con /txs/chain/<último visto>: se sigue hasta
                    // que no quede nada, con un tope por dirección.
                    var pagina: String? = cuerpo
                    var paginas = 0
                    var incompleta = false
                    while (pagina != null) {
                        if (paginas >= MAX_PAGINAS_HISTORIAL) { incompleta = true; break }
                        val arr = JSONArray(pagina)
                        if (arr.length() == 0) break
                        for (k in 0 until arr.length()) {
                            val tx = arr.getJSONObject(k)
                            porTx.putIfAbsent(tx.getString("txid"), tx)
                        }
                        paginas++
                        val ultimo = arr.getJSONObject(arr.length() - 1).getString("txid")
                        pagina = ChainApi.get("/address/$a/txs/chain/$ultimo", isTestnet)
                        if (pagina == null) incompleta = true
                    }
                    if (incompleta) sinResponder.add(a)
                    val n = respondidas
                    runOnUiThread { tvHead.text = "Loading… $n of ${consultar.size} addresses" }
                }
                if (respondidas == 0) {
                    // Electrum da la lista de identificadores pero no el detalle
                    // de cada transacción. Al menos se puede decir cuántas hay.
                    // Electrum sólo sabe la lista de una dirección, no el detalle: se dice.
                    val hist = ElectrumClient.historialOrNull(consultar[0], isTestnet)
                    runOnUiThread {
                        tvHead.text = if (hist == null)
                            "Could not fetch the history. Check your connection."
                        else "Only one address could be checked over Electrum: ${hist.size} transactions there. " +
                             "Details need mempool.space"
                        tvHead.setTextColor(AppTheme.WARN)
                    }
                    return@Thread
                }

                val cambio = direccionesDeCambio(mnHist)
                val propias = consultar.toHashSet().apply { addAll(cambio.keys) }
                val movs = porTx.values.map { tx ->
                    var entra = 0L; var sale = 0L
                    val vout = tx.getJSONArray("vout")
                    for (k in 0 until vout.length()) {
                        val o = vout.getJSONObject(k)
                        if (o.optString("scriptpubkey_address") in propias) entra += o.optLong("value", 0)
                    }
                    val vin = tx.getJSONArray("vin")
                    for (k in 0 until vin.length()) {
                        val pv = vin.getJSONObject(k).optJSONObject("prevout") ?: continue
                        if (pv.optString("scriptpubkey_address") in propias) sale += pv.optLong("value", 0)
                    }
                    val st = tx.optJSONObject("status")
                    MovTx(tx, tx.getString("txid"), st?.optBoolean("confirmed", false) ?: false,
                          st?.optLong("block_time", 0) ?: 0L, entra - sale, tx.optLong("fee", 0))
                }.sortedWith(compareBy<MovTx> { it.confirmado }.thenByDescending { it.tiempo })

                runOnUiThread {
                    val faltan = sinResponder.size
                    tvHead.text = "${movs.size} transactions · ${consultar.size} addresses" +
                        (if (faltan > 0) " · $faltan not checked: the list may be incomplete" else "") +
                        (if (buscando || descubriendo) " · still searching addresses" else "")
                    if (faltan > 0 || buscando || descubriendo) tvHead.setTextColor(AppTheme.WARN)
                    if (movs.isEmpty()) {
                        ll.addView(TextView(this).apply {
                            text = "No transactions yet"
                            setTextColor(TXT_SEC); textSize = AppTheme.SP_BODY
                            typeface = AppTheme.body(context)
                        })
                        return@runOnUiThread
                    }
                    if (avanzado()) ll.addView(TextView(this).apply {
                        text = "Export as CSV"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
                        typeface = AppTheme.medium(context); gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, dp(10))
                        isClickable = true; isFocusable = true
                        setOnClickListener { exportarCsv(movs) }
                    })
                    val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                    fun tanda(desde: Int) {
                    val hasta = minOf(desde + 50, movs.size)
                    for (m in movs.subList(desde, hasta)) {
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL; background = cardBg()
                            setPadding(dp(16),dp(14),dp(16),dp(14))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(AppTheme.GAP) }
                            isClickable = true; foreground = Ui.toque()
                        }
                        val arriba = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        }
                        arriba.addView(TextView(this).apply {
                            text = when { m.neto > 0 -> "Received"; m.neto < 0 -> "Sent"; else -> "Moved" }
                            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI); typeface = AppTheme.medium(context)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        arriba.addView(TextView(this).apply {
                            val real = (if (m.neto > 0) "+" else if (m.neto < 0) "−" else "") +
                                       "%.8f BTC".format(Math.abs(m.neto) / 1e8)
                            text = Privacidad.monto(context, real)
                            textSize = AppTheme.SP_BODY; typeface = AppTheme.bold(context)
                            setTextColor(if (m.neto > 0) GREEN else TXT_PRI)
                        })
                        card.addView(arriba)
                        if (m.neto != 0L) card.addView(TextView(this).apply {
                            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC); typeface = AppTheme.body(context)
                            gravity = Gravity.END; setPadding(0, dp(2), 0, 0)
                            ponerFiat(this, m.neto)
                        })
                        card.addView(TextView(this).apply {
                            val fecha = if (m.tiempo > 0) fmt.format(java.util.Date(m.tiempo * 1000)) else ""
                            text = (if (m.confirmado) "Confirmed" else "Pending") +
                                   (if (fecha.isNotEmpty()) " · $fecha" else "") + " · " + m.txid.take(12) + "…"
                            textSize = AppTheme.SP_CAPTION
                            setTextColor(if (m.confirmado) TXT_SEC else AppTheme.WARN)
                            typeface = AppTheme.body(context)
                            setPadding(0, dp(4), 0, 0)
                        })
                        card.setOnClickListener { detalleTx(m, propias, cambio) }
                        ll.addView(card)
                    }
                    if (hasta < movs.size) {
                        val resto = movs.size - hasta
                        val btn = TextView(this).apply {
                            text = "Show ${minOf(50, resto)} more (${resto} left)"
                            textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
                            typeface = AppTheme.medium(context); setTextColor(AppTheme.ACCENT)
                            setPadding(0, dp(14), 0, dp(14))
                            isClickable = true; isFocusable = true
                        }
                        btn.setOnClickListener { ll.removeView(btn); tanda(hasta) }
                        ll.addView(btn)
                    }
                    }
                    tanda(0)
                }
            } catch(e: Exception) { runOnUiThread { tvHead.text = motivo(e); tvHead.setTextColor(RED) } }
        }.start()
    }

    /**
     * El historial a un CSV en la carpeta compartida de la app, y a la hoja de
     * compartir. Sólo fechas, estado, txid e importes: sin claves ni direcciones.
     */
    private fun exportarCsv(movs: List<MovTx>) {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val sb = StringBuilder("date,status,txid,net_sat,fee_sat\n")
        for (m in movs) {
            val fecha = if (m.tiempo > 0) fmt.format(java.util.Date(m.tiempo * 1000)) else ""
            sb.append(fecha).append(',')
              .append(if (m.confirmado) "confirmed" else "pending").append(',')
              .append(m.txid).append(',')
              .append(m.neto).append(',')
              .append(m.fee).append('\n')
        }
        Thread {
            try {
                val f = java.io.File(BackupStore.compartidos(this), "historial_${System.currentTimeMillis()}.csv")
                f.writeText(sb.toString())
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
                runOnUiThread {
                    startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share history"))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not export: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Guarda la PSBT en la carpeta compartida y abre la hoja de compartir. */
    private fun compartirPsbt(bin: ByteArray) {
        try {
            val f = java.io.File(BackupStore.compartidos(this), "psbt_${System.currentTimeMillis()}.psbt")
            f.writeBytes(bin)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PSBT"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save the PSBT: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun detalleTx(m: MovTx, propias: Set<String>, cambio: Map<String, String>) {
        val sheet = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=GradientDrawable().apply{setColor(BG_PANEL);cornerRadius=dp(AppTheme.R_CARD).toFloat()}; setPadding(dp(20),dp(20),dp(20),dp(24)) }
        sheet.addView(TextView(this).apply{text="Transaction";textSize=AppTheme.SP_TITLE;setTextColor(TXT_PRI);typeface=AppTheme.title(context);setPadding(0,0,0,dp(18))})
        fun row(k:String,v:String,vc:Int=TXT_PRI){
            val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(10))}
            r.addView(TextView(this).apply{text=k;textSize=AppTheme.SP_CAPTION;setTextColor(TXT_SEC);typeface=AppTheme.medium(context);setPadding(0,0,0,dp(5))})
            val tv=TextView(this).apply{text=v;textSize=AppTheme.SP_CAPTION;setTextColor(vc);typeface=AppTheme.mono(context);background=GradientDrawable().apply{setColor(BG_ELEV);cornerRadius=dp(AppTheme.R_INNER).toFloat()};setPadding(dp(14),dp(12),dp(14),dp(12))}
            r.addView(tv);sheet.addView(r)
            tv.setOnLongClickListener{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("tx",v));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();true}
        }
        row("Transaction ID", m.txid)
        row("Status", if (m.confirmado) "Confirmed" else "Pending", if (m.confirmado) GREEN else AppTheme.WARN)
        if (m.neto != 0L) row(if (m.neto > 0) "Received" else "Sent (fee included)",
            Privacidad.monto(this, "%.8f BTC".format(Math.abs(m.neto) / 1e8)), if (m.neto > 0) GREEN else TXT_PRI)
        if (m.neto != 0L && !isTestnet) Fiat.de(this, m.neto)?.let { row("Worth today", "≈ $it") }
        if (m.fee > 0) row("Fee", "%,d sat".format(m.fee) + fiatEntre(m.fee))
        if (m.tiempo > 0) row("Date", java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(m.tiempo * 1000)))

        fun boton(t: String, principal: Boolean) = android.widget.Button(this).apply {
            text = t; textSize = AppTheme.SP_BODY; isAllCaps = false; stateListAnimator = null
            setTextColor(if (principal) AppTheme.ON_ACCENT else TXT_PRI)
            typeface = if (principal) AppTheme.bold(context) else AppTheme.medium(context)
            background = GradientDrawable().apply { setColor(if (principal) AppTheme.ACCENT else BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        }
        // Acelerar: sólo una pendiente que salió de esta cartera.
        val puedeAcelerar = !m.confirmado && m.neto < 0
        val btnAcelerar = if (puedeAcelerar) boton("Speed up (raise the fee)", true).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        } else null
        btnAcelerar?.let { sheet.addView(it) }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val btnExplorer = boton("View in the explorer", false).apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) } }
        val btnClose = boton("Close", false).apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) }
        btnRow.addView(btnExplorer); btnRow.addView(btnClose); sheet.addView(btnRow)
        val txDlg = AlertDialog.Builder(this).setView(android.widget.ScrollView(this).apply { addView(sheet) }).setCancelable(true).create()
        txDlg.window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setLayout((resources.displayMetrics.widthPixels*0.93f).toInt(),android.view.WindowManager.LayoutParams.WRAP_CONTENT);setGravity(Gravity.CENTER);attributes=attributes?.also{it.dimAmount=0.7f};addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)}
        txDlg.show()
        btnClose.setOnClickListener { txDlg.dismiss() }
        btnExplorer.setOnClickListener {
            val url = if (isTestnet) "https://mempool.space/testnet/tx/${m.txid}" else "https://mempool.space/tx/${m.txid}"
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
        btnAcelerar?.setOnClickListener { txDlg.dismiss(); acelerar(m, propias, cambio) }

        // CPFP: un pago recibido sin confirmar se acelera gastándolo desde aquí
        // con una transacción hija de comisión alta. Las dos juntas pagan la tasa
        // que se quiera, aunque el padre no se pueda reemplazar.
        if (avanzado() && !m.confirmado && m.neto > 0) {
            val child = boton("Speed up with a child (CPFP)", true).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
            }
            child.setOnClickListener { txDlg.dismiss(); cpfp(m, propias) }
            sheet.addView(child, sheet.indexOfChild(btnRow))
        }
    }

    /**
     * CPFP sobre un pago recibido sin confirmar. La hija gasta TODAS las monedas
     * de ese pago que siguen libres en esa dirección y se las manda a la misma
     * dirección, con una comisión tal que el par (padre + hija) pague la tarifa
     * rápida. La hija no tiene salida de cambio: la comisión se calcula con ella
     * igualmente, así que sobra un poco, nunca falta.
     */
    private fun cpfp(m: MovTx, propias: Set<String>) {
        fun aviso(t: String) = runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread   // salió de la pantalla: un diálogo aquí lanzaría BadTokenException
            AlertDialog.Builder(this).setTitle("Cannot speed it up").setMessage(t)
                .setPositiveButton("Got it", null).show()
        }
        // La dirección que recibió el pago (una de las nuestras).
        val vout = m.tx.optJSONArray("vout") ?: return aviso("The transaction data is incomplete.")
        var direccion = ""
        for (k in 0 until vout.length()) {
            val a = vout.getJSONObject(k).optString("scriptpubkey_address")
            if (a in propias) { direccion = a; break }
        }
        if (direccion.isEmpty()) return aviso("This payment does not pay to this wallet.")
        val fromKey = addresses.entries.firstOrNull { it.value == direccion }?.key
            ?: return aviso("That address is not in this wallet's list.")
        if (isWifMode && wifKey.isEmpty()) return aviso("This is a watch-only wallet: it has no key to sign with.")

        Thread {
            try {
                val lista = ChainInfo.utxos(direccion, isTestnet) ?: return@Thread aviso("Could not read the coins of that address.")
                val mias = lista.filter { it.optString("txid") == m.txid }
                if (mias.isEmpty()) return@Thread aviso("None of that payment's coins is still unspent here.")
                val tin = mias.sumOf { it.optLong("value", 0L) }

                val vp = m.tx.optLong("weight", 0L).let { (it + 3) / 4 }
                val fp = m.fee
                if (vp <= 0 || fp <= 0) return@Thread aviso("The transaction data is incomplete.")
                val objetivo = (ChainInfo.fees(isTestnet)?.fastest ?: 0).toLong()
                if (objetivo <= 0) return@Thread aviso("Could not get the current fee rates. Try again later.")

                // Tamaño de la hija con la misma cuenta que usa el envío: sus entradas, la
                // salida y la de cambio (aunque el cambio sea 0, doSend cobra con ella).
                // Sin salida de cambio: la hija gasta todo y manda el importe a una sola dirección.
                val vc = (inputVBytesDe(fromKey) * mias.size + CoinSelector.outputVBytes(direccion) + 11).toLong()
                // La tasa de la hija que hace que (padre + hija) paguen el objetivo.
                val tasaHija = Cpfp.tasaHija(vp, fp, objetivo, vc)
                val feeHija = tasaHija * vc
                val importe = tin - feeHija
                if (importe <= CoinSelector.polvoDe(direccion))
                    return@Thread aviso("The coins are not enough to pay the child's fee.")

                val pares = "%,d sat/vB".format(objetivo)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Speed up with a child?")
                        .setMessage(("The child spends the coins of that payment back to this address with " +
                                    "a fee of %,d sat (%d sat/vB). The parent and the child together will pay " +
                                    "about $pares, the fastest rate now.").format(feeHija, tasaHija))
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Send") { _, _ ->
                            selectedUtxos.clear(); selectedUtxos.addAll(mias)
                            val estado = TextView(this).apply { textSize = AppTheme.SP_BODY; setPadding(dp(20), dp(16), dp(20), dp(16)) }
                            // Con "Close": antes no tenía ninguna salida y tapaba la pantalla para siempre.
                            val dlg = AlertDialog.Builder(this).setTitle("Sending the child").setView(estado)
                                .setPositiveButton("Close", null).create()
                            dlg.show()
                            val boton = View(this)
                            doSend(direccion, importe / 1e8, tasaHija.toInt(), fromKey, direccion,
                                   estado, boton, "", emptyList())
                        }
                        .show()
                }
            } catch (e: Exception) { aviso(motivo(e)) }
        }.start()
    }

    /** La ruta BIP32 de una dirección de la lista, por su clave (p2wpkh_3…). */
    private fun rutaDeClave(k: String): String? = RutaClave.ruta(k, isTestnet, Frase.cuenta(mnemonic))

    /**
     * Sube la comisión de un envío atascado (RBF, BIP125).
     *
     * Los envíos salían ya como reemplazables y la pantalla lo decía ("you can
     * raise the fee if it gets stuck"), pero no había con qué hacerlo. Esto
     * rehace la MISMA transacción —mismas entradas, mismo destinatario, mismo
     * importe— con más comisión, que sale del cambio. El destinatario recibe
     * lo mismo.
     *
     * Sólo para envíos sencillos hechos desde una dirección: un destinatario y,
     * como mucho, un cambio propio. Es lo que hace la app al enviar.
     */
    private fun acelerar(m: MovTx, propias: Set<String>, cambio: Map<String, String>) {
        fun aviso(t: String) = runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread   // salió de la pantalla: un diálogo aquí lanzaría BadTokenException
            AlertDialog.Builder(this).setTitle("Cannot speed it up").setMessage(t)
                .setPositiveButton("Got it", null).show()
        }
        if (isWifMode && wifKey.isEmpty()) { aviso("This is a watch-only wallet: it has no key to sign with."); return }
        Toast.makeText(this, "Preparing…", Toast.LENGTH_SHORT).show()
        // Copia del estado, tomada aquí: el hilo de abajo no lee lo que cambia al cambiar de cartera.
        val mnSnap = mnemonic
        val wifSnap = isWifMode
        val wifKeySnap = wifKey
        val addrSnap = addresses.toMap()
        Thread {
            try {
                val tx = m.tx
                // Entradas: todas de UNA dirección de esta cartera.
                val vin = tx.getJSONArray("vin")
                val utxos = JSONArray(); var totalIn = 0L; var origen = ""
                for (k in 0 until vin.length()) {
                    val v = vin.getJSONObject(k)
                    val pv = v.optJSONObject("prevout") ?: return@Thread aviso("The transaction data is incomplete.")
                    val a = pv.optString("scriptpubkey_address")
                    if (origen.isEmpty()) origen = a
                    if (a != origen) return@Thread aviso("It spends from more than one address; this wallet cannot rebuild it.")
                    val valor = pv.optLong("value")
                    totalIn += valor
                    utxos.put(JSONObject().put("txid", v.getString("txid")).put("vout", v.getInt("vout")).put("amount", valor))
                }
                // Los importes de las entradas vienen de esta misma respuesta: se
                // contrastan con la transacción anterior de cada una, en todos los
                // servidores que respondan. Si ninguno responde, no se firma.
                for (k in 0 until vin.length()) {
                    val v = vin.getJSONObject(k)
                    val pv = v.getJSONObject("prevout")
                    val padres = ChainApi.getDeTodos("/tx/${v.getString("txid")}", isTestnet).mapNotNull {
                        try { JSONObject(it) } catch (e: Exception) { null } }
                    // Con un solo servidor no hay contraste: el importe de la entrada es
                    // de ese servidor, y una cifra falsa se paga en comisión.
                    if (padres.size < 2)
                        return@Thread aviso("Two servers must answer for the previous transaction to check its amounts. Try again later.")
                    if (padres.isEmpty())
                        return@Thread aviso("No server answered for the previous transaction of an input. Not rebuilding it.")
                    val indice = v.getInt("vout")
                    val valor = pv.optLong("value")
                    val direccion = pv.optString("scriptpubkey_address")
                    val todasDicen = padres.all { p ->
                        val outs = p.optJSONArray("vout")
                        outs != null && indice < outs.length() && outs.getJSONObject(indice).let {
                            it.optLong("value") == valor && it.optString("scriptpubkey_address") == direccion
                        }
                    }
                    if (!todasDicen) return@Thread aviso("The servers do not agree on the amount of an input. Not rebuilding it.")
                }
                // La dirección de origen es una de la lista o un cambio ya gastado
                // (que no sale en la lista, pero sí está en el mapa de cambio).
                val claveOrigen = addrSnap.entries.firstOrNull { it.value == origen }?.key
                val ruta = if (wifSnap) "" else when {
                    claveOrigen != null -> rutaDeClave(claveOrigen) ?: return@Thread aviso("Unsupported address type.")
                    cambio[origen] != null -> cambio[origen]!!
                    else -> return@Thread aviso("It was not sent from this wallet.")
                }

                // Salidas: un destinatario y, si lo hay, un cambio propio.
                val vout = tx.getJSONArray("vout")
                var destino = ""; var importe = 0L; var cambioAddr = ""; var ajenas = 0
                for (k in 0 until vout.length()) {
                    val o = vout.getJSONObject(k)
                    val a = o.optString("scriptpubkey_address")
                    if (a in propias) cambioAddr = a
                    else { ajenas++; destino = a; importe = o.optLong("value") }
                }
                if (ajenas != 1) return@Thread aviso("Only simple sends (one recipient) can be sped up here.")
                if (cambioAddr.isEmpty()) return@Thread aviso("It has no change output: there is nothing to take the extra fee from without paying the recipient less.")
                // El destinatario y el importe salen de la API, y la nueva transacción
                // vuelve a pagarlos: una respuesta manipulada redirigía el pago. Se
                // valida la dirección y se contrasta con los dos servidores: si
                // alguno dice otra cosa, no se firma.
                if (BtcAddress.validate(destino, isTestnet) !is BtcAddress.Result.Valid)
                    return@Thread aviso("The recipient address in that transaction is not valid.")
                val txid = tx.optString("txid")
                val versiones = ChainApi.getDeTodos("/tx/$txid", isTestnet).mapNotNull {
                    try { JSONObject(it) } catch (e: Exception) { null }
                }
                val discrepa = versiones.any { o ->
                    val vs = o.optJSONArray("vout") ?: return@any true
                    (0 until vs.length()).none {
                        val x = vs.getJSONObject(it)
                        x.optString("scriptpubkey_address") == destino && x.optLong("value") == importe
                    }
                }
                if (txid.length != 64 || versiones.isEmpty())
                    return@Thread aviso("No server answered for that transaction. Not rebuilding it.")
                if (discrepa)
                    return@Thread aviso("The servers do not agree on who that transaction pays. Not rebuilding it.")

                val vsize = (tx.optLong("weight", 0) + 3) / 4
                // Sólo se acelera una transacción que se puede reemplazar (BIP125): alguna
                // entrada con secuencia menor que 0xfffffffe. Si no, el nodo la rechaza.
                val replaceable = (0 until vin.length()).any { vin.getJSONObject(it).optLong("sequence", 0xFFFFFFFFL) < 0xFFFFFFFEL }
                if (!replaceable) return@Thread aviso("This transaction was not sent as replaceable (RBF), so it cannot be sped up. Use a child (CPFP) instead.")
                val feeViejo = tx.optLong("fee", 0)
                if (vsize <= 0 || feeViejo <= 0) return@Thread aviso("The transaction data is incomplete.")
                val tasaVieja = (feeViejo + vsize - 1) / vsize
                val rapida = (ChainInfo.fees(isTestnet)?.fastest ?: 0).toLong()
                val tasaNueva = maxOf(rapida, tasaVieja + 2)
                if (tasaNueva > ChainInfo.TARIFA_MAX)
                    return@Thread aviso("The fee needed (%d sat/vB) is above the safety limit of %d sat/vB.".format(tasaNueva, ChainInfo.TARIFA_MAX))
                // BIP125: la nueva tiene que pagar más en total, y al menos
                // 1 sat/vB más que la vieja por el espacio que ocupa.
                val feeNuevo = maxOf(tasaNueva * vsize, feeViejo + vsize)
                val cambioNuevo = totalIn - importe - feeNuevo
                if (cambioNuevo < 0) return@Thread aviso("The change is not enough to pay a higher fee.")
                val rutaCambio = when {
                    wifSnap || cambioAddr == origen -> null      // vuelve a la de origen
                    else -> cambio[cambioAddr]
                        ?: return@Thread aviso("The change address is not one this wallet can derive.")
                }

                val resumen = buildString {
                    appendLine("Recipient still receives %.8f BTC".format(importe / 1e8) + fiatEntre(importe))
                    appendLine("to $destino")
                    appendLine()
                    appendLine("Fee  %,d → %,d sat".format(feeViejo, feeNuevo))
                    appendLine("Rate %d → %d sat/vB".format(tasaVieja, feeNuevo / vsize))
                    appendLine()
                    append(if (cambioNuevo <= CoinSelector.DUST)
                        "The change is too small to keep and goes to the fee."
                    else "Change %.8f BTC back to your wallet.".format(cambioNuevo / 1e8))
                    appendLine(); appendLine()
                    append("The old transaction is replaced by this one.")
                    // Lo que gasta su cambio, si aún no está confirmado, depende de la
                    // transacción vieja: al sustituirla, la red lo descarta también.
                    appendLine()
                    append("Unconfirmed transactions that spend this one's change are dropped with it.")
                }
                val seguir = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                runOnUiThread {
                    // Si la pantalla ya no existe (se salió o se recreó), no se muestra: el diálogo lanzaría
                    // BadTokenException y nadie contestaría nunca.
                    if (isFinishing || isDestroyed) { seguir.offer(false); return@runOnUiThread }
                    AlertDialog.Builder(this).setTitle("Speed up the send?").setMessage(resumen)
                        .setCancelable(false)
                        .setNegativeButton("Cancel") { _, _ -> seguir.offer(false) }
                        .setPositiveButton("Speed up") { _, _ -> seguir.offer(true) }
                        .show()
                }
                // Con tope: sin él, un diálogo que se pierde (rotación, tema) dejaba este hilo esperando
                // para siempre, reteniendo la Activity y la frase semilla.
                if (seguir.poll(5, java.util.concurrent.TimeUnit.MINUTES) != true) return@Thread
                if (!PinAuthHelper.codigoParaFirmar(this)) return@Thread aviso("Cancelled: the code is needed to send.")

                // Tipo de la clave de origen, para firmar con esa dirección si es una WIF.
                val tipoOrigen = tipoWif(addrSnap.entries.firstOrNull { it.value == origen }?.key ?: "wif_0")
                val req = JSONObject()
                    .put("mnemonic", if (wifSnap) "" else mnSnap)
                    .put("testnet", if (isTestnet) 1 else 0)
                    .put("dust", CoinSelector.polvoDe(origen))
                    .put("path", ruta)
                    .put("utxos", utxos)
                    .put("to", destino)
                    .put("amount", importe)
                    .put("fee", feeNuevo)
                    .apply {
                        if (wifSnap) { put("wif", wifKeySnap); put("wif_tipo", tipoOrigen) }
                        if (rutaCambio != null) put("change_path", rutaCambio)
                        ChainInfo.tipHeight(isTestnet)?.let { put("locktime", it) }
                    }.toString()
                val raw = HunterEngine.buildAndSignTx(req)
                if (raw.startsWith("ERROR")) return@Thread aviso(raw)
                val resp = ChainInfo.broadcast(raw, isTestnet)
                runOnUiThread {
                    if (resp.startsWith("ERROR")) {
                        AlertDialog.Builder(this).setTitle("Not accepted")
                            .setMessage(resp.removePrefix("ERROR: "))
                            .setPositiveButton("Got it", null).show()
                    } else {
                        Toast.makeText(this, "Sped up. New transaction: ${resp.take(16)}…", Toast.LENGTH_LONG).show()
                        tabContent.removeAllViews(); loadHistoryTab()
                    }
                }
            } catch (e: Exception) { aviso(motivo(e)) }
        }.start()
    }

    /* -- SEND -- */
    //
    // Era un formulario: cinco campos apilados del mismo tamaño —origen,
    // destino, importe, comisión, monedas— y un botón. Todos pesaban lo mismo,
    // así que la pantalla no decía qué estabas a punto de hacer; había que
    // leerla entera para enterarte.
    //
    // El mockup la ordena por lo que importa. El importe es la cifra
    // protagonista y va arriba. Debajo, de dónde sale y a dónde va, cada uno en
    // su tarjeta y con lo que hace falta saber de él: el tipo y el saldo de la
    // dirección de origen, y si la de destino es válida y de qué clase. Luego
    // la comisión como tres opciones con su tiempo estimado en vez de un número
    // que hay que saberse. Y antes del botón, escrito, lo que va a pasar: a
    // dónde vuelve el cambio y que se podrá subir la comisión después.
    private fun loadSendTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(28))
        }

        val keys = addresses.keys.toList()
        if (keys.isEmpty()) {
            ll.addView(TextView(this).apply {
                text = "There are no addresses to send from yet."
                textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(dp(AppTheme.PAD_SIDE), dp(24), dp(AppTheme.PAD_SIDE), 0)
            })
            scroll.addView(ll); tabContent.addView(scroll); return
        }
        var fromIdx = 0

        fun side(v: View, top: Int = 0, bottom: Int = 0) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(top), dp(AppTheme.PAD_SIDE), dp(bottom))
            }
        }
        fun cap(t: String) = TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
        }

        /* ── IMPORTE ───────────────────────────────────────────────────── */
        val amountBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        amountBlock.addView(cap("Amount"))

        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        // Un EditText sin fondo: la cifra se escribe donde se lee, sin un campo
        // aparte que la repita más pequeña.
        val etAmt = EditText(this).apply {
            hint = "0,00000000"
            textSize = 40f
            setTextColor(TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.display(context)
            letterSpacing = -0.04f
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        amountRow.addView(etAmt)
        amountRow.addView(TextView(this).apply {
            text = "BTC"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        amountBlock.addView(amountRow)

        val underAmount = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val tvFromBal = TextView(this).apply {
            text = "Checking the balance…"
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // "All" vacía la dirección entera. No pone el saldo tal cual: hay que
        // dejar la comisión dentro, y eso sólo se sabe al seleccionar monedas,
        // así que resta una estimación y doSend ajusta el resto.
        val chipMax = TextView(this).apply {
            text = "All"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.bold(context)
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true; isFocusable = true
        }
        underAmount.addView(tvFromBal); underAmount.addView(chipMax)
        amountBlock.addView(underAmount)
        ll.addView(side(amountBlock, top = 8, bottom = 22))

        /* ── SALE DE ───────────────────────────────────────────────────── */
        val fromCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(17), dp(15), dp(17), dp(15))
            isClickable = true; isFocusable = true
        }
        val tvFromType = TextView(this).apply {
            textSize = AppTheme.SP_MICRO - 1f; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.bold(context)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(11).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(38)).apply { marginEnd = dp(13) }
        }
        val fromCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fromCol.addView(cap("From").apply { textSize = AppTheme.SP_MICRO })
        val tvFromAddr = TextView(this).apply {
            textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
            typeface = AppTheme.mono(context)   // es una dirección
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(3), 0, 0)
        }
        fromCol.addView(tvFromAddr)
        fromCard.addView(tvFromType); fromCard.addView(fromCol)
        fromCard.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(10) }
        })
        ll.addView(side(fromCard, bottom = AppTheme.GAP))

        /* ── VA A ──────────────────────────────────────────────────────── */
        val toCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(17), dp(15), dp(17), dp(15))
        }
        val toHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toHead.addView(cap("Goes to").apply {
            textSize = AppTheme.SP_MICRO
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // El veredicto de la dirección, ahí mismo mientras se escribe. Antes
        // sólo se comprobaba al pulsar enviar, y un envío a una dirección con
        // un carácter cambiado no tiene vuelta atrás.
        val tvToCheck = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_MICRO
            typeface = AppTheme.bold(context)
        }
        toHead.addView(tvToCheck)
        toCard.addView(toHead)

        val etTo = EditText(this).apply {
            hint = "bc1… o 1… o 3…"
            textSize = AppTheme.SP_CAPTION
            setTextColor(TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.mono(context)
            background = null
            setPadding(0, dp(8), 0, 0)
            minHeight = dp(44)
            setLineSpacing(0f, 1.45f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        toCard.addView(etTo)

        // Pegar y escanear: teclear 42 caracteres a mano es como se manda
        // dinero a una dirección con uno cambiado.
        fun rellenar(texto: String) {
            val p = PagoUri.leer(texto)
            if (p.error != null) {
                Toast.makeText(this, p.error, Toast.LENGTH_LONG).show()
                return
            }
            etTo.setText(p.direccion)
            // Una petición sin importe deja el campo vacío: antes se quedaba el
            // importe del envío anterior, que no era el que pedía nadie.
            if (p.importeBtc != null) etAmt.setText(p.importeBtc)
            else if (p.esPeticion) etAmt.setText("")
        }
        alLeerQr = { rellenar(it) }
        val filaAcciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        fun accion(t: String, icono: Int, ultimo: Boolean, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            foreground = Ui.toque()
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (!ultimo) marginEnd = dp(8) }
            addView(Ui.icon(this@WalletActivity, icono, 16, TXT_PRI).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
            })
            addView(TextView(this@WalletActivity).apply {
                text = t; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_PRI)
                typeface = AppTheme.medium(context)
            })
            setOnClickListener { click() }
        }
        filaAcciones.addView(accion("Paste", R.drawable.ic_copy, false) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (t.isEmpty()) Toast.makeText(this, "The clipboard is empty", Toast.LENGTH_SHORT).show()
            else rellenar(t)
        })
        filaAcciones.addView(accion("Scan QR", R.drawable.ic_scan, true) {
            startActivityForResult(Intent(this, QrScanActivity::class.java), REQ_QR)
        })
        toCard.addView(filaAcciones)
        ll.addView(side(toCard, bottom = 20))

        /* ── COMISIÓN ──────────────────────────────────────────────────── */
        ll.addView(side(cap("Fee"), bottom = 10))

        // -1 = automática. Se mantiene el contrato de doSend: quien decide es
        // la red salvo que aquí se elija otra cosa.
        var feeRate = -1
        val feeNames = listOf("Slow", "Normal", "Fast")
        val feeRates = intArrayOf(-1, -1, -1)
        val feePills = mutableListOf<LinearLayout>()
        val feeEtas  = mutableListOf<TextView>()
        var feeSel = 1

        val feeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tvTotal = TextView(this).apply {
            text = "—"
            textSize = 18f; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.02f
        }
        val tvFeeLine = TextView(this).apply {
            text = "—"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.bold(context)
        }
        // Precio de 1 BTC en la moneda elegida (0 = aún sin saber, o testnet): con él, la
        // comisión y lo que se envía se ven también en dinero normal.
        var precioFiat = 0.0
        fun enFiat(sat: Long): String {
            val v = sat / 1e8 * precioFiat
            return (if (v > 0 && v < 0.01) "<0.01" else "%,.2f".format(v)) + " " + fiatCode()
        }
        val tvFiatEnvio = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context); gravity = Gravity.END
            setPadding(0, 0, dp(2), dp(14))
        }

        /** Estimación honesta: una entrada, salida al destino y cambio. */
        // Monedas de la dirección de origen, cargadas al elegirla. Con ellas la
        // comisión de la pantalla es la que el envío pagaría de verdad.
        var monedasOrigen: List<org.json.JSONObject>? = null
        fun estimateFeeSat(): Long {
            val r = if (feeRate > 0) feeRate.toLong() else feeRates[feeSel].toLong()
            if (r <= 0) return -1L
            val fromKey = keys[fromIdx]
            val inVB = inputVBytesDe(fromKey)
            val outVB = CoinSelector.outputVBytes(etTo.text.toString().trim().ifEmpty { "1" }) +
                        salidasExtraEnvio.sumOf { it.vbytes() }
            val chgVB = CoinSelector.outputVBytes(addresses[fromKey] ?: "1")
            val monedas = if (selectedUtxos.isNotEmpty()) selectedUtxos.toList()
                          else monedasOrigen?.filter { it.optBoolean("confirmed", true) }
            val amtBtc = etAmt.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val amtSat = Math.round(amtBtc * 1e8) + salidasExtraEnvio.sumOf { it.sat }
            if (monedas != null && monedas.isNotEmpty() && amtSat > 0) {
                // El mismo selector que el envío: cuántas entradas hacen falta de verdad.
                CoinSelector.select(monedas, amtSat, r, inVB, outVB, chgVB)?.let { return it.feeSat }
            }
            // Sin monedas todavía (o sin importe): una entrada, como antes.
            return r * (inVB + outVB + chgVB + 11)
        }
        fun refreshTotals() {
            val fee = estimateFeeSat()
            val amt = etAmt.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            tvFeeLine.text = if (fee < 0) "whatever the network suggests"
                             else "≈ %,d sat".format(fee) +
                                  (if (precioFiat > 0 && !Privacidad.ocultos(this)) "  (≈ ${enFiat(fee)})" else "")
            val extras = salidasExtraEnvio.sumOf { it.sat }
            // Lo que se envía, la comisión y el total, también en dinero normal.
            val enviarSat = Math.round(amt * 1e8) + extras
            tvFiatEnvio.text = if (precioFiat > 0 && enviarSat > 0)
                Privacidad.monto(this, "Sending ≈ ${enFiat(enviarSat)}" +
                    (if (fee > 0) "\nFee ≈ ${enFiat(fee)}\nTotal ≈ ${enFiat(enviarSat + fee)}" else ""))
                else ""
            tvTotal.text = if (amt <= 0) "—"
                           else "%.8f".format(amt + extras / 1e8 + (if (fee > 0) fee / 1e8 else 0.0))
        }

        val tvMedida = TextView(this).apply { text = "Custom"; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER }
        fun paintFees() {
            val medida = feeRate > 0
            tvMedida.text = if (medida) "$feeRate sat/vB" else "Custom"
            tvMedida.setTextColor(if (medida) AppTheme.ACCENT else TXT_SEC)
            tvMedida.typeface = if (medida) AppTheme.bold(this) else AppTheme.medium(this)
            feePills.forEachIndexed { i, pill ->
                val on = i == feeSel && feeRate <= 0
                pill.background = GradientDrawable().apply {
                    setColor(if (on) AppTheme.BG_ELEV else AppTheme.BG_CARD)
                    cornerRadius = dp(AppTheme.R_INNER).toFloat()
                }
                (pill.getChildAt(0) as TextView).apply {
                    setTextColor(if (on) AppTheme.ACCENT else TXT_SEC)
                    typeface = if (on) AppTheme.bold(context) else AppTheme.medium(context)
                }
            }
            refreshTotals()
        }

        feeNames.forEachIndexed { i, name ->
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i < feeNames.size) marginEnd = dp(8) }
            }
            pill.addView(TextView(this).apply {
                text = name; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
            })
            val eta = TextView(this).apply {
                text = "—"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
                typeface = AppTheme.body(context); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            pill.addView(eta)
            pill.setOnClickListener { feeSel = i; feeRate = -1; paintFees() }
            feePills.add(pill); feeEtas.add(eta); feeRow.addView(pill)
        }
        // Tarifa a medida: pide sat/vB entre 1 y el tope de seguridad.
        fun pedirTarifa() {
            val et = EditText(this).apply {
                hint = "sat/vB, 1 to ${ChainInfo.TARIFA_MAX}"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            AlertDialog.Builder(this)
                .setTitle("Custom fee rate")
                .setMessage("Satoshis per virtual byte.")
                .setView(et)
                .setPositiveButton("Use") { _, _ ->
                    val v = et.text.toString().toLongOrNull()
                    if (v == null || v < 1 || v > ChainInfo.TARIFA_MAX) {
                        Toast.makeText(this, "Enter a rate from 1 to ${ChainInfo.TARIFA_MAX} sat/vB", Toast.LENGTH_SHORT).show()
                    } else {
                        feeRate = v.toInt(); paintFees()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val pillMedida = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pillMedida.addView(tvMedida)
        pillMedida.setOnClickListener { pedirTarifa() }
        if (avanzado()) feeRow.addView(pillMedida)
        ll.addView(side(feeRow, bottom = AppTheme.GAP))

        /* ── LO QUE VA A PASAR ─────────────────────────────────────────── */
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
        }
        fun infoRow(iconRes: Int, tint: Int, title: String, sub: String?, right: View?): LinearLayout {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(17), dp(14), dp(17), dp(14))
            }
            r.addView(android.widget.ImageView(this).apply {
                setImageResource(iconRes); setColorFilter(tint)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) }
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = title; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
                typeface = AppTheme.body(context)
            })
            if (sub != null) col.addView(TextView(this).apply {
                text = sub; textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
                setPadding(0, dp(2), 0, 0)
            })
            r.addView(col)
            if (right != null) r.addView(right)
            return r
        }
        fun sep() = View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = dp(17); marginEnd = dp(17) }
        }

        infoCard.addView(infoRow(R.drawable.ic_send, TXT_SEC, "Fee", null, tvFeeLine))
        infoCard.addView(sep())
        infoCard.addView(infoRow(R.drawable.ic_refresh, AppTheme.ACCENT,
            "Change goes back to an address of yours", "It is not left as fee", null))
        infoCard.addView(sep())
        infoCard.addView(infoRow(R.drawable.ic_clock, TXT_SEC,
            "You can raise the fee later", "Sent as replaceable (RBF)", null))
        if (avanzado()) infoCard.addView(sep())

        // Coin Control estaba suelto como un botón más; es un detalle de esta
        // misma lista: de qué monedas sale.
        val tvCoinCtl = TextView(this).apply {
            text = "automatic"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
        }
        val rowCoinCtl = infoRow(R.drawable.ic_wallet, TXT_SEC, "Coins being spent", null, tvCoinCtl)
        rowCoinCtl.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) }
        })
        rowCoinCtl.isClickable = true; rowCoinCtl.isFocusable = true
        if (avanzado()) infoCard.addView(rowCoinCtl)
        ll.addView(side(infoCard, bottom = 20))

        /* ── TOTAL Y ACCIÓN ────────────────────────────────────────────── */
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            setPadding(dp(2), 0, dp(2), dp(14))
        }
        totalRow.addView(TextView(this).apply {
            text = "Total to deduct"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        totalRow.addView(tvTotal)
        totalRow.addView(TextView(this).apply {
            text = "BTC"; textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5) }
        })
        ll.addView(side(totalRow))
        ll.addView(side(tvFiatEnvio))

        val btnSend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
            ).apply { setMargins(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), 0) }
        }
        btnSend.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(AppTheme.ON_ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(10) }
        })
        btnSend.addView(TextView(this).apply {
            text = "Review the send"
            textSize = AppTheme.SP_BODY + 1f; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context)
        })
        // Varias salidas: otro destinatario, o un OP_RETURN con datos.
        val tvExtras = TextView(this).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context); setPadding(0, dp(8), 0, dp(4))
        }
        fun pintarExtras() {
            tvExtras.text = if (salidasExtraEnvio.isEmpty()) "" else salidasExtraEnvio.joinToString("\n") { e ->
                if (e.datos != null) "OP_RETURN  ${e.datos.length / 2} bytes  (0 BTC)"
                else "to ${e.addr?.take(14)}…  %.8f BTC".format(e.sat / 1e8)
            } + "\n(tap here to clear them)"
            refreshTotals()
        }
        tvExtras.setOnClickListener { salidasExtraEnvio.clear(); pintarExtras() }
        val btnAnadirSalida = TextView(this).apply {
            text = "+ Add another output"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.medium(context); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
            isClickable = true; isFocusable = true
            setOnClickListener { dialogoSalidaExtra { pintarExtras() } }
        }
        if (avanzado()) { ll.addView(btnAnadirSalida); ll.addView(tvExtras) }
        pintarExtras()
        // Exportar sin firmar: la misma revisión, y una PSBT para firmar en otro aparato.
        if (avanzado()) ll.addView(TextView(this).apply {
            text = "Export as PSBT (sign on another device)"
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.medium(context); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
            isClickable = true; isFocusable = true
            setOnClickListener {
                // Un botón deshabilitado (envío en curso o ya enviado) no exporta nada.
                if (btnSend.isEnabled) { exportarSiguiente = true; btnSend.performClick() }
            }
        })
        ll.addView(btnSend)

        val tvStatus = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.35f)
        }
        ll.addView(side(tvStatus, top = 14))
        scroll.addView(ll); tabContent.addView(scroll)

        /* ── ESTADO ────────────────────────────────────────────────────── */
        var fromSat = -1L

        fun loadFromBalance() {
            val addr = addresses[keys[fromIdx]] ?: return
            fromSat = -1L
            monedasOrigen = null
            tvFromBal.text = "Checking the balance…"
            Thread {
                val res = BalanceLookup.query(addr, isTestnet)
                val monedas = try { ChainInfo.utxos(addr, isTestnet) } catch (e: Exception) { RegistroFallos.anotar("WalletActivity.loadFromBalance: utxos", e); null }
                runOnUiThread {
                    // Una respuesta que llega tarde, de la dirección que ya no está
                    // elegida, no puede poner su saldo en esta.
                    if (addresses[keys[fromIdx]] != addr) return@runOnUiThread
                    monedasOrigen = monedas
                    refreshTotals()
                    fromSat = res?.sat ?: -1L
                    tvFromBal.text = if (fromSat < 0) "Could not check the balance"
                                     else "Available %.8f BTC".format(fromSat / 1e8) + fiatEntre(fromSat)
                }
            }.start()
        }
        fun paintFrom() {
            val k = keys[fromIdx]
            tvFromType.text = when (tipoDeClave(k)) {
                "p2pkh"  -> "P2PKH"
                "p2sh"   -> "P2SH"
                "p2wpkh" -> "SegWit"
                "p2tr"   -> "Taproot"
                else     -> (labelMap[k] ?: k).take(6)
            }
            tvFromAddr.text = addresses[k] ?: ""
            selectedUtxos.clear()
            tvCoinCtl.text = "automatic"
            loadFromBalance()
            refreshTotals()
        }
        paintFrom()

        fromCard.setOnClickListener {
            val items = keys.map { k ->
                "${etiquetaClave(k)}\n${(addresses[k] ?: "").take(22)}…"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Which address does it come from?")
                .setItems(items) { _, i -> fromIdx = i; paintFrom() }
                .show()
        }

        chipMax.setOnClickListener {
            val addrDe = addresses[keys[fromIdx]] ?: return@setOnClickListener
            // Lo que había elegido el usuario al pulsar: la respuesta puede llegar con otro origen elegido,
            // y se leen aquí (en el hilo de la interfaz) las monedas a mano y las salidas extra.
            val claveOrigen = keys[fromIdx]
            val manual = selectedUtxos.toList()
            val extrasSat = salidasExtraEnvio.sumOf { it.sat }
            val extrasVb = salidasExtraEnvio.sumOf { it.vbytes() }
            tvStatus.text = "Checking the coins…"; tvStatus.setTextColor(TXT_SEC)
            Thread {
                // Lo mismo que gastaría Enviar: las monedas elegidas a mano, o las
                // confirmadas de la dirección. Con dos o más, la comisión crece con
                // cada entrada; restar la de una sola dejaba el envío sin fondos.
                val lista = try { ChainInfo.utxos(addrDe, isTestnet) } catch (e: Exception) { RegistroFallos.anotar("WalletActivity.chipMax: utxos", e); null }
                val usar: List<org.json.JSONObject>? = when {
                    manual.isNotEmpty() -> manual
                    lista != null -> lista.filter { it.optBoolean("confirmed", true) }
                    else -> null
                }
                runOnUiThread {
                    if (keys[fromIdx] != claveOrigen) return@runOnUiThread   // eligió otro origen mientras tanto
                    if (usar == null) {
                        tvStatus.text = "Could not read the coins of that address."
                        tvStatus.setTextColor(AppTheme.WARN); return@runOnUiThread
                    }
                    if (usar.isEmpty()) {
                        tvStatus.text = "That address has no confirmed coins to send."
                        tvStatus.setTextColor(AppTheme.WARN); return@runOnUiThread
                    }
                    val rate = if (feeRate > 0) feeRate.toLong() else feeRates[feeSel].toLong()
                    if (rate <= 0) {
                        tvStatus.text = "The fee rate is not known yet."
                        tvStatus.setTextColor(AppTheme.WARN); return@runOnUiThread
                    }
                    // Sin salida de cambio: se manda todo. Entradas + destino + 11.
                    val vs = inputVBytesDe(claveOrigen) * usar.size +
                             CoinSelector.outputVBytes(etTo.text.toString().trim().ifEmpty { "1" }) + extrasVb + 11
                    // Las salidas extra también salen de aquí: «All» es lo que queda para el destino principal.
                    val max = usar.sumOf { it.optLong("value", 0L) } - extrasSat - rate * vs
                    if (max <= CoinSelector.DUST) {
                        tvStatus.text = "The balance does not even cover the fee."
                        tvStatus.setTextColor(AppTheme.WARN); return@runOnUiThread
                    }
                    etAmt.setText("%.8f".format(max / 1e8))
                    tvStatus.text = ""
                }
            }.start()
        }

        // Veredicto en vivo de la dirección de destino.
        etTo.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val a = s?.toString()?.trim() ?: ""
                if (a.isEmpty()) { tvToCheck.text = ""; refreshTotals(); return }
                when (val r = BtcAddress.validate(a, isTestnet)) {
                    is BtcAddress.Result.Valid -> {
                        tvToCheck.text = when (r.info.type) {
                            BtcAddress.Type.P2TR   -> "Taproot, valid"
                            BtcAddress.Type.P2WPKH -> "SegWit, valid"
                            BtcAddress.Type.P2WSH  -> "SegWit script, valid"
                            BtcAddress.Type.P2SH   -> "P2SH, valid"
                            BtcAddress.Type.P2PKH  -> "Legacy, valid"
                        }
                        tvToCheck.setTextColor(AppTheme.ACCENT)
                    }
                    is BtcAddress.Result.Invalid -> {
                        tvToCheck.text = r.reason
                        tvToCheck.setTextColor(AppTheme.RED)
                    }
                }
                refreshTotals()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etAmt.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refreshTotals() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Las comisiones reales, de la mempool. Hasta que respondan, las tres
        // opciones enseñan "—" y el envío va con la que recomiende la red, que
        // es lo que hacía antes el campo vacío.
        // El precio, para enseñar también en dinero normal lo que se envía y la comisión.
        if (!isTestnet) Thread {
            val p = try {
                ChainApi.get("/v1/prices", false)?.let { JSONObject(it).optDouble(fiatCode(), 0.0) } ?: 0.0
            } catch (e: Exception) { 0.0 }
            if (p > 0) Fiat.fijar(this, fiatCode(), p)
            runOnUiThread {
                if (p > 0) {
                    precioFiat = p; refreshTotals()
                    if (fromSat >= 0) tvFromBal.text = "Available %.8f BTC".format(fromSat / 1e8) + fiatEntre(fromSat)
                }
            }
        }.start()
        Thread {
            val f = ChainInfo.fees(isTestnet)
            runOnUiThread {
                if (f == null) {
                    feeEtas.forEach { it.text = "no data" }
                } else {
                    feeRates[0] = f.economy; feeRates[1] = f.halfHour; feeRates[2] = f.fastest
                    listOf("~2 h" to f.economy, "~30 min" to f.halfHour, "~10 min" to f.fastest)
                        .forEachIndexed { i, (eta, r) -> feeEtas[i].text = "$eta · $r sat/vB" }
                }
                paintFees()
            }
        }.start()
        paintFees()

        /* ── MONEDAS (Coin Control) ────────────────────────────────────── */
        rowCoinCtl.setOnClickListener {
            val fromAddr = addresses[keys[fromIdx]] ?: return@setOnClickListener
            tvStatus.text = "Checking the available coins…"
            tvStatus.setTextColor(TXT_SEC)
            Thread {
                try {
                    val lista = ChainInfo.utxos(fromAddr, isTestnet)
                    if (lista == null) {
                        runOnUiThread {
                            tvStatus.text = "Could not query the chain. Check your connection."
                            tvStatus.setTextColor(RED)
                        }
                        return@Thread
                    }
                    if (lista.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "That address has no coins to spend."
                            tvStatus.setTextColor(AppTheme.WARN)
                        }
                        return@Thread
                    }
                    val utxos = JSONArray().also { a -> lista.forEach { a.put(it) } }
                    runOnUiThread {
                        tvStatus.text = ""
                        val items = Array(utxos.length()) { i ->
                            val u = utxos.getJSONObject(i)
                            "${Privacidad.monto(this, "%.8f BTC".format(u.getLong("value") / 1e8) + fiatEntre(u.getLong("value")))} · ${u.getString("txid").take(12)}…"
                        }
                        val checked = BooleanArray(items.size) { true }
                        // La selección se edita en una copia y sólo pasa a
                        // selectedUtxos con "Use these". Antes se rellenaba con
                        // TODAS al abrir: "Cancel" las dejaba elegidas a mano y el
                        // siguiente envío las gastaba todas, sin el selector.
                        val borrador = ArrayList<org.json.JSONObject>()
                        for (i in 0 until utxos.length()) borrador.add(utxos.getJSONObject(i))
                        AlertDialog.Builder(this)
                            .setTitle("Which coins are spent?")
                            .setMultiChoiceItems(items, checked) { _, idx, isChecked ->
                                val u = utxos.getJSONObject(idx)
                                if (isChecked) { if (!borrador.contains(u)) borrador.add(u) }
                                else borrador.remove(u)
                            }
                            .setPositiveButton("Use these") { _, _ ->
                                selectedUtxos.clear(); selectedUtxos.addAll(borrador)
                                tvCoinCtl.text = if (selectedUtxos.isEmpty()) "automatic"
                                    else "%d · %.8f BTC".format(selectedUtxos.size,
                                        selectedUtxos.sumOf { it.getLong("value") } / 1e8)
                            }
                            .setNeutralButton("Automatic") { _, _ ->
                                selectedUtxos.clear(); tvCoinCtl.text = "automatic"
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { tvStatus.text = motivo(e); tvStatus.setTextColor(RED) }
                }
            }.start()
        }

        /* ── ENVIAR ────────────────────────────────────────────────────── */
        btnSend.setOnClickListener {
            val exportar = exportarSiguiente
            exportarSiguiente = false
            // bech32 todo en mayúsculas (QR, o pegado a mano) es válido y es la misma
            // dirección: se pasa a minúsculas antes de validarla y firmarla.
            val toAddr = etTo.text.toString().trim().let {
                if ((it.startsWith("BC1") || it.startsWith("TB1")) && it == it.uppercase()) it.lowercase() else it
            }
            val amtBtc = etAmt.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val fromKey = keys.getOrNull(fromIdx) ?: return@setOnClickListener
            val fromAddr = addresses[fromKey] ?: return@setOnClickListener
            // Una dirección vigilada no tiene clave con la que firmar.
            if (isWifMode && wifKey.isEmpty()) {
                tvStatus.text = "This is a watch-only wallet: it has no key to send with."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            if (toAddr.isEmpty()) {
                tvStatus.text = "The destination address is missing."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            // El teclado decimal deja teclear más de 8 cifras: "0.123456789" se redondeaba en silencio a
            // 12 345 679 sat. PagoUri ya rechazaba esto en las URIs; el campo a mano no.
            if (etAmt.text.toString().replace(',', '.').substringAfter('.', "").length > 8) {
                tvStatus.text = "Bitcoin has at most 8 decimals."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            if (amtBtc.isNaN() || amtBtc.isInfinite() || amtBtc > 21_000_000.0) {
                tvStatus.text = "That amount is not valid."
                tvStatus.setTextColor(RED); return@setOnClickListener
            }
            if (amtBtc <= 0) {
                tvStatus.text = "The amount is missing."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            // Por debajo de 546 sat la red rechaza la salida por "polvo": se
            // firmaba y fallaba al emitirla, con un error que no decía por qué.
            val polvo = CoinSelector.polvoDe(toAddr)
            if (Math.round(amtBtc * 1e8) < polvo) {
                tvStatus.text = "The minimum for this address is $polvo sat: the network rejects smaller amounts."
                tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
            }
            val check = BtcAddress.validate(toAddr, isTestnet)
            if (check is BtcAddress.Result.Invalid) {
                tvStatus.text = "Invalid address: ${check.reason}"
                tvStatus.setTextColor(RED); return@setOnClickListener
            }
            val addrType = (check as BtcAddress.Result.Valid).info.type
            // El contrato de doSend no cambia: -1 sigue queriendo decir "la que
            // recomiende la red". Lo que cambia es que ahora se elige viendo el
            // tiempo estimado en vez de escribiendo un número a ciegas.
            val rate = if (feeRate > 0) feeRate else feeRates[feeSel].takeIf { it > 0 } ?: -1
            // Las salidas extra se revisan igual que la principal: dirección válida,
            // polvo de su tipo, y OP_RETURN en hex de hasta 80 bytes.
            for (e in salidasExtraEnvio) {
                if (e.datos != null) {
                    if (e.datos.isEmpty() || e.datos.length % 2 != 0 || e.datos.length / 2 > 80 ||
                        !e.datos.all { it in "0123456789abcdef" }) {
                        tvStatus.text = "An OP_RETURN must be hex data of up to 80 bytes."
                        tvStatus.setTextColor(RED); return@setOnClickListener
                    }
                } else {
                    val dir = e.addr ?: ""
                    val ok = BtcAddress.validate(dir, isTestnet) is BtcAddress.Result.Valid
                    if (!ok) {
                        tvStatus.text = "An extra output has an invalid address."
                        tvStatus.setTextColor(RED); return@setOnClickListener
                    }
                    if (e.sat > 2_100_000_000_000_000L) {
                        tvStatus.text = "An extra output is larger than all the bitcoin."
                        tvStatus.setTextColor(RED); return@setOnClickListener
                    }
                    if (e.sat < CoinSelector.polvoDe(dir)) {
                        tvStatus.text = "An extra output is below the minimum for its address type (${CoinSelector.polvoDe(dir)} sat)."
                        tvStatus.setTextColor(AppTheme.WARN); return@setOnClickListener
                    }
                }
            }
            doSend(toAddr, amtBtc, rate, fromKey, fromAddr, tvStatus, btnSend, addrType.toString(),
                   salidasExtraEnvio.toList(), exportarPsbt = exportar)
        }
    }

    /**
     * Una salida además de la principal: a una dirección con su importe, o un
     * OP_RETURN con datos (importe 0, no se puede gastar).
     */
    class SalidaExtra(val addr: String?, val datos: String?, val sat: Long) {
        /** Tamaño en vB de esta salida. */
        fun vbytes(): Int = if (datos != null) {
            val spkLen = if (datos.length / 2 <= 75) 2 + datos.length / 2 else 3 + datos.length / 2
            8 + 1 + spkLen
        } else CoinSelector.outputVBytes(addr ?: "")
    }

    private fun doSend(toAddr: String, amtBtc: Double, feeRateManual: Int,
                       fromKey: String, fromAddr: String,
                       tvStatus: TextView, btnSend: View, addrType: String = "",
                       extras: List<SalidaExtra> = emptyList(), exportarPsbt: Boolean = false) {
            // Copia de lo que el envío necesita, tomada aquí: el hilo de abajo no lee
            // campos que cambian al cambiar de cartera o al pulsar otro botón.
            val utxosSel = selectedUtxos.toList()
            val mnSnap = mnemonic
            val wifSnap = isWifMode
            val wifKeySnap = wifKey
            val addrSnap = addresses.toMap()
            tvStatus.text = "Preparing the send…"; tvStatus.setTextColor(TXT_SEC); btnSend.isEnabled = false
            Thread {
                try {
                    // Iba directo a mempool.space sin respaldo. Hay redes e ISP
                    // que no lo alcanzan —lo resuelven a una IP que no es suya—
                    // y entonces no se podía enviar aunque Electrum sí llegara.
                    val lista = ChainInfo.utxos(fromAddr, isTestnet)
                    if (lista == null) {
                        runOnUiThread {
                            tvStatus.text = "Could not query the chain. Check your connection."
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    // Lista vacía es "esta dirección no tiene nada", que es
                    // distinto de "no hubo respuesta".
                    if (lista.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "That address has no coins to spend."
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    // Sin Coin Control sólo se gastan monedas confirmadas: una sin
                    // confirmar de un tercero puede desaparecer (RBF) y llevarse
                    // con ella este envío después de que el destinatario lo viera.
                    val firmes = if (utxosSel.isNotEmpty()) lista
                                 else lista.filter { it.optBoolean("confirmed", true) }
                    if (firmes.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "The coins on that address are not confirmed yet. " +
                                            "Wait for a confirmation, or pick them in Coin control."
                            tvStatus.setTextColor(AppTheme.WARN); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    val fetched = JSONArray().also { a -> firmes.forEach { a.put(it) } }

                    // Coin Control rellenaba utxosSel y el envío lo ignoraba,
                    // gastando siempre todos los UTXOs: la función era decorativa.
                    val chosen: List<org.json.JSONObject> =
                        if (utxosSel.isNotEmpty())
                            utxosSel.filter { sel ->
                                (0 until fetched.length()).any {
                                    val f = fetched.getJSONObject(it)
                                    f.optString("txid") == sel.optString("txid") &&
                                    f.optInt("vout") == sel.optInt("vout")
                                }
                            }
                        else (0 until fetched.length()).map { fetched.getJSONObject(it) }
                    if (chosen.isEmpty()) { runOnUiThread { tvStatus.text = "None of the selected UTXOs is still available"; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread }

                    // Principal más las extra: el total que se firma y se paga.
                    val amtPrincipal = Math.round(amtBtc * 1e8)
                    val amtSat = amtPrincipal + extras.sumOf { it.sat }

                    // La tarifa era un campo con 5 sat/vB por defecto: un número
                    // fijo que no sabe cómo está la mempool. Se pregunta, y el
                    // campo manda sólo si el usuario escribió algo distinto.
                    val red = if (feeRateManual > 0) null else ChainInfo.fees(isTestnet)
                    val feeRate = when {
                        feeRateManual > 0 -> feeRateManual.toLong()
                        red != null       -> red.halfHour.toLong()
                        // Sin red y sin valor escrito: 5 sat/vB era el antiguo
                        // valor por defecto y sigue siendo un respaldo razonable.
                        else              -> 5L
                    }
                    if (feeRate > ChainInfo.TARIFA_MAX) {
                        runOnUiThread {
                            tvStatus.text = "A fee of $feeRate sat/vB is above ${ChainInfo.TARIFA_MAX}: " +
                                            "that is almost certainly a mistake. Check the fee."
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    val fuenteTarifa = when {
                        feeRateManual > 0 -> "the one you set"
                        red != null       -> "recommended, ~30 min"
                        else              -> "fallback: could not query"
                    }

                    // El tamaño de un input depende del tipo: ~148 vB en P2PKH,
                    // ~91 en P2SH-P2WPKH, ~68 en P2WPKH y ~58 en Taproot.
                    val inVBytes  = inputVBytesDe(fromKey)
                    val outVBytes = CoinSelector.outputVBytes(toAddr) + extras.sumOf { it.vbytes() }
                    val chgVBytes = CoinSelector.outputVBytes(fromAddr)

                    // Sin Coin Control manual se gastaban TODOS los UTXOs, con lo
                    // que la comisión crecía con cada entrada innecesaria.
                    val plan0 = if (utxosSel.isNotEmpty()) {
                        val tin = chosen.sumOf { it.getLong("value") }
                        val vsCon = inVBytes * chosen.size + outVBytes + chgVBytes + 11
                        val fCon = feeRate * vsCon
                        val vsSin = inVBytes * chosen.size + outVBytes + 11
                        when {
                            // Con cambio, como antes.
                            tin - amtSat - fCon >= 0 ->
                                CoinSelector.Plan(chosen, fCon, tin - amtSat - fCon, vsCon, "hand-picked")
                            // El cambio no alcanza pero sin salida de cambio sí: lo que sobra va a la
                            // comisión (sin cambio, la comisión es todo lo que no se envía).
                            tin - amtSat - feeRate * vsSin >= 0 ->
                                CoinSelector.Plan(chosen, tin - amtSat, 0L, vsSin, "hand-picked")
                            // No alcanza: la comprobación de fondos de abajo lo dice.
                            else -> CoinSelector.Plan(chosen, fCon, 0L, vsCon, "hand-picked")
                        }
                    } else {
                        // El cambio vuelve a fromAddr: con polvoDe(fromAddr) en vez del fijo de
                        // P2PKH, una dirección segwit/Taproot deja crear un cambio válido más
                        // pequeño en vez de soltarlo entero a la comisión en ese rango estrecho.
                        CoinSelector.select(chosen, amtSat, feeRate, inVBytes, outVBytes, chgVBytes,
                            CoinSelector.polvoDe(fromAddr))
                    }
                    if (plan0 == null || plan0.chosen.isEmpty()) {
                        runOnUiThread {
                            tvStatus.text = "Not enough balance for the amount plus the fee"
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }
                        return@Thread
                    }
                    // El motor no crea un cambio de 546 sat o menos (polvo): se lo
                    // da a la comisión. El diálogo enseñaba ese cambio y la comisión
                    // menor; ahora cuenta lo mismo que se firma.
                    val plan = if (plan0.changeSat in 1..CoinSelector.polvoDe(fromAddr))
                        plan0.copy(feeSat = plan0.feeSat + plan0.changeSat, changeSat = 0L) else plan0
                    // Entradas legacy (P2PKH): su firma NO compromete el importe de la
                    // moneda. Si ese importe viene de un servidor Electrum de respaldo,
                    // uno malicioso podía decir 0,01 BTC por una moneda de 1 BTC y el
                    // resto se iba en comisión con una firma válida. No se firma.
                    val entradaLegacy = fromKey.startsWith("p2pkh") ||
                        (wifSnap && (fromAddr.startsWith("1") || fromAddr.startsWith("m") || fromAddr.startsWith("n")))
                    // Con el nodo propio al día, cada moneda se comprueba contra su conjunto de UTXO: gastada o con otro
                    // importe, no se firma; y un importe que el nodo confirma ya no depende del servidor de respaldo.
                    val porNodo = CoreBackend.verificarMonedas(applicationContext, plan.chosen)
                    if (porNodo is CoreBackend.Verificacion.Mal) {
                        runOnUiThread {
                            tvStatus.text = "Your node disagrees: ${porNodo.motivo}. Nothing was signed."
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    val confirmadoPorNodo = porNodo is CoreBackend.Verificacion.Ok
                    if (entradaLegacy && !confirmadoPorNodo && plan.chosen.any { !ChainInfo.fuenteFiable(it) }) {
                        runOnUiThread {
                            tvStatus.text = "The coin amounts came from a fallback server and can't be checked " +
                                            "for a legacy (1…) address. Try again when mempool.space or " +
                                            "blockstream.info can be reached."
                            tvStatus.setTextColor(AppTheme.WARN); btnSend.isEnabled = true
                        }; return@Thread
                    }
                    val feeSat = plan.feeSat
                    val vsize  = plan.vsize
                    val utxoArr = JSONArray(); var totalIn = 0L
                    for (u in plan.chosen) {
                        val v = u.getLong("value"); totalIn += v
                        utxoArr.put(org.json.JSONObject()
                            .put("txid",   u.getString("txid"))
                            .put("vout",   u.getInt("vout"))
                            .put("amount", v))
                    }
                    if (totalIn < amtSat + feeSat) {
                        runOnUiThread {
                            tvStatus.text = "Not enough funds: $totalIn sat available, ${amtSat + feeSat} needed"
                            tvStatus.setTextColor(RED); btnSend.isEnabled = true
                        }
                        return@Thread
                    }
                    // deriveWallet emite p2pkh_N, p2sh_0, p2wpkh_N y p2tr_N. El
                    // `else` anterior mandaba también las Taproot por m/84', así
                    // que enviar desde una bc1p firmaba con la clave de OTRA
                    // dirección. Además fromKey.last() tomaba un solo carácter:
                    // con índices de dos cifras habría derivado el índice 0.

                    // Los cuatro tipos que la wallet deriva se pueden gastar:
                    // P2PKH heredado, P2SH-P2WPKH (BIP49), P2WPKH nativo y
                    // Taproot por gasto de clave (BIP86 + BIP341 con Schnorr).
                    val pathStr = when {
                        // Un WIF no tiene ruta: el motor firma con la clave del
                        // WIF (ver "wif" en la petición).
                        wifSnap -> ""
                        // Recepción (…/0/i) o cambio (…/1/i), con el coin type de la red.
                        // Un índice ilegible ya no se convierte en 0: no se firma.
                        RutaClave.ruta(fromKey, isTestnet, Frase.cuenta(mnSnap)) != null ->
                            RutaClave.ruta(fromKey, isTestnet, Frase.cuenta(mnSnap))!!
                        else -> {
                            runOnUiThread {
                                tvStatus.text = "Unsupported address type: $fromKey"
                                tvStatus.setTextColor(RED); btnSend.isEnabled = true
                            }
                            return@Thread
                        }
                    }
                    // El cambio volvía a la misma dirección de la que se
                    // gastaba, dejando claro en la cadena cuál de las dos
                    // salidas era el cambio y encadenando el historial. Se busca
                    // la primera dirección sin estrenar de la rama .../1/k.
                    //
                    // Si la red no deja averiguarlo se manda sin change_path y
                    // el motor usa la dirección de origen: peor para la
                    // privacidad, pero el dinero vuelve a una dirección propia,
                    // que es lo que no puede fallar.
                    val purpose = when {
                        fromKey.startsWith("p2pkh")  -> 44
                        fromKey.startsWith("p2sh")   -> 49
                        fromKey.startsWith("p2wpkh") -> 84
                        else                         -> 86
                    }
                    val changePath = if (wifSnap) null
                                     else HdScanner.nextChangePath(mnSnap, purpose, isTestnet)
                    runOnUiThread {
                        tvStatus.text = if (changePath != null)
                            "Change to a new address (${changePath.substringAfterLast('/')})"
                        else "Change back to the source address (could not query the branch)"
                        tvStatus.setTextColor(TXT_SEC)
                    }

                    // Se construía concatenando strings: la dirección venía del
                    // EditText sin escapar, así que unas comillas permitían alterar
                    // los campos amount/fee del JSON que firma el motor.
                    // nLockTime a la altura actual: con 0 cualquiera puede
                    // reminar el último bloque e incluir esta transacción. Sólo
                    // surte efecto porque las entradas van con nSequence por
                    // debajo de 0xFFFFFFFF, cosa que ya hacen por RBF.
                    val tip = ChainInfo.tipHeight(isTestnet)

                    val req = org.json.JSONObject()
                        .put("mnemonic", if (wifSnap) "" else mnSnap)
                    .put("testnet", if (isTestnet) 1 else 0)
                    .put("dust", CoinSelector.polvoDe(fromAddr))
                        .put("path",     pathStr)
                        .apply { if (wifSnap) { put("wif", wifKeySnap); put("wif_tipo", tipoWif(fromKey)) } }
                        .put("utxos",    utxoArr)
                        .put("to",       toAddr)
                        .put("amount",   amtSat)
                        .put("outputs", org.json.JSONArray().apply {
                            put(JSONObject().put("to", toAddr).put("amount", amtPrincipal))
                            extras.forEach { e ->
                                put(if (e.datos != null) JSONObject().put("data", e.datos)
                                    else JSONObject().put("to", e.addr).put("amount", e.sat))
                            }
                        })
                        .put("fee",      feeSat)
                        .apply {
                            if (changePath != null) put("change_path", changePath)
                            if (tip != null) put("locktime", tip)
                        }
                        .toString()

                    // ── Confirmación, ya con las cifras de verdad ──────────
                    //
                    // Antes se preguntaba antes de consultar nada, así que sólo
                    // podía enseñar el importe y la tarifa en sat/vB. Lo que le
                    // importa a quien pulsa es cuánto se le descuenta en total,
                    // y eso depende de cuántas entradas hagan falta.
                    val totalSat = amtSat + feeSat
                    val resumen = buildString {
                        appendLine("Send %.8f BTC".format(amtPrincipal / 1e8) + fiatEntre(amtPrincipal))
                        appendLine("to $toAddr" + if (addrType.isNotEmpty()) " ($addrType)" else "")
                        extras.forEach { e ->
                            if (e.datos != null) appendLine("+ OP_RETURN: ${e.datos.length / 2} bytes of data (0 BTC)")
                            else appendLine("+ %.8f BTC to %s".format(e.sat / 1e8, e.addr))
                        }
                        appendLine()
                        appendLine("Fee        %,d sat  ·  %d sat/vB (%s)".format(feeSat, feeRate, fuenteTarifa) + fiatEntre(feeSat))
                        appendLine("Size       ~%d vB with %d input(s) (%s)".format(vsize, plan.chosen.size, plan.reason))
                        appendLine("TOTAL      %.8f BTC".format(totalSat / 1e8) + fiatEntre(totalSat))
                        if (feeSat > amtSat) {
                            appendLine()
                            appendLine("⚠ The fee is LARGER than the amount you send.")
                        }
                        appendLine()
                        appendLine(if (plan.changeSat == 0L)
                            "No change output: the remainder goes to the fee."
                        else if (changePath != null)
                            "Change %.8f BTC to a new address (%s).".format(plan.changeSat / 1e8, changePath.substringAfterLast('/'))
                        else
                            "Change %.8f BTC to the source address: could not query the branch.".format(plan.changeSat / 1e8))
                        appendLine()
                        appendLine("Sent as replaceable: you can raise the fee if it gets stuck.")
                        appendLine()
                        append("A Bitcoin transaction CANNOT be undone.")
                    }
                    val seguir = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) { seguir.offer(false); return@runOnUiThread }
                        AlertDialog.Builder(this)
                            .setTitle(if (exportarPsbt) "Export as PSBT" else "Review the send")
                            .setMessage(if (exportarPsbt) resumen + "\n\nThe PSBT is not signed: another device " +
                                        "(with the key) signs it, then it is broadcast." else resumen)
                            .setCancelable(false)
                            .setNegativeButton("Cancel") { _, _ -> seguir.offer(false) }
                            .setPositiveButton(if (exportarPsbt) "Export" else "Send") { _, _ -> seguir.offer(true) }
                            .show()
                    }
                    if (seguir.poll(5, java.util.concurrent.TimeUnit.MINUTES) != true) {
                        runOnUiThread {
                            tvStatus.text = "Send cancelled"; tvStatus.setTextColor(TXT_SEC)
                            btnSend.isEnabled = true
                        }
                        return@Thread
                    }

                    // Por encima del umbral de Perfil, el PIN otra vez justo antes de
                    // firmar: la sesión abierta no basta para mover un importe grande.
                    // Lo que sale de verdad de la cartera: importe + comisión, y si no hay
                    // cambio, el sobrante (que se va a la comisión).
                    val debitoSat = totalIn - plan.changeSat
                    // Con la huella activada, enviar pide SIEMPRE el código (sin huella), tenga el importe que tenga.
                    if (WalletManager.hasPin(this) &&
                        (WalletManager.huellaActivada(this) || UmbralEnvio.exige(UmbralEnvio.leer(this), debitoSat))) {
                        val pinOk = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                        runOnUiThread {
                            tvStatus.text = "Enter your PIN to confirm this send"; tvStatus.setTextColor(TXT_SEC)
                            PinAuthHelper.pantalla(this, isSetup = false, autoBiometric = false,
                                                   permitirHuella = false) { ok -> pinOk.offer(ok) }
                        }
                        // Con tope: si la pantalla se cierra sin responder, el hilo no se queda colgado.
                        if (pinOk.poll(5, java.util.concurrent.TimeUnit.MINUTES) != true) {
                            runOnUiThread {
                                tvStatus.text = "Send cancelled"; tvStatus.setTextColor(TXT_SEC)
                                btnSend.isEnabled = true
                            }
                            return@Thread
                        }
                    }

                    if (exportarPsbt) {
                        runOnUiThread { tvStatus.text = "Building the PSBT…"; tvStatus.setTextColor(TXT_SEC) }
                        val sinFirmar = HunterEngine.buildAndSignTx(JSONObject(req).put("unsigned", 1).toString())
                        if (sinFirmar.startsWith("ERROR")) {
                            runOnUiThread { tvStatus.text = sinFirmar; tvStatus.setTextColor(RED); btnSend.isEnabled = true }
                            return@Thread
                        }
                        // Cada entrada lleva su UTXO: el script y el importe, o la transacción
                        // anterior entera si es legacy (su firmante la necesita para el importe).
                        val info = (BtcAddress.validate(fromAddr, isTestnet) as? BtcAddress.Result.Valid)?.info
                            ?: return@Thread runOnUiThread { tvStatus.text = "Could not read the source address."; tvStatus.setTextColor(RED); btnSend.isEnabled = true }
                        val spk = BtcAddress.scriptPubKey(info)
                        val legacy = fromKey.startsWith("p2pkh") ||
                            (wifSnap && (fromAddr.startsWith("1") || fromAddr.startsWith("m") || fromAddr.startsWith("n")))
                        // Derivación de la dirección de origen: sólo si hay semilla (una WIF no tiene
                        // huella del maestro). Sin ella el firmador externo no reconoce la entrada.
                        val rutaOrigen = if (isWifMode) null else rutaDeClave(fromKey)
                        val derivacion: Psbt.Derivacion? = rutaOrigen?.let { ruta ->
                            HunterEngine.infoDeRuta(mnemonic, ruta, isTestnet).split('|').takeIf { it.size == 2 }?.let { p ->
                                fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                                val pub = hexBytes(p[0])
                                Psbt.Derivacion(
                                    pub33 = pub,
                                    huella = hexBytes(p[1]),
                                    ruta = Psbt.rutaAIndices(ruta),
                                    rescate = if (fromKey.startsWith("p2sh")) byteArrayOf(0x00, 0x14) + Ripemd160.hash160(pub) else null,
                                    tapInterna = if (fromKey.startsWith("p2tr")) pub.copyOfRange(1, 33) else null)
                            }
                        }
                        val entradas = plan.chosen.map { u ->
                            val previa = if (!legacy) null else {
                                val hex = ChainApi.get("/tx/${u.optString("txid")}/hex", isTestnet)?.trim()
                                    ?: return@Thread runOnUiThread { tvStatus.text = "Could not fetch a previous transaction for the PSBT."; tvStatus.setTextColor(RED); btnSend.isEnabled = true }
                                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                            }
                            Psbt.EntradaPsbt(u.optLong("value", 0L), spk, previa, derivacion)
                        }
                        val psbt = Psbt.crear(sinFirmar, entradas)
                        runOnUiThread {
                            tvStatus.text = "PSBT ready. Share it to sign on the other device."
                            tvStatus.setTextColor(GREEN); btnSend.isEnabled = true
                            compartirPsbt(psbt)
                        }
                        return@Thread
                    }

                    runOnUiThread { tvStatus.text = "Signing…"; tvStatus.setTextColor(TXT_SEC) }
                    val rawTx = HunterEngine.buildAndSignTx(req)
                    if (rawTx.startsWith("ERROR")) {
                        val txt = when (rawTx) {
                            "ERROR:uncompressed_wif" -> "This is an uncompressed key (5…). This wallet can only " +
                                "spend compressed keys: move the coins with Electrum or Sparrow."
                            "ERROR:bad_to_address" -> "The destination address is not valid."
                            else -> rawTx
                        }
                        runOnUiThread { tvStatus.text = txt; tvStatus.setTextColor(RED); btnSend.isEnabled = true }; return@Thread
                    }
                    runOnUiThread { tvStatus.text = "Broadcasting…"; tvStatus.setTextColor(TXT_SEC) }
                    // Difundir también tenía un único camino. Si la firma salió
                    // bien y no se puede difundir, la transacción se pierde sin
                    // más: mejor que lo intenten los dos.
                    val resp = ChainInfo.broadcast(rawTx, isTestnet)
                    val ok = !resp.startsWith("ERROR")
                    runOnUiThread {
                        tvStatus.text = if (ok) "Sent.\nTransaction ID: $resp"
                                        else resp.removePrefix("ERROR: ")
                        tvStatus.setTextColor(if (ok) GREEN else RED)
                        // Enviado: el mismo formulario otra vez pagaría dos veces, o
                        // intentaría gastar monedas que ya no existen. Se vuelve a
                        // habilitar al cambiar de pestaña, que rehace el formulario.
                        if (ok) { selectedUtxos.clear(); salidasExtraEnvio.clear(); btnSend.isEnabled = false }
                        else btnSend.isEnabled = true
                    }
                } catch(e: Exception) { runOnUiThread { tvStatus.text = motivo(e); tvStatus.setTextColor(RED); btnSend.isEnabled = true } }
            }.start()
    }

    /* -- RECEIVE -- */
    //
    // Era un desplegable con "p2pkh_1  19WiY3ZLfNaLGa…", un QR, la dirección en
    // una línea y "Copy Address". Lo que le faltaba no era estilo:
    //
    //  - El desplegable obligaba a saberse los nombres internos de las claves
    //    para elegir tipo de dirección. Ahora son cuatro pastillas con el
    //    prefijo que vas a ver: bc1q, bc1p, 3…, 1….
    //  - La dirección iba de corrido a 10sp. Va troceada en grupos de cuatro,
    //    alternando tono, que es lo que permite compararla de un vistazo con la
    //    que tienes en la otra pantalla, o leerla en voz alta sin perderte.
    //  - No se podía compartir, sólo copiar.
    //  - Y no decía en ningún sitio que reutilizar una dirección deja tu
    //    historial a la vista de cualquiera, que es la única razón por la que
    //    una cartera va cambiándolas.
    private fun loadReceiveTab() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(26))
        }
        fun side(v: View, top: Int = 0, bottom: Int = 0) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(AppTheme.PAD_SIDE), dp(top), dp(AppTheme.PAD_SIDE), dp(bottom))
            }
        }

        val keys = addresses.keys.toList()
        if (keys.isEmpty()) {
            ll.addView(side(TextView(this).apply {
                text = "There are no addresses to show yet."
                textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
            }, top = 24))
            scroll.addView(ll); tabContent.addView(scroll); return
        }

        /* ── TIPO DE DIRECCIÓN ─────────────────────────────────────────── */
        // Se agrupan por prefijo, que es lo que el otro va a ver pegado en su
        // cartera. Si la cartera no tiene un tipo, su pastilla no sale.
        data class Grupo(val etiqueta: String, val clave: String)
        val grupos = listOf(
            Grupo("bc1q", "p2wpkh"), Grupo("bc1p", "p2tr"),
            Grupo("3…",   "p2sh"),   Grupo("1…",   "p2pkh")
        ).mapNotNull { g -> keys.firstOrNull { tipoDeClave(it).startsWith(g.clave) }?.let { g to it } }
        // Una cartera de sólo observación o de WIF puede no encajar en ninguno:
        // en ese caso se enseña lo que haya, sin selector.
        val efectivos = grupos.ifEmpty { listOf(Grupo(labelMap[keys[0]] ?: "Address", keys[0]) to keys[0]) }
        var tipoSel = 0

        val tipoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tipoPills = mutableListOf<TextView>()

        /* ── QR ────────────────────────────────────────────────────────── */
        val qrWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(AppTheme.TXT_PRI)   // el QR necesita claro, sea cual sea el tema
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val ivQr = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(216), dp(216))
        }
        qrWrap.addView(ivQr)
        val qrCenter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(qrWrap)
        }

        /* ── LA DIRECCIÓN ──────────────────────────────────────────────── */
        val addrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(19), dp(17), dp(19), dp(17))
        }
        val addrHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addrHead.addView(TextView(this).apply {
            text = "Your address"
            textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvEstreno = TextView(this).apply {
            text = "checking…"
            textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.bold(context)
        }
        addrHead.addView(tvEstreno)
        addrCard.addView(addrHead)

        val tvAddr = TextView(this).apply {
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.display(context)
            letterSpacing = 0.02f
            setLineSpacing(0f, 1.75f)
            setPadding(0, dp(10), 0, 0)
        }
        addrCard.addView(tvAddr)
        // Importe opcional: va en el QR como bitcoin:dirección?amount=… (BIP21).
        val etMonto = EditText(this).apply {
            hint = "Amount in BTC (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true; textSize = AppTheme.SP_BODY
            setTextColor(TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        addrCard.addView(etMonto)
        // Lo que vale ese importe en dinero normal, para saber cuánto se está pidiendo.
        val tvFiatMonto = TextView(this).apply {
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC); typeface = AppTheme.body(context)
            setPadding(dp(14), 0, dp(14), dp(4))
        }
        addrCard.addView(tvFiatMonto)
        // Otra dirección nueva de este mismo tipo, sin usar.
        val btnNueva = TextView(this).apply {
            text = "New address"
            textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
            typeface = AppTheme.medium(context); setTextColor(AppTheme.ACCENT)
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true; isFocusable = true
        }
        addrCard.addView(btnNueva)

        /* ── COPIAR Y COMPARTIR ────────────────────────────────────────── */
        fun accion(label: String, iconRes: Int, primary: Boolean, last: Boolean) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(if (primary) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CARD).toFloat()
                }
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                    if (!last) marginEnd = dp(AppTheme.GAP)
                }
                addView(android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(9) }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = AppTheme.SP_BODY
                    setTextColor(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
                    typeface = if (primary) AppTheme.bold(context) else AppTheme.medium(context)
                })
            }
        val btnCopiar    = accion("Copy", R.drawable.ic_copy, primary = true, last = false)
        val btnCompartir = accion("Share", R.drawable.ic_send, primary = false, last = true)
        val accionesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnCopiar); addView(btnCompartir)
        }

        /* ── POR QUÉ CAMBIA ────────────────────────────────────────────── */
        val notaCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg()
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        notaCard.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(TXT_SEC)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) }
        })
        val notaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        notaCol.addView(TextView(this).apply {
            text = "Best to use a new address for each payment"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.body(context)
        })
        notaCol.addView(TextView(this).apply {
            text = "The previous ones are still yours and show up in Balance. " +
                   "Reusing one leaves your history in plain sight."
            textSize = AppTheme.SP_MICRO; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.55f)
            setPadding(0, dp(3), 0, 0)
        })
        notaCard.addView(notaCol)

        if (efectivos.size > 1) ll.addView(side(tipoRow, bottom = 18))
        ll.addView(side(qrCenter, bottom = 18))
        ll.addView(side(addrCard, bottom = 14))
        ll.addView(side(accionesRow, bottom = 22))
        ll.addView(side(notaCard))
        scroll.addView(ll); tabContent.addView(scroll)

        /* ── LÓGICA ────────────────────────────────────────────────────── */
        fun qrBitmap(content: String, size: Int): Bitmap {
            val m = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val w = m.width; val h = m.height
            val px = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) px[row + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565)
        }

        /**
         * La dirección en grupos de cuatro, alternando tono.
         *
         * De corrido, una bech32 de 62 caracteres es imposible de comparar con
         * otra sin ir carácter a carácter. En grupos se compara por bloques, y
         * se puede leer en voz alta sin perder el sitio.
         */
        fun troceada(addr: String): CharSequence {
            val sb = android.text.SpannableStringBuilder()
            var i = 0
            var bloque = 0
            while (i < addr.length) {
                val fin = minOf(i + 4, addr.length)
                val desde = sb.length
                sb.append(addr, i, fin)
                if (bloque % 2 == 1) sb.setSpan(
                    android.text.style.ForegroundColorSpan(TXT_SEC),
                    desde, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (fin < addr.length) sb.append(' ')
                i = fin; bloque++
            }
            return sb
        }

        var addrActual = ""

        fun uriPedido(addr: String, monto: String): String {
            val btc = monto.trim().replace(',', '.').toDoubleOrNull()
            return if (btc != null && btc > 0)
                "bitcoin:$addr?amount=" + String.format(java.util.Locale.US, "%.8f", btc)
            else "bitcoin:$addr"
        }
        fun pintarQr(addr: String) {
            // El importe se lee aquí, en el hilo de la pantalla, y sólo se pinta si
            // sigue siendo el mismo: si no, un QR viejo quedaría con otro importe.
            val monto = etMonto.text.toString()
            Thread {
                try { val bm = qrBitmap(uriPedido(addr, monto), 512)
                      runOnUiThread { if (addrActual == addr && etMonto.text.toString() == monto) ivQr.setImageBitmap(bm) } }
                catch (e: Exception) { android.util.Log.w("WalletActivity", "QR: ${e.message}") }
            }.start()
        }
        etMonto.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (addrActual.isNotEmpty()) pintarQr(addrActual)
                val btc = s?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                if (btc > 0 && btc <= 21_000_000.0) ponerFiat(tvFiatMonto, Math.round(btc * 1e8))
                else tvFiatMonto.text = ""
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        fun mostrar(addr: String) {
            addrActual = addr
            tvAddr.text = troceada(addr)
            pintarQr(addr)
            // "Sin estrenar" no es decorativo: si ya ha recibido algo, decirlo
            // aquí es lo único que evita reutilizarla sin querer.
            tvEstreno.text = "checking…"
            tvEstreno.setTextColor(AppTheme.TXT_MUTED)
            Thread {
                val r = BalanceLookup.query(addr, isTestnet)
                // Usada no es lo mismo que tener saldo: una dirección que recibió
                // y se gastó entera no tiene saldo, pero ya no se debe reutilizar.
                val usada = BalanceLookup.isUsed(addr, isTestnet)
                runOnUiThread {
                    if (addrActual != addr) return@runOnUiThread
                    when {
                        r != null && r.sat > 0L -> { tvEstreno.text = "already has funds"; tvEstreno.setTextColor(AppTheme.WARN) }
                        usada == true           -> { tvEstreno.text = "already used: don't reuse it"; tvEstreno.setTextColor(AppTheme.WARN) }
                        usada == false          -> { tvEstreno.text = "unused";    tvEstreno.setTextColor(AppTheme.ACCENT) }
                        else                    -> { tvEstreno.text = "" }
                    }
                }
            }.start()
        }

        // Nueva dirección: la primera sin usar de este tipo, a partir de la siguiente
        // a la que se ve. Sólo en cartera de semilla: una WIF tiene una sola.
        tvAddr.setOnLongClickListener {
            val a = addrActual
            if (a.isNotEmpty()) {
                val et = EditText(this).apply {
                    setText(etiquetaDeDireccion(a) ?: "")
                    hint = "Name for this address"
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                AlertDialog.Builder(this).setTitle("Name this address").setView(et)
                    .setPositiveButton("Save") { _, _ ->
                        guardarEtiquetaDireccion(a, et.text.toString().trim())
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        btnNueva.setOnClickListener {
            if (isWifMode || mnemonic.isEmpty()) {
                Toast.makeText(this, "Only seed wallets have more addresses", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val k = efectivos[tipoSel].second
            val tipo = k.substringBefore('_')
            val purpose = when (tipo) { "p2pkh" -> 44; "p2sh" -> 49; "p2tr" -> 86; else -> 84 }
            // Desde el último índice ENTREGADO por esta cartera y tipo (guardado), y no
            // desde el de la lista: si no, se devolvía siempre la misma sin pagar.
            val prefsRecv = getSharedPreferences("recv_index", MODE_PRIVATE)
            val clavePref = (currentWalletId.ifEmpty { "main" }) + "|" + tipo
            val desde = maxOf(prefsRecv.getInt(clavePref, -1), k.substringAfter('_', "0").toIntOrNull() ?: 0) + 1
            btnNueva.text = "Searching…"
            Thread {
                var encontrada: Pair<Int, String>? = null
                try {
                    val arr = JSONArray(HunterEngine.deriveAddresses(mnemonic, purpose, 0, desde, 20, isTestnet))
                    for (n in 0 until arr.length()) {
                        val o = arr.optJSONObject(n) ?: continue
                        val a = o.optString("addr")
                        if (a.isEmpty()) continue
                        if (BalanceLookup.isUsed(a, isTestnet) == false) { encontrada = o.optInt("i") to a; break }
                    }
                } catch (e: Exception) { RegistroFallos.anotar("WalletActivity.btnNueva: deriveAddresses", e) }
                runOnUiThread {
                    btnNueva.text = "New address"
                    val hallada = encontrada
                    if (hallada == null) {
                        Toast.makeText(this, "No unused address in the next 20: try again later", Toast.LENGTH_LONG).show()
                    } else {
                        tvEstreno.text = "index ${hallada.first}"
                        prefsRecv.edit().putInt(clavePref, hallada.first).apply()
                        addresses[tipo + "_" + hallada.first] = hallada.second
                        mostrar(hallada.second)
                    }
                }
            }.start()
        }

        fun pintarTipos() {
            tipoPills.forEachIndexed { i, p ->
                val on = i == tipoSel
                p.background = GradientDrawable().apply {
                    setColor(if (on) AppTheme.ACCENT else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                }
                p.setTextColor(if (on) AppTheme.BG_DEEP else TXT_SEC)
                p.typeface = if (on) AppTheme.bold(p.context) else AppTheme.medium(p.context)
            }
        }
        efectivos.forEachIndexed { i, (g, k) ->
            val pill = TextView(this).apply {
                text = g.etiqueta
                textSize = AppTheme.SP_CAPTION
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i < efectivos.size - 1) marginEnd = dp(7) }
                setOnClickListener {
                    tipoSel = i; pintarTipos(); mostrar(addresses[k] ?: return@setOnClickListener)
                }
            }
            tipoPills.add(pill); tipoRow.addView(pill)
        }
        pintarTipos()
        mostrar(addresses[efectivos[0].second] ?: addresses.values.first())

        btnCopiar.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Bitcoin address", addrActual))
            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
        }
        btnCompartir.setOnClickListener {
            // Sólo la dirección: nada de claves. Va como texto plano para que
            // valga en cualquier aplicación.
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, addrActual)
            }, "Share the address"))
        }
    }

    /* -- MENU -- */
    /* -- WALLET SELECTOR -- */

    /** Pide una salida extra: a una dirección con importe, o un OP_RETURN en hex. */
    private fun dialogoSalidaExtra(alGuardar: () -> Unit) {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val etDir = EditText(this).apply { hint = "Recipient address (or leave empty for OP_RETURN)" }
        val etMonto = EditText(this).apply {
            hint = "Amount in BTC"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etDatos = EditText(this).apply { hint = "OP_RETURN data in hex (up to 80 bytes)" }
        caja.addView(etDir); caja.addView(etMonto); caja.addView(etDatos)
        AlertDialog.Builder(this).setTitle("Add output").setView(caja)
            .setPositiveButton("Add") { _, _ ->
                val datos = etDatos.text.toString().trim().lowercase()
                if (datos.isNotEmpty()) {
                    if (datos.length % 2 != 0 || datos.length / 2 > 80 || !datos.all { it in "0123456789abcdef" }) {
                        Toast.makeText(this, "OP_RETURN data must be hex, up to 80 bytes", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    salidasExtraEnvio.add(SalidaExtra(null, datos, 0L))
                } else {
                    val dir = etDir.text.toString().trim()
                    val sat = Math.round((etMonto.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0) * 1e8)
                    if (dir.isEmpty() || sat <= 0) {
                        Toast.makeText(this, "Enter an address and an amount, or OP_RETURN data", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    salidasExtraEnvio.add(SalidaExtra(dir, null, sat))
                }
                alGuardar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lo pone el botón "Export as PSBT" antes de disparar el envío: mismas comprobaciones. */
    private var exportarSiguiente = false

    /** Salidas extra del formulario de Enviar. Se vacían al cambiar de cartera y al enviar. */
    private val salidasExtraEnvio = mutableListOf<SalidaExtra>()

    private fun switchToWallet(onReady: () -> Unit) {
        salidasExtraEnvio.clear()
        ultimoSaldoSat = -1L
        // Limpiar estado anterior. Lo que siga corriendo de la cartera anterior
        // se descarta: comprueba la generación antes de tocar nada.
        generacion++
        historialExtra.clear()
        wifExtras.clear()
        descubriendo = false
        addresses.clear()
        mnemonic = ""
        wifKey = ""
        wifAddr = ""
        isWifMode = false
        // También el id: se quedaba el de la cartera anterior, y "Delete wallet"
        // en la principal borraba la secundaria que se había abierto antes.
        currentWalletId = ""
        selectedUtxos.clear()
        // Ejecutar la carga de la nueva wallet
        onReady()
    }

    /* Cada entrada abre su cartera con conSesion y no con authenticate ni
     * showPinDialog. Esos dos piden la huella o el PIN SIEMPRE, y a esta lista
     * se llega desde la pestaña Wallet, que acaba de pedirlo: elegir una
     * cartera costaba dos PIN seguidos. conSesion sólo lo pide si la sesión
     * está cerrada — pantalla apagada o app en segundo plano —, que es cuando
     * protege algo. */
    private fun showWalletSelectorDialog(forceShow: Boolean = false) {
        val wallets = WalletManager.listWallets(this).toMutableList()
        val hasSeed = WalletManager.hasSeed(this)
        val wifPair = WalletManager.loadWif(this)

        // Si solo hay una seed y sin WIF extras, ir directo (solo si no se fuerza el selector)
        val wifList2 = WalletManager.listWifs(this)
        val watchList2 = WalletManager.listWatchers(this)
        if (!forceShow && hasSeed && wallets.isEmpty() && wifList2.isEmpty() && watchList2.isEmpty()) {
            conSesion {
                mnemonic = WalletManager.loadSeed(this) ?: ""
                currentWalletName = WalletManager.mainName(this)
                loadAddresses(); buildUI()
            }
            return
        }

        val scroll = android.widget.ScrollView(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(sheet)

        sheet.addView(TextView(this).apply {
            text = "Pick a wallet"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0, 0, 0, dp(18))
        })

        /* Cada entrada: su nombre, qué tipo es y su dirección.
         *
         * Antes enseñaba "WIF Wallet" y los ocho primeros caracteres de la
         * CLAVE PRIVADA. Todas las WIF se llamaban igual, así que dos entradas
         * distintas eran indistinguibles; y una lista no es sitio para enseñar
         * trozos de una clave. La dirección identifica la cartera igual de bien
         * y es pública.
         *
         * La tarjeta iba en BG_CARD sobre una hoja BG_PANEL, que son el mismo
         * color: no se veía dónde acababa una entrada y empezaba otra. */
        fun corta(a: String) = if (a.length > 18) a.take(8) + "…" + a.takeLast(6) else a

        fun walletCard(name: String, subtitle: String, origen: String?,
                       onRename: () -> Unit, onClick: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
                }
                clipToOutline = true
                minimumHeight = dp(64)
                setPadding(dp(16), dp(10), dp(4), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                isClickable = true; isFocusable = true
                foreground = Ui.toque()
                contentDescription = "$name. $subtitle"
                setOnClickListener { onClick() }
            }
            val textos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textos.addView(TextView(this).apply {
                text = name; textSize = AppTheme.SP_BODY + 1f; setTextColor(TXT_PRI)
                typeface = AppTheme.bold(context)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textos.addView(TextView(this).apply {
                text = subtitle; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
                typeface = AppTheme.body(context)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            // De dónde viene: creada aquí, añadida a mano, hallada por el
            // puzzle… Las de antes de apuntarlo no lo tienen y no se inventa.
            WalletManager.textoOrigen(origen)?.let { o ->
                textos.addView(TextView(this).apply {
                    text = o; textSize = AppTheme.SP_MICRO
                    setTextColor(if (WalletManager.esDelUsuario(origen)) AppTheme.TXT_MUTED else AppTheme.ACCENT)
                    typeface = AppTheme.medium(context)
                    maxLines = 1
                    setPadding(0, dp(3), 0, 0)
                })
            }
            card.addView(textos)
            // El lápiz: renombrar sin tener que abrir la cartera. 48dp de
            // blanco de toque, separado del resto de la tarjeta para que
            // tocar el nombre abra la cartera y tocar el lápiz no.
            card.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_edit)
                setColorFilter(TXT_SEC)
                setPadding(dp(13), dp(13), dp(13), dp(13))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                isClickable = true; isFocusable = true
                foreground = Ui.toque()
                contentDescription = "Rename $name"
                setOnClickListener { onRename() }
            })
            sheet.addView(card)
        }

        // Wallet principal BIP39
        var selectorDlg: AlertDialog? = null

        if (hasSeed) {
            val nombreMain = WalletManager.mainName(this)
            walletCard(nombreMain, "Main seed · BIP39",
                       WalletManager.origen(this, WalletManager.CLAVE_PRINCIPAL), onRename = {
                renombrar(nombreMain) { n ->
                    WalletManager.renameMain(this, n)
                    selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                }
            }) {
                selectorDlg?.dismiss()
                switchToWallet {
                    conSesion {
                        mnemonic = WalletManager.loadSeed(this) ?: ""
                        currentWalletName = nombreMain; isWifMode = false
                        loadAddresses()
                    }
                }
            }
        }

        wallets.forEach { (id, name) ->
            walletCard(name, "Seed · BIP39", WalletManager.origen(this, id), onRename = {
                renombrar(name) { n ->
                    WalletManager.renameWallet(this, id, n)
                    selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                }
            }) {
                selectorDlg?.dismiss()
                switchToWallet {
                    conSesion {
                        mnemonic = WalletManager.loadWalletSeed(this, id) ?: ""
                        currentWalletId = id; currentWalletName = name; isWifMode = false
                        loadAddresses()
                    }
                }
            }
        }

        // Multiples WIF wallets
        val wifList = WalletManager.listWifs(this)
        wifList.forEach { (wid, wkey, wmeta) ->
            val parts = wmeta.split("|")
            val waddr = parts.getOrNull(0) ?: ""
            val wname = parts.getOrNull(1) ?: "WIF Wallet"
            walletCard(wname, if (waddr.isNotEmpty()) "WIF key · ${corta(waddr)}" else "WIF key",
                WalletManager.origen(this, wid), onRename = {
                    renombrar(wname) { n ->
                        WalletManager.renameWif(this, wid, n)
                        selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                    }
                }) {
                selectorDlg?.dismiss()
                conSesion {
                    switchToWallet {
                        wifKey = wkey
                        wifAddr = if (waddr.isNotEmpty()) waddr
                                  else direccionDeWif(wkey)
                        currentWalletName = wname; isWifMode = true
                        loadAddresses(); buildUI()
                    }
                }
            }
        }

        // Watcher wallets (solo lectura)
        val watchList = WalletManager.listWatchers(this)
        watchList.forEach { (wid, waddr, wlabel) ->
            walletCard(wlabel, "Watch only · ${corta(waddr)}", WalletManager.origen(this, wid), onRename = {
                renombrar(wlabel) { n ->
                    WalletManager.renameWatcher(this, wid, n)
                    selectorDlg?.dismiss(); showWalletSelectorDialog(forceShow = true)
                }
            }) {
                selectorDlg?.dismiss()
                switchToWallet {
                    wifKey = ""; wifAddr = waddr; isWifMode = true
                    currentWalletName = wlabel
                    loadAddresses(); buildUI()
                }
            }
        }

        // Crear, como acción principal; importar y vigilar, debajo.
        val btnCrear = botonHoja("Create new wallet", principal = true)
        sheet.addView(btnCrear)
        sheet.addView(TextView(this).apply {
            text = "Import"; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(dp(2), dp(16), 0, 0)
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun addBtn(label: String, last: Boolean = false) = Button(this).apply {
            text = label; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (!last) marginEnd = dp(6)
            }
        }
        val btnNew   = addBtn("Seed")
        val btnWif   = addBtn("WIF key")
        val btnWatch = addBtn("Watch", last = true)
        // Importar una seed es lo corriente; WIF y cartera vigilada, en las opciones avanzadas.
        if (avanzado()) { btnRow.addView(btnNew); btnRow.addView(btnWif); btnRow.addView(btnWatch) }
        else {
            btnNew.layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            btnNew.text = "Import a seed phrase"
            btnRow.addView(btnNew)
        }
        sheet.addView(btnRow)

        val dlg = AlertDialog.Builder(this).setView(scroll).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        selectorDlg = dlg
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setTitle(null)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()

        btnCrear.setOnClickListener { dlg.dismiss(); showCreateDialog() }
        btnNew.setOnClickListener { dlg.dismiss(); showSetupDialog() }
        btnWif.setOnClickListener { dlg.dismiss(); showWifImportDialog() }
        btnWatch.setOnClickListener { dlg.dismiss(); showWatcherImportDialog() }


    }

    private fun showWifImportDialog() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
            setPadding(dp(22), dp(22), dp(22), dp(24))
        }
        sheet.addView(TextView(this).apply {
            text = "Import a WIF key"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0,0,0,dp(6))
        })
        sheet.addView(TextView(this).apply {
            text = "Paste a WIF key (compressed K/L, uncompressed 5, or testnet c/9), " +
                   "or a 64-character hex private key."
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(18)); setLineSpacing(0f, 1.3f)
        })
        val etWif = EditText(this).apply {
            hint = "5HueCGU8… or 64 hex"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY
            minHeight = dp(48)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        sheet.addView(etWif)
        // Sólo para una clave en hex: la WIF ya dice si es comprimida o no.
        val cbSinComprimir = android.widget.CheckBox(this).apply {
            text = "Uncompressed (only for a hex key)"
            setTextColor(TXT_SEC); textSize = AppTheme.SP_CAPTION
            setPadding(0, dp(8), 0, dp(4))
        }
        sheet.addView(cbSinComprimir)
        val etNombre = campoNombre()
        sheet.addView(etNombre)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        val btnImport = Button(this).apply {
            text = "Import"; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        }
        btnRow.addView(btnImport); btnRow.addView(btnCancel)
        sheet.addView(btnRow)
        val dlg = AlertDialog.Builder(this).setView(sheet).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); finish(); overridePendingTransition(0, 0) }
        btnImport.setOnClickListener {
            val entrada = etWif.text.toString().trim()
            // Lo que se pega se normaliza y se valida en la red de la cartera. Una
            // WIF de la otra red, o algo que no es clave, no se guarda.
            val res = try { HunterEngine.importarClave(entrada, isTestnet, !cbSinComprimir.isChecked) } catch (e: Throwable) { "" }
            val partes = res.split('|')
            if (entrada.isEmpty() || partes.size != 3) {
                Toast.makeText(this, "Not a valid WIF or private key for ${if (isTestnet) "testnet" else "mainnet"}", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val w = partes[0]
            dlg.dismiss()
            guardarConPin(onOk = {
                val derivedAddr = partes[1]
                val nombre = WalletManager.limpiarNombre(etNombre.text.toString()).ifEmpty { "WIF Wallet" }
                if (WalletManager.listWifs(this).any { it.second == w }) {
                    Toast.makeText(this, "That key is already saved", Toast.LENGTH_SHORT).show()
                    return@guardarConPin
                }
                wifKey = w; wifAddr = derivedAddr; isWifMode = true
                currentWalletName = nombre
                WalletManager.saveWif(this, w, derivedAddr, nombre, WalletManager.O_IMPORTADA)
                loadAddresses(); buildUI()
            }, onCancel = { finish() })
        }
    }

    /**
     * Cambiar de cuenta (m/…/N'/…). Una cuenta es otra cartera con las mismas
     * palabras: se guarda como una cartera nueva, y la original no se toca.
     */
    private fun elegirCuenta() {
        val actual = Frase.cuenta(mnemonic)
        val etiquetas = (0..19).map { "Account $it" + if (it == actual) "  (this one)" else "" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Account")
            .setMessage("Same words, another account: a different set of addresses. " +
                        "It is saved as a new wallet; the current one stays.")
            .setItems(etiquetas) { _, i ->
                if (i != actual) {
                    guardarSeedNueva(Frase.conCuenta(mnemonic, i), "$currentWalletName · account $i",
                                     WalletManager.O_IMPORTADA)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Las claves públicas extendidas de la cuenta 0, para vigilar esta cartera
     * en otro sitio sin la semilla. No revelan claves privadas, pero sí todas
     * las direcciones de la cartera: por eso van detrás de la autenticación.
     */
    private fun mostrarXpubs() {
        val frase = mnemonic
        val red = isTestnet
        Thread {
            val partes = listOf(84 to "SegWit (84')", 49 to "SegWit in P2SH (49')", 44 to "legacy (44')")
                .mapNotNull { (p, etiqueta) ->
                    HunterEngine.exportarXpub(frase, p, red).takeIf { it.isNotEmpty() }
                        ?.let { "$etiqueta\n$it" }
                }
            val texto = if (partes.isEmpty()) "Could not derive the account keys." else partes.joinToString("\n\n")
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Account public keys")
                    .setMessage(texto)
                    .setPositiveButton("Copy") { _, _ ->
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("xpub", texto))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
    }

    /**
     * Los scriptPubKey (en hex) que vigila la verificación con la red: las direcciones que
     * muestra la cartera y, en una semilla, las primeras 40 de cada tipo y rama.
     * Hace cálculo nativo: nunca desde el hilo principal.
     */
    private fun scriptsDeCartera(): List<String> {
        val direcciones = LinkedHashSet<String>()
        direcciones.addAll(addresses.values)
        if (wifAddr.isNotEmpty()) direcciones.add(wifAddr)
        if (!isWifMode && mnemonic.isNotEmpty()) {
            for (purpose in HdScanner.PURPOSES) for (change in 0..1) {
                try {
                    val arr = org.json.JSONArray(HunterEngine.deriveAddresses(mnemonic, purpose, change, 0, 40, false))
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i)?.optString("addr", "") ?: ""
                        if (a.isNotEmpty()) direcciones.add(a)
                    }
                } catch (e: Exception) { RegistroFallos.anotar("WalletActivity.scriptsDeCartera: purpose $purpose change $change", e) }
            }
        }
        val out = ArrayList<String>()
        for (a in direcciones) {
            val r = BtcAddress.validate(a, false)
            if (r is BtcAddress.Result.Valid)
                out.add(BtcAddress.scriptPubKey(r.info).joinToString("") { "%02x".format(it) })
        }
        return out
    }

    private fun showMenu() {
        // "Save to finds vault" sólo en las carteras que puso el usuario y que
        // tienen clave: las que vienen de un hallazgo ya están en el baúl, y
        // una vigilada no tiene nada que guardar.
        val claveActual = claveCarteraActual()
        val tieneClave = if (isWifMode) wifKey.isNotEmpty() else mnemonic.isNotEmpty()
        val alBaul = tieneClave && claveActual != null &&
                     WalletManager.esDelUsuario(WalletManager.origen(this, claveActual))
        val av = avanzado()
        val opciones = listOfNotNull("Switch Wallet", "Show seed / WIF",
            if (av && !isWifMode && mnemonic.isNotEmpty()) "Export xpub" else null,
            if (av && !isWifMode && mnemonic.isNotEmpty()) "Account" else null,
            if (av && alBaul) "Save to finds vault" else null,
            // Sin "av": es la única forma de comprobar el saldo sin fiarse de un servidor y sin nodo propio,
            // y escondida tras las opciones avanzadas no la encontraba nadie.
            if (!isTestnet) "Verify with the network" else null,
            if (av && !isTestnet) "Verify with my node" else null,
            // En testnet se ofrece siempre, aunque el modo avanzado esté apagado: si no, quien lo activó y
            // luego lo apagó se quedaba en una red de pruebas sin forma de volver a mainnet.
            "Change PIN", if (av || isTestnet) "Toggle Testnet" else null, "Fiat currency",
            if (av) "Backup vault" else null, if (av) "Restore from file" else null,
            "Delete wallet",
            if (av) "Advanced options: on (turn off)" else "Advanced options: off (turn on)",
            "Cancel")
        AlertDialog.Builder(this).setTitle("Options")
            .setItems(opciones.toTypedArray()) { _, pos ->
                when (opciones[pos]) {
                    "Switch Wallet" -> showWalletSelectorDialog(forceShow = true)
                    "Save to finds vault" -> authenticate { guardarEnBaul(claveActual) }
                    "Show seed / WIF" -> authenticate {
                        // Numerada, una palabra por línea: era un único renglón
                        // de 12 o 24 palabras seguidas, que es justo como se
                        // apunta mal una. Y sin capturas —FLAG_SECURE—, igual
                        // que al crearla: una captura de la seed acaba en la
                        // galería y de ahí en la nube.
                        val msg = if (isWifMode) "WIF: $wifKey"
                                  else Frase.palabras(mnemonic).split(" ").mapIndexed { i, w -> "${i + 1}. $w" }
                                           .joinToString("\n") +
                                       (if (Frase.tienePassphrase(mnemonic))
                                           "\n\nPassphrase (the 25th word, kept apart from the words):\n${Frase.passphrase(mnemonic)}"
                                        else "")
                        val d = AlertDialog.Builder(this).setTitle("Do not show it to anyone")
                            .setMessage(msg).setPositiveButton("Got it", null).create()
                        d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        d.show()
                    }
                    "Export xpub" -> authenticate { mostrarXpubs() }
                    "Advanced options: on (turn off)", "Advanced options: off (turn on)" -> {
                        getSharedPreferences("hunter", MODE_PRIVATE).edit().putBoolean("modo_avanzado", !av).apply()
                        // Al volver a la vista simple no pueden quedar cosas avanzadas que se enviarían sin verse.
                        if (av) { salidasExtraEnvio.clear(); selectedUtxos.clear() }
                        Toast.makeText(this, if (av) "Simple view" else "Advanced options shown", Toast.LENGTH_SHORT).show()
                        buildUI()
                    }
                    "Account" -> authenticate { elegirCuenta() }
                    "Verify with the network" -> VerificacionLigera.mostrar(this, ultimoSaldoSat) { scriptsDeCartera() }
                    "Verify with my node" -> VerificacionNodo.mostrar(this, ultimoSaldoSat) { scriptsDeCartera() }
                    "Fiat currency" -> {
                        val monedas = arrayOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD")
                        AlertDialog.Builder(this).setTitle("Show the value in")
                            .setItems(monedas) { _, i ->
                                getSharedPreferences("hunter", MODE_PRIVATE).edit().putString("fiat_code", monedas[i]).apply()
                                Toast.makeText(this, "Value shown in ${monedas[i]} from the next refresh", Toast.LENGTH_SHORT).show()
                            }.show()
                    }
                    "Change PIN" -> authenticate { showPinDialog(isSetup = true) {} }
                    "Toggle Testnet" -> {
                        isTestnet = !isTestnet
                        // La dirección de una WIF se escribe distinta en cada red: se vuelve a derivar (una
                        // vigilada pegada a mano tiene una red fija y no cambia).
                        if (isWifMode && wifKey.isNotEmpty()) wifAddr = ""
                        // Hay que volver a derivar: en testnet la rama del árbol
                        // es otra (coin type 1'), así que las direcciones que se
                        // están enseñando no son las de esta red. Sin esto el
                        // interruptor cambiaba a qué explorador se preguntaba
                        // pero seguías viendo —y usando— las de mainnet.
                        loadAddresses()
                        Toast.makeText(this,
                            if (isTestnet) "Testnet enabled — addresses reloaded"
                            else "Mainnet — addresses reloaded",
                            Toast.LENGTH_SHORT).show()
                    }
                    // Con el PIN: desde una cartera vigilada (que no lo pide) se llegaba
                    // al baúl de copias sin él, y se podían compartir, borrar o probar
                    // contraseñas sin límite contra copias antiguas cifradas con el PIN.
                    "Backup vault" -> PinAuthHelper.show(this, autoBiometric = false) { ok -> if (ok) showBackupVault() }
                    "Restore from file" -> PinAuthHelper.show(this, autoBiometric = false) { ok -> if (ok) showRestoreDialog() }
                    "Delete wallet" -> {
                        val nombre = currentWalletName.ifEmpty { "this wallet" }
                        val aviso = buildString {
                            append("Delete \u201c$nombre\u201d?\n\n")
                            when {
                                ultimoSaldoSat > 0 -> append("It still holds " +
                                    "%.8f BTC%s. Move the funds out first — after deleting there is no way to spend them.\n\n"
                                        .format(ultimoSaldoSat / 1e8, fiatEntre(ultimoSaldoSat)))
                                ultimoSaldoSat == 0L -> append("Its balance is 0. ")
                                else -> append("Its balance has not been checked. If it might hold funds, move them out first. ")
                            }
                            append("Make sure you have a copy of the key: this cannot be undone.")
                        }
                        AlertDialog.Builder(this).setTitle("Delete the wallet?").setMessage(aviso)
                            .setPositiveButton("Delete") { _, _ ->
                                // Sólo se borra lo que está en pantalla, y se comprueba
                                // antes: con un id o un modo desfasados se borraba OTRA
                                // cartera, sin vuelta atrás.
                                val borrado: Boolean = if (isWifMode) {
                                    /* WIF o vigilada: nunca cae al borrado de una semilla. */
                                    val wifList = WalletManager.listWifs(this)
                                    val match = wifList.firstOrNull {
                                        (wifKey.isNotEmpty() && it.second == wifKey) ||
                                        (wifKey.isEmpty() && wifAddr.isNotEmpty() && it.third.substringBefore('|') == wifAddr)
                                    }
                                    if (match != null) WalletManager.removeWif(this, match.first)
                                    val watchMatch = if (wifAddr.isNotEmpty())
                                        WalletManager.listWatchers(this).firstOrNull { it.second == wifAddr } else null
                                    if (watchMatch != null) WalletManager.removeWatcher(this, watchMatch.first)
                                    match != null || watchMatch != null
                                } else if (mnemonic.isEmpty()) {
                                    false
                                } else if (currentWalletId.isNotEmpty()) {
                                    if (WalletManager.loadWalletSeed(this, currentWalletId) == mnemonic) {
                                        WalletManager.deleteWallet(this, currentWalletId); true
                                    } else false
                                } else {
                                    if (WalletManager.loadSeed(this) == mnemonic) {
                                        WalletManager.clearSeedOnly(this); true
                                    } else false
                                }
                                if (!borrado) {
                                    Toast.makeText(this, "Could not match this wallet to what is stored. Nothing was deleted.",
                                        Toast.LENGTH_LONG).show()
                                    return@setPositiveButton
                                }
                                finish()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            }.show()
    }

    /**
     * La clave con que se apunta el origen de la cartera abierta: "main", el
     * id de la seed, el de la WIF o el de la vigilada. null si no se encuentra.
     */
    private fun claveCarteraActual(): String? = when {
        !isWifMode && currentWalletId.isNotEmpty() -> currentWalletId
        !isWifMode -> WalletManager.CLAVE_PRINCIPAL
        wifKey.isNotEmpty() -> WalletManager.listWifs(this).firstOrNull { it.second == wifKey }?.first
        else -> WalletManager.listWatchers(this).firstOrNull { it.second == wifAddr }?.first
    }

    /**
     * Guarda una copia de la cartera abierta en el baúl de hallazgos: cifrada
     * aparte, con su propia llave del Keystore, y dentro de la copia de
     * seguridad. Borrar la cartera no se la lleva.
     */
    private fun guardarEnBaul(clave: String?) {
        val origen = clave?.let { WalletManager.origen(this, it) }
        val seed = if (isWifMode) "" else mnemonic
        val wif = if (isWifMode) wifKey else ""
        val nombre = currentWalletName; val addr = wifAddr
        Thread {
            val r = try { MatchVault.guardarCartera(this, nombre, origen, seed, wif, addr) }
                    catch (t: Throwable) { RegistroFallos.anotar("WalletActivity.guardarEnBaul", t); -1 }
            runOnUiThread {
                Toast.makeText(this, when (r) {
                    1    -> "Saved to the finds vault"
                    0    -> "It is already in the finds vault"
                    else -> "Could not save it to the vault"
                }, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /* -- SETUP DIALOG -- */
    /* -- SETUP DIALOG -- */
    // ── Crear una cartera nueva ──────────────────────────────────────────
    //
    // Hasta ahora sólo se podía IMPORTAR: escribir una seed que ya tuvieras.
    // Para empezar de cero había que generarla en otra app y copiarla aquí.

    /** Hoja con el aspecto de las demás de esta pantalla, en un diálogo. */
    private fun hojaSegura(contenido: LinearLayout): AlertDialog {
        val scroll = android.widget.ScrollView(this).apply { addView(contenido) }
        val dlg = AlertDialog.Builder(this).setView(scroll).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(),
                      android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Sin capturas ni vista previa en "recientes" mientras hay una seed
            // en pantalla. Una captura de las 12 palabras acaba en la galería
            // y de ahí en la nube: es la forma más común de perder una cartera.
            addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        return dlg
    }

    private fun hojaBase(titulo: String, texto: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
        setPadding(dp(22), dp(22), dp(22), dp(22))
        addView(TextView(this@WalletActivity).apply {
            text = titulo; textSize = AppTheme.SP_TITLE + 2f; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0, 0, 0, dp(8))
        })
        addView(TextView(this@WalletActivity).apply {
            text = texto; textSize = AppTheme.SP_BODY; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun botonHoja(texto: String, principal: Boolean) = Button(this).apply {
        text = texto; textSize = AppTheme.SP_BODY
        setTextColor(if (principal) AppTheme.ON_ACCENT else TXT_PRI)
        typeface = if (principal) AppTheme.bold(context) else AppTheme.medium(context)
        isAllCaps = false
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(if (principal) AppTheme.ACCENT else AppTheme.BG_ELEV)
            cornerRadius = dp(AppTheme.R_INNER).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(10) }
    }

    /**
     * Paso 1: las 12 palabras, para apuntarlas.
     *
     * Recibe la frase en vez de generarla dentro para que "Show the words
     * again", desde la comprobación, vuelva a enseñar LA MISMA y no una nueva.
     */
    private fun showCreateDialog(mn: String = Bip39.generate(12), nombre: String = "") {
        val palabras = mn.split(" ")
        val hoja = hojaBase("Your new wallet",
            "Write these ${palabras.size} words down on paper, in this order. They are the " +
            "only way to recover this wallet, and anyone who has them can take " +
            "its funds. Never type them into a website or send them to anyone.")

        // Se crea aquí y se añade más abajo: el selector de longitud de justo
        // debajo lo necesita para no perder el nombre ya escrito al cambiar.
        val etNombre = campoNombre().apply { setText(nombre) }
        var esteDialogo: AlertDialog? = null

        /* 12 o 24 palabras.
         *
         * Sólo había 12, y no por nada técnico: generate() ya hacía 24 y
         * estaba probado con los vectores de 24. 12 palabras son 128 bits, que
         * es lo mismo que resiste la propia curva de Bitcoin, así que 24 no la
         * hacen más difícil de romper en la práctica. Pero es una elección
         * legítima —hay carteras de hardware que sólo usan 24— y no es a esta
         * app a quien le toca decidirla. Cambiar genera una frase nueva: la de
         * ahora todavía no se ha guardado ni confirmado. */
        val selectorLargo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(BG_DEEP); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        for (n in listOf(12, 24)) {
            val activo = n == palabras.size
            selectorLargo.addView(TextView(this).apply {
                text = "$n words"; gravity = Gravity.CENTER
                textSize = AppTheme.SP_BODY
                setTextColor(if (activo) TXT_PRI else TXT_SEC)
                typeface = if (activo) AppTheme.bold(context) else AppTheme.medium(context)
                background = if (activo) GradientDrawable().apply {
                    setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                } else null
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                isClickable = true; isFocusable = true
                isSelected = activo
                if (!activo) setOnClickListener {
                    esteDialogo?.dismiss()
                    showCreateDialog(Bip39.generate(n), etNombre.text.toString())
                }
            })
        }
        hoja.addView(selectorLargo)
        for (fila in 0 until palabras.size / 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            // Por columnas: la primera mitad a la izquierda y la segunda a la
            // derecha (1-6 y 7-12, o 1-12 y 13-24), que es como se lee de
            // arriba abajo y como vienen las hojas de apuntarlas.
            for (col in 0 until 2) {
                val i = fila + col * (palabras.size / 2)
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (col == 0) marginEnd = dp(6) }
                    addView(TextView(this@WalletActivity).apply {
                        text = "${i + 1}"; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
                        typeface = AppTheme.body(context)
                        layoutParams = LinearLayout.LayoutParams(dp(24),
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    addView(TextView(this@WalletActivity).apply {
                        text = palabras[i]; textSize = AppTheme.SP_BODY + 1f; setTextColor(TXT_PRI)
                        typeface = AppTheme.bold(context)
                    })
                })
            }
            hoja.addView(row)
        }

        hoja.addView(etNombre)

        val chk = android.widget.CheckBox(this).apply {
            text = "I have written them down"
            textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.body(context)
            buttonTintList = android.content.res.ColorStateList.valueOf(AppTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        hoja.addView(chk)

        val btnSeguir = botonHoja("Continue", principal = true).apply { isEnabled = false; alpha = 0.4f }
        val btnCancelar = botonHoja("Cancel", principal = false)
        hoja.addView(btnSeguir); hoja.addView(btnCancelar)
        chk.setOnCheckedChangeListener { _, marcado ->
            btnSeguir.isEnabled = marcado; btnSeguir.alpha = if (marcado) 1f else 0.4f
        }

        val dlg = hojaSegura(hoja)
        esteDialogo = dlg
        dlg.show()
        btnCancelar.setOnClickListener { dlg.dismiss(); finish(); overridePendingTransition(0, 0) }
        btnSeguir.setOnClickListener {
            dlg.dismiss()
            comprobarFrase(mn, etNombre.text.toString())
        }
    }

    /**
     * Paso 2: dos palabras al azar, escritas de memoria del papel.
     *
     * Marcar "las he apuntado" no demuestra nada, y una cartera cuya frase no
     * está apuntada funciona perfectamente —hasta el día que se pierde el
     * móvil—. Pedir dos palabras sueltas cuesta diez segundos y es lo que
     * separa "las he apuntado" de "las apuntaré luego".
     */
    private fun comprobarFrase(mn: String, nombre: String) {
        val palabras = mn.split(" ")
        val azar = java.security.SecureRandom()
        val a = azar.nextInt(palabras.size)
        var b = azar.nextInt(palabras.size - 1); if (b >= a) b++
        val (p1, p2) = if (a < b) Pair(a, b) else Pair(b, a)

        val hoja = hojaBase("Check your words",
            "Type these two words from what you wrote down.")
        fun campo(n: Int) = campoNombre().apply {
            hint = "Word #${n + 1}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val et1 = campo(p1); val et2 = campo(p2)
        hoja.addView(et1); hoja.addView(et2)
        val btnOk = botonHoja("Create wallet", principal = true)
        val btnVer = botonHoja("Show the words again", principal = false)
        hoja.addView(btnOk); hoja.addView(btnVer)

        val dlg = hojaSegura(hoja)
        dlg.show()
        btnVer.setOnClickListener { dlg.dismiss(); showCreateDialog(mn, nombre) }
        btnOk.setOnClickListener {
            val ok1 = et1.text.toString().trim().lowercase() == palabras[p1]
            val ok2 = et2.text.toString().trim().lowercase() == palabras[p2]
            if (!ok1 || !ok2) {
                val mal = if (!ok1) p1 + 1 else p2 + 1
                Toast.makeText(this, "That is not word #$mal. Check what you wrote down.",
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dlg.dismiss()
            guardarConPin(onOk = { guardarSeedNueva(mn, nombre, WalletManager.O_CREADA) }, onCancel = { finish() })
        }
    }

    /**
     * Guarda una seed recién escrita y la abre.
     *
     * Esto llamaba siempre a saveSeed, que escribe la seed PRINCIPAL. Con una
     * principal ya guardada, "añadir" otra la SUSTITUÍA: la anterior se perdía,
     * y si no había copia de seguridad, con ella el acceso a sus fondos. Las
     * seeds adicionales ya existían —saveWallet, que usa la restauración de
     * copias—, sólo que añadir no las usaba.
     *
     * Ahora la primera va de principal y las siguientes, al lado. Y si la seed
     * ya está guardada, se abre la que hay en vez de duplicarla.
     */
    private fun guardarSeedNueva(mn: String, nombreEscrito: String, origen: String) {
        // La lógica vive en WalletManager.agregarSeed, que la comparte con
        // Recovery y el baúl: principal si no hay, al lado si ya hay, y sin
        // duplicar la que ya esté.
        val g = WalletManager.agregarSeed(this, mn, nombreEscrito, origen)
        if (g.yaEstaba) Toast.makeText(this, "That seed is already saved", Toast.LENGTH_SHORT).show()
        currentWalletId = g.id; currentWalletName = g.nombre
        mnemonic = mn; isWifMode = false
        loadAddresses(); buildUI()
    }

    private fun showSetupDialog() {
        val scroll = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat()
            }
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        scroll.addView(layout)
        layout.addView(TextView(this).apply {
            text = "Import a wallet"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0, 0, 0, dp(6))
        })
        layout.addView(TextView(this).apply {
            text = "Type the 12 or 24 words of your BIP39 seed."
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(22)); setLineSpacing(0f, 1.3f)
        })
        val tvCount = TextView(this).apply {
            text = "0 / 24 words"; textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.medium(context)
            gravity = Gravity.END; setPadding(0, 0, 0, dp(4))
        }
        layout.addView(tvCount)
        val etSeed = EditText(this).apply {
            hint = "word1 word2 word3 …"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply {
                setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY; minLines = 3; maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        layout.addView(etSeed)
        val suggestScroll = android.widget.HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(BG_DEEP); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
        }
        val suggestInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6),dp(6),dp(6),dp(6)) }
        suggestScroll.addView(suggestInner)
        layout.addView(suggestScroll)
        val etNombre = campoNombre()
        layout.addView(etNombre)
        val bip39 = Bip39Words.WORDS
        fun updateSuggestions(cur: String) {
            suggestInner.removeAllViews()
            if (cur.length < 2) return
            bip39.filter { it.startsWith(cur) }.take(7).forEach { word ->
                suggestInner.addView(Button(this@WalletActivity).apply {
                    text = word; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_CHIP).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(6) }
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener {
                        val t = etSeed.text.toString()
                        val sp = t.lastIndexOf(' ')
                        val nt = if (sp >= 0) t.substring(0, sp + 1) + word + " " else "$word "
                        etSeed.setText(nt); etSeed.setSelection(nt.length)
                        suggestInner.removeAllViews()
                    }
                })
            }
        }
        etSeed.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val txt = s.toString()
                val words = txt.trim().split(" +".toRegex()).filter { it.isNotEmpty() }
                val cnt = words.size
                tvCount.text = "$cnt / 24 words"
                tvCount.setTextColor(when { cnt == 12 || cnt == 24 -> GREEN; cnt > 24 -> RED; else -> TXT_MUTED })
                val lastWord = if (txt.endsWith(" ")) "" else words.lastOrNull() ?: ""
                updateSuggestions(lastWord)
            }
        })
        layout.addView(TextView(this).apply {
            text = "Seed encrypted with Android Keystore"
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(0, dp(16), 0, dp(4))
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        val btnNext = Button(this).apply {
            text = "Import"; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.display(context)
            background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.display(context)
            background = GradientDrawable().apply { setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        btnRow.addView(btnNext); btnRow.addView(btnCancel)
        // Passphrase opcional (la palabra 25). Con ella las mismas palabras dan otra
        // cartera: si la pone, tiene que guardarla aparte, porque sin ella no se recupera.
        // Sin sugerencias del teclado: la passphrase no se guarda en el diccionario del teclado.
        val etPassphrase2 = EditText(this).apply {
            hint = "Repeat the passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val etPassphrase = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "Passphrase (optional, the 25th word)"
            setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        layout.addView(etPassphrase)
        layout.addView(etPassphrase2)
        layout.addView(TextView(this).apply {
            text = "Keep the passphrase written down, apart from the words. Without it this wallet " +
                   "cannot be recovered, and it cannot be recovered from the words alone."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, 0)
        })
        layout.addView(btnRow)
        val dlg = AlertDialog.Builder(this).setView(scroll).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                      android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); finish(); overridePendingTransition(0, 0) }
        btnNext.setOnClickListener {
            val palabras = Bip39.normalize(etSeed.text.toString()).joinToString(" ")
            // Sólo se contaban palabras: una seed mal tecleada se guardaba igual
            // y la wallet derivaba direcciones ajenas, mostrando saldo cero.
            when (val v = Bip39.validate(palabras)) {
                is Bip39.Result.Invalid -> {
                    Toast.makeText(this, v.reason, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                else -> {}
            }
            val pass = etPassphrase.text.toString()
            Frase.errorPassphrase(pass)?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Una passphrase mal tecleada da otra cartera sin error: se pide dos veces.
            if (pass != etPassphrase2.text.toString()) {
                Toast.makeText(this, "The two passphrases do not match", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val mn = Frase.unir(palabras, pass)
            dlg.dismiss()
            guardarConPin(onOk = { guardarSeedNueva(mn, etNombre.text.toString(), WalletManager.O_IMPORTADA) },
                          onCancel = { finish() })
        }
    }

    private fun showWatcherImportDialog() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG_PANEL); cornerRadius = dp(AppTheme.R_CARD).toFloat() }
            setPadding(dp(22), dp(22), dp(22), dp(24))
        }
        sheet.addView(TextView(this).apply {
            text = "Watch Address"; textSize = AppTheme.SP_TITLE; setTextColor(TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(0,0,0,dp(6))
        })
        sheet.addView(TextView(this).apply {
            text = "Monitor any Bitcoin address (read-only, no private key needed)"
            textSize = AppTheme.SP_CAPTION; setTextColor(TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, 0, dp(18)); setLineSpacing(0f, 1.3f)
        })
        val etAddr = EditText(this).apply {
            hint = "bc1q... or 1... or 3..."; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY
            minHeight = dp(48)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val etLabel = EditText(this).apply {
            hint = "Label (e.g. Puzzle #71)"; setTextColor(TXT_PRI); setHintTextColor(TXT_MUTED)
            background = GradientDrawable().apply { setColor(BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = AppTheme.SP_BODY
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        sheet.addView(etAddr); sheet.addView(etLabel)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(14),0,0) }
        val btnAdd = Button(this).apply {
            text = "Watch"; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancel = Button(this).apply {
            text = "Cancel"; textSize = AppTheme.SP_BODY; setTextColor(TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply { setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        }
        btnRow.addView(btnAdd); btnRow.addView(btnCancel); sheet.addView(btnRow)
        val dlg = AlertDialog.Builder(this).setView(sheet).setCancelable(true).create()
        dlg.setOnCancelListener { finish(); overridePendingTransition(0, 0) }
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.75f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        btnCancel.setOnClickListener { dlg.dismiss(); finish(); overridePendingTransition(0, 0) }
        btnAdd.setOnClickListener {
            val addr = etAddr.text.toString().trim()
            val label = etLabel.text.toString().trim().ifEmpty { "Watcher" }
            // Una dirección de la otra red, o con una letra mal, se guardaba y luego
            // sólo decía "no answer". Se valida con la red de esta cartera.
            when (val v = BtcAddress.validate(addr, isTestnet)) {
                is BtcAddress.Result.Invalid -> {
                    Toast.makeText(this, "Not a valid ${if (isTestnet) "testnet" else "mainnet"} address: ${v.reason}", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                is BtcAddress.Result.Valid -> {}
            }
            dlg.dismiss()
            WalletManager.saveWatcher(this, addr, label, WalletManager.O_IMPORTADA)
            wifKey = ""; wifAddr = addr; isWifMode = true
            currentWalletName = label
            loadAddresses(); buildUI()
        }
    }

    /** Campo de contraseña con el aspecto de la app. */
    private fun campoSecreto(pista: String) = Ui.input(this, pista).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    /**
     * Crea una copia con su PROPIA contraseña.
     *
     * Antes se cifraba con el PIN de 6 dígitos: un millón de posibilidades,
     * que un ordenador prueba en horas si consigue el fichero. Y este fichero
     * se comparte, se sube a la nube, se manda por correo. Ver
     * WalletManager.cifrarCopia.
     */
    private fun showBackupDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        root.addView(TextView(this).apply {
            text = "A password only for this backup, of at least " +
                   "${WalletManager.MIN_CONTRASENA_COPIA} characters. Not your PIN: " +
                   "the file leaves the phone, and a 6-digit PIN is guessed in hours.\n\n" +
                   "Write it down. Without it the backup cannot be opened."
            setTextColor(AppTheme.TXT_SEC); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.3f)
        })
        val et1 = campoSecreto("Password")
        val et2 = campoSecreto("Repeat the password")
        // Medidor de fuerza: una estimación, no una garantía. Avisa mientras se escribe.
        val tvFuerza = TextView(this).apply {
            textSize = AppTheme.SP_CAPTION; typeface = AppTheme.body(context)
            setPadding(0, dp(6), 0, 0)
        }
        et1.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val p = s?.toString() ?: ""
                if (p.isEmpty()) { tvFuerza.text = ""; return }
                val r = FuerzaContrasena.evaluar(p)
                tvFuerza.text = "Strength: ${r.nivel.texto} (about ${r.bits.toInt()} bits, an estimate)"
                tvFuerza.setTextColor(when (r.nivel) {
                    FuerzaContrasena.Nivel.DEBIL -> AppTheme.RED
                    FuerzaContrasena.Nivel.JUSTA -> AppTheme.WARN
                    else -> AppTheme.ACCENT
                })
            }
        })
        root.addView(et1); root.addView(tvFuerza); root.addView(et2)

        val d = AlertDialog.Builder(this)
            .setTitle("New backup")
            .setView(root)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener {
            // El botón no cierra el diálogo si algo falla: se corrige aquí mismo.
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = et1.text.toString(); val p2 = et2.text.toString()
                when {
                    p1.length < WalletManager.MIN_CONTRASENA_COPIA ->
                        et1.error = "At least ${WalletManager.MIN_CONTRASENA_COPIA} characters"
                    !FuerzaContrasena.aceptable(p1) ->
                        et1.error = "Too easy to guess: use a longer phrase, or mix letters, digits and symbols"
                    p1 != p2 -> et2.error = "They do not match"
                    else -> {
                        d.dismiss()
                        Toast.makeText(this, "Encrypting the backup…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val file = try { WalletManager.exportBackup(this, p1) } catch (e: Exception) { RegistroFallos.anotar("WalletActivity.exportBackup", e); null }
                            runOnUiThread {
                                if (file != null) {
                                    Toast.makeText(this, "Backup saved to the vault", Toast.LENGTH_SHORT).show()
                                    showBackupVault()
                                } else {
                                    Toast.makeText(this, "No backup made: there is nothing to export, or something " +
                                        "could not be read (the app may have locked). Unlock and try again.",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    }
                }
            }
        }
        d.show()
    }

    // ── Baúl de copias ────────────────────────────────────────────────────────

    /** Lista las copias guardadas. Cada una se comparte, se mira o se restaura. */
    private fun showBackupVault() {
        val copias = BackupStore.list(this)
        val b = AlertDialog.Builder(this)
            .setTitle("Backup vault (${copias.size}/${BackupStore.MAX_KEPT})")

        if (copias.isEmpty()) {
            b.setMessage("No backup yet.\n\nA backup holds the seeds, " +
                         "the WIFs, the watchers and the vault finds, encrypted with " +
                         "a password of its own. The ${BackupStore.MAX_KEPT} most recent ones are kept.")
        } else {
            val items = copias.map {
                "${BackupStore.humanDate(it.createdAt)}  ·  ${BackupStore.humanSize(it.bytes)}"
            }.toTypedArray()
            b.setItems(items) { _, i -> showBackupActions(copias[i]) }
        }

        b.setPositiveButton("Create backup") { _, _ -> showBackupDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Qué hacer con una copia concreta. */
    private fun showBackupActions(info: BackupStore.Info) {
        val acciones = arrayOf(
            "Share",
            "View contents",
            "Restore this backup",
            "Delete")
        AlertDialog.Builder(this)
            .setTitle(BackupStore.humanDate(info.createdAt))
            .setItems(acciones) { _, which ->
                when (which) {
                    0 -> try {
                        startActivity(BackupStore.shareIntent(this, info.file))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this, "Could not share: ${e.message}",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    1 -> askPinFor("View contents", info.file.readBytes()) { pin -> inspectBackupFile(info, pin) }
                    2 -> askPinFor("Restore backup", info.file.readBytes()) { pin -> restoreFromVault(info, pin) }
                    3 -> confirmDeleteBackup(info)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Pide la contraseña de una copia. Las de antes de la contraseña propia
     * se abren con el PIN que hubiera cuando se crearon: se mira el fichero
     * para decir cuál de las dos toca.
     */
    private fun askPinFor(titulo: String, data: ByteArray, onPin: (String) -> Unit) {
        val conPin = WalletManager.copiaConPin(data)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        root.addView(TextView(this).apply {
            text = if (conPin) "An old backup: it opens with the PIN you had when you created it."
                   else "The password of this backup."
            setTextColor(AppTheme.TXT_SEC); textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.3f)
        })
        val et = campoSecreto(if (conPin) "PIN of the backup" else "Password").apply {
            if (conPin) inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        root.addView(et)
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(root)
            .setPositiveButton("OK") { _, _ -> onPin(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Descifra en segundo plano: 600 000 vueltas de PBKDF2 son un par de
     * segundos en un móvil, y en el hilo principal congelaban la pantalla.
     */
    private fun abrirCopia(secreto: String, data: ByteArray,
                           listo: (WalletManager.BackupSummary?) -> Unit) {
        Toast.makeText(this, "Opening the backup…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = try { WalletManager.inspectBackup(secreto, data) } catch (e: Exception) { null }
            runOnUiThread { if (!isFinishing) listo(r) }
        }.start()
    }

    /** Descifra y enseña qué trae la copia, sin mostrar ningún secreto. */
    private fun inspectBackupFile(info: BackupStore.Info, pin: String) {
        abrirCopia(pin, info.file.readBytes()) { resumen ->
            if (resumen == null) {
                AlertDialog.Builder(this)
                    .setTitle("Could not open it")
                    .setMessage("Wrong password, or the file is not a valid backup.")
                    .setPositiveButton("Got it", null)
                    .show()
                return@abrirCopia
            }
            val detalle = buildString {
                appendLine("Created: ${BackupStore.humanDate(resumen.createdAt)}")
                appendLine("Format: v${resumen.version}")
                appendLine("Size: ${BackupStore.humanSize(info.bytes)}")
                appendLine()
                appendLine("Main seed: ${if (resumen.hasMainSeed) "yes" else "no"}")
                appendLine("Wallets: ${resumen.wallets}")
                appendLine("WIF keys: ${resumen.wifs}")
                appendLine("Watch-only: ${resumen.watchers}")
                appendLine("Finds: ${resumen.matches}")
                if (resumen.version < 2) {
                    appendLine()
                    appendLine("Old backup: wallets only. The main seed, " +
                               "the WIFs, the watchers and the finds were not stored.")
                }
            }
            AlertDialog.Builder(this)
                .setTitle("Backup contents")
                .setMessage(detalle)
                .setPositiveButton("Restore") { _, _ -> restoreFromVault(info, pin) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    /** Restaura sin pasar por el selector de ficheros. Añade, no reemplaza. */
    private fun restoreFromVault(info: BackupStore.Info, pin: String) {
        confirmarRestaurar(pin, info.file.readBytes())
    }

    /** Enseña qué se va a añadir y, si se confirma, lo restaura. */
    private fun confirmarRestaurar(pin: String, data: ByteArray) {
        abrirCopia(pin, data) { resumen ->
            if (resumen == null) {
                Toast.makeText(this, "Wrong password or invalid backup", Toast.LENGTH_SHORT).show()
                return@abrirCopia
            }
            AlertDialog.Builder(this)
                .setTitle("Restore this backup?")
                // Ya no sustituye nada: la seed principal de la copia va al
                // lado de la que haya (WalletManager.agregarSeed). El aviso
                // de antes decía lo contrario.
                .setMessage("It will be added to what you have: ${resumen.wallets} wallet(s), " +
                            "${resumen.wifs} WIF, ${resumen.watchers} watch-only and " +
                            "${resumen.matches} find(s)." +
                            (if (resumen.hasMainSeed) " Its main seed goes next to yours, " +
                                                      "without replacing it." else "") +
                            " Nothing you have is deleted." +
                            // Las copias de antes se cifraron con el PIN de 6 dígitos: débil.
                            (if (WalletManager.copiaConPin(data))
                                "\n\nThis backup uses the old encryption (a 6-digit PIN, guessed in " +
                                "hours). After restoring, create a new backup with a strong password " +
                                "and delete this one." else ""))
                .setPositiveButton("Restore") { _, _ ->
                    Thread {
                        val count = WalletManager.importBackup(this, pin, data)
                        runOnUiThread {
                            if (count >= 0) {
                                Toast.makeText(this, "$count item(s) restored", Toast.LENGTH_SHORT).show()
                                buildUI()
                            } else if (count == WalletManager.RESTAURAR_SIN_SESION) {
                                Toast.makeText(this, "Unlock with your PIN first: the backup has seeds. Nothing was restored.",
                                    Toast.LENGTH_LONG).show()
                            } else if (count == WalletManager.RESTAURAR_SIN_LEER_WIFS) {
                                Toast.makeText(this, "Could not read the WIF keys stored here. Nothing was restored.",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Restore failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmDeleteBackup(info: BackupStore.Info) {
        AlertDialog.Builder(this)
            .setTitle("Delete this backup?")
            .setMessage("${BackupStore.humanDate(info.createdAt)}\n\n" +
                        "It cannot be undone. If it is the only one holding your seeds, " +
                        "they go with it.")
            .setPositiveButton("Delete") { _, _ ->
                BackupStore.delete(this, info.file)
                android.widget.Toast.makeText(this, "Backup deleted",
                    android.widget.Toast.LENGTH_SHORT).show()
                showBackupVault()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRestoreDialog() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_IMPORT_BACKUP)
    }

    private fun doRestore(uri: android.net.Uri) {
        val data = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
        if (data == null) {
            Toast.makeText(this, "Could not read the file", Toast.LENGTH_SHORT).show()
            return
        }
        askPinFor("Restore backup", data) { pin -> confirmarRestaurar(pin, data) }
    }



    /**
     * Faltaba por completo: showRestoreDialog() lanzaba el selector de ficheros
     * con REQ_IMPORT_BACKUP, pero esta clase no tenía onActivityResult, así que
     * doRestore() nunca llegaba a ejecutarse. "Restaurar Backup" abría el
     * selector y descartaba el fichero en silencio — sin forma de recuperar las
     * wallets desde una copia de seguridad.
     */
    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_IMPORT_BACKUP && res == RESULT_OK) {
            data?.data?.let { doRestore(it) }
        }
        if (req == REQ_QR && res == RESULT_OK) {
            data?.getStringExtra(QrScanActivity.EXTRA_TEXTO)?.let { alLeerQr?.invoke(it) }
        }
    }
}
