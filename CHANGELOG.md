# Changelog

All notable changes to Meiro are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **A Listening history page.** Settings > Listening history has Open, and the stats page has History: every play, newest first and day by day under Today, Yesterday and the date, each day saying how many plays and about how many minutes. Under each song is when it began, on which device and how much was heard, "heard to the end", "2:05 of 4:18" or "skipped at 0:12". Chips narrow it to the plays that count, the ones skipped, or one device, and a skipped play is faded: it stays in the list, but the stats and the recommendations do not count it. A press on a song does not start it, a double press does, as in a playlist.
- **One Daily mix for your whole account.** Each device used to make a Daily mix of its own, so two devices made two. Now the mix is one playlist: the first device to make today's fills it, the others get it with the next sync and do not make another, and it still teaches Meiro nothing. A Daily mix made before this one is replaced by the shared one.
- **Plays from every device count.** What you play on your other devices, read back from your server when you sign in, when the app starts, after each send and every five minutes, now counts in the stats and in what Meiro recommends, as if you had played it here. A play that comes back from the server is not counted twice, and the plays of other devices are not sent again.
- **Your playlists are the same on every device.** A playlist made, renamed, given a cover, reordered or deleted on one device, and a song added to it or taken out, now reaches every other device signed in to your server, read back when you sign in, when the app starts, after each send and every five minutes. When two devices change the same thing, the later change wins, and songs each added while apart both stay. A song keeps its cover wherever it goes. A server you already used keeps this computer's playlists as the truth on the first sync: what the server holds that this computer does not is deleted there, not brought back.
- **A playlist's songs start on a double press.** In a playlist, a single press on a song that is not playing does nothing, so a stray click does not change the song; a double press starts it. The same goes for the songs of Up next and Related in the full player. It holds for Meiro playlists and for YouTube Music playlists, not for the songs suggested under one, or for search.
- **Meiro recommends songs, on this device.** Meiro learns what you like from what you play: a song heard through counts for it and its artist, one left early counts against, a like, a playlist or a download count more, and what you did a month ago counts half as much as what you did today. It then asks YouTube Music and SoundCloud what is related to the artists you like most, and ListenBrainz for similar artists where it knows them, spacing its requests so none is over a limit and keeping what it learns for a day. Out of that it makes a **Daily mix** playlist, once a day, with the reason under each song, and puts a few of its picks in the queue ahead of YouTube Music's own. Settings has a Recommendations card to turn it off, to say how many of the songs may be by artists you have not played, and to make the mix now. The Daily mix is not counted as something you chose, so Meiro does not learn from its own picks.
- **Link a song to MusicBrainz anywhere, not only in a playlist.** Every song row, in a search, an album, the queue, Downloads and your playlists, says whether it is matched, unknown or marked as not on MusicBrainz, and an unknown one has the link button. A link belongs to the song, so a song linked while searching is linked in every playlist you add it to. The song that starts playing is tied to its recording too when the match is certain.
- **What Meiro learns is kept, and shared with your server.** The links, the artists and which YouTube Music song is the same recording as which SoundCloud one are kept on the device and sent to your Meiro server, and read back when you sign in or start the app, the later record of each winning. So another device, or a later run, does not ask MusicBrainz or YouTube Music or SoundCloud again for what one already found, and a song tried and not matched is not tried again for thirty days.
- **See and forget what Meiro remembers from MusicBrainz.** Settings has a MusicBrainz cache card, under Storage and privacy, that says how many answers are kept and how much room they take, lists the newest ones with what was asked, how many results it listed, how long ago it was kept and when it is asked again, and forgets one, or all of them. Artist searches are kept for 30 days and artist listeners for a week as before, and song searches, which only serve linking a song you just added, now for a day. The links you made are not in this cache, so forgetting it unlinks nothing.
- **Pressing the playing track does not start it over.** A single press on the track that is playing, in a list, a card or the queue, does nothing; a double press pauses it, and another resumes it.
- **Copy an album or a playlist into a Meiro playlist.** The menu of an album or a playlist page, and the menu of its card or row, has Duplicate as Meiro playlist: it makes a playlist of your own with the same name and songs, each once. The songs start unknown and are linked to MusicBrainz in the background, one after the other as it answers, those it is sure of becoming matched; the rest wait for you to link them.
- **Link tracks and artists to MusicBrainz.** A song added to a playlist is tied to its MusicBrainz recording, and its artist with it, when the match is certain: the title and the artist agree and both lengths are known and close. Otherwise it stays unknown and a small link button waits beside it, which never asks for anything. Press it to search MusicBrainz, with the results ordered by how well they fit and, among those that fit alike, by how many people listen to them; choose one, or say the song is not on MusicBrainz. The Artist tab does the same for the artist, in every playlist that credits it. The marks beside a song, matched or other, open the link again to change it or remove it.
- **Search reads YouTube Music and SoundCloud together.** Every search asks both sources at once, shows whichever answers first, and folds the answers together as they arrive: a song both hold becomes one row, and a song only one holds keeps a row of its own. Which source a shared song plays from follows a preference order, YouTube Music first on a first run, which Settings can change.
- **Choose the order the sources are preferred in.** Settings has a Music sources card, Preferred provider, that lists YouTube Music and SoundCloud in the order Meiro prefers them, with an arrow on each row to move it up or down. A song both hold plays from the one on top, and the next is tried when a provider fails. A track you downloaded keeps playing from the source it is kept from whatever the order.
- **Choose which source a song plays from, and keep playing when one fails.** A song both sources hold shows a mark for each in its row, and the mark of the source it plays from is picked out. Press the other mark to switch that song, in a search's results, a page, the queue, your own playlists or the downloads page. The mark of the source that plays is the one filled in. A song plays from the source it is downloaded from when there is one, and otherwise from the first of the preference order that holds it. When a source fails to resolve or play, the next one that holds the song is tried, that becomes the queue's source, and a short message says which provider took over; when every source fails the usual error is shown. YouTube Music and SoundCloud play from the same queue, in whichever order you put them.
- **Search keeps working with no network.** When both sources are out of reach, a search falls back to what is on the device, the tracks you downloaded and the tracks the playback cache kept, and says the results come from this device.
- **Meiro reads and plays SoundCloud.** Search runs against SoundCloud through yt-dlp, and a track plays from the page URL it carries, resolved to a direct audio URL the same way YouTube Music's does. SoundCloud is read signed out, so it has no account library or likes. Sets, artist pages, related tracks and track stations are read by the client but are not on a page yet.
- **Search shows artists from MusicBrainz, most listened to first.** Under the filters, Search lists the artists MusicBrainz matched for the query, ordered by how many people listen to them on ListenBrainz. Opening one shows the artist's top recordings, with a badge for each one a provider can play: a music.youtube.com link plays as YouTube Music, a soundcloud.com link as SoundCloud, and a recording with no such link is shown greyed out. MusicBrainz's one-request-a-second rule is respected with a single queue, and its answers are cached on disk, so a search made again costs no request. When MusicBrainz or ListenBrainz is out of reach, the artist section says so in one line and the provider's own search is unaffected.
- **Your own playlists, with songs from either source.** A new Playlists page holds lists you make yourself. Add any track to a list from its menu, and Meiro refuses one the list already holds, whether it is the same track or the same recording from another provider. A list plays as one queue in its own order, mixing YouTube Music and SoundCloud tracks, and each track shows whether Meiro matched it to a known recording. Open a list to rename it, delete it, or take a track out. The lists are kept on this device.
- **Download a track to keep, and play it offline.** A track's menu has Download, which keeps its audio on this device, outside the playback cache, so clearing a cache or filling it never removes it. A new Downloads page lists what is kept, with its source, size and when it was last played, and a button to remove one. A downloaded track plays with no network, and a track that is neither downloaded nor cached says so plainly when the network is out of reach.
- **Two caches, one per source, measured the same way.** Settings has a YouTube Music cache and a SoundCloud cache, side by side and alike: each has a size limit in megabytes (0 turns it off), a bar and a line that say how full it is and how many songs it holds, and a button that clears it without touching the other cache or any downloaded track. A third card chooses the one folder both are kept in, each in a folder of its own inside it. The YouTube Music cache used to be counted in songs; an old setting is carried over as the default size, and a cache that was off stays off.
- **Your listening, playlists and downloads can sync to a Meiro server.** Settings has a Meiro account card where you give the server address, sign in with an email and password, and turn sending on or off. Each play is counted by the same rule the stats use (thirty seconds, or half the track), and what you played, your playlists and your downloads are sent in the background. Sending is offline first: when the server is out of reach the records wait on disk and go out with the next try, so nothing is lost.

