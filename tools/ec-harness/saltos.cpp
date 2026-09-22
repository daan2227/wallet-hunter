/* La invariante del canguro: esta SIEMPRE donde dice su distancia.
 *
 * Un manso en dist*G, un salvaje en P' + dist*G. Son dos caminos distintos en
 * el codigo —salen de sitios distintos y con repartos distintos— asi que se
 * miran los dos.
 *
 * En cada salto avanza jx[h] en el punto y jlen[h] en la
 * distancia. Si las dos cosas no van a la par, el canguro sigue andando y
 * sigue dejando puntos distinguidos en la tabla, pero su distancia ya no dice
 * donde esta. Una colision suya da una resta que no es la clave.
 *
 * Y NO SE NOTA. kg_resolver comprueba kp*G contra el objetivo antes de cantar
 * victoria, asi que no sale una clave falsa: sale que no hay clave. La UI
 * sigue contando millones de claves por segundo, la tabla sigue creciendo, los
 * moviles siguen calentando. Exactamente igual que si todo fuera bien.
 *
 * Por eso esto se comprueba aqui y con el rango GRANDE, que es el que se usa.
 * El fallo que la trajo: la tabla de saltos guardaba jlen en uint64_t y el
 * salto i vale 2^i, asi que a partir de i=63 no cabia y se guardaba CERO. Como
 * ademas KG_MAX_JUMPS estaba en 64, eso pasaba en TODOS los rangos de mas de
 * 118 bits: #140, #145 y #155, los unicos para los que se usa Kangaroo. Medido
 * entonces en el #140: 15 de 145 mansos en su sitio.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <vector>
#include "../../app/src/main/cpp/kangaroo.h"

static int fallos=0;
#define OK(cond,et) do{ if(cond) printf("  OK   %s\n",et); \
                        else { printf("MAL  %s\n",et); fallos++; } }while(0)

/* ini/fin = 2^bits .. 2^(bits+1)-1, en big-endian de 32 bytes. */
static void pot2_be(uint8_t *be,int bits){
    memset(be,0,32);
    be[31-bits/8] = (uint8_t)(1u<<(bits%8));
}
static void todo_unos_be(uint8_t *be,int bits){   /* 2^(bits+1)-1 */
    memset(be,0,32);
    for(int i=0;i<=bits;i++) be[31-i/8] |= (uint8_t)(1u<<(i%8));
}

typedef struct { KangarooCtx *c; int n_kang; uint64_t sem; } Arg;
static void *anda(void *p){ Arg *a=(Arg*)p; kg_run(a->c,a->n_kang,a->sem); return NULL; }

