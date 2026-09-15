# Banco de pruebas del firmador

Compila `build_and_sign_tx()` —el código que firma de verdad, tal cual está en
`hunter_jni.cpp`— como un binario del anfitrión, para poder ejecutarlo sin
Android y comprobar las transacciones que produce.

Existe porque los vectores de BIP143/BIP341 validan el *algoritmo*, no el
código que lo implementa. Las dos primeras cosas que encontró:

- `json_int(req,"amount")` devolvía el valor del primer UTXO en lugar del
  importe a enviar, porque `"utxos":[...,"amount":N]` se serializa antes que el
  `"amount"` de nivel superior y la búsqueda era un `find()` sobre toda la
  cadena.
- `derive_path()` derivaba **sólo el primer nivel** de la ruta: se firmaba con
  la clave de cuenta `m/84'` en vez de con la hoja `m/84'/0'/0'/0/0`, de modo
  que la firma nunca podía satisfacer el script.

Con cualquiera de los dos, ninguna transacción habría sido válida.

## Uso

Hace falta `g++`, `libcrypto` (OpenSSL) y una copia de secp256k1:

    git clone --depth=1 https://github.com/bitcoin-core/secp256k1.git
    SECP="-Isecp256k1 -Isecp256k1/include -Isecp256k1/src -DSECP256K1_BUILD \
      -DUSE_FIELD_5x52 -DUSE_SCALAR_4x64 -DUSE_NUM_NONE -DUSE_FIELD_INV_BUILTIN \
      -DUSE_SCALAR_INV_BUILTIN -DECMULT_WINDOW_SIZE=15 -DECMULT_GEN_PREC_BITS=4 \
      -DENABLE_MODULE_EXTRAKEYS -DENABLE_MODULE_SCHNORRSIG"
    cc -O1 -c $SECP secp256k1/src/secp256k1.c            -o secp.o
    cc -O1 -c $SECP secp256k1/src/precomputed_ecmult.c   -o pre1.o
    cc -O1 -c $SECP secp256k1/src/precomputed_ecmult_gen.c -o pre2.o
    ./rebuild.sh          # extrae el código real y enlaza -> ./signer
    python3 verify.py     # firma y comprueba que las firmas validan

`rebuild.sh` recorta de `hunter_jni.cpp` los envoltorios JNI y sustituye lo que
viene de Android por `shim.h`; el resto —derivación, sighash, firma,
serialización— es el mismo código que va en el APK.

`signer` lee la petición JSON por la entrada estándar, igual que la recibe
`buildAndSignTx`, y escribe la transacción en hexadecimal.

`verify.py` no comprueba contra otra copia del mismo código: reimplementa
BIP32, BIP143, BIP341, BIP340 y ECDSA en Python puro, baja hasta la aritmética
de puntos de secp256k1, y verifica que la firma que salió del C++ valida contra
la clave pública del scriptPubKey de la propia transacción.
