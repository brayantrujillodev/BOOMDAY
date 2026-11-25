import { test } from "node:test";
import assert from "node:assert/strict";
import { BlobInfo, BlobKind, DEFAULT_CONFIG, OrphanConfig, OrphanDeps, classifyPath, configFromEnv, runOrphanCleanup } from "./orphans";
import { enforceUploadQuota } from "./quota";

const H = 3600 * 1000;
const NOW = Date.parse("2026-01-10T12:00:00Z");
const cfg = (o: Partial<OrphanConfig> = {}): OrphanConfig => ({ ...DEFAULT_CONFIG, dryRun: false, ...o });

/** Doble en memoria: storage + referencias. */
function world(opts: {
  blobs: BlobInfo[];
  refs?: string[]; // paths referenciados (videoUrl o thumbnailUrl)
  pageSize?: number;
  failDelete?: string[];
  failLookup?: string[];
  lookupReturnsEmpty?: boolean; // simula Firestore devolviendo vacío por error
}) {
  const store = new Map(opts.blobs.map((b) => [b.name, b]));
  const refs = new Set(opts.refs ?? []);
  const deleted: string[] = [];
  const logs: { level: string; message: string }[] = [];
  const pageSize = opts.pageSize ?? 1000;
  const deps: OrphanDeps = {
    now: () => NOW,
    log: (level, message) => logs.push({ level, message }),
    listPage: async (prefix, token) => {
      const all = [...store.values()].filter((b) => b.name.startsWith(prefix)).sort((a, b) => (a.name < b.name ? -1 : 1));
      const start = token ? Number(token) : 0;
      const slice = all.slice(start, start + pageSize);
      return { blobs: slice, nextToken: start + pageSize < all.length ? String(start + pageSize) : undefined };
    },
    isReferenced: async (path: string, _kind: BlobKind) => {
      if (opts.failLookup?.includes(path)) throw new Error("firestore down");
      if (opts.lookupReturnsEmpty) return false;
      return refs.has(path);
    },
    deleteBlob: async (path) => {
      if (opts.failDelete?.includes(path)) throw new Error("storage 500");
      store.delete(path);
      deleted.push(path);
    }
  };
  return { deps, store, deleted, logs, refs };
}
const blob = (name: string, ageMs: number): BlobInfo => ({ name, createdMs: NOW - ageMs });

test("archivo valido asociado a publicacion: NO se borra", async () => {
  const w = world({ blobs: [blob("videos/u1/video_1700000000000.mp4", 30 * H)], refs: ["videos/u1/video_1700000000000.mp4"] });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.referenced, 1);
  assert.deepEqual(w.deleted, []);
  assert.equal(s.orphans, 0);
});

test("archivo incompleto/sin doc dentro de gracia: NO se borra", async () => {
  const w = world({ blobs: [blob("videos/u1/video_1.mp4", 119 * 60 * 1000)] });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.inGrace, 1);
  assert.deepEqual(w.deleted, []);
});

test("blob huerfano viejo: se borra", async () => {
  const w = world({ blobs: [blob("videos/u1/video_2.mp4", 3 * H)] });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.deepEqual(w.deleted, ["videos/u1/video_2.mp4"]);
  assert.equal(s.deleted, 1);
});

test("blob recien creado: NO se borra (aunque no tenga doc)", async () => {
  const w = world({ blobs: [blob("videos/u1/video_3.mp4", 5 * 1000), { name: "videos/u1/video_4.mp4", createdMs: NOW + 10 * H }] });
  await runOrphanCleanup(w.deps, cfg());
  assert.deepEqual(w.deleted, []);
});

test("fecha ilegible: NO se borra", async () => {
  const w = world({ blobs: [{ name: "videos/u1/video_5.mp4", createdMs: null }] });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.unreadableAge, 1);
  assert.deepEqual(w.deleted, []);
});

test("reintento de publicacion: dos blobs, uno referenciado; borra solo el no referenciado y solo tras gracia", async () => {
  const a = "videos/u1/video_100.mp4", b = "videos/u1/video_200.mp4";
  // Dentro de gracia: ninguno se borra.
  const early = world({ blobs: [blob(a, 40 * H), blob(b, 60 * 60 * 1000)], refs: [a] });
  await runOrphanCleanup(early.deps, cfg());
  assert.deepEqual(early.deleted, []);
  // Pasada la gracia: solo b.
  const late = world({ blobs: [blob(a, 40 * H), blob(b, 3 * H)], refs: [a] });
  await runOrphanCleanup(late.deps, cfg());
  assert.deepEqual(late.deleted, [b]);
  assert.ok(late.store.has(a));
});

