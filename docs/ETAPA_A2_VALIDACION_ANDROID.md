# Etapa A.2 — Validación Android con Firebase Emulator Suite (2026-11-23)

**Veredicto:** la Etapa A queda validada funcionalmente en emulador Android contra emuladores locales. Se halló y corrigió un defecto real (subidas rechazadas por falta de `contentType`). Quedan defectos de UX/robustez no bloqueantes (sección 7). Nada se commiteó, subió ni desplegó.

## 1. Arquitectura del entorno
- AVD `Pixel_6_2` (Android 17, x86_64, google_apis). El host es visible como `10.0.2.2`.
- Firebase Emulator Suite local: Auth 9099, Firestore 8080, Storage 9199 (UI 4000), proyecto `demo-boomday`.
- Variante Gradle `local` (`initWith(debug)`, `applicationIdSuffix ".local"`) con su propio `google-services.json` falso (`app/src/local/`).
- `LocalEnvironment` (sourceset `local`) conecta la app a los emuladores. Las variantes `debug` y `release` llevan una versión no-op.
- `LocalTestSignInActivity` (solo `local`) crea o inicia sesión de verdad en el emulador de Auth con usuarios `@boomday.test`. Google Sign-In no se tocó.
- Datos de prueba: `local-emulators/seed.mjs` (alice/bob/carol, 6 videos, un MP4 no reproducible, un PNG). Limpieza: `reset.mjs`.

## 2. Configuración de emuladores
`local-emulators/firebase.json` y `start.ps1`/`start.sh`. Las reglas se copian a `.generated/` y `patch-rules.mjs` parchea **solo la copia**: la regex de `isValidStorageUrl` acepta además `http://10.0.2.2:9199` y el bucket `demo-boomday`, porque el emulador de Storage devuelve URLs `http://` y con las reglas reales ninguna publicación local podría completarse. `firestore.rules` y `storage.rules` del repo no cambian (QA: idénticas a HEAD).

## 3. Medidas de aislamiento
- `check(projectId == "demo-boomday")` antes de `useEmulator` (fail-closed).
- Cleartext solo hacia `10.0.2.2`/`localhost`, solo en el manifiesto `local`.
- Auth de prueba únicamente en `src/local`; exige `@boomday.test`.
- Verificación del Release (QA): `assembleRelease` OK; 0 coincidencias de `LocalTestSignIn`, `debuglocal`, `boomday.test`, `demo-boomday`, `10.0.2.2`, `networkSecurityConfig` en dex, recursos y manifiesto. Solo existe la clase `LocalEnvironment` no-op. Control positivo en el APK `local`.
- `app/google-services.json`, reglas raíz, `firebase.json` raíz y `functions/` sin cambios. Wrapper de Gradle: hashes idénticos antes y después. `.vscode/` intacto.
- Sin cuentas ni datos personales; contraseña de prueba ficticia.
- Incidente: el emulador de Storage descargó por sí solo un jar de ~53 MB (autorización no solicitada). Se informó; no hubo más descargas y las pruebas posteriores usaron solo la caché.

## 4. Escenarios ejecutados
A login/persistencia/cierre/reinicio · B1 subida válida (cámara y galería) · B2 pérdida de conexión · B3 cancelación · B4 reintento sin duplicados · B5 permiso denegado · B6 timeout de `saveVideo` · C bloqueo y persistencia tras reinicio, perfil intacto · D Feed/Ranking/Explore (carga, datos, vacío, error sin caché, recuperación, navegación) · E integridad del ranking. Fallos de red selectivos con `adb root` + `iptables` (DROP/REJECT/throttle sobre 10.0.2.2:8080/9199).

## 5. Resultados
| Escenario | Resultado |
|---|---|
| A login, persistencia, logout, reinicio | PASS |
| B1 subida válida (doc + blob `video/mp4`, sin FATAL) | PASS (tras el fix) |
| B2 pérdida de conexión | PASS parcial: sin crash, pero sin aviso durante >10 min (D-A) |
| B3 cancelación (sin doc ni blob huérfano) | PASS |
| B4 reintento sin duplicados (+1 doc exacto) | PASS |
| B5 permission-denied real → mensaje en español, sin crash | PASS; sesión caducada: BLOCKED (el emulador no invalida tokens) |
| B6 timeout 30 s + reintento → `AlreadyPublished` | PASS |
| C bloqueo persiste tras reinicio, perfil intacto | PASS |
| D estados de Feed/Explore/Ranking | PASS con defectos (ver §7) |
| E ranking: una vista por usuario | PASS |
| Unit JVM | 65/65 |
| Reglas Firestore + Storage | 42/42 (30+12) |
| Lint | 4 errores, todos preexistentes |
| Functions `npm run build` | OK |

