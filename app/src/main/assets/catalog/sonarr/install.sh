#!/bin/bash
source /opt/pocketserver/lib.sh
NAME=Sonarr PORT=8989 ROOT=/shared/Shows
OPT=/opt/sonarr
URL=http://127.0.0.1:$PORT
API=$URL/api/v3

apt_install ca-certificates curl jq libicu74 sqlite3
step "Downloading $NAME"
curl -fL --retry 3 --retry-delay 2 -sS -o /tmp/sonarr.tgz \
    "https://services.sonarr.tv/v1/download/main/latest?version=4&os=linux&arch=arm64"
step "Unpacking $NAME"
rm -rf "$OPT"
mkdir -p "$OPT/bin"
tar -xzf /tmp/sonarr.tgz -C "$OPT/bin" --strip-components=1
rm -rf /tmp/sonarr.tgz "$OPT/bin/Sonarr.Update"
# Updates come with PocketServer, never from the app itself (its updater is removed above).
printf 'PackageAuthor=PocketServer\nUpdateMethod=External\nUpdateMethodMessage=Updates are installed by PocketServer.\n' > "$OPT/package_info"

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/data"
mkdir -p "$APP_DIR/data" "$ROOT" /shared/Downloads
APIKEY=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
cat > "$APP_DIR/data/config.xml" <<EOF
<Config>
  <BindAddress>*</BindAddress>
  <Port>$PORT</Port>
  <SslPort>9898</SslPort>
  <EnableSsl>False</EnableSsl>
  <LaunchBrowser>False</LaunchBrowser>
  <ApiKey>$APIKEY</ApiKey>
  <AuthenticationMethod>Forms</AuthenticationMethod>
  <AuthenticationRequired>Enabled</AuthenticationRequired>
  <Branch>main</Branch>
  <LogLevel>info</LogLevel>
  <LogSizeLimit>1</LogSizeLimit>
  <LogDbEnabled>False</LogDbEnabled>
  <UrlBase></UrlBase>
  <InstanceName>$NAME</InstanceName>
  <UpdateMechanism>External</UpdateMechanism>
  <UpdateAutomatically>False</UpdateAutomatically>
  <AnalyticsEnabled>False</AnalyticsEnabled>
</Config>
EOF
chmod 600 "$APP_DIR/data/config.xml"

step "Starting $NAME for first-time setup"
bash /opt/pocketserver/recipes/sonarr/start.sh >/tmp/sonarr-setup.log 2>&1 &
TEMP_PIDS=($!)
trap 'for p in "${TEMP_PIDS[@]}"; do stop_pid "$p"; done' EXIT
for _ in $(seq 300); do curl -fs "$URL/ping" >/dev/null && break; sleep 2; done
api() { curl -fsS -H "X-Api-Key: $APIKEY" -H 'Content-Type: application/json' "$@"; }
# put <path> <jq args...>: GET a settings resource, change it with jq, PUT it back.
put() { api "$API/$1" | jq -c "${@:2}" | api -X PUT "$API/$1" --data-binary @- >/dev/null; }

step "Creating admin account"
PASS=$(gen_password 16)
put config/host --arg p "$PASS" '.authenticationMethod = "forms" | .authenticationRequired = "enabled"
    | .username = "admin" | .password = $p | .passwordConfirmation = $p
    | .analyticsEnabled = false | .updateAutomatically = false | .updateMechanism = "external"
    | .logLevel = "info" | .launchBrowser = false'
# RSS sync every 2 h (the maximum) instead of 15 min; no full disk rescans after each refresh;
# imports copy instead of hard-linking (proot only emulates hard links).
put config/indexer '.rssSyncInterval = 120'
# Finished-download checks poll qBittorrent every minute by default: every 15 min is plenty here.
put config/downloadclient 'if has("checkForFinishedDownloadInterval") then .checkForFinishedDownloadInterval = 15 else . end'
put config/mediamanagement '.copyUsingHardlinks = false | .rescanAfterRefresh = "afterManual"'
api -X POST "$API/rootfolder" -d "{\"path\":\"$ROOT\"}" >/dev/null

