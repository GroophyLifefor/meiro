# Android progress

Read this first in every session. A row is "done" only with evidence: the
command that was run and what it printed, or a screenshot path. "Tests pass"
alone is not evidence that a feature works (see the quality rules in
`PLAN.md`, "Bağlayıcı kalite kuralları").

Sources of truth:

- Design: `docs/android/mock/index.html`, its reference images in
  `docs/android/mock/ref/<screen>-<theme>.png` (370 x 824 CSS px), the element
  inventory in `docs/android/mock/inventory/<screen>.json` and the tokens in
  `docs/android/mock/tokens.json`. Regenerate with `tools/mockref`.
- Behaviour: `docs/android/PORTING.md` (desktop code to port, with line
  references) and the desktop Go code itself.
- Font: `fonts/GoogleSans.ttf` (variable, 100-1000), the same file the mock
  uses.

Toolchain (in the home directory, no sudo): JDK 21 at
`~/.local/share/jdks/jdk-21*`, Android SDK at `~/Android/Sdk`, AVD `meiro36`
(android-36, x86_64, google_apis). APKs are built here and installed with
`adb`; they are never committed or published.

## Milestones

| | Scope | Status | Evidence |
|---|---|---|---|
| A3a | Toolchain | done 2026-11-21 | `./gradlew :app:assembleDebug` builds; emulator boots with KVM |
| A3b | Spike: yt-dlp resolve and Media3 playback | done 2026-11-21 (emulator only) | `spike-results.txt`; resolve 3.4 s and READY 1.5 s after tap, re-run by the lead agent |
| M1 | App skeleton: theme from tokens, Google Sans, bottom bar, screen-vs-mock comparison, `check.sh` | done 2026-11-21 (screen `empty` only) | `./android/check.sh` prints PASS; side-by-side in `android/build/check/empty/`, emulator shots `android/build/check/emulator-{light,dark}.png`; negative tests: 2dp shift and the mock's 56px "+" (CONTAINMENT rule, also in `tools/mockref`) made it FAIL; now elements 34/34 matched, 0 mismatches, max bbox delta 1.00px, light+dark; pixel mismatch 2.5% (reported, not a gate) |
| M2 | Meiro sign-in, local store, sync engine (contract tests against server fixtures) | done 2026-11-21 (M2a engine, M2b sign-in UI + wiring) | M2b: screen `signin` 15/15 elements matched, 0 missing/extra, max bbox delta 1.00px, light+dark (`check.sh`; a 2dp shift made it FAIL); real flow on the emulator against the local dev server in `android/build/check/m2b/` (sign-in error snack, sign-in, shell, relaunch signed in, 401 back to sign-in), Room rows and logcat lines in the M2b report. `./gradlew :app:testDebugUnitTest` 124 pass; `./check.sh` PASS. Code: `data/`, `sync/`, `AppContainer.kt`, `ui/screens/SignInScreen.kt`. Deviations for the owner: see the M2b report |
| M3 | Playback: queue, Media3 session, lock screen, URL cache, pre-resolve | M3a done 2026-11-21 (engine, service, mini player; no list screens yet) | Emulator, real flow (`android/build/check/m3/`): `ytm:dQw4w9WgXcQ` plays 4.7 s after the request (resolve 3.5 s, visionos), `dumpsys media_session` PLAYING with advancing position; next to `ytm:fJ9rUzIMcZQ` 0.68 s (URL pre-resolved in 2.2 s while the first played, log `cache hit=true`); next to `sc:` (HLS) 0.44 s; home + 20 s still PLAYING, service foreground (type mediaPlayback), notification in `notification-shade-{light,dark}.png`; media key next/prev/pause/play via `cmd media_session dispatch`; preload hands track 2 to the player 19 s before the end and it takes over gaplessly; real HTTP 403 (damaged URL) and Wi-Fi off both re-resolve and resume at the same position; a finished play is in Room history (completed=1, played 66840 ms) and in the outbox; signed in to the local dev server the two plays reached `play_events` with the same ids and values (throwaway user deleted). `./gradlew :app:testDebugUnitTest` 162 pass (38 new: queue, URL cache, error messages, Watch, recording); `./check.sh` PASS (mini player is in the `empty` element comparison, 34/34). Code: `playback/`, `ui/components/MiniPlayer.kt`; debug entry `debug/DebugPlayActivity` |
| M4 | Home, Search, Library, playlist, album, artist screens | not started | |
| M5 | Downloads, audio cache, cover cache, offline | not started | |
| M6 | History, Daily mix, stats, settings, MusicBrainz linking | not started | |
| M7 | All 28 screens compared with the mock, run on Murat's phone | not started | |

## Waiting for Murat

- **Prod deploy of meiro-server** (`461cc41`): fixes a hole where any account
  could change another account's library by sending its id. Prod still runs
  the old code. Deploys need Murat's go-ahead.
- **Daily mix with more than one account:** the Daily mix id is the same for
  every account (`DeriveID` of a fixed name), and `libraries.id` is the
  primary key, so only the first account on a server can keep its Daily mix.
  After `461cc41` other accounts can no longer overwrite it; their own Daily
  mix simply does not reach the server. A fix needs a per-account id (desktop
  and phone together, plus moving the existing row) and is a design call.
- **Sign-in screen, things the mock does not show** (built the minimal way
  for now):
  - Errors use the mock's snack with the engine's English text ("The server
    did not accept the email and password.", "Your session ended. Sign in
    again to keep syncing.") in an otherwise Turkish UI.
  - No busy state while signing in; taps are ignored meanwhile.
  - "Hesapsız devam et" is not remembered, so a guest sees sign-in on every
    start.
  - After Back hides the keyboard, the password field keeps its focused look.

## Open items from M3a

- Mini player: no cover images yet (the mock's gradient placeholder, hue from the track key), no error surface (a track that cannot play is skipped, up to 3, and the reason is only in logcat and `PlayerUi.error`), tapping does nothing (full player is later).
- A play that is running when the process is killed (swipe away, force stop) is never filed (no `finish`): its history row stays at 0 ms and no event is queued. The desktop has the same hole on a crash; a phone is killed more often. Needs a decision (save progress every N s?).
- yt-dlp is updated in the app once a day before the first resolve (the shipped copy returned `client=ANDROID` URLs that answered 403 for some videos); no manual update or "update yt-dlp" message yet.
- Not built (later milestones): audio cache and downloads in `findAudio`, up-next generation (`autoPlay` is off, the queue ends where the chosen tracks end), shuffle/repeat buttons (queue logic is ported and tested), queue persistence across process death, Last.fm, loudness, episodes, source fallback (`nextSource`), `networkOnline` offline error.
- `POST_NOTIFICATIONS` is asked once, by the system dialog, when something first plays.
- Debug builds only: `DebugPlayActivity` (`--es items "ytm:<id>|title|artist|m:ss,sc:<url>"`, `--es action next|prev|pause|toggle|seek|break`); the spike service lost its intent filter so only the real service answers the media session action.

## Open items from M2a/M2b

- Periodic sync is WorkManager (15 min, the Android minimum; the desktop uses 5) plus a sync on start/foreground and after sign-in; run only on the emulator.
- Each play rewrites the history table (5000 rows took 235 ms on the JVM); measure on a phone.
- Sign-in errors and the "session ended" notice use the mock's snack with the engine's English messages (the mock has no error state on `signin`).

## Open items from the spike

- Not run on a real arm64 phone yet.
- Screen-off and swipe-away playback not tested.
- Single runs per measurement; numbers are rough.
- `YoutubeDL.version()` returns null before the first update; read the binary's
  `--version` instead.
