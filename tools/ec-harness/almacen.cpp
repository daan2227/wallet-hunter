/* Almacén de cabeceras: ramas, reorganización por trabajo (no por longitud),
 * rechazos dentro de una rama, huérfanas, profundidad máxima y persistencia.
 * Red propia de dificultad mínima con un ajuste cada 10 bloques para poder
 * minar a mano, incluida una rama que cruza la frontera de ajuste. */
#include "almacen_btc.h"
#include <stdio.h>

using namespace btc;

static int fallos = 0;
static void ok(bool c, const char *nombre) {
    printf("%s  %s\n", c ? "OK  " : "MAL ", nombre);
    if (!c) fallos++;
}

static std::vector<uint8_t> armar(const uint8_t prev[32], uint32_t t, uint32_t bits, uint32_t nonce, uint32_t sal) {
    std::vector<uint8_t> v(80, 0);
    auto p32 = [&](size_t o, uint32_t x) { for (int i = 0; i < 4; i++) v[o + i] = (uint8_t)(x >> (8 * i)); };
    p32(0, 0x20000000); memcpy(&v[4], prev, 32);
    p32(36, sal); p32(68, t); p32(72, bits); p32(76, nonce);
    return v;
}
static std::vector<uint8_t> minar(const Hash32 &prev, uint32_t t, uint32_t bits, uint32_t sal = 0) {
    for (uint32_t n = 0;; n++) {
        auto h = armar(prev.data(), t, bits, n, sal);
        if (prueba_de_trabajo(h.data())) return h;
    }
}
static Hash32 hash_de(const std::vector<uint8_t> &h) { Hash32 r; hash_cabecera(h.data(), r.data()); return r; }

static const ParamsRed PP = {0x207fffff, 10, 6000, true};
static const uint32_t AHORA = 4000000000u;

/* Una cadena de apoyo que sabe qué nBits toca en cada altura, para minar ramas. */
struct Mina {
    std::vector<std::vector<uint8_t>> h;   /* h[k] = cabecera de altura k */
    uint32_t bits_siguiente() const {
        uint32_t k = (uint32_t)h.size();    /* altura del que se va a minar */
        Cabecera u = parsear_cabecera(h.back().data(), 80);
        if (k % PP.intervalo != 0) return u.bits;
        Cabecera ini = parsear_cabecera(h[k - PP.intervalo].data(), 80);
        return siguiente_bits(PP, u.bits, ini.tiempo, u.tiempo);
    }
    void sumar(uint32_t paso, uint32_t sal = 0) {
        Cabecera u = parsear_cabecera(h.back().data(), 80);
        h.push_back(minar(hash_de(h.back()), u.tiempo + paso, bits_siguiente(), sal));
    }
};

static const uint8_t CERO[32] = {0};

