// Pruebas de reglas contra el Firestore Emulator (projectId demo-boomday, nunca un proyecto real).
// RULES_PATH permite probar otra version de las reglas (p. ej. la anterior, para demostrar el bug).
const { test, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, runTransaction, increment, collection, query, where } = require('firebase/firestore');

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
  videoUrl: url('videos', uid), thumbnailUrl: '', userName: '', userAvatar: '', ...over,
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

// ---------- B1: identidad del autor (userName/userAvatar) y /users ----------
const GPIC = 'https://lh3.googleusercontent.com/a/abc=s96-c';
const dbAs = (uid, claims) => env.authenticatedContext(uid, claims).firestore();
const createAs = (uid, claims, over) =>
  setDoc(doc(dbAs(uid, claims), 'videos', 'i' + Math.random().toString(36).slice(2)), baseVideo(uid, over));
const ALICE = { name: 'Alice', picture: GPIC };
const userRef = (db, uid) => doc(db, 'users', uid);

test('I01 legitimo: userName/userAvatar == claims del token', async () => {
  await assertSucceeds(createAs('alice', ALICE, { userName: 'Alice', userAvatar: GPIC }));
});
test('I02 legitimo sin foto: avatar vacio aceptado', async () => {
  await assertSucceeds(createAs('alice', ALICE, { userName: 'Alice', userAvatar: '' }));
});
test('I03 legitimo sin claims (cuenta sin nombre/foto): "" y ""', async () => {
  await assertSucceeds(createAs('alice', {}, { userName: '', userAvatar: '' }));
});
test('I04 usuario ajeno: userId de otro con identidad propia', async () => {
  await assertFails(createAs('alice', ALICE, { userId: 'bob', userName: 'Alice', userAvatar: GPIC }));
});
test('I05 identidad falsificada: userName de otra persona', async () => {
  await assertFails(createAs('alice', ALICE, { userName: 'Bob', userAvatar: GPIC }));
});
test('I06 identidad falsificada: avatar de otra persona (host Google distinto)', async () => {
  await assertFails(createAs('alice', ALICE, { userName: 'Alice', userAvatar: 'https://lh3.googleusercontent.com/a/otro' }));
});
test('I07 avatar en URL externa arbitraria (tracking)', async () => {
  await assertFails(createAs('alice', ALICE, { userName: 'Alice', userAvatar: 'https://evil.example.com/p.gif?u=1' }));
  await assertFails(createAs('alice', {}, { userAvatar: 'http://10.0.0.1/x.png' }));
});
test('I08 tipos invalidos: userName/userAvatar no string o ausentes', async () => {
  await assertFails(createAs('alice', ALICE, { userName: 5, userAvatar: GPIC }));
  await assertFails(createAs('alice', ALICE, { userName: 'Alice', userAvatar: null }));
  await assertFails(createAs('alice', ALICE, { userName: 'Alice', userAvatar: ['x'] }));
  const d = baseVideo('alice'); delete d.userName; delete d.userAvatar;
  await assertFails(setDoc(doc(dbAs('alice', ALICE), 'videos', 'noid'), d));
});
test('I09 tamano: userName enorme que no coincide con el token', async () => {
  await assertFails(createAs('alice', ALICE, { userName: 'x'.repeat(5000), userAvatar: '' }));
});
test('I10 update del dueno no puede editar userName/userAvatar/userId', async () => {
  const db = dbAs('owner', { name: 'Owner' });
  for (const f of [{ userName: 'Otro' }, { userAvatar: 'https://evil.example.com/p.gif' }, { userId: 'bob' }]) {
    await assertFails(updateDoc(videoRef(db), { ...f, title: 'x' }));
    await assertFails(updateDoc(videoRef(db), f));
  }
  await assertSucceeds(updateDoc(videoRef(db), { title: 'ok' }));
});
test('I11 compat: doc legacy (sin userName/userAvatar o con avatar externo) sigue operable', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'videos', 'legacy'), { ...baseVideo('owner'), userName: 'Viejo', userAvatar: 'https://old.example.com/a.png' });
  });
  const alice = dbOf('alice'), owner = dbOf('owner');
  const legacy = (db) => doc(db, 'videos', 'legacy');
  await assertSucceeds(getDoc(legacy(alice)));
  await assertSucceeds(updateDoc(legacy(owner), { title: 'editado' }));
  const b = writeBatch(alice);
  b.set(doc(alice, 'videos', 'legacy', 'views', 'alice'), { viewedAt: Date.now() });
  b.update(legacy(alice), { views: increment(1) });
  await assertSucceeds(b.commit());
  await assertSucceeds(deleteDoc(legacy(owner)));
});
// /users/{uid}
const userDoc = (uid, over = {}) => ({ uid, email: 'a@b.c', name: 'Alice', avatar: GPIC, type: 'free', createdAt: new Date(), updatedAt: new Date(), ...over });
test('U01 users: create legitimo (forma de UserRepository, avatar null o Google)', async () => {
  await assertSucceeds(setDoc(userRef(dbAs('alice', ALICE), 'alice'), userDoc('alice')));
  await assertSucceeds(setDoc(userRef(dbAs('bob', {}), 'bob'), userDoc('bob', { name: null, avatar: null })));
});
test('U02 users: createUser(User) con createdAt Long y blockedUsers', async () => {
  await assertSucceeds(setDoc(userRef(dbOf('alice'), 'alice'), userDoc('alice', { avatar: null, createdAt: Date.now(), blockedUsers: [] })));
});
test('U03 users: merge de blockedUsers/updatedAt sobre doc existente (legacy)', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await setDoc(userRef(ctx.firestore(), 'alice'), { uid: 'alice', email: 'a@b.c', avatar: 'https://viejo.example.com/a.png', extra: 1 }); });
  await assertSucceeds(updateDoc(userRef(dbOf('alice'), 'alice'), { updatedAt: new Date() }).catch(() => { throw new Error('legacy con avatar externo no debe bloquear updatedAt'); }));
});
test('U04 users: avatar externo / tamano / tipos / uid ajeno rechazados', async () => {
  const db = dbOf('alice');
  await assertFails(setDoc(userRef(db, 'alice'), userDoc('alice', { avatar: 'https://evil.example.com/p.gif' })));
  await assertFails(setDoc(userRef(db, 'alice'), userDoc('alice', { name: 'x'.repeat(101) })));
  await assertFails(setDoc(userRef(db, 'alice'), userDoc('alice', { name: 7 })));
  await assertFails(setDoc(userRef(db, 'alice'), userDoc('alice', { avatar: 'https://lh3.googleusercontent.com/' + 'a'.repeat(600) })));
  await assertFails(setDoc(userRef(db, 'alice'), userDoc('bob')));
  await assertFails(setDoc(userRef(db, 'alice'), userDoc('alice', { blockedUsers: 'x' })));
  await assertFails(setDoc(userRef(dbOf('bob'), 'alice'), userDoc('alice')));
});

