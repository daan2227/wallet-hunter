#pragma once
/* Jacobian batch normalization for secp256k1 sequential scan.
   Montgomery batch inverse: N inversions -> 1 inversion + 3N multiplications.
   ASCII only - no special chars. */
#include <stdint.h>
#include <string.h>

typedef uint64_t fe_t[4]; /* little-endian 64-bit limbs */

static const uint64_t FP[4]={
    0xFFFFFFFEFFFFFC2FULL,0xFFFFFFFFFFFFFFFFULL,
    0xFFFFFFFFFFFFFFFFULL,0xFFFFFFFFFFFFFFFFULL
};
/* secp256k1 generator G */
static const uint64_t FIELD_GX[4]={
    0x59F2815B16F81798ULL,0x029BFCDB2DCE28D9ULL,
    0x55A06295CE870B07ULL,0x79BE667EF9DCBBACULL
};
static const uint64_t FIELD_GY[4]={
    0x9C47D08FFB10D4B8ULL,0xFD17B448A6855419ULL,
    0x5DA4FBFC0E1108A8ULL,0x483ADA7726A3C465ULL
};

/* ---- Field arithmetic ---- */
static int fe_cmp(const fe_t a,const fe_t b){
    for(int i=3;i>=0;i--){if(a[i]>b[i])return 1;if(a[i]<b[i])return -1;}return 0;
}
static void fe_sub(fe_t r,const fe_t a,const fe_t b){
    uint64_t borrow=0;
    for(int i=0;i<4;i++){
        __uint128_t t=(__uint128_t)a[i]-b[i]-borrow;
        r[i]=(uint64_t)t;borrow=(t>>127)&1;
    }
    if(borrow){
        uint64_t carry=0;
        for(int i=0;i<4;i++){
            __uint128_t t=(__uint128_t)r[i]+FP[i]+carry;
            r[i]=(uint64_t)t;carry=(uint64_t)(t>>64);
        }
    }
}
static void fe_add(fe_t r,const fe_t a,const fe_t b){
    uint64_t carry=0;
    for(int i=0;i<4;i++){
        __uint128_t t=(__uint128_t)a[i]+b[i]+carry;
        r[i]=(uint64_t)t;carry=(uint64_t)(t>>64);
    }
    if(carry||fe_cmp(r,FP)>=0){
        uint64_t borrow=0;
        for(int i=0;i<4;i++){
            __uint128_t t=(__uint128_t)r[i]-FP[i]-borrow;
            r[i]=(uint64_t)t;borrow=(t>>127)&1;
        }
    }
}
/* Multiplicacion modular.
 *
 * ESTABA MAL. La version anterior traia asm a mano "para evitar __uint128_t" y
 * sumaba `lo` DOS VECES: una en C (t[i+j] = old + lo + c) y otra en el propio
 * asm (adds %0, %0, %2 con %2 = lo). Ademas tiraba el acarreo de salida de la
 * primera suma. Resultado: 2*3 daba 12 en vez de 6.
 *
 * Como fe_mul es la base de jp_add_G y de la normalizacion por lotes, el modo
 * puzzle y el modo clave directa llevaban todo este tiempo calculando claves
 * publicas que no corresponden a la privada que dicen estar probando. No se
 * notaba porque "no encontrar nada" es justo lo que se espera de los dos.
 *
 * Aqui va con __uint128_t, que en ARM64 el compilador traduce exactamente a
 * mul + umulh: lo que el asm intentaba hacer, pero bien.
 *
 * (El comentario vale para fe_mul, justo debajo de fe_reduce8.) */
/* Reduce un producto de 8 limbs a un elemento del cuerpo.
 *
 * Lo comparten fe_mul y fe_sqr: la reduccion no depende de como se haya
 * calculado el producto, y tenerla una sola vez evita que las dos versiones se
 * separen con el tiempo. */
