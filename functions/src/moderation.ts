/**
 * B3 - Moderacion y reportes: logica pura con dependencias inyectadas (se prueba con node:test
 * y dobles en memoria; los adaptadores reales con Admin SDK viven en index.ts).
 *
 * Principios: (1) nada se borra por conteo de reportes ni sin accion humana autorizada;
 * (2) la evidencia vive SOLO bajo `moderation_evidence/<reportId>/` (prefijo exacto, solo servidor);
 * (3) ante cualquier duda no se borra.
 */
import { classifyPath, extractStoragePath } from "./orphans";

export const EVIDENCE_ROOT = "moderation_evidence/";
export const REPORT_ID_RE = /^[A-Za-z0-9_-]{3,200}$/;
export const DAY_MS = 24 * 60 * 60 * 1000;

export type EvidenceState =
  | "preserved"
  | "missing"
  | "skipped_rate_limited"
  | "skipped_video_cap"
  | "failed"
  | "purged";

/** Estados en los que cleanupExpiredVideos puede borrar el video sin perder evidencia. */
export const GATE_SAFE_STATES: ReadonlyArray<string> = ["preserved", "missing"];

export interface ReportDoc {
  reporterId: string;
  reportedUserId: string;
  reportedVideoId: string;
  status: string;
  reviewedAt?: number;
  evidenceState?: string;
  evidencePath?: string;
  evidenceExpiresAt?: number;
  evidence?: { videoUrl?: string; title?: string; videoCreatedAt?: number; authorName?: string };
}

export type Level = "info" | "warn" | "error";
export type Log = (level: Level, message: string, data?: Record<string, unknown>) => void;

export function evidencePrefix(reportId: string): string {
  return `${EVIDENCE_ROOT}${reportId}/`;
}

// ---------------------------------------------------------------------------
// Preservacion de evidencia
// ---------------------------------------------------------------------------

export interface EvidenceDeps {
  now: () => number;
  log: Log;
  blobExists: (path: string) => Promise<boolean>;
  copyBlob: (src: string, dest: string) => Promise<void>;
  /** Anula firebaseStorageDownloadTokens en el destino (copy() hereda el token de la download URL publica). */
  stripToken: (path: string) => Promise<void>;
  /** true si el destino aun tiene token de descarga (debe ser false tras stripToken). */
  destHasToken: (path: string) => Promise<boolean>;
  deleteBlob: (path: string) => Promise<void>;
  /** Reportes del mismo video con evidenceState == 'preserved' (tope de copias por video). */
  countPreservedForVideo: (videoId: string) => Promise<number>;
  setReportFields: (reportId: string, fields: Record<string, unknown>) => Promise<void>;
}

export interface EvidenceConfig {
  maxPerVideo: number; // copias de evidencia por video (evita multiplicar el costo de Storage)
  maxRetentionMs: number; // tope absoluto desde la copia
}

export const DEFAULT_EVIDENCE_CONFIG: EvidenceConfig = { maxPerVideo: 3, maxRetentionMs: 90 * DAY_MS };

/**
 * Copia el blob reportado a moderation_evidence/<reportId>/. Idempotente. Lanza ante errores
 * transitorios (el trigger reintenta); devuelve 'missing' si el blob ya no existe.
 * `force` ignora el tope por video (lo usa el retiro autorizado).
 */
