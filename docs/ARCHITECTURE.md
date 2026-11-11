# Arquitectura de Racha

> Documento vivo. Las decisiones con contexto y alternativas están en [`docs/adr/`](adr/). La especificación de producto es [`ESPECIFICACION.md`](ESPECIFICACION.md).

## 1. Vista general

```
racha/
├── client/                  Kotlin Multiplatform + Compose Multiplatform
│   ├── shared/domain        Núcleo del hexágono: modelo, reglas, políticas. Kotlin puro.
│   ├── shared/application   Casos de uso (puertos de entrada) y puertos de salida.
│   ├── shared/ui            Compose común: adaptador de entrada (driving) para ambas apps.
│   ├── android              App Android: raíz de composición + adaptadores de plataforma
│   │                        (Media3, WebView, almacenamiento, WorkManager, widget).
│   └── desktop              App JVM de escritorio (Linux hoy; Windows/macOS posibles):
│                            raíz de composición + adaptadores (libmpv vía FFM, JavaFX WebView…).
├── server/                  Spring Boot 4 + PostgreSQL 18, hexágono por bounded context.
├── design/                  Design tokens (JSON) compartidos.
└── docs/                    Especificación, arquitectura, ADRs, evidencias por hito.
```

El servidor **nunca sirve contenido multimedia**: solo JSON de progreso, ajustes, estadísticas y sync (spec §0, §7).

## 2. Arquitectura hexagonal (puertos y adaptadores)

Regla de dependencias: **todo apunta hacia el dominio**.

```
 adaptadores de entrada            núcleo                     adaptadores de salida
 (UI Compose, REST, workers) ──▶ application ──▶ domain ◀── (SQLDelight/JPA, Ktor, Media3,
                                  (casos de uso,  (modelo,      libmpv, sistema de ficheros…)
                                   puertos)        políticas)        implementan puertos
```

- **domain**: entidades, value objects (`ContentId`, `RelativePath`, `PlaybackPosition`…), agregados y políticas de dominio (heurísticas de escaneo, resolución de conflictos, rachas). Sin frameworks, sin E/S, sin plataforma.
- **application**: un caso de uso por intención del usuario (`ScanLibrary`, `SaveProgress`, `MarkWatched`, `SyncNow`…). Define los **puertos de salida** que necesita (`LibrarySource`, `MediaProbe`, `PlayerEngine`, `ProgressRepository`, `SyncApi`, `Clock`…).
- **adaptadores**: implementan puertos. Viven en el módulo de plataforma o en módulos `shared/*` específicos de tecnología cuando son comunes (persistencia SQLDelight, red Ktor).
- **Raíz de composición**: cada app (`MainActivity`, `desktop/Main.kt`, `RachaApplication`) cablea adaptadores → casos de uso. En el cliente se hace **a mano**, sin framework de DI: cero reflexión y cero coste de arranque (batería, §ADR-0001).

### Cómo se hace cumplir

| Dónde | Mecanismo |
|---|---|
| Cliente | **Límites de módulo Gradle**: `shared:domain` no depende de nada; `shared:application` solo de `domain`; nadie depende de `android`/`desktop`. Un import prohibido no compila. |
| Servidor | **ArchUnit** (`HexagonalArchitectureTest`): `domain` no depende de `application` ni de adaptadores ni de Spring/JPA/Jackson; los adaptadores no se conocen entre sí. |

Paquetes del servidor por contexto: `io.github.roberbarras.racha.<contexto>.{domain,application,adapter.web,adapter.persistence,adapter.security}`. Las entidades JPA son adaptadores (`adapter.persistence`) y se mapean al modelo de dominio; el dominio no lleva anotaciones.

## 3. DDD: bounded contexts

DDD **táctico donde hay reglas de negocio reales**; CRUD ligero donde no las hay (evitamos ceremonia sin valor).

| Contexto | Cliente | Servidor | Riqueza de dominio |
|---|---|---|---|
| **Library** (catálogo): Category, CategoryRoot, LibraryItem, Section, PlayableElement, Subtitle | ✔ | ✔ (espejo para sync) | Alta: heurísticas de escaneo en cascada, `contentId`, archivado/revinculación por `fileHash`. |
| **Learning** (progreso): Progress, Bookmark, HistoryEntry, Favorite, rachas | ✔ | ✔ | Alta: completado, `maxPositionSec`, marcado manual, rachas. |
| **Playback** (sesión de reproducción) | ✔ | — | Media: A-B, velocidad, intro/outro, autoplay, guardado periódico. |
| **Preferences** (ajustes, modos Estudio/Ocio) | ✔ | ✔ | Baja; conflicto de config = pregunta al usuario. |
| **Identity** (usuarios, sesiones, dispositivos) | parcial | ✔ | Media en servidor (credenciales, refresh rotativo). |
| **Sync** (cola, push/pull, conflictos) | ✔ | ✔ | Alta: last-writer-wins por `updatedAt` + reglas de §6.3. |
| **Telemetry** (eventos, errores, estadísticas) | emisor | ✔ | Baja en reglas, alta en volumen. |

**Lenguaje ubicuo**: el código está en inglés; glosario es→en:

| Español | Código |
|---|---|
| Categoría / ruta raíz | `Category` / `CategoryRoot` |
| Curso, serie, directo (estructura detectada) | `LibraryItem` + `StructureKind` |
| Sección / temporada / grupo por fecha | `Section` |
| Elemento (vídeo o recurso) | `PlayableElement` |
| Recurso (HTML/URL/TXT/PDF) | `Resource` |
| Marcador | `Bookmark` |
| Racha | `Streak` |
| Modo Estudio / Ocio | `Mode.STUDY` / `Mode.LEISURE` |

**Reglas compartidas cliente↔servidor**: el servidor es Java (spec §7.1) y el cliente Kotlin, así que la política de conflictos se implementa en ambos lados. Para que no diverjan, sus casos de prueba vivirán como **vectores JSON compartidos** (`contracts/`) que ejecutan las dos suites de tests.

## 4. Principios de rendimiento y batería (prioridad del producto)

1. **Decodificación y presentación por hardware**: Media3 + MediaCodec sobre `SurfaceView` (composición por overlay del hardware, más eficiente que `TextureView`); en Linux, libmpv con `hwdec` y render API OpenGL sin copias (ADR-0002).
2. **Nada de sondeo**: la UI de posición se refresca solo con los controles visibles; la sync usa WorkManager con restricción de red y disparos por evento (pausa/salida/fin), no temporizadores en segundo plano.
3. **Trabajo pesado con restricciones**: `fileHash` (SHA-256 de ficheros grandes) y miniaturas en WorkManager, preferentemente cargando/inactivo; escaneo incremental por mtime.
4. **Escrituras mínimas**: el progreso vive en memoria y se persiste en el intervalo configurado y en pausa/salida/fin.
5. **Sin reflexión en caliente**: DI manual, serialización con kotlinx.serialization (generada en compilación), R8 en release, Baseline Profiles.

## 5. Flujo de trabajo

Trunk-based development (ADR-0007): `main` siempre verde, ramas cortas (`feat/…`, `fix/…`) integradas tras CI verde, versiones con tag `vX.Y.Z` desde `main`, cierre de hito con tag `milestone/hN`.
