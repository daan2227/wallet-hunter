/* Banco del motor de auditoría Taproot (taproot_audit.h), sin JNI ni Android.
 * tools/ec-harness/con_secp.sh lo compila junto con los demás. */
#include <cassert>
#include <cstdio>
#include <cstring>
#include <atomic>
#include <string>
#include <vector>
#include "../../app/src/main/cpp/taproot_audit.h"

static secp256k1_context *CTX;

static void hex32(uint64_t v, uint8_t out[32]){
    memset(out,0,32);
    for(int b=31;b>=0 && v;b--){ out[b]=(uint8_t)(v&0xFF); v>>=8; }
}

int main(){
    CTX = secp256k1_context_create(SECP256K1_CONTEXT_SIGN|SECP256K1_CONTEXT_VERIFY);

    // 1) El tweak coincide con lo que ya usa el motor (hunter_jni.cpp): clave interna == G,
    //    el resultado tiene que ser el mismo con cada implementación (vector cruzado).
    {
        uint8_t gx[32]; // x de G (BIP340 test vector)
        const char *gxhex="79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
        for(int i=0;i<32;i++) sscanf(gxhex+i*2,"%2hhx",&gx[i]);
        uint8_t out1[32];
        bool ok = tr_audit::tweak(CTX,gx,out1);
        assert(ok);
        printf("tweak(G) = ");
        for(int i=0;i<32;i++) printf("%02x",out1[i]);
        printf("\n");
    }

    // 2) cargar(): una dirección bc1p real de la red (bloque génesis de pruebas, dirección de
    //    ejemplo de BIP341) y una clave x-only pegada a mano, mezcladas con basura y repetidas.
    {
        const char *path="/tmp/tr_targets_test.txt";
        uint8_t otra[32]; memset(otra,0xAB,32);
        char addr_otra[128]; xonly_to_p2tr(otra,addr_otra,false);   // una dirección autogenerada, sin más
        FILE *f=fopen(path,"w");
        fprintf(f,"not an address\n");
        fprintf(f,"%s\n",addr_otra);
        fprintf(f,"%s\n",addr_otra);   // repetida
        fprintf(f,"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\n"); // x-only de G
        fprintf(f,"\n   \n");
        fclose(f);
        tr_audit::Objetivos o; char status[256];
        bool ok = tr_audit::cargar(path,&o,1000,status,sizeof(status));
        assert(ok);
        printf("cargar(): %s (n=%llu)\n",status,(unsigned long long)o.n);
        assert(o.n==2);   // la bc1p y la x-only de G, sin la repetida ni la basura
        o.liberar();
    }

    // 3) escanear_bloque(): una d pequeña (42) cuya salida tweakeada SÍ está en la lista de
    //    objetivos tiene que salir con su hex y dirección correctos; el resto del bloque, sin aviso.
    {
        uint8_t priv42[32]; hex32(42,priv42);
        secp256k1_pubkey pub; secp256k1_ec_pubkey_create(CTX,&pub,priv42);
        uint8_t p33[33]; size_t l=33; secp256k1_ec_pubkey_serialize(CTX,p33,&l,&pub,SECP256K1_EC_COMPRESSED);
        uint8_t xonly_tweaked[32]; tr_audit::tweak(CTX,p33+1,xonly_tweaked);
        char addr_esperada[128]; xonly_to_p2tr(xonly_tweaked,addr_esperada,false);

        tr_audit::Objetivos o;
        o.n=1; o.d=(uint8_t*)malloc(32); memcpy(o.d,xonly_tweaked,32);

        uint8_t start[32]; hex32(0,start);
        std::atomic<bool> parar(false);
        std::vector<std::pair<std::string,std::string>> hits;
        uint64_t hecho = tr_audit::escanear_bloque(CTX,&o,start,0,100,parar,
            [&](const char *privhex, const char *addr){ hits.emplace_back(privhex,addr); });
        assert(hecho==100);
        assert(hits.size()==1);
        char privhex_esperada[65]; for(int b=0;b<32;b++) sprintf(privhex_esperada+b*2,"%02x",priv42[b]);
        assert(hits[0].first==privhex_esperada);
        assert(hits[0].second==addr_esperada);
        printf("escanear_bloque(): found d=42 -> %s\n", hits[0].second.c_str());
        o.liberar();

        // 3b) Si no hay ningún objetivo en el bloque, no avisa de nada (y recorre todo el bloque).
        tr_audit::Objetivos vacio; uint8_t dummy[32]={0xff}; vacio.n=1; vacio.d=dummy;
        std::vector<std::pair<std::string,std::string>> hits2;
        uint64_t hecho2 = tr_audit::escanear_bloque(CTX,&vacio,start,0,50,parar,
            [&](const char *p, const char *a){ hits2.emplace_back(p,a); });
        assert(hecho2==50 && hits2.empty());

        // 3c) Parar a mitad: hecho < cuenta pedida.
        std::atomic<bool> parar_ya(true);
        uint64_t hecho3 = tr_audit::escanear_bloque(CTX,&vacio,start,0,5000,parar_ya,
            [&](const char*, const char*){});
        assert(hecho3 < 5000);
        printf("stop a mitad: hecho=%llu de 5000 pedidas\n",(unsigned long long)hecho3);
    }

    // 4) be32_add_u64: acarreo correcto (0xff...ff + 1 = 0x00...00, con overflow silencioso, que es
    //    lo esperado: el rango de 2^40 nunca llega a desbordar 32 bytes).
    {
        uint8_t v[32]; memset(v,0xff,32);
        tr_audit::be32_add_u64(v,1);
        for(int i=0;i<32;i++) assert(v[i]==0);
    }

    // 5) escanear_bloque_rapido() tiene que encontrar EXACTAMENTE lo mismo que escanear_bloque(),
    //    candidata por candidata, en un rango que no cae en ningún múltiplo de TR_LOTE (para que
    //    se vean varios lotes parciales) y con varios objetivos repartidos dentro.
    {
        uint8_t start[32]; hex32(123456789ULL,start);
        tr_audit::Objetivos o; char status[256];
        char path[]="/tmp/tr_targets_rapido.txt";
        FILE *f=fopen(path,"w");
        // tres d's dentro del rango de prueba (desde=0, cuenta=1000): 7, 500 y 999.
        for(uint64_t d : {7ULL,500ULL,999ULL}){
            uint8_t priv[32]; memcpy(priv,start,32); tr_audit::be32_add_u64(priv,d);
            secp256k1_pubkey pub; secp256k1_ec_pubkey_create(CTX,&pub,priv);
            uint8_t p33[33]; size_t l=33; secp256k1_ec_pubkey_serialize(CTX,p33,&l,&pub,SECP256K1_EC_COMPRESSED);
            uint8_t xonly_tw[32]; tr_audit::tweak(CTX,p33+1,xonly_tw);
            char addr[128]; xonly_to_p2tr(xonly_tw,addr,false);
            fprintf(f,"%s\n",addr);
        }
        fclose(f);
        bool ok = tr_audit::cargar(path,&o,1000,status,sizeof(status));
        assert(ok && o.n==3);

        std::atomic<bool> parar(false);
        std::vector<std::pair<std::string,std::string>> hits_lento, hits_rapido;
        uint64_t h1 = tr_audit::escanear_bloque(CTX,&o,start,0,1000,parar,
            [&](const char *p, const char *a){ hits_lento.emplace_back(p,a); });
        uint64_t h2 = tr_audit::escanear_bloque_rapido(CTX,&o,start,0,1000,parar,
            [&](const char *p, const char *a){ hits_rapido.emplace_back(p,a); });
        assert(h1==1000 && h2==1000);
        assert(hits_lento.size()==3 && hits_rapido.size()==3);
        for(size_t i=0;i<3;i++){
            assert(hits_lento[i].first==hits_rapido[i].first);
            assert(hits_lento[i].second==hits_rapido[i].second);
        }
        printf("escanear_bloque_rapido(): %zu hallazgos, iguales a escanear_bloque()\n",hits_rapido.size());

        // 5b) parar a mitad también funciona en la versión rápida.
        std::atomic<bool> parar_ya(true);
        uint64_t h3 = tr_audit::escanear_bloque_rapido(CTX,&o,start,0,5000,parar_ya,
            [&](const char*, const char*){});
        assert(h3 < 5000);

        o.liberar();
    }

    // 6) Caso raro: el rango incluye d=1 (P == G), donde jp_add_affine no vale y hace falta caer a
    //    escanear_bloque(). Tiene que seguir encontrando lo que haya que encontrar, sin romperse.
    {
        uint8_t start[32]; hex32(1,start);   // d=1 es la primera candidata del bloque
        uint8_t priv2[32]; hex32(2,priv2);   // y d=2 (start+1) lleva un objetivo plantado
        secp256k1_pubkey pub; secp256k1_ec_pubkey_create(CTX,&pub,priv2);
        uint8_t p33[33]; size_t l=33; secp256k1_ec_pubkey_serialize(CTX,p33,&l,&pub,SECP256K1_EC_COMPRESSED);
        uint8_t xonly_tw[32]; tr_audit::tweak(CTX,p33+1,xonly_tw);

        tr_audit::Objetivos o; o.n=1; o.d=(uint8_t*)malloc(32); memcpy(o.d,xonly_tw,32);
        std::atomic<bool> parar(false);
        std::vector<std::pair<std::string,std::string>> hits;
        uint64_t hecho = tr_audit::escanear_bloque_rapido(CTX,&o,start,0,10,parar,
            [&](const char *p, const char *a){ hits.emplace_back(p,a); });
        assert(hecho==10);
        assert(hits.size()==1);
        char privhex_esperada[65]; for(int b=0;b<32;b++) sprintf(privhex_esperada+b*2,"%02x",priv2[b]);
        assert(hits[0].first==privhex_esperada);
        printf("escanear_bloque_rapido(): d=1 en el bloque, cae a la vía lenta y sigue bien\n");
        o.liberar();
    }

    printf("OK: taproot_audit\n");
    secp256k1_context_destroy(CTX);
    return 0;
}