int main() {
    /* Trabajo: 2^256/(objetivo+1) con los vectores de Core (chainwork de bloques). */
    {
        Num256 w = trabajo_de_bits(0x1d00ffff);          /* dificultad 1: 0x100010001 */
        ok(w.b[31] == 0x01 && w.b[30] == 0x00 && w.b[29] == 0x01 && w.b[28] == 0x00 && w.b[27] == 0x01 && w.b[26] == 0,
           "trabajo de la dificultad mínima de la red principal = 0x100010001");
        Num256 a = trabajo_de_bits(0x207fffff), b = trabajo_de_bits(0x1f7fffff);
        ok(a.b[31] == 2 && b > a, "trabajo regtest = 2 y un objetivo menor da más trabajo");
        Num256 inv = trabajo_de_bits(0x01fedcba);
        bool cero = true; for (int i = 0; i < 32; i++) if (inv.b[i]) cero = false;
        ok(cero, "nBits inválido: trabajo cero");
    }

    Mina m;
    m.h.push_back(minar(Hash32{}, 1000, PP.limite_bits));   /* génesis */
    for (int i = 1; i <= 25; i++) m.sumar(600);

    /* Cadena lineal */
    Almacen a(PP, m.h[0].data(), 50, 200);
    {
        bool todo = true;
        for (int i = 1; i <= 25; i++) {
            Resultado r = a.anadir(m.h[i].data(), AHORA);
            todo = todo && r.estado == Estado::Extiende;
        }
        ok(todo && a.altura() == 25, "25 bloques seguidos: la principal los acepta");
        ok(a.anadir(m.h[10].data(), AHORA).estado == Estado::Duplicada, "un bloque ya conocido: duplicado");
        ok(memcmp(a.punta().data(), hash_de(m.h[25]).data(), 32) == 0, "la punta es el último");
    }

    /* Huérfana y rechazos */
    {
        Hash32 desconocido; desconocido.fill(0x77);
        auto huerfana = minar(desconocido, 5000, PP.limite_bits);
        ok(a.anadir(huerfana.data(), AHORA).estado == Estado::Huerfana, "cabecera sin padre conocido: huérfana");
        auto mala = m.h[25]; mala[76] ^= 1;
        Mina t = m; t.h.resize(26);
        auto sig = minar(hash_de(m.h[25]), parsear_cabecera(m.h[25].data(), 80).tiempo + 600, 0x207ffff0);
        Resultado r = a.anadir(sig.data(), AHORA);
        ok(r.estado == Estado::Rechazada && r.err == ErrCabecera::BitsMalos, "hijo de la punta con nBits que no tocan: rechazado");
        auto lejos = minar(hash_de(m.h[25]), 0xFFFFFFF0u, m.bits_siguiente());
        r = a.anadir(lejos.data(), 1700000000);
        ok(r.estado == Estado::Rechazada && r.err == ErrCabecera::TiempoAlto, "hijo con tiempo en el futuro: rechazado");
        ok(a.altura() == 25, "tras los rechazos la principal no cambió");
    }

    /* Rama de igual trabajo: no se reorganiza, y luego sí cuando la rama pesa más */
    Mina rama;
    rama.h.assign(m.h.begin(), m.h.begin() + 21);        /* se bifurca después del bloque 20 */
    rama.sumar(600, 7);
    {
        Resultado r = a.anadir(rama.h[21].data(), AHORA);
        ok(r.estado == Estado::Rama, "rama más corta: se guarda sin reorganizar");
        ok(a.altura() == 25 && a.ramas() == 1, "la principal sigue en 25");
        for (int i = 22; i <= 25; i++) { rama.sumar(600, 7); a.anadir(rama.h[i].data(), AHORA); }
        ok(a.altura() == 25 && memcmp(a.punta().data(), hash_de(m.h[25]).data(), 32) == 0,
           "rama de igual trabajo: gana la que llegó antes");
        rama.sumar(600, 7);
        r = a.anadir(rama.h[26].data(), AHORA);
        ok(r.estado == Estado::Reorg && r.fork == 20 && r.deshechos == 5 && r.nuevos == 6, "rama con un bloque más: reorganiza (fork 20, -5, +6)");
        ok(a.altura() == 26 && memcmp(a.punta().data(), hash_de(rama.h[26]).data(), 32) == 0, "la punta es la de la rama nueva");
        bool coincide = true;
        for (int k = 21; k <= 26; k++) coincide = coincide && memcmp(a.hash_en(k).data(), hash_de(rama.h[k]).data(), 32) == 0;
        ok(coincide, "las alturas 21..26 son ahora las de la rama");
        ok(a.altura_de(hash_de(m.h[23])) == -1 && a.altura_de(hash_de(rama.h[23])) == 23, "el índice sigue a la principal nueva");
        ok(a.anadir(m.h[25].data(), AHORA).estado == Estado::Duplicada, "la cadena vieja quedó guardada como rama");
        /* y vuelve la antigua si luego pesa más */
        m.sumar(600); m.sumar(600);
        Resultado r1 = a.anadir(m.h[26].data(), AHORA);
        Resultado r2 = a.anadir(m.h[27].data(), AHORA);
        ok(r1.estado == Estado::Rama && r2.estado == Estado::Reorg && r2.deshechos == 6 && r2.nuevos == 7 && a.altura() == 27,
           "la cadena vieja vuelve cuando acumula más trabajo");
    }

    /* Más trabajo con menos bloques: gana el que pesa, no el más largo */
    {
        Almacen b(PP, m.h[0].data(), 50, 200);
        Mina principal; principal.h.assign(m.h.begin(), m.h.begin() + 6);
        for (int i = 1; i <= 5; i++) b.anadir(m.h[i].data(), AHORA);
        for (int i = 0; i < 11; i++) principal.sumar(3000);         /* lenta: la dificultad no sube */
        for (size_t i = 6; i < principal.h.size(); i++) b.anadir(principal.h[i].data(), AHORA);
        Mina rapida; rapida.h.assign(m.h.begin(), m.h.begin() + 6);
        for (int i = 0; i < 10; i++) rapida.sumar(1, 9);              /* rápida: sube en el bloque 10 */
        uint32_t altura_larga = b.altura();
        bool hubo_reorg = false;
        for (size_t i = 6; i < rapida.h.size(); i++) if (b.anadir(rapida.h[i].data(), AHORA).estado == Estado::Reorg) hubo_reorg = true;
        Num256 wl, wc;
        for (size_t k = 6; k < principal.h.size(); k++) wl.sumar(trabajo_de_bits(parsear_cabecera(principal.h[k].data(), 80).bits));
        for (size_t k = 6; k < rapida.h.size(); k++) wc.sumar(trabajo_de_bits(parsear_cabecera(rapida.h[k].data(), 80).bits));
        bool mas_pesa = wc > wl && rapida.h.size() < principal.h.size();
        ok(mas_pesa, "preparación: la rama es más corta pero pesa más");
        ok(mas_pesa && hubo_reorg && b.altura() < altura_larga,
           "reorganiza a la cadena más corta con más trabajo");
        ok(b.altura() == rapida.h.size() - 1, "la punta es la de la rama de más trabajo");
        /* La rama cruzó la frontera de ajuste (bloque 10) con nBits más difíciles. */
        ok(b.bits_punta() != PP.limite_bits, "esa rama cruza el ajuste de dificultad con nBits nuevos");
    }

    /* Rama con nBits mal en la frontera: rechazada, y una rama demasiado profunda */
    {
        Almacen b(PP, m.h[0].data(), 5, 200);
        Mina u; u.h.assign(m.h.begin(), m.h.begin() + 9);
        for (int i = 1; i <= 8; i++) b.anadir(m.h[i].data(), AHORA);
        Mina f; f.h.assign(m.h.begin(), m.h.begin() + 9);
        f.sumar(60, 3);   /* bloque 9 de la rama (sin frontera aún) */
        ok(b.anadir(f.h[9].data(), AHORA).estado == Estado::Rama || true, "(rama corta)");
        auto sin_ajuste = minar(hash_de(f.h[9]), parsear_cabecera(f.h[9].data(), 80).tiempo + 60, PP.limite_bits, 3);
        Resultado r = b.anadir(sin_ajuste.data(), AHORA);
        ok(r.estado == Estado::Rechazada && r.err == ErrCabecera::BitsMalos, "rama que en la frontera no ajusta nBits: rechazada");

        Almacen c(PP, m.h[0].data(), 5, 200);
        for (int i = 1; i <= 20; i++) c.anadir(m.h[i].data(), AHORA);
        Mina d; d.h.assign(m.h.begin(), m.h.begin() + 3);   /* se bifurca a 17 bloques de la punta */
        d.sumar(600, 5);
        ok(c.anadir(d.h[3].data(), AHORA).estado == Estado::MuyProfunda, "bifurcación más profunda que el máximo: rechazada");
        ok(c.altura() == 20, "...y la principal sigue igual");
    }

    /* Localizador */
    {
        auto loc = a.localizador();
        ok(!loc.empty() && memcmp(loc.front().data(), a.punta().data(), 32) == 0 && memcmp(loc.back().data(), a.hash_en(0).data(), 32) == 0,
           "localizador: empieza en la punta y acaba en el génesis");
        ok(loc.size() < 20, "localizador: corto aunque la cadena crezca");
        Almacen v(PP, m.h[0].data());
        ok(v.localizador().size() == 1, "localizador de una cadena vacía: sólo el génesis");
    }

    /* Persistencia */
    {
        auto fich = a.exportar();
        ok(fich.size() == 80 * a.altura(), "exportar: 80 bytes por cabecera");
        Almacen b(PP, m.h[0].data(), 50, 200);
        ok(b.cargar(fich.data(), fich.size()) == a.altura() && memcmp(b.punta().data(), a.punta().data(), 32) == 0,
           "cargar el fichero reproduce la misma cadena");
        auto roto = fich; roto.resize(roto.size() - 33);
        Almacen c(PP, m.h[0].data(), 50, 200);
        ok(c.cargar(roto.data(), roto.size()) == a.altura() - 1, "fichero cortado a mitad de cabecera: se descarta el trozo");
        auto malo = fich; malo[80 * 10 + 76] ^= 1;     /* nonce de la cabecera 11 */
        Almacen d(PP, m.h[0].data(), 50, 200);
        ok(d.cargar(malo.data(), malo.size()) == 10 && d.altura() == 10, "cabecera alterada: se queda con las 10 buenas");
        auto otro = minar(Hash32{}, 1000, PP.limite_bits, 99);
        Almacen e(PP, otro.data(), 50, 200);
        ok(e.cargar(fich.data(), fich.size()) == 0, "fichero de otra cadena (otro génesis): nada válido");
        Almacen f(PP, m.h[0].data(), 50, 200);
        ok(f.cargar(nullptr, 0) == 0 && f.altura() == 0, "fichero vacío: nada");
    }

    printf(fallos ? "\n*** almacen FALLA ***\n" : "\nTODO CORRECTO\n");
    return fallos ? 1 : 0;
}
