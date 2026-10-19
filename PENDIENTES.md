# Lo que sigue pendiente, sin hacer

Funciones de la cartera que no están implementadas y por qué. Cada una pide
una tanda propia, con pruebas de la transacción o de la derivación antes de
tocar la app.

- **Varios destinatarios en un envío.** Cambia la pantalla de envío y el
  firmador (`build_and_sign_tx`). Hace falta una prueba de la transacción
  resultante contra un nodo o un vector.

- **CPFP (acelerar un pago recibido).** Requiere una acción nueva en el
  historial y un cálculo de comisión por paquete (la del padre más la del hijo).

- **OP_RETURN.** Necesita soporte de salidas sin dirección en el firmador.

- **Crear o exportar PSBT.** Es un formato nuevo (BIP174) con su propio flujo
  de firma. Hoy la app sólo revisa y firma PSBT ajenos.

- **Selección de cuenta (cuenta 1, 2…).** Toca la derivación en todos los
  caminos de la cartera: direcciones, saldo, historial, envío, copias y
  restauración.

- **Passphrase BIP39.** Afecta a la semilla, a las copias de seguridad y a la
  restauración. Hay que diseñarlo con cuidado: una passphrase olvidada deja la
  cartera sin fondos recuperables.

- **Comisión de la pantalla principal con varias entradas.** Sigue usando una
  sola entrada. La confirmación final sí es exacta.

- **Cartera señuelo.** No se implementa: es un riesgo de seguridad.

- **Borrar la semilla de la memoria.** No es posible del todo en la JVM: los
  `String` son inmutables y el recolector puede copiarlos. Se limita a lo que
  sí se controla (copias de los arrays de bytes en el motor nativo).
