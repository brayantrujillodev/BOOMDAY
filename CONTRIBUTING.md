# Contribuir a BoomDay

## Convenciones de código

- **MVVM estricto.** Los composables (`*Screen.kt`) nunca llaman a Firebase directo — siempre a través de un `ViewModel` inyectado con `hiltViewModel()`. Si una pantalla necesita un dato que su ViewModel no expone, se agrega al ViewModel; no se hace un bypass "por ahora".
- **Estado como `StateFlow` + `sealed interface`.** Cada pantalla expone un único `StateFlow<XState>` con, como mínimo, `Loading`, `Success`, `Empty` y `Error(message)`. Ver `FeedState`/`RankingState`/`UploadState` como referencia.
- **I/O siempre en `Dispatchers.IO`**, corrutinas ancladas a `viewModelScope` (nunca `GlobalScope`). Todo `callbackFlow` que registre un listener de Firestore cierra con `awaitClose { listener.remove() }`.
- **Manejo de errores explícito.** Ningún `catch (e: Exception) {}` vacío. Siempre `Timber.e(e, "contexto")` + `Result.failure(e)` o mapeo al estado `Error` correspondiente.
- **Repos con Hilt**: `@Singleton class XRepository @Inject constructor(...)`, expuestos también vía `@Provides` en `di/AppModule.kt` (así están todos los existentes — mantené la consistencia aunque técnicamente `@Inject constructor` solo ya alcanzaría).
- **Nada de strings hardcodeados** en composables — todo en `res/values/strings.xml`.
- **`key()` estable en listas** (`videoId`, nunca el índice).
- **Instancia única de `ExoPlayer`** en cualquier lista/pager de video — nunca una por item. Ver `docs/ARCHITECTURE.md` para el porqué.

Antes de escribir código nuevo en una capa, leé un archivo existente equivalente (otro repo, otro ViewModel, otra pantalla) y seguí su forma — el estilo del proyecto importa más que la preferencia personal de quien contribuye.

## Formato de commits

El historial del proyecto usa mensajes descriptivos en español, con prefijo de tipo cuando aplica (`feat:`, y libre para el resto). El cuerpo del commit explica el **por qué**, no solo el qué — especialmente si el cambio corrige un bug no obvio o toma una decisión de arquitectura (ver los commits de este mismo repo como ejemplo real).

```
feat: descripción corta en imperativo

Cuerpo opcional explicando el motivo del cambio, decisiones de diseño
no obvias, o qué bug corrige y cómo se manifestaba.
```

No se squashea ni se reescribe historial ya pusheado sin acuerdo explícito.

## Estructura de ramas

- `dev` es la rama principal de desarrollo activo — es donde vive el código más reciente y funcional.
- Para cambios grandes o que puedan romper algo mientras están a medio hacer, usar una rama `feature/<nombre-corto>` y mergear a `dev` cuando esté verificado (compila, se probó en dispositivo/emulador si toca UI o Firebase).
- `main` existe en el remoto pero no es la rama activa de desarrollo — no asumas que refleja el estado actual del proyecto sin confirmarlo primero.

## Antes de abrir un PR (o de commitear a `dev` directo, en modo solo-desarrollador)

- `./gradlew assembleDebug` compila sin errores.
- Si el cambio toca `firestore.rules`/`storage.rules`, quedó claro en la descripción qué reglas cambiaron y por qué (idealmente probado contra el emulador de Firebase).
- Si el cambio toca una pantalla con estados de carga/error/vacío, los tres siguen implementados — no se "olvida" uno al reescribir.
