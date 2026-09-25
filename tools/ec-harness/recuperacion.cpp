/* La recuperacion de frases de verdad (recovery_engine.cpp, bip32.cpp,
 * mnemonic.cpp, sha512.cpp) sin JNI, contra vectores conocidos.
 *
 * Necesita secp256k1 (la CI lo clona; ver .github/workflows/build.yml):
 *   g++ -O2 -DRECOVERY_SIN_JNI -I../../app/src/main/cpp -I<secp>/include -o recuperacion recuperacion.cpp \
 *       ../../app/src/main/cpp/{bip32,mnemonic,sha512}.cpp <secp objetos> -lcrypto -lpthread
 *
 * Frase de prueba: "abandon" x11 + "about" (la de BIP39/BIP84). */
#include <stdio.h>
#include <chrono>
#include "../../app/src/main/cpp/recovery_engine.cpp"
static const char *BIP39_LISTA[]={
#include "../../app/src/main/cpp/bip39_words.h"
};

static std::vector<std::string> lista(){ std::vector<std::string> v; for(const char *w: BIP39_LISTA) v.push_back(w); return v; }

int main(int argc,char **argv){
    int fallos=0;
    std::vector<std::string> wl=lista();
    if(argc>1){   /* ./recuperacion <hilos>: frases por segundo, 5 s */
        std::vector<std::string> f(11,"abandon"); f.push_back("about");
        std::vector<int> h={9,10,11}; for(int i:h) f[i]="???";
        std::thread t([]{ std::this_thread::sleep_for(std::chrono::seconds(5)); g_cancelled=true; });
        auto t0=std::chrono::steady_clock::now();
        recovery_run(f,wl,h,"bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",nullptr,atoi(argv[1]));
        double seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        t.join();
        printf("%.0f frases/s con %s hilo(s)\n", g_filtered.load()/seg, argv[1]);
        return 0;
    }
    std::vector<std::string> frase(11,"abandon"); frase.push_back("about");
    auto con_huecos=[&](std::vector<int> h){ std::vector<std::string> s=frase; for(int i:h) s[i]="???"; return s; };
    struct Caso{ const char *nombre; std::vector<int> huecos; const char *obj; };
    Caso casos[]={
        {"BIP84 indice 0, 1 hueco",      {11},   "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"},
        {"BIP84 indice 1, 2 huecos",     {0,11}, "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"},
        {"BIP44 indice 0, 1 hueco",      {5},    "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA"},
        {"BIP49 indice 0, 1 hueco",      {11},   "37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf"},
    };
    const std::string esperado="abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    for(auto& c: casos){
        auto t0=std::chrono::steady_clock::now();
        std::string r=recovery_run(con_huecos(c.huecos),wl,c.huecos,c.obj,nullptr);
        double seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        bool ok=(r==esperado);
        printf("%s  %s: %s (%.2f s, %lld frases con checksum valido)\n", ok?"OK ":"MAL", c.nombre,
               ok?"frase correcta":r.c_str(), seg, g_filtered.load());
        fallos+=!ok;
    }
    /* Un objetivo que no sale de esta frase: tiene que recorrerlo todo y no dar nada. */
    {
        std::vector<int> h={11};
        std::string r=recovery_run(con_huecos(h),wl,h,"bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",nullptr);
        bool ok=r.empty() && g_attempts.load()==2048 && g_filtered.load()==128;
        printf("%s  objetivo ajeno: %s, %lld combinaciones, %lld con checksum\n", ok?"OK ":"MAL",
               r.empty()?"nada":r.c_str(), g_attempts.load(), g_filtered.load());
        fallos+=!ok;
    }
    /* Sin objetivo: la primera frase con checksum valido, con su direccion. */
    {
        std::vector<int> h={11};
        std::string r=recovery_run(con_huecos(h),wl,h,"",nullptr,1);
        bool ok=r.rfind("abandon ",0)==0 && r.find("|ADDR:1")!=std::string::npos;
        printf("%s  sin objetivo: %s\n", ok?"OK ":"MAL", r.c_str()); fallos+=!ok;
    }
    /* Direccion con checksum roto. */
    {
        std::vector<int> h={11};
        std::string r=recovery_run(con_huecos(h),wl,h,"1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabB",nullptr);
        bool ok=r=="ERROR:INVALID_TARGET";
        printf("%s  direccion mal escrita: %s\n", ok?"OK ":"MAL", r.c_str()); fallos+=!ok;
    }
    printf("\n%s\n",fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos;
}