test("limpieza repetida es idempotente: segunda corrida sin efectos", async () => {
  const w = world({ blobs: [blob("videos/u1/video_7.mp4", 5 * H), blob("videos/u1/video_8.mp4", 50 * H)], refs: ["videos/u1/video_8.mp4"] });
  const s1 = await runOrphanCleanup(w.deps, cfg());
  const s2 = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s1.deleted, 1);
  assert.equal(s2.deleted, 0);
  assert.equal(s2.orphans, 0);
  assert.deepEqual(w.deleted, ["videos/u1/video_7.mp4"]);
});

test("path ajeno / fuera de prefijo / con '..' / profundidad extra: JAMAS se borra", async () => {
  const names = [
    "videos/u1/../u2/video_1.mp4",
    "videos/..%2Fu2/video_1.mp4",
    "videos/u1/sub/video_1.mp4",
    "videos/video_1.mp4",
    "other/u1/video_1.mp4",
    "videos//video_1.mp4",
    "videos/u1/.hidden",
    "thumbnails/u1/a..b.jpg",
    "videos/u 1/video_1.mp4",
    "videos/u1/"
  ];
  const w = world({ blobs: names.map((n) => blob(n, 100 * H)) });
  const s = await runOrphanCleanup(w.deps, cfg({ ratioMinSample: 1000 }));
  assert.deepEqual(w.deleted, []);
  assert.equal(s.foreign, names.filter((n) => n.startsWith("videos/") || n.startsWith("thumbnails/")).length);
  for (const n of names) assert.equal(classifyPath(n), null, n);
});

test("error parcial durante limpieza: el resto sigue y se informa", async () => {
  const bad = "videos/u1/video_10.mp4";
  const w = world({
    blobs: [blob(bad, 5 * H), blob("videos/u1/video_11.mp4", 5 * H), blob("videos/u1/video_12.mp4", 5 * H), ...Array.from({ length: 30 }, (_, i) => blob(`videos/u2/video_${100 + i}.mp4`, 9 * H))],
    refs: Array.from({ length: 30 }, (_, i) => `videos/u2/video_${100 + i}.mp4`),
    failDelete: [bad]
  });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.deleteFailed, 1);
  assert.equal(s.deleted, 2);
  assert.ok(w.logs.some((l) => l.level === "error"));
  assert.ok(w.store.has(bad));
});

test("fallo de consulta a Firestore en un blob: se asume referenciado (no se borra) y el resto sigue", async () => {
  const w = world({ blobs: [blob("videos/u1/video_20.mp4", 5 * H), blob("videos/u1/video_21.mp4", 5 * H)], failLookup: ["videos/u1/video_20.mp4"] });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.lookupFailed, 1);
  assert.deepEqual(w.deleted, ["videos/u1/video_21.mp4"]);
});

test("freno de seguridad: proporcion anomala de huerfanos (Firestore vacio por error) NO borra nada", async () => {
  const blobs = Array.from({ length: 40 }, (_, i) => blob(`videos/u1/video_${300 + i}.mp4`, 30 * H));
  const w = world({ blobs, refs: blobs.map((b) => b.name), lookupReturnsEmpty: true });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.brakeTripped, true);
  assert.equal(s.orphans, 40);
  assert.deepEqual(w.deleted, []);
  assert.ok(w.logs.some((l) => l.level === "error" && l.message.includes("FRENO")));
});

test("tope de borrados por corrida: el excedente se difiere", async () => {
  const blobs = Array.from({ length: 5 }, (_, i) => blob(`videos/u1/video_${400 + i}.mp4`, 5 * H));
  const keep = Array.from({ length: 95 }, (_, i) => blob(`videos/u2/video_${500 + i}.mp4`, 5 * H));
  const w = world({ blobs: [...blobs, ...keep], refs: keep.map((b) => b.name) });
  const s = await runOrphanCleanup(w.deps, cfg({ maxDeletes: 3 }));
  assert.equal(s.deleted, 3);
  assert.equal(s.deferredByCap, 2);
});

test("dry-run (valor por defecto) no borra nada pero reporta", async () => {
  const w = world({ blobs: [blob("videos/u1/video_30.mp4", 5 * H), blob("thumbnails/u1/thumb_30.jpg", 5 * H)] });
  const s = await runOrphanCleanup(w.deps, { ...DEFAULT_CONFIG });
  assert.equal(DEFAULT_CONFIG.dryRun, true);
  assert.equal(configFromEnv({}).dryRun, true);
  assert.equal(configFromEnv({ ORPHAN_CLEANUP_DRY_RUN: "0" }).dryRun, true);
  assert.equal(configFromEnv({ ORPHAN_CLEANUP_DRY_RUN: "false" }).dryRun, false);
  assert.equal(s.wouldDelete, 2);
  assert.equal(s.deleted, 0);
  assert.deepEqual(w.deleted, []);
});

