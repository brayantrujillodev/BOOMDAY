# BoomDay

App de video corto **efímero**: cada video vive 24 horas y desaparece. Ranking diario que resetea cada día, sin acumular estatus histórico. Lanzamiento inicial enfocado en **Neiva, Colombia** — el ranking es local a la ciudad, no global.

**Estado: MVP en desarrollo.** El ciclo completo (registro → grabar → subir → feed → ranking) funciona de punta a punta. Todavía no está listo para publicarse en Google Play — ver [ROADMAP](docs/ROADMAP.md).

**Nota de infraestructura (2026-09-28):** el proyecto de Firebase de producción es **`boomday-e2308`**, no `boomday-85bef` (el que trae el `google-services.json` de desarrollo histórico). La migración a `boomday-e2308` está en curso — ver el punto "Migración de proyecto de Firebase" en el [ROADMAP](docs/ROADMAP.md) para el estado exacto de qué falta (plan Blaze, Storage, proveedor de Google Sign-In).

## Qué es BoomDay

La idea central es la escasez: si tu video solo dura un día, cada publicación importa. El "top de hoy" en Neiva es una meta alcanzable (a diferencia de un ranking global), y esa pantalla de ranking está pensada como motor viral — se ve bien en captura de pantalla y se comparte.

## Stack técnico

| Componente | Versión |
|---|---|
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.10.1 |
| Jetpack Compose BOM | 2024.09.00 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Hilt | 2.52 |
| Firebase BOM | 33.7.0 (Auth, Firestore, Storage) |
| Media3 / ExoPlayer | 1.5.0 |
| CameraX | 1.4.1 |
| Coil | 2.7.0 |
| Navigation Compose | 2.9.6 |
| Coroutines | 1.9.0 |

Autenticación: Google Sign-In vía Firebase Auth.

## Screenshots

_Pendientes de capturar — ver `docs/screenshots/`._

| Login | Feed | Ranking |
|---|---|---|
| ![Login](docs/screenshots/login.png) | ![Feed](docs/screenshots/feed.png) | ![Ranking](docs/screenshots/ranking.png) |

## Arquitectura

MVVM estricto: los composables nunca llaman a Firebase directo, siempre a través de un `ViewModel` (`@HiltViewModel`) que inyecta repositorios (`@Singleton`) con Hilt. El estado se expone como `StateFlow` de sealed interfaces (`FeedState`, `RankingState`, `UploadState`). Todo el I/O corre en `Dispatchers.IO`; las corrutinas viven en `viewModelScope`.

