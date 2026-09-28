# Roadmap

Orden de prioridad real, con el motivo de cada punto — no es una lista de deseos, es lo que falta para que el producto sea publicable y sostenible.

## 0. Migración de proyecto de Firebase — en curso (2026-09-28)

**Hallazgo:** el `google-services.json` de desarrollo apunta a `boomday-85bef`, pero esa cuenta/proyecto no aparece en la cuenta de Firebase real del equipo (`sadtiger69@gmail.com`) — ni en `firebase projects:list` ni en la consola web. El proyecto real bajo esa cuenta es **`boomday-e2308`**, que existía pero nunca se terminó de configurar: tenía una app Android registrada con el paquete viejo (`com.example.boomday`, de antes del rename — ver punto 3) y **ni Firestore ni Storage ni Google Sign-In estaban activados**.

**Hecho hasta ahora en `boomday-e2308`:**
- App Android registrada con el paquete real (`com.negociodigital.boomday`), App ID `1:345021293955:android:cbc28ac7e965205838c2b6`, con el SHA-1 del keystore de debug agregado.
- Base de Firestore creada en modo Native, región `nam5`.
- Reglas de Firestore (`firestore.rules`) desplegadas.

**Pendiente, bloqueado por acciones que solo se pueden hacer desde la consola (no vía CLI):**
1. **Actualizar a plan Blaze** — https://console.firebase.google.com/project/boomday-e2308/usage/details. Storage (desde oct. 2024, Google exige Blaze para crear un bucket nuevo) y las Cloud Functions programadas (Cloud Scheduler + Pub/Sub, no disponibles en Spark) dependen de esto. Ver el punto 1 de abajo para el porqué específico de Functions.
2. **Activar Storage** — Firebase Console → Storage → "Comenzar" (solo posible después de Blaze). Región sugerida: `nam5`, igual que Firestore.
3. **Habilitar Google como proveedor de Sign-In** — Authentication → Sign-in method → Google → Habilitar. Esto genera el cliente OAuth (`client_type: 3`) que la app necesita para `default_web_client_id`; sin esto Google Sign-In no compila ni funciona con el `google-services.json` de este proyecto.

**Una vez hecho lo anterior:**
- Volver a descargar `google-services.json` (`firebase apps:sdkconfig ANDROID 1:345021293955:android:cbc28ac7e965205838c2b6 --project boomday-e2308`) — el actual en disco (apuntando a `boomday-85bef`) sigue siendo el que usa el build local hasta que se reemplace, y el descargado hasta ahora desde `boomday-e2308` no tiene el cliente OAuth (porque Google Sign-In no estaba habilitado cuando se descargó).
- Desplegar reglas de Storage (`firebase deploy --only storage`).
- Desplegar Cloud Functions (punto 1).
- Recompilar y probar login + upload end-to-end contra el proyecto nuevo antes de dar la migración por completa — no se ha verificado todavía que la app funcione contra `boomday-e2308` real.

## 1. ~~Cloud Function de expiración de 24h~~ — implementada, falta desplegar

Hoy `getTodayVideos()`/`getTopVideos()` **filtran en lectura** (por `createdAt`/`dayKey`), pero los blobs en Storage y los documentos en Firestore de videos vencidos **nunca se borran**. El video deja de aparecer en la app, pero se sigue pagando almacenamiento por él indefinidamente. Sin esto, el costo de Storage crece sin límite mientras la app tenga uso, sin ningún tope.

**Implementado**: `functions/src/index.ts` — `cleanupExpiredVideos`, una Cloud Function programada (`onSchedule`, cada 60 minutos, zona horaria `America/Bogota`) que busca videos con `createdAt` de más de 24h, borra el blob de video y el thumbnail en Storage (parseando el path desde la download URL guardada), y borra el documento en Firestore junto con su subcolección `views` (`recursiveDelete`).

**Pendiente, acción manual** — bloqueado por el plan Blaze pendiente en `boomday-e2308`, ver punto 0 arriba:
```bash
npm install -g firebase-tools   # si no lo tienes
firebase login
cd functions && npm install && cd ..
firebase deploy --only functions --project boomday-e2308
```

Queda pendiente, por separado, la **validación server-side de duración y content-type real** (hoy solo se valida `content-type` declarado por el cliente y tamaño en `storage.rules` — ninguna regla puede inspeccionar el contenido real del archivo). No se implementó en esta pasada: requiere `ffprobe` o similar corriendo en una función activada por subida (`onObjectFinalized`), es una pieza separada de la de expiración programada.

## 2. ~~Reporte y bloqueo de usuarios~~ — implementado, reglas desplegadas

La app pasó de "solo lectura de perfil" a generar contenido público real (UGC) visible por otros usuarios. La política de Contenido Generado por el Usuario de Google Play exige, antes de publicar una feature con UGC visible: mecanismo in-app para reportar contenido/usuarios, capacidad de bloquear usuarios, y compromiso de remover contenido reportado en un plazo razonable. No es opcional para pasar la revisión de Play — es un bloqueante de publicación, no solo una buena práctica.

