#pragma once
/* Pollard's Kangaroo para el logaritmo discreto en un intervalo.
 *
 * El problema: dado un punto P de la curva y sabiendo que P = k*G con k en un
 * intervalo [a,b] conocido, encontrar k.
 *
 * La fuerza bruta prueba las W = b-a claves una a una. Kangaroo necesita del
 * orden de raiz(W) operaciones. Para el puzzle #70 eso es la diferencia entre
 * 2^69 y unas 2^35: millones de anos contra horas.
 *
 * COMO FUNCIONA
 *
 * Se sueltan dos rebanos de "canguros" que saltan por la curva. El salto que da
 * cada uno depende solo de donde esta (de la x de su punto), asi que dos
 * canguros que caigan en el mismo punto siguen el mismo camino a partir de ahi:
 * se quedan pegados.
 *
 *  - Los MANSOS empiezan en un punto del que conocemos el logaritmo: d*G.
 *  - Los SALVAJES empiezan en P + d*G, asi que su logaritmo es k + d.
 *
 * Los dos van sumando la distancia recorrida. Cuando un manso y un salvaje
 * pisan el mismo punto, sus logaritmos coinciden:
 *
 *      dist_manso = k + dist_salvaje    =>    k = dist_manso - dist_salvaje
 *
 * Para detectar que han pisado el mismo punto sin guardarlos todos, solo se
 * apuntan los PUNTOS DISTINGUIDOS: aquellos cuya x acaba en D bits a cero. Uno
 * de cada 2^D. Como los caminos se pegan, si dos canguros se juntan acabaran
 * pasando los dos por el mismo distinguido.
 *
 * QUE NO HACE
 *
 * Kangaroo necesita el PUNTO P, no la direccion. Una direccion es
 * RIPEMD160(SHA256(pub)) y de ahi no se vuelve atras. Para un puzzle cuya
 * direccion nunca ha gastado, esto no sirve de nada y hay que seguir con
 * fuerza bruta. PubKeyFinder en la capa de Kotlin es quien lo averigua.
 */
#include "jac_batch.h"
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <atomic>
#include <chrono>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdint.h>

/* ── Semilla de los puntos de salida ──────────────────────────────────────────
 *
 * De donde sale cada canguro lo decide un xorshift sembrado con esto. Dos
 * aparatos con la MISMA semilla sueltan sus canguros en los MISMOS sitios y
 * recorren las MISMAS trayectorias: todo su trabajo esta duplicado, y por fuera
 * se ve identico a que fueran bien.
 *
 * Antes era:
 *
 *     semilla = 0x9E3779B97F4A7C15 * (hilo+1) ^ time(NULL)
 *
 * y time(NULL) va en SEGUNDOS. O sea que dos moviles que arrancaran Kangaroo en
 * el mismo segundo, con el mismo numero de hilos, salian con semillas iguales
 * byte a byte. No es rebuscado: en un cluster los arranques se agolpan —cuando
 * el maestro manda "reanudar todos", cuando vuelve la corriente, cuando se
 * reconecta la WiFi— y basta con caer en el mismo segundo.
 *
 * Ahora se coge entropia de verdad de /dev/urandom, y si no se puede, algo que
 * al menos no comparten dos aparatos: el reloj monotono en nanosegundos, el pid
 * y una direccion de la pila. El generador de destino no es criptografico y no
 * necesita serlo; lo que hace falta es que NO se repita entre aparatos.
 */
static uint64_t kg_semilla(uint64_t mezcla){
    uint64_t s=0;
    int fd=open("/dev/urandom",O_RDONLY);
    if(fd>=0){
        if(read(fd,&s,sizeof(s))!=(ssize_t)sizeof(s)) s=0;
        close(fd);
    }
    if(!s){
        struct timespec ts; ts.tv_sec=0; ts.tv_nsec=0;
        clock_gettime(CLOCK_MONOTONIC,&ts);
        s  = (uint64_t)ts.tv_nsec;
        s ^= (uint64_t)ts.tv_sec*0x9E3779B97F4A7C15ULL;
        s ^= (uint64_t)getpid()*0xC2B2AE3D27D4EB4FULL;
        s ^= (uint64_t)(uintptr_t)&ts;          /* ASLR */
        s ^= (uint64_t)time(NULL);
    }
    s ^= mezcla*0x9E3779B97F4A7C15ULL;
    /* Un revuelto final (splitmix64) para que bits parecidos den semillas
       distintas: sin esto, dos hilos consecutivos empiezan casi igual. */
    s += 0x9E3779B97F4A7C15ULL;
    uint64_t z=s;
    z=(z^(z>>30))*0xBF58476D1CE4E5B9ULL;
    z=(z^(z>>27))*0x94D049BB133111EBULL;
    return z^(z>>31);
}

/* ---------- Escalares de 256 bits (distancias y resultado) ---------- */
typedef uint64_t sc_t[4];

static void sc_zero(sc_t r){ r[0]=r[1]=r[2]=r[3]=0; }
static void sc_set_u64(sc_t r,uint64_t v){ r[0]=v; r[1]=r[2]=r[3]=0; }
static void sc_copy(sc_t r,const sc_t a){ memcpy(r,a,32); }
static int  sc_cmp(const sc_t a,const sc_t b){
    for(int i=3;i>=0;i--){ if(a[i]>b[i])return 1; if(a[i]<b[i])return -1; } return 0;
}
static void sc_add_u64(sc_t r,uint64_t v){
    uint64_t c=v;
    for(int i=0;i<4&&c;i++){ uint64_t o=r[i]; r[i]=o+c; c=(r[i]<o)?1:0; }
}
static void sc_add(sc_t r,const sc_t a,const sc_t b){
    uint64_t c=0;
    for(int i=0;i<4;i++){
        __uint128_t t=(__uint128_t)a[i]+b[i]+c;
        r[i]=(uint64_t)t; c=(uint64_t)(t>>64);
    }
}
/* r = a - b. Devuelve 1 si a >= b (resultado valido), 0 si se fue en negativo. */
static int sc_sub(sc_t r,const sc_t a,const sc_t b){
    uint64_t borrow=0;
    for(int i=0;i<4;i++){
        __uint128_t t=(__uint128_t)a[i]-b[i]-borrow;
        r[i]=(uint64_t)t; borrow=(t>>127)&1;
    }
    return borrow?0:1;
}
/* ---------- Aritmetica modulo el orden del grupo ----------
 *
 * Hace falta para poder RESTAR distancias sin salirse. Mientras todos los saltos
 * van hacia delante, una distancia solo crece y sc_t sin signo basta; en cuanto
 * un canguro puede ir hacia atras —que es lo que hace el mapa de negacion, donde
 * cambiar P por -P cambia d por -d— hay que trabajar en Z_n.
 *
 * n es el orden del grupo de secp256k1: cuantos puntos distintos hay. d*G y
 * (d+n)*G son el mismo punto, asi que las distancias viven ahi de forma natural.
 */
static const sc_t SC_N = {
    0xBFD25E8CD0364141ULL, 0xBAAEDCE6AF48A03BULL,
    0xFFFFFFFFFFFFFFFEULL, 0xFFFFFFFFFFFFFFFFULL
};

/* r = (a + b) mod n. Exige a,b < n. */
static void sc_add_n(sc_t r,const sc_t a,const sc_t b){
    uint64_t acarreo=0; sc_t t;
    for(int i=0;i<4;i++){
        __uint128_t s=(__uint128_t)a[i]+b[i]+acarreo;
        t[i]=(uint64_t)s; acarreo=(uint64_t)(s>>64);
    }
    /* a+b < 2n, asi que con restar n una vez basta. Si hubo acarreo fuera de los
       256 bits, la resta con prestamo da justo a+b-n: no es un caso aparte. */
    if(acarreo || sc_cmp(t,SC_N)>=0) sc_sub(r,t,SC_N);
    else                             sc_copy(r,t);
}

/* r = -a mod n. Exige a < n. */
static void sc_neg_n(sc_t r,const sc_t a){
    int cero=1; for(int i=0;i<4;i++) if(a[i]) cero=0;
    if(cero){ sc_zero(r); return; }      /* -0 = 0, y n-0 = n no vale */
    sc_sub(r,SC_N,a);
}

/* r = (a - b) mod n. Exige a,b < n. */
static void sc_sub_n(sc_t r,const sc_t a,const sc_t b){
    sc_t nb; sc_neg_n(nb,b); sc_add_n(r,a,nb);
}

/* r = a >> s. Se usa para sacar fracciones del ancho del intervalo (W/2, W/4...)
 * al repartir los puntos de salida. Solo en la preparacion. */
static void sc_shr(sc_t r,const sc_t a,int s){
    sc_t t; sc_copy(t,a);
    if(s>=256){ sc_zero(r); return; }
    int pal=s/64, bit=s%64;
    for(int i=0;i<4;i++){
        uint64_t v=0;
        if(i+pal<4){
            v=t[i+pal]>>bit;
            /* El desplazamiento por 64 es comportamiento indefinido en C, no
               cero: con bit==0 no hay nada que traer de la palabra de arriba. */
            if(bit && i+pal+1<4) v|=t[i+pal+1]<<(64-bit);
        }
        r[i]=v;
    }
}
/* Cuantos bits ocupa. 0 para el cero. */
static int sc_bits(const sc_t a){
    for(int w=3;w>=0;w--) for(int i=63;i>=0;i--)
        if((a[w]>>i)&1) return w*64+i+1;
    return 0;
}
static void sc_from_be32(sc_t r,const uint8_t *be){
    for(int i=0;i<4;i++){
        r[3-i]=0;
        for(int j=0;j<8;j++) r[3-i]|=((uint64_t)be[i*8+j])<<(56-j*8);
    }
}
static void sc_to_be32(const sc_t a,uint8_t *be){
    for(int i=0;i<4;i++) for(int j=0;j<8;j++)
        be[i*8+j]=(uint8_t)(a[3-i]>>(56-j*8));
}

/* ---------- Raiz cuadrada modular, para descomprimir la clave publica ----------
 * p = 3 (mod 4), asi que sqrt(v) = v^((p+1)/4). */
static void fe_sqrt(fe_t r,const fe_t v){
    static const uint64_t E[4]={
        0xFFFFFFFFBFFFFF0CULL,0xFFFFFFFFFFFFFFFFULL,
        0xFFFFFFFFFFFFFFFFULL,0x3FFFFFFFFFFFFFFFULL
    };
    fe_t res; res[0]=1;res[1]=res[2]=res[3]=0;
    for(int w=3;w>=0;w--)
        for(int b=63;b>=0;b--){
            fe_sqr(res,res);
            if((E[w]>>b)&1) fe_mul(res,res,v);
        }
    memcpy(r,res,32);
}

/* Descomprime una clave publica de 33 bytes a (x,y) afines.
 * @return 1 si el punto esta en la curva, 0 si no. */
static int kg_decompress(const uint8_t *pub33, fe_t x, fe_t y){
    if(pub33[0]!=2 && pub33[0]!=3) return 0;
    sc_from_be32(x,pub33+1);
    fe_t x3,siete,rhs;
    fe_sqr(x3,x); fe_mul(x3,x3,x);
    siete[0]=7; siete[1]=siete[2]=siete[3]=0;
    fe_add(rhs,x3,siete);              /* y^2 = x^3 + 7 */
    fe_sqrt(y,rhs);
    fe_t chk; fe_sqr(chk,y);
    if(memcmp(chk,rhs,32)!=0) return 0;   /* no era residuo cuadratico */
    /* La paridad la fija el primer byte: 02 = y par, 03 = y impar. */
    if((y[0]&1)!=(uint64_t)(pub33[0]&1)){
        fe_t cero; memset(cero,0,32);
        fe_sub(y,cero,y);
    }
    return 1;
}

/* ---------- Tabla de puntos distinguidos ----------
 * Direccionamiento abierto con sondeo lineal. La clave son los 128 bits bajos
 * de la x afin: con 2^40 puntos guardados la probabilidad de que dos x
 * distintas colisionen ahi es despreciable, y el resultado se verifica al final
 * de todas formas. */
typedef struct {
    uint64_t kx[2];   /* x[0],x[1] de la x afin. (0,0) = hueco libre */
    sc_t     dist;
    uint8_t  manso;   /* 1 manso, 0 salvaje */
    uint8_t  usado;
    /* Ya se ha mandado por la red. Sirve para que cada envio lleve solo lo
       nuevo en vez de la tabla entera. Un punto ya mandado no se vuelve a
       mandar aunque se reciba de otro sitio: da igual quien lo tenga, lo que
       importa es que el master lo tenga una vez.
       El struct ya ocupaba 56 bytes por alineacion, asi que este byte es
       gratis. */
    uint8_t  enviado;
} DP;

