package com.hunter.btc

import android.content.Context
import android.graphics.Color

object AppTheme {
    var isDark = true

    fun init(ctx: Context) { isDark = true }

    /* ── Palette from HTML ── */
    val BG_DEEP   = Color.parseColor("#0e0f0e")
    val BG_PANEL  = Color.parseColor("#161815")
    val BG_CARD   = Color.parseColor("#1a1c19")
    val BG_ELEV   = Color.parseColor("#222521")
    val AMBER     = Color.parseColor("#a8ff00")   /* lime accent */
    val GREEN     = Color.parseColor("#a8ff00")   /* same lime */
    val RED       = Color.parseColor("#ff4d4d")
    val CYAN      = Color.parseColor("#00c8d4")
    val BLUE      = Color.parseColor("#60a5fa")
    val TXT_PRI   = Color.parseColor("#e6ead8")
    val TXT_SEC   = Color.parseColor("#7a8a70")
    val TXT_MUTED = Color.parseColor("#556050")
    val BORDER_C  = Color.parseColor("#1e2420")

    /* aliases kept for compatibility */
    val YELLOW = AMBER
    val ORANGE = AMBER
}
