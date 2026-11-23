// Pruebas de reglas contra el Firestore Emulator (projectId demo-boomday, nunca un proyecto real).
// RULES_PATH permite probar otra version de las reglas (p. ej. la anterior, para demostrar el bug).
const { test, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, updateDoc, deleteDoc, writeBatch, runTransaction, increment } = require('firebase/firestore');

const RULES = process.env.RULES_PATH || path.join(__dirname, '..', 'firestore.rules');
const BUCKET = 'boomday-e2308.firebasestorage.app';
let env;

const dbOf = (uid) => env.authenticatedContext(uid).firestore();
const vid = 'v1';
const viewRef = (db, uid) => doc(db, 'videos', vid, 'views', uid);
const videoRef = (db) => doc(db, 'videos', vid);
const url = (folder, uid, bucket = BUCKET) =>
  `https://firebasestorage.googleapis.com/v0/b/${bucket}/o/${folder}%2F${uid}%2Ff.mp4`;
const baseVideo = (uid, over = {}) => ({
  userId: uid, views: 0, likes: 0, comments: 0, shares: 0, duration: 10,
  title: 't', description: 'd', hashtags: [], createdAt: Date.now(), dayKey: '2026-11-23',
  videoUrl: url('videos', uid), thumbnailUrl: '', ...over,
});

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-boomday',
    firestore: { rules: fs.readFileSync(RULES, 'utf8') },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(videoRef(ctx.firestore()), baseVideo('owner'));
  });
});

const getViews = async () => {
  let v;
  await env.withSecurityRulesDisabled(async (ctx) => {
    v = (await getDoc(videoRef(ctx.firestore()))).data().views;
  });
  return v;
};
const expectViews = async (n) => {
  const v = await getViews();
  if (v !== n) throw new Error(`views = ${v}, esperado ${n}`);
};
const honestBatch = (db, uid) => {
  const b = writeBatch(db);
  b.set(viewRef(db, uid), { viewedAt: Date.now() });
  b.update(videoRef(db), { views: increment(1) });
  return b.commit();
};
// Replica de VideoRepository.incrementViews()
const honestTx = (db, uid) => runTransaction(db, async (t) => {
  const s = await t.get(viewRef(db, uid));
  if (!s.exists()) {
    t.set(viewRef(db, uid), { viewedAt: Date.now() });
    t.update(videoRef(db), { views: increment(1) });
  }
});

