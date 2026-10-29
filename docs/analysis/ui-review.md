# Revisión independiente de la interfaz nueva

29 de octubre de 2026. Revisor distinto del autor. Alcance: `MainActivity`, construcción de vistas de `ReaderActivity`, `Ui`, `CoverCache`, sus llamadas, recursos y pruebas de UI existentes. No se ha editado producción, iniciado emulador/Docker ni utilizado una cuenta. Se han inspeccionado las capturas propias ya existentes `own-grid.png` y `own-reader.png`; los colores planos pertenecen a fixtures, no a portadas simuladas del producto.

## Hallazgos comunicados al integrador

### P2 — La portada CBZ no respeta las dimensiones anunciadas y puede superar toda la caché

Ubicación: `CoverCache.Load.run`, llamada `source.render(0, 192, 288)`; `PageSource.render` y `ComicFiles.sampleSize`.

El renderer CBZ conserva resolución para el lector: su muestreo depende de ambos ejes y de un máximo de tres megapíxeles. Solicitar 192×288 no impone esas dimensiones a su bitmap. La caché de portadas usa directamente el resultado y su límite es 8 MiB. Una página válida muy apaisada puede producir un bitmap de 12 MB, ser expulsada inmediatamente por superar la LRU y decodificarse otra vez en cada bind. También aumenta el coste de subir la imagen a la GPU. Incluso una portada habitual 1200×1800 produce 300×450, por encima de lo documentado.

Reproducción ejecutada con JShell/JDK 21 contra el `ComicFiles.class` real previamente compilado, sin lanzar Gradle ni Android:

| Página original | `sampleSize(...,192,288)` | Dimensiones resultantes previstas |
| --- | --- | --- |
| 1200×1800 | 4 | 300×450 |
| 4096×128 | 1 | 4096×128 |
| 100000×30 | 1 | 100000×30, unos 12 MB ARGB |

La tabla ejecuta el cálculo de muestreo; no se presenta como medición de memoria en Android. Los fixtures actuales son imágenes pequeñas, por lo que su assert ≤192×288 no cubre esta condición.

Reparación acotada: limitar el bitmap final de `CoverCache` a 192×288 manteniendo aspecto, o realizar muestreo estricto específico de miniaturas. No degradar el render del lector cambiando su helper compartido. Si se crea una imagen reducida, liberar solamente el bitmap intermedio nuevo y no compartido. Añadir una portada de tamaño habitual y otra muy apaisada al caso de dimensiones; comprobar que la imagen final cabe en la caché y que volver a enlazarla no exige otra decodificación.

### P2 — El deslizador cambia su posición por accesibilidad/teclado sin cambiar la página

Ubicación: listener del `SeekBar` en `ReaderActivity.onCreate`, `onProgressChanged` y `onStopTrackingTouch`.

El cambio de progreso iniciado por el usuario solo cambia el texto a «Ir a…». La petición de página se realiza exclusivamente al terminar una interacción táctil. Una acción accesible `ACTION_SET_PROGRESS`, scroll accesible o flechas del teclado modifica el progreso con `fromUser=true`, pero no llama a `onStopTrackingTouch`. El control y su anuncio indican otra posición mientras el cómic sigue en la página anterior.

El contrato se ha corroborado en las implementaciones oficiales [SeekBar](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/widget/SeekBar.java) y [AbsSeekBar](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/widget/AbsSeekBar.java): el seguimiento touch y las rutas de tecla/accesibilidad son distintos. No se ha ejecutado aquí TalkBack ni la acción sobre un dispositivo.

Reparación mínima: distinguir si hay arrastre táctil. Durante él, mantener la vista previa del contador y pedir la página al soltar; para un cambio `fromUser` fuera de arrastre, pedirla inmediatamente. Así se conserva la decodificación acotada al arrastrar. Verificación instrumentada: lanzar `ACTION_SET_PROGRESS` sobre el deslizador y comprobar la descripción de la página mostrada; repetir hacia atrás y conservar los casos de manga existentes. No basta con comprobar el número del slider.

## Conservación de funciones y límites de recursos

No se han encontrado otros P1/P2 estáticos claros en el alcance leído. Las siguientes conclusiones se basan en las llamadas actuales, no en una nueva ejecución del APK:

