# PROMPT MAESTRO — Aplicación de gestión y reproducción de cursos, series y directos locales, multiplataforma (Android + Linux) con backend Spring Boot + PostgreSQL

## 0. Resumen ejecutivo

Construir una aplicación tipo "Netflix/Udemy offline" para reproducir **contenido local** (vídeos, subtítulos `.srt`, recursos HTML/URL/TXT) organizado en **categorías configurables** (Cursos, Series, Directos, + las que cree el usuario). Debe existir en **Android**, **Linux desktop** y un **backend (Spring Boot + PostgreSQL)** que **nunca sirve contenido**, solo progreso, ajustes, estadísticas y sync.

Requisitos clave:
- **Offline-first** en el cliente; sync opt-in (pero recomendado) con servidor.
- **Reproductor completo** tipo VLC con gestos, velocidad, subtítulos, PiP, A-B loop, marcadores, etc.
- **Detección inteligente** de estructura (nombres + tiempos de modificación + fechas en nombre) y agrupación automática de cursos/series/directos.
- **UI/UX de nivel experto**, con identidad propia cross-platform (no Material genérico, no "AI slop"), orientada a **maximizar motivación y aprendizaje**.
- **Modo Estudio** y **Modo Ocio** (filtran categorías).
- **Multi-usuario** en backend (email/password + JWT + OAuth), sincronización de progreso con resolución por `updatedAt`.
- **Estadísticas completas** en servidor (usuarios, visualizaciones, horas, títulos, errores…) para futuro panel de admin.
- **Licencia MIT**, open source.

Todo lo listado a continuación es **requisito del producto final**, sin fases ni MVP. El implementador debe entregar la especificación completa funcionando.

---

## 1. Visión y objetivos

1.1. **Sustituir** plataformas de cursos/series cuando el material ya está en disco, con una experiencia de reproducción superior.
1.2. **Reducir la fricción**: escanear → organizar → reproducir → continuar donde lo dejaste, con cero configuración obligatoria.
1.3. **Motivar el consumo/estudio**: UI orientada a progreso visible, continuidad, estadísticas personales, widget/recordatorios.
1.4. **Privacidad**: el contenido jamás sale del dispositivo. El servidor solo guarda metadatos, progreso y estadísticas.
1.5. **Portabilidad futura** a iOS/macOS/Windows sin reescribir la lógica de dominio.

---

## 2. Alcance funcional

### 2.1. Plataformas
- **Android** (móvil y tablet).
- **Linux desktop** (AppImage/Flatpak recomendado).
- **Backend**: Spring Boot + PostgreSQL, Dockerizado.
- iOS/macOS/Windows **no** en esta entrega, pero arquitectura debe permitirlo.

### 2.2. Secciones de la app
- **Inicio** (Home) con:
  - Fila "Continuar viendo" (estilo Netflix).
  - Pestañas por **categoría** (Cursos, Series, Directos, + las del usuario). La **categoría principal** es configurable y se abre por defecto al iniciar.
  - Fila "Recomendado hoy" / "Aleatorio motivacional".
  - Fila "Novedades detectadas" (nuevos ficheros escaneados).
- **Detalle de curso/serie/canal** con lista de elementos, portada, descripción, progreso global, tiempo total, metadatos.
- **Reproductor** a pantalla completa con **barra lateral derecha desplegable** con todos los elementos del curso/serie (para saltar entre ellos) y botón de siguiente.
- **Visor de recursos** (HTML/URL/TXT) usando WebView/WebKit embebido; cuenta como "elemento" del curso para navegación siguiente/anterior y progreso de "visto".
- **Mi lista / Favoritos**.
- **Historial**.
- **Búsqueda global** (título, categoría, contenido textual de recursos si es viable).
- **Estadísticas personales** (tiempo visto, rachas, mapa de calor, cursos completados).
- **Notas y marcadores por timestamp**.
- **Ajustes** (completo, ver §9).
- **Perfil / Modo Estudio vs Modo Ocio**.
- **Widget Android** de motivación ("hoy toca estudiar X" / racha / progreso).
- **Deep links** para abrir un elemento concreto.

