#!/usr/bin/env bash
# Records real, signed-out YouTube Music InnerTube responses (the envelope of PORTING.md 2.1: WEB_REMIX, hl=en gl=US,
# no cookie, no visitor id) into app/src/test/resources/fixtures/ as golden files for the search parser:
#   ytm_search_daft_punk.json        search "daft punk" (no filter)
#   ytm_search_daft_punk_songs.json  the same search with the "Songs" chip's params, read from the first answer
#   ytm_search_songs_continuation.json  the next page of the Songs search
#   ytm_suggestions_daft_p.json      music/get_search_suggestions for "daft p"
#   ytm_browse_artist_daft_punk.json browse of the artist the search leads with (M4d artist page)
#   ytm_browse_album_ram.json        browse of the first album of that search, "Random Access Memories" (M4d album page)
# With the argument `browse` only the two browse files are recorded, from the ids in the saved search fixture, so the
# search fixtures (and the tests written against them) stay as they are.
# With the argument `upnext` (M4f auto-play) only the generated queue of that seed: ytm_upnext_seed.json (`next` with
# playlistId RDAMVM<id>) and ytm_upnext_continuation.json (its next page).
# With the argument `related` (M4e Daily mix) only the related tab of the first song of the saved search fixture:
#   ytm_next_related_seed.json       `next` for that video (it names the related tab's browse id)
#   ytm_browse_related.json          browse of that related tab (MUSIC_PAGE_TYPE_TRACK_RELATED)
# With the argument `lyrics` (M4g) the lyrics tabs and two LRCLIB answers:
#   ytm_browse_lyrics_none.json      web browse of the related seed's lyrics tab ("Lyrics not available")
#   ytm_next_lyrics_seed.json        `next` for the first song of the saved Songs search (names its lyrics tab)
#   ytm_browse_lyrics.json           web browse of that lyrics tab (plain text and its "Source:" footer)
#   ytm_browse_lyrics_android.json   the same browse as ANDROID_MUSIC (timed lines)
#   lrclib_get_get_lucky.json        lrclib.net /api/get "Get Lucky", Daft Punk, Random Access Memories, 369 s
#   lrclib_search_get_lucky.json     lrclib.net /api/search for the same title and artist, first 5 hits
# Anything that could tie a response to a visitor is removed: responseContext (visitorData, serviceTrackingParams,
# responseId) and every trackingParams / clickTrackingParams value. Output is minified JSON.
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/fixtures"
mkdir -p "$OUT"
python3 -I - "$OUT" "${1:-all}" <<'PY'
import json, sys, urllib.request
out = sys.argv[1]
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
VERSION = "1.20250219.01.00"

def call(endpoint, payload, audio_only=True):
    body = {"context": {"client": {"hl": "en", "gl": "US", "clientName": "WEB_REMIX", "clientVersion": VERSION}}}
    body.update(payload)
    if audio_only: body["isAudioOnly"] = True
    req = urllib.request.Request(
        "https://music.youtube.com/youtubei/v1/" + endpoint, data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": UA,
                 "Origin": "https://music.youtube.com", "Referer": "https://music.youtube.com/",
                 "X-YouTube-Client-Name": "67", "X-YouTube-Client-Version": VERSION})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()

def scrub(raw):
    # object_pairs_hook keeps member order and duplicate names as YouTube sent them.
    def pairs(p):
        kept = [(k, v) for k, v in p if k not in ("responseContext", "trackingParams", "clickTrackingParams", "visitorData")]
        return Ordered(kept)
    return json.loads(raw, object_pairs_hook=pairs)

class Ordered(list):
    pass

def dump(v):
    if isinstance(v, Ordered):
        return "{" + ",".join(json.dumps(k, ensure_ascii=False) + ":" + dump(x) for k, x in v) + "}"
    if isinstance(v, list):
        return "[" + ",".join(dump(x) for x in v) + "]"
    return json.dumps(v, ensure_ascii=False)

def save(name, raw):
    text = dump(scrub(raw))
    assert "visitorData" not in text and "VISITOR" not in text
    open(f"{out}/{name}.json", "w", encoding="utf-8").write(text)
    print(name, len(text), "bytes")
    return json.loads(raw)

def find(o, key):
    if isinstance(o, dict):
        if key in o: yield o[key]
        for v in o.values(): yield from find(v, key)
    elif isinstance(o, list):
        for v in o: yield from find(v, key)

