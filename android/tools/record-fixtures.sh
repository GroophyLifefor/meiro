#!/usr/bin/env bash
# Records real responses of the LOCAL dev server (meiro-server docker compose, port 8080)
# into app/src/test/resources/fixtures/. Tokens are scrubbed. Refuses to talk to anything
# but localhost. Needs a throwaway user: e2e-android-fixtures@example.com / e2e-pass-123
#   docker compose exec -T backend /usr/local/bin/meiro-server create-user --email e2e-android-fixtures@example.com --password e2e-pass-123
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
case "$BASE" in http://localhost:*|http://127.0.0.1:*) ;; *) echo "refusing non-local $BASE"; exit 2;; esac
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/fixtures"
EMAIL=e2e-android-fixtures@example.com; PASS=e2e-pass-123
mkdir -p "$OUT"
rec() { # name method path [body]  -> writes name.json (body) and name.status
  local name=$1 method=$2 path=$3 body=${4:-}
  local args=(-s -o "$OUT/$name.json" -w '%{http_code}' -X "$method" -H "Authorization: Bearer ${TOKEN:-}")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local code; code=$(curl "${args[@]}" "$BASE$path")
  echo "$code" > "$OUT/$name.status"
  echo "$name $method $path -> $code"
}
TOKEN=""
curl -s -X POST "$BASE/api/v1/auth/login" -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}" -o "$OUT/login.raw"
TOKEN=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$OUT/login.raw")
echo '{"token":"SCRUBBED-TOKEN-0000000000000000000000000000"}' > "$OUT/login.json"; echo 200 > "$OUT/login.status"; rm "$OUT/login.raw"
TOKEN_SAVE=$TOKEN; TOKEN=""
rec login_bad POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"wrong\"}"
TOKEN=$TOKEN_SAVE
TOKEN=bad rec changes_401 GET "/api/v1/sync/changes?since=0&limit=10"
LIB=11111111-2222-4333-8444-555555555555
rec push_libraries POST /api/v1/sync/libraries '{"libraries":[{"id":"'$LIB'","kind":"custom","name":"Gym","cover":"https://example.com/c.jpg","updated_at":"2026-11-21T10:00:00.123456789Z"},{"id":"bab208cb-a929-55cd-a091-c414d0e03864","kind":"custom","name":"Daily mix","updated_at":"2026-11-21T10:00:01.5Z"}]}'
rec push_libraries_bad_kind POST /api/v1/sync/libraries '{"libraries":[{"id":"'$LIB'","kind":"nope","name":"x","updated_at":"2026-11-21T10:00:00Z"}]}'
rec push_items POST /api/v1/sync/library_items '{"library_id":"'$LIB'","items":[{"track":{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","title":"Never Gonna","artist":"Rick","album":"Whenever","duration_ms":213000,"thumbnail":"https://example.com/t.jpg","album_thumbnail":"https://example.com/a.jpg"},"position":0,"note":"because","updated_at":"2026-11-21T10:00:02.000000001Z"},{"track":{"provider":"soundcloud","provider_track_id":"https://soundcloud.com/a/b","title":"SC song","artist":"SC artist"},"position":1,"updated_at":"2026-11-21T10:00:03Z"},{"track":{"provider":"ytm","provider_track_id":"gone0000001","title":"Removed","artist":"Nobody"},"position":0,"updated_at":"2026-11-21T10:00:04Z","deleted":true}]}'
rec push_items_unknown_library POST /api/v1/sync/library_items '{"library_id":"99999999-2222-4333-8444-555555555555","items":[{"track":{"provider":"ytm","provider_track_id":"x1","title":"t","artist":"a"},"position":0,"updated_at":"2026-11-21T10:00:00Z"}]}'
rec push_events POST /api/v1/sync/events '{"events":[{"client_event_id":"aaaaaaaa-0000-4000-8000-000000000001","track":{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","title":"Never Gonna","artist":"Rick","thumbnail":"https://example.com/t.jpg"},"played_ms":187000,"duration_ms":213000,"completed":true,"device":"Pixel","played_at":"2026-11-21T10:05:00.123456789Z"},{"client_event_id":"aaaaaaaa-0000-4000-8000-000000000002","track":{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","title":"Never Gonna","artist":"Rick"},"played_ms":5000,"completed":false,"played_at":"2026-11-21T10:06:00Z"}]}'
rec push_events_dup POST /api/v1/sync/events '{"events":[{"client_event_id":"aaaaaaaa-0000-4000-8000-000000000001","track":{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","title":"Never Gonna","artist":"Rick"},"played_ms":187000,"completed":true,"played_at":"2026-11-21T10:05:00.123456789Z"}]}'
rec push_downloads POST /api/v1/sync/downloads '{"downloads":[{"track":{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","title":"Never Gonna","artist":"Rick"},"device":"Pixel","size_bytes":123,"updated_at":"2026-11-21T10:07:00Z"}]}'
rec push_links POST /api/v1/sync/links '{"songs":[{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","state":"matched","mbid":"11111111-1111-4111-8111-111111111111","artist":"Rick","artist_mbid":"22222222-2222-4222-8222-222222222222","artist_state":"matched","checked_at":"2026-11-21T10:08:00Z","updated_at":"2026-11-21T10:08:00.5Z"}],"artists":[{"artist_key":"rick","name":"Rick","state":"matched","mbid":"22222222-2222-4222-8222-222222222222","updated_at":"2026-11-21T10:08:00Z"}],"alternates":[{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","other_provider":"soundcloud","other_track_id":"https://soundcloud.com/a/b","other_url":"https://soundcloud.com/a/b","checked_at":"2026-11-21T10:08:00Z","updated_at":"2026-11-21T10:08:00Z"}]}'
rec get_links GET /api/v1/links
rec changes_all GET "/api/v1/sync/changes?since=0&limit=1000"
rec changes_page1 GET "/api/v1/sync/changes?since=0&limit=2"
NEXT=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["next"])' "$OUT/changes_page1.json")
rec changes_page2 GET "/api/v1/sync/changes?since=$NEXT&limit=2"
rec changes_bad_since GET "/api/v1/sync/changes?since=-1"
rec events_all GET "/api/v1/events?since=0&limit=1000"
rec events_page1 GET "/api/v1/events?since=0&limit=1"
rec events_never_cleared GET "/api/v1/events?since=0&limit=1000"
rec clear_events DELETE /api/v1/events
rec events_after_clear GET "/api/v1/events?since=0&limit=1000"
rec delete_item_resurrect_older POST /api/v1/sync/library_items '{"library_id":"'$LIB'","items":[{"track":{"provider":"ytm","provider_track_id":"dQw4w9WgXcQ","title":"Never Gonna","artist":"Rick"},"position":0,"updated_at":"2026-11-21T09:00:00Z"}]}'
echo done
