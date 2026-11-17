# Guía de despliegue

Spec §15.6. Tres piezas independientes: el **servidor** es opcional (solo para sincronizar varios dispositivos y guardar estadísticas); las apps funcionan al 100 % sin él.

## 1. Servidor (opcional)

Requisitos: Docker con Compose.

```bash
cp .env.example .env                                   # junto a docker-compose.yml; una línea VARIABLE=valor
sed -i "s|^RACHA_JWT_SECRET=.*|RACHA_JWT_SECRET=$(openssl rand -base64 48)|" .env
sed -i "s|^RACHA_DB_PASSWORD=.*|RACHA_DB_PASSWORD=$(openssl rand -hex 24)|" .env   # antes del primer arranque
mkdir -p data backups
docker compose up -d --build
curl http://localhost:8080/actuator/health             # {"status":"UP"}
```

- Todo lo configurable (cuenta administradora, correo para recuperar contraseñas, proxies, copias, métricas): [SERVIDOR.md](SERVIDOR.md).
- **Datos y copias**: la base de datos vive en `data/postgres` y cada día queda una copia en `backups/` (14 diarias + 8 semanales). Restaurar o mover el servidor: `tools/restore-backup.sh backups/<copia>.dump` ([SERVIDOR.md](SERVIDOR.md#datos-copias-y-migración-a-otro-servidor)).
- **HTTPS**: el servidor habla HTTP; para usarlo fuera de casa ponlo detrás de un proxy con certificado (Cloudflare Tunnel o proxy, Caddy, Traefik, nginx) y ajusta `RACHA_TRUSTED_PROXIES` para que vea la IP real de cada cliente.
- **API**: OpenAPI en `/v3/api-docs` y Swagger UI en `/docs`; copia versionada en [api/openapi.json](api/openapi.json).
- **Estadísticas**: vistas `stats_*` en la base de datos ([ESTADISTICAS.md](ESTADISTICAS.md)).

## 2. Android

**Instalar**: descarga `Racha-<versión>.apk` de la *release* de GitHub y ábrelo en el móvil (permitir "instalar apps desconocidas" para el navegador o el gestor de archivos). Android 8.0 o posterior.

Al abrirla, el primer uso guía: idioma, modo, carpetas (Racha pide **acceso a todos los archivos** para leer tus carpetas; ADR-0005), recordatorio y sincronización.

**Compilar**:

```bash
cd client && ./gradlew :android:assembleDebug            # android/build/outputs/apk/debug/
cd client && ./gradlew :android:assembleRelease -Pracha.version=1.0.0 -Pracha.versionCode=10000
```

**Firmar la release** (ADR-0009). Crea la clave una vez y guárdala fuera del repositorio:

```bash
keytool -genkeypair -v -keystore racha-release.jks -alias racha -keyalg RSA -keysize 4096 -validity 10000
```

- En local: `RACHA_KEYSTORE=/ruta/racha-release.jks RACHA_KEYSTORE_PASSWORD=… RACHA_KEY_ALIAS=racha RACHA_KEY_PASSWORD=… ./gradlew :android:assembleRelease`. Sin esas variables, la release se firma con la clave de depuración (solo para probarla).
- En CI: añade en GitHub → *Settings → Secrets and variables → Actions* los secretos `RACHA_KEYSTORE_BASE64` (`base64 -w0 racha-release.jks`), `RACHA_KEYSTORE_PASSWORD`, `RACHA_KEY_ALIAS` y `RACHA_KEY_PASSWORD`. Sin ellos, CI avisa y firma con la clave de depuración.
- **Conserva la clave**: las actualizaciones de la app tienen que ir firmadas con la misma.

La release usa R8 (código reducido) y CI la prueba con `android/scripts/release-smoke.sh`: la instala en el emulador, recorre las pantallas por deep links y le lanza 1 500 eventos de interfaz, fallando ante cualquier cierre o ANR.

## 3. Linux (AppImage)

**Instalar**: descarga `Racha-<versión>-x86_64.AppImage`, dale permiso de ejecución y ábrelo:

```bash
chmod +x Racha-1.0.0-x86_64.AppImage
./Racha-1.0.0-x86_64.AppImage
./Racha-1.0.0-x86_64.AppImage --install       # menú del escritorio + abrir enlaces racha:// (solo en ~/.local/share)
./Racha-1.0.0-x86_64.AppImage --uninstall     # lo quita del menú
./Racha-1.0.0-x86_64.AppImage --self-test     # comprueba el motor de vídeo sin abrir ventana ni sonar
```

- Lleva dentro el JRE 25 (jlink) y **libmpv con sus bibliotecas**; usa del sistema solo lo que debe ser del sistema (glibc, controladores de GL/Vulkan, X11/Wayland, audio). Requiere **glibc 2.39 o posterior** (Ubuntu 24.04, Debian 13, Fedora 40, openSUSE Tumbleweed o más recientes).
- Si no tienes FUSE 2: `./Racha….AppImage --appimage-extract-and-run`.
- El llavero (KWallet, GNOME Keyring) se usa para la sesión de sincronización si existe; si no, un fichero que solo puede leer tu usuario.
- Datos: `~/.local/share/racha` (base de datos, portadas generadas), caché y log en `~/.cache/racha`.

**Construir**:

```bash
# herramientas: appimagetool y type2-runtime (github.com/AppImage), linuxdeploy (github.com/linuxdeploy)
cd client && desktop/packaging/build-appimage.sh appimagetool runtime-x86_64 linuxdeploy 1.0.0
```

El libmpv que se incluye es el de la máquina que construye (CI: Ubuntu 24.04, paquete `libmpv2`).

## 4. Publicar una versión

1. `main` en verde.
2. `git tag v1.0.0 && git push origin v1.0.0`.
3. CI ejecuta todo (servidor, cliente, e2e de sync, emulador y *smoke* de la release, AppImage con autotest) y crea la *release* de GitHub con `Racha-1.0.0-x86_64.AppImage` y `Racha-1.0.0.apk`.
