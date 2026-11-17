# ADR-0013 · Qué contiene una carpeta: biblioteca o un solo curso/serie

**Estado**: aceptada (2026-11-17, a petición del usuario).

**Contexto**: hasta ahora la regla de §3.2 decidía sola qué era la carpeta añadida. Si la mayoría de sus subcarpetas parecían secciones (`NN - Título`, temporadas, fechas), la carpeta era un curso; si no, cada subcarpeta era un curso. Un curso con secciones de nombres normales ("Introducción", "Fundamentos"…) acababa partido en un curso por sección, y el usuario no podía corregirlo.

**Decisión**: cada carpeta raíz guarda qué contiene (`RootLayout`), y lo elige el usuario al añadirla:

| Opción | Resultado |
|---|---|
| **Biblioteca** (`LIBRARY`): varios cursos o series | Cada subcarpeta es un curso/serie/canal y sus carpetas, las secciones; los ficheros sueltos en la raíz forman "Sueltos" |
| **Un curso o serie** (`SINGLE_ITEM`) | La carpeta es el curso/serie y sus subcarpetas, las secciones, se llamen como se llamen; los ficheros de la raíz van primero |
| `AUTO` | Solo para las carpetas añadidas antes de esta decisión: la regla de los nombres de siempre |

- **Antes de añadir**, la app enseña lo que saldría ("Se añadirán 12 cursos: …" / "Se añadirá «X» con 8 secciones") y deja cambiar de opción. Si el usuario elige biblioteca pero los nombres de las subcarpetas son de sección, se lo advierte. Los nombres normales no se usan como pista: no indican nada en ningún sentido.
- **Se puede cambiar después** (Carpetas → "Cambiar qué contiene"), y la carpeta se reescanea. Los vídeos y recursos conservan su id (depende de la ruta del fichero, ADR-0004) y, con él, el progreso, los marcadores y el historial. Los cursos que dejan de existir se archivan, con lo guardado a nivel de curso (Mi lista, favorito, portada, nombre editado), y vuelven si se deshace el cambio. Un curso que solo reagrupa vídeos ya conocidos no aparece como novedad.
- **Sincronización**: el tipo viaja con la carpeta (campo `layout` del `categoryRoot`, en el JSON del servidor: sin migración en el servidor). Al vincular una carpeta con la de otro dispositivo se adopta el tipo de aquella, para que ambos creen los mismos cursos. Una carpeta de un dispositivo con una versión anterior llega sin tipo y cuenta como `AUTO`.
- **Esquema local v7**: columna `category_root.layout` (por defecto `AUTO`) y `remote_root.layout`.

**Alternativas descartadas**:
- Mejorar la heurística: siempre habría casos ambiguos, y el usuario sabe qué ha elegido.
- Marcar curso a curso "esto es una sección": más trabajo para el usuario, y no evita el problema al añadir.

Precisa la regla 6 de §3.2 ("el resultado de la heurística es editable").