static void fe_reduce8(fe_t r,uint64_t *t){
    /* 2^256 == 0x1000003D1 (mod p). */
    const uint64_t C=0x1000003D1ULL;

    /* Primera pasada: parte baja + C * parte alta. */
    uint64_t carry=0;
    for(int i=0;i<4;i++){
        __uint128_t p=(__uint128_t)t[i+4]*C+t[i]+carry;
        t[i]=(uint64_t)p;carry=(uint64_t)(p>>64);
    }

    /* Y ahora se dobla el sobrante hacia abajo, otra vez por C.
     *
     * ESTO ESTABA MAL, y llevaba mal desde el principio. La version anterior
     * hacia una sola vuelta y la propagacion del acarreo cortaba en seco:
     *
     *     for(int i=1;i<4 && c2;i++) ...
     *
     * Si al llegar a i=3 todavia quedaba c2, se TIRABA. Y un acarreo que sale
     * por arriba de 2^256 no vale cero: vale C. O sea, el resultado salia
     * exactamente C corto.
     *
     * Hace falta que t[1], t[2] y t[3] esten los tres a 0xFFFF...FF justo en
     * ese momento, asi que con numeros al azar no pasa casi nunca —5000
     * productos aleatorios no lo encuentran— pero con valores con estructura
     * salta enseguida: el primero que lo destapo fue elevar p-2 a p-2, dentro
     * del inverso modular, donde daba un inverso que no era.
     *
     * El bucle repite mientras siga saliendo acarreo. Termina rapido: en la
     * segunda vuelta t[0] acaba de darse la vuelta, asi que ya no arrastra.
     */
    while(carry){
        uint64_t c=carry;
        __uint128_t p=(__uint128_t)c*C+t[0];
        t[0]=(uint64_t)p;
        uint64_t c2=(uint64_t)(p>>64);
        for(int i=1;i<4;i++){
            __uint128_t q=(__uint128_t)t[i]+c2;
            t[i]=(uint64_t)q;c2=(uint64_t)(q>>64);
        }
        carry=c2;
    }

    /* Ya cabe en 256 bits, y como 2p > 2^256 solo puede sobrar una p. */
    if(fe_cmp(t,FP)>=0){
        uint64_t borrow=0;
        for(int i=0;i<4;i++){
            __uint128_t p=(__uint128_t)t[i]-FP[i]-borrow;
            t[i]=(uint64_t)p;borrow=(p>>127)&1;
        }
    }
    memcpy(r,t,32);
}

static void fe_mul(fe_t r,const fe_t a,const fe_t b){
    /* Escolar 4x4 -> 8 limbs. El maximo de cada paso es
       (2^64-1) + (2^64-1)^2 + (2^64-1) = 2^128-1, asi que cabe en 128 bits y
       el acarreo nunca pasa de 64. */
    uint64_t t[8]={0};
    for(int i=0;i<4;i++){
        uint64_t carry=0;
        for(int j=0;j<4;j++){
            __uint128_t cur=(__uint128_t)a[i]*b[j]+t[i+j]+carry;
            t[i+j]=(uint64_t)cur;
            carry=(uint64_t)(cur>>64);
        }
        /* t[i+4] no se ha tocado todavia en esta pasada. */
        t[i+4]=carry;
    }
    fe_reduce8(r,t);
}

/* Elevar al cuadrado, con su propio camino.
 *
 * Antes era `fe_mul(a,a)`, que hace las 16 multiplicaciones de 64x64 del caso
 * general. Pero al cuadrado a[i]*a[j] y a[j]*a[i] son el MISMO producto: basta
 * calcular las 6 de arriba de la diagonal, duplicarlas de un golpe con un
 * desplazamiento, y sumar despues los 4 cuadrados a[i]^2. Diez
 * multiplicaciones en vez de dieciseis.
 *
 * Importa porque fe_sqr esta en los dos sitios calientes: una vez por salto de
 * canguro (la lambda al cuadrado) y 255 veces dentro de fe_inv. */
