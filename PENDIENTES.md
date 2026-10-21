# Pendientes de la cartera

## Implementado (sin probar en un teléfono)

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

## Sin implementar

- **Cartera señuelo.** No se implementa: una cartera oculta que se abre con un
  PIN de coacción engaña a quien la usa y abre superficie de ataque. Para
  ocultar fondos, la passphrase (arriba) es la herramienta.

- **Borrar la semilla de la memoria.** No es posible del todo en la JVM: los
  `String` son inmutables y el recolector puede copiarlos. El motor nativo
  limpia sus copias (`OPENSSL_cleanse`); la parte de Kotlin no.

- **Exportar PSBT de una cartera vigilada.** Hace falta derivar el cambio desde
  la xpub, que hoy sólo sirve para importar.
