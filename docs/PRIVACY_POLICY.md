# Política de Privacidad de BoomDay

**Última actualización:** 21 de noviembre de 2026

BoomDay ("la app", "nosotros") es una aplicación móvil para Android de video corto en la que cada video desaparece a las 24 horas. Esta política explica qué datos recolectamos, para qué los usamos, con quién se procesan y cómo puedes controlarlos o eliminarlos.

> **Responsable del tratamiento / contacto:** BoomDay — `sadtiger69@gmail.com` *(PLACEHOLDER: reemplazar por el correo y, si aplica, el nombre legal y dirección definitivos antes de publicar)*.

## 1. Datos que recolectamos

### 1.1 Datos de tu cuenta
Al iniciar sesión con **Google (Firebase Authentication)** recibimos y guardamos:
- Correo electrónico.
- Nombre de la cuenta de Google.
- Foto de perfil (URL), si tu cuenta de Google la tiene.
- Un identificador único de usuario (UID) generado por Firebase.
- Fecha de creación de tu perfil y tipo de cuenta (usuario o invitado).

### 1.2 Contenido que publicas
- **Videos** que grabas con la cámara o importas desde tu galería (incluido su audio), y la **miniatura** del video.
- Título, descripción, hashtags y duración del video.
- Tu nombre y foto de perfil se copian en cada video que publicas para mostrarlos como autor.

### 1.3 Interacciones con la app
- **Vistas de video:** registramos el identificador (UID) de cada usuario que vio un video (un registro por usuario y video) para contar las vistas sin duplicados. Ese registro solo es legible por el propio usuario y se elimina cuando el video expira (ver secciones 5 y 6). Los contadores de vistas, likes, comentarios y compartidos se guardan junto al video.
- **Reportes:** cuando reportas contenido o a un usuario guardamos tu identificador, el usuario/video reportado, el motivo que escribas y la fecha.
- **Bloqueos:** la lista de usuarios que has bloqueado se guarda en tu perfil.

### 1.4 Permisos del dispositivo
- **Cámara y micrófono:** solo se usan para grabar el video que decides publicar. No se accede a ellos en segundo plano.
- **Internet:** necesario para iniciar sesión, subir y ver videos.
- Si importas un video de tu galería, la app solo lee el archivo que seleccionas.

### 1.5 Lo que NO recolectamos
No recolectamos tu ubicación, contactos, SMS, historial de navegación ni identificadores publicitarios. La app no incluye SDK de publicidad ni de analítica de terceros.

## 2. Para qué usamos tus datos
- Crear y mantener tu cuenta y autenticarte.
- Mostrar tus videos y tu nombre/foto a otros usuarios de la app.
- Generar el feed y el ranking diario de videos (por vistas).
- Contar vistas y mostrar métricas.
- Moderación: atender reportes, aplicar bloqueos y retirar contenido que incumpla las normas o la ley.
- Seguridad y prevención de abuso (por ejemplo, validar que los archivos subidos sean videos válidos y dentro de los límites de tamaño y duración).

No vendemos tus datos ni los usamos para publicidad.

## 3. Quién puede ver tus datos
- **Otros usuarios autenticados** pueden ver tus videos publicados (dentro de su ventana de 24 horas), tu nombre, tu foto de perfil y las métricas del video.
- **Tu correo electrónico y tu lista de bloqueados** solo son visibles para ti (y para nosotros a nivel administrativo).
- Los **reportes** solo son accesibles por el equipo de BoomDay, nunca por otros usuarios.

## 4. Proveedores que procesan datos
Usamos servicios de **Google Firebase** (Google LLC) como proveedor de infraestructura:
- *Firebase Authentication* (inicio de sesión con Google).
- *Cloud Firestore* (perfil, metadatos de videos, vistas, reportes).
- *Cloud Storage for Firebase* (archivos de video y miniaturas).
- *Cloud Functions* (borrado automático a las 24 horas y validación de archivos subidos).

