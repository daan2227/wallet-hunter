#pragma once
#include <stdint.h>
#include <string.h>

/* =========================================================
   SHA256 inline optimizado para ARM
   ========================================================= */
#define ROR32(x,n) (((x)>>(n))|((x)<<(32-(n))))
#define CH(x,y,z)  (((x)&(y))^(~(x)&(z)))
#define MAJ(x,y,z) (((x)&(y))^((x)&(z))^((y)&(z)))
#define EP0(x)  (ROR32(x,2)^ROR32(x,13)^ROR32(x,22))
#define EP1(x)  (ROR32(x,6)^ROR32(x,11)^ROR32(x,25))
#define SIG0(x) (ROR32(x,7)^ROR32(x,18)^((x)>>3))
#define SIG1(x) (ROR32(x,17)^ROR32(x,19)^((x)>>10))

static const uint32_t K256[64]={
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static inline void sha256_block(uint32_t *st, const uint8_t *data){
    uint32_t w[64],a,b,c,d,e,f,g,h,t1,t2;
    for(int i=0;i<16;i++) w[i]=((uint32_t)data[i*4]<<24)|((uint32_t)data[i*4+1]<<16)|((uint32_t)data[i*4+2]<<8)|data[i*4+3];
    for(int i=16;i<64;i++) w[i]=SIG1(w[i-2])+w[i-7]+SIG0(w[i-15])+w[i-16];
    a=st[0];b=st[1];c=st[2];d=st[3];e=st[4];f=st[5];g=st[6];h=st[7];
    for(int i=0;i<64;i++){
        t1=h+EP1(e)+CH(e,f,g)+K256[i]+w[i];
        t2=EP0(a)+MAJ(a,b,c);
        h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    st[0]+=a;st[1]+=b;st[2]+=c;st[3]+=d;st[4]+=e;st[5]+=f;st[6]+=g;st[7]+=h;
}

static inline void sha256_33(const uint8_t *in, uint8_t *out){
    uint32_t st[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint8_t blk[64]={0};
    memcpy(blk,in,33);
    blk[33]=0x80;
    blk[62]=0x01; blk[63]=0x08; /* length = 33*8 = 264 bits = 0x108 */
    sha256_block(st,blk);
    for(int i=0;i<8;i++){out[i*4]=(uint8_t)(st[i]>>24);out[i*4+1]=(uint8_t)(st[i]>>16);out[i*4+2]=(uint8_t)(st[i]>>8);out[i*4+3]=(uint8_t)st[i];}
}

/* =========================================================
   RIPEMD160 inline
   ========================================================= */
#define ROL32(x,n) (((x)<<(n))|((x)>>(32-(n))))
#define F1(x,y,z) ((x)^(y)^(z))
#define F2(x,y,z) (((x)&(y))|(~(x)&(z)))
#define F3(x,y,z) (((x)|(~(y)))^(z))
#define F4(x,y,z) (((x)&(z))|((y)&(~(z))))
#define F5(x,y,z) ((x)^((y)|(~(z))))

static const uint32_t KL[5]={0x00000000,0x5A827999,0x6ED9EBA1,0x8F1BBCDC,0xA953FD4E};
static const uint32_t KR[5]={0x50A28BE6,0x5C4DD124,0x6D703EF3,0x7A6D76E9,0x00000000};
static const int RL[80]={
    0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
    7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,
    3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
    1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,
    4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13
};
static const int RR[80]={
    5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
    6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,
    15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
    8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,
    12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11
};
static const int SL[80]={
    11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
    7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,
    11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
    11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,
    9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6
};
static const int SR[80]={
    8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
    9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,
    9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
    15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8,
    8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11
};

static inline void ripemd160_32(const uint8_t *in, uint8_t *out){
    uint32_t st[5]={0x67452301,0xEFCDAB89,0x98BADCFE,0x10325476,0xC3D2E1F0};
    uint8_t blk[64]={0};
    memcpy(blk,in,32);
    blk[32]=0x80;
    blk[56]=0x00; blk[57]=0x01; /* 32*8=256 bits little-endian */
    uint32_t w[16];
    for(int i=0;i<16;i++) w[i]=((uint32_t)blk[i*4+3]<<24)|((uint32_t)blk[i*4+2]<<16)|((uint32_t)blk[i*4+1]<<8)|blk[i*4];
    uint32_t al=st[0],bl=st[1],cl=st[2],dl=st[3],el=st[4];
    uint32_t ar=st[0],br=st[1],cr=st[2],dr=st[3],er=st[4];
    for(int i=0;i<80;i++){
        int r=i/16;
        uint32_t fl,fr;
        if(r==0){fl=F1(bl,cl,dl);fr=F5(br,cr,dr);}
        else if(r==1){fl=F2(bl,cl,dl);fr=F4(br,cr,dr);}
        else if(r==2){fl=F3(bl,cl,dl);fr=F3(br,cr,dr);}
        else if(r==3){fl=F4(bl,cl,dl);fr=F2(br,cr,dr);}
        else{fl=F5(bl,cl,dl);fr=F1(br,cr,dr);}
        uint32_t tl=ROL32(al+fl+w[RL[i]]+KL[r],SL[i])+el;
        al=el;el=dl;dl=ROL32(cl,10);cl=bl;bl=tl;
        uint32_t tr=ROL32(ar+fr+w[RR[i]]+KR[r],SR[i])+er;
        ar=er;er=dr;dr=ROL32(cr,10);cr=br;br=tr;
    }
    uint32_t t=st[1]+cl+dr;
    st[1]=st[2]+dl+er;st[2]=st[3]+el+ar;st[3]=st[4]+al+br;st[4]=st[0]+bl+cr;st[0]=t;
    for(int i=0;i<5;i++){out[i*4]=(uint8_t)st[i];out[i*4+1]=(uint8_t)(st[i]>>8);out[i*4+2]=(uint8_t)(st[i]>>16);out[i*4+3]=(uint8_t)(st[i]>>24);}
}

/* Hash160 inline: SHA256(pub33) -> RIPEMD160 */
static inline void hash160_inline(const uint8_t *pub33, uint8_t *out20){
    uint8_t sha[32];
    sha256_33(pub33,sha);
    ripemd160_32(sha,out20);
}
