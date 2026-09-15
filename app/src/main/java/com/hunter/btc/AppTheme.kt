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

    /* ── Paleta minimalista clean ── */
    val BG_DEEP   get() = if (isDark) Color.parseColor("#090909") else Color.parseColor("#F4F4F4")
    val BG_PANEL  get() = if (isDark) Color.parseColor("#111111") else Color.parseColor("#FFFFFF")
    val BG_CARD   get() = if (isDark) Color.parseColor("#141414") else Color.parseColor("#FFFFFF")
    val BG_ELEV   get() = if (isDark) Color.parseColor("#1C1C1C") else Color.parseColor("#E8E8E8")
    val AMBER     get() = Color.parseColor("#00C896")   /* semantic: positive / found */
    val GREEN     get() = Color.parseColor("#00C896")
    val RED       get() = Color.parseColor("#F04040")
    val CYAN      get() = Color.parseColor("#6EA8FE")
    val BLUE      get() = Color.parseColor("#6EA8FE")
    val TXT_PRI   get() = if (isDark) Color.parseColor("#EFEFEF") else Color.parseColor("#0A0A0A")
    val TXT_SEC   get() = if (isDark) Color.parseColor("#868686") else Color.parseColor("#444444")
    val TXT_MUTED get() = if (isDark) Color.parseColor("#444444") else Color.parseColor("#909090")
    val BORDER_C  get() = if (isDark) Color.parseColor("#242424") else Color.parseColor("#E0E0E0")

    /* aliases */
    val YELLOW get() = AMBER
    val ORANGE get() = AMBER
}
