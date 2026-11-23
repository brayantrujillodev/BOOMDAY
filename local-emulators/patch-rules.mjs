// SOLO ENTORNO LOCAL: adapta la COPIA generada de firestore.rules para que acepte las URLs de
// descarga del Storage emulator (http://10.0.2.2:9199/v0/b/demo-boomday.appspot.com/o/...).
// Las reglas reales del repo NO se modifican; en produccion solo vale firebasestorage.googleapis.com.
import { readFileSync, writeFileSync } from 'node:fs';
const f = new URL('./.generated/firestore.rules', import.meta.url);
const s = readFileSync(f, 'utf8');
const B = String.fromCharCode(92); // backslash
const dot = B + B + '.'; // en la regla el punto escapado se escribe "\." dentro del string
const from = "'^https://firebasestorage" + dot + "googleapis" + dot + "com/v0/b/(boomday-85bef|boomday-e2308)" + dot + "(firebasestorage" + dot + "app|appspot" + dot + "com)/o/'";
const to = "'^(https://firebasestorage" + dot + "googleapis" + dot + "com|http://10" + dot + "0" + dot + "2" + dot + "2:9199)/v0/b/(boomday-85bef|boomday-e2308|demo-boomday)" + dot + "(firebasestorage" + dot + "app|appspot" + dot + "com)/o/'";
if (!s.includes(from)) { console.error('patch-rules: patron no encontrado, reglas NO adaptadas'); process.exit(1); }
writeFileSync(f, s.replace(from, () => to));
console.log('patch-rules: isValidStorageUrl acepta el Storage emulator (solo copia local)');