---

## 3. Detección, escaneo e indexación de la biblioteca

### 3.1. Estructura esperada (ejemplo real de referencia)

```text
<Categoría raíz>/
  01 - Introduction/
    001 Course Introduction.mp4
    001 Course Introduction_es.srt
    002 Creating an AWS Account.mp4
    002 Creating an AWS Account_es.srt
    003 AWS Account Activation Troubleshooting.html
  02 - Code & Slides Download/
    001 Code & Slides Download.html
    001 Course-Material-Download.url
    external-links.txt
  04 - IAM - Identity and Access Management/
    001 IAM Introduction Users, Groups, Policies.mp4
    001 IAM Introduction Users, Groups, Policies_es.srt
    ...
    021 Identity and Access Management - Quiz.html
  temp/
    index_XXXXX_1920x1080.m3u8 ...
```

### 3.2. Reglas de agrupación (heurística en cascada)
1. **Por convención de nombres**:
   - Carpetas con prefijo `NN - Título` = **sección/módulo**.
   - Ficheros `NNN Título.ext` = **elemento** ordenable.
   - Sufijo `_xx.srt` = **subtítulo del idioma `xx`** (el idioma de la app filtra por defecto).
   - Patrones de fecha (`YYYY-MM-DD`, `YYYYMMDD`, `DD-MM-YYYY`) en nombre = agrupación por fecha (útil en Directos).
   - Palabras clave en carpeta ("Season X", "Temporada X", "S01E02", "Capítulo X") = agrupación por temporada/episodio.
2. **Si el nombre no basta**: usar **fecha de modificación** (mtime) para ordenar y agrupar, y **duración** para detectar partes de un mismo vídeo.
3. **Ficheros sueltos** en la raíz de la categoría se agrupan como "Sueltos".
4. **Carpeta `temp/`** y `.m3u8` dentro de `temp/` = **ignorar/ocultar por defecto** (configurable).
5. **Detección de recursos**: `.html`, `.url`, `.txt`, `.pdf` (si existe visor) = **Recursos** del curso/serie, mostrados como elementos navegables.
6. Resultado de la heurística **editable**: si el usuario no está conforme, puede reordenar/renombrar metadatos en la app (solo metadatos, no ficheros).

### 3.3. Escaneo
- **Automático** al abrir la app y al detectar cambios (file-watcher nativo por SO).
- **Manual** desde ajustes ("Escanear ahora").
- **Incremental** (no reindexa lo ya conocido si no cambió mtime/hash).
- **Metadatos extraídos**: duración, resolución, codec, bitrate, fps, pistas de audio, pistas de subtítulos (vía `ffprobe`/`MediaMetadataRetriever`).
- **Miniaturas**:
  - Si existe `poster.jpg`/`folder.jpg`/`cover.jpg` en la carpeta → usarla.
  - Si no, generar thumbnail con `ffmpeg` (un frame a ~10% del vídeo).
  - Fallback: placeholder generado localmente.
  - **Opcional**: botón para generar portada con una API de IA de imágenes (ej. Pollinations, Hugging Face Inference gratis) configurable con endpoint + API key.

### 3.4. IDs estables entre dispositivos
- **`contentId` = SHA-256 de la ruta relativa dentro de la categoría** (normalizando separadores y sin extensión para agrupar vídeo+subtítulo).
- **`fileHash` = SHA-256 del fichero** (opcional, calculado en background) para **detectar reaparición** del mismo contenido en otra ruta/dispositivo y **re-asociar el progreso**.
- **Borrado local ≠ borrado lógico**: si el fichero desaparece pero hay progreso, se marca como `archived=true` y **sigue apareciendo** en la app con estado "Completado" o "Disponible de nuevo si lo vuelves a añadir". Al detectar un `fileHash` conocido, se **revincula** automáticamente.

---

## 4. Reproductor (especificación detallada)

