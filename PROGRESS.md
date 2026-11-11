# Progreso

Fuente de verdad: [`docs/ESPECIFICACION.md`](docs/ESPECIFICACION.md). Decisiones: [`docs/adr/`](docs/adr/README.md).

## Estado de hitos

| Hito | Objetivo | Estado |
|---|---|---|
| H0 | Fundaciones y walking skeleton | ✅ Cerrado |
| H1 | Spike Linux: presentación de vídeo con libmpv, JavaFX WebView, metadatos/frames | ✅ Cerrado |
| H2 | Modelo local (SQLDelight), migraciones, `contentId`, ajustes tipados | Pendiente |
| H3 | Escaneo e indexación (heurísticas, incremental, watcher, archivado/revinculación, miniaturas) | Pendiente |
| H4 | Sistema de diseño, navegación, Home/Detalle/Mi lista/Historial/Búsqueda, i18n, modos | Pendiente |
| H5 | Reproductor Android (Media3) + UI de reproductor compartida | Pendiente |
| H6 | Reproductor Linux (libmpv) | Pendiente |
| H7 | Visor de recursos HTML/URL/TXT/PDF | Pendiente |
| H8 | Backend: auth email/JWT, modelo remoto, endpoints §7.3, OpenAPI | Pendiente |
| H9 | Sync extremo a extremo y conflictos | Pendiente |
| H10 | Estadísticas y telemetría | Pendiente |
| H11 | Ajustes completos, notificaciones, widget, deep links, export/import, onboarding | Pendiente |
| H12 | Pulido UI/UX, empaquetado AppImage/APK firmado, entrega | Pendiente |

## Criterios de aceptación (§12)

| # | Criterio | Hito previsto | Estado |
|---|---|---|---|
| 1 | Escaneo de la estructura de ejemplo | H3 | ⬜ |
| 2 | Home con "Continuar viendo" y pestaña principal | H4 | ⬜ |
| 3 | Reproductor Android y Linux completo | H5, H6 | ⬜ |
| 4 | Offline al 100 % | H5, H9 | ⬜ |
| 5 | Sync ≤5 min / al recuperar red, conflictos | H9 | ⬜ |
| 6 | Recurso HTML como elemento del curso | H7 | ⬜ |
| 7 | IDs estables, archivado | H3 | ⬜ |
| 8 | Estadísticas persistidas en servidor | H10 | ⬜ |
| 9 | Auth (email/password + JWT; OAuth aplazado, ADR-0006) | H8, H9 | ⬜ |
| 10 | Idiomas es/en y subtítulos por idioma | H4, H5 | ⬜ |
| 11 | Modo Estudio/Ocio | H4 | ⬜ |
| 12 | Diseño con identidad propia | H4, H12 | ⬜ |

## Alcance añadido o ajustado por el usuario (2026-11-11)

- Marcado manual visto/no visto por elemento, sección, curso y "todo hasta aquí" (cursos importados a medias) → H4/H5.
- Botón de pantalla completa en el reproductor; el modo se mantiene al pasar al siguiente elemento con autoplay → H5/H6.
- Categorías sin tipo: todas las heurísticas se aplican en cascada a cada carpeta; `LibraryItem.kind` describe la estructura detectada → H3.
- Visor PDF integrado → H7.
- Gestos en Linux: clic/doble clic en bordes ±N s, arrastre horizontal = seek, rueda/arrastre vertical derecho = volumen, arrastre vertical izquierdo = brillo del vídeo, mantener = 2x → H6.
- OAuth aplazado; OTP por email posible más adelante (ADR-0006).
- Versiones más recientes en lugar de las citadas en la spec (ADR-0001).

---

## H1 · Spike Linux — ✅ cerrado

Informe completo con cifras: [docs/spikes/h1-linux.md](docs/spikes/h1-linux.md).

**Hecho**
- Bindings FFM de libmpv (API cliente y de render), sin JNA ni JNI.
- Medidas en hardware real (1080p30 H.264, % de un núcleo): ventana nativa de mpv 11,5 %; textura GL dentro de Compose ~45 %; render software ~118 %; **salida nativa incrustada + UI en ventana superpuesta 17,6 % con 0 frames perdidos** (elegida).
- Visor HTML: JavaFX WebView + servidor local limitado al curso; 9/9 comprobaciones (funcionales y de bloqueo de salida del curso), reproducibles con un fixture en el repo.
- Metadatos y miniatura WebP con libmpv sin ventana.
- `client/spikes/safe-run.sh`: lanzador con límites para cualquier prueba gráfica.

