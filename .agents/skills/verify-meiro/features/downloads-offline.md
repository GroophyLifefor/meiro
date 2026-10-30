# Downloads and offline

A user downloads a track to keep it on this device, sees it on the Downloads page with how it was last played, plays it with no network, and manages the two playback caches apart from each other and from the downloads.

## Sub-features

- `downloads-keep` downloads a track from its menu, copying its audio with ffmpeg into a store of its own, outside the playback cache.
- `downloads-page` lists what is kept, with the source, the size, and when each was last played, and removes one at a time.
- `downloads-offline` plays a downloaded track with no network, and says plainly when a track that is neither downloaded nor cached cannot play because the network is out of reach.
- `caches-ytm` keeps a number of recent YouTube Music songs in a folder the user chooses, in the playback cache card.
- `caches-soundcloud` keeps recent SoundCloud audio in a folder of its own, with a switch, a size limit in megabytes and a clear button.
- `caches-clear` clears either cache without touching the other or any downloaded track.

## How to get to it (user POV)

- Open any track's menu and click `Download` (it reads `Downloading...` while one is in flight, and `Remove download` once kept).
- Click `Downloads` in the navigation rail; each row plays on click and has a `Remove download of <title>` button.
- Settings > `Playback cache` holds the YouTube Music cache: songs kept, cache folder, and `Clear playback cache`.
- Settings > `SoundCloud cache` holds the SoundCloud cache: an on/off switch, a `Size limit` in MB, and `Clear SoundCloud cache`.

## Driving it with the headless tester

Preconditions:

- `a := newTestApp(t)`. `a.streams` resolves to a real audio file, and `a.downloads` is a store with a real directory, so a download runs ffmpeg on a local file with no network.
- The player decodes real audio on the null device, so playing a downloaded track works headlessly.

- **Download.** `p.openMenu("Search Result Song", "menu")`, then `p.click("Download", "downloaded")`. The toast reads `Downloaded Search Result Song`, and `a.downloads.Get(media.SourceYTM, "vid-4")` has a `File` and a `SizeBytes`.
- **Downloads page.** `a.router.Push("/downloads")`. The row shows `Search Result Song` and a line like `YouTube Music · 86.7 KB · not played yet`.
- **Last listened.** Click the row to play it; `a.downloads.Get(...).LastListenedAt` becomes set and the line shows `last listened <date>`.
- **Cache clear.** Settings > `Clear playback cache`; the Downloads page still lists the track and `a.downloads.Get` still finds it.
- **SoundCloud cache.** Settings shows `SoundCloud cache`, `Keep recent SoundCloud songs`, `Size limit` with a `Megabytes` field, and `Clear SoundCloud cache`.

## Gotchas

- The proof uses a real ffmpeg to copy a local sine file and a local HLS (m3u8); no network is touched. The before run fails because `internal/downloads` and the menu action do not exist yet, so it fails to compile.
- SoundCloud's cache is bounded by megabytes and kept in `Meiro SoundCloud Cache`, separate from `Meiro Audio Cache`; the two are separate `audio.Cache` values.
- A zero-size download result is treated as absent by `findAudio`, so a missing file falls through to the cache and then the network.
- Last-listen is recorded when audio actually starts, not when the row is clicked, so a failed play does not change it.
- Downloads are synced to the server later (A2f); nothing here leaves the device.
