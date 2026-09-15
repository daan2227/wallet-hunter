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
    var isDark = true

    fun init(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        isDark = prefs.getBoolean("dark_mode", true)
    }

    fun toggle(ctx: Context) {
        isDark = !isDark
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("dark_mode", isDark).apply()
    }

    /* ── Superficies ──────────────────────────────────────────────────────
       Sin bordes: la elevación la hace el tono, no una línea. */
    val BG_DEEP   get() = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F4F4F4")
    val BG_PANEL  get() = if (isDark) Color.parseColor("#161616") else Color.parseColor("#FFFFFF")
    val BG_CARD   get() = if (isDark) Color.parseColor("#161616") else Color.parseColor("#FFFFFF")
    val BG_ELEV   get() = if (isDark) Color.parseColor("#1D1D1D") else Color.parseColor("#E8E8E8")
    /** Superficie de un control pulsable en reposo (teclas, botones secundarios). */
    val BG_KEY    get() = if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#EDEDED")
    val BORDER_C  get() = if (isDark) Color.parseColor("#222222") else Color.parseColor("#E0E0E0")

    /* ── Texto ─────────────────────────────────────────────────────────── */
    val TXT_PRI   get() = if (isDark) Color.parseColor("#F2F2F2") else Color.parseColor("#0A0A0A")
    val TXT_SEC   get() = if (isDark) Color.parseColor("#8A8A8A") else Color.parseColor("#444444")
    val TXT_MUTED get() = if (isDark) Color.parseColor("#4A4A4A") else Color.parseColor("#909090")

    /* ── Semánticos: un color, un significado ─────────────────────────── */
    val ACCENT get() = Color.parseColor("#00C896")   // acción / positivo / corriendo
    val RED    get() = Color.parseColor("#F04040")   // destructivo / error
    val WARN   get() = Color.parseColor("#FF6B35")   // aviso, no error
    val BLUE   get() = Color.parseColor("#6EA8FE")   // informativo
    /** Fondo tenue del botón de parar. */
    val BG_STOP get() = Color.parseColor("#1E1414")

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
    const val SP_TITLE   = 17f   // título de pantalla o de sección
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
}
