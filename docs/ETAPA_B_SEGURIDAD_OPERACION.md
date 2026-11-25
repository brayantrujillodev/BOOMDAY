# Etapa B — Seguridad, integridad, costos y preparación operativa (2026-11-25)

**Estado: COMPLETADA CON PENDIENTES NO BLOQUEANTES (para el alcance local).** Todo se desarrolló y probó **solo en local** (JVM, emuladores Firebase, AVD). No hay commits de la Etapa B, ni push, ni despliegue, ni contacto con Firebase real. Esto **no** es validación de producción.

Detalle por área: `ETAPA_B2_STORAGE_COSTOS.md` y `ETAPA_B3_MODERACION.md`. Historial de auditoría y Etapa A intactos.

## 1. Resumen ejecutivo
- Rama de trabajo: `etapa-b/seguridad-operacion` (derivada de `2c73199`). Cambios **sin commitear** (ver §5).
- Fase 0: rama `integracion/boomday-etapa-a` (= `2c73199`, los 5 commits de la Etapa A) subida a `origin`. `origin/dev` y `main` no se tocaron.
- B1 (identidad), B2 (Storage/costos), B3 (moderación) y B4 (recuperación/UX) implementados y verificados localmente.
- Una revisión de seguridad independiente rechazó una primera versión de B3 por 4 defectos bloqueantes; se corrigieron y se reverificó todo.
- Deuda abierta principal: carrera de evidencia (§9), controles de abuso apagados por defecto (dry-run), nada validado contra Firebase real.

## 2. Hallazgos tratados y causas raíz
| ID | Hallazgo | Causa raíz | Estado |
|---|---|---|---|
| B1 | `userName`/`userAvatar` falsificables | `videos` create no validaba esos campos; `/users/{uid}` sin validación | Corregido (reglas) |
| B1b | Publicar podía fallar con `permission-denied` si el token estaba desactualizado | El cliente usaba `displayName`/`photoUrl` locales, la regla compara contra claims del token | Corregido (Kotlin: claims del ID token) |
| B2 | Blobs huérfanos sin limpieza | Ciclo «subir blob → crear doc» sin recolección server-side | Corregido (función, dry-run por defecto) |
| B2 | Nombres/sobrescritura/subidas ilimitadas en Storage | Reglas solo validaban tamaño y contentType | Corregido (reglas + cuota en función) |
| B2 | Feed sin paginación | Listener sin límite sobre todos los videos del día | Corregido (cursor, 15 por página) |
| B3 | Reportes sin validación, duplicados, sin estados ni evidencia | `reports` aceptaba cualquier id/video/autor | Corregido |
| B4 | Errores técnicos en inglés en Feed/Explore; estados de error persistentes; Ranking obsoleto tras publicar; cargas sin salida | `e.message` crudo, sin reintento al volver, sin señal de publicación, sin timeouts | Corregido |

## 3. Arquitectura final (resumen)
- **Identidad:** ancla = claims `name`/`picture` del ID token firmado por Auth (no `/users`, que escribe el propio cliente). `videos` create: `userName == token.name` (o `''`), `userAvatar ∈ {'', token.picture}`, `hasOnly` de las 17 claves del modelo. `/users/{uid}`: validación de nombre, avatar (`''`, token o `lh[0-9].googleusercontent.com`), `blockedUsers` ≤ 500.
- **Storage:** solo `create` (sin sobrescribir), nombres `video_<10–16 dígitos>.(mp4|webm|3gp|mov)` / `thumb_<dígitos>.(jpg|jpeg|png|webp)`, tamaño > 0, límites 100 MB / 5 MB sin cambio. `moderation_evidence/**` cerrado a clientes.
- **Functions (no desplegadas):** `cleanupOrphanedBlobs` (cada 6 h, gracia 2 h, tope 200, freno de proporción, dry-run), cuota por usuario en `validateUploadedVideo` (20/24 h, dry-run), métricas `ops_metrics/{día}`, `onReportCreated` (copia de evidencia sin token de descarga), `purgeModerationEvidence` (dry-run), callable `moderatorRemoveVideo` (claim `moderator`, auditoría en `moderation_actions`).
- **Moderación:** reportes con id `<uid>_<videoId>` (un reporte por usuario y video), motivos cerrados, estados `pending → in_review → resolved`, lectura/edición solo por moderador (custom claim), sin borrado automático por número de reportes.
- **Cliente:** Feed paginado con reintento de página, errores clasificados (sin conexión / genérico) en español, auto-reintento en ON_RESUME (Ranking siempre que esté en error; Feed/Explore una vez por episodio), `PublishSignal` para refrescar el Ranking tras publicar.

