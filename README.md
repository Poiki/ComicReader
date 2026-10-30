# ComicReader

Lector Android CBZ/ZIP, CBR/RAR y PDF, con código propio MIT, lectura offline y sincronización directa con MEGA y Google Drive. Android 9/API28 o posterior.

## Instalar en OnePlus Pad 2

Descarga el **[APK ARM64 más reciente](https://github.com/Poiki/ComicReader/releases/latest/download/ComicReader-arm64-v8a.apk)** desde [Releases](https://github.com/Poiki/ComicReader/releases). Incluye MEGA y está optimizado con R8. Instala encima de la anterior para conservar cómics, sesiones y progreso; no desinstales ni borres datos. No necesitas compilar ni configurar claves para MEGA. Para x86_64 o un APK universal, usa los otros assets de la release.

Desde0.4, **Ajustes → Actualizaciones de la app** busca nuevas versiones del repositorio. La comprobación al abrir está activada por defecto, como máximo una vez al día. Ofrece descargar e instalar con confirmación de Android, validando SHA-256 y el certificado instalado. [Funcionamiento y publicación](docs/UPDATES.md). La instalación desde0.3 o anterior debe hacerse manualmente una vez para incorporar el actualizador.

El diseño sigue el APK de referencia: fondo blanco, secciones grises, iconos negros, acento naranja y controles superpuestos al lector. Los recursos y el código de esta app son propios.

## Leer y sincronizar

La app abre inicialmente **Biblioteca**. Pulsa **+ → MEGA**, inicia sesión si hace falta, entra en la carpeta deseada y pulsa **Aceptar**. La carpeta queda guardada como biblioteca: puedes ver sus cómics, entrar en subcarpetas y volver con **Subir carpeta** o Atrás. Al tocar un cómic se descarga para leer; las siguientes veces se abre la copia offline. El catálogo consultado se conserva sin conexión, indicando que es una lista guardada. **Ajustes → Actualizar biblioteca** consulta de nuevo MEGA sin descargar todos los cómics.

El botón **Filtrar biblioteca** muestra todos, leyendo, sin leer, leídos y favoritos. Mantén pulsado para marcar leído/sin leer o favorito, incluso antes de descargar. Los estados anteriores a la primera descarga quedan en el catálogo de este dispositivo; al descargar se asocian al hash del contenido y pasan al protocolo habitual de sincronización. Las carpetas permanecen visibles para poder navegar durante un filtro.

**Ajustes → Tema** permite claro, oscuro o negro OLED; **Pantalla de inicio** permite biblioteca, conexiones/configuración o último cómic incompleto. Si este ya está terminado o no está disponible localmente, abre la biblioteca. El tema cambia también los formularios y controles del lector; no altera los colores del cómic.

MEGA admite segundo factor. La contraseña y el código 2FA no se guardan; la sesión propia se cifra mediante Android Keystore. El SDK MEGA real está incluido.

Desde 0.5 se muestran y leen **CBR/RAR**, incluidos RAR4 y RAR5 comprimidos/sólidos, tanto desde MEGA como en importación local y otros proveedores. Tras actualizar usa **Ajustes → Actualizar biblioteca** para volver a consultar la carpeta: el catálogo antiguo había omitido los CBR. Las portadas y el progreso usan el mismo flujo que CBZ. No hay extracción completa al disco.

Límites RAR: archivo de un solo volumen y sin contraseña, 64 MiB por entrada/diccionario y 4 GiB descomprimidos en total. Los archivos sólidos pueden necesitar repetir la descompresión de páginas anteriores al retroceder; se conserva la caché/precarga limitada. No se convierte automáticamente un ZIP renombrado como CBR. El decodificador Junrar conserva su [licencia UnRAR y atribución](THIRD_PARTY_NOTICES.md), independiente del MIT del código propio.

Drive también usa autorización directa dentro de la app. Antes de usar una cuenta Google hay que [registrar el paquete y la firma en Google Cloud](docs/CLOUD-NATIVE.md). WebDAV HTTPS queda disponible para otros servidores.

Se sincronizan página, leído, favoritos y marcadores mediante el proveedor seleccionado. Se usa uno a la vez; cambiarlo conserva la biblioteca local. La sincronización automática es optativa, agrupada y exige inicialmente red sin coste medido y batería no baja. El botón manual también permite datos móviles. Los cómics no se suben automáticamente.

La biblioteca ofrece portadas locales, lista/cuadrícula adaptable, búsqueda y filtros. **Ajustes → Carpetas y bibliotecas** cambia entre copias locales y la carpeta MEGA elegida, o permite elegir otra. Borrar una copia conserva el progreso y el archivo externo. La importación múltiple admite hasta 100 archivos y deduplica los mismos bytes mediante SHA-256.

En el lector, los tercios laterales cambian página y el centro muestra los controles. Hay zoom, manga RTL, marcadores, salto de página, ajuste página/ancho, brillo local, bloqueo de giro y volumen opcional. [Lista de QoL y funciones pendientes](docs/FEATURES.md).

## Compilar

JDK 21, Android SDK plataforma 37, Build Tools 36.0.0 y Docker Desktop con motor Linux. Configura `ANDROID_HOME` o `local.properties`. Gradle 9.5.0, AGP 9.3.1 y el SDK MEGA están fijados.

```powershell
.\scripts\build-mega-native.ps1
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

La primera compilación nativa descarga y construye el SDK oficial; las siguientes reutilizan su caché. [Detalles de MEGA, versiones y licencias](docs/CLOUD-MEGA.md). Los APKs se generan en `app/build/outputs/apk/{debug,release}`, por ABI y universal. `assembleRelease` genera APKs sin firmar. Las Releases actuales usan el certificado debug original para conservar actualizaciones de las instalaciones de pruebas. El código propio se publica con MIT; las dependencias conservan sus licencias. Las claves de firma no están en el repositorio.

En un emulador vacío propio, instala el APK debug de su arquitectura y `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, y ejecuta:

```powershell
adb -s emulator-5554 shell am instrument -w org.comicreader.app.test/org.comicreader.app.SmokeInstrumentation
adb -s emulator-5554 shell am instrument -w -e mega true -e network true org.comicreader.app.test/org.comicreader.app.SmokeInstrumentation
```

Los checks esperan una instalación de pruebas sin datos personales. El segundo usa exclusivamente una dirección reservada inexistente para comprobar la respuesta del SDK. [Verificación y límites de las pruebas](docs/QA.md).

## Rendimiento y límites

Decodificación ajustada a pantalla, máximo de 3 megapíxeles por imagen, caché del lector de 24 MiB y una precarga. Las portadas tienen un trabajador, cola acotada y caché de 8 MiB. La precarga se evita en ahorro de batería o dispositivos low-RAM. No hay anuncios, analítica, escaneo global, servicios permanentes ni pantalla encendida por defecto. Se solicita desconexión local de MEGA al pausar o finalizar el sync, conservando la sesión cifrada.

No se ha medido la autonomía en una tablet física ni una mejora porcentual frente al original. Las transferencias y el sync con cuentas reales no se cubren con los fixtures de pruebas. 7z, doble página y lectura continua están pendientes. Las copias locales de cómics no tienen cifrado adicional; desinstalar borra los datos privados. Los conflictos remotos se resuelven por cómic completo, con los límites descritos en [SYNC](docs/SYNC.md).

[Investigación del APK](docs/ANALYSIS.md) · [Revisión y limpieza](docs/CLEANUP.md) · [Licencias](THIRD_PARTY_NOTICES.md)
