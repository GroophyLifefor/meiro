#!/usr/bin/env bash
# One command that decides whether the Android app matches the mock and still builds.
#   1 unit tests            2 Roborazzi render + mock comparison (element-level is the gate)
#   3 lint                  4 assembleDebug
#   5 install + launch on the running emulator, screenshots of the Kütüphane tab in light and dark
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
rm -rf "$OUT/empty" "$OUT/roborazzi"
declare -a RESULTS=()
FAILED=0
step() { # name, status, detail
  RESULTS+=("$2  $1${3:+  ($3)}")
  [ "$2" = FAIL ] && FAILED=1
}
gw() { ./gradlew "$@" -q 2>&1 | grep -v "SDK XML\|^w: "; return "${PIPESTATUS[0]}"; }

echo "== 1/5 unit tests"
if gw :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests 'com.muratkirazkaya.meiro.YtdlpTest' >"$OUT/unit.log"; then
  step "unit tests (YtdlpTest)" PASS
else tail -20 "$OUT/unit.log"; step "unit tests" FAIL "see $OUT/unit.log"; fi

echo "== 2/5 Roborazzi render + mock comparison"
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests 'com.muratkirazkaya.meiro.ui.EmptyScreenTest' -q >"$OUT/compare.log" 2>&1
CMP=$?
for t in light dark; do
  f="$OUT/empty/summary-$t.txt"
  if [ -f "$f" ]; then
    sed -n 1,2p "$f"
    n=$(sed -n '3,$p' "$f" | grep -c .)
    [ "$n" -gt 0 ] && { echo "  mismatches ($t):"; sed -n '3,$p' "$f" | grep . | sed 's/^/    - /'; }
  else echo "  no summary for $t"; CMP=1; fi
done
if [ $CMP -eq 0 ]; then step "mock comparison (element-level, light+dark)" PASS "side-by-side: $OUT/empty/side-by-side-{light,dark}.png"
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
      adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2
      # tap the Kütüphane tab: find its bounds in the accessibility tree instead of guessing pixels
      adb shell uiautomator dump /sdcard/meiro-ui.xml >/dev/null 2>&1
      adb pull /sdcard/meiro-ui.xml "$OUT/emulator-ui-$theme.xml" >/dev/null 2>&1
      xy=$(python3 - "$OUT/emulator-ui-$theme.xml" <<'PY'
import re,sys
s=open(sys.argv[1],encoding='utf-8').read()
best=None
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s):
    if m.group(1)=="Kütüphane":
        x0,y0,x1,y1=map(int,m.groups()[1:]); best=((x0+x1)//2,(y0+y1)//2)
if best: print(best[0],best[1])
PY
)
      if [ -z "$xy" ]; then ok=0; echo "  could not find the Kütüphane tab in the UI tree ($theme)"; else
        adb shell input tap $xy; sleep 2; shot $theme; echo "  screenshot $theme: $OUT/emulator-$theme.png"; fi
    done
    [ "$ORIG_NIGHT" = yes ] && adb shell cmd uimode night yes >/dev/null || adb shell cmd uimode night no >/dev/null
    if [ $ok -eq 1 ]; then step "install + launch + screenshots" PASS "$OUT/emulator-{light,dark}.png"
    else step "install + launch + screenshots" FAIL; fi
  else tail -5 "$OUT/install.log" "$OUT/launch.log"; step "install/launch" FAIL; fi
fi

echo
echo "================ SUMMARY ================"
printf '%s\n' "${RESULTS[@]}" | tee "$OUT/status.txt"
if [ $FAILED -eq 0 ]; then echo "PASS"; echo PASS >>"$OUT/status.txt"; exit 0
else echo "FAIL"; echo FAIL >>"$OUT/status.txt"; exit 1; fi
