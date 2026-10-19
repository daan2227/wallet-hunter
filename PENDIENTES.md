# Pendientes de la cartera

## Implementado (sin probar en un teléfono)

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

## Sin implementar

- **Cartera señuelo.** No se implementa: una cartera oculta que se abre con un
  PIN de coacción engaña a quien la usa y abre superficie de ataque. Para
  ocultar fondos, la passphrase (arriba) es la herramienta.

- **Borrar la semilla de la memoria.** No es posible del todo en la JVM: los
  `String` son inmutables y el recolector puede copiarlos. El motor nativo
  limpia sus copias (`OPENSSL_cleanse`); la parte de Kotlin no.

- **Exportar PSBT de una cartera vigilada.** Hace falta derivar el cambio desde
  la xpub, que hoy sólo sirve para importar.