// B2: ops_metrics es solo-servidor.
test('M01 ops_metrics: ningun cliente (autenticado, dueno de videos o anonimo) lee ni escribe', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await setDoc(doc(ctx.firestore(), 'ops_metrics', '2026-11-25'), { orphan_runs: 1 }); });
  for (const db of [dbOf('alice'), dbOf('owner'), env.unauthenticatedContext().firestore()]) {
    const r = doc(db, 'ops_metrics', '2026-11-25');
    await assertFails(getDoc(r));
    await assertFails(setDoc(r, { orphan_runs: 0 }));
    await assertFails(updateDoc(r, { orphan_runs: 0 }));
    await assertFails(deleteDoc(r));
    await assertFails(setDoc(doc(db, 'ops_metrics', 'nuevo'), { x: 1 }));
  }
});

// ---------- B3: reportes y moderacion ----------
const repRef = (db, id) => doc(db, 'reports', id);
const modDb = () => env.authenticatedContext('mod1', { moderator: true }).firestore();
const goodReport = (uid, over = {}, video = baseVideo('owner')) => ({
  reportId: `${uid}_${vid}`, reporterId: uid, reportedUserId: 'owner', reportedVideoId: vid,
  reason: 'spam', status: 'pending', createdAt: Date.now(),
  evidence: { videoUrl: video.videoUrl, title: video.title, videoCreatedAt: video.createdAt, authorName: video.userName },
  ...over,
});
// El video sembrado por beforeEach tiene createdAt = Date.now() en ESE momento; lo releemos para el snapshot.
const seededVideo = async () => {
  let v;
  await env.withSecurityRulesDisabled(async (ctx) => { v = (await getDoc(videoRef(ctx.firestore()))).data(); });
  return v;
};
const report = async (uid, over = {}) => goodReport(uid, over, await seededVideo());
const seedReport = (id, data) => env.withSecurityRulesDisabled(async (ctx) => { await setDoc(repRef(ctx.firestore(), id), data); });

