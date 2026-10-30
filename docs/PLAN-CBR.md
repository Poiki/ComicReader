# CBR/RAR desde MEGA

Alcance: aceptar CBR/RAR en el filtro común de todos los proveedores; persistir formato cbr sin migrar ni borrar la biblioteca; añadir lectura RAR4/RAR5, portadas, orden natural y límites equivalentes al lector ZIP; MIME/importación, mensajes y documentación; publicar otra release con la misma firma.

Decodificador: Junrar8.1.2 fijado, sin JNI adicional. Extracción síncrona de una página a memoria acotada, sin usar nombres del archivo como rutas ni extraer todo al disco. Límite explícito del diccionario; rechazo de cifrado, volúmenes incompletos, entradas duplicadas, cabeceras dañadas y páginas demasiado grandes. No convertir un error de RAR en apertura ZIP silenciosa. Los archivos sólidos pueden requerir repetir trabajo al retroceder: documentar el coste, conservar caché y precarga existentes.

Baseline:31 JVM. Antes del cierre: fixtures propios RAR4 y RAR5 comprimido/sólido, orden, acceso fuera de orden, integridad/CRC, rechazo cifrado/corrupción, importación, catálogo MEGA y render real Android; conservar11 grupos de regresión. Licencia del decodificador y dependencias atribuida: el código propio sigue MIT, no se presenta UnRAR como MIT ni como OSI. No hay cambios en autenticación/sync ni bypass del instalador.
