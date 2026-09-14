# ── JNI ──────────────────────────────────────────────────────────────────────
# El símbolo nativo codifica el nombre completo de la clase y del método
# (Java_com_btcseedrecovery_..._bruteForceSeeds), así que renombrar cualquiera
# de los dos rompe el enlace en tiempo de ejecución, no en compilación.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# recovery_engine.cpp:262 resuelve este método por nombre y firma con
# GetMethodID(cls,"onProgress","(JJLjava/lang/String;)V") y lo invoca desde C++.
# R8 no puede ver esa referencia, así que hay que conservarla explícitamente.
-keep class com.btcseedrecovery.recovery.RecoveryEngine { *; }
-keep class com.btcseedrecovery.HunterEngine { *; }

# ── Modelos serializados ─────────────────────────────────────────────────────
# Los resultados del motor nativo llegan como JSON y se leen por nombre de campo.
-keepclassmembers class com.btcseedrecovery.** {
    public <init>(...);
}

# ── zxing (QR) ───────────────────────────────────────────────────────────────
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# ── AndroidX ─────────────────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }
-dontwarn androidx.security.crypto.**

# ── Diagnóstico ──────────────────────────────────────────────────────────────
# Conservar números de línea para que crash_log.txt siga siendo legible,
# ocultando el nombre real del fichero fuente.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
