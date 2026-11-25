// Pruebas de reglas de Storage contra el Storage Emulator (projectId demo-boomday, nunca un proyecto real).
// RULES_PATH permite probar otra version de las reglas.
const { test, before, after } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { ref, uploadBytes, getBytes, deleteObject } = require('firebase/storage');

const RULES = process.env.RULES_PATH || path.join(__dirname, '..', 'storage.rules');
const [HOST, PORT] = (process.env.FIREBASE_STORAGE_EMULATOR_HOST || '127.0.0.1:9198').split(':');
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-boomday',
    storage: { rules: fs.readFileSync(RULES, 'utf8'), host: HOST, port: Number(PORT) },
  });
});
after(async () => { await env.cleanup(); });

const stOf = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).storage();
const bytes = (n = 16) => new Uint8Array(n);
let seq = 0;
const vname = (ext) => `video_${String(1700000000000 + seq++)}.${ext}`;
const tname = (ext) => `thumb_${String(1700000000000 + seq++)}.${ext}`;
const put = (uid, p, data, contentType) =>
  uploadBytes(ref(stOf(uid), p), data, contentType === undefined ? undefined : { contentType });
const seed = (p, contentType) => env.withSecurityRulesDisabled(async (ctx) => {
  await uploadBytes(ref(ctx.storage(), p), bytes(), { contentType });
});

test('videos: propio uid con contentType video/mp4 OK', async () => {
  await assertSucceeds(put('alice', `videos/alice/${vname('mp4')}`, bytes(), 'video/mp4'));
});
test('videos: otros video/* (webm, quicktime) OK', async () => {
  await assertSucceeds(put('alice', `videos/alice/${vname('webm')}`, bytes(), 'video/webm'));
  await assertSucceeds(put('alice', `videos/alice/${vname('mov')}`, bytes(), 'video/quicktime'));
});
test('videos: SIN contentType (reproduce el bug: putFile sin metadata) DENEGADA', async () => {
  await assertFails(put('alice', `videos/alice/${vname('mp4')}`, bytes(), undefined));
});
test('videos: application/octet-stream DENEGADA', async () => {
  await assertFails(put('alice', `videos/alice/${vname('mp4')}`, bytes(), 'application/octet-stream'));
});
test('videos: contentType image/jpeg DENEGADA', async () => {
  await assertFails(put('alice', `videos/alice/${vname('mp4')}`, bytes(), 'image/jpeg'));
});
test('videos: subir en carpeta de otro uid DENEGADA', async () => {
  await assertFails(put('alice', `videos/bob/${vname('mp4')}`, bytes(), 'video/mp4'));
});
test('videos: sin autenticacion DENEGADA', async () => {
  await assertFails(put(null, `videos/alice/${vname('mp4')}`, bytes(), 'video/mp4'));
});
test('videos: >= 100 MB DENEGADA (buffer en memoria de 100 MB + 1 byte)', async () => {
  await assertFails(put('alice', `videos/alice/${vname('mp4')}`, bytes(100 * 1024 * 1024 + 1), 'video/mp4'));
});
test('thumbnails: image/jpeg OK, video/mp4 DENEGADA, sin contentType DENEGADA', async () => {
  await assertSucceeds(put('alice', `thumbnails/alice/${tname('jpg')}`, bytes(), 'image/jpeg'));
  await assertFails(put('alice', `thumbnails/alice/${tname('jpg')}`, bytes(), 'video/mp4'));
  await assertFails(put('alice', `thumbnails/alice/${tname('jpg')}`, bytes(), undefined));
});
test('thumbnails: carpeta ajena y sin auth DENEGADAS', async () => {
  await assertFails(put('alice', `thumbnails/bob/${tname('jpg')}`, bytes(), 'image/jpeg'));
  await assertFails(put(null, `thumbnails/alice/${tname('jpg')}`, bytes(), 'image/jpeg'));
});
test('read: autenticado OK, no autenticado DENEGADO (videos y thumbnails)', async () => {
  const v = `videos/alice/${vname('mp4')}`, t = `thumbnails/alice/${tname('jpg')}`;
  await seed(v, 'video/mp4'); await seed(t, 'image/jpeg');
  await assertSucceeds(getBytes(ref(stOf('bob'), v)));
  await assertSucceeds(getBytes(ref(stOf('bob'), t)));
  await assertFails(getBytes(ref(stOf(null), v)));
  await assertFails(getBytes(ref(stOf(null), t)));
});
test('delete: solo el dueño', async () => {
  const v = `videos/alice/${vname('mp4')}`, t = `thumbnails/alice/${tname('jpg')}`;
  await seed(v, 'video/mp4'); await seed(t, 'image/jpeg');
  await assertFails(deleteObject(ref(stOf('bob'), v)));
  await assertFails(deleteObject(ref(stOf(null), v)));
  await assertFails(deleteObject(ref(stOf('bob'), t)));
  await assertSucceeds(deleteObject(ref(stOf('alice'), v)));
  await assertSucceeds(deleteObject(ref(stOf('alice'), t)));
});

