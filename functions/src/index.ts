import { onSchedule } from "firebase-functions/v2/scheduler";
import { onObjectFinalized } from "firebase-functions/v2/storage";
import { logger } from "firebase-functions";
import * as admin from "firebase-admin";
import { BlobInfo, BlobKind, OrphanDeps, configFromEnv, extractStoragePath as extractPathExact, runOrphanCleanup } from "./orphans";
import { enforceUploadQuota, quotaFromEnv } from "./quota";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import {
  EvidenceDeps, GateDeps, ModerationError, PurgeCandidate, ReportDoc,
  evidenceGateForVideo, handleNewReport, preserveEvidence, purgeConfigFromEnv, removeReportedVideo,
  reportLimitFromEnv, runEvidencePurge, EVIDENCE_ROOT
} from "./moderation";

admin.initializeApp();

const EXPIRATION_MS = 24 * 60 * 60 * 1000;
const CLEANUP_BATCH_SIZE = 200;
// timeoutSeconds = 300; se corta a los 240 s para terminar limpio.
const CLEANUP_TIME_BUDGET_MS = 240 * 1000;

/**
 * Extrae el path dentro del bucket a partir de una download URL de Firebase Storage
 * (formato https://firebasestorage.googleapis.com/v0/b/<bucket>/o/<path-encoded>?alt=media&token=...).
 * VideoRepository/UserRepository guardan siempre esta URL completa, nunca el path
 * crudo, así que hay que revertir el encoding para poder borrar el blob real.
 */
