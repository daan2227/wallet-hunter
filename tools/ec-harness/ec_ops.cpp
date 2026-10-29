/* ec_ops.h: Silent Payments (BIP352, lado del que envía) contra los vectores OFICIALES de BIP352
 * (datos/bip352_envio.txt, sacado de send_and_receive_test_vectors.json con las entradas de clave
 * simple: P2PKH, P2WPKH y P2TR por clave), la salida Taproot de BIP86 y la derivación pública BIP32
 * frente a la privada de bip32.cpp. */
#include "ec_ops.h"
#include "bip32.h"
#include <openssl/evp.h>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <set>

static int fallos = 0, pruebas = 0;
static std::vector<uint8_t> hx(const std::string &s) {
    std::vector<uint8_t> o(s.size() / 2);
    for (size_t i = 0; i < o.size(); i++) o[i] = (uint8_t)strtol(s.substr(i * 2, 2).c_str(), nullptr, 16);
    return o;
}
static std::string hs(const uint8_t *b, size_t n) { std::string s; char t[3]; for (size_t i = 0; i < n; i++) { snprintf(t, 3, "%02x", b[i]); s += t; } return s; }
static void debe(bool ok, const std::string &q) { pruebas++; if (!ok) { fallos++; printf("MAL  %s\n", q.c_str()); } }

struct Caso { std::string nombre; struct In { std::vector<uint8_t> k; std::string txid; uint32_t vout; int neg; }; std::vector<In> ins;
              std::vector<ecops::Destinatario> r; std::string suma; std::vector<std::set<std::string>> exp; };

