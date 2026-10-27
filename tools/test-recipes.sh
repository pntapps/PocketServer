#!/bin/bash
# Dev harness: pushes the catalog recipes into the app's Ubuntu on a connected device and runs
# install -> start -> HTTP check for each app, via the temporary spike screen (MainActivity).
# Usage: tools/test-recipes.sh [app-id:port[:https] ...]   (default: filebrowser:8080 syncthing:8384)
# Recipes without start.sh (toolchains, port 0) are only installed. If smoke() below has a check for an
# app, it runs right after the port answers (or after install for toolchains) while the server is up.
# SKIP_INSTALL=1 reuses the existing install; HOLD=<secs> keeps servers up after the checks (for
# probing them from this PC).
# Needs a debug build installed (run-as) and ANDROID_SERIAL set when several devices are attached.
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
PKG=com.pntapps.pocketserver
CATALOG="$(cd "$(dirname "$0")/../app/src/main/assets/catalog" && pwd)"
APPS=("$@"); [ ${#APPS[@]} -eq 0 ] && APPS=(filebrowser:8080 syncthing:8384)

# Copies one host file into the rootfs (run-as gives us the app's uid, so no root needed).
push() {
    $ADB exec-in run-as $PKG sh -c "mkdir -p \"\$(dirname files/ubuntu$2)\" && cat > files/ubuntu$2 && chmod 755 files/ubuntu$2" < <(tr -d '\r' < "$1")
}

# Runs a script inside Ubuntu through the spike screen and prints its output; returns proot's exit code.
run() {
    $ADB logcat -c
    $ADB shell am force-stop $PKG
    $ADB shell "am start -n $PKG/.DebugActivity --es script '$1'" >/dev/null
    for _ in $(seq 1 400); do
        sleep 3
        $ADB logcat -d -s PSPIKE:I | grep -qE "exit=|ERROR" && break
    done
    local out; out=$($ADB logcat -d -s PSPIKE:I | sed 's/.*PSPIKE *: //')
    echo "$out" | grep -E '^\| |^exit=|^ERROR' | tail -"${2:-15}"
    echo "$out" | grep -q '^exit=0'
}

# Per-app smoke checks, run inside Ubuntu (bash, APP_DIR set, lib.sh sourced). Print evidence; fail on error.
smoke() {
    case $1 in
    devtools) cat <<'EOF'
set -e; source /etc/profile.d/pocketserver-dev.sh
echo "node $(node -v) npm $(npm -v)"; php -v | head -1; composer -V --no-ansi 2>/dev/null | head -1
python3 -V; pip3 -V | cut -d' ' -f1-2; git --version; mongosh --version
node -e 'require("http").createServer((q,r)=>r.end("node-ok")).listen(18080,()=>require("http").get("http://127.0.0.1:18080",s=>s.on("data",d=>{console.log("http:",String(d));process.exit(0)})))'
python3 -m venv /tmp/venv-test && /tmp/venv-test/bin/python -c 'import sys; print("venv ok", sys.prefix)'; rm -rf /tmp/venv-test
php -r 'echo "php exts: ", implode(",", array_intersect(["pdo_mysql","mbstring","xml","curl","zip","gd","intl","pdo_sqlite","bcmath","Zend OPcache"], get_loaded_extensions())), "
";'
du -sh /usr/local/lib/node_modules /usr/local/bin/node /usr/bin/mongosh 2>/dev/null | tr '
' ' '; echo
EOF
    ;;
    mariadb) cat <<'EOF'
set -e; P=$(sed -n 's/^Password=//p' $APP_DIR/credentials)
mariadb -h127.0.0.1 -udev -p"$P" -e 'select version(); create table dev.t(id int primary key, v varchar(9)); insert into dev.t values(1,"hello"); select * from dev.t;'
mariadb -h127.0.0.1 -udev -pwrong -e 'select 1' 2>&1 | head -1 || true
grep -E '^(Host|User)=' $APP_DIR/credentials
EOF
    ;;
    mongodb) cat <<'EOF'
set -e
U=$(grep '^Connection string=' /opt/pocketserver/apps/mongodb/credentials | cut -d= -f2-)
mongosh --quiet "$U" --eval 'db.getSiblingDB("dev").t.insertOne({hello:"world"}); printjson(db.getSiblingDB("dev").t.find({},{_id:0}).toArray()); print("server", db.version())'
echo "without password (must be refused):"; mongosh --quiet mongodb://127.0.0.1:27017/dev --eval 'db.t.countDocuments()' 2>&1 | grep -o 'requires authentication' | head -1 || true
EOF
    ;;
    phpsites) cat <<'EOF'