function extractStoragePath(downloadUrl: string): string | null {
  const match = downloadUrl.match(/\/o\/([^?]+)/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

/**
 * Borra el blob apuntado por una download URL SOLO si su path empieza por `allowedPrefix`
 * (p. ej. `videos/<uid>/`). Las credenciales Admin ignoran storage.rules, y los docs de
 * Firestore los escribe el cliente: sin este chequeo, un usuario podría apuntar su
 * videoUrl/thumbnailUrl al blob de otro y hacer que esta función lo borre.
 */
async function deleteStorageObjectIfPresent(
  downloadUrl: string | undefined,
  allowedPrefix: string
): Promise<void> {
  if (!downloadUrl) return;

  const path = extractStoragePath(downloadUrl);
  if (!path) {
    // No se loguea la URL completa: contiene el token de descarga.
    logger.warn(`cleanupExpiredVideos: no se pudo extraer el path de Storage (url de ${downloadUrl.length} caracteres)`);
    return;
  }
  if (!path.startsWith(allowedPrefix) || path.includes("..")) {
    logger.warn(`cleanupExpiredVideos: path ${path} fuera de ${allowedPrefix}, no se borra`);
    return;
  }

  try {
    await admin.storage().bucket().file(path).delete({ ignoreNotFound: true });
  } catch (error) {
    // No se relanza: un blob que falla al borrarse no debe abortar la limpieza del
    // resto de videos vencidos en este mismo ciclo.
    logger.error(`cleanupExpiredVideos: error borrando blob de Storage (${path})`, error);
  }
}

/**
 * Borra videos vencidos (>24h desde createdAt): el blob de video, el thumbnail (si
 * existe) y el documento de Firestore junto con su subcolección `views` (el dedupe de
 * vistas de VideoRepository.incrementViews, que Firestore no borra automáticamente al
 * borrar el doc padre).
 *
 * Corre cada hora. `createdAt` en Video es un Long (epoch millis) escrito por el
 * cliente, no un Timestamp de Firestore — la comparación numérica de abajo coincide
 * con el mismo campo/formato que ya usa VideoRepository.getTodayVideos().
 */
export const cleanupExpiredVideos = onSchedule(
  {
    schedule: "every 60 minutes",
    timeZone: "America/Bogota",
    timeoutSeconds: 300,
    memory: "256MiB",
    maxInstances: 1
  },
  async () => {
    const db = admin.firestore();
    const cutoff = Date.now() - EXPIRATION_MS;
    const startedAt = Date.now();
    const seen = new Set<string>();
    let deleted = 0;
    let held = 0;

    // Tandas acotadas: evita cargar toda la colección en memoria y respeta el timeout.
    while (Date.now() - startedAt < CLEANUP_TIME_BUDGET_MS) {
      const snapshot = await db
        .collection("videos")
        .where("createdAt", "<", cutoff)
        .select("videoUrl", "thumbnailUrl", "userId", "createdAt") // solo lo que se usa: menos ancho de banda
        .limit(CLEANUP_BATCH_SIZE)
        .get();

      const fresh = snapshot.docs.filter((d) => !seen.has(d.id));
      if (fresh.length === 0) break; // vacío, o solo quedan docs que ya fallaron en esta corrida

      for (const doc of fresh) {
        if (Date.now() - startedAt >= CLEANUP_TIME_BUDGET_MS) break;
        seen.add(doc.id);
        const video = doc.data();
        try {
          // B3: un video con reporte abierto no se borra hasta preservar su evidencia.
          const gate = await evidenceGateForVideo(gateDeps, doc.id, Number(video.createdAt) || 0, Date.now());
          if (!gate.allow) {
            logger.warn(`cleanupExpiredVideos: video ${doc.id} retenido (${gate.reason})`);
            held++;
            continue;
          }
          await Promise.all([
            deleteStorageObjectIfPresent(video.videoUrl, `videos/${video.userId}/`),
            deleteStorageObjectIfPresent(video.thumbnailUrl, `thumbnails/${video.userId}/`)
          ]);
          await db.recursiveDelete(doc.ref);
          deleted++;
        } catch (error) {
          // Se reintenta en la próxima corrida horaria.
          logger.error(`cleanupExpiredVideos: error borrando video ${doc.id}`, error);
        }
      }

      if (snapshot.size < CLEANUP_BATCH_SIZE) break;
    }

    logger.info(`cleanupExpiredVideos: ${deleted} videos vencidos borrados, ${held} retenidos por reporte abierto`);
  }
);

// ---------------------------------------------------------------------------
// Adaptadores reales (Admin SDK) para la lógica pura de orphans.ts / quota.ts
// ---------------------------------------------------------------------------

function dayKeyUtc(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

/** Contadores diarios en ops_metrics/{yyyy-MM-dd} (colección solo-servidor: las reglas deniegan a clientes). */
async function recordMetrics(counters: Record<string, number>): Promise<void> {
  try {
    const inc: Record<string, unknown> = { updatedAt: Date.now() };
    for (const [k, v] of Object.entries(counters)) {
      if (v) inc[k] = admin.firestore.FieldValue.increment(v);
    }
    await admin.firestore().collection("ops_metrics").doc(dayKeyUtc(Date.now())).set(inc, { merge: true });
  } catch (error) {
    logger.error("recordMetrics: no se pudo escribir ops_metrics", error);
  }
}

function toBlobInfo(f: { name: string; metadata: Record<string, unknown> }): BlobInfo {
  const t = Date.parse(String(f.metadata.timeCreated ?? ""));
  const u = Date.parse(String(f.metadata.updated ?? ""));
  const valid = [t, u].filter((x) => Number.isFinite(x));
  return { name: f.name, createdMs: valid.length ? Math.max(...valid) : null };
}

// Buckets cuyas download URLs pueden aparecer en docs (nuevo y legado durante la migración).
// El real va primero: un blob referenciado cuesta 1 consulta; solo un huérfano agota las 4.
const URL_BUCKETS = (process.env.URL_BUCKETS ??
  "boomday-e2308.firebasestorage.app,boomday-e2308.appspot.com,boomday-85bef.firebasestorage.app,boomday-85bef.appspot.com")
  .split(",").map((s) => s.trim()).filter(Boolean);

/** Consulta de rango sobre un único campo (índice automático, sin índice compuesto) + comparación exacta del path decodificado. */
async function isPathReferenced(path: string, kind: BlobKind): Promise<boolean> {
  const field = kind === "video" ? "videoUrl" : "thumbnailUrl";
  const db = admin.firestore();
  for (const bucket of URL_BUCKETS) {
    const prefix = `https://firebasestorage.googleapis.com/v0/b/${bucket}/o/${encodeURIComponent(path)}?`;
    const snap = await db
      .collection("videos")
      .where(field, ">=", prefix)
      .where(field, "<", prefix + "")
      .select(field)
      .limit(5)
      .get();
    if (snap.docs.some((d) => extractPathExact(String(d.get(field) ?? "")) === path)) return true;
  }
  return false;
}

const structuredLog: OrphanDeps["log"] = (level, message, data) => logger[level](message, data ?? {});

/**
 * Borra blobs de videos/ y thumbnails/ sin doc en Firestore y con edad > gracia (2 h por
 * defecto, mínimo duro 1 h). DRY-RUN por defecto: para activar el borrado real hay que
 * desplegar con ORPHAN_CLEANUP_DRY_RUN=false (decisión del titular tras revisar los logs).
 * Parámetros: ORPHAN_GRACE_HOURS, ORPHAN_MAX_DELETES, ORPHAN_MAX_RATIO, ORPHAN_RATIO_MIN_SAMPLE.
 */
export const cleanupOrphanedBlobs = onSchedule(
  {
    schedule: "every 6 hours",
    timeZone: "America/Bogota",
    timeoutSeconds: 300,
    memory: "256MiB",
    maxInstances: 1
  },
  async () => {
    const bucket = admin.storage().bucket();
    const cfg = configFromEnv(process.env);
    const stats = await runOrphanCleanup(
      {
        now: Date.now,
        log: structuredLog,
        listPage: async (prefix, pageToken) => {
          const [files, nextQuery] = await bucket.getFiles({
            prefix,
            maxResults: 500,
            pageToken,
            autoPaginate: false
          });
          return {
            blobs: files.map((f) => toBlobInfo({ name: f.name, metadata: f.metadata as Record<string, unknown> })),
            nextToken: (nextQuery as { pageToken?: string } | null | undefined)?.pageToken
          };
        },
        isReferenced: isPathReferenced,
        deleteBlob: async (path) => {
          await bucket.file(path).delete({ ignoreNotFound: true });
        }
      },
      cfg
    );
    await recordMetrics({
      orphan_runs: 1,
      orphan_listed: stats.listed,
      orphan_deleted: stats.deleted,
      orphan_would_delete: stats.wouldDelete,
      orphan_failed: stats.deleteFailed + stats.lookupFailed,
      orphan_brake_trips: stats.brakeTripped ? 1 : 0
    });
  }
);

async function applyUploadQuota(objectPath: string, bucketName: string): Promise<boolean> {
  const { limit, dryRun } = quotaFromEnv(process.env);
  const bucket = admin.storage().bucket(bucketName);
  const result = await enforceUploadQuota(
    {
      now: Date.now,
      log: structuredLog,
      listUserBlobs: async (prefix) => {
        const [files] = await bucket.getFiles({ prefix, autoPaginate: true, maxResults: 1000 });
        return files.map((f) => toBlobInfo({ name: f.name, metadata: f.metadata as Record<string, unknown> }));
      },
      deleteBlob: async (path) => {
        await bucket.file(path).delete({ ignoreNotFound: true });
      }
    },
    objectPath,
    limit,
    dryRun
  );
  if (result.action === "exceeded-deleted" || result.action === "exceeded-dry-run") {
    await recordMetrics({ quota_exceeded: 1, quota_deleted: result.action === "exceeded-deleted" ? 1 : 0 });
  }
  return result.action === "exceeded-deleted";
}

// ---------------------------------------------------------------------------
// Validación server-side de videos subidos
// ---------------------------------------------------------------------------

type StorageFile = ReturnType<ReturnType<ReturnType<typeof admin.storage>["bucket"]>["file"]>;

// Deben mantenerse alineados con storage.rules (100 MB) y UploadLimits en la app
// (MAX_DURATION_SECONDS = 60).
const MAX_VIDEO_BYTES = 100 * 1024 * 1024;
const MAX_DURATION_SECONDS = 60;
// La app trunca la duración a segundos enteros y el muxer puede redondear hacia arriba.
const DURATION_TOLERANCE_SECONDS = 1;
// Tope de lectura del átomo moov (solo metadatos; el video real vive en mdat).
const MAX_MOOV_BYTES = 8 * 1024 * 1024;
const MAX_TOP_LEVEL_BOXES = 64;

async function readRange(file: StorageFile, start: number, length: number): Promise<Buffer> {
  if (length <= 0) return Buffer.alloc(0);
  const [buf] = await file.download({ start, end: start + length - 1 });
  return buf;
}

type Container = "mp4" | "webm" | "unknown";

function detectContainer(head: Buffer): Container {
  // ISO BMFF (MP4/MOV/3GP): bytes 4..8 == "ftyp".
  if (head.length >= 12 && head.toString("latin1", 4, 8) === "ftyp") return "mp4";
  // Matroska/WebM: EBML header 1A 45 DF A3.
  if (head.length >= 4 && head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3) {
    return "webm";
  }
  return "unknown";
}

/** Busca mvhd dentro del contenido de moov y devuelve la duración en segundos (o null). */
function durationFromMoov(moov: Buffer): number | null {
  let offset = 0;
  while (offset + 8 <= moov.length) {
    const size = moov.readUInt32BE(offset);
    const type = moov.toString("latin1", offset + 4, offset + 8);
    if (size < 8) return null;
    if (type === "mvhd") {
      const body = offset + 8;
      const version = moov[body];
      let timescale: number;
      let duration: number;
      if (version === 1) {
        if (body + 32 > moov.length) return null;
        timescale = moov.readUInt32BE(body + 20);
        duration = Number(moov.readBigUInt64BE(body + 24));
      } else {
        if (body + 20 > moov.length) return null;
        timescale = moov.readUInt32BE(body + 12);
        duration = moov.readUInt32BE(body + 16);
      }
      if (timescale === 0) return null;
      return duration / timescale;
    }
    offset += size;
  }
  return null;
}

/**
 * Recorre los átomos de primer nivel del MP4 (con lecturas por rango, sin bajar el
 * archivo entero) hasta encontrar moov y devuelve la duración de mvhd.
 * - found=false: no hay moov, el archivo no es un MP4 reproducible.
 * - found=true y seconds=null: moov existe pero la duración no es verificable
 *   (p. ej. MP4 fragmentado con duración 0) — no se rechaza por duración.
 */
async function readMp4Duration(
  file: StorageFile,
  totalSize: number
): Promise<{ found: boolean; seconds: number | null }> {
  let offset = 0;
  for (let i = 0; i < MAX_TOP_LEVEL_BOXES && offset + 8 <= totalSize; i++) {
    const header = await readRange(file, offset, 16);
    if (header.length < 8) break;
    let size = header.readUInt32BE(0);
    const type = header.toString("latin1", 4, 8);
    let headerLen = 8;
    if (size === 1) {
      if (header.length < 16) break;
      size = Number(header.readBigUInt64BE(8));
      headerLen = 16;
    } else if (size === 0) {
      size = totalSize - offset; // el átomo llega hasta el final del archivo
    }
    if (size < headerLen) break;

    // mdat con tamaño 0 = "hasta el final del archivo": ningún moov puede venir después.
    // Sin moov previo no se puede verificar la duración (p. ej. moov al final no es posible
    // aquí), así que no se rechaza.
    if (type === "mdat" && header.readUInt32BE(0) === 0) {
      return { found: true, seconds: null };
    }

    if (type === "moov") {
      const bodyLen = Math.min(size - headerLen, MAX_MOOV_BYTES, totalSize - offset - headerLen);
      const body = await readRange(file, offset + headerLen, bodyLen);
      return { found: true, seconds: durationFromMoov(body) };
    }
    offset += size;
  }
  return { found: false, seconds: null };
}

async function deleteInvalidVideo(
  path: string,
  userId: string,
  reason: string,
  bucketName: string
): Promise<void> {
  logger.warn(`validateUploadedVideo: rechazado ${path}: ${reason}`);

  try {
    await admin.storage().bucket().file(path).delete({ ignoreNotFound: true });
  } catch (error) {
    logger.error(`validateUploadedVideo: error borrando blob inválido (${path})`, error);
  }

  // El cliente sube primero el blob y luego crea el doc de Firestore, así que lo normal es
  // que el doc todavía no exista aquí. Si ya existe (reintento / subida lenta), se borra
  // junto con su thumbnail y su subcolección views.
  try {
    const db = admin.firestore();
    // La download URL es https://firebasestorage.googleapis.com/v0/b/<bucket>/o/<path-encoded>?alt=media&token=...
    // El token no se conoce, pero el prefijo hasta "?" sí: consulta por rango sobre videoUrl
    // (índice de campo único automático, sin índice compuesto) y se filtra el dueño en código.
    const prefix = `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encodeURIComponent(path)}?`;
    const snapshot = await db
      .collection("videos")
      .where("videoUrl", ">=", prefix)
      .where("videoUrl", "<", prefix + "\uf8ff")
      .get();
    for (const doc of snapshot.docs) {
      const video = doc.data();
      if (video.userId === userId && extractStoragePath(video.videoUrl ?? "") === path) {
        await deleteStorageObjectIfPresent(video.thumbnailUrl, `thumbnails/${userId}/`);
        await db.recursiveDelete(doc.ref);
        logger.info(`validateUploadedVideo: doc ${doc.id} borrado junto con el blob inválido`);
      }
    }
  } catch (error) {
    logger.error(`validateUploadedVideo: error borrando doc asociado (${path})`, error);
  }
}

/**
 * Valida server-side cada archivo subido a videos/{userId}/{fileName}. Las reglas de
 * Storage solo ven el content-type DECLARADO por el cliente y el tamaño; aquí se verifica
 * el contenido real: content-type de la metadata, magic bytes (ftyp de MP4/MOV o EBML de
 * WebM), tamaño y, para MP4, la duración del átomo mvhd (sin ffprobe ni binarios nativos).
 * Si es inválido se borra el blob y el doc de Firestore asociado, si existe.
 *
 * Limitación conocida: como el doc se crea DESPUÉS de subir el blob, un doc creado luego
 * por un cliente malicioso apuntando a un blob ya borrado queda huérfano (sin video
 * reproducible); cleanupExpiredVideos lo elimina a las 24h.
 */
export const validateUploadedVideo = onObjectFinalized(
  {
    // Idempotente: solo lee, borra con ignoreNotFound y recursiveDelete; reintentar es seguro.
    retry: true,
    // AJUSTAR: debe coincidir con la región REAL del bucket de boomday-e2308 (Storage exige
    // que el trigger esté en la misma región/multirregión del bucket). Por defecto se asume
    // multirregión nam5 -> us-central1. Verificar en Firebase Console > Storage antes de desplegar.
    region: "us-central1",
    bucket: "boomday-e2308.firebasestorage.app",
    maxInstances: 10,
    memory: "256MiB",
    timeoutSeconds: 120
  },
  async (event) => {
    const object = event.data;
    const path = object.name;

    // Cuota de subidas por usuario/24 h (videos y thumbnails). Falla en modo seguro (no borra).
    if (path && /^(videos|thumbnails)\/[^/]+\/[^/]+$/.test(path)) {
      try {
        if (await applyUploadQuota(path, object.bucket)) return; // excedente ya borrado
      } catch (error) {
        logger.error(`validateUploadedVideo: error aplicando cuota (${path})`, error);
      }
    }

    const match = path?.match(/^videos\/([^/]+)\/[^/]+$/);
    if (!match) return; // thumbnails u otras rutas: sin validación de contenido
    const userId = match[1];

    const size = Number(object.size);
    const contentType = object.contentType ?? "";

    if (!contentType.startsWith("video/")) {
      return deleteInvalidVideo(path, userId, `content-type no es video (${contentType || "vacío"})`, object.bucket);
    }
    if (!Number.isFinite(size) || size <= 0 || size >= MAX_VIDEO_BYTES) {
      return deleteInvalidVideo(path, userId, `tamaño fuera de rango (${object.size} bytes)`, object.bucket);
    }

    const file = admin.storage().bucket(object.bucket).file(path);

    let container: Container;
    try {
      container = detectContainer(await readRange(file, 0, 16));
    } catch (error) {
      // Fallo de lectura transitorio: no se borra a ciegas un video posiblemente válido.
      logger.error(`validateUploadedVideo: no se pudo leer la cabecera de ${path}`, error);
      return;
    }
    if (container === "unknown") {
      return deleteInvalidVideo(path, userId, "magic bytes no corresponden a MP4/MOV ni WebM", object.bucket);
    }

    if (container === "mp4") {
      let result: { found: boolean; seconds: number | null };
      try {
        result = await readMp4Duration(file, size);
      } catch (error) {
        logger.error(`validateUploadedVideo: no se pudo leer la duración de ${path}`, error);
        return;
      }
      if (!result.found) {
        return deleteInvalidVideo(path, userId, "MP4 sin átomo moov (no reproducible)", object.bucket);
      }
      if (result.seconds !== null && result.seconds > MAX_DURATION_SECONDS + DURATION_TOLERANCE_SECONDS) {
        return deleteInvalidVideo(
          path,
          userId,
          `duración ${result.seconds.toFixed(1)}s excede el máximo de ${MAX_DURATION_SECONDS}s`,
          object.bucket
        );
      }
    }
    // WebM: la duración no se verifica (requeriría parsear EBML); queda cubierta por el
    // límite de tamaño y por la regla de Firestore sobre el campo duration.

    logger.info(`validateUploadedVideo: ${path} válido (${container}, ${size} bytes)`);
  }
);

// ---------------------------------------------------------------------------
// B3: moderacion y reportes (adaptadores Admin SDK sobre functions/src/moderation.ts)
// ---------------------------------------------------------------------------

const reportsCol = () => admin.firestore().collection("reports");

const evidenceDeps: EvidenceDeps = {
  now: Date.now,
  log: structuredLog,
  blobExists: async (path) => (await admin.storage().bucket().file(path).exists())[0],
  copyBlob: async (src, dest) => {
    const bucket = admin.storage().bucket();
    await bucket.file(src).copy(bucket.file(dest));
  },
  // copy() hereda firebaseStorageDownloadTokens (el token de la videoUrl publica): se anula en el destino.
  stripToken: async (path) => {
    await admin.storage().bucket().file(path).setMetadata({ metadata: { firebaseStorageDownloadTokens: null } });
  },
  destHasToken: async (path) => {
    const [md] = await admin.storage().bucket().file(path).getMetadata();
    const tokens = (md.metadata as Record<string, unknown> | undefined)?.firebaseStorageDownloadTokens;
    return typeof tokens === "string" ? tokens.length > 0 : tokens != null;
  },
  deleteBlob: async (path) => {
    await admin.storage().bucket().file(path).delete({ ignoreNotFound: true });
  },
  countPreservedForVideo: async (videoId) => {
    // Dos igualdades: no requiere indice compuesto.
    const snap = await reportsCol().where("reportedVideoId", "==", videoId).where("evidenceState", "==", "preserved").count().get();
    return snap.data().count;
  },
  setReportFields: async (reportId, fields) => {
    await reportsCol().doc(reportId).set(fields, { merge: true });
  }
};

const gateDeps: GateDeps = {
  log: structuredLog,
  openReportsForVideo: async (videoId) => {
    const snap = await reportsCol().where("reportedVideoId", "==", videoId).limit(100).get();
    return snap.docs
      .filter((d) => ["pending", "in_review"].includes(String(d.get("status"))))
      .map((d) => ({ reportId: d.id, report: d.data() as ReportDoc }));
  },
  preserve: (reportId, report, force) => preserveEvidence(evidenceDeps, reportId, report, undefined, force)
};

function dayKeyOf(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

/**
 * Contador diario por reportero en report_limits/{uid} (solo servidor). Idempotente: el total
 * queda guardado en el propio reporte (reporterDayCount) dentro de la misma transaccion, de modo
 * que un reintento del trigger no vuelve a contar.
 */
async function countReporterDay(reporterId: string, reportId: string): Promise<number> {
  const db = admin.firestore();
  const limitRef = db.collection("report_limits").doc(reporterId);
  const reportRef = reportsCol().doc(reportId);
  return db.runTransaction(async (tx) => {
    const [limitSnap, reportSnap] = await Promise.all([tx.get(limitRef), tx.get(reportRef)]);
    const already = reportSnap.get("reporterDayCount");
    if (typeof already === "number") return already;
    const today = dayKeyOf(Date.now());
    const count = (limitSnap.get("dayKey") === today ? Number(limitSnap.get("count")) || 0 : 0) + 1;
    tx.set(limitRef, { dayKey: today, count, updatedAt: Date.now() });
    tx.set(reportRef, { reporterDayCount: count }, { merge: true });
    return count;
  });
}

/**
 * Al crear un reporte: cuenta el uso diario del reportero (exceso => solo se marca, NO se borra
 * ni se auto-resuelve nada) y copia el blob reportado a moderation_evidence/<reportId>/.
 * retry: true; preserveEvidence es idempotente y lanza solo ante errores transitorios.
 */
export const onReportCreated = onDocumentCreated(
  { document: "reports/{reportId}", retry: true, memory: "256MiB", timeoutSeconds: 120, maxInstances: 5 },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    await handleNewReport(
      { ...evidenceDeps, countReporterDay },
      event.params.reportId,
      snap.data() as ReportDoc,
      reportLimitFromEnv(process.env)
    );
  }
);

/**
 * Purga la evidencia vencida (30 dias tras resolver, tope 90 dias desde la copia).
 * DRY-RUN por defecto: desplegar con EVIDENCE_PURGE_DRY_RUN=false para borrar de verdad.
 */
export const purgeModerationEvidence = onSchedule(
  { schedule: "every 24 hours", timeZone: "America/Bogota", timeoutSeconds: 300, memory: "256MiB", maxInstances: 1 },
  async () => {
    const bucket = admin.storage().bucket();
    await runEvidencePurge(
      {
        now: Date.now,
        log: structuredLog,
        // Dos consultas de rango de un solo campo (indices automaticos): tope absoluto y resueltos.
        listCandidates: async (nowMs) => {
          const out: PurgeCandidate[] = [];
          const retentionAgo = nowMs - 30 * 24 * 3600 * 1000;
          const queries = [
            reportsCol().where("evidenceExpiresAt", "<=", nowMs).limit(200),
            reportsCol().where("reviewedAt", "<=", retentionAgo).limit(200)
          ];
          for (const q of queries) {
            for (const d of (await q.get()).docs) {
              out.push({
                reportId: d.id,
                status: String(d.get("status")),
                reviewedAt: d.get("reviewedAt"),
                evidenceState: d.get("evidenceState"),
                evidencePath: d.get("evidencePath"),
                evidenceExpiresAt: d.get("evidenceExpiresAt")
              });
            }
          }
          return out;
        },
        listBlobs: async (prefix) => (await bucket.getFiles({ prefix, maxResults: 100 }))[0].map((f) => f.name),
        deleteBlob: async (path) => {
          if (!path.startsWith(EVIDENCE_ROOT)) throw new Error("fuera de moderation_evidence/");
          await bucket.file(path).delete({ ignoreNotFound: true });
        },
        markPurged: async (reportId) => {
          await reportsCol().doc(reportId).set({ evidenceState: "purged", evidencePurgedAt: Date.now() }, { merge: true });
        }
      },
      purgeConfigFromEnv(process.env)
    );
  }
);

/**
 * Retiro de contenido por un moderador autorizado (custom claim moderator == true). Exige un
 * reporte in_review; preserva evidencia, deja registro en moderation_actions, borra blob y doc
 * (el Feed/Ranking/Explore solo leen docs existentes, no hace falta filtro adicional) y cierra el
 * reporte como resolved/content_removed. Nunca se dispara automaticamente.
 */
export const moderatorRemoveVideo = onCall({ memory: "256MiB", timeoutSeconds: 120, maxInstances: 5 }, async (request) => {
  const db = admin.firestore();
  const bucket = admin.storage().bucket();
  try {
    const result = await removeReportedVideo(
      {
        now: Date.now,
        log: structuredLog,
        getReport: async (id) => {
          const s = await reportsCol().doc(id).get();
          return s.exists ? (s.data() as ReportDoc) : null;
        },
        getVideo: async (id) => {
          const s = await db.collection("videos").doc(id).get();
          return s.exists ? { userId: String(s.get("userId")), videoUrl: s.get("videoUrl"), thumbnailUrl: s.get("thumbnailUrl"), createdAt: s.get("createdAt") } : null;
        },
        preserve: (id, report) => preserveEvidence(evidenceDeps, id, report, undefined, true),
        writeAction: async (id, data) => {
          await db.collection("moderation_actions").doc(id).set(data, { merge: true });
        },
        deleteBlob: async (path) => {
          await bucket.file(path).delete({ ignoreNotFound: true });
        },
        deleteVideoDoc: async (videoId) => {
          await db.recursiveDelete(db.collection("videos").doc(videoId));
        },
        resolveReport: async (id, fields) => {
          await reportsCol().doc(id).set(fields, { merge: true });
        }
      },
      { uid: request.auth?.uid, isModerator: request.auth?.token?.moderator === true },
      request.data?.reportId
    );
    return { ok: result.removed, evidence: result.evidence, reason: result.removed ? undefined : "video-not-found" };
  } catch (e) {
    if (e instanceof ModerationError) throw new HttpsError(e.code, e.message);
    logger.error("moderatorRemoveVideo: error inesperado", e);
    throw new HttpsError("internal", "Error interno");
  }
});
