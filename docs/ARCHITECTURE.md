# Arquitectura

## Capas

MVVM estricto, con una regla que se hizo cumplir en cada revisión de código a lo largo del proyecto: **los composables nunca llaman a Firebase directo**. Siempre pasan por un `ViewModel`, que a su vez inyecta repositorios con Hilt.

```
ui/ (Composables)          — solo lectura de StateFlow, sin lógica de negocio
    ↓ hiltViewModel()
ui/<feature>/*ViewModel.kt — sealed interface de estado, StateFlow, viewModelScope
    ↓ @Inject
data/repository/*.kt       — @Singleton, un método por operación, Result<T> o Flow<T>
    ↓
Firebase SDK (Auth / Firestore / Storage)
```

Motivo de esta separación: permite testear ViewModels sin un dispositivo/emulador (mockeando repos), y significa que un cambio de proveedor de backend (si algún día pasara) solo tocaría `data/repository/`, no cientos de composables.

### Estado como sealed interface

Cada pantalla expone un único `StateFlow<XState>` donde `XState` es una `sealed interface` con, como mínimo, `Loading`, `Success(datos)`, `Empty` y `Error(mensaje)`. Ejemplo real, `FeedState`:

```kotlin
sealed interface FeedState {
    data object Loading : FeedState
    data class Success(val videos: List<Video>) : FeedState
    data class Refreshing(val videos: List<Video>) : FeedState
    data object Empty : FeedState
    data class Error(val message: String) : FeedState
}
```

`Empty` está separado de `Error` deliberadamente: "todavía no hay videos hoy" es un estado normal y esperado en una app nueva, no una falla — el copy y el diseño de esa pantalla lo reflejan (no se ve como un error).

## Por qué `dayKey` en vez de un rango sobre `createdAt`

Este fue el bug más caro del proyecto: invisible durante toda la Fase de desarrollo porque el Ranking usaba datos mock, y habría aparecido recién en producción con testers reportando "el ranking no carga".

**El problema.** `VideoRepository.getTopVideos()` necesita "los videos de hoy, ordenados por vistas". La forma obvia de escribirlo:

```kotlin
videosCollection
    .whereGreaterThan("createdAt", hace24Horas)
    .orderBy("views", Query.Direction.DESCENDING)
```

Firestore **rechaza esta query en el cliente**, antes de tocar red: si hay un filtro de desigualdad (`whereGreaterThan`) sobre un campo, el primer `orderBy` tiene que ser sobre ese mismo campo. Acá el filtro es sobre `createdAt` y el orden es sobre `views` — campos distintos. El SDK lanza `IllegalArgumentException` de forma síncrona, siempre, en el 100% de los dispositivos. Ningún índice compuesto arregla esto: la query nunca llega a evaluarse en el servidor.

**La solución.** Cambiar el filtro de desigualdad por uno de **igualdad**: un campo `dayKey` (string, `"yyyy-MM-dd"`, ej. `"2026-08-25"`) que representa el día calendario en que se publicó el video.

```kotlin
videosCollection
    .whereEqualTo("dayKey", currentDayKeyBogota())
    .orderBy("views", Query.Direction.DESCENDING)
```

Un filtro de igualdad no tiene la restricción de "mismo campo en el orderBy" — la query es válida.

**Por qué zona horaria `America/Bogota` y no UTC.** El producto resetea el ranking "cada día" para un lanzamiento local en Neiva. Si `dayKey` se calculara en UTC, el corte de "hoy" ocurriría a las 7pm hora Colombia (UTC-5), no a medianoche — el ranking cerraría mientras la gente todavía está publicando. `DateUtils.currentDayKeyBogota()` es la **única** función que calcula esto (`java.time.LocalDate.now(ZoneId.of("America/Bogota"))`), usada tanto al escribir el video (`UploadRepository`) como al leer (`VideoRepository.getTopVideos`). Esto no es incidental: si escritura y lectura calcularan el día por separado con lógica duplicada, un desajuste de un solo dígito de offset dejaría videos publicados en un `dayKey` que ninguna query consulta — silenciosamente invisibles en el Ranking, sin ningún error.

**Nota:** `getTodayVideos()` (la query del Feed) usa una ventana móvil de 24 horas sobre `createdAt`, no `dayKey` — es una semántica distinta a propósito (un video publicado a las 11pm de ayer sigue en el Feed hasta las 11pm de hoy), y esa query sí es válida para Firestore porque el filtro y el orden están sobre el mismo campo. Feed y Ranking usan definiciones de "hoy" ligeramente distintas; unificarlas sería un cambio de producto, no un bug.

## Por qué una sola instancia de ExoPlayer

