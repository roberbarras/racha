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
| H5 | Reproductor Android (Media3) + UI de reproductor compartida | ✅ Cerrado |
| H6 | Reproductor Linux (libmpv) | ✅ Cerrado |
| H7 | Visor de recursos HTML/URL/TXT/PDF | ✅ Cerrado |
| H8 | Backend: auth email/JWT, modelo remoto, endpoints §7.3, OpenAPI | ✅ Cerrado |
| H9 | Sync extremo a extremo y conflictos | ✅ Cerrado |
| H10 | Estadísticas y telemetría | ✅ Cerrado |
| H11 | Ajustes completos, notificaciones, widget, deep links, export/import, onboarding | ✅ Cerrado |
| H12 | Pulido UI/UX, empaquetado AppImage/APK firmado, entrega | ✅ Cerrado |
| 1.0 | Tras la entrega: qué contiene una carpeta (ADR-0013), sesiones tras reiniciar el servidor, versión 1.0.0 | ✅ Hecho; publicación de `v1.0.0` pendiente de tu OK |
| H13 | Servidor seguro y operable: IP real y bloqueos por cuenta+IP, datos y copias, admin, contraseñas, métricas y logs | ✅ Cerrado |
| H14 | Panel web (usuario y administración) | ✅ Cerrado |
| H15 | Reproducción cómoda: marcar visto, recordar velocidad/audio/subtítulos, atajos en Linux, PiP y solo audio | ✅ Cerrado |
| H16 | Estadísticas completas, copia de seguridad local y datos portables entre servidores | ✅ Cerrado |
| H17 | Resúmenes con IA a partir de subtítulos, con varios proveedores en el orden que elijas | ✅ Cerrado · publicado en **v1.1.0** |
| H18 | Chromecast, DLNA y pantalla externa con el móvil como mando | ✅ Cerrado |
| H19 | Carpetas remotas (Nextcloud, Google Drive): descargar al dispositivo todo o solo cursos concretos | ✅ Cerrado · publicado en **v1.2.0** |

## Criterios de aceptación (§12)

| # | Criterio | Hito previsto | Estado |
|---|---|---|---|
| 1 | Escaneo de la estructura de ejemplo | H3 | ✅ H3 (dominio, SQLite, Linux real, Android) |
| 2 | Home con "Continuar viendo" y pestaña principal | H4 | ✅ H4 (caso de uso + UI) |
| 3 | Reproductor Android y Linux completo | H5, H6 | ✅ Android (H5) y Linux (H6) |
| 4 | Offline al 100 % | H5, H9 | ✅ Reproducción y progreso 100 % locales (H5); la sync es opcional, nunca bloquea y guarda la cola sin conexión (H9) |
| 5 | Sync ≤5 min / al recuperar red, conflictos | H9 | ✅ H9 (dos clientes contra el servidor real en CI) |
| 6 | Recurso HTML como elemento del curso | H7 | ✅ H7 (Android y Linux, aislado a su curso) |
| 7 | IDs estables, archivado | H3 | ✅ H3 (mover conserva progreso; borrar archiva) |
| 8 | Estadísticas persistidas en servidor | H10 | ✅ H10 (visualizaciones, horas, errores, fallos de sync y eventos; vistas SQL consultadas en tests y contra el servidor real) |
| 9 | Auth (email/password + JWT; OAuth aplazado, ADR-0006) | H8, H9 | ✅ Servidor (H8) y registro/login/refresh desde las apps (H9); OAuth aplazado (ADR-0006) |
| 10 | Idiomas es/en y subtítulos por idioma | H4, H5 | ✅ UI es/en (H4) con selector de idioma de la app (H11); subtítulos por idioma, apagados por defecto (H5) |
| 11 | Modo Estudio/Ocio | H4 | ✅ H4 (caso de uso + UI) |
| 12 | Diseño con identidad propia | H4, H12 | ✅ Identidad propia en todas las pantallas (Home, Detalle, reproductores, visor, Ajustes, Progreso, onboarding, widget), adaptada a escala y ancho; revisión de pantallas en H12 ([evidencias](docs/evidence/h12)) |

## Alcance añadido o ajustado por el usuario (2026-11-11)

- Marcado manual visto/no visto por elemento, sección, curso y "todo hasta aquí" (cursos importados a medias) → H4/H5.
- Botón de pantalla completa en el reproductor; el modo se mantiene al pasar al siguiente elemento con autoplay → H5/H6.
- Categorías sin tipo: todas las heurísticas se aplican en cascada a cada carpeta; `LibraryItem.kind` describe la estructura detectada → H3.
- Visor PDF integrado → H7.
- Gestos en Linux: clic/doble clic en bordes ±N s, arrastre horizontal = seek, rueda/arrastre vertical derecho = volumen, arrastre vertical izquierdo = brillo del vídeo, mantener = 2x → H6.
- OAuth aplazado; OTP por email posible más adelante (ADR-0006).
- Versiones más recientes en lugar de las citadas en la spec (ADR-0001).
- §3.2.2 "partes de un mismo vídeo" por duración: fuera de alcance (2026-11-13); cada vídeo va por separado, ordenado y con autoplay.

---

## Mejoras tras la 1.2.0 (v1.2.1) — 🔄 en CI

