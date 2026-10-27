#!/bin/bash
# Usage: throttle.sh on|off
# on  = alternative (slow) speed limits, used while the phone runs on battery.
# off = normal limits, used while charging.
# Idempotent and silent on success. If qBittorrent is not running, the choice is written to its
# config so it applies on the next start.
set -euo pipefail
APP_DIR=/opt/pocketserver/apps/qbittorrent
CONF="$APP_DIR/qBittorrent/config/qBittorrent.conf"
URL=http://127.0.0.1:8180

case "${1:-}" in
    on) WANT=1 ;;
    off) WANT=0 ;;
    *) echo "Usage: $0 on|off" >&2; exit 2 ;;
esac

if ! (exec 3<>/dev/tcp/127.0.0.1/8180) 2>/dev/null; then
    [ -f "$CONF" ] || { echo "qBittorrent is not installed" >&2; exit 1; }
    VAL=$([ "$WANT" = 1 ] && echo true || echo false)
    if grep -q '^Session\\UseAlternativeGlobalSpeedLimit=' "$CONF"; then
        sed -i "s/^Session\\\\UseAlternativeGlobalSpeedLimit=.*/Session\\\\UseAlternativeGlobalSpeedLimit=$VAL/" "$CONF"
    else
        sed -i "/^\[BitTorrent\]/a Session\\\\UseAlternativeGlobalSpeedLimit=$VAL" "$CONF"
    fi
    exit 0
fi

USER_NAME=$(sed -n 's/^Username=//p' "$APP_DIR/credentials")
PASS=$(sed -n 's/^Password=//p' "$APP_DIR/credentials")
JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

RES=$(curl -fsS -c "$JAR" --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASS" "$URL/api/v2/auth/login")
[ "$RES" = "Ok." ] || { echo "qBittorrent login failed" >&2; exit 1; }
MODE=$(curl -fsS -b "$JAR" "$URL/api/v2/transfer/speedLimitsMode")
if [ "$MODE" != "$WANT" ]; then
    curl -fsS -b "$JAR" -X POST "$URL/api/v2/transfer/toggleSpeedLimitsMode"
fi
curl -fsS -b "$JAR" -X POST "$URL/api/v2/auth/logout" >/dev/null 2>&1 || true
