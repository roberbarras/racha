# ADR-0014 · Operación del servidor: datos, copias, administración y recuperación de contraseñas

**Estado**: aceptada (H13, 2026-11-17).

**Contexto**: el usuario va a exponer su servidor a Internet detrás de Cloudflare (Tunnel o proxy, sin decidir) y quiere: datos persistentes y fáciles de llevar a otra máquina, copias automáticas, un administrador, recuperar contraseñas por correo, métricas y logs, y que fallar contraseñas no pueda bloquear a nadie más.

**Decisiones**:
- **Datos en carpetas** junto a `docker-compose.yml` (`data/postgres`, `backups/`), no en volúmenes con nombre: `down -v` no los borra y se ven. Etiqueta `:z` para hosts con SELinux.
- **Copias** con un contenedor `postgres` del mismo número de versión (el `pg_dump` coincide): diaria a una hora fija, al arrancar si la última es de hace más de un día, rotación 14 diarias + 8 lunes, ficheros `600` del dueño de la carpeta. Restaurar y migrar con `pg_restore --clean --single-transaction` (script con confirmación). Se descarta copiar la carpeta de PostgreSQL: no sirve entre versiones mayores.
- **IP real** por un filtro antes de todo lo demás: cabeceras `CF-Connecting-IP`/`X-Forwarded-For` (de derecha a izquierda) solo si la conexión viene de un proxy de confianza (`local` por defecto; `cloudflare` = sus rangos publicados). Solo literales IP, nunca resolución DNS. Los bloqueos son por **cuenta + IP** (10) y por **IP** (50): nunca globales ni de una cuenta desde cualquier sitio.
- **Administrador**: `RACHA_ADMIN_EMAIL` o la primera cuenta (la más antigua en servidores existentes). Rol en la base de datos, comprobado en cada petición a `/admin/**` (quitarlo funciona al momento). Siempre queda un administrador activo y nadie se desactiva a sí mismo.
- **Recuperación**: código de 8 caracteres de un alfabeto sin confusiones (40 bits), 30 min, un uso, uno nuevo anula el anterior, con límites por cuenta e IP; enlace con el código en el fragmento de la URL (no llega a ningún log). Misma respuesta exista o no la cuenta, y el correo sale en segundo plano para que el tiempo de respuesta no lo delate. SMTP propio (`RACHA_MAIL_*`), sin servicios de terceros. Cambiar la contraseña cierra los demás dispositivos; recuperarla, todos.
- **Logs** en fichero JSON (ECS) con rotación, legibles y filtrables desde la administración; **métricas** de Micrometer en `/actuator/prometheus` solo con `RACHA_METRICS_TOKEN` (Prometheus no inicia sesión), y un resumen para el panel.

**Alternativas descartadas**: códigos largos o solo enlace (incómodos de teclear en el móvil); bloqueo por cuenta global (permite dejar a alguien sin acceso); `server.forward-headers-strategy` de Tomcat (no lee `CF-Connecting-IP` ni admite los rangos de Cloudflare con comodidad); copias desde el propio servidor Java (necesitaría `pg_dump` en su imagen).
