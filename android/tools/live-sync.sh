#!/usr/bin/env bash
# Runs the live sync integration test (two engines against the LOCAL dev server).
#  - brings up the meiro-server compose stack if it is not running, and stops it again only if this script started it
#  - creates a throwaway `e2e-android-live-*` user, runs the test, deletes only e2e-android-* users afterwards
# Never touches the prod server: the test itself refuses any host but localhost.
set -euo pipefail
cd "$(dirname "$0")/.."
SERVER_DIR="${SERVER_DIR:-$(cd .. && cd ../meiro-server && pwd)}"
export JAVA_HOME="${JAVA_HOME:-$(echo ~/.local/share/jdks/jdk-21*)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
URL="${MEIRO_LIVE_URL:-http://localhost:8080}"
case "$URL" in http://localhost:*|http://127.0.0.1:*) ;; *) echo "refusing non-local URL $URL"; exit 2;; esac
cd "$SERVER_DIR"
STARTED=0
if ! curl -fs "$URL/healthz" >/dev/null 2>&1; then docker compose up -d db backend; STARTED=1; for i in $(seq 1 60); do curl -fs "$URL/healthz" >/dev/null 2>&1 && break; sleep 1; done; fi
EMAIL="e2e-android-live-$(date +%s)@example.com"; PASS="e2e-pass-$RANDOM$RANDOM"
cleanup() {
  cd "$SERVER_DIR"
  docker compose exec -T db psql -U meiro -d meiro -q -c "DELETE FROM users WHERE email LIKE 'e2e-android-%'" >/dev/null || true
  [ "$STARTED" = 1 ] && docker compose stop >/dev/null || true
}
trap cleanup EXIT
docker compose exec -T backend /usr/local/bin/meiro-server create-user --email "$EMAIL" --password "$PASS" >/dev/null
# The Daily mix id is the same for every account and the server's libraries table has id as its only key
# (server bug): only exercise it when no other account holds that id.
OWNER=$(docker compose exec -T db psql -U meiro -d meiro -Atc "SELECT u.email FROM libraries l JOIN users u ON u.id=l.user_id WHERE l.id='bab208cb-a929-55cd-a091-c414d0e03864'" | tr -d '\r')
DM=0; if [ -z "$OWNER" ] || [[ "$OWNER" == e2e-android-* ]]; then DM=1; fi
echo "live user $EMAIL, daily-mix check: $DM (owner of the fixed id: '${OWNER:-none}')"
cd - >/dev/null
MEIRO_LIVE_URL="$URL" MEIRO_LIVE_EMAIL="$EMAIL" MEIRO_LIVE_PASSWORD="$PASS" MEIRO_LIVE_DAILYMIX="$DM" \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests 'com.muratkirazkaya.meiro.sync.LiveSyncTest' -i 2>&1 | grep -E "LIVE |LIVE:|PASS|FAIL|SKIP|requests=|BUILD|tests completed" || true
echo "---- summary file"; cat app/build/live-sync-summary.txt 2>/dev/null || echo "(no summary: the test did not finish)"
