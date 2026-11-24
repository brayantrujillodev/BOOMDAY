# Etapa A.3 — Robustez: recuperación del Ranking y subida sin conexión (2026-11-24)

**Estado: COMPLETADO y cerrado con commits locales (ver §11)** (Ranking PASS, Subidas PASS, con matices en §7). Nada commiteado, subido ni desplegado. Todas las pruebas funcionales usaron la variante `.local` con Firebase Emulator Suite (`demo-boomday`).

## 1. Causas raíz
- **Ranking:** `RankingViewModel.loadRanking()` se lanzaba una sola vez en `init`; tras un error (timeout de bloqueados o fallo del listener) no había forma de reintentar sin reiniciar el proceso. Los mensajes eran el texto crudo del SDK.
- **Subida:** sin red el SDK de Storage reintenta hasta 10 min (`maxUploadRetryTimeMillis`) y la UI se quedaba en «Subiendo… 0%». Solo `saveVideo` tenía timeout. Además, «Reintentar» devolvía el formulario vacío.
- **Defecto hallado en el AVD (D-A3-1):** al cancelar el `UploadTask` el SDK entrega un último snapshot de progreso; si llegaba tras `Error(Timeout)` pisaba el estado terminal y la UI mostraba «Ocurrió un error inesperado».

## 2. Soluciones
- **Ranking:** `retry()` solo actúa desde `Error`; pasa a `Loading` de forma síncrona (la doble pulsación no repite), cancela `loadJob` antes de relanzar (el `awaitClose` de `getTopVideos` desregistra el listener). Sin polling. Botón «Reintentar» en `RankingScreen`; `RankingState.Error` lleva `@StringRes` (`ranking_error_offline` / `ranking_error_generic`).
- **Subida:** `StallWatchdog` de inactividad (45 s, parámetro `stallTimeoutMs`): solo se reinicia cuando `bytesTransferred` supera el máximo registrado; no hay timeout global. Al expirar: marca `stalled`, `uploadTask.cancel()`, emite `Error(Timeout)` y cierra; el `ERROR_CANCELED` resultante se ignora (no es «cancelado»). Un snapshot de progreso posterior se descarta (listener y `UploadViewModel.handleStatus`). El borrador (título, descripción, hashtags) se conserva al reintentar; el archivo temporal solo se borra en éxito o al descartar. `maxUploadRetryTimeMillis = 120 s` en `AppModule` como defensa en profundidad. `deleteFileByUrl` recurre a borrar por path `videos/{uid}/{archivo}` si `getReferenceFromUrl` falla (URLs del emulador). Sin `NetworkMonitor`: el watchdog basta.
- **Duplicados:** sin cambios en `UploadRepository`; siguen el `videoId` fijo por intento, el `get()` previo y `AlreadyPublished`/`IdConflict`.

## 3. Archivos modificados (sin commitear)
`ui/ranking/{RankingState,RankingViewModel,RankingScreen}.kt`, `res/values/strings.xml` (+3 `ranking_*`), `data/repository/StorageRepository.kt`, `ui/upload/{UploadState,UploadViewModel,VideoReviewScreen}.kt`, `di/AppModule.kt`; tests nuevos `ui/ranking/RankingViewModelRetryTest.kt`, `data/StorageStallWatchdogTest.kt`; ampliado `upload/UploadViewModelTest.kt`. No se tocaron reglas, `google-services.json`, `functions/`, wrapper ni `.vscode`.

## 4. Pruebas ejecutadas
JVM (`testDebugUnitTest`), reglas Firestore+Storage, `assembleRelease` + búsqueda de restos locales, `lintDebug`, `functions npm run build`, mutaciones, y pruebas funcionales en AVD con `iptables`/`tc`.

## 5. Resultados
| Prueba | Resultado |
|---|---|
| JVM | **93/93**, 0 fallos (reejecutado con `--rerun-tasks` en el cierre; +9 Ranking, +15 watchdog/carrera, +3 ViewModel/snapshot tardío) |
| Reglas Firestore + Storage | 42/42 |
| Release | compila; sin `LocalTestSignIn`, `demo-boomday`, `10.0.2.2`, `boomday.test` ni `networkSecurityConfig` |
| Lint | 4 errores, todos preexistentes; sin errores nuevos |
| Functions | build OK |
| Mutaciones | detectadas: watchdog que reinicia con bytes repetidos (3 tests fallan); quitar `loadJob?.cancel()` (falla `retry_releasesPreviousListener`); quitar el descarte de progreso tras `stalled` (falla el test nuevo) |

