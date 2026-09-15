# Pasar de firma de debug a firma de release

## Qué está pasando ahora

El APK **ya se compila como release**: el CI ejecuta `./gradlew assembleRelease`
y Gradle marca `debuggable=false`. Eso no es lo que falla.

Lo que falla es **la firma**. El APK va firmado con `debug.keystore`, cuya
contraseña (`android`) es pública y la misma en todas las instalaciones de
Android Studio del mundo. Consecuencias:

- Android lo trata como app de desarrollo: avisos al instalar, Play Protect
  puede bloquearlo, y algunos fabricantes lo impiden directamente.
- Cualquiera puede firmar un APK que el sistema aceptaría como **actualización
  legítima** de esta app, porque la clave no es secreta.
- Google Play lo rechaza sin excepción.

## 1. Generar tu keystore

En tu máquina, **fuera del repositorio**:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias wallethunter \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

Te pedirá una contraseña y unos datos identificativos. Apunta:

- Contraseña del almacén (`storePassword`)
- Alias (`wallethunter` si usas el comando tal cual)
- Contraseña de la clave (`keyPassword`; con PKCS12 suele ser la misma)

> **Guárdalo como guardarías una seed.** Si lo pierdes no podrás volver a
> firmar actualizaciones que el sistema reconozca como la misma app: los
> usuarios tendrían que desinstalar y reinstalar, perdiendo los datos locales
> —incluidas las wallets cifradas—. Haz al menos una copia fuera de este
> ordenador.

## 2. Codificarlo en base64

GitHub Actions no admite ficheros binarios como secret:

```bash
base64 -w0 release.keystore > release.keystore.b64   # Linux
base64 -i release.keystore -o release.keystore.b64   # macOS
```

## 3. Crear los cuatro secrets

En GitHub: **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Valor |
|---|---|
| `RELEASE_KEYSTORE_B64` | contenido de `release.keystore.b64` |
| `RELEASE_STORE_PASSWORD` | contraseña del almacén |
| `RELEASE_KEY_ALIAS` | `wallethunter` |
| `RELEASE_KEY_PASSWORD` | contraseña de la clave |

## 4. Comprobar que funcionó

Lanza el workflow y mira los logs. Si aparece este aviso, los secrets no están
llegando y el APK sigue firmado con la clave de debug:

```
RELEASE_KEYSTORE_B64 not set — signing with the debug key.
```

Sobre el APK descargado, verifica la firma:

```bash
keytool -printcert -jarfile app-release.apk
```

El `CN` debe corresponder a los datos que introdujiste. Si dice
`CN=Android Debug`, sigue con la clave de debug.

## Nota sobre el primer cambio de firma

Cambiar la clave de firma **rompe la actualización** de cualquier instalación
previa: Android no deja actualizar una app con una clave distinta. Quien tenga
la versión actual instalada tendrá que desinstalarla antes.

Si tienes wallets guardadas en el dispositivo, **exporta un backup cifrado
antes de desinstalar** — los datos de la app se borran con ella.

## Ofuscación (opcional, no aplicada)

Este build no lleva `minifyEnabled`. Activarlo reduce tamaño y dificulta la
ingeniería inversa, pero necesita reglas ProGuard que conserven lo que R8 no
puede ver: las clases con métodos `native` —el símbolo JNI codifica el nombre
completo de clase y método— y `RecoveryEngine`, cuyo `onProgress` resuelve el
C++ por nombre con `GetMethodID`.

Los fallos de R8 aparecen **en ejecución, no al compilar**, así que un CI en
verde no demuestra que el APK ofuscado funcione. Si se activa, hay que probar
el flujo de recovery en un dispositivo real antes de distribuirlo.
