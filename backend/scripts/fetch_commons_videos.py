"""Download freely licensed demo videos from Wikimedia Commons into media-src/.

    python scripts/fetch_commons_videos.py [out_dir] [count]

For each search topic it takes the best clip that is 6-60 s long, at least 720p and
under a free license (CC0, public domain, CC BY, CC BY-SA), downloads Commons' own
1080p (or 720p) WebM transcode, and records who made it:

  credits.csv     file,title,creator  -> read by ingest_videos.sh, shown in the app
  ATTRIBUTION.md  title, author, license and source link for every clip (CC BY needs it)

Standard library only. Wikimedia asks automated clients for a descriptive User-Agent.
"""

import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import os

API = "https://commons.wikimedia.org/w/api.php"
# Wikimedia's User-Agent policy: name the tool and give a way to reach its operator
# (a URL or email). Downloads without contact details are refused with 429.
CONTACT = os.environ.get("COMMONS_CONTACT", "").strip()
USER_AGENT = "GlimpseDemoFetcher/1.0 (" + (CONTACT or "no contact given") + ")"
TOPICS = [
    "ocean waves", "waterfall", "time-lapse clouds", "city night", "fireworks",
    "snow falling", "aurora borealis", "forest stream", "jellyfish", "hummingbird",
    "rain drops", "sunset beach", "train", "lava", "butterfly", "desert",
]
FREE = re.compile(r"^(cc0|public domain|pd\b|cc by(-sa)? [0-9.]+)", re.IGNORECASE)
MIN_SECONDS, MAX_SECONDS = 6, 60
SAFE_TEXT = re.compile(r"[^\w .'&()-]+", re.UNICODE)


def get_json(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def plain(value, limit):
    """HTML from Commons metadata -> short single-line text without commas."""
    text = html.unescape(re.sub(r"<[^>]+>", " ", value or ""))
    text = SAFE_TEXT.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:limit].rstrip(" .-")


def candidates(topic):
    data = get_json({
        "action": "query", "format": "json", "formatversion": "2",
        "generator": "search", "gsrsearch": "filetype:video " + topic,
        "gsrnamespace": "6", "gsrlimit": "15",
        "prop": "videoinfo",
        "viprop": "url|size|mime|extmetadata|derivatives",
        "viextmetadatafilter": "Artist|LicenseShortName|LicenseUrl|ObjectName",
    })
    for page in data.get("query", {}).get("pages", []):
        info = (page.get("videoinfo") or [{}])[0]
        meta = info.get("extmetadata", {})
        seconds = float(info.get("duration") or 0)
        width, height = int(info.get("width") or 0), int(info.get("height") or 0)
        license_name = plain(meta.get("LicenseShortName", {}).get("value"), 40)
        if not (MIN_SECONDS <= seconds <= MAX_SECONDS) or min(width, height) < 720:
            continue
        if not FREE.match(license_name):
            continue
        # Commons' WebM transcodes: prefer 1080p, then 720p.
        derivative = None
        for want in (1080, 720):
            for d in info.get("derivatives", []):
                if d.get("type", "").startswith("video/webm") and want in (d.get("height"), d.get("width")):
                    derivative = d
                    break
            if derivative:
                break
        if not derivative:
            continue
        yield {
            "page": page["title"],
            "src": derivative["src"],
            "portrait": height > width,
            "seconds": seconds,
            "title": plain(meta.get("ObjectName", {}).get("value"), 48)
                     or plain(page["title"].removeprefix("File:").rsplit(".", 1)[0], 48),
            "creator": plain(meta.get("Artist", {}).get("value"), 32) or "Wikimedia Commons",
            "license": license_name,
            "license_url": (meta.get("LicenseUrl", {}).get("value") or ""),
            "source": info.get("descriptionurl", ""),
        }


def download(url, dest, attempts=3):
    """Downloads to a .part file first, so an interrupted download never looks complete.
    On 429 it waits as long as the server asks (Retry-After), then tries again."""
    tmp = dest.with_suffix(".part")
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
                while chunk := r.read(1 << 16):
                    f.write(chunk)
            tmp.replace(dest)
            return
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == attempts:
                raise
            wait = int(e.headers.get("Retry-After") or 10 * attempt)
            print(f"    throttled, waiting {wait}s")
            time.sleep(min(wait, 120))


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # titles in any script, Windows console too
    if not CONTACT:
        print("Set COMMONS_CONTACT to a URL or email for Wikimedia's User-Agent policy, e.g.\n"
              "  COMMONS_CONTACT=https://github.com/you python scripts/fetch_commons_videos.py")
        return 2
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "media-src")
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 12
    out.mkdir(parents=True, exist_ok=True)
    picked, seen = [], set()
    for topic in TOPICS:
        if len(picked) >= count:
            break
        try:
            found = [c for c in candidates(topic) if c["page"] not in seen]
        except OSError as e:
            print(f"  skip {topic!r}: {e}")
            continue
        if not found:
            print(f"  no free clip for {topic!r}")
            continue
        best = sorted(found, key=lambda c: (not c["portrait"], abs(c["seconds"] - 15)))[0]
        seen.add(best["page"])
        name = f"{len(picked) + 1:02d}-{re.sub(r'[^a-z0-9]+', '-', topic.lower()).strip('-')}.webm"
        print(f"  {name}  {best['title']}  by {best['creator']}  ({best['license']}, {best['seconds']:.0f}s)")
        try:
            download(best["src"], out / name)
        except OSError as e:
            print(f"    download failed: {e}")
            continue
        best["file"] = name
        picked.append(best)
        time.sleep(1)  # be gentle with Wikimedia's servers

    with open(out / "credits.csv", "w", encoding="utf-8", newline="\n") as f:
        f.write("file,title,creator\n")
        for c in picked:
            f.write(f"{c['file']},{c['title']},{c['creator']}\n")
    with open(out / "ATTRIBUTION.md", "w", encoding="utf-8", newline="\n") as f:
        f.write("# Video credits\n\nDemo clips from Wikimedia Commons, used under their licenses.\n\n")
        f.write("| Clip | Title | Author | License | Source |\n|---|---|---|---|---|\n")
        for c in picked:
            lic = f"[{c['license']}]({c['license_url']})" if c["license_url"] else c["license"]
            f.write(f"| {c['file']} | {c['title']} | {c['creator']} | {lic} | [Commons]({c['source']}) |\n")
    print(f"downloaded {len(picked)} clips to {out}/")
    return 0 if picked else 1


if __name__ == "__main__":
    sys.exit(main())
