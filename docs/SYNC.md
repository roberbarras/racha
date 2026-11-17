# Sincronización (cliente)

Spec §6.3 y §7.4. Protocolo y servidor: [ADR-0012](adr/0012-protocolo-sync-y-modelo-remoto.md) y [SERVIDOR.md](SERVIDOR.md). Ids comunes entre dispositivos: [ADR-0004](adr/0004-contentid-y-multiples-raices.md).

La sync es **opcional**: sin cuenta la app funciona igual y no hace ninguna llamada de red. Lo que viaja es el estado (progreso, marcadores, historial, favoritos, ajustes, categorías, raíces, la biblioteca indexada y los resúmenes con IA, [ADR-0016](adr/0016-resumenes-con-ia.md)); **los ficheros nunca salen del dispositivo**.

## Piezas

| Pieza | Dónde | Qué hace |
|---|---|---|
| `AccountService` | `application/sync` | Registro, entrada, salida, renovación del token de acceso (en memoria) y del refresh (rotativo, en el almacén seguro) |
| `SyncEngine` | `application/sync` | Una ronda: push del outbox por lotes de 500, aplica lo que el servidor tiene más reciente, pull desde el cursor |
| `SyncScheduler` | `application/sync` | Cuándo: al empezar, cada N minutos (5 por defecto), al recuperar la red y al pedirlo (entrar, "Sincronizar ahora", resolver un conflicto) |
| `SyncControls` | `application/sync` | Lo que usa la pantalla "Cuenta": estado, entrar/salir, sincronizar ahora, conflictos |
| `SqlSyncLocalStore` + `SyncTriggers` | `data` | Outbox con triggers SQLite, aplicar lo remoto, conflictos, cursor y estado |
| `SqlRootRelinker` | `data` | Vincular una carpeta local a la misma carpeta de otro dispositivo |
| `KtorSyncServer` | `remote` | Adaptador HTTP (Ktor + OkHttp) de los puertos `AuthServer` y `SyncServer` |
| `KeystoreCredentialStore` / `LibSecretCredentialStore` | `android/sync`, `desktop/sync` | Sesión cifrada con una clave del Android Keystore / en el servicio de secretos de Linux (KWallet, GNOME Keyring) por libsecret, con un fichero `0600` si no hay servicio |

## Outbox

- Cada cambio en una tabla sincronizable lo apunta un **trigger SQLite** en `sync_queue` (entidad, id). Solo con la sync activa (`sync_state.recording`) y nunca mientras se aplica lo que llega del servidor (`applying`), así lo remoto no vuelve a subir.
- Al iniciar sesión se **siembra** el outbox con todo lo existente; al cerrar sesión se vacía (los datos locales se quedan).
- Al enviar, la cola se **compacta por entidad**: se manda el estado actual de la fila (o una lápida si ya no existe), no cada cambio intermedio.
- Los ajustes de dispositivo (`DEVICE`: servidor, carpetas, decodificación por hardware…) no viajan; los de usuario (`USER`) sí.
- Un cambio que el servidor rechaza como inválido se reintenta 5 veces y luego se descarta, para no reintentarlo siempre; no bloquea el resto de la cola.

## Conflictos (§6.3)

- **Gana el cambio más reciente** (`updatedAt`), igual en el servidor (en SQL, atómico) y en el cliente al aplicar lo que llega.
- **Progreso**: la posición sigue al cambio más reciente, pero `maxPositionSec` es el máximo de ambos: rebobinar en un dispositivo nunca pierde lo que se llegó a ver. El historial solo se añade.
- **Ajustes** con la política "Preguntar" (por defecto): **solo se pregunta cuando el cambio local pierde**, es decir, este dispositivo tiene un cambio sin enviar y el otro es más reciente (al recibirlo en el pull o porque el servidor responde `stale` al push). Mientras no se responda, el valor local se mantiene y los pulls siguientes solo actualizan la pregunta. "Mantener este" lo vuelve a enviar; "Usar el otro" aplica el del otro dispositivo. Con "Gana el último" no se pregunta nunca.
- Un dispositivo con el **reloj adelantado** no gana siempre: el servidor recorta las horas futuras (más de 5 min) y el cliente adopta la hora que el servidor guardó.

## Carpetas en varios dispositivos (ADR-0004)

Los ids de contenido incluyen la clave de la raíz. Al añadir una carpeta, si la cuenta tiene carpetas de esa categoría que este dispositivo aún no tiene, la app pregunta **"¿Es la misma carpeta que en otro dispositivo?"** (primero la del mismo nombre). Vincularla le da la clave de la raíz remota, así los ids, y con ellos el progreso, coinciden. Una carpeta ya añadida se puede vincular después (icono de enlace en Carpetas): sus elementos y cursos cambian de id y se llevan su progreso, marcadores, historial y favoritos.

## Cuándo sincroniza (batería)

| Plataforma | Con la app en uso | En segundo plano |
|---|---|---|
| Linux | Bucle mientras la app está abierta; la red se comprueba cada 30 s (una consulta al kernel) | — (la app cerrada no sincroniza) |
| Android | Mientras está visible (`repeatOnLifecycle(STARTED)`); la red por `ConnectivityManager` (callbacks, sin sondeo) | WorkManager periódico cada max(N, 15) min **solo con red y batería no baja**, agrupado por el sistema con el trabajo de otras apps; al salir con cambios pendientes, un envío único en cuanto haya red |

## Pantalla "Cuenta"

Servidor (host, puerto, HTTPS, ruta base), entrar o crear cuenta, estado (última sincronización, pendientes, último error), "Sincronizar ahora", intervalo, política de conflictos, cerrar sesión y los ajustes por revisar. Si la sesión caduca o hay ajustes por revisar, Inicio lo avisa con un banner.

**Contraseña** (H13): con la sesión iniciada, «Cambiar contraseña» pide la actual y la nueva dos veces; cierra la sesión de los demás dispositivos y este sigue dentro. Sin sesión, «¿Olvidaste tu contraseña?» (bajo el formulario) pide al servidor del formulario un código para el correo escrito, en el idioma de la app; después se escribe el código y la nueva contraseña. Al recuperarla así se cierran todas las sesiones. Si el servidor no tiene correo configurado, la pantalla lo dice. Detalles del servidor en [SERVIDOR.md](SERVIDOR.md#correo-recuperar-la-contraseña).

## Tests

- `SyncStoreTest`, `RootLinkTest` (JVM, SQLite real): triggers, compactación, lápidas, reglas por entidad, conflictos, vinculación.
- `KtorSyncServerTest` (JVM, servidor simulado): rutas, cuerpos y errores.
- `EndToEndSyncTest` (JVM, **dos bases de datos contra el servidor real** en Docker; en CI, job `sync-e2e`): progreso, marcadores, historial y favoritos de un dispositivo a otro, rebobinado sin perder el máximo, conflicto de ajustes preguntado y resuelto, cola sin conexión que sale al volver la red, rotación del refresh y reentrada sin duplicar el dispositivo. En local:

  ```bash
  RACHA_JWT_SECRET=$(openssl rand -base64 48) RACHA_PORT=18080 docker compose -p racha-e2e up -d --build
  RACHA_E2E_URL=http://localhost:18080 ./gradlew :shared:remote:jvmTest --rerun
  ```
- `AccountUiTest` (escritorio, servidor falso): entrar (con error de credenciales antes), banner de ajustes por revisar en Inicio y respuesta.
- `CredentialStoreTest` (fichero `0600` de respaldo; los tests nunca tocan el llavero real) y `KeystoreCredentialStoreTest` (dispositivo: ida y vuelta, nada en claro en disco, borrado al salir).
