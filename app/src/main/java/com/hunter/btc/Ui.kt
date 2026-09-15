package com.hunter.btc

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Los componentes de "Bóveda", en un sitio.
 *
 * El rediseño anterior cambió la paleta y las tipografías, y reescribió las
 * cabeceras de tres pestañas. No se notó, y con razón: la paleta vieja ya era
 * casi ésta (el acento era el mismo #00C896 y los grises se movieron 2 o 3
 * unidades, que nadie ve), y debajo de esas cabeceras seguía TODO lo demás con
 * su forma original — rótulos de 9sp en monoespaciada mayúscula, un borde de
 * 1px alrededor de cada tarjeta, emojis por iconos y seis tamaños de letra
 * distintos por pantalla.
 *
 * El problema de fondo era que cada bloque se construía a mano en el sitio
 * donde se usaba, así que "aplicar el rediseño" significaba reescribir cada
 * uno por separado y el trabajo se quedaba a medias por cansancio. Aquí están
 * definidos una vez:
 *
 *  - [card]        superficie de tarjeta, sin borde
 *  - [sectionLabel] rótulo de grupo
 *  - [section]     sección plegable con icono vectorial
 *  - [primary]     acción principal, sólida
 *  - [ghost]       acción secundaria
 *  - [segmented]   selector de 2..n opciones
 *  - [input]       campo de texto
 *  - [row]         fila de ajuste: etiqueta a la izquierda, valor a la derecha
 *  - [divider]     separador de 1px dentro de una tarjeta
 *
 * SIN BORDES. Sobre #0E0E0E una tarjeta de #161616 ya se distingue; añadirle
 * además una línea de #222222 es decir dos veces lo mismo, y es lo que le daba
 * a la app ese aire de formulario.
 */
