# GitHub Releases y actualizaciones

Petición: publicar el proyecto en Poiki/ComicReader y actualizar la app desde sus releases. Alcance: cliente público GitHub, selección de APK por ABI, validación de descarga/paquete/firma/versión, instalador Android con consentimiento, menú y comprobación diaria al abrir. Sin sondeo continuo ni tokens GitHub en el APK.

Conservar biblioteca, cuentas, progreso y certificado de las versiones instaladas. La instalación la confirma Android; no se solicita instalación silenciosa. APKs, originales, decompilaciones, cachés y claves quedan fuera de Git. El código propio se publica con MIT y avisos de dependencias existentes.

Superficies: Gradle/versión; Main y nuevo Updater/ReleaseInfo; provider limitado al APK verificado y permiso de instalación; tests de versión, metadatos, hash/firma y UI; instrucciones de publicación local y README con enlaces a Releases. Una sola ruta de descarga usando OkHttp existente, límites explícitos y HTTPS. Errores automáticos conservados en ajustes, sin interrumpir lectura offline. No se ocultan errores como versión actualizada.

Orden: baseline; tests de reglas; implementación mínima; pruebas/build/R8/firma; revisar exactamente los archivos públicos; commit/push y release con APKs/checksums; verificar API real y actualización en emulador propio. Publicación autorizada por el usuario: no requiere otra confirmación. No subir claves de firma a GitHub ni crear infraestructura CI de firma sin necesidad.
