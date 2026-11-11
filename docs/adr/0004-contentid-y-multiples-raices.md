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
