# MEGA obligatorio y semejanza visual

Petición posterior: incorporar realmente el SDK MEGA al APK y acercar mucho la interfaz a la del artefacto de referencia.

## Alcance

- Construcción del SDK oficial desde el commit fijado, con Linux/NDK en Docker, ABIs ARM64 y x86_64, avisos de licencia y alineación 16 KiB. No se usa la biblioteca del APK original.
- Empaquetar bindings Java/Kotlin y JNI del mismo build en un AAR real. Integrarlo por defecto; retirar la variante que indica SDK ausente cuando el artefacto real esté construido.
- Probar carga JNI en Android, errores reales de autenticación sin cuenta y llamadas que no necesiten secretos. No inventar éxito de login ni pedir contraseñas en el chat.
- Observar pantallas originales en un emulador propio y replicar jerarquía, navegación, color y densidad con código y recursos propios. Conservar biblioteca, lector, accesibilidad, sincronización y sus tests.

## Comprobaciones y riesgos

El punto de partida tiene 29 tests JVM y 7 grupos Android. Se mantendrán las regresiones de progreso, importación, rotación y cifrado; se añadirán checks de JNI y UI donde cambie el contrato. El build no debe aceptar un AAR de tipos sin biblioteca nativa. Los originales, sesiones, fuentes decompilados y artefactos locales siguen excluidos de Git.

Las superficies alcanzadas son Gradle/source sets y manifest, scripts y avisos; adaptador MEGA y cierre de sesión; Main/Ui/Reader/recursos y portadas; tests y documentación. El login y el progreso ya guardado deben seguir funcionando, sin borrar bases ni bibliotecas existentes.

La compilación nativa puede ser costosa. Docker instalado permite realizarla sin sustituir toolchains globales. Se conservan logs/cache para no rehacer el trabajo ante un fallo; no se ocultan fallos con bibliotecas falsas, TLS permisivo ni un puente externo. El estilo se basa en observaciones, sin copiar marca o imágenes del original.
