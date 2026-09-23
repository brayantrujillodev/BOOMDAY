# Roadmap

Orden de prioridad real, con el motivo de cada punto — no es una lista de deseos, es lo que falta para que el producto sea publicable y sostenible.

## 1. ~~Cloud Function de expiración de 24h~~ — implementada, falta desplegar

Hoy `getTodayVideos()`/`getTopVideos()` **filtran en lectura** (por `createdAt`/`dayKey`), pero los blobs en Storage y los documentos en Firestore de videos vencidos **nunca se borran**. El video deja de aparecer en la app, pero se sigue pagando almacenamiento por él indefinidamente. Sin esto, el costo de Storage crece sin límite mientras la app tenga uso, sin ningún tope.

**Implementado**: `functions/src/index.ts` — `cleanupExpiredVideos`, una Cloud Function programada (`onSchedule`, cada 60 minutos, zona horaria `America/Bogota`) que busca videos con `createdAt` de más de 24h, borra el blob de video y el thumbnail en Storage (parseando el path desde la download URL guardada), y borra el documento en Firestore junto con su subcolección `views` (`recursiveDelete`).

**Pendiente, acción manual** — esto necesita el plan **Blaze** (pago por uso) habilitado en el proyecto de Firebase, porque las funciones programadas usan Cloud Scheduler + Pub/Sub, que no están disponibles en el plan gratuito Spark:
```bash
npm install -g firebase-tools   # si no lo tienes
firebase login
cd functions && npm install && cd ..
firebase deploy --only functions
```

Queda pendiente, por separado, la **validación server-side de duración y content-type real** (hoy solo se valida `content-type` declarado por el cliente y tamaño en `storage.rules` — ninguna regla puede inspeccionar el contenido real del archivo). No se implementó en esta pasada: requiere `ffprobe` o similar corriendo en una función activada por subida (`onObjectFinalized`), es una pieza separada de la de expiración programada.

## 2. ~~Reporte y bloqueo de usuarios~~ — implementado, falta desplegar reglas

La app pasó de "solo lectura de perfil" a generar contenido público real (UGC) visible por otros usuarios. La política de Contenido Generado por el Usuario de Google Play exige, antes de publicar una feature con UGC visible: mecanismo in-app para reportar contenido/usuarios, capacidad de bloquear usuarios, y compromiso de remover contenido reportado en un plazo razonable. No es opcional para pasar la revisión de Play — es un bloqueante de publicación, no solo una buena práctica.

**Implementado**: colección `reports/{reportId}` en Firestore (creable solo por el reportante, solo lectura/gestión administrativa), campo `blockedUsers: List<String>` en `User`, `UserRepository.blockUser/unblockUser/getBlockedUsers`, `ReportRepository.submitReport`, filtro reactivo de usuarios bloqueados en `FeedViewModel` (inmediato) y `RankingViewModel` (en la carga), menú de "más opciones" con reportar/bloquear en `FeedScreen`.

**Pendiente, acción manual**: las reglas nuevas de `firestore.rules` (colección `reports`) están en el repo pero **no desplegadas** al proyecto de Firebase real. Sin este paso, `ReportRepository.submitReport` fallará en producción con permiso denegado. Correr:
```bash
firebase deploy --only firestore:rules
```

## 3. Nombre del paquete — corrección respecto a la nota original

**Aclaración importante:** el paquete actual del proyecto es `com.negociodigital.boomday` (verificado en `app/build.gradle.kts`), no `com.example.boomday`. Google Play rechaza específicamente paquetes que empiecen con el prefijo literal `com.example` (la plantilla por defecto de Android Studio) — `com.negociodigital.boomday` **no cae en esa categoría** y no es, técnicamente, un bloqueante de publicación.

Dicho eso, sigue siendo válido reconsiderar el nombre por coherencia de marca: el paquete liga la identidad de la app a "negociodigital" en vez de "BoomDay". Si se decide cambiar (por ejemplo a algo como `com.boomday.app`), **tiene que hacerse antes de la primera publicación en Play Console** — el `applicationId` no se puede modificar después sin perder el historial de reviews, instalaciones y estadísticas (técnicamente equivale a publicar una app nueva).

## 4. Explore screen

Hoy es 100% mock: trending, categorías y usuarios sugeridos son datos hardcodeados, sin `ViewModel`, sin conexión a Firestore. Queda pendiente definir qué significa "trending"/"sugeridos" en un producto tan chico (¿los mismos videos del Ranking con otro layout? ¿un algoritmo real?) antes de invertir en implementarlo.

## 5. Requisitos de Google Play Store

Ninguno de estos existe todavía y corren en paralelo al código, no después:

- **Closed testing**: mínimo 12 testers activos durante 14 días consecutivos antes de poder solicitar producción — es el camino crítico de tiempo, conviene arrancarlo temprano aunque el producto no esté 100% terminado.
- **Política de privacidad** (URL pública, requisito de Play Console).
- **Formulario de seguridad de datos** (Data Safety) — declarar qué datos recolecta la app (email, videos, ubicación si se llega a usar para el ranking local) y con qué propósito.
- **Eliminación de cuenta**: Play exige que las apps con creación de cuenta ofrezcan eliminación de cuenta y datos asociados, tanto in-app como por un método externo. Hoy `ProfileRepository` solo tiene `signOut`, no borrado de cuenta/datos — falta implementar.

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
