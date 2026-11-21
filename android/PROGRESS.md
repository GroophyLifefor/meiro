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
| M2 | Meiro sign-in, local store, sync engine (contract tests against server fixtures) | M2a done 2026-11-21 (engine, no UI); M2b sign-in UI not started | `./gradlew :app:testDebugUnitTest` 122 tests pass; `tools/live-sync.sh` two engines vs local dev server 10/10 scenarios; `tools/cross-check.sh` PASS (desktop Go client reads what the phone wrote and the reverse); `./check.sh` PASS. Code: `data/` (stores, Room), `sync/` (client, outbox, engine). Details in the M2a report |
| M3 | Playback: queue, Media3 session, lock screen, URL cache, pre-resolve | not started | |
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

## Open items from M2a

- The engine has run on the JVM and Robolectric only, not on the emulator.
- `EncryptedTokenStore` never ran (needs the Keystore).
- No debug network config yet, so a debug build cannot reach a local
  `http://` server.
- WorkManager scheduling (the 5-minute sync) is not built.
- Each play rewrites the history table (5000 rows took 235 ms on the JVM);
  measure on a phone.

## Open items from the spike

- Not run on a real arm64 phone yet.
- Screen-off and swipe-away playback not tested.
- Single runs per measurement; numbers are rough.
- `YoutubeDL.version()` returns null before the first update; read the binary's
  `--version` instead.