/* El tamano de este struct decide cuanta RAM reserva la tabla, y quien hace esa
 * cuenta es HunterEngine.topeTablaBits() en Kotlin, que lleva el 56 escrito a
 * mano porque desde alli no se puede preguntar. Si aqui se anade un campo y el
 * struct crece, el movil reservaria mas de lo que cree y Android mataria la app
 * por memoria, sin que nada dijera por que.
 *
 * Asi que se sujeta aqui: si esto salta, hay que cambiar el 56 de
 * topeTablaBits() y su comentario. */
static_assert(sizeof(DP)==56,
    "sizeof(DP) ha cambiado: actualiza HunterEngine.topeTablaBits() en Kotlin");

typedef struct {
    DP      *slots;
    uint64_t mask;      /* capacidad-1, capacidad potencia de dos */
    uint64_t guardados;
    pthread_mutex_t mtx;
} DPTable;

static int dp_init(DPTable *t,int bits){
    uint64_t cap=1ULL<<bits;
    t->slots=(DP*)calloc(cap,sizeof(DP));
    if(!t->slots) return 0;
    t->mask=cap-1; t->guardados=0;
    pthread_mutex_init(&t->mtx,NULL);
    return 1;
}
static void dp_free(DPTable *t){
    if(t->slots){ free(t->slots); t->slots=NULL; }
    pthread_mutex_destroy(&t->mtx);
}

/* Inserta un distinguido.
 * Si ya habia otro canguro DEL OTRO REBANO en el mismo punto, devuelve 1 y deja
 * en `otro` su distancia: eso es la colision que resuelve el problema.
 *
 * `mismo` (puede ser NULL) sale a 1 cuando el punto ya estaba pero del MISMO
 * rebano. Esa no es una colision util, pero tampoco es nada: significa que este
 * canguro ha caido justo encima del rastro de otro de los suyos, y como el
 * salto depende SOLO de donde se esta, a partir de ahi los dos van a recorrer
 * exactamente el mismo camino para siempre. Uno de los dos sobra.
 *
 * Antes esto se miraba y se tiraba. El canguro pegado seguia saltando y
 * gastando su parte del lote sin aportar una sola huella nueva, y sin dejar
 * rastro en ningun contador: la UI seguia sumando saltos igual. Quien lo avisa
 * es la tabla, que es el unico sitio donde se ve que el punto ya estaba. */
static int dp_insert(DPTable *t,const uint64_t *kx,const sc_t dist,int manso,
                     sc_t otro,int *otro_manso,int *mismo){
    if(mismo) *mismo=0;
    pthread_mutex_lock(&t->mtx);
    uint64_t h=(kx[0]^(kx[1]*0x9E3779B97F4A7C15ULL))&t->mask;
    int res=0;
    for(uint64_t i=0;i<=t->mask;i++){
        DP *s=&t->slots[(h+i)&t->mask];
        if(!s->usado){
            /* Hueco libre: si la tabla esta casi llena se deja de guardar en
               vez de dar vueltas eternamente. Perder distinguidos solo hace la
               busqueda mas lenta, no incorrecta. */
            if(t->guardados*10 < (t->mask+1)*9){
                s->kx[0]=kx[0]; s->kx[1]=kx[1];
                sc_copy(s->dist,dist); s->manso=(uint8_t)manso; s->usado=1;
                t->guardados++;
            }
            break;
        }
        if(s->kx[0]==kx[0] && s->kx[1]==kx[1]){
            /* Mismo punto. Solo sirve si son de rebanos distintos: dos mansos
               que se juntan no dicen nada de k. */
            if(s->manso!=(uint8_t)manso){
                sc_copy(otro,s->dist); *otro_manso=s->manso; res=1;
            }else{
                if(mismo) *mismo=1;
            }
            break;
        }
    }
    pthread_mutex_unlock(&t->mtx);
    return res;
}

/* ---------- Guardar y recuperar el trabajo hecho ----------
 *
 * Lo que hay que conservar entre sesiones NO son los canguros, es la TABLA DE
 * DISTINGUIDOS. Ahi esta todo el trabajo: cada entrada es un punto por el que ya
 * paso alguien, con su distancia. Si se conserva, un rebano nuevo puede chocar
 * con un distinguido viejo y resolver el problema igual.
 *
 * Volver a soltar los canguros cuesta llegar al siguiente distinguido, unos 2^D
 * saltos. Frente a las 2^35 operaciones que lleva el conjunto, eso es nada.
 *
 * La cabecera lleva la clave publica y el rango: si no coinciden, el fichero es
 * de OTRO puzzle y se ignora. Mezclar dos tablas daria colisiones que no
 * significan nada. */

/* Mapa de negacion: 0 apagado (Kangaroo), 1 encendido (Gaudry-Schost).
 *
 * Va suelto por lo mismo que kg_politica_saltos: kg_setup tiene que saberlo
 * ANTES de construir nada. Se copia al contexto y a partir de ahi se lee de
 * ahi.
 *
 * ENCENDIDO, y ya no encima de Kangaroo sino con otro algoritmo. La historia,
 * para no repetirla:
 *
 * 1. Encima de Kangaroo NO FUNCIONA, y no es un fallo que arreglar. Con el
 *    mapa de negacion el canguro no avanza: canonizar le da la vuelta al punto
 *    una de cada dos veces, y la distancia hace un paseo al azar en vez de
 *    crecer. Un paseo al azar vuelve a pisar puntos, y en un camino
 *    determinista eso es un ciclo. Con dbits >= 13 —el de la app— no resolvia
 *    nunca: 17 distinguidos donde tocaban 244 (#40, dbits 14).
 *
 * 2. Lo que SI funciona es Gaudry-Schost con negacion (Galbraith y Ruprai,
 *    2010), que no necesita que los caminos avancen: cada uno se suelta de
 *    nuevo en cuanto da un distinguido, y lo que cuenta es de donde salen
 *    (ver soltar en kg_run). Hicieron falta cuatro cosas, medidas una a una
 *    con tools/ec-harness/negacion:
 *
 *      - Saltos mucho mas largos que el camino (lb = 1,5*dbits + 8, ver
 *        kg_setup). Con saltos cortos el paseo se pisa a si mismo.
 *      - El salto de escape de un ciclo esteril elegido por el punto, de una
 *        tabla de 64. Con uno fijo, dos escapes de signo contrario se anulan
 *        y el camino vuelve a donde estaba: un escape cada 3,3 pasos.
 *      - Soltar el camino si escapa dos veces del mismo punto.
 *      - Soltarlo tras cada distinguido.
 *
 *    Medido, #40 con 64 canguros (raices de W, menos es mejor):
 *
 *        dbits          8     10     12     14 (app)
 *        Kangaroo     1,98   1,75   3,46   4,22
 *        GS + neg     1,39   1,40   1,56   2,23
 *
 *    Despues, ajustando los terrenos de salida y el tamano de los saltos
 *    (ver soltar y kg_setup), con un banco de 2.400 claves de 30 bits y dbits
 *    5 —donde el coste de arranque pesa poco, como en los puzzles de verdad—:
 *    1,47 -> 1,38. RCKangaroo dice 1,15 con su metodo "SOTA"; no publica los
 *    detalles que harian falta para reproducirlo, y lo que si se probo de el
 *    (caminos sin reinicio, choques entre salvajes) no mejoro aqui.
 *
 *    La teoria da 1,36 para GS con negacion y ~2 para Kangaroo. Con dbits
 *    altos frente al rango (18 en el #40) los dos se disparan por el coste de
 *    llegar al primer distinguido; en los rangos donde se usa de verdad
 *    (#135 en adelante) esa parte es despreciable.
 *
 * Las tablas guardadas y los mensajes del cluster de un motor no le sirven al
 * otro: ver KG_VER_GS y KG_NET_VER. */
static int kg_negacion = 1;

#define KG_MAGIC 0x474E414BU   /* "KANG" */
/* Version 2: cada entrada lleva ademas la marca de "ya enviado".
 *
 * La 1 no la guardaba, asi que al recuperar la tabla todas las entradas
 * volvian a contar como sin mandar y el trabajador REENVIABA LA TABLA ENTERA.
 * Con la pausa a distancia eso pasa en cada pausa, no solo al reiniciar la app.
 *
 * dp_load sigue leyendo ficheros de la 1 —seria una faena tirar la tabla de
 * alguien por cambiar el formato—: se leen sus entradas de 49 bytes y se dan
 * por no enviadas, que provoca un ultimo reenvio y a partir de ahi ya se
 * guarda. */
/* Version 3: la tabla se escribio con una tabla de saltos que SI cuadra.
 *
 * Hasta la 2, el salto i valia 2^i guardado en un uint64_t y a partir de i=63
 * se quedaba en cero: el punto se movia y la distancia no. En rangos de mas de
 * 118 bits —#140, #145 y #155, los unicos en los que se usa Kangaroo— eso
 * envenenaba a casi todos los canguros en unas decenas de saltos, y la tabla se
 * llenaba de entradas cuya distancia no dice donde esta el punto. No dan una
 * clave falsa (kg_resolver la comprueba antes), dan que no hay clave.
 *
 * Esas entradas no sirven para nada y no hay forma de distinguir las buenas de
 * las malas dentro del fichero, asi que en esos rangos se tira la tabla vieja.
 * En rangos pequenos nunca hubo problema y se siguen leyendo.
 */
/* Version 4: las distancias de los SALVAJES van medidas desde el centro del
 * intervalo y no desde su inicio, que es lo que pide el mapa de negacion.
 *
 * No hace falta tirar nada: P' = P'' + (W/2)*G, asi que una distancia de
 * salvaje medida desde P' es esa misma mas W/2 medida desde P''. dp_load la
 * convierte al vuelo en los dos sentidos. Las de los mansos no cambian: un
 * manso esta en d*G y eso no depende de donde se traslade el objetivo. */
#define KG_VER   4u
/* Version 5: Gaudry-Schost con negacion (ver `nesc` y kg_setup).
 *
 * Los puntos de una tabla de Kangaroo no le sirven a Gaudry-Schost ni al
 * reves: cada uno camina con su propia tabla de saltos, asi que dos caminos de
 * motores distintos no se juntan nunca, solo coincidirian por casualidad en un
 * punto exacto. Una tabla del otro motor se tira. Tampoco se lee la 4, la del
 * mapa de negacion encima de Kangaroo, que no llegaba a resolver. */
#define KG_VER_GS 6u
/* 6: saltos de 1,5*dbits+11 bits en vez de +8. Otra tabla de saltos, asi
   que una tabla de la 5 no le sirve a la 6. */
#define KG_VER_SIN_ENVIADO 1u
#define KG_VER_CON_ENVIADO 2u
#define KG_VER_SIN_CENTRAR 3u
/* A partir de este ancho de intervalo, la tabla de saltos vieja se topaba. */
#define KG_BITS_SALTOS_ROTOS 118

typedef struct {
    uint32_t magic, ver;
    uint8_t  pub[33], ini[32], fin[32];
    uint32_t dbits;
    uint64_t ops;
    uint64_t n;
} KgCab;

/* Se escribe en un fichero aparte y se renombra al final: si el sistema mata la
 * app a mitad, el guardado anterior sigue entero en vez de quedar a medias. */
static int dp_save(DPTable *t,const char *ruta,const uint8_t *pub,
                   const uint8_t *ini,const uint8_t *fin,int dbits,uint64_t ops){
    if(!ruta||!ruta[0]) return 0;
    char tmp[1024];
    snprintf(tmp,sizeof(tmp),"%s.tmp",ruta);
    FILE *f=fopen(tmp,"wb");
    if(!f) return 0;
    pthread_mutex_lock(&t->mtx);
    KgCab c;
    memset(&c,0,sizeof(c));
    /* La version dice desde donde se miden las distancias de los salvajes, que
       es lo unico que cambia con el mapa de negacion. */
    c.magic=KG_MAGIC; c.ver=kg_negacion?KG_VER_GS:KG_VER_SIN_CENTRAR;
    c.dbits=(uint32_t)dbits; c.ops=ops;
    memcpy(c.pub,pub,33); memcpy(c.ini,ini,32); memcpy(c.fin,fin,32);
    c.n=t->guardados;
    int ok=(fwrite(&c,sizeof(c),1,f)==1);
    uint64_t escritas=0;
    if(ok) for(uint64_t i=0;i<=t->mask;i++){
        DP *sl=&t->slots[i];
        if(!sl->usado) continue;
        if(fwrite(sl->kx,8,2,f)!=2){ ok=0; break; }
        if(fwrite(sl->dist,8,4,f)!=4){ ok=0; break; }
        if(fwrite(&sl->manso,1,1,f)!=1){ ok=0; break; }
        if(fwrite(&sl->enviado,1,1,f)!=1){ ok=0; break; }
        escritas++;
    }
    pthread_mutex_unlock(&t->mtx);
    fclose(f);
    if(!ok||escritas!=c.n){ remove(tmp); return 0; }
    if(rename(tmp,ruta)!=0){ remove(tmp); return 0; }
    return 1;
}

