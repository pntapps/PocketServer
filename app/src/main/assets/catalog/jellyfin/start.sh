#!/bin/bash
APP_DIR=/opt/pocketserver/apps/jellyfin
# Workstation GC with a 512 MB heap cap: far less RAM and fewer GC threads than the server default.
export DOTNET_gcServer=0 DOTNET_GCHeapHardLimit=0x20000000 DOTNET_TieredPGO=0

if [ "${1:-}" != "--no-enforce" ] && [ -s "$APP_DIR/apikey" ]; then
    (
        until curl -fs http://127.0.0.1:8096/System/Info/Public >/dev/null; do sleep 3; done
        APIKEY=$(cat "$APP_DIR/apikey") bash /opt/pocketserver/recipes/jellyfin/enforce.sh
    ) &
fi

exec /usr/lib/jellyfin/bin/jellyfin \
    --datadir "$APP_DIR/data" --configdir "$APP_DIR/config" \
    --cachedir "$APP_DIR/cache" --logdir "$APP_DIR/log" \
    --webdir /usr/share/jellyfin/web --ffmpeg /usr/lib/jellyfin-ffmpeg/ffmpeg
