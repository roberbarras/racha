# ADR-0005 · Acceso al almacenamiento en Android

**Estado**: aceptada.

**Decisión**
- El dominio solo conoce el puerto **`LibrarySource`** (listar, metadatos de fichero, abrir, vigilar cambios, URI reproducible). En BD se guardan rutas **relativas** a la raíz y la URI de la raíz (`file://` hoy, `content://` mañana): cambiar de implementación no altera `contentId`.
- Reproductor y WebView consumen URIs, nunca `java.io.File`.
- Implementación actual: `MANAGE_EXTERNAL_STORAGE` + POSIX + `FileObserver` (file-watcher nativo de §3.3, escaneo rápido, recursos HTML relativos). Válido porque la distribución es APK fuera de Play.
- Para Play Store: añadir una implementación SAF (`DocumentsContract`) en un *product flavor*, sin tocar dominio ni aplicación.

**Descartado (hoy)**: SAF como única vía — escaneo lento, sin vigilancia de cambios fiable.
