#!/bin/bash
source /opt/pocketserver/lib.sh
# Newest release of the major series that supports Ubuntu 24.04's PHP 8.3.
MAJOR=35
FALLBACK_VER=35.0.2
WEB=/opt/nextcloud
SOCK=$APP_DIR/tmp/php-fpm.sock
export NEXTCLOUD_CONFIG_DIR=$APP_DIR/config PHP_INI_SCAN_DIR=":$APP_DIR/php.d"
occ() { php8.3 "$WEB/occ" --no-interaction "$@" </dev/null; }

apt_install ca-certificates curl unzip openssl nginx php8.3-fpm php8.3-cli php8.3-opcache php8.3-sqlite3 \
    php8.3-mbstring php8.3-xml php8.3-curl php8.3-zip php8.3-gd php8.3-intl php8.3-bcmath php8.3-gmp php-apcu

REL=https://download.nextcloud.com/server/releases
VER=$(curl -fsSL "$REL/" | grep -o "nextcloud-$MAJOR\.[0-9]*\.[0-9]*\.zip\"" | tr -d '"' | sed 's/^nextcloud-//; s/\.zip$//' | sort -V | tail -1 || true)
VER=${VER:-$FALLBACK_VER}
WORK=/tmp/nextcloud-dl && rm -rf $WORK && mkdir -p $WORK
fetch "$REL/nextcloud-$VER.zip" $WORK/nc.zip
step "Verifying checksum"
SUM=$(curl -fsSL "$REL/nextcloud-$VER.zip.sha256" | awk -v f="nextcloud-$VER.zip" '$2 == f {print $1}')
[ -n "$SUM" ] && [ "$(sha256sum $WORK/nc.zip | cut -d' ' -f1)" = "$SUM" ] || { echo "Checksum mismatch for Nextcloud $VER" >&2; exit 1; }
step "Unpacking Nextcloud $VER"
unzip -q $WORK/nc.zip -d $WORK
rm -f $WORK/nc.zip
rm -rf $WEB && mv $WORK/nextcloud $WEB
rm -rf $WORK
# Configuration, data and apps added later all live in $APP_DIR (backups, clean removal).
mkdir -p "$APP_DIR"/{config,data,custom_apps,php.d,tls} && chmod 770 "$APP_DIR/data"
rm -rf "$WEB/config" && ln -s "$APP_DIR/config" "$WEB/config"
rm -rf "$APP_DIR/tmp" && mkdir -p "$APP_DIR/tmp"

step "Applying battery-optimized settings"
# Its own PHP settings (loaded after the system ones): OPcache so nothing is recompiled, APCu
# for the local cache and file locks (no Redis), Nextcloud's minimum memory_limit.
cat > "$APP_DIR/php.d/zz-nextcloud.ini" <<'EOF'
memory_limit=512M
upload_max_filesize=1G
post_max_size=1G
output_buffering=0
expose_php=off
opcache.enable=1
opcache.enable_cli=0
opcache.memory_consumption=64
opcache.interned_strings_buffer=16
opcache.max_accelerated_files=10000
opcache.validate_timestamps=1
opcache.revalidate_freq=60
opcache.save_comments=1
opcache.jit=off
apc.enabled=1
apc.enable_cli=1
apc.shm_size=32M
EOF

# PHP workers start on demand and exit after 10 s idle: between requests nothing runs.
cat > "$APP_DIR/php-fpm.conf" <<EOF
[global]
pid = $APP_DIR/tmp/php-fpm.pid
error_log = /proc/self/fd/2
log_level = warning
daemonize = no
process.max = 4

[nextcloud]
user = root
group = root
listen = $SOCK
listen.mode = 0660
pm = ondemand
pm.max_children = 4
pm.process_idle_timeout = 10s
pm.max_requests = 500
catch_workers_output = yes
decorate_workers_output = no
clear_env = no
env[PATH] = /usr/local/bin:/usr/bin:/bin
env[NEXTCLOUD_CONFIG_DIR] = $APP_DIR/config
php_admin_value[error_log] = /proc/self/fd/2
php_admin_flag[log_errors] = on
EOF

