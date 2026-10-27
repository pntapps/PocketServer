#!/bin/bash
source /opt/pocketserver/lib.sh
FALLBACK_VER=7.1.3
WPCLI_VER=2.12.0
# Core files are replaced on every install (upgrade); wp-content, the database and secrets are kept.
WEB=$APP_DIR/site
SOCK=$APP_DIR/tmp/php-fpm.sock
export PHP_INI_SCAN_DIR=":$APP_DIR/php.d"
wp() { php8.3 /usr/local/bin/wp --allow-root --path="$WEB" "$@" </dev/null; }

apt_install ca-certificates curl unzip openssl nginx php8.3-fpm php8.3-cli php8.3-opcache php8.3-sqlite3 \
    php8.3-mysql php8.3-mbstring php8.3-xml php8.3-curl php8.3-zip php8.3-gd php8.3-intl

# WP-CLI does the setup (no web installer to click through).
fetch "https://github.com/wp-cli/wp-cli/releases/download/v$WPCLI_VER/wp-cli-$WPCLI_VER.phar" /tmp/wp-cli.phar
SUM=$(curl -fsSL "https://github.com/wp-cli/wp-cli/releases/download/v$WPCLI_VER/wp-cli-$WPCLI_VER.phar.sha512" | cut -d' ' -f1)
[ -n "$SUM" ] && [ "$(sha512sum /tmp/wp-cli.phar | cut -d' ' -f1)" = "$SUM" ] || { echo "Checksum mismatch for WP-CLI" >&2; exit 1; }
install -m 755 /tmp/wp-cli.phar /usr/local/bin/wp && rm -f /tmp/wp-cli.phar

