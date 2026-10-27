#!/bin/bash
source /opt/pocketserver/lib.sh
FALLBACK_VER=6.1.2
SOCK=$APP_DIR/tmp/php-fpm.sock

apt_install ca-certificates curl nginx php8.3-fpm php8.3-opcache php8.3-mysql php8.3-sqlite3 \
    php8.3-mbstring php8.3-xml php8.3-curl php8.3-zip php8.3-gd php8.3-intl php8.3-bcmath

# Adminer: single-file database manager (MySQL/MariaDB, SQLite, PostgreSQL), newest release.
VER=$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/vrana/adminer/releases/latest | sed -n 's#.*/tag/v##p')
VER=${VER:-$FALLBACK_VER}
mkdir -p /opt/adminer
fetch "https://github.com/vrana/adminer/releases/download/v$VER/adminer-$VER.php" /opt/adminer/adminer.php.new
mv /opt/adminer/adminer.php.new /opt/adminer/adminer.php

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/tmp"
mkdir -p "$APP_DIR/tmp" /root/www
# Workers start on demand and exit after 10 s idle: nothing runs between requests.
cat > "$APP_DIR/php-fpm.conf" <<EOF
[global]
pid = $APP_DIR/tmp/php-fpm.pid
error_log = /proc/self/fd/2
log_level = warning
daemonize = no
process.max = 3

[www]
user = root
group = root
listen = $SOCK
listen.mode = 0660
pm = ondemand
pm.max_children = 3
pm.process_idle_timeout = 10s
pm.max_requests = 500
catch_workers_output = yes
decorate_workers_output = no
clear_env = no
php_admin_value[error_log] = /proc/self/fd/2
php_admin_flag[log_errors] = on
php_admin_value[memory_limit] = 128M
php_value[upload_max_filesize] = 64M
php_value[post_max_size] = 64M
EOF
# OPcache on (no recompiling on every request); files are still re-checked every 2 s while editing.
cat > /etc/php/8.3/fpm/conf.d/99-pocketserver.ini <<'EOF'
opcache.enable=1
opcache.memory_consumption=32
opcache.interned_strings_buffer=8
opcache.max_accelerated_files=4000
opcache.validate_timestamps=1
opcache.revalidate_freq=2
opcache.jit=off
expose_php=off
EOF

cat > "$APP_DIR/nginx.conf" <<EOF
daemon off;
master_process off;
worker_processes 1;
error_log stderr warn;
pid $APP_DIR/tmp/nginx.pid;
events { worker_connections 256; }
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    access_log off;
    server_tokens off;
    sendfile on;
    client_max_body_size 64m;
    client_body_temp_path $APP_DIR/tmp/body;
    proxy_temp_path $APP_DIR/tmp/proxy;
    fastcgi_temp_path $APP_DIR/tmp/fastcgi;
    uwsgi_temp_path $APP_DIR/tmp/uwsgi;
    scgi_temp_path $APP_DIR/tmp/scgi;
    server {
        listen 8088;
        root /root/www;
        index index.php index.html index.htm;
        autoindex off;
        location / { try_files \$uri \$uri/ /index.php?\$query_string; }
        location ~ /\.(?!well-known) { deny all; }
        location ~ \.php\$ {
            try_files \$fastcgi_script_name =404;
            include /etc/nginx/fastcgi_params;
            fastcgi_param SCRIPT_FILENAME \$document_root\$fastcgi_script_name;
            fastcgi_pass unix:$SOCK;
        }
        location = /adminer { return 301 /adminer/; }
        location = /adminer/ {
            # Same reach as the database: this device only unless MariaDB's Wi-Fi access is on
            # (start.sh rewrites this file on every start).
            include $APP_DIR/adminer-access.conf;
            include /etc/nginx/fastcgi_params;
            fastcgi_param SCRIPT_FILENAME /opt/adminer/adminer.php;
            fastcgi_param SCRIPT_NAME /adminer/;
            fastcgi_pass unix:$SOCK;
        }
    }
}
EOF
printf 'allow 127.0.0.1;
deny all;
' > "$APP_DIR/adminer-access.conf"
nginx -t -c "$APP_DIR/nginx.conf" -e stderr
php-fpm8.3 -t -R -y "$APP_DIR/php-fpm.conf"

reset_credentials
set_credential "Web root" /root/www
set_credential Adminer "/adminer/ (MariaDB: server 127.0.0.1, user dev)"
