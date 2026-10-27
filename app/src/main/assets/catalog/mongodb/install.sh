#!/bin/bash
source /opt/pocketserver/lib.sh

apt_install ca-certificates curl gnupg
# MongoDB 7.0 (jammy build, runs on noble). 8.x cannot run on Android: its allocator needs a
# 48-bit address space and phones give apps 39 bits.
step "Adding MongoDB repository"
mkdir -p /etc/apt/keyrings
curl -fsSL https://pgp.mongodb.com/server-7.0.asc | gpg --dearmor --yes -o /etc/apt/keyrings/mongodb-7.0.gpg
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/mongodb-7.0.gpg] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" \
    > /etc/apt/sources.list.d/mongodb-org-7.0.list
apt_invalidate
apt_install mongodb-org-server mongodb-mongosh
mongod --version | head -1

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/data"
mkdir -p "$APP_DIR/data"
# Localhost only, 256 MB cache, no diagnostic data collection (FTDC samples every second),
# small journal files, quiet log to stdout (PocketServer's app log), no unix socket.
cat > "$APP_DIR/mongod.conf" <<EOF
storage:
  dbPath: $APP_DIR/data
  wiredTiger:
    engineConfig:
      cacheSizeGB: 0.25
      # Journal files of 20 MB written as needed, instead of three pre-filled 100 MB files.
      configString: "log=(file_max=20MB,prealloc=false)"
net:
  bindIp: 127.0.0.1
  port: 27017
  unixDomainSocket:
    enabled: false
systemLog:
  quiet: true
  verbosity: 0
setParameter:
  diagnosticDataCollectionEnabled: false
  ttlMonitorSleepSecs: 300
security:
  authorization: enabled
EOF
mongosh --nodb --quiet --eval 'disableTelemetry()' >/dev/null 2>&1 || true

# Always require a login, so switching on Wi-Fi access never exposes an open database.
# The first user can be created without auth from localhost (MongoDB's localhost exception).
step "Creating database user"
mongod --config "$APP_DIR/mongod.conf" >/tmp/mongod-setup.log 2>&1 &
DB=$!
trap 'stop_pid $DB' EXIT
wait_port 27017 120 || { tail -20 /tmp/mongod-setup.log >&2; exit 1; }
DEV_PASS=$(gen_password 16)
ADMIN_PASS=$(gen_password 20)
# dev (the Wi-Fi login) may read, write and manage every normal database but not admin/config/local;
# the root-role admin is restricted to logins from this device.
mongosh --quiet mongodb://127.0.0.1:27017/admin --eval "
    db.createUser({user: 'admin', pwd: '$ADMIN_PASS', roles: ['root'],
                   authenticationRestrictions: [{clientSource: ['127.0.0.1']}]});
    db.createUser({user: 'dev', pwd: '$DEV_PASS',
                   roles: ['readWriteAnyDatabase', 'dbAdminAnyDatabase', 'clusterMonitor']});" >/dev/null
stop_pid $DB
trap - EXIT

reset_credentials
set_credential User dev
set_credential Password "$DEV_PASS"
set_credential "Connection string" "mongodb://dev:$DEV_PASS@127.0.0.1:27017/?authSource=admin"
set_credential "Admin password" "$ADMIN_PASS (user admin, this device only)"
set_credential "Other devices" "Switch on Wi-Fi access, then use this device's IP instead of 127.0.0.1"