### Changed

- **Meiro's server is the default address.** A first run signs in to https://meiro.muratkirazkaya.com. The address can still be changed in Settings, so a server on your own machine works as before.
- **Track notifications are off until you turn them on.** A new install no longer announces every track. **Settings > Desktop > Track notifications** turns them on; a choice already made is kept.
- **The full player gives the side panel the free width.** In a wide window the Up next, Lyrics and Related panel used to stay 480 pixels wide with empty space around the artwork. Now the artwork keeps its size and the panel takes the rest, so lyrics have room to breathe.
- **Every song shows which provider it comes from.** A song only one provider holds now wears that provider's mark, in search results, lists, the player bar, the full player and the queue, where before only a song both providers held did.
- **A song that is playing is looked for on the other provider.** When it is the same recording there, it gains a mark for each provider, so you can switch to the other while it plays. It goes on playing from the one it plays from, and each song is looked up once.
- **SoundCloud songs are among the search results, not under them.** A song only SoundCloud has used to come after every row YouTube Music returned, so it was never reached. Now one comes after every three rows, and the rest after the last. A search narrowed to albums, playlists or artists no longer asks SoundCloud, which does not tell those apart.
- **Songs and playlists keep their covers.** A song in one of your playlists or in Downloads shows its own cover, or the cover of the album it came from when it has none, and a playlist copied from an album or a playlist keeps that picture as its own on the Playlists page.
- **The logo shows.** The window has the Meiro logo in its taskbar and title, and the navigation rail wears it at the top, with the name beside it when expanded.
- **MusicBrainz is asked politely.** Requests now identify the app with a name, a version and a contact address, as MusicBrainz asks. When MusicBrainz refuses a request for coming too fast, requests wait before going on, for as long as it says or for a pause that doubles up to five minutes, and linking a long playlist stops instead of adding to the load.
- **Library is shown only when you are signed in to YouTube Music.** It is that account's library, so the sidebar leaves it out until there is one. Playlists and Downloads, which are yours, are always there.
- **A search narrowed to songs, albums or the like no longer shows the MusicBrainz artists above its results.** They stay for a search with no filter and for one narrowed to artists.
- **The artists a search matched scroll with its results.** They are the first rows of the list rather than a part of the page that keeps its place, so a long list of artists no longer leaves no room to scroll.
- **Press anywhere on the search bar to type in it.** The field is only as tall as its text, so a press above or below the text, or on the search icon, did nothing. Now it focuses the field.
- **Playlists and cards say which provider they come from.** A card carries the mark of its provider at the end of its title line, a page's heading carries it beside its kind, and a Meiro playlist shows the marks of the providers it holds, with a line such as "28 tracks · 1 hr 52 min · YouTube Music 18, SoundCloud 10". A SoundCloud song that is only a thirty second preview says "preview" in its row, so the short length is no surprise. Where a long name is cut off, hovering it shows all of it.
- **The player bar and the full player show where a song plays from.** The marks of the providers that hold the song sit beside its name, in both. Pressing the other one carries on from the same place and says "Continuing on" the provider for two seconds.
- **Search no longer writes "Looking up artists on MusicBrainz" while it waits.**
- **Your Meiro account is apart from YouTube Music.** The account in the top bar is now the Meiro account, with its own menu to open Settings and sign in or out. In Settings the Meiro account card comes first, with the server and the sign-in that used to be the Sync card at the bottom, and the YouTube Music account is a second, optional card: it is needed only to read your YouTube Music library, likes and recap, and the channel it acts as is chosen there. Search and playback work without either. Where the Library and Recap pages ask you to sign in, they now say it is YouTube Music you sign in to.

