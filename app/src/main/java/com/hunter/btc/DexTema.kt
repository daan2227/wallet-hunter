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
 * Tokens del DEx. La apariencia (disposición, formas, tamaños) es siempre la de un
 * exchange, pero los colores salen del tema de la app (Vault Gold, claro o Spatial):
 * fondos, superficies, bordes, texto y acento son los del tema activo. Verde y rojo
 * de mercado: en los temas oscuros los de exchange (#2EBD85 / #F6465D, ≥ 5:1 sobre
 * el fondo); en el claro los semánticos del tema, oscurecidos para leerse sobre blanco.
 * Al cambiar de tema la app se recrea, así que las vistas toman los colores nuevos.
 */
object DexTema {
    val BG get() = AppTheme.BG_DEEP             // fondo de la pantalla
    val SURFACE get() = AppTheme.BG_CARD        // hojas, tarjetas
    val CHIP get() = AppTheme.BG_ELEV           // campos, botones, chips
    val STROKE get() = AppTheme.BORDER_C        // bordes y separadores
    val GRID get() = (AppTheme.BORDER_C and 0x00FFFFFF) or (0x66 shl 24)   // rejilla del gráfico
    val TXT get() = AppTheme.TXT_PRI
    val TXT2 get() = AppTheme.TXT_MUTED         // ≥ 4,5:1 en los tres temas
    val UP get() = if (AppTheme.isDark) 0xFF2EBD85.toInt() else AppTheme.OK
    val DOWN get() = if (AppTheme.isDark) 0xFFF6465D.toInt() else AppTheme.RED
    val UP_BG get() = UP                        // lado elegido de Comprar (relleno)
    val DOWN_BG get() = DOWN
    val ACCENT get() = AppTheme.ACCENT          // pestaña activa, órdenes, enlaces
    val LINK get() = AppTheme.ACCENT
    val WARN get() = AppTheme.ACCENT            // estrella de favorito
    val LIQ get() = AppTheme.WARN               // línea de liquidación (naranja del tema)
    /** Texto sobre el botón verde: oscuro en los temas oscuros (el blanco no llega a 3:1), blanco en el claro. */
    val ON_UP get() = if (AppTheme.isDark) 0xFF0B0E11.toInt() else 0xFFFFFFFF.toInt()
    /** Texto legible sobre un color de relleno cualquiera (etiquetas de líneas del gráfico). */
    fun sobre(c: Int): Int {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        return if (l > 0.35) 0xFF0B0E11.toInt() else 0xFFFFFFFF.toInt()
    }

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
                minHeight = dp(ctx, 48f).toInt(); setPadding(dp(ctx, 14f).toInt(), 0, dp(ctx, 14f).toInt(), 0)
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
     * Diálogo del DEx: oscuro o claro según el tema, sobre
     * #121212 con esquinas redondeadas y botones blanco / gris, para que el texto
     * en tokens del DEx se lea igual que en la pantalla.
     */
    fun dialogo(ctx: Context): androidx.appcompat.app.AlertDialog.Builder =
        object : androidx.appcompat.app.AlertDialog.Builder(ctx, if (AppTheme.isDark) androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
            else androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert) {
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

    /**
     * Campo con etiqueta dentro (como "Price (USDT)" sobre el valor), en caja #2B3139.
     * Devuelve la caja y el EditText; [derecha] es una vista opcional al lado (p. ej. la unidad).
     */
    fun campoEtiquetado(ctx: Context, etiqueta: String, derecha: View? = null): Pair<LinearLayout, android.widget.EditText> {
        val et = android.widget.EditText(ctx).apply {
            textSize = 16f; setTextColor(TXT); setHintTextColor(TXT2); background = null; setPadding(0, 0, 0, 0); setSingleLine()
            typeface = AppTheme.medium(ctx); hint = etiqueta
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val tvEt = TextView(ctx).apply { text = etiqueta; textSize = 12f; setTextColor(TXT2); visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { tvEt.visibility = if (e.isNullOrEmpty()) View.GONE else View.VISIBLE }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val caja = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = fondo(ctx, CHIP, 8f)
            minimumHeight = dp(ctx, 52f).toInt(); setPadding(dp(ctx, 12f).toInt(), dp(ctx, 6f).toInt(), dp(ctx, 12f).toInt(), dp(ctx, 6f).toInt())
            addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(tvEt); addView(et) })
            if (derecha != null) addView(derecha)
            setOnClickListener { et.requestFocus() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8f).toInt() }
        }
        return caja to et
    }

    /**
     * Pestañas con subrayado amarillo (Positions / Open orders / …). [alElegir] recibe
     * el índice; devuelve la fila y una función para cambiar los rótulos (contadores).
     */
    fun pestanasSubrayado(ctx: Context, rotulos: List<String>, inicial: Int, alElegir: (Int) -> Unit): Pair<LinearLayout, (List<String>) -> Unit> {
        var sel = inicial
        val fila = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tvs = ArrayList<TextView>()
        fun pintar() = tvs.forEachIndexed { i, t ->
            val on = i == sel
            t.setTextColor(if (on) TXT else TXT2); t.typeface = if (on) AppTheme.bold(ctx) else AppTheme.medium(ctx); t.isSelected = on
            t.background = if (on) android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(ctx, 2f) })).apply {
                setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(ctx, 3f).toInt()) } else null
        }
        rotulos.forEachIndexed { i, r ->
            val t = TextView(ctx).apply {
                text = r; textSize = 16f; gravity = Gravity.CENTER; minHeight = dp(ctx, 48f).toInt()
                isClickable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 22f).toInt() }
                setOnClickListener { sel = i; pintar(); alElegir(i) }
            }
            tvs.add(t); fila.addView(t)
        }
        pintar()
        return fila to { nuevos: List<String> -> nuevos.forEachIndexed { i, r -> if (i < tvs.size && tvs[i].text != r) tvs[i].text = r } }
    }

    /**
     * Fondo de control compacto con zona táctil de 48 dp: se dibuja con [altoVisualDp]
     * de alto centrado y el resto queda como margen tocable (regla de 48 dp de Android).
     */
    fun fondoTactil(ctx: Context, color: Int, radioDp: Float, altoVisualDp: Float, borde: Int = 0) =
        android.graphics.drawable.InsetDrawable(fondo(ctx, color, radioDp, borde), 0, dp(ctx, (48f - altoVisualDp) / 2).toInt(), 0, dp(ctx, (48f - altoVisualDp) / 2).toInt())

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
    /** false = dos mitades rectas: la elegida rellena (verde/rojo), la otra con contorno. */
    var diagonal = false
    var textos = "Long" to "Short"
        set(v) { field = v; contentDescription = "${v.first} / ${v.second}"; invalidate() }
    var alCambiar: ((Boolean) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; pathEffect = CornerPathEffect(8f * d) }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = DexTema.STROKE; pathEffect = CornerPathEffect(8f * d) }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * d; textAlign = Paint.Align.CENTER; typeface = AppTheme.bold(ctx) }

    init { isClickable = true; contentDescription = "Long / Short"; minimumHeight = (48 * d).toInt() }

    fun poner(l: Boolean) { if (l != largo) { largo = l; invalidate() } }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (44 * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = if (diagonal) h * 0.32f else 0f; val gap = if (diagonal) 4f * d else 0f; val m = 1f * d
        val izq = Path().apply { moveTo(m, m); lineTo(w / 2 + s / 2 - gap / 2, m); lineTo(w / 2 - s / 2 - gap / 2, h - m); lineTo(m, h - m); close() }
        val der = Path().apply { moveTo(w / 2 + s / 2 + gap / 2, m); lineTo(w - m, m); lineTo(w - m, h - m); lineTo(w / 2 - s / 2 + gap / 2, h - m); close() }
        if (!diagonal) { pBorde.color = DexTema.STROKE; c.drawRoundRect(m, m, w - m, h - m, 6f * d, 6f * d, pBorde) }
        if (largo) { pRelleno.color = DexTema.UP_BG; c.drawPath(izq, pRelleno); if (diagonal) c.drawPath(der, pBorde) }
        else { pRelleno.color = DexTema.DOWN_BG; c.drawPath(der, pRelleno); if (diagonal) c.drawPath(izq, pBorde) }
        val yt = h / 2 - (pTxt.descent() + pTxt.ascent()) / 2
        pTxt.color = if (largo) DexTema.ON_UP else DexTema.TXT2; c.drawText(textos.first, w / 4, yt, pTxt)
        pTxt.color = if (!largo) 0xFFFFFFFF.toInt() else DexTema.TXT2; c.drawText(textos.second, w * 3 / 4, yt, pTxt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) { val l = e.x < width / 2f; if (l != largo) { largo = l; invalidate(); alCambiar?.invoke(l) }; performClick() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/**
 * Deslizador de porcentaje con cinco rombos (0, 25, 50, 75, 100 %), como en los
 * exchanges. Arrastrar o tocar lo mueve (se ajusta al rombo cercano si está a < 4 %).
 * También se maneja con el lector de pantalla (acciones de subir y bajar).
 */
class SliderPorcentaje(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var valor = 0f; private set
    var alCambiar: ((Int) -> Unit)? = null
    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; color = DexTema.CHIP }
    private val pActiva = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; color = DexTema.TXT }
    private val pRombo = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.6f * d; style = Paint.Style.STROKE }
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    init { isClickable = true; isFocusable = true; contentDescription = "Amount percentage" }

    fun poner(v: Float) { valor = v.coerceIn(0f, 100f)
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = "${valor.toInt()} percent"; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (44 * d).toInt())

    private fun rombo(c: Canvas, x: Float, y: Float, r: Float, lleno: Boolean, grande: Boolean) {
        val p = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
        pRelleno.color = if (grande) DexTema.BG else if (lleno) DexTema.TXT else DexTema.BG; c.drawPath(p, pRelleno)
        pRombo.color = if (lleno || grande) DexTema.TXT else DexTema.TXT2; c.drawPath(p, pRombo)
    }

    override fun onDraw(c: Canvas) {
        val m = 12f * d; val w = width - 2 * m; val y = height / 2f
        val xv = m + w * valor / 100f
        c.drawLine(m, y, m + w, y, pLinea); c.drawLine(m, y, xv, y, pActiva)
        for (k in 0..4) { val x = m + w * k / 4f; rombo(c, x, y, 5f * d, k * 25f <= valor, false) }
        rombo(c, xv, y, 9f * d, true, true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = 12f * d; val w = width - 2 * m
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_UP) {
            var v = ((e.x - m) / w * 100f).coerceIn(0f, 100f)
            val cerca = (0..4).map { it * 25f }.minByOrNull { Math.abs(it - v) }!!
            if (Math.abs(cerca - v) < 4f) v = cerca
            if (v.toInt() != valor.toInt()) { poner(v); alCambiar?.invoke(v.toInt()) }
            if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        val paso = when (action) { android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 25f
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -25f; else -> return super.performAccessibilityAction(action, args) }
        poner(valor + paso); alCambiar?.invoke(valor.toInt()); return true
    }
}