/* @return numero de entradas recuperadas, 0 si el fichero no sirve. */
static uint64_t dp_load(DPTable *t,const char *ruta,const uint8_t *pub,
                        const uint8_t *ini,const uint8_t *fin,int dbits,uint64_t *ops){
    if(!ruta||!ruta[0]) return 0;
    FILE *f=fopen(ruta,"rb");
    if(!f) return 0;
    KgCab c;
    if(fread(&c,sizeof(c),1,f)!=1){ fclose(f); return 0; }
    /* Todo tiene que cuadrar: otro puzzle, otro rango u otro criterio de
       distinguido hacen que las entradas no signifiquen lo mismo. */
    /* Se aceptan las tres versiones. La 1 no guardaba la marca de "ya enviado";
       tirar la tabla de alguien por eso seria una faena mucho mayor que el
       ultimo reenvio que provoca. */
    /* Cada motor lee solo lo suyo: Gaudry-Schost la 5, Kangaroo de la 1 a la
       3. Ver KG_VER_GS. */
    int es_gs = (c.ver==KG_VER_GS);
    if(c.magic!=KG_MAGIC || es_gs!=(kg_negacion?1:0) ||
       (c.ver!=KG_VER_GS && c.ver!=KG_VER_SIN_CENTRAR &&
        c.ver!=KG_VER_CON_ENVIADO && c.ver!=KG_VER_SIN_ENVIADO) ||
       c.dbits!=(uint32_t)dbits ||
       memcmp(c.pub,pub,33)!=0 || memcmp(c.ini,ini,32)!=0 || memcmp(c.fin,fin,32)!=0){
        fclose(f); return 0;
    }
    /* Pero una tabla anterior a la 3 en un rango grande esta envenenada entera:
       ver el comentario de KG_VER. Se tira. Es trabajo perdido de verdad —dias
       de movil— y aun asi es lo correcto: lo que hay ahi dentro no puede
       resolver nada, y guardarlo solo sirve para reenviarlo al master para
       siempre. */
    sc_t rango_a,rango_b,ancho,medio;
    sc_from_be32(rango_a,ini); sc_from_be32(rango_b,fin);
    if(!sc_sub(ancho,rango_b,rango_a)){ fclose(f); return 0; }
    sc_shr(medio,ancho,1);
    if(c.ver==KG_VER_SIN_ENVIADO || c.ver==KG_VER_CON_ENVIADO){
        if(sc_bits(ancho)>KG_BITS_SALTOS_ROTOS){ fclose(f); return 0; }
    }
    /* ¿Desde donde mide el fichero las distancias de los salvajes, y desde
       donde las mide este motor? Si no coinciden hay que convertirlas: son
       exactamente W/2 de diferencia, ni una mas. */
    int fichero_centrado = es_gs;
    int motor_centrado   = kg_negacion?1:0;

    /* PERO una tabla de la version 4 no se puede leer sin mapa de negacion.
     *
     * Ahi cada punto esta en eps*(base + d*G) con eps a +1 o -1, y el eps NO se
     * guarda: al resolver se prueban los dos signos. Un motor sin negacion solo
     * prueba uno, asi que de esas entradas interpretaria mal la mayoria.
     *
     * Y no es solo perder trabajo: cuando dp_insert encuentra una pareja del
     * otro rebano avisa y NO GUARDA el punto que llega. O sea que una colision
     * que no cuadra por el signo se come un punto bueno. Mezclar sale peor que
     * no leer.
     *
     * El caso contrario —tabla vieja leida con negacion— si vale: esos puntos
     * son todos eps=+1 y probar los dos signos los incluye. */
    if(fichero_centrado && !motor_centrado){ fclose(f); return 0; }
    int con_enviado = (c.ver!=KG_VER_SIN_ENVIADO);
    uint64_t leidas=0;
    for(uint64_t i=0;i<c.n;i++){
        uint64_t kx[2]; sc_t d; uint8_t manso, env=0;
        if(fread(kx,8,2,f)!=2) break;
        if(fread(d,8,4,f)!=4) break;
        if(fread(&manso,1,1,f)!=1) break;
        if(con_enviado && fread(&env,1,1,f)!=1) break;
        if(!manso && fichero_centrado!=motor_centrado){
            if(motor_centrado) sc_add_n(d,d,medio);   /* de P' a P'' */
            else               sc_sub_n(d,d,medio);   /* de P'' a P' */
        }
        sc_t basura; int bm;
        /* NULL: aqui no hay canguro al que volver a soltar, se esta leyendo un
           fichero. Repetido solo significa que ya estaba. */
        dp_insert(t,kx,d,manso,basura,&bm,NULL);
        /* dp_insert no sabe de esto, asi que se busca el hueco donde ha
           quedado. Igual que hace kg_import con los que llegan por red. */
        if(env){
            uint64_t h=(kx[0]^(kx[1]*0x9E3779B97F4A7C15ULL))&t->mask;
            for(uint64_t j=0;j<=t->mask;j++){
                DP *sl=&t->slots[(h+j)&t->mask];
                if(!sl->usado) break;
                if(sl->kx[0]==kx[0] && sl->kx[1]==kx[1]){ sl->enviado=1; break; }
            }
        }
        leidas++;
    }
    fclose(f);
    if(ops) *ops=c.ops;
    return leidas;
}

/* Pasa un hex a 32 bytes big-endian, alineado a la derecha.
 *
 * OJO CON LA LONGITUD IMPAR. Un numero en hexadecimal no tiene por que tener un
 * numero par de digitos: 2^139 es un 8 seguido de 34 ceros, o sea 35. La version
 * anterior exigia longitud par y rechazaba de plano los rangos de los puzzles
 * #140, #145 y #155 — justo tres de los cinco que admiten Kangaroo. Se rellena
 * con un cero por delante, que es lo que significa.
 *
 * @return 1 si se pudo, 0 si el texto no es hex o no cabe en 32 bytes.
 */
static int kg_hex_a_be32(const char *h, uint8_t *out32){
    if(!h) return 0;
    size_t L=strlen(h);
    /* Admitir el prefijo 0x, que es como vienen escritos en muchas fuentes. */
    if(L>2 && h[0]=='0' && (h[1]=='x'||h[1]=='X')){ h+=2; L-=2; }
    while(L>1 && *h=='0'){ h++; L--; }      /* ceros a la izquierda sobran */
    if(L==0 || L>64) return 0;
    memset(out32,0,32);
    /* Se recorre de derecha a izquierda, asi el digito impar del principio cae
       solo en el nibble alto del primer byte sin tener que copiar la cadena. */
    int byte=31, alto=0;
    for(int i=(int)L-1;i>=0;i--){
        char c=h[i]; int v;
        if(c>='0'&&c<='9') v=c-'0';
        else if(c>='a'&&c<='f') v=c-'a'+10;
        else if(c>='A'&&c<='F') v=c-'A'+10;
        else return 0;
        if(byte<0) return 0;
        if(!alto){ out32[byte]=(uint8_t)v; alto=1; }
        else     { out32[byte]|=(uint8_t)(v<<4); alto=0; byte--; }
    }
    return 1;
}

/* ---------- Contexto de la busqueda ----------
 *
 * Cuantos saltos distintos caben en la tabla. El motor quiere n tal que
 * n - log2(n) = bits/2 - 1, o sea unos bits/2 + 6: para el #140 (139 bits)
 * salen 76, para el #160 hacen falta 86.
 *
 * Estaba en 64, y eso topaba la tabla en TODOS los rangos a partir de 118 bits
 * —#140, #145 y #155, que son justo los unicos para los que se usa Kangaroo—.
 * 128 llega hasta rangos de 245 bits, que es mas de lo que hay. Cuesta 12 KB
 * por contexto, uno por movil. */
#define KG_MAX_JUMPS 128

/* Como se construye la tabla de saltos. 0 = potencias de dos (lo de siempre),
 * 1 = longitudes al azar sacadas de la clave publica.
 *
 * MEDIDO: 2,20 +- 0,05 al azar contra 2,18 +- 0,06 con potencias de dos. Lo
 * mismo. Merecia la pena mirarlo porque con potencias de dos LA MITAD de los
 * saltos son muchisimo mas cortos que la media —en el #140 la media es 2^69 y
 * la mitad de los saltos mueven menos de 2^37—, que no es lo que supone el
 * analisis de siempre. Pero da igual: una colision exige que dos canguros caigan
 * en el MISMO punto, no cerca, asi que lo largo o corto que sea cada salto no
 * cambia la probabilidad. Lo unico que importa es por donde se sueltan, que es
 * lo que arregla politica_salida.
 *
 * Se queda como resultado negativo y se deja el interruptor: si un cambio futuro
 * hiciera que la tabla de saltos SI importara, aqui se veria.
 *
 * Es un interruptor del BANCO, no un ajuste: se pone antes de kg_setup para
 * medir una contra la otra con tools/ec-harness/constante. Va suelto y no
 * dentro del contexto porque kg_setup construye la tabla, o sea que hay que
 * saberlo antes de tener contexto. */
static int kg_politica_saltos = 0;


