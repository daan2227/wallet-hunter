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

### Lo que se probó y NO sirvió

Con potencias de dos, la mitad de los saltos son muchísimo más cortos que la
media: en el #140 la media es 2⁶⁹ y la mitad de los saltos mueven menos de 2³⁷.
Eso no es lo que supone el análisis estándar, así que parecía un sitio donde
podía haber algo. Medido con longitudes al azar de la misma media:

```
potencias de dos   2.18 +- 0.06
al azar            2.20 +- 0.05
```

Lo mismo. Y tiene explicación: una colisión exige que dos canguros caigan en el
**mismo punto**, no cerca, así que lo largo o corto que sea cada salto no cambia
la probabilidad. Lo único que importa es por dónde se sueltan.

El interruptor (`kg_politica_saltos`) se queda puesto: si algún cambio futuro
hiciera que la tabla de saltos sí importara, aquí se vería.

La otra que no sirvió está en el propio código: `politica_salida = 1` (los dos
rebaños juntos) mide **40·√W** con 256 canguros, veinte veces peor. Los mansos
amontonados en W/2 obligan a un salvaje en k a recorrer |k − W/2| para llegar a
ellos, y eso no lo reparte tener más canguros: todos caminan a la vez la misma
distancia. Se queda medida porque un resultado negativo también es un banco: si
algún día deja de ser mala, es que algo se ha roto.

## El canguro está donde dice su distancia

`saltos.cpp` comprueba la única invariante de la que depende todo lo demás: un
manso está en `dist·G` y un salvaje en `P' + dist·G`. Si eso deja de cumplirse,
el canguro sigue andando y sigue llenando la tabla, pero ninguno de sus puntos
puede cerrar una colisión.

Y **no se nota**. `kg_resolver` comprueba la clave contra el objetivo antes de
cantarla, así que no sale una clave falsa: sale que no hay clave. La tabla crece,
la pantalla cuenta millones de claves por segundo, los móviles calientan. Igual
que si todo fuera bien.

El fallo que trajo esta prueba: la tabla de saltos guardaba la longitud en un
`uint64_t` y el salto *i* vale 2ⁱ, así que a partir de i=63 se guardaba **cero**
—el punto se movía y la distancia no—. Con `KG_MAX_JUMPS` en 64, eso pasaba en
todos los rangos de más de 118 bits: **#140, #145 y #155, los únicos para los que
se usa Kangaroo**. Medido entonces en el #140: 15 de 145 mansos en su sitio.

```sh
g++ -O2 -o saltos saltos.cpp -lpthread && ./saltos
```

Va a 40 bits **como control** y a 119, 139 y 154, que es donde fallaba. El control
es lo que distingue «el motor está roto» de «la prueba está rota». Comprueba
además que en la tabla hay distancias de más de 64 bits: si no las hubiera, no
estaría mirando el caso que falla y diría que todo bien igualmente.

Y cubre el mismo tipo de fallo por los otros dos caminos: que una distancia
grande sobreviva a guardar/recuperar y al ida y vuelta por red, y que la resta
que da la clave (`kg_resolver`) funcione con distancias de verdad y no sólo con
las de un rango de juguete.

## Correrlo como lo corre CI

Desde `cc9eda9` el banco se ejecuta en cada compilación, **antes** de instalar el
NDK y cruzar Go: si el motor está roto, falla en un minuto en vez de después de
montar el APK entero.

El primer commit que lo activó se puso rojo al instante, y por algo real:
`vectores.cpp` llevaba metida la ruta absoluta de una máquina concreta en un
`#include`. Aquí esa ruta existía, así que la prueba salía verde sin probar nada
fuera de un único ordenador del mundo.

Para verlo antes de empujar, y sin gastar minutos de CI:

```sh
tools/desde-cero.sh
```

Exporta **sólo lo que sigue git** a un directorio temporal y corre el banco desde
ahí. Correr desde otro sitio pilla las rutas absolutas; exportar sólo lo seguido
pilla los ficheros que hacen falta y nadie subió.

## El mapa de negación

En esta curva `-P = (x, -y)`: un punto y su opuesto comparten la x, así que son
el mismo a efectos de la tabla de distinguidos. Si además el **camino** se queda
siempre con el mismo representante de la pareja `{P,-P}`, el espacio a recorrer
se parte por la mitad y el coste, que va con la raíz, baja en √2 = 1,41.

Hay que trasladar el objetivo al **centro** del intervalo para que sirva de algo:
la incógnita relativa está en `[0,W)`, toda positiva, y identificar `d` con `-d`
no dobla nada si el opuesto cae donde no pasa nadie. Centrando, la incógnita
queda en `[-W/2, W/2]` y su valor absoluto en `[0, W/2]`.

```
sin negación, tabla de ~20 saltos    2.27
con negación, tabla de ~20 saltos    1.99     1.14 veces
sin negación, tabla de 32 saltos     2.33
con negación, tabla de 32 saltos     1.69     1.38 veces
```

Las dos primeras líneas parecen decir que sólo vale un 14 %, y no es verdad: lo
que frena ahí es el tamaño de la tabla de saltos del banco. En el #140 la tabla
tiene 75 entradas, más del doble de las 32 con las que ya se mide 1,38.

