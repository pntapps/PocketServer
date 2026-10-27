#!/bin/bash
# Dev helper: runs a command inside PocketServer's Ubuntu on a connected device, without the app UI.
# Uses run-as (the app's own uid) + the app's proot, so several callers can run in parallel.
# Like the app, it pins work to the efficiency cores at low priority. The command runs in an
# interactive bash, so PATH and environment match Code Studio's terminal.
# HISTFILE=/dev/null: test commands (often with throwaway passwords) stay out of the user's history.
# Usage: tools/ps-exec.sh 'shell command'
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
PKG=com.pntapps.pocketserver
LIB="$($ADB shell pm dump $PKG | grep -m1 -o 'legacyNativeLibraryDir=[^ ]*' | sed 's/^legacyNativeLibraryDir=//' | tr -d '\r')/arm64"
ID="psx.$$.$RANDOM"
# Ship the command as a file: no quoting through adb, run-as and proot.
printf '%s\n' "$*" | $ADB exec-in run-as $PKG sh -c "cat > files/ubuntu/tmp/$ID"
$ADB shell run-as $PKG sh -c "'F=/data/data/$PKG/files; \
LD_LIBRARY_PATH=$LIB PROOT_LOADER=$LIB/libproot-loader.so PROOT_TMP_DIR=\$F/proot-tmp PROOT_L2S_DIR=\$F/ubuntu/.l2s \
$LIB/libproot.so --kill-on-exit --link2symlink -0 -L --sysvipc -r \$F/ubuntu \
 -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random -b /proc/self/fd:/dev/fd \
 -b \$F/ubuntu/tmp:/dev/shm -b \$F/shared:/shared -w /root \
 /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TERM=xterm-256color TMPDIR=/tmp HISTFILE=/dev/null \
 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
 /usr/bin/taskset -c 0-3 /usr/bin/nice -n 10 /bin/bash -i /tmp/$ID 2>&1; rc=\$?; rm -f \$F/ubuntu/tmp/$ID; exit \$rc'" \
  | grep --line-buffered -v -e '^groups: cannot find name for group ID' -e '^bash: cannot set terminal process group' -e '^bash: no job control'
exit "${PIPESTATUS[0]}"
