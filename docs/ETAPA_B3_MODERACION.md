# Etapa B3 - Moderación y reportes (MVP)

Alcance: reporte in-app endurecido, estados de moderación, conservación de evidencia y retiro autorizado.
NO hay panel administrativo; el moderador trabaja con la consola de Firestore o un script con Admin SDK.
NADA de esto está desplegado ni verificado contra un proyecto real (ver "Límites").

## 1. Flujo de estados

```
pending --(moderador)--> in_review --(moderador)--> resolved
                                                    resolution: dismissed | user_actioned   (cliente moderador)
                                                    resolution: content_removed              (solo Function moderatorRemoveVideo)
```
- `resolved` es terminal; no hay retrocesos, saltos (`pending -> resolved`) ni reapertura (reglas de Firestore).
- Campos de moderación: `status`, `resolution`, `reviewedBy` (uid del moderador, obligatorio == auth.uid), `reviewedAt` (ms, +-10 min).
- El moderador solo puede modificar esos 4 campos; el resto (reportId, reporterId, reason, evidence...) es inmutable.
- Ninguna regla ni Function elimina contenido por número de reportes. Toda acción irreversible requiere un moderador.

## 2. Creación del reporte (cliente)
Documento `reports/<reporterId>_<videoId>` (create-only => un reporte por usuario y video; el duplicado falla por permiso y la app muestra "Ya reportaste este video, o ya no está disponible").
Las reglas exigen: video existente, `reportedUserId` == autor del video, sin auto-reporte, `reason` en {spam, acoso, contenido_sexual, violencia, ilegal, otro}, `details` opcional <= 300, `status == 'pending'`, `createdAt` +-10 min, claves exactas (`hasOnly`), y `evidence` = {videoUrl, title, videoCreatedAt, authorName} idéntica al documento del video en ese momento.
Lectura: solo moderadores. El reportante NO lee ni sus propios reportes (evita represalias y enumeración; la app solo confirma el envío).

## 3. Autorización: claim `moderator`
Custom claim `moderator: true` en el ID token, asignado fuera de la app con Admin SDK. Nunca se asigna desde el cliente.
Procedimiento (ejecutarlo el titular, con credenciales propias, por proyecto; este repo NO lo ejecuta):
```js
// grant-moderator.js  (node grant-moderator.js <uid> [revoke])
const admin = require("firebase-admin");
admin.initializeApp(); // GOOGLE_APPLICATION_CREDENTIALS o `gcloud auth application-default login`
const [uid, mode] = process.argv.slice(2);
(async () => {
  const user = await admin.auth().getUser(uid);
  const claims = { ...(user.customClaims || {}) };
  if (mode === "revoke") delete claims.moderator; else claims.moderator = true;
  await admin.auth().setCustomUserClaims(uid, claims);
  await admin.auth().revokeRefreshTokens(uid); // fuerza renovar el token
  console.log("ok", uid, claims);
})();
```
El moderador debe cerrar sesión/renovar el token (`getIdToken(true)`) para que el claim aplique. Recomendación: 2-3 moderadores máximo, cuentas dedicadas con 2FA, revisión trimestral de quién tiene el claim. Los claims no deben llevar datos sensibles.

## 4. Evidencia
- `onReportCreated` (Firestore trigger, retry) copia el blob del video (Admin SDK) a `moderation_evidence/<reportId>/<archivo>` y guarda en el reporte `evidenceState` (`preserved | missing | skipped_video_cap | skipped_rate_limited | failed | purged`), `evidencePath`, `evidenceCopiedAt`, `evidenceExpiresAt`.
- Solo se copia el path `videos/<reportedUserId>/...` (no paths arbitrarios). Máximo 3 copias por video (los demás reportes quedan `skipped_video_cap`; el moderador usa la copia del primero). Thumbnail no se conserva.
- Storage: `moderation_evidence/**` con `allow read, write: if false` (solo Admin SDK).
- `cleanupExpiredVideos` consulta los reportes abiertos (pending/in_review) del video antes de borrar: si falta evidencia intenta copiarla; si no puede, RETIENE el video (reintenta cada hora) hasta 7 días desde su creación (tope para que no sea inmortal; pasado el tope se borra con log de error).
- `cleanupOrphanedBlobs` solo lista `videos/` y `thumbnails/` (probado: nunca toca `moderation_evidence/`). `validateUploadedVideo`/cuota solo actúan sobre `videos|thumbnails/<uid>/`.
- Purga `purgeModerationEvidence` (diaria, **dry-run por defecto**; borrar de verdad requiere desplegar con `EVIDENCE_PURGE_DRY_RUN=false`): borra evidencia 30 días después de `resolved` (`EVIDENCE_RETENTION_DAYS`, piso duro 7) o 90 días desde la copia aunque siga abierto. Solo el prefijo exacto `moderation_evidence/<reportId>/`, tope 50 reportes por corrida, idempotente.

