# Verificación de ComicReader

Entrega actual **0.5.0 (CBR/RAR)**: [pruebas y límites](QA-0.5.md). La evidencia de0.4/0.3 se conserva debajo como historial.

## Historial0.4.0

30 de octubre de 2026. **31 tests JVM y 11 grupos Android x86_64 PASS**, build debug/release R8 PASS y lint con **0 errores, 24 avisos**. El grupo nuevo prueba selección de ABI, versiones numéricas, prereleases, digest/origen/duplicados/tamaño, rechazo de un archivo alterado y confinamiento real del provider (lectura exclusiva del APK, escritura y traversal rechazados).

Se publicaron el código y la release [v0.4.0](https://github.com/Poiki/ComicReader/releases/tag/v0.4.0) usando el script de publicación: APK ARM64, x86_64, universal y SHA256SUMS. Firma original y alineación16KiB verificadas. La API pública devuelve assets con SHA-256 coincidente con los archivos locales. No se publican claves, sesiones, originales ni cachés.

Snapshot y hashes actuales: [qa-updates.json](qa-updates.json). El `qa-results.json` anterior conserva la entrega0.3.

## Prueba contra la release real

Un APK debug de prueba0.3.1, con el mismo actualizador y certificado, se instaló en el emulador propio y recibió un cómic, página2, favorito y marcador sintéticos. Desde su UI se comprobó: consulta pública de GitHub, detección0.4.0, selección del asset x86_64, descarga completa, digest y firma/paquete/versión aceptados, permiso estándar de instalaciones desde ComicReader y pantalla del instalador «Do you want to update this app?».

**La instalación final no pasó en este emulador Google Play.** El verificador Finsky devolvió REJECT; PackageManager registró `INSTALL_FAILED_VERIFICATION_FAILURE`. El dispositivo conservó0.3.1. No se desactivaron protecciones ni se atribuye una causa más concreta sin evidencia. La descarga/validación y entrega al instalador sí se comprobaron; la actualización instalada de extremo a extremo queda pendiente en otro entorno/dispositivo. No se da por probada a partir de tests sintéticos ni de una instalación por ADB.

Los XML de UI, logs de verificación y metadatos quedan en `.analysis` local. Google ofrece [orientación oficial sobre avisos de Play Protect](https://developers.google.com/android/play-protect/warning-dev-guidance). Una firma correcta para actualizar no garantiza que el sistema autorice instalar. Esta entrega conserva firma de pruebas y no es una publicación en Play Store.

## Historial: entrega 0.3.0

30 de octubre de2026. Windows, JDK21, Gradle9.5.0, AGP9.3.1, compileSdk37/target36. Se usó exclusivamente el emulador propio API36 x86_64 con traducción ARM64, sin cuentas personales. Ninguna tablet física del usuario.

| Comprobación | Resultado |
| --- | --- |
| JVM | **28 tests,0 fallos**: núcleo9, Drive4, WebDAV12, temporales3. Baseline anterior también PASS. |
| Build | App debug, tests Android y release optimizada con SDK MEGA real: PASS. |
| Lint | **0 errores,23 avisos**, sin supresiones nuevas para ocultarlos. |
| Android x86_64 | **10 grupos PASS**, con selección Aceptar desde UI, filtros, temas e inicio. |
| Android ARM64 traducido | **10 grupos PASS** en viewport tablet2120×3000/densidad320. |
| JNI/red ARM64 | SDK10.21.0 real, IO privado y callback remoto de rechazo de cuenta reservada inexistente: PASS. |
| Actualización0.2→0.3 | Instalación0.2 optimizada, importación por selector Android, favorito y marcador desde UI; actualización sin borrar datos y verificación de archivo, página2, favorito y marcador: PASS. |
| APK entregado | Firma del mismo certificado anterior, alineación16KiB y `apksigner verify`: PASS. |
| Release ARM64 | Instalado y abierto; catálogo guardado, portadas, tema OLED negro real, cambio a claro desde ajustes y disposición horizontal3000×2120 comprobados. Sin fallo fatal del proceso en la ventana observada. |
| APK original | SHA-256 conservado. |

Los hashes y tamaños exactos se guardan en [qa-results.json](qa-results.json). Instalador: **ComicReader-0.3.0-OnePlusPad2.apk**,27.935.763 bytes, ARM64, R8, firmado para pruebas. `ComicReader-OnePlusPad2.apk` es una copia de los mismos bytes. No es una publicación comercial.

## Cobertura

Los siete grupos anteriores conservan importación CBZ/PDF, orden, límites y rechazo sin perder datos; SQLite, conflictos y reloj; Keystore; JobScheduler optativo; parsers WebDAV/Drive; biblioteca, portadas, zoom/manga accesible, slider y recreación/persistencia del lector.

Los tres nuevos grupos cubren:

1. **Catálogo MEGA:** aislamiento por cuenta aunque coincidan handles; carpeta vacía frente a no consultada; rechazo atómico de duplicados conservando la lista anterior; filtro de formatos; actualización sin perder asociación al hash; flags previos a descargar transferidos al progreso; eliminación de hijos remotos conservando subcarpetas consultadas.
2. **Biblioteca/temas:** botón Aceptar real del selector con metadatos sintéticos, guardado de la selección, nueva instancia que recupera carpeta y flags, navegación/subida, filtros leído/sin leer, marcar antes de descargar, cuadrícula adaptable y fondos reales oscuro/OLED tras recreación. El catálogo de pruebas es sintético; no se simula login de un proveedor dentro del producto.
3. **Inicio:** biblioteca predeterminada, configuración elegida desde UI, apertura real del último cómic incompleto en su página y texto claro del lector OLED; si el último cómic está completo o no existe, biblioteca.

La prueba de actualización utiliza el APK0.2 firmado y optimizado: no se intentó modificar su base mediante clases desofuscadas. Se prepararon los datos desde su UI y se verificaron después de instalar0.3. La instrumentación solo puede acceder por tipos al nuevo APK debug; la comprobación visual final corresponde al release instalado.

## Límites

Las pruebas nuevas de carpetas usan un catálogo controlado sin cuenta real. El SDK nativo sí se ejecutó y recibió un rechazo real de autenticación para una dirección reservada inexistente; eso no verifica login correcto, 2FA ni una transferencia autenticada. El usuario informó de login MEGA correcto en0.2, pero no se atribuye ese uso a nuestras pruebas automatizadas. No se probaron aquí carpetas de una cuenta personal, cuotas ni sync entre dispositivos reales.

Las marcas de archivos aún no descargados son locales por cuenta/handle hasta conocer el hash del contenido. La sincronización existente resuelve conflictos por cómic completo. No se han medido batería, temperatura ni rendimiento en la OnePlus física, ni una biblioteca de50.000 archivos. Los avisos lint corresponden a herramientas/target, textos en código, APIs programáticas/accesibilidad, extracción de datos y referencia singleton; no sustituyen una auditoría completa con TalkBack.

Historial de0.2: SDK oficial10.21.0 compilado desde fuente para ARM64/x86_64, ELF16KiB, carga JNI/IO y callback remoto comprobados en ambas ABIs. Sus fuentes, dependencias y límites se describen en [CLOUD-MEGA](CLOUD-MEGA.md). Los informes estáticos y de semejanza del APK original conservan su alcance anterior.
