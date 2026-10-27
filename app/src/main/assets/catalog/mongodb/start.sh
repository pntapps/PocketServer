#!/bin/bash
APP_DIR=/opt/pocketserver/apps/mongodb
# Wi-Fi access is switched on from the app (flag file). Authentication is always on.
EXTRA=()
[ -f "$APP_DIR/lan-access" ] && EXTRA=(--bind_ip_all)
exec mongod --config "$APP_DIR/mongod.conf" "${EXTRA[@]}"
