# Biblioteca MEGA, temas e inicio

Alcance: sustituir el explorador MEGA de descarga puntual por selección explícita de carpeta y biblioteca persistente, con subcarpetas, caché de metadatos, asociación handle/cuenta a hash local y filtros de lectura. Conservar sesiones, cómics, progreso, límites de descarga y sincronización existentes.

Cambios necesarios: catálogo SQLite separado (sin migrar la base de lectura), MegaClient (identidad de cuenta), Main/adapter/navegación y ajustes, Ui/estilos/iconos, Reader (tema y último cómic), tests Android y documentación/APK. El catálogo no usa los nombres remotos como rutas ni inventa hashes de contenido. Los fallos de actualización conservan la última lista indicando que es caché; no se convierten en una carpeta vacía.

Inicio predeterminado: biblioteca, con cuadrícula adaptable si no hay preferencia previa. Opciones: biblioteca, conexiones/configuración actual, último cómic incompleto disponible; si este falta, biblioteca. Temas claro, oscuro y OLED con negro real en superficies. No se alteran los colores de las páginas del cómic.

Orden: bloquear comportamiento actual con tests; persistencia y validación; selección y navegación de biblioteca; filtros/descarga/reanudación; temas e inicio; eliminar explorador MEGA sustituido y duplicación; regresiones, build, firma y prueba de actualización sobre 0.2. No añadir dependencias ni escaneo/descarga recursiva automáticos. No usar cuentas del usuario en pruebas ni modificar originales.
