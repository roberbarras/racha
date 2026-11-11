# ADR-0001 · Stack y versiones

**Estado**: aceptada (2026-11-11).

**Contexto**: spec §11/§13 pide elegir framework cross-platform. El usuario pide usar siempre las **últimas versiones estables** (seguridad), aunque la spec cite otras, y prioriza fluidez y batería.

**Decisión**
- Cliente: **Kotlin Multiplatform + Compose Multiplatform** (Android + JVM desktop). Dominio, aplicación y UI compartidos; motores de vídeo y vistas web nativos por plataforma.
- Servidor: **Java 25 LTS**, **Spring Boot 4.1** (subir a 4.2 al salir GA), **PostgreSQL 18**, Flyway, Testcontainers.
- Toolchain: Gradle 9.9, Kotlin 2.4, Compose MP 1.12, AGP 9.5, compile/target SDK 37, minSdk 26 (PiP).
- Versiones centralizadas en `gradle/libs.versions.toml` (cliente y servidor) y vigiladas por Dependabot.
- DI manual en raíces de composición (sin reflexión).

**Desviaciones de la spec**: Java 21 → 25, Spring Boot 3.x → 4.x, PostgreSQL 16 → 18 (petición explícita del usuario).

**Descartado**
- *Flutter*: Media3 quedaría tras plugins sin control fino de PiP/pistas/A-B, o habría que usar libmpv también en Android (contra §4.1); añade Dart como tercer lenguaje.
- *Java 27*: más nueva, pero no LTS (6 meses de parches); obligaría a migrar cada semestre.

**Consecuencias**: un solo lenguaje en cliente; camino a iOS (Compose iOS estable) y Windows/macOS (JVM). Riesgo: no hay superficie de vídeo nativa en Compose Desktop → ADR-0002.
