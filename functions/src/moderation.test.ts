import { test } from "node:test";
import assert from "node:assert/strict";
import { configFromEnv, runOrphanCleanup } from "./orphans";
import {
  DAY_MS, MAX_HOLD_MS, ModerationError, ReportDoc, evidenceGateForVideo, evidencePrefix, handleNewReport,
  preserveEvidence, purgeConfigFromEnv, removeReportedVideo, runEvidencePurge, DEFAULT_PURGE_CONFIG,
  EvidenceDeps, PurgeCandidate
} from "./moderation";

const URL_OF = (p: string) => `https://firebasestorage.googleapis.com/v0/b/b.firebasestorage.app/o/${encodeURIComponent(p)}?alt=media&token=t`;
const SRC = "videos/owner/video_1700000000000.mp4";
const mkReport = (over: Partial<ReportDoc> = {}): ReportDoc => ({
  reporterId: "alice", reportedUserId: "owner", reportedVideoId: "v1", status: "pending",
  evidence: { videoUrl: URL_OF(SRC), videoCreatedAt: 1700000000000 }, ...over
});

/** Doble en memoria de Storage + Firestore de reportes. */
function fake(opts: { blobs?: string[]; failCopy?: boolean; stripBroken?: boolean; deleteBroken?: boolean } = {}) {
  const blobs = new Set(opts.blobs ?? [SRC]);
  // Token de descarga por blob: el original lo tiene (esta en la videoUrl publica); copy() lo hereda como en GCS.
  const tokens = new Map<string, string | null>([[SRC, "tok-publico"]]);
  const fields: Record<string, Record<string, unknown>> = {};
  const log: string[] = [];
  let t = 1_000_000_000_000;
  const deps: EvidenceDeps & { countReporterDay: (r: string, id: string) => Promise<number> } = {
    now: () => t,
    log: (l, m) => { log.push(`${l}:${m}`); },
    blobExists: async (p) => blobs.has(p),
    copyBlob: async (s, d) => {
      if (opts.failCopy) throw new Error("boom");
      assert.ok(blobs.has(s)); blobs.add(d); tokens.set(d, tokens.get(s) ?? null);
    },
    stripToken: async (p) => { if (!opts.stripBroken) tokens.set(p, null); },
    destHasToken: async (p) => !!tokens.get(p),
    deleteBlob: async (p) => { if (opts.deleteBroken) throw new Error("no se pudo borrar"); blobs.delete(p); tokens.delete(p); },
    countPreservedForVideo: async (v) => Object.values(fields).filter((f) => f.evidenceState === "preserved" && f.vid === v).length,
    setReportFields: async (id, f) => { fields[id] = { ...(fields[id] ?? {}), ...f, vid: "v1" }; },
    countReporterDay: async () => 1
  };
  return { deps, blobs, tokens, fields, log, advance: (ms: number) => { t += ms; } };
}

test("preserveEvidence copia a moderation_evidence/<reportId>/ y es idempotente", async () => {
  const f = fake();
  assert.equal(await preserveEvidence(f.deps, "alice_v1", mkReport()), "preserved");
  const dest = `${evidencePrefix("alice_v1")}video_1700000000000.mp4`;
  assert.ok(f.blobs.has(dest));
  assert.ok(f.blobs.has(SRC), "no mueve ni borra el original");
  assert.equal(f.fields["alice_v1"].evidencePath, dest);
  assert.equal(f.fields["alice_v1"].evidenceExpiresAt, 1_000_000_000_000 + 90 * DAY_MS);
  assert.equal(await preserveEvidence(f.deps, "alice_v1", mkReport({ evidenceState: "preserved" })), "preserved");
});

