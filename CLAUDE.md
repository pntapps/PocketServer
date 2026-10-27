# PocketServer

Android app (package `com.pntapps.pocketserver`, Kotlin + Jetpack Compose) that turns a phone or tablet into a
private home server: it runs Ubuntu 24.04 arm64 inside the app with **proot** (no root) and offers one-click
self-hosted apps grouped into packs, a terminal, and a sci-fi HUD UI. Open source (GPLv3), distributed via
GitHub Releases, **not** Google Play.

## Owner's hard rules (never trade these away)

1. **No root**, ever. Everything works on a stock device.
2. **Battery and heat come first.** Nothing runs until the user presses Start; with everything stopped the app
   does zero background work. Every app ships "battery-optimized": no transcoding (Jellyfin transcoding is
   disabled for every user), no telemetry, no self-updaters, no UPnP/relays, heavy jobs only while charging,
   small caches, logs capped. Servers run on the **efficiency cores** at low priority. The device must never
   overheat (see Thermal below). Prefer light apps; reject heavy ones (Stirling-PDF, Plex, Emby, Immich were
   rejected or declined for this reason; MongoDB 8 cannot run, see Gotchas).
3. **Local network only.** No remote-access features, tunnels or accounts. Databases are this-device-only by
   default; a per-app "Wi-Fi access" switch exposes them, always with a password.
4. **Clean uninstall:** all data lives in the app's private storage. Never write to shared storage.
5. **Keep receiving app updates:** targetSdk stays current (36). Do not use the old targetSdk 28 exec trick.

## Architecture