Estos proveedores pueden procesar datos en servidores ubicados fuera de tu país (principalmente Estados Unidos), bajo sus propias políticas y garantías: <https://firebase.google.com/support/privacy>. Los datos se transmiten cifrados (HTTPS/TLS).

## 5. Cuánto tiempo conservamos tus datos
- **Videos y miniaturas:** se eliminan automáticamente **24 horas** después de su publicación (archivo de Storage, miniatura, documento y registros de vistas asociados; si borras el video manualmente antes, los registros de vistas pueden permanecer, ver sección 6). Una tarea automática corre cada hora, por lo que el borrado efectivo puede tardar unos minutos adicionales.
- **Datos de tu cuenta (perfil, bloqueos):** mientras mantengas tu cuenta.
- **Reportes de moderación:** se conservan el tiempo necesario para gestionar el reporte y cumplir obligaciones legales o de seguridad.
- Si publicas un video, otros usuarios pudieron haberlo visto o descargado durante esas 24 horas; no podemos controlar copias hechas fuera de la app.

## 6. Eliminación de tu cuenta y tus datos
Puedes eliminar tu cuenta en cualquier momento **desde la propia app**: *Perfil → Eliminar cuenta*. Esto borra:
- Todos tus videos: el documento de Firestore, el archivo de video y la miniatura de Storage.
- Tu documento de perfil (correo, nombre, foto, lista de bloqueados).
- Tu cuenta de Firebase Authentication.

Lo que **no** se borra de inmediato al eliminar la cuenta:
- Los registros de vistas (solo UID y fecha) que quedaron en los videos que borras desde la app (al eliminar la cuenta o un video manualmente): la app no puede borrar esa subcolección, y la tarea automática solo limpia las vistas de videos que expiran por tiempo. Quedan huérfanos, sin contenido visible, y los eliminamos si lo solicitas por correo.
- Los reportes que hayas enviado o recibido (ver abajo).

Por seguridad, la app puede pedirte que vuelvas a iniciar sesión con Google para confirmar. Si no puedes acceder a la app, escríbenos a `sadtiger69@gmail.com` *(PLACEHOLDER)* desde el correo de tu cuenta y procesaremos la eliminación. También puedes pedirnos acceso, corrección o copia de tus datos por ese mismo medio.

Los reportes que hayas enviado o recibido pueden conservarse de forma limitada cuando sea necesario para la seguridad de la plataforma.

## 7. Derechos del titular
Según la legislación aplicable (incluida la Ley 1581 de 2012 de Colombia y normas equivalentes), puedes conocer, actualizar, rectificar y suprimir tus datos, y revocar tu autorización. Para ejercer estos derechos usa la eliminación in-app o escríbenos al contacto indicado arriba. Responderemos en un plazo razonable y, en todo caso, dentro de los plazos legales.

## 8. Menores de edad
BoomDay no está dirigida a menores de 13 años (ni a menores de la edad mínima de consentimiento digital de tu país). No recolectamos conscientemente datos de menores; si detectamos una cuenta de un menor, la eliminaremos. Si crees que un menor nos dio datos, contáctanos.

## 9. Contenido generado por usuarios y moderación
Puedes **reportar** videos o usuarios y **bloquear** usuarios desde la app. Revisamos los reportes y retiramos el contenido que incumpla las normas o la ley en un plazo razonable.

## 10. Seguridad
Aplicamos reglas de acceso en Firestore y Storage (cada usuario solo puede modificar su propio contenido), transmisión cifrada y validación server-side de los archivos subidos. Ningún sistema es 100% seguro; no podemos garantizar seguridad absoluta.

## 11. Cambios a esta política
Podemos actualizar esta política. Publicaremos la versión vigente en esta misma URL con su fecha de actualización y, si el cambio es sustancial, lo avisaremos dentro de la app.

## 12. Contacto
Para consultas sobre privacidad o ejercer tus derechos: `sadtiger69@gmail.com` *(PLACEHOLDER: confirmar antes de publicar)*.
