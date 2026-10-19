#pragma once
/* Direcciones de una clave WIF, de todos los tipos que tiene esa clave.
 *
 * Antes la cartera de un WIF sólo calculaba la P2PKH. Una clave comprimida
 * también controla las direcciones bc1q (P2WPKH), las 3... (P2SH-P2WPKH) y las
 * bc1p (P2TR). Si alguien mandaba fondos a una de esas, la cartera decía "0" y
 * no había ningún aviso. Y en testnet las direcciones salían con el prefijo
 * de mainnet.
 *
 * Aquí se saca la clave de la WIF (wif_decode, con el checksum entero) y se
 * escriben todas sus direcciones. Va en un fichero propio para poder probarlo
 * en el escritorio contra los vectores publicados (tools/ec-harness/wif.cpp).
 */
#include <stdint.h>
#include <string.h>
#include <string>
#include <openssl/sha.h>
#include <openssl/ripemd.h>
#include <secp256k1.h>
#include "addr_encode.h"

static const char WIF_B58[] = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

/*
 * Decodifica un WIF validándolo entero: la versión tiene que ser la de la red
 * pedida (0x80 mainnet, 0xEF testnet) | privkey(32) | [0x01 si comprimida] |
 * checksum(4). Una WIF de la otra red no vale: la misma clave daría direcciones
 * que no son de esta cartera.
 * El checksum son los 4 primeros bytes de SHA256(SHA256(resto)).
 * comprimida: true para K/L (marca 0x01), false para 5... / 9... / c...
 */
static bool wif_decode(const char *wif, uint8_t *privkey, bool *comprimida = nullptr,
                       bool testnet = false) {
    size_t wlen = strlen(wif);
    if (wlen < 50 || wlen > 53) return false;

    uint8_t buf[64] = {0};
    for (size_t i = 0; i < wlen; i++) {
        const char *p = strchr(WIF_B58, wif[i]);
        if (!p) return false;
        int carry = (int)(p - WIF_B58);
        for (int j = 63; j >= 0; j--) {
            carry += 58 * buf[j];
            buf[j] = (uint8_t)(carry & 0xff);
            carry >>= 8;
        }
        if (carry) return false;          /* no cabe en 64 bytes: no es un WIF */
    }

    /* En Base58 cada '1' inicial es un byte 0x00 por delante del número. */
    size_t zeros = 0;
    while (zeros < wlen && wif[zeros] == '1') zeros++;
    size_t first = 0;
    while (first < 64 && buf[first] == 0) first++;

    size_t plen = zeros + (64 - first);
    if (plen != 37 && plen != 38) return false;

    uint8_t payload[38] = {0};
    memcpy(payload + zeros, buf + first, 64 - first);

    if (payload[0] != (testnet ? 0xEF : 0x80)) return false;
    if (plen == 38 && payload[33] != 0x01) return false;  /* marca de comprimida */

    uint8_t ck[32];
    SHA256_CTX sc;
    SHA256_Init(&sc); SHA256_Update(&sc, payload, plen - 4); SHA256_Final(ck, &sc);
    SHA256_Init(&sc); SHA256_Update(&sc, ck, 32);           SHA256_Final(ck, &sc);
    if (memcmp(ck, payload + plen - 4, 4) != 0) return false;

    memcpy(privkey, payload + 1, 32);
    if (comprimida) *comprimida = (plen == 38);
    return true;
}

/* hash160 = RIPEMD160(SHA256(x)) */
static void wif_hash160(const uint8_t *x, size_t n, uint8_t *out) {
    uint8_t s[32];
    SHA256(x, n, s);
    RIPEMD160(s, 32, out);
}

/* Clave tweakeada de la salida P2TR (BIP341, sin árbol de scripts): la clave
   interna es la x-only con paridad par, y se le suma t·G con
   t = TaggedHash("TapTweak", x). */
static bool wif_taproot_xonly(secp256k1_context *ctx, const uint8_t *pub33, uint8_t *out32) {
    uint8_t th[32]; SHA256((const uint8_t*)"TapTweak", 8, th);
    SHA256_CTX c; SHA256_Init(&c);
    SHA256_Update(&c, th, 32); SHA256_Update(&c, th, 32); SHA256_Update(&c, pub33 + 1, 32);
    uint8_t tweak[32]; SHA256_Final(tweak, &c);

    uint8_t even[33]; even[0] = 0x02; memcpy(even + 1, pub33 + 1, 32);
    secp256k1_pubkey pk;
    if (!secp256k1_ec_pubkey_parse(ctx, &pk, even, 33)) return false;
    if (!secp256k1_ec_pubkey_tweak_add(ctx, &pk, tweak)) return false;
    uint8_t out33[33]; size_t l = 33;
    secp256k1_ec_pubkey_serialize(ctx, out33, &l, &pk, SECP256K1_EC_COMPRESSED);
    memcpy(out32, out33 + 1, 32);
    return true;
}

