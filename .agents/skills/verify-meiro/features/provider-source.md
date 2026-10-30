# Provider source

A song that YouTube Music and SoundCloud both hold is one row. The row shows a mark for each provider, the mark of the source it plays from is picked out, and pressing the other mark switches that song. When the source it is on fails, the other provider takes over with a short message.

## Sub-features

- `source-badges` draws a button for each provider that holds a song, the one playing ringed and the others faded, with `playing: <name>` beside them.
- `source-choose` switches a song to the other provider from its row, in a search, a page, the queue, a list, a download or the player bar.
- `source-default` plays a song from the provider it is downloaded from, and otherwise from the first of the preference order that holds it.
- `source-order` lists the two providers in Settings > Music sources > Preferred provider in the order Meiro prefers them, with an up and a down arrow on each row that move it one place; the top row's up arrow and the bottom row's down arrow are disabled, and the order is kept in `settings.json` as `providerOrder`.
- `source-queue` keeps the chosen source on the queue record, so it plays from there.
- `source-fallback` tries the next provider that holds the song when one fails, updates the queue's source, and shows a two second message `X failed, playing from Y`.
- `source-exhausted` shows the usual play error when every provider that holds a song fails.

## How to get to it (user POV)

- Search a song two providers hold, as `shared` finds `Cloud Song`.
- In its row, press the SoundCloud mark beside the YouTube Music one.
- In the player bar, press the other provider's mark for the track playing.
- Play the row after switching; the queue keeps the source.

## Driving it with the headless tester

Preconditions:

- `resetFakes()` first: the fake YouTube and SoundCloud answer a search from `harness_test.go`.
- `a, r := tonesApp(t, 1)` then `a.soundcloudStreams = audio.NewURLs(r.resolve)`, so a click needs no yt-dlp and the fallback resolves real audio.
- `a.router.Push("/search")`, `tt := ui.NewTester(a.view, 1000, 700)`.

- **Badges.** `p.click(searchPlaceholder, "search-focused")`, `tt.Type("shared")`, `tt.Key(0, ui.KeyEnter)`, `p.step("results")`. The merged row shows both marks and `playing: YouTube Music`.
- **Choose.** `p.click("Play from SoundCloud", "switched")`. The row shows `playing: SoundCloud`; `a.search.items[0].Source` is `media.SourceSoundCloud`.
- **Player bar.** `p.click("Cloud Song", "playing")`. `a.current.Source` and `a.queue.current().Source` are SoundCloud, `r.asked` the page URL, and the player bar shows the marks with `playing: SoundCloud`.
- **Fallback.** Set `r.err["vid-cloud"] = errors.New("Video unavailable")` before the click (the YouTube Music video ID of the shared song). Clicking the row resolves YouTube Music, fails, and plays from SoundCloud: the player bar shows `playing: SoundCloud` and the toast reads `YouTube Music failed, playing from SoundCloud`. `a.noticeTimeout` is `fallbackNotice`, two seconds.
- **Order.** Verified by `TestSettingsPreferredProviderMovesAndKeeps`. On `/settings`, `Search settings` filters to `preferred provider` and a 1000x900 window shows the `Preferred provider` card under `Music sources` with `YouTube Music` above `SoundCloud`. `tt.Click("Move YouTube Music down")` moves SoundCloud to the top and `a.providerOrder()[0]` is `media.SourceSoundCloud`; `a.saver.Path` is set, so `settings.Load(path).ProviderOrder` reads `["soundcloud", "ytm"]` back. The top row's up arrow and the bottom row's down arrow are disabled: clicking them leaves the order unchanged. The unified search then plays a shared song from SoundCloud by default.

## Gotchas

- The toast lives one frame: read `p.step` right after the click that failed over.
- `a.notice` is set on the main thread by the fallback; the view turns it into the toast and clears it, so a proof reads the screen, not `a.notice`, after a step.
- A single-source song shows no switch: only a song two providers hold gets two marks.
- The source a list entry knows is written to the app's own playlists and downloads when the song was added while two providers held it; the queue record always keeps it.
