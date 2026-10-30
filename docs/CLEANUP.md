# AI SLOP CLEANUP REPORT

**Scope:** código propio del lector, importación, progreso, proveedores cloud, UI, construcción nativa y pruebas. El APK original permanece intacto; no se trasladó implementación decompilada ni sus recursos.

**Behavior Lock:** 28 tests JVM y 10 grupos Android en la entrega0.3, repetidos en x86_64 y ARM64 traducido. Incluye catálogo, Aceptar real en UI, filtros, temas e inicio; se conservan las regresiones de imagen estrecha, portadas grandes/panorámicas y slider accesible. Resultado final y límites: [QA](QA.md).

**Cleanup Plan:** [PLAN](PLAN.md) y [MEGA/UI](PLAN-MEGA-UI.md), con revisiones independientes en `analysis`. Primero resolver errores y fallbacks; después retirar código sin uso y duplicación, comprobar nombres/límites y cerrar las regresiones.

## Fallback Findings

| Hallazgo | Resolución |
| --- | --- |
| Variante sin JNI MEGA | Eliminada. MEGA es obligatorio; `preBuild` rechaza bibliotecas/bindings ausentes. Se retiraron source sets alternativos y su test de ausencia. |
| FileWrapper propio del cliente completo MEGA ausente | El SDK oficial dispone de ruta POSIX explícita. Se comprobó IO nativo sobre rutas privadas; no se inventaron clases del cliente ni se copiaron del original. |
| Caché MEGA con `basePath=null` | Corregida: directorio privado escribible en `getNoBackupFilesDir`. Null no desactiva la caché en este tag. |
| Precarga/portada falla | Conserva lectura actual y diagnóstico; portada placeholder y libro accesible. No anuncia recuperación del archivo. |
| Callback nativo termina tras timeout/cancelación | Conserva el temporal hasta callback terminal; propiedad probada en ambos órdenes y concurrencia. |
| Contador/documento remoto inválido | Error explícito y transacción intacta; no reinicia revisiones ni base. |
| Red/auth/quotas/TLS o desconexión fallan | Error visible o diagnóstico específico de pausa; no inventa éxito ni revocación remota. |

## UI/Design Findings

Blanco/gris/naranja y controles de plataforma con iconos propios. Se reutiliza navegación y acciones existentes. Portadas: un trabajador, cola32, caché8MiB, bitmap final192×288 y cancelación de pendientes al pausar. Lector: controles superpuestos, sin nueva decodificación solo por mostrarlos. Acciones con etiquetas y tamaño táctil mínimo48dp. Tablet emulada comprobada; TalkBack completo y hardware físico pendientes.

## Passes Completed

1. **Resolution gate:** errores y compatibilidad clasificados, autenticación real y ausencia de resultados ficticios.
2. **Dead code:** retirados source sets sin MEGA y constructor AAR alternativo anterior; una sola ruta Docker/Gradle. Sin capas para proveedores futuros, polling ni assets copiados.
3. **Duplicate removal:** importación converge en validación/persistencia compartidas; descargas MEGA se mueven tras hashear sin segunda escritura completa.
4. **Naming/error handling:** límites de entrada, transacciones, TLS normal, timeout y propiedad del temporal explícitos.
5. **Test reinforcement:** memoria, merge, ciclo de vida, accesibilidad, parsers, cancelación y JNI/IO reales. Relectura independiente de los dos fallos finales de UI.

## Quality Gates

| Gate | Resultado |
| --- | --- |
| Regression tests | PASS28 JVM +10 grupos Android en ambas ABIs. |
| Lint | 0 errores,23 avisos. |
| Build/typecheck | PASS app/SDK real/debug/release R8; ELF16KiB, APK firmado/alineado. |
| Static/security | Revisión acotada de límites, almacenamiento privado, sesiones cifradas, TLS y exclusión del original. No equivale a pentest. |

**Changed Files:** `MegaClient` único, módulo `mega-sdk`, scripts Docker fijados, `MainActivity`/`Ui`/vectores propios, `CoverCache`, `ReaderActivity`, regresiones Android y documentación. Se conservan validación, manejo de errores y protocolo de progreso.

