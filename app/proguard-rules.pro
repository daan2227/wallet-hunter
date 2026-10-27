# Las clases de la app, enteras y con su nombre.
#
# El código nativo llama a métodos Kotlin buscándolos por nombre con JNI
# (HunterEngine.alHallar, los avisos del motor de Recovery…). R8 no ve esas
# llamadas: si renombrara o quitara esos métodos, la app compilaría igual y
# reventaría en el móvil al encontrar algo. Así que la app no se toca; R8 sólo
# recorta las librerías (AppCompat, Kotlin, zxing, biometric), que es donde
# está casi todo el dex que no se usa.
-keep class com.hunter.btc.** { *; }
-dontobfuscate

# Lightning (LDK Node): los bindings UniFFI llaman a la librería nativa con JNA, que busca clases y
# métodos por reflexión. R8 no lo ve: sin esto, compila y revienta en el móvil al arrancar el nodo.
-keep class org.lightningdevkit.ldknode.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn org.slf4j.**
