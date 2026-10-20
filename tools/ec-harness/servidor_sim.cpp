/* Nodo simulado por TCP, para probar LightClient.kt (sockets + JNI + C++) de punta a
 * punta en el escritorio. Sirve la misma cadena sintética que las demás pruebas, con
 * filtros BIP158 reales, y cada conexión puede portarse mal.
 *
 *   servidor_sim [--mal=N:tipo ...] [--confirma] [--sin-filtros]
 *     tipo: merkle | silencio | omitir   (N = número de conexión que llega, desde 1)
 *     --confirma: al recibir la transacción de prueba, mina un bloque que la incluye
 *
 * Imprime, una por línea: PORT n / GENESIS hex / TX hex / TXID hex (en el orden de los
 * exploradores). Escucha en todo 127.x.x.x, para que una misma máquina haga de varios nodos. */
#include "sim_red.h"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <mutex>
#include <thread>

static std::mutex M;
static std::shared_ptr<Cadena_> actual, mentirosa;
static TxSim MIA; static H ID_MIA; static bool SIN_FILTROS = false, CONFIRMA = false, confirmada = false;
static std::map<int, std::string> MAL;
static std::atomic<int> contador{0};

struct Con { int fd; Nodo nodo; bool relay = false; };
static std::vector<Con *> cons;

static void escribir(int fd, const Bytes &b) {
    size_t o = 0;
    while (o < b.size()) { ssize_t n = write(fd, b.data() + o, b.size() - o); if (n <= 0) return; o += (size_t)n; }
}

static void atender(Con *c, int idx) {
    std::string mal = MAL.count(idx) ? MAL[idx] : "";
    if (mal == "merkle") c->nodo.merkle_roto = true;
    if (mal == "silencio") c->nodo.silencio = true;
    if (SIN_FILTROS) c->nodo.servicios = NODE_NETWORK | NODE_WITNESS;
    uint8_t buf[65536];
    for (;;) {
        ssize_t n = read(c->fd, buf, sizeof buf);
        if (n <= 0) break;
        Bytes r; std::vector<std::pair<int, Bytes>> otros;
        {
            std::lock_guard<std::mutex> g(M);
            c->nodo.ch = mal == "omitir" ? mentirosa : actual;
            try { r = c->nodo.recibe(Bytes(buf, buf + n)); } catch (...) { break; }
            c->relay = c->nodo.relay;
            if (!c->nodo.nuevos.empty()) {
                std::vector<Inv> iv; bool es_mia = false;
                for (auto &t : c->nodo.nuevos) { iv.push_back({INV_TX, t}); if (t == ID_MIA) es_mia = true; }
                c->nodo.nuevos.clear();
                auto msg = serializar(MAGIC_PRINCIPAL, "inv", crear_inv(iv));
                for (Con *o : cons) if (o != c && o->relay) otros.push_back({o->fd, msg});
                if (CONFIRMA && es_mia && !confirmada) {
                    confirmada = true;
                    auto mas = std::make_shared<Cadena_>(*actual); mas->agregar({MIA}); actual = mas;
                }
            }
        }
        if (!r.empty()) escribir(c->fd, r);
        for (auto &p : otros) escribir(p.first, p.second);
    }
    close(c->fd);
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a == "--confirma") CONFIRMA = true;
        else if (a == "--sin-filtros") SIN_FILTROS = true;
        else if (a.rfind("--mal=", 0) == 0) { auto p = a.find(':'); MAL[std::stoi(a.substr(6, p - 6))] = a.substr(p + 1); }
    }
    const Bytes S1 = script(0x11), S2 = script(0x22), X = script(0x99);
    auto h = std::make_shared<Cadena_>();
    for (int k = 0; k <= 30; k++) {
        std::vector<TxSim> txs; Bytes cb = {0x51};
        if (k == 5) { TxSim a; a.ins = {{h->txid(1, 0), 0}}; a.outs = {{50000, S1}, {70000, X}}; txs.push_back(a); }
        if (k == 9) { TxSim b; b.ins = {{h->txid(2, 0), 0}}; b.outs = {{30000, S2}}; b.testigo = true; txs.push_back(b); }
        h->agregar(txs, cb);
    }
    actual = h;
    mentirosa = std::make_shared<Cadena_>(*h); mentirosa->omitir = {S1}; mentirosa->refiltrar();
    MIA.ins = {{h->txid(9, 1), 0}}; MIA.outs = {{29000, X}}; MIA.testigo = true;
    Bytes raw = ser_tx(MIA, false, 0, true, &ID_MIA);

    int ls = socket(AF_INET, SOCK_STREAM, 0); int uno = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &uno, sizeof uno);
    sockaddr_in sa{}; sa.sin_family = AF_INET; sa.sin_addr.s_addr = htonl(INADDR_ANY); sa.sin_port = 0;
    if (bind(ls, (sockaddr *)&sa, sizeof sa) < 0 || listen(ls, 16) < 0) { perror("bind"); return 1; }
    socklen_t l = sizeof sa; getsockname(ls, (sockaddr *)&sa, &l);
    auto hx = [](const Bytes &b) { std::string s; char t[3]; for (auto x : b) { snprintf(t, 3, "%02x", x); s += t; } return s; };
    printf("PORT %d\nGENESIS %s\nTX %s\n", ntohs(sa.sin_port), hx(h->b[0].cab).c_str(), hx(raw).c_str());
    Bytes idv(ID_MIA.rbegin(), ID_MIA.rend());
    printf("TXID %s\n", hx(idv).c_str()); fflush(stdout);
    for (;;) {
        int fd = accept(ls, nullptr, nullptr);
        if (fd < 0) continue;
        int idx = ++contador;
        Con *c = new Con(); c->fd = fd;
        { std::lock_guard<std::mutex> g(M); cons.push_back(c); }
        std::thread(atender, c, idx).detach();
    }
}
