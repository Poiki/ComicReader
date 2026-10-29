# Revisión independiente del núcleo y plan

Revisor separado del autor de la implementación. Alcance: `docs/PLAN.md`, `Progress.java`, `ComicFiles.java` y `Library.java` tal como estaban al leerlos; no se ha modificado código de producción. Los demás componentes se estaban construyendo, por lo que los contratos pendientes de sus llamadores se distinguen de fallos ya reproducidos.

## Hallazgos que deben resolverse

### P1 — El presupuesto de decodificación falla en imágenes muy estrechas

`ComicFiles.sampleSize`, líneas 67–68, usa `width / sample` y `height / sample` redondeados hacia abajo. Una dimensión puede convertirse en cero y hacer que el producto deje de reflejar el tamaño que decodificará Android. Caso ejecutado en JVM: `sampleSize(1, 100_000_000, 1080, 1920)` devuelve `2`; estimación con dimensiones redondeadas hacia arriba: **50.000.000 de píxeles**, frente al presupuesto declarado de 6.000.000. Esto puede agotar memoria al abrir un archivo ajeno.

Corrección mínima: calcular el presupuesto con división redondeada hacia arriba y aritmética `long` en ambas dimensiones; probar imagen vertical muy estrecha y panorama muy bajo, además de páginas normales. Mantener el tope después de decidir la escala, no confiar en la compresión del ZIP para limitar memoria.

### P1 — Una revisión remota aceptada puede inutilizar el contador local

`Progress` acepta `Long.MAX_VALUE - 1`; `Library.merge` eleva `clock` hasta esa revisión; `nextRevision` rechaza ese valor. Desde entonces cualquier cambio local de lectura/favorito/marcadores falla, incluso en otros cómics. El formato acotado en bytes no evita este bloqueo.

Reproducción ejecutada: constructor → `encode` → `decode` conserva `9223372036854775806` como revisión válida. La consecuencia sobre SQLite se deduce directamente de `UPDATE clock SET value=MAX(value,?)` y del guard de `nextRevision`; no se ha ejecutado en Android.

Corrección mínima: imponer un máximo de revisión sensato y explícito en el formato externo, suficientemente inferior al desbordamiento, y reservar margen para operaciones locales. Rechazar el documento antes de fusionar/avanzar `clock`, con transacción completa y mensaje visible. No bajar silenciosamente una revisión ni regenerar el dispositivo para esconder el problema. Añadir prueba de rechazo y de que la base sigue editable tras importación fallida.

### P2 — No se comprueba el resultado de las escrituras SQLite

`Library.add` y `put` llaman a `insertWithOnConflict` sin inspeccionar su resultado. Esa API puede devolver `-1` ante un error; `put` puede entonces continuar, confirmar el reloj y devolver un progreso que no se ha guardado. En `add`, una colisión prevista con `CONFLICT_IGNORE` debe distinguirse de un fallo real.

Corrección mínima: usar operaciones que lancen excepción en error y tratar explícitamente la existencia del libro. El llamador debe recibir el fallo y conservar el estado anterior. Probar al menos una escritura/reapertura de base y una transacción que falle; no basta con comprobar que la UI avanzó.

## Contratos que deben cerrar los llamadores

- **Número real de páginas:** el formato externo valida rangos internos, pero puede declarar un total distinto al archivo cuyo hash usa. `Library.add` conserva cualquier estado previo y `update` reutiliza `current.pages`. Al importar/abrir, confrontar el total de `PageSource` y definir una reparación explícita con página/marcadores válidos o rechazo. Un `crp` no puede ordenar al lector que abra la página 500 de un archivo de 2 páginas.
- **Identidad y rutas:** `Book` es un record sin validación y `file` concatena `id` y `format`. Los futuros llamadores deben derivar el ID del hash del contenido y restringir formato a `cbz/zip/pdf` antes de tocar rutas; mejor mantener esa invariante en el constructor para que ningún caller futuro la omita. No se ha observado aún una ruta explotable desde una actividad.
- **Archivo y base:** `add` mueve el staging antes de confirmar SQLite. Un fallo de la transacción puede dejar una copia huérfana; un destino existente deja staging para que el caller lo borre. La importación necesita `finally` para su staging y política de limpieza/recuperación segura; nunca borrar un destino preexistente al fallar.
- **Eliminar:** `remove` elimina el archivo antes del registro SQLite. Si falla el segundo paso, la UI puede seguir mostrando un libro sin copia. Detectarlo al listar/abrir y permitir reimportar con progreso conservado. No borrar progreso como compensación.
- **Cancelación:** `copy` detecta interrupción después de `read`. Un proveedor remoto bloqueado requiere cierre/cancelación real desde su dueño; el flag de hilo por sí solo no interrumpe un `InputStream` que no responde.

