# Panel web

H14. Lo sirve el propio servidor en **`/web/`** (la raíz `/` lleva ahí): `https://racha.midominio.es/web/` o, en casa, `http://<ip-del-servidor>:8080/web/`. Se entra con la misma cuenta que en las apps. Evidencias: [evidence/h14](evidence/h14).

## Qué hay

| Página | Contenido |
|---|---|
| **Resumen** | Tiempo visto, racha actual y mejor racha, días activos, completados, a medias, última vez; gráfico de los últimos 30 días, mapa de calor del último año y tiempo por categoría |
| **Actividad** | Sesiones de visionado (número, duración media, velocidad media, % con subtítulos); por hora del día y por día de la semana (en tu zona horaria); por dispositivo; cursos y series con su progreso y tiempo; lo visto recientemente |
| **Dispositivos** | Dónde tienes la sesión iniciada, con plataforma, versión y última actividad. **Cerrar sesión** de uno (un móvil perdido: conserva sus datos y puede volver a entrar) u **olvidarlo** |
| **Cuenta** | Correo, rol, fecha de alta, **cambiar la contraseña** (cierra la sesión de los demás dispositivos) y **tus datos**: descargar todo en un fichero e importar uno (para cambiar de servidor, [ADR-0015](adr/0015-copia-de-seguridad-y-portabilidad.md)) |
| **Servidor** (admin) | Versión, tiempo encendido, usuarios, activos, dispositivos, sesiones, tamaño de la base de datos, memoria, CPU, peticiones y errores; configuración (correo, URL pública, métricas, registro, proxies) con **correo de prueba**; últimas copias; usuarios activos por día, horas por usuario, funciones usadas, errores por versión, errores recientes de las apps y fallos de sincronización |
| **Usuarios** (admin) | Todas las cuentas: rol, estado, dispositivos, última actividad; **hacer o quitar admin** y **desactivar** (cierra todas sus sesiones). Siempre queda un administrador activo |
| **Logs** (admin) | Los eventos más recientes del servidor, filtrables por nivel |

Español o inglés según el idioma del navegador; claro u oscuro según el sistema; se adapta al móvil.

## Seguridad

- **La sesión del navegador** no deja el *refresh token* al alcance de JavaScript: va en una cookie `HttpOnly`, `SameSite=Strict`, limitada a `/auth/web` (y `Secure` cuando llegas por HTTPS, también detrás de un proxy que lo indique con `X-Forwarded-Proto`). La página solo guarda en memoria el token de acceso de 15 minutos y lo renueva sola. Renovar y salir exigen además la cabecera `X-Racha-Web`, que otra web no puede añadir.
- El navegador es un **dispositivo más** (plataforma `web`, nombre «Web · navegador · sistema»): aparece en la lista y se puede cerrar desde otro sitio. El mismo navegador en el mismo sistema reutiliza su dispositivo aunque se borren las cookies.
- **Content-Security-Policy** estricta: solo código y estilos del propio servidor, sin `iframe`, sin conexiones a terceros. Sin bibliotecas externas (los gráficos son SVG propios) y todo el texto se pinta como texto, nunca como HTML.
- Las páginas de administración comprueban el rol en el servidor en cada petición; ocultarlas en el menú es solo comodidad.

## API nueva

`POST /auth/web/login`, `/auth/web/refresh`, `/auth/web/logout`; `GET /stats/me/details?zone=`; `POST /devices/{id}/sign-out`, `DELETE /devices/{id}` (y `GET /devices` dice las sesiones activas y cuál es el dispositivo que llama); `GET /admin/activity`. Detalle en [api/openapi.json](api/openapi.json).

## Tests

`WebPanelApiTest`: cookie `HttpOnly`/`SameSite`, rotación, cabecera obligatoria, salida, reutilización del dispositivo; cerrar y olvidar dispositivos (y nunca los de otro); estadísticas detalladas (zona horaria, títulos editados, subtítulos, rachas); actividad solo para admin; la página con su CSP. Revisión visual con Chrome *headless* sobre un servidor aislado con datos de ejemplo (escritorio claro y oscuro, móvil).
