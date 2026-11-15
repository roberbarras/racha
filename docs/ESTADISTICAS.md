# Estadísticas y telemetría

Spec §2.2 ("Estadísticas personales"), §8 (estadísticas en el servidor), §11 (telemetría en lote con reintentos) y §12.8. Relacionado: [SYNC.md](SYNC.md) (cuenta y servidor), [SERVIDOR.md](SERVIDOR.md).

**Privacidad**: la telemetría solo existe con la cuenta iniciada y va solo al servidor de esa cuenta (el tuyo), o al endpoint de telemetría si se configura (§9 "Avanzado"). Sin cuenta no se guarda nada; al cerrar sesión se borra lo pendiente. Nunca viajan ficheros ni rutas locales: solo ids de contenido, tiempos y nombres de funciones.

## Qué envía el cliente

| Tipo (`analytics_events.type`) | Cuándo | Propiedades |
|---|---|---|
| `app_open` | Al arrancar la app | `platform`, `appVersion`, `mode` (estudio/ocio) |
| `watch` | Al terminar una sesión de visionado (pausar y salir, pasar al siguiente, cerrar) | `elementId`, `itemId`, `categoryId`, `startedAt`, `endedAt`, `positionSec`, `completed`, `playbackSpeed`, `subtitlesLang`, `seconds` (tiempo realmente reproducido) |
| `resource_view` | Al salir del visor de un recurso | `elementId`, `itemId`, `categoryId`, `resourceType`, `startedAt`, `endedAt` |
| `feature` | Al usar una función | `name`: `pip`, `ab_loop`, `bookmark`, `screenshot`, `fullscreen`, `speed`, `hold_speed`, `subtitles`, `audio_track` (+ detalle, p. ej. la velocidad) |
| `sync_failed` | Cuando una ronda de sync falla (una vez por cambio de estado, no en cada reintento) | `code`: `unreachable`, `unauthorized`, `rejected:<status>:<code>` |

**Errores** (`POST /errors`): cualquier excepción no capturada (Linux: cualquier hilo, también el de la interfaz; Android: el manejador del proceso) con mensaje, stack trace, plataforma, versión, dispositivo y el hilo. Se guarda antes de que el sistema cierre la app y se envía en el siguiente arranque con red.

**Envío**: cola local acotada (5.000 entradas; se descartan primero las más antiguas), lotes de 500 eventos y 100 errores tras cada ronda de sync (y desde WorkManager en Android). Si falla, espera 1, 2, 4… hasta 60 minutos; un lote que el servidor rechaza por inválido se descarta para no bloquear el resto. El servidor ignora duplicados (los ids los pone el cliente), así que reenviar es inocuo.

## Qué guarda y ofrece el servidor (§8, criterio 12.8)

Tablas: `analytics_events` (con validación del formato de `watch`), `errors`, `sync_events`, más el modelo sincronizado. Vistas para el futuro panel de administración (migración `V5__stats_views.sql`):

| Vista | Responde a |
|---|---|
| `stats_users`, `stats_users_by_platform`, `stats_users_by_mode` | Usuarios registrados, por plataforma y por modo |
| `stats_active_users_daily` | Usuarios activos por día (abrieron la app o vieron algo) |
| `stats_watch` | Eventos de reproducción con todos los campos de §8 |
| `stats_watch_hours_by_user` / `_by_category` / `_by_item` | Horas totales por usuario, categoría y curso/serie |
| `stats_titles`, `stats_completed_elements` | Títulos vistos y elementos completados |
| `stats_streaks` | Todas las rachas (días consecutivos), no solo la actual |
| `stats_sync_failures` | Cambios rechazados y rondas fallidas de los clientes |
| `stats_feature_usage` | Uso de funciones (PiP, A-B, marcadores…) |
| `stats_errors_by_version` | Errores por plataforma y versión |

```sql
select * from stats_watch_hours_by_category where user_id = '…';
select feature, uses, users from stats_feature_usage order by uses desc;
```

`GET /stats/me?zone=Europe/Madrid` devuelve además `watchByDay` (371 días, en la zona del cliente) y `watchByCategory`.

## Estadísticas personales (pantalla "Progreso")

Tiempo total y de los últimos 7 días, racha actual y mejor racha, días activos, lecciones y episodios completados, un año de actividad (mapa de calor), tiempo por categoría y cursos/series completados.

- **Funciona sin red**: el tiempo visto se cuenta en local por día y por categoría (`local_stat`); los días activos y las rachas salen del historial (sincronizado), igual que la racha de Inicio; los completados, del progreso (sincronizado).
- **Con cuenta**: se suman los datos de todos los dispositivos que da `/stats/me`. Por día y categoría se toma el mayor entre este dispositivo y el servidor, porque el servidor puede no tener aún los últimos minutos de este. Primero se muestra lo local y, si el servidor responde, se actualiza.
- **Batería**: se calcula al abrir la pantalla (no hay nada vigilando en segundo plano) y el mapa de calor es un único `Canvas`.
- En el móvil la barra inferior tiene cinco destinos; Carpetas y Cuenta se abren desde Progreso. En tableta y escritorio están en el raíl y la barra lateral.

## Tests

- Servidor: `StatsViewsTest` (PostgreSQL real): eventos con los campos de §8, formato de `watch` validado, todas las vistas consultadas y `/stats/me` con desglose por día y categoría.
- Cliente (JVM, SQLite real): `PlaybackControllerTest` (sesión de visionado con sus campos y uso de funciones), `TelemetryTest` (nada sin cuenta, lotes, backoff, lote rechazado, token renovado, endpoint propio, cola acotada y vaciada al salir), `PersonalStatsTest` (local, rachas y fusión con el servidor).
- UI de escritorio: `StatsUiTest` y capturas de Progreso (escritorio y móvil oscuro).
- `EndToEndSyncTest` contra el servidor real: eventos, error y fallo de sync de un cliente llegan al servidor y el tiempo visto en el portátil aparece en las estadísticas del móvil.