- `linux/` — `LinuxPaths` (canonical paths; must be `/data/data/...`, not `/data/user/0/...`, or link2symlink
  breaks), `Proot` (the ONLY place that knows how to launch Linux programs: proot + loader shipped as
  `jniLibs/arm64-v8a/libproot.so`/`libproot-loader.so`, exec'd from nativeLibraryDir), `RootfsInstaller`
  (downloads + verifies Ubuntu Base, writes `policy-rc.d` = exit 101 so packages never auto-start daemons),
  `FakeProc`.
- `server/AppManager` — owns the rootfs and every app process: install queue (one install at a time,
  `installLock`; installs `requires` first), start/stop (Stop sends SIGTERM to the guest processes first, then
  escalates), readiness (port open + optional HTTP health path), Wi-Fi access flag, credentials, logs.
  `cool()` wraps every guest command in `taskset -c <efficiency cores> nice -n 10`.
- `server/ServerService` — foreground service (type specialUse) that exists only while an app runs; holds the
  partial wake lock + Wi-Fi lock. `PowerPolicy` (on-demand auto-stop, idle stop, low-battery stop, home-server
  "only while charging" pause/resume, charging-only Jellyfin scans), `Settings` (DataStore).
- `server/ThermalGuard` — battery temp + Android thermal status (event-driven). Warm: pause heavy work and force
  Reduced motion. Hot (battery >= limit, default 40 C, max 42 C, or OS status MODERATE+): stop servers + installs,
  block starts until cooled 3 C below. Critical (45 C / SEVERE): also close terminal sessions. The
  `getThermalHeadroom` forecast only ever means Warm (it follows skin temperature and false-alarms).
- `catalog/Catalog.kt` — every app (`CatalogApp`: id, port, https, localPort, group, requires, service,
  localOnly, lanToggle, healthPath, sizes, card notes). The id matches `app/src/main/assets/catalog/<id>/`.
- `terminal/` — Termux terminal-view sessions running bash in proot. `backup/` — tar.gz backup/restore via SAF.
  `update/` — GitHub Releases updater (hidden while `BuildConfig.UPDATE_REPO` is empty).
- `ui/` — HUD theme (`theme/Hud.kt`), components (`HudComponents`, `HudFx` effects, `AppHolograms`,
  `BootSequence`), screens (Dashboard, Apps, Shell, Logs, System). Motion: `ui/motion/Motion.kt` (Full /
  Reduced; every looping animation must check `LocalMotion.current.animated`). The activity requests 60 Hz.

## Recipes (`app/src/main/assets/catalog/<id>/`)

- `install.sh` (+ `start.sh` for services). Both `source /opt/pocketserver/lib.sh` helpers: `step` (progress
  shown in the app as `::step:: text`), `apt_install` (waits for the apt lock), `fetch`, `gen_password`,
  `reset_credentials`/`set_credential` (key=value lines shown on the card; keys with password/user/connection or
  starting with "Run" get a copy icon), `wait_port`, `stop_pid`, `supervise`, `ensure_ca`/`issue_server_cert`
  (one shared local CA, `/shared/PocketServer-CA.crt`).
- `APP_ID` is set; `APP_DIR=/opt/pocketserver/apps/<id>`; the app writes `.installed` there on success.
  `start.sh` must stay in the **foreground** (exec the server); `PS_LAN_IPS` is passed at start.
- Never start daemons in install.sh except temporarily for first-time setup (then `stop_pid`).
- **Every password, token, secret and key is generated on the device at install time** (`gen_password`, the app's
  own generator), unique per install, never written into a recipe. `RecipeSecretsTest` fails the build otherwise.
- HTTPS apps (Vaultwarden, BentoPDF, Code Studio, Nextcloud, WordPress): nginx with HTTPS on a LAN port + plain HTTP on
  127.0.0.1 (`localPort`) so this device's browser needs no certificate.
- **Scripts must be LF.** Files written on Windows can come out CRLF; check with Python
  (`b'\r' in open(f,'rb').read()`), not with bash `$'\r'` (the agent shell mangles it). The app also strips CR
  when copying recipes, and `.gitattributes` forces LF.

## Packs and ports

Ports in use (keep them unique): FileBrowser 8080, Syncthing 8384, Jellyfin 8096, Vaultwarden 8443, BentoPDF
8444 (+127.0.0.1:8085), Code Studio 8090 (+127.0.0.1:8091, code-server itself 127.0.0.1:8092), PHP Sites 8088,
MariaDB 3306, MongoDB 27017, Nextcloud 8446 (+127.0.0.1:8087), WordPress 8448 (+127.0.0.1:8089), Gitea 3300 (+SSH 2222), Memos 5230,
qBittorrent 8180 (peers 6881), Prowlarr 9696, Sonarr 8989, Radarr 7878, Navidrome 4533, Audiobookshelf 13378,
Calibre-Web 8083. Dev servers by convention: Django 8000, Laravel 8001, Flask 5000, Vite 5173.

Packs (`CatalogApp.group`): Media (Jellyfin, Navidrome, Audiobookshelf, Calibre-Web), Files & Cloud
(FileBrowser, Syncthing, Nextcloud), Downloads (qBittorrent, Prowlarr, Sonarr, Radarr), Productivity
(Vaultwarden, BentoPDF, Memos), Web Dev (Dev Toolchain, Django & Flask, Laravel, React, Code Studio, PHP Sites,
WordPress, MariaDB, MongoDB, Gitea). Shared media folders live in `/shared` (Movies, Shows, Music, Books, Audiobooks,
Podcasts, Downloads).

## Build, install, test

- JDK + Android SDK from Android Studio; `./gradlew :app:assembleDebug`, unit tests `./gradlew :app:testDebugUnitTest`.
- Test device used so far: Galaxy Tab S8 (SM-X700, Android 16, cpu0-3 are the efficiency cores) over wireless
  adb. On a new PC: enable Wireless debugging on the device, `adb pair`/`adb connect`, then find the serial with
  `adb devices` (or `adb mdns services`) and `export ANDROID_SERIAL=...`. With several devices attached always
  set ANDROID_SERIAL.
- `tools/test-recipes.sh app:port[:https] ...` — pushes recipes into the device's Ubuntu and runs install ->
  start -> HTTP check through the debug-only `DebugActivity` (src/debug). It force-stops the app, so never run
  it while something else uses the device; it reopens MainActivity at the end.
- `tools/ps-exec.sh 'commands'` — runs commands inside the device's Ubuntu via `run-as` + the app's proot,
  without the UI; parallel-safe; same environment as Code Studio's terminal; pinned to cores 0-3. Servers you
  start die when the call returns (proot --kill-on-exit), so run long servers in a background call bounded by
  `timeout`.
- After changing recipes, the app copies them into Ubuntu on every install/start (`syncRecipes`).

## Gotchas learned the hard way

- Termux proot **.98** is pinned in jniLibs (binary-patched `libtalloc.so.2` -> `libtalloc.so`); .99 segfaults
  on hard links (link2symlink regression). Re-test hard links before upgrading proot.
- MongoDB **8** cannot run on Android (new tcmalloc needs a 48-bit address space; Android arm64 gives 39-bit).
  Use MongoDB **7.0** from the jammy repo (works on noble).
- Jellyfin: port opens before it is ready (wizard answers 503); readiness uses `/health`. It ignored SIGTERM
  under proot once: stops escalate to SIGKILL.
- Readiness checks to 127.0.0.1 need `res/xml/network_security_config.xml` (cleartext allowed for localhost only).
- Termux `TerminalView`: call `setTextSize` before `setTypeface` (NPE otherwise).
- Compose: don't put HUD effects inside `IntrinsicSize` rows (some are subcomposed -> crash); the dashboard
  computes row heights instead.
- Code Studio's terminal is a non-login shell: environment goes in `/etc/profile.d/pocketserver-*.sh` AND is
  sourced from `/etc/bash.bashrc`.
- Ubuntu blocks system-wide pip (PEP 668): Django & Flask live in the venv `/opt/pyweb`, first on PATH.
- `run-as` carries extra Android group ids Ubuntu has no names for (harmless `groups:` warnings). `taskset` on
  app processes from adb's run-as is denied by SELinux; the app itself may do it.
- Samsung's scheduler keeps changing the CPU affinity of busy processes (seen on code-server's Node: 0-4,6 /
  0-5 / 0-7 within seconds); `AppManager.repinCores()` (every PowerPolicy tick) pulls our processes back to the
  efficiency cores, but the ThermalGuard is the real heat safety net.
