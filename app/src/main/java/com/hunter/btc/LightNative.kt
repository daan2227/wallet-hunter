package com.hunter.btc

/**
 * Puente nativo del cliente ligero (BIP157/158): la máquina de estados vive en C++
 * (sincro_btc.h) y no sabe de sockets; [LightClient] abre las conexiones y le reparte
 * los bytes. Todas las llamadas llevan el identificador que devuelve [nuevo].
 *
 * La propiedad "hunter.ligero.lib" sólo la usan las pruebas del escritorio, que cargan
 * una biblioteca con este puente solo; en el teléfono es siempre hunter_jni.
 */
object LightNative {
    init { System.loadLibrary(System.getProperty("hunter.ligero.lib") ?: "hunter_jni") }

    /** red 0 = principal. red 1 = cadena sintética de pruebas (con el génesis en hex). 0 si falla. */
    external fun nuevo(red: Int, genesisHex: String, minPares: Int, desde: Int, nonce: Long, scriptsHex: String): Long
    external fun liberar(h: Long)
    /** Cabeceras 1..n de un fichero guardado; devuelve cuántas seguidas son válidas. */
    external fun cargar(h: Long, datos: ByteArray): Int
    external fun exportar(h: Long, desde: Int): ByteArray
    /** Bifurcación más baja desde la última pregunta, o -1. */
    external fun reorgMin(h: Long): Long
    external fun altura(h: Long): Int
    external fun parNuevo(h: Long, id: Int, ms: Long, relay: Boolean)
    external fun parCerrado(h: Long, id: Int, ms: Long)
    external fun recibido(h: Long, id: Int, datos: ByteArray, n: Int, ms: Long)
    external fun salida(h: Long, id: Int): ByteArray
    external fun cierres(h: Long): IntArray
    external fun tick(h: Long, ms: Long)
    external fun actualizar(h: Long, ms: Long)
    external fun reescanear(h: Long, scriptsHex: String, desde: Int, ms: Long)
    external fun estado(h: Long): String
    external fun difundir(h: Long, rawHex: String, ms: Long): String
    external fun pausarDifusion(h: Long, pausa: Boolean, ms: Long)
    external fun parListo(h: Long, id: Int): Boolean
    external fun estadoDifusion(h: Long, txid: String): String
}