typedef struct {
    /* Objetivo, ya trasladado a [0,W]: P' = P - a*G */
    JP       objetivo;
    fe_t     obj_x, obj_y;   /* el mismo punto en afin, que es como anda el bucle */
    int      objetivo_ok;
    sc_t     rango_ini;      /* a */
    sc_t     ancho;          /* W = b - a */
    int      bits;           /* log2(W), para dimensionar */
    /* Lo que hay que volver a sumar a la incognita para tener la clave.
     * Sin mapa de negacion es `a` y ya esta. Con el es a + W/2, porque el
     * problema se traslada al CENTRO del intervalo — ver `negacion`. */
    sc_t     desp;
    sc_t     medio;          /* W/2 */

    /* ---- Mapa de negacion ----
     *
     * 0 = apagado (todos los saltos hacia delante, distancias que solo crecen).
     * 1 = encendido.
     *
     * DE DONDE SALE LA GANANCIA. En esta curva -P = (x,-y): un punto y su
     * opuesto comparten la x, o sea que son el mismo a efectos de la tabla de
     * distinguidos, que va indexada por la x. Si ademas el CAMINO se queda
     * siempre con el mismo representante de la pareja {P,-P}, el espacio a
     * recorrer se parte por la mitad y el coste, que va con la raiz, baja en
     * raiz(2) = 1,41.
     *
     * POR QUE HAY QUE TRASLADAR AL CENTRO. La clave esta en [a,b], o sea que la
     * incognita relativa esta en [0,W): toda positiva. Identificar d con -d no
     * dobla nada si todo lo que hay es positivo — el opuesto cae en una zona por
     * la que no pasa nadie. Trasladando el objetivo a a+W/2, la incognita queda
     * en [-W/2, W/2] y su valor absoluto en [0, W/2]: AHI la identificacion si
     * parte el problema por la mitad, y por eso los mansos solo tienen que
     * cubrir W/2 en vez de W.
     *
     * LO QUE CUESTA. Las distancias pasan a poder ir hacia atras, asi que viven
     * en Z_n (ver sc_add_n). Y aparecen los ciclos esteriles: ver `eps` y el
     * escape en kg_run.
     *
     * MEDIDO (tools/ec-harness/constante, 30 bits, 256 canguros, 400 tandas):
     *
     *     sin negacion, tabla de ~20 saltos    2,27
     *     con negacion, tabla de ~20 saltos    1,99     1,14 veces
     *     sin negacion, tabla de 32 saltos     2,33
     *     con negacion, tabla de 32 saltos     1,69     1,38 veces
     *
     * Las dos primeras lineas parecen decir que el mapa de negacion solo vale un
     * 14 %, y no es verdad: lo que frena ahi es el TAMANO DE LA TABLA DE SALTOS
     * del banco. Los ciclos esteriles aparecen una vez cada 2*njumps pasos, asi
     * que con una tabla corta hay que escapar a todas horas y el escape se come
     * la ganancia. Con 32 saltos sale 1,38, que es practicamente el raiz(2)=1,41
     * teorico.
     *
     * En el movil esto no es un problema: la tabla tiene 75 entradas en el #140
     * y 82 en el #155, o sea mas del doble que las 32 con las que ya se mide
     * 1,38. Por eso se deja encendido. */
    int      negacion;
    /* Con negacion (Gaudry-Schost): cuantos saltos de escape hay detras de los
       njumps normales. Ver kg_setup. */
    int      nesc;

    /* Tabla de saltos: S[i] = 2^i * G, en afin */
    int      njumps;
    fe_t     jx[KG_MAX_JUMPS], jy[KG_MAX_JUMPS];
    /* La longitud de cada salto, del mismo tamano que la distancia que va
     * sumando. Era uint64_t, y el salto i vale 2^i: a partir de i=63 no cabia y
     * se guardaba CERO. El punto se movia 2^63*G y la distancia no se movia, asi
     * que el canguro dejaba de estar donde decia su distancia — y como
     * kg_resolver comprueba la clave antes de cantarla, eso no daba una clave
     * falsa: daba que no hay clave, con la tabla llenandose igual y la UI
     * contando millones de claves por segundo. Lo vigila
     * tools/ec-harness/saltos. */
    sc_t     jlen[KG_MAX_JUMPS];

    int      dbits;          /* un punto es distinguido si sus dbits bajos son 0 */
    uint64_t dmask;

    DPTable  tabla;

    /* Resultado */
    std::atomic<int> encontrado;
    sc_t     k;              /* clave privada, ya sumado el inicio del rango */

    std::atomic<long long> saltos;   /* operaciones de grupo hechas, para la UI */
    /* Cuantas veces un canguro ha caido encima del rastro de otro de su mismo
     * rebano. Se cuenta aunque soltar_muertos este apagado: asi el numero mide
     * el fenomeno y no el arreglo, y se puede ver si el arreglo tiene siquiera
     * ocasion de hacer algo. */
    std::atomic<long long> pegados;
    /* Canguros que ha habido que volver a soltar por llevar demasiado tiempo sin
     * dar un punto distinguido. Ver `pasos_sin_dp`. Deberia quedarse en cero o
     * casi; si sube, es que el detector de ciclos no da abasto. */
    std::atomic<long long> rescatados;
    /* Escapes de ciclo esteril, y de esos cuantos salen DEL MISMO PUNTO del que
     * ya salio ese canguro la vez anterior. Medir, no arreglar: el arreglo se
     * intento y se retiro (ver tools/ec-harness/negacion.cpp). */
    std::atomic<long long> escapes;
    std::atomic<long long> escapes_repe;
    std::atomic<int>       parar;
    /* Porcentaje de CPU, 1..100. Kangaroo no tenia freno: el selector de
       potencia estaba puesto pero no hacia nada, y "Baja" calentaba el movil
       igual que "Alta". En algo que va a estar dias encendido eso importa. */
    std::atomic<int>       cpu_limite;

    /* Donde se sueltan los canguros.
     *
     *   0  Reparto ancho (el de siempre). El manso sale de d*G y el salvaje de
     *      P'+d*G, con d al azar en [0,W). Como la clave relativa tambien esta
     *      en [0,W), los salvajes acaban repartidos por [0,2W) mientras los
     *      mansos solo cubren [0,W). Y como todos los saltos van hacia delante,
     *      un salvaje que arranque por encima de W no puede cruzarse jamas con
     *      el rastro de un manso.
     *
     *      MEDIDO (tools/ec-harness/constante): 3,16*raiz(W) de media, y lo que
     *      delata la causa es el reparto por cuartil — 2,60 / 2,40 / 2,74 /
     *      4,91. El coste CRECE segun la clave esta mas arriba del rango, que
     *      es justo lo que predice el solape: los dos rebanos solo coinciden en
     *      [k,W), asi que sale 2/raiz(1-k/W).
     *
     *   1  Rebanos juntos. Los mansos alrededor del centro del intervalo y los
     *      salvajes alrededor de P, los dos con dispersion pequena.
     *
     *      MEDIDO: 30,9*raiz(W). Diez veces peor, y en U: 53,9 / 13,2 / 12,8 /
     *      43,7. Con los mansos amontonados en W/2, un salvaje en k tiene que
     *      RECORRER |k-W/2| para llegar a ellos, y eso no lo reparte tener mas
     *      canguros: los 64 caminan a la vez la misma distancia. Se queda como
     *      resultado negativo, que tambien vale de banco: si un cambio futuro
     *      hace que la 1 deje de ser mala, es que ha roto algo.
     *
     *   2  LA DE AHORA. Mansos por [0, W + W>>salvaje_shift), salvajes por
     *      [0, W>>salvaje_shift). Lo unico que hace falta es que el terreno de
     *      los salvajes quepa ENTERO dentro del de los mansos; entonces no hay
     *      ningun salvaje condenado de salida y el coste deja de depender de
     *      donde este la clave. Se ensancha el de los mansos en vez de mover a
     *      los salvajes porque los salvajes cuelgan de P, que es justo lo que no
     *      se sabe donde esta.
     *
     *      MEDIDO: 2,13*raiz(W) con 256 canguros, y el reparto por cuartil sale
     *      PLANO (1,88 / 2,31 / 2,02 / 2,32). Que se aplane es mas importante
     *      que la media: es la prueba de que la causa era la que se decia.
     *
     * Es un campo y no una constante para poder medir unas contra otras con el
     * mismo banco antes de cambiar la que viene por defecto. Las tres siguen
     * ahi y tools/ec-harness/constante las mide en cada vuelta. */
    int      politica_salida;
    /* Solo para la politica 2: los salvajes se reparten por W>>salvaje_shift.
     * 0 = tan ancho como el intervalo.
     *
     * POR QUE 3. Estrechar a los salvajes concentra las huellas utiles —el
     * terreno de los mansos se acerca a W en vez de 2W— pero a partir de cierto
     * punto los salvajes empiezan a pisarse ENTRE ELLOS, y eso se paga. El campo
     * `pegados` cuenta esos pisotones y se duplican con cada shift, tal cual:
     *
     *    shift        0     1     2     3     4     6
     *    coste     2,74  2,38  2,27  2,18  2,23  2,66     (256 canguros, 400 tandas)
     *    pegados    1,4   2,0   3,1   5,0  10,0  51,0
     *
     * El fondo es plano entre 1 y 4, asi que acertar el numero exacto vale poco:
     * lo que cuesta caro es quedarse en 0 o pasarse a 6. Con rebanos mas pequenos
     * el minimo se corre a 2 (16 y 64 canguros), pero la diferencia esta dentro
     * del error de la medida. Se elige 3 porque es el minimo con el rebano
     * grande, que es como corre en el movil. */
    int      salvaje_shift;
    /* 1 = volver a soltar al canguro que se ha pegado a otro de su rebano.
     * Lo normal es 1. Esta puesto para poder MEDIR cuanto vale ese arreglo:
     * un arreglo que no se puede apagar es un arreglo que no se puede medir, y
     * entonces no se sabe si sigue haciendo algo. */
    int      soltar_muertos;
} KangarooCtx;

/* Multiplicacion escalar sencilla (doblar y sumar). Solo se usa en la
 * preparacion —tabla de saltos y puntos de salida—, nunca en el bucle. */
static void kg_scalar_mul(JP *R,const sc_t k,const fe_t px,const fe_t py){
    int empezado=0;
    JP acc;
    for(int w=3;w>=0;w--){
        for(int b=63;b>=0;b--){
            if(empezado) jp_dbl(&acc,&acc);
            if((k[w]>>b)&1){
                if(!empezado){
                    memcpy(acc.x,px,32); memcpy(acc.y,py,32);
                    memset(acc.z,0,32); acc.z[0]=1;
                    empezado=1;
                }else{
                    jp_add_affine(&acc,&acc,px,py);
                }
            }
        }
    }
    if(!empezado){ memset(R,0,sizeof(JP)); return; }   /* k = 0 -> infinito */
    *R=acc;
}

static void kg_normalize(const JP *P,fe_t x,fe_t y){
    fe_t zi,z2,z3;
    fe_inv(zi,(uint64_t*)P->z);
    fe_sqr(z2,zi); fe_mul(z3,z2,zi);
    fe_mul(x,(uint64_t*)P->x,z2);
    fe_mul(y,(uint64_t*)P->y,z3);
}

/* Prepara la busqueda.
 *
 * @param pub33  clave publica del objetivo
 * @param ini,fin  extremos del intervalo, big-endian de 32 bytes
 * @return 1 si todo bien, 0 si la clave publica no es valida
 */