### Removed

- **Automatic update checks and the Report a problem card.** Meiro looks for a new version only when you press Check for updates in Settings, and the card that opened a prefilled GitHub issue is gone.

### Fixed

- **The window buttons stay visible in the light appearance.** On Linux the minimise, maximise and close buttons are drawn by the desktop's own toolkit, which followed the system appearance instead of the one Meiro was set to, so they turned invisible on a light window. Meiro now tells the toolkit which appearance to use whenever it changes, and at launch.

## [0.4.14] - 2026-10-28

### Fixed

- **Unmuting brings back the volume you had.** It no longer jumps to 40% when the volume was lower.
- **The Flatpak can sign in with your browser.** Import from a browser reads the profiles of your Chrome, Brave, Edge, Helium, Firefox or Zen, and the keyring that unlocks Chromium cookies. Sign in with a browser window needs one permission the Flatpak does not ask for by default: the dialog shows the `flatpak override` command that grants it, with a button to copy it. After you run it and restart Meiro, the window opens your default browser, whether you installed it natively, with Flatpak or with Snap.

## [0.4.13] - 2026-10-28

### Added

- **Meiro is available as a Flatpak.** Releases include a `.flatpak` for Linux `amd64` and `arm64`, in the full build. Install it with `flatpak install --user`.
- **Pages can be refreshed from the keyboard.** Cmd/Ctrl with `R` loads Home, Explore or Library again instead of showing what was kept. Settings > Keyboard can rebind it.
- **Meiro has a website.** It shows what Meiro is, what it does, a player you
  can play with, and the download links, in a light or dark theme.

### Fixed

- **Go to artist is in the menu of a video from the queue.** A video whose
  channel the queue links, as an uploaded video does, had no such item.

## [0.4.12] - 2026-10-28

### Changed

- **Clicking the playing track in the queue pauses or resumes it.** It used to start the track again from the beginning.

### Fixed

- **Adding a song a playlist already holds says so.** The picker used to keep itself open over YouTube's raw failed status. It now closes and tells you the song is already in that playlist.
- **Choosing a search suggestion from another page opens the search.** Clicking a suggested query used to run the search without leaving the page, so it looked like nothing happened until you opened Search. It now shows the search page with the results, as pressing Enter does.

## [0.4.11] - 2026-10-27

