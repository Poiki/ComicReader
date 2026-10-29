# Publicación y actualizaciones

Fuente fija: [Poiki/ComicReader Releases](https://github.com/Poiki/ComicReader/releases). Se consulta la última release pública estable mediante la [API oficial](https://docs.github.com/en/rest/releases/releases#get-the-latest-release), sin credenciales. No usa el contenido de ramas como actualización ejecutable.

Al abrir la biblioteca, si hay Internet validado, la app comprueba novedades como máximo una vez al día. Los errores automáticos se guardan en ajustes y conservan la lectura local. La búsqueda manual permite reintentar inmediatamente. No hay servicio permanente ni tarea periódica para actualizaciones. Una versión nueva ofrece descargar e instalar, indicando tamaño; Android pide permitir instalaciones desde ComicReader y confirmar el APK. [Documentación de instalación Android](https://developer.android.com/reference/android/content/pm/PackageInstaller).

Se elige el primer ABI compatible (`arm64-v8a` o `x86_64`), con universal como alternativa explícita. Los nombres deben ser exactamente `ComicReader-arm64-v8a.apk`, `ComicReader-x86_64.apk` y `ComicReader-universal.apk`. Tags estables: `vMAJOR.MINOR.PATCH`, componentes0–9999; cada release incrementa también `versionCode`.

Se rechazan borradores/prereleases, metadatos inválidos, nombres duplicados, APKs de más de200MiB y assets sin digest SHA-256. La URL inicial debe pertenecer exactamente a este repositorio/tag/archivo. Las redirecciones se limitan a HTTPS en hosts conocidos de GitHub. Se comprueban longitud, digest, paquete, versión posterior, Android mínimo y certificado del APK frente al instalado. El instalador del sistema verifica la instalación; no se afirma éxito antes de que Android termine.

El APK temporal se almacena en caché privada. Un ContentProvider no exportado comparte únicamente ese archivo y solo con permisos de lectura por URI. Cancelar, cerrar la actividad o fallar una validación no afecta a cómics ni sesiones. Si se cancela el permiso/instalador, se puede reintentar desde Actualizaciones → Instalar descargada. El APK de una versión ya instalada se retira al abrir de nuevo.

## Publicar otra versión

1. Conserva la clave original fuera de Git. Cambiar de certificado impide actualizar estas instalaciones; el cliente lo rechaza. La clave de pruebas actual no se sube a GitHub ni a sus secretos.
2. Incrementa `versionCode` y `versionName` en `app/build.gradle`, y escribe `docs/releases/vX.Y.Z.md`.
3. Prepara el SDK MEGA, ejecuta pruebas pertinentes y haz commit en `main`.
4. Con JDK21, Android SDK y `gh` autenticado, ejecuta `./scripts/publish-release.ps1`. Usa el keystore debug local original por defecto; `-KeyStore` permite indicar una copia de esa misma clave. La contraseña puede pasarse mediante `COMICREADER_SIGN_PASSWORD`.

El script compila, prueba, firma, verifica certificado/alineación, genera SHA256SUMS, empuja commit/tag, sube todos los assets a un borrador y solo después lo publica como latest. No genera una clave nueva, sobrescribe tags, fuerza el push ni reemplaza una release existente. Los APKs van en Releases y quedan fuera del historial Git. No hace falta exponer claves de firma en un workflow público.
