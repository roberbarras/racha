# Modelo de datos local

Implementa la spec §6.1 en SQLite (SQLDelight, compatible con SQLite 3.18 = Android 8). Código:
[`shared/domain`](../client/shared/domain) (entidades y reglas), [`shared/application`](../client/shared/application) (puertos),
[`shared/data`](../client/shared/data) (adaptador SQLite). Identificadores: [ADR-0004](adr/0004-contentid-y-multiples-raices.md).

## Tablas

| Tabla | Clave | Entidad de dominio | Notas |
|---|---|---|---|
| `category` | `key` | `Category` | `is_primary` único (índice parcial). Visibilidad por modo y patrones de exclusión por categoría. |
| `category_root` | `key` (UUID) | `CategoryRoot` | `location` es local del dispositivo (`file://…`); `key` se sincroniza. FK → `category` en cascada. |
| `library_item` | `id` (`contentId`) | `LibraryItem` | Totales y `%` desnormalizados para que la Home lea una fila por curso. FK → `category`. |
| `section` | `id` | `Section` | Módulo, temporada, grupo por fecha o "sueltos". FK → `library_item`. |
| `playable_element` | `id` | `PlayableElement` | Vídeo o recurso. `file_size` + `file_modified_at` para escaneo incremental; `file_hash` para revincular. FK → `section`, `library_item`. |
| `subtitle` | `id` | `Subtitle` | `.srt` externo con idioma. FK → `playable_element`. |
| `progress` | `element_id` | `Progress` | Al segundo; `max_position_sec` nunca decrece. |
| `bookmark` | `id` | `Bookmark` | Marcador con nota. |
| `history_entry` | `id` | `HistoryEntry` | |
| `favorite` | (`target_type`, `target_id`) | `Favorite` | Curso/serie o elemento. |
| `local_stat` | (`metric`, `period`) | `LocalStat` | |
| `app_setting` | `key` | catálogo `Settings` | Valores tipados validados en el dominio. |
| `sync_queue` | `id` | `SyncQueueEntry` | FIFO de cambios pendientes de subir. |
| `device` | `id` | `Device` | Un único `is_current` (índice parcial). |
| `account` | `id` | `Account` | Una fila como máximo. |

Convenciones: instantes en milisegundos UTC; listas separadas por salto de línea; enums por nombre.
Las "inserciones o actualizaciones" son `INSERT OR IGNORE` + `UPDATE` en una transacción: `INSERT OR REPLACE` borraría
la fila y arrastraría en cascada sus hijos (hay un test que lo protege).

## Diferencias con §6.1 (y por qué)

| §6.1 | Implementado | Motivo |
|---|---|---|
| — | **`Section`** (nueva) | §12.1 exige agrupar secciones y §3.2.6 renombrarlas/reordenarlas. Aprobado por el usuario. |
| `Category.rootPath` | Solo `CategoryRoot` (varias por categoría) | §9 pide múltiples rutas raíz; la ruta es local del dispositivo y la clave se sincroniza (ADR-0004). |
| `Category.isPrimary` + ajuste "categoría principal" | Solo `Category.isPrimary` | Una única fuente de verdad, sincronizada con la categoría. |
| — | `Category.visibleInStudy/visibleInLeisure`, `excludePatterns` | §9: "qué categorías van en cada modo" y "excluir patrones" por categoría. |
| `LibraryItem` | + `rootKey`, `relativePath`, `order`, `titleOverride`, `orderOverride`, `archived`, `firstSeenAt` | Ediciones del usuario que el reescaneo no pisa (§3.2.6); archivado (§3.4); fila "Novedades" (§2.2). |
| `PlayableElement` | + `sectionId`, `resourceType`, `fps`, `bitrateKbps`, `fileSize`, `fileModifiedAt`, overrides, `firstSeenAt` | Metadatos de §3.3; escaneo incremental (§3.3); visor por tipo (§5). |
| `Progress.id` | Clave = `elementId`; + `maxPositionSec` | Un progreso por elemento; referencia que nunca se pierde (§6.3). |
| `Bookmark` | + `updatedAt` | Resolución de conflictos por `updatedAt` (§6.3). |
| `User(… mode, tokenBundle)` | `Account(id, email, displayName)` | Los tokens van al almacén seguro de credenciales del sistema, nunca a la BD. El modo Estudio/Ocio es un ajuste de usuario para que funcione sin cuenta y offline. |
| FKs implícitas progreso/marcadores/historial → elemento | Sin FK | El progreso sincronizado desde otro dispositivo puede llegar antes que el elemento, y debe sobrevivir a que el fichero desaparezca (§3.4). |

## Valores por defecto elegidos (la spec no los fija)

| Valor | Por defecto |
|---|---|
| Visibilidad por modo de las categorías integradas | Estudio: Cursos + Directos · Ocio: Series + Directos |
| Categoría principal inicial | Cursos |
| Barra lateral del reproductor visible al abrir | No |
| Recordatorio diario | Desactivado, 19:00 |
| Notificar contenido nuevo / sync fallido | Sí / Sí |
| Escala de vídeo | Ajustar (`FIT`) |
| Color de acento | `#E85D04` (provisional hasta los design tokens de H4) |
| Decodificación por hardware en escritorio | Desactivada (ADR-0011) |

Los valores fijados por la spec (10 s por gesto, guardado cada 60 s, subtítulos desactivados, autoplay activado, sync cada 5 min, conflicto de configuración = preguntar, pasos de velocidad 0,25–4) están cubiertos por `SettingsTest`.

## Migraciones

- Esquema actual: versión 1. Cada versión publicada deja una instantánea en `shared/data/src/commonMain/sqldelight/databases/<versión>.db`.
- Un cambio de esquema = un fichero `<versión>.sqm` + nueva instantánea (`generateCommonMainRachaDatabaseSchema`).
- Se verifica en `check`: `verifyCommonMainRachaDatabaseMigration` (las migraciones producen el esquema declarado) y `MigrationTest` (cada instantánea migrada == instalación limpia).
- Escritorio: el driver crea/migra con `PRAGMA user_version` al abrir. Android: `AndroidSqliteDriver` hace lo mismo. Ambos con claves foráneas activadas y WAL.