Detalle completo de decisiones técnicas (y el porqué de cada una) en [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```
app/src/main/java/com/negociodigital/boomday/
├── data/
│   ├── auth/           # Configuración de Google Sign-In
│   ├── model/           # Video, User, UploadStatus — modelos de Firestore
│   ├── repository/      # AuthRepository, VideoRepository, StorageRepository,
│   │                     # UploadRepository, UserRepository, ProfileRepository...
│   └── util/            # DateUtils (dayKey en zona horaria de Neiva)
├── di/                  # AppModule (bindings de Hilt)
├── domain/usecase/      # GoogleSignInUseCase, DeleteAccountUseCase
└── ui/
    ├── splash/, login/, main/, navigation/, theme/
    ├── feed/            # FeedScreen (VerticalPager + ExoPlayer), FeedViewModel
    ├── ranking/         # RankingScreen, RankingViewModel
    ├── upload/          # Grabar (CameraX) / importar / revisar / subir
    ├── explore/         # Placeholder, ver estado de features
    ├── profile/
    └── util/            # NumberFormatUtils (formato de vistas: 12.4K)
```

## Estado de features

| Feature | Estado |
|---|---|
| Autenticación (Google Sign-In) | ✅ |
| Splash / verificación de sesión | ✅ |
| Navegación (NavBar de 5 pestañas) | ✅ |
| Perfil y logout | ✅ |
| Eliminación de cuenta (requisito de Play para apps con creación de cuenta) | ✅ borra videos + perfil + cuenta de Auth, con reautenticación automática si Firebase la exige |
| Subir video (grabar con CameraX máx. 60s, importar de galería, revisar, subir con progreso/cancelación) | ✅ |
| Feed (VerticalPager, un solo ExoPlayer reutilizado, conteo de vistas) | ✅ |
| Ranking diario (Top por vistas, diseñado para screenshot) | ✅ |
| Explore (buscar, categorías, sugeridos) | ❌ mock, sin datos reales |
| Expiración real de 24h (borrado de Storage/Firestore) | 🚧 Cloud Function implementada (`functions/`), deploy bloqueado por plan Blaze pendiente en `boomday-e2308` (ver ROADMAP) |
| Reporte y bloqueo de usuarios | 🚧 implementado en código; reglas de Firestore ya desplegadas en `boomday-e2308` |
| Validación server-side de duración/content-type de video | ❌ pendiente (requiere una función `onObjectFinalized` separada de la de expiración) |
| Reglas de Firestore/Storage | ✅ escritas y con validación de ownership, límites y dedupe atómico |
| CI (build automático) | ✅ este mismo cambio |
| Tests automatizados | ❌ no hay |

Detalle priorizado de lo pendiente en [docs/ROADMAP.md](docs/ROADMAP.md).

## Setup para desarrolladores

### Requisitos

- JDK 17 (Android Gradle Plugin 8.10 lo exige para correr Gradle; el bytecode de la app compila a nivel Java 11, eso no cambia).
- Android SDK con la plataforma 36 instalada (`compileSdk`/`targetSdk = 36`).
- Una cuenta de Firebase con acceso al proyecto (o uno propio para desarrollo local).

### `google-services.json`

Este archivo **no está versionado** (contiene el `project_id`/`api_key` del proyecto Firebase real). Para compilar localmente contra el proyecto de producción (`boomday-e2308`):

1. Entrá a la [consola de Firebase](https://console.firebase.google.com/project/boomday-e2308) del proyecto `boomday-e2308` (o creá uno propio para desarrollo).
2. Ya existe registrada una app Android con `applicationId = com.negociodigital.boomday` (App ID `1:345021293955:android:cbc28ac7e965205838c2b6`). Si es tu primera vez, agregá el SHA-1 de tu keystore de debug en Configuración del proyecto → tu app → "Huellas digitales del certificado SHA" — sin esto Google Sign-In falla en tu build local aunque compile.
3. Descargá `google-services.json` (Configuración del proyecto → tu app → "Descargar google-services.json") y colocalo en `app/google-services.json`.

### Reglas de Firestore/Storage

```bash
npm install -g firebase-tools
firebase login
firebase use boomday-e2308   # o <tu-project-id> propio
firebase deploy --only firestore:rules,firestore:indexes,storage
```

Estado en `boomday-e2308`: las reglas de Firestore ya están desplegadas. El deploy de Storage está bloqueado porque el proyecto todavía no tiene Storage activado (requiere plan Blaze, ver abajo) — hay que entrar una vez a Firebase Console → Storage → "Comenzar" después de subir a Blaze, recién ahí el comando de arriba funciona.

El índice compuesto de Firestore tarda varios minutos en construirse — esperá a que aparezca "Enabled" en Firebase Console → Firestore → Índices antes de probar el Ranking.

### Cloud Functions (expiración de 24h)

`functions/` contiene `cleanupExpiredVideos`, la función programada que borra videos vencidos. Necesita el plan **Blaze** habilitado en el proyecto (las funciones programadas usan Cloud Scheduler + Pub/Sub, no disponibles en el plan gratuito Spark). `boomday-e2308` sigue en Spark — el deploy falla hasta que se actualice el plan desde https://console.firebase.google.com/project/boomday-e2308/usage/details:

```bash
cd functions
npm install
cd ..
firebase deploy --only functions
```

### Desarrollar sin gastar cuota

El plan gratuito (Spark) de Firebase cubre Firestore y Auth sin costo. **Storage requiere el plan Blaze** (subir video sí tiene costo). Para seguir desarrollando Feed/Ranking/Auth sin necesidad de plan de pago, usá el emulador local:

```bash
firebase emulators:start
```

### Compilar

```bash
./gradlew assembleDebug
```

## Roadmap

Ver [docs/ROADMAP.md](docs/ROADMAP.md) para la lista priorizada, con el motivo de cada punto.

## Contribuir

Ver [CONTRIBUTING.md](CONTRIBUTING.md).

## Licencia

MIT — ver [LICENSE](LICENSE).
