# Plan hacia producción — BoomDay

Estado a 2026-11-23, tras la Etapa A. Detalle de hallazgos en `docs/AUDITORIA_BOOMDAY.md`; resultados de la Etapa A en `docs/ETAPA_A_RESULTADOS.md`.

**Leyenda:** ✅ Corregido y verificado · 🟡 Corregido, pendiente de prueba (dispositivo/entorno real) · ⏳ Pendiente · 🚫 Bloqueado · ❔ No evaluado.
Esfuerzo: S < 2 h · M ≈ medio día · L > 1 día (estimaciones con incertidumbre alta; no hay datos de velocidad del equipo).

## Estado consolidado

| Área | Estado |
|---|---|
| Integridad del ranking (regla de `views`) | ✅ verificado en emulador de Firestore · limitación: cuentas múltiples |
| Errores de subida / cancelación | 🟡 verificado en JVM con mocks · falta dispositivo |
| Persistencia del bloqueo de usuarios | 🟡 verificado en JVM con mocks · falta dispositivo/Firestore real |
| Reintento de publicación idempotente (D1) | 🟡 JVM con mocks; la compatibilidad con reglas se probó en emulador según QA (ver resultados) |
| Cloud Functions desplegadas | 🚫 requiere plan Blaze (acción del dueño) |
| Migración a `boomday-e2308` probada de punta a punta | 🚫 requiere Blaze, Storage, Google Sign-In, nuevo `google-services.json` |
| Moderación de reportes | ⏳ |
| Release (R8, firma, logs, `debug/`) | ⏳ |
| CI con tests | ⏳ |
| Verificación en dispositivo / emulador Android | ❔ no ejecutada |
| Auditoría de dependencias (CVE) | ❔ sin red/herramienta |
| Escaneo de secretos del historial (gitleaks) | ❔ |

## Etapa A — Estabilización (en curso: parte local hecha)

| Tarea | Prior. | Estado |
|---|---|---|
| Regla de `views` | P0 | ✅ |
| Errores de subida | P0 | 🟡 |
| Bloqueo persistente | P1 | 🟡 |
| Validar `userName`/`userAvatar` y endurecer `reports` (tipos, ids, existencia) | P1 | ⏳ (S–M) |
| Limitar/paginar el Feed | P1 | ⏳ (M) |
| Verificar en dispositivo los tres flujos corregidos | P1 | ❔ (S, requiere dispositivo/emulador) |

## Etapa B — Consolidación

| Tarea | Prior. | Estado |
|---|---|---|
| CI: `testDebugUnitTest`, `lintDebug`, `npm ci && npm run build` en `functions/`, tests de reglas con emulador | P1 | ⏳ (S–M) |
| Pipeline de reportes: `onDocumentCreated(reports)` → aviso, ocultamiento por umbral, conservar evidencia | P1 | ⏳ (M) |
| Limpieza de blobs huérfanos y cuota por usuario | P1 | ⏳ (M) |
| `getBlockedUsers` como `Flow` compartido | P2 | ⏳ (M) |
| Conteo de vistas en servidor + App Check | P2 | ⏳ (M–L) |
| Restringir `users` (`keys().hasOnly`, campos inmutables), validar tipos de `videos` y `dayKey` | P2 | ⏳ (S) |
| Corregir documentación (README, ARCHITECTURE) | P3 | ⏳ (S) |

## Etapa C — Experiencia de usuario

Miniaturas (M) · ocultar/implementar like-comentarios-compartir (S/M) · `BackHandler` y `rememberSaveable` en la subida (S) · textos a `strings.xml` (M) · accesibilidad: touch targets, contraste (M) · enlaces a política y «Mis videos» (M) · Ranking con reintento y cambio de día (S) · Feed sin recrear el reproductor (S) · caché/errores de ExoPlayer (M) · tema oscuro, splash, edge-to-edge (S). Todo ⏳.

## Etapa D — Producción

| Tarea | Estado |
|---|---|
| Plan Blaze, Storage activo, Google Sign-In, nuevo `google-services.json` | 🚫 acción del dueño |
| Desplegar reglas de Storage/Firestore y Functions en `boomday-e2308`; confirmar región del bucket y ajustar `validateUploadedVideo` | 🚫 |
| Prueba de punta a punta (login, subida, feed, ranking, bloqueo, borrado de cuenta) | ⏳ |
| Cerrar `boomday-85bef`; quitar el bucket viejo de `firestore.rules` | ⏳ |
| Release: R8 + reglas keep para modelos, firma/Play App Signing, `Timber` sin PII, mover `debug/` a `src/debug`, `versionCode` | ⏳ (M) |
| Presupuesto y alertas en GCP; restringir API key por paquete+SHA-1 | ⏳ (S) |
| Política de privacidad pública (cambiar correo placeholder), Data Safety (incluir Diagnósticos), URL de eliminación de cuenta | ⏳ (S) |
| Decisión de verificación de edad y público objetivo | ⏳ |
| Closed testing: 12 testers × 14 días | ⏳ (camino crítico de tiempo) |
| Decidir el nombre del paquete antes de la primera publicación | ⏳ |

## Etapa E — Evolución

Subida en segundo plano (WorkManager) y compresión (L) · navegación desde Explore al video (M) · búsqueda real (hashtags) (M) · «ranking local por ciudad» solo si se decide como producto (L) · contadores distribuidos si hay videos virales (M).

## Intervenciones que requieren autorización explícita del dueño

Cualquier despliegue (reglas, Functions) · cambios de plan/consola de Firebase · publicar en Play · commits/push · revertir o aprobar el cambio del Gradle wrapper (preexistente en el árbol, fuera de alcance).
