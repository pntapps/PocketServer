#!/bin/bash
APP_DIR=/opt/pocketserver/apps/mariadb
mkdir -p /run/mysqld "$APP_DIR/tmp"
# Wi-Fi access is switched on from the app (flag file): listen on all addresses. Logins still
# need the dev password; root can only log in from this device.
EXTRA=()
[ -f "$APP_DIR/lan-access" ] && EXTRA=(--bind-address=0.0.0.0)
exec mariadbd --defaults-file="$APP_DIR/my.cnf" "${EXTRA[@]}"
