# Nube y sincronización del APK de referencia

Análisis estático del 29 de octubre de 2026. Artefacto: `Comicreader a imitar.apk`, paquete `com.progdigy.cdisplay`, versión `1.3.92` (`6292`), SHA-256 `737108cd3ecf7d488eaad92fde3513d6eaf060546af246ec4dce24727b61d4d0`. El motor empleado fue JADX headless MCP `0.7.1` mediante REA. No se ha ejecutado el APK ni conectado una cuenta real.

La evidencia local está en `evidence-cloud.json` (instantánea de la sesión REA compartida; puede incluir otras consultas). Contiene fragmentos decompilados y material del APK original: conservar como material de investigación local y excluir del repositorio público de la nueva aplicación. Este informe describe comportamiento; no constituye código reutilizable ni certifica la licencia del APK.

## Lo que hay realmente

| Proveedor | Acceso a archivos observado | Estado de lectura remoto observado | Autenticación observada |
|---|---|---|---|
| Google Drive | REST v3: listado, carpetas, compartidos, shortcuts, descarga `alt=media` | Sí: `PATCH /drive/v3/files/{id}` sobre `appProperties` | Bearer OAuth mediante AppAuth; renovación centralizada |
| MEGA | SDK Java/Android y JNI: listado de nodos, miniaturas, descarga a archivo o stream | No en las rutas de sincronización examinadas | Email/contraseña, PIN 2FA y sesión persistible con `dumpSession`/`fastLogin` |
| Dropbox | REST v2, descarga `POST /2/files/download` | No en las rutas examinadas | Bearer OAuth mediante AppAuth |
| OneDrive | Microsoft Graph v1.0, descarga por driveId/itemId o ruta | No en las rutas examinadas | Bearer OAuth mediante AppAuth |
| Komga | API HTTP(S), libros, páginas, series, colecciones | Sí: API `/api/v1/books/{id}/read-progress` | Separado del OAuth cloud; helper HTTP añade autenticación |
| Kavita | API HTTP(S), biblioteca/serie/volumen/capítulo | Sí: API `/api/Reader/progress` | Datos JWT/refresh propios; pantalla `KavitaAuth` |
| SMB | Ruta `smb` y clases de acceso SMB | No en las rutas examinadas | Fuera de este examen detallado |

**Distinción esencial:** acceder al cómic almacenado en la nube y sincronizar la posición de lectura son capacidades diferentes. El APK muestra ambas para Drive, Komga y Kavita. La presencia del SDK MEGA no demuestra sincronización remota de páginas para MEGA.

La selección de proveedores se observa en `database.r0Je.I7dW`, `uR1Z` y `funV`, con esquemas `drive`, `dropbox`, `onedrive`, `mega`, `kmg/kmgs`, `kvt/kvts`; `database.rfT3.uR1Z` deriva las rutas locales/SMB. Evidencias: `ev_c79340b48449aec2057cd513bd9b566898dd4cd39c1217e6d3ee584947f3f5a3`, `ev_310b7e49b625188b73e6216e1cc9d8455f2be28ca586da47d5a6704999df1d88`, `ev_f21a64ac66074ed989da262209202c4005933a931f1ebccb3318d5cf6a8e6da2`, `ev_e565aba2f18b2beac1d271f2e7cb954c4cee2263a98343b15fe74eb1ceb06cc7`.

## Google Drive: flujo observado

1. `database.r0Je.HxjM` obtiene el estado OAuth persistido y usa AppAuth para obtener un token válido antes de continuar. Distingue éxito/error y también admite una ruta de continuación sin credenciales cuando el argumento booleano lo permite. No se ha establecido el significado de ese argumento en todos sus llamadores.
2. `database.QPAI.ow63` lista `drive/v3/files`, selecciona campos concretos, filtra papelera y padres, solicita `supportsAllDrives=true`/`includeItemsFromAllDrives=true`, sigue `nextPageToken` y reconoce accesos directos a carpetas. Para raíz incluye `root` y `sharedWithMe`.
3. `database.QPAI.Ugh4(Uri)` descarga el contenido por ID con `alt=media` y cabecera Bearer.
4. Si `settings.G9d2.H0` devuelve true (`sync_rs_google_drive`, false por defecto), el listado pide también `appProperties` y el constructor interpreta un valor de cuatro campos: estado, página, número de páginas y timestamp.
5. `database.QPAI.G9d2(int,int,int)` hace PATCH de esos cuatro campos. No modifica el contenido del archivo de cómic. La clave de la propiedad depende de la autoridad de la URI, mediante `ixkf()`.

