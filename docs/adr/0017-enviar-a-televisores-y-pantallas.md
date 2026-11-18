# ADR-0017 · Enviar a televisores y pantallas externas

**Estado**: aceptada (H18, 2026-11-18, a petición del usuario).

**Contexto**: el usuario quiere ver las clases en la tele (Chromecast, DLNA) y en una pantalla conectada al móvil o la tableta (HDMI, USB-C, Miracast), con el dispositivo como mando. No se trata de usar el PC con Linux como televisor.

**Decisión**:

- **Un motor más**. `CastingEngine` (capa de aplicación) envuelve el motor local y puede pasar a un `RemotePlayer` en mitad de la reproducción, conservando la posición. El `PlaybackController` solo ve un `PlayerEngine`, así que progreso, historial, siguiente clase, marcadores y estadísticas funcionan igual mientras la tele reproduce. Las acciones al dispositivo van en orden por una cola. Mientras se envía, el estado se consulta una vez por segundo y la posición entre consultas se estima solo si el aparato dice que reproduce.
- **Sin Google Play services**. Los protocolos están implementados en JVM puro (módulo `remote`): funciona igual en Android y en Linux, y CI lo prueba contra aparatos simulados.
  - **Chromecast**: Cast v2, es decir, TLS al puerto 8009 con mensajes protobuf de cabecera de longitud y JSON. Usa el *Default Media Receiver* (`CC1AD845`, sin registro) con subtítulos WebVTT. Los Chromecast presentan certificados autofirmados, así que **solo esa conexión** acepta cualquiera; lleva órdenes de reproducción en la red local, ningún secreto.
  - **DLNA/UPnP AV**: SOAP al `AVTransport` del renderizador, con DIDL-Lite que incluye el `.srt` (`sec:CaptionInfoEx`) para los televisores que muestran subtítulos externos. El HTTP a los televisores va por un socket propio (`LanHttp`), porque Android prohíbe `http://` a sus clientes HTTP y los televisores solo hablan `http`; solo se usa con aparatos encontrados en la red local.
  - **Descubrimiento**: mDNS (`_googlecast._tcp`) y SSDP (`AVTransport:1`), con una pregunta cada 4 s en cada interfaz IPv4 y **solo mientras el panel «Enviar a» está abierto** (batería). En Android se mantiene un *multicast lock* durante la búsqueda y se pide el permiso de red local de Android 17 (`ACCESS_LOCAL_NETWORK`).
- **Servidor de ficheros en la LAN** (`LanMediaServer`). La tele pide el vídeo a este dispositivo, así que hace falta servirlo:
  - solo mientras se envía, y solo lo publicado, bajo una ruta aleatoria de 128 bits;
  - en la única dirección local que alcanza a la tele, nunca en todas las interfaces;
  - con rangos de bytes, `HEAD`, CORS (que Chromecast necesita para los subtítulos) y las cabeceras DLNA.
  - Es un servidor de sockets mínimo, porque Android no tiene `com.sun.net.httpserver`.
- **Subtítulos**: se eligen al cargar. Cambiar de subtítulo vuelve a cargar el vídeo donde estaba; es lo único que admiten todos los aparatos. Chromecast recibe WebVTT y DLNA, SubRip.
- **Velocidad**: en Chromecast sí; la mayoría de televisores DLNA no la admiten y se queda a 1×.
- **Fin del vídeo**:
  - Chromecast avisa de `FINISHED` una sola vez, en un estado que envía por su cuenta, y se recuerda.
  - En DLNA, `STOPPED` cerca del final (o justo después de estarlo) cuenta como terminado, y en mitad del vídeo no.
  - Un aparato inactivo justo después de cargar no cuenta como terminado.
- **Pantalla externa (Android)**: `Presentation` sobre las pantallas de categoría *presentation*, que el sistema ofrece igual para HDMI, USB-C y Miracast.
  - El `SurfaceView` del motor pasa a la otra pantalla con su relación de aspecto, y los subtítulos se dibujan también allí.
  - Al desconectar la pantalla, el vídeo vuelve al móvil.
  - En el emulador se prueba con una *overlay display*.
- **Interfaz**: el botón «Enviar a» abre un panel con este dispositivo, las pantallas conectadas y los aparatos de la red.
  - Mientras otra pantalla reproduce, este dispositivo muestra dónde y mantiene los controles a la vista.
  - Puede apagar la pantalla si el vídeo está en la tele, pero no si está en una pantalla conectada.
  - Un fallo al conectar o al abrir el vídeo, o una conexión perdida, se avisa, y el vídeo sigue en este dispositivo.

**Limitaciones conocidas**:

- El formato lo decide la tele: un MKV con HEVC puede no abrirse en un Chromecast antiguo (se avisa).
- Con la app en segundo plano en Android, el sistema puede pararla mientras la tele sigue reproduciendo. Al volver, el progreso se retoma desde la tele.
- En Linux, un cortafuegos que bloquee conexiones entrantes impide que la tele pida el vídeo.

**Alternativas descartadas**:

- El SDK de Google Cast: exige Google Play services, no existe en Linux y no se puede probar en el emulador AOSP de CI.
- Transcodificar en el dispositivo: gasta batería y CPU; se reproduce el fichero tal cual.
- Usar el PC como televisor: no es lo que se pidió.
