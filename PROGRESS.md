# Progreso

Fuente de verdad: [`docs/ESPECIFICACION.md`](docs/ESPECIFICACION.md). Decisiones: [`docs/adr/`](docs/adr/README.md).

## Estado de hitos

| Hito | Objetivo | Estado |
|---|---|---|
| H0 | Fundaciones y walking skeleton | ✅ Cerrado |
| H1 | Spike Linux: presentación de vídeo con libmpv, JavaFX WebView, metadatos/frames | ✅ Cerrado |
| H2 | Modelo local (SQLDelight), migraciones, `contentId`, ajustes tipados | ✅ Cerrado |
| H3 | Escaneo e indexación (heurísticas, incremental, watcher, archivado/revinculación, miniaturas) | ✅ Cerrado |
| H4 | Sistema de diseño, navegación, Home/Detalle/Mi lista/Historial/Búsqueda, i18n, modos | ✅ Cerrado |
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
| 1 | Escaneo de la estructura de ejemplo | H3 | ✅ H3 (dominio, SQLite, Linux real, Android) |
| 2 | Home con "Continuar viendo" y pestaña principal | H4 | ✅ H4 (caso de uso + UI) |
| 3 | Reproductor Android y Linux completo | H5, H6 | ⬜ |
| 4 | Offline al 100 % | H5, H9 | ⬜ |
| 5 | Sync ≤5 min / al recuperar red, conflictos | H9 | ⬜ |
| 6 | Recurso HTML como elemento del curso | H7 | ⬜ |
| 7 | IDs estables, archivado | H3 | ✅ H3 (mover conserva progreso; borrar archiva) |
| 8 | Estadísticas persistidas en servidor | H10 | ⬜ |
| 9 | Auth (email/password + JWT; OAuth aplazado, ADR-0006) | H8, H9 | ⬜ |
| 10 | Idiomas es/en y subtítulos por idioma | H4, H5 | 🟡 UI es/en ✅ (H4); subtítulos por idioma → H5 |
| 11 | Modo Estudio/Ocio | H4 | ✅ H4 (caso de uso + UI) |
| 12 | Diseño con identidad propia | H4, H12 | 🟡 Home y Detalle en Android y Linux ✅ (H4); player → H5/H6; pulido → H12 |

## Alcance añadido o ajustado por el usuario (2026-11-11)

- Marcado manual visto/no visto por elemento, sección, curso y "todo hasta aquí" (cursos importados a medias) → H4/H5.
- Botón de pantalla completa en el reproductor; el modo se mantiene al pasar al siguiente elemento con autoplay → H5/H6.
- Categorías sin tipo: todas las heurísticas se aplican en cascada a cada carpeta; `LibraryItem.kind` describe la estructura detectada → H3.
- Visor PDF integrado → H7.
- Gestos en Linux: clic/doble clic en bordes ±N s, arrastre horizontal = seek, rueda/arrastre vertical derecho = volumen, arrastre vertical izquierdo = brillo del vídeo, mantener = 2x → H6.
- OAuth aplazado; OTP por email posible más adelante (ADR-0006).
- Versiones más recientes en lugar de las citadas en la spec (ADR-0001).

---

## H4 · Sistema de diseño y pantallas — ✅ cerrado

Documento: [docs/DISENO.md](docs/DISENO.md). Capturas: [docs/evidence/h4](docs/evidence/h4).