static int kg_setup(KangarooCtx *c,const uint8_t *pub33,
                    const uint8_t *ini,const uint8_t *fin,int dp_bits,int tabla_bits){
    /* No se puede memset un struct con std::atomic dentro. */
    c->objetivo_ok=0; c->njumps=0; c->dbits=0; c->dmask=0;
    memset(&c->objetivo,0,sizeof(c->objetivo));
    memset(c->jx,0,sizeof(c->jx)); memset(c->jy,0,sizeof(c->jy));
    memset(c->jlen,0,sizeof(c->jlen));
    memset(&c->tabla,0,sizeof(c->tabla));
    sc_zero(c->rango_ini); sc_zero(c->ancho); sc_zero(c->k); c->bits=0;
    sc_zero(c->desp); sc_zero(c->medio); c->negacion=0;
    fe_t px,py;
    if(!kg_decompress(pub33,px,py)) return 0;

    sc_from_be32(c->rango_ini,ini);
    sc_t b; sc_from_be32(b,fin);
    if(!sc_sub(c->ancho,b,c->rango_ini)) return 0;   /* fin < ini */

    /* bits del ancho */
    c->bits=0;
    for(int w=3;w>=0;w--) for(int i=63;i>=0;i--)
        if((c->ancho[w]>>i)&1){ c->bits=w*64+i+1; w=-1; break; }
    if(c->bits<4) return 0;

    sc_shr(c->medio,c->ancho,1);              /* W/2 */
    c->negacion = kg_negacion ? 1 : 0;
    /* Cuanto se traslada el objetivo, y por tanto cuanto hay que volver a sumar
       al final. Sin mapa de negacion, el inicio del rango: la incognita queda en
       [0,W]. Con el, el CENTRO del intervalo: queda en [-W/2, W/2], que es lo
       que permite que identificar d con -d parta el problema por la mitad.
       a + W/2 no puede desbordar: a cabe de sobra en 161 bits en los puzzles que
       existen, y aqui hay 256. */
    if(c->negacion) sc_add(c->desp,c->rango_ini,c->medio);
    else            sc_copy(c->desp,c->rango_ini);

    /* P'' = P - desp*G, para que la incognita sea pequena y las distancias
       tambien. */
    JP P; memcpy(P.x,px,32); memcpy(P.y,py,32); memset(P.z,0,32); P.z[0]=1;
    int es_cero=1;
    for(int i=0;i<4;i++) if(c->desp[i]) es_cero=0;
    if(es_cero){
        c->objetivo=P;
    }else{
        JP aG; kg_scalar_mul(&aG,c->desp,FIELD_GX,FIELD_GY);
        fe_t ax,ay; kg_normalize(&aG,ax,ay);
        fe_t cero,nay; memset(cero,0,32); fe_sub(nay,cero,ay);   /* -desp*G */
        jp_add_affine(&c->objetivo,&P,ax,nay);
        /* Si la clave es EXACTAMENTE el punto de traslado, P'' sale el punto en
           el infinito y el rebano salvaje nace muerto. Pasa una vez entre 2^69,
           pero devolver "no encontrada" ahi seria mentir: la respuesta es desp. */
        int inf=1; for(int i=0;i<4;i++) if(c->objetivo.z[i]) inf=0;
        if(inf){
            sc_copy(c->k,c->desp);
            c->objetivo_ok=1;
            c->dbits=dp_bits; c->dmask=0;
            if(!dp_init(&c->tabla,4)) return 0;
            c->encontrado.store(1); c->saltos.store(0); c->pegados.store(0);
            c->rescatados.store(0);
            c->escapes.store(0); c->escapes_repe.store(0);
            c->parar.store(0);
            c->cpu_limite.store(100);
            /* Aunque ya este resuelto, kg_run puede llamarse igual y suelta el
               rebano ANTES de mirar si hay que parar. Si estos campos se quedan
               sin poner, lee basura: un salvaje_shift negativo sale del array
               en sc_shr. Salir por la puerta de atras no exime de dejar el
               struct entero. */
            c->politica_salida=0; c->salvaje_shift=0; c->soltar_muertos=1;
            return 1;
        }
    }
    c->objetivo_ok=1;

    /* ---- Tabla de saltos ----
     *
     * TIENE QUE SALIR IGUAL EN TODOS LOS APARATOS DEL CLUSTER.
     *
     * El salto depende solo de donde esta el canguro. Si dos aparatos usaran
     * tablas distintas, un manso de uno y un salvaje de otro podrian cruzarse
     * en un punto y separarse en el salto siguiente sin que ninguno lo apunte:
     * la colision solo se veria si cayera JUSTO en un punto distinguido, o sea
     * una vez de cada 2^dbits. Con dbits 28 eso es tirar el reparto entero.
     *
     * Por eso la tabla se saca de cosas que los dos lados ya comparten —el
     * ancho del rango, y en la politica 1 la clave publica— y nunca de un
     * generador del aparato.
     *
     * El salto medio conviene que ronde raiz(W)/2: mas corto y los canguros
     * tardan en separarse, mas largo y se pasan de largo de la zona util. */
    c->nesc=0;
    if(kg_negacion){
        /* ---- Gaudry-Schost con negacion: la tabla ----
         *
         * 64 saltos normales y 64 de escape, al azar, sacados de la clave
         * publica como en la politica 1 (todos los aparatos del cluster
         * construyen la MISMA). Todos del mismo tamano, de media 2^(lb-1) con
         *
         *     lb = 1,5 * dbits + 11
         *
         * y esa cifra no es de adorno: es lo que hace que funcione. Con
         * negacion el camino no avanza, va y viene —la distancia hace un paseo
         * al azar—, y un paseo al azar de L pasos con saltos de tamano m cubre
         * unos m*raiz(L) numeros. Si en ese trozo caben pocos mas que L, el
         * camino vuelve a pisar un numero por el que ya paso, y en un camino
         * determinista eso es un ciclo del que no sale. Para que no pase, m
         * tiene que ser mucho mayor que L^1,5, con L = 2^dbits. Con los saltos
         * de raiz(W)/2 de Kangaroo, en el #40 con dbits 14 salian 17 puntos
         * distinguidos donde tocaban 244.
         *
         * Los de escape son otra tabla, elegida por el punto (ver kg_run). */
        int n=64; if(n>KG_MAX_JUMPS/2) n=KG_MAX_JUMPS/2;
        c->njumps=n;
        c->nesc=KG_MAX_JUMPS-n;
        uint64_t s=0xA5A5A5A5DEADBEEFULL;
        for(int i=0;i<33;i++) s=s*0x100000001B3ULL ^ (uint64_t)pub33[i];
        /* +11 y no +8: barrido con un banco de 2.400 claves (28-30 bits,
           dbits 5), saltos 8 veces mas largos bajan el coste de 1,47 a 1,43
           raices de W: el paseo se pisa todavia menos. Mas no mejora. */
        int lb=(3*dp_bits)/2+11; if(lb>c->bits-3) lb=c->bits-3; if(lb<4) lb=4;
        for(int i=0;i<n+c->nesc;i++){      /* detras, los de escape */
            sc_zero(c->jlen[i]);
            int lbi = lb;
            for(int w=0;w<4;w++){
                s+=0x9E3779B97F4A7C15ULL;
                uint64_t z=s;
                z=(z^(z>>30))*0xBF58476D1CE4E5B9ULL;
                z=(z^(z>>27))*0x94D049BB133111EBULL;
                c->jlen[i][w]=z^(z>>31);
            }
            int top=(lbi-1)/64, sh=(lbi-1)%64;
            for(int w=3;w>top;w--) c->jlen[i][w]=0;
            if(sh<63) c->jlen[i][top]&=((1ULL<<(sh+1))-1);
            if(sc_bits(c->jlen[i])==0) sc_set_u64(c->jlen[i],1);
            JP S; kg_scalar_mul(&S,c->jlen[i],FIELD_GX,FIELD_GY);
            kg_normalize(&S,c->jx[i],c->jy[i]);
        }
    }else
    if(kg_politica_saltos==1){
        /* Longitudes al azar entre 1 y 2^(bits/2), o sea de media raiz(W)/2,
           que es lo que se quiere. El azar sale de la clave publica con
           splitmix64: es determinista y los dos lados del cluster tienen la
           misma clave publica, asi que construyen la MISMA tabla. Con 32 saltos
           basta para que el camino sea impredecible. */
        int n=32; if(n>KG_MAX_JUMPS-1) n=KG_MAX_JUMPS-1;
        c->njumps=n;
        uint64_t s=0xA5A5A5A5DEADBEEFULL;
        for(int i=0;i<33;i++) s=s*0x100000001B3ULL ^ (uint64_t)pub33[i];
        int lb=c->bits/2; if(lb<2) lb=2; if(lb>250) lb=250;
        for(int i=0;i<n;i++){
            sc_zero(c->jlen[i]);
            for(int w=0;w<4;w++){
                s+=0x9E3779B97F4A7C15ULL;
                uint64_t z=s;
                z=(z^(z>>30))*0xBF58476D1CE4E5B9ULL;
                z=(z^(z>>27))*0x94D049BB133111EBULL;
                c->jlen[i][w]=z^(z>>31);
            }
            int top=(lb-1)/64, sh=(lb-1)%64;
            for(int w=3;w>top;w--) c->jlen[i][w]=0;
            if(sh<63) c->jlen[i][top]&=((1ULL<<(sh+1))-1);
            if(sc_bits(c->jlen[i])==0) sc_set_u64(c->jlen[i],1);  /* 0*G no vale */
            JP S; kg_scalar_mul(&S,c->jlen[i],FIELD_GX,FIELD_GY);
            kg_normalize(&S,c->jx[i],c->jy[i]);
        }
    }else{
        /* Potencias de dos: S[i] = 2^i * G, que se saca doblando y no cuesta
           nada. La media de i=0..n-1 es (2^n - 1)/n, asi que hay que resolver
           n - log2(n) = bits/2 - 1. Se hace por iteracion, que converge en
           cuatro vueltas. */
        double objetivo=(double)c->bits/2.0-1.0;
        double nd=objetivo>1.0?objetivo:1.0;
        for(int it=0;it<40;it++){
            double l=0; double v=nd>1.0?nd:1.0;
            while(v>=2.0){ v/=2.0; l+=1.0; }
            l+=v-1.0;                       /* log2 aproximado, sobra de sobra */
            nd=objetivo+l;
        }
        int n=(int)(nd+0.5);
        /* -1: la ultima casilla se reserva para el salto de escape. */
        if(n>KG_MAX_JUMPS-1) n=KG_MAX_JUMPS-1;
        if(n<4) n=4;
        c->njumps=n;
        JP S; memcpy(S.x,FIELD_GX,32); memcpy(S.y,FIELD_GY,32);
        memset(S.z,0,32); S.z[0]=1;
        for(int i=0;i<n;i++){
            kg_normalize(&S,c->jx[i],c->jy[i]);
            sc_zero(c->jlen[i]); c->jlen[i][i/64]=1ULL<<(i%64);
            jp_dbl(&S,&S);
        }
    }

    /* ---- El salto de escape ----
     *
     * Va en la casilla njumps, fuera de la tabla que usa el camino normal, y
     * solo lo usa el escape de un ciclo esteril. Que NO este en la tabla es
     * justo lo que lo hace funcionar, y costo una tarde verlo:
     *
     * Un ciclo de dos aparece cuando, tras darle la vuelta al punto, el salto
     * que toca es el MISMO que se acaba de usar. Si el escape usara otro indice
     * de la tabla, la vuelta al mismo ciclo volveria a ser posible con
     * probabilidad 1/njumps — y como el escape es determinista, al fallar una
     * vez falla siempre: el canguro se queda atrapado para siempre, andando y
     * sin llegar a ningun punto distinguido. Medido antes de esto: 27.435
     * saltos por punto guardado, cuando tocaban 64.
     *
     * Con un salto que no esta en la tabla, volver al mismo sitio exigiria que
     * algun salto de la tabla valiera exactamente lo mismo que el de escape. Se
     * elige uno que no coincide con ninguno, asi que no puede pasar.
     *
     * La longitud es pequena a proposito: lo unico que tiene que hacer es sacar
     * al canguro del ciclo, y desde el punto nuevo el camino sigue como siempre.
     */
    if(!kg_negacion)
    {
        sc_t le; sc_set_u64(le,3);         /* 3 no es potencia de dos */
        for(int intento=0;intento<64;intento++){
            int choca=0;
            for(int i=0;i<c->njumps;i++) if(sc_cmp(c->jlen[i],le)==0){ choca=1; break; }
            if(!choca) break;
            sc_add_u64(le,2);
        }
        sc_copy(c->jlen[c->njumps],le);
        JP E; kg_scalar_mul(&E,le,FIELD_GX,FIELD_GY);
        kg_normalize(&E,c->jx[c->njumps],c->jy[c->njumps]);
    }

    c->dbits=dp_bits; c->dmask=(dp_bits>=64)?~0ULL:((1ULL<<dp_bits)-1);
    if(!dp_init(&c->tabla,tabla_bits)) return 0;
    c->encontrado.store(0);
    c->saltos.store(0);
    c->pegados.store(0);
    c->rescatados.store(0);
    c->escapes.store(0); c->escapes_repe.store(0);
    c->parar.store(0);
    c->cpu_limite.store(100);
    c->politica_salida=2;
    c->salvaje_shift=3;
    c->soltar_muertos=1;
    kg_normalize(&c->objetivo,c->obj_x,c->obj_y);
    return 1;
}

static void kg_free(KangarooCtx *c){ dp_free(&c->tabla); }

/* ---------- Resolver una colision ----------
 *
 * Dos canguros de rebanos distintos han caido en el mismo punto. El manso va
 * por d_manso*G desde el origen; el salvaje por P' + d_salvaje*G. Si estan en
 * el mismo sitio, P' = (d_manso - d_salvaje)*G, o sea la clave es esa resta.
 *
 * Estaba dentro de kg_run. Se saca porque ahora hay DOS sitios que encuentran
 * colisiones: los canguros de este movil, y los puntos que llegan por la red de
 * otro. La cuenta es la misma y no debe haber dos copias de ella.
 *
 * Se comprueba SIEMPRE antes de cantar victoria: kp*G tiene que dar exactamente
 * el objetivo. Dos caminos que se cruzan sin pegarse dan una resta que parece
 * buena y no lo es.
 *
 * @return 1 si la clave era buena y queda guardada en c->k.
 */
/* Prueba UN candidato: ¿es kp la incognita trasladada?
 *
 * Dos filtros, y los dos hacen falta:
 *   - kp*G tiene que dar el objetivo. Esto NO distingue kp de kp-n, porque
 *     n*G es el infinito y los dos dan el mismo punto.
 *   - la clave resultante tiene que caer dentro de [a,b]. Esto si los
 *     distingue, y es lo que descarta las restas que se fueron en negativo.
 */
static int kg_resolver_uno(KangarooCtx *c,const sc_t kp){
    JP chk; kg_scalar_mul(&chk,kp,FIELD_GX,FIELD_GY);
    int inf=1; for(int z=0;z<4;z++) if(chk.z[z]) inf=0;
    if(inf) return 0;
    fe_t cx,cy; kg_normalize(&chk,cx,cy);
    if(memcmp(cx,c->obj_x,32)!=0 || memcmp(cy,c->obj_y,32)!=0) return 0;
    sc_t kfinal; sc_add_n(kfinal,kp,c->desp);
    sc_t rel;
    if(!sc_sub(rel,kfinal,c->rango_ini)) return 0;   /* por debajo de a */
    if(sc_cmp(rel,c->ancho)>0) return 0;             /* por encima de b */
    sc_copy(c->k,kfinal);
    c->encontrado.store(1);
    return 1;
}

static int kg_resolver(KangarooCtx *c,const sc_t d_mio,int manso_mio,
                       const sc_t d_otro){
    sc_t manso,salvaje;
    if(manso_mio){ sc_copy(manso,d_mio); sc_copy(salvaje,d_otro); }
    else         { sc_copy(manso,d_otro); sc_copy(salvaje,d_mio); }

    sc_t kp;
    sc_sub_n(kp,manso,salvaje);
    if(kg_resolver_uno(c,kp)) return 1;

    /* Con mapa de negacion, ninguno de los dos guarda si esta en su punto o en
     * el opuesto: no hace falta. El manso esta en e_m*d_m*G y el salvaje en
     * e_s*(P'' + d_s*G); igualando y multiplicando por e_s sale
     *
     *     P'' = (e_m*e_s*d_m - d_s) * G
     *
     * o sea que solo cuenta el PRODUCTO de los dos signos: hay dos candidatos,
     * d_m - d_s y -d_m - d_s. Se prueban los dos y kg_resolver_uno se queda con
     * el que cuadre. Por eso el signo no viaja por la red ni ocupa sitio en la
     * tabla: sale mas barato probar dos veces una vez que guardarlo siempre. */
    if(c->negacion){
        sc_t nm; sc_neg_n(nm,manso);
        sc_sub_n(kp,nm,salvaje);
        if(kg_resolver_uno(c,kp)) return 1;
    }
    return 0;
}

/* ---------- Reparto por red ----------
 *
 * POR QUE SE COMPARTE LA TABLA Y NO EL RANGO.
 *
 * Con fuerza bruta, partir el rango en N trozos y dar uno a cada movil divide
 * el tiempo entre N: el coste es O(W) y cada uno hace W/N.
 *
 * Con Kangaroo no, porque el coste es O(raiz(W)). Partir el intervalo en N deja
 * cada trozo en raiz(W/N), pero hay que recorrer varios trozos porque no se
 * sabe en cual esta la clave: de media sale peor que no partir nada. Con N=2 el
 * coste esperado es 1,06*raiz(W) frente a 1,00 de un solo aparato; con N=8, 1,59.
 * O sea: repartir el rango EMPEORA la busqueda.
 *
 * Lo que si funciona es lo que ya hacen los hilos dentro de un movil: TODOS en
 * el MISMO intervalo, compartiendo una tabla de distinguidos. Asi el reparto es
 * casi lineal, porque la colision se encuentra N veces antes.
 *
 * Entre aparatos es lo mismo: cada uno camina el intervalo entero con sus
 * propios canguros y manda al master los distinguidos que va encontrando. El
 * master los mete todos en una sola tabla, y ahi aparece la colision aunque las
 * dos mitades vengan de moviles distintos.
 *
 * AVISO: lo que viaja son pares (punto, distancia). Dos de rebanos distintos que
 * coincidan DAN LA CLAVE. Es material de clave, y no hay forma de evitarlo sin
 * perder el beneficio entero. Va con codigo de acceso y pensado para una red
 * propia; no lo uses en una red que no controles.
 */

