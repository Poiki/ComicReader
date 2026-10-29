# Alcance y decisiones antes de implementar

La carpeta original solo contiene `Comicreader a imitar.apk`. No hay comportamiento propio que refactorizar ni llamadas/fixtures existentes. El APK se estudia como referencia funcional, sin trasladar código, recursos, identificadores OAuth, claves ni marca a la aplicación independiente. Su informe distingue observaciones, inferencias y lo que falta verificar.

## Entrega mínima completa

1. Investigación estática reproducible de identidad, lector, nube, persistencia, dependencias y permisos.
2. Aplicación Android nativa Java, biblioteca privada y lector CBZ/ZIP y PDF. Importación mediante selector Android (incluye proveedores de documentos instalados), también abrir desde otras aplicaciones.
3. QoL: reanudar, búsqueda/filtros, favoritos, marcadores, orden natural, manga, zoom/pan, salto de página, volumen/teclado, ajuste página/anchura, rotación, brillo local, pantalla encendida opcional, exportación/importación de progreso.
4. El usuario eligió autenticación directa: Drive con Google Identity Services y MEGA con SDK oficial. Navegación, copias offline y progreso propio. WebDAV HTTPS queda como conexión opcional adicional. Drive requiere configuración OAuth; MEGA requiere construir el AAR JNI, y la compilación base indica que no lo incluye.
5. Memoria acotada, trabajo fuera del hilo UI, una precarga, cancelación y cierre de archivos. Sin anuncios, telemetría, escaneos globales, servicios permanentes ni polling periódico.

## Superficies afectadas

Gradle/wrapper/manifest y licencia; archivos/importación/archivo comprimido/PDF; SQLite y protocolo de progreso; cliente WebDAV y credenciales; JobScheduler; biblioteca y lector accesibles; pruebas JVM e instrumentadas; documentación de uso/investigación/QA. Exportaciones públicas: solo actividad principal y MIME explícitos; servicio y lector privados. No se modifica el APK original ni archivos del proveedor.

## Behavior lock y limpieza

Antes del pase de limpieza se añadirán pruebas de orden natural, límites de archivos, combinación determinista del progreso, validación y recuperación sin sobrescrituras. Plan: revisar alternativas/fallbacks; eliminar código muerto; eliminar duplicación; hacer explícitos los errores; reforzar las pruebas. El plan se registra aquí y la revisión posterior en otro documento. No hay código previo ni fallbacks que inventariar aún. No hay motivo para un refactor arquitectónico o `$ralplan`.

Fallos de red, autenticación, archivos corruptos y almacenamiento lleno deben mostrarse conservando el estado local. Las credenciales se cifran con Android Keystore, no se exportan y no entran en backups. Los originales importados se copian; quitar un cómic elimina solo la copia privada y conserva su progreso. Los conflictos de progreso usan revisión lógica + dispositivo, no máximo de página ni reloj de pared. Se documentan conflictos simultáneos y límites de WebDAV.

## Límites que no se disfrazan

CBR/RAR/7z, streaming HTTP Range, OCR y recorte inteligente no están implementados. No se inventan conexiones. El rendimiento energético requiere mediciones físicas; tamaño de APK, pruebas y emulador solo verifican una parte.

## Ajustes de alcance aceptados durante el trabajo

El usuario pidió login directo y confirmó que todavía no tiene configuración Google propia. Se añadieron Google Play services Auth y OkHttp: el primero resuelve el consentimiento oficial; el segundo soporta WebDAV PROPFIND/MKCOL, que HttpURLConnection de Android rechaza. Core library desugaring mantiene el mínimo API26 con las APIs Java usadas. El SDK MEGA es opcional por compilación, no por un fallback de autenticación; no hay AAR oficial público listo para incorporar y esta máquina Windows no dispone de su entorno de construcción Linux/NDK. Se verificó el adaptador contra bindings oficiales generados realmente con SWIG, además del build base.
