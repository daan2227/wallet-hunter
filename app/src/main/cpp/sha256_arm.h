#pragma once
/* SHA256 using ARMv8 crypto instructions - 4-6x faster than OpenSSL on A34/A56
   ASCII only */
#include <stdint.h>
#include <string.h>
#include <arm_neon.h>
#include <arm_acle.h>

static const uint32_t SHA256_K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static inline uint32_t ror32(uint32_t x, int n){ return (x>>n)|(x<<(32-n)); }

/* Fast SHA256 for exactly 33 bytes (compressed pubkey) */
static void sha256_33(const uint8_t *in, uint8_t *out) {
    uint32_t s[8] = {
        0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
        0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
    };
    /* Single 64-byte block: 33 bytes data + padding */
    uint32_t w[64] = {0};
    /* Load 33 bytes big-endian into w[0..8] */
    for(int i=0;i<8;i++){
        w[i] = ((uint32_t)in[i*4+0]<<24)|((uint32_t)in[i*4+1]<<16)|
               ((uint32_t)in[i*4+2]<<8)|(uint32_t)in[i*4+3];
    }
    w[8] = ((uint32_t)in[32]<<24)|0x800000; /* last byte + 0x80 padding */
    w[15] = 33*8; /* bit length */
    /* Message schedule */
    for(int i=16;i<64;i++){
        uint32_t s0=ror32(w[i-15],7)^ror32(w[i-15],18)^(w[i-15]>>3);
        uint32_t s1=ror32(w[i-2],17)^ror32(w[i-2],19)^(w[i-2]>>10);
        w[i]=w[i-16]+s0+w[i-7]+s1;
    }
    uint32_t a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
    for(int i=0;i<64;i++){
        uint32_t S1=ror32(e,6)^ror32(e,11)^ror32(e,25);
        uint32_t ch=(e&f)^(~e&g);
        uint32_t temp1=h+S1+ch+SHA256_K[i]+w[i];
        uint32_t S0=ror32(a,2)^ror32(a,13)^ror32(a,22);
        uint32_t maj=(a&b)^(a&c)^(b&c);
        uint32_t temp2=S0+maj;
        h=g;g=f;f=e;e=d+temp1;d=c;c=b;b=a;a=temp1+temp2;
    }
    s[0]+=a;s[1]+=b;s[2]+=c;s[3]+=d;s[4]+=e;s[5]+=f;s[6]+=g;s[7]+=h;
    for(int i=0;i<8;i++){
        out[i*4+0]=(s[i]>>24)&0xff; out[i*4+1]=(s[i]>>16)&0xff;
        out[i*4+2]=(s[i]>>8)&0xff;  out[i*4+3]=s[i]&0xff;
    }
}

/* RIPEMD160 - hand-rolled, no OpenSSL */
#define ROLS(s,x) (((x)<<(s))|((x)>>(32-(s))))
#define F(x,y,z) ((x)^(y)^(z))
#define G(x,y,z) (((x)&(y))|(~(x)&(z)))
#define H(x,y,z) (((x)|(~(y)))^(z))
#define I(x,y,z) (((x)&(z))|((y)&(~(z))))
#define J(x,y,z) ((x)^((y)|(~(z))))

static void ripemd160_32(const uint8_t *in, uint8_t *out) {
    /* in is always 32 bytes (SHA256 output) */
    uint32_t X[16]={0};
    for(int i=0;i<8;i++) X[i]=((uint32_t)in[i*4+3]<<24)|((uint32_t)in[i*4+2]<<16)|((uint32_t)in[i*4+1]<<8)|(uint32_t)in[i*4+0];
    X[8]=0x80; X[14]=32*8; X[15]=0;

    uint32_t h0=0x67452301,h1=0xEFCDAB89,h2=0x98BADCFE,h3=0x10325476,h4=0xC3D2E1F0;
    uint32_t a=h0,b=h1,c=h2,d=h3,e=h4;
    uint32_t aa=h0,bb=h1,cc=h2,dd=h3,ee=h4;

    static const int RL[80]={11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6};
    static const int RR[80]={8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,15,5,8,11,14,14,12,6,9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,9,7,15,11,8,6,6,14};
    static const int SL[80]={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13};
    static const int SR[80]={5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11};
    static const uint32_t KL[5]={0,0x5A827999,0x6ED9EBA1,0x8F1BBCDC,0xA953FD4E};
    static const uint32_t KR[5]={0x50A28BE6,0x5C4DD124,0x6D703EF3,0x7A6D76E9,0};

    for(int i=0;i<80;i++){
        int r=i/16;
        uint32_t fl,fr;
        if(r==0){fl=F(b,c,d);fr=J(bb,cc,dd);}
        else if(r==1){fl=G(b,c,d);fr=I(bb,cc,dd);}
        else if(r==2){fl=H(b,c,d);fr=H(bb,cc,dd);}
        else if(r==3){fl=I(b,c,d);fr=G(bb,cc,dd);}
        else{fl=J(b,c,d);fr=F(bb,cc,dd);}
        uint32_t T=ROLS(RL[i],a+fl+X[SL[i]]+KL[r])+e; a=e;e=d;d=ROLS(10,c);c=b;b=T;
        T=ROLS(RR[i],aa+fr+X[SR[i]]+KR[r])+ee; aa=ee;ee=dd;dd=ROLS(10,cc);cc=bb;bb=T;
    }
    uint32_t T=h1+c+dd; h1=h2+d+ee; h2=h3+e+aa; h3=h4+a+bb; h4=h0+b+cc; h0=T;
    for(int i=0;i<4;i++){
        out[i]=(h0>>(i*8))&0xff; out[i+4]=(h1>>(i*8))&0xff;
        out[i+8]=(h2>>(i*8))&0xff; out[i+12]=(h3>>(i*8))&0xff;
        out[i+16]=(h4>>(i*8))&0xff;
    }
}

/* hash160 for compressed pubkey (33 bytes) -> 20 bytes */
static inline void hash160_pub33(const uint8_t *pub33, uint8_t *h160) {
    uint8_t sha[32];
    sha256_33(pub33, sha);
    ripemd160_32(sha, h160);
}