- Los filtros utilizan los estados reales y la búsqueda conserva el filtro seleccionado. Lista/cuadrícula usan tipos distintos en el adaptador. Importación local, menús de cada cómic, favoritos, terminado, borrado confirmado, exportación/importación de progreso, ajustes, selección de proveedor, descargas y sincronización conservan rutas de ejecución. «Descargas» representa las copias locales existentes.
- La UI no presenta una cuenta como conectada a partir de un icono o subtítulo: MEGA consulta el adaptador; Drive inicia autorización real. La disponibilidad nativa depende de la integración final que está preparando el integrador. El pase visual no demuestra acceso a cuentas reales.
- `CoverCache` mantiene un solo worker, cola de 32, referencia débil a cada celda y comparación de ID antes de publicar. Un resultado antiguo no debe colocarse en una celda que representa otro cómic. No recicla bitmaps compartidos con las vistas al expulsarlos de la LRU. El límite real de cada miniatura requiere reparar el primer hallazgo.
- Al pausar se vacía la cola de portadas; al reanudar, `refresh` notifica al adaptador y permite volver a solicitar las que quedaron en placeholder. Al destruir se cierra el worker y se vacía la caché. Una decodificación nativa que ya comenzó puede terminar tras la pausa; no hay cancelación inmediata prometida. La saturación usa descarte de trabajo antiguo y no genera una cola ilimitada. No se han medido saturación con muchas celdas visibles ni batería real.
- Los controles con iconos tienen descripción, tooltip y tamaño de 48 dp. Las filas agrupan título/estado sin exponer dos veces sus hijos al lector de pantalla. `PageView` sigue distinguiendo avance lógico accesible de la inversión física en manga. El slider es la excepción funcional señalada arriba.
- El lector conserva el mismo `PageSource` en su worker, cierre secuencial, generación de renders, caché con límite y guardado mediante `Library.IO`. Las barras superpuestas no eliminan zoom, marcadores, navegación ni persistencia. No se ha atribuido un fallo de memoria a este layout basándose solo en su caché por actividad.

## Limpieza y verificación

AI SLOP CLEANUP REPORT

- Scope: interfaz nueva y caché de portadas, sin refactor de servicios, protocolo ni SDK.
- Behavior Lock: se leyeron las pruebas existentes y los resultados previos del autor (29 JVM y 7 grupos Android); no se reejecutaron ni se atribuyen como pruebas de este revisor. La reproducción independiente de muestreo sí se ejecutó sobre la clase compilada.
- Cleanup Plan: reparar primero el tamaño real de portada; reparar la ruta accesible del slider; ampliar únicamente los casos que habrían detectado ambos fallos y ejecutar la validación integrada.
- Fallback Findings: placeholder de portada con `Log.w` y acceso al cómic conservado → degradación acotada y fundamentada. Error de una portada no declara que el documento se haya recuperado. Descarte por saturación → política de trabajo especulativo acotado, pendiente de medición de estrés; no autentica ni inventa contenido. Compatibilidad Atrás API 26–32 frente a dispatcher API 33+ → frontera de plataforma explícita. No se observaron proveedores o credenciales simulados añadidos por este cambio.
- UI/Design Findings: blanco, naranja y cabeceras oscuras están respaldados por la observación original documentada por el autor. No se formulan objeciones subjetivas a esa paleta. Se revisaron funcionalidad y accesibilidad.
- Passes Completed: revisión independiente y clasificación; no se realizaron pases de modificación por separación de autor/revisor.
- Quality Gates: inspección estática completada; reproducción JVM de muestreo confirma el hallazgo; builds, instrumentación, TalkBack, estrés de cola y medición de batería N/A para este pase. Pendientes las regresiones de ambos P2 y la validación final de integración.
- Changed Files: solo este informe.
- Remaining Risks: los dos P2 deben repararse/verificarse por el integrador; no se ha probado hardware ARM64 real ni cambios de escala de fuente grandes.

Ficheros leídos para fijar el estado de esta revisión:

| Archivo | SHA-256 |
| --- | --- |
| `CoverCache.java` | `e9512343b3432befd12006f400fa7e1047a2c7cae69a32599d4a777527aa9eab` |
| `ReaderActivity.java` | `44882f3775ab345450cc271ff7d276274b8ba862e2b12e9f375d3087d4403e02` |
| `MainActivity.java` | `401d1d0def8ddd05865a34e0905b6a00fa16681cd939570abee94ea02535834d` |
| `Ui.java` | `394a8ddf5c6524b4e183f5f38ae1ee4e9245d0b27be71760b001f0a6829491db` |

## Seguimiento de cierre — 29 de octubre de 2026

Se releen únicamente ambas reparaciones y sus regresiones nuevas en `SmokeInstrumentation`. Los dos P2 anteriores quedan **corregidos por inspección de código**, sin bloqueos nuevos en estas rutas:

- `CoverCache` reduce el resultado nuevo de `PageSource.render` a un máximo de 192×288 conservando aspecto y sin ampliar imágenes pequeñas. El reciclado afecta exclusivamente al bitmap intermedio recién decodificado, antes de insertarlo en la LRU o entregarlo a una vista; la ruta que obtiene un bitmap compartido de memoria no lo recicla. `testLargeCovers` importa fixtures CBZ de 1200×1800 y 4096×128, espera la imagen real y comprueba sus dimensiones finales; cierra la caché y elimina cada copia de prueba.
- `ReaderActivity` mantiene `trackingSeek`: un cambio iniciado por el usuario fuera de arrastre llama a `request(progress)`; durante el arrastre conserva la vista previa y carga al soltar. Los cambios programáticos del slider no vuelven a pedir una página. La regresión usa `ACTION_SET_PROGRESS` para ir a la página 2 y regresar a la 1, y comprueba la descripción de la página mostrada en ambos casos, con manga activado. Conserva las pruebas anteriores de avance lógico, recreación y progreso remoto.

Este seguimiento sustituye el estado pendiente de los dos hallazgos, sin borrar su evidencia inicial. No se han ejecutado compilaciones, instrumentación, emulador ni Docker por este revisor; la validación runtime integrada corresponde al integrador. Quedan fuera de este cierre las mediciones de batería, estrés de cola y hardware ARM64 real.
