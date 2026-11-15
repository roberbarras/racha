# Servidor

Spec §6.2, §7 y §8. ADRs: [0006 (autenticación)](adr/0006-autenticacion.md), [0012 (modelo remoto y sync)](adr/0012-protocolo-sync-y-modelo-remoto.md). Código: [`server/`](../server).

## Arrancarlo

```bash
export RACHA_JWT_SECRET="$(openssl rand -base64 48)"   # guárdalo: si cambia, todos los dispositivos vuelven a iniciar sesión
docker compose up -d                                   # PostgreSQL 18 + servidor en http://localhost:8080
```

| Variable | Por defecto | Qué hace |
|---|---|---|
| `RACHA_PORT` | 8080 | Puerto publicado |
| `RACHA_BASE_PATH` | (vacío) | Ruta base (p. ej. `/racha` detrás de un proxy) |
| `RACHA_JWT_SECRET` | aleatorio | Clave HS256 de los tokens de acceso (≥ 32 bytes) |
| `RACHA_REGISTRATION` | `open` | `closed`: solo usuarios existentes (crea el tuyo y ciérralo) |
| `RACHA_ACCESS_TOKEN_MINUTES` / `RACHA_REFRESH_TOKEN_DAYS` | 15 / 60 | Vida de los tokens |
| `RACHA_API_DOCS` | `true` | OpenAPI en `/v3/api-docs` y Swagger UI en `/docs` |
| `RACHA_DB_*` | ver `docker-compose.yml` | Conexión a PostgreSQL |

Para exponerlo fuera de casa, ponlo detrás de un proxy con HTTPS (Caddy, Traefik, nginx): el servidor habla HTTP plano.

## API

Descripción completa (OpenAPI 3): `GET /v3/api-docs`, navegable en `/docs`. Errores en formato RFC 9457 con un campo `code` estable.

| Grupo | Endpoints |
|---|---|
| Cuenta | `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` |
| Dispositivos | `POST /devices/register`, `GET /devices` |
| Sync | `POST /sync/push`, `GET /sync/pull?since=<cursor>&limit=` |
| Colecciones | `/categories`, `/library-items`, `/elements` (GET/POST/PUT/DELETE), `/progress` (GET/POST/PUT), `/bookmarks`, `/favorites` (GET/POST), `/history` (GET), `/settings` (GET/PUT) |
| Telemetría | `POST /analytics/events`, `POST /errors` (lotes, idempotentes por `id`) |
| Estadísticas | `GET /stats/me?zone=Europe/Madrid` (totales, racha, tiempo por día y por categoría); vistas SQL `stats_*` para el panel de administración: ver [ESTADISTICAS.md](ESTADISTICAS.md) |

**Sesiones**: token de acceso JWT (15 min, `sub` = usuario, `did` = dispositivo) + refresh opaco de un solo uso. Cada refresh devuelve uno nuevo; si alguien usa uno ya usado (copiado), se cierran todas las sesiones de ese inicio de sesión. Contraseñas con BCrypt, mínimo 10 caracteres; 10 fallos seguidos bloquean la cuenta 15 min (y 50 por dirección).

**Sync** (ADR-0012): cada cambio lleva `updatedAt`; gana el más reciente, decidido en una sola sentencia SQL. El progreso conserva siempre la posición más lejana (`maxPositionSec`); el historial solo se añade; un ajuste que pierde vuelve como `stale` con el valor del servidor para que la app pregunte. Un reloj adelantado más de 5 min se recorta. El pull usa un cursor de secuencia del servidor (no fechas).

## Tests

`./gradlew test` (Docker para Testcontainers, PostgreSQL 18): autenticación (registro, login, rotación y reutilización de refresh, logout, tokens manipulados, límites, registro cerrado), sync (incremental entre dispositivos, conflictos, progreso que nunca se pierde, historial, ajustes, lápidas, validación, relojes, aislamiento entre usuarios, paginación, vistas REST), telemetría, estadísticas, OpenAPI, reglas unitarias y la arquitectura hexagonal (ArchUnit).
