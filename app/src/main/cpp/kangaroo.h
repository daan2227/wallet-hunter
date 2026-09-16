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
#include <pthread.h>
#include <atomic>

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
 * en `otro` su distancia: eso es la colision que resuelve el problema. */
static int dp_insert(DPTable *t,const uint64_t *kx,const sc_t dist,int manso,
                     sc_t otro,int *otro_manso){
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
            }
            break;
        }
    }
    pthread_mutex_unlock(&t->mtx);
    return res;
}

/* ---------- Contexto de la busqueda ---------- */
#define KG_MAX_JUMPS 64

typedef struct {
    /* Objetivo, ya trasladado a [0,W]: P' = P - a*G */
    JP       objetivo;
    int      objetivo_ok;
    sc_t     rango_ini;      /* a */
    sc_t     ancho;          /* W = b - a */
    int      bits;           /* log2(W), para dimensionar */

    /* Tabla de saltos: S[i] = 2^i * G, en afin */
    int      njumps;
    fe_t     jx[KG_MAX_JUMPS], jy[KG_MAX_JUMPS];
    uint64_t jlen[KG_MAX_JUMPS];

    int      dbits;          /* un punto es distinguido si sus dbits bajos son 0 */
    uint64_t dmask;

    DPTable  tabla;

    /* Resultado */
    std::atomic<int> encontrado;
    sc_t     k;              /* clave privada, ya sumado el inicio del rango */

    std::atomic<long long> saltos;   /* operaciones de grupo hechas, para la UI */
    std::atomic<int>       parar;
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
            c->encontrado.store(1); c->saltos.store(0); c->parar.store(0);
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
            c->jlen[i]=(i<63)?(1ULL<<i):0;
            jp_dbl(&S,&S);
        }
    }

    c->dbits=dp_bits; c->dmask=(dp_bits>=64)?~0ULL:((1ULL<<dp_bits)-1);
    if(!dp_init(&c->tabla,tabla_bits)) return 0;
    c->encontrado.store(0);
    c->saltos.store(0);
    c->parar.store(0);
    return 1;
}

static void kg_free(KangarooCtx *c){ dp_free(&c->tabla); }

/* Un canguro: donde esta y cuanto lleva recorrido. */
typedef struct { JP pos; sc_t dist; int manso; } Kangaroo;

/* Suelta un rebano y lo hace saltar hasta que aparezca la solucion o se pare.
 *
 * Se puede llamar desde varios hilos a la vez con la MISMA ctx: la tabla de
 * distinguidos lleva su propio cerrojo. Cada hilo trae su propia semilla para
 * que los rebanos no salgan todos del mismo sitio.
 */
