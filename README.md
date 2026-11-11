# Racha

Self-hosted, offline-first app to watch and track your **local** courses, series and live-stream recordings.
Android + Linux desktop clients and an optional Spring Boot backend that syncs progress, settings and statistics — it **never** serves your media.

*App offline-first para ver y seguir tus cursos, series y directos locales. Clientes Android y Linux, y un backend opcional que sincroniza progreso, ajustes y estadísticas; nunca sirve tu contenido.*

> **Status:** early development — milestone H0 (walking skeleton). See [`PROGRESS.md`](PROGRESS.md).

## Repository layout

| Path | What |
|---|---|
| [`client/`](client) | Kotlin Multiplatform + Compose Multiplatform: `shared/{domain,application,ui}`, `android`, `desktop` |
| [`server/`](server) | Spring Boot 4 + PostgreSQL 18 REST backend (Java 25) |
| [`docker-compose.yml`](docker-compose.yml) | Runs the backend and its database |
| [`docs/`](docs) | [Spec](docs/ESPECIFICACION.md) · [Architecture](docs/ARCHITECTURE.md) · [ADRs](docs/adr/README.md) |

## Developer quick start

Requirements: JDK 25, Docker, Android SDK (platform 37) for the Android app.

```bash
docker compose up -d --build
```

The API is then available at `http://localhost:8080` (`/actuator/health` reports `UP`). Copy `.env.example` to `.env` to change ports or credentials.

```bash
cd client && ./gradlew :desktop:run
```

```bash
cd client && ./gradlew :android:assembleDebug
```

The debug APK is written to `client/android/build/outputs/apk/debug/`.

Run every test suite:

```bash
cd server && ./gradlew build
```

```bash
cd client && ./gradlew check
```

## Contributing

Trunk-based development: short-lived branches, [Conventional Commits](https://www.conventionalcommits.org/), green CI before merging into `main`. See [ADR-0007](docs/adr/0007-trunk-based.md).

## License

[MIT](LICENSE)
