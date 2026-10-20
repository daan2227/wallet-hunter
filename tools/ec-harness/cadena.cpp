/* Cadena de cabeceras: encadenado, dificultad, tiempo y puntos de control.
 *  - Génesis y bloques 1..3 reales de la red principal: hash conocido y cadena aceptada.
 *  - Ajuste de dificultad contra los vectores de Bitcoin Core (src/test/pow_tests.cpp).
 *  - Una cadena propia con dificultad mínima y un ajuste cada 10 bloques, para ver el
 *    cambio de nBits en la frontera sin tener que minar a dificultad real.
 *  - Lo que tiene que rechazarse: no encadena, nonce cambiado, nBits cambiados (en medio
 *    del periodo y en la frontera), tiempo no mayor que la mediana, tiempo futuro y un
 *    punto de control que no coincide.
 */
#include "cadena_btc.h"
#include "genesis_btc.h"
#include <stdio.h>
#include <string>

using namespace btc;

static int fallos = 0;
static void ok(bool c, const char *nombre) {
    printf("%s  %s\n", c ? "OK  " : "MAL ", nombre);
    if (!c) fallos++;
}

static std::vector<uint8_t> desde_hex(const std::string &h) {
    std::vector<uint8_t> v(h.size() / 2);
    for (size_t i = 0; i < v.size(); i++) v[i] = (uint8_t)std::stoi(h.substr(2 * i, 2), nullptr, 16);
    return v;
}
static std::string a_hex(const uint8_t *p, size_t n) {
    std::string s; char t[3];
    for (size_t i = 0; i < n; i++) { snprintf(t, 3, "%02x", p[i]); s += t; }
    return s;
}
/* Hash en el orden de los exploradores (invertido). */
static std::string mostrar(const uint8_t h[32]) {
    uint8_t r[32]; for (int i = 0; i < 32; i++) r[i] = h[31 - i];
    return a_hex(r, 32);
}

static std::vector<uint8_t> armar(int32_t ver, const uint8_t prev[32], const uint8_t raiz[32],
                                  uint32_t t, uint32_t bits, uint32_t nonce) {
    std::vector<uint8_t> v(80);
    auto p32 = [&](size_t o, uint32_t x) { for (int i = 0; i < 4; i++) v[o + i] = (uint8_t)(x >> (8 * i)); };
    p32(0, (uint32_t)ver); memcpy(&v[4], prev, 32); memcpy(&v[36], raiz, 32);
    p32(68, t); p32(72, bits); p32(76, nonce);
    return v;
}
static std::vector<uint8_t> interno(const char *mostrar_hex) {
    std::vector<uint8_t> v = desde_hex(mostrar_hex);
    return std::vector<uint8_t>(v.rbegin(), v.rend());
}

/* Mina una cabecera con dificultad mínima: prueba nonces hasta cumplir el trabajo. */
static std::vector<uint8_t> minar(const uint8_t prev[32], uint32_t t, uint32_t bits, uint32_t sal = 0) {
    uint8_t raiz[32]; memset(raiz, 0, 32); raiz[0] = (uint8_t)sal; raiz[1] = (uint8_t)(t >> 8);
    for (uint32_t n = 0;; n++) {
        auto h = armar(0x20000000, prev, raiz, t, bits, n);
        if (prueba_de_trabajo(h.data())) return h;
    }
}
static void hash_de(const std::vector<uint8_t> &h, uint8_t out[32]) { hash_cabecera(h.data(), out); }

