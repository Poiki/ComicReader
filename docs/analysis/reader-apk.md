# Lector, funciones de comodidad y coste de ejecución del APK

Análisis estático del APK suministrado, realizado el 29 de octubre de 2026, antes de instalarlo. Este documento describe comportamiento recuperado y decisiones de diseño para una implementación independiente; no contiene código decompilado. La observación posterior de interfaz en emulador se documenta por separado en [ui-similarity](ui-similarity.md).

- Archivo: `Comicreader a imitar.apk`, 15.134.794 bytes.
- Paquete y versión del análisis principal: `com.progdigy.cdisplay`, `1.3.92` / `6292`.
- SHA-256: `737108cd3ecf7d488eaad92fde3513d6eaf060546af246ec4dce24727b61d4d0`.
- Motor REA: JADX Headless MCP 0.7.1. El inventario inicial informa 4.751 clases. Se inspeccionaron las clases y métodos vinculados al lector y al procesamiento de páginas; no se revisaron todos los métodos ni las bibliotecas nativas.
- Evidencia complementaria reproducible: [reader-inventory.json](reader-inventory.json), generada con ZIP de Python y `aapt2 dump resources/xmltree`. Conserva tamaños, hashes y declaraciones, sin recursos gráficos ni código original.

## Qué está demostrado y qué falta

**Observado** significa una declaración del paquete, un recurso identificado o un cuerpo de método recuperado. Un texto de ajustes demuestra que el ajuste está empaquetado; por sí solo no demuestra que todas sus rutas funcionen. **Inferido** describe una consecuencia razonable del código. **Desconocido** necesita ejecutar, instrumentar o medir la aplicación.

El lector público `ReaderActivity` tiene muy pocos miembros propios. La mayor parte del comportamiento está en `reader.rfT3` y `reader.PJNV`, con nombres ofuscados. El paginador es `reader.YCP0`; el indicador de posición con marcadores es `reader.Slider`. Evidencias de inventario: `ev_f30e167f575a7ae7c418eeb1c8276fc15016ea0f50acc8155a2687e0756f93db`, `ev_f0e592eec881ef54d6223d729072d25da9ffba0bf874fa165b0d0935a23f65ba`, `ev_8ace364d022535021dba7cbadb1269155c1155d360a90de8d217a1643d797dd4`, `ev_f626c61b8aaad5077af30652b48e06e2576fa3ef5cb4575374893829d9dd1a27`.

## Funciones de comodidad encontradas

