/**
 * Cuota de subidas por usuario en ventana de 24 h, evaluada server-side al finalizar cada objeto
 * (las reglas de Storage no pueden contar objetos). Lógica pura e inyectable.
 * Solo puede borrar EL PROPIO objeto que disparó el evento (nunca otros) y solo si es el que
 * excede el cupo según orden de creación; con dry-run (por defecto) solo registra.
 */
import { BlobInfo } from "./orphans";

export const QUOTA_WINDOW_MS = 24 * 60 * 60 * 1000;
export const DEFAULT_QUOTA_PER_24H = 20;

export interface QuotaDeps {
  now: () => number;
  /** Lista los blobs del usuario bajo el prefijo (videos/<uid>/ o thumbnails/<uid>/), todas las páginas. */
  listUserBlobs: (prefix: string) => Promise<BlobInfo[]>;
  deleteBlob: (path: string) => Promise<void>;
  log: (level: "info" | "warn" | "error", message: string, data?: Record<string, unknown>) => void;
}

export interface QuotaResult {
  action: "ok" | "exceeded-deleted" | "exceeded-dry-run" | "skipped";
  rank: number;
  countInWindow: number;
}

export function quotaFromEnv(env: Record<string, string | undefined>): { limit: number; dryRun: boolean } {
  const n = Number(env.UPLOAD_QUOTA_PER_24H);
  const limit = env.UPLOAD_QUOTA_PER_24H && Number.isInteger(n) && n >= 1 ? n : DEFAULT_QUOTA_PER_24H;
  return { limit, dryRun: env.UPLOAD_QUOTA_DRY_RUN !== "false" };
}

/**
 * `objectPath` = videos/<uid>/<file> o thumbnails/<uid>/<file>. Falla en modo seguro: si el
 * listado falla o el objeto no aparece, no se borra.
 */
export async function enforceUploadQuota(
  deps: QuotaDeps,
  objectPath: string,
  limit: number,
  dryRun: boolean
): Promise<QuotaResult> {
  const m = /^(videos|thumbnails)\/([A-Za-z0-9_-]{1,128})\/[A-Za-z0-9._-]{1,200}$/.exec(objectPath);
  if (!m || objectPath.includes("..")) return { action: "skipped", rank: 0, countInWindow: 0 };
  const prefix = `${m[1]}/${m[2]}/`;

  let blobs: BlobInfo[];
  try {
    blobs = await deps.listUserBlobs(prefix);
  } catch (e) {
    deps.log("error", "quota: no se pudo listar, no se aplica", { objectPath, error: String(e) });
    return { action: "skipped", rank: 0, countInWindow: 0 };
  }
  const since = deps.now() - QUOTA_WINDOW_MS;
  const inWindow = blobs
    .filter((b) => b.name.startsWith(prefix) && b.createdMs !== null && b.createdMs >= since)
    .sort((a, b) => (a.createdMs! - b.createdMs!) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  const idx = inWindow.findIndex((b) => b.name === objectPath);
  if (idx === -1) return { action: "skipped", rank: 0, countInWindow: inWindow.length };
  const rank = idx + 1;
  if (rank <= limit) return { action: "ok", rank, countInWindow: inWindow.length };

  deps.log("warn", "quota: usuario excede cupo de subidas/24h", { objectPath, rank, limit, dryRun });
  if (dryRun) return { action: "exceeded-dry-run", rank, countInWindow: inWindow.length };
  try {
    await deps.deleteBlob(objectPath);
  } catch (e) {
    deps.log("error", "quota: error borrando excedente", { objectPath, error: String(e) });
    return { action: "skipped", rank, countInWindow: inWindow.length };
  }
  return { action: "exceeded-deleted", rank, countInWindow: inWindow.length };
}
