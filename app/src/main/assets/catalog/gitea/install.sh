#!/bin/bash
source /opt/pocketserver/lib.sh
FALLBACK_VER=28.2.0
BIN=/opt/gitea/gitea
INI=$APP_DIR/app.ini

apt_install ca-certificates curl git xz-utils jq

# Newest stable release (official static arm64 binary), checked against the published sha256.
VER=$(curl -fsSL https://dl.gitea.com/gitea/version.json | jq -r '.latest.version' 2>/dev/null || true)
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || VER=$FALLBACK_VER
URL=https://dl.gitea.com/gitea/$VER/gitea-$VER-linux-arm64.xz
WORK=/tmp/gitea-dl && rm -rf $WORK && mkdir -p $WORK
fetch "$URL" $WORK/gitea.xz
step "Verifying checksum"
SUM=$(curl -fsSL "$URL.sha256" | cut -d' ' -f1)
[ -n "$SUM" ] && [ "$(sha256sum $WORK/gitea.xz | cut -d' ' -f1)" = "$SUM" ] || { echo "Checksum mismatch for gitea $VER" >&2; exit 1; }
xz -d $WORK/gitea.xz
rm -rf /opt/gitea && mkdir -p /opt/gitea
mv $WORK/gitea $BIN && chmod 755 $BIN
rm -rf $WORK

step "Applying battery-optimized settings"
mkdir -p "$APP_DIR"/{data,repos,log,custom}
# Secrets survive reinstalls (they encrypt 2FA secrets and sign sessions).
secret() { [ -s "$APP_DIR/.$1" ] || $BIN generate secret "$2" </dev/null > "$APP_DIR/.$1"; chmod 600 "$APP_DIR/.$1"; cat "$APP_DIR/.$1"; }
SECRET_KEY=$(secret secret_key SECRET_KEY)
INTERNAL_TOKEN=$(secret internal_token INTERNAL_TOKEN)
JWT_SECRET=$(secret jwt_secret JWT_SECRET)
LFS_JWT_SECRET=$(secret lfs_jwt_secret JWT_SECRET)
IP=$(set -- ${PS_LAN_IPS:-127.0.0.1}; echo "$1")

# PocketServer's Ubuntu only has (fake) root, so Gitea's documented root override is set.
# Everything that wakes the CPU on a timer is off or stretched to days.
cat > "$INI" <<EOF
APP_NAME = Gitea on PocketServer
RUN_USER = root
RUN_MODE = prod
WORK_PATH = $APP_DIR
I_AM_BEING_UNSAFE_RUNNING_AS_ROOT = true

[server]
PROTOCOL = http
HTTP_ADDR = 0.0.0.0
HTTP_PORT = 3300
DOMAIN = $IP
ROOT_URL = http://$IP:3300/
APP_DATA_PATH = $APP_DIR/data
DISABLE_SSH = false
START_SSH_SERVER = true
SSH_DOMAIN = $IP
SSH_PORT = 2222
SSH_LISTEN_HOST = 0.0.0.0
SSH_LISTEN_PORT = 2222
BUILTIN_SSH_SERVER_USER = git
LFS_START_SERVER = true
LFS_JWT_SECRET = $LFS_JWT_SECRET
OFFLINE_MODE = true
LANDING_PAGE = explore

[database]
DB_TYPE = sqlite3
PATH = $APP_DIR/data/gitea.db
SQLITE_JOURNAL_MODE = WAL
MAX_OPEN_CONNS = 4

[repository]
ROOT = $APP_DIR/repos
DEFAULT_PRIVATE = private

[lfs]
PATH = $APP_DIR/data/lfs

[security]
INSTALL_LOCK = true
SECRET_KEY = $SECRET_KEY
INTERNAL_TOKEN = $INTERNAL_TOKEN

[oauth2]
JWT_SECRET = $JWT_SECRET

[service]
DISABLE_REGISTRATION = true
REQUIRE_SIGNIN_VIEW = true
ENABLE_NOTIFY_MAIL = false
REGISTER_EMAIL_CONFIRM = false

[mailer]
ENABLED = false

[indexer]
REPO_INDEXER_ENABLED = false
ISSUE_INDEXER_TYPE = db

[actions]
ENABLED = false

[api]
ENABLE_SWAGGER = false

[session]
PROVIDER = file

[cache]
ADAPTER = memory
INTERVAL = 600

[log]
MODE = console
LEVEL = Warn
ROOT_PATH = $APP_DIR/log

[cron]
ENABLED = true
RUN_AT_START = false

[cron.update_checker]
ENABLED = false

[cron.git_gc_repos]
ENABLED = true
RUN_AT_START = false
SCHEDULE = @every 720h

[cron.repo_health_check]
SCHEDULE = @every 168h

[cron.check_repo_stats]
RUN_AT_START = false
SCHEDULE = @every 168h

[cron.archive_cleanup]
SCHEDULE = @every 168h

[cron.update_mirrors]
SCHEDULE = @every 24h

[cron.sync_external_users]
ENABLED = false

[cron.deleted_branches_cleanup]
SCHEDULE = @every 168h

[mirror]
MIN_INTERVAL = 24h
DEFAULT_INTERVAL = 24h

[other]
SHOW_FOOTER_VERSION = false
EOF
chmod 600 "$INI"

export GITEA_WORK_DIR=$APP_DIR
step "Creating the database"
$BIN --config "$INI" migrate >/dev/null 2>&1 </dev/null

reset_credentials
if ! $BIN --config "$INI" admin user list --admin 2>/dev/null </dev/null | awk 'NR>1{print $2}' | grep -qx admin; then
    step "Creating the administrator account"
    PASS=$(gen_password 16)
    $BIN --config "$INI" admin user create --username admin --password "$PASS" \
        --email admin@pocketserver.local --admin --must-change-password=false >/dev/null </dev/null
    echo "$PASS" > "$APP_DIR/.admin-password" && chmod 600 "$APP_DIR/.admin-password"
fi
set_credential "Admin user" admin
[ -f "$APP_DIR/.admin-password" ] && set_credential "Admin password" "$(cat "$APP_DIR/.admin-password")"
set_credential "SSH clone" "ssh://git@<this device>:2222/admin/<repo>.git"
