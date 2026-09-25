#pragma once
/* El escaner de claves al azar (modo RawKey), rehecho para sacar mas claves
 * por cada operacion de curva.
 *
 * 1. Grupos simetricos en afin. Alrededor de un centro C se sacan C+iG y C-iG
 *    para i=1..M con una tabla fija de iG. Las dos sumas comparten el mismo
 *    denominador (x_iG - x_C), asi que una sola inversion (Montgomery, por
 *    lotes) vale para las dos: unas 3,5 multiplicaciones por punto, frente a
 *    las ~13 de sumar en Jacobiano y normalizar despues.
 * 2. Negacion: -P tiene la misma x y la otra paridad de y. Gratis.
 * 3. Endomorfismo de secp256k1: lambda*P = (beta*x, y). Una multiplicacion da
 *    otra x, y beta^2*x = -x - beta*x sale con una suma.
 *
 * Cada punto da seis claves publicas distintas (k, -k, lambda*k, -lambda*k,
 * lambda^2*k, -lambda^2*k). Al ser claves al azar, cualquiera vale tanto como
 * otra: son seis pruebas por el precio de poco mas que una, y lo que queda es
 * hash160, que va de cuatro en cuatro (hash160x4.h).
 *
 * Probado contra secp256k1 en tools/ec-harness/escaner.cpp. */
#include "jac_batch.h"
#include "hash160x4.h"

#define ESC_M 512                    /* puntos a cada lado del centro */
#define ESC_GRUPO (2*ESC_M+1)        /* claves de curva por grupo */

/* beta: raiz cubica de 1 modulo p; lambda*(x,y) = (beta*x, y) con
 * lambda = 0x5363ad4cc05c30e0a5261c028812645a122e22ea20816678df02967c1b23bd72. */
static const uint64_t ESC_BETA[4]={0xc1396c28719501eeULL,0x9cf0497512f58995ULL,0x6e64479eac3434e9ULL,0x7ae96a2b657c0710ULL};
static const uint8_t ESC_LAMBDA_BE[32]={
    0x53,0x63,0xad,0x4c,0xc0,0x5c,0x30,0xe0,0xa5,0x26,0x1c,0x02,0x88,0x12,0x64,0x5a,
    0x12,0x2e,0x22,0xea,0x20,0x81,0x66,0x78,0xdf,0x02,0x96,0x7c,0x1b,0x23,0xbd,0x72};

struct EscTabla {
    fe_t gx[ESC_M+1], gy[ESC_M+1];   /* i*G, i=1..M (el 0 no se usa) */
    fe_t sx, sy;                     /* ESC_GRUPO*G: de un centro al siguiente */
};

/* La tabla, una vez: M sumas y una normalizacion por lotes. */
static void esc_tabla_crear(EscTabla *t){
    static JP pts[ESC_M+2];
    static fe_t pfx[ESC_M+2];
    memcpy(pts[0].x,FIELD_GX,32); memcpy(pts[0].y,FIELD_GY,32);
    memset(pts[0].z,0,32); pts[0].z[0]=1;
    jp_dbl(&pts[1],&pts[0]);                          /* 2G: jp_add_affine no dobla */
    for(int i=2;i<ESC_M;i++) jp_add_G(&pts[i],&pts[i-1]);
    /* ESC_GRUPO*G = 2*(M*G) + G */
    JP d; jp_dbl(&d,&pts[ESC_M-1]); jp_add_G(&pts[ESC_M],&d);
    int n=ESC_M+1;
    memcpy(pfx[0],pts[0].z,32);
    for(int i=1;i<n;i++) fe_mul(pfx[i],pfx[i-1],pts[i].z);
    fe_t inv; fe_inv(inv,pfx[n-1]);
    for(int i=n-1;i>=0;i--){
        fe_t zi;
        if(i){ fe_mul(zi,inv,pfx[i-1]); fe_mul(inv,inv,pts[i].z); } else memcpy(zi,inv,32);
        fe_t z2,z3,x,y; fe_sqr(z2,zi); fe_mul(z3,z2,zi); fe_mul(x,pts[i].x,z2); fe_mul(y,pts[i].y,z3);
        if(i<ESC_M){ memcpy(t->gx[i+1],x,32); memcpy(t->gy[i+1],y,32); }
        else       { memcpy(t->sx,x,32);      memcpy(t->sy,y,32); }
    }
}

