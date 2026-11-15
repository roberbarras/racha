# Visor de recursos

Spec §5 y [ADR-0003](adr/0003-html-local-seguro.md). Código: reglas en [`domain/resource`](../client/shared/domain/src/commonMain/kotlin/io/github/roberbarras/racha/domain/resource),
caso de uso en [`application/resource`](../client/shared/application/src/commonMain/kotlin/io/github/roberbarras/racha/application/resource),
UI en [`ui/resource`](../client/shared/ui/src/commonMain/kotlin/io/github/roberbarras/racha/ui/resource),
adaptadores en [`android/resource`](../client/android/src/main/kotlin/io/github/roberbarras/racha/android/resource) y [`desktop/resource`](../client/desktop/src/main/kotlin/io/github/roberbarras/racha/desktop/resource).

## Qué se ve

| Tipo | Cómo |
|---|---|
| `.html`/`.htm` | Navegador embebido que solo accede a la carpeta del curso: CSS, imágenes, JS y quizzes relativos funcionan; cualquier enlace que salga del curso se abre en el navegador del sistema |
| `.url` | Tarjeta con el destino y "Abrir en el navegador" (solo `http`, `https`, `mailto`; `javascript:`/`file:` se ignoran) |
| `.txt` | Texto seleccionable con enlaces clicables; UTF-8 (con o sin BOM) o Windows-1252; hasta 2 MB |
| `.pdf` | Visor integrado: páginas ajustadas al ancho, zoom, indicador de página; solo se renderizan las visibles y al tamaño mostrado |
| Fichero que falta | Aviso "No se encuentra el fichero" |

Barra superior: atrás, anterior/siguiente en el orden del curso (vídeo → reproductor, recurso → visor), abrir fuera de la app, "Marcar como visto" y la lista del curso a la derecha (en teléfono, a pantalla completa). Al salir, el recurso queda **visto**; una visita de 5 s o más se anota en el historial.

## Plataformas

| | Android | Linux |
|---|---|---|
| HTML | WebView con `WebViewAssetLoader` y un manejador limitado a la ruta canónica del curso; sin acceso a ficheros ni a `content://`; JS sin puente con la app | JavaFX WebKit (`JFXPanel` en Compose) servido por un HTTP en `127.0.0.1` con token, limitado a la carpeta del curso; zoom = escala de la interfaz |
| PDF | `PdfRenderer` en un hilo propio | PDFBox en un hilo propio |
| Abrir fuera | Intent del sistema; ficheros vía `FileProvider` de solo lectura | `xdg-open` |

**Notas de Linux**
- **JavaFX fijado en 26.0.2**: el `JFXPanel` de 27.0.1 ejecuta la página pero nunca la pinta (`BufferOverflowException` en `EmbeddedScene.getPixels`, página en blanco), comprobado en tu escritorio y en Xvfb con una ventana mínima. El arnés de recursos comprueba que la página se pinta; subir de versión cuando una 27.x lo pase.
- **Fuentes de PDFBox**: para fuentes no incrustadas PDFBox indexa las del sistema la primera vez (15,5 s en tu equipo; 0,6 s después). El índice vive en `~/.cache/racha/pdfbox` y la app lo prepara una vez en segundo plano, 30 s después de arrancar; mientras, las páginas muestran un esqueleto de carga.

## Tests

- Dominio: `.url` (Windows, enlace suelto, esquemas peligrosos), enlaces en texto.
- `ResourceViewerTest` (SQLite + ficheros reales): HTML desde su carpeta, `.url`, TXT Windows-1252, PDF, fichero archivado, visto al salir e historial, marcas explícitas.
- `CourseFileServerTest`: el curso se sirve; 8 intentos de salir de él (`..`, `%2e%2e`, absoluta, otro curso, enlace simbólico, sin token, token erróneo, carpeta) → 404; solo lectura.
- `PdfBoxDocumentTest`: páginas, proporciones, píxeles del contenido; fichero roto → nulo.
- Android (dispositivo): `ResourceAndroidTest` (el curso de prueba de [`testdata/web`](../client/testdata/web) se autocomprueba en el WebView: 5 funciones ok y 4 escapes bloqueados; el enlace externo va al navegador; PDF con píxeles reales) y `ResourceUiTest` (app real: Biblioteca → curso → página HTML en el visor → visto al volver).
- Linux (Xvfb): `ResourceHarness` (app real: comprobaciones de la página en JavaFX, que la página se pinta, PDF y TXT, vistos al salir). Local: `spikes/safe-run.sh virtual 200 desktop-resources`; también en CI.
