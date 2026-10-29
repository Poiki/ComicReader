# Revisión independiente del lector y biblioteca

Alcance: `ReaderActivity.java`, `PageView.java`, `MainActivity.java`; lectura de `Library`, `PageSource`, `Importer`, `ComicFiles`, `Cloud`, `SyncJob`, `Ui` y manifest únicamente para comprobar sus contratos. Revisión del 29 de octubre de 2026. No se ha modificado implementación. Las integraciones nativas de nube, en desarrollo paralelo, no se consideran una ausencia ni un hallazgo.

La implementación cambió durante la revisión. Se distinguen hallazgos inicialmente comunicados y ya corregidos en la relectura de los pendientes en esa instantánea. Los números de línea corresponden a esa relectura y deben acompañarse del nombre del método.

## Pendientes concretos

### P2 — Un lector pausado conserva un estado anterior a la sincronización

Ubicación: `ReaderActivity.onResume` (90–93), `save` (153–162), `Library.update`.

Secuencia: abrir un cómic; mandar la app a segundo plano; permitir que `SyncJob` fusione una actualización de otro dispositivo con favoritos/marcadores/estado distintos; volver al lector retenido y pasar una página. `onResume` vuelve a renderizar la variable `page`, pero no vuelve a leer el estado de biblioteca. `save` envía `state.favorite`, `state.completed` y el conjunto de marcadores cargados antes de la pausa. `Library.update` utiliza la revisión actual para crear una revisión local posterior, pero sustituye todos esos campos por los valores antiguos enviados. Pasar una página puede, por tanto, deshacer un favorito o marcador remoto que el usuario no ha editado aquí.

La corrección anterior que evita marcar dirty al volver a renderizar una página elimina la sobrescritura sin interacción. Queda la sobrescritura del resto de campos cuando sí se cambia la página. Recargar/reconciliar al reanudar y preservar en escritura los campos que este lector no haya cambiado. Verificación propuesta: pausar, fusionar una marca/favorito remoto desde el almacenamiento, reanudar, avanzar y comprobar que se conservan.

### P2 — Reintento de medición puede mantener ocupado el hilo principal

Ubicación: `ReaderActivity.request` (111–112 aproximadamente; rama `width == 1 || height == 1`) y creación de `image` con altura cero + peso uno.

Si las barras superior/inferior consumen la altura disponible —por ejemplo una ventana muy baja o fuente grande—, el área de imagen puede permanecer con altura cero. La rama transforma cero en uno, hace `image.post`, vuelve a llamar a `request`, obtiene la misma dimensión y publica otro mensaje. No hay un cambio de layout ni temporización que corte ese ciclo. Puede mantener una cola activa y consumo de CPU mientras la ventana siga sin espacio.

Esperar a una notificación real de layout con dimensión válida, con un único listener pendiente y su eliminación al destruirse. La ausencia de espacio no debe crear un bucle de mensajes. Verificación propuesta: medir el área de lectura con altura cero, solicitar página y comprobar que no se encola continuamente; después asignar una altura válida y comprobar un único render.

### P2 — La acción accesible de avanzar retrocede en modo manga

Ubicación: `PageView.performAccessibilityAction` (97–98), `ReaderActivity.turn` (98).

Las acciones accesibles `ACTION_SCROLL_FORWARD/BACKWARD` llaman al mismo método que representa una dirección física de toque/deslizamiento. `turn` invierte esa dirección cuando manga está activo. Así, la acción semántica de avanzar pasa a `advance(-1)`; desde la primera página se anuncia primera página y no se avanza. Los botones visibles Anterior/Siguiente ya usan avance lógico y no presentan esta inversión.

Separar en ese punto el avance accesible lógico de la dirección física, o convertir la acción accesible antes de llamar a `turn`. Verificación propuesta: activar manga, ejecutar `ACTION_SCROLL_FORWARD` sobre el lector en la primera página y comprobar que se muestra la segunda; backward debe regresar a la primera.

### P2 — Rotar durante una importación del selector cancela la petición sin recuperarla

Ubicación: `MainActivity.importUris`, `task`, `onDestroy` (283), ausencia de estado guardado para la operación; `ComicFiles.copy` confirma que la interrupción cancela la copia.

