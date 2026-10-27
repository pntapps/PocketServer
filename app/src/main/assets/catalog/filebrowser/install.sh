#!/bin/bash
source /opt/pocketserver/lib.sh
# FileBrowser Quantum: the maintained fork of File Browser (the original was archived on 2026-09-01
# and gets no more security fixes). Same idea and UI, single Go binary, regular security releases.
VER=v2.0.2-stable
SHA=03eac074efc1b0ef3cb39882c04eeee26027049d261b6b6706650d4ac2d8e21c
BIN=/opt/filebrowser-quantum/filebrowser

apt_install ca-certificates curl
DL=$(mktemp)
fetch "https://github.com/gtsteffaniak/filebrowser/releases/download/$VER/linux-arm64-filebrowser" "$DL" "$SHA"
install -D -m755 "$DL" "$BIN"
rm -f "$DL" /usr/local/bin/filebrowser  # the archived original, if an older install left it

step "Applying battery-optimized settings"
# Fresh database on every install (the original's filebrowser.db format is not reused).
rm -rf "$APP_DIR/filebrowser.db" "$APP_DIR/filebrowser.sqlite"* "$APP_DIR/cache"
mkdir -p "$APP_DIR/cache"
PASS=$(gen_password 16)
cat > "$APP_DIR/config.yaml" <<EOF
server:
  # No update checks, no thumbnails or preview resizing (CPU heavy), one image worker at most.
  disableUpdateCheck: true
  numImageProcessors: 1
  disablePreviews: true
  disablePreviewResize: true
  disableTypeDetectionByHeader: true
  sources:
    - path: "/shared"
      name: "shared"
      config:
        defaultEnabled: true
  cacheDir: "$APP_DIR/cache"
  cacheDirCleanup: true
  indexSqlConfig:
    cacheSizeMB: 8
  database:
    path: "$APP_DIR/filebrowser.sqlite"
    activity:
      disabled: true
auth:
  tokenExpirationHours: 12
  methods:
    password:
      enabled: true
      minLength: 8
      signup: false
  # Unique per install: never the built-in admin/admin, and session/2FA keys made on this device.
  adminUsername: "admin"
  adminPassword: "$PASS"
  key: "$(gen_password 48)"
  totpSecret: "$(gen_password 32)"
frontend:
  name: "PocketServer Files"
  disableDefaultLinks: true
  disablePWAInstall: true
userDefaults:
  preview:
    image: false
    video: false
    audio: false
    motionVideoPreview: false
    office: false
    popup: false
    folder: false
    models: false
  fileViewer:
    autoplayMedia: false
  account:
    disableUpdateNotifications: true
http:
  port: 8080
  listen: "0.0.0.0"
EOF
chmod 600 "$APP_DIR/config.yaml"

reset_credentials
set_credential Username admin
set_credential Password "$PASS"
set_credential Files "/shared (Movies, Music, Books, Downloads...)"
set_credential WebDAV "Settings > API tokens: create one, then connect to http://<this device>:8080/dav/shared/ with the token as password"