| Función | Evidencia y alcance | Decisión para el lector propio |
|---|---|---|
| Reanudar lectura | `rfT3.onPause` llama al guardado; `PJNV.iPUu` envía posición, total, preferencias por libro y marcadores al almacén. `ev_e2b6811fbd272512b87ebccb64a7680a2f3ff528c64605218b9202586f82ef74`, `ev_63a2f21b872183b9c680c6cf946e11053b50895eb3b61b333a8e4ed630b6e0ab`. | Prioridad P0: persistir posición al cambiar de página y al pausar, con escrituras agrupadas. |
| Marcadores por página | `PJNV.k6Gw` alterna marcadores y conserva la relación con páginas dobles; `Slider.setBookmarks` y `onDraw` muestran marcas. `ev_336c9a92b8340c9892b12d4b05083f2453ab25606c59fd72156cfa6f47db1f1f`, `ev_034074cfe752da9d97dfa069fd7ba444d90cea00e5304047f238661eb586f7f5`, `ev_c4ff4021904fa75117f1fa95be5b95bc0786f8cc8df28d0255737ef00417d4fb`. | P0: añadir/quitar y volver a una página marcada. |
| Lectura de derecha a izquierda | Ajuste `right_to_left`, con valor inicial dependiente del idioma japonés; `YCP0.setRightToLeft` aplica la dirección. `ev_f1f471dcdc6ea79f974721abab63610ec8c6f0c9c0a40ff8b3be155d7d0469ac`, `ev_3c34d50cf4d04721428979f0d4150bcdd016ac938c3b565d49b91f5e991e0c90`. | P0: dirección explícita, con gestos y botones coherentes. |
| Páginas dobles y agrupación | `PJNV.BsUX` construye grupos de páginas según modo y dimensiones, ajustando marcadores/posición. Ajuste `double_page`. `ev_52b318e2938ac127838632cd08ee05bdf6d39005e310479d79289b2e240cf0ac`, `ev_2ccc603f6c852fc8207091b87747a34c26562165bbc8851fd49c2c0c416af135`. | P1: implementar después de tener probadas posición y orientación en página única. |
| Lectura vertical | Recurso `reading_vertical`; el motor tiene rutas de disposición para modos de lectura adicionales. `PJNV.FVlJ`, `ev_ca3effecc9cda7c87f25ab908813665fbceb59db73b27a88e733d5d3466147c2`. | P1: desplazamiento vertical con páginas visibles acotadas; el recurso no demuestra por sí solo todos los gestos. |
| Ir a primera, última o página concreta | Acciones remotas y recursos de navegación; `Slider` presente. `reader-inventory.json` y `ev_034074cfe752da9d97dfa069fd7ba444d90cea00e5304047f238661eb586f7f5`. | P0: indicador y salto numérico/slider. |
| Teclado, mando y control remoto | `dispatchKeyEvent` deriva a `V0`; `C1` gestiona repetición por dispositivo/tecla. XML `reader_remote_action.xml` declara siguiente/anterior, libros, zoom y extremos. `ev_2ba44c6e9b5118eecfb28e2c0b74dadecdafb9dfdf62c17120aee5a56636513d`, `ev_c2e86fcd5792c83be5c3b160fd4aa6a7a82cf48baf98ec196281eb16d45d1160`. `V0` requirió representación smali, `ev_6970ba9041b43fb8666d84b3c957d36a03bc1a4ff31505eb625aacb63b7d6863`. | P0: teclas de página/dirección y volumen opcional. No interceptar volumen sin ajuste. |
| Gestos de toque/deslizamiento y zoom | Recursos `tap_gesture`, `slide_gesture`, `double_tap_scroll`, zoom y acciones remotas; `YCP0.onTouchEvent` existe. | P0: cambio de página, doble toque y pinza. Personalización completa P2. No se inspeccionó cada ruta de gestos. |
| Bloquear giro | Menú recuperado con bloqueo/desbloqueo y llamada a `s1`. `ev_b130826f7858ef6e54bb4f06f55334d239c5e17b0d8dd1711159d580fa9bcfdd`. | P0: bloqueo opcional; conservar posición al recrear actividad. |
| Recortar bordes | Menú real alterna recorte/libre y recarga; ajuste `crop_image` desactivado inicialmente. `ev_b130826f7858ef6e54bb4f06f55334d239c5e17b0d8dd1711159d580fa9bcfdd`, `ev_4c367ffb03aa365ea38c478bed9c5ea28ac04bd7064d693503f65a2df892dfab`. | P1: cálculo al decodificar, con posibilidad de desactivarlo; evitar un escaneo por frame. |
| Corrección de color | Menú abre diálogo `Nd7Q`, que tiene controles `SeekBar`; ajuste `auto_color` desactivado inicialmente. `ev_b130826f7858ef6e54bb4f06f55334d239c5e17b0d8dd1711159d580fa9bcfdd`, `ev_ed1a24a52a4038e1d70e3945999f332921071b748232d0cbdfb22c8c7ce9e6e4`, `ev_1d3ece9858a58a45b201b3365657c54218618470c225bdda6af99756b8a297ae`. | P2: no necesaria para la primera base; no prometer igual algoritmo. |
| Color de fondo automático | Ajuste `auto_background`, inicialmente desactivado, y parámetro transmitido al decodificador. `ev_3cd7e47d29ae725575c41aa8f51cd0e6a42646e684ca51fba62d5b6d635d1ab4`, `ev_ded4694e568545992a1d92ccb6b15bdfab52db1749ea837c44788bc537315f1e`. | P0: fondo oscuro sencillo; automático P2. |
| Índice interno | Menú muestra tabla de contenidos únicamente cuando el motor dispone de la lista. `ev_b130826f7858ef6e54bb4f06f55334d239c5e17b0d8dd1711159d580fa9bcfdd`. | P1: metadatos/index cuando el formato lo permita; no inventar un índice si falta. |
| Miniaturas y vista previa de páginas | `onCreate` enlaza `page_thumbs` con RecyclerView y el slider; existen altura de miniatura y opciones de proporción. `ev_758104cfc8124e908f8cb16b74224361452f791da41f27d3124f3fdfd609c1a4`, `ev_ded4694e568545992a1d92ccb6b15bdfab52db1749ea837c44788bc537315f1e`. | P1: generar pequeñas miniaturas a demanda; no decodificar el archivo entero para abrir el lector. |
| Mantener pantalla encendida | `keep_screen_on` devuelve `true` por defecto y `onCreate` aplica la bandera 128 (`FLAG_KEEP_SCREEN_ON`). `ev_1bf6a7a8da64ca97a523e160777a1aabf5fc60922370037febd026a6079ed373`, `ev_758104cfc8124e908f8cb16b74224361452f791da41f27d3124f3fdfd609c1a4`. | P0: opción voluntaria, inicialmente desactivada para respetar el apagado del sistema. |
| Exportar página y abrir siguiente libro | Recursos y acciones XML identificados. | P1: acciones explícitas del usuario. No se siguió toda su implementación. |
| Buscar/ordenar/continuar y estado leído | Recursos de búsqueda por carpeta, ordenación por nombre/fecha/tamaño/acceso, leídos/no leídos y reanudación. | P0: búsqueda local, orden natural, continuar y leído. Estadísticas/listas avanzadas P2. Solo evidencia de recursos en esta revisión. |

