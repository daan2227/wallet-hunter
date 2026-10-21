# Pendientes de la cartera

## Implementado (sin probar en un teléfono)

- **Código alfanumérico.** El código de seis dígitos pasa a ser un texto de 8 a 24 caracteres con letras,
  números y al menos un símbolo (`PoliticaPin`, probada en la JVM); se rechazan «Password1!», «Abcd1234!»,
  años, secuencias y palabras corrientes. Los códigos nuevos derivan la clave con 600 000 vueltas de PBKDF2
  (los antiguos, 100 000); comprobar o fijar el código va en otro hilo. Quien tiene aún el de seis dígitos
  entra con él y se le ofrece cambiarlo (como mucho una vez al día).

- **Vista simple por defecto.** La cartera enseña lo corriente: crear o importar una
  seed, enviar (con las tres tarifas), recibir, historial, saldo, PIN, moneda, ver la
  seed y borrar. Lo demás está detrás de "Advanced options" (menú): varias salidas,
  OP_RETURN, PSBT, control de monedas, tarifa a mano, acelerar con hija (CPFP), CSV,
  xpub, cuentas, testnet, WIF y cartera vigilada, copia en baúl y restaurar de fichero,
  y la verificación con la red. No se ha borrado nada: sólo se esconde.

- **Varios destinatarios y OP_RETURN.** "Add another output" en Enviar. El
  firmador recibe "outputs"; el OP_RETURN se prueba en el escritorio
  (`tools/ec-harness/opreturn.cpp`). La transacción de varias salidas no se ha
  firmado todavía.
- **CPFP.** Un pago recibido sin confirmar se acelera con una hija
  ("Speed up with a child"). La tasa se prueba en la JVM (`CpfpTest`).
- **Comisión de la pantalla principal** con las monedas que usaría el envío.
- **Passphrase BIP39 (palabra 25).** Opcional al importar. Se guarda como
  "palabras|passphrase" y el motor la usa. Probado con los vectores de BIP39
  (`tools/ec-harness/frase.cpp`).
