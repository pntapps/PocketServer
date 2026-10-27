#!/bin/bash
source /opt/pocketserver/lib.sh
PORT=8096
URL=http://127.0.0.1:$PORT

apt_install ca-certificates curl gnupg jq
step "Adding Jellyfin repository"
mkdir -p /etc/apt/keyrings
curl -fsSL https://repo.jellyfin.org/jellyfin_team.gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/jellyfin.gpg
cat > /etc/apt/sources.list.d/jellyfin.sources <<EOF
Types: deb
URIs: https://repo.jellyfin.org/ubuntu
Suites: noble
Components: main
Architectures: arm64
Signed-By: /etc/apt/keyrings/jellyfin.gpg
EOF
apt_invalidate
# jellyfin-ffmpeg is still needed to read media info and to remux; it is never allowed to transcode.
apt_install jellyfin-server jellyfin-web jellyfin-ffmpeg7

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/config" "$APP_DIR/data" "$APP_DIR/cache" "$APP_DIR/log"
mkdir -p "$APP_DIR/config" /shared/Movies /shared/Shows /shared/Music
# No hardware acceleration (unavailable without root) and no tone mapping.
cat > "$APP_DIR/config/encoding.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<EncodingOptions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <EncodingThreadCount>1</EncodingThreadCount>
  <EnableThrottling>true</EnableThrottling>
  <EnableSegmentDeletion>true</EnableSegmentDeletion>
  <HardwareAccelerationType>none</HardwareAccelerationType>
  <EnableHardwareEncoding>false</EnableHardwareEncoding>
  <EnableTonemapping>false</EnableTonemapping>
  <EnableVppTonemapping>false</EnableVppTonemapping>
  <EnableSubtitleExtraction>false</EnableSubtitleExtraction>
</EncodingOptions>
EOF
# Warnings only: keeps log writes (and flash wear) minimal.
echo '{"Serilog":{"MinimumLevel":{"Default":"Warning"}}}' > "$APP_DIR/config/logging.json"

step "Starting Jellyfin for first-time setup"
bash /opt/pocketserver/recipes/jellyfin/start.sh --no-enforce >/tmp/jellyfin-setup.log 2>&1 &
JF=$!
trap 'stop_pid $JF' EXIT
wait_port $PORT 300
# The port opens long before startup finishes; the wizard answers 503 until then (first boot ~3 min).
for _ in $(seq 300); do curl -fs "$URL/Startup/Configuration" >/dev/null && break; sleep 2; done

step "Creating admin account"
PASS=$(gen_password 16)
post() { curl -fsS -X POST -H 'Content-Type: application/json' "${@:3}" "$URL$1" -d "$2"; }
post /Startup/Configuration '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
curl -fsS "$URL/Startup/User" >/dev/null
post /Startup/User "$(jq -nc --arg p "$PASS" '{Name:"admin",Password:$p}')"
post /Startup/RemoteAccess '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}'
post /Startup/Complete ''

AUTH='MediaBrowser Client="PocketServer", Device="PocketServer", DeviceId="pocketserver-setup", Version="1"'
LOGIN=$(post /Users/AuthenticateByName "$(jq -nc --arg p "$PASS" '{Username:"admin",Pw:$p}')" -H "Authorization: $AUTH")
TOKEN=$(jq -r .AccessToken <<<"$LOGIN")
api() { curl -fsS -H "Authorization: $AUTH, Token=\"$TOKEN\"" -H 'Content-Type: application/json' "$@"; }

# API key PocketServer uses to enforce the no-transcoding policy and to trigger scans while charging.
api -X POST "$URL/Auth/Keys?app=PocketServer"
api "$URL/Auth/Keys" | jq -r '.Items[] | select(.AppName=="PocketServer") | .AccessToken' | head -1 > "$APP_DIR/apikey"
chmod 600 "$APP_DIR/apikey"

step "Creating libraries"
# Trickplay, chapter images and loudness scans re-encode or decode whole files: all off.
LIBOPTS='{"EnableTrickplayImageExtraction":false,"ExtractTrickplayImagesDuringLibraryScan":false,
  "EnableChapterImageExtraction":false,"ExtractChapterImagesDuringLibraryScan":false,"EnableLUFSScan":false,
  "EnableRealtimeMonitor":false,"SaveLocalMetadata":false,"EnableEmbeddedTitles":false}'
for lib in "Movies movies" "Shows tvshows" "Music music"; do
    set -- $lib
    api -X POST "$URL/Library/VirtualFolders?name=$1&collectionType=$2&paths=%2Fshared%2F$1&refreshLibrary=false" \
        -d "{\"LibraryOptions\":$LIBOPTS}"
done

# No periodic background scans or image extraction; PocketServer scans only while charging.
api "$URL/ScheduledTasks" | jq -r '.[] | select(.Key | IN("RefreshLibrary","RefreshChapterImages","RefreshTrickplayImages","AudioNormalization","KeyframeExtraction","PluginUpdates")) | .Id' |
    while read -r id; do api -X POST "$URL/ScheduledTasks/$id/Triggers" -d '[]'; done

# No plugin catalog: Jellyfin would otherwise fetch its plugin repository in the background.
api -X POST "$URL/Repositories" -d '[]'

APIKEY=$(cat "$APP_DIR/apikey") bash /opt/pocketserver/recipes/jellyfin/enforce.sh

reset_credentials
set_credential Username admin
set_credential Password "$PASS"
set_credential "Media folders" "/shared/Movies, /shared/Shows, /shared/Music"
