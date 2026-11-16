# Racha

Self-hosted, offline-first app to watch and track your **local** courses, series and live-stream recordings — like a streaming or course platform, but for the files you already have. Android and Linux desktop clients, and an optional Spring Boot backend that syncs progress, settings and statistics between your devices. It **never** serves or uploads your media.

*App offline-first para ver y seguir tus cursos, series y directos **locales**: como una plataforma de cursos o de series, pero con los ficheros que ya tienes. Clientes Android y Linux, y un servidor opcional que sincroniza progreso, ajustes y estadísticas entre tus dispositivos. Nunca sirve ni sube tu contenido.*

> **Estado**: hitos H0–H12 completos (ver [`PROGRESS.md`](PROGRESS.md)). Licencia MIT.

## Para usarla

**Descarga** la última *release* de GitHub: `Racha-<versión>.apk` (Android 8+) o `Racha-<versión>-x86_64.AppImage` (Linux con glibc 2.39+). Instalación paso a paso en [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md).

1. **Primer uso**: elige idioma y modo (Estudio u Ocio) y añade las carpetas de cada categoría (Cursos, Series, Directos o las tuyas). Racha solo las lee: no mueve, renombra ni sube nada.
2. **Racha organiza** cada carpeta sola: detecta cursos y secciones por la numeración (`001 …`), temporadas y episodios (`S01E02`), directos por fecha, subtítulos `_es.srt`, y páginas HTML, enlaces, textos y PDF como parte del curso. Lo que desaparece se archiva con su progreso y vuelve si reaparece.
3. **Inicio** te muestra qué continuar, qué toca hoy y las novedades. El **reproductor** reanuda donde lo dejaste y tiene gestos, velocidades, subtítulos, PiP, bucle A-B, marcadores con nota, capturas y la lista del curso.
4. **Progreso** enseña tu tiempo, tus rachas, un año de actividad y lo completado; en Android, también un **widget** para la pantalla de inicio.
5. **Varios dispositivos** (opcional): monta tu servidor (`docker compose up -d`, ver la guía) y entra desde *Cuenta* en cada dispositivo. El progreso de un móvil aparece en el ordenador y al revés; si un ajuste cambia en los dos, Racha te pregunta.
6. **Ajustes** cubre todo lo demás: idioma, tema, color, tamaño, categorías, gestos, notificaciones (novedades, recordatorio diario), exportar/importar la configuración y más.

Atajos en Linux: `Ctrl+K` buscar, `Ctrl+R` escanear; en el reproductor, espacio, ←/→, ↑/↓, F, N/P, Esc. Los enlaces `racha://item/<id>` y `racha://play/<id>` abren un curso o un elemento (en Linux tras `Racha.AppImage --install`).

## Para desarrollar

Requisitos: JDK 25, Docker, Android SDK (plataforma 37) y, para el reproductor de Linux, `libmpv` (mpv ≥ 0.37).

| Ruta | Qué |
|---|---|
| [`client/`](client) | Kotlin Multiplatform + Compose Multiplatform: `shared/{domain,application,data,files,remote,ui}`, `android`, `desktop` |
| [`server/`](server) | Spring Boot 4 + PostgreSQL 18 (Java 25), hexagonal por contexto |
| [`design/`](design) | Design tokens (JSON) y marca |
| [`docs/`](docs) | [Especificación](docs/ESPECIFICACION.md) · [Arquitectura](docs/ARCHITECTURE.md) · [ADRs](docs/adr/README.md) · [Diseño](docs/DISENO.md) · [Despliegue](docs/DESPLIEGUE.md) · [API (OpenAPI)](docs/api/openapi.json) |

Guías por área: [escaneo](docs/ESCANEO.md), [modelo de datos](docs/MODELO-DATOS.md), [reproductor](docs/REPRODUCTOR.md), [recursos](docs/RECURSOS.md), [servidor](docs/SERVIDOR.md), [sync](docs/SYNC.md), [estadísticas](docs/ESTADISTICAS.md), [ajustes, notificaciones, widget y deep links](docs/AJUSTES.md).

```bash
docker compose up -d --build                 # servidor en http://localhost:8080 (salud en /actuator/health)
```

```bash
cd client && ./gradlew :desktop:run          # app de escritorio
```

```bash
cd client && ./gradlew :android:installDebug # app Android en un dispositivo o emulador
```

Tests:

```bash
cd server && ./gradlew build                                        # unitarios, ArchUnit y API contra PostgreSQL (Testcontainers)
```

```bash
cd client && ./gradlew check                                        # dominio, datos (SQLite real), UI de escritorio, adaptadores
```

```bash
cd client && ./gradlew :android:connectedDebugAndroidTest           # en un emulador o dispositivo
```

```bash
RACHA_E2E_URL=http://localhost:8080 ./gradlew -p client :shared:remote:jvmTest --rerun   # sync de punta a punta contra el servidor
```

Las pruebas que reproducen vídeo nunca emiten sonido (`ao=null`, emulador `-no-audio`), y las de interfaz en Linux corren en un display virtual (`client/spikes/safe-run.sh`).

## Contribuir

Trunk-based: ramas cortas, [Conventional Commits](https://www.conventionalcommits.org/) y CI en verde antes de integrar en `main` ([ADR-0007](docs/adr/0007-trunk-based.md)). Una versión es un tag `vX.Y.Z`: CI publica el AppImage y el APK.

## Licencia

[MIT](LICENSE)