def browse_ids(root):
    artist = album = None
    for card in find(root, "musicCardShelfRenderer"):
        for run in card.get("title", {}).get("runs", []):
            bid = run.get("navigationEndpoint", {}).get("browseEndpoint", {}).get("browseId", "")
            if bid.startswith("UC") and not artist: artist = bid
    # The mock's album, "Random Access Memories", when the search lists it; else the first album.
    for key in ("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer"):
        for r in find(root, key):
            text = json.dumps(r.get("flexColumns", r.get("title", "")))
            if "Random Access Memories" in text and not album:
                album = next((b["browseId"] for b in find(r, "browseEndpoint") if b.get("browseId", "").startswith("MPRE")), None)
    for be in find(root, "browseEndpoint"):
        bid = be.get("browseId", "")
        if bid.startswith("MPRE") and not album: album = bid
    assert artist and album, (artist, album)
    return artist, album

def save_browse(root):
    artist, album = browse_ids(root)
    print("artist", artist, "album", album)
    save("ytm_browse_artist_daft_punk", call("browse", {"browseId": artist}))
    save("ytm_browse_album_ram", call("browse", {"browseId": album}))

def first_video(root):
    for r in find(root, "musicResponsiveListItemRenderer"):
        for w in find(r, "watchEndpoint"):
            if w.get("videoId"): return w["videoId"]
    raise AssertionError("no video in the search fixture")

def related_browse_id(root):
    def walk(o, parents):
        if isinstance(o, dict):
            if o.get("pageType") == "MUSIC_PAGE_TYPE_TRACK_RELATED":
                for p in reversed(parents + [o]):
                    bid = p.get("browseEndpoint", {}).get("browseId") if isinstance(p, dict) else None
                    if bid: return bid
            for v in o.values():
                r = walk(v, parents + [o])
                if r: return r
        elif isinstance(o, list):
            for v in o:
                r = walk(v, parents)
                if r: return r
        return None
    bid = walk(root, [])
    assert bid, "no related tab"
    return bid

if sys.argv[2] == "related":
    vid = first_video(json.load(open(f"{out}/ytm_search_daft_punk.json", encoding="utf-8")))
    print("seed video", vid)
    nxt = save("ytm_next_related_seed", call("next", {"videoId": vid}))
    bid = related_browse_id(nxt)
    print("related browse", bid)
    save("ytm_browse_related", call("browse", {"browseId": bid}))
    sys.exit(0)

