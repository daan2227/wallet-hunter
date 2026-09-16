# Banco de pruebas de la curva elíptica

Compila el `jac_batch.h` de verdad —el que usan el modo puzzle y el modo clave
directa— como binario de escritorio y comprueba que las claves públicas que
produce son las de secp256k1.

```sh
./run.sh
```

## Por qué existe

`fe_mul` estuvo mal desde el principio y nadie se enteró.

La versión original traía ensamblador a mano "para evitar `__uint128_t`" y
sumaba el word bajo del producto **dos veces**: una en C y otra en el propio
asm. `2*3` daba `12`. Como `fe_mul` es la base de `jp_add_G` y de la
normalización por lotes, los dos modos rápidos calculaban claves públicas que
no correspondían a la clave privada que decían estar probando.

No se notó por dos motivos que conviene tener presentes:

1. **El resultado esperado de los dos modos es no encontrar nada.** Un motor
   roto y un motor correcto que aún no ha tenido suerte se ven exactamente
   igual desde fuera: un contador subiendo.

2. **El asm sólo compila en ARM64**, así que el código nunca pudo ejecutarse en
   un portátil, que es donde se prueban las cosas. Esa es justo la razón de que
   este banco exista: obliga a que el código sea portable y por tanto probable.

`simula_fe_mul.py` reproduce paso a paso lo que hacía la versión rota,
incluido el asm, y enseña el `12`. Se conserva para que el fallo quede
documentado y no vuelva a colarse el mismo "optimizado a mano sin probar".

## Kangaroo

`kang.cpp` resuelve logaritmos discretos de los que YA se sabe la respuesta y
comprueba que sale exactamente esa. Es la única prueba que vale aquí: si el
algoritmo estuviera mal, "no encontrar nada" se ve igual que "todavía no".

```sh
g++ -O2 -o kang kang.cpp -lpthread && ./kang
```

Cubre intervalos de 20 a 36 bits, las claves justo en los dos extremos, una
tanda de 30 al azar —los casos elegidos a mano pueden esquivar sin querer justo
el que falla— y cuatro hilos compartiendo la misma tabla, que es como corre en
el móvil.

Los recuentos de operaciones salen alrededor de 2,5 veces raíz(W), que es el
orden que predice la teoría.

### Dos fallos que encontró

**Aliasing.** `jp_dbl` y `jp_add_affine` escribían en `R->y` y después leían
`P->y`. Con `R` y `P` distintos no pasa nada, y así estaban probados; llamados
como `jp_dbl(&S,&S)` —que es exactamente lo que hace la tabla de saltos— leían
la Y ya pisada y devolvían un punto que no está en la curva. `prim.cpp` prueba
ahora los dos casos, aliasado y no.

**k justo en el extremo inferior.** Entonces `P - a·G` es el punto en el
infinito, el rebaño salvaje nace muerto y la búsqueda devolvía "no encontrada"
cuando la respuesta era `a`.

### Reanudar

`persist.cpp` comprueba que guardar y recuperar conserva el trabajo, que un
fichero de otro puzzle se rechaza en vez de mezclarse —dos tablas distintas
darían colisiones que no significan nada— y, lo que importa, que tras recuperar
la búsqueda sigue hasta encontrar la clave.

```sh
g++ -O2 -o persist persist.cpp -lpthread && ./persist
```

## Cuánto cuesta el hash

`coste.cpp` mide qué parte del trabajo por clave se va en el hash160. Importa
porque si el dataset trajera claves públicas en vez de direcciones, ese trozo
se podría saltar entero comparando la x.

```sh
g++ -O3 -o coste coste.cpp && ./coste
```

Medido aquí:

```
con hash160 (como ahora)     1.02 M claves/s
sin hash (compara la x)      2.32 M claves/s
```

El hash se lleva el 56 % del coste. Es la mejora más grande que aparece en el
motor — mayor que usar las instrucciones de SHA-256 por hardware.

## Dónde se va el tiempo en cada modo

`reparto.cpp` mide el coste por candidato de cada pieza. Sin esto, decidir qué
optimizar es adivinar — y la respuesta resulta ser opuesta según el modo.

```sh
g++ -O3 -o reparto reparto.cpp -lcrypto && ./reparto
```

Medido aquí:

```
hash160                     0.548 us
derivación BIP32 (x5)      10.192 us
PBKDF2 2048 (BIP39)      1441.479 us
```

En **clave directa** el hash160 es el 56 % del trabajo, así que un dataset de
claves públicas —comparando la x en vez de la dirección— daría unas 2x.

En **BIP39** el mismo cambio da 1,0004x: PBKDF2 cuesta 2.630 veces más que el
hash160, y el hash es el 0,038 % del candidato. Y PBKDF2 no es una
implementación lenta que se pueda mejorar: las 2048 vueltas las exige la norma,
precisamente para encarecer este tipo de búsqueda. Saltárselas produce semillas
que no son de ningún mnemónico BIP39, que es lo que hace el interruptor de
"escaneo rápido" y por lo que no puede encontrar nada.
