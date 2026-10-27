package com.hunter.btc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Las carteras cuyas claves están fuera de la app: se guarda su descriptor (público) y un nombre.
 * Un descriptor no permite gastar, pero sí ver todo el historial: va en el almacén privado de la app,
 * fuera de las copias en claro.
 */
object CarterasExternas {
    private const val PREFS = "carteras_externas"
    private const val K = "lista"

    data class Entrada(val id: String, val nombre: String, val descriptor: String)

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lista(ctx: Context): List<Entrada> = try {
        val a = JSONArray(p(ctx).getString(K, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entrada(o.getString("id"), o.getString("nombre"), o.getString("desc")) } }
    } catch (e: Exception) { RegistroFallos.anotar("CarterasExternas.lista", e); emptyList() }

    private fun guardar(ctx: Context, l: List<Entrada>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("nombre", it.nombre).put("desc", it.descriptor)) }
        p(ctx).edit().putString(K, a.toString()).apply()
    }

    fun anadir(ctx: Context, nombre: String, descriptor: String): String {
        val id = java.util.UUID.randomUUID().toString()
        guardar(ctx, lista(ctx) + Entrada(id, nombre.take(40).ifBlank { "Watch-only" }, descriptor))
        return id
    }
    fun renombrar(ctx: Context, id: String, nombre: String) = guardar(ctx, lista(ctx).map { if (it.id == id) it.copy(nombre = nombre.take(40)) else it })
    fun quitar(ctx: Context, id: String) = guardar(ctx, lista(ctx).filter { it.id != id })
    fun de(ctx: Context, id: String) = lista(ctx).firstOrNull { it.id == id }

    /** La curva de la app (motor nativo). */
    val derivador = Descriptor.Derivador { kc, ruta -> HunterEngine.ec("ckd", kc, ruta) }
    val tweaker = Descriptor.Tweaker { x -> HunterEngine.ec("tr", x, "") }

    /** El motor de un descriptor guardado o pegado (una clave o multifirma); null + motivo si no se reconoce. */
    fun motorDe(texto: String): Pair<MotorCartera?, String?> {
        MotorMultifirma.leer(texto, derivador)?.let { (m, e) -> return m to e }
        MotorHerencia.leer(texto, derivador)?.let { (m, e) -> return m to e }
        return when (val r = Descriptor.leer(texto)) {
            is Descriptor.R.Ok -> MotorVigilada(r.v, derivador, tweaker, r.avisos) to null
            is Descriptor.R.Mal -> null to r.motivo
        }
    }
}
