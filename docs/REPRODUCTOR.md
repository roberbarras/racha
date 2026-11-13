# Reproductor

Spec §4. Código: reglas puras en [`domain/playback`](../client/shared/domain/src/commonMain/kotlin/io/github/roberbarras/racha/domain/playback),
controlador y puerto del motor en [`application/playback`](../client/shared/application/src/commonMain/kotlin/io/github/roberbarras/racha/application/playback),
UI compartida en [`ui/player`](../client/shared/ui/src/commonMain/kotlin/io/github/roberbarras/racha/ui/player),
adaptadores en [`android/player`](../client/android/src/main/kotlin/io/github/roberbarras/racha/android/player) (Media3) y [`desktop/player`](../client/desktop/src/main/kotlin/io/github/roberbarras/racha/desktop/player) (libmpv). Mismo controlador y misma UI en ambas plataformas.

## Capas

| Pieza | Responsabilidad |
|---|---|
| `PlaybackRules` (dominio) | Dónde reanudar (umbral 10 s al principio y al final), inicio según modo y saltar intro, inicio del outro, anterior/siguiente en la cola, salto del bucle A-B |
| `PlayerEngine` (puerto) | Cargar, play/pausa, seek, velocidad, subtítulo y pista de audio; publica un `EngineSnapshot` solo cuando cambia algo (la posición se lee bajo demanda, no se emite) |
| `PlaybackController` (aplicación) | Reanudar/preguntar, guardado periódico y al pausar/salir/terminar (§4.3), completado + autoplay, cola del curso, A-B, marcadores, intro/outro por elemento, velocidad y "mantener para 2x", historial y segundos vistos |
| `PlayerScreen` + `PlayerViewModel` (UI) | Controles que se ocultan a los 3 s, gestos, paneles, pantalla completa que se mantiene con el autoplay, atajos de teclado |
| `Media3Engine`, `AndroidPlayerHooks` (Android) | ExoPlayer + MediaSession; superficie, inmersivo, PiP, brillo, volumen, pantalla encendida, captura |
| `MpvEngine`, `DesktopPlayerHooks` (Linux) | libmpv por FFM presentando en una ventana X11 nativa; capa de controles en ventana transparente; pantalla completa, PiP, brillo/volumen del vídeo, captura |

## Decisiones para fluidez y batería (Android)

- **SurfaceView**: el vídeo va a una capa del compositor (overlay de hardware), sin copias por GPU; es la vía más barata.
- **Decodificación por hardware** (MediaCodec) con *fallback* al siguiente decodificador si uno falla al arrancar.
- **Sin temporizadores en reposo**: el ticker de guardado solo existe mientras reproduce o con bucle A-B; la posición en pantalla se consulta 4 veces por segundo solo con los controles visibles.
- **Pantalla encendida solo mientras reproduce**; en pausa vuelve el apagado normal.
- **Nada en segundo plano**: al salir de la app se pausa y se guarda; la única excepción es PiP (la propia actividad en pequeño). El PiP automático al salir se activa solo mientras hay reproducción.
- Foco de audio y "auriculares desconectados → pausa" los gestiona ExoPlayer; la MediaSession hace que funcionen los botones de auriculares/Bluetooth.
- Los subtítulos (`.srt` externos) los pinta la UI compartida a partir de las *cues* del motor: igual en Android y Linux, y sin vistas extra encima de la superficie.

## Qué hay (§4.2)

| Función | Cómo |
|---|---|
| Tocar bordes / doble toque | ±N s (ajuste `seekStep`, 10 s por defecto) |
| Arrastre horizontal | Seek con vista previa del tiempo |
| Arrastre vertical izquierdo / derecho | Brillo de la ventana / volumen del sistema |
| Pellizco | Ajustar ↔ llenar (recorta) |
| Mantener pulsado | Velocidad temporal (2x por defecto) |
| Botones | Anterior/siguiente, −N/+N, play/pausa, velocidad, subtítulos, pista de audio (si hay más de una), PiP, A-B, marcadores con nota, captura, ajustes (intro/outro por elemento), lista de elementos, pantalla completa |
| Velocidad | Pasos configurables (0,25x–4x) |
| "Continuar viendo" | Desde Home/panel reanuda; desde la fila del detalle pregunta "Reanudar desde mm:ss / Empezar de nuevo" |
| Subtítulos | Solo los del elemento, por idioma; desactivados por defecto |
| Captura | Fotograma real (PixelCopy) a `Pictures/Racha` |
| Barra lateral | Lista del curso con progreso por elemento; se abre sola si el ajuste lo pide |
| Recursos en la cola | "Siguiente" o el autoplay sobre un HTML/URL/TXT/PDF abre el visor (provisional hasta H7) |