// ---------- views: integridad del ranking ----------
test('V01 primera vista valida (batch dedupe+increment)', async () => {
  await assertSucceeds(honestBatch(dbOf('alice'), 'alice'));
  await expectViews(1);
});
test('V02 cliente honesto: runTransaction get->set+update(increment) aceptado', async () => {
  await assertSucceeds(honestTx(dbOf('alice'), 'alice'));
  await expectViews(1);
});
test('V03 cliente honesto repetido: no-op, sigue en 1', async () => {
  const db = dbOf('alice');
  await assertSucceeds(honestTx(db, 'alice'));
  await assertSucceeds(honestTx(db, 'alice'));
  await expectViews(1);
});
test('V04 vista repetida (cliente modificado) rechazada', async () => {
  const db = dbOf('alice');
  await assertSucceeds(honestBatch(db, 'alice'));
  await assertFails(honestBatch(db, 'alice'));
  await assertFails(updateDoc(videoRef(db), { views: increment(1) }));
  await expectViews(1);
});
test('V05 incrementos arbitrarios: +2, -1, +0, +1000, set directo', async () => {
  const db = dbOf('alice');
  for (const n of [2, -1, 0, 1000]) {
    const b = writeBatch(db);
    b.set(viewRef(db, 'alice'), { viewedAt: Date.now() });
    b.update(videoRef(db), { views: increment(n) });
    await assertFails(b.commit());
  }
  const b2 = writeBatch(db);
  b2.set(viewRef(db, 'alice'), { viewedAt: Date.now() });
  b2.update(videoRef(db), { views: 999999 });
  await assertFails(b2.commit());
  await expectViews(0);
});
test('V06 no autenticado no puede votar ni leer', async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertFails(updateDoc(videoRef(db), { views: increment(1) }));
  await assertFails(setDoc(viewRef(db, 'alice'), { viewedAt: 1 }));
  await assertFails(getDoc(videoRef(db)));
});
test('V07 usuario distinto: bob no puede crear views/alice', async () => {
  const db = dbOf('bob');
  await assertFails(setDoc(viewRef(db, 'alice'), { viewedAt: Date.now() }));
  await assertFails(honestBatch(db, 'alice'));
  await expectViews(0);
});
test('V08 modificar contador sin crear dedupe rechazado', async () => {
  await assertFails(updateDoc(videoRef(dbOf('alice')), { views: increment(1) }));
});
test('V09 dedupe creado aparte + update aislado: rechazado (bypass del bug original)', async () => {
  const db = dbOf('alice');
  await assertSucceeds(setDoc(viewRef(db, 'alice'), { viewedAt: Date.now() }));
  await assertFails(updateDoc(videoRef(db), { views: increment(1) }));
  await assertFails(honestBatch(db, 'alice'));
  await expectViews(0);
});
test('V10 update/delete del dedupe denegados; lectura solo del propio uid', async () => {
  const db = dbOf('alice');
  await assertSucceeds(honestBatch(db, 'alice'));
  await assertFails(updateDoc(viewRef(db, 'alice'), { viewedAt: 1 }));
  await assertFails(deleteDoc(viewRef(db, 'alice')));
  await assertSucceeds(getDoc(viewRef(db, 'alice')));
  await assertFails(getDoc(viewRef(dbOf('bob'), 'alice')));
  await assertFails(deleteDoc(viewRef(dbOf('owner'), 'alice')));
});
test('V11 dedupe con campos extra o viewedAt no entero rechazado', async () => {
  const db = dbOf('alice');
  await assertFails(setDoc(viewRef(db, 'alice'), { viewedAt: Date.now(), x: 1 }));
  await assertFails(setDoc(viewRef(db, 'alice'), { viewedAt: 'hoy' }));
});
test('V12 no se pueden tocar otros campos (solo o junto con views)', async () => {
  const db = dbOf('alice');
  for (const extra of [{ likes: 5 }, { comments: 1 }, { shares: 1 }, { title: 'hack' }, { userId: 'alice' }]) {
    const b = writeBatch(db);
    b.set(viewRef(db, 'alice'), { viewedAt: Date.now() });
    b.update(videoRef(db), { views: increment(1), ...extra });
    await assertFails(b.commit());
  }
  for (const f of ['likes', 'comments', 'shares']) {
    const b = writeBatch(db);
    b.set(viewRef(db, 'alice'), { viewedAt: Date.now() });
    b.update(videoRef(db), { [f]: increment(1) });
    await assertFails(b.commit());
  }
  await expectViews(0);
});
test('V13 dueno no edita contadores directamente pero si el titulo', async () => {
  await assertFails(updateDoc(videoRef(dbOf('owner')), { views: 5000 }));
  await assertSucceeds(updateDoc(videoRef(dbOf('owner')), { title: 'nuevo' }));
});
test('V14 concurrencia: dos batches simultaneos del mismo uid, solo uno se aplica', async () => {
  const db = dbOf('alice');
  const rs = await Promise.allSettled([honestBatch(db, 'alice'), honestBatch(db, 'alice')]);
  const ok = rs.filter((r) => r.status === 'fulfilled').length;
  if (ok !== 1) throw new Error(`se aplicaron ${ok} batches (esperado 1)`);
  await expectViews(1);
});
test('V15 concurrencia: 10 batches simultaneos del mismo uid -> views == 1', async () => {
  const db = dbOf('alice');
  await Promise.allSettled(Array.from({ length: 10 }, () => honestBatch(db, 'alice')));
  await expectViews(1);
});
test('V16 concurrencia: dos runTransaction del mismo uid -> views == 1', async () => {
  const db = dbOf('alice');
  await Promise.allSettled([honestTx(db, 'alice'), honestTx(db, 'alice')]);
  await expectViews(1);
});
test('V17 usuarios distintos suman cada uno 1', async () => {
  await assertSucceeds(honestBatch(dbOf('alice'), 'alice'));
  await assertSucceeds(honestBatch(dbOf('bob'), 'bob'));
  await expectViews(2);
});
test('V18 compat datos existentes: dedupe previo (cualquier forma) bloquea nuevo +1; otros uids siguen pudiendo', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(viewRef(ctx.firestore(), 'old'), { viewedAt: 1, legacy: true });
  });
  await assertFails(honestBatch(dbOf('old'), 'old'));
  await assertSucceeds(honestBatch(dbOf('new'), 'new'));
});

// ---------- regresion create de videos ----------
const create = (uid, over) => setDoc(doc(dbOf(uid), 'videos', 'n' + Math.random().toString(36).slice(2)), baseVideo(uid, over));
test('C01 create valido', async () => { await assertSucceeds(create('alice', {})); });
test('C02 create valido con thumbnail propia', async () => {
  await assertSucceeds(create('alice', { thumbnailUrl: url('thumbnails', 'alice') }));
});
test('C03 videoUrl con uid ajeno', async () => { await assertFails(create('alice', { videoUrl: url('videos', 'bob') })); });
test('C04 thumbnailUrl con uid ajeno', async () => {
  await assertFails(create('alice', { thumbnailUrl: url('thumbnails', 'bob') }));
});
test('C05 bucket ajeno', async () => {
  await assertFails(create('alice', { videoUrl: url('videos', 'alice', 'otro-bucket.firebasestorage.app') }));
});
test('C06 host ajeno', async () => {
  await assertFails(create('alice', { videoUrl: 'https://evil.example.com/v0/b/boomday-e2308.firebasestorage.app/o/videos%2Falice%2Ff.mp4' }));
});
test('C07 carpeta equivocada (thumbnails en videoUrl)', async () => {
  await assertFails(create('alice', { videoUrl: url('thumbnails', 'alice') }));
});
test('C08 createdAt en el pasado > 10 min', async () => {
  await assertFails(create('alice', { createdAt: Date.now() - 11 * 60000 }));
});
test('C09 createdAt en el futuro > 10 min', async () => {
  await assertFails(create('alice', { createdAt: Date.now() + 11 * 60000 }));
});
test('C10 createdAt dentro de +-5 min aceptado', async () => {
  await assertSucceeds(create('alice', { createdAt: Date.now() - 5 * 60000 }));
  await assertSucceeds(create('alice', { createdAt: Date.now() + 5 * 60000 }));
});
test('C11 createdAt no entero', async () => { await assertFails(create('alice', { createdAt: 'ahora' })); });
test('C12 userId ajeno / views iniciales != 0', async () => {
  await assertFails(create('alice', { userId: 'bob' }));
  await assertFails(create('alice', { views: 1 }));
});
