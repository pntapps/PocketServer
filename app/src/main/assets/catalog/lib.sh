#!/bin/bash
# Shared helpers for PocketServer recipes. Every install/configure script sources this file.
# Lines printed as "::step:: <text>" are shown as progress in the app.
set -euo pipefail
# Android starts apps with umask 077; installed files must be readable like on a normal Linux box.
umask 022
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

PS_ROOT=/opt/pocketserver
APP_ID="${APP_ID:?APP_ID not set}"
APP_DIR="$PS_ROOT/apps/$APP_ID"
mkdir -p "$APP_DIR"

step() { echo "::step:: $*"; }

# apt index is refreshed at most once a day (saves data + time on repeated installs).
apt_ready() {
    local stamp="$PS_ROOT/.apt-updated"
    if [ ! -f "$stamp" ] || [ $(( $(date +%s) - $(stat -c %Y "$stamp") )) -gt 86400 ]; then
        step "Updating package lists"
        apt-get -o DPkg::Lock::Timeout=900 update -q
        touch "$stamp"
    fi
}

apt_install() {
    apt_ready
    step "Installing $*"
    # Wait for the package lock instead of failing: another install (or a terminal) may hold it.
    apt-get -o DPkg::Lock::Timeout=900 install -y -q --no-install-recommends "$@"
    apt-get clean
}

# fetch <url> <dest> [sha256]: with a hash, a corrupted or tampered download is deleted and fails.
fetch() {
    step "Downloading $(basename "$1")"
    curl -fL --retry 3 --retry-delay 2 -sS -o "$2" "$1"
    if [ -n "${3:-}" ] && [ "$(sha256sum "$2" | cut -d' ' -f1)" != "$3" ]; then
        rm -f "$2"
        echo "Checksum mismatch for $(basename "$1"): download corrupted or tampered with" >&2
        return 1
    fi
}

gen_password() { tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c "${1:-16}" || true; }

# Forces the next apt_ready to refresh (after adding a repository).
apt_invalidate() { rm -f "$PS_ROOT/.apt-updated"; }

# Credentials are shown on the app card (key=value per line).
reset_credentials() { : > "$APP_DIR/credentials"; chmod 600 "$APP_DIR/credentials"; }
set_credential() { echo "$1=$2" >> "$APP_DIR/credentials"; }

# Wait until a local TCP port accepts connections (used by configure scripts).
wait_port() {
    local port=$1 tries=${2:-120}
    for _ in $(seq "$tries"); do
        (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && return 0
        sleep 1
    done
    echo "Timed out waiting for port $port" >&2
    return 1
}

# Stops a process politely, then forcibly if it has not exited after 15 s.
stop_pid() {
    kill "$1" 2>/dev/null || return 0
    for _ in $(seq 15); do kill -0 "$1" 2>/dev/null || return 0; sleep 1; done
    kill -9 "$1" 2>/dev/null || true
}

# --- Local HTTPS -------------------------------------------------------------------------------
# One private CA for every HTTPS app, so each device installs a single certificate
# (/shared/PocketServer-CA.crt). Older installs kept the CA in Vaultwarden's folder; reuse it.
CA_DIR="$PS_ROOT/ca"

# The CA is name-constrained: it can only vouch for local names and private addresses, so even a
# leaked key cannot impersonate a real website to devices that trust it.
CA_CONSTRAINTS="critical,permitted;DNS:pocketserver.local,permitted;DNS:localhost,permitted;IP:127.0.0.0/255.0.0.0,permitted;IP:10.0.0.0/255.0.0.0,permitted;IP:172.16.0.0/255.240.0.0,permitted;IP:192.168.0.0/255.255.0.0,permitted;IP:100.64.0.0/255.192.0.0"

ensure_ca() {
    # Older installs made an unconstrained CA (and kept a copy under Vaultwarden): replace it once.
    rm -f "$PS_ROOT/apps/vaultwarden/tls/ca.key" "$PS_ROOT/apps/vaultwarden/tls/ca.crt" "$PS_ROOT/apps/vaultwarden/tls/ca.srl"
    if [ -f "$CA_DIR/ca.crt" ] && ! openssl x509 -in "$CA_DIR/ca.crt" -noout -ext nameConstraints 2>/dev/null | grep -qi permitted; then
        rm -f "$CA_DIR/ca.key" "$CA_DIR/ca.crt" "$CA_DIR/ca.srl"
    fi
    if [ ! -f "$CA_DIR/ca.key" ]; then
        mkdir -p "$CA_DIR" && chmod 700 "$CA_DIR"
        openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
            -subj "/CN=PocketServer Local CA" -keyout "$CA_DIR/ca.key" -out "$CA_DIR/ca.crt" \
            -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
            -addext "keyUsage=critical,keyCertSign,cRLSign" \
            -addext "nameConstraints=$CA_CONSTRAINTS" 2>/dev/null
        chmod 600 "$CA_DIR/ca.key"
    fi
    cp "$CA_DIR/ca.crt" /shared/PocketServer-CA.crt
}

# issue_server_cert <dir>: server.crt/server.key in <dir> for every address devices may use.
# PocketServer passes the phone's current LAN addresses in PS_LAN_IPS; re-issued only when they change.
issue_server_cert() {
    local dir=$1 ips="${PS_LAN_IPS:-$(hostname -I 2>/dev/null || true)}"
    local san="DNS:pocketserver.local,DNS:localhost,IP:127.0.0.1" ip
    for ip in $ips; do san="$san,IP:$ip"; done
    ensure_ca
    mkdir -p "$dir"
    if [ "$(cat "$dir/san" 2>/dev/null)" = "$san" ] && [ -f "$dir/server.crt" ] && [ "$(cat "$dir/ca.fp" 2>/dev/null)" = "$(sha256sum < "$CA_DIR/ca.crt")" ]; then
        return 0
    fi
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "/CN=pocketserver.local" \
        -keyout "$dir/server.key" -out "$dir/server.csr" 2>/dev/null
    openssl x509 -req -in "$dir/server.csr" -CA "$CA_DIR/ca.crt" -CAkey "$CA_DIR/ca.key" -CAcreateserial \
        -days 825 -extfile <(printf "subjectAltName=%s\nextendedKeyUsage=serverAuth" "$san") \
        -out "$dir/server.crt" 2>/dev/null
    chmod 600 "$dir/server.key"
    echo "$san" > "$dir/san"
    sha256sum < "$CA_DIR/ca.crt" > "$dir/ca.fp"
}

# --- Multi-process apps ------------------------------------------------------------------------
# supervise <pid>...: for start.sh scripts that run several servers (e.g. nginx in front of an app).
# Stays in the foreground until one of them exits, then stops the others and fails, so the app
# shows as stopped instead of half-running. SIGTERM (Stop in the app) shuts them all down cleanly.
supervise() {
    local pids=("$@") rc=0
    trap 'for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; wait; exit 0' TERM INT
    wait -n "${pids[@]}" || rc=$?
    trap - TERM INT
    echo "A server process exited (status $rc); stopping the others" >&2
    for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
    for p in "${pids[@]}"; do stop_pid "$p"; done
    wait || true
    exit $(( rc == 0 ? 1 : rc ))
}
