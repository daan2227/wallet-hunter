# Firma de release

El APK se firma actualmente con **la clave de debug**, cuya contraseña (`android`)
es pública y conocida. Eso significa dos cosas:

1. Google Play **rechaza** los APK firmados con la clave de debug.
2. Cualquiera puede firmar un APK que el sistema aceptaría como actualización
   legítima de esta app.

Para publicar hace falta un keystore propio. El build ya está preparado: en
cuanto existan los secrets, el workflow los usa automáticamente; mientras no
existan, cae al keystore de debug y avisa en los logs.

## 1. Generar el keystore

Ejecútalo en tu máquina, **no** en el repositorio:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias btcseedrecovery \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

Te pedirá una contraseña y algunos datos identificativos. Apunta:

- La contraseña del almacén (`storePassword`)
- El alias (`btcseedrecovery` si usas el comando tal cual)
- La contraseña de la clave (`keyPassword`; con PKCS12 suele ser la misma)

> **Guarda este fichero y sus contraseñas con el mismo cuidado que una seed.**
> Si los pierdes no podrás volver a publicar actualizaciones de la app: Google
> Play identifica la app por su clave de firma y no hay forma de recuperarla.
> Haz al menos una copia fuera del ordenador de trabajo.

## 2. Convertirlo a base64

GitHub Actions no admite ficheros binarios como secret, así que se guarda
codificado:

```bash
base64 -w0 release.keystore > release.keystore.b64   # Linux
base64 -i release.keystore -o release.keystore.b64   # macOS
```

## 3. Crear los secrets

En GitHub: **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Valor |
|---|---|
| `RELEASE_KEYSTORE_B64` | contenido de `release.keystore.b64` |
| `RELEASE_STORE_PASSWORD` | contraseña del almacén |
| `RELEASE_KEY_ALIAS` | `btcseedrecovery` |
| `RELEASE_KEY_PASSWORD` | contraseña de la clave |

## 4. Verificar

Lanza el workflow y revisa los logs. Si ves este aviso, los secrets no están
llegando y el APK sigue sin ser publicable:

```
RELEASE_KEYSTORE_B64 not set — signing with the debug key.
```

Sobre el APK generado, comprueba que la firma es la tuya:

```bash
keytool -printcert -jarfile app-release.apk
```

El `CN` debe corresponder a los datos que introdujiste al generar el keystore,
no a `CN=Android Debug`.

## Nota sobre ofuscación

El build de release lleva R8 activado (`minifyEnabled true`). Las reglas de
`app/proguard-rules.pro` conservan lo que R8 no puede ver por sí mismo: las
clases con métodos `native` —el símbolo JNI codifica el nombre completo de
clase y método— y `RecoveryEngine`, cuyo `onProgress` lo resuelve el código C++
por nombre con `GetMethodID`.

Los fallos de R8 aparecen **en ejecución, no al compilar**, así que un CI en
verde no demuestra que el APK ofuscado funcione. Prueba el flujo de recovery
en un dispositivo real antes de publicar.
