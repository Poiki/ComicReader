# Revisión independiente de compilación e integración MEGA

Fecha: 29 de octubre de 2026. Alcance: `scripts/build-mega-sdk.sh`, el adaptador `withMega/MegaClient.java`, sus llamadas y el SDK oficial fijado. Revisión de fuentes; no se ha ejecutado Docker, instalado herramientas, modificado producción ni repetido el análisis del APK original.

Identidad examinada: MEGA SDK `v10.21.0`, commit `d1c0cb76cb98598d93b9fb0bb89612daeb0fb39c`, repositorio local `.analysis/mega-sdk-src`. Los archivos fuera del sparse checkout se leyeron con `git show HEAD:ruta`.

## Hallazgo que bloquea una cuenta válida

**El constructor no admite ya `basePath = null` como forma de desactivar la caché.** La documentación Java mantiene esa descripción antigua, pero `MegaApiImpl::init` sustituye `null` por `std::filesystem::current_path()` y crea `MegaDbAccess` siempre (`src/megaapi_impl.cpp`, 7358–7385). `MegaClient::opensctable` abre la base al disponer de una sesión y llama a `fatalError(REASON_ERROR_DB_IO)` si falla (`src/megaclient.cpp`, 12765–12838).

El adaptador pasa actualmente `null` y afirma que así evita persistir claves del SDK. Esa afirmación es incorrecta para este commit. En un proceso Android cuyo directorio actual sea `/`, el destino no es escribible. Es una causa probable de fallo con login válido/fetchNodes que **una prueba de credenciales inválidas no descubriría**.

Reparación mínima: crear y validar un subdirectorio privado bajo `context.getNoBackupFilesDir()` y pasarlo al constructor. Conservar la sesión exportada en `Secrets`/Android Keystore. Documentar que el SDK mantiene su propia caché privada y excluirla de las copias. No borrar archivos abiertos por JNI para simular una caché desactivada. Después de un logout confirmado puede revisarse la política de conservación; no se debe borrar información mientras el SDK la usa.