if sys.argv[2] == "lyrics":
    # M4g lyrics tab. The related seed's lyrics page says "Lyrics not available" (kept: the no-lyrics case); the first
    # song of the saved Songs search has both forms. For it: `next` (it names the lyrics tab,
    # MUSIC_PAGE_TYPE_TRACK_LYRICS), that page read as the web client (plain text, musicDescriptionShelfRenderer) and as
    # ANDROID_MUSIC (timed lines, PORTING.md 2.3). Then LRCLIB's exact match and its search for "Get Lucky"
    # (internal/lrclib), the search trimmed to 5 hits.
    def lyrics_tab(root):
        for te in find(root, "tabRenderer"):
            be = te.get("endpoint", {}).get("browseEndpoint", {})
            if be.get("browseEndpointContextSupportedConfigs", {}).get("browseEndpointContextMusicConfig", {}).get("pageType") == "MUSIC_PAGE_TYPE_TRACK_LYRICS":
                return be["browseId"]
        raise AssertionError("no lyrics tab")
    none_bid = lyrics_tab(json.load(open(f"{out}/ytm_next_related_seed.json", encoding="utf-8")))
    print("no-lyrics browse", none_bid)
    save("ytm_browse_lyrics_none", call("browse", {"browseId": none_bid}))
    vid = first_video(json.load(open(f"{out}/ytm_search_daft_punk_songs.json", encoding="utf-8")))
    print("lyrics video", vid)
    bid = lyrics_tab(save("ytm_next_lyrics_seed", call("next", {"videoId": vid})))
    print("lyrics browse", bid)
    save("ytm_browse_lyrics", call("browse", {"browseId": bid}))
    AV = "7.21.50"
    body = {"context": {"client": {"hl": "en", "gl": "US", "clientName": "ANDROID_MUSIC", "clientVersion": AV}}, "browseId": bid}
    req = urllib.request.Request(
        "https://music.youtube.com/youtubei/v1/browse", data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json",
                 "User-Agent": "com.google.android.apps.youtube.music/" + AV + " (Linux; U; Android 14) gzip",
                 "Origin": "https://music.youtube.com", "Referer": "https://music.youtube.com/",
                 "X-YouTube-Client-Name": "21", "X-YouTube-Client-Version": AV})
    with urllib.request.urlopen(req, timeout=30) as r:
        save("ytm_browse_lyrics_android", r.read())
    import urllib.parse
    def lrclib(path, q):
        req = urllib.request.Request("https://lrclib.net" + path + "?" + urllib.parse.urlencode(q),
                                     headers={"User-Agent": "Meiro (https://github.com/elianiva/meiro)"})
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    q = {"track_name": "Get Lucky", "artist_name": "Daft Punk"}
    one = lrclib("/api/get", dict(q, album_name="Random Access Memories", duration=369))
    open(f"{out}/lrclib_get_get_lucky.json", "w", encoding="utf-8").write(json.dumps(one, ensure_ascii=False))
    many = lrclib("/api/search", q)[:5]
    open(f"{out}/lrclib_search_get_lucky.json", "w", encoding="utf-8").write(json.dumps(many, ensure_ascii=False))
    print("lrclib get", one.get("id"), "search", len(many))
    # The repository is public: song lyrics are someone else's text. Every lyric line becomes "Line N"; timestamps,
    # blank lines, "♪" and [section] markers stay, so parsing, timing and line counts are tested the same.
    import re
    def red_lines(text, keep_ts=False):
        res, n = [], 0
        for line in text.split("\n"):
            m = re.match(r"^(\[[0-9:.]+\]\s?)(.*)$", line) if keep_ts else None
            pre, body = (m.group(1), m.group(2)) if m else ("", line)
            if body.strip() in ("", "♪") or re.match(r"^\[.*\]$", body.strip()):
                res.append(pre + body); continue
            n += 1; res.append(pre + f"Line {n}")
        return "\n".join(res)
    def rewrite(name, fn):
        path = f"{out}/{name}.json"
        d = json.load(open(path, encoding="utf-8")); fn(d)
        open(path, "w", encoding="utf-8").write(json.dumps(d, ensure_ascii=False))
    def plain(d):
        for shelf in find(d, "musicDescriptionShelfRenderer"):
            for run in shelf["description"]["runs"]: run["text"] = red_lines(run["text"])
    def timed(d):
        n = 0
        for line in find(d, "timedLyricsData"):
            for entry in (line if isinstance(line, list) else [line]):
                if entry.get("lyricLine", "").strip() not in ("", "♪"):
                    n += 1; entry["lyricLine"] = f"Line {n}"
    def lrc(d):
        for it in (d if isinstance(d, list) else [d]):
            if it.get("plainLyrics"): it["plainLyrics"] = red_lines(it["plainLyrics"])
            if it.get("syncedLyrics"): it["syncedLyrics"] = red_lines(it["syncedLyrics"], True)
            if it.get("lyricsfile"): it["lyricsfile"] = "version: '1.0'\n# lyrics removed from the recorded response"
    rewrite("ytm_browse_lyrics", plain); rewrite("ytm_browse_lyrics_android", timed)
    rewrite("lrclib_get_get_lucky", lrc); rewrite("lrclib_search_get_lucky", lrc)
    sys.exit(0)

if sys.argv[2] == "upnext":
    # M4f auto-play: the generated queue of the same seed (`GetUpNext`: `next` with the track's own mix RDAMVM<id>)
    # and the next page of it (`ContinueUpNext`).
    vid = first_video(json.load(open(f"{out}/ytm_search_daft_punk.json", encoding="utf-8")))
    print("seed video", vid)
    first = save("ytm_upnext_seed", call("next", {"videoId": vid, "playlistId": "RDAMVM" + vid}))
    token = None
    for panel in find(first, "playlistPanelRenderer"):
        for c in panel.get("continuations", []):
            for k in ("nextRadioContinuationData", "nextContinuationData"):
                token = token or c.get(k, {}).get("continuation")
    assert token, "no up-next continuation"
    save("ytm_upnext_continuation", call("next", {"videoId": vid, "playlistId": "RDAMVM" + vid, "continuation": token}))
    sys.exit(0)

if sys.argv[2] == "browse":
    save_browse(json.load(open(f"{out}/ytm_search_daft_punk.json", encoding="utf-8")))
    sys.exit(0)

first = save("ytm_search_daft_punk", call("search", {"query": "daft punk"}))
params = None
for chip in find(first, "chipCloudChipRenderer"):
    label = "".join(r.get("text", "") for r in chip.get("text", {}).get("runs", []))
    if label == "Songs": params = chip["navigationEndpoint"]["searchEndpoint"]["params"]
assert params, "no Songs chip"
songs = save("ytm_search_daft_punk_songs", call("search", {"query": "daft punk", "params": params}))
token = next((c for c in find(songs, "continuation") if isinstance(c, str)), None) or \
        next((c["token"] for c in find(songs, "continuationCommand")), None)
assert token, "no continuation token"
save("ytm_search_songs_continuation", call("search", {"continuation": token}))
save("ytm_suggestions_daft_p", call("music/get_search_suggestions", {"input": "daft p"}))
save_browse(first)
PY