set -e; MD=/opt/pocketserver/apps/mariadb
if [ -f $MD/my.cnf ]; then (APP_ID=mariadb bash /opt/pocketserver/recipes/mariadb/start.sh >/tmp/mariadb.log 2>&1 &); wait_port 3306 60; fi
P=$(sed -n 's/^Password=//p' $MD/credentials 2>/dev/null)
cat > /root/www/dbtest.php <<PHP
<?php \$db = new PDO('mysql:host=127.0.0.1;dbname=dev', 'dev', '$P');
echo 'PDO->MariaDB ', \$db->query('select version()')->fetchColumn(), ' opcache=', (int)opcache_get_status(false)['opcache_enabled'], "
";
PHP
curl -fsS http://127.0.0.1:8088/ | grep -o 'It works!\|PHP [0-9.]* ([a-z-]*)\|OPcache: [a-z]*' | tr '
' ' '; echo
curl -fsS http://127.0.0.1:8088/dbtest.php
curl -fsS -o /dev/null -w 'adminer http=%{http_code} bytes=%{size_download}
' http://127.0.0.1:8088/adminer/
curl -fsS http://127.0.0.1:8088/adminer/ | grep -o '<title>[^<]*' | head -1
rm -f /root/www/dbtest.php
echo "fpm workers busy: $(pgrep -c -f 'php-fpm: pool') ; waiting 15s for ondemand idle timeout"; sleep 15
echo "fpm workers after idle: $(pgrep -c -f 'php-fpm: pool' || true)"
ps -eo rss=,args= | grep -E 'php-fpm|nginx|mariadbd' | grep -v grep
EOF
    ;;
    codestudio) cat <<'EOF'
set -e; P=$(sed -n 's/^Password=//p' $APP_DIR/credentials); IP=$(hostname -I | awk '{print $1}')
curl -fsS -o /dev/null -w 'local http 8091 /login: %{http_code}
' http://127.0.0.1:8091/login
curl -fsSk -o /dev/null -w "lan https $IP:8090 /login: %{http_code}
" https://$IP:8090/login
curl -fsS --cacert /shared/PocketServer-CA.crt -o /dev/null -w 'https verified with CA: %{http_code}
' https://127.0.0.1:8090/login
curl -sk -c /tmp/cs.cookies -o /dev/null -w 'login POST: %{http_code} -> %{redirect_url}
' -d "password=$P" https://127.0.0.1:8090/login
curl -sk -b /tmp/cs.cookies -o /dev/null -w 'workbench GET /: %{http_code}
' https://127.0.0.1:8090/
curl -sk -b /tmp/cs.cookies -o /dev/null -w 'websocket upgrade via nginx: %{http_code}
' --max-time 4 --http1.1   -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='   "https://127.0.0.1:8090/?reconnectionToken=abc&reconnection=false&skipWebSocketFrames=false" || true
(exec 3<>/dev/tcp/$IP/8092) 2>/dev/null && echo "8092 reachable on LAN IP (BAD)" || echo "8092 not reachable on LAN IP (good)"
ps -eo rss=,args= | grep -E 'code-server|nginx|node' | grep -v grep | cut -c1-120
EOF
    ;;
    filebrowser) cat <<'EOF'
set -e; P=$(sed -n 's/^Password=//p' $APP_DIR/credentials); B=http://127.0.0.1:8080
T=$(curl -fsS -X POST -H "X-Password: $P" "$B/api/auth/login?username=admin"); echo "login: token ${#T} chars"
curl -s -o /dev/null -w 'wrong password: %{http_code} (want 401)
' -X POST -H 'X-Password: admin' "$B/api/auth/login?username=admin"
curl -s -o /dev/null -w 'listing without login: %{http_code} (want 401)
' "$B/api/resources?source=shared&path=/"
head -c 200000 /dev/urandom > /tmp/qa.bin; S1=$(sha256sum < /tmp/qa.bin | cut -c1-16)
curl -s -o /dev/null -w 'upload: %{http_code}
' -X POST -H "Authorization: Bearer $T" --data-binary @/tmp/qa.bin "$B/api/resources?source=shared&path=/qa-upload.bin"
S2=$(curl -fsS -H "Authorization: Bearer $T" "$B/api/resources/download?source=shared&file=/qa-upload.bin" | sha256sum | cut -c1-16 || true)
echo "download matches: $([ "$S1" = "$S2" ] && echo yes || echo "no ($S1 vs $S2)")"
echo "webdav with the login password: $(curl -s -o /dev/null -w '%{http_code}' -X PROPFIND -H 'Depth: 1' -u "admin:$P" $B/dav/shared/) (401: WebDAV needs an API token)"
curl -s -o /dev/null -w 'delete: %{http_code}
' -X DELETE -H "Authorization: Bearer $T" "$B/api/resources?source=shared&path=/qa-upload.bin"
rm -f /tmp/qa.bin; ls /shared/qa-upload.bin 2>/dev/null && echo "LEFTOVER" || echo "cleaned up"
for p in $(pgrep -f filebrowser-quantum); do echo "pid $p rss=$(( $(grep VmRSS /proc/$p/status | tr -dc 0-9)/1024 ))MB $(taskset -p $p)"; done
EOF
    ;;
    wordpress) cat <<'EOF'