#define KG_NET_MAGIC 0x5044474BU   /* "KGDP" */
/* Version 2: los puntos vienen de una tabla de saltos que cuadra.
 *
 * Se sube por lo mismo que KG_VER pasa a 3 (ver alli). Un aparato sin
 * actualizar manda puntos cuya distancia no corresponde con el punto, y el
 * master no tiene forma de distinguirlos de los buenos: entrarian en la tabla y
 * se quedarian ahi ocupando sitio sin poder resolver nada.
 *
 * El precio es que un movil sin actualizar deja de contribuir y se le nota
 * —"Puntos recibidos" se queda quieto—, que es justo lo que hay que ver. La
 * alternativa era un cluster con buena pinta que no puede encontrar nada, que
 * es de donde venimos. */
/* Version 3: las distancias de los salvajes van medidas desde el centro, por el
 * mapa de negacion (ver KG_VER). Aqui no se convierte al vuelo como en el
 * fichero: dos aparatos que no coincidan tienen caminos distintos y ademas no se
 * cruzarian bien, asi que es mejor que se rechacen y se vea. */
/* Version 4: Gaudry-Schost con negacion. Sus puntos no le sirven a un aparato
 * con Kangaroo ni al reves (ver KG_VER_GS), asi que un aparato sin actualizar
 * se rechaza, igual que en la 2. */
#define KG_NET_VER   5u   /* 5: la tabla de saltos de KG_VER_GS 6 */
/* Cabecera: magic, ver, pub, ini, fin, dbits, n. Campo a campo, sin volcar el
   struct, para que el relleno del compilador no forme parte del formato. */
#define KG_NET_CAB   (4+4+33+32+32+4+4)
#define KG_NET_ENT   (16+32+1)     /* kx[2], dist[4], manso */

static void kg_put32(uint8_t *p,uint32_t v){
    p[0]=(uint8_t)v; p[1]=(uint8_t)(v>>8); p[2]=(uint8_t)(v>>16); p[3]=(uint8_t)(v>>24);
}
static uint32_t kg_get32(const uint8_t *p){
    return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);
}
static void kg_put64(uint8_t *p,uint64_t v){
    for(int i=0;i<8;i++) p[i]=(uint8_t)(v>>(8*i));
}
static uint64_t kg_get64(const uint8_t *p){
    uint64_t v=0; for(int i=0;i<8;i++) v|=((uint64_t)p[i])<<(8*i); return v;
}

/* Cuantos bytes ocupa un envio de n entradas. Lo usa quien reserva el buffer. */
static size_t kg_export_bytes(uint32_t n){ return KG_NET_CAB+(size_t)n*KG_NET_ENT; }

/* Vuelca a `buf` los distinguidos que aun no se han mandado y los marca.
 *
 * @param max_ent tope de entradas por envio, para que un mensaje no se haga
 *                enorme. Lo que no quepa sale en el siguiente.
 * @return bytes escritos, o 0 si no habia nada nuevo o no cabia la cabecera.
 */
static size_t kg_export(DPTable *t,const uint8_t *pub,const uint8_t *ini,
                        const uint8_t *fin,int dbits,
                        uint8_t *buf,size_t cap,uint32_t max_ent){
    if(!buf || cap<KG_NET_CAB) return 0;
    uint32_t caben=(uint32_t)((cap-KG_NET_CAB)/KG_NET_ENT);
    if(max_ent && caben>max_ent) caben=max_ent;
    if(caben==0) return 0;

    uint8_t *p=buf+KG_NET_CAB;
    uint32_t n=0;
    pthread_mutex_lock(&t->mtx);
    for(uint64_t i=0;i<=t->mask && n<caben;i++){
        DP *s=&t->slots[i];
        if(!s->usado || s->enviado) continue;
        kg_put64(p,s->kx[0]); kg_put64(p+8,s->kx[1]);
        for(int w=0;w<4;w++) kg_put64(p+16+8*w,s->dist[w]);
        p[48]=s->manso;
        p+=KG_NET_ENT;
        s->enviado=1;
        n++;
    }
    pthread_mutex_unlock(&t->mtx);
    if(n==0) return 0;

    kg_put32(buf,KG_NET_MAGIC);
    kg_put32(buf+4,KG_NET_VER);
    memcpy(buf+8,pub,33);
    memcpy(buf+41,ini,32);
    memcpy(buf+73,fin,32);
    kg_put32(buf+105,(uint32_t)dbits);
    kg_put32(buf+109,n);
    return KG_NET_CAB+(size_t)n*KG_NET_ENT;
}

/* Mete en la tabla los distinguidos que vienen de otro aparato.
 *
 * La cabecera tiene que cuadrar del todo —mismo puzzle, mismo rango, mismo
 * criterio de distinguido—: si no, las entradas no significan lo mismo y
 * mezclarlas daria colisiones que no dicen nada. Es la misma comprobacion que
 * hace dp_load con el fichero de guardado, y por el mismo motivo.
 *
 * Los que entran se marcan como YA ENVIADOS: vienen de fuera, asi que
 * reenviarlos solo seria devolverle al master lo que ya tiene.
 *
 * @param n_ok si no es NULL, cuantas entradas se han metido.
 * @return 1 si el bloque era valido, 0 si se rechaza entero.
 */
static int kg_import(KangarooCtx *c,const uint8_t *pub,const uint8_t *ini,
                     const uint8_t *fin,int dbits,
                     const uint8_t *buf,size_t len,uint32_t *n_ok){
    if(n_ok) *n_ok=0;
    if(!buf || len<KG_NET_CAB) return 0;
    if(kg_get32(buf)!=KG_NET_MAGIC || kg_get32(buf+4)!=KG_NET_VER) return 0;
    if(memcmp(buf+8,pub,33)!=0)  return 0;
    if(memcmp(buf+41,ini,32)!=0) return 0;
    if(memcmp(buf+73,fin,32)!=0) return 0;
    if(kg_get32(buf+105)!=(uint32_t)dbits) return 0;

    uint32_t n=kg_get32(buf+109);
    /* El mensaje viene de fuera: no fiarse de `n`. Tiene que cuadrar con el
       tamano real, o el bucle leeria fuera del buffer. */
    if((size_t)n>(len-KG_NET_CAB)/KG_NET_ENT) return 0;

    const uint8_t *p=buf+KG_NET_CAB;
    /* Cuantas entran DE VERDAD, no cuantas llegan.
     *
     * Antes se contaba una por entrada del mensaje, entrara o no. Con la tabla
     * reenviada por completo tras cada pausa, el maestro decia "Puntos
     * recibidos: 12" con 4 en la tabla — y el que mira no tiene forma de saber
     * cual de los dos numeros es el bueno. */
    uint64_t antes_guardados=c->tabla.guardados;
    for(uint32_t i=0;i<n;i++,p+=KG_NET_ENT){
        uint64_t kx[2]; sc_t d;
        kx[0]=kg_get64(p); kx[1]=kg_get64(p+8);
        for(int w=0;w<4;w++) d[w]=kg_get64(p+16+8*w);
        int manso=p[48]?1:0;

        sc_t otro; int otro_manso;
        /* NULL: el canguro pegado es de OTRO aparato. Aqui no se le puede
           soltar; ya lo hara el suyo cuando le toque. */
        if(dp_insert(&c->tabla,kx,d,manso,otro,&otro_manso,NULL)){
            /* Colision. Puede ser contra un punto de este movil o contra otro
               que llego antes por la red: da igual, la cuenta es la misma. */
            kg_resolver(c,d,manso,otro);
        }
        /* Marcarlo como ya enviado para no devolverlo. dp_insert no lo sabe,
           asi que se busca el hueco donde ha quedado. */
        pthread_mutex_lock(&c->tabla.mtx);
        {
            uint64_t h=(kx[0]^(kx[1]*0x9E3779B97F4A7C15ULL))&c->tabla.mask;
            for(uint64_t j=0;j<=c->tabla.mask;j++){
                DP *s=&c->tabla.slots[(h+j)&c->tabla.mask];
                if(!s->usado) break;
                if(s->kx[0]==kx[0] && s->kx[1]==kx[1]){ s->enviado=1; break; }
            }
        }
        pthread_mutex_unlock(&c->tabla.mtx);
    }
    if(n_ok) *n_ok=(uint32_t)(c->tabla.guardados-antes_guardados);
    return 1;
}

/* Un canguro: donde esta y cuanto lleva recorrido.
 *
 * EN AFIN, no en Jacobiano. El bucle anterior sumaba en Jacobiano y despues
 * normalizaba por lotes para poder leer la x — unas 15 multiplicaciones de
 * cuerpo por salto. Sumando directamente en afin, con la inversion tambien por
 * lotes, salen 6:
 *
 *   inversion amortizada (Montgomery, 3 mul por punto)   3
 *   lambda = (y2-y1) * inv                               1
 *   x3 = lambda^2 - x1 - x2                              1
 *   y3 = lambda*(x1-x3) - y1                             1
 *
 * La inversion por lotes ya estaba; lo que sobraba era convertir de ida y
 * vuelta entre las dos representaciones en cada paso. */
/* ---------- Lo que queda por hacer: el mapa de negacion ----------
 *
 * Es la mejora grande que sigue sin estar, asi que conviene dejar la cuenta
 * hecha y no tener que rehacerla: vale raiz(2) = 1,41 veces, o sea pasar de
 * 2,18 a 1,54 raices de W.
 *
 * LA IDEA. En esta curva -P = (x, -y): un punto y su opuesto comparten la x.
 * Como el salto se elige con la x, si cada canguro se queda siempre con el
 * representante "canonico" de la pareja {P,-P} el espacio de busqueda se parte
 * por la mitad, y el coste va con la raiz.
 *
 * LO QUE CUESTA, que es mas de lo que parece:
 *
 *  1. Las distancias pasan a ser mod n. Al cambiar P por -P hay que cambiar d
 *     por -d, y sc_t no tiene signo. Hace falta el orden del grupo —que hoy no
 *     esta en el fichero— y suma y resta moduladas en el bucle.
 *
 *  2. El salvaje no cumple la invariante. Un manso va por d*G y negarlo sigue
 *     siendo un manso (-d)*G. Un salvaje va por P' + d*G, y su opuesto
 *     -P' - d*G ya no tiene esa forma. Hay que llevar un signo aparte, con la
 *     invariante Pos = e*(Base + d*G), y el salto pasa a ser d += e*jlen. No
 *     hace falta guardar ese signo en la tabla: al resolver salen dos
 *     candidatos, k = d_manso - d_salvaje y k = -d_manso - d_salvaje, y
 *     kg_resolver YA comprueba la clave contra el objetivo antes de cantarla,
 *     asi que se prueban los dos y se queda el que cuadre. Eso no cambia ni el
 *     formato del fichero ni el de red.
 *
 *  3. LOS CICLOS ESTERILES, que es el problema de verdad. Sale de la propia
 *     cuenta: desde Pos canonico se salta a Q = Pos + S_h. Si al canonizar hay
 *     que darle la vuelta, el siguiente es -Q; pero -Q tiene la MISMA x que Q,
 *     o sea el mismo salto h, y -Q + S_h = -Pos, que al canonizar vuelve a ser
 *     Pos. O sea que CADA vez que la negacion actua —una de cada dos— el
 *     canguro entra en un ciclo de dos y se queda ahi para siempre. Sin
 *     tratarlo, el motor no anda: hay que detectarlo y escapar con otro salto.
 *
 *  4. Y el escape tiene que ser el MISMO en todos los aparatos y depender solo
 *     del punto. Si depende de por donde venia el canguro, dos que se hayan
 *     juntado pueden separarse en el escape y no llegar nunca al mismo
 *     distinguido — que es justo el fallo que no deja rastro y que costo esta
 *     semana encontrar en la tabla de saltos.
 *
 * Por eso no se hace de pasada. Cuando se haga: primero extender
 * tools/ec-harness/saltos a la invariante con signo, y despues mirar
 * tools/ec-harness/constante. Si los ciclos se estan comiendo la ganancia, el
 * 1,54 no aparece y se ve en el acto. */

/* Cuantas posiciones recuerda cada canguro para detectar ciclos esteriles.
 * El reparto medido de longitudes esta en el comentario de `ventana`. */
#ifndef KG_VENTANA          /* el banco lo varia con -D */
#define KG_VENTANA 16
#endif

