# Playlists

A user makes their own lists of tracks from either source, adds tracks to them from any track's menu, plays a whole list as one mixed queue, and renames, deletes or edits a list. The lists are kept on this device.

## Sub-features

- `playlists-make` makes a list from the Playlists page, or while adding a track.
- `playlists-add` puts any track, from either provider, into a list from its menu.
- `playlists-dedupe` refuses a track the list already holds, whether it is the same track or the same recording from another provider, and says it is already there.
- `playlists-play` plays a whole list as one queue, in its own order, mixing YouTube Music and SoundCloud tracks.
- `playlists-edit` renames, deletes, and takes a track out of a list.
- `playlists-state` marks each track with whether the app matched it to a known recording; with no recording to compare it with, it is `unknown`.
- `playlists-soundcloud` shows the app's own lists on the Library page while SoundCloud is the source, with a note that SoundCloud has no account sign-in here.

## How to get to it (user POV)

- Click `Playlists` in the navigation rail.
- Click `New playlist`, type a name, then `Save`.
- Hover a track row and click `More options for <title>`, then `Add to library`, and choose a list or `New playlist`.
- On the Playlists page, hover a list and click `More options for <name>` for `Rename` or `Delete playlist`; open a list and use `Play` or a track's `Remove from playlist`.

## Driving it with the headless tester

Preconditions:

- `a := playbackApp(t)` or `newTestApp(t)`. No network; the fake YouTube and the fake SoundCloud runner answer searches.
- The store is in memory unless a test sets `a.libraries` to a path itself; `TestLibrariesSurviveARestart` proves the file.

- **Make a list.** On `/libraries`, `p.click("New playlist", "name-dialog")`, `tt.Type("Road trip")`, `p.click("Save", "saved")`. The page opens the new list, which is empty. The name lives in `a.libraries.List()`.
- **Add a track.** `p.openMenu("Search Result Song", "menu")`, `p.click("Add to library", "picker")`, then either `p.click("Add to Road trip", ...)` or `p.click("New playlist", ...)`. The toast reads `Added “<title>” to “<list>”`.
- **Dedupe.** Adding the same track again leaves the list at the same length and toasts `“<title>” is already in “<list>”`. Adding the same recording from the other provider is refused the same way. The second view is `a.libraries.Get(id).Items`.
- **Play a list.** Open the list and click `Play`. `a.queue.len()` is the list's length and `a.current.Key()` is its first track; a YouTube Music track is keyed by video ID and a SoundCloud one by page URL. The headless tester cannot verify sound; `TestMixedQueuePlaysBothProviders` proves each track resolves through its own resolver.
- **Edit a list.** `Rename` and `Delete playlist` are on the list's row menu; `Remove from playlist` is on a track's menu inside the list.
- **SoundCloud note.** While SoundCloud is the provider, the Library page (`/library`) shows these lists with the note `SoundCloud has no account sign-in here, so there is no service library. These playlists are kept in Meiro.`

## Gotchas

- A route change animates the page in; call `tt.Advance(time.Second)` before a screenshot that must show the new page, or the frame is the transition's first one.
- The state chip reads `unknown` for a track the app has no recording to compare; the metadata matching that would mark one `matched` is not wired to the lists yet.
- The lists are local. Nothing here is read from or written to an account; syncing is a later step.