### Added

- **The interface can be zoomed.** Settings > Appearance has a Zoom control that makes everything larger or smaller, from 80% to 200%. The window keeps its size, and text, icons and artwork stay sharp at the new size. Cmd/Ctrl with `=` and `-` zoom anywhere, and with `0` go back to 100%. The three keys are shortcuts like any other, so Settings > Keyboard can rebind them.

### Changed

- **Moving elements are painted at a steadier rate.** The loading indicator, the dancing bars beside the playing track and the wavy progress bar now redraw about 60 times a second, aligned to the screen's refresh, instead of on a timer that drifted against it. They also redraw on their own, so the rest of the window costs nothing while they move, and a display that can refresh less often does.

### Fixed

- **Closing a full-screen window to the tray on macOS leaves the desktop.** The window used to stay as a black full-screen space. It now leaves full screen, hides, and comes back full screen the next time you open it.
- **A dialog taller than the window scrolls.** The sign-in dialog no longer runs past the bottom of a short window, so its buttons stay reachable.
- **Smaller platform fixes arrive with the toolkit update.** On macOS, Meiro no longer starts the AutoFill helper, which used CPU and could stall scrolling, and Cmd-Q always quits. On Windows, a window hidden to the tray no longer costs a second of work on each frame, and short button labels are no longer cut off with an ellipsis. On Linux, closing the window from its own title bar no longer crashes, and the first click on the tray icon works.

## [0.4.10] - 2026-10-24

### Added

- **Windows track notifications show the current cover art.** A missing or invalid cover falls back to Meiro's coloured app icon.

### Fixed

- **Discord and the window title show the artist of a track whose row links none.** Some track rows name the artist as plain text, so Discord left the member list as Meiro and the title dropped the artist. Both now fall back to the byline the player shows.
- **Long podcast episodes play without buffering.** YouTube slowed long episodes down to barely faster than they play, so a dip in the network meant silence. Meiro now fetches them at full speed, and saves them to the audio cache in seconds instead of giving up partway. Faster playback needs a recent ffmpeg, which the full build includes.
- **Times beside the position slider stay on one line.** For tracks and episodes over an hour, the elapsed time and length no longer break onto a second line.

### Changed

- **The navigation rail keeps each destination anchored as it opens: icons shift down within their rows as labels fade between stacked and inline layouts, the menu glyph rotates, and the page moves with the rail.**

## [0.4.9] - 2026-10-23

### Added

- **The window title names the song while it plays.** The taskbar preview and the window switcher show "Artist - Title", and go back to "Meiro" when playback pauses or stops.

### Fixed

- **The "Close Meiro?" prompt on Windows takes the first click.** Choosing "Minimize to tray" or "Close Meiro" works straight away instead of needing two or three clicks.

### Changed

- **Linux desktop widgets get higher-resolution cover art.** MPRIS requests a larger image when the cover URL supports it.
- **The macOS and Linux tray wordmark is optically centered.** Choose the standalone O mark in Desktop settings for a quieter tray icon; Windows keeps its coloured app icon.

### Fixed

- **Space plays and pauses after a click.** Clicking a control left it with the
  keyboard focus, and a focused control took Space as a click of its own, so
  after clicking a track, a tab or a button, Space pressed that control again
  instead of the player. Space now plays and pauses throughout the app shell,
  including on a focused switch; text fields keep it for typing. Dialogs keep
  their own Space behavior. Enter still activates a focused control.

## [0.4.8] - 2026-10-23

### Changed

- **Cached tracks with no saved length start sooner.** Meiro reads the duration from the audio file without starting FFmpeg.
- **The macOS and Linux system trays show Meiro's wordmark. Windows keeps the coloured app icon so it stays visible in the tray and track notifications.**
- **Artwork and avatars use native rounded corners.** Their shape stays consistent as the image scales, and the cache no longer keeps separate copies for different corner radii.
- **Expanded navigation follows the Material 3 rail layout.** The selected row fills the available rail width; inactive destinations stay clear.

### Fixed

- **Go to artist from the tray mini player opens the main window on that artist's page.**
- **The Windows tray and track notifications show Meiro's coloured app icon instead of a blank white square.**
- **Search inputs keep their contents in place when focused.** Clicking the global, Settings and playlist search fields no longer causes them to jiggle.
- **Episode results play from search.** YouTube Music episode cards now use the video ID embedded in their episode-page link, so the card has a working Play action.
- **Long queues keep every upcoming track after a restart.** The saved queue no longer drops songs beyond the first 200, so the Up next list and its remaining count stay complete.

## [0.4.7] - 2026-10-22

### Added

- **A volume slider in the tray mini player.** Adjust or mute playback volume directly from the tray flyout.
- **Search Settings.** A playlist-style search button filters settings in place, including the Discord Rich Presence option.

### Changed

- **Equaliser changes apply live while music keeps playing.** Moving a band or choosing a preset no longer restarts the stream.

