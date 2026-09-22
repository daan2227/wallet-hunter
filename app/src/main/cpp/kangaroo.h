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
#define KG_VER   3u
#define KG_VER_SIN_ENVIADO 1u
#define KG_VER_CON_ENVIADO 2u
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
    c.magic=KG_MAGIC; c.ver=KG_VER; c.dbits=(uint32_t)dbits; c.ops=ops;
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
    if(c.magic!=KG_MAGIC ||
       (c.ver!=KG_VER && c.ver!=KG_VER_CON_ENVIADO && c.ver!=KG_VER_SIN_ENVIADO) ||
       c.dbits!=(uint32_t)dbits ||
       memcmp(c.pub,pub,33)!=0 || memcmp(c.ini,ini,32)!=0 || memcmp(c.fin,fin,32)!=0){
        fclose(f); return 0;
    }
    /* Pero una tabla anterior a la 3 en un rango grande esta envenenada entera:
       ver el comentario de KG_VER. Se tira. Es trabajo perdido de verdad —dias
       de movil— y aun asi es lo correcto: lo que hay ahi dentro no puede
       resolver nada, y guardarlo solo sirve para reenviarlo al master para
       siempre. */
    if(c.ver!=KG_VER){
        sc_t a,b,ancho;
        sc_from_be32(a,ini); sc_from_be32(b,fin);
        if(sc_sub(ancho,b,a) && sc_bits(ancho)>KG_BITS_SALTOS_ROTOS){
            fclose(f); return 0;
        }
    }
    int con_enviado = (c.ver!=KG_VER_SIN_ENVIADO);
    uint64_t leidas=0;
    for(uint64_t i=0;i<c.n;i++){
        uint64_t kx[2]; sc_t d; uint8_t manso, env=0;
        if(fread(kx,8,2,f)!=2) break;
        if(fread(d,8,4,f)!=4) break;
        if(fread(&manso,1,1,f)!=1) break;
        if(con_enviado && fread(&env,1,1,f)!=1) break;
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

typedef struct {
    /* Objetivo, ya trasladado a [0,W]: P' = P - a*G */
    JP       objetivo;
    fe_t     obj_x, obj_y;   /* el mismo punto en afin, que es como anda el bucle */
    int      objetivo_ok;
    sc_t     rango_ini;      /* a */
    sc_t     ancho;          /* W = b - a */
    int      bits;           /* log2(W), para dimensionar */

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
     *    coste     2,77  2,38  2,22  2,13  2,19  2,58     (256 canguros)
     *    pegados    1,4   2,0   3,1   4,8   9,7  47,4
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

    /* P' = P - a*G : se resta el inicio del rango para que la incognita quede
       en [0,W] y las distancias sean pequenas. */
    JP P; memcpy(P.x,px,32); memcpy(P.y,py,32); memset(P.z,0,32); P.z[0]=1;
    int es_cero=1;
    for(int i=0;i<4;i++) if(c->rango_ini[i]) es_cero=0;
    if(es_cero){
        c->objetivo=P;
    }else{
        JP aG; kg_scalar_mul(&aG,c->rango_ini,FIELD_GX,FIELD_GY);
        fe_t ax,ay; kg_normalize(&aG,ax,ay);
        fe_t cero,nay; memset(cero,0,32); fe_sub(nay,cero,ay);   /* -a*G */
        jp_add_affine(&c->objetivo,&P,ax,nay);
        /* Si la clave es EXACTAMENTE el inicio del rango, P' sale el punto en
           el infinito y el rebano salvaje nace muerto. Pasa una vez entre 2^69,
           pero devolver "no encontrada" ahi seria mentir: la respuesta es a. */
        int inf=1; for(int i=0;i<4;i++) if(c->objetivo.z[i]) inf=0;
        if(inf){
            sc_copy(c->k,c->rango_ini);
            c->objetivo_ok=1;
            c->dbits=dp_bits; c->dmask=0;
            if(!dp_init(&c->tabla,4)) return 0;
            c->encontrado.store(1); c->saltos.store(0); c->pegados.store(0);
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

    /* Tabla de saltos S[i] = 2^i * G.
       El salto medio conviene que ronde raiz(W)/2: mas corto y los canguros
       tardan en separarse, mas largo y se pasan de largo de la zona util.
       Con potencias de dos la media de i=0..n-1 es (2^n - 1)/n, asi que hay
       que resolver n - log2(n) = bits/2 - 1. Se hace por iteracion, que
       converge en cuatro vueltas. */
    double objetivo=(double)c->bits/2.0-1.0;
    double nd=objetivo>1.0?objetivo:1.0;
    for(int it=0;it<40;it++){
        double l=0; double v=nd>1.0?nd:1.0;
        while(v>=2.0){ v/=2.0; l+=1.0; }
        l+=v-1.0;                       /* log2 aproximado, sobra de sobra */
        nd=objetivo+l;
    }
    int n=(int)(nd+0.5);
    if(n>KG_MAX_JUMPS) n=KG_MAX_JUMPS;
    if(n<4) n=4;
    c->njumps=n;
    {
        JP S; memcpy(S.x,FIELD_GX,32); memcpy(S.y,FIELD_GY,32);
        memset(S.z,0,32); S.z[0]=1;
        for(int i=0;i<n;i++){
            kg_normalize(&S,c->jx[i],c->jy[i]);
            sc_zero(c->jlen[i]); c->jlen[i][i/64]=1ULL<<(i%64);
            jp_dbl(&S,&S);
        }
    }

    c->dbits=dp_bits; c->dmask=(dp_bits>=64)?~0ULL:((1ULL<<dp_bits)-1);
    if(!dp_init(&c->tabla,tabla_bits)) return 0;
    c->encontrado.store(0);
    c->saltos.store(0);
    c->pegados.store(0);
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
static int kg_resolver(KangarooCtx *c,const sc_t d_mio,int manso_mio,
                       const sc_t d_otro){
    sc_t manso,salvaje;
    if(manso_mio){ sc_copy(manso,d_mio); sc_copy(salvaje,d_otro); }
    else         { sc_copy(manso,d_otro); sc_copy(salvaje,d_mio); }
    sc_t kp;
    if(!sc_sub(kp,manso,salvaje)) return 0;
    if(sc_cmp(kp,c->ancho)>0) return 0;
    JP chk; kg_scalar_mul(&chk,kp,FIELD_GX,FIELD_GY);
    int inf=1; for(int z=0;z<4;z++) if(chk.z[z]) inf=0;
    if(inf) return 0;
    fe_t cx,cy; kg_normalize(&chk,cx,cy);
    if(memcmp(cx,c->obj_x,32)!=0 || memcmp(cy,c->obj_y,32)!=0) return 0;
    sc_t kfinal; sc_add(kfinal,kp,c->rango_ini);
    sc_copy(c->k,kfinal);
    c->encontrado.store(1);
    return 1;
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
#define KG_NET_VER   2u
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
typedef struct { fe_t x, y; sc_t dist; int manso; } Kangaroo;

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
    sc_t disp_s, rango_m;
    sc_shr(disp_s,c->ancho,c->salvaje_shift);
    if(sc_bits(disp_s)==0) sc_set_u64(disp_s,1);
    sc_add(rango_m,c->ancho,disp_s);
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

    auto soltar=[&](int i,int manso){
        sc_t d; sc_zero(d);
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
        JP dG; kg_scalar_mul(&dG,d,FIELD_GX,FIELD_GY);
        if(manso){
            kg_normalize(&dG,K[i].x,K[i].y);           /* manso: sale de d*G */
        }else{
            fe_t dx,dy; kg_normalize(&dG,dx,dy);
            JP w2; jp_add_affine(&w2,&c->objetivo,dx,dy);  /* salvaje: P' + d*G */
            int inf=1; for(int j=0;j<4;j++) if(w2.z[j]) inf=0;
            if(inf){ memcpy(K[i].x,c->obj_x,32); memcpy(K[i].y,c->obj_y,32); sc_zero(K[i].dist); }
            else kg_normalize(&w2,K[i].x,K[i].y);
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
            if((K[i].x[0]&c->dmask)==0){
                sc_t otro; int otro_manso, mismo=0;
                if(dp_insert(&c->tabla,K[i].x,K[i].dist,K[i].manso,
                             otro,&otro_manso,&mismo))
                    kg_resolver(c,K[i].dist,K[i].manso,otro);
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
            memcpy(K[i].x,x3,32); memcpy(K[i].y,y3,32);
            sc_add(K[i].dist,K[i].dist,c->jlen[h]);
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
