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
static void fe_mul(fe_t r,const fe_t a,const fe_t b){
    uint64_t t[8]={0};
    for(int i=0;i<4;i++){
        uint64_t c=0;
        for(int j=0;j<4;j++){
            __uint128_t p=(__uint128_t)a[i]*b[j]+t[i+j]+c;
            t[i+j]=(uint64_t)p;c=(uint64_t)(p>>64);
        }
        t[i+4]+=c;
    }
    /* Reduce: p=2^256-C, C=2^32+977=0x1000003D1 */
    const uint64_t C=0x1000003D1ULL;
    uint64_t carry=0;
    for(int i=0;i<4;i++){
        __uint128_t p=(__uint128_t)t[i+4]*C+t[i]+carry;
        t[i]=(uint64_t)p;carry=(uint64_t)(p>>64);
    }
    /* Second reduction: carry*2^256 mod p = carry*C */
    if(carry){
        __uint128_t p2=(__uint128_t)carry*C+t[0];
        t[0]=(uint64_t)p2;uint64_t c2=(uint64_t)(p2>>64);
        for(int i=1;i<4&&c2;i++){t[i]+=c2;c2=(t[i]<c2)?1:0;}
    }
    if(fe_cmp(t,FP)>=0){
        uint64_t borrow=0;
        for(int i=0;i<4;i++){
            __uint128_t p=(__uint128_t)t[i]-FP[i]-borrow;
            t[i]=(uint64_t)p;borrow=(p>>127)&1;
        }
    }
    memcpy(r,t,32);
}
static void fe_sqr(fe_t r,const fe_t a){fe_mul(r,a,a);}
static void fe_dbl(fe_t r,const fe_t a){fe_add(r,a,a);}

/* Modular inverse: a^(p-2) mod p via square-and-multiply
   p-2 = FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFE FFFFFC2D */
static void fe_inv(fe_t r,const fe_t a){
    const uint64_t EXP[4]={
        0xFFFFFFFEFFFFFC2DULL,0xFFFFFFFFFFFFFFFFULL,
        0xFFFFFFFFFFFFFFFFULL,0xFFFFFFFFFFFFFFFFULL
    };
    fe_t res; res[0]=1;res[1]=res[2]=res[3]=0;
    for(int w=3;w>=0;w--){
        for(int b=63;b>=0;b--){
            fe_sqr(res,res);
            if((EXP[w]>>b)&1) fe_mul(res,res,a);
        }
    }
    memcpy(r,res,32);
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

/* Mixed addition: R = P (Jacobian) + G (affine)
   Uses formulas from https://hyperelliptic.org/EFD/g1p/auto-shortw-jacobian-0.html#addition-madd-2007-bl */
static void jp_add_G(JP *R,const JP *P){
    fe_t Z1Z1,U2,S2,H,HH,HHH,R2,V,tmp;
    fe_sqr(Z1Z1,P->z);
    fe_mul(U2,FIELD_GX,Z1Z1);
    fe_mul(S2,FIELD_GY,P->z); fe_mul(S2,S2,Z1Z1);
    fe_sub(H,U2,P->x);
    fe_sub(R2,S2,P->y);
    fe_sqr(HH,H);
    fe_mul(HHH,H,HH);
    fe_mul(V,P->x,HH);
    /* X3 = R^2 - HHH - 2*V */
    fe_sqr(R->x,R2);
    fe_sub(R->x,R->x,HHH);
    fe_dbl(tmp,V); fe_sub(R->x,R->x,tmp);
    /* Y3 = R*(V-X3) - Y1*HHH */
    fe_sub(tmp,V,R->x);
    fe_mul(R->y,R2,tmp);
    fe_mul(tmp,P->y,HHH);
    fe_sub(R->y,R->y,tmp);
    /* Z3 = H*Z1 */
    fe_mul(R->z,H,P->z);
}

#define JAC_BATCH 16000

/* Batch normalize: given pts[0..n-1] in Jacobian, write compressed pub33[i] for each.
   Uses Montgomery batch inversion: 1 inv + 3n mults instead of n invs. */
static void jac_batch_hash160(
    JP *pts, int n,
    void (*on_key)(int idx, const uint8_t *pub33, void *ctx),
    void *ctx)
{
    /* Prefix products of Z: pfx[i] = Z[0]*Z[1]*...*Z[i] */
    fe_t *pfx = (fe_t*)malloc(n*sizeof(fe_t));
    if(!pfx) return;
    /* Check for zero Z (point at infinity) - skip batch if found */
    for(int i=0;i<n;i++){
        bool zz=true;
        for(int j=0;j<4;j++) if(pts[i].z[j]){zz=false;break;}
        if(zz){ free(pfx); return; }
    }
    memcpy(pfx[0],pts[0].z,32);
    for(int i=1;i<n;i++) fe_mul(pfx[i],pfx[i-1],pts[i].z);
    /* Check final prefix is not zero before inverting */
    bool pfx_zero=true;
    for(int j=0;j<4;j++) if(pfx[n-1][j]){pfx_zero=false;break;}
    if(pfx_zero){ free(pfx); return; }
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
    free(pfx);
}
