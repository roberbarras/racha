# ADR-0011 · Presentación de vídeo en Linux

**Estado**: aceptada (2026-11-11). **Sustituye** la parte Linux de [ADR-0002](0002-motor-video-linux.md). Evidencia: [spike H1](../spikes/h1-linux.md).

**Contexto**: ADR-0002 eligió libmpv con su render API OpenGL dibujando dentro de Compose. El spike H1 lo midió en hardware real: funciona, pero cuesta ~45 % de un núcleo a 1080p30 (Skiko solo usa GLX y obliga a copiar cada frame, más el redibujado de Compose) y depende de internos de Skiko. La salida nativa de mpv con nuestra UI en una ventana superpuesta cuesta 17,6 % sin perder frames. Además, la decodificación VA-API con interop GL colgó dos veces el escritorio de pruebas.

**Decisión**
- libmpv (enlazado con FFM) **presenta por sí mismo** (`vo=gpu-next`) en una ventana X11 hija del reproductor (`wid`), sin pasar frames por Compose.
- La UI del reproductor (controles, barra lateral, gestos) es Compose en una **ventana transparente sin decoración, propiedad del reproductor**, sincronizada con el área de vídeo; recibe toda la entrada.
- **Decodificación por hardware desactivada por defecto** (`hwdec=no`, que es también el valor por defecto de mpv). Ajustes → Reproductor ofrece activarla como opción experimental.
- Las ejecuciones gráficas de prueba pasan por `client/spikes/safe-run.sh`.

**Descartado**
- *Textura GL dentro de Compose*: 2,5× la CPU, internos de Skiko, sospechoso en el incidente.
- *Render por software a bitmap*: 7× la CPU y pierde frames.
- *VA-API por defecto*: inestable en el equipo de pruebas; `vaapi-copy` además gasta más que decodificar por software a 1080p.

**Consecuencias**: hay que gestionar dos ventanas (posición, pantalla completa, PiP, multimonitor) en H6. `wid` depende de X11 (AWT corre en XWayland). Android no cambia: Media3 + MediaCodec + `SurfaceView`.

**Aprendido en H6 (2026-11-13)**: tener libmpv y AWT en el mismo proceso exige dos salvaguardas, ambas con test: reafirmar `LC_NUMERIC=C` antes de cada llamada a mpv (AWT lo cambia) y restaurar el manejador de errores de Xlib de AWT bajo su cerrojo cada vez que la salida de vídeo de mpv arranca o se cierra (mpv deja el manejador por defecto, que termina el proceso). Si apareciera otro problema de convivencia, la alternativa es ejecutar mpv en un proceso aparte (`--wid` + IPC JSON), que además aislaría un cuelgue de GPU.
