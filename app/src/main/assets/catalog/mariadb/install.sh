#!/bin/bash
source /opt/pocketserver/lib.sh
CNF=$APP_DIR/my.cnf
SOCK=/run/mysqld/mysqld.sock

apt_install mariadb-server mariadb-client

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/data" "$APP_DIR/tmp"
mkdir -p "$APP_DIR/data" "$APP_DIR/tmp" /run/mysqld
# Small caches, no binary log, no performance schema; commits are flushed to disk once a second
# instead of on every write (fine for development, far fewer flash writes and wake-ups).
cat > "$CNF" <<EOF
[client-server]
socket = $SOCK
port = 3306

[mariadbd]
user = root
datadir = $APP_DIR/data
tmpdir = $APP_DIR/tmp
pid-file = $APP_DIR/tmp/mariadbd.pid
bind-address = 127.0.0.1
skip-name-resolve
skip-log-bin
performance_schema = OFF
innodb_buffer_pool_size = 64M
innodb_log_file_size = 32M
innodb_log_buffer_size = 8M
innodb_use_native_aio = 0
innodb_flush_log_at_trx_commit = 2
innodb_io_capacity = 100
innodb_read_io_threads = 1
innodb_write_io_threads = 1
innodb_purge_threads = 1
key_buffer_size = 8M
aria_pagecache_buffer_size = 8M
max_connections = 30
thread_cache_size = 4
table_open_cache = 200
table_definition_cache = 400
tmp_table_size = 16M
max_heap_table_size = 16M
event_scheduler = OFF
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
log_warnings = 1
EOF

step "Creating databases"
mariadb-install-db --defaults-file="$CNF" --user=root --auth-root-authentication-method=normal \
    --skip-test-db >/dev/null

step "Starting MariaDB for first-time setup"
mariadbd --defaults-file="$CNF" >/tmp/mariadb-setup.log 2>&1 &
DB=$!
trap 'stop_pid $DB' EXIT
wait_port 3306 120 || { cat /tmp/mariadb-setup.log >&2; exit 1; }

ROOT_PASS=$(gen_password 20)
DEV_PASS=$(gen_password 16)
HOST=$(hostname); [ "$HOST" = localhost ] && HOST=localhost.invalid
mariadb --defaults-file="$CNF" -uroot <<EOF
DROP USER IF EXISTS ''@'localhost', ''@'$HOST', 'root'@'$HOST', 'root'@'::1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$ROOT_PASS';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1';
ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY '$ROOT_PASS';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
CREATE USER 'dev'@'localhost' IDENTIFIED BY '$DEV_PASS';
CREATE USER 'dev'@'127.0.0.1' IDENTIFIED BY '$DEV_PASS';
-- Other devices on the Wi-Fi (only listened for when Wi-Fi access is switched on). root stays local.
CREATE USER 'dev'@'%' IDENTIFIED BY '$DEV_PASS';
-- dev owns the dev database, any dev_* database it creates, and test_* databases (Django/Laravel
-- tests create them), but not the system databases: that stays with root, from this device only.
GRANT ALL PRIVILEGES ON \`dev\`.* TO 'dev'@'localhost', 'dev'@'127.0.0.1', 'dev'@'%';
GRANT ALL PRIVILEGES ON \`dev\_%\`.* TO 'dev'@'localhost', 'dev'@'127.0.0.1', 'dev'@'%';
GRANT ALL PRIVILEGES ON \`test\_%\`.* TO 'dev'@'localhost', 'dev'@'127.0.0.1', 'dev'@'%';
CREATE DATABASE dev;
FLUSH PRIVILEGES;
EOF
step "Stopping setup instance"
stop_pid $DB
trap - EXIT

reset_credentials
set_credential Host 127.0.0.1:3306
set_credential User dev
set_credential Password "$DEV_PASS"
set_credential Database dev
set_credential "Root password" "$ROOT_PASS"
set_credential "Other devices" "Switch on Wi-Fi access, then use this device's IP as host (user dev)"