/* Comprueba la invariante sobre los puntos que hayan quedado en la tabla. */
static void prueba(int bits,const char *et){
    printf("\n%s (intervalo de %d bits):\n",et,bits);
    uint8_t ini[32],fin[32],pub[33];
    pot2_be(ini,bits); todo_unos_be(fin,bits);

    /* Un objetivo cualquiera dentro del rango. No se va a encontrar —ni falta—:
       lo que se mira son las huellas que van quedando por el camino. */
    {
        sc_t s; sc_from_be32(s,ini); sc_add_u64(s,12345);
        JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&P,x,y);
        pub[0]=(y[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int b=0;b<8;b++)
            pub[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
    }

    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,6,14)){ printf("  setup fallo\n"); fallos++; return; }
    {
        /* El salto mayor tiene que valer 2^(njumps-1). Se mira aqui ademas de
           la invariante porque asi el mensaje dice CUAL es el problema. */
        const sc_t *J=&c.jlen[c.njumps-1];
        int b=sc_bits(*J);
        printf("       njumps=%d, salto mayor = 2^%d%s\n",
               c.njumps, b?b-1:-1,
               (b-1)!=c.njumps-1 ? "   <-- NO ES 2^(njumps-1)":"");
    }

    pthread_t h; Arg a={&c,32,0xC0FFEE};
    pthread_create(&h,NULL,anda,&a);
    /* Andar hasta juntar bastantes distinguidos. */
    for(int v=0; v<40000 && c.tabla.guardados<300; v++){
        struct timespec ts={0,200000}; nanosleep(&ts,NULL);
    }
    c.parar.store(1); pthread_join(h,NULL);

    uint64_t mansos=0, m_ok=0, salvajes=0, s_ok=0, dist_grande=0;
    for(uint64_t i=0;i<=c.tabla.mask;i++){
        DP *s=&c.tabla.slots[i];
        if(!s->usado) continue;
        if(sc_bits(s->dist)>64) dist_grande++;

        /* Manso: esta en dist*G.  Salvaje: esta en P' + dist*G.
           Son dos caminos distintos en el codigo y hay que mirar los dos: el
           salvaje sale de otro sitio y con otro reparto. */
        JP P; kg_scalar_mul(&P,s->dist,FIELD_GX,FIELD_GY);
        if(!s->manso){
            fe_t dx,dy;
            int cero=1; for(int z=0;z<4;z++) if(P.z[z]) cero=0;
            if(cero){ salvajes++; continue; }       /* dist=0: el propio P' */
            kg_normalize(&P,dx,dy);
            JP W; jp_add_affine(&W,&c.objetivo,dx,dy);
            P=W;
        }
        int inf=1; for(int z=0;z<4;z++) if(P.z[z]) inf=0;
        if(inf){ if(s->manso) mansos++; else salvajes++; continue; }
        fe_t x,y; kg_normalize(&P,x,y);
        int bien = (x[0]==s->kx[0] && x[1]==s->kx[1]);
        if(s->manso){ mansos++; m_ok+=bien; }
        else        { salvajes++; s_ok+=bien; }
    }
    printf("       mansos %llu/%llu en dist*G, salvajes %llu/%llu en P'+dist*G,"
           " %llu distancias de mas de 64 bits\n",
           (unsigned long long)m_ok,(unsigned long long)mansos,
           (unsigned long long)s_ok,(unsigned long long)salvajes,
           (unsigned long long)dist_grande);
    OK(mansos>0 && salvajes>0, "hay de los dos rebanos que comprobar");
    OK(mansos>0 && m_ok==mansos,
       "todos los mansos estan donde dice su distancia");
    OK(salvajes>0 && s_ok==salvajes,
       "todos los salvajes estan donde dice su distancia");
    /* En un rango grande las distancias PASAN de 64 bits enseguida. Si no
       apareciera ninguna, la prueba no estaria mirando lo que cree. */
    if(bits>80)
        OK(dist_grande>0, "y las distancias pasan de 64 bits, que es el caso que fallaba");
    kg_free(&c);
}

/* Una distancia grande tiene que sobrevivir al disco y al cable.
 *
 * Es el mismo tipo de fallo que el de jlen, por el otro lado: si el formato de
 * guardado o el de red se quedara corto, la distancia llegaria truncada y el
 * punto no. Otra vez una tabla llena de entradas que no pueden resolver nada,
 * y otra vez sin sintoma. En el #140 las distancias pasan de 64 bits enseguida,
 * asi que hay que probarlo con esas y no con las de un rango de juguete.
 */