export async function preserveEvidence(
  deps: EvidenceDeps,
  reportId: string,
  report: ReportDoc,
  cfg: EvidenceConfig = DEFAULT_EVIDENCE_CONFIG,
  force = false
): Promise<EvidenceState> {
  if (report.evidenceState === "preserved") return "preserved";
  // Id invalido: no se reintenta (no lanza); se marca failed si es posible.
  if (!REPORT_ID_RE.test(reportId) || !/^[A-Za-z0-9_-]{1,64}$/.test(report.reportedVideoId ?? "")) {
    deps.log("error", "moderation: reportId/videoId invalido, no se copia", { reportId });
    try { await deps.setReportFields(reportId, { evidenceState: "failed" }); } catch (e) { deps.log("error", "moderation: no se pudo marcar failed", { error: String(e) }); }
    return "failed";
  }

  const src = extractStoragePath(report.evidence?.videoUrl ?? "");
  // Solo el blob de video del propio autor reportado: nunca un path arbitrario.
  if (!src || classifyPath(src) !== "video" || !src.startsWith(`videos/${report.reportedUserId}/`)) {
    deps.log("error", "moderation: ruta de evidencia invalida, no se copia", { reportId });
    await deps.setReportFields(reportId, { evidenceState: "failed" });
    return "failed";
  }

  if (!force) {
    const n = await deps.countPreservedForVideo(report.reportedVideoId);
    if (n >= cfg.maxPerVideo) {
      await deps.setReportFields(reportId, { evidenceState: "skipped_video_cap" });
      return "skipped_video_cap";
    }
  }

  if (!(await deps.blobExists(src))) {
    await deps.setReportFields(reportId, { evidenceState: "missing" });
    return "missing";
  }

  const fileName = src.slice(src.lastIndexOf("/") + 1);
  const dest = `${evidencePrefix(reportId)}${fileName}`;
  await deps.copyBlob(src, dest);
  // Fail-closed: el destino NO puede conservar el token de descarga del original (saltaria storage.rules).
  let clean = false;
  try {
    await deps.stripToken(dest);
    clean = !(await deps.destHasToken(dest));
  } catch (e) {
    deps.log("error", "moderation: no se pudo limpiar el token del destino", { reportId, error: String(e) });
  }
  if (!clean) {
    await deps.deleteBlob(dest); // si falla, lanza y el trigger reintenta (nunca queda marcado como preservado)
    await deps.setReportFields(reportId, { evidenceState: "failed" });
    return "failed";
  }
  const now = deps.now();
  await deps.setReportFields(reportId, {
    evidenceState: "preserved",
    evidencePath: dest,
    evidenceCopiedAt: now,
    evidenceExpiresAt: now + cfg.maxRetentionMs
  });
  return "preserved";
}

// ---------------------------------------------------------------------------
// Reporte nuevo: contador diario por reportero (abuso) + evidencia
// ---------------------------------------------------------------------------

export interface NewReportDeps extends EvidenceDeps {
  /** Cuenta (idempotente por reportId) el reporte en el contador diario del reportero y devuelve el total del dia. */
  countReporterDay: (reporterId: string, reportId: string) => Promise<number>;
}

export const DEFAULT_REPORTS_PER_DAY = 20;

export function reportLimitFromEnv(env: Record<string, string | undefined>): number {
  const n = Number(env.REPORTS_PER_DAY_LIMIT);
  return env.REPORTS_PER_DAY_LIMIT && Number.isInteger(n) && n >= 1 ? n : DEFAULT_REPORTS_PER_DAY;
}

/** NUNCA borra nada: el exceso solo se marca (abuseFlag) y no copia evidencia (costo). */
export async function handleNewReport(
  deps: NewReportDeps,
  reportId: string,
  report: ReportDoc,
  limit: number,
  cfg: EvidenceConfig = DEFAULT_EVIDENCE_CONFIG
): Promise<{ abuse: boolean; evidence: EvidenceState | null }> {
  if (report.evidenceState) return { abuse: false, evidence: report.evidenceState as EvidenceState }; // reintento
  const count = await deps.countReporterDay(report.reporterId, reportId);
  if (count > limit) {
    deps.log("warn", "moderation: reportero sobre el limite diario, reporte marcado", { reportId, count, limit });
    await deps.setReportFields(reportId, { abuseFlag: "reporter_over_daily_limit", evidenceState: "skipped_rate_limited" });
    return { abuse: true, evidence: "skipped_rate_limited" };
  }
  const evidence = await preserveEvidence(deps, reportId, report, cfg);
  return { abuse: false, evidence };
}

// ---------------------------------------------------------------------------
// Compuerta para cleanupExpiredVideos
// ---------------------------------------------------------------------------

