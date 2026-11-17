#!/bin/bash
# Restores a database backup (backups/racha-*.dump, or any `pg_dump -Fc` of Racha) into the docker compose server.
# Also the way to move the server to another machine: copy .env and the newest dump there, start it, run this.
#
# usage (from the folder with docker-compose.yml): tools/restore-backup.sh backups/racha-20261117-033000.dump
set -euo pipefail

dump=${1:?usage: tools/restore-backup.sh <file.dump>}
[ -f "$dump" ] || { echo "No such file: $dump" >&2; exit 2; }
[ -f docker-compose.yml ] || { echo "Run it from the folder that has docker-compose.yml" >&2; exit 2; }

echo "This REPLACES every account, device and synced item on this server with the contents of:"
echo "  $dump ($(du -h "$dump" | cut -f1), $(date -r "$dump" '+%Y-%m-%d %H:%M'))"
read -r -p "Type 'restaurar' to continue: " answer
[ "$answer" = restaurar ] || { echo "Nothing changed."; exit 1; }

docker compose up -d db
until docker compose exec -T db sh -c 'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; do sleep 1; done
# The server must not write while the tables are replaced; devices keep their changes queued meanwhile.
docker compose stop server
docker compose exec -T db sh -c 'pg_restore --clean --if-exists --no-owner --single-transaction -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$dump"
docker compose start server
echo "Restored. Devices sync on their next round; sessions in the backup are valid again."