### 4.1. Motor
- **Android**: **Media3 / ExoPlayer** + extensiones (`ui`, `session`, `cast` opcional).
- **Linux**: **libmpv** embebido (recomendado por sencillez y calidad) **o** libVLC. Preferible **libmpv** con bindings, evitando reinventar el motor.
- **Formatos**: MP4, MKV, WEBM, AVI, MOV, HLS (`.m3u8`), y los que soporte el motor elegido.
- **Subtítulos**: solo `.srt` (externos). Conversión en memoria si hace falta.

### 4.2. Controles y gestos (todos configurables)
- **Tap borde izquierdo/derecho** → ±N segundos (default 10s, configurable: 5/10/30).
- **Doble tap** → ±N segundos también.
- **Swipe horizontal** → ±N segundos.
- **Swipe vertical izquierdo** → brillo. **Swipe vertical derecho** → volumen.
- **Pinch** → zoom (si el motor lo soporta) o ajuste de aspecto.
- **Long-press** → velocidad temporal 2x mientras se mantiene.
- **Botón central** → play/pausa.
- **Botones**: anterior/siguiente, retroceder/avanzar, velocidad, subtítulos, audio track, PiP, cast (si disponible), A-B loop, marcador, captura, ajustes de reproductor.
- **Velocidad**: 0.25x a 4x con pasos configurables (default 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 3, 4).
- **PiP**: soportado en Android y Linux (si el motor lo permite).
- **A-B loop**: marcar A y B, repetir.
- **Marcadores/capítulos manuales**: añadir timestamp con nota, navegable.
- **Captura de pantalla** del frame actual a galería/carpeta.
- **Saltar intro/outro**: configurable por elemento (segundos), opcional.
- **Autoplay siguiente**: activado por defecto, configurable.
- **"Continuar viendo"**: overlay al reabrir el vídeo ("Reanudar desde 12:34" / "Empezar de nuevo").
- **Subtítulos**: selector de idioma (solo los disponibles en el elemento). **Por defecto: desactivados**.
- **Barra lateral derecha desplegable** con la lista completa de elementos del curso/serie, resaltando el actual, con indicador de progreso por elemento. Se oculta para ver a pantalla completa.

### 4.3. Guardado de progreso
- Cada **60s** mientras reproduce (configurable: 15/30/60/300s) y **al pausar/salir/terminar**.
- Al **terminar** el elemento → marcar como completado + autoplay siguiente (si activado).
- **Granularidad**: segundo exacto.
- **Fila "Continuar viendo"** se actualiza en tiempo real.

---

## 5. Recursos no-vídeo (HTML/URL/TXT)

- Visor **WebView/WebKit embebido** a pantalla completa.
- Los `.html` se cargan desde disco (`file://` con sandbox desactivado solo para contenido local y controlado).
- Los `.url`/`.txt` se muestran como texto enriquecido o como enlace abrible en navegador externo.
- Cuentan como **elemento del curso** con botón "siguiente" y estado `visto` (marcado al cerrar o al pulsar "marcar como visto").
- Barra lateral derecha también disponible en el visor.
- Los `.html` tipo "Quiz" se muestran **tal cual** (no interactuamos con su lógica, solo los mostramos).

---

## 6. Datos y modelo

### 6.1. Modelo local (SQLite/Room en Android, SQLite en Linux)

Entidades principales:

- `Category` (id, name, icon, color, isPrimary, order, rootPath, createdAt, updatedAt)
- `LibraryItem` (id = contentId, categoryId, kind: course|serie|directo|sueltos, title, description, posterPath, totalItems, totalDuration, progressPct, updatedAt)
- `PlayableElement` (id, libraryItemId, kind: video|resource, title, relativePath, durationSec, resolution, codec, order, archived, fileHash, updatedAt)
- `Subtitle` (id, elementId, lang, relativePath, updatedAt)
- `Progress` (id, elementId, positionSec, durationSec, completed, lastWatchedAt, updatedAt)
- `Bookmark` (id, elementId, timestampSec, note, createdAt)
- `HistoryEntry` (id, elementId, watchedAt, positionAtEndSec)
- `Favorite` (id, elementId|libraryItemId, createdAt)
- `StatLocal` (metric, value, period)
- `AppSettings` (key, value, updatedAt)
- `SyncQueue` (id, entityType, entityId, operation, payload, retries, lastError, createdAt)
- `Device` (id, name, platform, lastSeenAt)
- `User` (id, email, displayName, mode: study|leisure, tokenBundle)
- `CategoryRoot` (id, categoryId, path, order)

