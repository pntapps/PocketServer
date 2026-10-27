#!/bin/bash
source /opt/pocketserver/lib.sh
export NEXTCLOUD_CONFIG_DIR=$APP_DIR/config PHP_INI_SCAN_DIR=":$APP_DIR/php.d"
occ() { php8.3 /opt/nextcloud/occ --no-interaction "$@" </dev/null >/dev/null; }

# Certificate and trusted addresses follow the device's current Wi-Fi address
# (occ only runs when the addresses changed).
issue_server_cert "$APP_DIR/tls"
IPS="${PS_LAN_IPS:-$(hostname -I 2>/dev/null || true)}"
WANT="localhost 127.0.0.1 pocketserver.local $IPS"
if [ "$WANT" != "$(cat "$APP_DIR/trusted" 2>/dev/null)" ]; then
    occ config:system:delete trusted_domains
    i=0
    for d in $WANT; do occ config:system:set trusted_domains $i --value="$d"; i=$((i + 1)); done
    set -- $IPS
    occ config:system:set overwrite.cli.url --value="https://${1:-pocketserver.local}:8446"
    echo "$WANT" > "$APP_DIR/trusted"
fi

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