- **Selección de cuenta (m/…/N').** Menú "Account": crea una cartera nueva con
  las mismas palabras y otra cuenta. Se guarda como "palabras[|pass]#N".
- **Crear y exportar PSBT.** "Export as PSBT" en Enviar: la transacción sin
  firmar más el UTXO de cada entrada, para firmarla en otro aparato. Requiere
  una cartera con su clave: una vigilada todavía no puede armar el cambio.
  La PSBT sin firmar se prueba en la JVM (`PsbtTest`).

- **Verificación con la red (BIP157/158).** Menú "Verify with the network": el saldo
  comprobado con nodos de Bitcoin, sin explorador. Baja los filtros de bloque del rango
  elegido (2 semanas, 3 meses, 1 año o desde una altura) y sólo los bloques que tocan
  a la cartera; los nodos no saben qué direcciones son tuyas. Las cabeceras de filtro se
  piden a dos nodos y tienen que coincidir; si difieren se baja el bloque y se expulsa
  al que miente. Cada bloque se comprueba contra su cabecera (prueba de trabajo, Merkle)
  y contra su filtro. Probado sin red (`p2p`, `almacen`, `cadena`, `bloque`, `sincro`,
  `difusion` en el banco) y de punta a punta con sockets reales y JNI contra un nodo
  simulado (`tools/ec-harness/ligero_e2e.sh`, a mano). Límites:
  - Sólo mainnet (la regla de testnet de 20 minutos no está).
  - Pocos nodos públicos sirven filtros; si no hay ninguno, se puede dar un nodo propio
    (`blockfilterindex=1`, `peerblockfilters=1`).
  - Sólo ve lo recibido dentro del rango y las primeras 40 direcciones de cada tipo.
  - Si TODOS los nodos preguntados mienten igual, un filtro que omita un pago no se nota.
  - Un filtro de BIP158 gasta ~20 KB por bloque: un año son ~1 GB.
  - Sin mempool ni estimación de comisión (un cliente BIP157 no los tiene): la comisión
    sigue viniendo de la API HTTP y lo no confirmado no aparece.
- **Difusión por P2P.** Si la API y Electrum no responden, la transacción firmada se
  ofrece directamente a nodos de la red (`P2pBroadcast`), sólo mainnet. Se da por buena
  cuando un nodo la pide y se ve propagar, o a los 15 s de haberla entregado.

- **Pestaña Core (sin probar en un teléfono, binario aún sin compilar).** `bitcoind` de Bitcoin Core v32.0rc4 (candidata a versión, no la final: v32.0 aún no estaba etiquetada) como
  proceso hijo (libbitcoind.so, ejecutable desde el directorio de librerías nativas), podado, con RPC solo en
  127.0.0.1 y cookie en el almacén privado. Servicio en primer plano, Wi-Fi solo opcional, snapshot de UTXO
  (assumeutxo) desde fichero o por https, `loadtxoutset` y `pruneblockchain` desde la pantalla. El binario lo
  compila `core.yml` (NDK + CMake, libevent a mano) y lo publica en la release `core-android`; `build.yml` lo
  incluye si existe. Límites:
  - La receta de compilación para Android (Core v29 ya no trae `depends` de Android) no se ha ejecutado nunca: la primera ejecución puede fallar.
  - La fuente se baja por etiqueta de git, sin verificar la firma de los desarrolladores.
  - Core sólo acepta snapshots cuyo hash está fijado en su código (mainnet: bloques 840000, 880000, 910000, 935000 y 965000 en v32.0rc4; v31.1 llega a 935000 y v29.0 a 880000).
  - Exige Android 9+ (API 28) y arm64; ocupa ~10 GB al final y varios días de descarga la primera vez sin snapshot.
  - Android 15 corta los servicios dataSync a las ~6 h: hay que volver a pulsar Start.
  - La cartera de la app todavía NO usa este nodo (saldo, UTXO, difusión y comisión siguen con el cliente ligero
    y las API); integrarlo es un trabajo aparte.

## Conocido tras la revisión total (no corregido)

- **Baúl y WIF atados al código (sin probar en un teléfono).** Con código puesto, el baúl y la lista de WIF van
  en dos capas: la del Keystore y, por fuera, una clave de datos aleatoria (DEK) sellada con la clave del código
  (PBKDF2). Sin el código no se abren, ni con root. Con la sesión cerrada, los hallazgos nuevos del motor van a
  un buzón cifrado para una clave pública (ECIES P-256, `BuzonCifrado`) y entran al baúl al desbloquear. Los
  baúles antiguos se migran al desbloquear, verificando antes de borrar la copia vieja. Límites: root con la app
  en marcha y la sesión abierta lee la clave de la memoria; un código débil se adivina con la base copiada
  (de ahí 8–24 caracteres y 600 000 vueltas); sin código puesto, el baúl sigue sólo con el Keystore.
- **Motor:** carreras muy raras al arrancar/parar los hilos de Kangaroo (los de fuerza bruta ya están
  cubiertas); `kangarooStop` cuando la pantalla halla la clave sigue en el hilo principal.
- **Secretos en memoria nativa:** las cadenas Java con claves (la copia que hace JNI de la frase/clave
  al leer un `jstring`) no se pueden limpiar del todo; el resto de rutas ya borra sus intermedios.

## Sin implementar

- **Cartera señuelo.** No se implementa: una cartera oculta que se abre con un
  PIN de coacción engaña a quien la usa y abre superficie de ataque. Para
  ocultar fondos, la passphrase (arriba) es la herramienta.

- **Borrar la semilla de la memoria.** No es posible del todo en la JVM: los
  `String` son inmutables y el recolector puede copiarlos. El motor nativo
  limpia sus copias (`OPENSSL_cleanse`); la parte de Kotlin no.

- **Exportar PSBT de una cartera vigilada.** Hace falta derivar el cambio desde
  la xpub, que hoy sólo sirve para importar.
