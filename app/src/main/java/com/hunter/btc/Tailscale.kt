package com.hunter.btc

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Integración con Tailscale.
 *
 * QUÉ ES ESTO Y QUÉ NO ES
 *
 * Tailscale no se empotra dentro de la app: se usa la app de Tailscale, que
 * monta un túnel a nivel de sistema. Eso significa que el tráfico de Wallet
 * Hunter ya pasa por él sin que la app tenga que hacer nada — lo que falta no
 * es fontanería de red, es que la app SEPA que está ahí y deje de pedirle al
 * usuario cosas que sólo valen en una WiFi.
 *
 * La alternativa era empotrar tsnet (el nodo de Tailscale en Go). Se descartó:
 * mete la cadena de Go en la compilación, suma 20-30 MB al APK y obliga a
 * reescribir el transporte entero, para acabar con la misma conexión que ya se
 * consigue así.
 *
 * POR QUÉ HACE FALTA
 *
 * El router de esta casa tiene CGNAT (WAN 10.41.14.226 contra una pública
 * distinta) y el IPv6 de la fibra llega desconectado y sin prefijo. O sea que
 * no hay forma de que un móvil de fuera llame al de casa: ni abriendo puertos,
 * porque el NAT que estorba es el de la operadora y ese no se toca.
 *
 * Tailscale atraviesa eso por los dos lados y además cifra de punta a punta con
 * WireGuard, que resuelve de paso que los pares (punto, distancia) del reparto
 * —material de clave— viajen en claro.
 */
object Tailscale {

    /** El paquete de la app oficial. */
    const val PAQUETE = "com.tailscale.ipn"

    /**
     * @param activo    hay un túnel de Tailscale levantado ahora mismo
     * @param direccion nuestra dirección dentro del tailnet (100.x.y.z), o ""
     */
    data class Estado(val activo: Boolean, val direccion: String)