int main() {
    const uint32_t AHORA = 1700000000;

    /* --- bloques reales --- */
    struct Real { const char *prev, *raiz, *hash; uint32_t t, nonce; };
    const Real reales[] = {
        {"0000000000000000000000000000000000000000000000000000000000000000",
         "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b",
         "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", 1231006505, 2083236893},
        {"000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
         "0e3e2357e806b6cdb1f70b54c3a3a17b6714ee1f0e68bebb44a74b1efd512098",
         "00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048", 1231469665, 2573394689},
        {"00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048",
         "9b0fc92260312ce44e74ef369f5c66bbb85848f2eddd5a7a1cde251e54ccfdd5",
         "000000006a625f06636b8bb6ac7b960a8d03705d1ace08b1a19da3fdcc99ddbd", 1231469744, 1639830024},
        {"000000006a625f06636b8bb6ac7b960a8d03705d1ace08b1a19da3fdcc99ddbd",
         "999e1c837c76a1b7fbb7e57baf87b309960f5ffefbf2a9b95dd890602272f644",
         "0000000082b5015589a3fdf2d4baff403e6f0be035a5d9742c1cae6295464449", 1231470173, 1844305925},
    };
    std::vector<std::vector<uint8_t>> rc;
    bool hashes_ok = true;
    for (auto &r : reales) {
        auto prev = interno(r.prev), raiz = interno(r.raiz);
        auto h = armar(1, prev.data(), raiz.data(), r.t, 0x1d00ffff, r.nonce);
        uint8_t hh[32]; hash_de(h, hh);
        if (mostrar(hh) != r.hash) hashes_ok = false;
        rc.push_back(h);
    }
    ok(hashes_ok, "génesis y bloques 1..3: hash conocido");

    {   /* El génesis incorporado a la app es el de la red principal. */
        uint8_t hh[32]; hash_cabecera(GENESIS_PRINCIPAL, hh);
        ok(mostrar(hh) == "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", "génesis incorporado: hash de la red principal");
        ok(memcmp(GENESIS_PRINCIPAL, rc[0].data(), 80) == 0, "...y es el mismo que armamos a partir de sus campos");
    }
    ParamsRed pr = red_principal();
    {
        Cadena c = Cadena::desde_genesis(pr, rc[0].data());
        bool todo = true;
        for (int i = 1; i < 4; i++) todo = todo && c.anadir(rc[i].data(), AHORA) == ErrCabecera::Ok;
        ok(todo && c.punta().altura == 3, "cadena real del génesis al bloque 3 aceptada");
        ok(mostrar(c.punta().hash) == reales[3].hash, "la punta es el bloque 3");
    }
    {
        Cadena c = Cadena::desde_genesis(pr, rc[0].data());
        ok(c.anadir(rc[2].data(), AHORA) == ErrCabecera::NoEncadena, "rechaza un bloque que no enlaza (salto)");
        ok(c.anadir(rc[1].data(), AHORA) == ErrCabecera::Ok, "...y sigue aceptando el que sí enlaza");
        ok(c.anadir(rc[1].data(), AHORA) == ErrCabecera::NoEncadena, "rechaza repetir el mismo bloque");
    }
    {
        auto m = rc[1]; m[76] ^= 1;   /* nonce cambiado */
        Cadena c = Cadena::desde_genesis(pr, rc[0].data());
        ok(c.anadir(m.data(), AHORA) == ErrCabecera::SinTrabajo, "rechaza un nonce cambiado");
        auto b = rc[1]; b[72] = 0xfe;   /* nBits distintos a los esperados */
        ok(c.anadir(b.data(), AHORA) == ErrCabecera::BitsMalos, "rechaza nBits que no son los esperados");
        ok(c.punta().altura == 0, "tras los rechazos la punta no se movió");
    }
    {
        Cadena c = Cadena::desde_genesis(pr, rc[0].data());
        uint8_t h1[32]; hash_de(rc[1], h1);
        c.exigir_hash(1, h1);
        ok(c.anadir(rc[1].data(), AHORA) == ErrCabecera::Ok, "punto de control que coincide: acepta");
        Cadena d = Cadena::desde_genesis(pr, rc[0].data());
        uint8_t mal[32]; memcpy(mal, h1, 32); mal[0] ^= 1;
        d.exigir_hash(1, mal);
        ok(d.anadir(rc[1].data(), AHORA) == ErrCabecera::Checkpoint, "punto de control que no coincide: rechaza");
        Cadena e = Cadena::desde_genesis(pr, rc[0].data());
        ok(e.anadir(rc[1].data(), 1231469665 - 7201 - 1) == ErrCabecera::TiempoAlto,
           "rechaza un bloque a más de 2 horas en el futuro");
    }

    /* --- ajuste de dificultad: vectores de Core (pow_tests.cpp) --- */
    ok(siguiente_bits(pr, 0x1d00ffff, 1261130161, 1262152739) == 0x1d00d86a, "Core: get_next_work");
    ok(siguiente_bits(pr, 0x1d00ffff, 1231006505, 1233061996) == 0x1d00ffff, "Core: tope de dificultad mínima");
    ok(siguiente_bits(pr, 0x1c05a3f4, 1279008237, 1279297671) == 0x1c0168fd, "Core: límite inferior (x1/4)");
    ok(siguiente_bits(pr, 0x1c387f6f, 1263163443, 1269211443) == 0x1d00e1fd, "Core: límite superior (x4)");

    /* --- cadena propia con ajuste cada 10 bloques --- */
    ParamsRed pp = {0x207fffff, 10, 6000, true};
    {
        auto gen = minar((const uint8_t *)"\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0", 1000, pp.limite_bits);
        Cadena c = Cadena::desde_genesis(pp, gen.data());
        std::vector<uint8_t> ult = gen;
        bool todo = true; uint32_t t = 1000, bits = pp.limite_bits;
        uint32_t tiempo_h0 = 1000;
        for (int h = 1; h <= 25; h++) {
            t += 300;   /* el doble de rápido que lo previsto (600 s) */
            if (h % 10 == 0) {
                /* frontera: el periodo previo se midió del bloque 0 (o 10) al anterior */
                bits = siguiente_bits(pp, bits, tiempo_h0, t - 300);
                tiempo_h0 = t;
            }
            uint8_t hp[32]; hash_de(ult, hp);
            ult = minar(hp, t, bits);
            if (c.anadir(ult.data(), t) != ErrCabecera::Ok) { todo = false; break; }
        }
        ok(todo && c.punta().altura == 25, "cadena propia de 25 bloques con 2 ajustes aceptada");
        ok(c.punta().bits != pp.limite_bits, "la dificultad subió tras ir demasiado deprisa");

        /* Rechazos en la frontera y a mitad de periodo. */
        Cadena d = Cadena::desde_genesis(pp, gen.data());
        std::vector<uint8_t> u = gen; uint32_t tt = 1000;
        for (int h = 1; h <= 9; h++) {
            tt += 300; uint8_t hp[32]; hash_de(u, hp);
            u = minar(hp, tt, pp.limite_bits);
            d.anadir(u.data(), tt);
        }
        uint8_t hp9[32]; hash_de(u, hp9);
        auto mismos = minar(hp9, tt + 300, pp.limite_bits);   /* no sube la dificultad en la frontera */
        ok(d.anadir(mismos.data(), tt + 300) == ErrCabecera::BitsMalos, "rechaza en la frontera no ajustar nBits");
        uint32_t nb = siguiente_bits(pp, pp.limite_bits, 1000, tt);
        auto bien = minar(hp9, tt + 300, nb);
        ok(d.anadir(bien.data(), tt + 300) == ErrCabecera::Ok, "acepta en la frontera los nBits ajustados");

        Cadena e = Cadena::desde_genesis(pp, gen.data());
        uint8_t hg[32]; hash_de(gen, hg);
        auto medio = minar(hg, 1300, 0x207ffff0);   /* bits distintos a mitad de periodo */
        ok(e.anadir(medio.data(), 1300) == ErrCabecera::BitsMalos, "rechaza cambiar nBits a mitad de periodo");

        auto igual = minar(hg, 1000, pp.limite_bits);   /* tiempo no mayor que la mediana */
        ok(e.anadir(igual.data(), 1300) == ErrCabecera::TiempoBajo, "rechaza tiempo no mayor que la mediana");
        auto antes = minar(hg, 999, pp.limite_bits);
        ok(e.anadir(antes.data(), 1300) == ErrCabecera::TiempoBajo, "rechaza tiempo anterior al padre");
    }
    {
        /* Mediana de 11: un padre con tiempo adelantado no sube la barra por sí solo. */
        auto gen = minar((const uint8_t *)"\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0", 1000, pp.limite_bits);
        ParamsRed sin = {0x207fffff, 2016, 1209600, false};
        Cadena c = Cadena::desde_genesis(sin, gen.data());
        std::vector<uint8_t> u = gen; uint32_t t = 1000;
        for (int h = 1; h <= 12; h++) {
            t += 100; uint8_t hp[32]; hash_de(u, hp);
            u = minar(hp, t, sin.limite_bits);
            c.anadir(u.data(), t + 10);
        }
        uint8_t hp[32]; hash_de(u, hp);
        /* mediana de los últimos 11 = tiempo del 7º más reciente; un tiempo apenas mayor que ella pasa
           aunque sea menor que el del padre (regla de Core). */
        auto menor_que_padre = minar(hp, t - 300, sin.limite_bits);
        ok(c.anadir(menor_que_padre.data(), t + 10) == ErrCabecera::Ok,
           "acepta tiempo menor que el padre si supera la mediana (regla de Core)");
    }

    /* bits_a_objetivo debe rechazar el formato negativo y el desbordado. */
    uint8_t tmp[32];
    ok(!bits_a_objetivo(0x01fedcba, tmp) && !bits_a_objetivo(0x21000001, tmp), "nBits negativo o desbordado: rechazado");

    printf(fallos ? "\n*** cadena FALLA ***\n" : "\nTODO CORRECTO\n");
    return fallos ? 1 : 0;
}
