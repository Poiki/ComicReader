# Quality of life: implementado y pendiente

Esta tabla describe la nueva app. Las funciones observadas en el APK original y su evidencia están en [reader-apk.md](analysis/reader-apk.md); que una función exista allí no la hace parte de esta implementación.

| Función | Estado en esta entrega |
| --- | --- |
| CBZ/ZIP con páginas JPEG, PNG, WebP, GIF estático y BMP; PDF | Implementado. Orden natural; se ignoran recursos ocultos y `__MACOSX`. |
| Reanudar página, conservar marcadores y estado al pausar | Implementado en SQLite; persistencia serializada entre instancias y recuperación en recreación. |
| Biblioteca offline e importación múltiple | Implementado; copias propias identificadas por SHA-256, sin modificar originales. |
| Búsqueda, favoritos, sin leer/en curso/terminados y orden por título | Implementado; marcar terminado es explícito. La primera página se clasifica como sin leer. |
| Estilo próximo al APK, portadas y lista/cuadrícula | Implementado con recursos propios: blanco, gris, naranja, navegación inferior y botón +. Portadas máximas de 192×288 con caché acotada. |
| Carpeta MEGA como biblioteca persistente | Implementado: Aceptar, catálogo por cuenta/handle, subcarpetas, copia de metadatos offline, actualización explícita y apertura/descarga desde la biblioteca. Sin escaneo recursivo ni descarga masiva automáticos. |
| Leídos/sin leer/favoritos en MEGA | Implementado, incluidos archivos no descargados. Tras descargar se vincula al progreso por hash. Las marcas previas a descargar son locales hasta que se conoce el contenido. |
| Claro, oscuro y negro OLED | Implementado en biblioteca, configuración, formularios y controles del lector. No transforma el color de las páginas. |
| Inicio elegible | Biblioteca predeterminada, conexiones/configuración o último cómic incompleto disponible; si falta/terminó, biblioteca. |
| Actualizaciones desde GitHub Releases | Comprobación diaria al abrir y manual; selección de ABI, tamaño/digest, paquete/firma/versión y confirmación del instalador Android. Sin token GitHub ni servicio permanente. |
| Marcadores por página, listado y salto | Implementado; selección de marcador o número/seekbar. |
| Manga RTL | Implementado para gestos/teclas laterales; botones y TalkBack mantienen avance lógico. |
| Zoom/pan, doble toque y ajuste al ancho/página | Implementado. Zoom sobre una imagen reducida; no decodificación por teselas a resolución original. |
| Controles ocultables / inmersivo | Implementado; teclado y volumen optativo. |
| Bloquear giro y brillo solo en ventana | Implementado, sin permisos globales de ajustes. |
| Mantener pantalla encendida | Optativo y desactivado inicialmente. |
| Lectura accesible | Texto de tamaño legible, botones con etiquetas, acciones accesibles siguiente/anterior y búsqueda. Falta auditoría completa con lector de pantalla físico. |
| Precarga limitada y ahorro de batería | Una página; no se inicia precarga en ahorro/low-RAM/pausa. Fallar una precarga conserva la página visible y registra diagnóstico. |
| Drive directo | Código y pruebas de transporte/parser implementados; login real pendiente de tu configuración Cloud y cuenta. |
| MEGA directo + 2FA | SDK oficial compilado e incluido. JNI, IO y rechazo de cuenta inexistente probados en ambas ABIs; login correcto, 2FA, descarga y sync de cuenta real pendientes de prueba. |
| Sincronización de página, terminado, favoritos y marcadores | Implementada como protocolo propio, por proveedor elegido y snapshots por dispositivo. No importa datos privados de CDisplayEx. |
| Exportar/importar progreso | Implementado mediante documento `.crp`, validación completa y transacción. No exporta cuentas ni sesiones. |
| Mostrar errores sin perder lectura local | Implementado. Sin resultados inventados cuando fallan autenticación, cuotas, parsing o TLS. |
| WebDAV HTTPS adicional | Implementado, probado con respuestas sintéticas; servidor real pendiente. |
| CBR/RAR/7z, carpetas de imágenes, escaneo de colecciones | Pendiente. Hace falta elegir codecs/flujo y medir su coste antes de ampliar formatos. |
| Doble página, recorte de márgenes, corrección de color, lectura continua | Pendiente. Se priorizó el núcleo comprobable; no se anuncian como implementados. |
| ComicInfo/índice PDF, transición automática al siguiente volumen | Pendiente. No se infiere orden de series por el nombre. |
| Dropbox, OneDrive, Komga, Kavita y SMB nativos | Inventariados en el APK, pendientes en la nueva app. Algunos pueden exponerse por WebDAV. |

## Límites deliberados

1 GiB por cómic, 64 MiB por imagen comprimida dentro del ZIP, hasta 100 megapíxeles de imagen de origen y 3 megapíxeles decodificados, 10.000 páginas por cómic. No hay streaming de un CBZ remoto ni descargas silenciosas de carpetas: se copia el archivo seleccionado para leer sin red. Esto favorece lectura estable y evita mantener la radio activa durante cada cambio de página, a costa de almacenamiento y espera inicial.

Solo la biblioteca y las sesiones están en el espacio privado de la app; las copias de cómics no están cifradas adicionalmente. Desinstalar borra esos datos locales. Exportar progreso o sincronizar antes de hacerlo; los originales seleccionados desde fuera se conservan.
