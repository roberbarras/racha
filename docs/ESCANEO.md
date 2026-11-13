# Escaneo e indexación

Implementa la spec §3. Código: detección pura en [`domain/library/scan`](../client/shared/domain/src/commonMain/kotlin/io/github/roberbarras/racha/domain/library/scan),
casos de uso en [`application/library/scan`](../client/shared/application/src/commonMain/kotlin/io/github/roberbarras/racha/application/library/scan),
adaptadores en `shared/files` (java.nio), `desktop/library` (libmpv, inotify) y `android/library` (MediaExtractor, FileObserver).

## 1. Qué es un curso, una serie o un canal

Las categorías no tienen tipo: a cada carpeta se le aplican todas las reglas y su `StructureKind` describe lo detectado.

| Situación en la raíz de la categoría | Resultado |
|---|---|
| La mayoría de subcarpetas parecen secciones (`NN - Título`, temporada o fecha) | **La raíz es un curso** (el ejemplo de §3.1); título = nombre de la carpeta raíz |
| Si no | Cada subcarpeta con contenido es un curso/serie/canal |
| Ficheros directamente en la raíz | Elemento "Sueltos" (§3.2.3) |

Dentro de cada curso/serie/canal:

| Señal | Tipo detectado | Secciones |
|---|---|---|
| Carpetas `Season X`/`Temporada X`/`S01`, o ficheros `S01E02`/`Capítulo X` | Serie | Una por temporada (carpeta o sintética desde `SxxEyy`) |
| Todas las subcarpetas son fechas, o la mayoría de ficheros llevan fecha | Directo | Una por fecha (del nombre; si no, del día de modificación local) |
| Cualquier otro caso | Curso | Una por subcarpeta (`NN - Título` → número + título); los ficheros sueltos del curso van primero |

Subcarpetas más profundas se aplanan dentro de su sección.

## 2. Orden y títulos

- Secciones: número (`NN`, temporada) → fecha → fecha de modificación más antigua (§3.2.2) → nombre en orden natural.
- Elementos: número `NNN` → episodio → fecha del nombre → fecha de modificación (solo si no hay número ni fecha) → orden natural.
- Títulos: sin número, sin extensión, separadores recortados (`001 Course Introduction.mp4` → "Course Introduction").
- Las ediciones del usuario (título y orden) se guardan aparte y ningún reescaneo las pisa (§3.2.6).

## 3. Qué ficheros cuentan

| Tipo | Extensiones |
|---|---|
| Vídeo | mp4, m4v, mkv, webm, avi, mov, m3u8, ts, mts, m2ts, mpg, mpeg, wmv, flv, ogv, 3gp |
| Recurso | html/htm (HTML), url (URL), txt (texto), pdf (PDF) |
| Subtítulo | `<vídeo>_xx.srt` (idioma `xx`, también `pt-BR`); `<vídeo>.srt` sin sufijo → idioma `und` |
| Portada | `poster`/`folder`/`cover` + jpg/jpeg/png/webp en la carpeta del curso |

- Los `.ts` de una carpeta que contiene un `.m3u8` son segmentos HLS, no episodios.
- Exclusiones por categoría (por defecto `temp/`), estilo gitignore: `nombre/` = carpeta en cualquier nivel; `*.ext` = fichero en cualquier nivel; `a/b/` = ruta desde la raíz. Lo oculto (`.algo`) se ignora siempre.
- Dos vídeos con el mismo nombre y distinta extensión: el segundo usa un id que incluye la extensión.

## 4. Escaneo incremental, archivado y revinculación (§3.3, §3.4)

1. Se listan los ficheros (sin entrar en carpetas excluidas) y se detecta la estructura.
2. Lo que ya no está en disco se **archiva** (no se borra; conserva progreso). Si vuelve a aparecer en la misma ruta, se **restaura**.
3. Ficheros nuevos o cambiados (tamaño o fecha de modificación; en modo "hash", contenido) se analizan con la sonda de metadatos. El resto no se toca.
4. Un fichero nuevo solo se lee entero (SHA-256) si existe un elemento archivado **con hash y del mismo tamaño**; si el hash coincide, su progreso, marcadores, historial y favoritos pasan al nuevo y el antiguo desaparece (fichero movido o renombrado).
5. El hash solo se calcula en segundo plano para elementos con datos del usuario (progreso, marcadores, favoritos). Leer entera una biblioteca de cientos de GB no aporta nada y gasta disco y batería.
6. Después de cada escaneo: hashes pendientes y portadas que falten (portada de carpeta → fotograma al 10 % → placeholder de la UI).

**Cuándo se escanea**: al abrir la app (si "Escaneo automático"), al detectar cambios (si "File-watcher": inotify en Linux, FileObserver en Android) agrupando ráfagas en 3 s y reescaneando solo las categorías afectadas, y con "Escanear ahora". En Android todo esto corre solo mientras la app está visible.

## 5. Metadatos

| Plataforma | Cómo | Coste medido |
|---|---|---|
| Linux | libmpv sin salida de vídeo/audio, una instancia reutilizada | 28 ms/fichero (mediana, vídeo 1080p de 82 MB) |
| Android | `MediaExtractor` + `MediaMetadataRetriever` | — |

Se guardan: duración, resolución, códec de vídeo, fps y bitrate medio del fichero. Las pistas de audio y subtítulos internos las lee el reproductor al abrir.

## Fuera de alcance

- **"Usar duración para detectar partes de un mismo vídeo" (§3.2.2)**: descartado por decisión del usuario (2026-11-13). Cada vídeo es un elemento independiente; basta con el orden y el autoplay al siguiente.
