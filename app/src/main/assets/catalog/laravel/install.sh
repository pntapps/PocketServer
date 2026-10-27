#!/bin/bash
source /opt/pocketserver/lib.sh
export COMPOSER_ALLOW_SUPERUSER=1 COMPOSER_NO_INTERACTION=1
# Extensions Laravel needs beyond the toolchain's set.
apt_install php8.3-sqlite3 php8.3-mbstring php8.3-xml php8.3-curl php8.3-zip php8.3-bcmath php8.3-intl unzip

step "Installing the Laravel installer"
composer global require -q laravel/installer
# Composer's global bin dir on every shell's PATH (login shells and Code Studio terminals). Asked
# from Composer itself: it is /root/.composer/vendor/bin here, not the XDG path some systems use.
BIN=$(composer global config bin-dir --absolute 2>/dev/null)
BIN=${BIN:-/root/.composer/vendor/bin}
cat > /etc/profile.d/pocketserver-laravel.sh <<SH
case ":\$PATH:" in *":$BIN:"*) ;; *) export PATH="$BIN:\$PATH" ;; esac
SH

step "Creating the starter project"
mkdir -p /root/projects
# Only when missing: never overwrite the user's work. Laravel defaults to SQLite, so it runs with no setup.
# Built under a temporary name and moved into place only when complete (see react/install.sh).
if [ ! -e /root/projects/laravel-starter ]; then
    rm -rf /root/projects/.laravel-starter.tmp
    composer create-project -q --prefer-dist laravel/laravel /root/projects/.laravel-starter.tmp
    mv /root/projects/.laravel-starter.tmp /root/projects/laravel-starter
fi

reset_credentials
set_credential Laravel "$(cd /root/projects/laravel-starter && php artisan --version | sed 's/Laravel Framework //')"
set_credential "Run Laravel" "cd ~/projects/laravel-starter && php artisan serve --host 0.0.0.0 --port 8001"
set_credential "New project" "laravel new myapp"
set_credential "Open" "http://<this device's IP>:8001 from any browser on the Wi-Fi (works from Code Studio's terminal too)"
