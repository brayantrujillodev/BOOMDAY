# Auditoría técnica integral de BoomDay

- **Fecha de la auditoría:** 2026-11-23 · **Rama:** `dev` · **Método:** revisión estática de código por tres revisores independientes (arquitectura/datos, seguridad, calidad/UX) más verificaciones propias de los hallazgos más graves.
- **Alcance de la verificación:** la auditoría original fue de **solo lectura**: no se compiló ni se ejecutó la app para producirla. Lo que se corrigió después (Etapa A) sí se compiló y probó; ver `docs/ETAPA_A_RESULTADOS.md`.
- **Leyenda de evidencia:** **[C]** confirmado en código · **[V]** verificado además con una prueba ejecutada · **[H]** hipótesis (no verificada).
- **Leyenda de estado:** ✅ corregido y verificado · 🟡 corregido, pendiente de prueba en dispositivo · ⏳ pendiente · 🚫 bloqueado · ❔ no evaluado.

Los hallazgos originales se conservan tal como se emitieron; el estado actual se indica en la columna/etiqueta «Estado».

---

## 1. Resumen ejecutivo

BoomDay es una app Android de video corto en la que cada video vive 24 horas y compite en un ranking diario. La arquitectura (MVVM + Hilt + Firebase) es razonable para su tamaño y el código es ordenado. El producto **no está listo para producción**: al momento de la auditoría el ranking se podía manipular, un fallo de subida podía cerrar la app, el bloqueo de usuarios probablemente no persistía, no había pruebas, el release no estaba configurado y el backend (Cloud Functions) no estaba desplegado.

**Dictamen original: NO APTO PARA PRODUCCIÓN. Madurez técnica: 45/100** (juicio profesional, no medición objetiva).
**Estado tras la Etapa A:** los tres defectos críticos/altos de la etapa están corregidos y probados a nivel de reglas (emulador) y JVM; ver sección 18 para el dictamen vigente (sigue siendo **NO APTO**).

## 2. Qué es BoomDay (según la evidencia)

App Android de videos cortos efímeros (24 h) con ranking diario por vistas. Documentación y código coinciden en lo esencial, con las discrepancias de la sección 13. No hay información comercial documentada (modelo de negocio, métricas objetivo); no se asumió ninguna.

## 3. Tecnologías y arquitectura

Kotlin, Jetpack Compose, MVVM, Hilt, Media3/ExoPlayer, CameraX, Coil, Timber; Firebase Auth (Google), Cloud Firestore, Storage y Cloud Functions (TypeScript, Node 20, `firebase-functions` v6). CI en GitHub Actions (solo `assembleDebug`). Capas: `ui/` (composables y ViewModels), `data/` (repositorios y modelos), `domain/usecase/` (2 casos de uso). Los composables no usan Firebase directo **[C]**; los ViewModels inyectan `FirebaseAuth` directamente (acoplamiento menor, P3).

## 4. Mapa de módulos

| Módulo | Estado |
|---|---|
| Splash / sesión | Implementado, runtime sin probar |
| Login Google + Firebase Auth | Implementado; no probado contra `boomday-e2308` (migración sin cerrar) |
| Feed (pager + ExoPlayer) | Implementado; botones like/comentarios/compartir sin lógica; sin miniaturas |
| Ranking | Implementado; sin reintento; se desfasa al cruzar medianoche |
| Subida (cámara, galería, revisión) | Implementado; fallos de error corregidos en Etapa A |
| Explore | Datos reales (Trending/Recientes/búsqueda local); tarjetas sin destino |
| Perfil / cerrar sesión | Implementado, básico |
| Eliminación de cuenta | Funcional; no limpia `views` de otros videos ni reportes |
| Reportar y bloquear | UI y repositorios presentes; persistencia del bloqueo corregida en Etapa A; sin moderación server-side |
| Cloud Functions | Código listo (`cleanupExpiredVideos`, `validateUploadedVideo`), **sin desplegar** |
| Likes / comentarios / shares | Solo campos en el modelo |
| «Ranking local por ciudad» (README) | **No existe** |

## 5. Estado del frontend

Pantallas completas pero con deuda de UX: botones muertos, sin `BackHandler` en la subida, estado perdido al rotar, textos hardcodeados mezclados con `strings.xml`, touch targets de 30 dp, contraste insuficiente (blanco sobre oro/plata), insets inconsistentes, tema oscuro nunca activo, splash fijo de 2,5 s. **No se ejecutó la app**: todo es revisión de código.

