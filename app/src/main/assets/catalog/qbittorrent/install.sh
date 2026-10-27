#!/bin/bash
source /opt/pocketserver/lib.sh
PORT=8180
PEER_PORT=6881

apt_install ca-certificates curl jq python3 qbittorrent-nox

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/qBittorrent"
CONF_DIR="$APP_DIR/qBittorrent/config"
mkdir -p "$CONF_DIR" /shared/Downloads/incomplete
PASS=$(gen_password 16)
# Web UI password in the format qBittorrent stores: PBKDF2-HMAC-SHA512, 100000 rounds, 16-byte salt.
HASH=$(QB_PASS="$PASS" python3 -I -c '
import base64, hashlib, os
salt = os.urandom(16)
key = hashlib.pbkdf2_hmac("sha512", os.environ["QB_PASS"].encode(), salt, 100000)
print("@ByteArray(%s:%s)" % (base64.b64encode(salt).decode(), base64.b64encode(key).decode()))')

# Phone-friendly: few active torrents and connections, small thread pools, seeding stops at
# ratio 1.0, local network only (no UPnP/NAT-PMP), no RSS, file log off (less flash wear).
# Alternative limits (1 MiB/s down, 128 KiB/s up) are toggled by throttle.sh on battery.
cat > "$CONF_DIR/qBittorrent.conf" <<EOF
[LegalNotice]
Accepted=true

[Application]
FileLogger\Enabled=false
MemoryWorkingSetLimit=128

[BitTorrent]
Session\Port=$PEER_PORT
Session\DefaultSavePath=/shared/Downloads
Session\TempPath=/shared/Downloads/incomplete
Session\TempPathEnabled=true
Session\QueueingSystemEnabled=true
Session\MaxActiveDownloads=2
Session\MaxActiveUploads=2
Session\MaxActiveTorrents=3
Session\IgnoreSlowTorrentsForQueueing=true
Session\MaxConnections=100
Session\MaxConnectionsPerTorrent=50
Session\MaxUploads=4
Session\MaxUploadsPerTorrent=2
Session\GlobalMaxRatio=1
Session\MaxRatioAction=0
Session\AlternativeGlobalDLSpeedLimit=1024
Session\AlternativeGlobalUPSpeedLimit=128
Session\UseAlternativeGlobalSpeedLimit=false
Session\BandwidthSchedulerEnabled=false
Session\DiskIOType=Posix
Session\DiskCacheSize=32
Session\AsyncIOThreadsCount=2
Session\HashingThreadsCount=1
Session\FilePoolSize=40
Session\CheckingMemUsageSize=16
Session\Preallocation=false
Session\AnonymousMode=false
Session\AddTrackersEnabled=false

[Network]
PortForwardingEnabled=false

[RSS]
AutoDownloader\EnableProcessing=false
Session\EnableProcessing=false

[Preferences]
General\Locale=en
Advanced\updateCheck=false
Downloads\SavePath=/shared/Downloads
Downloads\TempPath=/shared/Downloads/incomplete
Downloads\TempPathEnabled=true
WebUI\Enabled=true
WebUI\Address=0.0.0.0
WebUI\Port=$PORT
WebUI\UPnP=false
WebUI\Username=admin
WebUI\Password_PBKDF2="$HASH"
WebUI\LocalHostAuth=true
WebUI\CSRFProtection=true
WebUI\ClickjackingProtection=true
EOF
chmod 600 "$CONF_DIR/qBittorrent.conf"

reset_credentials
set_credential Username admin
set_credential Password "$PASS"
set_credential "Download folder" "/shared/Downloads (unfinished files in incomplete/)"
set_credential "Peer port" "$PEER_PORT (TCP+UDP, no router port forwarding)"

# --- Downloads Pack wiring: register qBittorrent in Sonarr/Radarr/Prowlarr if installed ----------
TEMP_PIDS=()
trap 'for p in "${TEMP_PIDS[@]}"; do stop_pid "$p"; done' EXIT

# Sonarr/Radarr/Prowlarr verify the connection before saving it, so both sides must be running.
# ensure_up <id> <port>: it answers HTTP, started temporarily (stopped on exit) if needed.
ensure_up() {
    curl -s -o /dev/null "http://127.0.0.1:$2/" && return 0
    bash "/opt/pocketserver/recipes/$1/start.sh" >"/tmp/$1-wiring.log" 2>&1 &
    TEMP_PIDS+=($!)
    for _ in $(seq 300); do curl -s -o /dev/null "http://127.0.0.1:$2/" && return 0; sleep 2; done
    return 1
}
arr_key() { sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' "/opt/pocketserver/apps/$1/data/config.xml"; }

# link_qbit <id> <port> <api version>: (re)creates the "qBittorrent" download client there.
link_qbit() {
    local id=$1 port=$2 key body old
    key=$(arr_key "$id")
    local api="http://127.0.0.1:$port/api/$3"
    body=$(curl -fsS -H "X-Api-Key: $key" "$api/downloadclient/schema" | QB_PASS="$PASS" jq -c '
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
    for old in $(curl -fsS -H "X-Api-Key: $key" "$api/downloadclient" | jq -r '.[] | select(.name == "qBittorrent") | .id'); do
        curl -fsS -H "X-Api-Key: $key" -X DELETE "$api/downloadclient/$old" >/dev/null
    done
    curl -fsS -H "X-Api-Key: $key" -H 'Content-Type: application/json' -X POST \
        "$api/downloadclient?forceSave=true" --data-binary @- <<<"$body" >/dev/null
}

LINKED=()
for member in "sonarr 8989 Sonarr v3" "radarr 7878 Radarr v3" "prowlarr 9696 Prowlarr v1"; do
    set -- $member
    [ -f "/opt/pocketserver/apps/$1/.installed" ] && [ -f "/opt/pocketserver/apps/$1/data/config.xml" ] || continue
    step "Connecting $3 to qBittorrent"
    if ensure_up qbittorrent $PORT && ensure_up "$1" "$2" && link_qbit "$1" "$2" "$4"; then
        LINKED+=("$3")
    else
        echo "Could not connect $3 (add qBittorrent in its Settings > Download Clients)" >&2
    fi
done
if [ ${#LINKED[@]} -gt 0 ]; then
    set_credential "Linked" "Download client of $(IFS=,; echo "${LINKED[*]}" | sed 's/,/, /g')"
fi
set_credential "Tip" "Slower limits apply automatically on battery; full speed while charging"