**Remaining Risks:** cuenta real/2FA/transferencias/sync sin probar; conflictos por cómic completo; paridad parcial de formatos/QoL; batería y tablet física sin medir.

## Seguimiento0.3

Plan previo: [PLAN-LIBRARY](PLAN-LIBRARY.md). Se eliminó el explorador MEGA de descarga puntual y se reutilizaron los controles, caché de portadas y progreso existentes. Catálogo separado por cuenta/handle; no se inventan hashes para archivos remotos. Error de actualización → lista conservada con diagnóstico, clasificado como recuperación fundamentada y comprobada mediante rechazo transaccional. Carpeta consultada vacía y carpeta nunca consultada son estados distintos. Sin dependencias nuevas. Se comparte la confirmación de borrado local entre ambas bibliotecas y se eliminó la escritura redundante de identidad SDK. Temas usan el sistema de estilos Android y colores compartidos. La separación red/presentación del selector permite comprobar su botón Aceptar sin simular autenticación. No hubo revisión por un agente independiente de este cambio.

## Seguimiento 0.4: actualizaciones

Scope: ReleaseInfo, Updater, UpdateProvider, Main/manifest, publicación local y documentación. Plan previo: [PLAN-UPDATES](PLAN-UPDATES.md). Baseline 28 JVM; cierre previo a publicar: 31 JVM y 11 grupos Android PASS, build R8 PASS y lint sin errores (24 avisos). Se conservan tests de biblioteca, lector, cuentas y persistencia.

Fallback Findings: sin nueva release/versión igual → resultado explícito, no error oculto. APK universal → alternativa solo si falta el ABI nativo, probada. Error automático offline/HTTP → diagnóstico visible en Ajustes, sin interrumpir lectura; manual muestra causa. No se permite continuar con digest/firma/origen inválidos. Android confirma permisos e instalación. Se corrigió el uso de una API Java no disponible en API28; no se suprimió lint. La comprobación del provider usa apertura real, porque ContentResolver.getType devuelve null cuando el provider rechaza una URI.

Passes: reglas acotadas, un solo cliente OkHttp existente, sin dependencia ni servicio nuevo, provider de un único archivo, fichero parcial retirado, cancelación y verificación conservadas, script de release sin claves en Git/Actions. Sin shims de autenticación ni instalador silencioso. La app y release conservan el certificado previo. El test de actualización completa por red se registra después de publicar en QA; no se da por probado por un parser sintético. No hubo revisión independiente de este cambio.

Cierre de publicación: repo y release públicos; consulta/descarga/validación y handoff al instalador comprobados contra GitHub real. La verificación externa del sistema rechazó la instalación en el emulador, documentada como límite abierto en QA, sin bypass. Se retiraron de main los sidecars `.idsig` generados y se añadió su exclusión; contienen firmas públicas, no claves privadas. No se reescribe historial ni se fuerza el push.

## Seguimiento0.5: CBR/RAR

Scope: formato común, importador, PageSource/RarPages, enum del libro, MIME y mensajes; fixture/tests/licencias y release. Plan previo: PLAN-CBR. Behavior Lock:31→36 JVM,12 grupos Android x86_64/ARM64 PASS; build/R8/lint PASS. Sin cambios en cuentas, sync ni migración/borrado de datos.

Fallback Findings: se elimina el rechazo común de CBR; no se cambia solo la extensión ni se hace fallback ZIP cuando falla RAR. Contraseña, volumen, CRC, cabeceras y límites dan error. Replay sólido verifica predecesores explícitamente porque el skip del proveedor puede registrar e ignorar fallos; una página dañada no se publica como válida. Las salidas son arrays de tamaño declarado y las rutas de archivo no se usan como destinos. No se añaden hilos de extracción, binding JNI, conversión completa aZIP ni escaneo recursivo.

Passes: reutilizar filtro, caché, importación por movimiento y progress; lector RAR separado para testear sin Android; actualizar expectativa del test antiguo; fixtures propios y RAR5 real sólido; atribuciones no presentadas como MIT. Sin revisión independiente. Riesgos restantes y métodos efectivamente probados: QA-0.5.
