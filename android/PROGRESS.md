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
| M4 | Home, Search, Library, playlist, album, artist screens | M4a done 2026-11-21 (Library + playlist); M4b done 2026-11-22 (Search, not committed); Home, album, artist not started | **M4b:** `./check.sh` PASS. Element comparison, light+dark: `search` 43/43 matched, `search-done` 68 matched on screen + 63 with the list scrolled by 532px (preview row, Albümler shelf, Listeler), 101 of its 102 elements compared; the 102nd, the "Listeler" chip, is off screen to the right in the mock and a test scrolls the chip row to it and taps it (`listsChipIsReachedByScrollingTheChips`); 0 missing, 0 extra, max bbox delta 1.00px outside the documented wrap-content text tolerances (`app/src/test/resources/mock-keys/search*.json`); a 2dp taller recent row made `search` FAIL (8 mismatches). Stress (`SearchStressTest`, 24 renders: 150-char title, long query, long artist/album/list names, 370/320dp, font 1.0/1.3, light+dark): 0 failures. Parser tests against real signed-out responses recorded by `tools/record-ytm-fixtures.sh` (search "daft punk", the Songs-filtered search, its continuation, suggestions for "daft p"; visitor data and tracking params removed) and a real `scsearch50:` output; merge, controller (arrival order, timeout, fallback, filters, next page, recent) tests. `./gradlew :app:testDebugUnitTest` 264 pass (65 new). Real flow on the emulator against the local dev server (throwaway user deleted) in `android/build/check/m4b/`: "daft punk" YouTube Music answer in 0.5-1.1 s, SoundCloud (50) in 18.7-28.5 s, merged rows (`real-merged-scrolled-light.png`: YT rows that gained a SoundCloud ref, SC-only rows after every 3), light+dark (`real-results-*`), tap on a result played it (`dumpsys media_session` PLAYING, position advancing) and `next` went on to the next result row, a SoundCloud track (PLAYING), "Şarkılar" chip ran the filtered search and scrolling to the end loaded 3 more pages, recent searches survived `am force-stop` + restart, × removed one, Temizle emptied the list (still empty after restart), network off fell back to "Ağ yok: ..." (0 local tracks). Code: `youtube/`, `search/`, `ui/screens/SearchScreen.kt`, `ui/components/SearchParts.kt`, `ui/SearchMapping.kt`, `playback/YtdlpSetup.kt`. **M4a:** `./check.sh` PASS. Element comparison with the mock, light+dark: `library` 59/59 matched, `playlist` 60 matched (all on screen) + 84 matched with the list scrolled to its end (link buttons, playing row with equaliser, downloaded mark, "Benzer şarkılar"), 0 missing, 0 extra, max bbox delta 1.00px; wrap-content text widths have documented tolerances (`toleranceNote` in `app/src/test/resources/mock-keys/{library,playlist}.json`); a 2dp row shift made `library` FAIL. Stress (`StressTest`, 24 renders, structural rules only): a list with a 120-char name, a track with a 150-char title and long artist, light+dark at 370dp, 320dp, font 1.3 and both: 0 failures (a title that no longer ellipsizes made it FAIL). Real flow on the emulator against the local dev server in `android/build/check/m4/` (sign-in, Kütüphane lists both seeded lists with Coil covers, list opens, back returns, row tap plays that row, list play starts at row 1, shuffle starts at a random row, SC row plays, FAB sheet creates a list that reached `libraries` on the server). `./gradlew :app:testDebugUnitTest` 199 pass. Code: `ui/screens/{LibraryScreen,PlaylistScreen,NewListSheet}.kt`, `ui/components/Rows.kt`, `ui/ListMapping.kt`, `AppContainer.createList`. Cover fix (`build/check/m4fix/`): YouTube video thumbnails (`i.ytimg.com/vi/...`, 4:3 with black bars) are trimmed of their bars before the square crop (`LetterboxTrim`), the mini player shows the track's real cover and only the sources the track has (the mock's `srcIcons`); `CoverRealDataTest` fails without each fix (real-data screenshots `playlist-playing-row2-{light,dark}.png`) |
| M5 | Downloads, audio cache, cover cache, offline | not started | |
| M6 | History, Daily mix, stats, settings, MusicBrainz linking | not started | |
| M7 | All 28 screens compared with the mock, run on Murat's phone | not started | |