**Hecho** (a petición del usuario)
- **Arranque en Linux medible**: `RACHA_LOG=debug` (o `--verbose`) saca todo el log a la terminal, con el tiempo de cada fase del arranque; los mensajes de las librerías (SLF4J) llegan al log en vez de perderse ([AJUSTES.md](docs/AJUSTES.md)).
- **Base de datos en Linux con una sola conexión**: el driver JDBC de SQLDelight reabría SQLite en cada consulta fuera de una transacción (268 aperturas en los primeros segundos, ahora 1; un escaneo vacío pasa de 1,3 s a 0,5 s). Android ya funcionaba así.
- **Lista de clases del reproductor (y del visor de recursos) por secciones**, con la duración de cada sección y, arriba, la del curso con lo visto y lo que queda.
- **Colores del tema a elección** (Ajustes → General): acento, tono del fondo y paneles oscuros, con muestras o cualquier `#RRGGBB`; el resto de la paleta se deriva y el texto mantiene siempre un contraste legible ([AJUSTES.md](docs/AJUSTES.md#colores-del-tema)).
- **Detalle del curso**: duración de cada sección junto a su título; junto al total, lo visto y lo que queda; las clases vistas con fondo verdoso en lugar del tick.
- **Portada desde cualquier imagen de la carpeta del curso** (`.jpg`, `.jpeg`, `.png`, al nivel de las secciones): ya no hace falta llamarla `poster`/`folder`/`cover`, que siguen mandando si hay varias. Sin imagen, lo de siempre ([ESCANEO.md](docs/ESCANEO.md)).

**Verificación**
| Comprobación | Resultado |
|---|---|
| Tiempos visto/restante (vistas enteras, a medias hasta lo más lejos, recursos fuera) | ✅ |
| Base de datos de escritorio: nueva, reinicio, migración desde la v1 con la conexión persistente | ✅ |
| Arranque en Xvfb con `--verbose`: fases medidas, primer fotograma ~3 s; en el escritorio real del usuario, pendiente de su log | ✅ / ⏳ |
| UI escritorio: detalle (claro y oscuro, móvil) y panel de clases por secciones (capturas) | ✅ |
| Portadas: cualquier imagen junto a las secciones, las nombradas primero, las de dentro de una sección y las ocultas no cuentan; sustituye al fotograma al reescanear | ✅ |
| Colores del tema: contraste garantizado con colores extremos en claro y oscuro; muestras, color escrito y restablecer; la app entera con colores propios (capturas) | ✅ |
| CI | ⏳ |

---

## H19 · Carpetas remotas — ✅ cerrado

Decisión: [ADR-0018](docs/adr/0018-carpetas-remotas.md). Documento técnico y configuración: [CARPETAS-REMOTAS.md](docs/CARPETAS-REMOTAS.md). Evidencias: [docs/evidence/h19](docs/evidence/h19). La guía de uso se escribirá con la aplicación completa.

**Hecho**
- **Carpetas remotas** en Carpetas (Android y Linux): **Nextcloud** (o cualquier WebDAV) y **Google Drive**. Se elige la carpeta del servidor, la categoría, qué contiene (biblioteca, un curso o automático) y **qué descargar: todo, o solo los cursos elegidos** (con su tamaño y lo que ya está aquí).
- Se descarga a una carpeta normal de la biblioteca (por defecto `~/Vídeos/Racha/<nombre>` en Linux y `Racha/<nombre>` en Android): los cursos aparecen al llegar y se usan como cualquier otro. Descargas continuables, pequeños primero, comprobación de espacio, nada se borra sin pedirlo («Liberar espacio», «Quitar»).
- Nextcloud con su **login flow** (contraseña de aplicación revocable, nunca la de la cuenta) o manual; Google Drive con OAuth y PKCE, **solo lectura**, con un cliente OAuth propio (pasos en el documento). Credenciales en el llavero o el Keystore.
- **Corrección (H18)**: al dejar de enviar a la tele, el servidor de ficheros dejaba su puerto aceptando conexiones sin atenderlas; ahora se cierra de verdad. Era lo que colgaba el CI: un test esperaba para siempre en una de esas conexiones. El CI tiene ahora límites de tiempo y, si algo se cuelga, publica qué test y qué hacía cada JVM.
- **Release v1.2.0** (con tu OK): incluye H18 y H19; el servidor usa 1.2.0 por defecto en `docker-compose.yml`.
- Segundo plano: Android con un *worker* en primer plano (notificación de progreso, «solo con Wi-Fi» opcional, cada 6 h mientras haya carpetas remotas); Linux mientras la app está abierta. Esquema local v10.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio: cursos de la lista remota, qué falta o cambió, qué liberar | ✅ |
| Caso de uso con SQLite y ficheros reales: cursos elegidos → biblioteca, repetir, elegir más, liberar, «todo», quitar, credenciales y espacio | ✅ |
| Nextcloud simulado: login flow, carpetas, lista, descarga continuada | ✅ |
| Google simulado: OAuth PKCE en loopback, Drive con páginas, descarga, acceso revocado | ✅ |
| UI escritorio: añadir por el navegador, elegir un curso, llega a la biblioteca (capturas) | ✅ |
| CI | ✅ [run 44324154039](https://github.com/roberbarras/racha/actions/runs/44324154039) |
| Con tu Nextcloud y tu Google Drive reales | ⏳ Pendiente de que lo pruebes |

---

## H18 · Enviar a la tele y a otras pantallas — ✅ cerrado

Decisión: [ADR-0017](docs/adr/0017-enviar-a-televisores-y-pantallas.md). Documento técnico: [REPRODUCTOR.md](docs/REPRODUCTOR.md#enviar-a-la-tele-y-a-otras-pantallas-h18). Evidencias: [docs/evidence/h18](docs/evidence/h18). La guía de uso se escribirá con la aplicación completa.

**Hecho**
- **«Enviar a»** en el reproductor (Android y Linux): Chromecast y televisores DLNA de la red local, sin Google Play services. La clase sigue en la tele donde estaba y **este dispositivo es el mando**: pausa, avanza, cambia de clase o de subtítulos; el progreso, la siguiente clase, los marcadores y las estadísticas siguen funcionando. «Volver a este dispositivo» la trae de vuelta; si se pierde la conexión, vuelve sola.
- **Pantalla conectada al móvil o la tableta** (HDMI, USB-C, Miracast): el vídeo, con sus subtítulos, se ve en ella y el móvil queda como mando; al desconectarla vuelve al móvil.
- Los ficheros se sirven a la tele solo mientras se envía, solo lo publicado y bajo una ruta imposible de adivinar; la red solo se busca con el panel abierto.
- Android 17: permiso de acceso a la red local, pedido al abrir «Enviar a».

**Verificación**
| Comprobación | Resultado |
|---|---|
| Motor: pasar a la tele y volver con la misma posición, órdenes del controlador en la tele, siguiente clase en la tele, tele perdida o inalcanzable | ✅ |
| Chromecast simulado (Cast v2): lanzar el receptor, cargar con subtítulo, órdenes, fin del vídeo, cerrar | ✅ |
| Televisor DLNA simulado: descripción, carga en la posición, órdenes, terminado frente a parado | ✅ |
| Servidor de ficheros: rangos, `HEAD`, WebVTT con CORS, nada sin publicar, cierre | ✅ |
| Respuestas mDNS y SSDP | ✅ |
| UI escritorio: panel, mando, volver, pantalla conectada y desconectada (capturas) | ✅ |
| Emulador: pantalla externa (overlay display) reproduce, vuelve, y vuelve sola al desconectarla | ✅ |
| CI | ✅ [run 44202727774](https://github.com/roberbarras/racha/actions/runs/44202727774) |
| Con un Chromecast o televisor reales | ⏳ Pendiente de que lo pruebes |

---

## H17 · Resúmenes con IA — ✅ cerrado

Decisión: [ADR-0016](docs/adr/0016-resumenes-con-ia.md). Guía: [docs/RESUMENES-IA.md](docs/RESUMENES-IA.md). Evidencias: [docs/evidence/h17](docs/evidence/h17).

**Hecho**
- **«Resumen y claves»** de una clase a partir de sus subtítulos, y **resumen de sección** a partir de los de sus clases. Están en el menú de cada clase, en la cabecera de cada sección y en un panel del reproductor. Es Markdown con Resumen, Claves, Términos y Para repasar, en el idioma de la app; se puede guardar como `.md`, volver a generar o borrar.
- **Varios proveedores** (Gemini, Groq, NVIDIA, OpenRouter, Mistral, Cerebras, DeepSeek, Ollama o cualquiera compatible con OpenAI). Se prueban en el **orden que elijas** y, si uno falla (sin cuota gratuita, clave, modelo, caído), lo intenta el siguiente. Clave por proveedor en el llavero o el Keystore; «Probar» y «Cargar modelos».
- Clases **sin `.srt`**: opción desactivada (y sin botón en el reproductor).
- Clases largas por partes. Los resúmenes se guardan con los datos de la app (esquema v9), **se sincronizan** (servidor: Flyway V8, `/summaries`) y entran en la copia completa.
- **Release v1.1.0** (con tu OK): incluye H13–H17; el servidor usa 1.1.0 por defecto en `docker-compose.yml`.
- Corrección: los apóstrofos de algunos textos en inglés se veían con una barra (`system\'s`).

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio: subtítulos a texto, troceado, instrucciones, lista de proveedores | ✅ |
| Caso de uso con SQLite real: paso al siguiente proveedor, sin clave o desactivado, por partes, sección, sin subtítulos, sin proveedores, todos fallan, sync | ✅ |
| Cliente HTTP compatible con OpenAI contra un servidor simulado (petición, modelos, errores, «pensamiento») | ✅ |
| Servidor: resúmenes entre dispositivos, `/summaries`, validación; OpenAPI regenerado | ✅ |
| UI escritorio: Markdown, opción desactivada sin `.srt`, generar y guardar `.md`, añadir proveedor (capturas) | ✅ |
| CI | ✅ [run 44196768481](https://github.com/roberbarras/racha/actions/runs/44196768481) (una ejecución anterior se colgó en el emulador; el smoke de la release tiene ahora límites de tiempo por fase y deja informe) |

---

## H16 · Estadísticas, copias y datos portables — ✅ cerrado

Decisión: [ADR-0015](docs/adr/0015-copia-de-seguridad-y-portabilidad.md). Documentos: [ESTADISTICAS.md](docs/ESTADISTICAS.md#en-las-apps-h16), [AJUSTES.md](docs/AJUSTES.md), [PANEL-WEB.md](docs/PANEL-WEB.md). Evidencias: [docs/evidence/h16](docs/evidence/h16).

**Hecho**
- **Panel de estadísticas completo en las apps**: indicadores, mapa del año, 12 semanas, hora del día, día de la semana, sesiones (duración, velocidad, subtítulos), categorías, dispositivos (con cuenta), cursos con progreso y tiempo, visto recientemente y completados. Funciona sin servidor; con cuenta suma todos los dispositivos.
- **Copia de seguridad completa en las apps** (Ajustes → Almacenamiento): exportar y restaurar progreso, marcadores, historial, favoritos, ajustes, carpetas, categorías y estadísticas; sin secretos.
- **Tus datos de un servidor a otro** (petición del usuario): el panel web descarga todo lo de la cuenta en un fichero y lo importa en otra cuenta u otro servidor; repetirlo no duplica nada. El mismo formato que la copia de las apps (se entienden entre sí).
- **Corrección**: los retoques de títulos y orden se perdían en un dispositivo nuevo (el escaneo era "más reciente"); ahora llegan.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Reproductor: totales por hora, día, curso y sesiones al terminar de ver | ✅ |
| Estadísticas: solo este dispositivo; combinadas con el servidor (la mayor, dispositivos del servidor) | ✅ |
| UI escritorio: pantalla de Progreso completa (captura) | ✅ |
| Copia completa: dispositivo A → B (carpeta, curso retocado, progreso, historial, ajustes, memoria, estadísticas), dos veces sin duplicar; fichero del servidor; ficheros ajenos rechazados | ✅ |
| Servidor: exportar, "otro servidor" (cuenta borrada, otra nueva) e importar con estadísticas; repetir; formatos ajenos; fichero de una app | ✅ |
| Sync: un retoque antiguo llega a un curso escaneado después, sin pisar uno propio más reciente | ✅ |
| CI | ✅ [run 44182046166](https://github.com/roberbarras/racha/actions/runs/44182046166) |

---

## H15 · Reproducción cómoda — ✅ cerrado

Documento: [docs/REPRODUCTOR.md](docs/REPRODUCTOR.md#comodidad-h15). Evidencias: [docs/evidence/h15](docs/evidence/h15).

**Hecho**
- **Recordar velocidad, pista de audio y subtítulos** por curso o serie, con lo último usado como punto de partida; por defecto, la primera pista de audio y sin subtítulos (esquema v8).
- **Desmarcar una sección** (curso y lección ya iban en los dos sentidos).
- **Atajos de teclado configurables en Linux** (Ajustes → Reproductor), con ayuda en el reproductor (H o F1) y acciones nuevas: silenciar, más lento/rápido, cambiar subtítulos, añadir marcador.
- **Solo audio**: sin decodificar vídeo; en Android sigue sonando con la pantalla apagada o la app en segundo plano (servicio de reproducción con controles en la notificación y la pantalla de bloqueo). PiP ya existía en las dos plataformas.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio: elección por curso/última, audio por idioma/posición, subtítulos; atajos (conflictos, codificación) | ✅ |
| SQLite y controlador: lo elegido sigue en el siguiente vídeo y al volver a abrir la app; "sin subtítulos" también; migración v7 → v8 | ✅ |
| UI escritorio: desmarcar sección; editor de atajos (capturar, mover entre acciones, Esc, restaurar) | ✅ |
| Linux, harness con mpv real en Xvfb: solo audio (`vid=no`, sigue avanzando, vuelve el vídeo) | ✅ |
| Android en el emulador: solo audio (sin vídeo, servicio activo, pantalla puede apagarse, sigue en segundo plano) | ✅ |
| CI | ✅ [run 44176778483](https://github.com/roberbarras/racha/actions/runs/44176778483) |

---

## H14 · Panel web — ✅ cerrado

Documento: [docs/PANEL-WEB.md](docs/PANEL-WEB.md). Evidencias: [docs/evidence/h14](docs/evidence/h14).

**Hecho**
- Panel en `/web/` servido por el propio servidor (sin otro contenedor ni bibliotecas externas): **Resumen** (indicadores, 30 días, mapa de calor del año, categorías), **Actividad** (sesiones, horas, días de la semana, dispositivos, cursos y series con progreso, lo visto reciente), **Dispositivos** (cerrar sesión u olvidar uno), **Cuenta** (cambiar contraseña).
- Para administradores: **Servidor** (estado, recursos, configuración con correo de prueba, copias, actividad de todo el servidor, errores y fallos de sincronización), **Usuarios** (rol y desactivar), **Logs** (por nivel).
- Sesión del navegador con el *refresh token* en cookie `HttpOnly`/`SameSite=Strict`, el navegador como dispositivo `web`, CSP estricta. Español/inglés, claro/oscuro, móvil.
- **Release v1.0.0 publicada** (https://github.com/roberbarras/racha/releases/tag/v1.0.0): AppImage y APK firmado con tu clave; CI pone ya la versión del tag en el APK (antes salía 0.0.1).

**Verificación**
| Comprobación | Resultado |
|---|---|
| Servidor: 54 tests (sesión web, dispositivos, estadísticas detalladas, actividad de admin, página y CSP, OpenAPI) | ✅ |
| Revisión visual con datos de ejemplo en un servidor aislado: escritorio claro y oscuro, móvil | ✅ |
| CI | ✅ [run 44139605649](https://github.com/roberbarras/racha/actions/runs/44139605649) |

---

## H13 · Servidor seguro y operable — ✅ cerrado

Decisión: [ADR-0014](docs/adr/0014-operacion-del-servidor.md). Guías: [SERVIDOR.md](docs/SERVIDOR.md), [DESPLIEGUE.md](docs/DESPLIEGUE.md), [`.env.example`](.env.example).

**Hecho**
- **IP real detrás de proxies** (`RACHA_TRUSTED_PROXIES`: `local` por defecto para Cloudflare Tunnel, nginx o Caddy; `cloudflare` para su proxy). **Bloqueos por cuenta + IP** (10 fallos) y **por IP** (50): fallar contraseñas ya no puede bloquear a nadie más ni a tu cuenta desde otro sitio.
- **Datos en `data/postgres`** (`down -v` no los borra), **copias diarias** en `backups/` con rotación (14 + 8 semanales) y copia a demanda, **restauración y migración** con `tools/restore-backup.sh`.
- **Administrador**: `RACHA_ADMIN_EMAIL` o la primera cuenta; API `/admin/*`: estado, recuentos, métricas, usuarios (rol, desactivar), copias, logs por nivel, correo de prueba. Salvaguardas: siempre un admin activo.
- **Contraseñas**: cambio (cierra los demás dispositivos) y **recuperación por correo** con código de 8 caracteres y enlace a `/reset-password`; SMTP configurable (`RACHA_MAIL_*`). En las dos apps (pantalla Cuenta) y en la página web.
- **Métricas** Prometheus con token y **logs** JSON con rotación.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Servidor: 49+ tests (IP real, bloqueos por cuenta+IP e IP, cambio y recuperación de contraseña, admin y salvaguardas, logs, copias, métricas con token, OpenAPI) | ✅ |
| Compose aislado: arranque, copia inicial y a demanda, restauración (una cuenta posterior a la copia desaparece), rotación | ✅ |
| Clientes: protocolo Ktor, cambio y recuperación en la UI de escritorio | ✅ |
| CI (todo, con la release firmada con tu clave) | ✅ [run 44128453934](https://github.com/roberbarras/racha/actions/runs/44128453934) |

**Notas**
- Firma de Android: los secretos ya están en GitHub y CI firma la release con tu clave.
- Docker Compose acepta `CLAVE: valor` en el `.env`, pero lo estándar es `CLAVE=valor` (así está en `.env.example`).
- Si ya tenías el servidor arrancado con la versión anterior (volumen `racha_racha-db`), hay que pasar los datos a la carpeta nueva: [SERVIDOR.md](docs/SERVIDOR.md#datos-copias-y-migración-a-otro-servidor).

---

## 1.0 · Mejoras tras la entrega y versión 1.0.0 — ✅ hecho

Peticiones del usuario tras H12. Decisión en [ADR-0013](docs/adr/0013-que-contiene-una-carpeta.md). Evidencias: [docs/evidence/v1.0](docs/evidence/v1.0).

**Hecho**
- **Dos formas de añadir contenido** (Carpetas y primer uso): **Añadir biblioteca** (cada subcarpeta es un curso o serie) y **Añadir curso o serie** (la carpeta es el curso y sus subcarpetas, las secciones, se llamen como se llamen). Antes de añadir se ve lo que saldrá ("Se añadirá «X»: 3 secciones · 5 vídeos y recursos" / "Se añadirán 12 cursos o series: …"), con cambio a la otra opción y aviso si los nombres (`01 - …`, temporadas, fechas) apuntan a un solo curso.
- **Cambiar qué contiene una carpeta ya añadida**: reescanea conservando progreso, marcadores e historial (los vídeos no cambian de id); lo guardado a nivel de curso queda en los cursos archivados y vuelve si se deshace. Las carpetas anteriores siguen con la detección automática hasta que se cambien.
- El tipo **se sincroniza** con la carpeta; al vincularla con la de otro dispositivo se adopta el de aquella. Esquema local v7 (sin cambios en el servidor: va en el JSON del `categoryRoot`).
- **Sesiones y reinicios del servidor**: ya sobrevivían (los refresh están en PostgreSQL y `/auth` no depende de la clave); se corrige el aviso y la documentación que decían lo contrario, y la pantalla de estadísticas ahora renueva el token tras un 401 como ya hacían la sync y la telemetría.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio: ambos tipos, ids de vídeos estables al cambiar, carpeta vacía | ✅ |
| SQLite: vista previa sin escribir, añadir como curso, cambiar a biblioteca y volver (progreso intacto, curso restaurado, sin "novedades" falsas) | ✅ |
| Sync: el tipo viaja, cambiarlo se encola, dispositivo antiguo = automático, vincular adopta el tipo remoto; migración v6 → v7 | ✅ |
| UI escritorio: vista previa, cambio de opción, añadir y cambiar después (capturas); onboarding con las dos opciones | ✅ |
| Estadísticas: 401 tras reinicio → renovación y datos | ✅ |
| AppImage 1.0.0 local: `--self-test` aislado (sin tocar los datos reales) | ✅ |
| APK 1.0.0 (versionCode 10000, R8, 3,1 MB) | ✅ firmado con la clave de depuración: no hay clave de release |
| CI (todas las piezas, emulador y release con su *smoke*) | ✅ [run 44068465931](https://github.com/roberbarras/racha/actions/runs/44068465931) |

---

## H12 · Pulido, empaquetado y entrega — ✅ cerrado

Guías: [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md) (servidor, Android, Linux, firma y versiones), [README](README.md) (usuarios y desarrolladores), [ARCHITECTURE.md](docs/ARCHITECTURE.md) (revisado contra el código, con las decisiones de §13), [api/openapi.json](docs/api/openapi.json). Precisión en [ADR-0009](docs/adr/0009-empaquetado-y-distribucion.md). Evidencias: [docs/evidence/h12](docs/evidence/h12).

**Hecho**
- **AppImage** con libmpv y sus bibliotecas dentro (linuxdeploy, dejando fuera lo que debe ser del sistema), construido en CI sobre Ubuntu 24.04; `--self-test` comprueba el motor de vídeo sin ventana ni sonido y CI lo ejecuta sobre el AppImage; `--install`/`--uninstall` añaden la entrada del menú y el manejador de `racha://` en `~/.local/share`.
- **APK de release** con R8, versión desde Gradle y firma desde variables de entorno (secretos de CI; nunca en el repositorio). CI la prueba con un *smoke test* sobre el APK minificado (deep links y 1 500 eventos de interfaz sin cierres ni ANR) y publica AppImage y APK como *release* de GitHub al subir un tag `vX.Y.Z`.
- **Robustez**: el visor HTML de Android se recupera si el sistema mata el proceso del renderizador (antes cerraba la app); el estado del idioma de la app ya no se arrastra entre ventanas o pruebas; corregida una carrera en el test de notificaciones.
- **Pulido**: revisión de todas las pantallas en capturas; tarjetas de "Hoy toca" con la misma altura; Historial guarda sus filas en un view model y las carga con una sola consulta (antes, dos por entrada en cada cambio, dentro de la composición; en los tests a veces no llegaban a mostrarse); capturas nuevas de Búsqueda, Mi lista, Categoría e Historial.
- **Entregables §15**: README para usuarios y desarrolladores, guía de despliegue, arquitectura con las decisiones de §13, OpenAPI versionado (un test comprueba que la copia del repositorio coincide con el servidor), tokens y sistema de componentes ([DISENO.md](docs/DISENO.md)).

**Verificación**
| Comprobación | Resultado |
|---|---|
| AppImage local: `--self-test` (libmpv incluido, `RUNPATH $ORIGIN`) y arranque aislado en Xvfb 25 s sin errores | ✅ |
| AppImage en CI (Ubuntu 24.04): construcción y `--self-test` | ✅ |
| APK de release (R8, 3,1 MB): *smoke test* en el emulador local y en CI | ✅ |
| Android en el emulador (debug): 42 tests | ✅ |
| Servidor: OpenAPI publicado y comprobado; resto de la batería | ✅ |
| Escritorio: toda la batería de UI y de datos | ✅ |
| CI (servidor + cliente + AppImage + e2e de sync + emulador + release con su *smoke*) | ✅ [run 44058588616](https://github.com/roberbarras/racha/actions/runs/44058588616) |

**Criterios §12**: ✅ **12**. Todos los criterios cumplidos salvo **9** en su parte de OAuth (aplazado por tu decisión, ADR-0006).

**Pendiente / notas**
- **Firma de la release**: hasta que añadas los secretos `RACHA_KEYSTORE_BASE64`, `RACHA_KEYSTORE_PASSWORD`, `RACHA_KEY_ALIAS` y `RACHA_KEY_PASSWORD` (guía en DESPLIEGUE.md), CI firma la release con la clave de depuración y lo avisa.
- **Primera versión publicada**: no he subido ningún tag `v*`; publicar es decisión tuya (`git tag v1.0.0 && git push origin v1.0.0`).
- El AppImage requiere glibc 2.39 o posterior (distribuciones de 2024 en adelante).
- JavaFX sigue en 26.0.2: no hay versión posterior a 27.0.1, la que rompía el pintado de JFXPanel.
- La release de Android no se prueba con la batería instrumentada completa (la infraestructura de tests choca con R8), sino con el *smoke test* sobre el APK real.
- En CI, el *smoke* de la release va en su propio trabajo sobre la imagen AOSP del emulador (API 37): en la imagen con Google APIs, Chrome, WebView y Trichrome ocupan ~680 MB de los 774 MB de la partición de datos y no cabía la release; ampliar la partición dejaba a las apps sin sus carpetas de datos. La app no usa nada de Google Play.
- Fallos puntuales de CI (construcción de la release, `docker compose` del e2e, un test de conflicto de ajustes) quedan ahora explicados en anotaciones públicas; la construcción de la release se reintenta una vez.
- Las capturas de pantallas con datos de la base de datos se hacen renderizando cada pantalla directamente: a través de la navegación, el reloj virtual del arnés de tests de Compose hace la temporización poco fiable.
- Durante la prueba del AppImage, un primer arranque usó por error tu carpeta real de datos (Java ignora `$HOME`): migró tu base de datos al esquema actual, sin pérdida y compatible con AppImages anteriores. Las pruebas posteriores van aisladas con `-Duser.home`.

**Bloqueos**: ninguno.

---

## H11 · Ajustes, notificaciones, widget, deep links, export/import y onboarding — ✅ cerrado

Documento: [docs/AJUSTES.md](docs/AJUSTES.md). Precisión en [ADR-0010](docs/adr/0010-formato-export-config.md). Evidencias: [docs/evidence/h11](docs/evidence/h11).

**Hecho**
- **Ajustes completos (§9)**, aplicados al momento: general (idioma de la app que cambia toda la interfaz sin reiniciar, tema, acento, tamaño de texto y, en Linux, tamaño de la interfaz), categorías (crear, renombrar, borrar, reordenar, principal, visibilidad por modo, exclusiones), reproductor, progreso, escaneo, sincronización, notificaciones, almacenamiento (cachés, historial local, exportar/importar), avanzado (nivel de log, endpoint de telemetría, proxy y User-Agent que se aplican de verdad) y acerca de.
- **Exportar/importar** la configuración (ADR-0010), sin secretos, carpetas ni la sync activa; ficheros inválidos o de versiones futuras rechazados sin tocar nada.
- **Búsqueda** sin mayúsculas ni acentos y **en el contenido** de recursos HTML/TXT/enlaces, con fragmento y la coincidencia en negrita (esquema v6).
- **Notificaciones**: novedades tras escaneos automáticos, sync fallida (sin ruido offline) y recordatorio diario solo los días sin estudiar. Android con canales, permiso y WorkManager; Linux por D-Bus.
- **Deep links** `racha://item|play/<id>` en Android y Linux; en Linux, **una sola instancia** que recibe enlaces por socket Unix y se trae al frente.
- **Widget de Android** (racha, hoy toca con su curso y progreso, continuar).
- **Onboarding** del primer uso (idioma, modo, carpetas, recordatorio, sync), solo con la biblioteca vacía y sustituyendo a la app mientras dura.
- **Portadas con IA** (opcionales, §3): endpoint, prompt base y API key en el almacén protegido del sistema.
- Filas de interruptor que se activan desde toda la fila; log de la app con nivel (Linux `~/.cache/racha/racha.log`, Android logcat).

**Verificación**
| Comprobación | Resultado |
|---|---|
| JVM (SQLite real): ajustes y categorías, export/import (ida y vuelta, ficheros malos), almacenamiento, búsqueda y contenido, notificaciones, portadas | ✅ |
| Adaptadores HTTP con servidores simulados (sync, telemetría, IA de imágenes); ningún test llama a servicios reales | ✅ |
| Linux: instancia única por socket, llamada D-Bus de notificaciones (sin mostrar ninguna), hora del recordatorio | ✅ |
| UI de escritorio: idioma al momento en toda la app, interruptores, categorías, onboarding completo (y que una biblioteca existente no lo ve), deep links; capturas | ✅ |
| Android en el emulador: 42 tests, incluidos notificaciones en sus canales, Keystore y el widget renderizado | ✅ |
| CI (servidor + cliente + arneses Linux + e2e + emulador) | ✅ [run 43838580857](https://github.com/roberbarras/racha/actions/runs/43838580857) |

**Criterios §12**: ✅ **10** (idioma de la app seleccionable, además del del sistema); 🟡 **12** avanza (Ajustes, Progreso, onboarding y widget con la identidad propia; pulido → H12).

**Pendiente / notas**
- En Linux el recordatorio diario solo llega con la app abierta (no hay planificador del sistema para una app cerrada).
- El AppImage declara `x-scheme-handler/racha`; desde H12, `Racha.AppImage --install` lo registra.
- Lint avisa de que el WebView de recursos no implementa `onRenderProcessGone`: resuelto en H12.

**Bloqueos**: ninguno.

---

## H10 · Estadísticas y telemetría — ✅ cerrado

Documento: [docs/ESTADISTICAS.md](docs/ESTADISTICAS.md).

**Hecho**
- Telemetría del cliente (§8, §11): sesiones de visionado con los campos de §8, vistas de recursos, uso de funciones (PiP, A-B, marcadores, captura, pantalla completa, velocidad, subtítulos, pista de audio), apertura de la app (plataforma, versión, modo) y fallos de sync; errores no capturados con stack trace en Linux y Android.
- Solo con cuenta y solo hacia su servidor (o el endpoint de telemetría configurado); cola local acotada (esquema v5) que se vacía al cerrar sesión; envío en lotes tras cada ronda de sync con backoff de 1 a 60 min.
- Servidor: validación del formato de `watch`; 14 vistas SQL para el panel futuro (usuarios por plataforma y modo, activos por día, horas por usuario/categoría/curso, títulos, completados, rachas, fallos de sync, uso de funciones, errores por versión); `/stats/me` con tiempo por día y por categoría.
- Pantalla **Progreso** (§2.2): tiempo total y de 7 días, racha actual y mejor, días activos, completados, mapa de calor de un año, tiempo por categoría y cursos completados. Funciona sin red y suma todos los dispositivos con cuenta. Tiempo visto por categoría guardado en local.
- Navegación: en el móvil la barra inferior pasa a cinco destinos (Inicio, Mi lista, Historial, Buscar, Progreso); Carpetas y Cuenta se abren desde Progreso.
- Tests de UI más robustos: esperas de 30 s en lugar de 10 (con toda la batería cargada, la de conflictos llegó a pasar de 10 s; probablemente era el fallo intermitente de CI de H9).

**Verificación**
| Comprobación | Resultado |
|---|---|
| Servidor contra PostgreSQL 18 (Testcontainers): `StatsViewsTest` (todas las vistas, eventos inválidos rechazados, `/stats/me` por día y categoría) y el resto de la batería | ✅ |
| Cliente JVM: `PlaybackControllerTest` (evento `watch` y funciones), `TelemetryTest` (6), `PersonalStatsTest` (3), `SchemaTest` v5 | ✅ |
| UI de escritorio: `StatsUiTest`, capturas de Progreso (escritorio, móvil oscuro); batería completa dos veces seguidas | ✅ |
| **Contra el servidor real** (Docker): eventos, error y fallo de sync llegan; el tiempo del portátil aparece en las estadísticas del móvil; vistas consultadas con `psql` | ✅ |
| Batería Android en el emulador (38 tests) | ✅ |
| CI (servidor + cliente + arneses Linux + e2e + emulador) | ✅ [run 43754314233](https://github.com/roberbarras/racha/actions/runs/43754314233) |

**Criterios §12**: ✅ **8**.

**Pendiente / notas**
- El panel de administración en sí queda fuera (la spec pide guardar "para futuro panel"); las vistas son su base.
- El widget de Android (racha, "hoy toca", continuar) quedó hecho en H11.

**Bloqueos**: ninguno.

---

## H9 · Sync extremo a extremo — ✅ cerrado

Documento: [docs/SYNC.md](docs/SYNC.md). Precisiones en [ADR-0004](docs/adr/0004-contentid-y-multiples-raices.md) y [ADR-0012](docs/adr/0012-protocolo-sync-y-modelo-remoto.md).

**Hecho**
- Outbox local con triggers SQLite (solo con la sync activa y nunca para lo que llega del servidor), siembra al iniciar sesión y compactación por entidad; lápidas para borrados.
- Motor de sync: push por lotes, aplicar lo que el servidor tiene más reciente, pull paginado con cursor; renovación del token y reintento ante 401; errores guardados con un código estable.
- Reglas en el cliente (§6.3): gana el más reciente; el progreso conserva siempre el máximo visto; el historial solo se añade; ajustes con "Preguntar" o "Gana el último", preguntando solo cuando el cambio local pierde; hora del servidor adoptada si recorta un reloj adelantado.
- Cuenta: registro, entrada, salida y renovación; sesión cifrada con el Android Keystore / en el servicio de secretos de Linux (libsecret por FFM, fichero `0600` de respaldo); volver a entrar reutiliza el dispositivo en el servidor.
- Adaptador HTTP Ktor 3.6 + OkHttp en un módulo nuevo (`shared:remote`).
- Programación: Linux mientras la app está abierta (red cada 30 s); Android mientras está visible (callbacks de red) y en segundo plano con WorkManager (red y batería no baja, cada max(N, 15) min, más un envío al salir con cambios pendientes).
- UI: pantalla "Cuenta" (servidor, entrar/crear cuenta, estado, pendientes, último error, "Sincronizar ahora", intervalo, política de conflictos, cerrar sesión), ajustes por revisar con los dos valores, banner en Inicio (sesión caducada o ajustes por revisar), vinculación de carpetas con las de otros dispositivos al añadirlas o después. Textos es/en.
- Navegación siempre en el hilo principal (también desde `go`): los clics que llegan por otro hilo, como en los tests, ya no rompen `NavController`.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Datos (JVM, SQLite real): outbox, compactación, lápidas, reglas por entidad, conflictos, vinculación de raíces (`SyncStoreTest`, `RootLinkTest`) | ✅ |
| Adaptador HTTP con servidor simulado (`KtorSyncServerTest`) | ✅ |
| **Dos clientes contra el servidor real** (Docker, PostgreSQL 18): progreso, marcadores, historial, favoritos, rebobinado sin perder el máximo, conflicto de ajustes preguntado y resuelto, cola sin conexión enviada al volver la red, rotación del refresh, reentrada sin duplicar dispositivo (`EndToEndSyncTest`; en CI job `sync-e2e`) | ✅ |
| UI de escritorio con servidor falso: entrar (con error de credenciales), banner de ajustes por revisar y respuesta (`AccountUiTest`) | ✅ |
| Credenciales: fichero de respaldo `0600` (JVM) y Keystore en dispositivo (`KeystoreCredentialStoreTest`); los tests no tocan el llavero real | ✅ |
| Batería Android en el emulador (incluye triggers en el SQLite del dispositivo) | ✅ |
| CI (servidor + cliente + arneses Linux + e2e de sync + emulador) | ✅ [run 43749114228](https://github.com/roberbarras/racha/actions/runs/43749114228) |

**Criterios §12**: ✅ **4**, ✅ **5**, ✅ **9** (OAuth aplazado por decisión, ADR-0006).

**Pendiente / notas**
- Una ejecución anterior de CI (43748193228) falló en el paso `check` del cliente sin cambios de código respecto a la verde; no se pudo ver qué test (los logs piden sesión). Desde ahora los tests que fallan se publican como anotaciones; si se repite, se verá cuál es.
- El envío de eventos de visualización a `/analytics/events` (estadísticas, §12.8) quedó hecho en H10.
- La pantalla de Ajustes completa (H11) incluirá también la sección de sync; los nombres legibles de los ajustes ya están (`settingName`).

**Bloqueos**: ninguno.

---

## H8 · Backend — ✅ cerrado

Documento: [docs/SERVIDOR.md](docs/SERVIDOR.md). ADR nuevo: [0012](docs/adr/0012-protocolo-sync-y-modelo-remoto.md).

**Hecho**
- Cuentas (ADR-0006): registro, login, refresh rotativo con detección de reutilización, logout, dispositivos; BCrypt; límite de intentos; registro abierto/cerrado por configuración.
- Modelo remoto (§6.2) en PostgreSQL 18 con Flyway: identidad, una tabla por entidad sincronizable, auditoría de sync, telemetría.
- Sync (§7.3–7.4, §6.3): push con "gana el más reciente" atómico en SQL, progreso que nunca se pierde, historial de solo inserción, conflictos de ajustes devueltos para preguntar, lápidas, relojes adelantados recortados, pull paginado con cursor.
- Todas las colecciones de §7.3, telemetría (`/analytics/events`, `/errors`) y `/stats/me`.
- OpenAPI 3 (`/v3/api-docs`, `/docs`); errores RFC 9457 con `code`.
- Arquitectura hexagonal por contextos (identidad, sync, telemetría, estadísticas) verificada con ArchUnit; la raíz de composición (`config`) es la única que une adaptadores y casos de uso.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Tests contra PostgreSQL 18 real (Testcontainers): auth 7, registro cerrado 1, sync 11, telemetría y estadísticas 2, OpenAPI 1 | ✅ |
| Unitarios (reglas de identidad, sync y racha) + ArchUnit | ✅ |
| `docker compose up`: salud, registro, push, pull y OpenAPI de punta a punta | ✅ |
| CI (servidor + cliente + arneses Linux + emulador) | ✅ [run 43737768122](https://github.com/roberbarras/racha/actions/runs/43737768122) |

**Criterios §12**: 🟡 **9** (servidor listo; las apps inician sesión en H9); 🟡 **5** y **8** dependen de H9/H10.

**Pendiente / notas**
- OAuth (Google/GitHub) y OTP por email siguen aplazados (ADR-0006); el esquema ya tiene `oauth_accounts`.
- HTTPS: detrás de un proxy (documentado).

**Bloqueos**: ninguno.

---

## H7 · Visor de recursos — ✅ cerrado

Documento: [docs/RECURSOS.md](docs/RECURSOS.md). Capturas: [docs/evidence/h7](docs/evidence/h7).

**Hecho**
- HTML, `.url`, `.txt` y PDF como elementos del curso en un visor a pantalla completa: anterior/siguiente, lista del curso, abrir fuera de la app, "Marcar como visto"; visto al salir e historial para visitas reales.
- HTML aislado a su carpeta de curso (ADR-0003): WebViewAssetLoader en Android, servidor local con token + JavaFX WebKit en Linux; enlaces externos al navegador del sistema.
- Visor PDF propio (PdfRenderer / PDFBox) que solo renderiza las páginas visibles, ajustadas al ancho, con zoom.
- Texto con enlaces clicables y detección de codificación; accesos directos `.url` con esquemas seguros.
- Arreglos encontrados al probar: abrir un elemento navega directo al visor o al reproductor y en el hilo principal (antes se superponían pantallas y podía cerrarse la app); JavaFX 26.0.2 (27.0.1 no pinta); PDF ajustado al ancho en pantallas grandes; índice de fuentes de PDFBox fuera de `$HOME` y preparado en segundo plano; módulos del JRE del AppImage.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Dominio + caso de uso (SQLite y ficheros reales) | ✅ 4 + 5 tests |
| Servidor local: 8 intentos de salir del curso → 404 | ✅ |
| PDFBox: páginas, proporciones, píxeles | ✅ 3 tests |
| Android en dispositivo: página autocomprobada (5 ok, 4 escapes bloqueados), enlace externo, PDF, flujo real del visor | ✅ 3 tests; suite completa 18/18 |
| Linux en Xvfb (app real): página autocomprobada en JavaFX, página pintada, PDF, TXT, vistos | ✅ también en CI |
| Tu escritorio con el AppImage: HTML (enlaces y quiz), PDF al ancho, TXT, anterior/siguiente, lista, "Visto" | ✅ confirmado por ti (2026-11-15) |
| CI (servidor + cliente + arneses Linux + emulador) | ✅ [run 43735623489](https://github.com/roberbarras/racha/actions/runs/43735623489) |

**Criterios §12**: ✅ **6**.

**Pendiente / notas**
- Búsqueda en el contenido textual de los recursos (spec §2 "si es viable": lo es): planificada en H11 junto con la búsqueda sin acentos, con un índice de texto completo (FTS) alimentado por el escaneo.
- JavaFX: volver a la última versión cuando una 27.x pase el arnés de recursos.

**Bloqueos**: ninguno.

---

## H6 · Reproductor Linux — ✅ cerrado

Documento: [docs/REPRODUCTOR.md](docs/REPRODUCTOR.md) (sección Linux), [ADR-0011](docs/adr/0011-presentacion-video-linux.md). Capturas: [docs/evidence/h6](docs/evidence/h6).

**Hecho**
- `MpvEngine` (libmpv por FFM): presenta en una ventana X11 nativa, eventos por observación de propiedades (sin sondeo), subtítulos SRT externos apagados por defecto, pistas de audio, velocidad, seek, escalado nativo, brillo y volumen del vídeo, captura de fotograma. Decodificación por software salvo que se active la de hardware en Ajustes.
- Capa de controles en ventana transparente sobre el vídeo con la misma UI compartida que Android; pantalla completa (se mantiene con el autoplay), PiP propio (ventana pequeña siempre encima), gestos de ratón aprobados, cursor oculto con los controles, atajos de teclado. Cerrar la ventana guarda la posición.
- Sin libmpv instalado, abrir un vídeo muestra un aviso en lugar de fallar.
- Escala de la interfaz según el escritorio (4K al 170 %: antes todo salía diminuto) y diseño adaptable: listas con ancho máximo legible y filas de tarjetas que se reparten el ancho; ventana inicial al 80 % de la pantalla.
- mpv limitado a contextos X11: en Wayland abría su propia superficie (no se veía el vídeo en la app) y probaba `drm`/`displayvk`, que tocan la GPU y la pantalla reales (probable causa del cuelgue de H1).
- AppImage de prueba reproducible: `desktop/packaging/build-appimage.sh` (libmpv del sistema por ahora).
- Arreglos de fondo encontrados al probar: el shell ya no recrea la pantalla al cambiar de ancho (afectaba también a la rotación en Android); `close()` del reproductor guarda siempre y una sola vez; locale numérico y manejador de errores de Xlib (ver ADR-0011).
- §3.2.2 "partes de un mismo vídeo": fuera de alcance por decisión tuya.

**Verificación**
| Comprobación | Resultado |
|---|---|
| `MpvEngineTest` (JVM, libmpv sin vídeo ni audio): MP4, MKV, WebM, MOV, AVI, HLS; rutas con espacios/símbolos; subtítulos ES/EN; inicio en posición; velocidad; seek; carga antes de tener ventana; locale con coma decimal | ✅ 11 tests (3/3 repeticiones) |
| Arnés de extremo a extremo en Xvfb (app real, ventana X11, sin audio): reanuda en 0:12, capa alineada en ventana/pantalla completa/PiP, PiP encima, captura, guardado al salir y al cerrar la ventana | ✅ 10/10 ejecuciones; también en CI |
| Android tras los cambios compartidos (emulador) | ✅ 15 tests instrumentados |
| `check` completo del cliente | ✅ |
| CI (servidor + cliente + arnés Linux + emulador) | ✅ [run 43653033593](https://github.com/roberbarras/racha/actions/runs/43653033593) |
| Escritorio real (KDE Wayland, 4K al 170 % + portátil), con el AppImage: vídeo visible bajo los controles, alineación, pantalla completa, PiP y tamaño de la interfaz | ✅ confirmado por ti (2026-11-15) |
| Capturas de diseño adaptable: 4K a pantalla completa (2259 dp), portátil 1280×720, teléfono | ✅ |

**Criterios §12**: ✅ **3**; 🟡 **12** (pulido en H12).

**Pendiente / notas**
- En Xvfb no hay compositor: la capa transparente se ve negra en las capturas; la comprobación visual se hizo en tu escritorio.
- Una sola escala X11 para todas las pantallas: en la pantalla del portátil (100 %) la app se ve algo grande. Un selector de escala llegará con Ajustes (H11); hoy `RACHA_SCALE`.
- Empaquetado final (libmpv dentro, CI, `racha://`): H12.

**Bloqueos**: ninguno.

---

## H5 · Reproductor Android — ✅ cerrado

Documento: [docs/REPRODUCTOR.md](docs/REPRODUCTOR.md). Capturas: [docs/evidence/h5](docs/evidence/h5).

**Hecho**
- Reglas de reproducción en el dominio y `PlaybackController` compartido (Android y, en H6, Linux): reanudar o preguntar, guardado cada N s y al pausar/salir/terminar, completado + autoplay (desactivable), cola del curso con recursos, A-B, marcadores con nota, intro/outro por elemento, velocidad y "mantener para 2x", historial y segundos vistos.
- UI compartida del reproductor: controles que se ocultan, gestos (bordes, doble toque, arrastres de seek/brillo/volumen, pellizco, mantener), paneles (lista con progreso, velocidad, subtítulos, audio, marcadores, intro/outro), pantalla completa que se mantiene con el autoplay, aviso "Reanudar desde / Empezar de nuevo", tarjeta de fin de curso, atajos de teclado. En teléfono la barra superior usa dos filas.
- Android: ExoPlayer (Media3 1.11.1) sobre SurfaceView, MediaSession, foco de audio, PiP (botón y automático al salir mientras reproduce), modo inmersivo, brillo de ventana, volumen, captura real a `Pictures/Racha`, pantalla encendida solo mientras reproduce, nada en segundo plano.
- Navegación: Home/panel reanudan, "Empezar de nuevo" empieza de cero, la fila del detalle pregunta. El reproductor ocupa toda la pantalla (sin barra de navegación). Los recursos abren un visor provisional (H7).
- CI: nuevo job con emulador (API 37) que ejecuta todos los tests instrumentados.

**Verificación**
| Comprobación | Resultado |
|---|---|
| Reglas + controlador (JVM, motor falso + SQLite): reanudar/preguntar, guardado, fin → completado + siguiente, recurso siguiente, autoplay off, intro/outro, A-B, marcadores, velocidad, subtítulos | ✅ 7 tests (8/8 repeticiones sin fallos) |
| `Media3EngineTest` en dispositivo: **MP4, MKV, WebM, MOV, AVI y HLS** hasta el final; subtítulos apagados al inicio y ES/EN por selección; velocidad y seek | ✅ 8 tests |
| `PlayerUiTest` en dispositivo (app real): reanudar en 0:12 desde "Continuar viendo", subtítulo ES visible, 1.5x, bucle A-B, captura en la galería, posición guardada al salir, PiP sin cortar la reproducción | ✅ 2 tests |
| Suite completa: `check` (JVM, escritorio, lint) + 13 tests instrumentados | ✅ |
| Capturas: controles, panel de subtítulos, PiP | ✅ 3 capturas |
| CI (servidor + cliente + emulador con los tests instrumentados) | ✅ [run 43437025676](https://github.com/roberbarras/racha/actions/runs/43437025676) |

**Criterios §12**: ✅ **10**; 🟡 **3** (falta Linux, H6); 🟡 **4** (falta sync, H9); 🟡 **12** (falta reproductor Linux y pulido).

**Pendiente / notas**
- El emulador sin GPU necesita `-feature -HardwareDecoder` (sus decodificadores `c2.goldfish.*` fallan sin GPU del host); en dispositivos reales se usa el decodificador por hardware. El motor activa además el *fallback* de decodificador.
- Cast (opcional en la spec) no se incluye: depende de servicios de Google.
- Visor de recursos: H7. Ajustes del reproductor en la pantalla de Ajustes (paso de seek, gestos, velocidades, intervalo de guardado): H11; hoy se leen de los ajustes con sus valores por defecto.
- §3.2.2 "partes de un mismo vídeo": fuera de alcance (decidido tras el hito).

**Bloqueos**: ninguno.

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
| CI (servidor + cliente) | ✅ [run 43401082518](https://github.com/roberbarras/racha/actions/runs/43401082518) |

**Criterios §12**: ✅ **2**, ✅ **11**; 🟡 **10** (falta subtítulos por idioma, H5); 🟡 **12** (falta el reproductor, H5/H6).

**Pendiente / notas**
- Tocar un elemento abre una pantalla provisional del reproductor (llega en H5/H6).
- El idioma de la app sigue al del sistema; el selector llega con Ajustes (H11). Ajustes completos, Estadísticas y Onboarding: H10/H11.
- La búsqueda distingue mayúsculas en letras acentuadas (`LIKE` de SQLite); búsqueda sin acentos y en el contenido de recursos: H7/H11.
- §3.2.2 "partes de un mismo vídeo": fuera de alcance (decidido tras el hito).

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
- §3.2.2 "usar duración para detectar partes de un mismo vídeo": fuera de alcance (decidido el 2026-11-13).
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
- El test instrumentado de Android se ejecuta localmente con `./gradlew :android:connectedDebugAndroidTest`; desde H5 también en CI (job con emulador).
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
