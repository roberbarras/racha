# Servidor

Spec §6.2, §7 y §8. ADRs: [0006 (autenticación)](adr/0006-autenticacion.md), [0012 (modelo remoto y sync)](adr/0012-protocolo-sync-y-modelo-remoto.md). Código: [`server/`](../server).

## Arrancarlo

```bash
cp .env.example .env        # y rellena al menos RACHA_JWT_SECRET y RACHA_DB_PASSWORD (antes del primer arranque)
mkdir -p data backups
docker compose up -d        # PostgreSQL 18 + copias diarias + servidor en http://localhost:8080
```

El `.env` va junto a `docker-compose.yml`, una línea `VARIABLE=valor` por ajuste. Todas están explicadas en [`.env.example`](../.env.example):

| Variable | Por defecto | Qué hace |
|---|---|---|
| `RACHA_JWT_SECRET` | aleatorio en cada arranque | Clave HS256 de los tokens de acceso (≥ 32 bytes). Fíjala |
| `RACHA_DB_PASSWORD` | `racha` | Contraseña de PostgreSQL; PostgreSQL la toma al crear la base de datos (primer arranque) |
| `RACHA_PORT` / `RACHA_BASE_PATH` | 8080 / vacío | Puerto publicado; ruta base (p. ej. `/racha` detrás de un proxy) |
| `RACHA_REGISTRATION` | `open` | `closed`: solo usuarios existentes (crea el tuyo y ciérralo) |
| `RACHA_ADMIN_EMAIL` | vacío | Tu cuenta: es la administradora. Vacío: la primera que se registre (en un servidor existente, la más antigua) |
| `RACHA_PUBLIC_URL` | vacío | Cómo llegan los usuarios (`https://racha.midominio.es`): enlace de los correos de recuperación |
| `RACHA_TRUSTED_PROXIES` | `local` | Quién puede decir la IP del cliente: ver «Detrás de un proxy» |
| `RACHA_MAIL_*` | sin correo | SMTP para recuperar contraseñas: ver «Correo» |
| `RACHA_METRICS_TOKEN` | vacío (cerrado) | Token para que Prometheus lea `/actuator/prometheus` |
| `RACHA_DATA_DIR` / `RACHA_BACKUP_DIR` | `./data` / `./backups` | Carpetas de la base de datos y de las copias |
| `RACHA_BACKUP_TIME` / `RACHA_BACKUP_KEEP_DAILY` / `RACHA_BACKUP_KEEP_WEEKLY` | 03:30 / 14 / 8 | Copias: hora diaria, cuántas diarias y semanales (lunes) se guardan |
| `RACHA_TZ` | `Europe/Madrid` | Zona horaria de las copias y los logs |
| `RACHA_ACCESS_TOKEN_MINUTES` / `RACHA_REFRESH_TOKEN_DAYS` | 15 / 60 | Vida de los tokens |
| `RACHA_API_DOCS` | `true` | OpenAPI en `/v3/api-docs` y Swagger UI en `/docs` |

## Datos, copias y migración a otro servidor

- **Base de datos** en `data/postgres` (carpeta, no volumen de Docker: `docker compose down -v` no la borra).
- **Copias**: el servicio `backup` hace un `pg_dump` diario a `RACHA_BACKUP_TIME` en `backups/racha-AAAAMMDD-HHMMSS.dump` (y otro al arrancar si el último tiene más de un día). Guarda las 14 más recientes y una por semana durante 8 semanas. Solo las puede leer el dueño de la carpeta: llevan los datos de todas las cuentas. Una copia ahora mismo: `docker compose exec backup racha-backup now`.
- **Restaurar**: `tools/restore-backup.sh backups/racha-….dump` (pide confirmación, para el servidor mientras sustituye los datos y lo vuelve a arrancar).
- **Mover el servidor**: copia a la máquina nueva el repositorio (o `docker-compose.yml`, `server/` y `tools/`), tu `.env` y la copia más reciente; `mkdir -p data backups && docker compose up -d` y `tools/restore-backup.sh <copia>`. Los dispositivos siguen con su sesión si la dirección pública no cambia.
- **Si ya lo tenías arrancado con la versión anterior** (volumen `racha_racha-db`), antes de actualizar: `docker compose exec db pg_dump -U racha -Fc racha > antes.dump`; después de actualizar, `tools/restore-backup.sh antes.dump`. El volumen antiguo no se borra solo (`docker volume rm racha_racha-db` cuando todo funcione).
- Las carpetas llevan `:z` para hosts con SELinux (Fedora, openSUSE); en el resto no cambia nada.

## Detrás de un proxy (Cloudflare, nginx, Caddy)

El servidor habla HTTP plano: para usarlo fuera de casa, ponlo detrás de un proxy con HTTPS. Detrás de un proxy todas las peticiones llegan desde él, así que el servidor lee la IP real de `CF-Connecting-IP` o `X-Forwarded-For` (de derecha a izquierda), **solo** si la conexión viene de un proxy de confianza:

| Montaje | `RACHA_TRUSTED_PROXIES` |
|---|---|
| Cloudflare Tunnel (`cloudflared` en la misma máquina o red), nginx/Caddy locales | `local` (por defecto: loopback, redes privadas, Docker) |
| Proxy de Cloudflare (nube naranja) apuntando a tu IP | `cloudflare` (rangos publicados por Cloudflare); `local,cloudflare` si además hay un proxy local |
| Expuesto directamente | `none` (o `local`: sin cabeceras no cambia nada) |

