package com.hunter.btc

import java.math.BigInteger

/**
 * Validación DNSSEC en el propio teléfono, desde el ancla de la raíz: BIP353 exige no fiarse de que un
 * resolvedor remoto la haga. El resolvedor (DoH) sólo trae los datos; aquí se comprueba que cada RRset está
 * firmado por una clave de su zona, que esa clave está en el DNSKEY de la zona firmado por una KSK, que esa
 * KSK está en el DS del padre (firmado a su vez), y así hasta la raíz, cuya KSK tiene que ser un ancla.
 *
 *   Algoritmos: 8 y 10 (RSA SHA-256/512, clave ≥ 1024 bits), 13 y 14 (ECDSA P-256/P-384), 15 (Ed25519).
 *   DS: SHA-256 o SHA-384. SHA-1 (DS tipo 1, algoritmos 5 y 7) no se acepta, como pide BIP353.
 *   Comodines (un registro firmado como *.…) no se aceptan: falla cerrado.
 *
 * Todo lo que no se entiende hace fallar la validación; nunca se da por bueno algo sin firma. Que un dato
 * NO exista no se prueba (no se miran NSEC): sólo significa "no hay instrucciones", nunca "son éstas".
 * Puro: [pedir] trae una respuesta DNS en binario; Bip353Test le pasa respuestas generadas con dnspython.
 */