## 5. Retiro de contenido (`moderatorRemoveVideo`, callable)
Exige claim `moderator`, `reportId` válido y reporte en `in_review`. Orden: (1) preserva evidencia (si falla, aborta sin tocar nada), (2) escribe `moderation_actions/remove_<reportId>` (solo servidor: acción, moderador, video, autor, estado de evidencia, fecha), (3) borra blob(s) del autor y el doc del video, (4) marca el reporte `resolved/content_removed`.
Decisión: BORRAR doc+blob (no marcar `moderation: removed`): Feed/Ranking/Explore/Perfil leen solo docs existentes, así que no hace falta ningún filtro Kotlin; una marca exigiría filtrar en cada consulta (Firestore no filtra por campo ausente) y el blob seguiría accesible por URL con token. La reversibilidad se cubre con la evidencia retenida.
El borrado de la cuenta u otras sanciones (`user_actioned`) son manuales; no hay automatización.

## 6. Abuso de reportes
Las reglas no pueden contar. `onReportCreated` mantiene `report_limits/<uid>` (contador diario UTC, solo servidor) y guarda `reporterDayCount` en el reporte. Sobre el límite (20/día, `REPORTS_PER_DAY_LIMIT`) el reporte se MARCA (`abuseFlag: reporter_over_daily_limit`), no copia evidencia y no se borra ni se descarta. Esto es mitigación parcial: el reporte hostil igual se crea en Firestore (costo de escritura) y sigue sujeto a revisión humana; no hay bloqueo del reportante. No se declara resuelto.

## 7. SLA operativo propuesto (decide el titular)
| Caso | Primera revisión |
|---|---|
| Sospecha de CSAM / contenido sexual con menores / amenaza inminente (`ilegal`, `contenido_sexual`) | Inmediato (<= 1-4 h); retirar, conservar evidencia y escalar según obligaciones legales (en Colombia, denuncia/reporte a las autoridades competentes; consultar asesoría legal) |
| `violencia`, `acoso` | <= 24 h |
| `spam`, `otro` | <= 72 h |
Ojo: los videos duran 24 h; con SLA > 24 h solo existe la copia de evidencia (por eso se conserva). Alineación con políticas UGC de Google Play: mecanismo de reporte in-app (hecho), bloqueo de usuarios (existente), moderación con acciones documentadas (este flujo). Falta publicar política de contenido/contacto en la ficha y la app (decisión del titular).

## 8. Datos guardados y retención
| Dato | Dónde | Retención |
|---|---|---|
| reporterId (uid), reportedUserId, videoId, reason, details, createdAt | `reports` | Sin purga automática implementada. Propuesta: 12 meses tras `resolved` (a decidir; requiere job adicional) |
| Snapshot título, URL del blob, fecha, nombre público del autor | `reports.evidence` | Igual que el reporte |
| Copia del video | `moderation_evidence/<reportId>/` | 30 d tras resolver, máx 90 d desde la copia |
| Contador diario del reportero | `report_limits/<uid>` | Sin purga automática (1 doc por reportero; propuesta: purgar > 30 d) |
| Auditoría de retiros | `moderation_actions` | Indefinida/mínima (uids + ids); decidir plazo |
Minimización: no se guarda email, nombre ni avatar del reportante; solo su uid. Los datos del autor son los ya públicos del video.

## 9. Límites conocidos
- Nada probado contra proyecto real: triggers, callable, claims y comportamiento del emulador de Storage con copias no se verificaron; solo reglas (emulador) y lógica con dobles.
- `PERMISSION_DENIED` en el cliente no distingue duplicado de "video cambió/borrado entre lectura y envío"; el mensaje cubre ambos.
- Si el autor edita el título entre que el reportante lee el video y envía, el reporte falla y debe reintentarse.
- Las Functions requieren índices de campo único (automáticos); la query `reportedVideoId == && evidenceState ==` usa igualdades (sin índice compuesto).
- Región/bucket de los triggers, `maxInstances` y variables de entorno deben revisarse antes de desplegar (igual que en B2). Despliegue de reglas y Functions pendiente.
- Moderación manual sin panel: los moderadores usan consola/script; la callable debe invocarse con un SDK autenticado con el claim.
- No hay notificación al reportante ni al autor, ni apelaciones, ni sanciones automáticas a cuentas.

