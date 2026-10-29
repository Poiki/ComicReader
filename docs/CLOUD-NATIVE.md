# Conexiones directas a la nube

El usuario eligió iniciar sesión dentro de la aplicación. WebDAV queda como conexión opcional; no sustituye al login nativo. La integración de MEGA se documenta por separado en `CLOUD-MEGA.md`.

## Google Drive

El código usa Google Identity Services `AuthorizationClient` y la API HTTP Drive v3. No incluye un cliente OAuth extraído del APK, esquemas URI de redirección, contraseña de Google ni `client_secret`. Google Play Services conserva la sesión; la aplicación solo mantiene el access token en memoria. Necesita un dispositivo con Play Services compatible. El código fuente de ComicReader es abierto; el SDK de Google y Play Services tienen sus propios términos y esto no constituye una build F-Droid libre de componentes Google.

### Configuración del propietario

1. Crear un proyecto propio en Google Cloud y habilitar **Google Drive API**.
2. Configurar Branding, Audience, Data Access y usuarios de prueba en Google Auth Platform. Para desarrollo, añadir la cuenta que vaya a probarlo.
3. Crear un cliente OAuth de tipo **Android**, con paquete **`org.comicreader.app`** y SHA-1 del certificado que firma el APK instalado. No hay que pegar ese client ID en el Java: GIS identifica la aplicación mediante paquete/firma registrados.
4. Registrar por separado la firma final de distribución; si se usa Play App Signing, registrar la firma de la aplicación distribuida, no asumir que coincide con la clave de subida.
5. Probar desde un dispositivo/emulador con Google Play Services y una cuenta autorizada. Un emulador AOSP sin GMS permite comprobar biblioteca/lector, pero no verifica este login.

