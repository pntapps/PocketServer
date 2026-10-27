#!/bin/bash
APP_DIR=/opt/pocketserver/apps/filebrowser
BIN=/opt/filebrowser-quantum/filebrowser
if [ ! -x "$BIN" ] || [ ! -f "$APP_DIR/config.yaml" ]; then
    echo "FileBrowser was updated to FileBrowser Quantum (the old project gets no security fixes)." >&2
    echo "Reinstall it from its card: your files in /shared are kept." >&2
    exit 1
fi
cd "$APP_DIR"
# A Go program: a lower memory target keeps it near 70 MB instead of ~90 MB, with no idle CPU cost.
exec env GOMEMLIMIT=48MiB GOGC=50 "$BIN" -c "$APP_DIR/config.yaml" --no-input