Una importación iniciada mediante el selector se ejecuta en el executor de esa instancia. La recreación por rotación llama a `shutdownNow`, que interrumpe la copia; la nueva instancia no conserva los URIs ni la operación pendiente. El callback de error de la instancia anterior se descarta si está destruida. La biblioteca nueva queda en reposo y el usuario tiene que elegir el documento otra vez. Los originales y la copia temporal están protegidos por el finally; el problema es la pérdida silenciosa de la operación autorizada, no la destrucción del original.

Conservar/reanudar la operación durante recreación o presentar su cancelación explícitamente con forma de reintentar. Para ACTION_VIEW hay además una reimportación automática en cada onCreate, pues no se consume el intent ni se comprueba `saved`; esto puede volver a copiar/hashar el mismo archivo tras girar. Verificación propuesta: proveedor o stream lento, iniciar importación, recrear y comprobar resultado o cancelación recuperable visible, sin copia duplicada.

## Corregidos durante la revisión

- **P1 — La biblioteca fallaba al pintar el primer elemento.** El constructor de `ArrayAdapter` usaba `simple_list_item_2` sin indicar `android.R.id.text1`, y el override llamaba a `super.getView`. ArrayAdapter intentaba convertir el contenedor en TextView. La relectura confirma que ahora se usa el overload con `android.R.id.text1`. El contrato se comprobó también en el [código fuente AOSP de ArrayAdapter](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/widget/ArrayAdapter.java).
- **P1 — Recreación podía cargar antes del guardado de la instancia anterior.** Originalmente el guardado estaba detrás del render/prefetch y la nueva instancia cargaba en otro executor. La relectura confirma guardado/carga de estado mediante `Library.IO`, snapshot de página/marcas/completado en `onSaveInstanceState` y que renderizar la misma página ya no marca dirty. Esto corrige la secuencia inicialmente comunicada; no sustituye la prueba instrumentada de recreación.

## Lo comprobado y los límites

Se trazaron todos los usos de `PageView.Actions`, render, avance, guardado, reanudación, parada y destrucción, junto con los límites/cierre de `PageSource`. El ticket comprobado en UI evita mostrar un render de una petición anterior tras un swipe nuevo; el executor elimina renders pendientes anteriores y conserva como máximo el trabajo activo y el último pendiente. El cierre del archivo se encola detrás del render y no se observó un cierre concurrente de ese mismo `PageSource`. La relectura confirma `generation` y `active` volátiles y dirección de precarga capturada.

No se ejecutaron pruebas nuevas en esta revisión de solo lectura. Las pruebas JVM del cliente WebDAV pertenecen a otra tarea y no validan estas secuencias Android. Las pruebas instrumentadas del padre están en marcha; no se les atribuye un resultado aquí. No se midió batería, presión de memoria tras rotaciones repetidas, ni se usó TalkBack real. No se declara un fallo de memoria basándose sólo en el límite de caché por actividad.

Revisión inicial terminada dentro del alcance solicitado. El seguimiento siguiente sustituye sus estados pendientes; conserva arriba las secuencias que motivaron las correcciones.

## Seguimiento del pase final de limpieza

Alcance ampliado únicamente a los archivos solicitados por el padre: `MainActivity`, `ReaderActivity`, `PageView`, `Cloud`, `Secrets`; consulta de `DriveAuth` y ambas variantes de `MegaClient` para validar llamadas. No se ha editado implementación ni añadido features. Durante esta segunda lectura se comunicaron tres fallos concretos, que el padre corrigió y se volvieron a leer.

### Corregido y confirmado por relectura