static void prueba_ida_y_vuelta(void){
    printf("\n5. Una distancia de mas de 64 bits, por disco y por red:\n");
    const int BITS=139, DB=6;
    uint8_t ini[32],fin[32],pub[33];
    pot2_be(ini,BITS); todo_unos_be(fin,BITS);
    {
        sc_t s; sc_from_be32(s,ini); sc_add_u64(s,12345);
        JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&P,x,y);
        pub[0]=(y[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int b=0;b<8;b++)
            pub[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
    }
    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,DB,14)){ printf("  setup fallo\n"); fallos++; return; }
    pthread_t h; Arg a={&c,32,0xBEEF};
    pthread_create(&h,NULL,anda,&a);
    for(int v=0; v<40000 && c.tabla.guardados<200; v++){
        struct timespec ts={0,200000}; nanosleep(&ts,NULL);
    }
    c.parar.store(1); pthread_join(h,NULL);

    /* Copia de lo que hay, para comparar contra ella. */
    struct Ent { uint64_t kx[2]; sc_t dist; uint8_t manso; };
    std::vector<Ent> antes;
    uint64_t grandes=0;
    for(uint64_t i=0;i<=c.tabla.mask;i++){
        DP *s=&c.tabla.slots[i];
        if(!s->usado) continue;
        Ent e; e.kx[0]=s->kx[0]; e.kx[1]=s->kx[1];
        sc_copy(e.dist,s->dist); e.manso=s->manso;
        antes.push_back(e);
        if(sc_bits(e.dist)>64) grandes++;
    }
    printf("       %zu puntos, %llu con la distancia por encima de 64 bits\n",
           antes.size(),(unsigned long long)grandes);
    OK(grandes>0, "hay distancias grandes que probar");

    /* Busca una entrada en una tabla y compara la distancia entera. */
    auto igual_en=[&](DPTable *t,const Ent &e){
        uint64_t hh=(e.kx[0]^(e.kx[1]*0x9E3779B97F4A7C15ULL))&t->mask;
        for(uint64_t j=0;j<=t->mask;j++){
            DP *sl=&t->slots[(hh+j)&t->mask];
            if(!sl->usado) return false;
            if(sl->kx[0]==e.kx[0] && sl->kx[1]==e.kx[1])
                return memcmp(sl->dist,e.dist,32)==0 && sl->manso==e.manso;
        }
        return false;
    };

    /* Disco. */
    dp_save(&c.tabla,"/tmp/kg_saltos.dat",pub,ini,fin,DB,0);
    KangarooCtx d; kg_setup(&d,pub,ini,fin,DB,14);
    dp_load(&d.tabla,"/tmp/kg_saltos.dat",pub,ini,fin,DB,NULL);
    uint64_t bien_disco=0;
    for(size_t i=0;i<antes.size();i++) bien_disco+=igual_en(&d.tabla,antes[i]);
    printf("       disco: %llu de %zu con la distancia intacta\n",
           (unsigned long long)bien_disco,antes.size());
    OK(bien_disco==antes.size(), "la distancia sobrevive a guardar y recuperar");

    /* Red. El master no anda: lo unico que puede tener es lo que reciba. */
    KangarooCtx m; kg_setup(&m,pub,ini,fin,DB,14);
    std::vector<uint8_t> buf(kg_export_bytes(4096));
    for(;;){
        size_t n=kg_export(&c.tabla,pub,ini,fin,DB,buf.data(),buf.size(),512);
        if(!n) break;
        uint32_t met=0;
        if(!kg_import(&m,pub,ini,fin,DB,buf.data(),n,&met)) break;
    }
    uint64_t bien_red=0;
    for(size_t i=0;i<antes.size();i++) bien_red+=igual_en(&m.tabla,antes[i]);
    printf("       red:   %llu de %zu con la distancia intacta\n",
           (unsigned long long)bien_red,antes.size());
    OK(bien_red==antes.size(), "la distancia sobrevive al ida y vuelta por red");

    remove("/tmp/kg_saltos.dat");
    kg_free(&c); kg_free(&d); kg_free(&m);
}

/* La resta que da la clave, con distancias de verdad.
 *
 * kg_resolver es el ultimo paso de toda la busqueda: dos canguros de rebanos
 * distintos en el mismo punto, y la clave es la resta de sus distancias. Las
 * pruebas que lo tocan (reparte, kang) van a 24 y 36 bits, donde las distancias
 * caben en una palabra de 64. En el #140 no caben — y este fichero existe
 * precisamente porque ahi es donde estaba el fallo.
 *
 * No hace falta andar: se fabrica la colision. Un manso en d_m*G y un salvaje
 * en P' + d_s*G con d_m - d_s = k - a caen en el MISMO punto por construccion,
 * asi que esto prueba la aritmetica sin gastar 2^70 operaciones.
 */
