# Backup-Container: Datenbank-Dump nach Zeitplan mit Aufbewahrung. Basis ist das Postgres-Image (bringt pg_dump in
# der passenden Version, Zeitzonen und einen Nutzer ohne Root-Rechte mit).
FROM postgres:17.11-alpine@sha256:e5d2edd28b1f8d40a0a6c0c9cdc545b19b56a0d5d88f94bc11a97d970d4700d0
RUN mkdir /backups && chown 70:70 /backups
COPY --chmod=755 backup.sh run.sh restore.sh /usr/local/bin/
USER postgres
# Gesund, wenn das letzte erfolgreiche Backup nicht älter als 36 Stunden ist
HEALTHCHECK --interval=5m --timeout=10s --start-period=3m --start-interval=10s --retries=2 \
  CMD [ -f /backups/.last_ok ] && [ $(( $(date +%s) - $(stat -c %Y /backups/.last_ok) )) -lt 129600 ] || exit 1
ENTRYPOINT ["run.sh"]