## 4. Reglas implementadas
Ver `firestore.rules` y `storage.rules`. Las reglas de Firestore del emulador Android se parchean solo en una copia generada (`local-emulators/patch-rules.mjs`); las reglas del repositorio no se alteran para el entorno local.

## 5. Archivos modificados (sin commitear)
- Reglas/pruebas: `firestore.rules`, `storage.rules`, `rules-tests/firestore.rules.test.js`, `rules-tests/storage.rules.test.js`.
- Functions: `functions/src/index.ts`, `functions/package.json`; nuevos `orphans.ts`, `quota.ts`, `moderation.ts`, `orphans.test.ts`, `moderation.test.ts`.
- Kotlin (`app/src/main/...`): `UploadRepository`, `VideoRepository`, `ReportRepository`, `Report`, `UploadException`, `UploadErrorMapper`, `FeedViewModel/State/Screen`, `ExploreViewModel/Screen`, `RankingViewModel/Screen`, `UploadScreen`, `strings.xml`; nuevo `ui/util/LoadErrors.kt`.
- Pruebas JVM: `UploadRepositoryTest`, `BlockedUsersViewModelsTest` (adaptados); nuevos `FeedPaginationTest`, `UploadIdentityTest`, `ReportRepositoryTest`, `RecoveryB4Test`.
- Docs: este archivo, `ETAPA_B2_STORAGE_COSTOS.md`, `ETAPA_B3_MODERACION.md`, `PLAN_PRODUCCION_BOOMDAY.md`.
- Fuera de los cambios de la Etapa B (preexistentes, no tocar): Gradle Wrapper y `.vscode/`.

## 6. Pruebas ejecutadas (reejecutadas desde cero por el responsable de QA)
| Suite | Resultado |
|---|---|
| JVM `testDebugUnitTest --rerun-tasks` | **PASS 126/126** (0 fallos, 0 omitidas) |
| Reglas Firestore + Storage (`rules-tests`, emulador) | **PASS 88/88** |
| Functions `npm run build` + `npm test` | **PASS** build OK, 44/44 |
| `assembleDebug` / `assembleRelease` | **PASS** |
| `lintDebug` | **4 errores, todos preexistentes** (`CameraCaptureScreen` MissingPermission; `FeedScreen` UnsafeOptInUsageError ×3); sin errores nuevos |
| Aislamiento Release (dex/manifiesto/recursos, bytes y UTF-16) | **PASS**: 0 coincidencias de `LocalTestSignIn`, `debuglocal`, `boomday.test`, `demo-boomday`, `10.0.2.2`, `networkSecurityConfig`, `FAKE-local` |
| Escaneo de secretos de los 5 commits de la Etapa A | **PASS (manual, regex)**: solo valores ficticios; sin herramienta dedicada |
| Mutaciones (ver informes B2/B3/B4) | Detectadas por las pruebas en todos los casos cubiertos |

## 7. Pruebas funcionales en AVD (Pixel_6_2, variante `.local`, emuladores `demo-boomday`)
| Escenario | Resultado |
|---|---|
| Publicar con las reglas nuevas | **PASS**: doc con exactamente las 17 claves, `userName` = claim del token (`Alice`), `userAvatar = ''` |
| Feed paginado carga la primera página | **PASS** |
| Reporte in-app: 6 motivos en español | **PASS** |
| Reporte válido | **PASS**: doc `<uid>_seed-bob1`, `status=pending`, evidencia de 4 campos |
| Reporte duplicado | **PASS**: no crea segundo doc; muestra «Ya reportaste este video, o ya no está disponible.»; sin crash |
| Feed y Explore sin conexión (REJECT, caché limpia) | **PASS**: «Sin conexión. Revisa tu internet e inténtalo de nuevo.» (antes, texto del SDK en inglés) |
| Recuperación al volver a la pestaña | **PASS parcial**: el Ranking se recuperó solo (a ~48 s, por el backoff del SDK). Feed y Explore **no** se recuperaron solos (el auto-reintento es de una sola vez y se consumió durante el backoff); «Reintentar» manual sí los recupera |
| Ranking tras publicar | **PASS**: incluye el video nuevo sin acción manual |
| Subida sin red (watchdog 45 s) | **PASS**: error entre +39 y +47 s, sin documento, sin crash; el reintento conserva el título y publica (+1 doc) |
| Sin `FATAL EXCEPTION` en toda la sesión | **PASS** |