class Dnssec(private val pedir: (String, Int) -> ByteArray?, private val ahora: Long,
             private val anclas: List<Ds> = ANCLAS_RAIZ) {

    class Ds(val etiqueta: Int, val alg: Int, val tipoResumen: Int, val resumen: ByteArray)

    companion object {
        /** KSK-2017 y KSK-2024 de la raíz (IANA; las mismas que trae Unbound). */
        val ANCLAS_RAIZ = listOf(
            Ds(20326, 8, 2, hexa("E06D44B80B8F1D39A95C0B0D7C65D08458E880409BBC683457104237C7F8EC8D")),
            Ds(38696, 8, 2, hexa("683D2D0ACB8C9B712A1948B27F741219298D0A450D612C483AF444A4C0FB2B16")))
        private val ALGS = setOf(8, 10, 13, 14, 15)
        private const val HOLGURA = 3600L
        fun hexa(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Lo que sale de validar: los registros, o el motivo por el que no se pueden dar por buenos. */
    class Validado(val registros: List<Dns.Rr>?, val error: String?, val noExiste: Boolean = false)

    private val claves = HashMap<String, List<Dns.Rr>>()
    private var pasos = 0

    /** Los TXT de [nombre] validados, siguiendo CNAME (validados también). */
    fun txt(nombre: String): Validado {
        var actual = Dns.canonico(nombre)
        var previo: Dns.Mensaje? = null
        repeat(8) {
            // El resolvedor suele traer el destino del CNAME en la misma respuesta; si no, se pregunta por él.
            val m = previo?.takeIf { p -> p.respuestas.any { it.nombre == actual } }
                ?: pedir(actual, Dns.TXT)?.let { Dns.leer(it) } ?: return Validado(null, "No DNS answer for $actual.")
            previo = m
            if (m.rcode == 3) return Validado(null, null, noExiste = true)
            if (m.rcode != 0) return Validado(null, "DNS error ${m.rcode} for $actual.")
            val txt = m.respuestas.filter { it.nombre == actual && it.tipo == Dns.TXT }
            if (txt.isNotEmpty()) {
                val e = validar(txt, firmasDe(m, actual, Dns.TXT)) ?: return Validado(txt, null)
                return Validado(null, e)
            }
            val cn = m.respuestas.filter { it.nombre == actual && it.tipo == Dns.CNAME }
            if (cn.size != 1) return Validado(null, null, noExiste = true)
            validar(cn, firmasDe(m, actual, Dns.CNAME))?.let { return Validado(null, "CNAME of $actual: $it") }
            actual = Dns.nombre(cn[0].rdata, 0).first
        }
        return Validado(null, "Too many CNAMEs.")
    }

    private fun firmasDe(m: Dns.Mensaje, nombre: String, tipo: Int) =
        m.respuestas.filter { it.nombre == nombre && it.tipo == Dns.RRSIG && it.rdata.size > 18 && u16(it.rdata, 0) == tipo }

    /** null si [rrset] queda validado por alguna de [firmas]; si no, el motivo. */
    private fun validar(rrset: List<Dns.Rr>, firmas: List<Dns.Rr>, firmante: String? = null): String? {
        if (++pasos > 64) return "Too many validation steps."
        if (firmas.isEmpty()) return "Not signed (no DNSSEC)."
        val dueño = rrset[0].nombre
        var motivo = "No valid signature."
        for (f in firmas) {
            val rd = f.rdata
            val alg = rd[2].toInt() and 0xff; val etiq = rd[3].toInt() and 0xff
            val incepcion = u32(rd, 12); val expira = u32(rd, 8); val tag = u16(rd, 16)
            val (signer, finNombre) = Dns.nombre(rd, 18)
            if (alg !in ALGS) { motivo = "Unsupported or weak algorithm $alg."; continue }
            if (!Dns.dentroDe(dueño, signer) || (firmante != null && signer != firmante)) { motivo = "Signed by the wrong zone."; continue }
            if (etiq != Dns.etiquetas(dueño)) { motivo = "Wildcard records are not accepted."; continue }
            if (ahora < incepcion - HOLGURA || ahora > expira + HOLGURA) { motivo = "Signature outside its validity period."; continue }
            val llaves = (if (signer == dueño && rrset[0].tipo == Dns.DNSKEY) null else clavesDe(signer)) ?: run { motivo = "Could not validate the keys of $signer."; null } ?: continue
            val datos = datosFirmados(rrset, rd.copyOfRange(0, finNombre), u32(rd, 4))
            val firma = rd.copyOfRange(finNombre, rd.size)
            if (llaves.any { k -> etiquetaDe(k.rdata) == tag && (k.rdata[3].toInt() and 0xff) == alg && verificar(alg, k.rdata.copyOfRange(4, k.rdata.size), datos, firma) }) return null
            motivo = "The signature does not match."
        }
        return motivo
    }

    /** Las claves de zona de [zona], validadas desde la raíz. null si no se puede. */
    private fun clavesDe(zona: String): List<Dns.Rr>? {
        claves[zona]?.let { return it }
        if (++pasos > 64) return null
        val m = pedir(zona, Dns.DNSKEY)?.let { Dns.leer(it) } ?: return null
        val set = m.respuestas.filter { it.nombre == zona && it.tipo == Dns.DNSKEY && it.rdata.size > 4 }
        if (set.isEmpty()) return null
        val ds: List<Ds> = if (zona == ".") anclas else {
            val md = pedir(zona, Dns.DS)?.let { Dns.leer(it) } ?: return null
            val dsSet = md.respuestas.filter { it.nombre == zona && it.tipo == Dns.DS && it.rdata.size > 4 }
            if (dsSet.isEmpty()) return null
            // El DS lo firma el padre: el firmante tiene que estar por encima de la zona, nunca ella misma.
            val firmasDs = firmasDe(md, zona, Dns.DS).filter { Dns.nombre(it.rdata, 18).first.let { s -> s != zona && Dns.dentroDe(zona, s) } }
            if (validar(dsSet, firmasDs) != null) return null
            dsSet.map { Ds(u16(it.rdata, 0), it.rdata[2].toInt() and 0xff, it.rdata[3].toInt() and 0xff, it.rdata.copyOfRange(4, it.rdata.size)) }
        }
        // KSK: las DNSKEY que coinciden con algún DS (SHA-256 o SHA-384).
        val ksk = set.filter { k -> ds.any { d -> coincide(zona, k, d) } }
        if (ksk.isEmpty()) return null
        // El DNSKEY tiene que estar firmado por una de esas KSK.
        val firmas = firmasDe(m, zona, Dns.DNSKEY)
        val ok = firmas.any { f ->
            val rd = f.rdata; val (signer, fin) = Dns.nombre(rd, 18)
            val alg = rd[2].toInt() and 0xff
            signer == zona && alg in ALGS && (rd[3].toInt() and 0xff) == Dns.etiquetas(zona) &&
                ahora >= u32(rd, 12) - HOLGURA && ahora <= u32(rd, 8) + HOLGURA &&
                ksk.any { k -> etiquetaDe(k.rdata) == u16(rd, 16) && (k.rdata[3].toInt() and 0xff) == alg &&
                    verificar(alg, k.rdata.copyOfRange(4, k.rdata.size), datosFirmados(set, rd.copyOfRange(0, fin), u32(rd, 4)), rd.copyOfRange(fin, rd.size)) }
        }
        if (!ok) return null
        // Claves de zona (bit 7 de flags), no revocadas (bit 8), protocolo 3.
        val zonales = set.filter { k -> (u16(k.rdata, 0) and 0x0100) != 0 && (u16(k.rdata, 0) and 0x0080) == 0 && (k.rdata[2].toInt() and 0xff) == 3 }
        claves[zona] = zonales
        return zonales
    }

    private fun coincide(zona: String, k: Dns.Rr, d: Ds): Boolean {
        if ((k.rdata[3].toInt() and 0xff) != d.alg || etiquetaDe(k.rdata) != d.etiqueta) return false
        if ((u16(k.rdata, 0) and 0x0100) == 0 || (u16(k.rdata, 0) and 0x0080) != 0) return false
        val md = when (d.tipoResumen) { 2 -> "SHA-256"; 4 -> "SHA-384"; else -> return false }   // SHA-1 (1): no
        return java.security.MessageDigest.getInstance(md).digest(Dns.aWire(zona) + k.rdata).contentEquals(d.resumen)
    }

    /** RFC 4034 3.1.8.1: RRSIG sin la firma, y cada RR canónico con el TTL original, ordenados por RDATA. */
    private fun datosFirmados(rrset: List<Dns.Rr>, rrsigSinFirma: ByteArray, ttlOriginal: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream(); o.write(rrsigSinFirma)
        val dueño = Dns.aWire(rrset[0].nombre)
        val rds = rrset.map { it.rdata }.sortedWith { a, b -> comparar(a, b) }
        var previo: ByteArray? = null
        for (rd in rds) {
            if (previo != null && previo.contentEquals(rd)) continue        // duplicados fuera
            previo = rd
            o.write(dueño)
            o.write(byteArrayOf((rrset[0].tipo shr 8).toByte(), rrset[0].tipo.toByte(), (rrset[0].clase shr 8).toByte(), rrset[0].clase.toByte()))
            o.write(byteArrayOf((ttlOriginal shr 24).toByte(), (ttlOriginal shr 16).toByte(), (ttlOriginal shr 8).toByte(), ttlOriginal.toByte()))
            o.write(byteArrayOf((rd.size shr 8).toByte(), rd.size.toByte())); o.write(rd)
        }
        return o.toByteArray()
    }

    private fun comparar(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
        return a.size - b.size
    }

    /** RFC 4034 apéndice B. */
    private fun etiquetaDe(rd: ByteArray): Int {
        var ac = 0L
        for (i in rd.indices) ac += if (i and 1 == 0) (rd[i].toLong() and 0xff) shl 8 else (rd[i].toLong() and 0xff)
        ac += (ac shr 16) and 0xffff
        return (ac and 0xffff).toInt()
    }

    private fun verificar(alg: Int, clave: ByteArray, datos: ByteArray, firma: ByteArray): Boolean = try {
        when (alg) {
            8, 10 -> {
                // RFC 3110: largo del exponente (1 byte, o 0 y 2 bytes), exponente, módulo.
                var p = 0; var le = clave[0].toInt() and 0xff; p = 1
                if (le == 0) { le = u16(clave, 1); p = 3 }
                val e = BigInteger(1, clave.copyOfRange(p, p + le)); val n = BigInteger(1, clave.copyOfRange(p + le, clave.size))
                if (n.bitLength() < 1024) false else {
                    val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(n, e))
                    java.security.Signature.getInstance(if (alg == 8) "SHA256withRSA" else "SHA512withRSA").run { initVerify(pub); update(datos); verify(firma) }
                }
            }
            13, 14 -> {
                val n = if (alg == 13) 32 else 48
                if (clave.size != 2 * n || firma.size != 2 * n) false else {
                    val ap = java.security.AlgorithmParameters.getInstance("EC")
                    ap.init(java.security.spec.ECGenParameterSpec(if (alg == 13) "secp256r1" else "secp384r1"))
                    val params = ap.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
                    val punto = java.security.spec.ECPoint(BigInteger(1, clave.copyOfRange(0, n)), BigInteger(1, clave.copyOfRange(n, 2 * n)))
                    val pub = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(punto, params))
                    java.security.Signature.getInstance(if (alg == 13) "SHA256withECDSA" else "SHA384withECDSA")
                        .run { initVerify(pub); update(datos); verify(der(firma.copyOfRange(0, n), firma.copyOfRange(n, 2 * n))) }
                }
            }
            15 -> Ed25519.verificar(clave, datos, firma)
            else -> false
        }
    } catch (e: Exception) { false }

    /** r ‖ s (DNSSEC) a DER (lo que pide Java). */
    private fun der(r: ByteArray, s: ByteArray): ByteArray {
        fun ent(x: ByteArray): ByteArray { val v = BigInteger(1, x).toByteArray(); return byteArrayOf(2, v.size.toByte()) + v }
        val c = ent(r) + ent(s)
        return byteArrayOf(0x30, c.size.toByte()) + c
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, p: Int) = ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)
}
