#!/bin/bash
source /opt/pocketserver/lib.sh
VENV=/opt/pyweb

# mysqlclient (Django's MySQL/MariaDB driver) compiles against MariaDB's client library.
apt_install python3-venv python3-dev pkg-config libmariadb-dev

step "Creating Python environment"
# Ubuntu forbids pip installs into the system Python (PEP 668); one shared venv first on PATH
# makes `pip install` just work for beginners, while per-project venvs keep working as usual.
rm -rf "$VENV"
python3 -m venv "$VENV"
"$VENV/bin/pip" install -q --upgrade pip

step "Installing Django and Flask"
"$VENV/bin/pip" install -q django flask gunicorn python-dotenv pymongo PyMySQL
step "Building the MySQL driver"
"$VENV/bin/pip" install -q mysqlclient

# Every shell gets the venv: login shells via profile.d, and the non-login terminals of Code Studio
# via bash.bashrc (which also picks up the toolchain's settings in profile.d).
cat > /etc/profile.d/pocketserver-pyweb.sh <<'SH'
case ":$PATH:" in *":/opt/pyweb/bin:"*) ;; *) export PATH="/opt/pyweb/bin:$PATH" ;; esac
SH
if ! grep -q pocketserver-profile /etc/bash.bashrc; then
    cat >> /etc/bash.bashrc <<'SH'
# pocketserver-profile: same environment in every terminal (Shell tab and Code Studio)
for f in /etc/profile.d/pocketserver-*.sh; do [ -r "$f" ] && . "$f"; done
SH
fi

step "Creating starter projects"
mkdir -p /root/projects
# Starters are only created when missing: never overwrite the user's work.
# Built under a temporary name and moved into place only when complete (see react/install.sh).
if [ ! -e /root/projects/django-starter ]; then
    D=/root/projects/.django-starter.tmp
    rm -rf "$D" && mkdir -p "$D"
    "$VENV/bin/django-admin" startproject mysite "$D"
    # Reachable from other devices on the Wi-Fi during development.
    sed -i "s/^ALLOWED_HOSTS = \[\]/ALLOWED_HOSTS = ['*']/" "$D/mysite/settings.py"
    # STUDIO=1 makes the starter work through Code Studio's /absproxy/8000/ (logins, forms, static files).
    cat /opt/pocketserver/recipes/pyweb/studio_settings.py >> "$D/mysite/settings.py"
    cat /opt/pocketserver/recipes/pyweb/studio_urls.py >> "$D/mysite/urls.py"
    cp /opt/pocketserver/recipes/pyweb/studio_wsgi.py "$D/mysite/studio_wsgi.py"
    (cd "$D" && "$VENV/bin/python" manage.py migrate -v 0)
    mv "$D" /root/projects/django-starter
fi
if [ ! -e /root/projects/flask-starter ]; then
    mkdir -p /root/projects/flask-starter
    cat > /root/projects/flask-starter/app.py <<'PY'
from flask import Flask

app = Flask(__name__)


@app.get("/")
def hello():
    return "<h1>Hello from Flask on PocketServer</h1>"
PY
fi

reset_credentials
set_credential Django "$("$VENV/bin/python" -m django --version)"
set_credential Flask "$("$VENV/bin/python" -c 'import importlib.metadata as m; print(m.version("flask"))')"
set_credential "Run Django" "cd ~/projects/django-starter && python manage.py runserver 0.0.0.0:8000"
set_credential "Run in Code Studio" "cd ~/projects/django-starter && STUDIO=1 python manage.py runserver 0.0.0.0:8000, then open /absproxy/8000/"
set_credential "Run Flask" "cd ~/projects/flask-starter && flask run --host 0.0.0.0 --port 5000"
