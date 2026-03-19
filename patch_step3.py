#!/usr/bin/env python3
"""
patch_step3.py
Agrega la función mnemonic_to_seed() en C++ usando PBKDF2-HMAC-SHA512 via OpenSSL.
Ejecutar desde la raiz del proyecto: python3 patch_step3.py
"""

import os
import sys

PROJECT_ROOT = os.getcwd()
JNI_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "cpp")

# ── 1. Buscar el CMakeLists.txt ──────────────────────────────────────────────
cmake_path = os.path.join(JNI_DIR, "CMakeLists.txt")
if not os.path.exists(cmake_path):
    # Buscar en subdirectorios
    for root, dirs, files in os.walk(PROJECT_ROOT):
        if "CMakeLists.txt" in files:
            candidate = os.path.join(root, "CMakeLists.txt")
            content = open(candidate).read()
            if "secp256k1" in content or "wallet" in content.lower():
                cmake_path = candidate
                JNI_DIR = root
                break

if not os.path.exists(cmake_path):
    print("ERROR: No se encontró CMakeLists.txt con secp256k1")
    sys.exit(1)

print(f"CMakeLists.txt encontrado: {cmake_path}")

# ── 2. Crear mnemonic.h ───────────────────────────────────────────────────────
mnemonic_h = os.path.join(JNI_DIR, "mnemonic.h")

mnemonic_h_content = r"""#pragma once
#ifndef WALLET_HUNTER_MNEMONIC_H
#define WALLET_HUNTER_MNEMONIC_H

#include <stdint.h>
#include <string>
#include <vector>

/**
 * Convierte una seed phrase BIP39 a 64 bytes de seed via PBKDF2-HMAC-SHA512.
 *
 * @param mnemonic  Frase de 12/24 palabras separadas por espacios
 * @param passphrase Passphrase adicional (vacía por defecto)
 * @param seed_out  Buffer de salida, debe tener al menos 64 bytes
 * @return true si OK, false si error
 */
bool mnemonic_to_seed(const std::string& mnemonic,
                      const std::string& passphrase,
                      uint8_t seed_out[64]);

/**
 * Valida que todas las palabras de la frase existen en el wordlist BIP39.
 * @param words    Vector de palabras
 * @param wordlist Vector de 2048 palabras BIP39
 * @return índice de la primera palabra inválida, o -1 si todas OK
 */
int validate_mnemonic_words(const std::vector<std::string>& words,
                            const std::vector<std::string>& wordlist);

/**
 * Convierte un mnemonic a índices del wordlist BIP39.
 * Útil para el motor de combinaciones.
 */
std::vector<int> mnemonic_to_indices(const std::vector<std::string>& words,
                                     const std::vector<std::string>& wordlist);

#endif // WALLET_HUNTER_MNEMONIC_H
"""

with open(mnemonic_h, "w") as f:
    f.write(mnemonic_h_content)
print(f"✓ Creado: {mnemonic_h}")

# ── 3. Crear mnemonic.cpp ─────────────────────────────────────────────────────
mnemonic_cpp = os.path.join(JNI_DIR, "mnemonic.cpp")