### 6.2. Modelo remoto (PostgreSQL)

- `users`, `sessions`, `oauth_accounts`
- `devices`
- `categories`, `library_items`, `playable_elements`, `subtitles`
- `progress`, `bookmarks`, `history`, `favorites`
- `app_settings` (por usuario y por dispositivo)
- `sync_events` (auditoría)
- `analytics_events` (ver §8)
- `errors` (ver §8)

### 6.3. Resolución de conflictos
- Por entidad: **gana el `updatedAt` más reciente**.
- Config: en conflicto → **preguntar al usuario** con diff visual (local vs servidor, por clave).
- Progreso: si `positionSec` difieren y el remoto es más reciente, se aplica el remoto; si el local es más reciente, se empuja. Nunca se pierde progreso: se guarda el máximo `positionSec` como referencia secundaria si `completed=true`.

---

## 7. Backend (Spring Boot + PostgreSQL)

### 7.1. Stack
- Java 21, Spring Boot 3.x, Spring Web, Spring Security (JWT + OAuth2), Spring Data JPA, Flyway/Liquibase, PostgreSQL 16.
- **Docker + docker-compose** para levantar el servidor.
- **REST API** únicamente (sin WebSocket/SSE en esta entrega).
- **No sirve contenido**. Solo JSON.

### 7.2. Auth
- **Email + password** (BCrypt/Argon2).
- **OAuth2**: Google y GitHub.
- **JWT access + refresh**.
- Endpoints `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/oauth/{provider}/callback`.
- Multi-usuario real.

### 7.3. Endpoints principales (REST)
- `GET/POST/PUT/DELETE /categories`
- `GET/POST/PUT/DELETE /library-items`
- `GET/POST/PUT/DELETE /elements`
- `GET/POST/PUT /progress` (batch de sync)
- `GET/POST /bookmarks`
- `GET/POST /favorites`
- `GET /history`
- `GET/PUT /settings` (por usuario y por dispositivo)
- `POST /sync/push` (lote de cambios locales)
- `GET /sync/pull?since=...` (lote de cambios remotos)
- `POST /devices/register`
- `POST /analytics/events` (batch)
- `POST /errors` (batch)
- `GET /stats/me` (estadísticas personales)

### 7.4. Comportamiento sync
- **Opt-in** desde ajustes (con host, puerto, HTTPS on/off, path base, y credenciales del usuario).
- Cola local (`SyncQueue`) que empuja en background cada X minutos (configurable, default 5) y al recuperar red.
- Pull incremental con `since`.
- Conflictos resueltos según §6.3, con **pregunta al usuario** si afecta a config.

---

## 8. Estadísticas (servidor)

Guardar **todo** para futuro panel de admin:
- Usuarios registrados, activos, por plataforma, por modo (estudio/ocio).
- Eventos de reproducción: `elementId`, `userId`, `deviceId`, `startedAt`, `endedAt`, `positionSec`, `completed`, `playbackSpeed`, `subtitlesLang`.
- Horas totales por usuario, por categoría, por curso/serie.
- Títulos vistos, elementos completados, rachas.
- Errores de cliente (con stack trace, plataforma, versión, deviceId).
- Fallos de sync.
- Eventos de uso de features (PiP, A-B loop, marcadores…).

---

## 9. Ajustes (lista completa)

