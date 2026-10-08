package com.hunter.btc

import android.content.Context
import android.graphics.Color

/**
 * Sistema visual "Bóveda".
 *
 * La app mezclaba TRES paletas: la gris de las pantallas principales, una
 * azulada en cluster y recovery (#171C2C, #1E2540, #0B0E14…) y una verdosa en
 * la wallet y el teclado de PIN (#E6EAD8, #556050, #2A3028…). Cada pantalla
 * parecía de una aplicación distinta, y los colores estaban escritos a mano en
 * 247 sitios, así que cambiar cualquier cosa obligaba a repasarlos todos.
 *
 * Aquí está la paleta única, y con ella la escala tipográfica, los radios y el
 * espaciado que antes tampoco existían: cada pantalla elegía su propio tamaño
 * de letra y su propio radio de esquina.
 *
 * REGLA DEL ACENTO: [ACCENT] sólo en tres sitios — la acción principal, un
 * saldo positivo y el estado "está corriendo". En ningún otro. Antes pintaba
 * también el dataset, el ritmo y los BTC disponibles, y cuando un color
 * significa cinco cosas deja de significar ninguna.
 */
object AppTheme {

    /**
     * Los temas disponibles. OSCURO y CLARO son los de siempre; MEDIANOCHE es
     * un azul-tinta nuevo; AUTO es el híbrido día/noche: claro de día, oscuro
     * de noche, según la hora del móvil.
     */
    enum class Modo { OSCURO, CLARO, MEDIANOCHE, NEON, EXCHANGE, AUTO }

    /** El modo elegido por el usuario. AUTO no es una paleta en sí: se resuelve
     *  a clara u oscura al arrancar cada pantalla. */
    @Volatile var modo: Modo = Modo.OSCURO
        private set

    /** Una paleta concreta. Las superficies y el texto cambian siempre; el
     *  acento lo lleva cada tema (el verde de siempre, o uno futurista) pero
     *  SIGUE siendo un solo acento con un solo significado. Los otros
     *  semánticos (rojo, naranja, azul) son fijos. */
    private class Paleta(
        val bgDeep: Int, val bgPanel: Int, val bgCard: Int, val bgElev: Int, val bgKey: Int,
        val border: Int, val txtPri: Int, val txtSec: Int, val txtMuted: Int, val bgStop: Int,
        val accent: Int, val onAccent: Int, val oscuro: Boolean
    )

    private fun c(hex: String) = Color.parseColor(hex)

    private const val VERDE = 0xFF00C896.toInt()        // el acento histórico
    private const val SOBRE_VERDE = 0xFF0F1210.toInt()

    private val OSCURA = Paleta(
        bgDeep = c("#191919"), bgPanel = c("#232323"), bgCard = c("#232323"),
        bgElev = c("#2D2D2D"), bgKey = c("#282828"), border = c("#383838"),
        txtPri = c("#F2F2F2"), txtSec = c("#A4A4A4"), txtMuted = c("#6C6C6C"),
        bgStop = c("#331F1F"), accent = VERDE, onAccent = SOBRE_VERDE, oscuro = true)

    private val CLARA = Paleta(
        bgDeep = c("#F4F4F4"), bgPanel = c("#FFFFFF"), bgCard = c("#FFFFFF"),
        bgElev = c("#E8E8E8"), bgKey = c("#EDEDED"), border = c("#E0E0E0"),
        txtPri = c("#0A0A0A"), txtSec = c("#444444"), txtMuted = c("#909090"),
        bgStop = c("#FBE3E3"), accent = VERDE, onAccent = SOBRE_VERDE, oscuro = false)

    /**
     * MEDIANOCHE: azul-tinta profundo. Mantiene los mismos escalones de
     * elevación que el oscuro (unos diez valores por nivel) pero con tinte frío
     * y textos en blanco azulado. El acento verde resalta más sobre azul que
     * sobre gris, así que el tema se siente distinto sin tocar la semántica.
     */
    private val MEDIANOCHE = Paleta(
        bgDeep = c("#10131A"), bgPanel = c("#181D27"), bgCard = c("#181D27"),
        bgElev = c("#232A39"), bgKey = c("#1E2430"), border = c("#2C3444"),
        txtPri = c("#ECEFF6"), txtSec = c("#9AA6BD"), txtMuted = c("#5E6880"),
        bgStop = c("#3A1E26"), accent = VERDE, onAccent = SOBRE_VERDE, oscuro = true)

