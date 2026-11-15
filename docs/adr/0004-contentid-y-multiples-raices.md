# ADR-0004 · `contentId` estable y múltiples rutas raíz

**Estado**: aceptada.

**Contexto**: §3.4 define `contentId` = SHA-256 de la ruta relativa dentro de la categoría; §13 pide evitar colisiones con varias raíces por categoría. El ID debe coincidir entre dispositivos para que la sync funcione.

**Decisión**
- `contentId = SHA-256(categoryKey + "/" + rootKey + "/" + relPath)`.
- `categoryKey`: fija para las integradas (`courses`, `series`, `live`), UUID para las del usuario. `rootKey`: UUID estable y **sincronizado** por cada `CategoryRoot`.
- `relPath`: Unicode NFC, separador `/`, sin `./`, mayúsculas preservadas. Vídeos **sin extensión** (para asociar `NNN Título_xx.srt`); recursos **con extensión** (evita colisión `003 X.mp4` / `003 X.html`).
- Al añadir una raíz en otro dispositivo se propone vincularla a una raíz existente de la categoría (sugerida por nombre, confirmada muestreando `fileHash`).
- Mover un curso entre raíces cambia el `contentId`; el progreso se conserva por coincidencia de `fileHash` (§12.7).

**Descartado**: solo `relPath` (colisiones entre raíces, p. ej. "Sueltos"); ruta absoluta (distinta en cada dispositivo); id local autoincremental de la raíz (distinto en cada dispositivo).

**Precisión (H2, 2026-11-12)**: las carpetas (cursos/series y secciones) se identifican con la ruta terminada en `/`, de modo que la carpeta `001 Intro` y el vídeo `001 Intro.mp4` a su lado no comparten `contentId`; los grupos sin carpeta propia (p. ej. por fecha) usan `SHA-256(<contentId del padre> "#" <clave del grupo>)`. Vectores de referencia en `ContentIdTest` (calculados de forma independiente).

**Precisión (H9, 2026-11-15)**: la propuesta de vinculación muestra las carpetas de la categoría que la cuenta tiene en otros dispositivos y este aún no, con la del mismo nombre primero; **la confirma el usuario**. No se muestrea `fileHash`: solo existe para los elementos ya vistos (se calcula en segundo plano), así que no sirve para confirmar una carpeta nueva. Si la vinculación fue errónea, la reasociación por `fileHash` (§12.7) sigue recuperando el progreso de lo visto. Una carpeta ya añadida también se puede vincular después: sus ids cambian a los de la raíz remota y se llevan el progreso, marcadores, historial y favoritos. Detalle en [SYNC.md](../SYNC.md).