- Android apps run with umask 077: `lib.sh` and the terminal profile set `umask 022`, or project files come out
  mode 600.
- Previewing dev servers through Code Studio: code-server's `/proxy/<port>/` strips the prefix and rewrites
  redirects; `/absproxy/<port>/` passes paths unchanged. Vite needs `--base /absproxy/5173/` (`npm run
  dev:studio`); the Django starter has `STUDIO=1` mode (WSGI prefix wrapper `mysite/studio_wsgi.py`, explicit
  STATIC_URL, CSRF_TRUSTED_ORIGINS) for `/absproxy/8000/`; Laravel is opened directly on :8001.
- MariaDB user `dev` owns only `dev`, `dev_*` and `test_*` databases; root logs in only from the device.
- While the device is locked/dozing, inbound Wi-Fi traffic to the app uid is firewalled unless the app's
  foreground service runs; servers started via `tools/ps-exec.sh` are then unreachable from the PC (probe from
  inside instead). Real use is fine: ServerService exempts the servers.
- Long jobs (installs, Ubuntu setup, backup/restore) run under `AppManager.keepAlive`, which keeps
  ServerService in the foreground (wake lock, no Wi-Fi lock) so Android doesn't freeze/kill them.
- Security model (from the 2026-10-24 audit): local CA is name-constrained to local names/private IPs with
  keyUsage (old unconstrained CAs rotate automatically in `ensure_ca`); backups can be passphrase-encrypted
  (chunked AES-256-GCM, `backup/BackupCrypto.kt`) and include the CA; MongoDB `dev` has no user-admin rights,
  `admin` only from 127.0.0.1; MariaDB `dev` only `dev`/`dev_*`/`test_*`; Adminer follows MariaDB's Wi-Fi
  switch; Gitea requires sign-in to view; Vaultwarden sign-ups never open (first account by invite only); the
  debug-only DebugActivity requires the DUMP permission (adb shell has it).
