# ADR-0012 · Modelo remoto y protocolo de sincronización

**Estado**: aceptada (2026-11-15). Implementa §6.2, §6.3 y §7.3–7.4 en el servidor (H8); el cliente lo usa en H9.

**Contexto**: varios dispositivos por usuario, cada uno con su SQLite. Los ids de contenido ya coinciden entre dispositivos (ADR-0004). Los relojes de los dispositivos pueden estar desajustados y la red ser intermitente.

**Decisión**
- **Un almacén por entidad sincronizable**, con columnas tipadas (§6.2) más `updated_at` (hora del cambio en el cliente), `deleted` (lápida) y `seq` (secuencia global del servidor asignada en cada escritura). Entidades: `categories`, `category_roots`, `library_items`, `sections`, `playable_elements`, `subtitles`, `progress`, `bookmarks`, `history`, `favorites`, `app_settings`. Todo va acotado por `user_id`.
- **Push** (`POST /sync/push`): lote de cambios `{entity, id, updatedAt, deleted, data}`. Se aplica **"gana el `updatedAt` más reciente"** (§6.3) de forma atómica en SQL (`INSERT … ON CONFLICT … DO UPDATE … WHERE excluded.updated_at > tabla.updated_at`). Cada cambio devuelve `applied`, `stale` (el servidor tiene uno más reciente) o `invalid`.
  - **Progreso**: además, `max_position_sec` = máximo histórico, nunca baja (§6.3 "nunca se pierde progreso").
  - **Historial**: solo se añade (ids únicos); no hay conflictos.
  - **Ajustes**: si el servidor tiene un valor más reciente, el cambio vuelve como `stale` con el valor del servidor para que la app pregunte al usuario (§6.3 "config: preguntar").
  - **Relojes**: un `updatedAt` en el futuro (más de 5 min) se recorta a la hora del servidor, para que un dispositivo con el reloj adelantado no gane siempre.
- **Pull** (`GET /sync/pull?since=<cursor>`): cambios con `seq > since`, en orden, paginados (`hasMore`). El cursor es la secuencia del servidor, **no una fecha** (inmune a relojes desajustados y a cambios con la misma marca de tiempo). La spec dice `since=...`: el nombre se mantiene.
- Los endpoints CRUD de §7.3 (`/categories`, `/library-items`, `/elements`, `/progress`, `/bookmarks`, `/favorites`, `/history`, `/settings`) son vistas sobre el mismo almacén con las mismas reglas; `DELETE` deja lápida para que llegue al resto de dispositivos.
- **Auth** (ADR-0006): BCrypt; JWT de acceso HS256 de 15 min con `sub` = usuario y `did` = dispositivo; refresh opaco de 256 bits, guardado con SHA-256, rotativo; reutilizar un refresh ya rotado revoca toda su familia (robo de token). Límite de intentos de login/registro. Registro abierto o cerrado por configuración (servidor propio).
- Persistencia con SQL explícito (`JdbcClient`), no JPA: las reglas de conflicto viven en la consulta y son atómicas.

**Consecuencias**: el cliente guarda el último cursor por servidor; tras un pull aplica los cambios con la misma regla. La telemetría (`/analytics/events`, `/errors`) se guarda tal cual para el panel futuro (§8).
