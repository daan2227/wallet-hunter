/* Prueba de punta a punta de LightClient.kt: sockets de verdad, el puente JNI y la
 * máquina de estados en C++, contra servidor_sim (un nodo simulado por TCP).
 * Se lanza con ligero_e2e.sh. */
import com.hunter.btc.LightClient
import com.hunter.btc.LightClient.Direccion
import java.io.File
import java.io.RandomAccessFile

var fallos = 0
fun ok(c: Boolean, nombre: String) { println((if (c) "OK   " else "MAL  ") + nombre); if (!c) fallos++ }

class Servidor(exe: String, vararg args: String) {
    val proc: Process = ProcessBuilder(listOf(exe) + args).redirectErrorStream(false).start()
    var port = 0; var genesis = ""; var tx = ""; var txid = ""
    init {
        val r = proc.inputStream.bufferedReader()
        while (txid.isEmpty()) {
            val l = r.readLine() ?: break
            val p = l.split(" ")
            when (p[0]) { "PORT" -> port = p[1].toInt(); "GENESIS" -> genesis = p[1]; "TX" -> tx = p[1]; "TXID" -> txid = p[1] }
        }
    }
    fun parar() { proc.destroy(); proc.waitFor() }
}

fun campo(json: String, k: String): String =
    Regex("\"$k\":(\"([^\"]*)\"|[^,}\\]]+)").find(json)?.let { it.groupValues[2].ifEmpty { it.groupValues[1] } } ?: ""

fun esperar(ms: Long, cond: () -> Boolean): Boolean {
    val fin = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < fin) { if (cond()) return true; Thread.sleep(150) }
    return cond()
}

