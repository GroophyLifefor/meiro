#!/usr/bin/env bash
# Records real, signed-out YouTube Music InnerTube responses (the envelope of PORTING.md 2.1: WEB_REMIX, hl=en gl=US,
# no cookie, no visitor id) into app/src/test/resources/fixtures/ as golden files for the search parser:
#   ytm_search_daft_punk.json        search "daft punk" (no filter)
#   ytm_search_daft_punk_songs.json  the same search with the "Songs" chip's params, read from the first answer
#   ytm_search_songs_continuation.json  the next page of the Songs search
#   ytm_suggestions_daft_p.json      music/get_search_suggestions for "daft p"
# Anything that could tie a response to a visitor is removed: responseContext (visitorData, serviceTrackingParams,
# responseId) and every trackingParams / clickTrackingParams value. Output is minified JSON.
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/fixtures"
mkdir -p "$OUT"
python3 -I - "$OUT" <<'PY'
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
PY
