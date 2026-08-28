# Roadmap

Orden de prioridad real, con el motivo de cada punto — no es una lista de deseos, es lo que falta para que el producto sea publicable y sostenible.

## 1. Cloud Function de expiración de 24h — crítico

Hoy `getTodayVideos()`/`getTopVideos()` **filtran en lectura** (por `createdAt`/`dayKey`), pero los blobs en Storage y los documentos en Firestore de videos vencidos **nunca se borran**. El video deja de aparecer en la app, pero se sigue pagando almacenamiento por él indefinidamente. Sin esto, el costo de Storage crece sin límite mientras la app tenga uso, sin ningún tope.

Alcance sugerido: una Cloud Function programada (`onSchedule`, cada hora o cada día) que borre documentos de `videos/` con `createdAt` de más de 24h y su blob correspondiente en Storage. De paso, esta misma función es el lugar natural para resolver la deuda pendiente de **validación server-side de duración y content-type real** (hoy solo se valida `content-type` declarado por el cliente y tamaño en `storage.rules` — ninguna regla puede inspeccionar el contenido real del archivo; una Cloud Function con `ffprobe` o similar sí puede, y podría rechazar/eliminar archivos que no cumplan al vuelo).

## 2. Reporte y bloqueo de usuarios — requisito de Google Play

La app pasó de "solo lectura de perfil" a generar contenido público real (UGC) visible por otros usuarios. La política de Contenido Generado por el Usuario de Google Play exige, antes de publicar una feature con UGC visible: mecanismo in-app para reportar contenido/usuarios, capacidad de bloquear usuarios, y compromiso de remover contenido reportado en un plazo razonable. No es opcional para pasar la revisión de Play — es un bloqueante de publicación, no solo una buena práctica.

Alcance mínimo: colección `reports/{reportId}` en Firestore, campo `blockedUsers: List<String>` en `User`, filtrar videos de usuarios bloqueados en las queries del feed, botón de "reportar" en la pantalla de reproducción.

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
- Permisos de cámara sin re-chequeo si se revocan mientras la app está en segundo plano.
- `StorageRepository` atrapa `CancellationException` como error genérico en un log (funcionalmente inofensivo, pero ensucia logs con "errores" que en realidad son cancelaciones normales).
- Archivo de video grabado no se borra del cache de la app tras subir exitosamente.
- Falta `launchSingleTop` en la navegación al tab "Crear" (doble tap podría apilar la ruta de upload dos veces; impacto bajo, se limpia solo al salir del flujo).
- `UserRepository.createOrUpdateUser` escribe el campo `displayName`, pero el modelo `User` define el campo como `name` — inconsistencia detectada durante una revisión de reglas de Firestore, no confirmada como bug en producción, pendiente de verificar.

**Ya resuelto (no reabrir sin evidencia nueva):**
- ~~Query inválida de `getTopVideos()`~~ — corregido con `dayKey` (ver `docs/ARCHITECTURE.md`).
- ~~Feed reiniciaba el video activo en cada escritura de Firestore~~ — corregido, `LaunchedEffect` ahora usa como key el `videoId` de la página activa, no la lista completa.
- ~~Incremento de `views` no atómico~~ — corregido con transacción de Firestore + regla `existsAfter()`.
- ~~`videoUrl`/`thumbnailUrl` sin validar dominio en las reglas~~ — corregido.
- ~~`notifiedViewIds` con riesgo de fragilidad de concurrencia~~ — corregido, `Set` sincronizado explícitamente.
- ~~`FeedItem.kt` con código muerto~~ — eliminado, `formatCount` movido a `ui/util/NumberFormatUtils.kt`.
