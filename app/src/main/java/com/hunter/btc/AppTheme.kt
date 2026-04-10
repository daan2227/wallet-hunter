package com.hunter.btc

import android.content.Context
import android.graphics.Color

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

    /* ── Dynamic palette ── */
    val BG_DEEP   get() = if (isDark) Color.parseColor("#0B0E14") else Color.parseColor("#F0F2F5")
    val BG_PANEL  get() = if (isDark) Color.parseColor("#111520") else Color.parseColor("#FFFFFF")
    val BG_CARD   get() = if (isDark) Color.parseColor("#111520") else Color.parseColor("#FFFFFF")
    val BG_ELEV   get() = if (isDark) Color.parseColor("#171C2C") else Color.parseColor("#E8EDF5")
    val AMBER     get() = Color.parseColor("#00C896")   /* accent green - same both themes */
    val GREEN     get() = Color.parseColor("#00C896")
    val RED       get() = Color.parseColor("#FF6B35")
    val CYAN      get() = Color.parseColor("#0087FF")
    val BLUE      get() = Color.parseColor("#0087FF")
    val TXT_PRI   get() = if (isDark) Color.parseColor("#E8EAF0") else Color.parseColor("#0B0E14")
    val TXT_SEC   get() = if (isDark) Color.parseColor("#A0A8C0") else Color.parseColor("#3A4060")
    val TXT_MUTED get() = if (isDark) Color.parseColor("#5A607A") else Color.parseColor("#6A7090")
    val BORDER_C  get() = if (isDark) Color.parseColor("#1E2540") else Color.parseColor("#D0D8E8")

    /* aliases kept for compatibility */
    val YELLOW get() = AMBER
    val ORANGE get() = AMBER
}
