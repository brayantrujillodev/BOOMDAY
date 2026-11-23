# Arranca Auth(9099)/Firestore(8080)/Storage(9199) SOLO locales, proyecto ficticio demo-boomday.
# No requiere login ni toca Firebase real. Ctrl+C para apagar.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:FIREBASE_CLI_DISABLE_UPDATE_CHECK = 'true'
$fb = Join-Path $root 'rules-tests\node_modules\.bin\firebase.cmd'
# firebase-tools exige que las reglas esten DENTRO del directorio del proyecto (el de
# firebase.json), asi que se copian las reglas reales del repo en cada arranque (nunca derivan).
$gen = Join-Path $PSScriptRoot '.generated'
New-Item -ItemType Directory -Force $gen | Out-Null
Copy-Item (Join-Path $root 'firestore.rules'), (Join-Path $root 'storage.rules'), (Join-Path $root 'firestore.indexes.json') $gen -Force
node (Join-Path $PSScriptRoot 'patch-rules.mjs')
Set-Location $PSScriptRoot
& $fb emulators:start --project demo-boomday --config firebase.json