Las prioridades son decisiones de diseño. No indican que las funciones ya estén implementadas en el nuevo proyecto; su estado real debe consultarse en el README y las pruebas de ese proyecto.

## Formatos y almacenamiento

El manifiesto declara MIME de CBZ/ZIP, CBR/RAR, CB7/7z, CBT/TAR y PDF. La configuración empaquetada incluye abrir archivos comprimidos, carpetas de imágenes y PDFs. Esto demuestra intención de apertura; no demuestra compatibilidad con todas las variantes, RAR5, archivos cifrados, ZIP64 o paquetes dañados.

El APK contiene `libpdfium.so` y una clase `pdfium.PdfiumCore`. Contiene asimismo un puente nativo `libnative.so`; `u0.Vk8L.fc1G` envía la apertura de un miembro al puente nativo o a la implementación del origen remoto. Evidencia: `ev_2504c2970736ff5b1522221a22b08e6b9000121437a986a87e8356dfb78cab75`. No se decompiló `libnative.so`: el algoritmo exacto de archivo comprimido y sus límites permanecen desconocidos.

Para la base propia, ZIP/CBZ y PDF pueden aprovechar funciones de Java y Android, evitando arrastrar el PDFium del APK. RAR/7z requieren una implementación independiente compatible y revisión de su licencia. No conviene anunciar esos formatos hasta probar corpus reales. Carpetas seleccionadas mediante Storage Access Framework evitan permisos globales de almacenamiento; la disponibilidad y semántica de escritura dependen del proveedor.

## Caché, precarga y memoria

