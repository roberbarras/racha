# ADR-0009 · Empaquetado y distribución

**Estado**: aceptada.

**Decisión**
- **Linux**: AppImage x86_64 construido en CI (base glibc antigua), con JRE 25 recortado por jlink y libmpv/FFmpeg LGPL. Registra el esquema `racha://` para deep links.
- **Android**: APK universal firmado en CI (keystore en *secrets*, nunca en el repo); debug firmado localmente.

**Descartado**: Flatpak en esta entrega (el sandbox choca con leer carpetas arbitrarias e inotify; requeriría `--filesystem=host`); AAB (solo útil en Play, fuera de alcance).
