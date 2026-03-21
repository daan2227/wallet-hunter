package com.hunter.btc

import android.app.Activity
import android.app.Dialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

// Función compartida de PIN screen
// Usada por WelcomeActivity y PinAuthHelper
    fun Activity.showPinScreen(
        activity: android.app.Activity,
