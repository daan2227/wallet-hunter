#pragma once
/* SHA-512, HMAC-SHA512 y PBKDF2-HMAC-SHA512 propios.
 *
 * PBKDF2 con 2048 vueltas es casi todo el coste de pasar de una frase BIP39 a
 * sus direcciones (1,4 ms de 1,45). OpenSSL lo hace bien pero generico: en
 * cada vuelta copia un contexto HMAC, pasa por EVP, rellena y convierte bytes.
 * Aqui cada vuelta son exactamente dos bloques de compresion sobre palabras de
 * 64 bits, sin copiar ni convertir nada:
 *
 *   interno = compresion(estado_ipad, U || relleno fijo)
 *   U       = compresion(estado_opad, interno || relleno fijo)
 *
 * El resumen de un bloque ya ES el mensaje del siguiente, palabra a palabra.
 *
 * Tres formas de hacer el bucle, que se eligen solas (sha512_modo):
 *   0  software
 *   1  instrucciones SHA-512 del procesador (ARMv8.2, si las tiene)
 *   2  las mismas, con dos frases entrelazadas: cada instruccion tarda varios
 *      ciclos en dar su resultado y la siguiente ronda lo necesita; con dos
 *      cadenas independientes el procesador rellena esa espera con la otra.
 * sha512.cpp y sha512_hw.cpp. */
#include <stdint.h>
#include <stddef.h>

/* Un bloque: st += compresion(st, w). w son las 16 palabras ya en orden. */
void sha512_bloque(uint64_t st[8], const uint64_t w[16]);

void sha512(const uint8_t *m, size_t n, uint8_t out[64]);
void hmac_sha512(const uint8_t *k, size_t kn, const uint8_t *m, size_t mn, uint8_t out[64]);

/* PBKDF2-HMAC-SHA512 con salida de 64 bytes (lo que pide BIP39). */
void pbkdf2_sha512(const uint8_t *pw, size_t pwn, const uint8_t *sal, size_t saln,
                   uint32_t vueltas, uint8_t out[64]);
/* Dos contrasenas con la misma sal a la vez. Con modo 2 van entrelazadas;
 * con los demas, una detras de otra. */
void pbkdf2_sha512_x2(const uint8_t *pwA, size_t nA, const uint8_t *pwB, size_t nB,
                      const uint8_t *sal, size_t saln, uint32_t vueltas,
                      uint8_t outA[64], uint8_t outB[64]);

/* BIP39: semilla = PBKDF2(frase, "mnemonic"+pass, 2048). */
void bip39_semilla(const char *frase, size_t n, const char *pass, size_t pn, uint8_t out[64]);
void bip39_semilla_x2(const char *fA, size_t nA, const char *fB, size_t nB,
                      const char *pass, size_t pn, uint8_t outA[64], uint8_t outB[64]);

int  sha512_tiene_hw(void);       /* el procesador tiene las instrucciones */
int  sha512_modo(void);           /* el que se esta usando */
void sha512_fijar_modo(int m);    /* -1 = elegir solo; 0/1/2 a mano (no pasa de lo que haya) */
const char *sha512_nombre_modo(int m);

/* Internas: los bucles de las vueltas 2..n de PBKDF2. */
void pbkdf2_bucle_sw(const uint64_t is[8], const uint64_t os[8], uint64_t u[8], uint64_t acc[8], uint32_t n);
#if defined(__aarch64__)
void pbkdf2_bucle_hw(const uint64_t is[8], const uint64_t os[8], uint64_t u[8], uint64_t acc[8], uint32_t n);
void pbkdf2_bucle_hw2(const uint64_t isA[8], const uint64_t osA[8], uint64_t uA[8], uint64_t accA[8],
                      const uint64_t isB[8], const uint64_t osB[8], uint64_t uB[8], uint64_t accB[8], uint32_t n);
void sha512_bloque_hw(uint64_t st[8], const uint64_t w[16]);
#endif
