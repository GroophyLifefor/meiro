# MusicBrainz links

A song in one of the user's playlists, and the song's artist, can be tied to a MusicBrainz record. The app ties it alone only when sure. Otherwise the song stays unknown and the listener may link it, mark it as not on MusicBrainz, or leave it.

## Sub-features

- `mb-auto-link` ties a song just added to a playlist to its recording, and its artist, when the title and the artist agree and both lengths are known and close.
- `mb-link-button` waits beside an unknown song and opens the search; it never asks for anything.
- `mb-link-dialog` lists the records ordered by how well they fit and then by listeners, with `Song` and `Artist` tabs, `Not on MusicBrainz`, `Remove link` for a song already marked, and `Link`.
- `mb-link-change` opens the dialog again from the `matched` or `other` mark.

## How to get to it (user POV)

- Open `Playlists`, open a playlist, and click `Link to MusicBrainz` beside an `unknown` song.
- Click the `matched` or `other` mark of a song (`Change the MusicBrainz link`) to change or remove it.

## Driving it with the headless tester

Preconditions:

- `a := newTestApp(t)` and `a.run = func(work func()) { work() }`. Set the recording answers with `fakeMB(a).found` (keyed by title), the artist answers with `fakeMB(a).artists` and `popularity`.
- Make the list with `a.libraries.Create` and `AddItem`, then `a.router.Push("/libraries")` and `a.libSelected = list.ID`. `tt := ui.NewTester(a.view, 1000, 900)`.

- **Alone.** `a.autoLink(listID, item)` then `pollUntil` the state is `library.Matched`. A recording of another length, with no length, or two artists for one title leaves the song `unknown`.
- **By hand.** `p.click("Link to MusicBrainz", "dialog")`, poll until `100% fit` shows, `p.click("Link", "linked")`. The list's item has `State` `matched`, the `MBID` and the artist's.
- **Other and remove.** `p.click("Not on MusicBrainz", ...)`, then `p.click("Change the MusicBrainz link", ...)` shows `Remove link`.
- **Artists.** In the dialog, `p.click("Artist", ...)`; `a.link.candidates` is ordered by fit, then listeners.

## Gotchas

- The row is not played by pressing the link button or the mark.
- The playlist's own `Playlists` page shows no marks until the playlist is opened.