static inline void esc_pub33(uint8_t *p,const fe_t x,int impar){
    p[0]=impar?0x03:0x02;
    for(int w=0;w<4;w++) for(int b=0;b<8;b++) p[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}

/* Lo que se hace con cada hash160: j es el desplazamiento desde el centro
 * (-M..M) y v la variante (0..5: k, -k, lk, -lk, l2k, -l2k). */
typedef void (*EscVisto)(const uint8_t *h160, int j, int v, void *ctx);

struct EscCola {                     /* de cuatro en cuatro para hash160_x4 */
    uint8_t pub[4][33]; int j[4], v[4]; int n;
};
static inline void esc_vaciar(EscCola *c, EscVisto f, void *ctx){
    if(!c->n) return;
    uint8_t h[4][20];
    if(c->n==4){
        const uint8_t *pp[4]={c->pub[0],c->pub[1],c->pub[2],c->pub[3]};
        hash160_x4(pp,h);
    } else for(int k=0;k<c->n;k++) hash160_inline(c->pub[k],h[k]);
    for(int k=0;k<c->n;k++) f(h[k],c->j[k],c->v[k],ctx);
    c->n=0;
}
static inline void esc_meter(EscCola *c, const fe_t x, int impar, int j, int v, EscVisto f, void *ctx){
    esc_pub33(c->pub[c->n],x,impar); c->j[c->n]=j; c->v[c->n]=v;
    if(++c->n==4) esc_vaciar(c,f,ctx);
}
/* Las seis claves de un punto (x, paridad de y). */
static inline void esc_seis(EscCola *c, const fe_t x, int impar, int j, EscVisto f, void *ctx){
    fe_t bx,b2x,t; static const fe_t CERO={0,0,0,0};
    fe_mul(bx,x,ESC_BETA);
    fe_add(t,x,bx); fe_sub(b2x,CERO,t);               /* beta^2*x = -x - beta*x */
    esc_meter(c,x,  impar,  j,0,f,ctx); esc_meter(c,x,  !impar,j,1,f,ctx);
    esc_meter(c,bx, impar,  j,2,f,ctx); esc_meter(c,bx, !impar,j,3,f,ctx);
    esc_meter(c,b2x,impar,  j,4,f,ctx); esc_meter(c,b2x,!impar,j,5,f,ctx);
}

/* Un grupo alrededor del centro (cx,cy), y el centro pasa al siguiente
 * (+ESC_GRUPO). dx, pfx: sitio para ESC_M+1. Devuelve 0 si el centro cae
 * justo en +-iG (imposible con claves al azar; entonces no se toca nada). */
static int esc_grupo(const EscTabla *t, fe_t cx, fe_t cy, fe_t *dx, fe_t *pfx, EscVisto f, void *ctx){
    const int n=ESC_M+1;
    for(int i=1;i<=ESC_M;i++) fe_sub(dx[i-1],t->gx[i],cx);
    fe_sub(dx[ESC_M],t->sx,cx);
    memcpy(pfx[0],dx[0],32);
    for(int i=1;i<n;i++) fe_mul(pfx[i],pfx[i-1],dx[i]);
    int cero=1; for(int w=0;w<4;w++) if(pfx[n-1][w]) cero=0;
    if(cero) return 0;
    fe_t inv; fe_inv(inv,pfx[n-1]);
    EscCola cola; cola.n=0;
    fe_t sig_x,sig_y;
    for(int i=n-1;i>=0;i--){
        fe_t di;
        if(i){ fe_mul(di,inv,pfx[i-1]); fe_mul(inv,inv,dx[i]); } else memcpy(di,inv,32);
        const uint64_t *qx=(i==ESC_M)?t->sx:t->gx[i+1];
        const uint64_t *qy=(i==ESC_M)?t->sy:t->gy[i+1];
        /* C + Q */
        fe_t l,l2,x3,y3,tmp;
        fe_sub(tmp,qy,cy); fe_mul(l,tmp,di);
        fe_sqr(l2,l); fe_sub(x3,l2,cx); fe_sub(x3,x3,qx);
        fe_sub(tmp,cx,x3); fe_mul(y3,l,tmp); fe_sub(y3,y3,cy);
        if(i==ESC_M){ memcpy(sig_x,x3,32); memcpy(sig_y,y3,32); continue; }
        esc_seis(&cola,x3,(int)(y3[0]&1),i+1,f,ctx);
        /* C - Q: la misma inversa, con -qy */
        fe_add(tmp,qy,cy); fe_mul(l,tmp,di);                /* l' = -(qy+cy)/dx; se usa l'^2 y -l' */
        fe_sqr(l2,l); fe_sub(x3,l2,cx); fe_sub(x3,x3,qx);
        fe_sub(tmp,x3,cx); fe_mul(y3,l,tmp); fe_sub(y3,y3,cy);  /* -l'*(cx-x3) = l*(x3-cx) */
        esc_seis(&cola,x3,(int)(y3[0]&1),-(i+1),f,ctx);
    }
    esc_seis(&cola,cx,(int)(cy[0]&1),0,f,ctx);
    esc_vaciar(&cola,f,ctx);
    memcpy(cx,sig_x,32); memcpy(cy,sig_y,32);
    return 1;
}

/* La clave privada de un hallazgo: centro kc (32 bytes big-endian), j y v
 * como los da EscVisto. Solo se llama cuando hay coincidencia. */
#include <secp256k1.h>
static int esc_clave(const secp256k1_context *ctx, const uint8_t kc[32], int j, int v, uint8_t out[32]){
    memcpy(out,kc,32);
    if(j){
        uint8_t tw[32]={0}; unsigned a=(unsigned)(j<0?-j:j);
        tw[28]=(uint8_t)(a>>24); tw[29]=(uint8_t)(a>>16); tw[30]=(uint8_t)(a>>8); tw[31]=(uint8_t)a;
        if(j<0 && !secp256k1_ec_seckey_negate(ctx,out)) return 0;     /* k - a = -(-k + a) */
        if(!secp256k1_ec_seckey_tweak_add(ctx,out,tw)) return 0;
        if(j<0 && !secp256k1_ec_seckey_negate(ctx,out)) return 0;
    }
    for(int e=0;e<v/2;e++) if(!secp256k1_ec_seckey_tweak_mul(ctx,out,ESC_LAMBDA_BE)) return 0;
    if(v&1) return secp256k1_ec_seckey_negate(ctx,out);
    return 1;
}
