#!/bin/bash
source /opt/pocketserver/lib.sh
FALLBACK_VER=4.141.0

apt_install ca-certificates curl openssl nginx git

# Newest code-server release (its tarball bundles the Node.js it needs).
VER=$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/coder/code-server/releases/latest | sed -n 's#.*/tag/v##p')
VER=${VER:-$FALLBACK_VER}
fetch "https://github.com/coder/code-server/releases/download/v$VER/code-server-$VER-linux-arm64.tar.gz" /tmp/code-server.tgz
step "Installing code-server $VER"
rm -rf /opt/code-server && mkdir -p /opt/code-server
tar -xzf /tmp/code-server.tgz -C /opt/code-server --strip-components=1 --no-same-owner
rm -f /tmp/code-server.tgz

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/data" "$APP_DIR/extensions" "$APP_DIR/tmp"
mkdir -p "$APP_DIR/data/User" "$APP_DIR/extensions" "$APP_DIR/tmp" "$APP_DIR/tls" /root/projects
PASS=$(gen_password 16)
# Listens on localhost only: the LAN reaches it through nginx (HTTPS) below.
cat > "$APP_DIR/config.yaml" <<EOF
bind-addr: 127.0.0.1:8092
auth: password
password: $PASS
cert: false
user-data-dir: $APP_DIR/data
extensions-dir: $APP_DIR/extensions
disable-telemetry: true
disable-update-check: true
disable-workspace-trust: true
disable-getting-started-override: true
EOF
chmod 600 "$APP_DIR/config.yaml"
/opt/code-server/bin/code-server --config "$APP_DIR/config.yaml" --version | head -1
# No telemetry, update or marketplace polling; file watching and type downloads kept light.
cat > "$APP_DIR/data/User/settings.json" <<'EOF'
{
  "workbench.colorTheme": "Default Dark Modern",
  "workbench.startupEditor": "none",
  "workbench.enableExperiments": false,
  "workbench.tips.enabled": false,
  "telemetry.telemetryLevel": "off",
  "update.mode": "none",
  "extensions.autoCheckUpdates": false,
  "extensions.autoUpdate": false,
  "extensions.ignoreRecommendations": true,
  "security.workspace.trust.enabled": false,
  "npm.fetchOnlinePackageInfo": false,
  "typescript.disableAutomaticTypeAcquisition": true,
  "typescript.tsserver.maxTsServerMemory": 512,
  "git.autofetch": false,
  "git.openRepositoryInParentFolders": "never",
  "search.followSymlinks": false,
  "files.watcherExclude": {
    "**/node_modules/**": true, "**/vendor/**": true, "**/.git/objects/**": true,
    "**/.venv/**": true, "**/dist/**": true, "**/build/**": true
  },
  "editor.minimap.enabled": false,
  "terminal.integrated.gpuAcceleration": "off"
}
EOF

# nginx in front: HTTPS for other devices (WebSockets included), plain HTTP on localhost for this one.
cat > "$APP_DIR/nginx.conf" <<EOF
daemon off;
master_process off;
worker_processes 1;
error_log stderr warn;
pid $APP_DIR/tmp/nginx.pid;
events { worker_connections 256; }
http {
    access_log off;
    server_tokens off;
    client_body_temp_path $APP_DIR/tmp/body;
    proxy_temp_path $APP_DIR/tmp/proxy;
    fastcgi_temp_path $APP_DIR/tmp/fastcgi;
    uwsgi_temp_path $APP_DIR/tmp/uwsgi;
    scgi_temp_path $APP_DIR/tmp/scgi;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_certificate $APP_DIR/tls/server.crt;
    ssl_certificate_key $APP_DIR/tls/server.key;
    ssl_session_cache shared:SSL:1m;
    map \$http_upgrade \$connection_upgrade { default upgrade; '' close; }
    server {
        listen 8090 ssl;
        listen 127.0.0.1:8091;
        client_max_body_size 0;
        location / {
            proxy_pass http://127.0.0.1:8092;
            proxy_http_version 1.1;
            proxy_set_header Host \$http_host;
            proxy_set_header Upgrade \$http_upgrade;
            proxy_set_header Connection \$connection_upgrade;
            proxy_set_header X-Forwarded-For \$remote_addr;
            proxy_set_header X-Forwarded-Proto \$scheme;
            proxy_read_timeout 1d;
            proxy_send_timeout 1d;
            proxy_buffering off;
        }
    }
}
EOF
issue_server_cert "$APP_DIR/tls"
nginx -t -c "$APP_DIR/nginx.conf" -e stderr

reset_credentials
set_credential Password "$PASS"
set_credential "This device" "Tap Open (no certificate needed)"
set_credential "Other devices" "Install /shared/PocketServer-CA.crt once, then use the https address"
set_credential Projects /root/projects
