#!/usr/bin/env bash
# Arranca Auth(9099)/Firestore(8080)/Storage(9199) SOLO locales, proyecto ficticio demo-boomday.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
export JAVA_HOME="${JAVA_HOME:-C:\Program Files\Android\Android Studio\jbr}"
export PATH="$JAVA_HOME/bin:$PATH"
export FIREBASE_CLI_DISABLE_UPDATE_CHECK=true
# firebase-tools exige reglas DENTRO del directorio del proyecto: copiar las reales en cada arranque.
mkdir -p "$HERE/.generated"
cp -f "$ROOT/firestore.rules" "$ROOT/storage.rules" "$ROOT/firestore.indexes.json" "$HERE/.generated/"
cd "$HERE"
node patch-rules.mjs
exec "$ROOT/rules-tests/node_modules/.bin/firebase" emulators:start --project demo-boomday --config firebase.json
