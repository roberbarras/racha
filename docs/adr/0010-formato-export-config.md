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