## 8. Revisión QA independiente
Un revisor de seguridad de solo lectura devolvió B3 con defectos bloqueantes; todos tratados:
1. La copia de evidencia heredaba el token de descarga del video (descargable aunque se retirara) → el token se anula y se verifica; si no se puede, falla cerrado.
2. Carrera: el dueño puede borrar su video antes de la copia → **no resuelto** (§9).
3. Videos con reporte abierto se borraban a las 24 h sin evidencia → solo `preserved`/`missing` permiten borrar; el resto retiene hasta 7 días.
7. Ids de video exóticos provocaban reintentos infinitos → charset restringido en reglas y estado `failed` sin reintento.
También: `hasOnly` en `videos` create (#5, parcial), validación del video actual contra el snapshot en el retiro (#9), no loguear la URL con token (#12), runbook de activación de controles (#4).

## 9. Riesgos residuales
- **Carrera de evidencia (ALTO, abierto):** el autor puede borrar su blob y doc entre el reporte y la copia (segundos); entonces solo queda el snapshot de metadatos. Las reglas de Storage no pueden consultar reportes por video. Mitigación futura: callable `deleteMyVideo` o un doc `video_flags` escrito por servidor.
- **Controles de abuso apagados por defecto:** limpieza de huérfanos, cuota y purga de evidencia están en dry-run hasta que el titular defina las variables (runbook en `ETAPA_B3_MODERACION.md` §10–11).
- Cuota de subidas actúa después de la subida; el usuario puede borrar sus blobs para eludirla. Una cuota dura requiere App Check/URLs firmadas.
- Abuso de reportes solo se marca (no se bloquea); un reporte marcado no copia evidencia y el video se retiene hasta 7 días.
- Hashtags sin validar por elemento (las reglas no iteran listas).
- Reportes, `report_limits` y `moderation_actions` sin purga/TTL.
- Feed ya no es en tiempo real: los videos nuevos de otros aparecen al refrescar (**cambio de comportamiento a aceptar por producto**).
- Feed/Explore: el auto-reintento es único por episodio; tras un corte largo puede hacer falta pulsar «Reintentar» (el primer reintento puede fallar por el backoff de Firestore).
- Nombres que suplanten a otra persona/marca no se previenen. Si se habilita otro proveedor de Auth (anónimo/correo) en consola, `token.name` puede ser vacío: verificar que solo Google esté activo.
- No verificado localmente: limpieza real del token en GCS, triggers/scheduler/callable desplegados, región/bucket del trigger, claims reales, comportamiento sobre redes y cuotas reales.

## 10. Estado de costos y cuotas
Límites de tamaño sin cambio (100 MB / 5 MB; riesgo conocido: `CameraX Quality.HIGHEST` a 4K podría superar 100 MB). Cuota 20 subidas/24 h por usuario (dry-run). Métricas diarias en `ops_metrics`. Recomendado antes de producción: presupuesto y alertas en GCP, regla de ciclo de vida GCS de respaldo, App Check (ver `ETAPA_B2_STORAGE_COSTOS.md`).

## 11. Estado de moderación
Reporte in-app, validación, estados y evidencia implementados; revisión solo por moderador (claim); retiro por callable auditada. **No hay panel**, notificaciones, apelaciones ni sanciones a cuentas. Pendientes de decisión del titular: retención de evidencia (propuesta 30 d tras resolver / 90 d máx.) y de reportes (12 meses), SLA (ilegal/sexual inmediato; violencia y acoso ≤ 24 h; spam/otro ≤ 72 h), quién es moderador y cómo se audita, límite diario (20), procedimiento legal ante sospecha de CSAM.

## 12. Requisitos aún pendientes para Google Play
Política de privacidad pública (correo placeholder), términos y normas de comunidad, Data Safety, eliminación de cuenta con su URL web y borrado en cascada de reportes/evidencia/`report_limits`, proceso de moderación operativo (panel o procedimiento) y contacto, decisión de edad/público, release firmado con R8, closed testing (12 testers × 14 días), escaneo de secretos del historial y de dependencias (sin ejecutar).

## 13. Recomendaciones de despliegue (no ejecutadas)
1. Cuenta Blaze, bucket y región confirmados; ajustar `region`/`bucket` de los triggers.
2. Desplegar **primero** reglas en un proyecto de pruebas; validar publicación con una cuenta Google real (claims `name`/`picture`) antes de producción.
3. Desplegar Functions con los tres controles en dry-run; revisar logs (`wouldDelete ≈ 0`) y activarlos de uno en uno.
4. Asignar el claim `moderator` solo a cuentas autorizadas (procedimiento en `ETAPA_B3_MODERACION.md`).
5. Presupuesto/alertas y App Check antes de abrir al público.

## 14. Dictamen QA
- **Local:** todas las suites en PASS; pruebas funcionales de AVD en PASS salvo el PASS parcial de recuperación automática de Feed/Explore (§7), no bloqueante.
- **Bloqueantes para producción (no para el cierre local):** carrera de evidencia (§9), activación y verificación de controles en un proyecto Firebase de pruebas, y los requisitos de Play (§12).
- Estado: **COMPLETADA CON PENDIENTES NO BLOQUEANTES (local)**.
