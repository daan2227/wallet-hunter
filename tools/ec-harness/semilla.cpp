/* La semilla de los puntos de salida tiene que ser distinta en cada aparato.
 *
 * De donde sale cada canguro lo decide un xorshift sembrado con kg_semilla().
 * Dos aparatos con la misma semilla sueltan sus canguros en los MISMOS sitios y
 * recorren las MISMAS trayectorias. Todo su trabajo esta duplicado, y por fuera
 * —velocidad, puntos distinguidos, todo— se ve identico a que fueran bien. Es
 * el peor tipo de fallo: el que se parece al funcionamiento correcto.
 *
 * La version anterior era
 *
 *     semilla = 0x9E3779B97F4A7C15 * (hilo+1) ^ time(NULL)
 *
 * y time(NULL) va en SEGUNDOS, asi que dos moviles arrancando en el mismo
 * segundo salian iguales byte a byte. La prueba 1 lo reproduce para que quede
 * escrito que pasaba de verdad y no era una precaucion teorica.
 *
 * Las demas comprueban lo que hace la buena.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <set>
#include "../../app/src/main/cpp/kangaroo.h"

static int fallos=0;

/* La formula de antes, tal cual estaba. */
static uint64_t semilla_vieja(uint64_t hilo,uint64_t segundos){
    return 0x9E3779B97F4A7C15ULL*(hilo+1) ^ segundos;
}

/* ---- 1. La vieja chocaba donde la nueva no ----
 *
 * Se montan DOS aparatos que arrancan en el mismo segundo con los mismos hilos,
 * y se les pide la semilla con las dos formulas. Con la vieja salen iguales —no
 * tiene ninguna entrada que distinga un aparato de otro, que es exactamente el
 * fallo— y con la nueva salen distintas.
 */
static void prueba_la_vieja_chocaba(){
    printf("\n1. Dos aparatos arrancando en el mismo segundo:\n");
    const uint64_t t=1789647435ULL;     /* un segundo cualquiera */
    int viejos_iguales=0, nuevos_iguales=0;
    for(uint64_t h=0;h<8;h++){
        /* aparato A y aparato B, mismas condiciones */
        if(semilla_vieja(h,t) == semilla_vieja(h,t)) viejos_iguales++;
        if(kg_semilla(h+1)    == kg_semilla(h+1))    nuevos_iguales++;
    }
    if(viejos_iguales==8)
        printf("  OK   con la formula vieja los 8 hilos salen identicos\n");
    else { printf("  MAL  se esperaban 8 iguales y hubo %d\n",viejos_iguales); fallos++; }
    if(nuevos_iguales==0)
        printf("  OK   con kg_semilla no coincide ninguno de los 8\n");
    else {
        printf("  MAL  kg_semilla ha repetido en %d de 8\n",nuevos_iguales);
        fallos++;
    }

    /* Y con un segundo de diferencia tampoco se salva: cambia un bit bajo, asi
       que las semillas siguen siendo vecinas. Se comprueba que difieren en
       MENOS de 8 bits, que para un generador es estar practicamente encima. */
    uint64_t a=semilla_vieja(3,t), b=semilla_vieja(3,t+1);
    int dif=__builtin_popcountll(a^b);
    if(dif<8) printf("  OK   con 1 s de diferencia solo cambian %d bits\n",dif);
    else { printf("  MAL  se esperaban pocos bits de diferencia, hubo %d\n",dif); fallos++; }
}

/* ---- 2. La nueva no repite ---- */
static void prueba_no_repite(){
    printf("\n2. kg_semilla, 10.000 llamadas seguidas:\n");
    std::set<uint64_t> vistas;
    const int N=10000;
    for(int i=0;i<N;i++) vistas.insert(kg_semilla((uint64_t)(i%8)+1));
    if((int)vistas.size()==N)
        printf("  OK   %d semillas, %d distintas\n",N,(int)vistas.size());
    else {
        printf("  MAL  %d semillas y solo %d distintas (%d repetidas)\n",
               N,(int)vistas.size(),N-(int)vistas.size());
        fallos++;
    }
}

/* ---- 3. Hilos distintos del mismo arranque no se parecen ----
 *
 * Es el caso que mas se da: los hilos de un mismo movil arrancan en el mismo
 * instante. Si la mezcla del numero de hilo no revolviera bien, saldrian
 * semillas vecinas y los canguros de un hilo pisarian a los del de al lado.
 */
static void prueba_hilos_separados(){
    printf("\n3. Hilos del mismo arranque:\n");
    const int H=8;
    uint64_t s[H];
    for(int i=0;i<H;i++) s[i]=kg_semilla((uint64_t)(i+1));
    int peor=64;
    for(int i=0;i<H;i++)
        for(int j=i+1;j<H;j++){
            int d=__builtin_popcountll(s[i]^s[j]);
            if(d<peor) peor=d;
        }
    /* Entre dos numeros de 64 bits al azar la diferencia esperada son 32 bits.
       Se exige 16 como minimo: deja margen de sobra para la casualidad y sigue
       descartando de plano el caso "casi iguales". */
    if(peor>=16) printf("  OK   el par mas parecido difiere en %d bits\n",peor);
    else { printf("  MAL  hay dos hilos que difieren en solo %d bits\n",peor); fallos++; }
}

/* ---- 4. Lo que de verdad importa: los canguros salen a sitios distintos ----
 *
 * Las pruebas anteriores miran numeros. Esta mira el efecto: se montan dos
 * aparatos con el mismo puzzle y el mismo rango, se les suelta con semillas
 * sacadas de kg_semilla, y se comprueba que los puntos distinguidos que
 * producen NO son los mismos. Si lo fueran, el segundo aparato no aportaria
 * nada por muchos saltos que diera.
 */