static void fe_sqr(fe_t r,const fe_t a){
    uint64_t t[8]={0};
    __uint128_t cur; uint64_t c;

    /* Triangulo de arriba: a[i]*a[j] con i<j, colocado en t[1..6]. */
    cur=(__uint128_t)a[0]*a[1];      t[1]=(uint64_t)cur; c=(uint64_t)(cur>>64);
    cur=(__uint128_t)a[0]*a[2]+c;    t[2]=(uint64_t)cur; c=(uint64_t)(cur>>64);
    cur=(__uint128_t)a[0]*a[3]+c;    t[3]=(uint64_t)cur; t[4]=(uint64_t)(cur>>64);

    cur=(__uint128_t)a[1]*a[2]+t[3];      t[3]=(uint64_t)cur; c=(uint64_t)(cur>>64);
    cur=(__uint128_t)a[1]*a[3]+t[4]+c;    t[4]=(uint64_t)cur; t[5]=(uint64_t)(cur>>64);

    cur=(__uint128_t)a[2]*a[3]+t[5];      t[5]=(uint64_t)cur; t[6]=(uint64_t)(cur>>64);

    /* Duplicar el triangulo entero: cada producto cruzado va dos veces.
       t[0] es cero, asi que el desplazamiento no pierde nada por abajo. */
    t[7]=t[6]>>63;
    for(int i=6;i>=1;i--) t[i]=(t[i]<<1)|(t[i-1]>>63);

    /* Y ahora los cuadrados de la diagonal, en las posiciones pares. */
    c=0;
    for(int i=0;i<4;i++){
        cur=(__uint128_t)a[i]*a[i]+t[2*i]+c;
        t[2*i]=(uint64_t)cur;   c=(uint64_t)(cur>>64);
        cur=(__uint128_t)t[2*i+1]+c;
        t[2*i+1]=(uint64_t)cur; c=(uint64_t)(cur>>64);
    }
    /* a < 2^256 => a^2 < 2^512: el ultimo acarreo es siempre 0. */
    fe_reduce8(r,t);
}
static void fe_dbl(fe_t r,const fe_t a){fe_add(r,a,a);}

/* Inverso modular: a^(p-2) mod p.
 *
 * Antes iba bit a bit sobre p-2: 256 cuadrados y ~128 multiplicaciones, unas
 * 384 operaciones. Pero p-2 no es un exponente cualquiera, es
 *
 *     p-2 = 2^256 - 2^32 - 979
 *
 * y en binario son tiras larguisimas de unos. Eso permite una cadena de
 * adicion: se construyen x2=a^(2^2-1), x3=a^(2^3-1), x6, x11, x22... cada una
 * doblando la anterior, y se llega a p-2 con 255 cuadrados y solo 15
 * multiplicaciones.
 *
 * Con el fe_sqr de arriba, el inverso sale a menos de la mitad de coste. Se
 * nota sobre todo con lotes pequenos, donde la inversion del lote se reparte
 * entre pocos canguros: con lote 64 eran seis multiplicaciones por salto solo
 * de invertir.
 *
 * Nota: para a=0 esto devuelve 0, igual que la version anterior. Quien llama
 * comprueba el cero antes (en el bucle de canguros es el caso de dos que caen
 * en la misma x).
 */
static void fe_inv(fe_t r,const fe_t a){
    fe_t x2,x3,x6,x9,x11,x22,x44,x88,x176,x220,x223,t;

    fe_sqr(x2,a);        fe_mul(x2,x2,a);          /* a^(2^2-1)   */
    fe_sqr(x3,x2);       fe_mul(x3,x3,a);          /* a^(2^3-1)   */

    memcpy(x6,x3,32);
    { for(int i=0;i<3;i++) fe_sqr(x6,x6); } fe_mul(x6,x6,x3);    /* 2^6-1  */
    memcpy(x9,x6,32);
    { for(int i=0;i<3;i++) fe_sqr(x9,x9); } fe_mul(x9,x9,x3);    /* 2^9-1  */
    memcpy(x11,x9,32);
    { for(int i=0;i<2;i++) fe_sqr(x11,x11); } fe_mul(x11,x11,x2);  /* 2^11-1 */
    memcpy(x22,x11,32);
    { for(int i=0;i<11;i++) fe_sqr(x22,x22); } fe_mul(x22,x22,x11);/* 2^22-1 */
    memcpy(x44,x22,32);
    { for(int i=0;i<22;i++) fe_sqr(x44,x44); } fe_mul(x44,x44,x22);
    memcpy(x88,x44,32);
    { for(int i=0;i<44;i++) fe_sqr(x88,x88); } fe_mul(x88,x88,x44);
    memcpy(x176,x88,32);
    { for(int i=0;i<88;i++) fe_sqr(x176,x176); } fe_mul(x176,x176,x88);
    memcpy(x220,x176,32);
    { for(int i=0;i<44;i++) fe_sqr(x220,x220); } fe_mul(x220,x220,x44);
    memcpy(x223,x220,32);
    { for(int i=0;i<3;i++) fe_sqr(x223,x223); } fe_mul(x223,x223,x3);

    /* La cola: los 33 bits de abajo de p-2, que ya no son todo unos. */
    memcpy(t,x223,32);
    { for(int i=0;i<23;i++) fe_sqr(t,t); } fe_mul(t,t,x22);
    { for(int i=0;i<5;i++) fe_sqr(t,t); } fe_mul(t,t,a);
    { for(int i=0;i<3;i++) fe_sqr(t,t); } fe_mul(t,t,x2);
    { for(int i=0;i<2;i++) fe_sqr(t,t); } fe_mul(t,t,a);
    memcpy(r,t,32);
}

