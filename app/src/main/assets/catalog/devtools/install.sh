#!/bin/bash
source /opt/pocketserver/lib.sh
NODE_MAJOR=24

apt_install ca-certificates curl gnupg xz-utils unzip git openssh-client build-essential \
    python3 python3-pip python3-venv \
    php8.3-cli php8.3-mysql php8.3-mbstring php8.3-xml php8.3-curl php8.3-zip php8.3-gd \
    php8.3-intl php8.3-sqlite3 php8.3-bcmath php8.3-opcache

# --- Node.js: newest v24 LTS build from nodejs.org, checked against the published SHA-256 list.
step "Downloading Node.js $NODE_MAJOR LTS"
WORK=/tmp/devtools && rm -rf $WORK && mkdir -p $WORK
curl -fsSL --retry 3 "https://nodejs.org/dist/latest-v$NODE_MAJOR.x/SHASUMS256.txt" -o $WORK/SHASUMS256.txt
TARBALL=$(grep -oE "node-v[0-9.]+-linux-arm64\.tar\.xz$" $WORK/SHASUMS256.txt | head -1)
[ -n "$TARBALL" ] || { echo "Node.js arm64 build not found" >&2; exit 1; }
fetch "https://nodejs.org/dist/latest-v$NODE_MAJOR.x/$TARBALL" "$WORK/$TARBALL"
(cd $WORK && grep " $TARBALL\$" SHASUMS256.txt | sha256sum -c --quiet -) || { echo "Node.js checksum mismatch" >&2; exit 1; }
step "Installing Node.js"
rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /usr/local/include/node
tar -xJf "$WORK/$TARBALL" -C /usr/local --strip-components=1 --no-same-owner \
    --exclude='*/CHANGELOG.md' --exclude='*/README.md' --exclude='*/LICENSE' --exclude='*/share/doc'
rm -rf $WORK

# --- Composer: official installer, verified against its published signature.
step "Installing Composer"
mkdir -p $WORK
curl -fsSL --retry 3 https://composer.github.io/installer.sig -o $WORK/installer.sig
curl -fsSL --retry 3 https://getcomposer.org/installer -o $WORK/composer-setup.php
[ "$(php -r "echo hash_file('sha384', '$WORK/composer-setup.php');")" = "$(tr -d '[:space:]' < $WORK/installer.sig)" ] ||
    { echo "Composer installer signature mismatch" >&2; exit 1; }
php $WORK/composer-setup.php --quiet --install-dir=/usr/local/bin --filename=composer
rm -rf $WORK

# --- mongosh (the MongoDB shell) from MongoDB's repository (7.0 line: the newest that runs here).
step "Adding MongoDB repository"
mkdir -p /etc/apt/keyrings
curl -fsSL https://pgp.mongodb.com/server-7.0.asc | gpg --dearmor --yes -o /etc/apt/keyrings/mongodb-7.0.gpg
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/mongodb-7.0.gpg] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" \
    > /etc/apt/sources.list.d/mongodb-org-7.0.list
apt_invalidate
apt_install mongodb-mongosh

step "Applying quiet settings"
# No update nags, funding messages or version checks phoning home on every command.
npm config set --global update-notifier false
npm config set --global fund false
npm config set --global audit false
cat > /etc/pip.conf <<'EOF'
[global]
disable-pip-version-check = true
EOF
# Opt out of framework telemetry (Next.js, Nuxt, Astro, Gatsby, Angular, .NET, ...) for every shell.
cat > /etc/profile.d/pocketserver-dev.sh <<'EOF'
# Android starts apps with umask 077; new project files should get normal Linux permissions.
umask 022
export DO_NOT_TRACK=1 NEXT_TELEMETRY_DISABLED=1 NUXT_TELEMETRY_DISABLED=1 ASTRO_TELEMETRY_DISABLED=1 \
    GATSBY_TELEMETRY_DISABLED=1 NG_CLI_ANALYTICS=false STORYBOOK_DISABLE_TELEMETRY=1 \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 COMPOSER_ALLOW_SUPERUSER=1
EOF
export COMPOSER_ALLOW_SUPERUSER=1
# Every terminal gets the environment: login shells via profile.d, and Code Studio's non-login
# terminals via bash.bashrc.
if ! grep -q pocketserver-profile /etc/bash.bashrc; then
    cat >> /etc/bash.bashrc <<'SH'
# pocketserver-profile: same environment in every terminal (Shell tab and Code Studio)
for f in /etc/profile.d/pocketserver-*.sh; do [ -r "$f" ] && . "$f"; done
SH
fi
mongosh --nodb --quiet --eval 'disableTelemetry()' >/dev/null 2>&1 || true

# Workspaces. Existing projects are never touched; only a missing start page is created.
mkdir -p /root/projects /root/www
if [ ! -e /root/www/index.php ] && [ ! -e /root/www/index.html ]; then
cat > /root/www/index.php <<'EOF'
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>It works</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem}code{background:#eee;padding:0 .3em}</style>
<h1>It works!</h1>
<p>This page is <code><?= htmlspecialchars(__FILE__) ?></code>. Put your PHP sites in <code>/root/www</code>.</p>
<ul>
  <li>PHP <?= PHP_VERSION ?> (<?= php_sapi_name() ?>)</li>
  <li>Server: <?= htmlspecialchars($_SERVER['SERVER_SOFTWARE'] ?? 'cli') ?></li>
  <li>OPcache: <?= function_exists('opcache_get_status') && @opcache_get_status(false) ? 'on' : 'off' ?></li>
  <li>Extensions: <?= htmlspecialchars(implode(', ', get_loaded_extensions())) ?></li>
</ul>
<p><a href="/adminer/">Adminer</a> (database manager)</p>
EOF
fi

step "Checking tools"
NODE_V=$(node -v); NPM_V=$(npm -v)
PHP_V=$(php -r 'echo PHP_VERSION;'); COMPOSER_V=$(composer -V --no-ansi 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
PY_V=$(python3 -c 'import platform; print(platform.python_version())'); GIT_V=$(git --version | awk '{print $3}')
MONGOSH_V=$(mongosh --version 2>/dev/null | head -1)

reset_credentials
set_credential Node "$NODE_V (npm $NPM_V)"
set_credential PHP "$PHP_V (Composer $COMPOSER_V)"
set_credential Python "$PY_V (pip, venv)"
set_credential Git "$GIT_V"
set_credential mongosh "$MONGOSH_V"
set_credential Folders "/root/projects (code), /root/www (PHP sites)"
