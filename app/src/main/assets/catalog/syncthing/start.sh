#!/bin/bash
export STNOUPGRADE=1 STNORESTART=1 STNODEFAULTFOLDER=1
exec syncthing serve --home=/opt/pocketserver/apps/syncthing/home --no-browser
