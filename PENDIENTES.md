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
  - La cartera usa el nodo sólo si se activa «Use my node to send»: difusión (testmempoolaccept + sendrawtransaction),
    comisión (estimatesmartfee, requiere «Blocks + mempool»), altura, y comprobación de cada moneda con gettxout antes
    de firmar. «Verify with my node» escanea el conjunto de UTXO (scantxoutset) y lista las monedas. Saldos de la
    pantalla principal, historial y UTXO de Enviar siguen viniendo de los servidores: un escaneo por dirección
    tardaría horas y un nodo podado no tiene índice de transacciones.
  - Monedero de solo lectura en el nodo (historial): descartado. Con snapshot y poda no se puede reescanear lo
    anterior, así que sólo vería lo posterior al alta y daría saldos engañosos.
  - Tor: «Through Tor» y «Tor only» necesitan una app de Tor en marcha (Orbot, 127.0.0.1:9050); la difusión privada
    de Core es experimental y, sin Tor, la transacción podría no salir del teléfono.
  - Filtros BIP157 locales (+~10 GB): se sirven sólo en 127.0.0.1; el índice se construye junto a la comprobación de
    fondo y no se ha probado que funcione bien con poda y snapshot.
  - Dataset del Scanner desde el nodo: probado con un snapshot sintético, no con uno real de ~10 GB.
  - Crear un snapshot .dat (pestaña Core): `dumptxoutset` (ahora o con rollback a una altura) y deja el fichero como
    mainnet-<altura>-utxos.dat con una ficha .txt (bloque base, monedas, hash del conjunto, si Core lo acepta). El avance
    se sigue por el tamaño del fichero temporal y el tiempo restante es aproximado (usa el tamaño del snapshot cargado).
    No se puede cancelar. Un nodo podado suele rechazar el rollback. Sin probar en un teléfono.
  - Formato de la clave pública en el CSV: tal cual, comprimida (02/03), sin comprimir (04) o x-only. Las Taproot vienen
    como x-only (32 bytes, sin prefijo; por BIP340 su Y es par, así que valen 02+x). Al convertir se comprueba que la
    clave sea un punto de la curva; una inválida sale vacía.
  - Instantánea de UTXO en CSV (pestaña Core): todo el conjunto de la cadena con las columnas que se elijan (dirección, utxo,
    txid, vout, fondo en BTC o sat, tipo, clave pública, hash, script, altura, fecha, coinbase, confirmaciones; por
    defecto las seis de siempre), filtros por cada uno de esos campos (importe mínimo y máximo, tipos, clave pública con/sin y prefijo, texto de la
    dirección, txid, vout, hash, script, altura, fecha, minadas o no, confirmaciones), progreso y tiempo estimado. La clave pública sólo
    sale donde la cadena la muestra sin gastar (P2PK y Taproot). La fecha se calcula por altura con la hora de un
    bloque de cada 100 (error de horas). Con «All» son unos 190 M de filas (~25 GB), cálculo mío sin medir. Probado
    con un snapshot sintético, no con uno real.
  - Las pruebas de CoreConfig y del conversor corren en la JVM; nada de esto se ha ejecutado en un teléfono.

- **Desbloqueo con huella (opcional, sin probar en un teléfono).** Ajuste en Perfil → Fingerprint, apagado por
  defecto; encenderlo pide el código y una huella. La clave que sale del código se sella con una clave del Keystore
  que exige una huella válida por uso (biometría fuerte; en Android 11+ `setUserAuthenticationParameters(0, …)`).
  Sólo se ofrece al abrir la app y al abrir las carteras. El baúl, enviar (también acelerar con RBF y el barrido de
  Tools), ver la seed, cambiar el código, copias, restaurar, mostrar saldos, el resumen y Perfil/Tools/Debug piden
  siempre el código. Con la huella activada, enviar exige teclear el código en cada envío, sea cual sea el importe.
  Costes: con ella activa, quien pueda usar tu huella abre la app y ve las carteras sin el código, pero no abre el
  baúl ni mueve fondos sin teclearlo. La sesión abierta con huella tiene en memoria la clave de datos del baúl
  (como con el código): la pantalla del baúl pide el código, pero no es una barrera criptográfica nueva. Cambiar el código
  o las huellas registradas en el móvil la apaga. Tras 5 fallos de código, la huella queda vetada hasta un código bueno.