/*
 * Todas las direcciones de la clave de un WIF, una por línea: "TIPO direccion".
 * Tipos: P2PKH (siempre), y si la clave es comprimida, P2WPKH, P2SH-P2WPKH y
 * P2TR. Cadena vacía si el WIF no es válido.
 */
static std::string wif_direcciones(const char *wif, bool testnet) {
    uint8_t priv[32] = {0};
    bool comprimida = true;
    if (!wif_decode(wif, priv, &comprimida, testnet)) return "";

    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string out;
    if (secp256k1_ec_seckey_verify(ctx, priv)) {
        secp256k1_pubkey pk;
        if (secp256k1_ec_pubkey_create(ctx, &pk, priv)) {
            uint8_t pub[65]; size_t n = comprimida ? 33 : 65;
            secp256k1_ec_pubkey_serialize(ctx, pub, &n, &pk,
                comprimida ? SECP256K1_EC_COMPRESSED : SECP256K1_EC_UNCOMPRESSED);
            uint8_t h160[20];
            wif_hash160(pub, n, h160);
            char a[MAX_ADDR] = {0};

            h160_to_addr(h160, a, testnet);
            out += "P2PKH "; out += a; out += "\n";
            if (comprimida) {
                h160_to_bech32(h160, a, testnet);
                out += "P2WPKH "; out += a; out += "\n";
                h160_to_p2sh(h160, a, testnet);
                out += "P2SH-P2WPKH "; out += a; out += "\n";
                uint8_t tx[32];
                if (wif_taproot_xonly(ctx, pub, tx)) {
                    xonly_to_p2tr(tx, a, testnet);
                    out += "P2TR "; out += a; out += "\n";
                }
            }
        }
    }
    secp256k1_context_destroy(ctx);
    return out;
}

/* Hex de 64 caracteres a 32 bytes. false si no lo es. */
static bool wif_hex32(const std::string &h, uint8_t *out) {
    if (h.size() != 64) return false;
    for (int i = 0; i < 32; i++) {
        int v = 0;
        for (int j = 0; j < 2; j++) {
            char c = h[2 * i + j]; int d;
            if (c >= '0' && c <= '9') d = c - '0';
            else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
            else return false;
            v = v * 16 + d;
        }
        out[i] = (uint8_t)v;
    }
    return true;
}

/*
 * Importa lo que se pega en la pantalla: una WIF comprimida (K/L, o c en
 * testnet), una WIF sin comprimir (5, o 9 en testnet), o una clave privada en
 * 64 hex. Hex no dice si la clave es comprimida, así que se usa
 * [hexComprimida]. Devuelve "WIF|direccion|1" (el 1 si la clave es comprimida),
 * con la WIF normalizada, o "" si no vale. La dirección es la P2PKH de esa
 * clave, que es la que se gasta.
 */
static std::string wif_importar(const std::string &entrada, bool testnet, bool hexComprimida) {
    std::string s;
    for (char c : entrada) if (c != ' ' && c != '\n' && c != '\t' && c != '\r') s += c;
    uint8_t priv[32] = {0};
    bool comp = true;
    if (s.size() == 64) {
        if (!wif_hex32(s, priv)) return "";
        comp = hexComprimida;
    } else if (!wif_decode(s.c_str(), priv, &comp, testnet)) {
        return "";
    }
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string out;
    if (secp256k1_ec_seckey_verify(ctx, priv)) {
        /* WIF: versión | privkey | [01 si comprimida], con checksum en b58enc. */
        uint8_t payload[34]; int n = 0;
        payload[n++] = testnet ? 0xEF : 0x80;
        memcpy(payload + n, priv, 32); n += 32;
        if (comp) payload[n++] = 0x01;
        char wif[128] = {0};
        b58enc(payload, n, wif, sizeof wif);

        secp256k1_pubkey pk;
        if (secp256k1_ec_pubkey_create(ctx, &pk, priv)) {
            uint8_t pub[65]; size_t l = comp ? 33 : 65;
            secp256k1_ec_pubkey_serialize(ctx, pub, &l, &pk,
                comp ? SECP256K1_EC_COMPRESSED : SECP256K1_EC_UNCOMPRESSED);
            uint8_t h160[20];
            wif_hash160(pub, l, h160);
            char a[MAX_ADDR] = {0};
            h160_to_addr(h160, a, testnet);
            out = std::string(wif) + "|" + a + "|" + (comp ? "1" : "0");
        }
    }
    secp256k1_context_destroy(ctx);
    return out;
}