### Fixed

- **The player's side panel does not crowd controls in short windows.** When the window is wide but short, the queue and lyrics panel stays as an overlay behind its toggle instead of splitting side-by-side and squeezing the album artwork.
- **Palette options have a uniform size, and hover outlines no longer shift their contents.** Colour swatches also keep their contents in place when hovered.

## [0.4.6] - 2026-10-22

### Fixed

- **Discord Rich Presence finds custom clients installed with Flatpak or Snap.** On Linux, Meiro checks each client's exported socket location instead of only the official Discord Flatpak paths. Any client that exposes Discord's standard RPC socket can share activity.
- **Keyboard navigation keeps focus on the control you choose.** Tab and Shift+Tab reach row and card menus without losing focus. Menus support arrow keys, Home, End and Escape, and return focus when closed. Shift+F10 opens an item's menu, the player tabs support arrow keys, and Enter plays the selected queue track.
- **Keyboard focus follows each control's shape.** Song rows have one outline instead of an inset outline inside a second highlight. Buttons, navigation and sliders indicate the focused shape rather than their larger pointer target, and selected queue text stays readable.
- **Keyboard slider changes take effect.** Position changes seek playback, volume changes are saved, and the narrow player's volume popup keeps focus when you Tab into it.

## [0.4.5] - 2026-10-22

### Added

- **Media buttons on the Windows taskbar thumbnail.** Hover over Meiro in the taskbar for Previous, Play or Pause, and Next, the way Spotify offers them, without bringing the window back. They follow the taskbar's light or dark theme and come back after Explorer restarts.

### Fixed

- **The tab of the page already showing puts the player away.** With the player
  open, the page's own tab in the rail did nothing, so the only way back to the
  page was the player's close button. The tab now closes the player and leaves
  the page as it was.
- **The queue's Recommended heading has equal space above and below, and cannot be selected.** It lines up with the artwork and separates recommendations from chosen tracks without a full-width divider.

### Changed

- **Carousel scrollbars sit below each card's text.** A clear gap separates
  the bar from the song title and artist.
- **The player always shows the wavy progress bar.** The setting is gone, and the wave is on by default on every platform.

## [0.4.4] - 2026-10-21

### Changed

- **Queue tracks can be selected and edited together.** Use Cmd-click on macOS or Ctrl-click on Windows/Linux to select tracks, then drag any selected row to move the group or right-click for **Remove N tracks**. The selection highlight follows the active theme, and the track playing is never removed.
- **The tray keeps its place.** macOS remembers where Meiro's icon was placed in the menu bar, and Windows remembers whether it was shown on the taskbar or in its overflow.
- **The mini player uses the system's popover material where supported.** Its contents stay legible over the native backdrop, while platforms without vibrancy keep Meiro's themed surface.
- **Full builds include FFmpeg 9.0.2 for audio decoding.**
- **Scrollbars match the active theme.** Their slimmer, primary-colour thumb is consistent across pages, and carousel shelves leave more room below the bar before the next section.
- **The queue's header reads in one line.** Where the queue plays from is one line, **Playing from** and the playlist in the same size with the playlist in bold, instead of a caption over a second line. The source, **Auto-play** and the count of the songs next sit one gap apart, and closer than before.
- **A queue row is its song and nothing else.** The three-dot button and the X are gone from the rows, and the length takes the width they held. Right-click a row for **Move up**, **Move down** and **Remove from queue**. The track playing still offers no remove.
- **Shuffle arranges the whole queue, and turning it off puts the queue back.** Turning shuffle on moves the track playing to the top and shuffles every other song behind it, the way Spotify does it, instead of arranging only the songs after the track playing and leaving the ones before it out. Turning shuffle off returns the queue to the order it held, with the track playing where it was, and the generated tracks keep their place at the end.
- **A long playlist sorts and filters without a stall.** Sorting by title, artist or length is several times faster, which also shows in each key typed into a sorted playlist's search.
- **Artwork shows sooner.** A cover appears as soon as it is downloaded, rather than after it is saved to disk.

### Fixed