Fuentes: [inicialización oficial](https://github.com/meganz/sdk/blob/v10.21.0/src/megaapi_impl.cpp), [apertura de la base](https://github.com/meganz/sdk/blob/v10.21.0/src/megaclient.cpp). La condición de permisos de `/` es una inferencia de Android; aún debe comprobarse en el proceso que ejecute esta app.

## Entorno Linux y dependencias

La [imagen oficial Android](https://github.com/meganz/sdk/blob/v10.21.0/dockerfile/android-cross-build.dockerfile) usa Ubuntu 24.04 y estos paquetes:

```text
autoconf autoconf-archive build-essential cmake curl git libtool-bin nasm
openjdk-21-jdk pkg-config python3 python3-pip swig unzip wget zip
```

Para el entorno propio conviene declarar además `ca-certificates` y `automake` explícitamente. El script exige `libtoolize`, proporcionado por el paquete libtool. AWS CLI y credenciales de AWS del Dockerfile oficial son para su caché remota de builds y no hacen falta para esta app. Python pip tampoco es una dependencia directa del empaquetador AAR actual. CMake mínimo: 3.20 (`CMakeLists.txt`). La guía oficial exige NDK 27.1.12297006 o posterior.

`vcpkg.json` fija `builtin-baseline = ef7dbf94b9198bc58f45951adcf1f041fcbc5ea0`, además del override ICU `74.2#4`. Fijar también el checkout de vcpkg evita que el ejecutable/herramientas cambien entre builds; registrar el commit exacto. Las recetas superpuestas de MEGA forman parte de la identidad del SDK y deben conservarse. Las dependencias obligatorias declaradas son Crypto++, curl con websockets/zstd, ICU, libsodium y SQLite; OpenSSL se activa por defecto en Android.

El preset `mega-android` activa `ENABLE_MEDIA_FILE_METADATA=ON`, incorporando MediaInfo. Para importar CBZ/ZIP/PDF y sincronizar CRP no se utiliza: **es razonable desactivarlo**. FreeImage, FFmpeg y PDFium están desactivados por defecto para Android; no es necesario añadirlos. ICU contiene su propio filtro de datos y descargas tanto de fuente como de datos: no sustituirlo por una versión distinta para acelerar el primer build.

`ENABLE_SYNC`, `ENABLE_CHAT` y `USE_LIBUV` están activos por defecto en Android. Aunque esta app no use sus funciones, el wrapper oficial `MegaApiJava` completo importa `MegaSync` y hace referencia a APIs condicionadas por estos flags. Por ejemplo, la firma nativa de `logout` cambia con `ENABLE_SYNC`; HTTP/FTP se ocultan con `HAVE_LIBUV`. Desactivarlos exige reconstruir y adaptar los wrappers con una revisión aparte. No es un recorte seguro del build actual. Desactivar MediaInfo no cambia ese contrato Java.

El script oficial asigna `JOBS=$(nproc)` y no respeta un `JOBS` externo. Para el equipo disponible debe limitarse el `cmake --build` a pocos trabajos y exportarse también `VCPKG_MAX_CONCURRENCY` para las dependencias. La primera compilación de dos ABI es considerable; lanzar tantos compiladores como CPU exponga Docker puede agotar la RAM.

Fuentes: [opciones](https://github.com/meganz/sdk/blob/v10.21.0/cmake/modules/sdklib_options.cmake), [presets](https://github.com/meganz/sdk/blob/v10.21.0/CMakePresets.json), [manifest vcpkg](https://github.com/meganz/sdk/blob/v10.21.0/vcpkg.json), [configuración vcpkg](https://github.com/meganz/sdk/blob/v10.21.0/cmake/modules/vcpkg_management.cmake).

## AAR, ABI y páginas de 16 KiB

La ruta oficial construye una biblioteca compartida llamada `libmega.so`, genera sus clases Java mediante SWIG y añade `JNI_OnLoad` si el SDK es el proyecto principal con biblioteca dinámica. El preset y el script actual cumplen esas condiciones. Construir `arm64-v8a` y `x86_64` secuencialmente y empaquetar ambas bibliotecas con las clases generadas del mismo commit/flags. Las clases SWIG son independientes de la ABI para estos parámetros; no combinar bindings de otra versión.

Las reglas `-keep class nz.mega.sdk.** { *; }` protegen nombres y callbacks JNI frente a R8. Los wrappers Kotlin oficiales deben compilarse en el AAR: AGP 9 incorpora Kotlin; no se debe añadir el plugin `kotlin-android`. Para dejar explícita su ubicación, copiar los `.kt` a `src/main/kotlin/nz/mega/sdk` o declarar un source set Kotlin; el directorio Java queda para `.java`. Ajustar `compileSdk` del módulo generado a 37 si esa es la plataforma instalada. Mantener Java/JVM target 17 y minSdk 28.

Android usa STL estático por defecto con CMake, y el script oficial copia únicamente `libmega.so`. Aun así, **verificar `DT_NEEDED` del ELF generado**: cualquier dependencia compartida no perteneciente al sistema también debe empaquetarse para cada ABI. No suponer que un archivo copiado implica una biblioteca autocontenida.

Los overlays `arm64-android-mega` y `x64-android-mega` ya establecen `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`; `sdklib_variables.cmake` también lo establece para el SDK. NDK r28+ ofrece soporte por defecto. La verificación final requiere:

1. Revisar todos los segmentos LOAD con `llvm-objdump -p` o `llvm-readelf -lW`: alineación mínima `0x4000` para arm64/x86_64.
2. Revisar que el final de GNU_RELRO esté alineado a 16 KiB cuando existe.
3. Ejecutar `zipalign -c -P 16 4` contra el APK final, usando build-tools 35 o posteriores.
4. Probar en un entorno de páginas de 16 KiB; una ejecución normal de 4 KiB no verifica ese comportamiento.

Fuentes: [CMake bindings](https://github.com/meganz/sdk/blob/v10.21.0/bindings/java/CMakeLists.txt), [variables Android](https://github.com/meganz/sdk/blob/v10.21.0/cmake/modules/sdklib_variables.cmake), [Kotlin integrado](https://developer.android.com/build/migrate-to-built-in-kotlin), [verificación Android 16 KiB](https://developer.android.com/guide/practices/page-sizes).

## Contrato nativo y adaptador

| Pregunta | Resultado de la revisión |
| --- | --- |
| ¿Hace falta appkey? | En este commit, ambos constructores C++ de `MegaApi` declaran el argumento como `/*appKey*/` y no lo usan (`src/megaapi.cpp`, 2085 y 2095). `null` es correcto para este pin. La prosa Java y cabeceras históricas que la exigen están obsoletas. |
| ¿Se carga la biblioteca real? | `System.loadLibrary("mega")` antes de crear `MegaApiJava` es apropiado. El código generado también intenta cargarla; una segunda carga en el mismo classloader es válida. Debe comprobarse en runtime contra el AAR real. |
| ¿Se pierden callbacks por GC? | Los wrappers conservan delegates en sets estáticos y los retiran después del callback final. El adaptador mantiene además `pending`, `pendingRequest` y el cancel token. `MegaApiJava.runCallback` ejecuta inline; usar esta clase sin wrapper Android evita redirigir los callbacks al hilo UI bloqueado. |
| ¿Se guarda la contraseña? | El adaptador solo la entrega al SDK. Persiste `dumpSession()` mediante `Secrets`, con flag `connected`. Esto no significa que la caché nativa esté desactivada; corregir el directorio indicado arriba. |
| ¿Login antes de fetchNodes? | El orden login/fastLogin → fetchNodes → navegación/transferencia coincide con el contrato oficial. |
| ¿Subida con el nombre del dispositivo? | `MegaUploadOptions.setFileName(device + ".crp")` se consume en `createUploadTransfer`. No usa el nombre aleatorio del temporal como nombre remoto. |
| ¿Crea un duplicado nuevo cada sync? | La subida usa `UseLocalVersioningFlag` (`sendPendingTransfers`, 21009). `File::completed` busca el nombre existente y aplica `ovhandle` (`src/file.cpp`, 497–505): se conserva versión o se reemplaza según la configuración de la cuenta. El SDK también optimiza mismo contenido, incluso si solo cambió mtime. No hay que borrar el archivo anterior antes de subir. |
| ¿Puede crecer el histórico? | Sí, las versiones de instantáneas modificadas dependen de la política de la cuenta. No cambiar globalmente la configuración del usuario ni purgar versiones ajenas. El adaptador ya documenta que no las purga. |
| ¿Download valida archivo? | Usa handles, un temporal privado y límite de bytes; espera el callback terminal y verifica longitud antes de devolverlo. La integridad criptográfica corresponde al SDK. El importador realiza su validación de formato y SHA-256. |
| ¿Callbacks de streaming? | `onTransferData=false` solo afecta streaming; `startDownload` a archivo no utiliza ese callback. |
| ¿Timeout equivale a cancelación instantánea? | No. El adaptador marca cancelación del transfer, conserva referencias hasta terminal y rechaza operaciones nuevas mientras una anterior sigue pendiente. Las peticiones API no cancelables pueden finalizar después. La desconexión de login tardío es una protección necesaria. |

El `JNI_OnLoad` oficial busca además clases `mega/privacy/android/data/filewrapper/FileWrapper` y `ChildMetadata`, pertenecientes al cliente MEGA. No están en este lector. El código de inicio comprueba esos resultados, describe y limpia las excepciones y continúa devolviendo JNI 1.6. **No fabricar stubs de esas clases**: la ruta actual usa archivos privados normales; verificar en la ejecución que toma la implementación POSIX y no necesita el puente SAF del cliente MEGA. Si se exige acceso nativo a URI `content://` en el futuro, será una integración distinta.

Fuentes: [constructor real](https://github.com/meganz/sdk/blob/v10.21.0/src/megaapi.cpp), [JNI generado](https://github.com/meganz/sdk/blob/v10.21.0/bindings/megaapi.i), [callbacks Java](https://github.com/meganz/sdk/tree/v10.21.0/bindings/java/nz/mega/sdk), [transferencias](https://github.com/meganz/sdk/blob/v10.21.0/src/megaapi_impl.cpp), [versionado](https://github.com/meganz/sdk/blob/v10.21.0/src/file.cpp).

## Comprobaciones y límites

Esta revisión es independiente del autor de producción. Solo modifica este informe. Se han inspeccionado las fuentes y las firmas; no se han ejecutado autenticación, JNI, build C++, tests de cuenta ni mediciones de batería. Los tipos SWIG previamente compilados por el trabajo principal no equivalen a una biblioteca JNI funcional.

Prioridad antes de entregar: corregir el directorio nativo, construir las dos ABI, validar ELF/AAR/APK y ejecutar callbacks nativos con credenciales inválidas y una descarga pública cuando sea posible. Con una cuenta autorizada quedan por probar login válido/2FA, restauración, navegación, upload/download, dos dispositivos, sesión revocada y pérdida de red. No se han pedido contraseñas ni tokens al usuario.

AI SLOP CLEANUP REPORT: alcance limitado al build y adaptador MEGA; revisión de solo lectura. Behavior lock: no se han cambiado comportamientos ni ejecutado regresiones nuevas. Fallbacks: la variante sin SDK es un fallo explícito y documentado, pero no satisface la entrega solicitada ahora; no debe distribuirse como si MEGA estuviera incluido. La afirmación `null = sin caché` es un contrato obsoleto que debe repararse en su origen. Los fallbacks de carga del SDK son upstream y están acotados por la carga explícita previa del adaptador; no se modifican. UI N/A. Pases de eliminación/refactor N/A; comprobación de producción pendiente en el trabajo principal.

## Adenda: constructor corregido y nueva ruta Docker

Revisión posterior de `scripts/mega-native.Dockerfile`, `scripts/mega-native-build.sh`, `scripts/build-mega-native.ps1`, `mega-sdk/build.gradle` y `app/src/nativeTest`. El constructor del adaptador ya utiliza `getNoBackupFilesDir()/mega-sdk`, crea el directorio y falla explícitamente si no puede hacerlo: queda reparado el bloqueo de `basePath` identificado arriba. Aún no se ha comprobado aquí un login válido.

**Fallo de compilación encontrado en el módulo nuevo:** el script copia todos los wrappers Java oficiales y `mega-sdk/build.gradle` no los excluye. `MegaApiSwing.java` importa `javax.swing.SwingUtilities`, ausente en Android. `AndroidGfxProcessor.java` importa `androidx.exifinterface.media.ExifInterface`, dependencia que este lector no necesita. `MegaApiAndroid` y `MegaUtilsAndroid` dependen de ese procesador. Deben mantenerse las cuatro exclusiones que ya tenía el empaquetador AAR anterior:

```groovy
sourceSets.main.java.exclude '**/MegaApiSwing.java', '**/MegaApiAndroid.java',
        '**/AndroidGfxProcessor.java', '**/MegaUtilsAndroid.java'
```

No añadir Swing ni ExifInterface para compilar código que la app no usa. Las ubicaciones separadas `java`/`kotlin` del módulo nuevo son coherentes con AGP 9. `compileSdk 37`, `minSdk 28`, Java 17, STL estático explícito, SDK/vcpkg fijados y MediaInfo desactivado son consistentes con esta integración. El nuevo build limita compiladores y vcpkg a tres trabajos. Exporta cabeceras de programa y dependencias ELF: aún hace falta evaluar esos archivos y la alineación del APK final; generarlos no constituye una validación por sí solo. El script PowerShell comprueba códigos de salida y conserva su volumen para reintentos.

### FileWrapper ausente: la ruta POSIX está implementada expresamente

La revisión más profunda permite cerrar la duda estática anterior. `createFSA()` en `src/megaapi_impl.cpp`, 45637–45649, construye inicialmente `FSACCESS_CLASS` y, en Android, pregunta `AndroidFileSystemAccess::isFileWrapperActive`. Si no hay `FileWrapper` JNI, **sustituye el objeto por `LinuxFileSystemAccess`**. El constructor de `MegaClient` repite esa selección para su propio `fsaccess` (`src/megaclient.cpp`, 2035–2042). La condición se define como `fileWrapper != nullptr` (`include/mega/android/androidFileSystem.h`, 386–389).

Por tanto, los `fsAccess` y `fingerprintingFsAccess` del API y el filesystem del cliente eligen POSIX sin depender de clases Java ficticias. Se clasifica como compatibilidad upstream fundamentada: condición explícita de capacidad, implementación completa y mensaje de diagnóstico; no oculta un intento fallido de autenticación. Las excepciones de clase no encontrada descritas por `JNI_OnLoad` quedan limpiadas antes de que se seleccione esa ruta. No se justifica parchear SDK ni añadir stubs para los archivos privados utilizados aquí. El comportamiento del ELF final sigue pendiente de ejecución.

### Alcance real del test nativo

`MegaNativeInstrumentation` rechaza el flag de cuenta guardada y usa una dirección `example.invalid` con contraseña de prueba fija; no lee credenciales de personas ni necesita una cuenta real. Su caso sin red fuerza el constructor JNI mediante `client.list`, valida el fallo explícito de ausencia de sesión y comprueba el directorio privado. Con `network=true` exige que una cuenta inexistente reciba un callback de autenticación y que no se guarde la sesión. Esas verificaciones están acotadas correctamente; no prueban autenticación válida ni sincronización.

La creación/borrado de temporal mediante `java.io.File` solo prueba escritura Java. Para verificar específicamente POSIX nativo sin cuenta, el test puede acceder por reflexión al `MegaApiJava` ya creado, escribir un pequeño archivo privado, pedir `getFingerprint(path)` y exigir resultado no nulo, además de `null` para una ruta inexistente. No hace falta añadir métodos a producción: `MegaApiImpl::getFingerprint`, 14243–14265, usa precisamente `fsAccess->newfileaccess()->fopen` y `FileFingerprint::genfingerprint` sin requerir login. Esta prueba comprueba lectura real a través del filesystem elegido por JNI, mientras una operación Java no lo hace.

Los tiempos de vida del adaptador siguen conservando request listeners y cancel tokens hasta el callback terminal; el SDK mantiene sus delegates en sets. No se ha observado otro defecto estático nuevo que impida login/download/sync tras reparar el directorio y excluir los wrappers no utilizados. La selección opcional antigua de Gradle está en transición y no se trata como un defecto de la entrega final.

Verificación de esta adenda: lecturas y búsquedas de fuentes, sin Docker, emulador ni tests ejecutados por este revisor. Solo se ha modificado este informe.

## Cierre de construcción por el autor, 29-10-2026

El constructor anterior `build-mega-sdk.sh` y el source set `withMega` se retiraron tras validar la ruta única Docker/Gradle. SDK real compilado para ambas ABIs, módulos app/AAR completos, ELF16KiB, firma y alineación del APK verificados; JNI, IO y callback remoto de cuenta inexistente PASS en ambas ABIs. La salida release ARM64 se instaló y comprobó desde UI. No se atribuye al revisor esta ejecución. [QA actual](../QA.md).
