# ADR-0018 · Carpetas remotas (Nextcloud/WebDAV y Google Drive)

**Estado**: aceptada (H19, 2026-11-18, a petición del usuario).

**Contexto**: el usuario quiere añadir carpetas de Nextcloud o de Google Drive con cursos y series, configurables desde Android y Linux. Eligió los dos proveedores y **descargar al dispositivo** (no reproducir en *streaming*), todo o solo los cursos que elija.

**Decisión**:

- **La descarga va a una carpeta normal de la biblioteca**. Cada carpeta remota (`RemoteSource`) tiene su destino local, que se añade como raíz de la categoría elegida con su forma (biblioteca, un curso o automática, ADR-0013). A partir de ahí todo funciona como con cualquier carpeta: escaneo, reproducción, progreso, sincronización y vinculación entre dispositivos. No hace falta un tipo de contenido «remoto».
- **Cursos detectados como en local**. La lista remota se convierte en `FileEntry` y pasa por las mismas reglas (`LibraryLayout`). Cada curso tiene una clave: su carpeta de primer nivel en una biblioteca, `""` para los ficheros sueltos o para la carpeta entera si es un solo curso. «Todo» descarga también los cursos nuevos; «los que elija» solo los marcados.
- **Qué se descarga** (`RemoteCourses.toDownload`, puro):
  - los ficheros de los cursos elegidos que faltan o cambiaron (tamaño distinto o fecha más nueva, con 2 s de tolerancia);
  - los pequeños primero (subtítulos, recursos, carátulas), así un curso se puede usar antes;
  - nunca lo oculto ni lo excluido en la categoría.
- **Cómo se descarga**:
  - a `<nombre>.racha-part`, continuando con `Range` lo que quedó a medias;
  - al terminar, se renombra y recibe la fecha remota, así que el escaneo nunca ve medio fichero;
  - antes de empezar se comprueba el espacio libre (más 500 MB de margen);
  - después se escanea la categoría y lo que llegó aparece como cursos.
- **Lo descargado no se borra solo**. Desmarcar un curso deja de descargarlo; «Liberar espacio» borra sus ficheros cuando el usuario lo pide. Quitar la carpeta remota pregunta si conservar los ficheros. Lo que desaparece del servidor se conserva aquí.
- **Proveedores** (módulo `remote`, mismo puerto `RemoteStorage`):
  - **WebDAV** (Nextcloud, ownCloud y cualquier servidor WebDAV): `PROPFIND` de un nivel cada vez (muchos servidores rechazan `infinity`) y autenticación Basic sobre HTTPS.
    - En Nextcloud, el acceso es con el **login flow v2**: el navegador abre el servidor, el usuario entra allí (con su 2FA) y Racha recibe una **contraseña de aplicación** revocable. Nunca pide la contraseña de la cuenta.
    - Alternativa manual: contraseña de aplicación o un servidor WebDAV cualquiera.
  - **Google Drive**: OAuth 2.0 para apps instaladas, con PKCE y redirección a una dirección `127.0.0.1` de un solo uso. Pide solo el alcance `drive.readonly`. Recorre «Mi unidad» y «Compartido conmigo», y los documentos de Google (sin tamaño) se ignoran.
    - Google no deja publicar un cliente OAuth que valga para todos en una app de código abierto: **el usuario crea el suyo** («App de escritorio») y Racha guarda su id y secreto en el llavero (ver [CARPETAS-REMOTAS.md](../CARPETAS-REMOTAS.md#cliente-oauth-de-google)).
- **Credenciales**: en el almacén protegido (Android Keystore, libsecret en Linux), una por carpeta remota más el cliente de Google. Nunca en la base de datos, la exportación, la copia ni la sincronización.
  - La definición de la carpeta remota es **de este dispositivo** (tabla `remote_source`, esquema v10, sin sincronizar): cada dispositivo decide qué descarga y dónde.
- **Cuándo se descarga**:
  - al añadir la carpeta y con «Descargar ahora»;
  - después, para traer las clases nuevas:
    - **Android**: un *worker* de WorkManager en primer plano (tipo `dataSync`, con notificación de progreso) que sobrevive a salir de la app. Pide red, batería no baja y Wi-Fi si la carpeta es «solo con Wi-Fi». Se repite cada 6 h, programado solo mientras hay carpetas remotas.
    - **Linux**: mientras la app está abierta, 30 s después de arrancar y cada 6 h.
- **Destino por defecto**:
  - Linux: `<carpeta de vídeos de xdg-user-dirs>/Racha/<nombre>`, p. ej. `~/Vídeos/Racha/Cursos`;
  - Android: `Racha/<nombre>` en el almacenamiento compartido, con el acceso a archivos que la app ya pide (ADR-0005);
  - se puede elegir otra carpeta.

**Limitaciones conocidas**:

- En Android solo vale `https://`: un Nextcloud doméstico en `http://` necesita un certificado (o un proxy con TLS).
- Con el cliente de Google en modo «Prueba», Google caduca el permiso cada 7 días; conviene ponerlo «En producción» (sin verificar, solo para uno mismo).
- En Android 15, Google limita las tareas `dataSync` a unas 6 h al día: una descarga enorme sigue al día siguiente donde se quedó.

**Alternativas descartadas**:

- **Reproducir en *streaming***: el usuario prefiere tener los cursos en el dispositivo, también sin conexión.
- **El SDK de Google (Play services / Drive API para Android)**: no existe en Linux y añade dependencias propietarias. El protocolo HTTP es el mismo en las dos plataformas.
- **Sincronizar la definición de la carpeta remota**: las credenciales y el destino son de cada dispositivo.
