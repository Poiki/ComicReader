# Componentes ajenos

El código propio usa MIT. Las licencias de las dependencias conservan su alcance:

| Componente | Uso | Licencia / condiciones |
| --- | --- | --- |
| [OkHttp 4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0) y [Okio](https://github.com/square/okio) | HTTPS, cancelación y WebDAV | Apache-2.0 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | Dependencias OkHttp y SDK MEGA | Apache-2.0 |
| [Java API desugaring](https://github.com/google/desugar_jdk_libs) | Compatibilidad Android anterior | GPL-2.0 con Classpath Exception y avisos incluidos por la herramienta |
| [Google Play services Auth](https://developers.google.com/android/guides/overview) | Autorización directa Drive | Condiciones del SDK de Google; componente de Google, no código propio MIT |
| [MEGA SDK v10.21.0](https://github.com/meganz/sdk/tree/v10.21.0) | JNI incluido, criptografía y API MEGA | BSD-2-Clause y licencias de sus dependencias nativas; sus avisos se incluyen como assets del APK |
| [Junrar8.1.2](https://github.com/junrar/junrar/tree/v8.1.2) | Decodificar CBR/RAR, incluido RAR5 | Licencia UnRAR: permite redistribución/lectura, prohíbe usar sus fuentes para recrear compresión RAR; no es MIT ni licencia OSI. Texto incluido en assets/licenses/junrar.txt. |
| [SLF4J2.0.17](https://github.com/qos-ch/slf4j/tree/v_2.0.17) | API de diagnóstico requerida por Junrar | MIT, texto incluido en assets/licenses/slf4j.txt. |
| [JUnit 4.13.2](https://github.com/junit-team/junit4) | Solo pruebas JVM | EPL-1.0 |
| [Gradle](https://github.com/gradle/gradle) | Wrapper y construcción | Apache-2.0 |

La lectura local funciona sin cuenta ni Google Play services. El login Google sí depende de los servicios de Google instalados. Esta entrega ofrece código propio abierto; no afirma que todos los binarios de Google sean software libre.

El APK de referencia, su decompilación y sus recursos **no son parte de la aplicación ni de la distribución abierta**. Los informes identifican hechos observados y fuentes; los bundles completos de evidencia se mantienen locales, excluidos por `.gitignore`. El proyecto no ha importado marca, assets ni bibliotecas de ese APK.