# Static settings (an extra config file Nextcloud merges in). Nothing checks for updates;
# previews are small and never made from videos; logs are capped.
cat > "$APP_DIR/config/pocketserver.config.php" <<EOF
<?php
\$CONFIG = array (
  'apps_paths' => array (
    array ('path' => '$WEB/apps', 'url' => '/apps', 'writable' => false),
    array ('path' => '$APP_DIR/custom_apps', 'url' => '/custom_apps', 'writable' => true),
  ),
  'memcache.local' => '\\\\OC\\\\Memcache\\\\APCu',
  'memcache.locking' => '\\\\OC\\\\Memcache\\\\APCu',
  'filelocking.enabled' => true,
  'updatechecker' => false,
  'upgrade.disable-web' => true,
  'has_internet_connection' => true,
  'appstoreenabled' => true,
  'lookup_server' => '',
  'enable_previews' => true,
  'preview_max_x' => 1024,
  'preview_max_y' => 1024,
  'preview_max_scale_factor' => 1,
  'preview_concurrency_all' => 2,
  'preview_concurrency_new' => 1,
  'jpeg_quality' => 70,
  'enabledPreviewProviders' => array (
    'OC\\\\Preview\\\\PNG', 'OC\\\\Preview\\\\JPEG', 'OC\\\\Preview\\\\GIF', 'OC\\\\Preview\\\\BMP',
    'OC\\\\Preview\\\\XBitmap', 'OC\\\\Preview\\\\WebP', 'OC\\\\Preview\\\\MP3', 'OC\\\\Preview\\\\TXT',
    'OC\\\\Preview\\\\MarkDown', 'OC\\\\Preview\\\\Krita', 'OC\\\\Preview\\\\OpenDocument',
  ),
  'loglevel' => 2,
  'log_rotate_size' => 5242880,
  'maintenance_window_start' => 1,
  'skeletondirectory' => '',
  'templatedirectory' => '',
  'simpleSignUpLink.shown' => false,
  'activity_expire_days' => 30,
  'trashbin_retention_obligation' => 'auto, 30',
  'versions_retention_obligation' => 'auto, 30',
  'htaccess.RewriteBase' => '/',
);
EOF

# Security headers, repeated in blocks that set their own add_header.
cat > "$APP_DIR/headers.conf" <<'EOF'
add_header Referrer-Policy "no-referrer" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header X-Permitted-Cross-Domain-Policies "none" always;
add_header X-Robots-Tag "noindex, nofollow" always;
EOF