set -e; P=$(sed -n 's/^Admin password=//p' $APP_DIR/credentials); IP=$(hostname -I | awk '{print $1}')
curl -fsS http://127.0.0.1:8089/ | grep -o '<title>[^<]*' | head -1
curl -fsS --cacert /shared/PocketServer-CA.crt -o /dev/null -w "lan https $IP:8448 verified with CA: %{http_code}
" https://$IP:8448/
echo "links on LAN page point at: $(curl -fsSk https://$IP:8448/ | grep -o "https://$IP:8448/[a-z-]*" | head -1)"
curl -sk -c /tmp/wp.cookies -b 'wordpress_test_cookie=WP%20Cookie%20check' -o /dev/null -w 'login POST: %{http_code} -> %{redirect_url}
' --data-urlencode log=admin --data-urlencode "pwd=$P" -d 'testcookie=1' https://$IP:8448/wp-login.php
curl -sk -b /tmp/wp.cookies https://$IP:8448/wp-admin/ | grep -o '<title>[^<]*' | head -1
curl -s -o /dev/null -w 'pretty link /hello-world/: %{http_code}
' http://127.0.0.1:8089/hello-world/
curl -s -o /dev/null -w 'database file: %{http_code} (want 403)
' http://127.0.0.1:8089/wp-content/database/.ht.sqlite
echo '<?php echo "RAN";' > $APP_DIR/site/wp-content/uploads/t.php
curl -s -o /dev/null -w 'php in uploads: %{http_code} (want 403)
' http://127.0.0.1:8089/wp-content/uploads/t.php; rm -f $APP_DIR/site/wp-content/uploads/t.php
curl -s -o /dev/null -w 'xmlrpc: %{http_code} (want 403)
' http://127.0.0.1:8089/xmlrpc.php
export PHP_INI_SCAN_DIR=":$APP_DIR/php.d"
echo "cron update hooks left: $(php8.3 /usr/local/bin/wp --allow-root --path=$APP_DIR/site cron event list --fields=hook --format=csv 2>/dev/null | grep -cE 'wp_version_check|wp_update_' || true)"
echo "db: $(php8.3 /usr/local/bin/wp --allow-root --path=$APP_DIR/site eval 'echo DB_ENGINE;' 2>/dev/null) $(du -h $APP_DIR/site/wp-content/database/.ht.sqlite | cut -f1)"
ps -eo rss=,args= | grep -E 'php-fpm|nginx' | grep -v grep | cut -c1-100
EOF
    ;;
    esac
}

$ADB shell pm path $PKG >/dev/null || { echo "$PKG not installed"; exit 1; }
# First launch installs Ubuntu if needed.
run 'echo ROOTFS_OK' 3 || { echo "rootfs not ready"; exit 1; }
push "$CATALOG/lib.sh" /opt/pocketserver/lib.sh

declare -A RESULT
for entry in "${APPS[@]}"; do
    IFS=: read -r id port scheme <<<"$entry"; scheme=${scheme:-http}
    echo; echo "===== $id (port $port)"
    for f in "$CATALOG/$id"/*.sh; do push "$f" "/opt/pocketserver/recipes/$id/$(basename "$f")"; done
    R=/opt/pocketserver/recipes/$id
    SM=/opt/pocketserver/smoke-$id.sh
    push <(echo 'source /opt/pocketserver/lib.sh'; smoke "$id"; echo 'echo "--- RSS (KB) of running processes:"; ps -eo rss=,args= | grep -vE " (ps|grep|sleep|bash|cut|tail)( |$)|smoke-" | cut -c1-110'
           [ -n "${HOLD:-}" ] && echo "echo holding; sleep $HOLD") $SM
    if [ -n "${SKIP_INSTALL:-}" ]; then :; elif ! run "S=\$(date +%s); APP_ID=$id bash $R/install.sh 2>&1 | tail -25; rc=\${PIPESTATUS[0]}; echo install_rc=\$rc secs=\$(( \$(date +%s)-S )); exit \$rc" 30; then
        RESULT[$id]="install FAILED"; continue
    fi
    if [ ! -f "$CATALOG/$id/start.sh" ]; then
        # Toolchain: nothing to start, just check the tools.
        if run "export APP_ID=$id; source /opt/pocketserver/lib.sh; set +e; bash $SM 2>&1; rc=\$?; echo smoke_rc=\$rc; exit \$rc" 40; then
            RESULT[$id]="OK"; else RESULT[$id]="smoke FAILED"; fi
        continue
    fi
    # start in background, wait for the port, then ask for the web UI and run the smoke check.
    if run "export APP_ID=$id; bash $R/start.sh >/tmp/$id.log 2>&1 & source /opt/pocketserver/lib.sh; S=\$(date +%s); if wait_port $port 120; then echo up_after=\$(( \$(date +%s)-S ))s; curl -skL -o /dev/null -w \"http=%{http_code}\n\" $scheme://127.0.0.1:$port/ || true; set +e; bash $SM 2>&1; rc=\$?; echo smoke_rc=\$rc; tail -5 /tmp/$id.log; exit \$rc; else tail -20 /tmp/$id.log; exit 1; fi" 60; then
        RESULT[$id]="OK"
    else
        RESULT[$id]="start FAILED"
    fi
done

echo; echo "===== summary"
for entry in "${APPS[@]}"; do id=${entry%%:*}; echo "$id: ${RESULT[$id]:-?}"; done

# Leave the real app in front, not the debug test screen.
$ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1