static void prueba_resolver_grande(void){
    printf("\n6. La resta que da la clave, con distancias de mas de 64 bits:\n");
    const int BITS=139, DB=6;
    uint8_t ini[32],fin[32],pub[33];
    pot2_be(ini,BITS); todo_unos_be(fin,BITS);

    /* k = a + 2^100 + 12345. La incognita relativa pasa de 64 bits, que es lo
       que hay que probar. */
    sc_t a_sc; sc_from_be32(a_sc,ini);
    sc_t rel; sc_zero(rel); rel[1]=1ULL<<36; rel[0]=12345;     /* 2^100 + 12345 */
    sc_t k_sc; sc_add(k_sc,a_sc,rel);
    {
        JP P; kg_scalar_mul(&P,k_sc,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&P,x,y);
        pub[0]=(y[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int b=0;b<8;b++)
            pub[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
    }

    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,DB,12)){ printf("  setup fallo\n"); fallos++; return; }

    /* d_s cualquiera, grande. d_m = d_s + (k-a). */
    sc_t d_s; sc_zero(d_s); d_s[1]=1ULL<<26; d_s[0]=777;       /* 2^90 + 777 */
    sc_t d_m; sc_add(d_m,d_s,rel);

    /* Los dos puntos, que tienen que salir el mismo. */
    uint64_t kx_m[2], kx_s[2];
    {
        JP T; kg_scalar_mul(&T,d_m,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&T,x,y); kx_m[0]=x[0]; kx_m[1]=x[1];
    }
    {
        JP S; kg_scalar_mul(&S,d_s,FIELD_GX,FIELD_GY);
        fe_t sx,sy; kg_normalize(&S,sx,sy);
        JP W; jp_add_affine(&W,&c.objetivo,sx,sy);
        fe_t x,y; kg_normalize(&W,x,y); kx_s[0]=x[0]; kx_s[1]=x[1];
    }
    OK(kx_m[0]==kx_s[0] && kx_m[1]==kx_s[1],
       "el manso y el salvaje caen en el mismo punto (la colision existe)");

    /* Se meten como los mete kg_run. El segundo encuentra al primero. */
    sc_t otro; int otro_manso, mismo=0;
    dp_insert(&c.tabla,kx_m,d_m,1,otro,&otro_manso,&mismo);
    int hay=dp_insert(&c.tabla,kx_s,d_s,0,otro,&otro_manso,&mismo);
    OK(hay==1, "la tabla ve la colision");
    if(hay) kg_resolver(&c,d_s,0,otro);

    OK(c.encontrado.load()==1, "kg_resolver saca la clave");
    OK(memcmp(c.k,k_sc,32)==0, "y es exactamente la que se puso");
    if(c.encontrado.load() && memcmp(c.k,k_sc,32)!=0)
        printf("       esperada 2^139+2^100+12345, salio otra cosa\n");
    /* Y el contexto no ha dado un solo salto: la clave no puede venir de haber
       buscado, solo de la resta. */
    OK(c.saltos.load()==0, "sin dar un solo salto");
    kg_free(&c);
}

int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    printf("Los saltos mueven el punto y la distancia a la par\n");
    /* Rango pequeno: la tabla de saltos cabe de sobra. Es el control — si esto
       tambien fallara, el fallo estaria en la prueba y no en el motor. */
    prueba(40,"1. Rango pequeno (control)");
    /* Rangos de verdad. El #140 es el que corre ahora mismo en los moviles. */
    prueba(119,"2. Justo donde la tabla de saltos se topa");
    prueba(139,"3. Puzzle #140");
    prueba(154,"4. Puzzle #155");
    prueba_ida_y_vuelta();
    prueba_resolver_grande();
    printf("\n%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos?1:0;
}