    /**
     * NEON: cibernético. Negro violáceo con textos en lavanda y un acento cian
     * eléctrico. El acento sí cambia aquí —es el punto del tema— pero sigue
     * marcando lo mismo: acción, positivo, corriendo.
     */
    private val NEON = Paleta(
        bgDeep = c("#0B0712"), bgPanel = c("#140C20"), bgCard = c("#140C20"),
        bgElev = c("#1F1433"), bgKey = c("#190F2A"), border = c("#2E1F48"),
        txtPri = c("#ECE6FF"), txtSec = c("#A79AC8"), txtMuted = c("#6C5E8C"),
        bgStop = c("#36132A"), accent = c("#1FE3FF"), onAccent = c("#05141A"), oscuro = true)

    /**
     * EXCHANGE: terminal de trading. Carbón muy oscuro, texto claro frío y el
     * verde "vela" de los exchanges. Es el tema que va con el gráfico de velas.
     */
    private val EXCHANGE = Paleta(
        bgDeep = c("#0B0E11"), bgPanel = c("#161A1E"), bgCard = c("#161A1E"),
        bgElev = c("#1E2329"), bgKey = c("#1B2026"), border = c("#2B3139"),
        txtPri = c("#EAECEF"), txtSec = c("#9AA3AF"), txtMuted = c("#5E6673"),
        bgStop = c("#2A1518"), accent = c("#0ECB81"), onAccent = c("#06130D"), oscuro = true)

    @Volatile private var activa: Paleta = OSCURA

    /** ¿La paleta activa es oscura? Lo usan los diálogos XML y el teclado PIN. */
    val isDark get() = activa.oscuro

    /** Día = 07:00–19:59 locales. Simple y predecible. */
    private fun esDeDia(): Boolean =
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 7..19

    /** ¿El sistema está en modo oscuro? (Ajuste de tema del móvil.) */
    private fun sistemaOscuro(ctx: Context): Boolean {
        val f = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return f == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** La paleta de un modo CONCRETO (no AUTO). */
    private fun paletaFija(m: Modo): Paleta = when (m) {
        Modo.CLARO -> CLARA
        Modo.MEDIANOCHE -> MEDIANOCHE
        Modo.NEON -> NEON
        Modo.EXCHANGE -> EXCHANGE
        else -> OSCURA
    }

    /**
     * Resuelve el modo a una paleta. AUTO sigue al sistema Y además a la hora:
     * claro sólo si el sistema está en claro y es de día; oscuro si el sistema
     * ya está en oscuro, o si es de noche aunque el sistema esté en claro. Así
     * respeta el ajuste del móvil y encima baja la luz por la noche.
     */
    private fun resolver(ctx: Context, m: Modo): Paleta =
        if (m == Modo.AUTO) {
            if (sistemaOscuro(ctx) || !esDeDia()) OSCURA else CLARA
        } else paletaFija(m)

    private fun leerModo(p: android.content.SharedPreferences): Modo {
        p.getString("theme_mode", null)?.let { s ->
            try { return Modo.valueOf(s) } catch (e: Exception) {}
        }
        // Compatibilidad con el ajuste booleano antiguo (dark_mode).
        return if (p.getBoolean("dark_mode", true)) Modo.OSCURO else Modo.CLARO
    }

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        modo = leerModo(p)
        // AUTO se resuelve aquí: una vez por pantalla, igual que el tema XML de
        // los diálogos, así toda la pantalla usa la misma paleta.
        activa = resolver(ctx, modo)
    }

    /**
     * El tema de XML que toca: oscuro o claro. Casi toda la interfaz se colorea
     * en código con esta paleta, pero los diálogos los dibuja Android con el
     * tema de la actividad, que no puede leer [isDark]; por eso cada actividad
     * llama a esto con setTheme() ANTES de super.onCreate.
     */
    fun estilo(ctx: Context): Int {
        init(ctx)
        return if (isDark) R.style.AppThemeDark else R.style.AppThemeLight
    }

    /** Colores de muestra para la vista previa del selector de tema: el fondo,
     *  una tarjeta encima, el acento y el color del texto. AUTO no se pide aquí
     *  (no es una paleta): el selector dibuja su muestra con CLARO + OSCURO. */
    class Muestra(val bg: Int, val card: Int, val accent: Int, val texto: Int, val oscuro: Boolean)

    fun muestra(m: Modo): Muestra = paletaFija(m).let {
        Muestra(it.bgDeep, it.bgCard, ACCENT, it.txtPri, it.oscuro)
    }

    /** Guarda el tema elegido. Quien llama recrea la pantalla para repintarla
     *  (los colores se leen al construir cada vista). */
    fun ponerModo(ctx: Context, m: Modo) {
        modo = m
        activa = resolver(ctx, m)
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", m.name).apply()
    }

