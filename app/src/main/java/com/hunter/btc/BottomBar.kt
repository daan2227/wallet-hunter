package com.hunter.btc

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * La barra de pestañas de abajo.
 *
 * Sustituye al menú lateral. Con el menú, las siete secciones vivían detrás de
 * un icono de hamburguesa: no se veía dónde estabas hasta abrirlo, y cambiar
 * de sección costaba dos toques y una animación. Aquí las cinco que se usan
 * están siempre a la vista y a un toque, y la activa se ve sin buscarla.
 *
 * Lo que se usa menos —Recovery, Debug, el tema— va dentro de More. La barra
 * no sabe navegar: sólo dice qué se ha tocado, y la pantalla decide.
 */
class BottomBar(private val ctx: Context, seleccion: Int, private val alTocar: (Int) -> Unit) {

    companion object {
        // Destinos lógicos: los que piden las pantallas (EXTRA_TAB) y los que recibe alTocar.
        // Scanner, Puzzle, Recovery y Weak ya no tienen hueco propio en la barra: viven dentro de
        // Hunt, con sus pestañas arriba. Los valores no cambian para no romper EXTRA_TAB.
        const val SCANNER  = 0
        const val PUZZLE   = 1
        const val WALLET   = 2
        const val RECOVERY = 3
        const val WEAK     = 4
        const val CORE     = 5
        const val MORE     = 6
        const val MINER    = 7
        /** La pestaña Hunt de la barra: quien la recibe abre la última de sus cuatro herramientas. */
        const val HUNT     = 8

        /** Qué pestaña pedir al volver a MainActivity desde otra pantalla. */
        const val EXTRA_TAB = "com.hunter.btc.TAB"

        fun esHunt(tab: Int) = tab == SCANNER || tab == PUZZLE || tab == RECOVERY || tab == WEAK || tab == HUNT
    }

    private data class Item(val icono: Int, val rotulo: String, val destino: Int)

    // Cinco, no siete (rediseño "Security print"): Material pide 3–5 en la barra, y con siete los
    // rótulos se quedaban en 12 sp apretados y las píldoras a 40 dp.
    private val items = listOf(
        Item(R.drawable.ic_search,   "Hunt",   HUNT),
        Item(R.drawable.ic_wallet,   "Wallet", WALLET),
        Item(R.drawable.ic_chip,     "Miner",  MINER),
        Item(R.drawable.ic_core,     "Core",   CORE),
        Item(R.drawable.ic_more,     "More",   MORE)
    )

    private val pildoras = mutableListOf<FrameLayout>()
    private val iconos   = mutableListOf<ImageView>()
    private val rotulos  = mutableListOf<TextView>()
    private val celdas   = mutableListOf<LinearLayout>()

    /** El acento al 18 %: la píldora detrás del icono activo. */
    private val acentoSuave get() = (AppTheme.ACCENT and 0x00FFFFFF) or (0x2E shl 24)

    private fun dp(v: Int) = Ui.dp(ctx, v)

    /**
     * La barra, de borde a borde, con un filete arriba (Material 3: barra de navegación, no una
     * píldora flotante). Se añade al final de la pantalla con MATCH_PARENT × WRAP_CONTENT.
     */
    val vista: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    init {
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(12))
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(Ui.filete(oro = false)),
                android.graphics.drawable.ColorDrawable(AppTheme.BG_PANEL)
            )).apply { setLayerInset(1, 0, 1, 0, 0) }
        }
        items.forEachIndexed { i, item -> fila.addView(celda(i, item)) }
        vista.addView(fila, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        seleccionar(seleccion)
    }

    private fun celda(i: Int, item: Item): LinearLayout {
        val pildora = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(32))
        }
        val icono = ImageView(ctx).apply {
            setImageResource(item.icono)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        pildora.addView(icono)
        val rotulo = TextView(ctx).apply {
            text = item.rotulo
            textSize = AppTheme.SP_MICRO
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(56)
            isClickable = true; isFocusable = true
            foreground = Ui.toque()
            contentDescription = item.rotulo
            setOnClickListener { alTocar(item.destino) }
            addView(pildora); addView(rotulo)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pildoras += pildora; iconos += icono; rotulos += rotulo; celdas += c
        return c
    }

    /**
     * Marca como activa la pestaña que contiene el destino [tab] (Scanner, Puzzle, Recovery y Weak
     * marcan Hunt). No navega: eso lo hace quien tiene la barra.
     */
    fun seleccionar(tab: Int) {
        val slot = items.indexOfFirst { it.destino == tab || (it.destino == HUNT && esHunt(tab)) }
        for (k in items.indices) {
            val activa = k == slot
            pildoras[k].background = if (activa) GradientDrawable().apply {
                setColor(acentoSuave); cornerRadius = dp(16).toFloat()
            } else null
            iconos[k].setColorFilter(if (activa) AppTheme.ACCENT else AppTheme.TXT_SEC)
            rotulos[k].setTextColor(if (activa) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
            rotulos[k].typeface = if (activa) AppTheme.bold(ctx) else AppTheme.medium(ctx)
            // Lo que TalkBack dice: "Hunt, seleccionado".
            celdas[k].isSelected = activa
        }
    }
}
