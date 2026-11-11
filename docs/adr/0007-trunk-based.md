# ADR-0007 · Flujo de Git: trunk-based development

**Estado**: aceptada (petición del usuario).

**Decisión**
- `main` es el tronco y está siempre verde y publicable.
- Ramas de vida corta (`feat/…`, `fix/…`, `chore/…`), commits atómicos en Conventional Commits; se integran en `main` cuando la CI está verde.
- Funcionalidad incompleta visible al usuario va tras *feature flag*.
- Versiones: tag `vX.Y.Z` sobre `main`. Cierre de hito: tag `milestone/hN` tras demostrar sus criterios.
