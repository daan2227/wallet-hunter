/* Puente JNI del cliente ligero (BIP157/158). Sólo mueve bytes entre Kotlin y la
 * máquina de estados de sincro_btc.h: los sockets, los hilos y el fichero de
 * cabeceras están en Kotlin (LightClient.kt). Un mutex por cliente: Kotlin llama
 * desde un hilo por conexión. */
#include <jni.h>
#include <memory>
#include <mutex>
#include <sstream>
#include "genesis_btc.h"
#include "sincro_btc.h"

namespace {

struct Ligero {
    btc::Almacen alm;
    btc::Cliente cli;
    std::mutex m;
    Ligero(const btc::ParamsRed &p, const uint8_t *genesis, const btc::ConfigCliente &cfg,
           std::vector<std::vector<uint8_t>> scripts)
        : alm(p, genesis), cli(cfg, alm, std::move(scripts)) {}
};

static int hexval(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }
static bool desde_hex(const std::string &h, std::vector<uint8_t> &out) {
    if (h.size() % 2) return false;
    out.clear();
    for (size_t i = 0; i < h.size(); i += 2) {
        int a = hexval(h[i]), b = hexval(h[i + 1]);
        if (a < 0 || b < 0) return false;
        out.push_back((uint8_t)(a * 16 + b));
    }
    return true;
}
static std::string a_hex(const uint8_t *p, size_t n) {
    static const char *d = "0123456789abcdef"; std::string s;
    for (size_t i = 0; i < n; i++) { s += d[p[i] >> 4]; s += d[p[i] & 15]; }
    return s;
}
static std::string txid_visible(const std::array<uint8_t, 32> &t) {
    uint8_t r[32]; for (int i = 0; i < 32; i++) r[i] = t[31 - i];
    return a_hex(r, 32);
}
static std::string esc(const std::string &s) {
    std::string o;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += (char)c; }
        else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += (char)c;
    }
    return o;
}
static std::string str(JNIEnv *env, jstring j) {
    if (!j) return "";
    const char *c = env->GetStringUTFChars(j, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(j, c);
    return s;
}
static std::vector<std::vector<uint8_t>> scripts_de(const std::string &lista) {
    std::vector<std::vector<uint8_t>> v; std::stringstream ss(lista); std::string t;
    while (std::getline(ss, t, ',')) { std::vector<uint8_t> b; if (!t.empty() && desde_hex(t, b)) v.push_back(b); }
    return v;
}
static Ligero *L(jlong h) { return reinterpret_cast<Ligero *>(h); }
static const char *nombre_fase(btc::Fase f) {
    switch (f) {
        case btc::Fase::Conectando: return "conectando";
        case btc::Fase::Cabeceras: return "cabeceras";
        case btc::Fase::FiltrosCab: return "cabeceras_de_filtro";
        case btc::Fase::Filtros: return "filtros";
        case btc::Fase::Bloques: return "bloques";
        case btc::Fase::Listo: return "listo";
        case btc::Fase::Error: return "error";
    }
    return "?";
}
static const char *nombre_difusion(btc::EstadoDifusion e) {
    switch (e) {
        case btc::EstadoDifusion::Desconocida: return "desconocida";
        case btc::EstadoDifusion::Pendiente: return "pendiente";
        case btc::EstadoDifusion::Enviada: return "enviada";
        case btc::EstadoDifusion::Propagada: return "propagada";
        case btc::EstadoDifusion::Confirmada: return "confirmada";
        case btc::EstadoDifusion::Reemplazada: return "reemplazada";
    }
    return "?";
}
static jbyteArray bytes_a_java(JNIEnv *env, const std::vector<uint8_t> &v) {
    jbyteArray a = env->NewByteArray((jsize)v.size());
    if (a && !v.empty()) env->SetByteArrayRegion(a, 0, (jsize)v.size(), reinterpret_cast<const jbyte *>(v.data()));
    return a;
}

}  // namespace

