# ADR-0009 · Empaquetado y distribución

**Estado**: aceptada.

**Decisión**
- **Linux**: AppImage x86_64 construido en CI (base glibc antigua), con JRE 25 recortado por jlink y libmpv/FFmpeg LGPL. Registra el esquema `racha://` para deep links.
- **Android**: APK universal firmado en CI (keystore en *secrets*, nunca en el repo); debug firmado localmente.

**Descartado**: Flatpak en esta entrega (el sandbox choca con leer carpetas arbitrarias e inotify; requeriría `--filesystem=host`); AAB (solo útil en Play, fuera de alcance).

**Precisión (H12, 2026-11-16)**:
- El AppImage se construye en **Ubuntu 24.04** (glibc 2.39) en lugar de una base antigua: compilar mpv y FFmpeg sobre una base vieja exigiría mantener esa cadena de compilación entera. Funciona en distribuciones de 2024 en adelante. libmpv y sus bibliotecas se recogen con linuxdeploy, que deja fuera lo que debe venir del sistema (glibc, GL/Vulkan, X11/Wayland, ALSA…). `--self-test` lo comprueba en CI.
- `racha://` se registra con `Racha.AppImage --install` (entrada del menú y manejador del esquema en `~/.local/share`), o con la integración de AppImage del escritorio.
- Android: la release usa R8; la clave llega por variables de entorno (en CI, de los *secrets*) y nunca está en el repositorio. Sin ella, la release se firma con la clave de depuración y CI lo avisa.
- Las versiones se publican desde un tag `vX.Y.Z`: CI crea la *release* de GitHub con el AppImage y el APK. Guía: [DESPLIEGUE.md](../DESPLIEGUE.md).