## 10. Correcciones tras QA
- **Token de descarga:** `copy()` hereda `firebaseStorageDownloadTokens`, el mismo token que va en la `videoUrl` (visible para autenticados) y que permite bajar el blob ignorando storage.rules. Tras copiar se anula el token en el destino (`setMetadata`) y se verifica con `getMetadata`; si sigue presente, se BORRA el destino y el reporte queda `failed` (si ni borrar se puede, lanza y el trigger reintenta). Aplica también al retiro (usa la misma función). Probado con doble de Storage que hereda token; el comportamiento real de GCS no se pudo verificar localmente: confirmar en el emulador/proyecto de pruebas que `getMetadata().metadata.firebaseStorageDownloadTokens` queda vacío.
- **Compuerta de borrado:** con reporte abierto el video solo se borra si la evidencia es `preserved` (o `missing`: el blob realmente no existe). `skipped_video_cap` se reintenta forzando la copia; `skipped_rate_limited`, `failed` y `purged` retienen el video hasta 7 días desde su creación (log de aviso; pasado el tope se borra con log de error).
- **Ids:** las reglas exigen `reportedVideoId` y `reportId` con `[A-Za-z0-9_-]` (video <= 64, reporte 3-200; cubre UUID y auto-id). `preserveEvidence` marca `failed` sin lanzar ante ids inválidos (sin reintento infinito).
- **videos create:** `hasOnly` con las 17 claves de Video.kt y `hashtags is list`. Las reglas no pueden iterar la lista, así que el tipo/longitud de cada hashtag NO se valida (residual).
- **Retiro:** exige que el doc actual coincida con el reportado (autor, `videoUrl`, `createdAt` del snapshot); si el video ya no existe responde `ok:false, reason: video-not-found` sin auditar ni resolver (el moderador cierra el reporte manualmente, p. ej. `user_actioned`/`dismissed`).
- **Logs:** ya no se registra la download URL (lleva token).

### Riesgo residual: carrera de evidencia (NO resuelto)
El dueño puede borrar su blob/doc (storage.rules y firestore.rules le permiten borrar lo suyo) en los segundos entre el reporte y la copia del trigger; la evidencia queda `missing` (solo queda el snapshot de título/fecha/autor/url dentro del reporte). Evaluado: bloquear el borrado vía `firestore.get/exists` en storage.rules no es viable porque las reglas no pueden consultar "reportes del video" (los ids son `<reportero>_<video>`, sin query), y hacer el borrado solo vía callable rompería el flujo legítimo (el cliente borra su video y los blobs huérfanos en reintentos de subida). Mitigación: trigger sin cola previa, reintentos y snapshot en el reporte. Alternativa futura: callable `deleteMyVideo` o un doc `video_flags/<videoId>` escrito por servidor.

## 11. Runbook: dry-run y activación de borrados
Por defecto TODOS los borrados automáticos nuevos están en dry-run (solo loguean `wouldDelete`/`[dry-run]`): `ORPHAN_CLEANUP_DRY_RUN`, `UPLOAD_QUOTA_DRY_RUN`, `EVIDENCE_PURGE_DRY_RUN`; solo el literal `false` los activa. (`cleanupExpiredVideos` sí borra de verdad, como antes, ahora con la compuerta de evidencia.)
1. Configurar en `functions/.env.<projectId>` (o `functions/.env`) p. ej. `ORPHAN_CLEANUP_DRY_RUN=true`, `UPLOAD_QUOTA_DRY_RUN=true`, `EVIDENCE_PURGE_DRY_RUN=true` (explícito aunque sea el valor por defecto) y desplegar en el proyecto de pruebas primero.
2. Dejar correr al menos varios ciclos y revisar los logs de Cloud Functions: `orphans: [dry-run] se borraría`, `quota ... exceeded-dry-run`, `purge: [dry-run]`, y las métricas en `ops_metrics`.
3. Activar un flag a la vez cuando lo logueado como `wouldDelete` sea el esperado (cercano a 0 para huérfanos en operación normal; la evidencia vencida solo a los 30/90 días): cambiar a `false` en el `.env`, redesplegar solo esa función y vigilar el siguiente ciclo.
4. Revertir = volver a `true` y redesplegar. Los topes (`ORPHAN_MAX_DELETES`, `EVIDENCE_PURGE_MAX_REPORTS`) limitan el daño por corrida.