## Linux (ADR-0011)

- **Vídeo**: mpv presenta por su cuenta (`vo=gpu-next`) dentro de un `Canvas` AWT (ventana X11 hija, `wid`); los fotogramas nunca pasan por Compose. Decodificación por software por defecto (`hwdec=no`); "Decodificación por hardware" en Ajustes la activa (`auto-safe`).
- **Controles**: la misma UI compartida, dibujada en una ventana transparente sin decoración, propiedad de la principal y colocada exactamente sobre el área del reproductor (sigue movimientos, redimensionado, pantalla completa y PiP). Recibe ratón y teclado.
- **Escalado** (ajustar / llenar / estirar) lo hace mpv (`panscan`, `keepaspect`), porque Compose no puede recortar una ventana nativa.
- **Gestos con ratón**: clic/doble clic en bordes ±N s, arrastre horizontal = seek, rueda o arrastre vertical derecho = volumen del reproductor, arrastre vertical izquierdo = brillo del vídeo, mantener = 2x; mover el ratón muestra los controles y el cursor se oculta con ellos. Teclado: espacio/K, ←/→ (J/L), ↑/↓ volumen, F, N/P, Esc.
- **Pantalla completa** sobre la ventana principal; se mantiene con el autoplay. **PiP**: la ventana principal pasa a 480 px, siempre encima, en la esquina inferior derecha; clic = play/pausa, doble clic o Esc = volver.
- **Cerrar la ventana** con un vídeo abierto guarda la posición antes de salir.
- mpv no lee la configuración del usuario (`config=no`), no carga scripts ni usa red (`ytdl=no`), ni carga subtítulos o audios externos por su cuenta.

**Dos trampas de convivir con AWT en el mismo proceso** (resueltas y con test):
- *Locale numérico*: mpv necesita `LC_NUMERIC=C` durante toda su vida, pero AWT vuelve a fijar el locale del sistema al arrancar; con `es_ES` mpv leía "1.5" como 1 y "16,2" no se podía leer. El binding lo reafirma antes de cada llamada.
- *Manejador de errores de Xlib*: es global al proceso. Al cerrar su salida de vídeo, mpv deja el manejador por defecto de Xlib, que **termina el proceso** ante cualquier error X; el siguiente error inocuo de AWT cerraba la app. El de AWT (que nunca termina) se restaura bajo el cerrojo de AWT cada vez que mpv arranca o cierra su salida.

No incluido: **Cast** (opcional en la spec; requiere servicios de Google y la app es sin cuenta de Google).

## Formatos verificados

MP4 (H.264/AAC), MKV, WebM (VP9/Opus), MOV, AVI (H.264/MP3) y HLS (`.m3u8` + segmentos `.ts`), en Android (Media3) y Linux (libmpv). Clips en [`client/testdata/media`](../client/testdata/media).

## Tests

- `PlaybackControllerTest` (JVM, motor falso + SQLite real): reanudar/preguntar, guardado al pausar, fin → completado + siguiente, recurso siguiente, autoplay desactivado, intro/outro, A-B, marcadores, velocidad y subtítulos.
- `Media3EngineTest` (dispositivo): los seis formatos hasta el final, subtítulos por idioma (apagados al inicio), velocidad y seek.
- `PlayerUiTest` (dispositivo, app real): reanudar desde "Continuar viendo", subtítulo ES visible, 1.5x, bucle A-B, captura guardada en la galería, guardado de la posición al salir, PiP sin cortar la reproducción.

- `MpvEngineTest` (JVM, libmpv sin salida de vídeo ni audio): los seis formatos, rutas con espacios y símbolos, subtítulos por idioma, inicio en una posición, velocidad, seek, órdenes antes de que exista la ventana y números con un locale de coma decimal.
- `PlayerHarness` (Linux, app real en una ventana X11 de Xvfb, sin audio): reanudar, capa de controles alineada (también en pantalla completa y PiP), PiP siempre encima, captura, guardado al salir y al cerrar la ventana. Local: `spikes/safe-run.sh virtual 120 desktop-player`; también en CI.

Ningún test reproduce audio: los clips llevan un tono de 440 Hz (`ao=null` en libmpv, `-no-audio` en el emulador).

El emulador de CI no tiene GPU: se arranca con `-feature -HardwareDecoder` para usar los decodificadores software (los `c2.goldfish.*` fallan sin GPU del host).
