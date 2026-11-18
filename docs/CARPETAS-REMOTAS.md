# Carpetas remotas (H19)

Decisión y detalles: [ADR-0018](adr/0018-carpetas-remotas.md). La guía de uso completa se escribirá con la aplicación terminada; aquí está lo técnico y lo que hay que configurar una vez.

## Piezas

| Pieza | Dónde | Qué hace |
|---|---|---|
| `RemoteCourses` | `domain/remote` | Cursos de la lista remota (mismas reglas que el escaneo), qué falta o cambió, qué liberar |
| `RemoteSources` | `application/remote` | Añadir, elegir cursos, descargar con progreso, liberar espacio, quitar |
| `RemoteAccounts` | `application/remote` | Acceso a Nextcloud (login flow) y a Google (OAuth) |
| `WebDavStorage` / `NextcloudLogin` | `remote/storage` | WebDAV (`PROPFIND`, `Range`) y login flow v2 |
| `GoogleDriveStorage` / `GoogleDriveAuth` | `remote/storage` | Drive v3 y OAuth con PKCE en `127.0.0.1` |
| `NioLocalCopies` | `files` | Las copias descargadas, espacio libre, borrar |
| `SqlRemoteSources` | `data` | Tabla `remote_source` (v10), solo de este dispositivo |
| `RemoteFoldersSection` | `ui/folders` | Carpetas → «Carpetas remotas»: asistente y tarjetas |
| `RemoteDownloadWorker` | `android/remote` | Descargas en segundo plano con notificación |

## Cliente OAuth de Google

Para usar Google Drive hace falta, una vez, un cliente OAuth propio:

1. En [Google Cloud Console](https://console.cloud.google.com/), crea un proyecto (o usa uno) y activa la **Google Drive API** (APIs y servicios → Biblioteca).
2. **Pantalla de consentimiento de OAuth**:
   - tipo «Externo»;
   - nombre de la app (p. ej. «Racha»);
   - tu correo;
   - en *Alcances*, añade `.../auth/drive.readonly`.
3. Publícala **«En producción»**: en «Prueba», Google caduca el acceso cada 7 días. Sin verificar, Google avisa al iniciar sesión de que la app no está verificada: es la tuya, puedes continuar.
4. **Credenciales → Crear credenciales → ID de cliente de OAuth → «App de escritorio»**. Copia el **ID de cliente** y el **secreto**.
5. En Racha: Carpetas → Añadir carpeta remota → Google Drive, pega los dos y pulsa «Iniciar sesión con Google». El mismo cliente sirve para Android y Linux.

## Nextcloud

- **Recomendado**: «Iniciar sesión en el navegador» (login flow v2). Racha recibe una contraseña de aplicación que aparece en Nextcloud → Configuración → Seguridad, desde donde se puede revocar.
- **Manual**: una contraseña de aplicación creada allí mismo, o cualquier servidor WebDAV (dirección WebDAV, usuario y contraseña).
- En Android el servidor tiene que ser `https://`.

## Tests

- Dominio (`RemoteCoursesTest`): cursos de una biblioteca y de un solo curso, ocultos, excluidos y descargas a medias fuera, qué falta o cambió, pequeños primero, qué liberar.
- JVM con SQLite y ficheros reales (`RemoteSourcesTest`):
  - solo llegan los cursos elegidos y se convierten en cursos de la biblioteca;
  - repetir no descarga nada; elegir más; liberar espacio; «todo» con cursos nuevos;
  - quitar borrando;
  - credenciales rechazadas y falta de espacio.
- Adaptadores contra servidores simulados, nunca los reales (`RemoteStorageTest`):
  - Nextcloud: login flow, carpetas, lista recursiva, descarga continuada con `Range` y fecha remota, credenciales malas;
  - Google: OAuth con PKCE y vuelta a `127.0.0.1`, «Mi unidad», páginas, documentos de Google fuera, descarga, acceso revocado.
- UI escritorio (`RemoteFoldersUiTest`): Nextcloud por el navegador, elegir carpeta, elegir un curso, se descarga y aparece en la biblioteca (capturas).