export interface GateDeps {
  log: Log;
  openReportsForVideo: (videoId: string) => Promise<{ reportId: string; report: ReportDoc }[]>;
  /** `force` ignora el tope de copias por video (solo se usa con skipped_video_cap). */
  preserve: (reportId: string, report: ReportDoc, force: boolean) => Promise<EvidenceState>;
}

export const MAX_HOLD_MS = 7 * DAY_MS;

/**
 * Un video con reporte abierto (pending/in_review) solo se borra si la evidencia ya esta
 * preservada (o es imposible/no corresponde). Si no, intenta preservarla ahora; si falla, lo
 * retiene hasta MAX_HOLD_MS desde su creacion (no queda inmortal).
 */
export async function evidenceGateForVideo(
  deps: GateDeps,
  videoId: string,
  videoCreatedAt: number,
  now: number
): Promise<{ allow: boolean; reason: string }> {
  let open: { reportId: string; report: ReportDoc }[];
  try {
    open = await deps.openReportsForVideo(videoId);
  } catch (e) {
    deps.log("error", "gate: no se pudo consultar reportes, se retiene el video", { videoId, error: String(e) });
    return { allow: now - videoCreatedAt >= MAX_HOLD_MS, reason: "lookup-failed" };
  }
  let blocked = false;
  for (const { reportId, report } of open) {
    if (report.evidenceState && GATE_SAFE_STATES.includes(report.evidenceState)) continue;
    // skipped_rate_limited (reportero abusivo) NO se fuerza: el video se retiene hasta MAX_HOLD, con log.
    if (report.evidenceState === "skipped_rate_limited") {
      deps.log("warn", "gate: reporte abierto marcado por abuso, video retenido sin copiar", { reportId, videoId });
      blocked = true;
      continue;
    }
    try {
      const s = await deps.preserve(reportId, report, report.evidenceState === "skipped_video_cap");
      if (!GATE_SAFE_STATES.includes(s)) blocked = true;
    } catch (e) {
      deps.log("error", "gate: no se pudo preservar evidencia", { reportId, error: String(e) });
      blocked = true;
    }
  }
  if (!blocked) return { allow: true, reason: open.length ? "evidence-preserved" : "no-open-reports" };
  if (now - videoCreatedAt >= MAX_HOLD_MS) {
    deps.log("error", "gate: retencion maxima superada, se borra sin evidencia", { videoId });
    return { allow: true, reason: "hold-expired" };
  }
  return { allow: false, reason: "evidence-pending" };
}

// ---------------------------------------------------------------------------
// Purga de evidencia vencida
// ---------------------------------------------------------------------------

export interface PurgeCandidate {
  reportId: string;
  status: string;
  reviewedAt?: number;
  evidenceState?: string;
  evidencePath?: string;
  evidenceExpiresAt?: number;
}

export interface PurgeDeps {
  now: () => number;
  log: Log;
  listCandidates: (nowMs: number) => Promise<PurgeCandidate[]>;
  /** Lista nombres de blobs bajo el prefijo EXACTO dado. */
  listBlobs: (prefix: string) => Promise<string[]>;
  deleteBlob: (path: string) => Promise<void>;
  markPurged: (reportId: string) => Promise<void>;
}

export interface PurgeConfig {
  resolvedRetentionMs: number;
  maxReportsPerRun: number;
  maxBlobsPerReport: number;
  dryRun: boolean;
}

export const MIN_RESOLVED_RETENTION_MS = 7 * DAY_MS;
export const DEFAULT_PURGE_CONFIG: PurgeConfig = {
  resolvedRetentionMs: 30 * DAY_MS,
  maxReportsPerRun: 50,
  maxBlobsPerReport: 20,
  dryRun: true
};