# --- Downloads Pack wiring with members that are already installed -----------------------------
# Links are verified by the app that stores them, so the other member must be running.
# ensure_up <id> <port>: it answers HTTP, started temporarily (stopped on exit) if needed.
ensure_up() {
    curl -s -o /dev/null "http://127.0.0.1:$2/" && return 0
    bash "/opt/pocketserver/recipes/$1/start.sh" >"/tmp/$1-wiring.log" 2>&1 &
    TEMP_PIDS+=($!)
    for _ in $(seq 300); do curl -s -o /dev/null "http://127.0.0.1:$2/" && return 0; sleep 2; done
    return 1
}
arr_key() { sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' "/opt/pocketserver/apps/$1/data/config.xml"; }
installed() { [ -f "/opt/pocketserver/apps/$1/.installed" ]; }

link_qbit() {
    local body old
    body=$(api "$API/downloadclient/schema" | QB_PASS="$QB_PASS" jq -c '
        .[] | select(.implementation == "QBittorrent")
        | .name = "qBittorrent" | .enable = true | .priority = 1
        | .removeCompletedDownloads = true | .removeFailedDownloads = true
        | .fields |= map(
            if .name == "host" then .value = "127.0.0.1"
            elif .name == "port" then .value = 8180
            elif .name == "useSsl" then .value = false
            elif .name == "username" then .value = "admin"
            elif .name == "password" then .value = env.QB_PASS
            else . end)')
    for old in $(api "$API/downloadclient" | jq -r '.[] | select(.name == "qBittorrent") | .id'); do
        api -X DELETE "$API/downloadclient/$old" >/dev/null
    done
    api -X POST "$API/downloadclient?forceSave=true" --data-binary @- <<<"$body" >/dev/null
}

# link_prowlarr: (re)creates this app in Prowlarr's Applications so indexers sync here.
link_prowlarr() {
    local pkey papi=http://127.0.0.1:9696/api/v1 body old
    pkey=$(arr_key prowlarr)
    body=$(curl -fsS -H "X-Api-Key: $pkey" "$papi/applications/schema" | MY_KEY="$APIKEY" jq -c \
        --arg impl "$NAME" --arg url "$URL" '
        .[] | select(.implementation == $impl) | .name = $impl | .syncLevel = "fullSync"
        | .fields |= map(
            if .name == "prowlarrUrl" then .value = "http://127.0.0.1:9696"
            elif .name == "baseUrl" then .value = $url
            elif .name == "apiKey" then .value = env.MY_KEY
            else . end)')
    for old in $(curl -fsS -H "X-Api-Key: $pkey" "$papi/applications" | jq -r --arg n "$NAME" '.[] | select(.name == $n) | .id'); do
        curl -fsS -H "X-Api-Key: $pkey" -X DELETE "$papi/applications/$old" >/dev/null
    done
    curl -fsS -H "X-Api-Key: $pkey" -H 'Content-Type: application/json' -X POST \
        "$papi/applications?forceSave=true" --data-binary @- <<<"$body" >/dev/null
}

LINKS=()
if installed qbittorrent && QB_PASS=$(sed -n 's/^Password=//p' /opt/pocketserver/apps/qbittorrent/credentials) && [ -n "$QB_PASS" ]; then
    step "Connecting to qBittorrent"
    if ensure_up qbittorrent 8180 && link_qbit; then LINKS+=("qBittorrent (downloads)"); else echo "Could not add qBittorrent" >&2; fi
fi
if installed prowlarr && [ -f /opt/pocketserver/apps/prowlarr/data/config.xml ]; then
    step "Connecting to Prowlarr"
    if ensure_up prowlarr 9696 && link_prowlarr; then LINKS+=("Prowlarr (indexers)"); else echo "Could not connect Prowlarr" >&2; fi
fi

reset_credentials
set_credential Username admin
set_credential Password "$PASS"
set_credential "API key" "$APIKEY"
set_credential "Library folder" "$ROOT"
[ ${#LINKS[@]} -gt 0 ] && set_credential "Linked" "$(IFS=,; echo "${LINKS[*]}" | sed 's/,/, /g')"
set_credential "Tip" "Add indexers in Prowlarr, then search shows here; new episodes are checked every 2 h"
