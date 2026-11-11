# ADR-0003 · Carga segura de HTML local

**Estado**: aceptada por el usuario (2026-11-11); mecanismo Linux validado en el [spike H1](../spikes/h1-linux.md) (9/9 comprobaciones). Desviación del mecanismo literal de §5.

**Contexto**: §5 propone `file://` con el sandbox desactivado (`allowFileAccessFromFileURLs`/`allowUniversalAccessFromFileURLs`). Con esas opciones, cualquier JavaScript de un HTML descargado de terceros puede leer cualquier fichero accesible por la app (con "acceso a todos los archivos": fotos, documentos…) y enviarlo a internet.

**Decisión**: mismo resultado visible (HTML con sus imágenes/CSS/JS relativos y quizzes funcionando) sin abrir el almacenamiento:
- **Android**: `WebViewAssetLoader` con un `PathHandler` propio en `https://appassets.androidplatform.net/r/<rootKey>/…` que solo entrega ficheros cuya ruta canónica está dentro de la carpeta del curso. `allowFileAccess=false`, acceso universal desactivado, JS activo sin `addJavascriptInterface`, enlaces externos al navegador del sistema.
- **Linux**: JavaFX WebView (WebKit) servido por un HTTP interno en `127.0.0.1`, puerto aleatorio y token de 128 bits en la ruta, limitado a la carpeta del curso.
- `.url`/`.txt`: texto enriquecido + abrir en navegador externo. `.pdf`: PdfRenderer (Android) / PDFBox (Linux).

**Descartado**: `file://` con acceso universal (exfiltración); JCEF/KCEF (+150–200 MB de Chromium); WebKitGTK vía JNA (no incrustable de forma fiable en AWT).
