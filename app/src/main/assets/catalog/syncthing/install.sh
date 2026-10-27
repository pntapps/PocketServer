#!/bin/bash
source /opt/pocketserver/lib.sh
VER=v2.1.7

apt_install ca-certificates curl
fetch "https://github.com/syncthing/syncthing/releases/download/$VER/syncthing-linux-arm64-$VER.tar.gz" /tmp/st.tgz \
    48e456690cd2b97e7d26b02b5ac35f62a3b36172267b144cfeaf02c993ab955c
mkdir -p /tmp/st && tar -xzf /tmp/st.tgz -C /tmp/st --strip-components=1
install -m755 /tmp/st/syncthing /usr/local/bin/syncthing
rm -rf /tmp/st /tmp/st.tgz

step "Applying battery-optimized settings"
HOME_DIR="$APP_DIR/home"
rm -rf "$HOME_DIR"
PASS=$(gen_password 16)
STNODEFAULTFOLDER=1 syncthing generate --home="$HOME_DIR" --gui-user=admin --gui-password="$PASS"
CFG="$HOME_DIR/config.xml"
# Local network only: no global discovery, relays or NAT traversal. No telemetry or self-upgrade.
sed -i \
    -e 's#<globalAnnounceEnabled>true#<globalAnnounceEnabled>false#' \
    -e 's#<relaysEnabled>true#<relaysEnabled>false#' \
    -e 's#<natEnabled>true#<natEnabled>false#' \
    -e 's#<urAccepted>[-0-9]*<#<urAccepted>-1<#' \
    -e 's#<autoUpgradeIntervalH>[0-9]*<#<autoUpgradeIntervalH>0<#' \
    -e 's#<crashReportingEnabled>true#<crashReportingEnabled>false#' \
    -e 's#<startBrowser>true#<startBrowser>false#' \
    -e 's#<address>127.0.0.1:8384</address>#<address>0.0.0.0:8384</address>#' \
    "$CFG"
mkdir -p /shared/Sync
reset_credentials
set_credential Username admin
set_credential Password "$PASS"
