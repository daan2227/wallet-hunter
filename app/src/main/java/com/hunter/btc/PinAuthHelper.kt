package com.hunter.btc

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hunter.btc.AppTheme.AMBER
import com.hunter.btc.AppTheme.BG_CARD
import com.hunter.btc.AppTheme.BG_PANEL
import com.hunter.btc.AppTheme.BORDER_C
import com.hunter.btc.AppTheme.TXT_MUTED
import com.hunter.btc.AppTheme.TXT_PRI
import com.hunter.btc.AppTheme.TXT_SEC

object PinAuthHelper {

    private val RED = 0xFFFF4444.toInt()

    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun show(activity: Activity, onResult: (Boolean) -> Unit) {
        val hasPin = WalletManager.hasPin(activity)
        android.widget.Toast.makeText(activity, "hasPin=$hasPin", android.widget.Toast.LENGTH_LONG).show()
        if (!hasPin) { onResult(true); return }

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG_PANEL); cornerRadius = activity.dp(16).toFloat()
                setStroke(1, BORDER_C)
            }
            setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(32))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(activity.dp(24), 0, activity.dp(24), 0) }
        }

        // Handle bar
        sheet.addView(View(activity).apply {
            background = GradientDrawable().apply { setColor(BORDER_C); cornerRadius = activity.dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(activity.dp(36), activity.dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = activity.dp(22)
            }
        })

        // Title
        sheet.addView(TextView(activity).apply {
            text = "Enter PIN"
            textSize = 16f; setTextColor(TXT_PRI)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.04f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, activity.dp(22))
        })

        // Dots
        val pinDisplay = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, activity.dp(28))
        }
        val dots = Array(6) {
            View(activity).apply {
                val sz = activity.dp(12)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = activity.dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(activity.dp(2), BORDER_C)
                }
            }
        }
        dots.forEach { pinDisplay.addView(it) }
        sheet.addView(pinDisplay)

        val tvStatus = TextView(activity).apply {
            text = "Enter your PIN"
            textSize = 10f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.05f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, activity.dp(16))
        }

        val pin = StringBuilder()
        var dlg: AlertDialog? = null

        fun updateDots() = dots.forEachIndexed { i, d ->
            val bg = d.background as GradientDrawable
            if (i < pin.length) { bg.setColor(AMBER); bg.setStroke(0, Color.TRANSPARENT) }
            else { bg.setColor(Color.TRANSPARENT); bg.setStroke(activity.dp(2), BORDER_C) }
        }

        fun handleDigit(k: String) {
            if (k == "DEL") { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1); updateDots(); return }
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return
            if (WalletManager.checkPin(activity, pin.toString())) {
                dlg?.dismiss(); onResult(true)
            } else {
                pin.clear(); updateDots()
                tvStatus.text = "Wrong PIN"; tvStatus.setTextColor(RED)
            }
        }

        // Numpad
        val numpad = GridLayout(activity).apply { columnCount = 3; rowCount = 4; setPadding(0, 0, 0, activity.dp(10)) }
        listOf("1","2","3","4","5","6","7","8","9","","0","DEL").forEach { k ->
            numpad.addView(Button(activity).apply {
                text = k
                if (k == "DEL") { textSize = 12f; setTextColor(RED); typeface = Typeface.create("monospace", Typeface.NORMAL); letterSpacing = 0.05f }
                else { textSize = 22f; setTextColor(TXT_PRI); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) }
                background = GradientDrawable().apply {
                    setColor(if (k.isEmpty()) Color.TRANSPARENT else BG_CARD)
                    if (k.isNotEmpty()) setStroke(1, BORDER_C)
                    cornerRadius = activity.dp(10).toFloat()
                }
                val sz = activity.dp(76)
                layoutParams = GridLayout.LayoutParams().apply { width = sz; height = sz; setMargins(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4)) }
                isEnabled = k.isNotEmpty()
                if (k.isNotEmpty()) setOnClickListener { handleDigit(k) }
            })
        }
        sheet.addView(numpad)
        sheet.addView(tvStatus)

        val btnCancel = Button(activity).apply {
            text = "Cancel"; textSize = 12f; setTextColor(TXT_SEC)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.1f; isAllCaps = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); setStroke(1, BORDER_C); cornerRadius = activity.dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(48)).apply { topMargin = activity.dp(8) }
        }
        sheet.addView(btnCancel)

        dlg = AlertDialog.Builder(activity).setView(sheet).setCancelable(false).create()
        dlg!!.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        btnCancel.setOnClickListener { dlg?.dismiss(); onResult(false) }
        dlg!!.show()
    }
}