export function purgeConfigFromEnv(env: Record<string, string | undefined>): PurgeConfig {
  const d = DEFAULT_PURGE_CONFIG;
  const num = (v: string | undefined, def: number) => {
    const n = Number(v);
    return v !== undefined && v !== "" && Number.isFinite(n) && n > 0 ? n : def;
  };
  return {
    resolvedRetentionMs: Math.max(MIN_RESOLVED_RETENTION_MS, num(env.EVIDENCE_RETENTION_DAYS, d.resolvedRetentionMs / DAY_MS) * DAY_MS),
    maxReportsPerRun: Math.floor(num(env.EVIDENCE_PURGE_MAX_REPORTS, d.maxReportsPerRun)),
    maxBlobsPerReport: d.maxBlobsPerReport,
    // Solo el literal "false" desactiva el dry-run.
    dryRun: env.EVIDENCE_PURGE_DRY_RUN !== "false"
  };
}

export function isEvidenceExpired(c: PurgeCandidate, now: number, cfg: PurgeConfig): boolean {
  if (Number.isFinite(c.evidenceExpiresAt) && now >= (c.evidenceExpiresAt as number)) return true; // tope absoluto
  const retention = Math.max(MIN_RESOLVED_RETENTION_MS, cfg.resolvedRetentionMs);
  return c.status === "resolved" && Number.isFinite(c.reviewedAt) && now >= (c.reviewedAt as number) + retention;
}

export interface PurgeStats { candidates: number; expired: number; purged: number; wouldPurge: number; deferredByCap: number; skipped: number; failed: number; dryRun: boolean }

export async function runEvidencePurge(deps: PurgeDeps, cfg: PurgeConfig): Promise<PurgeStats> {
  const stats: PurgeStats = { candidates: 0, expired: 0, purged: 0, wouldPurge: 0, deferredByCap: 0, skipped: 0, failed: 0, dryRun: cfg.dryRun };
  const now = deps.now();
  let list: PurgeCandidate[];
  try {
    list = await deps.listCandidates(now);
  } catch (e) {
    deps.log("error", "purge: fallo listando candidatos, no se borra", { error: String(e) });
    return stats;
  }
  const seen = new Set<string>();
  const expired: PurgeCandidate[] = [];
  for (const c of list) {
    if (seen.has(c.reportId)) continue;
    seen.add(c.reportId);
    stats.candidates++;
    if (c.evidenceState !== "preserved") { stats.skipped++; continue; }
    if (!REPORT_ID_RE.test(c.reportId) || c.evidencePath === undefined || !c.evidencePath.startsWith(evidencePrefix(c.reportId))) {
      stats.skipped++;
      deps.log("warn", "purge: candidato con ruta fuera del prefijo exacto, se ignora", { reportId: c.reportId });
      continue;
    }
    if (!isEvidenceExpired(c, now, cfg)) { stats.skipped++; continue; }
    expired.push(c);
  }
  stats.expired = expired.length;
  const batch = expired.slice(0, cfg.maxReportsPerRun);
  stats.deferredByCap = expired.length - batch.length;

  for (const c of batch) {
    const prefix = evidencePrefix(c.reportId);
    try {
      const blobs = await deps.listBlobs(prefix);
      const safe = blobs.filter((b) => b.startsWith(prefix) && !b.includes("..") && b.length > prefix.length);
      if (safe.length !== blobs.length || safe.length > cfg.maxBlobsPerReport) {
        stats.skipped++;
        deps.log("warn", "purge: listado inesperado (fuera de prefijo o demasiados), no se borra", { reportId: c.reportId, n: blobs.length });
        continue;
      }
      if (cfg.dryRun) {
        stats.wouldPurge++;
        deps.log("info", "purge: [dry-run] se borraria evidencia", { reportId: c.reportId, blobs: safe.length });
        continue;
      }
      for (const b of safe) await deps.deleteBlob(b);
      await deps.markPurged(c.reportId);
      stats.purged++;
    } catch (e) {
      stats.failed++;
      deps.log("error", "purge: error con un reporte, se continua", { reportId: c.reportId, error: String(e) });
    }
  }
  deps.log("info", "purgeModerationEvidence: resumen", { ...stats });
  return stats;
}

