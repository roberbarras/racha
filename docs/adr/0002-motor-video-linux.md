# ADR-0002 · Motor de vídeo

**Estado**: aceptada; la vía de render en Linux se valida con medidas en el Hito 1.

**Contexto**: máxima fluidez y mínimo consumo, con nuestra UI (controles, barra lateral, gestos) **encima** del vídeo (§4.2, §12.3, §12.12). VLC y mpv decodifican igual (FFmpeg + VA-API); la diferencia está en cómo llega el frame a pantalla.

**Decisión**
- **Android**: Media3/ExoPlayer, decodificación MediaCodec por hardware, superficie `SurfaceView`.
- **Linux**: **libmpv** con su **render API OpenGL** dibujando en una textura que compone Skia/Compose (sin copias GPU→CPU), enlazado con **FFM (java.lang.foreign, JDK 25)**, sin JNA ni JNI.
- Orden de alternativas si la anterior no cumple: (1) libmpv GL + lectura PBO (una copia), (2) libmpv incrustado por `wid` con controles en ventana superpuesta, (3) libVLC con bindings FFM propios.

**Descartado**
- *Lanzar VLC/mpv como ventana externa*: no sería nuestra UI (sin barra lateral, gestos, marcadores, A-B); incumple §4.2/§12.3/§12.12 y exige VLC instalado.
- *libVLC 3 con callbacks de memoria*: copia cada frame GPU→CPU→GPU (más CPU, crítico a 4K). VLC 4 (salida OpenGL) no tiene versión estable.
- *vlcj*: GPLv3 y mismas limitaciones de render que libVLC 3.

**Licencias**: el código del repo es MIT. libmpv/FFmpeg del sistema no se distribuyen (sin obligaciones). Si se empaquetan en el AppImage se usarán builds LGPL; el usuario acepta GPL si alguna vez aporta rendimiento.