1. **Presupuesto en megapíxeles.** `settings.BaGg.Fi0V` devuelve la preferencia explícita `reader_cache_size`; si falta, usa RAM total y estado `lowMemory`: 12 MP para dispositivos de hasta 1 GiB, 14 para hasta 2 GiB, 16 si hay baja memoria o hasta 3 GiB, y 24 en el resto habitual. `PJNV` multiplica por un millón. Evidencias: `ev_b514a205234369509583a4271be16d943bf7e7a73bc363927303b5631c1d5550`, `ev_ded4694e568545992a1d92ccb6b15bdfab52db1749ea837c44788bc537315f1e`.
2. **Consecuencia inferida.** 24 MP en ARGB de cuatro bytes supone aproximadamente 96 MB de píxeles, antes de copias, superficies, metadatos y buffers. Es una estimación del presupuesto, no una medición del heap. Un límite explícito de bytes calculado con el heap de la app es más predecible que RAM total.
3. **Ventana de lectura y prioridad de dirección.** `PJNV.D0kt` aumenta la ventana al mantener la dirección, limita sus extremos al documento, prioriza el lado por el que se avanza, decodifica páginas cercanas y solicita solo preparación de archivos para parte de la zona más alejada. Devuelve elementos fuera de ventana a colas/reutilización. Evidencia: `ev_4e5a9315cbe32e5a7478f7544a56427fb12751b5da89303119136db69e35daef`. `PJNV.uR1Z` asigna una extensión de precarga de cuatro para determinados casos de origen/formato, pero la clasificación exacta necesita seguir las constantes. `ev_18967ab0280d4b566f92e201f73e15ab9de8f7532133f5a5829a43fe32af5db1`.
4. **Reutilización y zoom.** `jDQA` conserva una página ya decodificada si su escala/presupuesto lo permiten y evita duplicar solicitudes en curso; usa archivo preparado cuando existe y, si no, solicita la lectura al origen. `oDd5` compara presupuesto y escala. Evidencias: `ev_128b6c5b9a7ab863affc80fabf2c42c86282c4ec99764167389b8567998fcd01`, `ev_ac578916b844e70b0e35b2bccea11b5c531b1d4a2a8cc220d7febf62b3dca669`.
5. **Páginas continuas.** `FVlJ` distribuye el presupuesto entre páginas de la ventana visible/próxima y retira imágenes fuera de ella. `ev_ca3effecc9cda7c87f25ab908813665fbceb59db73b27a88e733d5d3466147c2`. El Java recuperado contiene reconstrucciones delicadas de variables; no debe convertirse en un informe de errores sin corroborar smali y ejecución.
6. **Liberación al detenerse.** `rfT3.onStop` llama a `n1(false,true)`, que termina en `PJNV.xIgx` liberando las entradas decodificadas de su array; también elimina mensajes de un handler. Evidencias: `ev_dfae9f48f1a3359858828ea409dff0dbceacb111ca846411411745cf76ddc2a7`, `ev_446e5ad4d1ba7c5043fae803db2009762c50ef703f223134120f6e2d0cc3d03a`, `ev_cc42ebb89332cb65bdabb4611b75458f7f78bce37fed9de1f5df92ad28fd32c1`. Por tanto, sería incorrecto afirmar que el APK no libera memoria al salir.
7. **Formato de píxel condicionado.** `r0.wsAs.g0Bl` y `FWfT` eligen RGB_565/ARGB_8888 según API, MIME, configuración y espacio de color; hay un ajuste de compatibilidad para RGB565. Evidencias: `ev_61fb58adbea7595c05591c04277e5cd370da8121bb6cff52929982199b32384d`, `ev_a63f56adcc4053d818762c7913ccb155bee1eca85359351e91f10e258bae280c`, `ev_6d9821583deffa7ebfae5403ce966452686e666413ec51e4e2ab7657a80330f2`. No copiar la selección indiscriminadamente: RGB565 reduce memoria a costa de precisión de color.

La implementación propia debería comenzar con una página visible, una siguiente preparada, decodificación a resolución útil, límite de memoria en bytes, cancelación al cambiar de libro y liberación al ocultarse. Aumentar precarga o calidad exige una medición que justifique el coste.

## Hilos, tareas periódicas y batería

`r0.wsAs` crea **tres** `ThreadPoolExecutor`, cada uno con núcleo `min(8,max(4,availableProcessors()))`, máximo declarado 100 y `LinkedBlockingQueue` sin capacidad explícita, además de un `ScheduledThreadPoolExecutor` con ese mismo núcleo. La cifra 100 no demuestra 100 hilos activos: con una cola no acotada el ejecutor normalmente encola tras ocupar sus hilos núcleo. Sí demuestra un presupuesto potencial de concurrencia alto para un lector. Evidencias: constructor `ev_43bc19d1c43b5bf70e8f0bac7843bab0c01ffb06ea1e687cd706b40af483fbf6`, inicialización smali `ev_a7a74a16383f30772dea30ffd7bab8fdc571f12bb84b60facc85dfd08343a9f8`.

Un runnable llama a `moS4(false)` y se reprograma a los cinco minutos. `moS4` recorre dos colas de manejadores, comprueba su estado y cierra los que cumplen la condición. Evidencias: `ev_ee62d253120ad16d0bbb7867f8d870ac55ff51eb3b129c00680b668da50ee83e`, `ev_2d615c6b8059217a3b951e949349f9a7d954115d97358a0ec1588ab76a37ff4b`.

**Inferencia y límite:** esta tarea puede ejecutar trabajo innecesario mientras el proceso sigue vivo; no demuestra que despierte un dispositivo suspendido, que mantenga CPU bloqueada ni un gasto energético concreto. El permiso WAKE_LOCK del manifiesto tampoco demuestra por sí solo su adquisición. Hacen falta Battery Historian/Perfetto y medición en dispositivo para atribuir consumo.

