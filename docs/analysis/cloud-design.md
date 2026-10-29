# Integraciones cloud: decisión y límites verificables

Consulta: 29 de octubre de 2026. Investigación de documentación y código oficiales; no se ha iniciado sesión en ninguna cuenta, ejecutado MEGA ni probado la sincronización contra una nube real. Este documento describe opciones de diseño: no certifica que estén implementadas en la aplicación.

## Alternativa inicial y decisión posterior

La alternativa inicial era lectura local mediante Storage Access Framework (SAF) y un cliente WebDAV para catálogo, descarga bajo demanda y sincronización de progreso. **El usuario eligió después acceso directo en la aplicación**, por lo que el puente no sustituye a las integraciones nativas: queda como opción adicional. El diseño y configuración actuales de Drive están en [CLOUD-NATIVE.md](../CLOUD-NATIVE.md); MEGA tiene su propia documentación de construcción. Un puente externo rclone/MEGAcmd sigue siendo posible, pero necesita permanecer disponible en PC/NAS y no debe llamarse login nativo.

SAF es la opción más pequeña para acceder directamente a proveedores ya instalados, incluido Google Drive. El usuario concede acceso desde el selector y la aplicación conserva el permiso URI concedido. `ACTION_OPEN_DOCUMENT_TREE` permite una carpeta; `ACTION_OPEN_DOCUMENT`, un archivo. Esto evita pedir acceso a todo el almacenamiento. La presencia de un proveedor, sus capacidades y su comportamiento sin conexión deben verificarse en el dispositivo. [Android: acceso a documentos](https://developer.android.com/training/data-storage/shared/documents-files).

**Separar cómics de progreso.** Abrir un CBZ desde Drive no sincroniza automáticamente la página leída. El estado del lector necesita su propio transporte y formato, también si el cómic se importó por SAF. Una exportación manual de JSON tampoco equivale a sincronización automática.

## MEGA mediante SAF: resultado condicionado

La petición oficial de `DocumentsProvider` #110 continúa abierta; su estado por sí solo no demuestra ausencia de implementación. [Issue oficial](https://github.com/meganz/android/issues/110).

El código `master` consultado **sí contiene** `CloudDriveDocumentProvider`, declarado en el manifest con `android:enabled="false"`, permiso `MANAGE_DOCUMENTS` y acción `DOCUMENTS_PROVIDER`. La implementación incluye lectura, creación y cambio de nombre; su `openDocument` devuelve archivos locales de solo lectura para nodos existentes y escritura para creaciones pendientes. No se ha establecido qué edición publicada lo habilita ni cómo puede activarlo el usuario. Por tanto, la aplicación propia puede aprovecharlo si el proveedor aparece en el selector, pero no debe prometer MEGA SAF universal ni modificación arbitraria de archivos existentes. [Manifest oficial](https://github.com/meganz/android/blob/master/app/src/main/AndroidManifest.xml), [implementación oficial](https://github.com/meganz/android/blob/master/app/src/main/java/mega/privacy/android/app/providers/documentprovider/CloudDriveDocumentProvider.kt).

La documentación interna del proveedor describe creación de archivos temporales, subida en segundo plano y propagación posterior del nodo definitivo. Cerrar un descriptor de escritura no demuestra que el objeto ya esté confirmado en la nube. Evitar usarlo como si fuese un sistema POSIX con reemplazo atómico o bloqueo entre dispositivos. [Flujos SAF de MEGA](https://github.com/meganz/android/blob/master/app/src/main/java/mega/privacy/android/app/providers/documentprovider/SAF_FLOWS.md).

## Conexiones nativas

### Google Drive

Para progreso propio, el scope mínimo es `https://www.googleapis.com/auth/drive.appdata`. Permite guardar datos ocultos en `appDataFolder`, accesibles únicamente por esa aplicación. No sirve para explorar la biblioteca visible de cómics ni para leer los datos privados de CDisplay. La creación usa `parents: ["appDataFolder"]` y el listado filtra por ese espacio. El usuario puede eliminar esos datos desde Drive: el estado local debe seguir siendo útil. [Drive: datos de aplicación](https://developers.google.com/workspace/drive/api/guides/appdata).

Para archivos elegidos/creados por la app, `drive.file` es un scope recomendado no sensible. Explorar toda la biblioteca con `drive.readonly` o `drive` exige scopes restringidos y puede añadir verificación y evaluación de seguridad según el uso. SAF permite evitar pedir esos scopes al lector para archivos elegidos por el usuario. [Scopes oficiales](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

La vía Android recomendada para autorización es Google Identity Services `AuthorizationClient`. Requiere un proyecto propio en Cloud Console, Drive API habilitada, consentimiento y un cliente Android asociado al paquete y SHA-1 del certificado de firma. Una build debug y la distribución final pueden requerir registros distintos. No hay credenciales del propietario en este repositorio; no se puede dar por funcional el login sin configurarlas y probar una cuenta real. La app debe permitir desconectar/revocar acceso. [Autorización Android](https://developer.android.com/identity/authorization), [API AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient).

No reutilizar sin comprobar ejemplos antiguos de navegador + esquema URI personalizado. Google deshabilitó esa vía por defecto para nuevos clientes Android y recomienda GIS. La guía actual de OAuth de aplicaciones instaladas está orientada a escritorio y remite a la guía Android para móviles. PKCE protege clientes públicos, pero no soluciona por sí mismo un redirect no admitido. No introducir un `client_secret` supuesto secreto en el APK. [Restricciones de Google](https://developers.googleblog.com/improving-user-safety-in-oauth-flows-through-new-oauth-custom-uri-scheme-restrictions/), [guía actual de escritorio](https://developers.google.com/identity/protocols/oauth2/native-app), [RFC 8252](https://www.rfc-editor.org/info/rfc8252/).

### MEGA SDK

El SDK oficial proporciona API C++ y bindings Java/SWIG para Android. Es una integración JNI/NDK real, con dependencias nativas y construcción por ABI; la versión actual documenta Android 9 como mínimo. No es un pequeño cliente REST que deba reconstruirse a mano: el SDK gestiona el cifrado y las claves del cliente. [README oficial](https://github.com/meganz/sdk/blob/master/README.md).

La cabecera antigua `megaapp.h` aún menciona una application key, pero **no es un requisito vigente**: el mantenedor oficial confirma que el constructor de `MegaApi` admite `null`/`nullptr`. No hay que obtener ni reutilizar la clave de otra app. La licencia del SDK es BSD de dos cláusulas y exige conservar sus avisos en fuente/binarios; también deben respetarse las condiciones del servicio y las licencias de dependencias. No copiar sesiones ni implementación de autenticación de CDisplay. [Aclaración oficial y documentación del constructor](https://github.com/meganz/sdk/issues/2706), [licencia del SDK](https://github.com/meganz/sdk/blob/master/LICENSE).

## Puente WebDAV: ruta operativa

`rclone serve webdav remote:carpeta` ofrece lectura y escritura para un remoto ya configurado. Activar autenticación (`--htpasswd`, preferiblemente BCrypt) y HTTPS con certificado válido; por defecto puede servir sin login y HTTP no cifra la conexión entre el lector y el puente. El proceso debe permanecer funcionando y ser alcanzable desde los dispositivos. Un ejemplo de configuración del servidor es:

```sh
rclone serve webdav mega:Comics --addr 127.0.0.1:8080 --htpasswd /ruta/usuarios.htpasswd --vfs-cache-mode writes
```

Publicarlo mediante un reverse proxy HTTPS autenticado o una red privada configurada; usar la URL HTTPS del puente en el lector. No presentar `127.0.0.1` de un PC como URL accesible desde el teléfono. `writes` almacena temporalmente las escrituras para subirlas después; confirmar la respuesta WebDAV no implica que Drive/MEGA ya haya recibido el contenido. [rclone WebDAV](https://rclone.org/commands/rclone_serve_webdav/).

Drive en rclone requiere autorizar la cuenta. La documentación actual indica que el `client_id` compartido se retira durante 2026 y pide crear uno propio. Por tanto, no prometer configuración de Drive indefinidamente sin proyecto OAuth, aunque rclone simplifique el flujo. [rclone Drive](https://rclone.org/drive/).

El backend MEGA de rclone ofrece transferencia cifrada y su configuración actual incluye `2fa`; requiere que las claves de la cuenta ya se hayan generado tras un login normal. Documenta ausencia de mtime/hash, nombres duplicados y posibles bloqueos por logins repetidos. Mantener un servidor persistente evita lanzar una autenticación por cada página. No desactivar 2FA como supuesto requisito general basado en tutoriales antiguos. [rclone MEGA](https://rclone.org/mega/).

MEGAcmd oficial es otra opción para MEGA: expone carpetas por WebDAV con lectura/escritura, caché y TLS opcional. Su documentación advierte que HTTP sirve archivos descifrados y que el servicio puede exponerse remotamente con `--public`; no debe abrirse así a Internet sin control adicional. El documento consultado no promete autenticación de usuario/contraseña por petición. Se necesita proxy autenticado/red privada y revisar las capacidades de la build instalada. [WebDAV oficial MEGAcmd](https://github.com/meganz/MEGAcmd/blob/master/contrib/docs/WEBDAV.md).

## Contrato mínimo de sincronización propuesto

Estas son decisiones de diseño, no comportamiento observado de los proveedores:

- Estado local duradero primero; red fuera del hilo UI; nunca impedir leer por estar offline.
- Identidad estable del cómic: identificador explícito transportable o hash del contenido al importarlo. Un URI local, nombre, tamaño o fecha por sí solos no garantizan identidad entre dispositivos.
- Guardar página/total, marcadores y cambios con versión de esquema y origen del dispositivo. Separar cambios de página, favoritos y marcadores evita pisar campos independientes.
- No fusionar con `max(page)`: una relectura o reinicio puede retroceder legítimamente. Resolver cambios concurrentes de forma determinista y conservar evidencia/conflictos; los relojes locales pueden estar desajustados.
- No borrar progreso al faltar un remoto o cómic; no interpretar un fallo de listado como biblioteca vacía confirmada.
- Para un único archivo compartido: lectura + ETag fuerte + escritura condicional `If-Match`; `412` exige releer/fusionar con reintentos acotados. Si el servidor ignora precondiciones o devuelve solo validadores débiles, no declarar la ruta libre de pérdidas. Una alternativa es un archivo por dispositivo más fusión; necesita comprobar actualización visible y evitar duplicados del backend.
- Descargar CBZ/PDF bajo demanda a almacenamiento privado, acotar caché y dejar una copia offline explícita. Evitar reabrir/descargar todo el CBZ por cada página.
- Sin sondeo continuo: sincronización manual y al cerrar una sesión con trabajo agrupado. La sincronización periódica opcional debe respetar Wi-Fi/red medida y batería.
- Errores de autenticación, límites de cuota, timeout, XML/JSON inválidos y conflictos deben verse en UI; nunca informar «sincronizado» si solo se actualizó localmente.

La semántica de precondiciones y validadores la define HTTP; **no se ha probado que cada puente/backend conserve todas esas garantías**. [RFC 9110, If-Match](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match).

## Pendientes que requieren configuración o verificación real

1. La decisión de producto ya es login nativo; el puente es opcional y no cubre por sí solo esa petición.
2. Configurar el proyecto OAuth propio de Drive y construir/incluir el SDK oficial MEGA antes de dar por operativos ambos clientes. MEGA no exige app key actualmente.
3. Verificar SAF de las versiones concretas de Drive/MEGA instaladas; `master` no demuestra funciones habilitadas en Google Play.
4. Probar dos dispositivos concurrentes, offline prolongado, relojes distintos, revocación y cuotas contra cuentas del propietario.
5. Medir red, memoria y batería en hardware; la elección arquitectónica sola no demuestra ahorro energético.

No se han instalado servidores ni registrado cuentas/clientes; no se ha probado una nube real. Las conexiones directas deben verificarse con la configuración del propietario; la alternativa WebDAV depende además de un puente externo disponible.
