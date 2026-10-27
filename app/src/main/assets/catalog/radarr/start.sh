#!/bin/bash
APP_DIR=/opt/pocketserver/apps/radarr
mkdir -p /shared/Movies /shared/Downloads
# Workstation GC, 256 MB heap cap and no dynamic PGO: less RAM, fewer GC threads, less JIT work.
export DOTNET_gcServer=0 DOTNET_GCHeapHardLimit=0x10000000 DOTNET_TieredPGO=0
exec /opt/radarr/bin/Radarr -nobrowser -data="$APP_DIR/data"
