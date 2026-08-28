# BoomDay

App de video corto **efímero**: cada video vive 24 horas y desaparece. Ranking diario que resetea cada día, sin acumular estatus histórico. Lanzamiento inicial enfocado en **Neiva, Colombia** — el ranking es local a la ciudad, no global.

**Estado: MVP en desarrollo.** El ciclo completo (registro → grabar → subir → feed → ranking) funciona de punta a punta. Todavía no está listo para publicarse en Google Play — ver [ROADMAP](docs/ROADMAP.md).

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
├── domain/usecase/      # GoogleSignInUseCase
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
| Subir video (grabar con CameraX máx. 60s, importar de galería, revisar, subir con progreso/cancelación) | ✅ |
| Feed (VerticalPager, un solo ExoPlayer reutilizado, conteo de vistas) | ✅ |
| Ranking diario (Top por vistas, diseñado para screenshot) | ✅ |
| Explore (buscar, categorías, sugeridos) | ❌ mock, sin datos reales |
| Expiración real de 24h (borrado de Storage/Firestore) | ❌ solo se filtra en lectura, los archivos nunca se borran |
| Reporte y bloqueo de usuarios | ❌ no existe |
| Validación server-side de duración/content-type de video | 🚧 parcial (reglas de Storage validan tamaño/tipo declarado; falta Cloud Function) |
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

Este archivo **no está versionado** (contiene el `project_id`/`api_key` del proyecto Firebase real). Para compilar localmente:

1. Entrá a la [consola de Firebase](https://console.firebase.google.com/) del proyecto (o creá uno nuevo para desarrollo).
2. Agregá una app Android con `applicationId = com.negociodigital.boomday`.
3. Descargá `google-services.json` y colocalo en `app/google-services.json`.

### Reglas de Firestore/Storage

```bash
npm install -g firebase-tools
firebase login
firebase use <tu-project-id>
firebase deploy --only firestore:rules,firestore:indexes,storage
```

El índice compuesto de Firestore tarda varios minutos en construirse — esperá a que aparezca "Enabled" en Firebase Console → Firestore → Índices antes de probar el Ranking.

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