- **Taproot audit (sin probar en un teléfono).** Pantalla aparte desde Weak: fuerza bruta con el tweak de
  BIP341 contra direcciones Taproot PROPIAS que se cargan desde un fichero (una por línea: bc1p… o una clave
  x-only de 64 hex, sin tope de tamaño). Ni Kangaroo ni BSGS sirven con una clave tweakeada: el exponente
  queda disperso por todo el cuerpo aunque la clave interna esté en un rango pequeño (ver el comentario de
  `taproot_audit.h`), así que no hay atajo. Selector de rango (inicio y ancho hasta 2^40) y progreso con
  ritmo y ETA medidos en vivo. Los hallazgos van al baúl como los demás (source `taproot-audit`). Probado el
  motor en el banco de pruebas (`tools/ec-harness/taproot_audit.cpp`), no en un teléfono. Un rango de 2^40
  son días de cómputo incluso con varios núcleos: el selector lo permite, no lo recomienda.

  Añadido después: **reanudar** una auditoría parada (aproximado — guarda hasta dónde llegó y retoma desde
  ahí la próxima vez con el MISMO inicio y ancho; un puñado de claves justo en el punto de corte puede quedar
  repetido o, más raro, sin probar — insignificante frente al rango entero), selector de **núcleos a usar**,
  **historial** de los rangos ya barridos (los últimos 20, en el teléfono), **aviso del sistema al terminar**
  (se dispara aunque la pantalla esté cerrada, porque el propio motor avisa) y recuerda el último inicio,
  ancho, núcleos y lo pegado la próxima vez que se abre la pantalla.

  Descartado: probar las dos paridades de la clave interna. No hace falta — BIP341 toma siempre la Y par al
  reconstruir la clave interna (lift_x), así que la salida tweakeada depende sólo de la x de la clave interna,
  no de qué raíz cuadrada diera la privada candidata: ya está bien como está.

  Añadido después: **motor más rápido** (`escanear_bloque_rapido()` en `taproot_audit.h`, usado ya por el
  motor nativo). Antes se calculaba P = d·G con una multiplicación de curva completa por cada d candidata
  (`secp256k1_ec_pubkey_create`); ahora se calcula una sola vez por bloque de 256 candidatas y de ahí en
  adelante sólo se SUMA +G (reutilizando `jac_batch.h`, lo mismo que ya usa el escáner de claves al azar),
  normalizando por lotes con una sola inversión en vez de una por candidata. Medido en el escritorio contra
  secp256k1 (400 000 candidatas, sin ningún hallazgo: el peor caso, el que más cuenta): **1,57× más rápido**
  (21 200/s → 33 400/s) — menos de lo que parecía al principio porque el propio tweak de BIP341
  (`secp256k1_ec_pubkey_tweak_add`) hace su PROPIA multiplicación de curva completa por cada candidata, para
  sumar t·G, donde t es un hash distinto cada vez y no se puede precalcular ni encadenar: sólo se pudo quitar
  una de las dos multiplicaciones por candidata, no las dos. Si el centímetro que falta importa, la otra
  mitad exigiría reimplementar el tweak a mano con `jac_batch.h` en vez de la función genérica de secp256k1 —
  más trabajo y más riesgo de un error sutil, no hecho todavía. Caso raro (si el rango cae justo en d=1 o
  d=orden−1, donde sumar +G no vale: hay que doblar) cubierto cayendo a la vía de siempre para ese bloque;
  probado en `tools/ec-harness/taproot_audit.cpp` contra la vía lenta, candidata por candidata, y con ese
  caso raro forzado a propósito. En el teléfono real, el multiplicador medido ahí puede salir distinto (el
  banco corre con qemu/x86, el móvil es ARM64 con su propio camino en `fe_mul`/`fe_sqr`) — no probado en un
  teléfono.

- **Quitar el código, para "entornos seguros" (sin probar en un teléfono).** En Perfil, junto a "Change
  PIN": "Remove PIN", sólo visible con un código puesto. Pide primero un aviso explícito (qué deja de
  protegerse) y luego el propio código vigente — no basta con tener el teléfono ya desbloqueado en la mano,
  hace falta demostrar que se sabe. A partir de ahí la app abre directamente, sin pedir nada, en cada arranque
  y tras cada bloqueo (pantalla apagada, segundos fuera de la app): la única barrera que queda es el bloqueo
  de pantalla del propio teléfono. La huella de la app se apaga a la vez, porque sólo tenía sentido como atajo
  al código.

  Por dentro (`WalletManager.quitarPin`/`entrarSinPin`): las semillas, la lista de WIF y la clave de datos del
  baúl (DEK) que estaban selladas con la clave del código se vuelven a sellar, en el mismo guardado, con una
  clave de puro Keystore (hardware, sin huella ni autenticación exigida — el mismo mecanismo que ya protege el
  nombre de perfil) en vez de quedarse sin forma de abrirse. `savePin()` ya sabía re-envolver "desde la clave
  vigente"; el único cambio ahí es que esa clave vigente ahora puede venir también de aquí, no sólo de un PIN
  anterior — así que volver a poner un código más tarde (el mismo botón, que pasa a decir "Set PIN") funciona
  sin tocar nada más.

  Riesgo que no se puede quitar, sólo trasladar: con el código quitado, cualquiera que desbloquee el teléfono
  —con su propio PIN, patrón o huella del sistema— puede abrir la cartera, el baúl y enviar fondos sin teclear
  nada más. Es exactamente lo que dice el aviso antes de confirmar. Pensado para un teléfono dedicado que se
  sabe que queda en un sitio de confianza, no para el día a día.

