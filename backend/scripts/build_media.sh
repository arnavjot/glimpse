#!/bin/sh
# Builds the clip library once (run by the "media" compose service). Real videos in
# media-src/ are converted with ingest_videos.sh; with none there, synthetic demo clips
# are generated with make_media.sh. Sponsored creatives come from make_ads.sh. Each
# part is skipped when its manifest exists: delete it to rebuild that part.
set -eu

if [ -f media/manifest.json ] && [ -f media/sponsored.json ]; then
  echo "clips already built (delete media/manifest.json or media/sponsored.json to rebuild)"
  exit 0
fi
apk add --no-cache ffmpeg font-dejavu >/dev/null

[ -f media/sponsored.json ] || sh scripts/make_ads.sh media
[ -f media/manifest.json ] && exit 0

if [ -n "$(find media-src -maxdepth 1 -type f \( -iname '*.mp4' -o -iname '*.mov' -o -iname '*.webm' -o -iname '*.mkv' \) 2>/dev/null | head -n 1)" ]; then
  sh scripts/ingest_videos.sh media-src media 15
else
  sh scripts/make_media.sh media 24 12
fi