/* ---- Jacobian point ---- */
typedef struct { fe_t x,y,z; } JP;

/* Load affine point (from secp256k1 uncompressed pub65) into JP with Z=1 */
static void jp_from_affine(JP *P,const uint8_t *pub65){
    /* pub65: 0x04 | X_be[32] | Y_be[32] */
    for(int i=0;i<4;i++){
        P->x[3-i]=P->y[3-i]=0;
        for(int j=0;j<8;j++){
            P->x[3-i]|=((uint64_t)pub65[1+i*8+j])<<(56-j*8);
            P->y[3-i]|=((uint64_t)pub65[33+i*8+j])<<(56-j*8);
        }
    }
    memset(P->z,0,32); P->z[0]=1;
}

/* Adicion mixta general: R = P (Jacobiano) + Q (afin).
   madd-2007-bl de https://hyperelliptic.org/EFD/g1p/auto-shortw-jacobian-0.html

   OJO: no cubre el caso P == Q. Ahi H sale 0 y Z3 tambien, o sea el punto en el
   infinito, que no es la respuesta. Para doblar esta jp_dbl. */
static void jp_add_affine(JP *R,const JP *P,const uint64_t *qx,const uint64_t *qy){
    /* Se calcula TODO en locales y se vuelca al final: asi vale llamarlo con
       R == P. Escribiendo directamente en R->y y leyendo despues P->y se lee el
       valor nuevo en vez del viejo, y el resultado es un punto que no esta en
       la curva. Con R y P distintos no se nota, que es justo lo que hace que
       este fallo sobreviva a las pruebas. */
    fe_t Z1Z1,U2,S2,H,HH,HHH,R2,V,tmp,x3,y3,z3;
    fe_sqr(Z1Z1,P->z);
    fe_mul(U2,qx,Z1Z1);
    fe_mul(S2,qy,P->z); fe_mul(S2,S2,Z1Z1);
    fe_sub(H,U2,P->x);
    fe_sub(R2,S2,P->y);
    fe_sqr(HH,H);
    fe_mul(HHH,H,HH);
    fe_mul(V,P->x,HH);
    fe_sqr(x3,R2);
    fe_sub(x3,x3,HHH);
    fe_dbl(tmp,V); fe_sub(x3,x3,tmp);
    fe_sub(tmp,V,x3);
    fe_mul(y3,R2,tmp);
    fe_mul(tmp,P->y,HHH);
    fe_sub(y3,y3,tmp);
    fe_mul(z3,H,P->z);
    memcpy(R->x,x3,32); memcpy(R->y,y3,32); memcpy(R->z,z3,32);
}