Evidencias: autenticación `ev_cd6b062fa9e4450f6e6f5e3fb57bb260add49cec28433f5d0ddfabd8377b4773`; listado `ev_5aa20fdf5bd3e902ec9f20832e1711a3c0303f7fe54c8622f3b4209f543388c8`; descarga `ev_289e33c77a7b25706a1e778507588e36610498777bf8e639f4944e6af2c569c8`; preferencia `ev_464e8406b3f8a7a88346a6ae34c1e9804de54a878971179f7332a26ff7c355d1`; escritura `ev_dff82ff4b3753006c37f6da0c1bb5ac857ef23e5cd013b3631db356fe4532f7b`; lectura `ev_37f7579ca74aefe84bbf2bd73f97f348a3616b2c00d393e3905e26d64ff68d6e`; clave `ev_b394f48237c22cb9c6ce4b6f6790c9cf6bb9d6ff17af387146c74acd6b9676ee`.

Inferencia para la reconstrucción: utilizar un cliente OAuth propio y un formato de progreso propio, versionado y exportable. No dar por hecho que una app distinta tendrá acceso a las propiedades privadas de otra aplicación. La migración de progreso de CDisplay debe verificarse por exportación oficial o documentación de Drive antes de prometerse; copiar una clave o identidad OAuth del APK no resuelve este límite.

## MEGA: flujo observado

`nz.mega.ui.LoginActivity.onClick` valida email/contraseña e invoca `login`. `onRequestFinish` diferencia errores, activa UI de PIN cuando llega el código que la app interpreta como 2FA y, en éxito, devuelve usuario y `dumpSession` a su llamador. `database.g5vx.QPAI.run` reusa una sesión con `fastLogin` cuando no hay login activo.

`database.g5vx.BaGg(Uri)` obtiene la sesión y el usuario a partir de una autoridad de URI, mantiene un mapa de instancias por cuenta y autentica. `database.BsUX.ow63` solicita el listado mediante callbacks del SDK. `database.g5vx.Vk8L` selecciona entre `startDownload` y `startUnbufferedDownload`. `database.g5vx.G9d2` obtiene una miniatura en archivo temporal, la decodifica y elimina ese archivo.

Esto confirma un conector de archivos con SDK; no demuestra un motor de sincronización bidireccional de directorios ni progreso de lectura almacenado en MEGA. En la selección de objetos capaces de sincronizar estado (`r0Je.uR1Z`) no se selecciona MEGA y `BsUX` no expone el método de escritura de progreso que tienen Drive/Komga/Kavita. Es una conclusión limitada a los puntos examinados, no una prueba de ausencia de toda función dinámica.

Evidencias: clases SDK `ev_ba881ce30d9e9693a2f59c53d7045d6edc17b72396d53efed2993916058a9f50`; login `ev_a1ac014232e8df8f271b6f6ee32b408a93099dcd52642d6d871a10cda9ebea78`; resultado/2FA `ev_ec56a26fa53932edb614ee83d14ffb25ad98c82897f08dcec5cd0bee22525d9f`; sesión `ev_2e66570fce9fdfd7e5ca809e2d42e2e34f4ba8702bd8c746ca9b9bc27a4262b4`; instancias `ev_2c19a75e7845445ed0cf5471fc10073b979a1bcbb75a331afc275cca2aebcc07`; listado `ev_03bd6f723ac2399a99f27739eb8f12b43cd03d7e20651f64dafd2bb7ce199f30`; descarga `ev_c0c5441a04e7a91bceaa9c8c66fd6b260fcd314c73f753f2dbfead2bf052d7c1`; miniatura `ev_5c77fd09770808e721a6de1c15afb48d895128b3cbcf929d4f12bae7ee834ed5`.

