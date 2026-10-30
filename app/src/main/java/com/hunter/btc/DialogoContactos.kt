package com.hunter.btc

import android.app.Activity
import android.widget.LinearLayout
import android.widget.Toast
import android.app.AlertDialog

/**
 * El diálogo de la agenda, el mismo para Enviar (cadena) y para Lightning: tocar un contacto lo usa,
 * mantenerlo pulsado lo borra, y "Add" guarda uno nuevo (con [destinoActual] ya escrito si lo hay).
 */
object DialogoContactos {

    fun mostrar(act: Activity, lightning: Boolean, destinoActual: String?, alElegir: (String) -> Unit) {
        val todos = Contactos.lista(act).filter { c -> c.tipo?.let { if (lightning) it.enLightning else it.enCadena } == true }
        val nombres = todos.map { c -> "${c.nombre}\n${corto(c.destino)} · ${c.tipo?.etiqueta}" }.toTypedArray()
        val b = AlertDialog.Builder(act).setTitle("Contacts")
            .setNeutralButton("Add") { _, _ -> anadir(act, destinoActual) { mostrar(act, lightning, destinoActual, alElegir) } }
            .setNegativeButton("Close", null)
        if (todos.isEmpty()) b.setMessage(if (lightning) "No Lightning contacts yet (₿names, Lightning Addresses, BOLT12 offers or LNURLs)."
                                          else "No contacts yet. Add one with \"Add\".")
        else b.setItems(nombres) { _, i -> alElegir(todos[i].destino) }
        val d = b.show()
        d.listView?.setOnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(act).setTitle("Delete ${todos[i].nombre}?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> Contactos.quitar(act, todos[i].nombre); d.dismiss(); mostrar(act, lightning, destinoActual, alElegir) }
                .show()
            true
        }
    }

    private fun corto(d: String) = if (d.length > 28) d.take(14) + "…" + d.takeLast(10) else d

    private fun anadir(act: Activity, destino: String?, luego: () -> Unit) {
        val etN = Ui.input(act, "Name")
        val etD = Ui.input(act, "Address, ₿name, Lightning Address, offer or LNURL", mono = true).apply { setText(destino?.trim() ?: "") }
        val dp = act.resources.displayMetrics.density
        val v = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0); addView(etN); addView(etD) }
        AlertDialog.Builder(act).setTitle("New contact").setView(v)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = etN.text.toString(); val dst = etD.text.toString()
                val err = Contactos.validar(n, dst)
                if (err != null) { Toast.makeText(act, err, Toast.LENGTH_LONG).show(); return@setPositiveButton }
                Contactos.anadir(act, Contactos.Contacto(n, dst)); luego()
            }.show()
    }
}
