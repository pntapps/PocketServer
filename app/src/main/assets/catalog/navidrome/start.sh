#!/bin/bash
APP_DIR=/opt/pocketserver/apps/navidrome
cd "$APP_DIR"
exec /opt/navidrome/navidrome --configfile "$APP_DIR/navidrome.toml"
