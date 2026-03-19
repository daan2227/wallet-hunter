#!/usr/bin/env python3
"""
patch_step4.py
Agrega bip32.h y bip32.cpp al proyecto: derivación HD BIP32/BIP44.
Ejecutar desde la raiz del proyecto: python3 patch_step4.py
"""

import os, re, sys

PROJECT_ROOT = os.getcwd()

# Localizar carpeta cpp
def find_cpp_dir():
    for root, dirs, files in os.walk(PROJECT_ROOT):
        if "CMakeLists.txt" in files:
            content = open(os.path.join(root, "CMakeLists.txt")).read()
            if "secp256k1" in content or "wallet" in content.lower():
                return root
    return None

JNI_DIR = find_cpp_dir()
if not JNI_DIR:
    print("ERROR: No se encontró directorio cpp con CMakeLists.txt")
    sys.exit(1)

cmake_path = os.path.join(JNI_DIR, "CMakeLists.txt")
print(f"Directorio cpp: {JNI_DIR}")

# ── 1. bip32.h ────────────────────────────────────────────────────────────────
bip32_h = r"""#pragma once
#ifndef WALLET_HUNTER_BIP32_H
#define WALLET_HUNTER_BIP32_H

#include <stdint.h>
#include <stdbool.h>
#include <string>

/**
 * Nodo BIP32 — contiene clave privada + chain code.
 * 32 bytes cada uno.
 */
struct Bip32Node {
    uint8_t key[32];        // clave privada
    uint8_t chain_code[32]; // chain code
};

/**
 * Deriva el nodo raíz (master key) desde 64 bytes de seed BIP39.
 * Usa HMAC-SHA512 con key="Bitcoin seed".
 *
 * @param seed      64 bytes del seed (output de mnemonic_to_seed)
 * @param node_out  nodo maestro resultante
 * @return true si OK
 */
bool bip32_master_key(const uint8_t seed[64], Bip32Node& node_out);

/**
 * Derivación de hijo — hardened o normal.
 *
 * Hardened: index >= 0x80000000  (usa clave privada del padre)
 * Normal:   index <  0x80000000  (usa clave pública del padre)
 *
 * Para BIP44 se usan siempre hardened en los primeros 3 niveles:
 *   purpose=44'  → index = 44  | 0x80000000
 *   coin=0'      → index =  0  | 0x80000000
 *   account=0'   → index =  0  | 0x80000000
 *   change=0     → index =  0  (normal)
 *   address=N    → index =  N  (normal)
 *
 * @param parent    nodo padre
 * @param index     índice de derivación
 * @param child_out nodo hijo resultante
 * @return true si OK
 */
bool bip32_derive_child(const Bip32Node& parent,
                        uint32_t index,
                        Bip32Node& child_out);

/**
 * Derivación BIP44 completa desde seed hasta clave privada final.
 * Ruta: m/44'/0'/0'/0/address_index
 *
 * @param seed          64 bytes del seed BIP39
 * @param address_index índice de la dirección (0, 1, 2, ...)
 * @param privkey_out   32 bytes de clave privada resultante
 * @return true si OK
 */
bool bip44_derive_privkey(const uint8_t seed[64],
                          uint32_t address_index,
                          uint8_t privkey_out[32]);

#endif // WALLET_HUNTER_BIP32_H
"""

with open(os.path.join(JNI_DIR, "bip32.h"), "w") as f:
    f.write(bip32_h)
print("✓ Creado: bip32.h")

