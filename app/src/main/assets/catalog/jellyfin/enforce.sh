#!/bin/bash
# Turns off video/audio transcoding for every Jellyfin user (new users get Jellyfin's defaults,
# so this runs on each start). Remuxing (container change, no re-encode) stays allowed.
set -euo pipefail
URL=http://127.0.0.1:8096
AUTH="MediaBrowser Token=\"${APIKEY:?}\""

curl -fsS -H "Authorization: $AUTH" "$URL/Users" | jq -c '.[]' | while read -r user; do
    id=$(jq -r .Id <<<"$user")
    policy=$(jq -c '.Policy
        | .EnableVideoPlaybackTranscoding = false
        | .EnableAudioPlaybackTranscoding = false
        | .EnablePlaybackRemuxing = true
        | .EnableMediaConversion = false' <<<"$user")
    if [ "$policy" != "$(jq -c .Policy <<<"$user")" ]; then
        curl -fsS -X POST -H "Authorization: $AUTH" -H 'Content-Type: application/json' \
            "$URL/Users/$id/Policy" -d "$policy"
        echo "Transcoding disabled for $(jq -r .Name <<<"$user")"
    fi
done