test("preserveEvidence: blob inexistente => missing; ruta ajena/invalida => failed sin copiar; error transitorio relanza", async () => {
  const f = fake({ blobs: [] });
  assert.equal(await preserveEvidence(f.deps, "alice_v1", mkReport()), "missing");
  const g = fake({ blobs: ["videos/otro/video_1700000000000.mp4"] });
  const ajeno = mkReport({ evidence: { videoUrl: URL_OF("videos/otro/video_1700000000000.mp4") } });
  assert.equal(await preserveEvidence(g.deps, "alice_v1", ajeno), "failed");
  const trav = mkReport({ evidence: { videoUrl: URL_OF("videos/owner/../otro/x.mp4") } });
  assert.equal(await preserveEvidence(g.deps, "alice_v1", trav), "failed");
  assert.equal([...g.blobs].filter((b) => b.startsWith("moderation_evidence/")).length, 0);
  await assert.rejects(preserveEvidence(fake({ failCopy: true }).deps, "alice_v1", mkReport()), /boom/);
  // ids invalidos: no lanza (no reintento infinito), marca failed y no copia
  assert.equal(await preserveEvidence(f.deps, "../x", mkReport()), "failed");
  assert.equal(await preserveEvidence(f.deps, "ok_v1", mkReport({ reportedVideoId: "a/b" })), "failed");
  assert.equal(await preserveEvidence(f.deps, "ok_v1", mkReport({ reportedVideoId: "x".repeat(65) })), "failed");
  assert.equal([...f.blobs].filter((b) => b.startsWith("moderation_evidence/")).length, 0);
});

test("evidencia: la copia NO conserva el token de descarga del original (fail-closed)", async () => {
  const f = fake();
  assert.equal(await preserveEvidence(f.deps, "alice_v1", mkReport()), "preserved");
  const dest = `${evidencePrefix("alice_v1")}video_1700000000000.mp4`;
  assert.equal(f.tokens.get(dest), null, "destino sin token");
  assert.equal(f.tokens.get(SRC), "tok-publico", "el original conserva el suyo");
});
test("evidencia: si no se puede anular el token se borra el destino y se marca failed", async () => {
  const f = fake({ stripBroken: true });
  assert.equal(await preserveEvidence(f.deps, "alice_v1", mkReport()), "failed");
  assert.ok(![...f.blobs].some((b) => b.startsWith("moderation_evidence/")), "destino con token borrado");
  assert.equal(f.fields["alice_v1"].evidenceState, "failed");
  assert.equal(f.fields["alice_v1"].evidencePath, undefined);
  // si ni siquiera puede borrar el destino, lanza (el trigger reintenta) y nunca queda 'preserved'
  const g = fake({ stripBroken: true, deleteBroken: true });
  await assert.rejects(preserveEvidence(g.deps, "alice_v1", mkReport()), /no se pudo borrar/);
  assert.notEqual(g.fields["alice_v1"]?.evidenceState, "preserved");
});

test("preserveEvidence: tope de copias por video (4o reporte no copia) y force lo ignora", async () => {
  const f = fake();
  for (const id of ["a_v1", "b_v1", "c_v1"]) assert.equal(await preserveEvidence(f.deps, id, mkReport()), "preserved");
  assert.equal(await preserveEvidence(f.deps, "d_v1", mkReport()), "skipped_video_cap");
  assert.equal(await preserveEvidence(f.deps, "d_v1", mkReport(), undefined, true), "preserved");
});

test("handleNewReport: bajo el limite copia evidencia; sobre el limite SOLO marca, no copia ni borra", async () => {
  const f = fake();
  let n = 0;
  f.deps.countReporterDay = async () => ++n;
  const r1 = await handleNewReport(f.deps, "alice_v1", mkReport(), 2);
  assert.deepEqual(r1, { abuse: false, evidence: "preserved" });
  await handleNewReport(f.deps, "alice_v2", mkReport(), 2);
  const before = f.blobs.size;
  const r3 = await handleNewReport(f.deps, "alice_v3", mkReport(), 2);
  assert.deepEqual(r3, { abuse: true, evidence: "skipped_rate_limited" });
  assert.equal(f.fields["alice_v3"].abuseFlag, "reporter_over_daily_limit");
  assert.equal(f.blobs.size, before, "no se copia ni se borra nada");
  assert.ok(f.blobs.has(SRC));
});

