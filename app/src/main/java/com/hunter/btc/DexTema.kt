package com.hunter.btc

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tokens del DEx: estética de exchange (negro OLED, superficies #121212, verde y
 * rojo de mercado). El DEx es siempre oscuro, como las apps de trading, para que
 * velas y libro se lean igual de día que de noche. Contraste sobre negro:
 * TXT 21:1, TXT2 7,4:1, UP 7,6:1, DOWN 5,8:1, LINK 7,2:1 (texto normal ≥ 4,5:1).
 */
object DexTema {
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF121212.toInt()      // hojas, campos
    const val CHIP = 0xFF1E1E20.toInt()         // chips, píldoras rellenas
    const val STROKE = 0xFF2E2E31.toInt()       // bordes y separadores
    const val GRID = 0xFF17171A.toInt()         // rejilla del gráfico
    const val TXT = 0xFFFFFFFF.toInt()
    const val TXT2 = 0xFF9A9AA0.toInt()
    const val UP = 0xFF20B26C.toInt()
    const val DOWN = 0xFFF0454A.toInt()
    const val UP_BG = 0xFF0E2A1D.toInt()
    const val DOWN_BG = 0xFF33151A.toInt()
    const val LINK = 0xFF3D9BF5.toInt()
    const val WARN = 0xFFF0B90B.toInt()
    const val ON_UP = 0xFF06140D.toInt()        // texto sobre botón verde

    fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    /** Fondo redondeado; [radioDp] ≥ 100 = píldora. Con [borde] dibuja solo el contorno si [color] es 0. */
    fun fondo(ctx: Context, color: Int, radioDp: Float, borde: Int = 0, anchoBordeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(ctx, radioDp)
        if (borde != 0) setStroke(dp(ctx, anchoBordeDp).toInt().coerceAtLeast(1), borde)
    }

    /**
     * Pestañas de píldora del DEx (sustituyen al segmented azulado de la app): la
     * elegida en píldora #1E1E20 y blanco; las demás en gris sin fondo. Misma firma
     * que Ui.segmented; cada hijo es pulsable (performClick selecciona).
     */
    fun pestanas(ctx: Context, opciones: List<Pair<String, String?>>, inicial: Int = 0, alElegir: (Int) -> Unit): LinearLayout {
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val tvs = ArrayList<TextView>()
        fun pintar(k: Int) = tvs.forEachIndexed { i, t ->
            val on = i == k
            t.background = if (on) fondo(ctx, CHIP, 100f) else fondo(ctx, 0, 100f, STROKE)
            t.setTextColor(if (on) TXT else TXT2); t.typeface = if (on) AppTheme.bold(ctx) else AppTheme.medium(ctx)
            t.isSelected = on
        }
        opciones.forEachIndexed { i, (txt, _) ->
            val t = TextView(ctx).apply {
                text = txt; textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
                minHeight = dp(ctx, 40f).toInt(); setPadding(dp(ctx, 14f).toInt(), 0, dp(ctx, 14f).toInt(), 0)
                isClickable = true; isFocusable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(ctx, 8f).toInt() }
                setOnClickListener { pintar(i); alElegir(i) }
            }
            tvs.add(t); fila.addView(t)
        }
        pintar(inicial)
        return fila
    }

    /** Casilla de verificación blanca/gris como en los ajustes de gráfico de un exchange. */
    fun casilla(ctx: Context, texto: String, marcada: Boolean, alCambiar: (Boolean) -> Unit) = android.widget.CheckBox(ctx).apply {
        text = texto; isChecked = marcada; textSize = 15f; setTextColor(TXT); minHeight = dp(ctx, 48f).toInt()
        buttonTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(TXT, TXT2))
        setOnCheckedChangeListener { _, b -> alCambiar(b) }
    }

    /**
     * Diálogo del DEx: siempre oscuro (aunque la app esté en tema claro), sobre
     * #121212 con esquinas redondeadas y botones blanco / gris, para que el texto
     * en tokens del DEx se lea igual que en la pantalla.
     */
    fun dialogo(ctx: Context): androidx.appcompat.app.AlertDialog.Builder =
        object : androidx.appcompat.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert) {
            override fun show(): androidx.appcompat.app.AlertDialog {
                val d = super.show()
                d.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(fondo(ctx, SURFACE, 20f), dp(ctx, 16f).toInt()))
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(TXT)
                d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(TXT2)
                d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setTextColor(TXT2)
                return d
            }
        }

    /** Campo de texto del DEx: relleno #1E1E20 redondeado, texto blanco, pista gris, 48 dp. */
    fun campo(ctx: Context, hint: String, mono: Boolean = false) = android.widget.EditText(ctx).apply {
        this.hint = hint; textSize = 15f; setTextColor(TXT); setHintTextColor(TXT2)
        typeface = if (mono) AppTheme.mono(ctx) else AppTheme.body(ctx)
        background = fondo(ctx, CHIP, 8f)
        val h = dp(ctx, 14f).toInt(); setPadding(h, dp(ctx, 12f).toInt(), h, dp(ctx, 12f).toInt())
        minimumHeight = dp(ctx, 48f).toInt()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Texto de sección, gris y pequeño. */
    fun seccion(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 14f; setTextColor(TXT2); typeface = AppTheme.bold(ctx)
        setPadding(0, dp(ctx, 18f).toInt(), 0, dp(ctx, 10f).toInt())
    }

    /**
     * Hoja inferior (sin Material): diálogo pegado abajo, esquinas superiores
     * redondeadas, asa y título. Devuelve el diálogo; [contenido] va con scroll.
     */
    fun hoja(ctx: Context, titulo: String, contenido: View): Dialog {
        val d = Dialog(ctx)
        val caja = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(SURFACE)
                val r = dp(ctx, 24f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(dp(ctx, 20f).toInt(), dp(ctx, 10f).toInt(), dp(ctx, 20f).toInt(), dp(ctx, 24f).toInt())
            addView(View(ctx).apply {
                background = fondo(ctx, STROKE, 100f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40f).toInt(), dp(ctx, 4f).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(ctx, 16f).toInt() }
            })
            addView(TextView(ctx).apply { text = titulo; textSize = 22f; setTextColor(TXT); typeface = AppTheme.bold(ctx)
                setPadding(0, 0, 0, dp(ctx, 8f).toInt()) })
            addView(ScrollView(ctx).apply { addView(contenido); isVerticalScrollBarEnabled = false })
        }
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(caja)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.6f)
            attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
        }
        return d
    }
}

