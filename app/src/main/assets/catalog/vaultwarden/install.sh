#!/bin/bash
source /opt/pocketserver/lib.sh
VER=1.37.4
IMAGE=vaultwarden/server
REG=https://registry-1.docker.io/v2/$IMAGE

apt_install ca-certificates curl jq openssl sqlite3 inotify-tools

# Vaultwarden only ships container images. The alpine variant holds a static binary, so we pull
# its layers straight from Docker Hub (no Docker needed) and verify every blob's sha256.
step "Downloading Vaultwarden $VER"
TOKEN=$(curl -fsS "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$IMAGE:pull" | jq -r .token)
reg() { curl -fsSL -H "Authorization: Bearer $TOKEN" "$@"; }
INDEX=$(reg -H 'Accept: application/vnd.oci.image.index.v1+json' "$REG/manifests/$VER-alpine")
DIGEST=$(jq -r '.manifests[] | select(.platform.architecture=="arm64") | .digest' <<<"$INDEX")
LAYERS=$(reg -H 'Accept: application/vnd.oci.image.manifest.v1+json' "$REG/manifests/$DIGEST" | jq -r '.layers | reverse | .[].digest')

WORK=/tmp/vw && rm -rf $WORK && mkdir -p $WORK/root
# Newest layers first: the binary and the web vault sit on top, so we usually stop after two.
for layer in $LAYERS; do
    reg -o $WORK/layer.tgz "$REG/blobs/$layer"
    [ "sha256:$(sha256sum $WORK/layer.tgz | cut -d' ' -f1)" = "$layer" ] || { echo "Checksum mismatch for $layer" >&2; exit 1; }
    tar -xzf $WORK/layer.tgz -C $WORK/root --wildcards 'vaultwarden' 'web-vault/*' 2>/dev/null || true
    [ -x $WORK/root/vaultwarden ] && [ -f $WORK/root/web-vault/index.html ] && break
done
[ -x $WORK/root/vaultwarden ] || { echo "vaultwarden binary not found in image" >&2; exit 1; }
rm -rf /opt/vaultwarden && mkdir -p /opt/vaultwarden
mv $WORK/root/vaultwarden $WORK/root/web-vault /opt/vaultwarden/
rm -rf $WORK

step "Creating local HTTPS certificate"
# Bitwarden apps refuse plain HTTP: certificates come from PocketServer's private CA. Users install
# its certificate once per device; start.sh re-issues the server certificate when the IP changes.
mkdir -p "$APP_DIR/data"
ensure_ca

reset_credentials
set_credential "First account" "Enter your email on this card, start, then create the account with that email"
set_credential Certificate "Install /shared/PocketServer-CA.crt on each device that uses Bitwarden"
