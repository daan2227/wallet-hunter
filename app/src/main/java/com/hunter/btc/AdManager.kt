package com.hunter.btc

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {

    // IDs de prueba de Google — reemplazar con reales al publicar
    const val BANNER_ID       = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED_ID     = "ca-app-pub-3940256099942544/5224354917"

    private var interstitialAd: InterstitialAd? = null
    private var matchCount = 0

    fun init(ctx: Context) {
        MobileAds.initialize(ctx) {}
    }

    fun createBanner(ctx: Context): AdView {
        return AdView(ctx).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = BANNER_ID
            loadAd(AdRequest.Builder().build())
        }
    }

    fun loadInterstitial(ctx: Context) {
        InterstitialAd.load(ctx, INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            })
    }

    fun showInterstitialIfReady(activity: Activity, onDismiss: () -> Unit = {}) {
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitial(activity)
                    onDismiss()
                }
            }
            ad.show(activity)
        } else {
            loadInterstitial(activity)
            onDismiss()
        }
    }

    // Mostrar interstitial cada 5 matches
    fun onMatchFound(activity: Activity) {
        matchCount++
        if (matchCount % 5 == 0) {
            showInterstitialIfReady(activity)
        }
    }
}