- **General**: idioma de la app (es/en), tema (claro/oscuro/sistema), color de acento, tamaño de fuente.
- **Categorías**: añadir/quitar/renombrar/reordenar, elegir **categoría principal**, añadir **múltiples rutas raíz** por categoría, excluir patrones (ej. `temp/`), reescanear.
- **Modo**: Estudio / Ocio (filtra categorías visibles), configurable qué categorías van en cada modo.
- **Reproductor**: velocidad por defecto, subtítulos on/off por defecto, idioma subtítulos preferido, segundos por tap/gesto, swipe brillo/volumen on/off, autoplay siguiente on/off, saltar intro/outro (segundos), mostrar barra lateral por defecto, zoom.
- **Progreso**: intervalo de guardado (15/30/60/300s).
- **Escaneo**: auto/manual, file-watcher on/off, generación de thumbnails on/off, uso de IA de imágenes (endpoint + API key + prompt base), detección de cambios por mtime/hash.
- **Sync**: on/off, host, puerto, HTTPS, path base, usuario, token, intervalo, resolución de conflictos (último gana / preguntar).
- **Notificaciones**: nuevos episodios detectados, sync fallido, recordatorio diario de estudio (hora).
- **Almacenamiento**: borrar caché de thumbnails, borrar caché de metadatos, borrar historial local, exportar/importar config (JSON).
- **Avanzado**: nivel de log, endpoint de telemetría/crash reporting, proxy, User-Agent.
- **Acerca de**: versión, licencia, enlaces.

---

## 10. UI/UX

### 10.1. Principios
- **Identidad propia cross-platform** (no Material genérico en Android ni Adwaita puro en Linux): tokens de diseño compartidos (color, tipografía, espaciado, radios, sombras, motion) implementados por plataforma.
- **Orientado a motivación y aprendizaje**: progreso visible, rachas, "hoy toca", mensajes de ánimo, celebraciones al completar.
- **Cero fricción**: la Home "sabe" qué mostrar (continuar, recomendado, novedades).
- **Densidad de información alta pero respirable**.
- **Motion**: transiciones suaves (~200–300 ms), respetando `reduce motion`.
- **Tema claro/oscuro** con **color de acento** configurable.

### 10.2. Referencias (para el diseñador)
- **No** copiar Netflix/Plex/Jellyfin/Infuse/Stremio literalmente. Inspiración de **patrones** (fila continuar, detalle, player).
- Referencias estéticas de **apps premium** (Linear, Arc, Raycast, Things, Cron/Notion Calendar) — sin copiar.
- **Prohibido**: gradientes genéricos morado-azul, glassmorphism excesivo, emojis como iconos, tipografía Inter por defecto sin criterio, "hero" con foto de stock.

### 10.3. Entregables de diseño
- **Design tokens** (JSON) compartidos.
- **Sistema de componentes** por plataforma (Android: Compose; Linux: Qt/QML o GTK4/Adwaita si se elige; recomendable **Compose Multiplatform** o **Flutter** para reutilizar UI entre Android y Linux — decisión del implementador, justificar).
- **Pantallas**: Home, Detalle, Player, Recurso HTML, Ajustes, Perfil, Mi lista, Historial, Búsqueda, Estadísticas, Onboarding.
- **Estados**: vacío, carga, error, offline, sincronizando, conflicto de config.

### 10.4. Idiomas UI
- **es** y **en** completos. Estructura i18n preparada para más.

### 10.5. Widget Android
- Widget con: racha actual, "hoy toca estudiar X", progreso del curso activo, botón "continuar".

---

## 11. Arquitectura técnica sugerida

- **Cliente**: **Kotlin Multiplatform (KMP)** con **Compose Multiplatform** para compartir dominio + UI entre Android y Linux (JVM desktop). Alternativa: **Flutter**. Elegir una y justificar. Motor de vídeo **nativo por plataforma** (Media3/ExoPlayer en Android, libmpv vía JNI/FFI en Linux).
- **Persistencia local**: **SQLDelight** (KMP) o Room/SQLite por plataforma.
- **Backend**: Spring Boot + PostgreSQL, Docker.
- **Contratos**: **OpenAPI** generado, DTOs compartidos vía KMP o generación de clientes.
- **Lógica de sync**: módulo compartido en KMP.
- **Logs/telemetría**: cliente → `POST /analytics/events` y `POST /errors` en batch, reintentos con backoff.
- **Licencia**: **MIT** en cliente y servidor.