int main(int argc, char **argv) {
    std::string ruta = argc > 1 ? argv[1] : "datos/bip352_envio.txt";
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);
    std::ifstream f(ruta); if (!f) { printf("MAL  no está %s\n", ruta.c_str()); return 1; }
    std::vector<Caso> casos; std::string l;
    while (std::getline(f, l)) {
        if (l.empty() || l[0] == '#') continue;
        std::istringstream is(l); std::string t; is >> t;
        if (t == "CASE") { casos.emplace_back(); is >> casos.back().nombre; }
        else if (t == "IN") { Caso::In in; std::string k; is >> k >> in.txid >> in.vout >> in.neg; in.k = hx(k); casos.back().ins.push_back(in); }
        else if (t == "R") { std::string a, b; int n = 1; is >> a >> b >> n; ecops::Destinatario d; memcpy(d.scan, hx(a).data(), 33); memcpy(d.spend, hx(b).data(), 33);
                             for (int i = 0; i < n; i++) casos.back().r.push_back(d); }
        else if (t == "SUM") is >> casos.back().suma;
        else if (t == "EXP") { std::string e; is >> e; std::set<std::string> s; if (e != "-") { std::stringstream ss(e); std::string x; while (std::getline(ss, x, ',')) s.insert(x); } casos.back().exp.push_back(s); }
    }
    for (auto &c : casos) {
        /* a = Σ k_i, negando las Taproot de y impar */
        uint8_t a[32] = {0};
        std::vector<std::vector<uint8_t>> ks; std::vector<bool> neg;
        for (auto &in : c.ins) { ks.push_back(in.k); neg.push_back(in.neg != 0); }
        bool ok = ecops::priv_sumar(ctx, ks, neg, a);            /* suma 0 → inválida: no se puede enviar */
        /* outpoint menor: txid en orden de la transacción (al revés que el mostrado) ‖ vout LE */
        std::vector<uint8_t> menor;
        for (auto &in : c.ins) {
            auto t = hx(in.txid); std::reverse(t.begin(), t.end());
            t.push_back(in.vout & 0xff); t.push_back((in.vout >> 8) & 0xff); t.push_back((in.vout >> 16) & 0xff); t.push_back(in.vout >> 24);
            if (menor.empty() || t < menor) menor = t;
        }
        if (ok && !c.suma.empty()) debe(hs(a, 32) == c.suma, c.nombre + ": suma de privadas");
        std::vector<std::vector<uint8_t>> out;
        bool hecho = ok && ecops::sp_salidas(ctx, a, menor.data(), c.r, out);
        std::set<std::string> got; for (auto &o : out) got.insert(hs(o.data(), 32));
        bool vale = false;
        for (auto &e : c.exp) if ((e.empty() && !hecho) || (hecho && e == got)) vale = true;
        debe(vale, c.nombre + ": salidas");
    }
    printf("BIP352: %zu casos de envío\n", casos.size());

    /* BIP86: m/86'/0'/0'/0/0 de "abandon … about" */
    uint8_t q[32];
    debe(ecops::tr_salida(ctx, hx("cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115").data(), q) &&
         hs(q, 32) == "a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c", "BIP86 salida Taproot");

    /* BIP32: derivar en público desde la cuenta = derivar en privado */
    const char *frase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    uint8_t seed[64]; PKCS5_PBKDF2_HMAC(frase, (int)strlen(frase), (const unsigned char*)"mnemonic", 8, 2048, EVP_sha512(), 64, seed);
    Bip32Node m, n1, n2, cta; bip32_master_key(seed, m);
    bip32_derive_child(m, 0x80000000u | 84, n1); bip32_derive_child(n1, 0x80000000u, n2); bip32_derive_child(n2, 0x80000000u, cta);
    uint8_t cpub[33]; ecops::pub_de_priv(ctx, cta.key, cpub);
    for (uint32_t c = 0; c < 2; c++) for (uint32_t i : {0u, 1u, 7u, 199u}) {
        Bip32Node a1, a2; bip32_derive_child(cta, c, a1); bip32_derive_child(a1, i, a2);
        uint8_t esperado[33], pub[33]; ecops::pub_de_priv(ctx, a2.key, esperado);
        debe(ecops::ckd_pub_ruta(ctx, cpub, cta.chain_code, {c, i}, pub) && memcmp(pub, esperado, 33) == 0,
             "BIP32 público " + std::to_string(c) + "/" + std::to_string(i));
    }
    /* m/84'/0'/0'/0/0 de BIP84 */
    { uint8_t pub[33]; ecops::ckd_pub_ruta(ctx, cpub, cta.chain_code, {0, 0}, pub);
      debe(hs(pub, 33) == "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c", "BIP84 primera pública"); }
    /* BIP340: firmar con los vectores oficiales que traen privada (se compara la firma exacta). */
    { std::ifstream b("datos/bip340.csv"); std::string l; std::getline(b, l); int n = 0;
      while (std::getline(b, l)) {
          std::vector<std::string> c; std::stringstream ss(l); std::string t; while (std::getline(ss, t, ',')) c.push_back(t);
          if (c.size() < 7 || c[1].empty() || c[4].size() != 64) continue;
          auto lower = [](std::string z) { for (auto &ch : z) ch = tolower(ch); return z; };
          uint8_t sig[64], x[32];
          bool ok = ecops::schnorr_firmar(ctx, hx(c[1]).data(), hx(c[4]).data(), hx(c[3]).data(), sig) &&
                    ecops::xonly_de_priv(ctx, hx(c[1]).data(), x) &&
                    hs(sig, 64) == lower(c[5]) && hs(x, 32) == lower(c[2]);
          debe(ok, "BIP340 vector " + c[0]); n++;
      }
      printf("BIP340: %d vectores de firma\n", n); }
    /* BIP340: verificar con TODOS los vectores (válidos e inválidos). */
    { std::ifstream b("datos/bip340.csv"); std::string l; std::getline(b, l); int n = 0;
      while (std::getline(b, l)) {
          std::vector<std::string> c; std::stringstream ss(l); std::string t; while (std::getline(ss, t, ',')) c.push_back(t);
          if (c.size() < 7 || c[4].size() != 64 || c[5].size() != 128 || c[2].size() != 64) continue;   /* mensajes de otro largo: no se usan */
          bool esperado = c[6] == "TRUE";
          bool r = ecops::schnorr_verificar(ctx, hx(c[5]).data(), hx(c[4]).data(), hx(c[2]).data());
          debe(r == esperado, "BIP340 verificar " + c[0]); n++;
      }
      printf("BIP340: %d vectores de verificación\n", n); }
    /* NIP-44: la clave de conversación es HMAC-SHA256("nip44-v2", ecdh_x). */
    { std::ifstream b("datos/nip44_conv.txt"); std::string l; int n = 0;
      while (std::getline(b, l)) {
          if (l.empty() || l[0] == '#') continue;
          std::istringstream is(l); std::string a1, p2, ck; is >> a1 >> p2 >> ck;
          uint8_t sx[32], mac[32]; unsigned int ml = 32;
          bool ok = ecops::ecdh_x(ctx, hx(a1).data(), hx(p2).data(), sx);
          if (ok) HMAC(EVP_sha256(), "nip44-v2", 8, sx, 32, mac, &ml);
          debe(ok && hs(mac, 32) == ck, "NIP-44 conversation key"); n++;
      }
      printf("NIP-44: %d claves de conversación\n", n); }
    /* BIP322: las firmas de los vectores oficiales verifican con su sighash (ECDSA y Schnorr); y ecdsa_verificar
     * rechaza mensaje cambiado, s alto (LOW_S) y DER no estricto (STRICTENC), como pide la especificación. */
    { std::ifstream b("datos/bip322_firmas.txt"); std::string l; int n = 0;
      while (std::getline(b, l)) {
          if (l.empty() || l[0] == '#') continue;
          std::istringstream is(l); std::string tipo, p, h, s; is >> tipo >> p >> h >> s;
          auto P = hx(p), H = hx(h), S = hx(s);
          if (tipo == "ECDSA") {
              debe(ecops::ecdsa_verificar(ctx, S.data(), S.size(), H.data(), P.data(), P.size()), "BIP322 ECDSA vector");
              auto H2 = H; H2[0] ^= 1;
              debe(!ecops::ecdsa_verificar(ctx, S.data(), S.size(), H2.data(), P.data(), P.size()), "BIP322 ECDSA mensaje cambiado");
          } else {
              debe(ecops::schnorr_verificar(ctx, S.data(), H.data(), P.data()), "BIP322 Schnorr vector");
              auto H2 = H; H2[31] ^= 1;
              debe(!ecops::schnorr_verificar(ctx, S.data(), H2.data(), P.data()), "BIP322 Schnorr mensaje cambiado");
          }
          n++;
      }
      /* Firma propia: vale; con s alto (n - s) no; con un 0x00 de relleno en r (DER no mínimo) no. */
      uint8_t k[32]; for (int i = 0; i < 32; i++) k[i] = (uint8_t)(i * 7 + 1);
      uint8_t m[32]; for (int i = 0; i < 32; i++) m[i] = (uint8_t)(0xa0 ^ i);
      uint8_t pub[33]; ecops::pub_de_priv(ctx, k, pub);
      secp256k1_ecdsa_signature sg; secp256k1_ecdsa_sign(ctx, &sg, m, k, nullptr, nullptr);
      uint8_t der[72]; size_t nd = sizeof der; secp256k1_ecdsa_signature_serialize_der(ctx, der, &nd, &sg);
      debe(ecops::ecdsa_verificar(ctx, der, nd, m, pub, 33), "ECDSA propia");
      uint8_t c64[64]; secp256k1_ecdsa_signature_serialize_compact(ctx, c64, &sg);
      { /* s → n − s */
        static const uint8_t N[32] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFE,
                                      0xBA,0xAE,0xDC,0xE6,0xAF,0x48,0xA0,0x3B,0xBF,0xD2,0x5E,0x8C,0xD0,0x36,0x41,0x41};
        int borrow = 0; for (int i = 31; i >= 0; i--) { int v = N[i] - c64[32 + i] - borrow; borrow = v < 0; c64[32 + i] = (uint8_t)(v & 0xff); }
        secp256k1_ecdsa_signature alto; secp256k1_ecdsa_signature_parse_compact(ctx, &alto, c64);
        uint8_t d2[72]; size_t n2 = sizeof d2; secp256k1_ecdsa_signature_serialize_der(ctx, d2, &n2, &alto);
        debe(!ecops::ecdsa_verificar(ctx, d2, n2, m, pub, 33), "ECDSA s alto rechazada");
      }
      { /* r con un 0x00 de más delante: DER válido para BER, no para DER estricto */
        std::vector<uint8_t> d(der, der + nd); size_t rl = d[3];
        d.insert(d.begin() + 4, 0x00); d[3] = (uint8_t)(rl + 1); d[1] = (uint8_t)(d[1] + 1);
        if (d[5] < 0x80) debe(!ecops::ecdsa_verificar(ctx, d.data(), d.size(), m, pub, 33), "ECDSA DER no estricto rechazada");
      }
      /* La ruta Taproot de FirmaBip322: d' = (±d) + H_TapTweak(x(P)) con priv_sumar; su x-only tiene que ser la
       * clave de salida BIP86 (tr_salida) y su firma BIP340 tiene que verificar por ella. Con claves de y par e impar. */
      int impares = 0;
      for (int c = 1; c <= 40; c++) {
          uint8_t d[32]; for (int i = 0; i < 32; i++) d[i] = (uint8_t)(c * 31 + i * 17 + (i * c) % 251);
          uint8_t p33[33]; if (!ecops::pub_de_priv(ctx, d, p33)) continue;
          bool impar = p33[0] == 3; impares += impar;
          uint8_t t[32]; ecops::tagged("TapTweak", p33 + 1, 32, t);
          std::vector<std::vector<uint8_t>> ks = { std::vector<uint8_t>(d, d + 32), std::vector<uint8_t>(t, t + 32) };
          uint8_t dq[32], xq[32], q[32], sig[64], aux[32] = {7};
          bool ok = ecops::priv_sumar(ctx, ks, { impar, false }, dq) && ecops::xonly_de_priv(ctx, dq, xq) && ecops::tr_salida(ctx, p33 + 1, q);
          debe(ok && memcmp(xq, q, 32) == 0, "Taproot: la privada ajustada da la clave de salida");
          debe(ok && ecops::schnorr_firmar(ctx, dq, m, aux, sig) && ecops::schnorr_verificar(ctx, sig, m, q), "Taproot: firma por la clave de salida");
      }
      debe(impares > 5 && impares < 35, "Taproot: hubo claves de y par y de y impar");
      printf("BIP322: %d firmas de vectores + casos negativos + 40 ajustes Taproot\n", n); }
    secp256k1_context_destroy(ctx);
    printf(fallos ? "\nHAY FALLOS: %d de %d\n" : "\nTodas las pruebas correctas (%d de %d).\n", fallos ? fallos : pruebas, pruebas);
    return fallos ? 1 : 0;
}