typedef struct {
    fe_t x, y;
    sc_t dist;
    int  manso;
    /* ---- Solo con mapa de negacion ----
     *
     * La invariante deja de ser "el canguro esta en base + dist*G" y pasa a ser
     *
     *     posicion = eps * (base + dist*G),   eps = +1 o -1
     *
     * porque quedarse con el representante canonico de {P,-P} le da la vuelta al
     * punto. Un salto suma eps*jlen a la distancia; canonizar cambia eps y deja
     * la distancia igual. El eps NO se guarda en la tabla: al resolver salen dos
     * candidatos y se prueban los dos. */
    int  eps;

    /* Ciclos esteriles. Desde un punto P se salta a Q = P + S_h; si al canonizar
     * hay que darle la vuelta, se sigue en -Q, que tiene la MISMA x que Q y por
     * tanto el mismo salto h. Si ademas ese h coincide con el de P —una vez de
     * cada njumps— entonces -Q + S_h = -P, que al canonizar vuelve a ser P: el
     * canguro se queda dando vueltas entre dos puntos para siempre.
     *
     * Pasa una vez de cada 2*njumps pasos, asi que no es raro: sin tratarlo el
     * rebano se queda parado y la busqueda no avanza, con la UI contando saltos
     * igual que siempre.
     *
     * Se detecta comparando con la x de hace dos pasos. Para escapar hay que
     * saltar con otro indice, y AQUI ESTA LO DELICADO: el escape tiene que
     * depender solo del punto, nunca de por donde se venia. Si dos canguros que
     * se han juntado escapan distinto, se separan y ya no llegan al mismo punto
     * distinguido — la colision se pierde sin que nada lo diga. Por eso se
     * escapa siempre desde el MISMO punto del ciclo: el menor de todos ellos.
     * Dos canguros que caigan en el ciclo, vengan de donde vengan, acaban en el
     * mismo sitio.
     *
     * POR QUE UNA VENTANA Y NO SOLO EL PASO ANTERIOR. Los ciclos no son todos de
     * longitud dos. Medido a tamano real (njumps=75), sobre 20.000 canguros
     * sueltos sin detector ninguno:
     *
     *     longitud   2      3     4    5   6   mas
     *     veces    19442   406   136  15   1    0
     *
     * El 97 % son de dos, pero la cola importa: entre dos puntos distinguidos
     * hay ~1,7 millones de ciclos, asi que fallar uno de cada mil seria fallar
     * mil veces. Con el reparto medido, una ventana de 16 falla menos de una vez
     * entre 10^12, que sobra. (Con njumps=15 salian ciclos de 82; eran artefacto
     * del rango de juguete, no del algoritmo.)
     *
     * Se guarda (x[0],x[1]) y no la x entera: son los mismos 16 bytes con los
     * que la tabla de distinguidos identifica un punto. */
    uint64_t ventana[KG_VENTANA][2];
    int  vn;              /* cuantas posiciones hay guardadas */
    int  vpos;            /* siguiente hueco del anillo */
    uint64_t esc_obj[2];  /* el punto del ciclo desde el que hay que escapar */
    int  esc_act;         /* 1 = hay un escape pendiente */
    uint64_t ult_esc[2];  /* de donde escapo la vez anterior (solo para medir) */
    int  ult_esc_ok;

    /* Pasos desde el ultimo punto distinguido.
     *
     * RED DE SEGURIDAD. La ventana de arriba caza los ciclos que se han medido,
     * pero no puede prometer que los caza todos: un ciclo mas largo que la
     * ventana deja al canguro dando vueltas PARA SIEMPRE, andando y sin producir
     * nada. Y kg_run no tiene forma de salir de ahi, asi que en un rango pequeno
     * —donde la tabla de saltos es corta y los ciclos largos son mas frecuentes—
     * el motor se queda colgado. Paso de verdad: la prueba `semilla`, que va en
     * 22 bits, estuvo diez minutos sin terminar.
     *
     * Un punto distinguido sale cada 2^dbits pasos de media, y la espera es
     * geometrica: pasar de 20 veces esa media tiene probabilidad e^-20, o sea
     * dos entre mil millones. Asi que si se pasa, no es mala suerte: es que el
     * canguro no va a ningun sitio. Se le vuelve a soltar y el motor sigue.
     *
     * Lo que se pierde es su rastro desde el ultimo distinguido. Lo que se gana
     * es que no haya ningun camino por el que esto se quede parado. */
    uint64_t pasos_sin_dp;
    uint64_t gs_esc[4][2];   /* ultimos puntos de escape (negacion) */
    int gs_ep;
} Kangaroo;

/* Suelta un rebano y lo hace saltar hasta que aparezca la solucion o se pare.
 *
 * Se puede llamar desde varios hilos a la vez con la MISMA ctx: la tabla de
 * distinguidos lleva su propio cerrojo. Cada hilo trae su propia semilla para
 * que los rebanos no salgan todos del mismo sitio.
 */