test("handleNewReport: reintento con evidenceState ya fijado no recuenta ni recopia", async () => {
  const f = fake();
  let calls = 0;
  f.deps.countReporterDay = async () => { calls++; return 1; };
  await handleNewReport(f.deps, "alice_v1", mkReport({ evidenceState: "preserved" }), 5);
  assert.equal(calls, 0);
});

// ---- compuerta de cleanupExpiredVideos ----
const gateDeps = (reports: { reportId: string; report: ReportDoc }[], preserve: (id: string, r: ReportDoc) => Promise<any>) => ({
  log: () => {}, openReportsForVideo: async () => reports, preserve
});
const T0 = 2_000_000_000_000;

test("gate: sin reportes abiertos => se puede borrar", async () => {
  assert.equal((await evidenceGateForVideo(gateDeps([], async () => "preserved"), "v1", T0 - 25 * 3600e3, T0)).allow, true);
});
test("gate: reporte abierto SIN evidencia y copia falla => NO se borra el blob (retenido)", async () => {
  let deleted = false;
  const g = await evidenceGateForVideo(
    gateDeps([{ reportId: "a_v1", report: mkReport() }], async () => { throw new Error("storage caido"); }), "v1", T0 - 25 * 3600e3, T0);
  if (g.allow) deleted = true;
  assert.equal(deleted, false);
  assert.equal(g.reason, "evidence-pending");
});
test("gate: reporte abierto sin evidencia => la preserva y entonces permite borrar", async () => {
  let preserved = 0;
  const g = await evidenceGateForVideo(gateDeps([{ reportId: "a_v1", report: mkReport() }], async () => { preserved++; return "preserved"; }), "v1", T0 - 25 * 3600e3, T0);
  assert.equal(preserved, 1);
  assert.equal(g.allow, true);
});
test("gate: evidencia ya preservada no se recopia; 'failed' retiene; tras MAX_HOLD se borra con error registrado", async () => {
  let calls = 0;
  const ok = gateDeps([{ reportId: "a_v1", report: mkReport({ evidenceState: "preserved" }) }], async () => { calls++; return "preserved"; });
  assert.equal((await evidenceGateForVideo(ok, "v1", T0 - 25 * 3600e3, T0)).allow, true);
  assert.equal(calls, 0);
  const failed = gateDeps([{ reportId: "a_v1", report: mkReport() }], async () => "failed");
  assert.equal((await evidenceGateForVideo(failed, "v1", T0 - 25 * 3600e3, T0)).allow, false);
  assert.equal((await evidenceGateForVideo(failed, "v1", T0 - MAX_HOLD_MS - 1, T0)).reason, "hold-expired");
});
test("gate: por estado de evidencia de un reporte abierto", async () => {
  const young = T0 - 25 * 3600e3;
  const run = async (state: string | undefined, preserveResult: string = "failed") => {
    const forced: boolean[] = [];
    const g = await evidenceGateForVideo(
      gateDeps([{ reportId: "a_v1", report: mkReport({ evidenceState: state }) }], async (_i: string, _r: ReportDoc, force?: boolean) => { forced.push(!!force); return preserveResult; }),
      "v1", young, T0);
    return { allow: g.allow, forced };
  };
  assert.deepEqual(await run("preserved"), { allow: true, forced: [] });
  assert.deepEqual(await run("missing"), { allow: true, forced: [] });
  assert.equal((await run("failed")).allow, false);
  assert.equal((await run(undefined)).allow, false);
  // purged/skipped_* ya NO son seguros para borrar
  assert.equal((await run("purged")).allow, false);
  // skipped_video_cap: se reintenta forzando; si copia, permite
  assert.deepEqual(await run("skipped_video_cap", "preserved"), { allow: true, forced: [true] });
  assert.deepEqual(await run("skipped_video_cap", "failed"), { allow: false, forced: [true] });
  // skipped_rate_limited: retiene sin copiar (costo), hasta MAX_HOLD
  assert.deepEqual(await run("skipped_rate_limited", "preserved"), { allow: false, forced: [] });
  const old = await evidenceGateForVideo(gateDeps([{ reportId: "a_v1", report: mkReport({ evidenceState: "skipped_rate_limited" }) }], async () => "failed"), "v1", T0 - MAX_HOLD_MS - 1, T0);
  assert.equal(old.reason, "hold-expired");
});
test("gate: si falla la consulta de reportes se retiene", async () => {
  const d = { log: () => {}, openReportsForVideo: async () => { throw new Error("x"); }, preserve: async () => "preserved" as const };
  assert.equal((await evidenceGateForVideo(d, "v1", T0 - 25 * 3600e3, T0)).allow, false);
});