// ---- B2: nombres estrictos, solo create, sin sobrescritura, archivo ajeno ----
test('B2 videos: nombres arbitrarios / traversal / extension no permitida DENEGADOS', async () => {
  for (const n of ['evil.mp4', 'video_abc.mp4', 'video_1700000000000.exe', 'video_1700000000000.mp4.sh', 'video_123.mp4', 'video_17000000000000000000.mp4', 'video_1700000000000', '..%2Fx.mp4', 'a b.mp4']) {
    await assertFails(put('alice', `videos/alice/${n}`, bytes(), 'video/mp4'));
  }
  await assertSucceeds(put('alice', 'videos/alice/video_1700000000999.3gp', bytes(), 'video/3gpp'));
});
test('B2 thumbnails: nombres arbitrarios DENEGADOS, thumb_<ms>.png/webp OK', async () => {
  for (const n of ['x.jpg', 'thumb_x.jpg', 'thumb_1700000000000.gif', 'thumb_1.jpg']) {
    await assertFails(put('alice', `thumbnails/alice/${n}`, bytes(), 'image/jpeg'));
  }
  await assertSucceeds(put('alice', `thumbnails/alice/${tname('png')}`, bytes(), 'image/png'));
});
test('B2 videos: archivo vacio DENEGADO; ruta anidada DENEGADA', async () => {
  await assertFails(put('alice', `videos/alice/${vname('mp4')}`, bytes(0), 'video/mp4'));
  await assertFails(put('alice', `videos/alice/sub/${vname('mp4')}`, bytes(), 'video/mp4'));
});
test('B2 sobrescribir un blob existente (update) DENEGADO, incluso el propio', async () => {
  const v = `videos/alice/${vname('mp4')}`, t = `thumbnails/alice/${tname('jpg')}`;
  await seed(v, 'video/mp4'); await seed(t, 'image/jpeg');
  await assertFails(put('alice', v, bytes(32), 'video/mp4'));
  await assertFails(put('alice', t, bytes(32), 'image/jpeg'));
  await assertFails(put('bob', v, bytes(32), 'video/mp4'));
});
test('B2 archivo ajeno: bob no puede escribir ni borrar el de alice; alice si puede borrar el suyo', async () => {
  const v = `videos/alice/${vname('mp4')}`;
  await seed(v, 'video/mp4');
  await assertFails(put('bob', `videos/alice/${vname('mp4')}`, bytes(), 'video/mp4'));
  await assertFails(deleteObject(ref(stOf('bob'), v)));
  await assertFails(deleteObject(ref(stOf(null), v)));
  await assertSucceeds(deleteObject(ref(stOf('alice'), v)));
});
test('B2 rutas fuera de videos/ y thumbnails/ DENEGADAS', async () => {
  await assertFails(put('alice', `otros/alice/${vname('mp4')}`, bytes(), 'video/mp4'));
  await assertFails(put('alice', `${vname('mp4')}`, bytes(), 'video/mp4'));
});

test('B3 moderation_evidence: ningun cliente (ni moderador) lee, escribe ni borra', async () => {
  const p = 'moderation_evidence/alice_v1/video_1700000000000.mp4';
  await seed(p, 'video/mp4');
  const mod = env.authenticatedContext('mod1', { moderator: true }).storage();
  for (const st of [stOf('alice'), stOf('owner'), stOf(null), mod]) {
    await assertFails(getBytes(ref(st, p)));
    await assertFails(uploadBytes(ref(st, 'moderation_evidence/x_y/video_1700000000001.mp4'), bytes(), { contentType: 'video/mp4' }));
    await assertFails(deleteObject(ref(st, p)));
  }
});