/**
 * Selector Long/Short con el corte en diagonal de los exchanges. El lado elegido
 * va relleno con su color tenue y el texto en su color; el otro, solo contorno.
 */
class SegmentoLargoCorto(ctx: Context) : View(ctx) {
    var largo = true; private set
    var textos = "Long" to "Short"
        set(v) { field = v; contentDescription = "${v.first} / ${v.second}"; invalidate() }
    var alCambiar: ((Boolean) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; pathEffect = CornerPathEffect(8f * d) }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = DexTema.STROKE; pathEffect = CornerPathEffect(8f * d) }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * d; textAlign = Paint.Align.CENTER; typeface = AppTheme.medium(ctx) }

    init { isClickable = true; contentDescription = "Long / Short"; minimumHeight = (48 * d).toInt() }

    fun poner(l: Boolean) { if (l != largo) { largo = l; invalidate() } }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (48 * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = h * 0.32f; val gap = 4f * d; val m = 1f * d
        val izq = Path().apply { moveTo(m, m); lineTo(w / 2 + s / 2 - gap / 2, m); lineTo(w / 2 - s / 2 - gap / 2, h - m); lineTo(m, h - m); close() }
        val der = Path().apply { moveTo(w / 2 + s / 2 + gap / 2, m); lineTo(w - m, m); lineTo(w - m, h - m); lineTo(w / 2 - s / 2 + gap / 2, h - m); close() }
        if (largo) { pRelleno.color = DexTema.UP_BG; c.drawPath(izq, pRelleno); c.drawPath(der, pBorde) }
        else { pRelleno.color = DexTema.DOWN_BG; c.drawPath(der, pRelleno); c.drawPath(izq, pBorde) }
        val yt = h / 2 - (pTxt.descent() + pTxt.ascent()) / 2
        pTxt.color = if (largo) DexTema.UP else DexTema.TXT2; c.drawText(textos.first, w / 4, yt, pTxt)
        pTxt.color = if (!largo) DexTema.DOWN else DexTema.TXT2; c.drawText(textos.second, w * 3 / 4, yt, pTxt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) { val l = e.x < width / 2f; if (l != largo) { largo = l; invalidate(); alCambiar?.invoke(l) }; performClick() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