# ── 2. bip32.cpp ──────────────────────────────────────────────────────────────
bip32_cpp = r"""#include "bip32.h"
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <secp256k1.h>
#include <cstring>
#include <arpa/inet.h>  // htonl

// Constante BIP32
static const char* BIP32_SEED_KEY = "Bitcoin seed";

// ── HMAC-SHA512 helper ────────────────────────────────────────────────────────
static bool hmac_sha512(const uint8_t* key, size_t key_len,
                        const uint8_t* data, size_t data_len,
                        uint8_t out[64])
{
    unsigned int out_len = 64;
    return HMAC(EVP_sha512(),
                key, (int)key_len,
                data, (int)data_len,
                out, &out_len) != nullptr;
}

// ── Verificar que la clave es válida en secp256k1 ────────────────────────────
static bool is_valid_privkey(const uint8_t key[32]) {
    secp256k1_context* ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    bool valid = secp256k1_ec_seckey_verify(ctx, key) == 1;
    secp256k1_context_destroy(ctx);
    return valid;
}

// ── Master key desde seed ────────────────────────────────────────────────────
bool bip32_master_key(const uint8_t seed[64], Bip32Node& node_out)
{
    uint8_t I[64];
    if (!hmac_sha512(
            (const uint8_t*)BIP32_SEED_KEY, strlen(BIP32_SEED_KEY),
            seed, 64,
            I)) {
        return false;
    }

    // IL (primeros 32 bytes) = clave privada maestra
    // IR (últimos  32 bytes) = chain code maestro
    memcpy(node_out.key,        I,      32);
    memcpy(node_out.chain_code, I + 32, 32);

    return is_valid_privkey(node_out.key);
}

// ── Derivación de hijo ────────────────────────────────────────────────────────
bool bip32_derive_child(const Bip32Node& parent,
                        uint32_t index,
                        Bip32Node& child_out)
{
    uint8_t data[37];  // 1+32 bytes para hardened, o 33 bytes pubkey para normal
    size_t  data_len;

    bool hardened = (index >= 0x80000000u);

    if (hardened) {
        // data = 0x00 || parent_key || index_BE
        data[0] = 0x00;
        memcpy(data + 1, parent.key, 32);
        uint32_t idx_be = htonl(index);
        memcpy(data + 33, &idx_be, 4);
        data_len = 37;
    } else {
        // data = compressed_pubkey(parent_key) || index_BE
        secp256k1_context* ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
        secp256k1_pubkey pubkey;
        if (!secp256k1_ec_pubkey_create(ctx, &pubkey, parent.key)) {
            secp256k1_context_destroy(ctx);
            return false;
        }
        size_t pub_len = 33;
        secp256k1_ec_pubkey_serialize(ctx, data, &pub_len,
                                      &pubkey, SECP256K1_EC_COMPRESSED);
        secp256k1_context_destroy(ctx);

        uint32_t idx_be = htonl(index);
        memcpy(data + 33, &idx_be, 4);
        data_len = 37;
    }

    uint8_t I[64];
    if (!hmac_sha512(parent.chain_code, 32, data, data_len, I)) {
        return false;
    }

    // child_key = (IL + parent_key) mod n  (suma en campo secp256k1)
    memcpy(child_out.key, I, 32);
    secp256k1_context* ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    bool ok = secp256k1_ec_seckey_tweak_add(ctx, child_out.key, parent.key) == 1;
    secp256k1_context_destroy(ctx);

    if (!ok) return false;

    memcpy(child_out.chain_code, I + 32, 32);
    return true;
}

// ── BIP44 completo: m/44'/0'/0'/0/index ──────────────────────────────────────
bool bip44_derive_privkey(const uint8_t seed[64],
                          uint32_t address_index,
                          uint8_t privkey_out[32])
{
    Bip32Node node, child;

    // Master key
    if (!bip32_master_key(seed, node)) return false;

    // m/44'  (purpose, hardened)
    if (!bip32_derive_child(node, 44 | 0x80000000u, child)) return false;
    node = child;

    // m/44'/0'  (coin: Bitcoin, hardened)
    if (!bip32_derive_child(node, 0 | 0x80000000u, child)) return false;
    node = child;

    // m/44'/0'/0'  (account 0, hardened)
    if (!bip32_derive_child(node, 0 | 0x80000000u, child)) return false;
    node = child;

    // m/44'/0'/0'/0  (external chain, normal)
    if (!bip32_derive_child(node, 0, child)) return false;
    node = child;

    // m/44'/0'/0'/0/index  (dirección, normal)
    if (!bip32_derive_child(node, address_index, child)) return false;

    memcpy(privkey_out, child.key, 32);
    return true;
}
"""

with open(os.path.join(JNI_DIR, "bip32.cpp"), "w") as f:
    f.write(bip32_cpp)
print("✓ Creado: bip32.cpp")

# ── 3. Parchear CMakeLists.txt ────────────────────────────────────────────────
cmake_content = open(cmake_path).read()

files_to_add = ["bip32.cpp"]
modified = False

for fname in files_to_add:
    if fname not in cmake_content:
        # Agregar después de mnemonic.cpp si existe, si no después del último .cpp
        if "mnemonic.cpp" in cmake_content:
            cmake_content = cmake_content.replace(
                "mnemonic.cpp",
                "mnemonic.cpp\n        bip32.cpp"
            )
        else:
            # Buscar último .cpp en add_library y agregar después
            lines = cmake_content.splitlines()
            new_lines = []
            added = False
            for i, line in enumerate(lines):
                new_lines.append(line)
                if not added and ".cpp" in line and "add_library" not in line:
                    indent = len(line) - len(line.lstrip())
                    new_lines.append(" " * indent + fname)
                    added = True
            cmake_content = "\n".join(new_lines)
        modified = True

if modified:
    with open(cmake_path, "w") as f:
        f.write(cmake_content)
    print("✓ bip32.cpp agregado a CMakeLists.txt")
else:
    print("→ bip32.cpp ya estaba en CMakeLists.txt")

# ── 4. Verificar que secp256k1 está en CMakeLists ────────────────────────────
cmake_final = open(cmake_path).read()
if "secp256k1" not in cmake_final:
    print("\n⚠️  AVISO: secp256k1 no detectado en CMakeLists.txt")
    print("   Verifica que target_link_libraries incluye secp256k1")
else:
    print("✓ secp256k1 detectado en CMakeLists.txt")

# ── 5. Resumen ────────────────────────────────────────────────────────────────
print("""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Paso 4 completado:

  app/src/main/cpp/bip32.h      ← struct Bip32Node + declaraciones
  app/src/main/cpp/bip32.cpp    ← derivación HMAC-SHA512 BIP32/BIP44

Función principal:
  bip44_derive_privkey(seed[64], address_index, privkey_out[32])

Ruta derivada: m/44'/0'/0'/0/index

Siguiente: Paso 5 (función JNI bruteForceSeeds)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