test("thumbnails huerfanos: se borran; thumbnail referenciado se conserva", async () => {
  const w = world({
    blobs: [blob("thumbnails/u1/thumb_1.jpg", 5 * H), blob("thumbnails/u1/thumb_2.jpg", 5 * H), blob("thumbnails/u1/thumb_3.jpg", 20 * 60 * 1000)],
    refs: ["thumbnails/u1/thumb_2.jpg"]
  });
  const s = await runOrphanCleanup(w.deps, cfg({ ratioMinSample: 100 }));
  assert.deepEqual(w.deleted, ["thumbnails/u1/thumb_1.jpg"]);
  assert.equal(s.referenced, 1);
  assert.equal(s.inGrace, 1);
});

test("paginacion: recorre todas las paginas", async () => {
  const blobs = Array.from({ length: 25 }, (_, i) => blob(`videos/u1/video_${600 + i}.mp4`, 30 * H));
  const w = world({ blobs, refs: blobs.map((b) => b.name), pageSize: 7 });
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.listed, 25);
  assert.equal(s.referenced, 25);
});

test("gracia: el suelo duro de 1 h no se puede reducir por config", async () => {
  assert.equal(configFromEnv({ ORPHAN_GRACE_HOURS: "0" }).graceMs, H);
  const w = world({ blobs: [blob("videos/u1/video_40.mp4", 30 * 60 * 1000)] });
  await runOrphanCleanup(w.deps, cfg({ graceMs: 0 }));
  assert.deepEqual(w.deleted, []);
});

test("re-verificacion previa al borrado: si aparece una referencia, no se borra", async () => {
  const p = "videos/u1/video_50.mp4";
  const w = world({ blobs: [blob(p, 5 * H)] });
  let calls = 0;
  const inner = w.deps.isReferenced;
  w.deps.isReferenced = async (path, kind) => (++calls >= 2 ? true : inner(path, kind));
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.recheckSaved, 1);
  assert.deepEqual(w.deleted, []);
});

test("fallo al listar: aborta sin borrar", async () => {
  const w = world({ blobs: [blob("videos/u1/video_60.mp4", 5 * H)] });
  w.deps.listPage = async () => { throw new Error("boom"); };
  const s = await runOrphanCleanup(w.deps, cfg());
  assert.equal(s.deleted, 0);
  assert.equal(s.brakeTripped, true);
});

// ---- cuota por usuario ----
function quotaWorld(n: number, dryRun: boolean, extra: BlobInfo[] = []) {
  const blobs = [...Array.from({ length: n }, (_, i) => ({ name: `videos/u1/video_${1000 + i}.mp4`, createdMs: NOW - (n - i) * 60 * 1000 })), ...extra];
  const deleted: string[] = [];
  const deps = {
    now: () => NOW,
    log: () => undefined,
    listUserBlobs: async (prefix: string) => blobs.filter((b) => b.name.startsWith(prefix)),
    deleteBlob: async (p: string) => { deleted.push(p); }
  };
  return { blobs, deleted, deps, run: (p: string, limit = 3) => enforceUploadQuota(deps, p, limit, dryRun) };
}

test("cuota: dentro del cupo no se borra; el excedente (solo el propio objeto) si, ordenado por creacion", async () => {
  const q = quotaWorld(5, false);
  assert.equal((await q.run(q.blobs[2].name)).action, "ok");
  assert.equal((await q.run(q.blobs[3].name)).action, "exceeded-deleted");
  assert.deepEqual(q.deleted, [q.blobs[3].name]);
});

test("cuota: dry-run no borra; blobs fuera de ventana 24h no cuentan; otro usuario no cuenta", async () => {
  const old = Array.from({ length: 10 }, (_, i) => ({ name: `videos/u1/video_${i + 1}.mp4`, createdMs: NOW - 48 * H }));
  const other = Array.from({ length: 10 }, (_, i) => ({ name: `videos/u2/video_${i + 1}.mp4`, createdMs: NOW - 1000 }));
  const q = quotaWorld(4, true, [...old, ...other]);
  const r = await q.run(q.blobs[3].name);
  assert.equal(r.action, "exceeded-dry-run");
  assert.equal(r.countInWindow, 4);
  assert.deepEqual(q.deleted, []);
  const q2 = quotaWorld(3, false, [...old, ...other]);
  assert.equal((await q2.run(q2.blobs[2].name)).action, "ok");
});

test("cuota: fallo al listar o path invalido => no borra", async () => {
  const q = quotaWorld(5, false);
  q.deps.listUserBlobs = async () => { throw new Error("x"); };
  assert.equal((await q.run(q.blobs[4].name)).action, "skipped");
  assert.equal((await q.run("videos/u1/../u2/video_1.mp4")).action, "skipped");
  assert.deepEqual(q.deleted, []);
});
