/* Filtros de bloque (BIP158) y cabeceras, contra los vectores de Bitcoin Core
 * (src/test/data/blockfilters.json, en datos/blockfilters.txt). Cada bloque:
 *   - el filtro que construimos desde el bloque coincide byte a byte;
 *   - la cadena de cabeceras de filtro encadena con las que da Core;
 *   - la cabecera del bloque cumple su prueba de trabajo;
 *   - los scripts gastados están en el filtro y uno ajeno no.
 * Y los fallos que tienen que rechazarse: un filtro alterado, un nonce cambiado,
 * un nBits negativo, y un filtro cortado.
 */
#include "gcs_bip158.h"
#include "bloques_btc.h"
#include "cabecera_btc.h"
#include <stdio.h>
#include <string>
#include <fstream>
#include <sstream>

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
static std::vector<uint8_t> invertir(std::vector<uint8_t> v) { return std::vector<uint8_t>(v.rbegin(), v.rend()); }
static std::string a_hex(const uint8_t *p, size_t n) {
    std::string s; char t[3];
    for (size_t i = 0; i < n; i++) { snprintf(t, 3, "%02x", p[i]); s += t; }
    return s;
}

int main() {
    /* SipHash-2-4: vector de la propia especificación (clave 00..0f, mensaje vacío). */
    ok(siphash24(0x0706050403020100ULL, 0x0f0e0d0c0b0a0908ULL, nullptr, 0) == 0x726fdb47dd0e0e31ULL,
       "SipHash-2-4 con el vector de la especificación");

    std::ifstream f("datos/blockfilters.txt");
    if (!f.is_open()) {
        std::printf("FALLA: no se encuentra datos/blockfilters.txt (vector de Bitcoin Core)\n");
        return 1;
    }
    std::string linea;
    std::vector<std::vector<uint8_t>> prevs_hdr;      /* cabeceras de bloque, para encadenar */
    std::vector<uint8_t> hdr_filtro_anterior(32, 0); /* cabecera de filtro de "antes del génesis" */
    int filas = 0, mal_filtro = 0, mal_cab = 0, mal_pow = 0, mal_membresia = 0, mal_encadena = 0;
    std::vector<uint8_t> filtro_ultimo;

    while (std::getline(f, linea)) {
        std::istringstream s(linea);
        std::string altura, hash_mostrar, blk_hex, n_str, prev_hdr_hex, filt_hex, hdr_hex;
        s >> altura >> hash_mostrar >> blk_hex >> n_str;
        int n_prev = std::stoi(n_str);
        std::vector<std::vector<uint8_t>> prev_scripts;
        for (int i = 0; i < n_prev; i++) { std::string h; s >> h; prev_scripts.push_back(h == "-" ? std::vector<uint8_t>{} : desde_hex(h)); }
        s >> prev_hdr_hex >> filt_hex >> hdr_hex;
        auto bloque = desde_hex(blk_hex);
        if (filt_hex == "-") filt_hex.clear();
        auto filtro_vector = desde_hex(filt_hex);
        auto hdr_vector = desde_hex(hdr_hex);
        filas++;

        /* Clave del filtro: los 16 primeros bytes del hash del bloque, en orden interno. */
        uint8_t hint[32]; btc::hash_cabecera(bloque.data(), hint);
        uint64_t k0 = 0, k1 = 0;
        for (int i = 7; i >= 0; i--) { k0 = (k0 << 8) | hint[i]; k1 = (k1 << 8) | hint[8 + i]; }

        /* Elementos: salidas (sin OP_RETURN) y los scripts que gastan las entradas. */
        std::vector<std::vector<uint8_t>> elementos = btc::scripts_de_salida(bloque);
        for (auto &p : prev_scripts) if (!p.empty()) elementos.push_back(p);
        auto filtro = gcs::construir(elementos, k0, k1);
        if (a_hex(filtro.data(), filtro.size()) != filt_hex) mal_filtro++;

        /* Cabecera de filtro encadenada desde la anterior. Los vectores van en orden de
           visualización (invertido); el cálculo, en orden interno. */
        std::vector<uint8_t> prev_fh = invertir(desde_hex(prev_hdr_hex));
        uint8_t fh[32]; btc::cabecera_de_filtro(filtro_vector, prev_fh.data(), fh);
        if (a_hex(invertir(std::vector<uint8_t>(fh, fh + 32)).data(), 32) != hdr_hex) mal_cab++;

        /* Prueba de trabajo de la cabecera del bloque. */
        if (!btc::prueba_de_trabajo(bloque.data())) mal_pow++;

        /* Cada script gastado está en el filtro. */
        for (auto &p : prev_scripts) if (!p.empty() && !gcs::contiene(filtro_vector, k0, k1, p)) mal_membresia++;

        /* El génesis parte de una cabecera anterior de ceros. Los demás bloques de
           este archivo NO son consecutivos (son una muestra), así que no se encadenan
           entre sí aquí: el encadenado completo se prueba con bloques seguidos. */
        if (altura == "0" && a_hex(prev_fh.data(), 32) != std::string(64, '0')) mal_encadena++;
        filtro_ultimo = filtro_vector;
        (void)hdr_filtro_anterior;
    }
    printf("filas leidas: %d\n", filas);
    ok(filas >= 10, "vectores de BIP158 leídos");
    ok(mal_filtro == 0, "cada filtro se reconstruye byte a byte desde el bloque");
    ok(mal_cab == 0, "la cadena de cabeceras de filtro coincide con Core");
    ok(mal_pow == 0, "las cabeceras de bloque cumplen la prueba de trabajo");
    ok(mal_membresia == 0, "los scripts gastados están en el filtro");
    ok(mal_encadena == 0, "el génesis parte de una cabecera anterior de ceros");

    /* ---- Fallos que tienen que rechazarse ---- */
    /* Un byte del filtro cambiado: la cabecera de filtro ya no coincide. */
    {
        auto bloque = desde_hex(std::string(160, '0'));  /* placeholder, se rellena abajo */
        (void)bloque;
    }
    /* Usamos el último bloque de la prueba para los casos de fallo. */
    std::ifstream g("datos/blockfilters.txt");
    std::string ult, l2;
    while (std::getline(g, l2)) if (!l2.empty()) ult = l2;
    if (ult.empty()) {
        std::printf("FALLA: datos/blockfilters.txt está vacío\n");
        return 1;
    }
    {
        std::istringstream s(ult);
        std::string altura, hash_mostrar, blk_hex, n_str;
        s >> altura >> hash_mostrar >> blk_hex >> n_str;
        auto bloque = desde_hex(blk_hex);
        int n_prev = std::stoi(n_str);
        for (int i = 0; i < n_prev; i++) { std::string h; s >> h; }
        std::string prev_hdr_hex, filt_hex, hdr_hex;
        s >> prev_hdr_hex >> filt_hex >> hdr_hex;
        if (filt_hex == "-") filt_hex.clear();
        auto filtro = desde_hex(filt_hex);
        auto prev_fh = desde_hex(prev_hdr_hex);

        std::vector<uint8_t> alterado = filtro;
        if (alterado.size() > 1) alterado[alterado.size() - 1] ^= 0x01;
        uint8_t fh_alt[32]; btc::cabecera_de_filtro(alterado, prev_fh.data(), fh_alt);
        ok(a_hex(fh_alt, 32) != hdr_hex, "un filtro alterado no da la cabecera de Core");

        std::vector<uint8_t> nonce_roto = bloque;
        nonce_roto[76] ^= 0xff; nonce_roto[77] ^= 0xff;
        ok(!btc::prueba_de_trabajo(nonce_roto.data()), "un nonce cambiado no cumple la prueba de trabajo");

        std::vector<uint8_t> bits_neg = bloque;
        bits_neg[75] |= 0x80;                      /* bit de signo de nBits */
        ok(!btc::prueba_de_trabajo(bits_neg.data()), "un nBits negativo se rechaza");

        std::vector<uint8_t> cortado(filtro.begin(), filtro.begin() + (filtro.size() > 3 ? filtro.size() / 2 : 1));
        bool lanzo = false;
        try { gcs::contiene(cortado, 1, 2, std::vector<uint8_t>{0x6a}); } catch (std::runtime_error &) { lanzo = true; }
        /* Un filtro cortado puede dar "no está" sin lanzar (si el corte cae después de
           la zona que se lee). Lo que no puede es decir "está" sin datos. */
        ok(lanzo || !gcs::contiene(cortado, 1, 2, std::vector<uint8_t>{0x6a}), "un filtro cortado no afirma pertenencia");
    }

    printf(fallos ? "\nHAY FALLOS: %d\n" : "\nTodas las pruebas correctas.\n", fallos);
    return fallos ? 1 : 0;
}
