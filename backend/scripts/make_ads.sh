#!/bin/sh
# Sponsored creatives for the demo: house ads (Glimpse promoting itself) rendered with
# ffmpeg in the design system's colours (bg #0B0B0F, Volt #C6F432). Same encoding as
# the clips: vertical 720x1280 HLS, 2-second segments, a poster. Writes
# <out_dir>/sponsored.json for the server to seed sponsored_clips from.
#
#   sh scripts/make_ads.sh [out_dir] [cta_url]
set -eu

OUT="${1:-media}"
CTA="${2:-https://github.com/arnavjot}"
SECONDS_PER_AD=10
mkdir -p "$OUT"

# id|headline|subline
ADS="ad001|Glimpse|Swipe. Watch. Instantly.
ad002|Built with Media3|and Spring Boot. See the code.
ad003|Never miss a clip|Turn on notifications in Settings"

MANIFEST="$OUT/sponsored.json"
TMP="$MANIFEST.tmp"
echo "[" > "$TMP"
first=1
echo "$ADS" | while IFS='|' read -r id headline subline; do
  dir="$OUT/$id"
  rm -rf "$dir" && mkdir -p "$dir"
  src="gradients=size=720x1280:rate=30:speed=0.01:c0=0x0B0B0F:c1=0x16161C:c2=0x2E3A0A:c3=0x0B0B0F"
  vf="drawtext=text='$headline':fontcolor=0xC6F432:fontsize=72:x=(w-text_w)/2:y=h*0.42,\
drawtext=text='$subline':fontcolor=white:fontsize=34:x=(w-text_w)/2:y=h*0.42+110"

  ffmpeg -nostdin -v error -y -f lavfi -i "$src" -t "$SECONDS_PER_AD" -vf "$vf" -an \
    -c:v libx264 -preset veryfast -profile:v main -b:v 1200k -maxrate 1500k -bufsize 2500k \
    -g 60 -keyint_min 60 -sc_threshold 0 -pix_fmt yuv420p \
    -f hls -hls_time 2 -hls_playlist_type vod \
    -hls_segment_filename "$dir/seg_%03d.ts" "$dir/index.m3u8"
  ffmpeg -nostdin -v error -y -f lavfi -i "$src" -vf "$vf" -frames:v 1 -q:v 3 "$dir/poster.jpg"

  [ "$first" -eq 1 ] || echo "," >> "$TMP"
  first=0
  printf '  {"id": "%s", "title": "%s", "advertiser": "Glimpse", "duration_ms": %d, "width": 720, "height": 1280, "hls_path": "%s/index.m3u8", "poster_path": "%s/poster.jpg", "cta_url": "%s"}' \
    "$id" "$headline" $((SECONDS_PER_AD * 1000)) "$id" "$id" "$CTA" >> "$TMP"
  echo "  $id  $headline"
done
printf '\n]\n' >> "$TMP"
mv "$TMP" "$MANIFEST"
echo "wrote sponsored creatives to $OUT/"