// ---- purga ----
function purgeFake(cands: PurgeCandidate[], blobsByPrefix: Record<string, string[]>) {
  const deleted: string[] = [];
  const purged: string[] = [];
  const listed: string[] = [];
  return {
    deleted, purged, listed,
    deps: {
      now: () => T0, log: () => {},
      listCandidates: async () => cands,
      listBlobs: async (p: string) => { listed.push(p); return blobsByPrefix[p] ?? []; },
      deleteBlob: async (p: string) => { deleted.push(p); },
      markPurged: async (id: string) => { purged.push(id); }
    }
  };
}
const cand = (id: string, over: Partial<PurgeCandidate> = {}): PurgeCandidate => ({
  reportId: id, status: "resolved", reviewedAt: T0 - 31 * DAY_MS, evidenceState: "preserved",
  evidencePath: `${evidencePrefix(id)}video_1.mp4`, evidenceExpiresAt: T0 + 50 * DAY_MS, ...over
});
const wet = { ...DEFAULT_PURGE_CONFIG, dryRun: false };

test("purga: dry-run por defecto no borra ni marca", async () => {
  assert.equal(purgeConfigFromEnv({}).dryRun, true);
  assert.equal(purgeConfigFromEnv({ EVIDENCE_PURGE_DRY_RUN: "False" }).dryRun, true);
  const f = purgeFake([cand("a_v1")], { [evidencePrefix("a_v1")]: [`${evidencePrefix("a_v1")}video_1.mp4`] });
  const s = await runEvidencePurge(f.deps, purgeConfigFromEnv({}));
  assert.equal(s.wouldPurge, 1);
  assert.deepEqual(f.deleted, []);
  assert.deepEqual(f.purged, []);
});
test("purga real: resuelto > 30 d, tope absoluto 90 d; NO borra pendientes ni resueltos recientes", async () => {
  const keep = [
    cand("pend_v1", { status: "pending", reviewedAt: undefined }),
    cand("inrev_v1", { status: "in_review", reviewedAt: T0 - 40 * DAY_MS }),
    cand("recent_v1", { reviewedAt: T0 - 5 * DAY_MS }),
    cand("nostate_v1", { evidenceState: "purged" })
  ];
  const old = cand("max_v1", { status: "pending", reviewedAt: undefined, evidenceExpiresAt: T0 - 1 });
  const f = purgeFake([cand("a_v1"), old, ...keep], {
    [evidencePrefix("a_v1")]: [`${evidencePrefix("a_v1")}video_1.mp4`],
    [evidencePrefix("max_v1")]: [`${evidencePrefix("max_v1")}video_1.mp4`]
  });
  const s = await runEvidencePurge(f.deps, wet);
  assert.deepEqual(f.purged.sort(), ["a_v1", "max_v1"]);
  assert.equal(s.purged, 2);
  assert.ok(f.deleted.every((p) => p.startsWith("moderation_evidence/")));
});
test("purga: salvaguardas (ruta fuera del prefijo exacto, listado con intrusos, tope por corrida, idempotencia)", async () => {
  const f = purgeFake(
    [cand("evil_v1", { evidencePath: "videos/owner/video_1.mp4" }), cand("intr_v1"), cand("a_v1"), cand("b_v1"), cand("c_v1")],
    { [evidencePrefix("intr_v1")]: [`${evidencePrefix("intr_v1")}ok.mp4`, "videos/owner/video_1.mp4"] }
  );
  const s = await runEvidencePurge(f.deps, { ...wet, maxReportsPerRun: 2 });
  assert.ok(!f.listed.includes("videos/owner/"), "no lista fuera de moderation_evidence/<id>/");
  assert.ok(!f.deleted.includes("videos/owner/video_1.mp4"));
  assert.ok(!f.purged.includes("evil_v1"));
  assert.ok(!f.purged.includes("intr_v1"));
  assert.equal(s.deferredByCap, 2);
  assert.deepEqual(f.purged, ["a_v1"]);
  // idempotencia: sin blobs restantes solo se marca
  const g = purgeFake([cand("z_v1")], {});
  await runEvidencePurge(g.deps, wet);
  assert.deepEqual(g.purged, ["z_v1"]);
});
test("purga: el piso de retencion de resueltos es 7 dias aunque el env diga menos", () => {
  assert.equal(purgeConfigFromEnv({ EVIDENCE_RETENTION_DAYS: "1" }).resolvedRetentionMs, 7 * DAY_MS);
});

