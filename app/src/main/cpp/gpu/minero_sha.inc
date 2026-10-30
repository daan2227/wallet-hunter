/* Doble SHA-256 de una cabecera de minado: el MISMO texto se compila como GLSL (minero.comp, en la GPU) y como
 * C++ (tools/ec-harness/minero_gpu.cpp, contra el bloque 1). Quien lo incluye define:
 *   uint (entero de 32 bits sin signo), KT[64] y H0T[8] (constantes de SHA-256),
 *   ARR8_IN(n) / ARR8_OUT(n) / ARR16_IN(n) (parámetros array) y CLZ(x) (ceros a la izquierda, x != 0).
 * Sólo usa lo que es igual en los dos lenguajes. */

uint ror_(uint x, int n) { return (x >> uint(n)) | (x << uint(32 - n)); }
uint bsw(uint x) { return (x >> 24u) | ((x >> 8u) & 0xff00u) | ((x << 8u) & 0xff0000u) | (x << 24u); }

/* Lo que sigue usa arrays: en la GPU no se compila (MINERO_SOLO_BASE), para que no le quite registros a la
 * vía rápida; allí los candidatos los comprueba la CPU. */
#ifndef MINERO_SOLO_BASE
/* Un bloque: estado st, 16 palabras m (big-endian), resultado en o. */
void comprimir(ARR8_IN(st), ARR16_IN(m), ARR8_OUT(o)) {
    uint w[16];
    for (int i = 0; i < 16; i++) w[i] = m[i];
    uint a = st[0]; uint b = st[1]; uint c = st[2]; uint d = st[3];
    uint e = st[4]; uint f = st[5]; uint g = st[6]; uint h = st[7];
    for (int t = 0; t < 64; t++) {
        uint wt;
        if (t < 16) wt = w[t];
        else {
            uint w15 = w[(t - 15) & 15]; uint w2 = w[(t - 2) & 15];
            uint s0 = ror_(w15, 7) ^ ror_(w15, 18) ^ (w15 >> 3u);
            uint s1 = ror_(w2, 17) ^ ror_(w2, 19) ^ (w2 >> 10u);
            wt = w[t & 15] + s0 + w[(t - 7) & 15] + s1;
            w[t & 15] = wt;
        }
        uint t1 = h + (ror_(e, 6) ^ ror_(e, 11) ^ ror_(e, 25)) + ((e & f) ^ (~e & g)) + KT[t] + wt;
        uint t2 = (ror_(a, 2) ^ ror_(a, 13) ^ ror_(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    o[0] = st[0] + a; o[1] = st[1] + b; o[2] = st[2] + c; o[3] = st[3] + d;
    o[4] = st[4] + e; o[5] = st[5] + f; o[6] = st[6] + g; o[7] = st[7] + h;
}

/* Doble SHA-256 de la cabecera: mid = compresión de sus 64 primeros bytes; t0..t2 = bytes 64..75 en palabras
 * big-endian; el nonce va en little-endian en la cabecera. */
void minar_hash(ARR8_IN(mid), uint t0, uint t1, uint t2, uint nonce, ARR8_OUT(h2)) {
    uint m[16];
    m[0] = t0; m[1] = t1; m[2] = t2; m[3] = bsw(nonce); m[4] = 0x80000000u;
    for (int i = 5; i < 15; i++) m[i] = 0u;
    m[15] = 640u;
    uint h1[8];
    comprimir(mid, m, h1);
    uint m2[16];
    for (int i = 0; i < 8; i++) m2[i] = h1[i];
    m2[8] = 0x80000000u;
    for (int i = 9; i < 15; i++) m2[i] = 0u;
    m2[15] = 256u;
    comprimir(H0T, m2, h2);
}

/* Ceros a la izquierda del hash tal como se escribe (el digest al revés). */
uint ceros(ARR8_IN(h)) {
    uint n = 0u;
    for (int i = 7; i >= 0; i--) {
        uint v = bsw(h[i]);
        if (v == 0u) n += 32u; else return n + CLZ(v);
    }
    return n;
}

/* ¿hash ≤ objetivo? (objetivo en 8 palabras, la más significativa primero) */
bool cumple(ARR8_IN(h), ARR8_IN(obj)) {
    for (int i = 0; i < 8; i++) {
        uint v = bsw(h[7 - i]);
        if (v < obj[i]) return true;
        if (v > obj[i]) return false;
    }
    return true;
}
#endif
