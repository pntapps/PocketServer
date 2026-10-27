#!/bin/bash
source /opt/pocketserver/lib.sh
FALLBACK_VER=0.31.0
PORT=5230

apt_install ca-certificates curl jq

# Newest stable release (one static Go binary with the web UI built in).
VER=$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/usememos/memos/releases/latest | sed -n 's#.*/tag/v##p')
VER=${VER:-$FALLBACK_VER}
REL=https://github.com/usememos/memos/releases/download/v$VER
TGZ=memos_${VER}_linux_arm64.tar.gz
WORK=/tmp/memos-dl && rm -rf $WORK && mkdir -p $WORK
fetch "$REL/$TGZ" "$WORK/$TGZ"
curl -fsSL "$REL/checksums.txt" -o "$WORK/checksums.txt"
step "Verifying checksum"
(cd $WORK && grep " $TGZ\$" checksums.txt | sha256sum -c --status) || { echo "Checksum mismatch for $TGZ" >&2; exit 1; }
tar -xzf "$WORK/$TGZ" -C $WORK memos
rm -rf /opt/memos && mkdir -p /opt/memos
mv $WORK/memos /opt/memos/memos && chmod 755 /opt/memos/memos
rm -rf $WORK

mkdir -p "$APP_DIR/data"
reset_credentials

# First account becomes the administrator. Create it now (memos only listening on localhost),
# then close public sign-ups so nobody else on the Wi-Fi can register.
# Keyed on the saved password, not the database: an install interrupted after the database was
# created but before sign-ups were closed must redo this step, or the first visitor becomes admin.
if [ ! -s "$APP_DIR/admin-password" ]; then
    step "Creating the administrator account"
    rm -rf "$APP_DIR/data" && mkdir -p "$APP_DIR/data"
    PASS=$(gen_password 16)
    /opt/memos/memos --addr 127.0.0.1 --port $PORT --data "$APP_DIR/data" --log-level warn &
    MP=$!
    trap 'stop_pid $MP' EXIT
    wait_port $PORT 90
    API=http://127.0.0.1:$PORT/api/v1
    curl -fsS -o /dev/null -H 'Content-Type: application/json' \
        -d "$(jq -n --arg p "$PASS" '{username:"admin", password:$p, role:"ADMIN"}')" "$API/users"
    TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
        -d "$(jq -n --arg p "$PASS" '{passwordCredentials:{username:"admin", password:$p}}')" \
        "$API/auth/signin" | jq -r .accessToken)
    [ -n "$TOKEN" ] && [ "$TOKEN" != null ] || { echo "Could not sign in as admin" >&2; exit 1; }
    curl -fsS -o /dev/null -X PATCH -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d '{"name":"instance/settings/GENERAL","generalSetting":{"disallowUserRegistration":true}}' \
        "$API/instance/settings/GENERAL?updateMask=disallow_user_registration"
    stop_pid $MP
    trap - EXIT
    echo "$PASS" > "$APP_DIR/admin-password" && chmod 600 "$APP_DIR/admin-password"
fi

set_credential "Admin user" admin
[ -f "$APP_DIR/admin-password" ] && set_credential "Admin password" "$(cat "$APP_DIR/admin-password")"
set_credential "Sign-ups" "Closed: add people under Settings > Members"
