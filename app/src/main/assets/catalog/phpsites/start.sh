#!/bin/bash
source /opt/pocketserver/lib.sh
mkdir -p "$APP_DIR/tmp"
rm -f "$APP_DIR/tmp/php-fpm.sock"
# Adminer manages MariaDB, so it gets the database's reach: local unless Wi-Fi access is on.
if [ -f /opt/pocketserver/apps/mariadb/lan-access ]; then
    echo 'allow all;' > "$APP_DIR/adminer-access.conf"
else
    printf 'allow 127.0.0.1;
deny all;
' > "$APP_DIR/adminer-access.conf"
fi
# -R: everything runs as (fake) root inside PocketServer's Ubuntu.
php-fpm8.3 -F -R -y "$APP_DIR/php-fpm.conf" &
FPM=$!
# nginx only after PHP's socket exists: otherwise the first requests fail with 502.
SOCK=$(sed -n 's/^listen *= *//p' "$APP_DIR/php-fpm.conf" | head -1)
for _ in $(seq 60); do [ -S "$SOCK" ] && break; kill -0 $FPM 2>/dev/null || break; sleep 0.5; done
nginx -c "$APP_DIR/nginx.conf" -e stderr &
NG=$!
supervise $FPM $NG