- FileBrowser is **FileBrowser Quantum** (gtsteffaniak/filebrowser, pinned + sha256) since the original was archived
  on 2026-09-01: config in `apps/filebrowser/config.yaml` (mode 600; random admin password, JWT key and TOTP secret),
  previews/update checks/activity log off, GOMEMLIMIT=48MiB GOGC=50 (~70 MB RSS, ~0 idle CPU). API: login
  `POST /api/auth/login?username=` with `X-Password`, download `/api/resources/download?source=shared&file=`.
  WebDAV at `/dav/shared/` needs an API token (Settings > API tokens) as password, not the login password.
  Recipes download to `mktemp` paths, never fixed /tmp names.
- `fetch <url> <dest> [sha256]` verifies downloads; pin hashes for fixed versions.
- Server notification (`server/ServerNotifications.kt`): custom RemoteViews (`res/layout/notif_hud_*.xml`), colorized
  navy (allowed only for foreground services), Chronometer widgets tick in SystemUI (no app wake-ups); refreshed on
  state changes + the PowerPolicy minute tick. Expanded height is capped by Android (~252 dp): max 5 app rows.
- WordPress: SQLite (official sqlite-database-integration drop-in), WP_HOME follows the Host header (local names
  only), a mu-plugin rewrites stored local URLs (127.0.0.1:8089 / LAN:8448) to the visitor's address and refuses
  update/Site Health cron jobs via the `schedule_event` filter.
- Audiobookshelf 2.37 resets the server-wide `podcastEpisodeSchedule` to hourly at every start (not settable);
  per-podcast auto-download is off by default.
- Vaultwarden: SIGNUPS_ALLOWED is always false. The first account is invite-only: the card's "First account" field
  writes `owner-email`; start.sh (while 0 accounts) invites that address via the local admin API (random
  ADMIN_TOKEN only in that state; no SMTP needed, the invited email registers from the web vault) and reports
  `first-account` = needs-email | invited:<email> | done (event-driven via inotifywait). Tested: other emails get
  "Registration not allowed".
- Composer's global bin dir here is `/root/.composer/vendor/bin` (ask `composer global config bin-dir --absolute`).
- Time zone: Proot passes TZ. Android reports legacy names (Asia/Calcutta) that Ubuntu 24.04 lacks (tzdata-legacy):
  `Proot.guestTimeZone()` maps to the IANA name (API 34+) or a fixed-offset POSIX zone.
- `tools/ps-exec.sh` builds its own proot command: it does NOT see SD card binds or TZ; test those from the app
  (Shell tab, or servers started in the app).
- `tools/ps-exec.sh`: an `awk` that prints to stdout kills the rest of the output (exit 1); write to a file instead.
- SD card (`linux/SharedStorage.kt`): only the app's own card folder (getExternalFilesDirs), bound by proot over
  `/shared/<Folder>` for the media folders; config in `files/storage.conf` (card UUID + folders). Starts/installs are
  refused while that card is missing; ServerService stops servers on MEDIA_EJECT/UNMOUNTED. FAT/exFAT: no
  permissions/symlinks/hard links, so never databases or app data on the card. Test without a card:
  `adb shell sm set-virtual-disk true`, then `sm partition <disk> public`.
- AppManager process lifecycle: the exit watcher only touches its own run (`processes.remove(id, process)`); a
  "did not start in time" run is reported Failed only after it exited (`timedOut`); `maintenance` blocks starts,
  installs and resumes during backup/restore and SD moves.
- `tools/ps-exec.sh` occasionally loses its output (exit 1 with nothing printed) even though the commands ran;
  re-check state before retrying non-idempotent steps.

## Working style the owner expects

- Test everything on the real device and show evidence (screenshots, curl from another device on the Wi-Fi).
- For big batches, use parallel subagents with disjoint file ownership; only one thing may drive the device UI
  at a time (use `tools/ps-exec.sh` for parallel device work).
- Keep the UI HUD-styled (Iron Man / Avengers inspired, original artwork only, no Marvel marks) and fill wide
  tablet layouts (no big empty areas).
- Explain trade-offs plainly (battery, heat, storage) before adding heavy features.

