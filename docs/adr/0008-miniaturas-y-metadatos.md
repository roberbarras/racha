# ADR-0008 · Miniaturas y metadatos

**Estado**: aceptada; vía Linux validada en el [spike H1](../spikes/h1-linux.md). **Resuelto en H3**: con una instancia de libmpv reutilizada, los metadatos cuestan 28 ms/fichero de mediana (p90 35 ms) frente a 30 ms de `ffprobe`, así que no se usa `ffprobe`. Detalle en [ESCANEO.md](../ESCANEO.md).

**Decisión**
- Orden: `poster.jpg`/`folder.jpg`/`cover.jpg` → frame al ~10 % → placeholder tipográfico generado con los tokens → (opcional) IA de imágenes configurable.
- **Android**: `MetadataRetriever` de Media3 (pistas, codec, fps, bitrate, idioma) + `MediaMetadataRetriever.getFrameAtTime`.
- **Linux**: instancia libmpv sin ventana (ya es dependencia; usa libavformat/libavcodec). Respaldo: `ffprobe`/`ffmpeg` del PATH si existen.
- Caché por `contentId` + mtime, WebP ~480 px, generación perezosa con concurrencia limitada y restricciones de batería.

**Descartado**: ffmpeg-kit (archivado en 2025); ffmpeg CLI estático empaquetado (+~80 MB, procesos externos); JavaCV (pesado).
