# Seguimiento de revisión MEGA

El revisor independiente de nube examinó el adaptador y el constructor AAR mientras se preparaban. Sus hallazgos concretos se comunicaron antes de las siguientes correcciones del autor principal:

| Hallazgo del revisor | Resolución posterior |
| --- | --- |
| `connected` + `sync` descargaban el árbol dos veces en proceso frío | `connected` indica si ya obtuvo árbol; sync solo refresca si la sesión ya estaba lista. |
| Login que termina tarde podía dejar sesión nativa no guardada y bloquear el siguiente intento | Se conserva la referencia a la petición hasta el callback terminal; un login abandonado se desconecta localmente. Nuevas operaciones esperan o fallan explícitamente. |
| Timeout de subida eliminaba el archivo mientras JNI podía seguir leyéndolo | La vida del archivo queda en `TransferFile`, hasta el callback terminal. Cancelar no borra un recurso aún en uso. |
| Carrera cancelación/finalización podía dejar un destino huérfano | `discard` y `complete` sincronizados; pruebas de ambos órdenes y 100 carreras concurrentes. |
| El script usa opciones SDK Android que incluyen capacidades chat/sync del núcleo | Se corrigió la afirmación de exclusión en la documentación. No se desactivaron símbolos sin verificar bindings/JNI. |
| Permanencia de conexión nativa tras lectura local | Se solicita `localLogout` al pausar biblioteca y después del sync, conservando la sesión cifrada. Su efecto real requiere JNI. |

La separación de revisión/autoría se conserva: los hallazgos proceden de otro agente; las correcciones y este seguimiento final los escribió el autor principal. No constituyen aprobación de un JNI inexistente.

El autor generó bindings reales con SWIG 4.5.1 del SDK v10.21.0 y comprobó su compilación Java/Kotlin y la del adaptador. El build Gradle de Java con el AAR de tipos también pasó. Ese AAR no contiene `libmega.so`, permanece en `.analysis` y no se entrega como conexión funcional.

Las pruebas JVM verifican la propiedad de archivos durante cancelación/finalización, y la variante base verifica rechazo por SDK ausente. No se han ejecutado callbacks de red reales, login/2FA, cuota, revocación ni sustitución/versionado de snapshots en MEGA. La carpeta remota duplicada se trata como error; las instantáneas duplicadas no se borran automáticamente.

## Cierre de integración 0.2 por el autor

La instantánea anterior queda conservada como historial. Después se compiló e incluyó el SDK nativo oficial para ARM64/x86_64, se retiró la variante sin SDK y se probaron ambas ABIs: JNI, versión, fingerprint de archivos privados y callback de rechazo real de cuenta reservada inexistente. El APK optimizado ARM64 también responde desde su formulario. Cuentas reales y operaciones autenticadas siguen sin probar. Los resultados actuales sustituyen los pendientes de construcción anteriores: [QA](../QA.md).
