# Meiro (personal fork)

> **Please don't use this.** This is my personal, hobby fork of
> [Meiro](https://github.com/elianiva/meiro), reshaped for my own taste. It has
> no releases, no installers, no distribution and no support. It talks to a
> server that only I run, things break and change without notice, and I will
> not answer issues or review pull requests.
>
> **If you want a YouTube Music desktop app, use the original
> [Meiro by elianiva](https://github.com/elianiva/meiro).** It is the real,
> maintained project, with releases for macOS, Windows and Linux.

## What this is

The original Meiro is a lightweight, native YouTube Music player built with
[MyGO](https://github.com/egoist/mygo). I liked it, and then kept bending it
until it became a different app: one that treats YouTube Music as just one
music source, keeps my own account and playlists on my own server, links songs
to MusicBrainz, recommends music from what I actually listen to, and is meant to
have an Android companion.

"Meiro fork'u üzerine Murat Kirazkaya tarafından geliştirilmiştir" (developed by
Murat Kirazkaya on top of a fork of Meiro), as the About card in Settings puts
it.

## From that to this

| | Original Meiro | This fork |
| --- | --- | --- |
| Music sources | YouTube Music | YouTube Music **and** SoundCloud, side by side |
| Search | YouTube Music | Both sources at once; the same song from both shows as one row with two source marks, and either can be played |
| Account | Your Google account | A separate **Meiro account** on my own server; YouTube Music sign-in is optional |
| Playlists | Your YouTube Music library | **Meiro playlists** that mix YouTube Music and SoundCloud tracks, plus "copy this album/playlist as a Meiro playlist" |
| Sync | None | Two-way sync of playlists, deletions, renames, covers and plays across devices |
| Artists and songs | As YouTube names them | Linked to **MusicBrainz** (popularity from ListenBrainz): automatic when certain, a "link" button on every song row otherwise; links are shared through the server |
| Recommendations | YouTube Music's | Its own as well: a taste profile from my plays, a **Daily mix** with the reason under each song, and suggestions in the queue |
| History and stats | Plays per day | A **Listening history** page (day by day, per device, skips faded) and stats from every device |
| Offline | Recent-song cache | Separate YouTube Music and SoundCloud caches, plus permanent **downloads** that play offline |
| Monthly and yearly recap | None | On the server's web dashboard |
| Phone | None | An Android app is planned (Kotlin, Jetpack Compose) |
| Updates | Checked automatically | No automatic checks; there are no releases to update to |
| Equaliser | Yes | Still in the code, but dropped from the design and on its way out |

Smaller things that differ: in playlists and in Up next a song starts on a
double click (a stray click does not change the song), a single click on the
playing song does nothing and a double click pauses it, track notifications are
off by default, and sync works quietly without a "Synced N records" toast.

## Status

Work in progress, for one person. The desktop app is developed and tested on
Ubuntu only. Much of it is checked by headless tests and screenshots rather than
by daily use. The companion server (meiro-server) is a separate project and not public. See [`PLAN.md`](PLAN.md) (in Turkish) for where
things stand and what comes next, and [`CHANGELOG.md`](CHANGELOG.md) for the
changes.

## Building it for myself

You need [Bazel](https://bazel.build/) through
[bazelisk](https://github.com/bazelbuild/bazelisk), plus `ffmpeg` and `yt-dlp`
on `PATH`.

```sh
bazel run //:dev                  # run with live reload
bazel test //...                  # every test
bazel test --config=race //...    # the same under the race detector
bazel run //:lint
bazel run //:format-check         # bazel run //:format writes it
bazel run //:gazelle              # after adding, moving or deleting a Go file
```

## Credits

- [Meiro](https://github.com/elianiva/meiro) by
  [elianiva](https://github.com/elianiva): the app this fork grew out of. Almost
  everything good here started there. Go use it.
- [MyGO](https://mygo.egoist.dev/) by [EGOIST](https://github.com/egoist), the
  framework that draws the native window and interface.
- [ffmpeg](https://ffmpeg.org) decodes the audio,
  [yt-dlp](https://github.com/yt-dlp/yt-dlp) finds the streams, and
  [QuickJS](https://github.com/quickjs-ng/quickjs) runs the JavaScript yt-dlp
  needs when Node or Bun is not installed. See
  [`resources/THIRD-PARTY-NOTICES.md`](resources/THIRD-PARTY-NOTICES.md).
- [MusicBrainz](https://musicbrainz.org) and
  [ListenBrainz](https://listenbrainz.org) for artist and recording data, and
  [LRCLIB](https://lrclib.net) for synced lyrics.
- [Google Sans](https://github.com/googlefonts/googlesans), under the SIL Open
  Font License (`fonts/OFL.txt`).

## License

Like the original, this fork is free software under the
[GNU General Public License v3.0](LICENSE).

It is not affiliated with or endorsed by YouTube, Google, SoundCloud or the
MetaBrainz Foundation, nor by the original Meiro project.
