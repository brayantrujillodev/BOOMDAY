# Etapa A — Resultados (2026-11-23)

**Estado de la Etapa A: COMPLETADA en su alcance local y automatizado, con verificación en dispositivo PENDIENTE.**
Los diez criterios de cierre se cumplen con evidencia ejecutada, salvo que ningún flujo se probó en un teléfono ni emulador Android (no disponible en este entorno). Nada se commiteó, subió ni desplegó. Producción: **NO APTO** (ver `docs/AUDITORIA_BOOMDAY.md` §18).

## 1. Resumen de cambios

| ID | Problema | Causa raíz | Corrección | Prueba | Resultado |
|---|---|---|---|---|---|
| P0-1 | Ranking manipulable | La regla de update de `views` usaba solo `existsAfter(views/{uid})`, verdadero también si la vista ya existía → `views+1` repetible | `firestore.rules`: exige `!exists(...)` previo **y** `existsAfter(...)`; el doc de dedupe solo admite `{viewedAt: int}` | `rules-tests/` (30 pruebas, emulador Firestore) | PASS 30/30; con la regla anterior fallan V04, V09, V11 (bug reproducido) |
| P0-2 | Fallo de subida podía cerrar la app | `callbackFlow` con `close(exception)` + `collect` sin `catch` en repositorio y ViewModel; `userId` lanzaba dentro del flow; sin timeout en `saveVideo` | `StorageRepository`/`UploadRepository`/`UploadViewModel`: el flow nunca termina con excepción, errores → `UploadStatus.Error` con mensajes en `strings.xml`, `CancellationException` relanzada y cancelación ≠ error, timeout de 30 s en `saveVideo`, `videoId` fijo por intento | `UploadErrorPropagationTest`, `UploadRepositoryTest`, `UploadViewModelTest`, `UploadErrorMapperTest` | PASS; la prueba de propagación FALLA con el código viejo y la mutación (volver a `close(exception)`) es detectada |
| P1-1 | Bloqueo de usuarios no persistía | `createdAt` se escribe como `Timestamp` pero `User.createdAt: Long` → `toObject` lanza, `getUser` devuelve `null`, `getBlockedUsers` devolvía `emptySet()` | `UserMapper` (acepta Timestamp/Long/Date/ausente), `getBlockedUsers` lee `blockedUsers` directo y devuelve `Result` (error ≠ vacío), `blockUser/unblockUser` con `set+merge`; Feed/Ranking/Explore fail-closed con reintento y timeout de 15 s | `UserMapperTest`, `UserRepositoryBlockTest`, `BlockedUsersViewModelsTest` | PASS; mutaciones (error→`emptySet()`, quitar timeout) detectadas |
| D1 | (hallado por QA) Reintento tras timeout daba `permission-denied`: un `set` sobre doc existente es un update que las reglas restringen | — | `saveVideo` hace `get()` previo: doc propio existente → `AlreadyPublished` (éxito, se borra el blob nuevo); doc de otro usuario → `IdConflict`; `get()` falla → continúa al `set` | `VideoRepositorySaveTest`, `UploadRepositoryTest`; QA verificó contra el emulador que el `set` sobre doc existente es rechazado y que `get()` está permitido | PASS (lógica y reglas); código real no ejecutado contra emulador |
| D3/D4 | (hallados por QA) Sin pruebas de bloqueo en ViewModels; sin timeout en Feed/Ranking | — | Pruebas añadidas; `withTimeoutOrNull(15 s)` fail-closed en Feed y Ranking | ver arriba | PASS |

## 2. Archivos modificados / nuevos (sin commitear)

Modificados: `firestore.rules`, `app/build.gradle.kts` (+`mockk`, `kotlinx-coroutines-test` solo en `testImplementation`), `gradle/libs.versions.toml`, `app/src/main/res/values/strings.xml` (+8 `upload_error_*`), y en `app/src/main/java/com/negociodigital/boomday/`: `data/repository/{StorageRepository,UploadRepository,UserRepository,VideoRepository}.kt`, `ui/{explore/ExploreViewModel,feed/FeedViewModel,ranking/RankingViewModel}.kt`, `ui/upload/{UploadScreen,UploadState,UploadViewModel}.kt`.
Nuevos: `data/model/{UploadException,UserMapper}.kt`, `ui/upload/UploadErrorMapper.kt`, pruebas bajo `app/src/test/java/com/negociodigital/boomday/{data,ui,upload}/`, `rules-tests/` (package.json, lockfile, firebase.json, test, .gitignore), y `docs/{AUDITORIA_BOOMDAY,PLAN_PRODUCCION_BOOMDAY,ETAPA_A_RESULTADOS}.md`.
**Fuera de alcance, preexistentes y NO tocados:** `gradle/wrapper/gradle-wrapper.{jar,properties}`, `gradlew`, `gradlew.bat` (wrapper cambiado a Gradle 9.7.1 por algo ajeno a este trabajo). Deben quedar fuera de los commits salvo que se apruebe esa migración por separado.

## 3. Pruebas ejecutadas (2.ª pasada de QA independiente)

Entorno: Windows 11, JDK 21 (JBR de Android Studio, solo por `JAVA_HOME` en la sesión), emulador de Firestore con `projectId` `demo-boomday`. Sin Firebase real ni credenciales.

