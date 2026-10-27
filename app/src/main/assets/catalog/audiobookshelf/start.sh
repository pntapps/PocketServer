#!/bin/bash
APP_DIR=/opt/pocketserver/apps/audiobookshelf
ROOT=/opt/audiobookshelf
export NODE_ENV=production HOST=0.0.0.0 PORT=13378 \
    CONFIG_PATH="$APP_DIR/config" METADATA_PATH="$APP_DIR/metadata" \
    FFMPEG_PATH="$ROOT/bin/ffmpeg" FFPROBE_PATH="$ROOT/bin/ffprobe" NUSQLITE3_PATH="$ROOT/lib/libnusqlite3.so" \
    SKIP_BINARIES_CHECK=1 UV_THREADPOOL_SIZE=2
cd "$ROOT/app"
# Capped heap: keeps the V8 garbage collector from growing (and working) unbounded.
exec "$ROOT/node/bin/node" --max-old-space-size=384 dist-server/index.js