**Decisiones**
- [ADR-0011](docs/adr/0011-presentacion-video-linux.md) sustituye la parte Linux de ADR-0002: mpv presenta en ventana hija (`wid`), UI Compose en ventana transparente superpuesta; decodificación por hardware desactivada por defecto en Linux (opción experimental en Ajustes).
- ADR-0003 y ADR-0008 validados.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Vía elegida en escritorio real (15 s medidos) | ✅ 17,6 % CPU, 0 frames perdidos, 0 retrasados; transparencia y alineación confirmadas visualmente por el usuario |
| Visor HTML en Xvfb vía `safe-run.sh` | ✅ 9/9 |
| Metadatos + miniatura (3 ejecuciones) | ✅ |
| CI de la rama | ver commit de cierre |

**Incidente**: una batería de pruebas GPU bloqueó el escritorio del usuario (decodificación VA-API con interop GL). Causa probable documentada en el informe; medidas: `safe-run.sh`, pruebas en escritorio real solo con permiso y una a una, VA-API desactivado por defecto.

**Pendiente / pasa a otros hitos**
- H3: reutilizar instancia de libmpv para metadatos (hoy ~320 ms/fichero) o usar `ffprobe` si existe.
- H6: sincronizar la capa de UI en pantalla completa, PiP y multimonitor; test automático de alineación; alternativa JAWT/FFM al uso de internos `sun.awt`.
- H7: inicializar JavaFX en segundo plano (primer arranque 5,7 s); vigilar `BufferOverflowException` transitorias de `JFXPanel`.
- Criterios §12: ninguno se cierra en este hito (es un spike de riesgos); desbloquea 3 (Linux) y 6.

**Bloqueos**: ninguno.

---

## H0 · Fundaciones y walking skeleton — ✅ cerrado

**Hecho**
- Monorepo: `client/` (KMP: `shared/domain`, `shared/application`, `shared/ui`, `android`, `desktop`), `server/`, `docs/`, `docker-compose.yml`, licencia MIT, README.
- Arquitectura hexagonal establecida: límites de módulo Gradle en el cliente; reglas ArchUnit en el servidor ([ARCHITECTURE.md](docs/ARCHITECTURE.md)).
- Walking skeleton que atraviesa capas (UI → caso de uso `DescribeEnvironment` → puerto `PlatformInfo` → adaptador de plataforma) en Android y Linux.
- Servidor Spring Boot 4.1.2 / Java 25 con Flyway, actuator y Testcontainers (PostgreSQL 18); imagen Docker no-root.
- CI en GitHub Actions (servidor: build, tests, imagen Docker; cliente: tests, APK debug, jar desktop) y Dependabot.
- Spec copiada a `docs/ESPECIFICACION.md`; 10 ADRs con las decisiones de §13 y las del arranque.

**Verificación**
| Comprobación | Resultado |
|---|---|
| `cd server && ./gradlew build` | ✅ 3 tests (health con PostgreSQL 18 real + 2 reglas de arquitectura) |
| `docker compose up -d --build` + `curl localhost:8080/actuator/health` | ✅ `{"status":"UP"}` |
| `cd client && ./gradlew check :android:assembleDebug :desktop:jar` | ✅ |
| APK en emulador Android 17 (API 37) | ✅ [captura](docs/evidence/h0/android-hello.png) |
| App de escritorio en Linux (Xvfb) | ✅ [captura](docs/evidence/h0/desktop-hello.png) |
| CI en GitHub (servidor + cliente) | ✅ [run 43083387805](https://github.com/roberbarras/racha/actions/runs/43083387805) |

**Pendiente / notas**
- Lint Android: `MissingApplicationIcon` (llega con el sistema de diseño, H4) y `ObsoleteLintCustomCheck` (de una dependencia AndroidX, no nuestro).
- Gradle avisa de una deprecación (`Configuration.setVisible`) que proviene de un plugin de terceros.
- Xvfb no ofrece contexto GL: Skia cae a render por software. Las medidas de rendimiento de vídeo del H1 deben hacerse en la sesión gráfica real.

**Bloqueos**: ninguno.