Para la nueva app: un trabajador de lectura secuencial y trabajo de red acotado; no escaneo periódico ni animación continua durante una página quieta. Sincronización explícita o programada con restricciones de red/batería, dejando que Android agrupe trabajo. Mantener pantalla encendida solo a petición. Reducir concurrencia es una hipótesis de eficiencia que se debe comprobar frente a latencia real.

## Tamaño y dependencias empaquetadas

El ZIP contiene 1.103 entradas, dos DEX y nueve bibliotecas nativas, todas bajo `arm64-v8a`. Los tamaños siguientes son descomprimidos; no equivalen al tamaño de descarga ni memoria residente:

| Grupo | Tamaño |
|---|---:|
| `libmega.so` | 17.342.320 bytes |
| `libpdfium.so` | 4.972.504 bytes |
| `libnative.so` | 1.177.640 bytes |
| Cuatro bibliotecas Crashlytics | 1.323.376 bytes |
| GIF nativo | 42.464 bytes |
| Contador DataStore nativo | 7.112 bytes |
| DEX principal y secundario | 6.943.388 bytes |

Hay perfiles de arranque `baseline.prof`/`baseline.profm`. Su presencia es un recurso de optimización ya existente; no debe describirse el original como carente de toda optimización.

El mayor binario es MEGA: conservar una integración oficial equivalente puede aumentar el tamaño de una implementación propia. Quitar telemetría/anuncios y usar PDF del sistema reduce dependencias, pero no prueba por sí solo menor batería ni garantiza que una API del sistema iguale todas las funciones de PDFium. La biblioteca MEGA y sus términos se estudian en el informe de cloud.

## Hallazgos para AI Slop Cleaner

El APK no es el código fuente de la nueva aplicación; no se propone una limpieza directa de sus binarios.

- `r0.wsAs.moS4` ignora `IOException` al cerrar manejadores. Clasificación local: **masking fallback slop**, porque esa ruta suprime la evidencia del fallo. No se ha demostrado pérdida de datos; las clases concretas y sus contratos de cierre no se recorrieron completamente. En código propio se debe conservar un error útil sin registrar credenciales.
- `u0.Vk8L.fc1G` cambia un `FileOutputStream` a `null` si no puede crearlo y sigue con la apertura del origen. Clasificación: **ambigua** sin seguir el contrato completo del caché; podría ser caché opcional válida o una omisión silenciosa. No declararlo error global ni reproducirlo por imitación. En la app propia: distinguir explícitamente caché prescindible de escritura de progreso imprescindible.
- Selección RGB565/ARGB y espacio de color: **compatibilidad fundamentada** en límites de API/decodificador, aunque no se ejecutó una matriz de dispositivos. La nueva app necesita sus propias pruebas de imágenes, no heredar ramas sin justificación.
- No se trasladaron algoritmos, recursos visuales, nombres de marca ni implementaciones decompiladas a la nueva app.

## Preguntas cerradas y pendientes

| Pregunta | Estado |
|---|---|
| Identidad/versión y superficie del lector | Respondida mediante inventario estático; autenticidad de firma no verificada. |
| QoL de posición, marcadores, RTL, giro, recorte y caché | Respondida estáticamente para los métodos citados. |
| Formatos exactos, cifrado y variantes de archivo | Parcial: MIME, configuración y puente nativo; no corpus funcional ni análisis nativo. |
| Precarga, presupuesto y liberación | Respondida estáticamente en rutas citadas; sin medición de heap. |
| Hilos y mantenimiento periódico | Respondida estáticamente; hilos reales y trabajo efectivo no medidos. |
| Consumo de batería, latencia, calidad de imagen | Pendiente de hardware, corpus y perfiles comparables. |
| Todas las personalizaciones de gestos, TOC/exportación y biblioteca | Parcial: inventario/recursos y algunas entradas; no se siguieron todas sus rutas. |
| Sincronización cloud | Fuera de este subinforme; consultar el análisis cloud. |

No se han verificado firma del APK, seguridad completa, licencias de todas sus dependencias, cobertura reflectiva/dinámica ni contenido de sus bibliotecas nativas. No se han realizado llamadas de red desde la aplicación ni usado cuentas del usuario.