**Hecho**
- `design/tokens.json` (paletas clara/oscura, tipografía, espaciado, radios, movimiento, layout) generado a Kotlin en cada build. Instrument Sans (OFL) con cifras tabulares; iconos Lucide (ISC) convertidos a vectores; reducir movimiento según Android/GNOME/KDE.
- Componentes propios sobre `foundation` (sin Material): botones, selector, progreso, etiquetas, buscador, tarjetas, portadas con placeholder generado, estados vacío/carga/error, anillo de foco.
- Shell adaptativo inspirado en tus referencias: barra lateral (escritorio), carril oscuro + panel de reanudar (tableta), barra inferior (teléfono). Atajos Ctrl+K (buscar) y Ctrl+R (escanear).
- Pantallas: Home (continuar viendo, hoy toca, novedades, biblioteca por pestañas), Detalle (secciones, marcado visto/no visto por elemento, sección, curso y "hasta aquí", Mi lista), Categoría, Mi lista, Historial por días, Búsqueda, Carpetas (añadir/quitar raíces, escanear) y aviso de permiso en Android.
- Casos de uso de navegación, recomendaciones "Hoy toca" (siguiente lección + descubrimiento estable por día) y racha en el dominio.
- Selector de carpetas nativo: kdialog/zenity en Linux; selector del sistema en Android, convertido a ruta.
- Textos completos en inglés y español.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio: racha, recomendaciones, fechas | ✅ 4 tests |
| Casos de uso sobre SQLite: criterio 2, primaria configurable, criterio 11, recomendaciones y racha, marcado manual, Mi lista, búsqueda | ✅ 7 tests |
| UI (Compose, sin pantalla): **criterio 2** (último elemento con `12:34 / 45:10`, pestaña principal abierta), **criterio 11** (Ocio muestra solo lo suyo), **criterio 10** (interfaz en español) | ✅ 3 tests |
| Capturas de escritorio: Home es/en, claro/oscuro, detalle, tableta y teléfono | ✅ 6 capturas |
| **App Android real** en emulador (carpeta indexada, miniatura real, Home): teléfono y tableta | ✅ 2 capturas |
| CI | ver commit de cierre |

**Criterios §12**: ✅ **2**, ✅ **11**; 🟡 **10** (falta subtítulos por idioma, H5); 🟡 **12** (falta el reproductor, H5/H6).

**Pendiente / notas**
- Tocar un elemento abre una pantalla provisional del reproductor (llega en H5/H6).
- El idioma de la app sigue al del sistema; el selector llega con Ajustes (H11). Ajustes completos, Estadísticas y Onboarding: H10/H11.
- La búsqueda distingue mayúsculas en letras acentuadas (`LIKE` de SQLite); búsqueda sin acentos y en el contenido de recursos: H7/H11.
- §3.2.2 "partes de un mismo vídeo": sigue pendiente de que concretes el caso.

**Bloqueos**: ninguno.

---

## H3 · Escaneo e indexación — ✅ cerrado

Reglas implementadas: [docs/ESCANEO.md](docs/ESCANEO.md).