La asociación paquete/SHA-1 y el uso de `AuthorizationClient` siguen la [guía oficial Android](https://developer.android.com/identity/authorization). La dependencia es `com.google.android.gms:play-services-auth:22.0.0`; OkHttp `4.12.0` ya se usa para HTTP.

La firma **debug local** obtenida mediante `gradlew.bat :app:signingReport` el 29-10-2026 es:

```text
Paquete: org.comicreader.app
SHA-1: 5D:B7:A7:3A:F5:87:1D:3A:58:05:59:E8:AC:9D:53:33:8E:7C:93:C4
```

Esta huella deja de servir si cambia el keystore, la máquina o la firma. Volver a ejecutar `signingReport` en el checkout usado para generar la build. La variante release todavía necesita su configuración de firma propia.

### Permisos solicitados

| Scope | Uso |
| --- | --- |
| `https://www.googleapis.com/auth/drive.readonly` | Navegar los hijos de Mi unidad y descargar los cómics elegidos; no modificar originales. |
| `https://www.googleapis.com/auth/drive.appdata` | Leer/escribir las instantáneas de progreso de esta aplicación en su espacio oculto. |

`drive.readonly` es **restringido**. Una distribución pública puede exigir verificación de Google y, según cómo se almacenen/transmitan datos, controles adicionales. No dar por aprobada una publicación porque funcione con usuarios de prueba. `drive.appdata` es no sensible. [Scopes y requisitos oficiales](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

El permiso `appdata` no abre los datos privados de CDisplay ni de otros clientes OAuth. Solo sincroniza datos de ComicReader. El usuario puede borrar ese espacio desde Drive; la copia local y la exportación del progreso siguen siendo necesarias. [Espacio appDataFolder](https://developers.google.com/workspace/drive/api/guides/appdata).

### Interfaces implementadas

- `DriveAuth.signIn(activity, onToken, onFailure)`: inicia autorización y muestra el `PendingIntent` de Google cuando hace falta.
- `DriveAuth.onActivityResult(...)`: consume **requestCode 411**, incluidas cancelación y concesión parcial. Main debe delegarle antes de sus otros resultados.
- `DriveAuth.silent(context)`: solo desde hilo de trabajo; espera como máximo 30 segundos. Si requiere interacción, falla con instrucción de abrir la aplicación; un job no muestra diálogos.
- `DriveAuth.disconnect(activity, done, fail)`: obtiene token sin UI, revoca el permiso en el endpoint OAuth oficial y limpia su caché GIS; callbacks en el hilo UI. Si no queda una sesión accesible, el usuario también puede revocar desde los permisos de su cuenta Google.
- `DriveClient(token).list(folderId)`: `root` como inicio, paginación completa acotada, carpetas primero y orden natural. Los IDs se validan; un cursor repetido, IDs duplicados o búsqueda incompleta se muestran como error.
- `DriveClient(token).download(id)`: devuelve un stream que el importador debe cerrar. Descarga una copia privada; el límite real de bytes lo aplica también el importador.
- `DriveClient(token).sync(context)`: operación bloqueante para el executor; devuelve el número de estados fusionados después de subir correctamente la instantánea.

El listado usa `files.list` y el cursor `nextPageToken`; la descarga usa `files.get?alt=media`. Los originales de Drive no se modifican. [API de listado](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list), [descargas](https://developers.google.com/workspace/drive/api/guides/manage-downloads).

La revocación usa POST a `https://oauth2.googleapis.com/revoke`, con el token en el cuerpo de formulario, nunca en URL o logs. Puede revocar el conjunto de permisos del proyecto, no únicamente lectura de cómics; conviene un proyecto dedicado a esta aplicación. La limpieza posterior usa `AuthorizationClient.clearToken`. [Revocación OAuth](https://developers.google.com/identity/protocols/oauth2/native-app#tokenrevoke).

### Sincronización y límites

Cada instalación escribe **`{device-uuid}.crp`** en su `appDataFolder`. Si ya existe, actualiza ese ID con PATCH media; si no, crea archivo y contenido en un multipart único. Los archivos pertenecen al espacio oculto de la app y no se mezclan con los cómics. No son snapshots inmutables: cada dispositivo actualiza su propio archivo. Las instancias del cliente serializan sync dentro del proceso.

Antes de tocar la base se descargan y validan todos los estados elegidos; los conflictos usan el contrato existente `(revision, device)`, no el máximo de página ni la fecha del móvil. Se comprueba la exportabilidad del resultado antes del merge SQLite. Un fallo de subida conserva los cambios locales y se comunica como sync pendiente. El registro ganador es por cómic completo: cambios simultáneos independientes de favorito/marcadores pueden quedar sustituidos por el estado ganador.

Límites explícitos: 128 dispositivos, 8 MiB por fichero de progreso, 64 MiB de instantáneas descargadas por sync, 10.000 hijos por carpeta y 128 páginas de listado. No se borran automáticamente archivos remotos duplicados. No se implementa navegación especial de shortcuts, unidades compartidas ni exportación de documentos Google Workspace como cómics.

HTTPS valida certificados con la configuración normal de Android/OkHttp. No se siguen redirecciones ni se desactiva TLS; una respuesta inesperada falla sin reenviar bearer a otro host. `401` pide volver a conectar, `403` pide revisar permisos/API/cuota y `429` pide esperar. No hay sondeo continuo del servicio.

### Verificación y estado real

Ejecutado `gradlew.bat testDebugUnitTest --tests org.comicreader.app.DriveTest`: **4 tests, 0 fallos**. Comprueban scopes parciales, token malformado, petición/descarga y cierre, identificador no válido, fallo de autenticación y revocación sin token en URL.

`DriveClientInstrumentedTest.run()` contiene comprobaciones Android con JSON real y HTTP interceptado: paginación/orden, malformados, búsqueda incompleta, cursores repetidos e IDs duplicados. Su ejecución forma parte del runner smoke de la aplicación; consultar la evidencia final de QA para saber si se ejecutó en la build entregada.

No se ha probado login, listado, descarga, revocación ni sincronización contra una cuenta real de Google. Falta configurar el proyecto OAuth propio y verificar con cuentas/dispositivos autorizados. Las pruebas interceptadas demuestran el contrato local de HTTP y parsing, no aprobación de Google ni acceso a una nube real.