## 6. Evidencia
Capturas, logs y consultas REST en el scratchpad de la sesión (`scratchpad\a2\` ronda 1; `scratchpad\a2b\` ronda 2). Ejemplos: `b3-cancelled.png`, `b6-timeout.png`, `b2-error.png`, `dx-feed.png`, `dx-rank.png`. No contienen datos personales.

## 7. Errores detectados
| ID | Sev. | Descripción | Estado |
|---|---|---|---|
| A2-1 | Alta | Subidas rechazadas (403): faltaba `contentType` en Storage. Preexistente, solo visible funcionalmente | **Corregido** |
| A2-2 | Media | Sin red, la subida queda en «Subiendo… 0%» >10 min (el SDK reintenta 10 min) | Abierto |
| A2-3 | Media | Ranking en error no tiene reintento y no se recupera sin reiniciar | Abierto |
| A2-4 | Baja | Mensajes técnicos en inglés del SDK en Feed/Ranking; «Sin conexión» hardcodeado | Abierto |
| A2-5 | Baja | Tras error, «Reintentar» devuelve al formulario con el título vacío | Abierto |
| A2-6 | Baja | `deleteFileByUrl` usa `getReferenceFromUrl`: falla con URLs del emulador; blobs de reintento huérfanos (borrar por path sería más robusto) | Abierto |
| A2-7 | Baja | Botón grabar/detener cambia de posición; primer toque a veces ignorado | Abierto |
| Previos | — | D2 (sin timeout offline en subida), D3 (conteo de vistas propias), D4 (autobloqueo), D5, D6, D7, i18n | Abiertos |

## 8. Correcciones realizadas
- `StorageRepository`/`UploadRepository`: la subida envía `StorageMetadata` con `video/*` (tipo resuelto, con respaldo `video/mp4`) y `image/jpeg` para miniaturas. Pruebas: `StorageContentTypeTest` (4), ajustes en tests de subida, `rules-tests/storage.rules.test.js` (12).
- Infraestructura: variante `local`, `LocalEnvironment`, sign-in de prueba, `local-emulators/`, parche de reglas solo en copia generada.
- README de `local-emulators` corregido (jars de Storage y UI en caché).

## 9. Riesgos residuales
- La app ejecutada es la variante `.local`; no se probó Google Sign-In ni Firebase real. El parche de URL de las reglas locales implica que la validación de `isValidStorageUrl` con URLs reales de producción se cubre solo por las pruebas de reglas.
- `start.ps1` no se ejecutó (solo `start.sh`); usa rutas fijas del JBR.
- Sesión caducada (B5) no reproducida.
- El emulador no replica latencia, cuotas ni App Check de producción.
- Sockpuppets en el ranking siguen siendo posibles (Etapa B).

## 10. Recomendación
- La Etapa A tiene validación funcional suficiente para sus tres defectos (ranking, crash de subida, bloqueo persistente) y destapó un cuarto, ya corregido.
- Recomiendo **dos commits locales separados**: (1) `fix(upload): enviar contentType a Storage` con `StorageRepository`, `UploadRepository`, tests y `rules-tests/storage*`; (2) `test(infra): entorno local con Firebase Emulator Suite` con `app/build.gradle.kts`, `BoomDayApplication.kt`, `app/src/{local,debug,release}`, `local-emulators/`. Mantener fuera el wrapper de Gradle y `.vscode/`. No se ha hecho ningún commit.
- Antes de producción: A2-2 y A2-3 (y repetir B2 con Firebase real de pruebas).

## 11. Cierre controlado (commits locales)
- **Commit 1:** `c7b8127` — `fix(upload): enviar contentType correcto a Firebase Storage`. Incluye `StorageRepository`, `UploadRepository`, `UploadErrorPropagationTest`, `UploadRepositoryTest`, `StorageContentTypeTest`, `rules-tests/{firebase.json,package.json,storage.rules.test.js}`.
- **Commit 2:** `test(infra): agregar entorno Android aislado con Firebase Emulator Suite` — hash en `git log -2` (un commit no puede contener su propio hash). Incluye `app/build.gradle.kts` (build type `local`), `BoomDayApplication.kt` (una llamada a `LocalEnvironment.init`), `app/src/{local,debug,release}`, `local-emulators/` y este documento.
- **Excluidos:** `gradle/wrapper/*`, `gradlew`, `gradlew.bat` (modificados, sin tocar), `.vscode/`, `local-emulators/.generated/` y `*.log` (gitignored).
- **Separación de reglas:** `rules-tests/` lee siempre `firestore.rules`/`storage.rules` originales del repositorio; las reglas adaptadas al emulador Android existen solo como copia generada en `local-emulators/.generated/` (gitignored).
- **Aislamiento revalidado:** reglas, `app/google-services.json`, `firebase.json` raíz y `functions/` sin diferencias respecto a HEAD; `demo-boomday` y `10.0.2.2` solo aparecen en `app/src/local` y `local-emulators`; Release verificado por QA (sin sign-in de prueba ni config de red local).
- **Pruebas:** JVM 65/65, reglas 42/42, lint 4 errores preexistentes, `functions` build OK (ver §5).
- **Pendientes:** A2-2 a A2-7 y previos (§7). **Limitaciones de emulación y riesgos:** §9.