extern "C" {

/* red 0: red principal (génesis incorporado). red 1: sólo para pruebas del escritorio,
 * con una cadena sintética de dificultad mínima y el génesis que se pasa en hex. */
JNIEXPORT jlong JNICALL
Java_com_hunter_btc_LightNative_nuevo(JNIEnv *env, jobject, jint red, jstring jgenesis, jint minPares,
                                      jint desde, jlong nonce, jstring jscripts, jboolean soloDifusion) {
    try {
        btc::ConfigCliente cfg;
        cfg.min_pares = minPares < 1 ? 1 : (uint32_t)minPares;
        cfg.desde = desde < 0 ? 0 : (uint32_t)desde;
        cfg.nonce = (uint64_t)nonce;
        cfg.solo_difusion = soloDifusion != JNI_FALSE;
        std::vector<uint8_t> gen;
        btc::ParamsRed p = btc::red_principal();
        if (red == 1) {
            p = {0x207fffff, 2016, 1209600, false};
            if (!desde_hex(str(env, jgenesis), gen) || gen.size() != 80) return 0;
        } else gen.assign(btc::GENESIS_PRINCIPAL, btc::GENESIS_PRINCIPAL + 80);
        return reinterpret_cast<jlong>(new Ligero(p, gen.data(), cfg, scripts_de(str(env, jscripts))));
    } catch (...) { return 0; }
}

JNIEXPORT void JNICALL Java_com_hunter_btc_LightNative_liberar(JNIEnv *, jobject, jlong h) { delete L(h); }

JNIEXPORT jint JNICALL
Java_com_hunter_btc_LightNative_cargar(JNIEnv *env, jobject, jlong h, jbyteArray datos) {
    Ligero *l = L(h); if (!l || !datos) return 0;
    jsize n = env->GetArrayLength(datos);
    std::vector<uint8_t> b((size_t)n);
    if (n) env->GetByteArrayRegion(datos, 0, n, reinterpret_cast<jbyte *>(b.data()));
    std::lock_guard<std::mutex> g(l->m);
    try { return (jint)l->alm.cargar(b.data(), b.size()); } catch (...) { return 0; }
}

JNIEXPORT jbyteArray JNICALL
Java_com_hunter_btc_LightNative_exportar(JNIEnv *env, jobject, jlong h, jint desde) {
    Ligero *l = L(h); if (!l) return bytes_a_java(env, {});
    std::lock_guard<std::mutex> g(l->m);
    return bytes_a_java(env, l->alm.exportar(desde < 1 ? 1 : (uint32_t)desde));
}

JNIEXPORT jlong JNICALL Java_com_hunter_btc_LightNative_reorgMin(JNIEnv *, jobject, jlong h) {
    Ligero *l = L(h); if (!l) return -1;
    std::lock_guard<std::mutex> g(l->m); return (jlong)l->alm.tomar_reorg_min();
}
JNIEXPORT jint JNICALL Java_com_hunter_btc_LightNative_altura(JNIEnv *, jobject, jlong h) {
    Ligero *l = L(h); if (!l) return 0;
    std::lock_guard<std::mutex> g(l->m); return (jint)l->alm.altura();
}

JNIEXPORT void JNICALL Java_com_hunter_btc_LightNative_parNuevo(JNIEnv *, jobject, jlong h, jint id, jlong ms, jboolean relay) {
    Ligero *l = L(h); if (!l) return;
    std::lock_guard<std::mutex> g(l->m); l->cli.par_nuevo(id, (uint64_t)ms, relay != JNI_FALSE);
}
JNIEXPORT void JNICALL Java_com_hunter_btc_LightNative_parCerrado(JNIEnv *, jobject, jlong h, jint id, jlong ms) {
    Ligero *l = L(h); if (!l) return;
    std::lock_guard<std::mutex> g(l->m); l->cli.par_cerrado(id, (uint64_t)ms);
}
JNIEXPORT void JNICALL
Java_com_hunter_btc_LightNative_recibido(JNIEnv *env, jobject, jlong h, jint id, jbyteArray datos, jint n, jlong ms) {
    Ligero *l = L(h); if (!l || !datos || n <= 0) return;
    if (n > env->GetArrayLength(datos)) return;
    std::vector<uint8_t> b((size_t)n);
    env->GetByteArrayRegion(datos, 0, n, reinterpret_cast<jbyte *>(b.data()));
    std::lock_guard<std::mutex> g(l->m); l->cli.recibido(id, b.data(), b.size(), (uint64_t)ms);
}
JNIEXPORT jbyteArray JNICALL Java_com_hunter_btc_LightNative_salida(JNIEnv *env, jobject, jlong h, jint id) {
    Ligero *l = L(h); if (!l) return bytes_a_java(env, {});
    std::lock_guard<std::mutex> g(l->m); return bytes_a_java(env, l->cli.tomar_salida(id));
}
JNIEXPORT jintArray JNICALL Java_com_hunter_btc_LightNative_cierres(JNIEnv *env, jobject, jlong h) {
    std::vector<int> v;
    if (Ligero *l = L(h)) { std::lock_guard<std::mutex> g(l->m); v = l->cli.tomar_cierres(); }
    jintArray a = env->NewIntArray((jsize)v.size());
    if (a && !v.empty()) env->SetIntArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}
JNIEXPORT void JNICALL Java_com_hunter_btc_LightNative_tick(JNIEnv *, jobject, jlong h, jlong ms) {
    Ligero *l = L(h); if (!l) return;
    std::lock_guard<std::mutex> g(l->m); l->cli.tick((uint64_t)ms);
}
JNIEXPORT void JNICALL Java_com_hunter_btc_LightNative_actualizar(JNIEnv *, jobject, jlong h, jlong ms) {
    Ligero *l = L(h); if (!l) return;
    std::lock_guard<std::mutex> g(l->m); l->cli.actualizar((uint64_t)ms);
}
JNIEXPORT void JNICALL
Java_com_hunter_btc_LightNative_reescanear(JNIEnv *env, jobject, jlong h, jstring jscripts, jint desde, jlong ms) {
    Ligero *l = L(h); if (!l) return;
    auto sc = scripts_de(str(env, jscripts));
    std::lock_guard<std::mutex> g(l->m); l->cli.reescanear(sc, desde < 0 ? 0 : (uint32_t)desde, (uint64_t)ms);
}

JNIEXPORT jstring JNICALL Java_com_hunter_btc_LightNative_estado(JNIEnv *env, jobject, jlong h) {
    Ligero *l = L(h); if (!l) return env->NewStringUTF("{}");
    std::lock_guard<std::mutex> g(l->m);
    std::ostringstream o;
    o << "{\"fase\":\"" << nombre_fase(l->cli.fase()) << "\",\"error\":\"" << esc(l->cli.error()) << "\""
      << ",\"saldo\":" << l->cli.saldo() << ",\"alturaCabeceras\":" << l->alm.altura()
      << ",\"alturaProcesada\":" << l->cli.altura_procesada()
      << ",\"verificado\":" << (l->cli.verificado() ? "true" : "false")
      << ",\"bloques\":" << l->cli.bloques_descargados()
      << ",\"paresVivos\":" << l->cli.pares_vivos() << ",\"paresListos\":" << l->cli.pares_listos()
      << ",\"utxos\":[";
    bool primero = true;
    for (auto &u : l->cli.utxos()) {
        if (!primero) o << ","; primero = false;
        o << "{\"txid\":\"" << txid_visible(u.txid) << "\",\"vout\":" << u.vout << ",\"valor\":" << u.valor
          << ",\"altura\":" << u.altura << ",\"coinbase\":" << (u.coinbase ? "true" : "false")
          << ",\"spk\":\"" << a_hex(u.spk.data(), u.spk.size()) << "\"}";
    }
    o << "],\"registro\":[";
    primero = true;
    for (auto &r : l->cli.registro()) { if (!primero) o << ","; primero = false; o << "\"" << esc(r) << "\""; }
    o << "]}";
    return env->NewStringUTF(o.str().c_str());
}

/* "txid" visible si va bien, o "ERR:motivo". */
JNIEXPORT jstring JNICALL Java_com_hunter_btc_LightNative_difundir(JNIEnv *env, jobject, jlong h, jstring jraw, jlong ms) {
    Ligero *l = L(h); if (!l) return env->NewStringUTF("ERR:cliente cerrado");
    std::vector<uint8_t> raw;
    if (!desde_hex(str(env, jraw), raw)) return env->NewStringUTF("ERR:hex inválido");
    std::lock_guard<std::mutex> g(l->m);
    try { return env->NewStringUTF(txid_visible(l->cli.difundir(raw, (uint64_t)ms)).c_str()); }
    catch (const std::exception &e) { return env->NewStringUTF((std::string("ERR:") + e.what()).c_str()); }
}
JNIEXPORT void JNICALL Java_com_hunter_btc_LightNative_pausarDifusion(JNIEnv *, jobject, jlong h, jboolean pausa, jlong ms) {
    Ligero *l = L(h); if (!l) return;
    std::lock_guard<std::mutex> g(l->m); l->cli.pausar_difusion(pausa != JNI_FALSE, (uint64_t)ms);
}
JNIEXPORT jboolean JNICALL Java_com_hunter_btc_LightNative_parListo(JNIEnv *, jobject, jlong h, jint id) {
    Ligero *l = L(h); if (!l) return JNI_FALSE;
    std::lock_guard<std::mutex> g(l->m); return l->cli.par_listo(id) ? JNI_TRUE : JNI_FALSE;
}
/* "estado:altura". El txid va en el orden de los exploradores. */
JNIEXPORT jstring JNICALL Java_com_hunter_btc_LightNative_estadoDifusion(JNIEnv *env, jobject, jlong h, jstring jtxid) {
    Ligero *l = L(h); if (!l) return env->NewStringUTF("desconocida:0");
    std::vector<uint8_t> v;
    if (!desde_hex(str(env, jtxid), v) || v.size() != 32) return env->NewStringUTF("desconocida:0");
    std::array<uint8_t, 32> t; for (int i = 0; i < 32; i++) t[i] = v[31 - i];
    std::lock_guard<std::mutex> g(l->m);
    std::string r = std::string(nombre_difusion(l->cli.estado_difusion(t))) + ":" + std::to_string(l->cli.altura_difusion(t));
    return env->NewStringUTF(r.c_str());
}

}  // extern "C"