## Contrato de sincronización mínimo aceptable

1. `Progress.latest` implementa un registro **LWW por cómic completo** ordenado por `(revision, device)`. La revisión lógica permite reinicios/relecturas; no depende de reloj de pared y no fuerza siempre la página mayor.
2. El orden es determinista y el merge debe ser conmutativo, asociativo e idempotente para estados bien formados. Dos estados distintos con la misma revisión y dispositivo deben fallar y dejar la transacción anterior intacta; el código actual ya los rechaza.
3. LWW por cómic puede perder un favorito/marcador offline si otro dispositivo cambia la página y gana. Eso no equivale a fusión por campo. Para la primera base puede ser una limitación explícita; no anunciar «ningún cambio se pierde». Si se requiere preservar campos independientes, hacen falta versiones separadas y pruebas, no un `union` incondicional que impida borrar marcadores.
4. `/comicreader-state-v1/{device}.crp` evita que dos dispositivos normales escriban el mismo objeto. **Son snapshots por dispositivo, no inmutables**, si se actualizan mediante `PUT`. Un journal inmutable necesitaría nombres con generación o contenido.
5. Solo un sync por dispositivo en vuelo. Evitar subir una instantánea antigua después de una nueva; un job cancelado debe cerrar HTTP antes de permitir otro. La importación no cambia el identificador de la instalación ni hace a esta instalación escritora del archivo remoto de otro dispositivo.
6. Cada snapshot puede contener el estado fusionado de varios dispositivos; `state.device` registra al autor de la revisión, no obliga a coincidir con el dueño del archivo de snapshot. No rechazar estados transitivos por esa diferencia.
7. Leer y validar completos todos los documentos aceptados antes de una transacción de merge. Si falla una parte de la nube no presentar éxito global ni borrar registros ausentes. Reintentos son acotados; fallos de auth/certificado/cuota son visibles.
8. La lectura offline y cambios locales siguen funcionando aunque falle la nube. Estado local primero y exportación reproducible; originales no se sobreescriben. La respuesta del puente confirma su recepción, no la persistencia final en MEGA/Drive.
9. Pruebas mínimas del protocolo: roundtrip, truncado, trailing bytes, versión desconocida, duplicados, límites, revisión extrema, página/marcadores inválidos y orden del merge. Prueba de integración: mismo hash en dos bibliotecas con autor distinto y lectura posterior, más fallo de red sin destruir progreso.

## Clasificación de fallbacks y limpieza

No hay fallbacks que oculten autenticación o errores de parsing en estos tres archivos. `decode` local transforma `IOException` en fallo explícito, conserva causa y evita continuar con estado inventado: **fail-safe justificado**. El rechazo de revisión contradictoria y de migración desconocida es también fallo explícito, no slop. `CONFLICT_IGNORE` de `add` es la única ruta que requiere distinguir «ya existe» de error, como arriba.

El plan mantiene un alcance razonable para una base pequeña: Java/plataforma, copia privada, lectores CBZ/PDF y un transporte WebDAV. Las conexiones MEGA/Drive por puente deben describirse como tales en la UI/documentación; no sustituyen una implementación nativa de OAuth/SDK. CBR/7z y procesamiento inteligente se difieren con motivo claro. No se detecta necesidad de nuevas capas ni dependencias para resolver los fallos listados.

## Evidencia de la revisión

- Lectura completa del plan y tres clases; búsqueda de todos sus callers existentes con `rg`.
- Compilación temporal con `javac` y ejecución JVM de las dos reproducciones anteriores; **ambas reproducen**. Los archivos de prueba se guardaron en un directorio temporal ajeno al repositorio; no son regresiones incorporadas por esta revisión.
- No había pruebas JVM/instrumentadas presentes en el instante de la primera lectura. El autor debe añadir y ejecutar los tests antes de declarar completadas las puertas de calidad.
- No se ha ejecutado SQLite en Android, lector ni sincronización real. Los fallos SQLite y el bloqueo de contador se apoyan en código, no en una traza de runtime.

Huellas de los archivos leídos inicialmente:

| Archivo | SHA-256 |
| --- | --- |
| Progress.java | `905a86b99ad68a394b33c8a02b9bed1735058f6c90a5a56cad1a4b53313a6e7a` |
| ComicFiles.java | `d273f0b73c2341cbe7c0741d50cf72816c48a7b5ffb085658011ed1e3efa0cda` |
| Library.java | `8dd30a38b6adb03d910e0c4ac6712db8b02807375b3a70398d70c0a3ed43a0b7` |

Esta revisión no aprueba la implementación final: los hallazgos deben corregirse y verificarse tras los cambios del autor.
