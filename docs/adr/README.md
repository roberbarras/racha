# Architecture Decision Records

Formato: Estado · Contexto · Decisión · Alternativas descartadas · Consecuencias. Una decisión aprobada no se edita: se sustituye con un ADR nuevo que la referencia.

| ADR | Decisión | Estado |
|---|---|---|
| [0001](0001-stack-y-versiones.md) | KMP + Compose Multiplatform; Java 25, Spring Boot 4, PostgreSQL 18 | Aceptada |
| [0002](0002-motor-video-linux.md) | Media3 en Android; (Linux sustituido por 0011) | Aceptada / parcialmente sustituida |
| [0003](0003-html-local-seguro.md) | HTML local servido solo desde la carpeta del curso | Aceptada, validada en H1 |
| [0004](0004-contentid-y-multiples-raices.md) | `contentId` con `categoryKey` + `rootKey` estables | Aceptada |
| [0005](0005-acceso-almacenamiento-android.md) | Puerto `LibrarySource`; "acceso a todos los archivos" hoy, SAF posible | Aceptada |
| [0006](0006-autenticacion.md) | Email + contraseña + JWT; OAuth aplazado | Aceptada |
| [0007](0007-trunk-based.md) | Trunk-based development | Aceptada |
| [0008](0008-miniaturas-y-metadatos.md) | Miniaturas/metadatos con APIs nativas y libmpv | Aceptada, validada en H1 |
| [0009](0009-empaquetado-y-distribucion.md) | AppImage (Linux) y APK universal firmado (Android) | Aceptada |
| [0010](0010-formato-export-config.md) | Export/import de configuración en JSON versionado | Aceptada |
| [0011](0011-presentacion-video-linux.md) | Linux: salida nativa de mpv incrustada + UI en ventana superpuesta; hwdec desactivado por defecto | Aceptada |
| [0012](0012-protocolo-sync-y-modelo-remoto.md) | Modelo remoto por entidad, LWW atómico en SQL, pull con cursor de secuencia | Aceptada |
| [0013](0013-que-contiene-una-carpeta.md) | Cada carpeta es una biblioteca o un solo curso/serie, a elección del usuario | Aceptada |
| [0014](0014-operacion-del-servidor.md) | Datos en carpetas, copias con rotación, IP real tras proxies, bloqueos por cuenta+IP, administrador, recuperación por correo, logs y métricas | Aceptada |
| [0015](0015-copia-de-seguridad-y-portabilidad.md) | Formato `racha-backup`: copia completa en las apps y datos de una cuenta de un servidor a otro | Aceptada |
| [0016](0016-resumenes-con-ia.md) | Resúmenes con IA: proveedores compatibles con OpenAI en el orden del usuario, a partir de los subtítulos, guardados y sincronizados con los datos de la app | Aceptada |
| [0017](0017-enviar-a-televisores-y-pantallas.md) | Enviar a Chromecast, DLNA y pantallas conectadas: protocolos en JVM puro, servidor de ficheros solo mientras se envía, el dispositivo como mando | Aceptada |
