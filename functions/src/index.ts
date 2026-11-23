import { onSchedule } from "firebase-functions/v2/scheduler";
import { onObjectFinalized } from "firebase-functions/v2/storage";
import { logger } from "firebase-functions";
import * as admin from "firebase-admin";

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
    logger.warn(`cleanupExpiredVideos: no se pudo extraer el path de Storage de: ${downloadUrl}`);
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

    // Tandas acotadas: evita cargar toda la colección en memoria y respeta el timeout.
    while (Date.now() - startedAt < CLEANUP_TIME_BUDGET_MS) {
      const snapshot = await db
        .collection("videos")
        .where("createdAt", "<", cutoff)
        .limit(CLEANUP_BATCH_SIZE)
        .get();

      const fresh = snapshot.docs.filter((d) => !seen.has(d.id));
      if (fresh.length === 0) break; // vacío, o solo quedan docs que ya fallaron en esta corrida

      for (const doc of fresh) {
        if (Date.now() - startedAt >= CLEANUP_TIME_BUDGET_MS) break;
        seen.add(doc.id);
        const video = doc.data();
        try {
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

    logger.info(`cleanupExpiredVideos: ${deleted} videos vencidos borrados`);
  }
);

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

    const match = path?.match(/^videos\/([^/]+)\/[^/]+$/);
    if (!match) return; // thumbnails u otras rutas: fuera de alcance
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
