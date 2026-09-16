/* Comprueba jp_dbl y jp_add_affine contra puntos conocidos de secp256k1. */
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include "../../app/src/main/cpp/jac_batch.h"

static void hx(const char*h, fe_t o){
    uint8_t b[32]; for(int i=0;i<32;i++){unsigned v;sscanf(h+i*2,"%2x",&v);b[i]=v;}
    for(int i=0;i<4;i++){o[3-i]=0;for(int j=0;j<8;j++)o[3-i]|=((uint64_t)b[i*8+j])<<(56-j*8);}
}
/* Normaliza un JP a x,y afines */
static void norm(const JP*P, fe_t x, fe_t y){
    fe_t zi,z2,z3; fe_inv(zi,(uint64_t*)P->z);
    fe_sqr(z2,zi); fe_mul(z3,z2,zi);
    fe_mul(x,(uint64_t*)P->x,z2); fe_mul(y,(uint64_t*)P->y,z3);
}
static int eq(const fe_t a,const char*h){ fe_t b; hx(h,b); return memcmp(a,b,32)==0; }
static void mk(JP*P,const char*xs,const char*ys){
    hx(xs,P->x); hx(ys,P->y); memset(P->z,0,32); P->z[0]=1;
}
int main(){
    const char *GX="79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798";
    const char *GY="483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8";
    const char *X2="C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5";
    const char *Y2="1AE168FEA63DC339A3C58419466CEAEEF7F632653266D0E1236431A950CFE52A";
    const char *X4="E493DBF1C10D80F3581E4904930B1404CC6C13900EE0758474FA94ABE8C4CD13";
    const char *Y4="51ED993EA0D455B75642E2098EA51448D967AE33BFBDFE40CFE97BDC47739922";
    const char *X8="2F01E5E15CCA351DAFF3843FB70F3C2F0A1BDD05E5AF888A67784EF3E10A2A01";
    const char *Y8="5C4DA8A741539949293D082A132D13B4C2E213D6BA5B7617B5DA2CB76CBDE904";
    const char *X3="F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9";
    const char *Y3="388F7B0F632DE8140FE337E62A37F3566500A99934C2231B6CB9FD7584B8E672";
    fe_t x,y; JP A,B,R; int f=0;

    /* doblar G -> 2G */
    mk(&A,GX,GY); jp_dbl(&R,&A); norm(&R,x,y);
    printf("2*G   %s\n", (eq(x,X2)&&eq(y,Y2))?"OK":"MAL"); f+=!(eq(x,X2)&&eq(y,Y2));
    /* doblar 2G -> 4G */
    mk(&A,X2,Y2); jp_dbl(&R,&A); norm(&R,x,y);
    printf("2*2G  %s\n", (eq(x,X4)&&eq(y,Y4))?"OK":"MAL"); f+=!(eq(x,X4)&&eq(y,Y4));
    /* doblar 4G -> 8G */
    mk(&A,X4,Y4); jp_dbl(&R,&A); norm(&R,x,y);
    printf("2*4G  %s\n", (eq(x,X8)&&eq(y,Y8))?"OK":"MAL"); f+=!(eq(x,X8)&&eq(y,Y8));
    /* suma general: 2G + G = 3G */
    fe_t gx,gy; hx(GX,gx); hx(GY,gy);
    mk(&A,X2,Y2); jp_add_affine(&R,&A,gx,gy); norm(&R,x,y);
    printf("2G+G  %s\n", (eq(x,X3)&&eq(y,Y3))?"OK":"MAL"); f+=!(eq(x,X3)&&eq(y,Y3));
    /* suma general con un punto que no es G: 4G + 4G no vale (doblado),
       pero 4G + 2G = 6G */
    const char *X6="FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A1460297556";
    const char *Y6="AE12777AACFBB620F3BE96017F45C560DE80F0F6518FE4A03C870C36B075F297";
    fe_t x2,y2; hx(X2,x2); hx(Y2,y2);
    mk(&A,X4,Y4); jp_add_affine(&R,&A,x2,y2); norm(&R,x,y);
    printf("4G+2G %s\n", (eq(x,X6)&&eq(y,Y6))?"OK":"MAL"); f+=!(eq(x,X6)&&eq(y,Y6));
    /* ALIASING: llamar con R == P. Es lo que hace kg_scalar_mul y lo que
       montaba la tabla de saltos de Kangaroo. Con R y P distintos todo pasaba;
       aliasado, jp_dbl leia la Y ya pisada y devolvia un punto que no esta en
       la curva. */
    mk(&A,GX,GY); jp_dbl(&A,&A); norm(&A,x,y);
    printf("dbl(&A,&A)          %s\n", (eq(x,X2)&&eq(y,Y2))?"OK":"MAL");
    f+=!(eq(x,X2)&&eq(y,Y2));
    mk(&A,X2,Y2); jp_add_affine(&A,&A,gx,gy); norm(&A,x,y);
    printf("add_affine(&A,&A,G) %s\n", (eq(x,X3)&&eq(y,Y3))?"OK":"MAL");
    f+=!(eq(x,X3)&&eq(y,Y3));
    /* Y encadenado, que es el uso real: doblar ocho veces seguidas. */
    mk(&A,GX,GY); for(int i=0;i<3;i++) jp_dbl(&A,&A); norm(&A,x,y);
    printf("dbl x3 encadenado   %s\n", (eq(x,X8)&&eq(y,Y8))?"OK":"MAL");
    f+=!(eq(x,X8)&&eq(y,Y8));

    printf("\n%s\n", f?"HAY FALLOS":"TODO CORRECTO");
    return f;
}