// ---- retiro por moderador ----
function removeFake(report: ReportDoc | null, over: Record<string, unknown> = {}) {
  const calls: string[] = [];
  const actions: Record<string, Record<string, unknown>> = {};
  let resolved: Record<string, unknown> | null = null;
  const deps = {
    now: () => T0, log: () => {},
    getReport: async () => report,
    getVideo: async () => ({ userId: "owner", videoUrl: URL_OF(SRC), thumbnailUrl: URL_OF("thumbnails/owner/thumb_1700000000000.jpg"), createdAt: 1700000000000 }),
    preserve: async () => { calls.push("preserve"); return "preserved" as const; },
    writeAction: async (id: string, d: Record<string, unknown>) => { calls.push("action"); actions[id] = d; },
    deleteBlob: async (p: string) => { calls.push(`blob:${p}`); },
    deleteVideoDoc: async (v: string) => { calls.push(`doc:${v}`); },
    resolveReport: async (_id: string, f: Record<string, unknown>) => { calls.push("resolve"); resolved = f; },
    ...over
  };
  return { deps, calls, actions, resolved: () => resolved };
}
const mod = { uid: "mod1", isModerator: true };

test("retiro: exige claim moderador, reportId valido y estado in_review (nada se toca si falla)", async () => {
  const f = removeFake(mkReport({ status: "in_review" }));
  await assert.rejects(removeReportedVideo(f.deps, { uid: "alice", isModerator: false }, "alice_v1"), (e: ModerationError) => e.code === "permission-denied");
  await assert.rejects(removeReportedVideo(f.deps, { isModerator: true }, "alice_v1"), (e: ModerationError) => e.code === "permission-denied");
  await assert.rejects(removeReportedVideo(f.deps, mod, "../x"), (e: ModerationError) => e.code === "invalid-argument");
  await assert.rejects(removeReportedVideo(f.deps, mod, 42), (e: ModerationError) => e.code === "invalid-argument");
  await assert.rejects(removeReportedVideo(removeFake(null).deps, mod, "alice_v1"), (e: ModerationError) => e.code === "not-found");
  for (const status of ["pending", "resolved"]) {
    const g = removeFake(mkReport({ status }));
    await assert.rejects(removeReportedVideo(g.deps, mod, "alice_v1"), (e: ModerationError) => e.code === "failed-precondition");
    assert.deepEqual(g.calls, []);
  }
  assert.deepEqual(f.calls, []);
});
test("retiro: orden evidencia -> auditoria -> borrado -> cierre; solo carpetas del autor", async () => {
  const f = removeFake(mkReport({ status: "in_review" }));
  const r = await removeReportedVideo(f.deps, mod, "alice_v1");
  assert.equal(r.removed, true);
  assert.deepEqual(f.calls, ["preserve", "action", `blob:${SRC}`, "blob:thumbnails/owner/thumb_1700000000000.jpg", "doc:v1", "resolve"]);
  assert.equal(f.actions["remove_alice_v1"].moderatorUid, "mod1");
  assert.deepEqual(f.resolved(), { status: "resolved", resolution: "content_removed", reviewedBy: "mod1", reviewedAt: T0 });
});
test("retiro: si la evidencia no se preserva NO se borra nada ni se audita", async () => {
  const f = removeFake(mkReport({ status: "in_review" }), { preserve: async () => "failed" });
  await assert.rejects(removeReportedVideo(f.deps, mod, "alice_v1"), (e: ModerationError) => e.code === "failed-precondition");
  assert.deepEqual(f.calls, []);
  const g = removeFake(mkReport({ status: "in_review" }), { preserve: async () => { throw new Error("storage"); } });
  await assert.rejects(removeReportedVideo(g.deps, mod, "alice_v1"), /storage/);
  assert.deepEqual(g.calls, []);
});
test("retiro: video de otro autor o URLs fuera de la carpeta del autor => no borra blobs ajenos", async () => {
  const f = removeFake(mkReport({ status: "in_review" }), { getVideo: async () => ({ userId: "otro" }) });
  await assert.rejects(removeReportedVideo(f.deps, mod, "alice_v1"), (e: ModerationError) => e.code === "failed-precondition");
  const g = removeFake(mkReport({ status: "in_review" }), { getVideo: async () => ({ userId: "owner", videoUrl: URL_OF("videos/victima/video_1.mp4"), createdAt: 1700000000000 }) });
  await assert.rejects(removeReportedVideo(g.deps, mod, "alice_v1"), (e: ModerationError) => e.code === "failed-precondition");
  assert.deepEqual(g.calls, []);
});
test("retiro: doc distinto al reportado (otra url/fecha) => no borra; sin video => removed:false sin auditar ni resolver", async () => {
  const otraFecha = removeFake(mkReport({ status: "in_review" }), { getVideo: async () => ({ userId: "owner", videoUrl: URL_OF(SRC), createdAt: 5 }) });
  await assert.rejects(removeReportedVideo(otraFecha.deps, mod, "alice_v1"), (e: ModerationError) => e.code === "failed-precondition");
  assert.deepEqual(otraFecha.calls, []);
  const sinVideo = removeFake(mkReport({ status: "in_review" }), { getVideo: async () => null });
  const r = await removeReportedVideo(sinVideo.deps, mod, "alice_v1");
  assert.equal(r.removed, false);
  assert.deepEqual(sinVideo.calls, []);
});

// ---- interaccion con B2 ----
test("cleanupOrphanedBlobs nunca lista ni borra moderation_evidence/", async () => {
  const prefixes: string[] = [];
  const deleted: string[] = [];
  const old = Date.now() - 10 * 24 * 3600e3;
  await runOrphanCleanup({
    now: Date.now, log: () => {},
    listPage: async (prefix) => {
      prefixes.push(prefix);
      return { blobs: [{ name: "moderation_evidence/a_v1/video_1.mp4", createdMs: old }] };
    },
    isReferenced: async () => false,
    deleteBlob: async (p) => { deleted.push(p); }
  }, { ...configFromEnv({}), dryRun: false });
  assert.deepEqual(prefixes.sort(), ["thumbnails/", "videos/"]);
  assert.deepEqual(deleted, []);
});
