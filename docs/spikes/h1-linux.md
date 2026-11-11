# Spike H1 · Vídeo, recursos HTML y metadatos en Linux

**Fecha**: 2026-11-11 · **Código**: [`client/spikes/`](../../client/spikes) (no se distribuye) · **Decisiones**: [ADR-0011](../adr/0011-presentacion-video-linux.md), [ADR-0003](../adr/0003-html-local-seguro.md), [ADR-0008](../adr/0008-miniaturas-y-metadatos.md)

## Objetivo

Antes de construir el reproductor de escritorio (H6) y el visor de recursos (H7), validar en hardware real:

1. Cómo mostrar vídeo de libmpv dentro de una app Compose Desktop **con nuestra UI encima**, al menor coste de CPU/batería.
2. Que un `.html` local con CSS/JS/imágenes relativos funciona en un WebKit embebido **sin poder leer fuera de su curso**.
3. Que libmpv sin ventana extrae metadatos y una miniatura.

**Equipo de medida**: portátil Intel Core i7-1165G7, Iris Xe (Mesa 26.2.2, VA-API `iHD`), openSUSE Tumbleweed, KDE Plasma Wayland (las apps JVM corren en XWayland). JDK 25, libmpv 0.41.
**Vídeo**: `001 Introduction and Course Outline.mp4` del usuario (H.264 High 1080p30, 2,1 Mb/s, AAC); no está en el repo.
**Métrica**: CPU del proceso completo (JVM + Compose + hilos de mpv) en % de **un núcleo**, ventana de 1280×720.

## 1. Presentación de vídeo

| Vía | CPU | Frames perdidos | UI encima del vídeo | Notas |
|---|---|---|---|---|
| Ventana propia de mpv, decodificación `vaapi` (referencia) | 11,5 % ¹ | 0 | ✗ | Lo mínimo posible; sin nuestra UI. |
| Ventana propia de mpv, `vaapi-copy` | 30,1 % ¹ | 0 | ✗ | Aísla el coste de copiar cada frame decodificado a RAM. |
| **A** · mpv en textura GL dibujada por Skia/Compose ("sin copias") | 42–55 % ¹ | 0 | ✓ | Skiko solo usa GLX en Linux → libmpv no puede hacer interop VA-API (requiere EGL) → `vaapi-copy` forzado; +~15 puntos de redibujado de Compose. Requiere tocar internos de Skiko por reflexión. |
| **B** · render por software de mpv a bitmap Skia | 117,8 % ¹ | — | ✓ | Además pierde frames de pantalla (25,6 dibujos/s para vídeo a 30 fps). |
| **C** · salida nativa de mpv incrustada (`wid`) + UI Compose en ventana transparente superpuesta, decodificación software | **17,6 %** ² | **0** (0 retrasados) | ✓ | **Elegida.** Presentación por GPU propia de mpv (`gpu-next`). |
| C con decodificación `vaapi` | — | — | — | **Se colgó** (ver §4). |
| Compose en reposo (sin vídeo) | 0,1 % ³ | — | — | Base. |

¹ pasadas de 5–10 s. ² 15 s medidos tras 8 s de calentamiento. ³ 60 s.

**Conclusiones**
- Lo que gasta no es decodificar sino **cómo llega el frame a pantalla**. Meter el vídeo en el pipeline de Compose (A, B) cuesta de 2,5× a 7× más que dejar que mpv presente por su cuenta.
- En este equipo, a 1080p H.264, **decodificar por software (17,6 %) es más barato que `vaapi-copy` (30 %)**: la copia GPU→RAM pesa más que el propio decodificado.
- La vía C mantiene la identidad visual (nuestra UI, barra lateral y gestos en la capa superpuesta) con el coste más bajo de las opciones estables.

**Detalles de la vía C** (`client/spikes/linux-video/src/main/kotlin/.../Main.kt`)
- Un `java.awt.Canvas` (componente pesado, ventana X11 propia) dentro de un `SwingPanel`; su id de ventana X11 se pasa a mpv como `wid` (se lee con `sun.awt` vía `--add-exports`).
- La UI vive en un `DialogWindow` sin decoración y transparente, **propiedad** de la ventana del reproductor (el gestor de ventanas lo mantiene encima) y sincronizado con la posición y tamaño del lienzo.
- La entrada de ratón/gestos la recibe la capa superpuesta; mpv tiene la entrada desactivada.
- Validado: en Xvfb, mpv incrustado y la capa alineada (mismas coordenadas); en el escritorio real, el usuario confirmó visualmente transparencia y alineación.

