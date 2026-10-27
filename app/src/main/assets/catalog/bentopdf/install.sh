#!/bin/bash
source /opt/pocketserver/lib.sh
VER=v2.8.10
WEB=/opt/bentopdf/html
SRC=https://raw.githubusercontent.com/alam00000/bentopdf/$VER

apt_install ca-certificates curl unzip openssl nginx

# The "simple" build is the full toolset without the marketing pages. Every PDF is processed in the
# visitor's browser (WebAssembly); the server only hands out static files.
fetch "https://github.com/alam00000/bentopdf/releases/download/$VER/dist-simple-${VER#v}.zip" /tmp/bento.zip \
    c8b59b2572c522ffc06fdfa5f76cb926cc84ef077dd025e15074de7abf0f7777
step "Unpacking BentoPDF"
rm -rf /opt/bentopdf && mkdir -p "$WEB"
unzip -q /tmp/bento.zip -d "$WEB"
rm -f /tmp/bento.zip
# Brotli copies are useless to stock nginx and source maps are for developers.
find "$WEB" \( -name '*.br' -o -name '*.map' \) -delete

step "Pre-compressing files"
# Compress once now so nginx never gzips on the fly: page loads then cost no CPU (and no heat).
find "$WEB" -type f -size +1k \( -name '*.js' -o -name '*.mjs' -o -name '*.css' -o -name '*.json' \
    -o -name '*.wasm' -o -name '*.svg' -o -name '*.html' -o -name '*.txt' -o -name '*.xml' \) \
    -exec gzip -k -6 -f {} +

step "Applying battery-optimized settings"
mkdir -p "$APP_DIR/tmp" "$APP_DIR/tls"
# Security headers (incl. the cross-origin isolation BentoPDF's multi-threaded engines need) come
# from the release's own _headers file.
headers_conf() {  # headers_conf <path-pattern-line> -> nginx add_header lines for that block
    awk -v blk="$1" '
        $0 == blk { inb = 1; next }
        /^[^ ]/ { inb = 0 }
        inb && /^  [A-Za-z-]+: / { sub(/^  /, ""); n = index($0, ": ");
            printf "add_header %s \"%s\" always;\n", substr($0, 1, n - 1), substr($0, n + 2) }
    ' "$WEB/_headers"
}
headers_conf "/*" > "$APP_DIR/security-headers.conf"
headers_conf "/docs/*" > "$APP_DIR/security-headers-docs.conf"
[ -s "$APP_DIR/security-headers.conf" ] || { echo "Could not read BentoPDF security headers" >&2; exit 1; }

# Upstream nginx.conf (routes, WebAssembly types), adapted: one process, no access log, no
# on-the-fly gzip, HTTPS for the LAN plus plain HTTP on localhost for this device's own browser.
curl -fsSL "$SRC/nginx.conf" -o /tmp/bento-nginx.conf
sed -e "s#^pid .*#daemon off;\nmaster_process off;\nworker_processes 1;\nerror_log stderr warn;\npid $APP_DIR/tmp/nginx.pid;#" \
    -e "s#worker_connections 1024#worker_connections 256#" \
    -e "s#^    gzip on;#    gzip off;\n    server_tokens off;\n    access_log off;\n    client_body_temp_path $APP_DIR/tmp/body;\n    proxy_temp_path $APP_DIR/tmp/proxy;\n    fastcgi_temp_path $APP_DIR/tmp/fastcgi;\n    uwsgi_temp_path $APP_DIR/tmp/uwsgi;\n    scgi_temp_path $APP_DIR/tmp/scgi;\n    ssl_protocols TLSv1.2 TLSv1.3;\n    ssl_certificate $APP_DIR/tls/server.crt;\n    ssl_certificate_key $APP_DIR/tls/server.key;#" \
    -e "s#^        listen 8080;#        listen 8444 ssl;\n        listen 127.0.0.1:8085;#" \
    -e "/listen \[::\]:8080;/d" \
    -e "s#/etc/nginx/security-headers#$APP_DIR/security-headers#g" \
    -e "s#root /usr/share/nginx/html;#root $WEB;#" \
    /tmp/bento-nginx.conf > "$APP_DIR/nginx.conf"
rm -f /tmp/bento-nginx.conf
issue_server_cert "$APP_DIR/tls"
nginx -t -c "$APP_DIR/nginx.conf" -e stderr

reset_credentials
set_credential "This device" "Tap Open (no certificate needed)"
set_credential "Other devices" "Install /shared/PocketServer-CA.crt once, then use the https address"