## Waiting for Murat

- **meiro-server `461cc41` on prod** (fixes a hole where any account could
  change another account's library by sending its id): prod (Coolify)
  auto-deploys every push to `main`; `bca896a` was live a minute after its
  push (checked in the prod CSS), so `461cc41` should be too. Not checked by
  behaviour on prod (that needs two prod accounts), and the desktop app has
  not synced with prod since. Murat allowed server deploys on 2026-11-21 as
  long as the desktop app keeps working.
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

## M4a: inert controls (rendered as the mock shows them, do nothing yet)

Nothing is forgotten if each of these is built in the milestone named:

- Library: sort button in the top bar (later: sort order), the account avatar (account sheet), the ⋮ button of every list (list menu: copy, download all, rename, link all, delete), the "YouTube Music" tab (not shown at all: needs YouTube Music sign-in).
- Playlist: search and ⋮ in the top bar (list menu), download, edit order, share, the "Ekleme sırası" sort button, the ⋮ of every track (track menu), the MusicBrainz link button of a track (link sheet), the downloaded mark (always off until M5 downloads), "Benzer şarkılar" (the section and its rows are built and compared with the mock, but nothing feeds it, so the app hides it until related tracks exist).
- Mini player: tapping it opens nothing (full player is later).

## M4b: inert controls (rendered as the mock shows them, do nothing yet)

- Search: the account avatar in the top bar; artist rows (artist page, M4/M6); album tiles (album page); list rows and their ⋮ (playlist page of a YouTube Music list, list menu); the ⋮ of every track (track menu); the MusicBrainz link buttons of tracks and artists (link sheet, M6).

## M4b: deviations, for the owner

- **SoundCloud timeout is 45 s on the phone, not 7 s** (`SearchController.SOUNDCLOUD_TIMEOUT_MS`; YouTube Music keeps 7 s). With 7 s SoundCloud never answered on the emulator: `scsearch50:` through the bundled yt-dlp took 18.7 s and 28.5 s (Python start plus one HEAD request per result inside yt-dlp to check the artwork's file type). YouTube Music rows still show within about a second; SoundCloud rows join when they arrive, so the Şarkılar list changes under the user 20-30 s later. Alternatives for Murat: fewer SoundCloud results (10 took 7.3 s once, another run did not finish in 20 s), or keep 7 s and accept that SoundCloud is missing on slow phones. Not measured on a real phone.
- yt-dlp gets `--cache-dir <app cache>/yt-dlp` for the search (youtubedl-android passes `--no-cache-dir`), so SoundCloud's client id is kept between searches.
- **Sanatçılar** shows the artists of the YouTube Music answer (the lead card first), not MusicBrainz (M6): at most 3 under "Hepsi", all under the "Sanatçılar" chip. Each uses the mock's unlinked-artist layout ("Anıl Kaya" row): the YouTube Music logo, the text "YouTube Music sanatçısı" (not in the mock) and the inert link button. The mock's MusicBrainz row ("MusicBrainz · Fransa · elektronik") is built and compared with the mock but nothing feeds it yet. YouTube Music "Profile" channels are not shown as artists.
- **Hepsi** shows at most 8 tracks; the "Şarkılar" chip shows all and loads the next page at the end of the list (the mock fixes no count). Songs and music videos both count as tracks; podcast episodes and podcasts are left out.
- **Filter chips** run YouTube Music's own filter (`params` of its chip, by the English label): Şarkılar = "Songs", Sanatçılar = "Artists", Albümler = "Albums", Listeler = "Featured playlists" (YouTube Music's own lists; "Community playlists" is not offered). SoundCloud is asked only for Hepsi and Şarkılar (desktop rule), and its answer for the same query is reused instead of searched again. Before YouTube Music has answered a chip only narrows what is shown.
- **Provider chips** toggle a provider's rows on and off; the last one on cannot be turned off. Turning YouTube Music off hides artists, albums and lists too (they come only from it). The mock shows only the selected state; the unselected one uses the mock's `.chip` style.
- Tracks the app has no MusicBrainz link for show the link button, as in M4a, so every search row shows it until M6.
- "önizleme" is shown for a SoundCloud-only track whose length is 29-31 s (SoundCloud's preview).
- Song meta is "artists · length" (the byline's linked artists joined ", "); YouTube Music gives no length for most rows of an unfiltered search, so those rows show the artist alone. Songs inside YouTube Music's artist card name no artist; the app credits them with the card's artist (the desktop shows "Song").
- A track is listed once per search even when two result pages carry it (same video id). SoundCloud rows take the largest listed artwork (the desktop leaves SoundCloud rows without a cover).
- Album tile meta drops the kind word: "Album • Daft Punk • 2013" becomes "Daft Punk · 2013". List rows say "YouTube Music listesi".
- No loading or error state is shown while a search runs or when one provider fails (the mock has none); the section simply fills when an answer arrives.
- Both providers failing falls back to the mock's offline note "Ağ yok: sonuçlar bu cihazdan, indirilenlerden ve önbellekten." over matching tracks of the stored lists (downloads and the audio cache come in M5; the desktop searches those).
- "Son aramalar" with no recent searches is hidden (the mock has no empty state). Recent searches: newest first, at most 8, a repeat in any case moves up (desktop `Remember`), stored in SharedPreferences on the device only.
- Search suggestions (`music/get_search_suggestions`) are parsed and tested but not shown: the mock has no suggestion panel.
- The search bar scrolls with the results, as the mock's does (only the top bar stays).
- Key maps were generated with a script that is not in the repo (scratchpad); the maps themselves are in `mock-keys/search*.json`.

## M4a: deviations from the mock, for the owner

- Playlist test state: the mock draws the bottom bar with "İndirilenler" selected on `playlist` (it reaches the screen from another tab); the app keeps "Kütüphane" selected. The comparison uses the mock's state, so this one difference is not machine-checked.
- Library rows: the mock's "Daily mix" row ("Meiro · bugün · 30 parça", opens `mix`) and "Hidra albümünden kopya" provenance cannot come from the store yet; every list shows "Meiro · N parça" plus ", YT a, SC b" only when it holds both providers. Lists are in store order (oldest first); the mock fixes no order.
- Tracks the server knows no MusicBrainz link for show the link button (`unknown`), as the mock does for unmatched tracks. The server never sets a track's status (`PORTING.md`), so until M6 every track of a real list shows it and "eşleşen" counts 0.
- A list's cover is its own picture if it has one, else its first track's picture, else the mock's gradient (hue from the list id); track covers are the track's thumbnail, else the gradient. A SoundCloud track from the server carries no thumbnail unless the desktop pushed one.
- Creating a list: the sheet's field starts empty (the mock shows the sample "Yaz akşamları"); "Oluştur" with an empty name does nothing; the snack text is the mock's `“name” oluşturuldu`; the new list is not opened (the mock does not).
- Duplicate list names are allowed (the store has no rule against it).
- At font scale 1.3 and 320dp the bottom bar labels ("Kütüphane", "İndirilenler") end in an ellipsis (one-line label in the mock's own CSS; the stress rule allows a one-line ellipsis, it is not a failure but a visible truncation).
- The notification permission dialog appears again each time the activity is recreated (theme change) while something is queued; it is M3a behaviour, left alone.
- Cover shadow of the playlist hero uses Compose's shadow (elevation 16, black 35%), not the mock's `0 10px 30px`; visible only in the pixel diff, not a gate.

## Open items from M3a

- Mini player: no error surface (a track that cannot play is skipped, up to 3, and the reason is only in logcat and `PlayerUi.error`), tapping does nothing (full player is later).
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