    /* ── Superficies ──────────────────────────────────────────────────────
       Sin bordes: la elevación la hace el tono, no una línea.

       Y para que el tono pueda hacerla tiene que haber tono. El tema oscuro
       empezaba en #0E0E0E y las tarjetas iban a #161616: ocho valores de
       diferencia sobre 255, que en un móvil a media luz no se ven. El efecto
       no era sólo "está muy oscuro", era que la pantalla se leía como una
       sola mancha negra con texto encima: sin separación entre el fondo y la
       tarjeta no hay manera de ver qué va con qué, y todo parece amontonado
       aunque el espaciado esté bien.

       Ahora cada nivel sube unos diez valores sobre el anterior — fondo,
       tarjeta, elemento elevado —, que es el escalón mínimo que se distingue
       sin mirar fijamente. El más oscuro sube de #0E a #19, porque el negro
       casi puro no es "elegante" en una pantalla OLED: es el sitio donde el
       resto de tonos no tiene contra qué destacar. */
    val BG_DEEP   get() = activa.bgDeep
    val BG_PANEL  get() = activa.bgPanel
    val BG_CARD   get() = activa.bgCard
    val BG_ELEV   get() = activa.bgElev
    /** Superficie de un control pulsable en reposo (teclas, botones secundarios). */
    val BG_KEY    get() = activa.bgKey
    val BORDER_C  get() = activa.border

    /* ── Texto ───────────────────────────────────────────────────────────
       TXT_SEC sube de #8A a #A4 y TXT_MUTED de #4A a #6C: al aclarar el
       fondo, los grises de antes perdían el contraste que tenían. #A4 sobre
       la tarjeta da 6,3:1 y #F2 da 16:1, los dos por encima del mínimo que
       se lee con el móvil al sol. */
    val TXT_PRI   get() = activa.txtPri
    val TXT_SEC   get() = activa.txtSec
    val TXT_MUTED get() = activa.txtMuted

    /* ── Semánticos: un color, un significado ─────────────────────────── */
    /** Acción / positivo / corriendo. Lo pone el tema (verde salvo en los
     *  futuristas), pero sigue siendo un único acento con un único significado. */
    val ACCENT get() = activa.accent

    /**
     * Lo que va ENCIMA del acento: texto de un botón principal, un icono
     * dentro de un chip verde.
     *
     * No es un color más de la paleta, es una corrección. Todo esto se pintaba
     * con [BG_DEEP], que en oscuro es casi negro y sobre el verde se lee
     * perfectamente. Con el tema claro BG_DEEP pasa a ser #F4F4F4: blanco roto
     * sobre verde medio, 2,3:1, ilegible. Diecisiete botones —incluido el de
     * arrancar el escaneo y el de revisar un envío— se quedaban sin texto
     * visible en cuanto se cambiaba de tema.
     *
     * Va con el acento del tema (cada paleta trae su pareja acento/sobre-acento),
     * así que un botón verde, uno cian o uno "exchange" siempre tienen el texto
     * legible encima.
     */
    val ON_ACCENT get() = activa.onAccent
    val RED    get() = Color.parseColor("#F04040")   // destructivo / error
    val WARN   get() = Color.parseColor("#FF6B35")   // aviso, no error
    val BLUE   get() = Color.parseColor("#6EA8FE")   // informativo
    /**
     * Fondo tenue del botón de parar.
     *
     * Sube con el resto: a #1E1414 le pasaba lo mismo que a las tarjetas, sólo
     * que al revés — quedaba por DEBAJO del panel nuevo, así que el botón de
     * parar se hundía en vez de destacar. Es el mismo nivel que [BG_ELEV] con
     * el rojo dentro. Y en claro es un rosa pálido, no el mismo granate: sobre
     * una tarjeta blanca, un fondo oscuro con el texto en rojo encima no se
     * lee ni pega con nada.
     */
    val BG_STOP get() = activa.bgStop

    /* Compatibilidad: AMBER y GREEN eran el mismo verde con dos nombres. */
    val AMBER  get() = ACCENT
    val GREEN  get() = ACCENT
    val CYAN   get() = BLUE
    val YELLOW get() = WARN
    val ORANGE get() = WARN

    /* ── Escala tipográfica ───────────────────────────────────────────────
       Una cifra domina por pantalla; lo demás baja a BODY o CAPTION. Antes
       cada pantalla inventaba su tamaño: 56, 44, 40, 28, 22, 20, 19, 18… */
    const val SP_HERO    = 58f   // la cifra del escáner, que domina su pantalla
    const val SP_DISPLAY = 44f   // la cifra protagonista
    const val SP_FIGURE  = 22f   // cifras de apoyo (tarjetas de estadística)
    /**
     * Título de página. Con la barra de pestañas abajo ya no hay cabecera
     * que diga dónde estás, así que lo dice la propia página, y a un tamaño
     * que se lee de un vistazo: el de sección (17) se confundía con los
     * rótulos de las tarjetas de debajo.
     */
    const val SP_PAGE    = 28f
    const val SP_TITLE   = 17f   // título de sección
    const val SP_BODY    = 14f   // texto normal, etiquetas de fila
    const val SP_CAPTION = 12f   // secundario, unidades, pies
    const val SP_MICRO   = 11f   // sólo para datos densos: direcciones, hex