**Hecho**
- Detección pura (dominio) con la cascada de §3.2: `NN - sección`, `NNN elemento`, `_xx.srt`, fechas (3 formatos), temporadas/`SxxEyy`/`Capítulo`, orden natural y por fecha de modificación, "Sueltos", exclusiones estilo gitignore (`temp/` por defecto), segmentos HLS, portadas de carpeta. Detecta tanto "la raíz es un curso" (§3.1) como "la raíz contiene cursos".
- `ScanLibrary` incremental: solo analiza lo nuevo o cambiado, conserva ediciones del usuario, archiva lo desaparecido, restaura lo que vuelve, revincula ficheros movidos por SHA-256 sin leer bibliotecas enteras.
- Trabajos en segundo plano: hashes de elementos con datos del usuario y portadas (carpeta → fotograma al 10 %).
- `KeepLibraryUpToDate`: escaneo al abrir, vigilancia de cambios con agrupación de ráfagas por categoría, "Escanear ahora"; conectado en ambas apps (en Android solo con la app visible).
- Adaptadores: java.nio común (listado, SHA-256 en streaming, fechas locales); Linux: libmpv vía FFM (metadatos, miniaturas WebP), inotify; Android: MediaExtractor/MediaMetadataRetriever, miniaturas WebP, FileObserver recursivo; permisos de almacenamiento declarados.
- Esquema v2 (índice para revincular): primera migración real, verificada contra la instantánea v1.
- Clips de prueba de 2 s (MP4/MKV/WebM, 37 KB) en `client/testdata/media`.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio: árbol exacto de §3.1, series, directos, mtime, exclusiones, parser | ✅ 21 tests nuevos (49 en total) |
| Escaneo sobre SQLite: §3.1 de extremo a extremo, incremental, ediciones, archivado/restaurado, fichero movido con progreso y marcadores, modo hash, `temp/`, progreso del curso, portadas | ✅ 10 tests |
| Escaneo automático: inicial, ráfagas por categoría, ajustes | ✅ 2 tests |
| Migración v1 → v2 | ✅ |
| java.nio: listado, exclusiones, precisión de mtime, SHA-256 por bloques | ✅ 4 tests |
| **Linux real** (ficheros en disco + libmpv + SQLite en fichero): criterio 1, criterio 7, portada WebP, inotify | ✅ 4 tests |
| **Android 17 (emulador)**: criterio 1 con MediaExtractor, criterio 7, portada WebP, FileObserver | ✅ 3 tests |
| App de escritorio arrancada (Xvfb): crea BD v2 en WAL con las 3 categorías | ✅ |
| Coste de metadatos con libmpv reutilizado (vídeo real 1080p) | 28 ms mediana, p90 35 ms |
| CI (servidor + cliente, con libmpv en Ubuntu) | ✅ [run 43392334028](https://github.com/roberbarras/racha/actions/runs/43392334028) |

**Criterios §12**: ✅ **1** y ✅ **7**.

**Pendiente / notas**
- §3.2.2 "usar duración para detectar partes de un mismo vídeo": a la espera de que concretes el caso.
- La pantalla que pide el permiso de "acceso a todos los archivos" en Android llega con el onboarding (H4); sin él, una raíz externa se lista vacía.
- Si una raíz deja de estar disponible (disco desconectado), su contenido se archiva y se restaura al volver, como indica §3.4.

**Bloqueos**: ninguno.

---

## H2 · Modelo de datos local — ✅ cerrado

Documento: [docs/MODELO-DATOS.md](docs/MODELO-DATOS.md) (tablas, diferencias con §6.1 y su motivo, valores por defecto elegidos).

**Hecho**
- Dominio puro (sin frameworks): `ContentId`/`RelativePath` según ADR-0004, entidades de §6.1 + `Section`, reglas de `Progress` (máximo alcanzado, marcado manual visto/no visto), catálogo tipado de los ajustes de §9 con valores por defecto, validación y codificación.
- Aplicación: puertos por bounded context, servicio `Preferences` tipado, caso de uso `EnsureBuiltInCategories`, modelo de lectura "Continuar viendo" como `Flow`.
- Persistencia (`shared:data`): SQLDelight validado contra SQLite 3.18 (Android 8), claves foráneas, índices parciales, WAL; instantánea de esquema v1 y verificación de migraciones en `check`.
- Ambas apps abren la base de datos real (Android: `AndroidSqliteDriver`; escritorio: `~/.local/share/racha/racha.db`).

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio (`ContentIdTest` con vectores dorados, rutas, progreso, ajustes, categorías) | ✅ 28 tests |
| Aplicación (`Preferences`, `EnsureBuiltInCategories`) | ✅ 9 tests |
| Persistencia en SQLite real (esquema, FKs, ida y vuelta exacta, orden con overrides, "Continuar viendo" en tiempo real, cola de sync, migraciones) | ✅ 17 tests |
| Escritorio: crear BD en primer arranque y conservar datos al reiniciar | ✅ 1 test |
| Android: test instrumentado en emulador Android 17 sobre el SQLite del sistema | ✅ 1 test |
| CI (servidor + cliente) | ✅ [run 43284457086](https://github.com/roberbarras/racha/actions/runs/43284457086) |

**Criterios §12**: ninguno se cierra todavía; queda la base de 4 (offline: todo el estado vive en la BD local) y 7 (IDs estables y `fileHash`; la revinculación y el archivado se demuestran con el escaneo en H3).

**Pendiente / notas**
- El test instrumentado de Android no corre en CI (necesita emulador); se ejecuta localmente con `./gradlew :android:connectedDebugAndroidTest`. Se añadirá un job con emulador cuando haya pruebas de reproductor (H5).
- Valores por defecto que la spec no fija: ver tabla en MODELO-DATOS.md (pendientes de tu visto bueno).

**Bloqueos**: ninguno.

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
| CI de la rama (servidor + cliente, incluidos los spikes) | ✅ [run 43108926634](https://github.com/roberbarras/racha/actions/runs/43108926634) |

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
