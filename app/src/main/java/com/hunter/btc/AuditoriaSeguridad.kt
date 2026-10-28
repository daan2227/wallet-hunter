package com.hunter.btc

/**
 * Revisión de seguridad de TUS PROPIAS semillas y carteras: junta lo que ya saben otras piezas (fuerza de
 * contraseña, patrones de frase débil, reutilización de direcciones, nonce expuesto) en una lista de
 * hallazgos con su gravedad y una nota de 0 a 100, con un consejo para cada cosa.
 *
 * Puro: no toca red ni Android. Los datos que no se pueden saber aquí (si hay copia de seguridad, si la
 * huella está activada, cuántas direcciones se reutilizaron) se pasan en [Entrada]; los hace la pantalla.
 * Se prueba en la JVM (AuditoriaSeguridadTest).
 */
object AuditoriaSeguridad {

    enum class Gravedad(val penaliza: Int, val etiqueta: String) {
        CRITICO(45, "Critical"), ALTO(25, "High"), MEDIO(12, "Medium"), BAJO(4, "Low"), INFO(0, "Info")
    }

    class Hallazgo(val gravedad: Gravedad, val titulo: String, val detalle: String, val consejo: String)

    /**
     * Lo que la pantalla reúne de una cartera concreta. Los campos que no apliquen van en null: una cartera
     * vigilada no tiene frase, una WIF suelta no tiene las 12 palabras, etc.
     */
    class Entrada(
        val nombre: String,
        /** Las palabras BIP39 de la frase (sin la passphrase), o null si esta cartera no es de semilla. */
        val palabras: List<String>? = null,
        /** La passphrase (25.ª palabra): null = no se usa, "" = no se sabe aquí. */
        val passphrase: String? = null,
        /** Bits de la contraseña de copia probada, o null si no se probó ninguna. */
        val bitsContrasenaCopia: Double? = null,
        /** Direcciones de recepción reutilizadas (con más de una transacción), y cuántas se miraron. -1 = no se pudo. */
        val reutilizadas: Int = 0, val direccionesMiradas: Int = 0,
        /** Firmas con el mismo r (nonce repetido) halladas en las transacciones de esta cartera: la clave queda al descubierto. */
        val nonceExpuesto: Boolean = false,
        /** true si alguna dirección gasta desde una clave SIN comprimir (WIF 5…): menos privada y no la mueven algunas carteras. */
        val claveSinComprimir: Boolean = false,
    )

    /** Lo que vale para TODA la app, no para una cartera: el código, la huella, las copias, las capturas. */
    class Ajustes(
        val tienePin: Boolean,
        /** Bits del código actual (si se conoce), o null. */
        val bitsPin: Double? = null,
        /** El código usa el formato antiguo (PBKDF2), no Argon2id. */
        val pinAntiguo: Boolean = false,
        val huella: Boolean = false,
        /** Se ha hecho al menos una copia de seguridad del baúl. */
        val copiaHecha: Boolean = false,
        /** Las capturas de pantalla están bloqueadas (FLAG_SECURE / PantallaSegura). */
        val capturasBloqueadas: Boolean = true,
        val numeroCarteras: Int = 0,
    )

    fun nota(hallazgos: List<Hallazgo>): Int =
        (100 - hallazgos.sumOf { it.gravedad.penaliza }).coerceIn(0, 100)

    /** De la nota a una palabra y un color (ARGB) para la pantalla. */
    fun veredicto(nota: Int): Pair<String, Int> = when {
        nota >= 90 -> "Strong" to 0xFF2ECC71.toInt()
        nota >= 70 -> "Good" to 0xFF8BC34A.toInt()
        nota >= 45 -> "Needs attention" to 0xFFF1C40F.toInt()
        else -> "At risk" to 0xFFE74C3C.toInt()
    }

    // ── Análisis de la frase (puro, sin red) ───────────────────────────────────────────────────────

    class Frase(val palabras: Int, val todasBip39: Boolean, val checksumOk: Boolean, val banderas: List<String>)

    /** Revisa patrones de frase hecha a mano / poca entropía. Reutiliza la lógica de "Mnemonic strength". */
    fun analizarFrase(palabras: List<String>): Frase {
        val n = palabras.size
        val idx = palabras.map { Bip39Words.WORDS.indexOf(it) }
        val todas = idx.none { it < 0 }
        if (!todas || n < 12) return Frase(n, todas, false, emptyList())
        val cs = Bip39.validate(palabras.joinToString(" "))
        val checksumOk = cs is Bip39.Result.Valid
        val f = ArrayList<String>()
        val distintas = palabras.toSet().size
        if (distintas == 1) f.add("every word is the same")
        else if (distintas <= n / 2) f.add("only $distintas distinct words of $n")
        if (idx == idx.sorted()) f.add("words are in dictionary order")
        if (n > 1 && idx.zipWithNext().all { (a, b) -> b - a == 1 }) f.add("consecutive dictionary indices")
        val span = idx.max() - idx.min()
        if (span in 1..63) f.add("all words fall in a tiny range ($span apart)")
        return Frase(n, true, checksumOk, f)
    }

