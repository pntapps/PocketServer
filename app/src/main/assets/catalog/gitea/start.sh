#!/bin/bash
source /opt/pocketserver/lib.sh
INI=$APP_DIR/app.ini

# Clone URLs follow the device's current Wi-Fi address.
IP=$(set -- ${PS_LAN_IPS:-}; echo "${1:-}")
if [ -n "$IP" ] && ! grep -qx "DOMAIN = $IP" "$INI"; then
    sed -i -e "s#^DOMAIN = .*#DOMAIN = $IP#" -e "s#^SSH_DOMAIN = .*#SSH_DOMAIN = $IP#" \
        -e "s#^ROOT_URL = .*#ROOT_URL = http://$IP:3300/#" "$INI"
fi

export GITEA_WORK_DIR=$APP_DIR GOMAXPROCS=2 GOGC=50
cd "$APP_DIR"
exec /opt/gitea/gitea web --config "$INI"
