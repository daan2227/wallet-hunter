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
 * Cinco y no más: es lo que cabe en un móvil con el rótulo entero y un blanco
 * de toque decente. Lo que se usa menos —Recovery, History, Debug, el tema—
 * va dentro de More.
 *
 * La usan dos pantallas: MainActivity, que tiene Scanner, Puzzle, Wallet y More
 * como páginas, y NetworkActivity, que ES la pestaña Cluster. Por eso la barra
 * no sabe navegar: sólo dice qué se ha tocado, y cada pantalla decide.
 */
class BottomBar(private val ctx: Context, seleccion: Int, private val alTocar: (Int) -> Unit) {

    companion object {
        const val SCANNER = 0
        const val PUZZLE  = 1
        const val WALLET  = 2
        const val CLUSTER = 3
        const val MORE    = 4

        /** Qué pestaña pedir al volver a MainActivity desde otra pantalla. */
        const val EXTRA_TAB = "com.hunter.btc.TAB"
    }

    private data class Item(val icono: Int, val rotulo: String)

    private val items = listOf(
        Item(R.drawable.ic_scan,    "Scanner"),
        Item(R.drawable.ic_puzzle,  "Puzzle"),
        Item(R.drawable.ic_wallet,  "Wallet"),
        Item(R.drawable.ic_network, "Cluster"),
        Item(R.drawable.ic_more,    "More")
    )

    private val pildoras = mutableListOf<FrameLayout>()
    private val iconos   = mutableListOf<ImageView>()
    private val rotulos  = mutableListOf<TextView>()
    private val celdas   = mutableListOf<LinearLayout>()

    /** El acento con un 14 % de alpha: la píldora detrás del icono activo. */
    private val acentoSuave get() = (AppTheme.ACCENT and 0x00FFFFFF) or (0x24 shl 24)

    private fun dp(v: Int) = Ui.dp(ctx, v)

    /**
     * La barra, con su filo de arriba. Se añade al final de la pantalla con
     * MATCH_PARENT de ancho y WRAP_CONTENT de alto.
     */
    val vista: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(AppTheme.BG_PANEL)
    }

    init {
        // Un filo de 1px y no una sombra: la elevación en este tema la hace el
        // tono, y una sombra sobre fondo casi negro no se ve.
        vista.addView(View(ctx).apply {
            setBackgroundColor(AppTheme.BG_ELEV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        items.forEachIndexed { i, item -> fila.addView(celda(i, item)) }
        vista.addView(fila, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        seleccionar(seleccion)
    }

    private fun celda(i: Int, item: Item): LinearLayout {
        val pildora = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(30))
        }
        val icono = ImageView(ctx).apply {
            setImageResource(item.icono)
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        pildora.addView(icono)
        val rotulo = TextView(ctx).apply {
            text = item.rotulo
            textSize = AppTheme.SP_MICRO + 1f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 56dp de alto: por encima de los 48 de blanco de toque mínimo,
            // que con icono y rótulo apilados se queda justo.
            minimumHeight = dp(56)
            setPadding(0, dp(2), 0, 0)
            isClickable = true; isFocusable = true
            foreground = Ui.toque()
            contentDescription = item.rotulo
            setOnClickListener { alTocar(i) }
            addView(pildora); addView(rotulo)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pildoras += pildora; iconos += icono; rotulos += rotulo; celdas += c
        return c
    }

    /**
     * Marca una pestaña como la activa. No navega: eso lo hace quien tiene la
     * barra, que es quien sabe si la pestaña es una página o otra pantalla.
     */
    fun seleccionar(i: Int) {
        for (k in items.indices) {
            val activa = k == i
            pildoras[k].background = if (activa) GradientDrawable().apply {
                setColor(acentoSuave); cornerRadius = dp(15).toFloat()
            } else null
            iconos[k].setColorFilter(if (activa) AppTheme.ACCENT else AppTheme.TXT_SEC)
            rotulos[k].setTextColor(if (activa) AppTheme.ACCENT else AppTheme.TXT_SEC)
            rotulos[k].typeface = if (activa) AppTheme.bold(ctx) else AppTheme.medium(ctx)
            // Lo que TalkBack dice: "Puzzle, seleccionado". Sin esto el color
            // era la única pista de cuál está activa.
            celdas[k].isSelected = activa
        }
    }
}
