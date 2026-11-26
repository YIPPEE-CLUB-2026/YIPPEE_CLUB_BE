#!/usr/bin/env bash
# Daily backup of the database and the activity files, kept on this server for KEEP_DAYS days.
# Run as root (see the cron line at the bottom). The database is read over the local socket as
# root, so no password is written anywhere.
#
# What this protects against: a bad migration, a mistaken delete, a broken deploy.
# What it does not: losing the disk itself. For that the disk needs snapshots (or these files
# must be copied off the server); a snapshot of the disk also carries these dumps.
#
# Install:  install -m 700 deploy/backup.sh /usr/local/sbin/yippee-backup
#           echo '15 18 * * * root /usr/local/sbin/yippee-backup' > /etc/cron.d/yippee-backup   # 03:15 KST
# Restore:  gunzip -c /var/backups/yippee/db-<stamp>.sql.gz | mysql yippee_club_db
#           tar -xzf /var/backups/yippee/files-<stamp>.tar.gz -C /
set -euo pipefail
umask 077

DATABASE="${BACKUP_DATABASE:-yippee_club_db}"
FILES="${BACKUP_FILES:-/data/yippee-files}"
TARGET="${BACKUP_TARGET:-/var/backups/yippee}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"

mkdir -p "$TARGET"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"

# One consistent picture of every table without stopping the service. Written under a temporary
# name first: a dump that broke off half-way never looks like a finished backup.
mysqldump --single-transaction --quick --routines --triggers --set-gtid-purged=OFF "$DATABASE" \
    | gzip > "$TARGET/db-$stamp.sql.gz.part"
gzip -t "$TARGET/db-$stamp.sql.gz.part"
mv "$TARGET/db-$stamp.sql.gz.part" "$TARGET/db-$stamp.sql.gz"

tar -czf "$TARGET/files-$stamp.tar.gz.part" -C / "${FILES#/}"
mv "$TARGET/files-$stamp.tar.gz.part" "$TARGET/files-$stamp.tar.gz"

# Old backups go only after today's are complete.
find "$TARGET" -maxdepth 1 -type f \( -name 'db-*.sql.gz' -o -name 'files-*.tar.gz' -o -name '*.part' \) \
    -mtime "+$KEEP_DAYS" -delete

echo "backup $stamp: $(du -h "$TARGET/db-$stamp.sql.gz" | cut -f1) database, $(du -h "$TARGET/files-$stamp.tar.gz" | cut -f1) files"
