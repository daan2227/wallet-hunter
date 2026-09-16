#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include "/home/user/wallet-hunter/app/src/main/cpp/jac_batch.h"

static void to_limbs(const char*hex, fe_t o){
    uint8_t b[32]; for(int i=0;i<32;i++){unsigned v;sscanf(hex+i*2,"%2x",&v);b[i]=v;}
    for(int i=0;i<4;i++){o[3-i]=0;for(int j=0;j<8;j++)o[3-i]|=((uint64_t)b[i*8+j])<<(56-j*8);}
}
static void pr(const char*n,const fe_t a){
    printf("%s=",n); for(int w=3;w>=0;w--) printf("%016llx",(unsigned long long)a[w]); printf("\n");
}
struct Cap { int n; uint8_t keys[64][33]; };
static void cap(int idx,const uint8_t*p33,void*c){
    Cap*x=(Cap*)c; if(idx<64){memcpy(x->keys[idx],p33,33); if(idx+1>x->n)x->n=idx+1;}
}
int main(){
    /* 1) fe_mul basico */
    fe_t a,b,r; memset(a,0,32); memset(b,0,32); a[0]=2; b[0]=3;
    fe_mul(r,a,b);
    printf("2*3 -> %llu  %s\n",(unsigned long long)r[0], r[0]==6&&!r[1]&&!r[2]&&!r[3]?"OK":"MAL");

    /* 2) (p-1)*(p-2) mod p == 2 */
    to_limbs("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2E",a);
    to_limbs("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2D",b);
    fe_mul(r,a,b);
    printf("(p-1)*(p-2) -> %llu  %s\n",(unsigned long long)r[0], r[0]==2&&!r[1]&&!r[2]&&!r[3]?"OK":"MAL");

    /* 3) fe_inv: a * a^-1 == 1 */
    to_limbs("0123456789ABCDEFFEDCBA98765432100123456789ABCDEFFEDCBA9876543210",a);
    fe_inv(b,a); fe_mul(r,a,b);
    printf("a*inv(a) -> %llu  %s\n",(unsigned long long)r[0], r[0]==1&&!r[1]&&!r[2]&&!r[3]?"OK":"MAL");

    /* 4) La prueba de verdad: G, 2G, 3G... y sus pubkeys comprimidas */
    uint8_t g65[65]={0x04};
    /* Se arranca en 2G: sumar G a G es un DOBLADO, y la formula de adicion
       mixta no lo cubre (H sale 0 y Z3 tambien). En la app no pasa porque
       nunca se empieza exactamente en k=1. */
    { const char*gx="C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5";
      const char*gy="1AE168FEA63DC339A3C58419466CEAEEF7F632653266D0E1236431A950CFE52A";
      for(int i=0;i<32;i++){unsigned v;sscanf(gx+i*2,"%2x",&v);g65[1+i]=v;}
      for(int i=0;i<32;i++){unsigned v;sscanf(gy+i*2,"%2x",&v);g65[33+i]=v;} }
    JP pts[8]; jp_from_affine(&pts[0],g65);
    for(int i=1;i<8;i++) jp_add_G(&pts[i],&pts[i-1]);
    static fe_t pfx[8];
    Cap c={0};
    jac_batch_hash160(pts,8,pfx,cap,&c);
    const char* esperado[8]={
      "02C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5",
      "02F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
      "02E493DBF1C10D80F3581E4904930B1404CC6C13900EE0758474FA94ABE8C4CD13",
      "022F8BDE4D1A07209355B4A7250A5C5128E88B84BDDC619AB7CBA8D569B240EFE4",
      "03FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A1460297556",
      "025CBDF0646E5DB4EAA398F365F2EA7A0E3D419B7E0330E39CE92BDDEDCAC4F9BC",
      "022F01E5E15CCA351DAFF3843FB70F3C2F0A1BDD05E5AF888A67784EF3E10A2A01",
      "03ACD484E2F0C7F65309AD178A9F559ABDE09796974C57E714C35F110DFC27CCBE"};
    int fallos=0;
    for(int i=0;i<8;i++){
        char got[67]={0}; for(int b2=0;b2<33;b2++) sprintf(got+b2*2,"%02X",c.keys[i][b2]);
        int ok=strcmp(got,esperado[i])==0; if(!ok)fallos++;
        printf("%dG  %s\n", i+2, ok?"OK":"MAL");
        if(!ok){ printf("    esperado %s\n    obtenido %s\n",esperado[i],got); }
    }
    printf("\n%s\n", fallos==0 ? "TODO CORRECTO" : "HAY FALLOS");
    return fallos;
}
