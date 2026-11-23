# Formulario Data Safety (Google Play Console) — BoomDay

Respuestas mapeadas a lo que el código realmente recolecta (revisado contra `data/model/*`, `AndroidManifest.xml`, dependencias de `app/build.gradle.kts`, `firestore.rules`, `storage.rules`). Si se agrega analítica, crash reporting, anuncios o ubicación, hay que actualizar este documento y el formulario.

Proveedor de backend: Firebase (Auth, Firestore, Storage, Functions). Google actúa como *service provider/procesador* de infraestructura.

## 1. Preguntas generales

| Pregunta | Respuesta |
|---|---|
| ¿La app recolecta o comparte alguno de los tipos de datos requeridos? | **Sí** |
| ¿Todos los datos se cifran en tránsito? | **Sí** (HTTPS/TLS de Firebase) |
| ¿Ofreces una forma de solicitar la eliminación de datos? | **Sí** — in-app (Perfil → Eliminar cuenta) y por correo |
| URL de eliminación de cuenta/datos (campo de Play Console) | Pendiente: usar la URL pública de la política de privacidad (sección 6) o una página dedicada. |
| URL de política de privacidad | Pendiente de hospedar (`docs/PRIVACY_POLICY.md`) |
| ¿Cumple la política Families / está dirigida a niños? | No (público 13+) |

## 2. Tipos de datos

"Compartido" en Play = transferido a un tercero. Enviar datos a Firebase como procesador/service provider **no cuenta como compartir**. Lo que ven otros usuarios dentro de la app es contenido público del usuario, no un tercero; se declara en la descripción pero no como "compartido".

| Categoría Play | Tipo | ¿Recolectado? | ¿Compartido? | Obligatorio/Opcional | Propósito | Dato real en el código |
|---|---|---|---|---|---|---|
| Información personal | Dirección de correo | Sí | No | Obligatorio (login) | Funcionalidad de la app, Gestión de cuentas | `User.email` (Google Auth) |
| Información personal | Nombre | Sí | No | Obligatorio | Funcionalidad de la app, Gestión de cuentas | `User.name`, `Video.userName` |
| Información personal | ID de usuario | Sí | No | Obligatorio | Funcionalidad de la app, Gestión de cuentas, Seguridad/prevención de fraude | `User.uid`, `Video.userId`, `Report.reporterId`, `views/{uid}` |
| Fotos y videos | Videos | Sí | No | Opcional (solo si publica) | Funcionalidad de la app | Archivos en Storage `videos/{uid}/…`, expiran a las 24 h |
| Fotos y videos | Fotos | Sí | No | Opcional | Funcionalidad de la app | Miniatura `thumbnails/{uid}/…` y foto de perfil de Google (`User.avatar`, `Video.userAvatar`) |
| Audio | Grabaciones de voz o sonido | Sí | No | Opcional (audio del video) | Funcionalidad de la app | Micrófono (`RECORD_AUDIO`) solo dentro del video grabado |
| Mensajes / Contenido de la app | Otro contenido generado por usuarios | Sí | No | Opcional | Funcionalidad de la app | Título, descripción, hashtags del video; motivo de reporte (`Report.reason`) |
| Actividad en la app | Otras acciones de usuario (vistas, reportes, bloqueos) | Sí | No | Obligatorio para vistas | Funcionalidad de la app, Seguridad/prevención de fraude | `videos/{id}/views/{uid}`, `reports`, `User.blockedUsers` |

## 3. Tipos de datos que NO se recolectan (marcar "No")
- Ubicación (aproximada y precisa): no hay permisos ni uso en el código.
- Contactos, calendario, SMS/llamadas, archivos y documentos (solo el video que el usuario elige con el selector del sistema).
- Información financiera, salud y estado físico.
- Historial de navegación / búsqueda.
- Identificadores de dispositivo o de publicidad (no hay SDK de ads ni analítica).
- ~~Información de diagnóstico / crash logs / rendimiento~~ **RECOMENDADO DECLARAR**: aunque no hay Crashlytics ni Analytics, los SDK de Firebase/Google Play Services envían telemetría técnica mínima (diagnósticos/rendimiento). Declarar *Diagnósticos* (registros de fallos / diagnóstico), recolectado, no compartido, propósito "Funcionalidad de la app", para evitar discrepancias en la revisión de Play.
- Raza, orientación, creencias, etc.

## 4. Prácticas de seguridad (sección del formulario)
- Datos cifrados en tránsito: **Sí**.
- Los usuarios pueden solicitar que se eliminen los datos: **Sí** (in-app + correo).
- Revisión de seguridad independiente: No.
- Compromiso con la Play Families Policy: No aplica.

## 5. Declaraciones relacionadas a revisar en Play Console
- **Contenido generado por usuarios (UGC):** declarar que existe; cumple con reporte in-app, bloqueo de usuarios y remoción de contenido reportado (ver `docs/ROADMAP.md` punto 2).
- **Eliminación de cuenta:** la app ofrece eliminación in-app; falta además la URL web de eliminación que pide Play (puede ser la sección 6 de la política de privacidad).
- **Clasificación de contenido (IARC):** cuestionario aparte; marcar UGC/interacción entre usuarios.
- **Permisos sensibles:** `CAMERA` y `RECORD_AUDIO` son de uso en tiempo de ejecución, justificados por la grabación del video. No se usan permisos de ubicación, almacenamiento amplio ni de segundo plano.
- `allowBackup="false"` en el manifest: los datos locales de la app no entran en backups de Android; el dato principal vive en Firebase.
