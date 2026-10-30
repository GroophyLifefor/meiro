# MusicBrainz artists

A user searches, sees the artists MusicBrainz matched under the results, opens one, and sees its top recordings with where each can be played.

## Sub-features

- `mb-artist-section` shows the artists a submitted query matched, most listened to first, under the search filters and above the provider's own results.
- `mb-artist-page` opens an artist's MusicBrainz page, listing its top recordings from ListenBrainz.
- `mb-recording-link` marks each recording with the provider it plays on, and plays the ones that have a link.
- `mb-unavailable` says, in one line, when MusicBrainz or ListenBrainz is out of reach, without blocking the provider search.

## How to get to it (user POV)

- Search for a song or an artist in the top bar, then press Enter, or choose a suggestion.
- Under the filters, the `Artists` heading lists the matched artists. Click one to open its page.
- On the artist page, a recording with a `YouTube Music` or `SoundCloud` badge plays when clicked; one marked `No link` is greyed out and does nothing.

## Driving it with the headless tester

Preconditions:

- `a := newTestApp(t)`, then set the fake metadata answers with `fakeMB(a)`: `artists`, `popularity`, `recordings`, `links`.
- `a.router.Push("/search")`, `tt := ui.NewTester(a.view, 1000, 700)`.

- **Section.** `p.click(searchPlaceholder, "focus")`, `tt.Type("nova")`, `tt.Key(0, ui.KeyEnter)`, `p.step("results")`. `Artists`, `Nova Artist` and `Second Artist` show. `a.artists.artists[0].Name` is the one with more ListenBrainz listeners, whatever order MusicBrainz returned.
- **Artist page.** `p.click("Nova Artist", "artist")`. `a.path()` is `/mb/artist/a1`, and `Playable Song`, `Unlinked Song` and the badges show.
- **Playable link.** `p.click("Playable Song", "playing")` sets `a.current.Key()` to the music.youtube.com video ID `abc`.
- **Unavailable.** With `fakeMB(a).err` set, the provider results still show and one line says the artist data is unavailable.

## Gotchas

- The provider quick switch in the top bar also shows the text `YouTube Music`, so a badge is proved by `a.mbArtist.recordings[i].playable`, not by the text alone.
- Each provider link costs one MusicBrainz request a second, so only the first `mbLinkLimit` recordings of an artist are resolved; the rest show `No link`.
- ListenBrainz is sparse for Turkish and other non-Western artists; when it has no top recordings, the artist page falls back to MusicBrainz's own recording list.
- Search is never blocked by a metadata failure: the artist section says so and the provider's results still load.
