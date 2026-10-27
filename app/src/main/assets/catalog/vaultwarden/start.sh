#!/bin/bash
APP_DIR=/opt/pocketserver/apps/vaultwarden
TLS=$APP_DIR/tls

source /opt/pocketserver/lib.sh
issue_server_cert "$TLS"

# Sign-ups are never open. The first account is invite-only: the owner enters their email on the
# PocketServer card (saved in owner-email), and while no account exists this start invites exactly
# that address through the local admin API. Nobody else on the Wi-Fi can register, not even for a
# moment. No mail server is needed: an invited address registers straight from the web vault.
# The card reads first-account: needs-email | invited:<email> | done.
DB=$APP_DIR/data/db.sqlite3
users() { sqlite3 "$DB" "select count(*) from users where password_hash != x''" 2>/dev/null || echo 0; }
OWNER=$(head -1 "$APP_DIR/owner-email" 2>/dev/null | tr -d '[:space:]' || true)
rm -f "$APP_DIR/data/config.json"  # older installs closed sign-ups there; nothing to override now
EXTRA_ENV=()
if [ "$(users)" != 0 ]; then
    echo done > "$APP_DIR/first-account"
elif [ -z "$OWNER" ]; then
    echo needs-email > "$APP_DIR/first-account"
else
    echo "invited:$OWNER" > "$APP_DIR/first-account"
    # Admin page only in this state, with a random token that never leaves this process.
    ADMIN_TOKEN=$(gen_password 48)
    EXTRA_ENV=(ADMIN_TOKEN="$ADMIN_TOKEN")
    (
        J=$(mktemp)
        wait_port 8443 120 || exit 0
        curl -sk -c "$J" -o /dev/null --data-urlencode "token=$ADMIN_TOKEN" https://127.0.0.1:8443/admin
        curl -sk -b "$J" -o /dev/null -H 'Content-Type: application/json' \
            -d "$(jq -nc --arg e "$OWNER" '{email: $e}')" https://127.0.0.1:8443/admin/invite
        rm -f "$J"
        # Event-driven (no timer): marks the card done once the owner has created the account.
        while [ "$(users)" = 0 ]; do
            if command -v inotifywait >/dev/null; then
                inotifywait -qq -t 600 -e modify,create,close_write "$APP_DIR/data" || true
            else
                sleep 5
            fi
        done
        echo done > "$APP_DIR/first-account"
    ) &
fi
# Links, passkeys and WebAuthn need the address clients actually use.
FIRST_IP=$(echo "${PS_LAN_IPS:-}" | awk '{print $1}')
DOMAIN_URL="https://${FIRST_IP:-pocketserver.local}:8443"

export DATA_FOLDER=$APP_DIR/data WEB_VAULT_FOLDER=/opt/vaultwarden/web-vault \
    ROCKET_ADDRESS=0.0.0.0 ROCKET_PORT=8443 ROCKET_WORKERS=2 \
    ROCKET_TLS="{certs=\"$TLS/server.crt\",key=\"$TLS/server.key\"}" \
    SIGNUPS_ALLOWED=false INVITATIONS_ALLOWED=false SHOW_PASSWORD_HINT=false \
    DISABLE_ICON_DOWNLOAD=true ICON_SERVICE=internal \
    JOB_POLL_INTERVAL_MS=600000 DATABASE_MAX_CONNS=2 LOG_LEVEL=warn DOMAIN="$DOMAIN_URL"
cd /opt/vaultwarden && exec env "${EXTRA_ENV[@]}" ./vaultwarden