test('R01 reporte valido (id determinista, evidencia coherente)', async () => {
  await assertSucceeds(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice')));
});
test('R02 con details <= 300 aceptado; > 300 rechazado', async () => {
  await assertSucceeds(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', { details: 'x'.repeat(300) })));
  await assertFails(setDoc(repRef(dbOf('bob'), `bob_${vid}`), await report('bob', { details: 'x'.repeat(301) })));
});
test('R03 duplicado del mismo usuario y video rechazado (create-only)', async () => {
  const db = dbOf('alice');
  await assertSucceeds(setDoc(repRef(db, `alice_${vid}`), await report('alice')));
  await assertFails(setDoc(repRef(db, `alice_${vid}`), await report('alice', { reason: 'ilegal' })));
});
test('R04 id aleatorio (no determinista) rechazado', async () => {
  await assertFails(setDoc(repRef(dbOf('alice'), 'idAleatorio'), await report('alice', { reportId: 'idAleatorio' })));
});
test('R05 video inexistente rechazado', async () => {
  const r = await report('alice', { reportedVideoId: 'fantasma', reportId: 'alice_fantasma' });
  await assertFails(setDoc(repRef(dbOf('alice'), 'alice_fantasma'), r));
});
test('R06 reportedUserId que no es el autor rechazado', async () => {
  await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', { reportedUserId: 'bob' })));
});
test('R07 auto-reporte rechazado', async () => {
  await assertFails(setDoc(repRef(dbOf('owner'), `owner_${vid}`), await report('owner')));
});
test('R08 reporterId ajeno / anonimo rechazado', async () => {
  await assertFails(setDoc(repRef(dbOf('alice'), `bob_${vid}`), await report('bob')));
  await assertFails(setDoc(repRef(env.unauthenticatedContext().firestore(), `alice_${vid}`), await report('alice')));
});
test('R09 reason fuera del catalogo rechazado; todos los codigos del catalogo aceptados', async () => {
  await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', { reason: 'inappropriate' })));
  await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', { reason: '' })));
  let i = 0;
  for (const reason of ['spam', 'acoso', 'contenido_sexual', 'violencia', 'ilegal', 'otro']) {
    const u = `u${i++}`;
    await assertSucceeds(setDoc(repRef(dbOf(u), `${u}_${vid}`), await report(u, { reason })));
  }
});
test('R10 campos extra (moderacion del cliente) rechazados', async () => {
  for (const extra of [{ reviewedBy: 'x' }, { resolution: 'dismissed' }, { reviewedAt: 1 }, { evidenceState: 'preserved' }, { foo: 1 }]) {
    await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', extra)));
  }
});
test('R11 status distinto de pending rechazado', async () => {
  for (const status of ['resolved', 'in_review', '']) {
    await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', { status })));
  }
});
test('R12 faltan campos obligatorios (evidence / status) rechazado', async () => {
  const r = await report('alice'); delete r.evidence;
  await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), r));
  const r2 = await report('alice'); delete r2.status;
  await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), r2));
});
test('R13 createdAt fuera de +-10 min rechazado', async () => {
  await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', { createdAt: Date.now() - 11 * 60000 })));
  await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), await report('alice', { createdAt: Date.now() + 11 * 60000 })));
});
test('R14 evidencia que no coincide con el doc del video rechazada (url, titulo, fecha, autor, claves extra)', async () => {
  const base = await report('alice');
  for (const patch of [{ videoUrl: url('videos', 'otro') }, { title: 'otro titulo' }, { videoCreatedAt: 1 }, { authorName: 'otro' }, { email: 'a@b.c' }]) {
    await assertFails(setDoc(repRef(dbOf('alice'), `alice_${vid}`), { ...base, evidence: { ...base.evidence, ...patch } }));
  }
});
test('R15 el reportante NO lee reportes propios ni ajenos; anonimo tampoco', async () => {
  await seedReport(`alice_${vid}`, await report('alice'));
  await assertFails(getDoc(repRef(dbOf('alice'), `alice_${vid}`)));
  await assertFails(getDoc(repRef(dbOf('bob'), `alice_${vid}`)));
  await assertFails(getDoc(repRef(env.unauthenticatedContext().firestore(), `alice_${vid}`)));
  await assertFails(getDocs(query(collection(dbOf('alice'), 'reports'), where('reporterId', '==', 'alice'))));
});
test('R16 usuario normal no cambia estado ni borra (propio, ajeno, o con claim falso moderator=false)', async () => {
  await seedReport(`alice_${vid}`, await report('alice'));
  const falso = env.authenticatedContext('bob', { moderator: false }).firestore();
  for (const db of [dbOf('alice'), dbOf('bob'), falso]) {
    await assertFails(updateDoc(repRef(db, `alice_${vid}`), { status: 'in_review', reviewedBy: 'alice', reviewedAt: Date.now() }));
    await assertFails(deleteDoc(repRef(db, `alice_${vid}`)));
  }
});
test('R17 moderador lee y lista reportes', async () => {
  await seedReport(`alice_${vid}`, await report('alice'));
  await assertSucceeds(getDoc(repRef(modDb(), `alice_${vid}`)));
  await assertSucceeds(getDocs(collection(modDb(), 'reports')));
});
const mod = (over) => ({ reviewedBy: 'mod1', reviewedAt: Date.now(), ...over });
test('R18 moderador: pending -> in_review -> resolved (dismissed / user_actioned)', async () => {
  for (const [i, resolution] of ['dismissed', 'user_actioned'].entries()) {
    const id = `alice${i}_${vid}`;
    await seedReport(id, await report(`alice${i}`));
    await assertSucceeds(updateDoc(repRef(modDb(), id), mod({ status: 'in_review' })));
    await assertSucceeds(updateDoc(repRef(modDb(), id), mod({ status: 'resolved', resolution })));
  }
});
test('R19 transiciones invalidas: saltos, retrocesos y reapertura denegados', async () => {
  const id = `alice_${vid}`;
  await seedReport(id, await report('alice'));
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'resolved', resolution: 'dismissed' }))); // pending -> resolved
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'pending' })));
  await assertSucceeds(updateDoc(repRef(modDb(), id), mod({ status: 'in_review' })));
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'pending' }))); // retroceso
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'in_review' }))); // repetir
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'resolved' }))); // sin resolution
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'resolved', resolution: 'borrar_todo' })));
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'resolved', resolution: 'content_removed' }))); // solo la Function
  await assertSucceeds(updateDoc(repRef(modDb(), id), mod({ status: 'resolved', resolution: 'dismissed' })));
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'in_review' }))); // reabrir
  await assertFails(updateDoc(repRef(modDb(), id), mod({ status: 'resolved', resolution: 'user_actioned' }))); // cambiar resolucion
});
test('R20 moderador no edita campos protegidos ni suplanta reviewedBy ni borra', async () => {
  const id = `alice_${vid}`;
  await seedReport(id, await report('alice'));
  const ok = mod({ status: 'in_review' });
  for (const extra of [{ reporterId: 'x' }, { reportId: 'y' }, { reason: 'otro' }, { reportedUserId: 'z' }, { createdAt: 1 }, { evidence: {} }, { 'evidence.title': 'x' }, { details: 'x' }, { evidenceState: 'purged' }]) {
    await assertFails(updateDoc(repRef(modDb(), id), { ...ok, ...extra }));
  }
  await assertFails(updateDoc(repRef(modDb(), id), { ...ok, reviewedBy: 'otro-mod' }));
  await assertFails(updateDoc(repRef(modDb(), id), { ...ok, reviewedAt: Date.now() - 3600000 }));
  await assertFails(deleteDoc(repRef(modDb(), id)));
});
test('R21 moderation_actions y report_limits: ningun cliente (ni moderador)', async () => {
  for (const col of ['moderation_actions', 'report_limits']) {
    await env.withSecurityRulesDisabled(async (ctx) => { await setDoc(doc(ctx.firestore(), col, 'x'), { a: 1 }); });
    for (const db of [dbOf('alice'), modDb(), env.unauthenticatedContext().firestore()]) {
      await assertFails(getDoc(doc(db, col, 'x')));
      await assertFails(setDoc(doc(db, col, 'x'), { a: 2 }));
      await assertFails(setDoc(doc(db, col, 'nuevo'), { a: 2 }));
      await assertFails(deleteDoc(doc(db, col, 'x')));
    }
  }
});

