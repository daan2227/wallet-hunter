#pragma once
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

/**
 * Derivación genérica m/purpose'/0'/0'/0/address_index.
 *
 * purpose 44 → P2PKH   ("1...")
 * purpose 49 → P2SH-P2WPKH ("3...")
 * purpose 84 → P2WPKH  ("bc1q...")
 *
 * bip44_derive_privkey sólo cubría purpose 44, así que la recuperación no
 * podía encontrar wallets SegWit por muy correcta que fuese la seed.
 */
bool bip_derive_privkey(const uint8_t seed[64],
                        uint32_t purpose,
                        uint32_t address_index,
                        uint8_t privkey_out[32]);

/**
 * Derivación en dos fases, para cuando se prueban varios índices sobre la
 * misma seed. m/purpose'/0'/0'/0 es común a todos, así que rederivarla por
 * índice desperdicia cuatro HMAC-SHA512 de cinco.
 *
 * bip_derive_chain: m/purpose'/0'/0'/0  (una vez por seed)
 * bip_derive_from_chain: .../address_index  (una por índice)
 */
bool bip_derive_chain(const uint8_t seed[64],
                      uint32_t purpose,
                      Bip32Node& chain_out);

bool bip_derive_from_chain(const Bip32Node& chain,
                           uint32_t address_index,
                           uint8_t privkey_out[32]);

/** Igual, con la clave publica comprimida del nodo ya calculada
 *  (bip32_pub33): una multiplicacion escalar menos por indice. */
bool bip_derive_from_chain_pub(const Bip32Node& chain, const uint8_t chain_pub33[33],
                               uint32_t address_index, uint8_t privkey_out[32]);

/** Clave publica comprimida de una privada. */
bool bip32_pub33(const uint8_t key[32], uint8_t out33[33]);

#endif // WALLET_HUNTER_BIP32_H