VER=$(curl -fsSL https://api.wordpress.org/core/version-check/1.7/ | grep -o '"current":"[0-9.]*"' | head -1 | cut -d'"' -f4 || true)
VER=${VER:-$FALLBACK_VER}
WORK=/tmp/wordpress-dl && rm -rf $WORK && mkdir -p $WORK
fetch "https://wordpress.org/wordpress-$VER.tar.gz" $WORK/wp.tar.gz
step "Verifying checksum"
SUM=$(curl -fsSL "https://wordpress.org/wordpress-$VER.tar.gz.sha1" | cut -d' ' -f1)
[ -n "$SUM" ] && [ "$(sha1sum $WORK/wp.tar.gz | cut -d' ' -f1)" = "$SUM" ] || { echo "Checksum mismatch for WordPress $VER" >&2; exit 1; }
fetch https://downloads.wordpress.org/plugin/sqlite-database-integration.latest-stable.zip $WORK/sqlite.zip

step "Unpacking WordPress $VER"
tar -xzf $WORK/wp.tar.gz -C $WORK
unzip -q $WORK/sqlite.zip -d $WORK
mkdir -p "$WEB"
# Upgrade: new core, same site (themes, plugins, uploads, database). The site is parked inside
# $APP_DIR, never in /tmp, so an install killed halfway (heat, Android) loses nothing: the next
# run finds it there and puts it back.
KEEP=$APP_DIR/.keep-content
[ -d "$WEB/wp-content" ] && [ ! -d "$KEEP" ] && mv "$WEB/wp-content" "$KEEP"
rm -rf "$WEB" && mv $WORK/wordpress "$WEB"
if [ -d "$KEEP" ]; then
    rm -rf "$WEB/wp-content" && mv "$KEEP" "$WEB/wp-content"
fi
# SQLite instead of MySQL: no database server has to run next to WordPress.
rm -rf "$WEB/wp-content/plugins/sqlite-database-integration"
mv $WORK/sqlite-database-integration "$WEB/wp-content/plugins/"
sed -e "s#{SQLITE_IMPLEMENTATION_FOLDER_PATH}#$WEB/wp-content/plugins/sqlite-database-integration#" \
    -e "s#{SQLITE_PLUGIN}#sqlite-database-integration/load.php#" \
    "$WEB/wp-content/plugins/sqlite-database-integration/db.copy" > "$WEB/wp-content/db.php"
mkdir -p "$WEB/wp-content/database" "$WEB/wp-content/uploads" "$WEB/wp-content/mu-plugins"
rm -rf $WORK
rm -rf "$APP_DIR/tmp" && mkdir -p "$APP_DIR"/{tmp,php.d,tls}

step "Applying battery-optimized settings"
cat > "$APP_DIR/php.d/zz-wordpress.ini" <<'EOF'
memory_limit=256M
upload_max_filesize=64M
post_max_size=64M
max_execution_time=120
expose_php=off
opcache.enable=1
opcache.enable_cli=0
opcache.memory_consumption=48
opcache.interned_strings_buffer=8
opcache.max_accelerated_files=8000
opcache.validate_timestamps=1
opcache.revalidate_freq=10
opcache.jit=off
EOF

# PHP workers start on demand and exit after 10 s idle: between requests nothing runs.
cat > "$APP_DIR/php-fpm.conf" <<EOF
[global]
pid = $APP_DIR/tmp/php-fpm.pid
error_log = /proc/self/fd/2
log_level = warning
daemonize = no
process.max = 3

[wordpress]
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
env[PATH] = /usr/local/bin:/usr/bin:/bin
php_admin_value[error_log] = /proc/self/fd/2
php_admin_flag[log_errors] = on
EOF

# Secret keys are made once and kept across upgrades (changing them logs everyone out).
if [ ! -f "$APP_DIR/salts.php" ]; then
    {
        echo '<?php'
        for k in AUTH_KEY SECURE_AUTH_KEY LOGGED_IN_KEY NONCE_KEY AUTH_SALT SECURE_AUTH_SALT LOGGED_IN_SALT NONCE_SALT; do
            echo "define('$k', '$(gen_password 64)');"
        done
    } > "$APP_DIR/salts.php"
    chmod 600 "$APP_DIR/salts.php"
fi

# The site answers on whichever local address it was opened with (this device, the Wi-Fi IP,
# pocketserver.local), so links never point at an old IP. Only local names are accepted.
cat > "$WEB/wp-config.php" <<EOF
<?php
define('DB_NAME', 'wordpress');
define('DB_USER', '');
define('DB_PASSWORD', '');
define('DB_HOST', '');
define('DB_CHARSET', 'utf8mb4');
define('DB_COLLATE', '');
define('DB_DIR', '$WEB/wp-content/database/');
define('DB_FILE', '.ht.sqlite');
\$table_prefix = 'wp_';
require '$APP_DIR/salts.php';

\$ps_host = \$_SERVER['HTTP_HOST'] ?? '';
// Same ranges as the local CA allows (incl. 100.64.0.0/10).
\$ps_local = '/^(localhost|127\\.0\\.0\\.1|pocketserver\\.local|10(\\.\\d{1,3}){3}|192\\.168(\\.\\d{1,3}){2}|172\\.(1[6-9]|2\\d|3[01])(\\.\\d{1,3}){2}|100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])(\\.\\d{1,3}){2})(:\\d{1,5})?\$/';
if (\$ps_host !== '' && preg_match(\$ps_local, \$ps_host)) {
    \$ps_https = !empty(\$_SERVER['HTTPS']) && \$_SERVER['HTTPS'] !== 'off';
    define('WP_HOME', (\$ps_https ? 'https://' : 'http://') . \$ps_host);
} else {
    define('WP_HOME', 'http://127.0.0.1:8089');
}
define('WP_SITEURL', WP_HOME);

// No self-updates (update from Dashboard > Updates when you choose), no file editor in the admin.
define('AUTOMATIC_UPDATER_DISABLED', true);
define('WP_AUTO_UPDATE_CORE', false);
define('DISALLOW_FILE_EDIT', true);
// Small database and memory footprint.
define('WP_POST_REVISIONS', 5);
define('EMPTY_TRASH_DAYS', 30);
define('WP_MEMORY_LIMIT', '128M');
define('WP_MAX_MEMORY_LIMIT', '256M');
define('WP_DEBUG', false);
// Scheduled tasks run briefly after page visits, never from a timer waking the device.
define('WP_CRON_LOCK_TIMEOUT', 600);
define('FS_METHOD', 'direct');

if (!defined('ABSPATH')) define('ABSPATH', __DIR__ . '/');
require_once ABSPATH . 'wp-settings.php';
EOF

# Must-use plugin: removes the background "phone home" checks and heavy extras. Updates and the
# plugin/theme directory still work when you open them yourself.
cat > "$WEB/wp-content/mu-plugins/pocketserver.php" <<'EOF'
<?php
/* Plugin Name: PocketServer battery settings */
// No timed update checks (they send site details to wordpress.org).
remove_action('init', 'wp_schedule_update_checks');
// The weekly Site Health run also calls wordpress.org; Tools > Site Health still works on demand.
foreach (['wp_version_check', 'wp_update_plugins', 'wp_update_themes', 'wp_maybe_auto_update', 'wp_site_health_scheduled_check'] as $h) {
    add_action('init', function () use ($h) { wp_clear_scheduled_hook($h); });
}
// Nor on every admin page load (Dashboard > Updates still checks when you open it).
foreach (['_maybe_update_core', '_maybe_update_plugins', '_maybe_update_themes'] as $h) {
    remove_action('admin_init', $h);
}
// Refused at the source too, whichever code tries to schedule them again.
add_filter('schedule_event', function ($event) {
    $blocked = ['wp_version_check', 'wp_update_plugins', 'wp_update_themes', 'wp_maybe_auto_update', 'wp_site_health_scheduled_check'];
    return ($event && in_array($event->hook, $blocked, true)) ? false : $event;
});
add_filter('automatic_updater_disabled', '__return_true');
add_filter('auto_update_plugin', '__return_false');
add_filter('auto_update_theme', '__return_false');
// No news feeds, browser or PHP version lookups on the dashboard.
add_action('wp_dashboard_setup', function () {
    remove_meta_box('dashboard_primary', 'dashboard', 'side');
    remove_meta_box('dashboard_php_nag', 'dashboard', 'normal');
}, 20);
add_filter('pre_site_transient_browser_' . md5($_SERVER['HTTP_USER_AGENT'] ?? ''), function () {
    return ['name' => '', 'version' => '', 'current_version' => '', 'upgrade' => false, 'insecure' => false,
        'update_url' => '', 'img_src' => '', 'img_src_ssl' => ''];
});
add_filter('pre_site_transient_php_check_' . md5(PHP_VERSION), function () {
    return ['recommended_version' => PHP_VERSION, 'is_supported' => true, 'is_secure' => true, 'is_acceptable' => true];
});
// No pings to other blogs, no emoji script from a CDN.
add_filter('pre_option_default_pingback_flag', '__return_zero');
remove_action('wp_head', 'print_emoji_detection_script', 7);
remove_action('admin_print_scripts', 'print_emoji_detection_script');
remove_action('wp_print_styles', 'print_emoji_styles');
remove_action('admin_print_styles', 'print_emoji_styles');
// Smaller images on upload: less work for the CPU and less storage.
add_filter('big_image_size_threshold', function () { return 2048; });
// Lower heartbeat (the admin pings the server every 15 s by default).
add_filter('heartbeat_settings', function ($s) { $s['interval'] = 60; return $s; });
// Sign-ups stay closed (no mail on this device anyway).
add_filter('pre_option_users_can_register', '__return_zero');

// The editor saves absolute URLs (an image added on this device is stored as
// http://127.0.0.1:8089/wp-content/...). Rewrite any of this site's local addresses to the one
// the visitor is using, so pages work from every device and after the Wi-Fi IP changes.
function pocketserver_local_urls($text) {
    if (!is_string($text) || $text === '' || !defined('WP_HOME')) return $text;
    static $re = '#https?://(?:localhost|127\.0\.0\.1|pocketserver\.local|10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}|100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])(?:\.\d{1,3}){2}):(?:8448|8089)(?=/|"|\'|\s|$)#';
    return preg_replace($re, WP_HOME, $text);
}
foreach (['the_content', 'render_block', 'wp_get_attachment_url', 'widget_text', 'widget_block_content', 'the_excerpt'] as $f) {
    add_filter($f, 'pocketserver_local_urls', 99);
}
add_filter('wp_calculate_image_srcset', function ($sources) {
    foreach ((array) $sources as $k => $s) $sources[$k]['url'] = pocketserver_local_urls($s['url']);
    return $sources;
}, 99);
EOF

cat > "$APP_DIR/headers.conf" <<'EOF'
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header X-Robots-Tag "noindex, nofollow" always;
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
    gzip off;
    client_max_body_size 64m;
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

    server {
        listen 8448 ssl;
        listen 127.0.0.1:8089;
        server_name _;
        root $WEB;
        index index.php;
        include $APP_DIR/headers.conf;

        location / { try_files \$uri \$uri/ /index.php?\$args; }
        # Never served: the database, secrets, config and dot files.
        location ^~ /wp-content/database/ { deny all; }
        location ~ /\.(?!well-known) { deny all; }
        location = /wp-config.php { deny all; }
        location = /xmlrpc.php { deny all; }
        # Uploaded files are never run as PHP.
        location ~* ^/wp-content/uploads/.*\.php\$ { deny all; }
        location ~ \.php\$ {
            try_files \$fastcgi_script_name =404;
            include /etc/nginx/fastcgi_params;
            fastcgi_param SCRIPT_FILENAME \$document_root\$fastcgi_script_name;
            fastcgi_param HTTPS \$https if_not_empty;
            fastcgi_pass unix:$SOCK;
            fastcgi_read_timeout 300s;
        }
        location ~* \.(?:css|js|mjs|svg|gif|ico|jpe?g|png|webp|avif|woff2?|ttf)\$ {
            expires 7d;
            include $APP_DIR/headers.conf;
        }
    }
}
EOF
issue_server_cert "$APP_DIR/tls"
nginx -t -c "$APP_DIR/nginx.conf" -e stderr
php-fpm8.3 -t -R -y "$APP_DIR/php-fpm.conf"