## Otros proveedores y progreso

- Dropbox usa `content.dropboxapi.com/2/files/download`, POST y cabecera `Dropbox-API-Arg` con el path. Evidencia `ev_89818f76727cc960337428574df2b99d7027e01a9974bc69b344c7e12fb3aba4`.
- OneDrive construye `graph.microsoft.com/v1.0/drives/{driveId}/items/{id}/content` o `/me/drive/root:/ruta:/content`. Evidencia `ev_4afbc5a9de4e30de6cad36a414a27fbd60d6ee314546caeb39d202134229e293`.
- Komga traduce el estado local sin leer/en lectura/completado a su API de read-progress; en lectura convierte índice interno cero a página remota uno. Evidencia `ev_a98d1824cddd426fb184376cc71bd240a2ca4b9bbeaf0c145e6ca586d3e0bce0`.
- Kavita envía IDs de biblioteca/serie/volumen/capítulo y `pageNum` a `/api/Reader/progress`. Evidencia `ev_c9e9e526159ef3b576097a0b71f18f079d666889d42ab7cc4201c77adbbeb3b5`.
- `database.OhRQ.p0` reconcilia progreso local/remoto comparando timestamps y estado; puede adoptar un estado remoto anterior en número de página si su timestamp es posterior. No es simplemente conservar siempre la página máxima. Evidencia `ev_5399ebd8dd2d74012af2e000eb4bafa8b1e3f4139f269cbddf4f494c82e6a748`.

## Costes y problemas que conviene evitar

Son observaciones estáticas; no se ha medido su impacto en un teléfono.

| Observación y evidencia | Interpretación y decisión recomendada |
|---|---|
| Drive/Dropbox/OneDrive devuelven null tras capturar `Throwable` en descarga (`ev_289e33c77a7b25706a1e778507588e36610498777bf8e639f4944e6af2c569c8`, `ev_89818f76727cc960337428574df2b99d7027e01a9974bc69b344c7e12fb3aba4`, `ev_4afbc5a9de4e30de6cad36a414a27fbd60d6ee314546caeb39d202134229e293`). | Clasificación: masking fallback. La nueva app debe conservar causa HTTP/autenticación, permitir reintento explícito y nunca mostrar éxito tras fallo. |
| Varias operaciones SDK MEGA esperan con `wait()` sin timeout en el código mostrado (`ev_74446976621df78fefaa16722d6123728c4c7257934cdfe172630cbb5922b170`, `ev_4bdcc26b2e0800231b4d94ac836d02d1f9e1c1ad7b5c0f8b89fba5001172c3f0`, `ev_03bd6f723ac2399a99f27739eb8f12b43cd03d7e20651f64dafd2bb7ce199f30`). | No copiar el puente bloqueante: callbacks cancelables, deadlines y trabajo fuera del hilo UI. No se ha probado que la app actual bloquee la UI. |
| Helpers HTTP examinados configuran timeout de conexión 2000 ms; no muestran un timeout de lectura en esos métodos. | Definir ambos timeouts y cancelación; evitar reintentos agresivos ante red móvil lenta. La política global no quedó completamente examinada. |
| OAuth state se obtiene como string desde `locations.password`; `OhRQ.Z` escribe directamente el string recibido en SQLite (`ev_7544ff0c8a705037a25f6b11972d00d6ac4505a66a8f427ac151d3242d4414a7`, `ev_e2919377e7e49fb7e37c966f62af73eb7e168df8823fdf4abc05670962d8b789`). | No hay cifrado de aplicación visible en estos puntos. Para una app propia: Keystore para tokens, no incluirlos en exportación/progreso/logs. No se ha auditado la protección completa del dispositivo. |
| MEGA usa usuario/sesión en autoridad URI según `BaGg`. | Evitar credenciales en URIs, nombres, stack traces o claves de caché. Usar ID de cuenta local y almacén separado. |
| Komga permite ruta opcional con hostname verifier que retorna true (`ev_a78f89ae3af2a5755516f50a4dfd154f3435d3f05e156a6f41c37e6679501f34`, `ev_82cbb392cac98f385174e51c7c1acff762cb09400bc126d439af2cb6d1245cf3`). | Compatibilidad insegura opcional: no reproducir un trust-all global. Certificado válido o confianza específica y explícita por servidor. |
| `QPAI.r0Je` formatea/parses fechas cambiando zona horaria para producir timestamp (`ev_955ae0b3d86b0a7fb2ba44159f7d88fd113e80c802a34f0e1b5670b789043bb9`). | Para formato propio usar epoch UTC directamente. El desfase efectivo original requiere contrastar llamadores y ejecución. |

