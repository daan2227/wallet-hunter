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

## El parseo del rango

`hex.cpp` prueba `kg_hex_a_be32`, que convierte el rango de texto a 32 bytes.

Existe porque ahí se coló un fallo que los tests de Kangaroo no podían ver: a
`kg_setup` le pasaban buffers de 32 bytes ya montados, nunca el texto que llega
desde la app. Y el parseo **exigía un número par de dígitos hexadecimales**.

Un número en hexadecimal no tiene por qué tener longitud par: 2^139 es un 8
seguido de 34 ceros, o sea 35 dígitos. Eso hacía que arrancar Kangaroo fallara
de plano en los puzzles #140, #145 y #155 — tres de los únicos cinco que admiten
Kangaroo.

```sh
g++ -O2 -o hex hex.cpp -lpthread && ./hex
```

Cubre longitud impar y par, el prefijo `0x`, ceros a la izquierda, y los casos
que deben rechazarse.

## Cuánto cuesta Kangaroo

`constante.cpp` mide **la cifra del motor**: cuántas operaciones de grupo cuesta
resolver un logaritmo discreto, en unidades de √W. El doble de esa constante es
el doble de días de móvil encendido.

Estaba apuntada de memoria de una tanda a mano (*«3,4–4,6»*), que es lo mismo
que no tener nada: sin punto de partida reproducible no se puede saber si un
cambio mejora algo.

```sh
g++ -O2 -o constante constante.cpp -lpthread
./constante 30 256 5 400 1      # bits, canguros, dbits, tandas, barrido
```

Lo importante no es la media, es **el reparto por cuartil de la posición de la
clave dentro del rango**. Con el reparto de salida viejo salía así:

```
politica 0 (ancha)   4.16   cuartiles  1.75  1.99  3.00  9.89
```

El coste crecía con la posición de la clave. Eso no es ruido, es la firma de una
causa concreta: los mansos salían por `[0,W)` y los salvajes por `[k,k+W)`, así
que los dos rebaños solo se pisaban en `[k,W)` y un salvaje que saliera por
encima de W no podía cruzarse jamás con el rastro de un manso —todos los saltos
van hacia delante—. Sale `2/√(1−k/W)`, que promediado da 4. Medido: 4,16.

La política 2 reparte a los mansos por `[0, W + W/8)` y a los salvajes por
`[0, W/8)`, de forma que el terreno de los salvajes **cabe entero** dentro del
de los mansos:

```
politica 2 shift 3   2.13   cuartiles  1.88  2.31  2.02  2.32
```

Plano, y 1,6–1,9 veces más barato. Que se aplane importa más que la media: es la
prueba de que la causa era la que se decía y no otra.

El banco corre esta prueba pequeña (28 bits, 120 tandas, ~10 s) con un techo:
si el coste de lo que trae `kg_setup` se pasa de 2,9, falla. Para comparar
variantes se lanza a mano con más tandas y `barrido=1`.
