# MEGA integrado en la aplicación

MEGA es una dependencia obligatoria desde ComicReader 0.2. La app incorpora el SDK oficial compilado desde fuente y permite correo/contraseña, segundo factor de seis dígitos, restauración de sesión, navegación, descarga para lectura offline y sincronización del progreso. No utiliza un puente externo ni bibliotecas extraídas del APK de referencia.

## Construcción

Se fija SDK v10.21.0, commit `d1c0cb76cb98598d93b9fb0bb89612daeb0fb39c`; vcpkg `ef7dbf94b9198bc58f45951adcf1f041fcbc5ea0`; NDK r28c. El toolchain Linux se construye en Docker para evitar sustituir las herramientas de Windows. Requiere Docker con motor Linux y el SDK Android/JDK del proyecto.

```powershell
docker desktop start --detach
.\scripts\build-mega-native.ps1
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

El script conserva el checkout y las compilaciones en el volumen Docker `comicreader-mega-work-v10` y exporta a `.local/mega-sdk`. El módulo `mega-sdk` incorpora los bindings Java/Kotlin y las bibliotecas JNI de ese mismo build. Genera también el AAR con `:mega-sdk:assembleRelease`. Las salidas locales y cachés no se añaden a Git. El build falla explícitamente si faltan bibliotecas reales; ya no hay una variante de aplicación con MEGA simulado o ausente.

ABIs: `arm64-v8a` y `x86_64`. Android 9/API28 o posterior. Se producen APKs por arquitectura y uno universal; para teléfonos ARM64 usa el correspondiente a `arm64-v8a`. Se comprueban dependencias ELF, segmentos de 16 KiB y alineación final del APK.

Se desactiva MediaInfo porque este lector no lo necesita. Se mantienen las capacidades del núcleo requeridas por los wrappers oficiales Java; no se parchea la criptografía ni se eliminan símbolos a ciegas. Las bibliotecas se enlazan con STL estático. La licencia BSD del SDK y avisos de sus dependencias se incorporan como assets del módulo.

## Cuenta y seguridad

No hace falta appkey para el tag fijado: la implementación C++ ignora ese parámetro y el constructor recibe `null`. Las cabeceras históricas Java que afirman lo contrario no representan la implementación actual. Fuentes: [incidencia oficial](https://github.com/meganz/sdk/issues/2706), [SDK fijado](https://github.com/meganz/sdk/tree/v10.21.0).

Introduce tu cuenta únicamente en el formulario de la aplicación. La contraseña y el código 2FA no se guardan. La sesión de `dumpSession()` se cifra con AES-GCM y Android Keystore. Las copias de seguridad y transferencias no incluyen las sesiones propias.

El SDK actual crea una caché de nodos: se le pasa expresamente `getNoBackupFilesDir()/mega-sdk`, privada y escribible. Pasar `basePath=null` no desactiva la caché en este tag; usa el directorio actual, que puede no ser escribible en Android. La app no afirma que toda información interna del SDK esté cifrada por Keystore: aplica la protección propia del SDK y del almacenamiento privado del sistema.

Al pausar la biblioteca o terminar un sync se solicita `localLogout`, conservando la sesión cifrada. El siguiente acceso restaura la sesión y actualiza el árbol. Desconectar la cuenta intenta revocar la sesión remota y borra la sesión propia guardada; si no puede confirmar la revocación remota lo comunica. Los cómics y el progreso local se conservan.

## Archivos y sincronización

Desde 0.3 el selector tiene **Aceptar**. Guarda cuenta, handle y nombre de la carpeta como raíz de la biblioteca. Un catálogo SQLite separado conserva los hijos de cada carpeta consultada y sus asociaciones a hashes locales. No migra ni sustituye la base de lectura existente. Navegar carga primero la copia de metadatos; una consulta fallida conserva esa lista con indicación de caché/error. Se consulta de nuevo al abrir una biblioteca MEGA con sesión guardada; los cambios de tema no repiten esa actualización y existe actualización manual.

Los filtros de lectura usan el progreso por hash cuando existe. Marcar leído/favorito antes de descargar guarda una marca local asociada a cuenta/handle; al obtener el archivo se aplica a su progreso por contenido. Estas marcas previas no se sincronizan entre dispositivos hasta que se conoce el hash. Los nombres de carpetas/archivos y asociaciones se guardan en el espacio privado de la app, excluido de backups. Desconectar MEGA retira la selección de biblioteca y conserva los cómics y progreso locales.

La navegación usa handles de MEGA. Los nombres remotos no se convierten en rutas locales. Descargar guarda un temporal privado, lo valida y hashea, y lo mueve a la biblioteca sin escribir una segunda copia completa. Archivos compatibles: CBZ/ZIP, CBR/RAR y PDF, máximo 1 GiB. Desde0.5 se incluyen CBR/RAR en el filtro común: actualizar la carpeta vuelve a incorporar los que las versiones anteriores habían omitido. El CBR descargado conserva el mismo protocolo de identidad SHA-256 y progreso.

El progreso propio se guarda en `.comicreader-state-v1/<UUID-dispositivo>.crp`. Se sincronizan posición, leído, favoritos y marcadores; no credenciales ni cómics automáticamente. Límites: 8 MiB por snapshot, 64 MiB descargados por sync, 128 snapshots y 50.000 cómics. Todos los documentos aceptados se validan antes de la transacción local. Los conflictos se resuelven como explica [SYNC.md](SYNC.md).

MEGA puede conservar versiones del mismo snapshot según la configuración de la cuenta. Las carpetas duplicadas se rechazan, y no se eliminan automáticamente versiones ni datos remotos. Esto no tiene la semántica de permisos de `appDataFolder` de Google.

Cada petición espera hasta dos minutos y cada transferencia hasta diez. Cancelar marca el token del SDK y conserva los archivos hasta el callback terminal: JNI puede seguir usándolos después de que el caller Java deje de esperar. Un login completado después de un timeout se desconecta localmente; mientras una petición sigue pendiente se informa y no se superponen nuevas operaciones.

## Verificación

[QA.md](QA.md) registra la compilación y las pruebas ejecutadas, diferenciando carga JNI, IO nativo, callback de autenticación, pruebas de UI y cuentas reales. La prueba con una dirección reservada inexistente no equivale a iniciar sesión correctamente en una cuenta del usuario.

Las fuentes del SDK fijado tienen una ruta POSIX explícita cuando no está disponible el FileWrapper del cliente completo de MEGA. La app solo entrega al SDK rutas privadas normales; no inventa esas clases del cliente ni le pasa URI content de otros proveedores.