# Nextcloud's recommended nginx configuration, adapted: one process, no access log, no
# on-the-fly gzip, HTTPS for the LAN plus plain HTTP on localhost for this device's own browser.
cat > "$APP_DIR/nginx.conf" <<EOF
daemon off;
master_process off;
worker_processes 1;
error_log stderr warn;
pid $APP_DIR/tmp/nginx.pid;
events { worker_connections 256; }
http {
    include /etc/nginx/mime.types;
    types { text/javascript mjs; }
    default_type application/octet-stream;
    access_log off;
    server_tokens off;
    sendfile on;
    gzip off;
    client_body_temp_path $APP_DIR/tmp/body;
    proxy_temp_path $APP_DIR/tmp/proxy;
    fastcgi_temp_path $APP_DIR/tmp/fastcgi;
    uwsgi_temp_path $APP_DIR/tmp/uwsgi;
    scgi_temp_path $APP_DIR/tmp/scgi;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:1m;
    ssl_session_timeout 1h;
    ssl_certificate $APP_DIR/tls/server.crt;
    ssl_certificate_key $APP_DIR/tls/server.key;

    upstream php-handler { server unix:$SOCK; }
    map \$arg_v \$asset_immutable { "" ""; default ", immutable"; }

    server {
        listen 8446 ssl;
        listen 127.0.0.1:8087;
        server_name _;
        root $WEB;

        client_max_body_size 1G;
        client_body_timeout 300s;
        client_body_buffer_size 512k;
        fastcgi_buffers 64 4K;
        include $APP_DIR/headers.conf;
        fastcgi_hide_header X-Powered-By;
        index index.php index.html /index.php\$request_uri;

        location = / {
            if ( \$http_user_agent ~ ^DavClnt ) { return 302 /remote.php/webdav/\$is_args\$args; }
        }
        location = /robots.txt { allow all; }
        location ^~ /.well-known {
            location = /.well-known/carddav { return 301 /remote.php/dav/; }
            location = /.well-known/caldav  { return 301 /remote.php/dav/; }
            location /.well-known/acme-challenge { try_files \$uri \$uri/ =404; }
            location /.well-known/pki-validation { try_files \$uri \$uri/ =404; }
            return 301 /index.php\$request_uri;
        }
        location ~ ^/(?:build|tests|config|lib|3rdparty|templates|data)(?:\$|/) { return 404; }
        location ~ ^/(?:\.|autotest|occ|issue|indie|db_|console) { return 404; }

        # Apps added from the app store live in $APP_DIR/custom_apps.
        location ^~ /custom_apps/ {
            root $APP_DIR;
            location ~ \.php(?:\$|/) { rewrite ^ /index.php\$request_uri last; }
            try_files \$uri /index.php\$request_uri;
            include $APP_DIR/headers.conf;
            add_header Cache-Control "public, max-age=15778463\$asset_immutable";
        }

        location ~ \.php(?:\$|/) {
            rewrite ^/(?!index|remote|public|cron|core\/ajax\/update|status|ocs\/v[12]|updater\/.+|ocs-provider\/.+|.+\/richdocumentscode(_arm64)?\/proxy) /index.php\$request_uri;
            fastcgi_split_path_info ^(.+?\.php)(/.*)\$;
            set \$path_info \$fastcgi_path_info;
            try_files \$fastcgi_script_name =404;
            include /etc/nginx/fastcgi_params;
            fastcgi_param SCRIPT_FILENAME \$document_root\$fastcgi_script_name;
            fastcgi_param PATH_INFO \$path_info;
            fastcgi_param HTTPS \$https;
            fastcgi_param modHeadersAvailable true;
            fastcgi_param front_controller_active true;
            fastcgi_pass php-handler;
            fastcgi_intercept_errors on;
            fastcgi_request_buffering off;
            fastcgi_max_temp_file_size 0;
            fastcgi_read_timeout 300s;
        }
        location ~ \.(?:css|js|mjs|svg|gif|ico|jpg|png|webp|wasm|tflite|map|ogg|flac)\$ {
            try_files \$uri /index.php\$request_uri;
            include $APP_DIR/headers.conf;
            add_header Cache-Control "public, max-age=15778463\$asset_immutable";
        }
        location ~ \.(otf|woff2?)\$ {
            try_files \$uri /index.php\$request_uri;
            expires 7d;
        }
        location /remote { return 301 /remote.php\$request_uri; }
        location / { try_files \$uri \$uri/ /index.php\$request_uri; }
    }
}
EOF
issue_server_cert "$APP_DIR/tls"
nginx -t -c "$APP_DIR/nginx.conf" -e stderr
php-fpm8.3 -t -R -y "$APP_DIR/php-fpm.conf"

reset_credentials
if grep -qs "'installed' => true" "$APP_DIR/config/config.php"; then
    step "Upgrading Nextcloud"
    occ upgrade || true
    occ maintenance:mode --off || true
else
    # SQLite: no database server has to be running, fine for one person's files. Many users or
    # heavy sync clients would want MariaDB instead (occ db:convert-type).
    step "Setting up Nextcloud (takes a few minutes)"
    PASS=$(gen_password 20)
    occ maintenance:install --database sqlite --database-name nextcloud \
        --admin-user admin --admin-pass "$PASS" --data-dir "$APP_DIR/data" >/dev/null
    echo "$PASS" > "$APP_DIR/.admin-password" && chmod 600 "$APP_DIR/.admin-password"
    occ maintenance:repair --include-expensive >/dev/null  # mimetype migrations: quick while empty
fi

step "Turning off background work and online extras"
# Background jobs run briefly after page visits (no cron daemon waking the phone).
occ background:ajax >/dev/null
# Apps that phone home, run machine learning, or need Docker/extra services.
ENABLED=$(occ app:list --output=json)
for a in survey_client firstrunwizard recommendations weather_status support updatenotification \
    nextcloud_announcements suspicious_login app_api; do
    grep -q "\"$a\"" <<<"$ENABLED" && occ app:disable "$a" >/dev/null 2>&1 || true
done
occ config:app:set password_policy enforceHaveIBeenPwned --value=0 >/dev/null 2>&1 || true
rm -f "$APP_DIR/trusted"  # start.sh sets trusted_domains for the current addresses

set_credential "Admin user" admin
[ -f "$APP_DIR/.admin-password" ] && set_credential "Admin password" "$(cat "$APP_DIR/.admin-password")"
set_credential "This device" "Tap Open (no certificate needed)"
set_credential "Other devices" "Install /shared/PocketServer-CA.crt once, then use the https address"
set_credential "Calendar & Contacts" "Apps menu > Office & text > Download and enable (needs internet once)"