El Feed es un `VerticalPager` de scroll infinito estilo Reels/TikTok. La tentación natural es crear un `ExoPlayer` por item del pager (uno por video) — es lo que haría un primer borrador ingenuo. Eso revienta la memoria en cuestión de segundos de scroll: cada instancia de `ExoPlayer` reserva buffers de decodificación (típicamente varios MB) y threads propios: 20 videos scrolleados sin liberar player = 20 decodificadores de video vivos en memoria simultáneamente.

La solución: **una sola instancia** de `ExoPlayer`, creada con `remember` a nivel de la pantalla (`FeedPagerContent`), reasignada al `MediaItem` correspondiente cada vez que cambia la página activa (`LaunchedEffect` keyed por el `videoId` de la página actual — nunca por la lista completa, ver nota abajo). Las páginas no activas del pager muestran solo el `thumbnailUrl` (Coil), sin decodificar video.

**Nota sobre la key del `LaunchedEffect`.** La primera versión usaba `LaunchedEffect(pagerState.currentPage, videos)` — incluir la lista completa como key parecía inofensivo, pero `Video` es una `data class`: cualquier cambio de contenido en cualquier video de la lista (incluido el propio incremento de `views` que el mismo efecto dispara 2 segundos después de empezar a reproducir) genera una lista con distinta igualdad estructural, lo que reinicia el efecto — el video que el usuario está viendo activamente salta a 0:00. El fix: la key es únicamente el `videoId` de la página activa (derivado con `remember`), nunca la lista completa ni ningún campo mutable.

Recursos liberados en `DisposableEffect(Unit) { onDispose { player.release() } }`, reforzado con un `LifecycleEventObserver` que pausa (no libera) en `ON_PAUSE`/reanuda en `ON_RESUME`, para no seguir consumiendo batería/datos si la app pasa a segundo plano sin salir del composable.

## Modelo de datos de Firestore

### `videos/{videoId}`

| Campo | Tipo | Notas |
|---|---|---|
| `videoId` | string | igual al id del documento |
| `userId` | string | dueño; validado contra `request.auth.uid` en las reglas |
| `userName`, `userAvatar` | string | denormalizados al momento de subir, no se sincronizan si el usuario cambia su perfil después |
| `videoUrl` | string | download URL de Firebase Storage; las reglas validan que el dominio sea el bucket real del proyecto |
| `thumbnailUrl` | string | igual, opcional (vacío si no se generó thumbnail) |
| `title`, `description` | string | límites de 100 / 500 caracteres, en UI y en las reglas |
| `duration` | int | segundos, 3-60, validado con `MediaMetadataRetriever` antes de subir Y en las reglas de creación |
| `views`, `likes`, `comments`, `shares` | int | deben ser 0 al crear; `views` solo se modifica vía el flujo atómico de `incrementViews()` (ver abajo) |
| `hashtags` | array\<string\> | máx. 30 |
| `dayKey` | string | `"yyyy-MM-dd"`, zona `America/Bogota` — ver sección de arriba |
| `createdAt` | long (millis) | usado por `getTodayVideos()` (ventana móvil de 24h) |
| `updatedAt` | Timestamp | editable solo junto con título/descripción/hashtags |

### `videos/{videoId}/views/{userId}` (subcolección)

| Campo | Tipo |
|---|---|
| `viewedAt` | long (millis) |

Existencia de este documento = "este usuario ya contó como vista para este video". `VideoRepository.incrementViews()` crea este documento Y hace `views += 1` en una **transacción atómica de Firestore** (`runTransaction`); las reglas exigen con `existsAfter()` que el subdocumento exista al cerrar esa misma transacción para autorizar el incremento — un cliente que intente incrementar `views` sin pasar por el flujo de dedupe es rechazado.

### `users/{userId}`

| Campo | Tipo |
|---|---|
| `uid` | string |
| `email` | string |
| `name` | string |
| `avatar` | string? |
| `type` | string (`"user"` \| `"guest"`) |
| `createdAt` | long (millis) |

## Reglas de seguridad (resumen)

`firestore.rules` / `storage.rules` completos están en la raíz del repo. Puntos clave:

- Lectura de `videos/{videoId}`: cualquier usuario autenticado.
- Creación: solo con `userId == request.auth.uid`, contadores en 0, `duration`/longitudes de campo dentro de rango, `videoUrl`/`thumbnailUrl` validadas contra el bucket real del proyecto (no aceptan URLs arbitrarias).
- Edición: el dueño solo puede tocar `title`/`description`/`hashtags`/`updatedAt` — no puede reescribir sus propios contadores.
- Incremento de `views`: cualquier autenticado, +1 exacto por request, atado a la creación atómica del subdocumento de dedupe (ver arriba).
- Storage (`videos/{userId}/...`, `thumbnails/{userId}/...`): solo el dueño escribe en su propia carpeta, con límite de tamaño (100MB video / 5MB thumbnail) y `content-type` validado.