## Requisitos de reconstrucción derivados

1. Biblioteca y lectura disponibles sin red; guardar progreso local inmediatamente en operaciones breves y acotadas.
2. Separar sincronización de metadatos pequeños de descarga de cómics. No subir cómics al guardar una página.
3. Encolar sólo progreso cambiado y agrupar escrituras; exponer estado pendiente/último éxito/error. Reintentar con backoff y política de red elegida.
4. Usar IDs de archivo estables por proveedor o huella de contenido, no la URI del selector Android como identidad entre dispositivos.
5. Validar esquema/versiones/límites del progreso remoto antes del merge. No reemplazar datos locales por un JSON vacío o corrupto.
6. Resolver conflictos por una política explícita que permita releer y desmarcar; conservar únicamente el máximo de página rompe esas operaciones.
7. Caché acotada, archivo temporal + renombrado al terminar, cancelación, descargas sólo a petición y modo sólo Wi-Fi. Precargar como máximo lo que se vaya a usar.
8. Cuentas y scopes propios. El SDK, OAuth, redistribución y credenciales de una nueva app tienen que configurarse de manera independiente del APK de referencia.

Estas son decisiones de diseño de una app nueva. No implican que ya se hayan implementado ni que sean comportamientos medidos del APK.

## Cobertura y pendientes

| Pregunta | Estado |
|---|---|
| Identidad/versiones del APK | Respondida por inventario REA del padre |
| MEGA y Drive son conectores reales o sólo strings | Respondida por métodos y rutas estáticas |
| Diferencia entre archivo remoto y progreso | Respondida para los puntos examinados |
| Almacenamiento de progreso Drive y merge | Respondida estáticamente |
| Dropbox/OneDrive/Komga/Kavita | Confirmados; análisis más reducido que MEGA/Drive |
| Programación exacta de sincronización | Parcial: no se trazaron todos los llamadores de `OhRQ.p0/q0` |
| Exportación/migración de progreso original a otro OAuth client | Pendiente de contrato oficial y prueba autorizada |
| Tráfico real, cuotas, 2FA, expiración, reconexión y consumo | No ejecutados ni medidos |
| Licencias de redistribución y autenticidad/firma del APK | No verificadas |

Las búsquedas completas de nombres `Job`, `Worker`, `Service`, `Alarm` sólo encontraron schedulers/servicios de bibliotecas Google y no un worker de sincronización reconocible por nombre. Esto no demuestra ausencia de sincronización en segundo plano: el código está ofuscado y puede ejecutarse desde otros puntos. Las búsquedas sin coincidencias `Drive`, `Cloud`, `Webdav` tampoco demostraron ausencia; Drive se confirmó después por llamadas REST. No se ha hecho una afirmación global de WebDAV soportado/no soportado.

La cobertura de referencias entrantes también es estática. Por ejemplo, `QPAI.Ugh4` tiene un llamador directo identificado en `r0Je.I7dW`, con instrucción DEX concreta; reflexión/carga dinámica siguen fuera de esta garantía (`ev_eda51344d6f5fef2854daf062d0990a38f0a7d938b9ac07ba5921173dd467c70`).

No se omitió trabajo de ejecución dando por bueno el estático; se mantiene como pendiente explícito. Riesgo principal: no prometer conservación automática de sesiones o progreso del producto original sin verificar interoperabilidad.
