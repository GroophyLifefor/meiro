#!/usr/bin/env bash
# Cross-check with the DESKTOP sync client. Needs `go`. The repo is not touched: a copy of internal/sync and media goes
# to a temp module together with tools/crosscheck/main.go.txt. Local dev server only; throwaway e2e-android- user.
set -euo pipefail
cd "$(dirname "$0")/.."
ANDROID_DIR="$PWD"; REPO="$(cd .. && pwd)"
SERVER_DIR="${SERVER_DIR:-$(cd ../../meiro-server && pwd)}"
export JAVA_HOME="${JAVA_HOME:-$(echo ~/.local/share/jdks/jdk-21*)}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
URL="${MEIRO_LIVE_URL:-http://localhost:8080}"
case "$URL" in http://localhost:*|http://127.0.0.1:*) ;; *) echo "refusing non-local URL"; exit 2;; esac
WORK="$(mktemp -d)"; DUMP="$WORK/dump"; mkdir -p "$DUMP" "$WORK/mod/cmd/cross" "$WORK/mod/internal"
cp -r "$REPO/media" "$WORK/mod/media"; cp -r "$REPO/internal/sync" "$WORK/mod/internal/sync"; cp -r "$REPO/internal/fsutil" "$WORK/mod/internal/fsutil"
find "$WORK/mod" -name '*_test.go' -delete
printf 'module github.com/elianiva/meiro\n\ngo 1.27.3\n' > "$WORK/mod/go.mod"
cp tools/crosscheck/main.go.txt "$WORK/mod/cmd/cross/main.go"
(cd "$WORK/mod" && go build -o "$WORK/cross" ./cmd/cross)
cd "$SERVER_DIR"
STARTED=0
if ! curl -fs "$URL/healthz" >/dev/null 2>&1; then docker compose up -d db backend; STARTED=1; for i in $(seq 1 60); do curl -fs "$URL/healthz" >/dev/null 2>&1 && break; sleep 1; done; fi
EMAIL="e2e-android-cross-$(date +%s)@example.com"; PASS="e2e-pass-$RANDOM$RANDOM"
cleanup() { cd "$SERVER_DIR"; docker compose exec -T db psql -U meiro -d meiro -q -c "DELETE FROM users WHERE email LIKE 'e2e-android-%'" >/dev/null || true; [ "$STARTED" = 1 ] && docker compose stop >/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT
docker compose exec -T backend /usr/local/bin/meiro-server create-user --email "$EMAIL" --password "$PASS" >/dev/null
echo "== desktop (Go) seeds the account, TZ=Europe/Istanbul so times carry +03:00 offsets"
TZ=Europe/Istanbul "$WORK/cross" seed "$URL" "$EMAIL" "$PASS" "$DUMP"
cd "$ANDROID_DIR"
echo "== phone (Kotlin) reads it, writes back"
MEIRO_LIVE_URL="$URL" MEIRO_LIVE_EMAIL="$EMAIL" MEIRO_LIVE_PASSWORD="$PASS" MEIRO_GO_DUMP_DIR="$DUMP" \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests 'com.muratkirazkaya.meiro.sync.GoDesktopCrossTest' -i 2>&1 | grep -E "CROSS|FAILED|BUILD|AssertionError" || true
cat app/build/cross-check-summary.txt
echo "== desktop (Go) reads what the phone wrote"
"$WORK/cross" verify "$URL" "$EMAIL" "$PASS" | tee "$WORK/verify.txt"
grep -q 'list "Phone made list"' "$WORK/verify.txt" && grep -q 'key=phone-song-1 deleted=false' "$WORK/verify.txt" && grep -q 'key=https://soundcloud.com/p/q deleted=false' "$WORK/verify.txt" \
  && grep -q 'key=go-song-2 deleted=true' "$WORK/verify.txt" && grep -q 'id=7e57ab1e-0000-4000-8000-0000000000aa device=phone' "$WORK/verify.txt" && echo "CROSS-CHECK PASS: the desktop client reads everything the phone wrote" || { echo "CROSS-CHECK FAIL"; exit 1; }
