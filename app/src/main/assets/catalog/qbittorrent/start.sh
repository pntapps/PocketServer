#!/bin/bash
APP_DIR=/opt/pocketserver/apps/qbittorrent
mkdir -p /shared/Downloads/incomplete
# Profile (config, resume data, logs) lives under the app folder; settings come from install.sh.
exec qbittorrent-nox --profile="$APP_DIR" --webui-port=8180
