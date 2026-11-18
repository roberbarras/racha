# Ajustes, notificaciones, widget, deep links y primer uso

Spec §2.2, §3 (portadas con IA), §9 (ajustes), §10.3 (onboarding) y §10.5 (widget). Relacionado: [SYNC.md](SYNC.md), [ESTADISTICAS.md](ESTADISTICAS.md), [ADR-0010](adr/0010-formato-export-config.md).

## Ajustes (§9)

Pantalla **Ajustes** (en el móvil, desde Progreso). Todo se aplica al momento, sin botón de guardar. Los ajustes de usuario viajan con la sincronización; los de dispositivo (carpetas, conexión, escala, notificaciones…) no.

| Sección | Qué hay |
|---|---|
| General | Idioma (sistema / español / inglés: **toda la app cambia al momento**, sin reiniciar), tema, color de acento, tamaño del texto; en Linux, **tamaño de la interfaz** (automático = la escala del escritorio, o de 75 % a 300 %) |
| Categorías | Crear, renombrar (en blanco, una integrada vuelve a su nombre traducido), borrar (las del usuario, con confirmación), reordenar, categoría principal, en qué modo se ve cada una (Estudio/Ocio), patrones a omitir al escanear (se reescanea al guardar). Las carpetas, en su pantalla |
| Reproductor | Velocidad por defecto y pasos de velocidad, salto (5/10/30 s), subtítulos y su idioma preferido (lo que se usa mientras no hayas elegido otra cosa: después se recuerda por curso, [REPRODUCTOR.md](REPRODUCTOR.md#comodidad-h15)), reproducir el siguiente, barra lateral, saltar intro/outro, escalado del vídeo, los siete gestos y la velocidad al mantener; en Linux, decodificación por hardware y **atajos de teclado** |
| Progreso | Intervalo de guardado (15/30/60/300 s) |
| Escaneo | Al abrir, vigilar carpetas, miniaturas, detección de cambios (fecha y tamaño / contenido), **portadas con IA** |
| Resúmenes con IA | Proveedores para «Resumen y claves»: añadir desde una lista (Gemini, Groq, NVIDIA, OpenRouter, Mistral, Cerebras, DeepSeek, Ollama o cualquiera compatible con OpenAI), ordenar (es el orden en que se prueban), activar, clave, modelo (con «Cargar modelos») y «Probar» ([RESUMENES-IA.md](RESUMENES-IA.md)) |
| Sincronización | Estado y acceso a Cuenta ([SYNC.md](SYNC.md)) |
| Notificaciones | Novedades, sincronización fallida, recordatorio diario y su hora; si el sistema no las permite, lo dice y ofrece permitirlas |
| Almacenamiento | Borrar la caché de miniaturas, borrar la caché de metadatos (se releen duraciones y códecs), borrar el historial **de este dispositivo**, exportar e importar la configuración y la **copia de seguridad completa** (exportar y restaurar: progreso, marcadores, historial, favoritos, ajustes, carpetas y estadísticas; [ADR-0015](adr/0015-copia-de-seguridad-y-portabilidad.md)) |
| Avanzado | Nivel de log, endpoint de telemetría, proxy (`http://` o `socks5://host:puerto`) y User-Agent: se aplican a todas las peticiones al servidor y a la IA de portadas |
| Acerca de | Versión, licencia MIT, código fuente |

**Log**: en Linux `~/.cache/racha/racha.log` (1 MB y uno anterior); en Android, logcat. Registra escaneos, rondas de sync, errores de telemetría y fallos no capturados según el nivel elegido.

**Log detallado en Linux** (para ver qué tarda o qué falla): arrancar con `RACHA_LOG=debug ./Racha-<versión>-x86_64.AppImage` (o `--verbose`). Todo se escribe también en la terminal, desde el primer milisegundo, con cada fase del arranque medida (`startup: +1234 ms …`: JVM, base de datos, ventana, API gráfica elegida, primer fotograma, primera pantalla y primer escaneo con su duración). Los mensajes de las librerías (SQLite, HTTP…) van al mismo log; su nivel de detalle se activa aparte con `RACHA_LOG_LIBS=debug`. `RACHA_LOG` admite `error`, `warn`, `info` o `debug` y gana sobre el nivel de Ajustes.

### Exportar / importar (ADR-0010)

JSON `racha-config` v1: los ajustes que el usuario cambió, las categorías (nombre, icono, color, principal, orden, exclusiones) y `modes` (modo actual y qué categorías se ven en cada modo). **Nunca** van secretos (sesión, claves de API), carpetas (las rutas son de cada dispositivo) ni si la sincronización está activa (necesita iniciar sesión). Al importar se ignora lo desconocido o inválido y se cuenta; un fichero de una versión más nueva se rechaza sin tocar nada.

## Búsqueda

Ignora mayúsculas y acentos ("jose" encuentra "José", "nino" encuentra "Niño") en títulos de cursos, series y elementos, y busca **dentro del texto** de los recursos HTML, TXT y enlaces, mostrando las palabras alrededor de la coincidencia (en negrita).

- El plegado (minúsculas sin acentos, un carácter por otro) se hace en Kotlin: el `LIKE` de SQLite solo ignora mayúsculas ASCII, y FTS no está garantizado en el SQLite de Android 8 (minSdk 26). Los títulos se comparan en memoria (son miles como mucho).
- El texto de los recursos se indexa tras cada escaneo, solo los ficheros nuevos o cambiados, hasta 1 MB por fichero: sin scripts, estilos ni comentarios, con las entidades decodificadas (esquema v6). Los PDF no se indexan (Android no extrae su texto).

## Notificaciones

| Aviso | Cuándo | Sin ruido |
|---|---|---|
| Novedades | Tras un escaneo automático (al abrir, vigilancia de carpetas) que encuentra elementos nuevos | No al añadir una carpeta (todo es nuevo) ni en un escaneo manual que el usuario está viendo. Un solo aviso agrupado; si es una sola serie, tocarlo la abre |
| Sync fallida | Cuando la sincronización deja de funcionar | Una vez por tipo de fallo; "servidor inaccesible" solo tras un día sin sincronizar (un portátil sin red una tarde no es un problema) |
| Recordatorio diario | A la hora elegida | Solo los días sin estudiar; con la racha en juego y qué continuar (tocarlo lo abre) |

- **Android**: un canal por tipo (el usuario puede silenciar cada uno), permiso `POST_NOTIFICATIONS` en Android 13+ pedido desde Ajustes u onboarding. El recordatorio va con WorkManager: el sistema puede agruparlo unos minutos para ahorrar batería (no hace falta una alarma exacta).
- **Linux**: el servicio de notificaciones de la sesión (KDE, GNOME…) por D-Bus (`gdbus`, si no `notify-send`). El recordatorio solo con la app abierta.

## Deep links

`racha://item/<id>` abre un curso o serie; `racha://play/<id>`, un elemento (vídeo en el reproductor, recurso en el visor); también `racha://stats`, `racha://settings` y `racha://account`. Los ids son los mismos en todos los dispositivos (ADR-0004): un enlace funciona en cualquiera. Lo que no es un enlace nuestro se ignora.

- **Android**: `intent-filter` `VIEW` con el esquema `racha` (también desde el navegador); el enlace se procesa una vez (no otra vez al girar).
- **Linux**: la entrada `.desktop` del AppImage declara `x-scheme-handler/racha`. **Una sola instancia**: un segundo lanzamiento (p. ej. un enlace abierto desde el navegador) entrega el enlace a la ventana abierta por un socket Unix en `$XDG_RUNTIME_DIR` y la trae al frente, en lugar de abrir otra ventana sobre la misma base de datos.

## Widget de Android (§10.5)

Racha actual, "Hoy toca" (lo que está a medias o la recomendación de hoy), el progreso de ese curso y **Continuar** (abre el elemento por su deep link; el resto del widget abre la app). `RemoteViews` sin trabajo propio en segundo plano: la app lo actualiza cuando cambian la racha o lo de hoy, y el sistema lo pide cada 6 h por el cambio de día.

## Primer uso (§10.3)

Bienvenida e idioma → para qué la vas a usar (Estudio/Ocio) → carpetas por categoría (con el permiso de almacenamiento en Android) → recordatorio diario → sincronización (opcional, lleva a Cuenta). Todo se puede saltar y cambiar después. Solo aparece con la biblioteca vacía y una vez: una instalación existente cuenta como hecha. **Sustituye** a la app mientras dura (no se puede pulsar nada de detrás).

## Portadas con IA (§3, opcional)

Desactivadas por defecto. En Ajustes → Escaneo: endpoint, prompt base (`{title}` se sustituye por el título) y API key. Añade "Generar portada con IA" al detalle de cada curso o serie; **solo se envía el título**.

- Endpoint con `{prompt}` → GET (p. ej. `https://image.pollinations.ai/prompt/{prompt}`); sin él → POST `{"inputs": …}` con la clave como *bearer* (Hugging Face Inference).
- La respuesta tiene que ser una imagen de verdad (PNG, JPEG o WebP por su firma, como mucho 15 MB).
- La **API key** va al almacén protegido (llavero por libsecret en Linux, Android Keystore), nunca a los ajustes, la exportación ni la sincronización; la pantalla solo dice si hay una guardada.
- La portada se guarda con los datos de la app (no es caché: cuesta una petición). La carátula de la carpeta (`poster.jpg`…) sigue mandando.

## Tests

- JVM (SQLite real): `SettingsUseCasesTest` (categorías, export/import de ida y vuelta y ficheros malos, almacenamiento), `SearchTest` (acentos, contenido, fragmento, reindexado solo si cambia), `NotificationsTest`, `GenerateCoverTest`; `KtorImageGeneratorTest` y `KtorSyncServerTest` contra servidores simulados.
- Linux: `SingleInstanceTest` (socket temporal), `DesktopNotifierTest` (solo los argumentos de la llamada: los tests nunca muestran notificaciones en el escritorio), `ReminderTimeTest`.
- UI de escritorio: `SettingsUiTest` (idioma al momento en toda la app, tema, interruptores, crear categoría), `OnboardingUiTest` (recorrido completo; una biblioteca existente no lo ve), `DeepLinkTest` y navegación por enlace; capturas de Ajustes, Progreso y onboarding.
- Android (emulador): `AndroidNotifierTest` (canales y toque), `TodayWidgetTest` (el layout del widget se infla y dibuja), además de la batería existente.
