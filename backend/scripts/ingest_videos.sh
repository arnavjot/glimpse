#!/bin/sh
# Turn real video files (stock footage, phone clips) into the feed's clip library:
# vertical 720x1280 HLS with 2-second segments (same encoding as make_media.sh),
# AAC audio when the source has sound, a poster per clip, and media/manifest.json.
#
#   sh scripts/ingest_videos.sh <src_dir> [out_dir] [max_seconds]
#
# <src_dir> holds .mp4/.mov/.webm/.mkv files, played in file-name order. Optional
# <src_dir>/credits.csv gives each one a title and creator (no commas in either):
#
#   file,title,creator
#   ocean.mp4,Morning swell,Jane Doe
#
# Without a line in credits.csv the title comes from the file name.
#
# Clips get ids s001, s002, ... (not v001...): the app's disk cache and nginx's
# year-long cache headers are keyed by URL, so reusing the old ids would keep
# serving the old clips. Earlier generated clips in <out_dir> are removed.
set -eu

SRC="${1:?usage: ingest_videos.sh <src_dir> [out_dir] [max_seconds]}"
OUT="${2:-media}"
MAX_SECONDS="${3:-15}"
CREDITS="$SRC/credits.csv"

mkdir -p "$OUT"
rm -rf "$OUT"/v[0-9][0-9][0-9] "$OUT"/s[0-9][0-9][0-9]

# JSON string escaping for titles and names: backslash and double quote.
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# "ocean_waves-4k.mp4" -> "Ocean waves 4k"
title_from_name() {
  printf '%s' "$1" | sed -e 's/\.[^.]*$//' -e 's/[_-]/ /g' \
    | awk '{ print toupper(substr($0, 1, 1)) substr($0, 2) }'
}

# Field 2 (title) or 3 (creator) of the credits.csv line for a file, or empty.
credit() {
  [ -f "$CREDITS" ] || return 0
  awk -F, -v f="$1" -v col="$2" 'NR > 1 && $1 == f { print $col; exit }' "$CREDITS" | tr -d '\r'
}

MANIFEST="$OUT/manifest.json"
TMP_MANIFEST="$MANIFEST.tmp"
echo "[" > "$TMP_MANIFEST"
i=0

find "$SRC" -maxdepth 1 -type f \( -iname '*.mp4' -o -iname '*.mov' -o -iname '*.webm' -o -iname '*.mkv' \) | sort > "$OUT/.sources"
while IFS= read -r file; do
  i=$((i + 1))
  base=$(basename "$file")
  id=$(printf "s%03d" "$i")
  dir="$OUT/$id"
  mkdir -p "$dir"

  title=$(credit "$base" 2)
  [ -n "$title" ] || title=$(title_from_name "$base")
  creator=$(credit "$base" 3)
  [ -n "$creator" ] || creator="stock"

  duration=$(ffprobe </dev/null -v error -show_entries format=duration -of csv=p=0 "$file")
  seconds=$(awk -v d="$duration" -v m="$MAX_SECONDS" 'BEGIN { print (d < m ? d : m) }')
  duration_ms=$(awk -v s="$seconds" 'BEGIN { printf "%d", s * 1000 }')
  poster_at=$(awk -v s="$seconds" 'BEGIN { print (s > 2 ? 1 : s / 2) }')

  # (-nostdin and </dev/null: inside this while-read loop, ffmpeg and ffprobe would
  # otherwise read stdin and swallow the rest of the file list.)
  # Fill the 9:16 frame (scale up to cover, centre crop), constant 30 fps.
  vf="scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,setsar=1,fps=30"
  if [ -n "$(ffprobe </dev/null -v error -select_streams a -show_entries stream=index -of csv=p=0 "$file" | head -n 1)" ]; then
    audio="-map 0:a:0 -c:a aac -b:a 128k -ac 2"
  else
    audio="-an"
  fi

  # shellcheck disable=SC2086 # $audio is a list of arguments
  ffmpeg -nostdin -v error -y -i "$file" -t "$seconds" -map 0:v:0 $audio -vf "$vf" \
    -c:v libx264 -preset veryfast -profile:v main -b:v 1500k -maxrate 1800k -bufsize 3000k \
    -g 60 -keyint_min 60 -sc_threshold 0 -pix_fmt yuv420p \
    -f hls -hls_time 2 -hls_playlist_type vod \
    -hls_segment_filename "$dir/seg_%03d.ts" "$dir/index.m3u8"
  ffmpeg -nostdin -v error -y -ss "$poster_at" -i "$file" -vf "$vf" -frames:v 1 -q:v 3 "$dir/poster.jpg"

  [ "$i" -gt 1 ] && echo "," >> "$TMP_MANIFEST"
  printf '  {"id": "%s", "title": "%s", "creator": "%s", "duration_ms": %d, "width": 720, "height": 1280, "hls_path": "%s/index.m3u8", "poster_path": "%s/poster.jpg"}' \
    "$id" "$(json_escape "$title")" "$(json_escape "$creator")" "$duration_ms" "$id" "$id" >> "$TMP_MANIFEST"
  echo "  $id  $title  ($creator, ${seconds}s)"
done < "$OUT/.sources"
rm -f "$OUT/.sources"

if [ "$i" -eq 0 ]; then
  rm -f "$TMP_MANIFEST"
  echo "no video files in $SRC" >&2
  exit 1
fi
printf '\n]\n' >> "$TMP_MANIFEST"
mv "$TMP_MANIFEST" "$MANIFEST"
echo "wrote $i clips to $OUT/"