- **The queue's Recommended heading aligns with the artwork below it, without a full-width divider.** The recommendations get a cleaner break from the chosen tracks.
- **Long playlists show all their songs.** Later batches stay with the playlist instead of being mistaken for recommendations.
- **Closing the player no longer flashes a blank frame before the page returns.** The page now appears as the player finishes closing, without a full-window clipping mask.
- **Buttons near the sheet's rounded corners respond where they appear.** The corner decoration no longer takes pointer events from the controls beneath it.
- **The full player's cover holds still while the window is resized.** It blinked to the placeholder and was decoded again at nearly every height the window passed through, which also pushed other covers out of memory.
- **The mini player's Clear queue ends on the queue's edge.** Its label stopped 16 px short of the lengths under it, so the narrow column read as two right edges.
- **The player's controls keep their distance from their edges.** The three-dot menu on the album art sat against the artwork's corner, and the Up next, Lyrics and Related row started further below the side panel's top than from its sides; both now keep the margin the rest of the player keeps.
- **A queue that holds the same song twice picks out only the copy playing.** Both copies were drawn as playing, with the dancing bars and the tonal row, because a row was picked out by its song rather than by its place in the queue.
- **A context menu opens again where it was closed.** The mark a menu left where the pointer pressed took the next right click on that spot for itself, so a card's or a page's menu could be opened only once until the page changed.
- **A playlist write YouTube refuses is written to the log in full**, for the add and the remove path, so a refusal that the player bar shows as a line can be read whole.
- **A track whose stream stops at its end plays the next one.** YouTube's server stops at the end of a track's data, which the app took for a stream that broke off: it found the stream again and played the last moment of the track a second time. The break now ends the track.
- **A track starts at its corrected volume.** With normalization on, the first moment of a track played at the volume the track before it had — or at the plain volume when the app started — until the correction landed a moment later. The gain is now in place before the audio starts.

## [0.4.3] - 2026-10-20

### Added

- **A playlist can be deleted from its menu.** The menu on a playlist card, and the menu on a playlist page, offers **Delete playlist**. It asks first, and names the playlist it would take, because a delete cannot be undone.
- **Podcast shows open.** A show from search opens as a page with its description and its episodes, newest first, each with its description, date and length. An episode plays like a track, with seek, the queue and the player bar. An episode you leave part-way through starts where you stopped the next time you play it, also after a restart. When you are signed in, Meiro also reads and updates YouTube Music's episode progress, so you can continue listening on another device; a local resume position takes priority.

### Changed

- **Discord shows every artist on a track.** Collaborations now list all credited artists instead of only the first.
- **Settings pages have a simpler top bar.** The music search and account menu are hidden while you are in Settings.

### Fixed

- **Auto-play skips music-video recommendations.** When YouTube recommends both a song and its video, Meiro chooses the song and leaves your queue and current track alone.
- **The "Close Meiro?" prompt respects your choice.** Pressing Escape or the close button keeps the app open. Choosing **Minimize to tray** also makes sure the tray icon appears, even if **Remember my choice** is off.

## [0.4.2] - 2026-10-20

### Added

- **Windows has a portable download.** Extract the ZIP and run Meiro without installing it.

### Changed

- **Similar songs have their own section on playlist pages.** The first five suggestions appear below the playlist under **Similar songs**. Choose **Show more** to load more. Suggestions stay separate from the playlist and its queue: playing a playlist song plays the playlist, while playing a suggestion starts the suggestions.
- **The player bar keeps its mute button in narrow windows.** Hover it to reveal a vertical volume slider without taking space from the track.
- **The Linux tray menu now offers Open Meiro and Quit.** The Linux tray does not provide the icon position needed to place the mini player, so **Open Meiro** brings back the main window instead.
- **Discord Rich Presence shows a time bar with the track's length** instead of only the elapsed time.
- **Discord's member list shows the artist you are listening to** instead of Meiro's name.

### Fixed

- **Signing in and account features work again.** YouTube rejected valid sessions because a request's authorization hash did not match its destination. The hash now matches, so sign-in, library access, likes and playlist changes work as expected.
- **Discord Rich Presence works with a Flatpak Discord.** Meiro finds the socket Flatpak exports, so the manual symlink workaround is no longer needed.
- **Button labels stay whole when they fit.** Theme, playlist and shortcut buttons no longer truncate labels that have room to display in full.
- **Linux packages install the AppIndicator runtime needed to show the tray icon.**
- **Home shelves no longer show scrollbars over their artwork.** Use the left and right arrows to move through a shelf.
- **Show more on Home now appends the next sections to the feed.**

## [0.4.1] - 2026-10-19

### Changed

- **The tray mini player stays compact and vertical.** Its tabs and auto-play controls are smaller, leaving more room to see upcoming tracks.
- **Playlist sorting makes it easier to find recently added songs.** Descending order now reverses the entire playlist, across sections. You can choose the sort direction before picking a field, and changing the field keeps the direction you selected.

### Fixed

- **Discord Rich Presence shows the credited artist.** It no longer shows the song's full subtitle, which could also include the song type, album or length.

## [0.4.0] - 2026-10-18

### Added

- **Discord Rich Presence.** When enabled in Settings, Discord shows the playing track, artist, cover and elapsed time, without repeating the title in the cover's hover text. The status clears when playback pauses or stops.

### Fixed

- **Switching YouTube brand accounts selects the right channel.** The account menu now marks only the active channel as selected.
- **Tracks play while signed in.** Plays are added to your YouTube Music history after playback starts.

## [0.3.3] - 2026-10-18

### Changed

- **Playlist sort has a separate direction button.** The sort menu now only picks what to sort by, and a button beside it reverses the order in one click.

