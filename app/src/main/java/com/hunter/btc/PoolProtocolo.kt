package com.hunter.btc

import org.json.JSONObject

/**
 * El protocolo del pool del puzzle (servidor de referencia: tools/pool/servidor.py): pedir un trozo del
 * rango con sus direcciones testigo, y devolver las claves de los testigos (y la del puzzle si salió).
 * Puro, para probarlo en la JVM; la red y el motor están en PoolPuzzle.
 */
object PoolProtocolo {
    data class Trabajo(val id: Long, val puzzle: Int, val inicio: String, val fin: String, val objetivo: String, val testigos: List<String>)

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val HEX40 = Regex("^[0-9a-f]{40}$")

    /** Lee y comprueba un trabajo; null si algo no es razonable (rango al revés o enorme, testigos raros…). */
    fun leerTrabajo(json: String): Trabajo? = try {
        val o = JSONObject(json)
        val ini = o.getString("inicio").lowercase(); val fin = o.getString("fin").lowercase()
        val ts = o.getJSONArray("testigos").let { a -> (0 until a.length()).map { a.getString(it).lowercase() } }
        val obj = o.getString("objetivo")
        val a = java.math.BigInteger(ini, 16); val b = java.math.BigInteger(fin, 16)
        val ok = HEX64.matches(ini) && HEX64.matches(fin) && a.signum() > 0 && b >= a &&
            b.subtract(a).bitLength() <= 48 &&                       // como mucho 2^48 claves por trozo
            ts.size in 1..16 && ts.all { HEX40.matches(it) } &&
            BtcAddress.validate(obj, false).let { it is BtcAddress.Result.Valid && it.info.type == BtcAddress.Type.P2PKH }
        if (!ok) null else Trabajo(o.getLong("id"), o.optInt("puzzle", 0), ini, fin, obj, ts)
    } catch (e: Exception) { null }

    /** "h160:priv,h160:priv" del motor → mapa sólo con los testigos de este trabajo. */
    fun testigosHallados(motor: String, t: Trabajo): Map<String, String> =
        motor.split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 && it[0] in t.testigos && HEX64.matches(it[1]) }?.let { it[0] to it[1] } }.toMap()

    fun resultado(t: Trabajo, cliente: String, testigos: Map<String, String>, hallada: String?): String =
        JSONObject().put("id", t.id).put("cliente", cliente)
            .put("testigos", JSONObject().apply { testigos.forEach { (h, k) -> put(h, k) } })
            .apply { if (hallada != null) put("hallada", hallada) }.toString()

    /** La clave del puzzle de lo que guardó el motor ("…ADDR… PRIV:<hex>…"), si está. */
    fun claveDe(matches: String, objetivo: String): String? =
        matches.lines().firstOrNull { it.contains(objetivo) }?.let { Regex("PRIV:([0-9a-f]{64})").find(it)?.groupValues?.get(1) }
}
