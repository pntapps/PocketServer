#!/bin/bash
source /opt/pocketserver/lib.sh
VER=0.64.2
PORT=4533
URL=http://127.0.0.1:$PORT
BASE="https://github.com/navidrome/navidrome/releases/download/v$VER"

apt_install ca-certificates curl jq
fetch "$BASE/navidrome_${VER}_linux_arm64.tar.gz" /tmp/navidrome.tgz
fetch "$BASE/navidrome_checksums.txt" /tmp/navidrome.sums
step "Verifying checksum"
(cd /tmp && grep " navidrome_${VER}_linux_arm64.tar.gz\$" navidrome.sums | sed "s# navidrome_.*# navidrome.tgz#" | sha256sum -c -)
rm -rf /opt/navidrome && mkdir -p /opt/navidrome
tar -xzf /tmp/navidrome.tgz -C /opt/navidrome navidrome
rm -f /tmp/navidrome.tgz /tmp/navidrome.sums

step "Applying battery-optimized settings"
rm -rf "$APP_DIR/data"
mkdir -p "$APP_DIR/data" /shared/Music
cat > "$APP_DIR/navidrome.toml" <<TOML
Address = "0.0.0.0"
Port = $PORT
MusicFolder = "/shared/Music"
DataFolder = "$APP_DIR/data"
LogLevel = "warn"
# Scans: once a day at most (PocketServer also scans while charging). No full scan at every start.
# The folder watcher uses inotify (no polling) and waits for copies to settle before a quick scan.
Scanner.Schedule = "@every 24h"
Scanner.ScanOnStartup = false
Scanner.WatcherWait = "5m"
# Local network only: no metadata agents, scrobblers, insights or plugins.
EnableExternalServices = false
EnableInsightsCollector = false
Agents = ""
LastFM.Enabled = false
ListenBrainz.Enabled = false
Deezer.Enabled = false
Plugins.Enabled = false
EnableGravatar = false
# No ffmpeg: songs stream as-is (no transcoding), small caches, no artwork pre-rendering.
EnableTranscodingConfig = false
EnableArtworkPrecache = false
TranscodingCacheSize = "0"
ImageCacheSize = "32MB"
TOML

step "Starting Navidrome for first-time setup"
bash /opt/pocketserver/recipes/navidrome/start.sh >/tmp/navidrome-setup.log 2>&1 &
ND=$!
trap 'stop_pid $ND' EXIT
wait_port $PORT 120

step "Creating admin account"
PASS=$(gen_password 16)
curl -fsS -X POST -H 'Content-Type: application/json' "$URL/auth/createAdmin" \
    -d "$(jq -nc --arg p "$PASS" '{username:"admin",password:$p}')" >/dev/null
# The first (empty) library scan starts right after; let it finish so it is not redone at next start.
SUB="$URL/rest/getScanStatus?u=admin&p=enc:$(printf %s "$PASS" | od -An -tx1 | tr -d ' \n')&v=1.16.1&c=PocketServer&f=json"
for _ in $(seq 60); do
    sleep 2
    [ "$(curl -fs "$SUB" | jq -r '."subsonic-response".scanStatus.scanning')" = false ] && break
done

reset_credentials
set_credential Username admin
set_credential Password "$PASS"
set_credential "Music folder" /shared/Music
