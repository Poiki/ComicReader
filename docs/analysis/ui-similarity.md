# Interfaz próxima al APK suministrado

La app propia adopta el fondo blanco, el acento naranja, las cabeceras gris oscuro, la navegación inferior y el botón flotante de la versión 1.3.92 observada. Los iconos son vectores propios y los textos están en español. No se han trasladado imágenes, marca ni código de la app original al proyecto.

## Evidencia y alcance

Referencia: `Comicreader a imitar.apk`, SHA-256 `737108cd3ecf7d488eaad92fde3513d6eaf060546af246ec4dce24727b61d4d0`, paquete `com.progdigy.cdisplay`. Identidad vinculada al análisis REA previo `ev_d8db9f4b87507d0aa689aa9d402eacd82c34467fed1a17998769cea1d977c24e`. Esta ampliación se realizó el 29 de octubre de 2026 sobre el mismo archivo.

Se ejecutó el APK original en el emulador desechable `ComicReader_Test_API36`, API 36, pantalla 1080×2400, ABI x86_64 con traducción ARM64. No se utilizaron dispositivos físicos ni cuentas personales. Se observaron la pantalla de permisos, el inicio, la sección Reading vacía, el explorador de carpetas y el selector inferior de proveedores. El diálogo de licencia se canceló; no se modificó el APK ni su comprobación de licencia. Para explorar el único CBZ sintético en el emulador se habilitó allí el permiso de archivos declarado por la app.

Las capturas originales permanecen exclusivamente en `.analysis/`, excluidas de Git. [Comparación local lado a lado](../../.analysis/ui-comparison.html). [Inicio original](../../.analysis/original-home.png) y [nuevo inicio](../../.analysis/own-home.png). El registro de hashes completo está en `.analysis/ui-evidence.json`.

| Evidencia local | Qué demuestra |
|---|---|
| `original-home.png`, SHA-256 `772f99716f590dead7e9db87252635cc1d12de233278776f0256f676dd60c188` | Inicio blanco, secciones, filas con iconos, barra inferior y botón naranja. |
| `original-reading.png`, SHA-256 `180c855bd958ca05effb88e9720cea112643495d7f2b77b3c4cdf11b2b3c6469` | Sección Reading con cabecera gris y accesos de navegación inferiores. |
| `original-add.png`, SHA-256 `f06010ccf4e3b75c07a51517753da01b2e60c992518851169ee2eb4186c0ba4e` | Selector inferior de proveedores; el original incluye MEGA y Drive. |
| `original-reader-layout.txt`, SHA-256 `93ae758c472645624169fedb88281acac967897e4909dcdb689922bc834cbcea` | Disposición declarada: barra superior con botones y título, controles inferiores y slider con contador. |
| `original-resources.txt`, SHA-256 `e865d78cbb32199112be9676f3f68ae093e18d5076b37a264b5ee8b8e4f096b8` | Valores declarados: gris `#424242`, papel `#FFFBef`, título de lector 16 sp, botones 40/48 dp según configuración. |

Los dos últimos archivos son salidas locales de `aapt2 dump`, no componentes incluidos en la app nueva. Se documentan los valores y la organización, sin reproducir el XML original en el código del proyecto.

## Decisiones aplicadas

- Inicio con Almacenamiento, Biblioteca y Nube; Leyendo, Terminados, Sin leer, Favoritos y Descargas abren filtros reales. Descargas muestra las copias locales existentes. Importar usa el selector de documentos de Android. No se presenta una carpeta ni una cuenta como conectada sin estarlo.
- Barra inferior con Menú, búsqueda y ajustes. Dentro de la biblioteca ofrece todos los cómics, Leyendo y cambio entre lista y portadas. Los filtros sustituyen al spinner siempre visible anterior. La búsqueda se abre cuando se necesita.
- Botón circular naranja de añadir, con selector inferior de archivo local, MEGA, Drive y WebDAV. Solo muestra proveedores que tienen una implementación en este proyecto. Conserva los formularios, descargas y sincronización existentes. Si no hay proveedor configurado, Sincronizar abre las opciones de nube.
- Lista con miniaturas y vista de portadas adaptable al ancho, sin imágenes de demostración dentro del APK. Las capturas propias utilizan únicamente los archivos sintéticos de las pruebas. La cuadrícula es una elección propia compatible con la acción Display mode observada; no se afirma coincidencia exacta con una biblioteca poblada del original.
- Lector con controles de color papel `#FFFBef`, título de 16 sp, botones con iconos, contador y deslizador naranja. Las barras se superponen a la página para que mostrar u ocultar controles no cambie su tamaño de decodificación. Conserva zoom, manga, marcadores, teclado, ajustes y guardado.
- Controles de al menos 48 dp con descripción y tooltip; las filas anuncian título y estado una sola vez. Atrás usa el dispatcher nativo desde API 33 y la ruta de Activity para API 26–32.

