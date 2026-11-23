// Siembra datos 100% ficticios en los emuladores locales. Solo Node 18+, sin dependencias.
// Escribe con "Authorization: Bearer owner" (bypass de reglas del emulador). Reset lo borra todo.
// Uso: node local-emulators/seed.mjs   (emuladores corriendo)
const PROJECT = 'demo-boomday';
const BUCKET = 'demo-boomday.appspot.com';
const H = '127.0.0.1';              // host desde la PC
const AVD_HOST = '10.0.2.2';        // host que vera el emulador Android (URLs guardadas)
const PASSWORD = 'Test1234!';
const owner = { Authorization: 'Bearer owner' };
const FS = `http://${H}:8080/v1/projects/${PROJECT}/databases/(default)/documents`;

// --- Auth ---
async function ensureUser(email, displayName) {
  const base = `http://${H}:9099/identitytoolkit.googleapis.com/v1/accounts`;
  const body = JSON.stringify({ email, password: PASSWORD, displayName, returnSecureToken: true });
  const h = { 'Content-Type': 'application/json' };
  let r = await fetch(`${base}:signUp?key=fake`, { method: 'POST', headers: h, body });
  if (!r.ok) r = await fetch(`${base}:signInWithPassword?key=fake`, { method: 'POST', headers: h, body });
  if (!r.ok) throw new Error(`auth ${email}: ${r.status} ${await r.text()}`);
  return (await r.json()).localId;
}

// --- Firestore ---
function val(v) {
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  if (typeof v === 'string') return { stringValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return { integerValue: String(v) };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(val) } };
  if (v === null) return { nullValue: null };
  throw new Error('tipo no soportado');
}
async function put(path, data) {
  const fields = Object.fromEntries(Object.entries(data).map(([k, v]) => [k, val(v)]));
  const r = await fetch(`${FS}/${path}`, { method: 'PATCH', headers: { ...owner, 'Content-Type': 'application/json' }, body: JSON.stringify({ fields }) });
  if (!r.ok) throw new Error(`firestore ${path}: ${r.status} ${await r.text()}`);
}

// --- Storage ---
// MP4 minimo ESTRUCTURALMENTE valido (ftyp + free + mdat vacio). NO es reproducible
// (sin moov/pistas): sirve para probar URLs/descarga, no para decodificar en ExoPlayer.
function box(type, payload = Buffer.alloc(0)) {
  const b = Buffer.alloc(8 + payload.length);
  b.writeUInt32BE(b.length, 0); b.write(type, 4, 'ascii'); payload.copy(b, 8);
  return b;
}
const MP4 = Buffer.concat([
  box('ftyp', Buffer.concat([Buffer.from('isom'), Buffer.from([0, 0, 2, 0]), Buffer.from('isomiso2mp41')])),
  box('free'), box('mdat'),
]);
// PNG 1x1 valido
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

async function upload(name, bytes, contentType) {
  const r = await fetch(`http://${H}:9199/v0/b/${BUCKET}/o?name=${encodeURIComponent(name)}`, {
    method: 'POST', headers: { ...owner, 'Content-Type': contentType }, body: bytes,
  });
  if (!r.ok) throw new Error(`storage ${name}: ${r.status} ${await r.text()}`);
  const meta = await r.json();
  const token = (meta.downloadTokens ?? '').split(',')[0];
  return `http://${AVD_HOST}:9199/v0/b/${BUCKET}/o/${encodeURIComponent(name)}?alt=media${token ? '&token=' + token : ''}`;
}

// dayKey de hoy en America/Bogota (yyyy-MM-dd)
const dayKey = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());

const users = [
  { email: 'alice@boomday.test', name: 'Alice', createdAtKind: 'timestamp' }, // contrato real
  { email: 'bob@boomday.test', name: 'Bob', createdAtKind: 'number' },       // historico (Long)
  { email: 'carol@boomday.test', name: 'Carol', createdAtKind: 'timestamp' },
];
const uid = {};
for (const u of users) {
  uid[u.name] = await ensureUser(u.email, u.name);
  const now = new Date();
  await put(`users/${uid[u.name]}`, {
    uid: uid[u.name], email: u.email, name: u.name, avatar: null, type: 'free',
    createdAt: u.createdAtKind === 'timestamp' ? now : now.getTime(),
    updatedAt: now,
  });
  console.log(`user ${u.email} uid=${uid[u.name]} createdAt=${u.createdAtKind}`);
}

const plan = [
  ['Bob', 'bob1', 120, ['#boom', '#bob']], ['Bob', 'bob2', 45, ['#dia']], ['Bob', 'bob3', 7, []],
  ['Carol', 'carol1', 300, ['#carol', '#top']], ['Carol', 'carol2', 15, ['#hola']], ['Carol', 'carol3', 0, []],
];
const thumb = {};
let i = 0;
for (const [who, key, views, hashtags] of plan) {
  const id = `seed-${key}`;
  const videoUrl = await upload(`videos/${uid[who]}/${id}.mp4`, MP4, 'video/mp4');
  const thumbnailUrl = await upload(`thumbnails/${uid[who]}/${id}.png`, PNG, 'image/png');
  const nowMs = Date.now() - i++ * 60_000;
  await put(`videos/${id}`, {
    videoId: id, userId: uid[who], userName: who, userAvatar: '',
    videoUrl, thumbnailUrl, title: `Video ${key}`, description: `Seed ficticio de ${who}`,
    duration: 10, views, likes: 0, comments: 0, shares: 0, hashtags,
    createdAt: nowMs, updatedAt: new Date(nowMs), dayKey,
  });
  console.log(`video ${id} views=${views} dayKey=${dayKey}`);
}
console.log('SEED OK. Password de los usuarios: ' + PASSWORD);
