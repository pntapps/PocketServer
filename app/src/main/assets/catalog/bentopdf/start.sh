#!/bin/bash
source /opt/pocketserver/lib.sh
# Certificate follows the device's current Wi-Fi address.
issue_server_cert "$APP_DIR/tls"
exec nginx -c "$APP_DIR/nginx.conf" -e stderr
