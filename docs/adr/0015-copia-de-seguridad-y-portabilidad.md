# ADR-0015 · Copia de seguridad completa y datos portables entre servidores

**Estado**: aceptada (H16, 2026-11-17, a petición del usuario).

**Contexto**: el usuario quiere una copia local completa en las apps (exportar e importar), y poder sacar todos sus datos de un servidor para importarlos en otro. La copia diaria de la base de datos (ADR-0014) mueve un servidor entero con todos sus usuarios, pero no sirve para una sola cuenta ni para un dispositivo sin servidor.

**Decisión**: un único formato JSON, `racha-backup`, para las tres cosas:

```json
{
  "format": "racha-backup", "schemaVersion": 1, "createdAt": "…",
  "source": { "kind": "server" | "device", "app": "…" },
  "records": [ { "entity": "progress", "id": "…", "updatedAt": "…", "deleted": false, "data": { … } } ],
  "events":  [ { "id": "…", "type": "watch", "occurredAt": "…", "properties": { … } } ],
  "device":  { "roots": [ … ], "settings": { … }, "playbackMemory": { … }, "localStats": [ [metric, period, value] ] }
}
```

- **`records`** tiene la misma forma que un cambio de sincronización (ADR-0012): categorías, carpetas, retoques de la biblioteca, progreso, marcadores, historial, favoritos y ajustes de usuario. Importar es aplicar esos cambios con las reglas de la sync (gana el más reciente), así que **repetir una importación no cambia nada**.
- **`events`** (solo desde el servidor): los eventos de visionado, para que las estadísticas viajen. Conservan su id: no se guardan dos veces.
- **`device`** (solo desde una app): lo que solo tiene ese dispositivo. Son sus carpetas (ruta y tipo, ADR-0013), sus ajustes de dispositivo, las elecciones recordadas del reproductor y los totales de las estadísticas. Un servidor lo ignora.
- **Nunca** van secretos (sesión, claves de API) ni `sync.enabled`: activar la sync exige iniciar sesión.

**Usos**:
- **Panel web → Cuenta → «Tus datos»**: descargar (`GET /account/export`) e importar (`POST /account/import`) en la cuenta con la que se ha entrado. Para cambiar de servidor: descargar en el antiguo, crear la cuenta en el nuevo, importar y que los dispositivos entren en el nuevo.
- **Apps → Ajustes → Almacenamiento → «Copia de seguridad completa» / «Restaurar una copia»**. El orden al restaurar es:
  1. categorías;
  2. carpetas que falten (con su clave: los ids coinciden, ADR-0004);
  3. ajustes, elecciones y totales (los totales se quedan con la cifra mayor, nunca se suman);
  4. todos los registros;
  5. escaneo de las carpetas restauradas;
  6. retoques de los cursos.

  Con sesión iniciada, lo restaurado se envía también al servidor.
- Los dos sentidos son compatibles: el fichero del servidor se restaura en una app (sin carpetas) y el de una app se importa en un servidor.

**Corrección asociada**: los retoques de títulos, orden e intro/outro se perdían en un dispositivo nuevo. El escaneo marca las filas con su propia hora, más reciente que un retoque hecho antes en otro dispositivo. Ahora, si la fila no tiene retoques propios, acepta los que llegan; un retoque propio más reciente sigue ganando.

**Alternativas descartadas**: un volcado SQL por usuario (acopla al esquema del servidor y no sirve en las apps); formatos distintos para app y servidor (dos importadores y ficheros que no se entienden entre sí).
