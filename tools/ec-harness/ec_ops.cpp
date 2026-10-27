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
    secp256k1_context_destroy(ctx);
    printf(fallos ? "\nHAY FALLOS: %d de %d\n" : "\nTodas las pruebas correctas (%d de %d).\n", fallos ? fallos : pruebas, pruebas);
    return fallos ? 1 : 0;
}