// ---------- B3 (QA): charset del videoId y claves exactas de videos ----------
test('R22 reportedVideoId con charset invalido o > 64 rechazado; UUID y auto-id aceptados', async () => {
  for (const id of ['a.b', 'a b', 'x'.repeat(65)]) {
    const v = baseVideo('owner');
    await env.withSecurityRulesDisabled(async (ctx) => { await setDoc(doc(ctx.firestore(), 'videos', id), v); });
    await assertFails(setDoc(repRef(dbOf('alice'), `alice_${id}`), goodReport('alice', { reportedVideoId: id, reportId: `alice_${id}` }, v)));
  }
  for (const id of ['3f2b8c1e-5d4a-4c6b-9e7f-0a1b2c3d4e5f', 'Ab3dEf6hIj9lMn2pQr5T']) {
    const v = baseVideo('owner');
    await env.withSecurityRulesDisabled(async (ctx) => { await setDoc(doc(ctx.firestore(), 'videos', id), v); });
    const r = goodReport('alice', { reportedVideoId: id, reportId: `alice_${id}` }, v);
    await assertSucceeds(setDoc(repRef(dbOf('alice'), `alice_${id}`), r));
  }
});
test('C13 videos create: claves no previstas rechazadas; hashtags no-lista rechazado; modelo completo de Video.kt aceptado', async () => {
  await assertFails(create('alice', { campoInventado: 1 }));
  await assertFails(create('alice', { hashtags: 'a,b' }));
  await assertSucceeds(create('alice', { videoId: 'x', updatedAt: new Date(), hashtags: ['a', 'b'] }));
});
