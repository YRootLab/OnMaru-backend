FROM postgres:17-alpine

RUN apk add --no-cache gnupg jq

COPY --chmod=0555 bin/create-backup /usr/local/bin/create-backup
COPY --chmod=0555 bin/restore-drill /usr/local/bin/restore-drill
COPY sql /opt/onmaru/backup/sql
RUN chmod -R a+rX /opt/onmaru/backup/sql

ENV ONMARU_BACKUP_SQL_DIR=/opt/onmaru/backup/sql

USER postgres
ENTRYPOINT []
CMD ["restore-drill"]
