/**
 * Limpieza de blobs huérfanos de Storage (videos/ y thumbnails/ sin documento en Firestore).
 *
 * Todo el módulo es lógica pura con dependencias inyectadas (listado, consulta de referencias,
 * borrado, reloj, log): se prueba con node:test usando dobles en memoria, sin red ni emuladores.
 * Principio rector: ante cualquier duda (error de consulta, fecha ilegible, path raro, proporción
 * anómala) NO se borra.
 */

export type BlobKind = "video" | "thumbnail";

export interface BlobInfo {
  name: string; // path completo dentro del bucket
  createdMs: number | null; // max(timeCreated, updated) en epoch ms; null si ilegible
}

export interface ListPage {
  blobs: BlobInfo[];
  nextToken?: string;
}

export interface OrphanDeps {
  now: () => number;
  /** Lista una página de blobs bajo `prefix` ("videos/" o "thumbnails/"). */
  listPage: (prefix: string, pageToken?: string) => Promise<ListPage>;
  /** true si algún doc `videos` referencia ese path (videoUrl o thumbnailUrl según kind). Debe lanzar si no puede asegurarlo. */
  isReferenced: (path: string, kind: BlobKind) => Promise<boolean>;
  /** Borra el blob (idempotente: ignoreNotFound). */
  deleteBlob: (path: string) => Promise<void>;
  log: (level: "info" | "warn" | "error", message: string, data?: Record<string, unknown>) => void;
}

export interface OrphanConfig {
  graceMs: number;
  maxDeletes: number;
  /** Fracción (0-1) de huérfanos sobre blobs evaluados que dispara el freno. */
  maxOrphanRatio: number;
  /** El freno solo aplica con al menos esta muestra; por debajo rige solo maxDeletes. */
  ratioMinSample: number;
  dryRun: boolean;
  timeBudgetMs: number;
  maxListed: number;
}

export interface OrphanStats {
  listed: number;
  foreign: number; // path fuera del patrón estricto: jamás se tocan
  unreadableAge: number;
  inGrace: number;
  evaluated: number; // pasaron gracia y se consultó Firestore
  referenced: number;
  lookupFailed: number;
  orphans: number;
  wouldDelete: number; // dry-run
  deleted: number;
  deleteFailed: number;
  deferredByCap: number;
  recheckSaved: number; // dejó de ser huérfano en la re-verificación previa al borrado
  brakeTripped: boolean;
  truncated: boolean;
  dryRun: boolean;
}

export const MIN_GRACE_MS = 60 * 60 * 1000; // suelo duro: nunca menos de 1 h

export const DEFAULT_CONFIG: OrphanConfig = {
  graceMs: 2 * 60 * 60 * 1000,
  maxDeletes: 200,
  maxOrphanRatio: 0.5,
  ratioMinSample: 20,
  dryRun: true,
  timeBudgetMs: 240 * 1000,
  maxListed: 50000
};

const PATH_RE = /^(videos|thumbnails)\/[A-Za-z0-9_-]{1,128}\/([A-Za-z0-9._-]{1,200})$/;

/** Path válido SOLO si es exactamente videos|thumbnails/<uid>/<archivo>, sin '..' ni segmentos extra. */
export function classifyPath(path: string): BlobKind | null {
  if (path.includes("..")) return null;
  const m = PATH_RE.exec(path);
  if (!m) return null;
  if (m[2].startsWith(".")) return null;
  return m[1] === "videos" ? "video" : "thumbnail";
}