## Auditoría general (2026-10-25)

Seis repasos en paralelo, cada uno sobre un área: núcleo criptográfico nativo, baúl/PIN/huella/backup,
integración de Bitcoin Core, cliente ligero/Electrum/difusión/precios, firma de transacciones/PSBT/envío, y
superficie expuesta de la app (manifest, pantallas, logs, portapapeles). Sin fallos críticos en ninguna de
las seis. Un hallazgo alto, corregido:

- **`DebugActivity.kt` enseñaba claves privadas en texto plano, sin protección de pantalla.** El log del
  motor que se ve en vivo ahí puede llevar una clave entera (la de un puzzle resuelto, por ejemplo) antes de
  tacharla — el botón "Copy" ya la tachaba desde hace tiempo, pero lo que se veía en el `TextView` en vivo,
  no. Y a diferencia del baúl, del envío o de la auditoría Taproot, esta pantalla no ponía `FLAG_SECURE`: una
  captura, una grabación de pantalla o la miniatura de "Recientes" podían enseñarla igual. Corregido:
  `PantallaSegura.aplicar()` en el `onCreate` (fijo, no opcional) y `Secretos.tachar()` aplicado también al
  texto que se pinta en pantalla, no sólo al que se copia.

Hallazgos menores, dejados como están (documentados, no fallos de implementación):

- `WalletManager.kt`: un comentario dice que no hay forma de probar el código offline sin conexión; en
  realidad el verificador cifrado con la clave del PIN sí permite probarlo offline si alguien saca los
  SharedPreferences (ya mitigado por la política de 8–24 caracteres y 600 000 vueltas de `PoliticaPin.kt`,
  que si lo dice bien) — falta corregir el comentario para que no afirme algo que no es cierto por sí solo.
- Un código antiguo de 6 dígitos (100 000 combinaciones) se sigue aceptando indefinidamente tras migrar a uno
  nuevo; sólo avisa una vez al usarlo, sin forzar el cambio. Decisión de UX consciente, no un descuido.
- `Psbt.kt::revisar()`: la protección contra un importe de entrada no verificado (tipo CVE-2020-14199) sólo
  cubre P2PKH explícitamente; otras entradas "legacy" sin compromiso de importe (P2SH simple, P2PK) no la
  llevan. Impacto limitado porque `Psbt.sign()` de todas formas sólo firma P2WPKH/P2PKH — no se pierden
  fondos propios por esto, pero el total/comisión que se enseña en pantalla al revisar una PSBT ajena
  (coordinador multisig, compañero de coinjoin) podría ser engañoso.
- Confianza en un único servidor (`ChainApi`/`ElectrumClient`/`BalanceLookup`, el primero que conteste, sin
  cruzar fuentes) para saldo/UTXO/historial cuando no hay nodo propio: **revisado y no es una vía de pérdida
  de fondos** — para direcciones segwit/Taproot el propio sighash incluye el importe, así que un servidor
  mentiroso sólo logra que el envío se rechace en la red, no que se pierda nada; el único caso donde SÍ
  importaría (legacy P2PKH, cuya firma no compromete el importe) ya está bloqueado explícitamente en
  `WalletActivity.kt` (variable `entradaLegacy`) si la fuente no es de fiar y el nodo propio no lo confirma.
- `bsgs.h`: el mismo caso raro ya aceptado en `taproot_audit.h` (el acumulador cae justo en ±G) existe
  también aquí sin cubrir explícitamente, pero sólo sería alcanzable con una tabla del tamaño del orden de la
  curva (~2^256) — inalcanzable con los límites reales de la app (hasta 2^40).
- `CoreNodo.cola()` sólo lee los últimos 512 KB de `debug.log`: en una carga de snapshot muy larga con mucho
  ruido de por medio, la línea de progreso podría quedar fuera de esa ventana y la barra mostraría "sin
  datos" en vez de un número — subestima, nunca sobreestima, así que no engaña con un progreso falso.

### Errores antes silenciosos, ahora registrados