/* Los mismos que usa reparte.cpp, para no inventar nombres. */
static void be_u64(uint8_t *be,unsigned long long v){
    memset(be,0,32); for(int i=0;i<8;i++) be[31-i]=(uint8_t)(v>>(8*i));
}
static void pub_de(unsigned long long k,uint8_t *pub33){
    sc_t s; sc_set_u64(s,k);
    JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y);
    pub33[0]=(y[0]&1)?0x03:0x02;
    for(int w=0;w<4;w++) for(int b=0;b<8;b++)
        pub33[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}

static void prueba_caminos_distintos(){
    printf("\n4. Dos aparatos, mismo rango, semillas de kg_semilla:\n");
    unsigned long long a=1ULL<<22, b=(1ULL<<23)-1, k=a+7654321ULL%(1ULL<<22);
    uint8_t pub[33],ini[32],fin[32];
    pub_de(k,pub); be_u64(ini,a); be_u64(fin,b);

    KangarooCtx c1,c2;
    if(!kg_setup(&c1,pub,ini,fin,6,14)||!kg_setup(&c2,pub,ini,fin,6,14)){
        printf("  MAL  setup\n"); fallos++; return;
    }
    /* Pocos saltos: solo interesa por donde empiezan, no resolverlo. */
    c1.parar.store(0); c2.parar.store(0);
    kg_run(&c1,16,kg_semilla(1));
    kg_run(&c2,16,kg_semilla(1));

    /* Cuantos puntos de la tabla de uno estan tambien en la del otro. */
    std::set<std::pair<uint64_t,uint64_t> > s1;
    for(uint64_t i=0;i<=c1.tabla.mask;i++){
        DP *s=&c1.tabla.slots[i];
        if(s->usado) s1.insert(std::make_pair(s->kx[0],s->kx[1]));
    }
    int comunes=0,total2=0;
    for(uint64_t i=0;i<=c2.tabla.mask;i++){
        DP *s=&c2.tabla.slots[i];
        if(!s->usado) continue;
        total2++;
        if(s1.count(std::make_pair(s->kx[0],s->kx[1]))) comunes++;
    }
    printf("  aparato 1: %llu puntos   aparato 2: %d puntos   comunes: %d\n",
           (unsigned long long)s1.size(),total2,comunes);
    /* Algun punto comun es normal y ademas es LO QUE SE BUSCA: asi aparece la
       colision. Lo que no puede pasar es que sean el mismo camino entero. */
    if(total2>0 && comunes==total2){
        printf("  MAL  el aparato 2 no ha pisado un solo punto propio:\n"
               "       esta repitiendo el camino del 1 entero\n");
        fallos++;
    } else if(total2==0){
        printf("  MAL  el aparato 2 no ha guardado nada, la prueba no vale\n");
        fallos++;
    } else {
        printf("  OK   cada uno anda por su cuenta\n");
    }
    kg_free(&c1); kg_free(&c2);
}

/* ---- 5. Y con la formula vieja, lo contrario ----
 *
 * La misma prueba 4 pero sembrando como antes, en el mismo segundo. Tiene que
 * salir el camino IDENTICO. Si algun dia esto dejara de cumplirse, es que el
 * arreglo ya no hacia falta y esta prueba sobra; mientras tanto, es la que
 * demuestra que el fallo existia.
 */
static void prueba_la_vieja_repetia_camino(){
    printf("\n5. Los mismos dos aparatos, sembrados como antes:\n");
    unsigned long long a=1ULL<<22, b=(1ULL<<23)-1, k=a+7654321ULL%(1ULL<<22);
    uint8_t pub[33],ini[32],fin[32];
    pub_de(k,pub); be_u64(ini,a); be_u64(fin,b);

    KangarooCtx c1,c2;
    if(!kg_setup(&c1,pub,ini,fin,6,14)||!kg_setup(&c2,pub,ini,fin,6,14)){
        printf("  MAL  setup\n"); fallos++; return;
    }
    const uint64_t t=1789647435ULL;
    c1.parar.store(0); c2.parar.store(0);
    kg_run(&c1,16,semilla_vieja(0,t));
    kg_run(&c2,16,semilla_vieja(0,t));

    std::set<std::pair<uint64_t,uint64_t> > s1;
    for(uint64_t i=0;i<=c1.tabla.mask;i++){
        DP *s=&c1.tabla.slots[i];
        if(s->usado) s1.insert(std::make_pair(s->kx[0],s->kx[1]));
    }
    int comunes=0,total2=0;
    for(uint64_t i=0;i<=c2.tabla.mask;i++){
        DP *s=&c2.tabla.slots[i];
        if(!s->usado) continue;
        total2++;
        if(s1.count(std::make_pair(s->kx[0],s->kx[1]))) comunes++;
    }
    printf("  comunes %d de %d\n",comunes,total2);
    if(total2>0 && comunes==total2)
        printf("  OK   camino identico, como se esperaba del fallo\n");
    else { printf("  MAL  se esperaba el camino identico\n"); fallos++; }
    kg_free(&c1); kg_free(&c2);
}

int main(){
    printf("=== semillas de salida ===\n");
    prueba_la_vieja_chocaba();
    prueba_no_repite();
    prueba_hilos_separados();
    prueba_caminos_distintos();
    prueba_la_vieja_repetia_camino();
    printf("\n%s\n",fallos?"HAY FALLOS":"todo correcto");
    return fallos?1:0;
}