| Hallazgo | Estado final de la instantánea releída |
|---|---|
| ArrayAdapter de biblioteca | Conserva `android.R.id.text1`; no se reintrodujo el fallo P1. |
| Guardado y carga tras recreación | Conserva `Library.IO`, guardado de instancia y dirty sólo al cambiar página. |
| Reintento de imagen con altura cero | El post recursivo desapareció. Ahora vuelve sin encolar y un listener de cambio real de layout reintenta al obtener dimensiones positivas. |
| Acción accesible en manga | `PageView.Actions` distingue `advance` lógico de `turn` físico; scroll accesible usa `advance`. |
| Rotación durante operación de biblioteca | `task` bloquea orientación mientras trabaja y restaura en éxito/error. ACTION_VIEW comprueba `saved == null`; se elimina la reimportación automática por recreación ordinaria. Esto no pretende conservar tareas ante muerte del proceso o cualquier cambio de configuración. |
| Error de página precargada presentado como error de la página actual | Confirmado como P2 y corregido durante esta lectura: la precarga tiene catch propio con diagnóstico `Log.w`. Un fallo especulativo ya no vacía la caché buena ni abre un diálogo; la petición explícita de esa página mantiene su error visible. Clasificación: fail-safe acotado, conserva evidencia y no declara éxito de la página fallida. |
| MEGA ya conectado no se convertía en proveedor activo | La rama `loggedIn()` ahora llama `useProvider("mega")` antes de navegar; sincronizar utiliza el proveedor que se acaba de elegir. |
| Elegir WebDAV con Drive activo intentaba crear un cliente sin endpoint | `webDavMenu` ahora comprueba específicamente el endpoint WebDAV; abre configuración cuando falta, aunque otro proveedor esté activo. |

### P2 restante: sincronización mientras el lector permanece activo

La recarga de estado en `ReaderActivity.onResume` corrige el caso normal de pausa/reanudación. Sin embargo, `SyncJob` puede fusionar estado mientras el lector está activo: la biblioteca programa un job con latencia mínima de 30 segundos, el usuario abre el lector antes de que arranque y el job después fusiona un favorito o marcador remoto. La siguiente escritura del lector sigue pasando todos los campos de su snapshot anterior a `Library.update`. El favorito no se modifica desde el lector, pero aun así puede sobrescribir el favorito recién sincronizado. Es el caso concurrente del P2 inicial, no una petición de sincronización adicional.

La reparación debe preservar los campos que el lector no haya editado al actualizar el almacenamiento, o reconciliar el snapshot contra el estado vigente dentro de la operación de guardado. Verificación mínima: conservar el lector activo, fusionar un favorito/marcador remoto con revisión superior, pasar una página y comprobar que los campos ajenos al cambio se conservan. No se ha simulado esta secuencia en dispositivo en esta revisión.

Además, la recarga al reanudar comparte `generation` con los renders. Si se mantiene la carga `Library.IO` pendiente y durante ella se produce un request por layout, tecla o toque, ese request incrementa `generation` y el callback de carga descarta el estado nuevo. Un ticket independiente o impedir nuevas peticiones hasta completar la recarga evita saltarse ese punto de reconciliación. Se comunicó al padre como parte de la misma frontera de estado/reanudación.

### Energía, colas, errores y credenciales

- El guard de precarga exige ticket vigente, actividad activa, batería fuera del modo ahorro y dispositivo no low-RAM. No se inicia una nueva precarga desde una petición obsoleta o pausada. Una decodificación especulativa que ya empezó puede terminar después de la pausa; no hay cancelación de ese trabajo nativo en curso. No se atribuye una cifra de ahorro de batería sin medirla.
- El último render pendiente sustituye a los anteriores. El render activo no publica una página si su ticket quedó obsoleto. El cierre de `PageSource` sigue en el mismo worker y después de sus renders; no se introdujo un cierre concurrente.
- El cambio de tamaño limpia la caché asociada a dimensiones anteriores. `PageView` reutiliza su RectF de dibujo y mantiene acciones de accesibilidad separadas de la inversión manga.
- `Cloud.sync` selecciona explícitamente Drive/MEGA/WebDAV, registra el error real y sólo actualiza último éxito tras la operación completa. La falta del SDK en la variante base genera un estado y excepciones explícitos; no hay una respuesta cloud inventada que aparente haber sincronizado.
- `Secrets` mantiene AES-GCM con clave Android Keystore, escritura comprobada y error ante fallo de autenticación del cifrado. Las sesiones no se introducen en `Progress`. No se observó un catch que devolviera credenciales vacías después de un error de descifrado. La ausencia de autorización guardada sí representa acceso WebDAV anónimo configurado o no configurado, no un fallo de descifrado ocultado.

No aparecieron nuevos P1 en estas rutas leídas. Este seguimiento no certifica acceso real a cuentas ni el SDK nativo que falta en la compilación base. No se ejecutaron builds/tests adicionales para este pase de revisión; corresponde al padre incorporar la evidencia instrumentada final. Queda comunicado el P2 de snapshot concurrente y el ticket de recarga asociado.