**Intentos fallidos de contraseña**: 10 en 15 min bloquean **esa cuenta desde esa IP**; 50 bloquean **esa IP** para cualquier cuenta. Nunca se bloquea a los demás ni a tu cuenta desde otros sitios.

## Correo (recuperar la contraseña)

Cualquier SMTP: el de tu proveedor de correo, o Gmail con una contraseña de aplicación (`smtp.gmail.com`, 587, `starttls`).

```
RACHA_MAIL_HOST=smtp.midominio.es
RACHA_MAIL_PORT=587
RACHA_MAIL_USERNAME=racha@midominio.es
RACHA_MAIL_PASSWORD=…
RACHA_MAIL_SECURITY=starttls        # ssl para el puerto 465, none sin cifrado
RACHA_MAIL_FROM=racha@midominio.es
RACHA_PUBLIC_URL=https://racha.midominio.es
```

Comprueba la configuración desde el panel de administración («Enviar correo de prueba»: muestra el error del servidor de correo si lo hay). Sin `RACHA_MAIL_HOST`, la recuperación dice que el servidor no puede enviar correos.

**Recuperación**: la app (Cuenta → «¿Olvidaste tu contraseña?») o la página `/reset-password` piden un código; llega por correo (8 caracteres, 30 minutos, un solo uso; uno nuevo anula el anterior) con un enlace a la página ya rellenado. Al cambiar la contraseña así se cierran todas las sesiones. No revela si una cuenta existe: la respuesta es la misma y el correo sale en segundo plano. Límites: 5 peticiones por cuenta y 20 por IP, 10 códigos erróneos por cuenta, cada 15 min.

## Administración

La cuenta administradora (`RACHA_ADMIN_EMAIL` o la primera) ve en `/admin/*` (panel web en H14): versión, tiempo encendido, configuración (correo, URL pública, proxies, métricas), recuentos (usuarios, dispositivos, sesiones, progreso, eventos y errores recientes, tamaño de la base de datos), memoria, CPU, peticiones y errores HTTP, las copias y los logs (fichero JSON con rotación, 10 MB × 14, filtrables por nivel). Puede hacer administrador a otro usuario o desactivar una cuenta (cierra todas sus sesiones); siempre queda al menos un administrador activo y nadie puede desactivarse a sí mismo. El rol se comprueba en cada petición.

**Métricas** para Prometheus en `/actuator/prometheus` con `Authorization: Bearer <RACHA_METRICS_TOKEN>`.

## API

Descripción completa (OpenAPI 3): `GET /v3/api-docs`, navegable en `/docs`. Errores en formato RFC 9457 con un campo `code` estable.

| Grupo | Endpoints |
|---|---|
| Cuenta | `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/password/forgot`, `/auth/password/reset`; `GET /account/me`, `POST /account/password` |
| Administración | `GET /admin/overview`, `/admin/users`, `/admin/backups`, `/admin/logs?level=&lines=`; `POST /admin/users/{id}/role`, `/admin/users/{id}/disabled`, `/admin/mail/test` |
| Dispositivos | `POST /devices/register`, `GET /devices` |
| Sync | `POST /sync/push`, `GET /sync/pull?since=<cursor>&limit=` |
| Colecciones | `/categories`, `/library-items`, `/elements` (GET/POST/PUT/DELETE), `/progress` (GET/POST/PUT), `/bookmarks`, `/favorites` (GET/POST), `/history` (GET), `/settings` (GET/PUT) |
| Telemetría | `POST /analytics/events`, `POST /errors` (lotes, idempotentes por `id`) |
| Estadísticas | `GET /stats/me?zone=Europe/Madrid` (totales, racha, tiempo por día y por categoría); vistas SQL `stats_*` para el panel de administración: ver [ESTADISTICAS.md](ESTADISTICAS.md) |

**Sesiones**: token de acceso JWT (15 min, `sub` = usuario, `did` = dispositivo) + refresh opaco de un solo uso. Cada refresh devuelve uno nuevo; si alguien usa uno ya usado (copiado), se cierran todas las sesiones de ese inicio de sesión. **Las sesiones sobreviven a los reinicios**: los refresh se guardan (su SHA-256) en PostgreSQL, en `data/postgres`. Si la clave de los tokens de acceso cambia (sin `RACHA_JWT_SECRET`, cada arranque usa una aleatoria), el primer acceso de cada dispositivo recibe un 401 y renueva su token solo. Contraseñas con BCrypt, mínimo 10 caracteres. Cambiar la contraseña cierra las sesiones de los demás dispositivos.

**Sync** (ADR-0012): cada cambio lleva `updatedAt`; gana el más reciente, decidido en una sola sentencia SQL. El progreso conserva siempre la posición más lejana (`maxPositionSec`); el historial solo se añade; un ajuste que pierde vuelve como `stale` con el valor del servidor para que la app pregunte. Un reloj adelantado más de 5 min se recorta. El pull usa un cursor de secuencia del servidor (no fechas).

## Tests

`./gradlew test` (Docker para Testcontainers, PostgreSQL 18): autenticación (registro, login, rotación y reutilización de refresh, logout, tokens manipulados, límites por cuenta+IP y por IP detrás de un proxy, registro cerrado), contraseñas (cambio, recuperación con código, reutilización, límites, cuentas inexistentes), administración (quién es admin, salvaguardas, copias, logs, métricas con token), IP real tras proxies, sync (incremental entre dispositivos, conflictos, progreso que nunca se pierde, historial, ajustes, lápidas, validación, relojes, aislamiento entre usuarios, paginación, vistas REST), telemetría, estadísticas, OpenAPI, reglas unitarias y la arquitectura hexagonal (ArchUnit).