Cierre: tras el último cambio de código (decisión terminal atómica, §11) se reejecutaron JVM (93/93), reglas (42/42), `assembleRelease`, `lintDebug` (4 errores preexistentes) y `functions npm run build`.

## 6. Evidencia Android (AVD Pixel_6_2, `.local`)
Capturas y logcat en `scratchpad\a3\` y `scratchpad\a3b\`.
- **R1** Ranking carga (6 filas) PASS · **R2** sin conexión (REJECT y DROP, caché borrada): «Sin conexión. Revisa tu internet.» + «Reintentar», sin spinner eterno PASS · **R3** triple toque sin crash; al restaurar la red se recupera sin reiniciar la app PASS (tras el backoff del SDK: de inmediato a ~55 s; en la última ronda el primer reintento falló y el segundo cargó).
- **U1** corte sin progreso: error «La subida tardó demasiado…» a 44,7–48 s del último avance PASS. **U1-mid** corte a mitad (30 %): 45,004 s, mensaje correcto en 2/2 tras la corrección (antes 0/2) PASS. Sin crash, sin doc, sin «flow terminó sin estado terminal».
- **U2/U4-reintento** formulario conserva título, descripción y hashtags; publicar tras restaurar red crea exactamente +1 doc PASS · **U3** cancelación voluntaria → «Subida cancelada», sin salto a timeout PASS · **U5** subida normal PASS · **U4-lenta** (tbf 100 kbit/s, 84 s totales, nunca 45 s sin avance) no expira PASS · bloqueo de usuarios persiste tras reinicio PASS.

## 7. Defectos pendientes
- **D-A3-3 (baja):** el watchdog mide por chunk; a ~100 kbit/s el mayor intervalo entre avances fue 43,7 s (margen 1,3 s). Redes de ≲90 kbit/s con chunks grandes podrían expirar aun avanzando. Se mantiene 45 s por la orden; valorar subirlo (p. ej. 60 s) o medir por bytes.
- **Carrera watchdog/éxito:** mitigada en el cierre (§11); queda una ventana residual de microsegundos.
- **Ranking:** el primer «Reintentar» justo tras restaurar la red puede fallar por el backoff del SDK de Firestore (hasta ~1 min); la app no lo evita ni reintenta sola.
- **D-A3-2 / A2-4:** Feed y Explorar siguen mostrando texto técnico en inglés del SDK («Failed to get document because the client is offline.»); solo Ranking se tradujo. «Sin conexión» sigue hardcodeado en otras pantallas.
- `deleteFileByUrl` por path no valida uid ni bucket (las reglas de Storage impiden borrar blobs ajenos).
- Previos abiertos: D2–D7, A2-7, hashtags truncados/normalizados al reintentar.
- Warnings: 2 `UnusedResources` (`ranking_position_cd`, `ranking_loading_cd`, origen no verificado).

## 8. Riesgos residuales
- Emulación: Storage/Firestore emulados no reproducen latencias, cuotas ni el backoff real de producción; el comportamiento del SDK sobre redes móviles reales no se midió.
- El incidente de entorno (el emulador de Storage cayó por borrarse `%TEMP%\firebase\storage\blobs` durante pruebas en paralelo) no es un defecto de la app.
- `start.ps1` sigue sin ejecutarse.
- Sockpuppets en el ranking: Etapa B.

## 9. Revisión QA
QA independiente: **APROBADO** (0 bloqueantes). Revisó watchdog (sin flow sin estado terminal), `retry()`, borrador, `AppModule`, fallback de borrado, integridad de reglas/`google-services.json`/`functions`/wrapper (sha1 idénticos) y aislamiento de Release. QA en AVD halló D-A3-1, corregido y reverificado (3 escenarios + regresiones).

## 10. Recomendación de commits (a decidir por el titular)
1. `fix(ranking): permitir reintento con liberación de listeners y mensajes en español` — archivos de Ranking, strings `ranking_*`, `RankingViewModelRetryTest`.
2. `fix(upload): watchdog de inactividad, conservar borrador y evitar estados pisados` — `StorageRepository`, `UploadState`, `UploadViewModel`, `VideoReviewScreen`, `AppModule`, tests de subida.
Mantener fuera el wrapper de Gradle y `.vscode/`. Evaluar antes D-A3-3 (umbral 45 s vs 60 s).

## 11. Cierre definitivo

### 11.1 Análisis de la carrera watchdog ↔ éxito
- **¿Existía?** Sí. Tres fuentes pueden decidir el estado terminal desde hilos distintos: el watchdog (corrutina en `Dispatchers.IO`), y los listeners de éxito y de fallo/cancelación del SDK (hilo principal). Antes, cada una consultaba el flag `stalled` por separado y no había una decisión atómica. `watchdog.stop()` cancela el job pero no interrumpe un `onStall` ya en ejecución.
- **Escenarios reales:** (1) el SDK ya completó la subida y el callback de éxito está en cola cuando expira el watchdog → se emitía `Error(Timeout)` aunque el blob estaba completo (blob huérfano, sin documento; el reintento usa otro path y el mismo `videoId`: no hay duplicados de documento). (2) Un fallo/cancelación tardío tras un éxito ya reclamado podía emitir `Cancelled`/`Error`; en la práctica el canal ya estaba cerrado y `trySend` fallaba, pero dependía del orden.
- **Solución mínima (localizada, sin rediseño):** `terminalClaimed: AtomicBoolean` con `compareAndSet(false, true)`; solo la primera fuente en reclamar emite estado terminal y las demás se ignoran (watchdog, éxito y fallo). Además el watchdog no declara timeout si `uploadTask.isComplete` (deja que el listener de éxito reclame). El progreso se descarta una vez reclamado el estado.
- **Pruebas nuevas (StorageStallWatchdogTest):** éxito exactamente al expirar el watchdog gana y no se emite Timeout; éxito posterior a un Timeout reclamado se ignora; fallo/cancelación tardío tras un éxito no lo sobrescribe (con `downloadUrl` pendiente para que el canal siga abierto). **Mutaciones:** quitar la comprobación `isComplete` → falla el primer test; quitar el claim del listener de éxito → falla el tercero.
- **Residual (no resuelto del todo):** si la transferencia termina entre la comprobación `isComplete` y `cancel()` (microsegundos), se emite Timeout y el blob queda huérfano en Storage (no hay documento; la limpieza de blobs huérfanos corresponde a la Etapa B). Además, `downloadUrl.await()` tras el éxito no tiene timeout propio (lo acota el SDK). No se cambió el timeout de 45 s: no hay evidencia para cambiarlo (D-A3-3 documentada).

### 11.2 Verificaciones ejecutadas en el cierre
| Verificación | Resultado |
|---|---|
| JVM `testDebugUnitTest --rerun-tasks` | 93/93, 0 fallos |
| Reglas Firestore + Storage (`npm test`) | 42/42 |
| `assembleRelease` | OK; sin `LocalTestSignIn`, `demo-boomday`, `10.0.2.2`, `boomday.test`, `networkSecurityConfig` (búsqueda en bytes y UTF-16 sobre el APK descomprimido) |
| `lintDebug` | 4 errores (los preexistentes), 65 warnings |
| Functions `npm run build` | OK |
| Reglas, `google-services.json`, `firebase.json`, `functions/` vs HEAD | sin diferencias |
| Wrapper de Gradle | sha1 idénticos antes/después de los builds; sigue modificado y fuera de los commits |
| Pruebas AVD | ejecutadas antes del último cambio (§6); la decisión atómica solo cubre pruebas JVM (no se repitió AVD tras ese cambio) |

### 11.3 Commits locales
Hashes: ver `git log -2` (un commit no contiene su propio hash).
1. `fix(ranking): agregar recuperación y reintento seguro` — `RankingState`, `RankingViewModel`, `RankingScreen`, `strings.xml` (solo 3 cadenas `ranking_*`), `RankingViewModelRetryTest`.
2. `fix(upload): controlar inactividad y recuperar subidas` — `StorageRepository`, `AppModule`, `UploadState`, `UploadViewModel`, `VideoReviewScreen`, `UploadViewModelTest`, `StorageStallWatchdogTest`, este documento.
Excluidos: wrapper de Gradle y `.vscode/`. No hay archivos compartidos entre ambos commits.

### 11.4 Riesgos residuales y recomendación para la Etapa B
- Residuales: ventana de microsegundos descrita arriba; umbral de 45 s frente a redes ≲100 kbit/s; backoff de Firestore en el reintento del Ranking; textos técnicos en inglés en Feed/Explorar; emulación sin latencias ni cuotas reales.
- Recomendación: iniciar la Etapa B solo tras autorización; prioridades sugeridas: limpieza de blobs huérfanos y cuota, i18n de errores restantes, CI con las suites JVM/reglas, y repetir B2/U1 contra un proyecto Firebase de pruebas real antes de producción.