### Los ciclos estériles, que es donde está la dificultad

Desde `P` se salta a `Q = P + S_h`; si al canonizar hay que darle la vuelta, se
sigue en `-Q`, que tiene la **misma x** que `Q` y por tanto el mismo salto. Si
ese salto coincide con el anterior, `-Q + S_h = -P`, que al canonizar vuelve a
ser `P`: el canguro rebota entre dos puntos para siempre. Pasa una vez cada
`2·njumps` pasos, así que no es raro.

Tres cosas hicieron falta, y las tres se descubrieron midiendo:

1. **El salto de escape no puede estar en la tabla.** Con `(h+1) % njumps` el
   escape volvía al mismo ciclo con probabilidad `1/njumps`, y como es
   determinista, al fallar una vez fallaba siempre. Medido entonces: **27.435
   saltos por punto guardado**, cuando tocaban 64.
2. **Una ventana, no sólo el paso anterior.** `ciclos.cpp` mide el reparto de
   longitudes: a tamaño real, 3890 de 2, 84 de 3, 22 de 4, 4 de 5, ninguno mayor.
   La ventana de 16 sobra, y esa prueba salta si algún día deja de sobrar.
3. **Una red de seguridad.** La ventana caza los ciclos medidos, no puede
   prometer que los caza todos, y un canguro atrapado deja `kg_run` sin salida:
   el motor se cuelga. Le pasó a la prueba `semilla`, que va en 22 bits, donde la
   tabla de saltos es corta y los ciclos largos más frecuentes. Ahora un canguro
   que lleve 20 veces lo esperado sin dar un distinguido se vuelve a soltar —la
   espera es geométrica, así que pasarse de 20 veces la media tiene probabilidad
   e⁻²⁰— y el contador `rescatados` lo deja ver.

4. **Escapar del ciclo no basta: hay que ver si el escape vuelve.** Medido con
   `negacion.cpp`, la **mitad** de los escapes salían del mismo punto que el
   anterior. Al escapar de `P` el canguro olvida que estuvo en `P` —la ventana
   se borra— así que cuando el camino nuevo lo devuelve allí no hay forma de
   verlo: vuelve a caer en el mismo ciclo, vuelve a escapar por el mismo sitio
   —el escape TIENE que ser determinista— y otra vez, para siempre. Andando,
   contando saltos y sin dar un distinguido en su vida.

El escape tiene que depender **sólo del punto**, nunca de por dónde se vino: si
dos canguros que se han juntado escapan distinto, se separan y la colisión que
ya tenían se pierde sin dejar rastro. Por eso se escapa siempre desde el menor de
los puntos del ciclo.

### El mapa de negación sigue apagado, y un intento de arreglo que se retiró

`kg_negacion = 0`. Lo de arriba está diagnosticado y **no** arreglado.

Lo primero que hubo que tirar fue el enunciado. "Se rompe a partir de dbits 13"
era falso: lo que decide no es `dbits` sino los **pasos por canguro**, y `dbits`
sólo cambia cuánto dura la corrida antes de agotar el presupuesto. Por eso las
investigaciones anteriores buscaron donde no era.

Lo que se sabe de la trampa, todo medido con `negacion.cpp`:

- **Los canguros están atrapados de verdad, no lentos.** Bajando la red de
  seguridad de `20·2^dbits` a `3·2^dbits` (`-DKG_FACTOR_SIN_DP=3ULL`), `dbits`
  12 pasa de 0/3 a 3/3 con 20 rescates. Por encima de 13 la red no llega a
  saltar nunca, porque su umbral es mayor que lo que un canguro llega a andar.
- **Depende de cuántos escapes hay entre dos distinguidos.** Con 44 bits y
  `dbits` 8 —unos 5 escapes por distinguido— el rendimiento es 0,92 de lo
  esperado, sano. Con 28 bits y `dbits` 12 —unos 120— es 0,02.

**El intento que se retiró.** Conservar la ventana al escapar, más varios saltos
de escape elegidos por la longitud del ciclo. Bajaba los escapes repetidos del
50 % al 0,6 % y hacía que `dbits` 12 resolviera 3/3 a 4,65·raíz(W) contra 8,99
sin negación. Pero dejaba `constante` **sin terminar** en la variante "negación,
SIN soltar muertos": con la ventana sin borrar, la detección puede casar con un
punto de ANTES del escape —es un regreso de verdad, pero esas casillas no están
todas en un mismo ciclo—, así que el mínimo de ellas puede ser un punto al que el
canguro ya no vuelve, `esc_act` se queda a 1 para siempre y el escape no se
dispara nunca. Una trampa cambiada por otra.

Restaurar sólo el borrado de la ventana **tampoco** bastó: seguía sin terminar.
O sea que la causa del cuelgue no está aislada, y por eso se revirtió el cambio
entero del motor y se quedaron sólo los contadores (`escapes`, `escapes_repe`) y
los dos macros que el banco varía con `-D`.

Para arreglarlo de verdad hace falta separar dos cosas que hoy son la misma: la
**ventana de detección**, que tiene que contener sólo puntos posteriores al
último escape, y la **memoria de por dónde se escapó**, que tiene que sobrevivir
al escape. Eso está sin hacer.