### Fixed

- **Tracks without available audio now show a clear message.** Instead of yt-dlp's technical error, the player and skip message explain that yt-dlp may need an update. The **Learn more** button gives steps for full and minimal builds.
- **Desktop launches now record errors in the log.** Errors are saved even when Meiro is opened without a terminal, so a skipped track has a reason in the log.

## [0.3.2] - 2026-10-17

### Changed

- **The tray player always shows Up next, Lyrics and Related.** The show-or-hide button is gone, so the player keeps a consistent size instead of stretching or reopening.

### Fixed

- **The tray player's theme now follows the artwork on its own.** Its colours update with each track, even when the main window is closed.

## [0.3.1] - 2026-10-17

### Changed

- **Settings is easier to browse.** Cards are grouped under Account, Appearance and sound, System, Storage and privacy, and About and support. Loudness normalization is under Sound, and listening stats are next to listening history.

### Fixed

- **Auto-play no longer queues the same song twice.** Recommendations with the same title and artist are skipped, even when their lengths are missing or the title differs only in case or punctuation. When a song is recommended as both a video and a music version, the music version is queued.

- **The tray player updates its theme with the artwork.** With dynamic colours on, it now matches each track without needing you to open the main window.

- **The tray player no longer stretches when you show or hide up next.** The player now opens again at the new size, so its content stays sharp.

- **You can reopen Meiro after closing it to the tray.** Choose **Open Meiro** in the mini player, launch the app again, click a notification or click its Dock icon to show the window.

## [0.3.0] - 2026-10-16

### Added

- **Firefox and Zen sign in with a browser window.** Account setup now opens a separate Firefox or Zen window with a temporary profile when one of those is the default browser, or when it is the one installed. Native, Flatpak and Snap installs all work, because the profile is created in the data directory the browser's sandbox sees. Meiro reads the session from that profile the way an import does, closes the window, and deletes the profile.
- **Synced lyrics.** The Lyrics tab now shows timed lyrics, from YouTube Music when it has them and from LRCLIB otherwise. The line being sung is lit and glides to the middle as the song plays, lines fade the further they are from it, and a click on a line jumps to it. Choose **Synced** or **Static** above the lyrics, where the source of the lyrics also shows; Meiro remembers the choice. A lookup that fails is tried again the next time the tab opens.
- **Windows downloads are available (untested).** Releases include an `amd64` installer in full and minimal builds. Windows support has not yet been tested.

### Fixed

