#!/bin/bash
source /opt/pocketserver/lib.sh
VER=2.37.1
NODE_VER=24.22.1
PORT=13378
URL=http://127.0.0.1:$PORT
ROOT=/opt/audiobookshelf
GH=https://github.com
# Audiobookshelf ships no arm64 tarball. Its official arm64 image (advplyr/audiobookshelf:$VER) holds the
# prebuilt web client and server in two small layers: fetched by content digest (verified below), so no
# Node build runs on the phone. The image is Alpine-based, so the one native module (sqlite3) is swapped
# for upstream's glibc prebuild.
LAYER_CLIENT=90a3a166faa06d4b1e7b5b9230b7ee9688b11277fc78dfc629ee85603de52036
LAYER_SERVER=7c58304e777f4fcd3ea3b3466bc8cdf46ff8936698b188b6858bcada0fd41eeb

apt_install ca-certificates curl jq unzip xz-utils
rm -rf "$ROOT" && mkdir -p "$ROOT/app" "$ROOT/bin" "$ROOT/lib" "$ROOT/node/bin"

# check <file> <sha256>
check() { echo "$2  $1" | sha256sum -c - >/dev/null || { echo "Checksum mismatch: $1" >&2; exit 1; }; }

step "Downloading Audiobookshelf $VER"
TOKEN=$(curl -fsS "https://auth.docker.io/token?service=registry.docker.io&scope=repository:advplyr/audiobookshelf:pull" | jq -r .token)
for layer in $LAYER_CLIENT $LAYER_SERVER; do
    curl -fL --retry 3 -sS -H "Authorization: Bearer $TOKEN" -o /tmp/abs-layer.tgz \
        "https://registry-1.docker.io/v2/advplyr/audiobookshelf/blobs/sha256:$layer"
    check /tmp/abs-layer.tgz $layer
    tar -xzf /tmp/abs-layer.tgz -C "$ROOT/app" --strip-components=1 app
done
rm -f /tmp/abs-layer.tgz
# The web client checks GitHub for new releases from every browser that opens it: point it nowhere.
grep -rl 'api.github.com/repos/advplyr/audiobookshelf/releases' "$ROOT/app/client/dist" 2>/dev/null |
    xargs -r sed -i 's#https://api.github.com/repos/advplyr/audiobookshelf/releases#data:application/json,[]#g'

fetch "$GH/TryGhost/node-sqlite3/releases/download/v5.1.7/sqlite3-v5.1.7-napi-v6-linux-arm64.tar.gz" /tmp/sqlite3.tgz
check /tmp/sqlite3.tgz 0f112c63a74bebdffce298792c264b3af4b85d7fe1975a4bca1227438f531dbb
tar -xzf /tmp/sqlite3.tgz -C "$ROOT/app/node_modules/sqlite3"

step "Installing the Node.js runtime"
fetch "https://nodejs.org/dist/v$NODE_VER/node-v$NODE_VER-linux-arm64.tar.xz" /tmp/node.txz
check /tmp/node.txz dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e
# Only the node binary (no npm): Audiobookshelf has its own copy, independent of the Dev Toolchain.
tar -xJf /tmp/node.txz -C "$ROOT/node" --strip-components=1 "node-v$NODE_VER-linux-arm64/bin/node"

# ffprobe reads audio metadata during scans; ffmpeg is only used on demand (playing formats the
# browser cannot, merging/embedding). Static 5.1 builds, the version Audiobookshelf expects (~100 MB).
for b in "ffmpeg a58818d0a81920fedecd707ba39ae08f641cd2ca4e0d9bc34f50326e76e36b88" \
         "ffprobe 37c220d8cd3e3cd497d4aa281133dcc27ce55a6178868d9f08e05fd90211a545"; do
    set -- $b
    fetch "$GH/ffbinaries/ffbinaries-prebuilt/releases/download/v5.1/$1-5.1-linux-arm-64.zip" /tmp/$1.zip
    check /tmp/$1.zip $2
    unzip -oq /tmp/$1.zip -d "$ROOT/bin" && chmod 755 "$ROOT/bin/$1"
done
# Unicode-aware search/sort extension for SQLite.
fetch "$GH/mikiher/nunicode-sqlite/releases/download/v1.2/libnusqlite3-linux-arm64.zip" /tmp/nus.zip
check /tmp/nus.zip b92ed0f5c45fc10bd0230577ee429141e6a3b5fca289b8432fa46833d21e38d0
unzip -oq /tmp/nus.zip -d "$ROOT/lib" && echo 1.2 > "$ROOT/lib/libnusqlite3.so.ver"
rm -f /tmp/sqlite3.tgz /tmp/node.txz /tmp/ffmpeg.zip /tmp/ffprobe.zip /tmp/nus.zip

step "Starting Audiobookshelf for first-time setup"
rm -rf "$APP_DIR/config" "$APP_DIR/metadata"
mkdir -p "$APP_DIR/config" "$APP_DIR/metadata" /shared/Audiobooks /shared/Podcasts
bash /opt/pocketserver/recipes/audiobookshelf/start.sh >/tmp/audiobookshelf-setup.log 2>&1 &
ABS=$!
trap 'stop_pid $ABS' EXIT
wait_port $PORT 180
for _ in $(seq 60); do curl -fs "$URL/status" >/dev/null && break; sleep 2; done

step "Creating root account"
PASS=$(gen_password 16)
post() { curl -fsS -X POST -H 'Content-Type: application/json' "${@:3}" "$URL$1" -d "$2"; }
post /init "$(jq -nc --arg p "$PASS" '{newRoot:{username:"root",password:$p}}')" >/dev/null
TOKEN=$(post /login "$(jq -nc --arg p "$PASS" '{username:"root",password:$p}')" | jq -r '.user.accessToken // .user.token')
api() { curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@" >/dev/null; }

step "Applying battery-optimized settings"
# No folder watcher, no cover lookups online, no nightly backups, warnings-only log. Podcast
# auto-download is off for every podcast unless turned on there (each podcast has its own check
# schedule; the server-wide default cannot be changed in 2.37, it resets to hourly at start).
api -X PATCH "$URL/api/settings" -d '{"scannerDisableWatcher":true,"scannerFindCovers":false,
  "scannerPreferMatchedMetadata":false,"backupSchedule":false,"logLevel":3,"chromecastEnabled":false}'
# Libraries without timed auto-scans or watchers (PocketServer scans while charging).
LIBSET='{"disableWatcher":true,"autoScanCronExpression":null,"skipMatchingMediaWithAsin":true,"skipMatchingMediaWithIsbn":true}'
api -X POST "$URL/api/libraries" -d "{\"name\":\"Audiobooks\",\"mediaType\":\"book\",\"folders\":[{\"fullPath\":\"/shared/Audiobooks\"}],\"settings\":$LIBSET}"
api -X POST "$URL/api/libraries" -d "{\"name\":\"Podcasts\",\"mediaType\":\"podcast\",\"folders\":[{\"fullPath\":\"/shared/Podcasts\"}],\"settings\":$LIBSET}"

reset_credentials
set_credential Username root
set_credential Password "$PASS"
set_credential "Media folders" "/shared/Audiobooks, /shared/Podcasts"