// ---------------------------------------------------------------------------
// Retiro de contenido por moderador (callable)
// ---------------------------------------------------------------------------

export class ModerationError extends Error {
  constructor(public code: "permission-denied" | "invalid-argument" | "not-found" | "failed-precondition", message: string) {
    super(message);
  }
}

export interface RemoveDeps {
  now: () => number;
  log: Log;
  getReport: (reportId: string) => Promise<ReportDoc | null>;
  getVideo: (videoId: string) => Promise<{ userId: string; videoUrl?: string; thumbnailUrl?: string; createdAt?: number } | null>;
  /** Preserva evidencia (force) y devuelve el estado resultante. */
  preserve: (reportId: string, report: ReportDoc) => Promise<EvidenceState>;
  /** Escritura idempotente (id determinista) en moderation_actions. */
  writeAction: (actionId: string, data: Record<string, unknown>) => Promise<void>;
  deleteBlob: (path: string) => Promise<void>;
  deleteVideoDoc: (videoId: string) => Promise<void>;
  resolveReport: (reportId: string, fields: Record<string, unknown>) => Promise<void>;
}

export async function removeReportedVideo(
  deps: RemoveDeps,
  caller: { uid?: string; isModerator: boolean },
  reportId: unknown
): Promise<{ removed: boolean; evidence: EvidenceState }> {
  if (!caller.uid || !caller.isModerator) throw new ModerationError("permission-denied", "Solo moderadores autorizados");
  if (typeof reportId !== "string" || !REPORT_ID_RE.test(reportId)) throw new ModerationError("invalid-argument", "reportId invalido");
  const report = await deps.getReport(reportId);
  if (!report) throw new ModerationError("not-found", "Reporte inexistente");
  if (report.status !== "in_review") throw new ModerationError("failed-precondition", "El reporte debe estar in_review");

  const video = await deps.getVideo(report.reportedVideoId);
  // Sin video no hay nada que retirar: no se audita ni se resuelve como content_removed.
  if (!video) return { removed: false, evidence: (report.evidenceState as EvidenceState) ?? "missing" };
  // El doc actual debe ser el mismo que se reporto (autor, url y fecha del snapshot de evidencia).
  if (
    video.userId !== report.reportedUserId ||
    video.videoUrl !== report.evidence?.videoUrl ||
    video.createdAt !== report.evidence?.videoCreatedAt
  ) {
    throw new ModerationError("failed-precondition", "El video actual no coincide con el reportado (autor/url/fecha)");
  }

  // 1) Evidencia primero; si no esta asegurada, NO se retira nada.
  const evidence = await deps.preserve(reportId, report);
  if (evidence !== "preserved" && evidence !== "missing") {
    throw new ModerationError("failed-precondition", `Evidencia no preservada (${evidence})`);
  }

  // 2) Registro de auditoria ANTES de la accion (id determinista => idempotente).
  const now = deps.now();
  await deps.writeAction(`remove_${reportId}`, {
    action: "remove_video",
    reportId,
    videoId: report.reportedVideoId,
    ownerUserId: report.reportedUserId,
    moderatorUid: caller.uid,
    evidenceState: evidence,
    at: now
  });

  // 3) Retiro: blobs solo dentro de las carpetas del autor, luego el doc (con subcoleccion views).
  {
    for (const [url, folder] of [[video.videoUrl, "videos"], [video.thumbnailUrl, "thumbnails"]] as const) {
      const p = url ? extractStoragePath(url) : null;
      if (p && classifyPath(p) !== null && p.startsWith(`${folder}/${report.reportedUserId}/`)) await deps.deleteBlob(p);
      else if (url) deps.log("warn", "moderatorRemoveVideo: ruta fuera de la carpeta del autor, blob no borrado", { reportId });
    }
    await deps.deleteVideoDoc(report.reportedVideoId);
  }

  // 4) Cierre del reporte.
  await deps.resolveReport(reportId, { status: "resolved", resolution: "content_removed", reviewedBy: caller.uid, reviewedAt: now });
  return { removed: true, evidence };
}
