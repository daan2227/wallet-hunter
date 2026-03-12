package com.hunter.btc

import android.content.Context
import android.graphics.Color

object AppTheme {
    private const val PREFS = "theme_prefs"
    private const val KEY   = "is_dark"

    var isDark = true
        private set

    fun init(ctx: Context) {
        isDark = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun toggle(ctx: Context) {
        isDark = !isDark
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, isDark).apply()
    }

    // -- NIGHT --
    private val D_BG_DEEP   = Color.parseColor("#080b10")
    private val D_BG_PANEL  = Color.parseColor("#0c0f18")
    private val D_BG_CARD   = Color.parseColor("#0d1117")
    private val D_BG_ELEV   = Color.parseColor("#181d2e")
    private val D_AMBER      = Color.parseColor("#f0a500")
    private val D_GREEN      = Color.parseColor("#2dd4a0")
    private val D_RED        = Color.parseColor("#f05252")
    private val D_CYAN       = Color.parseColor("#60a5fa")
    private val D_TXT_PRI    = Color.parseColor("#e2e6f0")
    private val D_TXT_SEC    = Color.parseColor("#7a8299")
    private val D_TXT_MUTED  = Color.parseColor("#3d4560")
    private val D_BORDER_C   = Color.parseColor("#1a2332")

    // -- DAY --
    private val L_BG_DEEP   = Color.parseColor("#f0f2f7")
    private val L_BG_PANEL  = Color.parseColor("#e4e8f0")
    private val L_BG_CARD   = Color.parseColor("#ffffff")
    private val L_BG_ELEV   = Color.parseColor("#d8dde8")
    private val L_AMBER      = Color.parseColor("#b37400")
    private val L_GREEN      = Color.parseColor("#0e8a66")
    private val L_RED        = Color.parseColor("#c0392b")
    private val L_CYAN       = Color.parseColor("#1a6fbb")
    private val L_TXT_PRI    = Color.parseColor("#0d1117")
    private val L_TXT_SEC    = Color.parseColor("#4a5568")
    private val L_TXT_MUTED  = Color.parseColor("#9aaabb")
    private val L_BORDER_C   = Color.parseColor("#c8d0e0")

    val BG_DEEP   get() = if (isDark) D_BG_DEEP   else L_BG_DEEP
    val BG_PANEL  get() = if (isDark) D_BG_PANEL  else L_BG_PANEL
    val BG_CARD   get() = if (isDark) D_BG_CARD   else L_BG_CARD
    val BG_ELEV   get() = if (isDark) D_BG_ELEV   else L_BG_ELEV
    val AMBER     get() = if (isDark) D_AMBER      else L_AMBER
    val GREEN     get() = if (isDark) D_GREEN      else L_GREEN
    val RED       get() = if (isDark) D_RED        else L_RED
    val CYAN      get() = if (isDark) D_CYAN       else L_CYAN
    val TXT_PRI   get() = if (isDark) D_TXT_PRI    else L_TXT_PRI
    val TXT_SEC   get() = if (isDark) D_TXT_SEC    else L_TXT_SEC
    val TXT_MUTED get() = if (isDark) D_TXT_MUTED  else L_TXT_MUTED
    val BORDER_C  get() = if (isDark) D_BORDER_C   else L_BORDER_C
    val YELLOW    get() = AMBER
    val ORANGE    get() = AMBER
}
