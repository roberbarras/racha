#!/bin/sh
# Database backups for the `backup` service of docker-compose.yml (postgres image: pg_dump of the same version).
#
# Every day at RACHA_BACKUP_TIME (container time zone, TZ) it writes /backups/racha-YYYYMMDD-HHMMSS.dump (custom
# format: tools/restore-backup.sh restores it), and once at start-up if the newest one is more than a day old.
# Keeps the newest RACHA_BACKUP_KEEP_DAILY dumps plus one per week (Mondays) for RACHA_BACKUP_KEEP_WEEKLY weeks.
# Files belong to whoever owns the backups folder and only they can read them (they hold every account's data).
#
#   racha-backup          run the schedule (the service's entrypoint)
#   racha-backup now      one backup right away: docker compose exec backup racha-backup now
set -eu

dir=/backups
time_of_day=${RACHA_BACKUP_TIME:-03:30}
keep_daily=${RACHA_BACKUP_KEEP_DAILY:-14}
keep_weekly=${RACHA_BACKUP_KEEP_WEEKLY:-8}

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') racha-backup: $*"; }

backup() {
    stamp=$(date +%Y%m%d-%H%M%S)
    final="$dir/racha-$stamp.dump"
    part="$dir/.racha-$stamp.dump.part"
    if pg_dump --format=custom --file="$part"; then
        chown "$(stat -c %u:%g "$dir")" "$part" 2>/dev/null || true
        chmod 600 "$part"
        mv "$part" "$final"
        log "saved $(basename "$final") ($(du -h "$final" | cut -f1))"
        prune
    else
        rm -f "$part"
        log "FAILED: pg_dump could not dump the database" >&2
        return 1
    fi
}

# Newest first: the first $keep_daily stay; of the rest, the newest of each Monday for $keep_weekly weeks.
prune() {
    count=0
    weeks=0
    last_week=""
    for file in $(ls -1 "$dir"/racha-*.dump 2>/dev/null | sort -r); do
        count=$((count + 1))
        [ "$count" -le "$keep_daily" ] && continue
        day=$(basename "$file" | cut -c7-14)
        weekday=$(date -d "$(echo "$day" | cut -c1-4)-$(echo "$day" | cut -c5-6)-$(echo "$day" | cut -c7-8)" +%u 2>/dev/null || echo 0)
        if [ "$weekday" = 1 ] && [ "$day" != "$last_week" ] && [ "$weeks" -lt "$keep_weekly" ]; then
            weeks=$((weeks + 1))
            last_week=$day
            continue
        fi
        rm -f "$file"
        log "removed $(basename "$file")"
    done
}

newest_age() {
    newest=$(ls -1 "$dir"/racha-*.dump 2>/dev/null | sort -r | head -n 1)
    [ -n "$newest" ] || { echo 999999; return; }
    echo $(( $(date +%s) - $(stat -c %Y "$newest") ))
}

if [ ! -w "$dir" ]; then
    log "FAILED: $dir is not writable (create the backups folder before starting, or fix its owner)" >&2
    exit 1
fi

if [ "${1:-}" = "now" ]; then
    backup
    exit
fi

trap 'log "stopping"; exit 0' TERM INT
log "daily at $time_of_day ($(date +%Z)), keeping $keep_daily daily and $keep_weekly weekly backups in $dir"
if [ "$(newest_age)" -gt 86400 ]; then backup || true; fi
while true; do
    now=$(date +%s)
    next=$(date -d "$(date +%Y-%m-%d) $time_of_day" +%s)
    [ "$next" -le "$now" ] && next=$((next + 86400))
    sleep $((next - now)) &
    wait $!
    backup || true
done
