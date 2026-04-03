package com.hunter.btc

import android.content.Context
import android.graphics.Color

object AppTheme {
    var isDark = true

    fun init(ctx: Context) { isDark = true }

    /* ── New dashboard palette ── */
    val BG_DEEP   = Color.parseColor("#0B0E14")
    val BG_PANEL  = Color.parseColor("#111520")
    val BG_CARD   = Color.parseColor("#111520")
    val BG_ELEV   = Color.parseColor("#171C2C")
    val AMBER     = Color.parseColor("#00C896")   /* accent green */
    val GREEN     = Color.parseColor("#00C896")
    val RED       = Color.parseColor("#FF6B35")
    val CYAN      = Color.parseColor("#0087FF")
    val BLUE      = Color.parseColor("#0087FF")
    val TXT_PRI   = Color.parseColor("#E8EAF0")
    val TXT_SEC   = Color.parseColor("#A0A8C0")
    val TXT_MUTED = Color.parseColor("#5A607A")
    val BORDER_C  = Color.parseColor("#1E2540")

    /* aliases kept for compatibility */
    val YELLOW = AMBER
    val ORANGE = AMBER
}
