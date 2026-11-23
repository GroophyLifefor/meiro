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
