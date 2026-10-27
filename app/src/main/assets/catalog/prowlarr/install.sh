#!/bin/bash
source /opt/pocketserver/lib.sh
NAME=Prowlarr PORT=9696
OPT=/opt/prowlarr
URL=http://127.0.0.1:$PORT
API=$URL/api/v1

apt_install ca-certificates curl jq libicu74 sqlite3
step "Downloading $NAME"
curl -fL --retry 3 --retry-delay 2 -sS -o /tmp/prowlarr.tgz \
    "https://prowlarr.servarr.com/v1/update/master/updatefile?os=linux&runtime=netcore&arch=arm64"
step "Unpacking $NAME"
rm -rf "$OPT"
mkdir -p "$OPT/bin"
tar -xzf /tmp/prowlarr.tgz -C "$OPT/bin" --strip-components=1
rm -rf /tmp/prowlarr.tgz "$OPT/bin/Prowlarr.Update"
# Updates come with PocketServer, never from the app itself (its updater is removed above).
printf 'PackageAuthor=PocketServer\nUpdateMethod=External\nUpdateMethodMessage=Updates are installed by PocketServer.\n' > "$OPT/package_info"

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/data"
mkdir -p "$APP_DIR/data"
APIKEY=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
cat > "$APP_DIR/data/config.xml" <<EOF
<Config>
  <BindAddress>*</BindAddress>
  <Port>$PORT</Port>
  <SslPort>6969</SslPort>
  <EnableSsl>False</EnableSsl>
  <LaunchBrowser>False</LaunchBrowser>
  <ApiKey>$APIKEY</ApiKey>
  <AuthenticationMethod>Forms</AuthenticationMethod>
  <AuthenticationRequired>Enabled</AuthenticationRequired>
  <Branch>master</Branch>
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
bash /opt/pocketserver/recipes/prowlarr/start.sh >/tmp/prowlarr-setup.log 2>&1 &
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

# --- Downloads Pack wiring with members that are already installed -----------------------------
# Prowlarr verifies a connection before saving it, so the other member must be running.
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

# link_app <Name> <port> <apikey>: (re)creates a Sonarr/Radarr entry under Applications.
link_app() {
    local body old
    body=$(api "$API/applications/schema" | APP_KEY="$3" jq -c --arg impl "$1" --arg url "http://127.0.0.1:$2" '
        .[] | select(.implementation == $impl) | .name = $impl | .syncLevel = "fullSync"
        | .fields |= map(
            if .name == "prowlarrUrl" then .value = "http://127.0.0.1:9696"
            elif .name == "baseUrl" then .value = $url
            elif .name == "apiKey" then .value = env.APP_KEY
            else . end)')
    for old in $(api "$API/applications" | jq -r --arg n "$1" '.[] | select(.name == $n) | .id'); do
        api -X DELETE "$API/applications/$old" >/dev/null
    done
    api -X POST "$API/applications?forceSave=true" --data-binary @- <<<"$body" >/dev/null
}

# For grabbing a release straight from Prowlarr's search page.
link_qbit() {
    local body old
    body=$(api "$API/downloadclient/schema" | QB_PASS="$1" jq -c '
        .[] | select(.implementation == "QBittorrent")
        | .name = "qBittorrent" | .enable = true | .priority = 1
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

LINKS=()
for member in "sonarr 8989 Sonarr" "radarr 7878 Radarr"; do
    set -- $member
    installed "$1" && [ -f "/opt/pocketserver/apps/$1/data/config.xml" ] || continue
    step "Connecting $3"
    if ensure_up "$1" "$2" && link_app "$3" "$2" "$(arr_key "$1")"; then LINKS+=("$3"); else echo "Could not add $3" >&2; fi
done
if installed qbittorrent && QB_PASS=$(sed -n 's/^Password=//p' /opt/pocketserver/apps/qbittorrent/credentials) && [ -n "$QB_PASS" ]; then
    step "Connecting to qBittorrent"
    if ensure_up qbittorrent 8180 && link_qbit "$QB_PASS"; then LINKS+=("qBittorrent"); else echo "Could not add qBittorrent" >&2; fi
fi

reset_credentials
set_credential Username admin
set_credential Password "$PASS"
set_credential "API key" "$APIKEY"
[ ${#LINKS[@]} -gt 0 ] && set_credential "Linked" "$(IFS=,; echo "${LINKS[*]}" | sed 's/,/, /g')"
set_credential "Tip" "Add your indexers here once; they sync to Sonarr and Radarr automatically"
