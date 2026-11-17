# Arquitectura de Racha

> Documento vivo. Las decisiones con contexto y alternativas están en [`docs/adr/`](adr/README.md). La especificación de producto es [`ESPECIFICACION.md`](ESPECIFICACION.md).

## 1. Vista general

```
racha/
├── client/                  Kotlin Multiplatform + Compose Multiplatform
│   ├── shared/domain        Núcleo del hexágono: modelo, reglas, políticas. Kotlin puro, sin E/S.
│   ├── shared/application   Casos de uso (puertos de entrada) y puertos de salida.
│   ├── shared/data          Adaptador de persistencia: SQLDelight (SQLite), outbox de sync, telemetría.
│   ├── shared/files         Adaptadores de ficheros JVM (Android y Linux): escaneo, hash, recursos, cachés.
│   ├── shared/remote        Adaptador HTTP (Ktor + OkHttp): cuenta, sync, telemetría, estadísticas, IA de portadas.
│   ├── shared/ui            Compose común: adaptador de entrada (driving) para ambas apps.
│   ├── android              App Android: raíz de composición + adaptadores de plataforma
│   │                        (Media3, WebView, Keystore, WorkManager, notificaciones, widget).
│   └── desktop              App JVM de escritorio (Linux): raíz de composición + adaptadores
│                            (libmpv vía FFM, JavaFX WebView, PDFBox, libsecret, D-Bus, instancia única).
├── server/                  Spring Boot 4 + PostgreSQL 18, hexágono por bounded context.
├── design/                  Design tokens (JSON) compartidos y marca.
└── docs/                    Especificación, arquitectura, ADRs, guías, OpenAPI y evidencias por hito.
```

El servidor **nunca sirve contenido multimedia**: solo JSON de progreso, ajustes, estadísticas y sync (spec §0, §7).

## 2. Arquitectura hexagonal (puertos y adaptadores)

Regla de dependencias: **todo apunta hacia el dominio**.

```
 adaptadores de entrada            núcleo                     adaptadores de salida
 (UI Compose, REST, workers) ──▶ application ──▶ domain ◀── (SQLDelight, JdbcClient, Ktor, Media3,
                                  (casos de uso,  (modelo,      libmpv, ficheros, Keystore/libsecret…)
                                   puertos)        políticas)        implementan puertos
```

- **domain**: entidades, value objects (`ContentId`, `RelativePath`, `CalendarDate`…) y políticas (heurísticas de escaneo, reglas de reproducción, progreso que nunca baja, rachas, plegado de texto para búsqueda). Sin frameworks, sin E/S, sin plataforma.
- **application**: un caso de uso por intención del usuario (`ScanLibrary`, `MarkProgress`, `PlaybackController`, `SyncEngine`, `LoadPersonalStats`, `ConfigTransfer`…). Define los **puertos de salida** que necesita (`LibrarySource`, `MediaProbe`, `PlayerEngine`, `ProgressRepository`, `SyncServer`, `CredentialStore`, `Notifier`, `Clock`…).
- **adaptadores**: implementan puertos, en `shared/{data,files,remote}` cuando son comunes a las dos apps y en `android`/`desktop` cuando son de plataforma.
- **Raíz de composición**: `AndroidGraph` y `DesktopGraph` cablean adaptadores → casos de uso **a mano**, sin framework de DI: cero reflexión y cero coste de arranque (batería, ADR-0001).

### Cómo se hace cumplir

| Dónde | Mecanismo |
|---|---|
| Cliente | **Límites de módulo Gradle**: `shared:domain` no depende de nada; `shared:application` solo de `domain`; los adaptadores dependen de `application`; nadie depende de `android`/`desktop`. Un import prohibido no compila. |
| Servidor | **ArchUnit** (`HexagonalArchitectureTest`): `domain` no depende de `application` ni de adaptadores ni de Spring/Jackson; los adaptadores no se conocen entre sí; `config` es la única raíz de composición. |

Paquetes del servidor por contexto: `io.github.roberbarras.racha.<contexto>.{domain,application,adapter.web,adapter.persistence,adapter.security}`. La persistencia es **SQL explícito con `JdbcClient`** (sin JPA): las reglas de conflicto viven en la consulta y son atómicas (ADR-0012).

## 3. DDD: bounded contexts

DDD **táctico donde hay reglas de negocio reales**; CRUD ligero donde no las hay.

| Contexto | Cliente | Servidor | Riqueza de dominio |
|---|---|---|---|
| **Library** (catálogo): Category, CategoryRoot, LibraryItem, Section, PlayableElement, Subtitle | ✔ | ✔ (espejo para sync) | Alta: heurísticas de escaneo en cascada, `contentId`, archivado/revinculación por `fileHash`. |
| **Learning** (progreso): Progress, Bookmark, HistoryEntry, Favorite, rachas | ✔ | ✔ | Alta: completado, `maxPositionSec`, marcado manual, rachas. |
| **Playback** (sesión de reproducción) | ✔ | — | Media: A-B, velocidad, intro/outro, autoplay, guardado periódico. |
| **Preferences** (ajustes, modos Estudio/Ocio) | ✔ | ✔ | Baja; conflicto de config = pregunta al usuario. |
| **Identity** (usuarios, sesiones, dispositivos) | parcial | ✔ | Media en servidor (credenciales, refresh rotativo con detección de reutilización). |
| **Sync** (outbox, push/pull, conflictos) | ✔ | ✔ | Alta: gana el `updatedAt` más reciente + reglas de §6.3. |
| **Telemetry / Stats** (eventos, errores, estadísticas) | emisor | ✔ | Baja en reglas, alta en volumen. |