mnemonic_cpp_content = r"""#include "mnemonic.h"
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <cstring>
#include <algorithm>

/**
 * PBKDF2-HMAC-SHA512
 * Implementación BIP39 estándar:
 *   password   = mnemonic (UTF-8, NFKD normalizado idealmente)
 *   salt       = "mnemonic" + passphrase
 *   iterations = 2048
 *   keylen     = 64 bytes
 */
bool mnemonic_to_seed(const std::string& mnemonic,
                      const std::string& passphrase,
                      uint8_t seed_out[64])
{
    // Construir salt: "mnemonic" + passphrase
    const std::string salt_prefix = "mnemonic";
    std::string salt = salt_prefix + passphrase;

    int result = PKCS5_PBKDF2_HMAC(
        mnemonic.c_str(),          // password
        (int)mnemonic.size(),      // password length
        (const unsigned char*)salt.c_str(),  // salt
        (int)salt.size(),          // salt length
        2048,                      // iterations (BIP39 estándar)
        EVP_sha512(),              // hash function
        64,                        // key length en bytes
        seed_out                   // output buffer
    );

    return result == 1;
}

int validate_mnemonic_words(const std::vector<std::string>& words,
                            const std::vector<std::string>& wordlist)
{
    for (int i = 0; i < (int)words.size(); i++) {
        auto it = std::find(wordlist.begin(), wordlist.end(), words[i]);
        if (it == wordlist.end()) {
            return i; // índice de palabra inválida
        }
    }
    return -1; // todas válidas
}

std::vector<int> mnemonic_to_indices(const std::vector<std::string>& words,
                                     const std::vector<std::string>& wordlist)
{
    std::vector<int> indices;
    for (const auto& word : words) {
        auto it = std::find(wordlist.begin(), wordlist.end(), word);
        if (it != wordlist.end()) {
            indices.push_back((int)(it - wordlist.begin()));
        } else {
            indices.push_back(-1);
        }
    }
    return indices;
}
"""

with open(mnemonic_cpp, "w") as f:
    f.write(mnemonic_cpp_content)
print(f"✓ Creado: {mnemonic_cpp}")

# ── 4. Parchear CMakeLists.txt ────────────────────────────────────────────────
cmake_content = open(cmake_path).read()

# Agregar mnemonic.cpp a los sources si no está ya
if "mnemonic.cpp" not in cmake_content:
    # Buscar la línea add_library con los sources
    import re

    # Patrón: add_library( ... src1.cpp src2.cpp ... )
    pattern = r'(add_library\s*\([^)]*?)(\.cpp\s*\))'

    def add_mnemonic_source(match):
        return match.group(0).replace(match.group(2), ".cpp\n        mnemonic.cpp\n)")

    new_content = re.sub(pattern, add_mnemonic_source, cmake_content, count=1, flags=re.DOTALL)

    if new_content == cmake_content:
        # Intentar otro patrón más simple: agregar antes del último .cpp en add_library
        lines = cmake_content.splitlines()
        new_lines = []
        added = False
        for line in lines:
            new_lines.append(line)
            if not added and ".cpp" in line and "add_library" not in line:
                # Agregar mnemonic.cpp en la siguiente línea con la misma indentación
                indent = len(line) - len(line.lstrip())
                new_lines.append(" " * indent + "mnemonic.cpp")
                added = True
        new_content = "\n".join(new_lines)

    with open(cmake_path, "w") as f:
        f.write(new_content)
    print(f"✓ mnemonic.cpp agregado a CMakeLists.txt")
else:
    print("→ mnemonic.cpp ya estaba en CMakeLists.txt")

# Verificar que OpenSSL está linkeado
cmake_content_updated = open(cmake_path).read()
if "ssl" not in cmake_content_updated.lower() and "crypto" not in cmake_content_updated.lower():
    print("\n⚠️  AVISO: OpenSSL no detectado en CMakeLists.txt")
    print("   Verifica que tienes algo como:")
    print("   target_link_libraries(... ssl crypto ...)")
    print("   Si usas libsecp256k1 precompilado con OpenSSL, ya debería estar incluido.")
else:
    print("✓ OpenSSL ya está linkeado en CMakeLists.txt")

# ── 5. Resumen ────────────────────────────────────────────────────────────────
print("""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Paso 3 completado:

  app/src/main/cpp/mnemonic.h     ← declaraciones
  app/src/main/cpp/mnemonic.cpp   ← PBKDF2-HMAC-SHA512

Función principal:
  mnemonic_to_seed(mnemonic, passphrase, seed_out[64])

Siguiente: Paso 4 (BIP32 derivación HD en C++)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
