# Prueba de RIPEMD-160

Android no trae RIPEMD-160 en sus proveedores de `MessageDigest`, así que
`Ripemd160.kt` lo implementa a mano. Un hash escrito a mano que no se prueba es
exactamente el tipo de cosa que falla en silencio — y aquí falla de la peor
manera posible: diría que la clave pública de una dirección "no está publicada"
cuando sí lo está, o al revés.

```sh
javac Ripemd160Test.java && java R
```

Comprueba los vectores de la especificación (`""`, `"abc"`, `"message digest"`)
y, sobre todo, que `hash160` de la clave pública de k=1 da
`751e76e8199196d454941c45d1b3a323f1433bd6`, que es el que lleva dentro la
dirección `1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH`.

El fichero es Java y no Kotlin porque aquí no hay compilador de Kotlin. La
aritmética de `Int` es la misma en los dos —32 bits con signo y desbordamiento
silencioso—, así que el código se transcribe línea a línea.
