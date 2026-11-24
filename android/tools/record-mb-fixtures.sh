#!/usr/bin/env bash
# Records real MusicBrainz and ListenBrainz answers (M6a linking) into app/src/test/resources/fixtures/ as golden files
# for the MusicBrainz client and the link rules. Sends the desktop's User-Agent (musicbrainz/musicbrainz.go UserAgent)
# and waits 1.2 s between MusicBrainz requests (MusicBrainz allows one a second). Run it rarely: 4 MusicBrainz requests
# and 1 ListenBrainz request (M6d adds 14 and 2; a file already recorded is not asked for again).
#   mb_recording_get_lucky.json          recording:"Get Lucky" AND artist:"Daft Punk", limit 10
#   mb_recording_instant_crush.json      recording:"Instant Crush" AND artist:"Daft Punk", limit 10
#   mb_recording_none.json               recording:"Gece Yarisi zzqx" AND artist:"Anil Kaya zzqx" (nothing found)
#   mb_artist_daft_punk.json             artist query "Daft Punk", limit 25
#   lb_popularity_daft_punk.json         ListenBrainz popularity of the artists in that answer
# M6d (the search's "Sanatçılar" and the MusicBrainz artist page, artists.go), for Daft Punk (056e4f3e-...):
#   lb_top_recordings_daft_punk.json     ListenBrainz top recordings for the artist, the first 40 (1 ListenBrainz request)
#   mb_links_<recording mbid>.json       recording/<id>?inc=url-rels for the first 12 of them (12 MusicBrainz requests)
#   mb_artist_lookup_daft_punk.json      artist/<id> (1 MusicBrainz request)
#   mb_artist_recordings_daft_punk.json  recording?artist=<id>&limit=25, the fallback without ListenBrainz (1 request)
#   lb_similar_daft_punk.json            ListenBrainz Labs similar artists (the desktop's algorithm) (1 request)
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/fixtures"
mkdir -p "$OUT"
python3 -I - "$OUT" <<'PY'
import json, os, sys, time, urllib.error, urllib.parse, urllib.request
out = sys.argv[1]
UA = "Playlist Manager SideProject/1.0.0 (muratkirazkaya0@gmail.com)"
def get(url):
    # MusicBrainz answers 503 when it is busy for everyone (x-ratelimit-zone: global): wait as it asks and try again,
    # at most 5 times.
    for attempt in range(5):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code not in (429, 503) or attempt == 4: raise
            wait = max(int(e.headers.get("Retry-After") or 0), 2) * (attempt + 1)
            print("busy (%d), waiting %d s" % (e.code, wait)); time.sleep(wait)
def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"User-Agent": UA, "Accept": "application/json", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())
def q(t): return '"' + t.replace('\\', '\\\\').replace('"', '\\"') + '"'
def rec(title, artist):
    query = "recording:" + q(title) + (" AND artist:" + q(artist) if artist else "")
    return get("https://musicbrainz.org/ws/2/recording?" + urllib.parse.urlencode({"fmt": "json", "limit": "10", "query": query}))
def save(name, data):
    with open(out + "/" + name, "w") as f: json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print("wrote", name)
# A file already recorded is kept (delete it to record it again), so a run that MusicBrainz cut short asks only for the rest.
def have(name): return os.path.exists(out + "/" + name)
for name, title, artist in [("mb_recording_get_lucky.json", "Get Lucky", "Daft Punk"),
                            ("mb_recording_instant_crush.json", "Instant Crush", "Daft Punk"),
                            ("mb_recording_none.json", "Gece Yarisi zzqx", "Anil Kaya zzqx")]:
    if not have(name): save(name, rec(title, artist)); time.sleep(1.2)
if have("mb_artist_daft_punk.json"):
    artists = json.load(open(out + "/mb_artist_daft_punk.json"))
else:
    artists = get("https://musicbrainz.org/ws/2/artist?" + urllib.parse.urlencode({"fmt": "json", "limit": "25", "query": "Daft Punk"}))
    save("mb_artist_daft_punk.json", artists)
ids = sorted({a["id"] for a in artists.get("artists", []) if a.get("id")})
if not have("lb_popularity_daft_punk.json"):
    save("lb_popularity_daft_punk.json", post("https://api.listenbrainz.org/1/popularity/artist", {"artist_mbids": ids}))
DP = "056e4f3e-d505-4dad-8ec1-d04f521cbb56"
MB = "https://musicbrainz.org/ws/2/"
if have("lb_top_recordings_daft_punk.json"):
    top = json.load(open(out + "/lb_top_recordings_daft_punk.json"))
else:
    # ListenBrainz answers with every recording it knows (1003 for Daft Punk, 820 KB): the first 40 are kept.
    top = get("https://api.listenbrainz.org/1/popularity/top-recordings-for-artist/" + DP)[:40]
    save("lb_top_recordings_daft_punk.json", top)
for r in top[:12]:
    name = "mb_links_" + r["recording_mbid"] + ".json"
    if not have(name):
        save(name, get(MB + "recording/" + r["recording_mbid"] + "?" + urllib.parse.urlencode({"fmt": "json", "inc": "url-rels"}))); time.sleep(1.2)
if not have("mb_artist_lookup_daft_punk.json"):
    save("mb_artist_lookup_daft_punk.json", get(MB + "artist/" + DP + "?fmt=json")); time.sleep(1.2)
if not have("mb_artist_recordings_daft_punk.json"):
    save("mb_artist_recordings_daft_punk.json", get(MB + "recording?" + urllib.parse.urlencode({"artist": DP, "fmt": "json", "limit": "25"}))); time.sleep(1.2)
if not have("lb_similar_daft_punk.json"):
    save("lb_similar_daft_punk.json", get("https://labs.api.listenbrainz.org/similar-artists/json?algorithm=session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30&artist_mbids=" + DP))
PY
