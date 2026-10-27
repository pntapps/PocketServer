#!/bin/bash
source /opt/pocketserver/lib.sh
VER=0.6.27
VENV=/opt/calibreweb
LIB=/shared/Books
# Empty-library schema from Calibre itself (pinned + checksummed); Calibre is not installed.
SCHEMA_URL=https://raw.githubusercontent.com/kovidgoyal/calibre/v9.17.0/resources/metadata_sqlite.sql
SCHEMA_SHA=3503d771a142fcfb2c4461b4346ee0b53b95b117209240d40f264c1c1218225f

# libmagic: file type checks on upload. No ImageMagick/Calibre converters (CPU heavy).
apt_install ca-certificates curl python3-venv sqlite3 libmagic1

step "Installing Calibre-Web $VER"
rm -rf "$VENV"
python3 -m venv "$VENV"
"$VENV/bin/pip" install -q --disable-pip-version-check --no-cache-dir "calibreweb==$VER"
# No self-updater: it would overwrite the pip install (updates come from PocketServer).
sed -i 's/^UPDATER_AVAILABLE = True/UPDATER_AVAILABLE = False/' "$VENV"/lib/python3*/site-packages/calibreweb/cps/constants.py
# ImageMagick is not installed (cover conversion is optional). Without it, every `import wand` probes
# dozens of library names through ldconfig/ld subprocesses: ~90 s of CPU at each start under proot.
sed -i 's/ctypes\.util\.find_library(/(lambda _name: None)(/' "$VENV"/lib/python3*/site-packages/wand/api.py

mkdir -p "$LIB"
if [ ! -f "$LIB/metadata.db" ]; then
    step "Creating an empty book library"
    fetch "$SCHEMA_URL" /tmp/calibre-schema.sql
    echo "$SCHEMA_SHA  /tmp/calibre-schema.sql" | sha256sum -c - >/dev/null
    sqlite3 "$LIB/metadata.db" < /tmp/calibre-schema.sql
    sqlite3 "$LIB/metadata.db" "INSERT INTO library_id(uuid) VALUES('$(cat /proc/sys/kernel/random/uuid)');"
    rm -f /tmp/calibre-schema.sql
fi

step "Applying battery-optimized settings"
rm -f "$APP_DIR"/app.db "$APP_DIR"/gdrive.db "$APP_DIR"/*.log* "$APP_DIR"/.key
# Policy needs upper, lower, digit and a symbol.
PASS=""
until [[ $PASS =~ [0-9] && $PASS =~ [a-z] && $PASS =~ [A-Z] ]]; do PASS="$(gen_password 8)-$(gen_password 8)"; done
# Creates app.db (with the stock admin/admin123), then replaces that password and exits.
CALIBRE_DBPATH="$APP_DIR" "$VENV/bin/cps" -p "$APP_DIR/app.db" -s "admin:$PASS" >/dev/null
# Library path, uploads on, warnings-only log. Thumbnail generation, reconnect and metadata backup
# schedules stay off (their defaults); Kobo sync, LDAP and OAuth stay off.
sqlite3 "$APP_DIR/app.db" "UPDATE settings SET config_calibre_dir='$LIB', config_uploading=1, config_log_level=30, config_port=8083;"

reset_credentials
set_credential Username admin
set_credential Password "$PASS"
set_credential "Book library" "$LIB"