reset_credentials
if [ -f "$WEB/wp-content/database/.ht.sqlite" ] && wp core is-installed 2>/dev/null; then
    step "Upgrading the WordPress database"
    wp core update-db >/dev/null || true
else
    step "Setting up WordPress"
    PASS=$(gen_password 20)
    # Saved first: if setup is interrupted after creating the account, the password is not lost.
    echo "$PASS" > "$APP_DIR/.admin-password" && chmod 600 "$APP_DIR/.admin-password"
    wp core install --url=http://127.0.0.1:8089 --title="My PocketServer Site" \
        --admin_user=admin --admin_password="$PASS" --admin_email=admin@pocketserver.local --skip-email >/dev/null
    wp plugin activate sqlite-database-integration >/dev/null
    # Pretty links (/my-post/) instead of ?p=123.
    wp rewrite structure '/%postname%/' >/dev/null
    # Starter content that only ships for demos.
    wp plugin delete akismet hello >/dev/null 2>&1 || true
    wp option update blog_public 0 >/dev/null
    wp option update default_ping_status closed >/dev/null
    wp option update default_comment_status closed >/dev/null
fi

set_credential "Admin user" admin
[ -f "$APP_DIR/.admin-password" ] && set_credential "Admin password" "$(cat "$APP_DIR/.admin-password")"
set_credential "Dashboard" "/wp-admin/"
set_credential "This device" "Tap Open (no certificate needed)"
set_credential "Other devices" "Install /shared/PocketServer-CA.crt once, then use the https address"
set_credential "Site files" "$WEB/wp-content (themes, plugins, uploads)"