## 6. Estado del backend

Cloud Functions escritas y compilables (`npm run build`), **no desplegadas** (plan Blaze pendiente). `validateUploadedVideo` fija región `us-central1` y bucket `boomday-e2308.firebasestorage.app` sin confirmar la región real. No hay función para reportes ni para conteo de vistas.

## 7. Estado de la base de datos

Modelo: `videos`, `videos/{id}/views/{uid}`, `users`, `reports`. Un índice compuesto (`dayKey`, `views desc`) que coincide con la query real **[C]**. Riesgos: Feed sin `limit` ni paginación (costo creciente), contención en el contador `views` de videos virales **[H]**, huérfanos de datos (`views`, blobs sin documento).

## 8. Seguridad

Lo bueno: `reports` no legible por clientes; `users/{uid}` solo legible por su dueño; URLs de video atadas a la carpeta del propio usuario; `createdAt` acotado ±10 min; borrado Admin restringido al dueño; `allowBackup=false`. Pendiente: ver hallazgos P1/P2 (suplantación de nombre/avatar, reportes sin procesar ni límite, almacenamiento sin cuota, App Check, menores, logs con PII, release sin R8). Historial git: `google-services.json` estuvo commiteado (API key de cliente, pública por diseño; conviene restringirla por paquete+SHA-1); no se encontró keystore ni `private_key` por ruta/patrón, **sin escaneo con gitleaks**.

## 9. Pruebas

Al auditar: **cero** (solo `ExampleUnitTest`/`ExampleInstrumentedTest`); CI sin tests ni lint. Tras la Etapa A: pruebas JVM para subida, mapeo de usuarios, bloqueo y ViewModels, y 30 pruebas de reglas de Firestore con emulador. Resultados y comandos en `docs/ETAPA_A_RESULTADOS.md`. Siguen sin existir pruebas instrumentadas ni E2E, y el CI no las ejecuta.

## 10. Rendimiento y escalabilidad

Hipótesis (no medidas): lecturas de Firestore superlineales por listeners sin límite sobre una colección cuyos documentos cambian con cada vista; contención de escrituras en `views`; ExoPlayer sin caché ni precarga; subida de video original sin compresión. Validar con el Firebase Emulator + generador de carga y con el panel de uso de Firestore.

## 11. Hallazgos críticos (P0)

| ID | Hallazgo | Evidencia | Estado |
|---|---|---|---|
| P0-1 | **Ranking manipulable**: `existsAfter()` es verdadero también si la vista ya existía; un cliente modificado repite `views+1` sin límite. | `firestore.rules` (update de `videos`) **[V]** — 3 pruebas fallan con la regla vieja | ✅ Corregido y probado en emulador (regla exige `!exists` previo + `existsAfter`); limitación residual: cuentas múltiples |
| P0-2 | **La app puede cerrarse si falla una subida**: `close(exception)` en `callbackFlow` + `collect` sin `catch`. | `StorageRepository.kt`, `UploadRepository.kt`, `UploadViewModel.kt` **[V]** — prueba falla con el código viejo | 🟡 Corregido y probado en JVM; sin verificar en dispositivo |

## 12. Hallazgos importantes (P1)

| ID | Hallazgo | Estado |
|---|---|---|
| P1-1 | **El bloqueo de usuarios no persistía**: `createdAt` se escribe como `Timestamp` y `User.kt` lo declara `Long`; `getUser()` devolvía `null` y `getBlockedUsers` un set vacío. **[V]** (prueba con `CustomClassMapper`) | 🟡 Corregido (`UserMapper`, lectura directa de `blockedUsers`, fail-closed) y probado en JVM; sin verificar en dispositivo |
| P1-2 | Reportes sin revisión ni SLA; la evidencia desaparece a las 24 h | ⏳ |
| P1-3 | Reportes sin validación de relaciones, deduplicación ni límite de tasa | ⏳ |
| P1-4 | Suplantación: `userName`/`userAvatar` sin validar (avatar con URL externa) | ⏳ |
| P1-5 | Feed escucha 24 h de videos sin límite/paginación (costo) **[H]** magnitud | ⏳ |
| P1-6 | Storage sin cuota; blobs huérfanos sin limpieza | ⏳ |
| P1-7 | Pruebas inexistentes y CI mínimo | 🟡 Parcial (Etapa A añade pruebas; CI no las ejecuta) |
| P1-8 | Release sin R8/firma; código `debug/` en el APK | ⏳ |
| P1-9 | Subida sin compresión ni WorkManager | ⏳ |
| P1-10 | Miniaturas nunca generadas (`uploadThumbnail` sin llamadores) | ⏳ |
| P1-11 | Botones muertos en Feed; atrás cancela la subida sin avisar | ⏳ |
| P1-12 | Sin enlace a política de privacidad; Perfil sin «Mis videos» | ⏳ |