object Ui {

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Fondo de tarjeta. Sin `setStroke`: el tono ya separa. */
    fun cardBg(radius: Int = AppTheme.R_CARD, color: Int = AppTheme.BG_CARD, ctx: Context) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radius).toFloat()
        }

    /** Tarjeta contenedora con el margen lateral de pantalla. */
    fun card(ctx: Context, topGap: Int = AppTheme.GAP, pad: Int = AppTheme.PAD_CARD):
            LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg(ctx = ctx)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(ctx, AppTheme.PAD_SIDE), dp(ctx, topGap),
                       dp(ctx, AppTheme.PAD_SIDE), 0)
        }
        setPadding(dp(ctx, pad), dp(ctx, pad), dp(ctx, pad), dp(ctx, pad))
    }

    /**
     * Rótulo de un grupo de ajustes.
     *
     * Antes: `textSize = 9f`, monoespaciada en negrita, MAYÚSCULAS y
     * `letterSpacing = 0.1f`. Eso es la serigrafía de un instrumento de
     * laboratorio, no una etiqueta que alguien vaya a leer en un móvil. 9sp
     * está por debajo del mínimo legible de Android (12sp) y el interletrado
     * amplio empeora todavía más las mayúsculas.
     */
    fun sectionLabel(ctx: Context, text: String, topGap: Int = 0) = TextView(ctx).apply {
        this.text = text
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(ctx)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, topGap); bottomMargin = dp(ctx, 10) }
    }

    /** Un icono del juego de trazo, teñido. */
    fun icon(ctx: Context, res: Int, size: Int = 20, tint: Int = AppTheme.TXT_SEC) =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

    /**
     * Sección plegable.
     *
     * Cambia respecto a la anterior: el icono es un vector del juego en vez de
     * un emoji (el emoji lo pinta la fuente del sistema, así que cada móvil
     * enseñaba un dibujo distinto y a todo color sobre una interfaz gris), el
     * título sube de 13sp a la escala real, la flecha "›"/"∨" — dos glifos que
     * ni siquiera tienen el mismo peso óptico — pasa a ser un chevron que gira,
     * y desaparece el borde.
     */
    fun section(ctx: Context, iconRes: Int, title: String,
                build: LinearLayout.() -> Unit): LinearLayout {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(ctx = ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(ctx, AppTheme.PAD_SIDE), dp(ctx, AppTheme.GAP),
                           dp(ctx, AppTheme.PAD_SIDE), 0)
            }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, AppTheme.PAD_CARD), dp(ctx, 16),
                       dp(ctx, AppTheme.PAD_CARD), dp(ctx, 16))
            isClickable = true; isFocusable = true
        }
        header.addView(icon(ctx, iconRes).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(ctx, 14)
        })
        header.addView(TextView(ctx).apply {
            text = title
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(ctx)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val chevron = icon(ctx, R.drawable.ic_chevron, 18, AppTheme.TXT_MUTED)
        header.addView(chevron)
        container.addView(header)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(ctx, AppTheme.PAD_CARD), 0,
                       dp(ctx, AppTheme.PAD_CARD), dp(ctx, AppTheme.PAD_CARD))
        }
        body.build()
        container.addView(body)

        header.setOnClickListener {
            val opening = body.visibility == View.GONE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (opening) 90f else 0f).setDuration(140).start()
        }
        return container
    }

    /**
     * Acción principal: sólida, con el texto en negro sobre el acento.
     *
     * Era un rectángulo de #1A1A1A con un filo blanco de 1px y el texto en
     * MAYÚSCULAS con interletrado — indistinguible de una tarjeta cualquiera,
     * que es justo lo contrario de lo que tiene que parecer el único botón que
     * pone la app en marcha.
     */
    fun primary(ctx: Context, label: String, height: Int = 56) = TextView(ctx).apply {
        text = label
        textSize = AppTheme.SP_TITLE
        setTextColor(AppTheme.BG_DEEP)
        typeface = AppTheme.bold(ctx)
        gravity = Gravity.CENTER
        background = cardBg(AppTheme.R_KEY, AppTheme.ACCENT, ctx)
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, height)
        ).apply {
            setMargins(dp(ctx, AppTheme.PAD_SIDE), dp(ctx, 20),
                       dp(ctx, AppTheme.PAD_SIDE), dp(ctx, 8))
        }
    }

    /** Acción secundaria: misma forma, sin peso de color. */
    fun ghost(ctx: Context, label: String, color: Int = AppTheme.TXT_PRI, height: Int = 48) =
        TextView(ctx).apply {
            text = label
            textSize = AppTheme.SP_BODY
            setTextColor(color)
            typeface = AppTheme.medium(ctx)
            gravity = Gravity.CENTER
            background = cardBg(AppTheme.R_INNER, AppTheme.BG_KEY, ctx)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, height))
        }

    /**
     * Selector de opciones excluyentes.
     *
     * Antes cada uno se montaba a mano con dos `forEachIndexed` que repintaban
     * fondo, borde y color de texto — y en el selector de modo estaban además
     * DUPLICADOS: el `setOnClickListener` se asignaba dentro del bucle de
     * construcción y otra vez después, así que el primero no llegaba a
     * ejecutarse nunca y el estado inicial no coincidía con lo que pintaba.
     *
     * @param onPick recibe el índice elegido
     * @return la fila, ya construida y con la opción [initial] marcada
     */
    fun segmented(ctx: Context, options: List<Pair<String, String?>>, initial: Int = 0,
                  onPick: (Int) -> Unit): LinearLayout {
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, ctx)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val pills = mutableListOf<LinearLayout>()

        fun paint(active: Int) {
            pills.forEachIndexed { i, p ->
                val on = i == active
                p.background = if (on) cardBg(AppTheme.R_CHIP, AppTheme.BG_KEY, ctx) else null
                (p.getChildAt(0) as TextView).apply {
                    setTextColor(if (on) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
                    typeface = if (on) AppTheme.bold(ctx) else AppTheme.medium(ctx)
                }
                (p.getChildAt(1) as? TextView)?.setTextColor(
                    if (on) AppTheme.TXT_SEC else AppTheme.TXT_MUTED)
            }
        }

        options.forEachIndexed { idx, (label, sub) ->
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true; isFocusable = true
            }
            pill.addView(TextView(ctx).apply {
                text = label
                textSize = AppTheme.SP_BODY
                gravity = Gravity.CENTER
            })
            if (sub != null) pill.addView(TextView(ctx).apply {
                text = sub
                textSize = AppTheme.SP_MICRO
                typeface = AppTheme.body(ctx)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 2) }
            })
            pill.setOnClickListener { paint(idx); onPick(idx) }
            pills.add(pill)
            track.addView(pill)
        }
        paint(initial)
        return track
    }

    /** Campo de texto sobre la superficie elevada, sin filo. */
    fun input(ctx: Context, hint: String, mono: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        textSize = if (mono) AppTheme.SP_CAPTION else AppTheme.SP_BODY
        setTextColor(AppTheme.TXT_PRI)
        setHintTextColor(AppTheme.TXT_MUTED)
        typeface = if (mono) android.graphics.Typeface.MONOSPACE else AppTheme.body(ctx)
        background = cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, ctx)
        setPadding(dp(ctx, 14), dp(ctx, 13), dp(ctx, 14), dp(ctx, 13))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Fila de ajuste: etiqueta a la izquierda, valor a la derecha.
     *
     * @return el TextView del valor, para poder actualizarlo después
     */
    fun row(ctx: Context, parent: LinearLayout, label: String, value: String): TextView {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 6) }
        }
        r.addView(TextView(ctx).apply {
            text = label
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(ctx)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val v = TextView(ctx).apply {
            text = value
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.medium(ctx)
        }
        r.addView(v)
        parent.addView(r)
        return v
    }

    /** Separador dentro de una tarjeta. 1px real, no 1dp. */
    fun divider(ctx: Context, gap: Int = 12) = View(ctx).apply {
        setBackgroundColor(AppTheme.BORDER_C)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = dp(ctx, gap); bottomMargin = dp(ctx, gap) }
    }
}
