#include "bip32.h"
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

bool bip_derive_privkey(const uint8_t seed[64],
                        uint32_t purpose,
                        uint32_t address_index,
                        uint8_t privkey_out[32])
{
    Bip32Node node, child;

    if (!bip32_master_key(seed, node)) return false;

    // m/purpose'
    if (!bip32_derive_child(node, purpose | 0x80000000u, child)) return false;
    node = child;
    // m/purpose'/0'   (coin: Bitcoin)
    if (!bip32_derive_child(node, 0 | 0x80000000u, child)) return false;
    node = child;
    // m/purpose'/0'/0' (account 0)
    if (!bip32_derive_child(node, 0 | 0x80000000u, child)) return false;
    node = child;
    // m/purpose'/0'/0'/0 (external chain)
    if (!bip32_derive_child(node, 0, child)) return false;
    node = child;
    // m/purpose'/0'/0'/0/index
    if (!bip32_derive_child(node, address_index, child)) return false;

    memcpy(privkey_out, child.key, 32);
    return true;
}

bool bip_derive_chain(const uint8_t seed[64],
                      uint32_t purpose,
                      Bip32Node& chain_out)
{
    Bip32Node node, child;
    if (!bip32_master_key(seed, node)) return false;
    if (!bip32_derive_child(node, purpose | 0x80000000u, child)) return false;
    node = child;
    if (!bip32_derive_child(node, 0 | 0x80000000u, child)) return false;
    node = child;
    if (!bip32_derive_child(node, 0 | 0x80000000u, child)) return false;
    node = child;
    if (!bip32_derive_child(node, 0, child)) return false;
    chain_out = child;
    return true;
}

bool bip_derive_from_chain(const Bip32Node& chain,
                           uint32_t address_index,
                           uint8_t privkey_out[32])
{
    Bip32Node child;
    if (!bip32_derive_child(chain, address_index, child)) return false;
    memcpy(privkey_out, child.key, 32);
    return true;
}
