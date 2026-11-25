# Etapa B2 - Storage, costos y huérfanos

## Escenarios de blob huérfano
1. Subida completa pero `saveVideo` falla/expira; el borrado del cliente es best-effort.
2. Proceso muerto entre subida y creación del doc.
3. Cancelación/watchdog tras completarse la transferencia (ventana mínima).
4. Reintento con el mismo `videoId`: `AlreadyPublished` borra el blob nuevo (best-effort) o, si "la última escritura gana", queda el viejo huérfano.
5. Docs con thumbnail que ya no existe / thumbnails sin doc.
6. Abuso: nombres arbitrarios, sobrescritura, subidas ilimitadas por usuario.

## cleanupOrphanedBlobs (functions/src/orphans.ts + index.ts)
- Cada 6 h. DRY-RUN por defecto; para borrar de verdad desplegar con `ORPHAN_CLEANUP_DRY_RUN=false` tras revisar logs del dry-run.
- Solo path exacto `videos|thumbnails/<uid>/<archivo>` (sin `..`, sin subcarpetas); lo demás se cuenta como `foreign` y jamás se toca.
- Gracia por defecto 2 h (`ORPHAN_GRACE_HOURS`, suelo duro 1 h no reducible). Justificación: el objeto solo aparece al finalizar la subida (una subida en curso no es listable); el doc debe crearse con `createdAt` dentro de +-10 min del servidor (regla de Firestore), así que un doc legítimo no puede aparecer horas después; 2 h es >10x ese margen y >>45 s de inactividad / 120 s de reintentos del SDK. Fecha ilegible o futura = en gracia.
- Referencia: consulta de rango sobre `videoUrl`/`thumbnailUrl` (índice de campo único automático, sin índice compuesto; `firestore.indexes.json` no cambia) por blob candidato + comparación exacta del path decodificado; prueba el bucket actual y los legados. Error de consulta = se asume referenciado.
- Re-verificación de referencia justo antes de borrar.
- Topes: `ORPHAN_MAX_DELETES`=200/corrida; freno: si huérfanos/evaluados > `ORPHAN_MAX_RATIO` (0.5) con muestra >= `ORPHAN_RATIO_MIN_SAMPLE` (20), aborta sin borrar y registra error. Presupuesto 240 s y máx. 50 000 listados. Errores por blob aislados. Fallo al listar = aborta sin borrar.
- Costo de lecturas: 1 consulta por blob con edad > gracia y vivo (~ videos vivos por corrida); un huérfano gasta hasta 4 consultas. Con 4 corridas/día es despreciable frente a la expiración de 24 h. Un backlog inicial grande puede disparar el freno: revisar en dry-run y subir `ORPHAN_MAX_RATIO` temporalmente.

## Cuotas y abuso
- storage.rules: solo `create` con `resource == null` (no sobrescribir; el emulador trata un overwrite como create, por eso la condición explícita), nombre `video_<10-16 dígitos>.(mp4|webm|3gp|mov)` / `thumb_<dígitos>.(jpg|jpeg|png|webp)`, tamaño > 0. Tamaños sin cambio (100 MB video, 5 MB thumb): 100 MB / 60 s = 13 Mb/s; reducirlo podría rechazar videos legítimos 1080p de CameraX (`Quality.HIGHEST`, que en 4K podría incluso superar 100 MB: riesgo existente a revisar en el cliente, fuera de este alcance).
- Cuota por usuario/24 h: las reglas de Storage no cuentan objetos, así que se hace en `validateUploadedVideo` (corre por cada objeto, ahora también para thumbnails): lista `videos|thumbnails/<uid>/`, ordena por creación y, si el objeto es el posición > `UPLOAD_QUOTA_PER_24H` (20), borra SOLO ese objeto. DRY-RUN por defecto (`UPLOAD_QUOTA_DRY_RUN=false` para activar). Fallo al listar = no borra. Si el excedente es un video con doc ya creado, el doc queda sin blob y lo borra `cleanupExpiredVideos` a las 24 h.
- No verificable localmente: el límite es "best-effort" tras la subida (el abuso ocurre antes de ser borrado); la cuota dura requeriría subir vía backend (URL firmada) o App Check.

## Observabilidad
- Logs estructurados con contadores (`listed, foreign, inGrace, evaluated, referenced, orphans, deleted, deleteFailed, lookupFailed, deferredByCap, brakeTripped, dryRun`).
- `ops_metrics/{yyyy-MM-dd}` (UTC): contadores incrementales escritos por Functions (`orphan_runs, orphan_listed, orphan_deleted, orphan_would_delete, orphan_failed, orphan_brake_trips, quota_exceeded, quota_deleted`). Reglas: `read, write: if false` (probado en M01).
- Recomendado (no ejecutado): presupuesto en Cloud Billing con alertas 50/90/100 %; alerta de logging sobre severity>=ERROR con `FRENO`; métrica de bytes almacenados del bucket; regla de ciclo de vida GCS de respaldo (borrar objetos de videos/ y thumbnails/ con edad > 7 días) como red de seguridad ante fallos de las Functions.

## Consultas Firestore revisadas
- `cleanupExpiredVideos`: ahora `select("videoUrl","thumbnailUrl","userId")` (menos ancho de banda); la consulta `createdAt <` usa índice de campo único.
- `validateUploadedVideo`: la consulta por `videoUrl` solo corre para objetos inválidos; sin cambios.

## Pruebas
`cd functions && npm test` (21 pruebas node:test con dobles en memoria) y `cd rules-tests && npm test` (emuladores locales, proyecto demo-boomday).