    /* ── Radios y espaciado ──────────────────────────────────────────────
       Antes convivían 8, 10, 12, 14, 16 y 18 sin criterio.

       En dp y como Int: se pasan por dp(), que toma Int, y el .toFloat() va al
       final para cornerRadius. Declararlos Float obligaba a recordar el orden
       exacto de las conversiones en cada uso, y bastaba equivocarse una vez
       para no compilar. */
    const val R_CARD   = 14
    const val R_INNER  = 12
    const val R_CHIP   = 10
    const val R_KEY    = 16

    const val PAD_SIDE = 22   // margen lateral de pantalla
    const val PAD_CARD = 18   // interior de tarjeta
    const val GAP      = 12   // entre tarjetas hermanas

    /* ── Tipografías ──────────────────────────────────────────────────────
       Dos familias y nada más. La app usaba las del sistema:
       "sans-serif-black" para las cifras grandes, "sans-serif" para los
       títulos y "monospace" para todo lo demás, incluidas etiquetas que no
       son datos.

       Sora lleva numerales de ancho tabular, que es lo que impide que una
       columna de cantidades baile al cambiar de valor — con la fuente del
       sistema, "1.11" y "8.88" no ocupan lo mismo y la cifra tiembla mientras
       el escáner corre.

       Se cachean: getFont() lee y parsea el TTF, y esto se llama desde el
       bucle de construcción de cada pantalla. */
    @Volatile private var fDisplay: android.graphics.Typeface? = null
    @Volatile private var fTitle:   android.graphics.Typeface? = null
    @Volatile private var fBody:    android.graphics.Typeface? = null
    @Volatile private var fMedium:  android.graphics.Typeface? = null
    @Volatile private var fBold:    android.graphics.Typeface? = null
    @Volatile private var fMono:    android.graphics.Typeface? = null

    private fun load(ctx: Context, res: Int, fallback: String, weight: Int):
            android.graphics.Typeface =
        try {
            androidx.core.content.res.ResourcesCompat.getFont(ctx, res)
                ?: android.graphics.Typeface.create(fallback, weight)
        } catch (e: Exception) {
            // Un APK al que le falte el recurso no debe dejar la pantalla en
            // blanco: se cae a la del sistema.
            android.graphics.Typeface.create(fallback, weight)
        }

    /** Sora Bold — la cifra protagonista. */
    fun display(ctx: Context) = fDisplay
        ?: load(ctx, R.font.sora_bold, "sans-serif-black", android.graphics.Typeface.BOLD)
            .also { fDisplay = it }

    /** Sora SemiBold — títulos de pantalla y cifras de apoyo. */
    fun title(ctx: Context) = fTitle
        ?: load(ctx, R.font.sora_semibold, "sans-serif", android.graphics.Typeface.BOLD)
            .also { fTitle = it }

    /** Public Sans Regular — texto normal. */
    fun body(ctx: Context) = fBody
        ?: load(ctx, R.font.public_sans_regular, "sans-serif", android.graphics.Typeface.NORMAL)
            .also { fBody = it }

    /** Public Sans Medium — etiquetas y secundarios. */
    fun medium(ctx: Context) = fMedium
        ?: load(ctx, R.font.public_sans_medium, "sans-serif-medium", android.graphics.Typeface.NORMAL)
            .also { fMedium = it }

    /** Public Sans SemiBold — botones y valores destacados. */
    fun bold(ctx: Context) = fBold
        ?: load(ctx, R.font.public_sans_semibold, "sans-serif", android.graphics.Typeface.BOLD)
            .also { fBold = it }

    /**
     * JetBrains Mono — para datos de ancho fijo: claves, direcciones, hashes.
     *
     * Antes todo esto usaba [android.graphics.Typeface.MONOSPACE], la mono del
     * sistema, que cambia con cada fabricante (una Droid Sans Mono vieja en
     * unos, otra en otros) y no alinea con el resto de la tipografía. Con una
     * propia, un hex se ve idéntico en todos los móviles y las columnas de
     * cifras no bailan. Si faltara el recurso, cae a la mono del sistema.
     */
    fun mono(ctx: Context) = fMono
        ?: load(ctx, R.font.jetbrains_mono, "monospace", android.graphics.Typeface.NORMAL)
            .also { fMono = it }
}
