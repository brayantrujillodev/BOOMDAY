import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import * as admin from "firebase-admin";

admin.initializeApp();

const EXPIRATION_MS = 24 * 60 * 60 * 1000;

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

async function deleteStorageObjectIfPresent(downloadUrl: string | undefined): Promise<void> {
  if (!downloadUrl) return;

  const path = extractStoragePath(downloadUrl);
  if (!path) {
    logger.warn(`cleanupExpiredVideos: no se pudo extraer el path de Storage de: ${downloadUrl}`);
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
    memory: "256MiB"
  },
  async () => {
    const db = admin.firestore();
    const cutoff = Date.now() - EXPIRATION_MS;

    const expiredSnapshot = await db
      .collection("videos")
      .where("createdAt", "<", cutoff)
      .get();

    if (expiredSnapshot.empty) {
      logger.info("cleanupExpiredVideos: no hay videos vencidos");
      return;
    }

    logger.info(`cleanupExpiredVideos: ${expiredSnapshot.size} videos vencidos, borrando`);

    for (const doc of expiredSnapshot.docs) {
      const video = doc.data();

      await Promise.all([
        deleteStorageObjectIfPresent(video.videoUrl),
        deleteStorageObjectIfPresent(video.thumbnailUrl)
      ]);

      await db.recursiveDelete(doc.ref);
    }

    logger.info(`cleanupExpiredVideos: ${expiredSnapshot.size} videos borrados`);
  }
);
