#pragma once
/* Indice de direcciones de 9 bytes por registro (direcciones_ordenado.bin):
 *
 *   [tipo:1][primeros 8 bytes del hash:8], sin cabecera, ordenado por los 9
 *   bytes. tipo: 0 P2PKH, 1 P2WPKH, 2 P2SH, 3 P2WSH/P2TR (hash de 32 bytes
 *   truncado), 9 otros.
 *
 * El fichero no se copia a memoria: se proyecta con mmap y el sistema trae las
 * paginas que hagan falta (333 MB para 38,8 M direcciones). Delante va un
 * filtro de Bloom por bloques: los 6 bits de cada consulta caen en la misma
 * linea de cache de 64 bytes, asi que descartar una clave (lo que pasa casi
 * siempre) cuesta un solo fallo de cache, y solo lo que pasa el filtro va a la
 * busqueda binaria en el fichero.
 *
 * Con 8 bytes de hash, una coincidencia es "casi segura": la probabilidad de
 * que una clave al azar coincida por casualidad con alguno de los 38,8 M
 * prefijos es 38,8e6 / 2^64 ~ 2e-12 por consulta. A 20 M consultas por
 * segundo eso es un falso positivo cada ~7 horas de media, asi que un
 * hallazgo se guarda marcado para comprobar el saldo. */
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#define I9_REG 9

struct Indice9 {
    const uint8_t *d = nullptr;   /* el fichero proyectado */
    size_t   len = 0;
    uint64_t n = 0;               /* registros */
    uint64_t *bloom = nullptr;    /* bloques de 8 palabras (512 bits) */
    uint64_t nbloques = 0;
    uint64_t por_tipo[10] = {0};
};

static inline uint64_t i9_mezcla(uint64_t v){
    v^=v>>33; v*=0xff51afd7ed558ccdULL; v^=v>>33; v*=0xc4ceb9fe1a85ec53ULL; v^=v>>33; return v;
}
/* El hash de (tipo, prefijo). Los 8 bytes del prefijo ya son un hash
 * criptografico, pero se mezclan con el tipo para que el mismo prefijo en dos
 * tipos no comparta bits. */
static inline uint64_t i9_hash(uint8_t tipo, const uint8_t *p8){
    uint64_t v; memcpy(&v,p8,8);
    return i9_mezcla(v ^ ((uint64_t)(tipo+1)*0x9E3779B97F4A7C15ULL));
}
static inline void i9_bloom_poner(Indice9 *x, uint64_t h){
    uint64_t *b=x->bloom + 8*(uint64_t)(((__uint128_t)h*x->nbloques)>>64);
    uint64_t g=i9_mezcla(h+0x632BE59BD9B4E019ULL);
    for(int k=0;k<6;k++){ unsigned bit=(unsigned)(g>>(9*k))&511; b[bit>>6]|=1ULL<<(bit&63); }
}
static inline int i9_bloom_ver(const Indice9 *x, uint64_t h){
    const uint64_t *b=x->bloom + 8*(uint64_t)(((__uint128_t)h*x->nbloques)>>64);
    uint64_t g=i9_mezcla(h+0x632BE59BD9B4E019ULL);
    for(int k=0;k<6;k++){ unsigned bit=(unsigned)(g>>(9*k))&511; if(!(b[bit>>6]&(1ULL<<(bit&63)))) return 0; }
    return 1;
}

/* Indice del registro (tipo, primeros 8 bytes de hash) o -1. */
static inline int64_t i9_buscar(const Indice9 *x, uint8_t tipo, const uint8_t *hash){
    if(!x->d) return -1;
    if(!i9_bloom_ver(x,i9_hash(tipo,hash))) return -1;
    uint8_t k[I9_REG]; k[0]=tipo; memcpy(k+1,hash,8);
    int64_t lo=0, hi=(int64_t)x->n-1;
    while(lo<=hi){
        int64_t m=(lo+hi)>>1;
        int c=memcmp(x->d+(uint64_t)m*I9_REG,k,I9_REG);
        if(!c) return m;
        if(c<0) lo=m+1; else hi=m-1;
    }
    return -1;
}

static void i9_cerrar(Indice9 *x){
    if(x->d) munmap((void*)x->d,x->len);
    free(x->bloom);
    *x=Indice9();
}

/* ¿Tiene pinta de indice de 9 bytes? Tamano multiplo de 9, que no sea el
 * formato viejo (cuenta de 8 bytes + 20 por direccion), tipos validos y
 * ordenado en una muestra. */
static int i9_parece(const uint8_t *d, size_t len){
    if(len<I9_REG || len%I9_REG) return 0;
    if(len>=8){ uint64_t n; memcpy(&n,d,8); if(n && 8+n*20==len) return 0; }
    uint64_t n=len/I9_REG, paso=n>2000?n/2000:1;
    const uint8_t *prev=nullptr;
    for(uint64_t i=0;i<n;i+=paso){
        const uint8_t *r=d+i*I9_REG;
        if(r[0]>3 && r[0]!=9) return 0;
        if(prev && memcmp(prev,r,I9_REG)>0) return 0;
        prev=r;
    }
    return 1;
}

/* Abre y prepara. progreso(hechos,total) de vez en cuando (puede ser NULL).
 * Devuelve 0 si no es un indice de 9 bytes o no se puede abrir. */
static int i9_abrir(Indice9 *x, const char *ruta, void (*progreso)(uint64_t,uint64_t)){
    int fd=open(ruta,O_RDONLY); if(fd<0) return 0;
    struct stat st; if(fstat(fd,&st)){ close(fd); return 0; }
    size_t len=(size_t)st.st_size;
    void *m=len?mmap(nullptr,len,PROT_READ,MAP_SHARED,fd,0):MAP_FAILED; close(fd);
    if(m==MAP_FAILED) return 0;
    if(!i9_parece((const uint8_t*)m,len)){ munmap(m,len); return 0; }
    Indice9 y; y.d=(const uint8_t*)m; y.len=len; y.n=len/I9_REG;
    /* 16 bits por direccion: ~0,1 % de falsos positivos del filtro. */
    y.nbloques=(y.n*16+511)/512; if(!y.nbloques) y.nbloques=1;
    y.bloom=(uint64_t*)calloc(y.nbloques*8,sizeof(uint64_t));
    if(!y.bloom){ munmap(m,len); return 0; }
    madvise(m,len,MADV_SEQUENTIAL);
    for(uint64_t i=0;i<y.n;i++){
        const uint8_t *r=y.d+i*I9_REG;
        y.por_tipo[r[0]<10?r[0]:9]++;
        i9_bloom_poner(&y,i9_hash(r[0],r+1));
        if(progreso && (i&0xFFFFF)==0) progreso(i,y.n);
    }
    madvise(m,len,MADV_RANDOM);
    i9_cerrar(x);
    *x=y;
    return 1;
}