A raíz de la pregunta "¿se ven todos los errores en Debug?": no, de 415 bloques `catch` en todo el Kotlin de
la app, sólo 3 llamaban a `Log.*`/`RegistroFallos.anotar` — los otros 412 se tragaban el error sin dejar
ningún rastro, ni en pantalla ni en fichero. Es un patrón deliberado (que un fallo puntual de red/fichero no
tumbe la app), pero significaba que Debug no enseñaba de verdad "todos" los errores.

Añadido `RegistroFallos.anotar(donde: String, e: Throwable)`: igual que las versiones con `Context` que ya
existían, pero usando un `Context` de aplicación guardado internamente (se rellena en `instalar()`, que corre
al abrir cualquier pantalla), para no tener que colar un `Context` por cada función de una clase que no lo
recibía. El texto pasa igual por `Secretos.tachar()` antes de escribirse.

Revisadas las cuatro áreas que de verdad importan (seis repasos en paralelo, el mismo reparto que la
auditoría de arriba, cada uno decidiendo caso por caso qué merecía instrumentarse) e instrumentados **79**
catches silenciosos:
- **Red** (8): `ChainApi`, `ElectrumClient`, `LightClient`, `P2pBroadcast`, `VerificacionLigera`.
- **Bitcoin Core** (24): `CoreNodo`, `CoreBackend`, `CoreSnapshot`, `PestanaCore`, `HerramientasNodo`,
  `VerificacionNodo` — incluido `CoreBackend.verificarMonedas`, la comprobación con el nodo propio antes de
  enviar, que antes caía a "no se pudo comprobar" sin dejar ningún rastro de por qué el nodo dejó de
  contestar a mitad.
- **Firma y envío** (~12): `FirmaTx`, `Psbt`, `UmbralEnvio`, varios en `WalletActivity` (selección de
  monedas, derivar dirección nueva, guardar en el baúl, exportar backup).
- **Baúl, PIN, huella y backup** (~35): `MatchVault` (migración, buzón cifrado, y sobre todo
  `guardarRecuperada` — una seed recuperada puede haber costado horas de cómputo, que se perdiera sin rastro
  habría sido lo peor posible), `WalletManager`, `HuellaDesbloqueo`, `VaultActivity`.

Dejados exactamente igual, a propósito: cualquier catch donde "falló" es el resultado NORMAL de la operación
(un PIN o una contraseña de backup incorrectos — indistinguibles de un error real por diseño, para no dar
pistas a quien intente adivinarlos; un dato pegado por el usuario que no tiene el formato esperado), bucles de
sondeo de la UI cada 1–3 segundos (instrumentar ahí habría inundado `crash_log.txt` con el mismo fallo
repetido durante toda una caída de red/nodo, sin ninguna bandera barata de "ya avisé" para evitarlo), y
clases puras sin `Context` con sus propios tests JVM (`SeedEnvelope`, `Shamir256`, `BuzonCifrado` — su fallo
ya queda capturado en el punto donde `MatchVault` las llama).

Añadido después, a raíz de "¿podemos mejorar el debug, no guarda un histórico con fechas?": tenía razón en
parte — las 79 instrumentaciones de arriba (y las que ya había antes en `MainActivity`/`PestanaPuzzle`) se
escribían SIN fecha; sólo el manejador de cierres la ponía. Corregido centralizando la fecha en
`RegistroFallos.anotar(ctx, texto)` (la función por la que pasan todas las demás), así que ahora toda entrada
a `crash_log.txt` lleva `[yyyy-MM-dd HH:mm:ss]` sin que cada llamante tenga que acordarse; de paso, el
`stamp` del log del motor (`engine_log.txt`) pasó de sólo-hora a fecha completa, por el mismo motivo (un
fichero que dura semanas con sólo "14:32:10" no dice de qué día es). Y el propio "histórico" ya existía pero
estaba escondido: "Clear" archiva con fecha en el nombre y guarda las últimas 3 copias de cada fichero desde
hace tiempo, sólo que nada en la app lo enseñaba — hacía falta un explorador de ficheros o ADB para verlo.
Añadido un botón "History" en Debug que lista esos archivados (`Diagnostico.archivados()`) con fecha legible
y tamaño, y al tocar uno enseña su contenido (tachado) en el mismo visor de siempre. De paso, la vista en
vivo de `crash_log.txt` pasó de 800 a 6000 caracteres: con las nuevas ~80 fuentes de error escribiendo ahí,
800 se quedaban en menos de una traza completa.

No se ha compilado de verdad (sin NDK/SDK de Android en este entorno; sólo contado llaves por fichero a mano
y dejado que lo verifique la CI), ni probado en un teléfono.

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