## Status (end of session 2026-10-24, second session) — RESUME HERE

**How to continue:** open Claude Code in this folder and say "continue PocketServer from CLAUDE.md". Reconnect the
tablet first (see Build, install, test; the wireless-debugging port changes after a reboot: `adb mdns services`).
The latest debug build is installed on the Tab S8; nothing is running on it; display settings are back to normal.

**State: feature-complete and device-tested; NOT yet released.** All 46 unit tests pass; debug and release
(unsigned) builds succeed.

**Done and device-tested this session:** WordPress (one-click, SQLite, HTTPS 8448 / local 8089); HUD notification
(custom RemoteViews, live clocks, per-app rows, install progress); SD card storage for media folders (virtual SD
tested: move, play via Navidrome, card pulled = servers stopped + alert, start refused, move back); Vaultwarden
invite-only first account; FileBrowser replaced by FileBrowser Quantum v2.0.2 (original archived); phone layouts at
360/411 dp and font scale 1.3; time zone in Ubuntu; random secrets per install (+ RecipeSecretsTest); all fixes from
a 5-agent QA pass (AppManager stop/restart races, backup/restore maintenance lock, log cap, updater stops servers,
service start races, Laravel PATH, SSH client, Django studio welcome page, file modes). Earlier unverified items
(portrait layouts, backup encryption UI, background install, Vaultwarden sign-ups) are all verified.

**NEXT (what the owner wants to do next; offered, not started):**
1. `git init` + first commit, then a GitHub repo (source only, see business model below).
2. Release signing key: `keystore.properties` (git-ignored), same key forever; owner picks the password.
3. README (features, screenshots from the tablet, 1-minute demo video), license notices (GPLv3; proot GPLv2 source
   offer; third-party notices), trademark notice for the "PocketServer" name and icon.
4. Run the signed release build on a device (a spare phone, or the tablet after a backup: replacing the debug
   build wipes app data).
5. Donation link in the System tab (GitHub Sponsors / Ko-fi), no tracking.
6. Optional later: draft an NLnet / NGI Zero grant application; FUTO grant.

**Business model discussed (owner's direction, not final):** source code public on GitHub (GPLv3), the signed APK
sold separately (itch.io / Gumroad, pay-what-you-want with a minimum), plus donations and grants. Explained to the
owner: GPL lets anyone build/share it for free and F-Droid may build it; buyers pay for convenience + support;
protect the name/logo with a trademark notice. Updater: keep it off for itch.io builds (UPDATE_REPO empty) or point
it at GitHub Releases if the "paid first, free on GitHub 1-2 months later" model is chosen. No licence keys or
accounts (conflicts with the no-accounts rule). No ads, no telemetry. Not legal advice: suggest a lawyer for the
trademark once money is involved.

**Open / later**
- Test backup left on the tablet: /sdcard/Download/pocketserver-20261024.tar.gz.enc (passphrase in Claude memory, not in the repo).
- WordPress reuses the PHP Sites hologram (no own artwork yet).
- Release build not yet run on a device (needs the signing key; installing it over the debug build wipes data).
- Sonarr v4 has no setting to slow finished-download checks (Radarr is set to 15 min).
- BentoPDF loads some WASM engines/fonts from the internet (CDN) on first use of some tools.
- Code Studio drifts off the efficiency cores (Samsung scheduler); repinCores pulls it back each minute.
- Nice-to-have: charging-only scans for Navidrome/Audiobookshelf; a shared `ensure_up` helper in lib.sh (the
  *arr recipes duplicate a temporary-start helper); Jellyfin `EnableRemoteAccess=false` (needs a device test:
  Android may misreport LAN subnets); Nextcloud HSTS; unit tests for parseCpuList, IdleDetector and the
  install queue.
- Distribute only release builds (debug builds include DebugActivity).
- Gitea: a QA agent deleted the admin user's SSH key titled "pc" by mistake; the owner may need to re-add it.
- Chrome on the tablet still has the OLD FileBrowser password saved for 127.0.0.1:8080 (left untouched).
