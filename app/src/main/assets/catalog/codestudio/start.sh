#!/bin/bash
source /opt/pocketserver/lib.sh
# Certificate follows the device's current Wi-Fi address.
issue_server_cert "$APP_DIR/tls"
/opt/code-server/bin/code-server --config "$APP_DIR/config.yaml" /root/projects &
CS=$!
# nginx opens the public ports only once the editor answers, so "Running" means usable.
for _ in $(seq 120); do
    kill -0 $CS 2>/dev/null || { wait $CS; exit 1; }
    (exec 3<>/dev/tcp/127.0.0.1/8092) 2>/dev/null && break
    sleep 1
done
nginx -c "$APP_DIR/nginx.conf" -e stderr &
NG=$!
supervise $CS $NG
