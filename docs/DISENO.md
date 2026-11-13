# Sistema de diseño

Spec §10. Fuente única: [`design/tokens.json`](../design/tokens.json). Código: [`shared/ui/.../ui/design`](../client/shared/ui/src/commonMain/kotlin/io/github/roberbarras/racha/ui/design). Evidencias: [`docs/evidence/h4`](evidence/h4).

## Identidad

- **Papel cálido + tinta azul marino + un único acento brasa** (`#E8812A`). Sin degradados morado-azul ni glassmorphism (§10.2).
- **Tipografía: Instrument Sans** (OFL, variable). Grotesca algo condensada: cabe más texto en tarjetas y barras laterales, y se lee bien en tamaños pequeños. Los tiempos usan cifras tabulares (`tnum`) para que `12:34 / 45:10` no baile mientras avanza.
- **Iconos Lucide** (ISC) de trazo, convertidos a vectores de Compose; nunca emojis (§10.2).
- **Placeholders generados en el dispositivo**: un tono por título (paleta sobria), líneas diagonales e iniciales. Sin fotos de stock.
- Inspiración de patrones, no copia: barra lateral con racha (escritorio), carril oscuro y panel de reanudar (tableta), barra inferior (teléfono).

## Marca e icono

- Origen: [`design/brand/racha-icon-original.png`](../design/brand/racha-icon-original.png) (128×146 px). Se vectorizó con potrace (máscara por luminosidad sobre un recorte escalado) y se le aplicó un degradado real (`#F3B65C` → `#DD7552`) sobre el azul marino `#212A41`.
- Maestros: [`racha-emblem.svg`](../design/brand/racha-emblem.svg) (emblema) y [`racha-icon.svg`](../design/brand/racha-icon.svg) (icono cuadrado).
- Generados por `tools/potrace_to_brand.py`: primer plano del icono adaptativo de Android (+ capa monocroma para iconos temáticos) y los vectores `RachaLogo` / `RachaFlame` de Compose (UI, ventana de escritorio, animación de arranque).
- Arranque en Android: splash del sistema (`core-splashscreen`, emblema sobre azul marino desde el primer fotograma) → `LaunchAnimation` (~2 s: el emblema se asienta, un destello recorre la llama, aparecen el nombre y una línea de carga) mientras la Home carga en segundo plano; sale cuando hay datos (máx. 1,5 s extra) y se reduce a un fundido si el sistema pide reducir movimiento. Solo en arranque en frío. Evidencias: [`docs/evidence/brand`](evidence/brand).

## Tokens

| Grupo | Contenido |
|---|---|
| `color.light` / `color.dark` | 19 roles (fondo, superficies, bordes, tinta, acento, carril, progreso, éxito, peligro, foco). Ambas paletas deben tener las mismas claves (lo comprueba el generador). |
| `typography.styles` | display, title, heading, body, bodyStrong, label, caption, overline (tamaño, interlineado, peso variable, tracking). |
| `spacing`, `radius` | Escalas de 2–48 dp y 8–22 dp + píldora. |
| `motion` | 150 / 220 / 300 ms; todo se anula si el sistema pide reducir movimiento (Android: escala de animación 0; GNOME/KDE: animaciones desactivadas). |
| `layout` | Cortes de ancho (600 / 1100 dp), barra lateral, carril, panel de detalle, tarjeta. |

`:shared:ui:generateDesignTokens` genera `Tokens.kt` en cada compilación; el código accede vía `Racha.colors`, `Racha.type`, `Racha.space`…

## Componentes (`ui/design`)

| Componente | Uso |
|---|---|
| `RText`, `RIcon` | Texto de la escala tipográfica e iconos tintados |
| `Modifier.interactive` | Clic con capa sutil en hover/pulsación y **anillo de foco visible** (teclado) |
| `RButton` (Primary/Secondary/Ghost), `RIconButton` | Acciones |
| `SegmentedToggle` | Estudio/Ocio, pestañas de categoría |
| `ProgressBar` | Progreso (semántica de rango para lectores de pantalla) |
| `Tag`, `SectionHeader`, `SearchField`, `StreakMeter` | |
| `RCard`, `Poster` | Tarjetas con borde; portada o placeholder 16:9 |
| `MessageState`, `SkeletonBlock` | Estados vacío / error / carga (§10.3) |

## Pantallas (H4)

Home (continuar viendo, hoy toca, novedades, biblioteca por pestañas con la categoría principal abierta, panel de reanudar en pantallas anchas), Detalle (secciones, progreso, marcado manual, Mi lista), Categoría, Mi lista, Historial, Búsqueda (Ctrl+K), Carpetas (Escanear ahora, Ctrl+R) y aviso de permiso de almacenamiento en Android. Player, Recursos, Ajustes completos, Estadísticas y Onboarding llegan en sus hitos.

| Ancho | Navegación |
|---|---|
| < 600 dp (teléfono) | Barra inferior |
| 600–1100 dp (tableta) | Carril oscuro + panel de reanudar si hay ≥ 840 dp |
| ≥ 1100 dp (escritorio) | Barra lateral con racha, categorías y estado del escaneo |

## Idiomas

`composeResources/values` (inglés, base) y `values-es` (español), mismas claves. Fechas largas con nombres de días y meses traducidos. Siguen el idioma del sistema; el selector de idioma de la app llega con Ajustes (H11).

## Regenerar iconos

```bash
python3 -I tools/lucide_to_compose.py <carpeta con SVG de Lucide> client/shared/ui/src/commonMain/kotlin/io/github/roberbarras/racha/ui/design/RachaIcons.kt
```
