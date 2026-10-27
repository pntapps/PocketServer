#!/bin/bash
source /opt/pocketserver/lib.sh
export PHP_INI_SCAN_DIR=":$APP_DIR/php.d"
# The certificate follows the device's current Wi-Fi address (re-issued only when it changed).
issue_server_cert "$APP_DIR/tls"

mkdir -p "$APP_DIR/tmp"
rm -f "$APP_DIR/tmp/php-fpm.sock"
# -R: everything runs as (fake) root inside PocketServer's Ubuntu.
php-fpm8.3 -F -R -y "$APP_DIR/php-fpm.conf" &
FPM=$!
# nginx only after PHP's socket exists: otherwise the first requests fail with 502.
SOCK=$(sed -n 's/^listen *= *//p' "$APP_DIR/php-fpm.conf" | head -1)
for _ in $(seq 60); do [ -S "$SOCK" ] && break; kill -0 $FPM 2>/dev/null || break; sleep 0.5; done
nginx -c "$APP_DIR/nginx.conf" -e stderr &
NG=$!
supervise $FPM $NG
