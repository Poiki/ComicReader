# Investigación del APK y reconstrucción independiente

La primera fase analizó `Comicreader a imitar.apk` estáticamente. Después se ejecutó en un emulador propio sin cuentas para observar su interfaz; [comparación visual y límites](analysis/ui-similarity.md). El lector original abortó bajo traducción ARM64, por lo que su apariencia se reconstruyó a partir de recursos declarados. No se verificó su autenticidad editorial. Sus declaraciones identifican **CDisplayEx 1.3.92**, paquete `com.progdigy.cdisplay`, versión interna 6292; 15.134.794 bytes y SHA-256 `737108cd3ecf7d488eaad92fde3513d6eaf060546af246ec4dce24727b61d4d0`.

REA/JADX Headless MCP 0.7.1 recuperó un inventario de 4.751 clases y 1.103 recursos/entradas reportadas. El manifiesto declara mínimo API23 y target35. Evidencia principal: `ev_d8db9f4b87507d0aa689aa9d402eacd82c34467fed1a17998769cea1d977c24e`; manifiesto: `ev_cc84c30685c5a3e898f80b032e47edb41a36a718b0dbaf62a5f9bd9dfbdc7fe4`.

## Informes detallados

- [Lector, QoL, formatos, hilos, caché y batería](analysis/reader-apk.md).
- [MEGA, Drive, Dropbox, OneDrive, servidores y progreso](analysis/cloud-apk.md).
- [Inventario de tamaños y hashes](analysis/reader-inventory.json).
- [Opciones cloud y sus fuentes oficiales](analysis/cloud-design.md).
- [Revisión independiente del núcleo](analysis/review-core.md) y [del lector](analysis/review-reader.md). Los informes conservan sus hallazgos de la instantánea revisada; [QA](QA.md) registra las correcciones y pruebas posteriores.

Las conclusiones de esos informes citan Evidence IDs, métodos y límites. Los bundles locales completos en `.analysis` y `docs/analysis/evidence-cloud.json` contienen decompilación y se excluyen de la distribución abierta; los documentos explicativos no incluyen implementación decompilada.

## Hallazgos que cambian el diseño

**Archivos cloud y progreso son funciones distintas.** Drive usa REST v3 y guarda estado mediante `appProperties`; el SDK MEGA aporta login, 2FA, sesión, listado y descarga. En las rutas examinadas, el routing de progreso remoto cubre Drive, Komga y Kavita; no se confirmó progreso remoto MEGA. Una búsqueda de nombres no basta para demostrar ausencia: varias clases están ofuscadas y se siguieron métodos/llamadas.

**La aplicación propia tiene que ser dueña de sus sesiones y de su formato de progreso.** Los datos privados Drive de CDisplayEx no pasan a ser visibles al registrar otra identidad OAuth. No se copian clientes, claves ni tokens. La nueva implementación usa GIS para Drive y SDK oficial para MEGA; el estado se identifica por SHA-256 del cómic y se intercambia por CRP1. Las credenciales no forman parte del protocolo.

**El original ya hace trabajo de rendimiento útil.** Precarga según dirección, libera imágenes al detener el lector y contiene perfiles de arranque. No sería correcto describirlo como un lector sin caché ni gestión de ciclo de vida. Al mismo tiempo, el ajuste de pantalla encendida devuelve `true` inicialmente, la caché configura 12–24 MP según RAM y hay varios pools con colas no acotadas. Son observaciones estáticas; no demuestran por sí solas consumo elevado en uso real.

**Hay rutas que no conviene trasladar.** El examen encuentra una descarga Drive que captura errores y devuelve `null`, esperas MEGA sin timeout visible y una ruta opcional de verificación de hostname permisiva. La app propia mantiene errores explícitos, límites, TLS normal y espera acotada; no se afirma que las rutas del original fueran activadas o explotables en ejecución.

**Las bibliotecas nativas explican parte del peso.** El inventario ARM64 incluye `libmega.so` de 17.342.320 bytes y `libpdfium.so` de 4.972.504 bytes sin comprimir. Esto no mide su uso de RAM ni CPU. La nueva base utiliza `PdfRenderer` de Android y no copia esas bibliotecas; incorporar SDK MEGA actual vuelve a añadir su propio coste, todavía no medido.

## Cobertura de la petición

| Pregunta | Estado y límite |
| --- | --- |
| Identidad, manifiesto, permisos y paquete | Respondida estáticamente; firma/autenticidad no verificadas. |
| Principales funciones de lector y QoL | Respondida para menús, recursos y métodos seleccionados; no todos los métodos ni variantes de archivos. |
| Drive y MEGA: login, archivos, persistencia y sincronización | Respondida para rutas seleccionadas; cuentas y ejecución original no probadas. |
| Otros proveedores presentes | Inventariados; SMB, Dropbox, OneDrive y servidores no tienen auditoría de cada ruta. |
| Posibles costes de memoria/hilos/pantalla | Observados estáticamente, con inferencias separadas de mediciones. |
| Versión propia más eficiente | Implementadas decisiones acotadas de memoria, trabajo y red; no hay comparativa física de batería ni promesa porcentual. |
| QoL en la nueva app | [Tabla exacta implementado/pendiente](FEATURES.md). No hay paridad completa con CDisplayEx. |
| Login directo solicitado | Drive implementado pendiente de registro. MEGA SDK oficial compilado e incluido, con JNI/IO y callback remoto comprobados; cuentas reales pendientes. |

Esta investigación es profunda en los subsistemas indicados; no es una auditoría exhaustiva de todos los métodos, bibliotecas C/C++ o comunicaciones del APK. Un análisis estático no sustituye pruebas de cuenta, trazas runtime ni medidas de energía.