**Riesgos que pasan a H6**
- Sincronizar la capa en pantalla completa, PiP y multimonitor.
- Las coordenadas que reporta X11 sugerían ~20 px de desfase vertical que el usuario no apreció: añadir una comprobación de alineación automatizada.
- `wid` es X11: funciona porque AWT corre sobre XWayland. Un futuro AWT nativo Wayland requeriría revisarlo.
- Obtener el id X11 usa internos del JDK (`--add-exports`); alternativa sin internos: JAWT vía FFM.

## 2. Recursos HTML (ADR-0003)

JavaFX 27 WebView (WebKit) dentro de Compose (`SwingPanel` + `JFXPanel`), contenido servido por `CourseFileServer` (HTTP en 127.0.0.1, puerto aleatorio, token de 128 bits, solo la carpeta del curso). Fixture: [`client/spikes/linux-webview/fixture`](../../client/spikes/linux-webview/fixture).

| Comprobación | Resultado |
|---|---|
| JavaScript del curso | ✅ ok |
| CSS relativo | ✅ ok |
| Imagen relativa | ✅ ok |
| Interacción del quiz | ✅ ok |
| Otro fichero del mismo curso | ✅ ok |
| `file:///etc/passwd` | 🔒 bloqueado |
| Salida con `%2e%2e` codificado | 🔒 404 |
| Otro curso (`../../Other Course/…`) | 🔒 404 |
| Ruta absoluta (`//etc/passwd`) | 🔒 404 |

Reproducible: `client/spikes/safe-run.sh virtual 60 linux-webview "<ruta>/fixture/AWS Course" "01 - Introduction/003 AWS Account Activation Troubleshooting.html"`.

**Riesgos que pasan a H7**
- Primer arranque lento (5,7 s en escritorio real, 16 s en Xvfb, incluye inicializar la JVM y JavaFX): inicializar JavaFX en segundo plano al abrir un curso.
- `JFXPanel` lanza `BufferOverflowException` transitorias al redimensionar y copia píxeles a Swing por CPU en cada repintado (irrelevante para HTML estático; no usar para contenido animado).
- Memoria: ~410–430 MiB de RSS con WebView cargado.

## 3. Metadatos y miniaturas con libmpv (ADR-0008)

| Operación | libmpv | ffmpeg CLI (referencia) |
|---|---|---|
| Metadatos (duración, resolución, fps, códecs, pistas e idiomas) | 318–414 ms (instancia nueva por fichero) | `ffprobe`: 30 ms |
| Miniatura WebP 480 px al 10 % | 130–253 ms | `ffmpeg`: 120 ms |

- Miniatura de 12 KB, correcta. Metadatos completos (incluido el idioma `spa` de una pista de audio).
- El coste de metadatos lo domina crear e inicializar mpv: en H3 se reutilizará una instancia para todo el escaneo y se medirá; si no baja de ~100 ms/fichero, se usará `ffprobe` cuando esté disponible.
- Hallazgo: **libmpv no arranca con un locale de coma decimal** (`es_ES`): hay que fijar `LC_NUMERIC=C` (vía `setlocale` con FFM) antes de `mpv_create`.

## 4. Incidente: bloqueo del escritorio

- 20:29 — una batería de mediciones de 60 s en el escritorio real se colgó en la ejecución "ventana propia de mpv + `vaapi`". Se interpretó erróneamente como monitor en ahorro de energía y se lanzaron ~7 ejecuciones más. El sistema quedó bloqueado y hubo que reiniciar (21:55).
- 23:00 — la vía C con `vaapi` se volvió a colgar; esta vez el limitador la mató a los 53 s y el escritorio siguió funcionando.
- Común a ambos: **mpv presentando por GPU + decodificación VA-API con interop GL**. Con decodificación software no hubo ningún problema. No se pudo leer el log del kernel (sin permisos) para confirmar un cuelgue de GPU.

**Medidas adoptadas**
- [`client/spikes/safe-run.sh`](../../client/spikes/safe-run.sh): una sola JVM sin hijos de Gradle, en un *scope* de systemd con límites de memoria/CPU y `timeout` con `SIGKILL`; Xvfb por defecto; comprueba que no quede nada vivo.
- Pruebas en el escritorio real solo con permiso explícito, de una en una, y se detiene todo al primer cuelgue.
- Producto: decodificación por hardware **desactivada por defecto en Linux** (ADR-0011).
