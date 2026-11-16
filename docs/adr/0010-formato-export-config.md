# ADR-0010 · Formato de export/import de configuración

**Estado**: aceptada.

**Decisión**: JSON versionado con migración por versión:

```json
{
  "format": "racha-config",
  "schemaVersion": 1,
  "appVersion": "x.y.z",
  "exportedAt": "2026-11-11T18:00:00Z",
  "settings": {},
  "categories": [],
  "modes": {}
}
```

Se excluyen tokens y API keys.

**Precisión (H11, 2026-11-16)**: `settings` lleva solo los ajustes que el usuario cambió (clave → valor codificado), sin `sync.enabled` (activar la sync exige iniciar sesión); `categories` lleva clave, nombre, icono, color, principal, orden y exclusiones; `modes` = `{ "current": "study"|"leisure", "study": [claves], "leisure": [claves] }`. Las carpetas no se exportan (rutas por dispositivo). Al importar, lo desconocido o inválido se ignora y se cuenta, y un `schemaVersion` mayor se rechaza sin cambios. Detalle en [AJUSTES.md](../AJUSTES.md).