/** Extrae el path decodificado de una download URL de Firebase Storage (comparación exacta, no substring). */
export function extractStoragePath(downloadUrl: string): string | null {
  const match = downloadUrl.match(/\/o\/([^?]+)/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

export function configFromEnv(env: Record<string, string | undefined>): OrphanConfig {
  const num = (v: string | undefined, d: number) => {
    const n = Number(v);
    return v !== undefined && v !== "" && Number.isFinite(n) && n >= 0 ? n : d;
  };
  const d = DEFAULT_CONFIG;
  return {
    graceMs: Math.max(MIN_GRACE_MS, num(env.ORPHAN_GRACE_HOURS, d.graceMs / 3600000) * 3600000),
    maxDeletes: Math.floor(num(env.ORPHAN_MAX_DELETES, d.maxDeletes)),
    maxOrphanRatio: Math.min(1, num(env.ORPHAN_MAX_RATIO, d.maxOrphanRatio)),
    ratioMinSample: Math.floor(num(env.ORPHAN_RATIO_MIN_SAMPLE, d.ratioMinSample)),
    // Solo el literal "false" desactiva el dry-run; cualquier otra cosa (o ausencia) = dry-run.
    dryRun: env.ORPHAN_CLEANUP_DRY_RUN !== "false",
    timeBudgetMs: d.timeBudgetMs,
    maxListed: d.maxListed
  };
}

export async function runOrphanCleanup(deps: OrphanDeps, cfg: OrphanConfig): Promise<OrphanStats> {
  const stats: OrphanStats = {
    listed: 0, foreign: 0, unreadableAge: 0, inGrace: 0, evaluated: 0, referenced: 0,
    lookupFailed: 0, orphans: 0, wouldDelete: 0, deleted: 0, deleteFailed: 0,
    deferredByCap: 0, recheckSaved: 0, brakeTripped: false, truncated: false, dryRun: cfg.dryRun
  };
  const graceMs = Math.max(MIN_GRACE_MS, cfg.graceMs);
  const startedAt = deps.now();
  const overBudget = () => deps.now() - startedAt >= cfg.timeBudgetMs;
  const candidates: { path: string; kind: BlobKind }[] = [];

  // Fase 1: listar y clasificar. Nada se borra aquí.
  scan:
  for (const prefix of ["videos/", "thumbnails/"]) {
    let token: string | undefined;
    do {
      if (overBudget() || stats.listed >= cfg.maxListed) { stats.truncated = true; break scan; }
      let page: ListPage;
      try {
        page = await deps.listPage(prefix, token);
      } catch (e) {
        // Sin listado fiable no se sabe nada: se aborta el barrido sin borrar.
        deps.log("error", "orphans: fallo listando, se aborta sin borrar", { prefix, error: String(e) });
        stats.truncated = true;
        stats.brakeTripped = true;
        return stats;
      }
      for (const blob of page.blobs) {
        stats.listed++;
        const kind = classifyPath(blob.name);
        if (kind === null || !blob.name.startsWith(prefix) || (kind === "video") !== (prefix === "videos/")) {
          stats.foreign++;
          continue;
        }
        if (blob.createdMs === null || !Number.isFinite(blob.createdMs)) { stats.unreadableAge++; continue; }
        // Edad negativa (reloj/fecha futura) también cuenta como "en gracia".
        if (deps.now() - blob.createdMs < graceMs) { stats.inGrace++; continue; }
        if (overBudget()) { stats.truncated = true; break scan; }
        stats.evaluated++;
        try {
          if (await deps.isReferenced(blob.name, kind)) { stats.referenced++; continue; }
        } catch (e) {
          // Si no se puede confirmar que está huérfano, se asume referenciado.
          stats.lookupFailed++;
          deps.log("warn", "orphans: consulta de referencia falló, no se borra", { path: blob.name, error: String(e) });
          continue;
        }
        stats.orphans++;
        candidates.push({ path: blob.name, kind });
      }
      token = page.nextToken;
    } while (token);
  }

  // Freno de seguridad: proporción anómala => la consulta a Firestore probablemente falló en silencio.
  if (stats.evaluated >= cfg.ratioMinSample && stats.orphans / stats.evaluated > cfg.maxOrphanRatio) {
    stats.brakeTripped = true;
    deps.log("error", "orphans: FRENO, proporción anómala de huérfanos; no se borra nada", { ...stats, ratio: stats.orphans / stats.evaluated });
    return stats;
  }

  // Fase 2: borrar (o reportar en dry-run) con tope.
  const batch = candidates.slice(0, cfg.maxDeletes);
  stats.deferredByCap = candidates.length - batch.length;
  for (const c of batch) {
    if (overBudget()) { stats.truncated = true; break; }
    if (cfg.dryRun) {
      stats.wouldDelete++;
      deps.log("info", "orphans: [dry-run] se borraría", { path: c.path });
      continue;
    }
    try {
      // Re-verificación justo antes de borrar (cierra la ventana entre fase 1 y 2).
      if (await deps.isReferenced(c.path, c.kind)) { stats.recheckSaved++; continue; }
      await deps.deleteBlob(c.path);
      stats.deleted++;
    } catch (e) {
      stats.deleteFailed++;
      deps.log("error", "orphans: error con un blob, se continúa con el resto", { path: c.path, error: String(e) });
    }
  }

  deps.log(stats.brakeTripped ? "error" : "info", "cleanupOrphanedBlobs: resumen", { ...stats });
  return stats;
}
