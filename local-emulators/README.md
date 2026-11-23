# Entorno local aislado (Firebase Emulator Suite)

Proyecto ficticio `demo-boomday`. Nada de esto toca Firebase real.

## Uso

1. `local-emulators/start.ps1` (o `start.sh`): arranca Auth 9099, Firestore 8080, Storage 9199, UI 4000. Usa el CLI de `rules-tests/node_modules` y el JDK de Android Studio. Requiere en la caché `~/.cache/firebase/emulators` los jars de Firestore y de las reglas de Storage (`cloud-storage-rules-runtime-*.jar`) y el zip del UI emulator; en una máquina limpia la primera ejecución descarga ~53 MB+ (necesita red y aprobación). `seed.mjs` y `reset.mjs` requieren los emuladores en marcha. `start.ps1`/`start.sh` copian las reglas del repo a `.generated/` y las parchean solo allí (`patch-rules.mjs`: acepta la URL `http://10.0.2.2:9199` y el bucket `demo-boomday`); las reglas reales no se modifican.
2. `node local-emulators/seed.mjs`: crea alice/bob/carol `@boomday.test` (password `Test1234!`), `users/*` (alice y carol con `createdAt` Timestamp, bob con numero) y 6 videos de bob/carol con `dayKey` de hoy (America/Bogota).
3. `gradlew :app:installLocal` (variante `local`, applicationId `com.negociodigital.boomday.local`) en un AVD (el host `10.0.2.2` solo existe en el AVD, no en dispositivos fisicos).
4. Login de pruebas:
   `adb shell am start -n com.negociodigital.boomday.local/com.negociodigital.boomday.debuglocal.LocalTestSignInActivity --es email alice@boomday.test --es password Test1234!`
5. `local-emulators/reset.ps1` (o `reset.sh`): borra Auth, Firestore y Storage. Detener emuladores con Ctrl+C (los datos no se persisten).

## Aislamiento

- Build type `local` con `app/src/local/google-services.json` ficticio. `debug`/`release` siguen con `app/google-services.json` y un `LocalEnvironment` NO-OP.
- `LocalEnvironment` real falla (IllegalStateException) si el projectId != `demo-boomday`.
- Cleartext permitido solo a `10.0.2.2`/`localhost` (solo build `local`).
- Todos los emuladores escuchan solo en `127.0.0.1`: el AVD estandar alcanza el loopback del host como `10.0.2.2`, asi que no se expone nada a la red. Si un AVD/dispositivo no lo alcanza, cambiar `host` a `0.0.0.0` (expone puertos a la LAN).
- Seed: el MP4 subido es estructuralmente valido pero NO reproducible (sin moov); las URLs apuntan al Storage emulator via `10.0.2.2:9199`.
