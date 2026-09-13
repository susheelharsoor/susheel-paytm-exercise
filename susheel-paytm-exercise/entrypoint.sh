#!/bin/sh
set -e

# ==========================================
# Standalone mode (DB_HOST=localhost):
#   Init the PostgreSQL cluster + app DB/user at first run, then hand off
#   to supervisord which manages both postgres and Spring Boot.
#
# External-DB mode (DB_HOST != localhost):
#   Drop privileges to appuser via gosu and start Spring Boot directly.
#   gosu preserves the full inherited environment (DB_HOST, DB_PASSWORD, …)
#   unlike su/su-exec which sanitise it.
# ==========================================

if [ "${DB_HOST:-localhost}" = "localhost" ]; then

    # Bootstrap the PostgreSQL cluster if it has never been initialised.
    # PGDATA is set via ENV in the Dockerfile; default: /var/lib/postgresql/16/main
    if [ ! -f "${PGDATA}/PG_VERSION" ]; then
        echo "[entrypoint] Initialising PostgreSQL cluster in ${PGDATA} ..."
        gosu postgres /usr/lib/postgresql/16/bin/initdb -D "${PGDATA}"
    fi

    # Start postgres temporarily to create the app user + database.
    echo "[entrypoint] Starting PostgreSQL to provision database ..."
    gosu postgres /usr/lib/postgresql/16/bin/pg_ctl \
        -D "${PGDATA}" \
        -o "-c listen_addresses=''" \
        -w start

    # Provision database + user if they do not already exist (idempotent).
    gosu postgres psql --username postgres <<-EOSQL
        DO \$\$
        BEGIN
            IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${DB_USERNAME}') THEN
                CREATE USER "${DB_USERNAME}" WITH PASSWORD '${DB_PASSWORD}';
            END IF;
        END
        \$\$;

        SELECT 'CREATE DATABASE "${DB_NAME}" OWNER "${DB_USERNAME}"'
        WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${DB_NAME}')\gexec
    EOSQL

    echo "[entrypoint] Stopping temporary PostgreSQL ..."
    gosu postgres /usr/lib/postgresql/16/bin/pg_ctl -D "${PGDATA}" -w stop

    echo "[entrypoint] Handing off to supervisord ..."
    exec /usr/bin/supervisord -n -c /etc/supervisor/conf.d/supervisord.conf

else
    # External database — drop to non-root and start the JVM directly.
    echo "[entrypoint] External DB detected (DB_HOST=${DB_HOST}), starting Spring Boot as appuser ..."
    exec gosu appuser java org.springframework.boot.loader.launch.JarLauncher
fi