- **A Firefox or Zen sign-in finds the session it just made.** Firefox keeps a fresh cookie in a write-ahead log, and the sign-in read only the main database, so a finished sign-in could look signed out. The window now makes Firefox checkpoint the cookie at once.
- **A Firefox or Zen sign-in falls back to a Chromium browser without yt-dlp.** Reading a Firefox profile needs yt-dlp. Without it, the window opens in a Chromium-based browser instead of failing.
- **A removed Flatpak or Snap install no longer hides a native browser.** An export or snap link can outlive an uninstall and win over the browser actually installed. The sign-in now asks the package manager whether the install is still there.
- **The fallback order is the same on every platform.** When the default browser is not one Meiro drives, the window opens in the first Chromium-based browser Meiro knows, on Windows and macOS too. A user Flatpak install under a custom `XDG_DATA_HOME` is found as well.
- **A Zen Flatpak install shows its session in the import menu.** The menu searched a directory Zen never writes, so a session in a Flatpak Zen was missing from the list. It now finds the profiles in the directory Flatpak persists, `~/.var/app/app.zen_browser.zen/.zen`.
- **The menu button no longer slides.** When the side menu opens or closes, the button stays above the icons instead of drifting while the menu grows.
- **Small buttons are easier to hit.** Icon buttons, chips, switches and sliders now react to a press up to 48 pixels around them, as large as their look is small. Neighbouring buttons (the player's, the queue's and a shelf's arrows) sit a little further apart, so a press on one never lands on the next. The search bar keeps one width on every page, the playlist page lines its "Songs" heading up with the other pages, and the equaliser sliders spread across their card.
- **Cards line up with their heading, and the player bar fits narrow windows.** The name and details under a card's picture start at the picture's edge, as the section heading does. In a narrow window the player bar keeps room for the track's name instead of showing "…".
- **Album, playlist and artist pages fit narrow windows.** The picture and the buttons shrink, so Play, Shuffle and the rest stay on one line. The pictures of the songs now start where the page's picture and headings do.
- **The expanded navigation rail lines up.** Its menu button sits over the icons below it and no longer pushes them down when the rail opens. The twelve colours in Settings fit on one line.
- **The menu button on a picture can be seen.** On the large picture of the player and on cards, the three dots sat straight on the artwork and vanished against a bright cover. They now have a soft round ground.
- **Song lengths no longer jump.** A song's length stayed put only until the pointer reached its row, when the menu button pushed it to the left. The button now has its place held, and the dots of the song that is paused sit in the middle of its row.
- **Window buttons on Windows sit on the grey frame.** The page surface now starts below them instead of running under them, and the navigation rail no longer keeps an empty band at its top.
- **Songs in the queue no longer show as videos.** The recommended tracks after a song now show as songs. Only real music videos carry the video badge.
- **The playlist search bar grows and shrinks with its control.** It now expands beside the buttons that play the page, and closes back into the button that opened it. It used to open in a row of its own above them, at full size, and then jump straight into the button when it closed.
- **The playlist sort menu closes when its control is pressed again.** The button that opened the menu now closes it too, as the account menu's button does.

## [0.2.0] - 2026-10-15

### Added

- **Sign in with a browser window.** Account setup opens a separate Chromium-based browser (your default browser when it is one, such as Chrome, Edge, Brave, Helium, Chromium or Vivaldi) with a temporary profile. Sign in there; Meiro reads the session, closes the window and deletes the profile. YouTube does not rotate the session because the window is not used again. Browser import and Cookie-header sign-in are under **Advanced**.
- **Ten-band equaliser.** Shape the sound in Settings → Sound with ten frequency sliders and a set of presets. Changing the equaliser restarts playback at its current position. Boosted bands reserve matching headroom to prevent clipping.
- **Loudness normalization** keeps tracks at a more consistent volume when enabled in Settings and the account is signed in.
- **Playlist sorting**: order playlist songs by title, artist, or duration in either direction. The selected order also controls Play, Shuffle, track activation, and playlist search results.
- **Last.fm scrobbling.** Connect your account in Settings; Meiro then sends what is playing and scrobbles each track once you have listened to half of it, or four minutes. Pause scrobbling without disconnecting, and plays that could not be sent are kept and retried, also after a restart.

### Changed

- A song played on its own now starts its mix directly, which saves one request.
- Settings scrolls faster, because only the cards in view are built and painted, and the hue slider paints fewer gradients. A page restored from the page cache returns to its previous scroll position.

### Fixed

- Signed-in playback is now reported to YouTube Music, so plays reach the listening history that recommendations use.
- On macOS, apps started from the Dock or Finder now find `ffmpeg` and `yt-dlp` in common tool folders such as Homebrew's.

## [0.1.0] - 2026-10-14

The first release.

### Added

- **Browse** YouTube Music: the Home feed and Explore, plus artist, album, and playlist pages, with your Library when you are signed in.
- **Search** from the bar on every page, filter the results by the categories YouTube Music offers, and keep your eight most recent searches.
- **Playback** from feeds, search results, and playlists, with seek, volume, previous and next track, shuffle, and repeat for the current track or the queue. A compact player stays available while you browse, and the full player shows the queue, lyrics, and related tracks.
- **Auto-play**, on by default, to add YouTube Music recommendations after the current queue ends, toggled from the queue.
- **Appearance** that follows the system theme or uses light or dark, with Material 3 palettes whose hue you can adjust or set from the current track's artwork.
- **Audio cache** for the ten most recently played tracks by default, with a configurable limit and folder and kept in a private cache folder. Set the limit to `0` to turn the cache off.
- **Keyboard shortcuts** for playback, navigation, search, and Settings, with a held modifier showing each button's key, all rebindable in Settings.
- **System media controls** through macOS Now Playing and Linux MPRIS, with track details, cover art, and playback state, and commands for play, pause, stop, previous, next, seek, volume, shuffle, and repeat.
- **Account import** from the YouTube Music session in Chrome, Safari, Firefox, Brave, Edge, or Helium, stored in the system credential store where one is available. Public browsing and playback need no account.
- **Search menus**: right-click a result on the search page, or an item in the panel under the search bar, to open its menu. A song offers Play next, Add to queue, Add to playlist and Copy link; an album, artist or playlist offers the page it names and its link, with Shuffle play for a playlist. The leading result offers the same menu, from a button on its artwork.
- **Playlist search**: an icon button beside a playlist page's Play and Shuffle buttons opens a bar that narrows the page to the songs whose title or artist holds what is typed. Play, Shuffle and a song's own place still use the whole playlist, so a match plays from where it sits in it.
- **Tray mini player**: the tray icon's menu opens a small player with the current track's artwork, a position slider, shuffle, previous, play or pause, next and repeat, and buttons to open Meiro or quit. On Linux it opens in the middle of the screen, since tray icons there report no clicks.
- **Packages** for macOS as one universal download that runs on Apple Silicon and Intel, and Linux `amd64` and `arm64` as `.deb`, `.rpm`, `.AppImage`, and `.tar.gz`. Full builds bundle ffmpeg and yt-dlp; minimal builds use the ones on your system.
- **Automatic updates**: the app checks once a day for a signed release, shows its notes, and offers to install, skip, or remind you later, with optional background installation in Settings. Package installs and development builds do not check.