**Lenguaje ubicuo**: el código está en inglés; glosario es→en:

| Español | Código |
|---|---|
| Categoría / ruta raíz | `Category` / `CategoryRoot` |
| Curso, serie, directo (estructura detectada) | `LibraryItem` + `StructureKind` |
| Sección / temporada / grupo por fecha | `Section` |
| Elemento (vídeo o recurso) | `PlayableElement` |
| Recurso (HTML/URL/TXT/PDF) | `ElementKind.RESOURCE` + `ResourceType` |
| Marcador | `Bookmark` |
| Racha | `Streak` |
| Modo Estudio / Ocio | `Mode.STUDY` / `Mode.LEISURE` |

**Reglas compartidas cliente↔servidor**: el servidor es Java (spec §7.1) y el cliente Kotlin, así que la política de conflictos está en los dos lados, cada uno con sus tests (`SyncRulesTest` en el servidor, `SyncStoreTest` en el cliente). Lo que garantiza que coinciden es el **test de punta a punta** (`EndToEndSyncTest`): dos clientes reales contra el servidor real en Docker, en CI en cada push.

## 4. Principios de rendimiento y batería (prioridad del producto)

1. **Decodificación y presentación eficientes**: Media3 + MediaCodec sobre `SurfaceView` (overlay del hardware). En Linux, mpv presenta por su cuenta en una ventana hija y la UI va en una capa superpuesta: los fotogramas nunca pasan por Compose (ADR-0011). VA-API es opcional y está desactivado por defecto por estabilidad.
2. **Nada de sondeo**: la posición en pantalla se consulta solo con los controles visibles; la red se observa por callbacks en Android; la sync en segundo plano va por WorkManager con red y batería no baja; el widget no tiene trabajo propio (la app lo actualiza cuando cambia algo).
3. **Trabajo pesado acotado**: escaneo incremental por fecha/tamaño; `fileHash` solo de los elementos ya vistos; el texto de recursos solo se indexa si el fichero cambió; miniaturas y escaneo solo con la app visible en Android.
4. **Escrituras mínimas**: el progreso se persiste en el intervalo configurado y en pausa/salida/fin; la telemetría va a una cola acotada y se envía en lotes tras cada sync.
5. **Sin reflexión en caliente**: DI manual, kotlinx.serialization generado en compilación, R8 en la release de Android (probada con un *smoke test* sobre el APK minificado).

## 5. Decisiones del implementador (spec §13)

| Decisión | Elección | Dónde |
|---|---|---|
| Framework UI multiplataforma | Kotlin Multiplatform + Compose Multiplatform (dominio, casos de uso y UI compartidos) | [ADR-0001](adr/0001-stack-y-versiones.md) |
| Motor de vídeo en Linux e integración | libmpv por FFM (Java 25), salida nativa incrustada + UI en ventana superpuesta | [ADR-0002](adr/0002-motor-video-linux.md), [ADR-0011](adr/0011-presentacion-video-linux.md) |
| Miniaturas y metadatos | APIs nativas (Android `MediaMetadataRetriever`) y libmpv en Linux; sin FFmpeg embebido aparte | [ADR-0008](adr/0008-miniaturas-y-metadatos.md) |
| Proveedor OAuth | Aplazado por decisión del usuario: email + contraseña + JWT con refresh; esquema preparado | [ADR-0006](adr/0006-autenticacion.md) |
| Export/import de configuración | JSON versionado `racha-config` | [ADR-0010](adr/0010-formato-export-config.md) |
| Empaquetado Linux | AppImage con libmpv incluido (sin Flatpak: el sandbox choca con leer carpetas arbitrarias) | [ADR-0009](adr/0009-empaquetado-y-distribucion.md) |
| Distribución Android | APK universal firmado en CI (claves en *secrets*) | [ADR-0009](adr/0009-empaquetado-y-distribucion.md) |
| HTML local seguro | Servido solo desde la carpeta de su curso (WebViewAssetLoader / servidor local en Linux) | [ADR-0003](adr/0003-html-local-seguro.md) |
| Varias raíces por categoría sin colisiones | `contentId` con `categoryKey` + `rootKey` estables y sincronizados | [ADR-0004](adr/0004-contentid-y-multiples-raices.md) |
| Sync | Modelo remoto por entidad, "gana el más reciente" atómico en SQL, pull por cursor de secuencia | [ADR-0012](adr/0012-protocolo-sync-y-modelo-remoto.md) |
| Qué contiene una carpeta | El usuario elige biblioteca o un solo curso/serie al añadirla, con vista previa; cambiable sin perder progreso | [ADR-0013](adr/0013-que-contiene-una-carpeta.md) |

## 6. Flujo de trabajo

Trunk-based development (ADR-0007): `main` siempre verde, ramas cortas (`feat/…`, `fix/…`) integradas tras CI verde, cierre de hito con tag `milestone/hN`; una versión es un tag `vX.Y.Z` desde `main`, que hace que CI publique el AppImage y el APK firmado como *release* de GitHub.
