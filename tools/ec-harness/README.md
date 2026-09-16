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