static void kg_run(KangarooCtx *c,int n_kang,uint64_t semilla){
    if(!c->objetivo_ok || n_kang<1) return;
    Kangaroo *K=(Kangaroo*)calloc(n_kang,sizeof(Kangaroo));
    fe_t *den=(fe_t*)calloc(n_kang,sizeof(fe_t));   /* x2 - x1 de cada uno */
    fe_t *pfx=(fe_t*)calloc(n_kang,sizeof(fe_t));
    int  *jmp=(int*)calloc(n_kang,sizeof(int));
    /* Canguros a los que no se les ha podido calcular el denominador en esta
       vuelta. Ver mas abajo: en vez de dejarles dar un salto inventado, se les
       vuelve a soltar. */
    int  *mal=(int*)calloc(n_kang,sizeof(int));
    if(!K||!den||!pfx||!jmp||!mal){
        free(K);free(den);free(pfx);free(jmp);free(mal); return; }

    /* xorshift: aqui no hace falta un generador criptografico, solo que los
       puntos de salida esten repartidos. */
    uint64_t rng=semilla?semilla:0x2545F4914F6CDD1DULL;
    #define NEXT() (rng^=rng<<13, rng^=rng>>7, rng^=rng<<17, rng)

    /* Coloca (o recoloca) un canguro en un punto de salida al azar. */
    /* Dispersion de salida para la politica 1: bastante para que los canguros
       no se pisen desde el primer salto, pero pequena frente a W. Se toma
       (salto medio) * (numero de canguros) * 8, en potencias de dos. */
    int disp_bits;
    {
        int lm = c->njumps - 1;              /* log2 del salto medio, aprox */
        int ln = 0; while((1<<ln) < n_kang && ln < 20) ln++;
        disp_bits = lm + ln + 3;
        if(disp_bits > c->bits-2) disp_bits = c->bits-2;   /* nunca mas de W/4 */
        if(disp_bits < 1) disp_bits = 1;
    }
    /* Terrenos de la politica 2: los salvajes por [0, disp_s) y los mansos por
       [0, rango_m) con rango_m = W + disp_s, que es lo que hace falta para que
       el terreno de los salvajes quepa entero dentro del de los mansos. */
    /* Cuanto puede estar un canguro sin dar un punto distinguido antes de darlo
       por atrapado. 20 veces la media, que en una espera geometrica es e^-20.
       Se calcula UNA vez y con cuidado: 20<<dbits desborda los 64 bits a partir
       de dbits=59, y al desbordar no da un numero grande sino uno pequeno, con
       lo que el motor se pondria a resoltar canguros sin parar y no produciria
       nada. Justo el tipo de fallo que esta red viene a evitar. */
#ifndef KG_FACTOR_SIN_DP    /* el banco lo baja con -D para ver la trampa */
#define KG_FACTOR_SIN_DP 20ULL
#endif
    uint64_t tope_sin_dp = (c->dbits<58) ? (KG_FACTOR_SIN_DP<<c->dbits) : ~0ULL;

    sc_t disp_s, rango_m;
    /* El terreno a cubrir: W normalmente, W/2 con mapa de negacion —el objetivo
       esta trasladado al centro, asi que la incognita en valor absoluto no pasa
       de W/2—. Esa mitad es exactamente de donde sale el raiz(2). */
    sc_t terreno;
    if(c->negacion) sc_copy(terreno,c->medio); else sc_copy(terreno,c->ancho);
    if(sc_bits(terreno)==0) sc_set_u64(terreno,1);
    sc_shr(disp_s,terreno,c->salvaje_shift);
    if(sc_bits(disp_s)==0) sc_set_u64(disp_s,1);
    sc_add(rango_m,terreno,disp_s);
    int bits_disp=sc_bits(disp_s), bits_rm=sc_bits(rango_m);

    /* Un valor al azar en [0, lim), por rechazo. Como lim pasa de 2^(bits-1),
       acierta mas de una vez de cada dos y la media son dos vueltas. Esto es
       preparacion, no el bucle: se llama una vez por canguro. */
    auto azar_bajo=[&](sc_t out,const sc_t lim,int lim_bits){
        for(int intento=0;intento<64;intento++){
            for(int j=0;j<4;j++) out[j]=NEXT();
            int top=(lim_bits-1)/64, sh=(lim_bits-1)%64;
            for(int j=3;j>top;j--) out[j]=0;
            if(sh<63) out[top]&=((1ULL<<(sh+1))-1);
            if(sc_cmp(out,lim)<0) return;
        }
        sc_zero(out); out[0]=1;      /* no deberia pasar nunca */
    };

    /* Centro del intervalo, W/2: de ahi salen los mansos en la politica 1. */
    sc_t centro; sc_copy(centro,c->ancho);
    {
        uint64_t arr=0;
        for(int j=3;j>=0;j--){ uint64_t n=centro[j]&1ULL; centro[j]=(centro[j]>>1)|(arr<<63); arr=n; }
    }

    sc_t gs_octavo; sc_shr(gs_octavo,c->medio,2);         /* W/8 */
    if(sc_bits(gs_octavo)==0) sc_set_u64(gs_octavo,2);
    sc_t gs_dieciseisavo; sc_shr(gs_dieciseisavo,gs_octavo,1);
    int bits_medio=sc_bits(c->medio), bits_octavo=sc_bits(gs_octavo);
    auto soltar=[&](int i,int manso){
        sc_t d; sc_zero(d);
        if(c->negacion){
            /* Gaudry-Schost con negacion (Galbraith y Ruprai): mansos por
               [0, W/2) —con la negacion, eso es todo [-W/2, W/2]— y salvajes
               por P'' + [-W/16, W/16). Cada camino se suelta de nuevo en cuanto
               da un distinguido, asi que lo que importa es de DONDE salen,
               no a donde llegan: no hace falta que avancen. */
            /* Los salvajes, de una franja ESTRECHA alrededor de la clave:
               [-W/16, W/16]. Era [-W/4, W/4], lo del articulo. Medido con el
               mismo banco, junto con los saltos mas largos: 1,47 -> 1,38
               raices de W (+-0,015). Mas estrecha no gana, y los mansos tienen
               que cubrir todo [0, W/2): con 3/4 de eso sube a 1,75. */
            if(manso){ azar_bajo(d,c->medio,bits_medio); }
            else{
                sc_t u; azar_bajo(u,gs_octavo,bits_octavo);
                sc_sub_n(d,u,gs_dieciseisavo);
            }
        }else
        if(c->politica_salida==1){
            /* Un valor al azar de disp_bits bits, y al manso se le suma el
               centro. El salvaje se queda pegado a P. Asi la distancia entre
               un manso y un salvaje esta acotada por W/2 + dispersion, en vez
               de llegar a W. */
            for(int j=0;j<4;j++) d[j]=NEXT();
            int top=disp_bits/64, sh=disp_bits%64;
            for(int j=3;j>top;j--) d[j]=0;
            d[top] &= sh ? ((1ULL<<sh)-1) : 0ULL;
            if(manso) sc_add(d,d,centro);
        }else if(c->politica_salida==2){
            /* El manso cubre TODO el terreno donde puede caer un salvaje; el
               salvaje se queda en su trozo colgando de P. No hay que centrar
               nada ni restar: un manso en d_m y un salvaje en d_s que coincidan
               siguen dando k = d_m - d_s, que es lo que resuelve kg_resolver.
               Y como d_m llega hasta W + disp y d_s empieza en 0, la resta no
               se queda nunca corta. */
            /* Con mapa de negacion el objetivo esta trasladado al centro, asi
               que la incognita vive en [-W/2, W/2] y su valor absoluto en
               [0, W/2]: los mansos solo tienen que cubrir la MITAD de terreno
               que antes. De ahi sale el raiz(2). */
            if(manso) azar_bajo(d,rango_m,bits_rm);
            else      azar_bajo(d,disp_s,bits_disp);
        }else{
            /* Politica 0, la de siempre: d al azar en todo [0,W). */
            int w=(c->bits+63)/64;
            for(int j=0;j<w&&j<4;j++) d[j]=NEXT();
            for(int j=3;j>=0;j--) if(j>=w) d[j]=0;
            if(c->bits<256){
                int top=(c->bits-1)/64, sh=(c->bits-1)%64;
                for(int j=3;j>top;j--) d[j]=0;
                if(sh<63) d[top]&=((1ULL<<(sh+1))-1);
            }
            if(sc_cmp(d,c->ancho)>=0) sc_sub(d,d,c->ancho);
        }
        int cero=1; for(int j=0;j<4;j++) if(d[j]) cero=0;
        if(cero) sc_set_u64(d,1);      /* 0*G es el infinito: no vale de salida */

        K[i].manso=manso;
        sc_copy(K[i].dist,d);
        K[i].eps=1;
        K[i].vn=0; K[i].vpos=0; K[i].esc_act=0;   /* ventana de ciclos limpia */
        K[i].ult_esc_ok=0;
        K[i].pasos_sin_dp=0;
        JP dG; kg_scalar_mul(&dG,d,FIELD_GX,FIELD_GY);
        if(manso){
            kg_normalize(&dG,K[i].x,K[i].y);           /* manso: sale de d*G */
        }else{
            fe_t dx,dy; kg_normalize(&dG,dx,dy);
            JP w2; jp_add_affine(&w2,&c->objetivo,dx,dy);  /* salvaje: P'' + d*G */
            int inf=1; for(int j=0;j<4;j++) if(w2.z[j]) inf=0;
            if(inf){ memcpy(K[i].x,c->obj_x,32); memcpy(K[i].y,c->obj_y,32); sc_zero(K[i].dist); }
            else kg_normalize(&w2,K[i].x,K[i].y);
        }
        /* Salir ya canonizado. Si no, el primer paso lo haria y el canguro
           empezaria con el eps al reves de lo que dice su distancia. */
        if(c->negacion && (K[i].y[0]&1)){
            fe_t cero; memset(cero,0,32); fe_sub(K[i].y,cero,K[i].y);
            K[i].eps=-1;
        }
    };
    for(int i=0;i<n_kang;i++) soltar(i,i&1);   /* mitad mansos, mitad salvajes */

    while(!c->parar.load() && !c->encontrado.load()){
        auto t_ini=std::chrono::steady_clock::now();

        /* 1) Elegir el salto de cada uno y preparar el denominador.
              El salto depende SOLO de donde esta: por eso dos canguros que
              coinciden se quedan pegados a partir de ahi. */
        for(int i=0;i<n_kang;i++){
            int h=(int)(K[i].x[0]%(uint64_t)c->njumps);
            /* Escape de un ciclo esteril: solo al pasar por el punto elegido
               del ciclo, y con el salto reservado, que no esta en la tabla.
               Ver el comentario de Kangaroo.ventana y el de kg_setup. */
            if(K[i].esc_act && K[i].x[0]==K[i].esc_obj[0]
                            && K[i].x[1]==K[i].esc_obj[1]){
                if(c->negacion){
                    /* Salto de escape elegido por el PUNTO, con otros bits de
                       la x. Con uno fijo, dos escapes con signos contrarios se
                       anulan y el camino vuelve exactamente a donde estaba: un
                       ciclo que pasa por los escapes y que la ventana no ve,
                       porque se vacia en cada escape. Medido: un escape cada
                       3,3 pasos. Con 64 para elegir, al ritmo de la teoria,
                       uno cada 2*njumps. */
                    h=c->njumps+(int)(K[i].x[1]%(uint64_t)c->nesc);
                    /* Y si aun asi se escapa dos veces del mismo punto, el
                       camino ha dado la vuelta: se suelta de nuevo. En
                       Gaudry-Schost soltar es lo normal, no una perdida. */
                    int visto=0;
                    for(int q=0;q<4;q++) if(K[i].gs_esc[q][0]==K[i].x[0] && K[i].gs_esc[q][1]==K[i].x[1]) visto=1;
                    if(visto){ c->escapes_repe.fetch_add(1); soltar(i,K[i].manso);
                               h=(int)(K[i].x[0]%(uint64_t)c->njumps); jmp[i]=h;
                               fe_sub(den[i],c->jx[h],K[i].x); mal[i]=0;
                               int z0=1; for(int j=0;j<4;j++) if(den[i][j]) z0=0;
                               if(z0){ den[i][0]=1; den[i][1]=den[i][2]=den[i][3]=0; mal[i]=1; }
                               continue; }
                    K[i].gs_esc[K[i].gs_ep][0]=K[i].x[0]; K[i].gs_esc[K[i].gs_ep][1]=K[i].x[1];
                    K[i].gs_ep=(K[i].gs_ep+1)&3;
                }else
                h=c->njumps; K[i].esc_act=0;
                K[i].vn=0; K[i].vpos=0;   /* tras escapar, el camino es otro */
                c->escapes.fetch_add(1);
                if(K[i].ult_esc_ok && K[i].ult_esc[0]==K[i].x[0]
                                   && K[i].ult_esc[1]==K[i].x[1])
                    c->escapes_repe.fetch_add(1);
                K[i].ult_esc[0]=K[i].x[0]; K[i].ult_esc[1]=K[i].x[1];
                K[i].ult_esc_ok=1;
            }
            jmp[i]=h;
            fe_sub(den[i],c->jx[h],K[i].x);
            /* Denominador cero: el canguro esta justo encima del punto de salto
               o de su opuesto. No se puede dividir, y ademas su camino ya no
               es util: se le suelta otra vez. */
            mal[i]=0;
            int z=1; for(int j=0;j<4;j++) if(den[i][j]) z=0;
            if(z){ soltar(i,K[i].manso);
                   h=(int)(K[i].x[0]%(uint64_t)c->njumps); jmp[i]=h;
                   fe_sub(den[i],c->jx[h],K[i].x);
                   int z2=1; for(int j=0;j<4;j++) if(den[i][j]) z2=0;
                   /* Dos veces seguidas cayendo justo encima del punto de salto
                      no va a pasar nunca, pero si pasara no se puede dividir.
                      Se mete un 1 para que la inversion del lote —que es
                      compartida— siga saliendo bien para los DEMAS, y se marca
                      este para no usar el resultado: con den = 1 el salto seria
                      inventado y su distancia dejaria de corresponder con donde
                      esta. Un canguro asi no solo no sirve: mete basura en la
                      tabla de distinguidos, que la comparten todos los hilos. */
                   if(z2){ den[i][0]=1; den[i][1]=den[i][2]=den[i][3]=0;
                           mal[i]=1; } }
        }

        /* 2) Una sola inversion para todo el rebano. Es lo que hace que cada
              salto cueste unas pocas multiplicaciones en vez de una inversion
              entera, que son ~512. */
        memcpy(pfx[0],den[0],32);
        for(int i=1;i<n_kang;i++) fe_mul(pfx[i],pfx[i-1],den[i]);
        fe_t inv; fe_inv(inv,pfx[n_kang-1]);

        /* 3) Recorrer al reves deshaciendo los prefijos, y saltar. */
        for(int i=n_kang-1;i>=0;i--){
            fe_t dinv;
            if(i){ fe_mul(dinv,inv,pfx[i-1]); fe_mul(inv,inv,den[i]); }
            else   memcpy(dinv,inv,32);

            /* Marcado arriba: ni se apunta en la tabla ni se salta. Se suelta
               otra vez y a la vuelta siguiente ya viene bien. */
            if(mal[i]){ soltar(i,K[i].manso); continue; }

            /* Punto distinguido: los dbits bajos de la x a cero. Se mira la
               posicion ACTUAL, antes de saltar. */
            /* Red de seguridad: un canguro que lleva demasiado sin dar un
               distinguido no esta de mala suerte, esta atrapado. Ver
               Kangaroo.pasos_sin_dp. */
            if(++K[i].pasos_sin_dp > tope_sin_dp){
                c->rescatados.fetch_add(1);
                soltar(i,K[i].manso);
                continue;
            }

            if((K[i].x[0]&c->dmask)==0){
                K[i].pasos_sin_dp=0;
                sc_t otro; int otro_manso, mismo=0;
                if(dp_insert(&c->tabla,K[i].x,K[i].dist,K[i].manso,
                             otro,&otro_manso,&mismo))
                    kg_resolver(c,K[i].dist,K[i].manso,otro);
                /* Gaudry-Schost: el camino acaba en su distinguido y se
                   empieza otro desde un sitio nuevo. */
                if(c->negacion){ soltar(i,K[i].manso); continue; }
                else if(mismo){
                    c->pegados.fetch_add(1);
                    if(c->soltar_muertos){
                        /* Canguro muerto: va pegado a otro de su mismo rebano y
                           a partir de aqui los dos caminos son el mismo. Se le
                           suelta en otro sitio. Lo que se pierde son los saltos
                           dados desde que se pegaron hasta este distinguido
                           —unos 2^dbits como mucho—, no el resto. */
                        soltar(i,K[i].manso);
                        continue;
                    }
                }
            }

            /* Suma afin: lambda = (y2-y1)/(x2-x1), x3 = lambda^2-x1-x2,
               y3 = lambda*(x1-x3)-y1. Seis multiplicaciones contando la
               inversion amortizada. */
            int h=jmp[i];
            fe_t lam,t1,x3,y3;
            fe_sub(t1,c->jy[h],K[i].y);
            fe_mul(lam,t1,dinv);
            fe_sqr(x3,lam);
            fe_sub(x3,x3,K[i].x);
            fe_sub(x3,x3,c->jx[h]);
            fe_sub(t1,K[i].x,x3);
            fe_mul(y3,lam,t1);
            fe_sub(y3,y3,K[i].y);

            if(!c->negacion){
                memcpy(K[i].x,x3,32); memcpy(K[i].y,y3,32);
                sc_add(K[i].dist,K[i].dist,c->jlen[h]);
            }else{
                /* La posicion es eps*(base + dist*G). Sumarle el salto deja eps
                   igual y mueve la distancia eps*jlen: hacia delante si eps es
                   +1 y hacia atras si es -1. De ahi que las distancias tengan
                   que vivir en Z_n. */
                if(K[i].eps>0) sc_add_n(K[i].dist,K[i].dist,c->jlen[h]);
                else           sc_sub_n(K[i].dist,K[i].dist,c->jlen[h]);

                /* Canonizar: de la pareja {P,-P} nos quedamos siempre con la de
                   y par. Eso le da la vuelta al punto, o sea que cambia eps y
                   deja la distancia como esta. */
                if(y3[0]&1){
                    fe_t cero; memset(cero,0,32); fe_sub(y3,cero,y3);
                    K[i].eps=-K[i].eps;
                }

                /* ¿Ciclo? Si la posicion nueva es una por la que ya se paso
                   hace j pasos, el canguro esta dando vueltas a un ciclo de j
                   puntos: los j ultimos de la ventana. Se escapa desde el MENOR
                   de ellos, que es un criterio que no depende de por donde se
                   vino: dos canguros que caigan en este mismo ciclo saldran por
                   el mismo punto y con el mismo salto, y por tanto seguiran
                   juntos. Si dependiera de la historia se separarian, y la
                   colision que ya tenian se perderia sin dejar rastro. */
                for(int j=1;j<=K[i].vn;j++){
                    int idx=(K[i].vpos-j+KG_VENTANA)%KG_VENTANA;
                    if(K[i].ventana[idx][0]!=x3[0] ||
                       K[i].ventana[idx][1]!=x3[1]) continue;
                    uint64_t m0=K[i].ventana[idx][0], m1=K[i].ventana[idx][1];
                    for(int q=1;q<j;q++){
                        int p2=(K[i].vpos-q+KG_VENTANA)%KG_VENTANA;
                        uint64_t a0=K[i].ventana[p2][0], a1=K[i].ventana[p2][1];
                        if(a1<m1 || (a1==m1 && a0<m0)){ m0=a0; m1=a1; }
                    }
                    K[i].esc_obj[0]=m0; K[i].esc_obj[1]=m1; K[i].esc_act=1;
                    break;
                }
                K[i].ventana[K[i].vpos][0]=x3[0];
                K[i].ventana[K[i].vpos][1]=x3[1];
                K[i].vpos=(K[i].vpos+1)%KG_VENTANA;
                if(K[i].vn<KG_VENTANA) K[i].vn++;
                memcpy(K[i].x,x3,32); memcpy(K[i].y,y3,32);
            }
        }
        c->saltos.fetch_add((long long)n_kang);
        if(c->encontrado.load()) break;

        /* Freno de CPU: se duerme en proporcion a lo que ha costado la vuelta,
           igual que hace el motor de fuerza bruta. Al 50 % duerme lo mismo que
           ha trabajado. En trozos de 250 ms como mucho, para que pulsar
           "detener" no tarde en responder. */
        int cpu=c->cpu_limite.load();
        if(cpu>0 && cpu<100){
            double ms=std::chrono::duration<double,std::milli>(
                std::chrono::steady_clock::now()-t_ini).count();
            double dormir=ms*(100.0-cpu)/cpu;
            if(dormir>0.5){
                if(dormir>250.0) dormir=250.0;
                struct timespec ts;
                ts.tv_sec=(time_t)(dormir/1000.0);
                ts.tv_nsec=(long)((dormir-(double)ts.tv_sec*1000.0)*1e6);
                nanosleep(&ts,NULL);
            }
        }
    }
    #undef NEXT
    free(K); free(den); free(pfx); free(jmp); free(mal);
}