/* Doblado Jacobiano para a=0 (secp256k1). dbl-2009-l */
static void jp_dbl(JP *R,const JP *P){
    /* Igual que jp_add_affine: en locales, para que jp_dbl(&S,&S) funcione.
       Z3 = 2*Y*Z necesita la Y VIEJA, y escribir antes en R->y se la cargaba. */
    fe_t A,B,C,D,E,F,t1,t2,x3,y3,z3;
    fe_sqr(A,P->x);                 /* A = X^2 */
    fe_sqr(B,P->y);                 /* B = Y^2 */
    fe_sqr(C,B);                    /* C = B^2 */
    fe_add(t1,P->x,B); fe_sqr(t1,t1);
    fe_sub(t1,t1,A); fe_sub(t1,t1,C);
    fe_dbl(D,t1);                   /* D = 2*((X+B)^2 - A - C) */
    fe_dbl(E,A); fe_add(E,E,A);     /* E = 3*A */
    fe_sqr(F,E);                    /* F = E^2 */
    fe_dbl(t1,D); fe_sub(x3,F,t1);              /* X3 = F - 2D */
    fe_sub(t1,D,x3); fe_mul(t1,E,t1);
    fe_dbl(t2,C); fe_dbl(t2,t2); fe_dbl(t2,t2); /* 8C */
    fe_sub(y3,t1,t2);                           /* Y3 = E*(D-X3) - 8C */
    fe_mul(t1,P->y,P->z); fe_dbl(z3,t1);        /* Z3 = 2*Y*Z, con la Y vieja */
    memcpy(R->x,x3,32); memcpy(R->y,y3,32); memcpy(R->z,z3,32);
}

/* R = P + G. Caso particular del anterior; se deja como nombre propio porque
   es el del bucle secuencial, pero comparte formulas para que no haya dos
   copias que puedan divergir. */
static void jp_add_G(JP *R,const JP *P){
    jp_add_affine(R,P,FIELD_GX,FIELD_GY);
}

#define JAC_BATCH 16000

/* Batch normalize: given pts[0..n-1] in Jacobian, write compressed pub33[i] for each.
   Uses Montgomery batch inversion: 1 inv + 3n mults instead of n invs. */
/* `pfx` lo aporta el llamante: reservar y liberar n*sizeof(fe_t) (512 KB con
   JAC_BATCH=16000) en cada lote castigaba al asignador desde varios hilos a la
   vez. Debe tener sitio para al menos n elementos. */
static void jac_batch_hash160(
    JP *pts, int n, fe_t *pfx,
    void (*on_key)(int idx, const uint8_t *pub33, void *ctx),
    void *ctx)
{
    if(!pfx || n<1) return;
    /* Check for zero Z (point at infinity) - skip batch if found */
    for(int i=0;i<n;i++){
        bool zz=true;
        for(int j=0;j<4;j++) if(pts[i].z[j]){zz=false;break;}
        if(zz){ return; }
    }
    memcpy(pfx[0],pts[0].z,32);
    for(int i=1;i<n;i++) fe_mul(pfx[i],pfx[i-1],pts[i].z);
    /* Check final prefix is not zero before inverting */
    bool pfx_zero=true;
    for(int j=0;j<4;j++) if(pfx[n-1][j]){pfx_zero=false;break;}
    if(pfx_zero){ return; }
    /* Invert the last prefix: inv = 1/(Z[0]*...*Z[n-1]) */
    fe_t inv;
    fe_inv(inv,pfx[n-1]);
    /* Work backwards: compute each Z[i]^-1 */
    uint8_t pub33[33];
    for(int i=n-1;i>=1;i--){
        /* zinv = inv * pfx[i-1]  =>  Z[i]^-1 */
        fe_t zinv; fe_mul(zinv,inv,pfx[i-1]);
        /* Update inv for next: inv = inv * Z[i] */
        fe_mul(inv,inv,pts[i].z);
        /* Compute affine x, y */
        fe_t iz2,iz3,xaff,yaff;
        fe_sqr(iz2,zinv); fe_mul(iz3,iz2,zinv);
        fe_mul(xaff,pts[i].x,iz2);
        fe_mul(yaff,pts[i].y,iz3);
        /* Compress: prefix = 02 or 03, x in big-endian */
        pub33[0]=(yaff[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int b=0;b<8;b++)
            pub33[1+(3-w)*8+(7-b)]=(uint8_t)(xaff[w]>>(b*8));
        on_key(i,pub33,ctx);
    }
    /* Last remaining: inv is now Z[0]^-1 */
    {
        fe_t iz2,iz3,xaff,yaff;
        fe_sqr(iz2,inv); fe_mul(iz3,iz2,inv);
        fe_mul(xaff,pts[0].x,iz2);
        fe_mul(yaff,pts[0].y,iz3);
        pub33[0]=(yaff[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int b=0;b<8;b++)
            pub33[1+(3-w)*8+(7-b)]=(uint8_t)(xaff[w]>>(b*8));
        on_key(0,pub33,ctx);
    }
}