    /**
     * ¿Está Tailscale levantado, y con qué dirección?
     *
     * NO hace red — sólo mira las interfaces—, así que se puede llamar desde el
     * hilo de la pantalla y refrescar cada pocos segundos sin coste. El nombre
     * MagicDNS va aparte, en [nombreDe], precisamente porque ese sí resuelve por
     * DNS y desde el hilo principal lanzaría NetworkOnMainThreadException.
     *
     * Se piden las DOS cosas —interfaz de túnel Y rango 100.64.0.0/10— porque
     * por separado cada una se equivoca: hay VPN de empresa que no usan ese
     * rango, y sobre todo 100.64/10 es el rango del CGNAT de las operadoras, así
     * que una dirección de datos móviles puede caer ahí sin ser ninguna VPN.
     * Juntas aciertan en el caso que importa.
     */
    fun estado(): Estado {
        try {
            for (iface in java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp) continue
                val n = iface.name
                if (!(n.startsWith("tun") || n.startsWith("ts"))) continue
                for (addr in java.util.Collections.list(iface.inetAddresses)) {
                    if (addr !is java.net.Inet4Address) continue
                    val d = addr.hostAddress ?: continue
                    if (!enRangoTailscale(d)) continue
                    return Estado(true, d)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Tailscale", "status: ${e.message}")
        }
        return Estado(false, "")
    }

    /** 100.64.0.0/10, el rango que reparte Tailscale. */
    fun enRangoTailscale(dir: String): Boolean {
        val p = dir.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    /**
     * El nombre MagicDNS de una dirección del tailnet, resolviendo a la inversa.
     *
     * Vale más que la dirección para dárselo al otro móvil: el nombre se lee y
     * se teclea sin equivocarse, y sigue valiendo aunque la dirección cambie.
     *
     * Devuelve "" si MagicDNS está apagado o si la resolución no contesta, que
     * es un caso normal y no un error: el nombre es una comodidad, la dirección
     * sigue valiendo.
     *
     * HACE RED. Nunca desde el hilo principal.
     */
    fun nombreDe(dir: String): String = try {
        val h = java.net.InetAddress.getByName(dir).canonicalHostName
        // Si no resuelve, getCanonicalHostName devuelve la propia dirección.
        if (h == dir || h.isEmpty()) "" else h
    } catch (e: Exception) { "" }

    /** ¿Está instalada la app de Tailscale? */
    fun instalado(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PAQUETE, 0)
        true
    } catch (e: Exception) { false }

    /**
     * Abre Tailscale, o su ficha en la tienda si no está instalado.
     *
     * @return false si no se pudo hacer ninguna de las dos cosas. Se devuelve en
     *   vez de tragarlo para que quien llame pueda decirlo: un botón que no hace
     *   nada al pulsarlo es peor que no tener botón.
     */
    fun abrir(ctx: Context): Boolean {
        if (instalado(ctx)) {
            val i = ctx.packageManager.getLaunchIntentForPackage(PAQUETE)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try { ctx.startActivity(i); true } catch (e: Exception) { false }
            }
        }
        // Primero la tienda instalada; si no hay, el navegador.
        for (uri in listOf("market://details?id=$PAQUETE",
                           "https://play.google.com/store/apps/details?id=$PAQUETE")) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) { /* se prueba el siguiente */ }
        }
        return false
    }

    /* ── Los demás aparatos del tailnet ───────────────────────────────────────
     *
     * Con esto no hay que teclear ninguna dirección: se eligen de una lista.
     *
     * "Buscar maestros en la red" no sirve aquí y no puede servir: va por
     * difusión UDP, y la difusión no cruza routers ni VPNs. Lo que sí hay es la
     * API de Tailscale, que es HTTPS normal y corriente.
     *
     * Hace falta una clave de API, que se saca en login.tailscale.com →
     * Settings → Keys → Generate API key. Se guarda en las preferencias
     * privadas de la app, al lado de todo lo demás.
     *
     * AVISO que hay que darle al usuario: esa clave deja LEER el inventario de
     * su tailnet —nombres, direcciones, sistemas operativos— a quien la tenga.
     * Es de sólo lectura para lo que hacemos aquí, pero es una credencial y
     * caduca a los 90 días por omisión.
     */

    data class Aparato(val nombre: String, val direccion: String, val so: String,
                       val enLinea: Boolean)

    private const val PREF_CLAVE = "tailscale_api_key"

    fun clave(ctx: Context): String =
        ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE)
            .getString(PREF_CLAVE, "") ?: ""

    fun guardarClave(ctx: Context, k: String) {
        ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE).edit()
            .putString(PREF_CLAVE, k.trim()).apply()
    }

    fun olvidarClave(ctx: Context) {
        ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE).edit()
            .remove(PREF_CLAVE).apply()
    }

    /**
     * Los aparatos del tailnet, por la API.
     *
     * Hace red: NUNCA desde el hilo principal.
     *
     * @throws java.io.IOException con un mensaje que se puede enseñar tal cual.
     *   Se distingue el 401 del resto porque es el único que el usuario puede
     *   arreglar él —la clave está mal o ha caducado— y decir "error 401" no le
     *   dice qué hacer.
     */
    fun aparatos(apiKey: String): List<Aparato> {
        if (apiKey.isBlank()) throw java.io.IOException("There is no API key")
        val url = java.net.URL("https://api.tailscale.com/api/v2/tailnet/-/devices")
        val c = url.openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            c.connectTimeout = 15000
            c.readTimeout = 15000
            val cod = c.responseCode
            if (cod == 401 || cod == 403)
                throw java.io.IOException(
                    "Tailscale rejects the key. It may have expired —they last 90 " +
                    "days— or been copied wrong. Get another at login.tailscale.com " +
                    "→ Settings → Keys.")
            if (cod != 200)
                throw java.io.IOException("Tailscale answered $cod")
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONArray("devices")
                ?: throw java.io.IOException("Tailscale answered without a device list")
            val out = ArrayList<Aparato>()
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                // La primera dirección del rango de Tailscale. Un aparato trae
                // también su IPv6, y para el cluster vale cualquiera, pero la
                // IPv4 se lee y se teclea mejor si hay que repetirla a mano.
                val dirs = d.optJSONArray("addresses")
                var dir = ""
                if (dirs != null) for (j in 0 until dirs.length()) {
                    val s = dirs.optString(j, "")
                    if (enRangoTailscale(s)) { dir = s; break }
                }
                if (dir.isEmpty()) continue
                out.add(Aparato(
                    nombre    = d.optString("hostname", d.optString("name", dir)),
                    direccion = dir,
                    so        = d.optString("os", ""),
                    // "online" no siempre viene; ausente se trata como conectado
                    // para no esconder un aparato que sí está.
                    enLinea   = d.optBoolean("online", true)))
            }
            return out.sortedBy { it.nombre }
        } finally {
            try { c.disconnect() } catch (e: Exception) {}
        }
    }
}
