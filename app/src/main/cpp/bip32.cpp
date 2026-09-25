#include "bip32.h"
#include "sha512.h"
#include <secp256k1.h>
#include <cstring>

// Constante BIP32
static const char* BIP32_SEED_KEY = "Bitcoin seed";

// ── Un solo contexto para todo ───────────────────────────────────────────────
// Antes cada llamada creaba y destruia el suyo: unas 16 veces por candidato en
// la recuperacion, con su reserva de memoria cada vez. Las funciones que se
// usan aqui toman el contexto como const, asi que compartirlo entre hilos vale.
static secp256k1_context* ctx_bip32() {
    static secp256k1_context* c = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    return c;
}

// ── Verificar que la clave es válida en secp256k1 ────────────────────────────
static bool is_valid_privkey(const uint8_t key[32]) {
    return secp256k1_ec_seckey_verify(ctx_bip32(), key) == 1;
}

// ── Master key desde seed ────────────────────────────────────────────────────
bool bip32_master_key(const uint8_t seed[64], Bip32Node& node_out)
{
    uint8_t I[64];
    hmac_sha512((const uint8_t*)BIP32_SEED_KEY, strlen(BIP32_SEED_KEY), seed, 64, I);

    // IL (primeros 32 bytes) = clave privada maestra
    // IR (últimos  32 bytes) = chain code maestro
    memcpy(node_out.key,        I,      32);
    memcpy(node_out.chain_code, I + 32, 32);

    return is_valid_privkey(node_out.key);
}

// ── Derivación de hijo ────────────────────────────────────────────────────────
// parent_pub33: la clave publica comprimida del padre si ya se tiene (para los
// hijos normales), o NULL para calcularla aqui. Al probar varios indices bajo
// el mismo nodo, calcularla una vez ahorra una multiplicacion escalar por
// indice.
static bool derive_child_pub(const Bip32Node& parent, const uint8_t* parent_pub33,
                             uint32_t index, Bip32Node& child_out)
{
    uint8_t data[37];
    if (index >= 0x80000000u) {
        data[0] = 0x00;
        memcpy(data + 1, parent.key, 32);
    } else if (parent_pub33) {
        memcpy(data, parent_pub33, 33);
    } else {
        if (!bip32_pub33(parent.key, data)) return false;
    }
    data[33] = (uint8_t)(index >> 24); data[34] = (uint8_t)(index >> 16);
    data[35] = (uint8_t)(index >> 8);  data[36] = (uint8_t)index;

    uint8_t I[64];
    hmac_sha512(parent.chain_code, 32, data, 37, I);

    // child_key = (IL + parent_key) mod n
    memcpy(child_out.key, I, 32);
    if (secp256k1_ec_seckey_tweak_add(ctx_bip32(), child_out.key, parent.key) != 1) return false;
    memcpy(child_out.chain_code, I + 32, 32);
    return true;
}

bool bip32_pub33(const uint8_t key[32], uint8_t out33[33])
{
    secp256k1_pubkey pubkey;
    if (!secp256k1_ec_pubkey_create(ctx_bip32(), &pubkey, key)) return false;
    size_t len = 33;
    secp256k1_ec_pubkey_serialize(ctx_bip32(), out33, &len, &pubkey, SECP256K1_EC_COMPRESSED);
    return true;
}

bool bip32_derive_child(const Bip32Node& parent,
                        uint32_t index,
                        Bip32Node& child_out)
{
    return derive_child_pub(parent, nullptr, index, child_out);
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

bool bip_derive_from_chain_pub(const Bip32Node& chain, const uint8_t chain_pub33[33],
                               uint32_t address_index, uint8_t privkey_out[32])
{
    Bip32Node child;
    if (!derive_child_pub(chain, chain_pub33, address_index, child)) return false;
    memcpy(privkey_out, child.key, 32);
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
