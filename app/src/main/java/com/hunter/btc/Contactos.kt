package com.hunter.btc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agenda de contactos: destinatarios con nombre, para no pegar una dirección larga cada vez. Un destino
 * puede ser una dirección, una petición "bitcoin:…", un nombre (₿usuario@dominio o Lightning Address), una
 * oferta BOLT12 o un LNURL. Al enviar se rellena tal cual: un nombre se resuelve en ese momento (BIP353
 * con DNSSEC), así una rotación de dirección del destinatario se respeta.
 *
 * Clasificar y validar es puro (ContactosTest); el almacén son unas preferencias.
 */
object Contactos {

    enum class Tipo(val etiqueta: String, val enCadena: Boolean, val enLightning: Boolean) {
        DIRECCION("Address", true, false),
        PETICION("Payment request", true, true),
        NOMBRE("₿name / Lightning Address", true, true),
        OFERTA("BOLT12 offer", false, true),
        LNURL("LNURL", false, true),
    }

    data class Contacto(val nombre: String, val destino: String) { val tipo: Tipo? get() = tipoDe(destino) }

    fun tipoDe(d0: String): Tipo? {
        val d = d0.trim()
        if (d.isEmpty()) return null
        if (d.startsWith("bitcoin:", ignoreCase = true)) return Tipo.PETICION
        val sinLn = d.removePrefix("lightning:").removePrefix("LIGHTNING:")
        if (sinLn.startsWith("lno1", ignoreCase = true)) return Tipo.OFERTA
        if ('@' in d && (Bip353.leer(d) != null || Lnurl.direccion(d) != null)) return Tipo.NOMBRE
        if (Lnurl.leer(d) is Lnurl.Destino.Url) return Tipo.LNURL
        if (BtcAddress.validate(d, false) is BtcAddress.Result.Valid || BtcAddress.validate(d, true) is BtcAddress.Result.Valid) return Tipo.DIRECCION
        return null
    }

    /** null si vale; si no, el motivo. */
    fun validar(nombre: String, destino: String): String? = when {
        nombre.isBlank() -> "Give it a name."
        nombre.length > 40 -> "The name is too long (40 characters at most)."
        tipoDe(destino) == null -> "That is not an address, ₿name, Lightning Address, BOLT12 offer or LNURL."
        else -> null
    }

    fun aJson(l: List<Contacto>): String = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("n", it.nombre).put("d", it.destino)) } }.toString()

    fun deJson(s: String?): List<Contacto> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Contacto(o.getString("n"), o.getString("d")) } }
    } catch (e: Exception) { emptyList() }

    /** Añade o sustituye (por nombre, sin mirar mayúsculas), ordenado por nombre. */
    fun con(l: List<Contacto>, c: Contacto): List<Contacto> =
        (l.filterNot { it.nombre.equals(c.nombre.trim(), ignoreCase = true) } + Contacto(c.nombre.trim(), c.destino.trim()))
            .sortedBy { it.nombre.lowercase() }

    // ── Almacén ──

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("contactos", Context.MODE_PRIVATE)
    fun lista(ctx: Context): List<Contacto> = deJson(prefs(ctx).getString("lista", "[]"))
    fun anadir(ctx: Context, c: Contacto) = prefs(ctx).edit().putString("lista", aJson(con(lista(ctx), c))).apply()
    fun quitar(ctx: Context, nombre: String) =
        prefs(ctx).edit().putString("lista", aJson(lista(ctx).filterNot { it.nombre == nombre })).apply()
}
