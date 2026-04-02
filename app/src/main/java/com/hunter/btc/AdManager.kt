package com.hunter.btc

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout

// Ads desactivados temporalmente
object AdManager {
    fun init(ctx: Context) {}
    fun createBanner(ctx: Context): LinearLayout = LinearLayout(ctx)
    fun loadInterstitial(ctx: Context) {}
    fun showInterstitialIfReady(activity: Activity, onDismiss: () -> Unit = {}) { onDismiss() }
    fun onMatchFound(activity: Activity) {}
}
