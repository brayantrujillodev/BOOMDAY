// Borra TODOS los datos de los emuladores locales (Auth, Firestore, Storage). Solo Node 18+.
// Seguridad: solo habla con 127.0.0.1 y proyecto demo-boomday.
const PROJECT = 'demo-boomday';
const BUCKET = 'demo-boomday.appspot.com';
const H = '127.0.0.1';
const owner = { Authorization: 'Bearer owner' };

async function del(url, label) {
  const r = await fetch(url, { method: 'DELETE', headers: owner });
  console.log(`${label}: HTTP ${r.status}`);
  if (!r.ok) process.exitCode = 1;
}

await del(`http://${H}:9099/emulator/v1/projects/${PROJECT}/accounts`, 'auth clear');
await del(`http://${H}:8080/emulator/v1/projects/${PROJECT}/databases/(default)/documents`, 'firestore clear');

// Storage no tiene endpoint de clear: listar y borrar objeto por objeto.
let token = '';
let n = 0;
do {
  const r = await fetch(`http://${H}:9199/v0/b/${BUCKET}/o?maxResults=1000${token ? '&pageToken=' + token : ''}`, { headers: owner });
  if (!r.ok) { console.log(`storage list: HTTP ${r.status}`); process.exitCode = 1; break; }
  const j = await r.json();
  for (const it of j.items ?? []) {
    const d = await fetch(`http://${H}:9199/v0/b/${BUCKET}/o/${encodeURIComponent(it.name)}`, { method: 'DELETE', headers: owner });
    if (d.ok) n++; else { console.log(`storage delete ${it.name}: HTTP ${d.status}`); process.exitCode = 1; }
  }
  token = j.nextPageToken ?? '';
} while (token);
console.log(`storage objects deleted: ${n}`);