| Prueba | Comando | Resultado |
|---|---|---|
| Compilación Android Debug | `./gradlew :app:compileDebugKotlin --rerun-tasks` | **PASS** |
| Pruebas unitarias | `./gradlew :app:testDebugUnitTest` | **PASS** — 61 tests, 0 fallos (UserMapper 8, UserRepositoryBlock 7, BlockedUsersViewModels 10, UploadErrorMapper 2, UploadErrorPropagation 2, UploadRepository 14, UploadViewModel 13, VideoRepositorySave 4, Example 1) |
| Lint | `./gradlew :app:lintDebug` | **4 errores, todos preexistentes** (`CameraCaptureScreen.kt:423` MissingPermission; `FeedScreen.kt:336` ×3 UnsafeOptInUsageError); sin errores nuevos. Comparación de los 65 warnings con una base: **NOT RUN** |
| Cloud Functions | `cd functions && npm run build` | **PASS** (sin cambios en `functions/`) |
| Reglas Firestore (nueva) | `cd rules-tests && npm test` | **PASS 30/30** |
| Reglas Firestore (anterior, `HEAD`) | misma suite con `RULES_PATH` | 27 PASS / **3 FAIL esperados** (V04, V09, V11) |
| D1 contra emulador | test en scratchpad con campos reales de `Video` | **PASS** |
| Mutaciones (prueba que detecta el bug) | worktree/copia en scratchpad | **PASS** (detectadas, eliminadas después) |
| Pruebas instrumentadas / E2E / en dispositivo | — | **NOT RUN** (sin emulador ni dispositivo Android) |
| Despliegue o prueba contra Firebase real | — | **NOT RUN** por diseño |

QA independiente: **APROBADO** (2.ª pasada; la 1.ª fue RECHAZADA por D1–D4, corregidos).

## 4. Problemas sin resolver y riesgos residuales

- **Sin verificación en dispositivo:** subida real con red caída/cancelación, persistencia del bloqueo tras reiniciar, estados de error de Feed/Ranking/Explore, comportamiento offline de Firestore.
- **Vistas:** una cuenta = una vista por video; quien cree muchas cuentas sigue pudiendo inflar el ranking, y las reglas no prueban que hubo reproducción. Siguiente paso: conteo en servidor + App Check.
- **D1:** si `get()` falla por red y el primer `set` ya se había confirmado, se cae al `set` y puede volver el `permission-denied` (ventana pequeña).
- `blockUser` con `set+merge` crearía un `users/{uid}` mínimo si el doc no existiera (improbable: el login lo crea).
- `UserRepositoryBlockTest` comprueba la forma de la llamada (`set` merge con clave `blockedUsers`), no que el valor sea un `arrayUnion` real.
- Deuda i18n: «Sin conexión» y mensajes de Feed/Ranking hardcodeados.
- Mapeo de `FirebaseFirestoreException.Code` no cubierto en JVM (el enum necesita runtime Android).
- Explore sin prueba de mutación propia; Feed/Ranking sin pruebas de UI.
- Docs `users` históricos con `createdAt` ausente/inválido se leen como 0.
- Sigue abierto todo lo no incluido en la Etapa A (Functions sin desplegar, moderación, release, costos, App Check, edad, i18n/accesibilidad, CI).

## 5. Respuestas a las preguntas de cierre

1. **Corregidos realmente:** P0-1, P0-2, P1-1 y los defectos D1, D3, D4 hallados por QA.
2. **Evidencia:** sección 1 y 3 (pruebas que fallan contra el código/regla vieja y pasan con la nueva; mutaciones detectadas).
3. **Pruebas ejecutadas:** sección 3.
4. **Pruebas que faltan:** dispositivo/emulador Android, instrumentadas, E2E, carga, línea base de lint, escaneo de dependencias y de secretos del historial.
5. **Archivos cambiados:** sección 2.
6. **Arquitectura preservada:** sí (MVVM/Hilt/Flow/`UploadStatus`); solo se añadieron `UploadException`, `UserMapper`, `UploadErrorMapper`, y `UploadState.Error` pasó a llevar un `@StringRes`.
7. **Vulnerabilidades críticas restantes:** ninguna de las identificadas como P0 queda abierta; persisten P1 (suplantación de nombre/avatar, reportes sin moderación ni límite, almacenamiento sin cuota) y sockpuppets en el ranking.
8. **Riesgo de pérdida de datos:** sin cambios destructivos ni migraciones; las reglas nuevas solo restringen escrituras; `set+merge` no pisa campos. Riesgo residual bajo documentado arriba.
9. **¿Se puede ejecutar BoomDay?** Compila y las pruebas pasan; no se ejecutó en un dispositivo. Para correr contra Firebase hace falta completar la migración a `boomday-e2308`.
10. **Qué impide publicar en Google Play:** ver `docs/PLAN_PRODUCCION_BOOMDAY.md` Etapa D (Functions, migración, moderación, release, política/Data Safety/eliminación web, closed testing 12×14).
11. **Siguiente trabajo prioritario:** verificar los tres flujos en dispositivo; luego validar `userName`/`userAvatar`, endurecer `reports` y paginar el Feed; en paralelo, las acciones del dueño en la consola de Firebase.
12. **Requieren autorización adicional:** commits/push, cualquier despliegue (reglas, Functions), cambios en Firebase Console, publicar en Play, y decidir qué hacer con el cambio de Gradle wrapper.
