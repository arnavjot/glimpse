#!/bin/sh
# Generate the demo video library: vertical 720x1280 clips as HLS with 2-second
# segments (a keyframe every 2 s, so playback can start after one segment),
# a poster per clip, and media/manifest.json for the server to seed from.
# Everything is synthesised from ffmpeg's built-in test sources: no third-party
# footage, nothing to license.
#
#   sh scripts/make_media.sh [out_dir] [count] [seconds]
set -eu

OUT="${1:-media}"
COUNT="${2:-24}"
SECONDS_PER_CLIP="${3:-12}"
mkdir -p "$OUT"

source_for() {
  case $(( $1 % 8 )) in
    0) echo "testsrc2=size=720x1280:rate=30|Signal" ;;
    1) echo "mandelbrot=size=720x1280:rate=30|Fractal Dive" ;;
    2) echo "life=size=720x1280:rate=30:mold=10:ratio=0.1:death_color=#1b1f3b:life_color=#f7b32b|Life" ;;
    3) echo "cellauto=size=720x1280:rate=30:rule=110|Rule 110" ;;
    4) echo "gradients=size=720x1280:rate=30:speed=0.02|Gradients" ;;
    5) echo "smptehdbars=size=720x1280:rate=30|Bars" ;;
    6) echo "rgbtestsrc=size=720x1280:rate=30|RGB" ;;
    7) echo "zoneplate=size=720x1280:rate=30:ku=64:kv=64:kt2=2|Zone Plate" ;;
  esac
}
creator_for() {
  case $(( $1 % 4 )) in
    0) echo "arnav.studio" ;; 1) echo "pixelfield" ;; 2) echo "lumenworks" ;; 3) echo "framecraft" ;;
  esac
}

MANIFEST="$OUT/manifest.json"
echo "[" > "$MANIFEST"
i=0
while [ "$i" -lt "$COUNT" ]; do
  spec=$(source_for "$i")
  src=${spec%%|*}
  name=${spec#*|}
  id=$(printf "v%03d" $((i + 1)))
  title="$name #$(( i / 8 + 1 ))"
  hue=$(( (i * 47) % 360 ))
  dir="$OUT/$id"
  mkdir -p "$dir"
  vf="hue=h=$hue,drawtext=text='$title':fontcolor=white:fontsize=56:box=1:boxcolor=black@0.45:boxborderw=18:x=(w-text_w)/2:y=h*0.72"

  ffmpeg -v error -y -f lavfi -i "$src" -t "$SECONDS_PER_CLIP" -vf "$vf" \
    -c:v libx264 -preset veryfast -profile:v main -b:v 1500k -maxrate 1800k -bufsize 3000k \
    -g 60 -keyint_min 60 -sc_threshold 0 -pix_fmt yuv420p \
    -f hls -hls_time 2 -hls_playlist_type vod \
    -hls_segment_filename "$dir/seg_%03d.ts" "$dir/index.m3u8"
  ffmpeg -v error -y -f lavfi -i "$src" -vf "$vf" -frames:v 1 -q:v 4 "$dir/poster.jpg"

  [ "$i" -gt 0 ] && echo "," >> "$MANIFEST"
  printf '  {"id": "%s", "title": "%s", "creator": "%s", "duration_ms": %d, "width": 720, "height": 1280, "hls_path": "%s/index.m3u8", "poster_path": "%s/poster.jpg"}' \
    "$id" "$title" "$(creator_for "$i")" $(( SECONDS_PER_CLIP * 1000 )) "$id" "$id" >> "$MANIFEST"
  echo "  $id  $title"
  i=$((i + 1))
done
printf '\n]\n' >> "$MANIFEST"
echo "wrote $COUNT clips to $OUT/"
