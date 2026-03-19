#!/usr/bin/env python3
"""
patch_step2.py
Crea RecoveryParser.kt en el proyecto Wallet Hunter.
Ejecutar desde la raiz del proyecto: python3 patch_step2.py
"""

import os

# Detectar package base del proyecto
def find_package_path(base="app/src/main/java"):
    for root, dirs, files in os.walk(base):
        for f in files:
            if f.endswith(".kt"):
                # Leer package del archivo
                try:
                    content = open(os.path.join(root, f)).read()
                    for line in content.splitlines():
                        if line.startswith("package "):
                            pkg = line.replace("package ", "").strip().rstrip(";")
                            # Tomar solo las 3 primeras partes: com.user.appname
                            parts = pkg.split(".")
                            base_pkg = ".".join(parts[:3])
                            base_path = os.path.join(base, *parts[:3])
                            return base_pkg, base_path
                except:
                    pass
    return None, None

base_pkg, base_path = find_package_path()

if not base_pkg:
    print("ERROR: No se pudo detectar el package. Asegurate de correr desde la raiz del proyecto.")
    exit(1)

print(f"Package detectado: {base_pkg}")
print(f"Ruta base: {base_path}")

# Crear carpeta recovery
recovery_path = os.path.join(base_path, "recovery")
os.makedirs(recovery_path, exist_ok=True)
print(f"Carpeta creada: {recovery_path}")

# Contenido de RecoveryParser.kt
content = f'''package {base_pkg}.recovery

/**
 * Parsea una seed phrase ingresada por el usuario.
 * Las palabras desconocidas se marcan con "???" (o "?" o "____").
 *
 * Ejemplo de input:
 *   "abandon ??? letter ??? advice cage absurd amount doctor acoustic avoid ???"
 *
 * Resultado:
 *   slots = [0:"abandon", 1:null, 2:"letter", 3:null, 4:"advice", ...]
 *   missingCount = 3
 *   missingIndices = [1, 3, 11]
 */
data class ParsedPhrase(
    val slots: List<String?>,
    val wordCount: Int,
    val missingCount: Int,
    val missingIndices: List<Int>,
    val knownWords: List<String>
)

sealed class ParseResult {{
    data class Success(val parsed: ParsedPhrase) : ParseResult()
    data class Error(val message: String) : ParseResult()
}}

object RecoveryParser {{

    private val MISSING_TOKENS = setOf("???", "?", "____", "_", "***", "xx", "XX")
    private val VALID_LENGTHS = setOf(12, 15, 18, 21, 24)
    const val MAX_MISSING = 4

    fun parse(input: String, wordlist: Set<String>): ParseResult {{

        val cleaned = input.trim()
            .lowercase()
            .replace(Regex("[,;.|]+"), " ")
            .replace(Regex("\\\\s+"), " ")

        if (cleaned.isEmpty()) {{
            return ParseResult.Error("Ingresa tu seed phrase.")
        }}

        val tokens = cleaned.split(" ").filter {{ it.isNotEmpty() }}

        if (tokens.size !in VALID_LENGTHS) {{
            return ParseResult.Error(
                "La seed phrase debe tener 12, 15, 18, 21 o 24 palabras. " +
                "Detectadas: ${{tokens.size}}"
            )
        }}

        val slots = mutableListOf<String?>()
        val missingIndices = mutableListOf<Int>()
        val knownWords = mutableListOf<String>()
        val invalidWords = mutableListOf<Pair<Int, String>>()

        tokens.forEachIndexed {{ index, token ->
            if (isMissingToken(token)) {{
                slots.add(null)
                missingIndices.add(index)
            }} else {{
                if (token !in wordlist) {{
                    invalidWords.add(Pair(index + 1, token))
                }}
                slots.add(token)
                knownWords.add(token)
            }}
        }}

        if (invalidWords.isNotEmpty()) {{
            val details = invalidWords.joinToString(", ") {{ (pos, word) ->
                "posición $pos: \\"$word\\""
            }}
            return ParseResult.Error(
                "Palabras no encontradas en BIP39: $details\\n" +
                "Verifica la ortografía o márcalas con ???"
            )
        }}

        val missingCount = missingIndices.size

        if (missingCount == 0) {{
            return ParseResult.Error(
                "No se encontraron palabras faltantes. " +
                "Usa ??? para las palabras que no recuerdas."
            )
        }}

        if (missingCount > MAX_MISSING) {{
            val combinations = estimateCombinations(missingCount)
            return ParseResult.Error(
                "Tienes $missingCount palabras faltantes (~$combinations combinaciones). " +
                "El máximo recomendado es $MAX_MISSING."
            )
        }}

        return ParseResult.Success(
            ParsedPhrase(
                slots = slots,
                wordCount = tokens.size,
                missingCount = missingCount,
                missingIndices = missingIndices,
                knownWords = knownWords
            )
        )
    }}

    fun estimateCombinations(missingCount: Int): String {{
        val total = Math.pow(2048.0, missingCount.toDouble()).toLong()
        return when {{
            total < 1_000_000L -> "${{total / 1000}}K"
            total < 1_000_000_000L -> "${{total / 1_000_000}}M"
            else -> "${{total / 1_000_000_000}}B"
        }}
    }}

    fun estimateTimeSeconds(missingCount: Int): Long {{
        val total = Math.pow(2048.0, missingCount.toDouble()).toLong()
        return total / 50_000L
    }}

    fun formatEstimatedTime(seconds: Long): String {{
        return when {{
            seconds < 60 -> "$seconds seg"
            seconds < 3600 -> "${{seconds / 60}} min"
            seconds < 86400 -> "${{seconds / 3600}} horas"
            else -> "${{seconds / 86400}} días"
        }}
    }}

    private fun isMissingToken(token: String): Boolean {{
        return token in MISSING_TOKENS || token.all {{ it == \'?\' || it == \'_\' || it == \'*\' }}
    }}
}}
'''

out_path = os.path.join(recovery_path, "RecoveryParser.kt")
with open(out_path, "w") as f:
    f.write(content)

print(f"✓ Creado: {out_path}")
print("\nPaso 2 completado. No requiere cambios en CMakeLists ni build.gradle.")
print("Siguiente: Paso 3 (PBKDF2 mnemonic→seed en C++)")
