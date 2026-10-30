# Verificación de CBR/RAR:0.5.0

30 de octubre de2026. Baseline:31 tests JVM. Se añadieron5 regresiones de CBR y se actualizó la expectativa anterior que rechazaba `.cbr`.

- **36 tests JVM PASS**, incluido RAR4 almacenado, RAR5 comprimido/sólido, orden natural y acceso0→2→1→0→2→2. Rechazo de duplicados, cifrado y alteración de datos por CRC. Antes de implementar, la prueba nueva falló por falta del lector RAR.
- **12 grupos Android PASS en x86_64 y ARM64 traducido**, emulador propio API36. El grupo nuevo importa descargas RAR4/RAR5 por el flujo real, comprueba colores de páginas y portada, abre ReaderActivity, cambia de página y verifica que el catálogo MEGA conserve `.CBR`/`.RAR` y vincule el hash tras descargar.
- Build debug, tests Android, release R8 y lint PASS; **0 errores y24 avisos lint**.
- **Release ARM64/R8 firmada:** instalada por ADB en el emulador propio; biblioteca local, portada, apertura de `rar5-solid.cbr` y avance real a página2/3 comprobados desde UI. No hubo excepción fatal del proceso en la ventana observada. Es una comprobación del lector, no una prueba de instalación mediante el actualizador.

Los fixtures solo contienen imágenes BMP sintéticas propias. RAR4 se generó con entradas almacenadas y cabeceras estándar; RAR5 se comprimió con el RAR7.23 de evaluación ya instalado. Ambos archivos válidos pasaron `rar t`. El generador y los fixtures quedan en Git; el ejecutable del archiver no se distribuye. No se han leído ni enviado cómics o cuentas privadas.

El lector RAR no extrae rutas al disco. Usa ArchiveOptions con diccionario máximo64MiB, una salida de tamaño declarado y límites globales. Se evitan los streams con ejecutores internos: se extrae sincrónicamente una sola página. En sólido se verifican explícitamente los predecesores para evitar que un skip interno oculte un CRC fallido. Se conservan el hash de identidad, la base de lectura y el protocolo de sincronización.

Pendientes: archivo concreto y cuenta MEGA del usuario, bibliotecas extensas, batería/hardware físico, todos los métodos de compresión y variantes históricas. No son compatibles CBR cifrado, multivolumen, diccionarios mayores de64MiB o ZIP simplemente renombrado aCBR. Un archivo sólido puede necesitar reproducir datos al retroceder. Los límites se comunican como error, sin resultados ficticios.

La validación de instalación/Play Protect de0.4 conserva su alcance: no se han desactivado protecciones ni se atribuye éxito de autoactualización en un dispositivo físico.
