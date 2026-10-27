#!/bin/bash
APP_DIR=/opt/pocketserver/apps/calibreweb
export CALIBRE_DBPATH="$APP_DIR"
cd "$APP_DIR"
exec /opt/calibreweb/bin/cps -p "$APP_DIR/app.db" -o "$APP_DIR/calibre-web.log"
