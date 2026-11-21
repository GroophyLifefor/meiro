#!/usr/bin/env bash
# One command that decides whether the Android app matches the mock and still builds.
#   1 unit tests            2 Roborazzi render + mock comparison (element-level is the gate)
#   3 lint                  4 assembleDebug
#   5 install + launch on the running emulator, screenshots of the sign-in screen and of the Kütüphane tab (reached with "Hesapsız devam et") in light and dark
# Everything lands in android/build/check/ (gitignored). Exit code 0 only if every step passed.
set -u
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(echo ~/.local/share/jdks/jdk-21*)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
OUT="$PWD/build/check"
PKG=com.muratkirazkaya.meiro
mkdir -p "$OUT"
rm -f "$OUT"/*.log "$OUT"/emulator-*.png "$OUT"/status.txt
rm -rf "$OUT/empty" "$OUT/signin" "$OUT/library" "$OUT/playlist" "$OUT/search" "$OUT/search-done" "$OUT/stress" "$OUT/roborazzi"
declare -a RESULTS=()
FAILED=0
step() { # name, status, detail
  RESULTS+=("$2  $1${3:+  ($3)}")
  [ "$2" = FAIL ] && FAILED=1
}
gw() { ./gradlew "$@" -q 2>&1 | grep -v "SDK XML\|^w: "; return "${PIPESTATUS[0]}"; }

echo "== 1/5 unit tests"
# M2a: the sync engine's tests (JVM, MockWebServer, Room under Robolectric) are part of this step. The live tests
# (tools/live-sync.sh) are excluded here: MEIRO_LIVE_URL must be unset or Gradle would run only those.
unset MEIRO_LIVE_URL
if gw :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests 'com.muratkirazkaya.meiro.YtdlpTest' --tests 'com.muratkirazkaya.meiro.sync.*' --tests 'com.muratkirazkaya.meiro.data.*' --tests 'com.muratkirazkaya.meiro.playback.*' --tests 'com.muratkirazkaya.meiro.ui.ListMappingTest' --tests 'com.muratkirazkaya.meiro.youtube.*' --tests 'com.muratkirazkaya.meiro.search.*' >"$OUT/unit.log"; then
  step "unit tests (YtdlpTest + sync/data/playback engine tests + YouTube Music search parser against recorded responses, SoundCloud parse, provider merge, search controller)" PASS
else tail -20 "$OUT/unit.log"; step "unit tests" FAIL "see $OUT/unit.log"; fi

echo "== 2/5 Roborazzi render + mock comparison"
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests 'com.muratkirazkaya.meiro.ui.EmptyScreenTest' --tests 'com.muratkirazkaya.meiro.ui.SignInScreenTest' --tests 'com.muratkirazkaya.meiro.ui.LibraryScreenTest' --tests 'com.muratkirazkaya.meiro.ui.PlaylistScreenTest' --tests 'com.muratkirazkaya.meiro.ui.PlaylistScrolledTest' --tests 'com.muratkirazkaya.meiro.ui.SearchScreenTest' --tests 'com.muratkirazkaya.meiro.ui.SearchDoneScreenTest' --tests 'com.muratkirazkaya.meiro.ui.SearchDoneScrolledTest' --tests 'com.muratkirazkaya.meiro.ui.StressTest' --tests 'com.muratkirazkaya.meiro.ui.SearchStressTest' --tests 'com.muratkirazkaya.meiro.ui.CoverRealDataTest' -q >"$OUT/compare.log" 2>&1
CMP=$?
# playlist-scrolled: the same screen scrolled to its end, so the mock's offscreen elements are compared too.
# search-done-scrolled: the same for the search results (the preview row, "Albümler" and "Listeler").
for screen in empty signin library playlist playlist-scrolled search search-done search-done-scrolled; do
  for t in light dark; do
    f="$OUT/$screen/summary-$t.txt"; [ "$screen" = playlist-scrolled ] && f="$OUT/playlist/summary-$t-scrolled.txt"
    [ "$screen" = search-done-scrolled ] && f="$OUT/search-done/summary-$t-scrolled.txt"
    if [ -f "$f" ]; then
      echo "  [$screen]"; sed -n 1,2p "$f"
      n=$(sed -n '3,$p' "$f" | grep -c .)
      [ "$n" -gt 0 ] && { echo "  mismatches ($screen, $t):"; sed -n '3,$p' "$f" | grep . | sed 's/^/    - /'; }
    else echo "  no summary for $screen $t"; CMP=1; fi
  done
done
if [ $CMP -eq 0 ]; then step "mock comparison (element-level, empty + signin + library + playlist + search + search-done, light+dark) and stress renders (long names, 320dp, font 1.3), real-data cover rules (no letterbox band, mini player cover and source icons)" PASS "side-by-side: $OUT/{empty,signin,library,playlist,search,search-done}/side-by-side-{light,dark}.png, stress: $OUT/stress/"
else step "mock comparison (element-level)" FAIL "see above, $OUT/compare.log"; fi

echo "== 3/5 lint"
if gw :app:lintDebug >"$OUT/lint.log"; then step "lint (lintDebug)" PASS
else tail -20 "$OUT/lint.log"; step "lint" FAIL "see $OUT/lint.log and app/build/reports/lint-results-debug.html"; fi

echo "== 4/5 assembleDebug"
if gw :app:assembleDebug >"$OUT/assemble.log"; then step "assembleDebug" PASS
else tail -20 "$OUT/assemble.log"; step "assembleDebug" FAIL "see $OUT/assemble.log"; fi

echo "== 5/5 emulator"
APK=app/build/outputs/apk/debug/app-debug.apk
shot() { adb exec-out screencap -p >"$OUT/emulator-$1.png"; }
if ! adb get-state >/dev/null 2>&1; then step "emulator screenshots" FAIL "no device $ANDROID_SERIAL"
elif [ ! -f "$APK" ]; then step "emulator screenshots" FAIL "no APK"
else
  ORIG_NIGHT=$(adb shell cmd uimode night | grep -o 'yes\|no\|auto' | head -1)
  if adb install -r "$APK" >"$OUT/install.log" 2>&1 && adb shell am force-stop $PKG &&
     adb shell am start -W -n $PKG/.MainActivity >"$OUT/launch.log" 2>&1; then
    ok=1
    for mode in no yes; do
      theme=light; [ $mode = yes ] && theme=dark
      adb shell cmd uimode night $mode >/dev/null; sleep 2
      adb shell pm clear $PKG >/dev/null; adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
      # sign-in screen first (fresh data: no account), then "Hesapsız devam et" opens the shell
      shot signin-$theme; echo "  screenshot signin-$theme: $OUT/emulator-signin-$theme.png"
      for label in "Hesapsız devam et" "Kütüphane"; do
        adb shell uiautomator dump /sdcard/meiro-ui.xml >/dev/null 2>&1
        adb pull /sdcard/meiro-ui.xml "$OUT/emulator-ui-$theme.xml" >/dev/null 2>&1
        xy=$(python3 - "$OUT/emulator-ui-$theme.xml" "$label" <<'PY'
import re,sys
s=open(sys.argv[1],encoding='utf-8').read()
best=None
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s):
    if m.group(1)==sys.argv[2]:
        x0,y0,x1,y1=map(int,m.groups()[1:]); best=((x0+x1)//2,(y0+y1)//2)
if best: print(best[0],best[1])
PY
)
        if [ -z "$xy" ]; then ok=0; echo "  could not find \"$label\" in the UI tree ($theme)"; break; fi
        adb shell input tap $xy; sleep 2
      done
      shot $theme; echo "  screenshot $theme: $OUT/emulator-$theme.png"
    done
    [ "$ORIG_NIGHT" = yes ] && adb shell cmd uimode night yes >/dev/null || adb shell cmd uimode night no >/dev/null
    if [ $ok -eq 1 ]; then step "install + launch + screenshots" PASS "$OUT/emulator-{signin-,}{light,dark}.png"
    else step "install + launch + screenshots" FAIL; fi
  else tail -5 "$OUT/install.log" "$OUT/launch.log"; step "install/launch" FAIL; fi
fi

echo
echo "================ SUMMARY ================"
printf '%s\n' "${RESULTS[@]}" | tee "$OUT/status.txt"
if [ $FAILED -eq 0 ]; then echo "PASS"; echo PASS >>"$OUT/status.txt"; exit 0
else echo "FAIL"; echo FAIL >>"$OUT/status.txt"; exit 1; fi
