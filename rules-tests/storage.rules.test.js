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
const name = (ext) => `f${Date.now()}_${seq++}.${ext}`;
const put = (uid, p, data, contentType) =>
  uploadBytes(ref(stOf(uid), p), data, contentType === undefined ? undefined : { contentType });
const seed = (p, contentType) => env.withSecurityRulesDisabled(async (ctx) => {
  await uploadBytes(ref(ctx.storage(), p), bytes(), { contentType });
});

test('videos: propio uid con contentType video/mp4 OK', async () => {
  await assertSucceeds(put('alice', `videos/alice/${name('mp4')}`, bytes(), 'video/mp4'));
});
test('videos: otros video/* (webm, quicktime) OK', async () => {
  await assertSucceeds(put('alice', `videos/alice/${name('webm')}`, bytes(), 'video/webm'));
  await assertSucceeds(put('alice', `videos/alice/${name('mov')}`, bytes(), 'video/quicktime'));
});
test('videos: SIN contentType (reproduce el bug: putFile sin metadata) DENEGADA', async () => {
  await assertFails(put('alice', `videos/alice/${name('mp4')}`, bytes(), undefined));
});
test('videos: application/octet-stream DENEGADA', async () => {
  await assertFails(put('alice', `videos/alice/${name('mp4')}`, bytes(), 'application/octet-stream'));
});
test('videos: contentType image/jpeg DENEGADA', async () => {
  await assertFails(put('alice', `videos/alice/${name('mp4')}`, bytes(), 'image/jpeg'));
});
test('videos: subir en carpeta de otro uid DENEGADA', async () => {
  await assertFails(put('alice', `videos/bob/${name('mp4')}`, bytes(), 'video/mp4'));
});
test('videos: sin autenticacion DENEGADA', async () => {
  await assertFails(put(null, `videos/alice/${name('mp4')}`, bytes(), 'video/mp4'));
});
test('videos: >= 100 MB DENEGADA (buffer en memoria de 100 MB + 1 byte)', async () => {
  await assertFails(put('alice', `videos/alice/${name('mp4')}`, bytes(100 * 1024 * 1024 + 1), 'video/mp4'));
});
test('thumbnails: image/jpeg OK, video/mp4 DENEGADA, sin contentType DENEGADA', async () => {
  await assertSucceeds(put('alice', `thumbnails/alice/${name('jpg')}`, bytes(), 'image/jpeg'));
  await assertFails(put('alice', `thumbnails/alice/${name('jpg')}`, bytes(), 'video/mp4'));
  await assertFails(put('alice', `thumbnails/alice/${name('jpg')}`, bytes(), undefined));
});
test('thumbnails: carpeta ajena y sin auth DENEGADAS', async () => {
  await assertFails(put('alice', `thumbnails/bob/${name('jpg')}`, bytes(), 'image/jpeg'));
  await assertFails(put(null, `thumbnails/alice/${name('jpg')}`, bytes(), 'image/jpeg'));
});
test('read: autenticado OK, no autenticado DENEGADO (videos y thumbnails)', async () => {
  const v = `videos/alice/${name('mp4')}`, t = `thumbnails/alice/${name('jpg')}`;
  await seed(v, 'video/mp4'); await seed(t, 'image/jpeg');
  await assertSucceeds(getBytes(ref(stOf('bob'), v)));
  await assertSucceeds(getBytes(ref(stOf('bob'), t)));
  await assertFails(getBytes(ref(stOf(null), v)));
  await assertFails(getBytes(ref(stOf(null), t)));
});
test('delete: solo el dueño', async () => {
  const v = `videos/alice/${name('mp4')}`, t = `thumbnails/alice/${name('jpg')}`;
  await seed(v, 'video/mp4'); await seed(t, 'image/jpeg');
  await assertFails(deleteObject(ref(stOf('bob'), v)));
  await assertFails(deleteObject(ref(stOf(null), v)));
  await assertFails(deleteObject(ref(stOf('bob'), t)));
  await assertSucceeds(deleteObject(ref(stOf('alice'), v)));
  await assertSucceeds(deleteObject(ref(stOf('alice'), t)));
});