---

## 12. Criterios de aceptación (medibles)

1. **Escaneo**: sobre la estructura de ejemplo (§3.1), la app detecta Cursos, agrupa secciones, ordena por `NNN`, asocia `_es.srt` correctamente y **oculta `temp/`** por defecto.
2. **Home**: al abrir, muestra "Continuar viendo" con el último elemento y su timestamp; la pestaña principal es la configurada.
3. **Reproductor Android y Linux**: reproduce `.mp4`, `.mkv`, `.webm`, HLS; gestos configurables; velocidad 0.25–4x; subtítulos `.srt` externos seleccionables; PiP; A-B loop; marcadores; captura; barra lateral derecha con elementos y progreso.
4. **Offline**: con servidor apagado, la app funciona al 100% (reproducir, guardar progreso, escanear, ajustes).
5. **Sync**: al activar sync con host/puerto/credenciales, el progreso se empuja en ≤5 min o al recuperar red, y se recibe en otro dispositivo con el mismo usuario; conflictos resueltos por `updatedAt`; conflictos de config piden al usuario.
6. **Recursos HTML**: se abre `003 AWS Account Activation Troubleshooting.html` como elemento del curso, con botón siguiente y barra lateral.
7. **IDs estables**: mover un fichero de ruta **no** pierde progreso si `fileHash` matchea; borrar el fichero **no** lo elimina de la app (queda archivado con estado).
8. **Estadísticas**: el servidor recibe y persiste visualizaciones, horas, errores y eventos; consultables en BD.
9. **Auth**: registro email/password, login OAuth (Google + GitHub), JWT con refresh.
10. **Idiomas**: UI en es/en; subtítulos por idioma disponibles.
11. **Modo Estudio/Ocio**: filtran categorías visibles según configuración.
12. **Diseño**: la app **no** parece generada por IA; hay identidad visual coherente entre Android y Linux; capturas del player, home y detalle demuestran calidad de UI.

---

## 13. Decisiones que el implementador debe tomar y justificar

- Framework UI cross-platform (KMP+Compose vs Flutter).
- Motor de vídeo en Linux (libmpv vs libVLC) y cómo integrarlo (JNI/FFI).
- Estrategia de thumbnails (ffmpeg embebido vs librería).
- Proveedor OAuth (Google + GitHub en esta entrega; extensible).
- Formato del export/import de configuración (JSON con versión).
- Estrategia de empaquetado Linux (AppImage vs Flatpak vs ambos).
- Estrategia de build y distribución Android (APK firmado; Play Store fuera de alcance).
- Cómo cargar `.html` locales de forma segura en WebView/WebKit.
- Cómo gestionar múltiples rutas raíz por categoría sin colisiones de `contentId` (prefijo por `rootPathId`).

---

## 14. Fuera de alcance explícito

- Servir vídeo desde el servidor (streaming remoto).
- Web app.
- iOS/macOS/Windows (arquitectura preparada, no implementación).
- Panel de administración web (los datos se guardan para un futuro panel, no se construye ahora).
- Cast/AirPlay (puede añadirse después si el motor lo facilita; no es requisito).
- Accesibilidad WCAG AA formal (recomendada, no bloqueante).

---

## 15. Entregables

1. Repositorio **cliente** (KMP o Flutter + módulos nativos) con licencia MIT.
2. Repositorio **servidor** Spring Boot + PostgreSQL con `docker-compose.yml`.
3. **Especificación OpenAPI** del backend.
4. **Design tokens** (JSON) + **sistema de componentes** documentado.
5. **Documento de arquitectura** (decisiones §13).
6. **Guía de despliegue**: servidor (docker compose up), cliente Android (APK), cliente Linux (AppImage/Flatpak).
7. **README** con onboarding para desarrolladores y usuario final.
8. **Suite de tests** mínima: escaneo/heurísticas, resolución de conflictos, sync end-to-end, reproductor (smoke).