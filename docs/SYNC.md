# Progreso transportable CRP1

Se sincronizan página, total, terminado, favorito y marcadores. No se suben cómics, títulos, rutas, contraseñas ni sesiones. El identificador es SHA-256 de **todos los bytes del archivo** calculado una sola vez al importar. Reempaquetar un CBZ o modificar un PDF cambia esa identidad aunque las páginas parezcan iguales.

Cada instalación genera un UUID propio. Cada cambio local obtiene una revisión lógica mayor que las conocidas; el orden entre versiones de un mismo libro es `(revision, UUID autor)`. No depende del reloj del teléfono ni obliga a conservar siempre la página más avanzada: permite releer. La combinación es determinista, conmutativa, asociativa e idempotente para documentos bien formados. Una misma revisión/autor con contenidos distintos se rechaza.

El registro remoto se resuelve por **cómic completo**. Dos ediciones offline concurrentes pueden descartar el marcador/favorito de la versión que pierde; no se promete CRDT por campo. Dentro de un lector activo, guardar página aplica solo los campos editados sobre el estado SQLite más reciente; no pisa un favorito remoto ni marcadores ajenos que no se hayan modificado localmente.

Los snapshots `<UUID>.crp` tienen un escritor por instalación. Son reemplazables, no un journal inmutable. Drive los guarda en `appDataFolder` privado de esta app; MEGA en `.comicreader-state-v1`; WebDAV en una carpeta con ese mismo nombre. No se mezclan automáticamente varios proveedores. Cada uno guarda el estado que conoce la instalación; puedes cambiar de proveedor y sincronizar para transportarlo.

El sync lee/valida todos los snapshots aceptados antes de la transacción local, combina con el estado local y publica la instantánea propia. Un error no elimina registros ausentes ni borra progreso offline. Si subir falla después de combinar, el estado local combinado queda guardado y se informa del fallo; se puede reintentar. Hay una sola sincronización de la app a la vez. Ni HTTP 200 de un puente ni una cola VFS demuestran persistencia final en la nube.

## Esquema binario abierto

`Progress.java` es la implementación de referencia; todos los enteros son big-endian:

| Campo | Codificación |
| --- | --- |
| Magic | 4 bytes ASCII `CRP1` |
| Número de registros | int32, 0–50000 |
| ID por registro | Java DataOutput UTF: uint16 longitud + 64 caracteres ASCII hex |
| UUID autor | Java DataOutput UTF: uint16 longitud + 36 caracteres ASCII |
| Revisión | int64, 1–10^12 |
| Página actual y total | Dos int32; índice desde 0 y total 1–10000 |
| Terminado y favorito | Dos bytes booleanos |
| Marcadores | int32 cantidad, después int32 por índice, sin repetidos |

Se rechazan documentos de más de 8 MiB, duplicados, truncados, versión desconocida, índices fuera de rango, revisiones contradictorias y bytes finales inesperados. Los registros se exportan ordenados por hash. En importación se compara el total con los cómics locales existentes; ante discrepancia no se cambia la base. Un progreso de un libro todavía no importado también deberá coincidir con el total real cuando se incorpore ese archivo.

Para evitar que un contador remoto malformado inutilice la instalación, un documento no puede adelantar más de diez millones de acciones respecto al contador local. Es un límite práctico explícito: un archivo extraordinariamente antiguo con un salto mayor requiere revisar/migrar el protocolo, sin reducir sus revisiones silenciosamente.

## Qué no se migra del APK

Drive guarda en CDisplayEx progreso mediante `appProperties` privado de su aplicación. Registrar una identidad propia no da acceso a esos valores privados. No se copian sus clientes OAuth ni claves. No se ha implementado migración automática de sus sesiones/base de datos ni se verificó un exportador compatible. La app comienza su propio estado; los cómics siguen siendo archivos normales.