fun main(args: Array<String>) {
    val exe = args[0]
    val S1 = "0014" + "11".repeat(20); val S2 = "0014" + "22".repeat(20)
    val dirs = ArrayList<File>()
    fun tmp(): File = File.createTempFile("ligero", "").also { it.delete(); it.mkdirs(); dirs.add(it) }
    fun direcciones(port: Int, n: Int) = (1..n).map { Direccion("127.0.0.$it", port) }
    fun cliente(s: Servidor, dir: File, minPares: Int, objetivo: Int, n: Int) =
        LightClient(dir, 1, s.genesis, minPares, objetivo, resolver = { direcciones(s.port, n) })

    // ---- 1. nodos honestos: saldo, y las cabeceras quedan guardadas ----
    val dir1 = tmp()
    var s = Servidor(exe)
    var c = cliente(s, dir1, 2, 3, 5)
    c.iniciar(listOf(S1, S2), 0)
    ok(esperar(30_000) { campo(c.estadoJson(), "fase") == "listo" }, "tres nodos honestos: sincronización terminada")
    var j = c.estadoJson()
    ok(campo(j, "saldo") == "80000", "saldo = 80000 (50000 + 30000)")
    ok(campo(j, "verificado") == "true", "verificado contra dos nodos")
    ok(campo(j, "alturaCabeceras") == "30", "30 cabeceras")
    esperar(5000) { File(dir1, "cabeceras_btc.bin").length() == 80L * 30 }
    ok(File(dir1, "cabeceras_btc.bin").length() == 80L * 30, "las cabeceras se guardaron en el fichero (30 x 80 bytes)")
    ok(esperar(6000) { File(dir1, "pares_btc.txt").exists() }, "se recordaron los nodos que sirvieron")
    c.detener()

    // ---- 2. reinicio: carga el fichero y no vuelve a pedir las cabeceras ----
    c = cliente(s, dir1, 2, 3, 5)
    c.iniciar(listOf(S1, S2), 0)
    ok(campo(c.estadoJson(), "alturaCabeceras") == "30", "al reiniciar, las 30 cabeceras vuelven del fichero sin red")
    ok(esperar(30_000) { campo(c.estadoJson(), "fase") == "listo" && campo(c.estadoJson(), "saldo") == "80000" }, "...y termina con el mismo saldo")
    c.detener()

    // ---- 3. fichero alterado: se queda con el prefijo bueno y se corrige ----
    RandomAccessFile(File(dir1, "cabeceras_btc.bin"), "rw").use { it.seek(80L * 10 + 76); val b = it.read(); it.seek(80L * 10 + 76); it.write(b xor 0xff) }
    c = cliente(s, dir1, 2, 3, 5)
    c.iniciar(listOf(S1, S2), 0)
    ok(campo(c.estadoJson(), "alturaCabeceras") == "10", "cabecera alterada en el fichero: sólo se aceptan las 10 buenas")
    ok(File(dir1, "cabeceras_btc.bin").length() == 800L, "...y el fichero se recorta a 800 bytes")
    ok(esperar(30_000) { campo(c.estadoJson(), "fase") == "listo" && campo(c.estadoJson(), "saldo") == "80000" }, "...y se vuelve a sincronizar bien")
    esperar(5000) { File(dir1, "cabeceras_btc.bin").length() == 80L * 30 }
    ok(File(dir1, "cabeceras_btc.bin").length() == 2400L, "...dejando el fichero completo otra vez")
    c.detener(); s.parar()

    // ---- 4. un nodo que miente en el filtro ----
    s = Servidor(exe, "--mal=1:omitir")
    c = cliente(s, tmp(), 2, 2, 5)
    c.iniciar(listOf(S1, S2), 0)
    ok(esperar(40_000) { campo(c.estadoJson(), "fase") == "listo" }, "con un nodo mentiroso entre dos: termina con otros")
    j = c.estadoJson()
    ok(campo(j, "saldo") == "80000", "...con el saldo correcto")
    ok(j.contains("expulsado"), "...y el registro dice que expulsó a alguien")
    c.detener(); s.parar()

    // ---- 5. bloque con Merkle roto ----
    s = Servidor(exe, "--mal=1:merkle")
    c = cliente(s, tmp(), 1, 2, 5)
    c.iniciar(listOf(S1, S2), 0)
    ok(esperar(40_000) { campo(c.estadoJson(), "fase") == "listo" } && campo(c.estadoJson(), "saldo") == "80000", "un nodo con Merkle roto: se cambia de nodo y el saldo es correcto")
    c.detener(); s.parar()

    // ---- 6. difundir, ver que se propaga y que se confirma ----
    s = Servidor(exe, "--confirma")
    c = cliente(s, tmp(), 2, 3, 6)
    c.iniciar(listOf(S1, S2), 0)
    ok(esperar(30_000) { campo(c.estadoJson(), "fase") == "listo" }, "difusión: sincronizado antes de empezar")
    val txid = c.difundir(s.tx)
    ok(txid == s.txid, "difundir devuelve el txid")
    ok(esperar(20_000) { c.estadoDifusion(txid).startsWith("propagada") || c.estadoDifusion(txid).startsWith("confirmada") }, "se ve propagarse por la conexión con relay")
    ok(esperar(30_000) { c.actualizar(); c.estadoDifusion(txid) == "confirmada:31" }, "un bloque nuevo la confirma en la altura 31")
    ok(esperar(10_000) { campo(c.estadoJson(), "saldo") == "50000" }, "y el saldo baja a 50000")
    ok(c.difundir("zz").startsWith("ERR:"), "una transacción inválida da error")
    c.detener(); s.parar()

    // ---- 6b. sólo difusión: vale un nodo que no sirve filtros ----
    s = Servidor(exe, "--sin-filtros", "--confirma")
    c = LightClient(tmp(), 1, s.genesis, 1, 3, soloDifusion = true, resolver = { direcciones(s.port, 4) })
    c.iniciar(emptyList(), 0)
    val txid2 = c.difundir(s.tx)
    ok(txid2 == s.txid, "sólo difusión: devuelve el txid")
    ok(esperar(30_000) { c.estadoDifusion(txid2).startsWith("propagada") }, "sólo difusión: un nodo sin filtros la recibe y se ve propagar")
    ok(campo(c.estadoJson(), "fase") == "conectando", "sólo difusión: no sincroniza nada")
    c.detener(); s.parar()

    // ---- 7. sin nodos: no inventa ----
    s = Servidor(exe)
    c = LightClient(tmp(), 1, s.genesis, 2, 2, resolver = { emptyList() })
    c.iniciar(listOf(S1, S2), 0)
    Thread.sleep(1500)
    ok(campo(c.estadoJson(), "fase") == "conectando" && campo(c.estadoJson(), "saldo") == "0", "sin nodos: espera conectando y no da saldo")
    c.detener(); s.parar()

    dirs.forEach { it.deleteRecursively() }
    println(if (fallos == 0) "\nTODO CORRECTO" else "\n*** $fallos FALLOS ***")
    System.exit(if (fallos == 0) 0 else 1)
}