## Portadas y coste

`CoverCache` crea miniaturas de la primera página a una resolución máxima de 192×288, aplicando también un reescalado final cuando el muestreo no basta. Solo solicita las celdas visibles del GridView: un hilo, cola máxima de 32 y caché LRU de 8 MiB. Retira solicitudes de celdas recicladas y descarta primero el trabajo antiguo si se satura la cola. No escanea la biblioteca, no usa red, no mantiene temporizadores y no añade dependencias. Al pausar la pantalla cancela las solicitudes pendientes; como máximo acaba la decodificación ya iniciada. Al destruirla cancela su trabajador y vacía la caché.

Un error de portada conserva un icono y registra la excepción; abrir el archivo sigue siendo posible y el lector informa de un error real del documento. Es una degradación limitada a una miniatura, no una recuperación silenciosa del archivo. No hay caché persistente de miniaturas: al arrancar de nuevo se decodifican las visibles. Las dimensiones y el límite de memoria son decisiones de implementación, no mediciones de batería.

## Verificación y límites

El baseline previo pasó 29 pruebas JVM y 7 grupos Android en el emulador limpio. Después del cambio pasaron `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` y `lintDebug` (0 errores, 14 avisos). La instrumentación vuelve a pasar los 7 grupos y añade comprobaciones de miniaturas CBZ/PDF, dimensiones, cambio lista/cuadrícula, búsqueda/restauración de resultados y recuperación de una portada ausente. Mantiene las aserciones de giro, recreación del lector, avances accesibles en manga y conservación de progreso remoto. El helper de pruebas reconoce también descripciones accesibles de botones con iconos.

La prueba Android debe iniciarse tras `adb -s emulator-5554 shell pm clear org.comicreader.app`, porque sus archivos y base de datos son fixtures nuevos para cada ejecución. Solo se limpió la aplicación de prueba de ese emulador.

[Lista propia](../../.analysis/own-list.png), [portadas propias](../../.analysis/own-grid.png) y [lector propio](../../.analysis/own-reader.png) se inspeccionaron visualmente. No se pudo comparar el lector original en ejecución: `libnative.so` abortó con SIGABRT/Scudo al generar una miniatura del CBZ bajo traducción ARM64. No demuestra un fallo equivalente en un dispositivo ARM64 real. Por eso el estilo del lector se apoya en recursos estáticos y la cuadrícula se presenta como una adaptación propia. No se han medido rendimiento ni consumo en hardware real; tampoco se validó login con cuentas reales en esta tarea visual.

## Limpieza acotada

AI SLOP CLEANUP REPORT

- Scope: `MainActivity`, `ReaderActivity` (solo construcción de vistas), `Ui`, `CoverCache`, tema/iconos propios y helpers/pruebas de disposición de `SmokeInstrumentation`.
- Behavior Lock: baseline JVM y Android antes de editar; mismas aserciones conservadas y verificaciones de biblioteca añadidas.
- Cleanup Plan: retirar los controles redundantes siempre visibles; reutilizar los flujos de importación/nube/lector; usar widgets Android y vectores sencillos; revisar el límite y la degradación de las miniaturas; verificar todos los accesos afectados.
- Fallback Findings: portada ausente → degradación acotada con excepción registrada, placeholder y prueba de recuperación. API 26–32 para Atrás → compatibilidad documentada; API 33+ usa dispatcher nativo. No se añadieron bypasses ni proveedores simulados.
- UI/Design Findings: se retiró el tema oscuro/dorado inventado. Blanco, naranja y gris están respaldados por ejecución y recursos del APK. No hay gradientes, tarjetas decorativas ni sombras de contenido; solo el botón flotante observado mantiene elevación.
- Passes Completed: resolución de degradación; eliminación del toolbar/spinner anteriores y vectores sin usar; reutilización de `Ui`; mensajes de WebDAV y estado de disponibilidad MEGA aclarados; pruebas y lint.
- Quality Gates finales de 0.2: 28 JVM PASS, build integrado con JNI real PASS, 7 grupos Android PASS en ambas ABIs y lint sin errores (21 avisos). Se comprobaron también el cierre de cola al pausar, las portadas grandes/panorámicas y el slider accesible. La revisión independiente y su seguimiento están en [ui-review](ui-review.md); el detalle de runtime y artefactos está en [QA](../QA.md).
- Changed Files: pantalla principal/navegación; controles de lector; tema y utilidades; miniaturas acotadas; vectores propios; verificaciones de UI.
- Remaining Risks: lector original no comparable en ejecución bajo este emulador; miniaturas solo en memoria; batería y escala de bibliotecas grandes pendientes de medir.