static void kg_run(KangarooCtx *c,int n_kang,uint64_t semilla){
    if(!c->objetivo_ok || n_kang<1) return;
    Kangaroo *K=(Kangaroo*)calloc(n_kang,sizeof(Kangaroo));
    JP  *pts=(JP*)calloc(n_kang,sizeof(JP));
    fe_t *pfx=(fe_t*)calloc(n_kang,sizeof(fe_t));
    if(!K||!pts||!pfx){ free(K);free(pts);free(pfx); return; }

    /* xorshift: aqui no hace falta un generador criptografico, solo que los
       puntos de salida esten repartidos. */
    uint64_t rng=semilla?semilla:0x2545F4914F6CDD1DULL;
    #define NEXT() (rng^=rng<<13, rng^=rng>>7, rng^=rng<<17, rng)

    for(int i=0;i<n_kang;i++){
        /* Salida aleatoria dentro del ancho, con los bits que toquen. */
        sc_t d; sc_zero(d);
        int w=(c->bits+63)/64;
        for(int j=0;j<w&&j<4;j++) d[j]=NEXT();
        /* recortar a [0,W) */
        for(int j=3;j>=0;j--) if(j>=w) d[j]=0;
        if(c->bits<256){
            int top=(c->bits-1)/64, sh=(c->bits-1)%64;
            for(int j=3;j>top;j--) d[j]=0;
            if(sh<63) d[top]&=((1ULL<<(sh+1))-1);
        }
        if(sc_cmp(d,c->ancho)>=0) sc_sub(d,d,c->ancho);

        K[i].manso=(i&1);           /* mitad mansos, mitad salvajes */
        sc_copy(K[i].dist,d);
        JP dG; kg_scalar_mul(&dG,d,FIELD_GX,FIELD_GY);
        int d_cero=1; for(int j=0;j<4;j++) if(d[j]) d_cero=0;
        if(K[i].manso){
            /* Manso: sale de d*G, logaritmo conocido = d */
            if(d_cero){ sc_set_u64(K[i].dist,1); kg_scalar_mul(&dG,K[i].dist,FIELD_GX,FIELD_GY); }
            K[i].pos=dG;
        }else{
            /* Salvaje: sale de P' + d*G, logaritmo = k + d */
            if(d_cero){ K[i].pos=c->objetivo; }
            else{
                fe_t dx,dy; kg_normalize(&dG,dx,dy);
                jp_add_affine(&K[i].pos,&c->objetivo,dx,dy);
            }
        }
    }

    while(!c->parar.load() && !c->encontrado.load()){
        for(int i=0;i<n_kang;i++) pts[i]=K[i].pos;

        /* Una sola inversion para todo el rebano: es lo que hace que cada salto
           cueste unas pocas multiplicaciones en vez de una inversion entera. */
        /* Un canguro puede caer en el infinito (sumar un punto a su opuesto).
           Antes eso rompia el bucle y dejaba el hilo sin hacer nada el resto de
           la sesion; ahora se le suelta de nuevo en otro sitio y el resto del
           rebano sigue. */
        for(int i=0;i<n_kang;i++){
            int z0=1; for(int j=0;j<4;j++) if(pts[i].z[j]) z0=0;
            if(!z0) continue;
            sc_t d; sc_zero(d); d[0]=NEXT();
            if(c->bits<64) d[0]&=((c->bits>=64)?~0ULL:((1ULL<<c->bits)-1));
            if(sc_cmp(d,c->ancho)>=0) sc_sub(d,d,c->ancho);
            int dz=1; for(int j=0;j<4;j++) if(d[j]) dz=0;
            if(dz) sc_set_u64(d,1);
            JP dG; kg_scalar_mul(&dG,d,FIELD_GX,FIELD_GY);
            sc_copy(K[i].dist,d);
            if(K[i].manso) K[i].pos=dG;
            else{
                fe_t dx,dy; kg_normalize(&dG,dx,dy);
                jp_add_affine(&K[i].pos,&c->objetivo,dx,dy);
            }
            pts[i]=K[i].pos;
        }

        memcpy(pfx[0],pts[0].z,32);
        for(int i=1;i<n_kang;i++) fe_mul(pfx[i],pfx[i-1],pts[i].z);
        fe_t inv; fe_inv(inv,pfx[n_kang-1]);

        for(int i=n_kang-1;i>=0;i--){
            fe_t zinv;
            if(i){ fe_mul(zinv,inv,pfx[i-1]); fe_mul(inv,inv,pts[i].z); }
            else   memcpy(zinv,inv,32);
            fe_t z2,xa; fe_sqr(z2,zinv); fe_mul(xa,pts[i].x,z2);

            /* Punto distinguido: los dbits bajos de la x a cero. */
            if((xa[0]&c->dmask)==0){
                sc_t otro; int otro_manso;
                if(dp_insert(&c->tabla,xa,K[i].dist,K[i].manso,otro,&otro_manso)){
                    /* k = distancia del manso - distancia del salvaje */
                    sc_t manso,salvaje;
                    if(K[i].manso){ sc_copy(manso,K[i].dist); sc_copy(salvaje,otro); }
                    else          { sc_copy(manso,otro);      sc_copy(salvaje,K[i].dist); }
                    sc_t kp;
                    if(sc_sub(kp,manso,salvaje) && sc_cmp(kp,c->ancho)<=0){
                        /* Se comprueba antes de cantar victoria: kp*G tiene que
                           dar exactamente el objetivo trasladado. Una colision
                           puede salir de dos caminos que se cruzan sin pegarse,
                           y entonces la resta da un numero que parece bueno y
                           no lo es. */
                        JP chk; kg_scalar_mul(&chk,kp,FIELD_GX,FIELD_GY);
                        fe_t cx,cy,ox,oy;
                        int chk_inf=1; for(int z=0;z<4;z++) if(chk.z[z]) chk_inf=0;
                        if(!chk_inf){
                            kg_normalize(&chk,cx,cy);
                            kg_normalize(&c->objetivo,ox,oy);
                            if(memcmp(cx,ox,32)==0 && memcmp(cy,oy,32)==0){
                                sc_t kfinal; sc_add(kfinal,kp,c->rango_ini);
                                sc_copy(c->k,kfinal);
                                c->encontrado.store(1);
                            }
                        }
                    }
                    /* Si la resta sale negativa o fuera del rango, no es una
                       colision util (puede pasar con caminos que se cruzan sin
                       pegarse): se sigue saltando. */
                }
            }

            /* El salto depende SOLO de donde esta: por eso dos canguros que
               coinciden se quedan pegados. */
            int h=(int)(xa[0]%(uint64_t)c->njumps);
            JP nueva;
            jp_add_affine(&nueva,&K[i].pos,c->jx[h],c->jy[h]);
            K[i].pos=nueva;
            sc_add_u64(K[i].dist,c->jlen[h]);
        }
        c->saltos.fetch_add((long long)n_kang);
        if(c->encontrado.load()) break;
    }
    #undef NEXT
    free(K); free(pts); free(pfx);
}