**Implementado**: colección `reports/{reportId}` en Firestore (creable solo por el reportante, solo lectura/gestión administrativa), campo `blockedUsers: List<String>` en `User`, `UserRepository.blockUser/unblockUser/getBlockedUsers`, `ReportRepository.submitReport`, filtro reactivo de usuarios bloqueados en `FeedViewModel` (inmediato) y `RankingViewModel` (en la carga), menú de "más opciones" con reportar/bloquear en `FeedScreen`.

**Resuelto (2026-09-28)**: las reglas de `firestore.rules` (incluida la colección `reports`) ya están desplegadas en `boomday-e2308`.

## 3. Nombre del paquete — corrección respecto a la nota original

**Aclaración importante:** el paquete actual del proyecto es `com.negociodigital.boomday` (verificado en `app/build.gradle.kts`), no `com.example.boomday`. Google Play rechaza específicamente paquetes que empiecen con el prefijo literal `com.example` (la plantilla por defecto de Android Studio) — `com.negociodigital.boomday` **no cae en esa categoría** y no es, técnicamente, un bloqueante de publicación.

Dicho eso, sigue siendo válido reconsiderar el nombre por coherencia de marca: el paquete liga la identidad de la app a "negociodigital" en vez de "BoomDay". Si se decide cambiar (por ejemplo a algo como `com.boomday.app`), **tiene que hacerse antes de la primera publicación en Play Console** — el `applicationId` no se puede modificar después sin perder el historial de reviews, instalaciones y estadísticas (técnicamente equivale a publicar una app nueva).

**Actualización (2026-09-28)**: al migrar de proyecto de Firebase (ver punto 0), se confirmó que `boomday-e2308` solo tenía registrada una app con el paquete viejo `com.example.boomday` — evidencia de que ese proyecto quedó a medio configurar desde antes del rename. Ya se registró ahí la app con el paquete correcto `com.negociodigital.boomday`.

## 4. Explore screen

Hoy es 100% mock: trending, categorías y usuarios sugeridos son datos hardcodeados, sin `ViewModel`, sin conexión a Firestore. Queda pendiente definir qué significa "trending"/"sugeridos" en un producto tan chico (¿los mismos videos del Ranking con otro layout? ¿un algoritmo real?) antes de invertir en implementarlo.

## 5. Requisitos de Google Play Store

Corren en paralelo al código, no después:

- **Closed testing**: mínimo 12 testers activos durante 14 días consecutivos antes de poder solicitar producción — es el camino crítico de tiempo, conviene arrancarlo temprano aunque el producto no esté 100% terminado. Pendiente.
- **Política de privacidad** (URL pública, requisito de Play Console). Pendiente.
- **Formulario de seguridad de datos** (Data Safety) — declarar qué datos recolecta la app (email, videos, ubicación si se llega a usar para el ranking local) y con qué propósito. Pendiente.
- ~~**Eliminación de cuenta**~~ — **implementado (2026-09-28)**: `DeleteAccountUseCase` borra los videos del usuario (Firestore + Storage), el documento de perfil y la cuenta de Firebase Auth, con reautenticación automática vía Google si Firebase la exige por sesión no reciente. UI en `ProfileScreen` (botón "Eliminar cuenta" con confirmación). Ver `domain/usecase/DeleteAccountUseCase.kt`.

## 6. Deuda técnica conocida

Ya identificada en revisiones de código anteriores. Lo ya resuelto se marca explícitamente para no reabrirlo sin motivo.

**Pendiente:**
- Validación server-side real de content-type y duración de video (depende de la Cloud Function del punto 1).

**Ya resuelto (no reabrir sin evidencia nueva):**
- ~~Query inválida de `getTopVideos()`~~ — corregido con `dayKey` (ver `docs/ARCHITECTURE.md`).
- ~~Feed reiniciaba el video activo en cada escritura de Firestore~~ — corregido, `LaunchedEffect` ahora usa como key el `videoId` de la página activa, no la lista completa.
- ~~Incremento de `views` no atómico~~ — corregido con transacción de Firestore + regla `existsAfter()`.
- ~~`videoUrl`/`thumbnailUrl` sin validar dominio en las reglas~~ — corregido.
- ~~`notifiedViewIds` con riesgo de fragilidad de concurrencia~~ — corregido, `Set` sincronizado explícitamente.
- ~~`FeedItem.kt` con código muerto~~ — eliminado, `formatCount` movido a `ui/util/NumberFormatUtils.kt`.
- ~~Permisos de cámara sin re-chequeo si se revocan en segundo plano~~ — corregido, `CameraGateScreen` re-chequea permisos en `ON_RESUME` con un `LifecycleEventObserver`.
- ~~`StorageRepository` logueaba cancelación de subida como error genérico~~ — corregido, se distingue `StorageException.ERROR_CANCELED` y se loguea como `Timber.d`, no `Timber.e`.
- ~~Archivo de video grabado no se borraba del cache tras subir exitosamente~~ — corregido, `UploadViewModel` borra el archivo local (scheme `file`) al recibir `UploadStatus.Success`; los videos importados de galería (`content://`) no se tocan.
- ~~Faltaba `launchSingleTop` en la navegación al tab "Crear"~~ — corregido en `NavGraph.kt`.
- ~~`UserRepository.createOrUpdateUser` escribía `displayName` pero el modelo `User` define `name`~~ — confirmado como bug real (el nombre se perdía al releer el usuario desde Firestore) y corregido.