## 13. Deuda técnica (P2/P3 y documentación)

P2: ranking desfasado tras medianoche y sin reintento; Feed pierde posición al refrescar; ExoPlayer sin manejo de errores/caché/foco de audio; temporales de grabación acumulados; rotación/tablet/edge-to-edge; strings hardcodeados; accesibilidad; `Timber.DebugTree` en release con PII; `users` sin restricción de campos; validación de `videos` incompleta; validación de duración WebM ausente; migración de bucket sin cerrar; sin App Check; verificación de edad; eliminación de cuenta incompleta/sin URL web; CI sin tests; dependencias antiguas (**[H]** sin consultar CVEs).
P3: código muerto (`StoryModel.kt`, `debug/`), componentes grandes (`FeedScreen.kt` 779 líneas), splash, tema oscuro, `CancellationException` capturada en varios `catch` heredados, `kotlinCompilerExtensionVersion` redundante.
Documentación desactualizada: README (Explore «mock», validación server-side «pendiente», «ranking local» inexistente), `ARCHITECTURE.md` (tipo de usuario «user/guest» vs `"free"` escrito).
Nota: la hipótesis «el wrapper local de Gradle 9.7.1 es incompatible con AGP 8.10.1» **no se confirmó**: la compilación y las pruebas pasan con ese wrapper. El cambio del wrapper, sin embargo, **no es de este trabajo** y se deja fuera de los commits.

## 14. Riesgos operativos y comerciales

Costos Firebase sin tope (Blaze sin presupuesto/alertas, listeners sin límite, blobs huérfanos); moderación UGC inexistente (riesgo de rechazo en Play y legal, incluido contenido ilegal o de menores); privacidad (menores sin verificación de edad; datos de rostro/voz; revisión legal pendiente); dependencia de un único desarrollador y de acciones manuales en consola; sin información comercial para evaluar conversión/retención. Estrategia de arranque (masa crítica local) fuera del alcance técnico.

## 15. Plan de corrección priorizado

Ver `docs/PLAN_PRODUCCION_BOOMDAY.md` (etapas A–E con criterios de aceptación).

## 16. Recomendaciones de arquitectura

Conservar MVVM/Hilt/Firebase; no reconstruir. Incrementales: (1) mover el conteo de vistas a servidor (Cloud Function + App Check) cuando el ranking tenga valor real; (2) estado de bloqueo como `Flow` compartido entre ViewModels; (3) `status` de moderación en `videos` (`pending/approved/hidden`); (4) paginar el Feed; (5) contrato de datos único para `users` (`createdAt`); (6) mover `debug/` a `src/debug`. No se justifican microservicios ni infraestructura adicional.

## 17. Condiciones mínimas para producción

1. Cloud Functions desplegadas y validadas en `boomday-e2308` (plan Blaze, región correcta).
2. Migración de proyecto cerrada y probada de punta a punta; bucket/regla viejos eliminados.
3. Moderación operativa (alerta de reportes, ocultamiento, SLA, contacto).
4. Release: R8 con reglas, firma, logging sin PII, `debug/` fuera del APK.
5. Límite/paginación del Feed y presupuesto con alertas.
6. Política de privacidad pública, Data Safety, URL de eliminación de cuenta, verificación de edad decidida.
7. Pruebas en CI (unitarias + reglas) y verificación en dispositivo de los flujos críticos.
8. 12 testers durante 14 días (closed testing).

## 18. Dictamen técnico final

**NO APTO PARA PRODUCCIÓN** (vigente tras la Etapa A).
Justificación: la Etapa A cerró los defectos críticos de integridad, estabilidad y bloqueo, pero quedan abiertos: Cloud Functions sin desplegar, moderación inexistente, riesgo de costos, release sin configurar, ausencia de verificación en dispositivo y de pruebas en CI, y la migración Firebase sin validar.
**Madurez técnica: ~50/100** (antes 45). Criterios: arquitectura y reglas sólidas, y ahora pruebas en las rutas críticas; penalizan la falta de despliegue, de verificación en dispositivo, de moderación y de configuración de release. Es un juicio profesional, no una medida objetiva.