    // ── La revisión ─────────────────────────────────────────────────────────────────────────────────

    fun revisar(ajustes: Ajustes, carteras: List<Entrada>): List<Hallazgo> {
        val h = ArrayList<Hallazgo>()

        // ── Ajustes de la app ──
        if (!ajustes.tienePin) h.add(Hallazgo(Gravedad.ALTO, "No app code set",
            "Anyone who picks up your unlocked phone can open your wallets and spend.",
            "Set a code in Profile: 8–24 characters with letters, numbers and a symbol."))
        else {
            ajustes.bitsPin?.let { b -> if (b < 45) h.add(Hallazgo(Gravedad.MEDIO, "Weak app code",
                "Your code is easy to guess (~${b.toInt()} bits). Someone who takes the phone could brute-force it.",
                "Change it to something longer and less predictable (avoid words, years and sequences).")) }
            if (ajustes.pinAntiguo) h.add(Hallazgo(Gravedad.BAJO, "App code uses the old key derivation",
                "Your code still protects data with PBKDF2, not the newer memory-hard Argon2id.",
                "Change your code once (Profile → Change code) to upgrade it to Argon2id."))
        }
        if (!ajustes.huella && ajustes.tienePin) h.add(Hallazgo(Gravedad.INFO, "Fingerprint off",
            "You type the code every time. That is fine; biometrics is only a convenience.",
            "Optional: turn on fingerprint in Profile for quicker unlocks (the code still guards sends)."))
        if (!ajustes.copiaHecha && ajustes.numeroCarteras > 0) h.add(Hallazgo(Gravedad.ALTO, "No backup made",
            "If you lose or reset the phone, the coins are gone. The vault lives only on this device.",
            "Write down each seed phrase on paper, and make an encrypted vault backup (Options → Backup vault)."))
        if (!ajustes.capturasBloqueadas) h.add(Hallazgo(Gravedad.BAJO, "Screenshots allowed",
            "Another app or an accidental screenshot could capture a seed or address on screen.",
            "Turn on 'Block screenshots' in More → Privacy."))

        // ── Cada cartera ──
        for (c in carteras) {
            val pre = if (carteras.size > 1) "${c.nombre}: " else ""
            c.palabras?.let { w ->
                val fr = analizarFrase(w)
                when {
                    !fr.todasBip39 -> h.add(Hallazgo(Gravedad.CRITICO, "${pre}Not a valid BIP39 phrase",
                        "Some words are not in the BIP39 list. This phrase may be mistyped — a backup could be unrecoverable.",
                        "Check the words against your written backup."))
                    !fr.checksumOk -> h.add(Hallazgo(Gravedad.CRITICO, "${pre}Phrase checksum fails",
                        "The last word doesn't match the rest. The phrase is wrong somewhere; a restore from it may fail.",
                        "Re-check every word and its order against your backup."))
                    fr.banderas.isNotEmpty() -> h.add(Hallazgo(Gravedad.CRITICO, "${pre}Guessable seed phrase",
                        "This phrase looks hand-picked, not random:\n• " + fr.banderas.joinToString("\n• ") +
                        "\nAn attacker can brute-force it. Funds on it are not safe.",
                        "Create a NEW wallet with a freshly generated phrase and move the coins to it."))
                    fr.palabras == 12 -> h.add(Hallazgo(Gravedad.INFO, "${pre}12-word seed",
                        "12 words give 128-bit security — strong and standard. 24 words give 256-bit.",
                        "Nothing to do. If you want extra margin for a large, long-term holding, a 24-word seed is an option."))
                }
            }
            if (c.passphrase != null && c.passphrase.isNotEmpty()) {
                val b = FuerzaContrasena.evaluar(c.passphrase).bits
                if (b < 40) h.add(Hallazgo(Gravedad.MEDIO, "${pre}Weak passphrase (25th word)",
                    "Your passphrase adds only ~${b.toInt()} bits. A weak passphrase gives little extra protection.",
                    "Use a longer, less predictable passphrase — and never store it with the seed."))
            }
            if (c.nonceExpuesto) h.add(Hallazgo(Gravedad.CRITICO, "${pre}Private key exposed by a reused signature nonce",
                "Two of this wallet's transactions signed with the same nonce. Anyone can now derive the private key from the public chain.",
                "Move ALL funds from this wallet to a brand-new wallet immediately."))
            if (c.reutilizadas > 0) h.add(Hallazgo(Gravedad.MEDIO, "${pre}Address reuse",
                "${c.reutilizadas} of your addresses were used more than once. Reuse links your payments together and hurts privacy.",
                "Use a fresh receive address for each payment (the wallet already offers the next unused one)."))
            if (c.claveSinComprimir) h.add(Hallazgo(Gravedad.BAJO, "${pre}Uncompressed key",
                "This wallet spends an uncompressed (5…) key: larger, slightly more expensive and less private.",
                "Move the coins to a modern SegWit (bc1q) or Taproot (bc1p) address."))
        }
        return h.sortedByDescending { it.gravedad.penaliza }
    }
}
