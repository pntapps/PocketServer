#!/bin/bash
source /opt/pocketserver/lib.sh
# One Go process, SQLite in $APP_DIR/data. No background jobs, no update checks, no telemetry.
export GOMAXPROCS=2 GOGC=50
exec /opt/memos/memos --port 5230 --data "$APP_DIR/data" --log-level warn --trusted-proxies none
