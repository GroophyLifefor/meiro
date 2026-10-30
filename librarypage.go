package main

import (
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/internal/match"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// The app's own playlists: lists of tracks from either provider, made by hand
// and kept on this device. Nothing here is synced to an account; a service's
// own library is read elsewhere (routeLibrary).

// libraryListPrefix names a row's list when it is one of the app's playlists,
// so a row drawn from a list can offer to take its track out of that list.
const libraryListPrefix = "library:"

// libraryListID reads the list an open playlist row belongs to, if any.
func libraryListID(list string) (string, bool) {
	id, ok := strings.CutPrefix(list, libraryListPrefix)
	return id, ok && id != ""
}

// libraryItem flattens a track for a list: what plays it, what names it, its
// length in milliseconds, and the state the app gave it.
func libraryItem(item youtube.MusicItem, albumArt string) library.Item {
	return library.Item{
		Thumbnail:      item.Thumbnail,
		AlbumThumbnail: albumArt,
		Source:         resolveSource(item),
		Key:            item.Key(),
		URL:            item.URL,
		Title:          item.Title,
		Artist:         item.Artist,
		DurationMS:     durationMillis(item.Duration),
		State:          libraryState(item),
		AddedAt:        time.Now(),
		Sources:        item.Sources,
	}
}

// musicItem builds the track a list row plays. A YouTube Music track is keyed
// by its video ID, a SoundCloud track by its page URL. A track the list holds
// on more than one provider plays from the one its Source names.
func musicItem(item library.Item) youtube.MusicItem {
	out := youtube.MusicItem{
		Source:    item.Source,
		Title:     item.Title,
		Subtitle:  item.Artist,
		Artist:    item.Artist,
		Kind:      media.KindTrack,
		Duration:  formatMilliseconds(int(item.DurationMS)),
		Sources:   item.Sources,
		Thumbnail: firstNonEmpty(item.Thumbnail, item.AlbumThumbnail),
	}
	if len(item.Sources) > 0 {
		if ref, ok := out.RefFor(item.Source); ok {
			return out.WithSource(ref.Source)
		}
	}
	if item.Source == media.SourceSoundCloud {
		out.URL = item.URL
	} else {
		out.VideoID = item.Key
	}
	return out
}

// libraryState is what the app knows of a track when it is added: nothing yet,
// so it is unknown. A track the app can tie to a MusicBrainz recording with
// certainty is marked matched a moment later, by autoLink, once MusicBrainz has
// answered.
func libraryState(youtube.MusicItem) library.State {
	return library.State(match.Unknown)
}

// durationMillis reads a length written as "3:42" as milliseconds.
func durationMillis(duration string) int64 {
	return int64(parseDuration(duration) / time.Millisecond)
}

// addItemToLibrary puts a track in a list, and says whether it was already
// there.
func (a *app) addItemToLibrary(id string, item youtube.MusicItem) {
	lib, ok := a.libraries.Get(id)
	if !ok {
		return
	}
	added, err := a.libraries.AddItem(id, libraryItem(item, a.pageArt()))
	if err != nil {
		a.notice = "Could not add to the list: " + firstLine(err.Error())
		return
	}
	if !added {
		a.notice = "“" + item.Title + "” is already in “" + lib.Name + "”"
		return
	}
	a.notice = "Added “" + item.Title + "” to “" + lib.Name + "”"
	a.page.rowsDirty = true
	a.autoLink(item)
}

// duplicateCollection makes a Meiro playlist of an album or a playlist a card
// or a row names, reading its tracks first.
func (a *app) duplicateCollection(item youtube.MusicItem, key string) {
	a.withCollectionTracks(item, key, func(songs []youtube.MusicItem) {
		a.duplicateAsMeiro(item.Title, item.Thumbnail, songs)
	})
}

// duplicatePage makes a Meiro playlist of the album or the playlist the page
// shows.
func (a *app) duplicatePage() {
	a.duplicateAsMeiro(a.page.detail.title, a.page.detail.art, a.page.playable)
}

// duplicateAsMeiro puts a copy of songs in a new playlist of the app's own,
// named like the one it comes from. The songs start unknown, and each is tied
// to MusicBrainz in the background when the match is certain: asking takes a
// second a song, so the playlist does not wait for it.
func (a *app) duplicateAsMeiro(name, cover string, songs []youtube.MusicItem) {
	items := make([]library.Item, 0, len(songs))
	var copied []youtube.MusicItem
	for _, song := range songs {
		if song.IsTrack() {
			copied = append(copied, song)
			items = append(items, libraryItem(song, cover))
		}
	}
	name = strings.TrimSpace(name)
	if name == "" {
		name = "Copied playlist"
	}
	if len(items) == 0 {
		a.notice = "There are no songs in “" + name + "” to copy"
		return
	}
	lib, err := a.libraries.Create(name)
	if err != nil {
		a.notice = "Could not make the playlist: " + firstLine(err.Error())
		return
	}
	if cover != "" {
		// The copy keeps the picture of what it was copied from, so it is
		// recognised on the playlists page.
		if err := a.libraries.SetCover(lib.ID, cover); err != nil {
			log.Printf("keeping the cover of a copy: %v", err)
		}
	}
	added, err := a.libraries.AddItems(lib.ID, items)
	if err != nil {
		a.notice = "Could not copy the songs: " + firstLine(err.Error())
		return
	}
	a.notice = "Copied " + trackCount(added) + " to the Meiro playlist “" + lib.Name + "”, linking them to MusicBrainz in the background"
	a.page.rowsDirty = true
	a.autoLinkAll(copied)
}

// pageArt is the cover of the album or playlist page open, which a song added
// from it falls back to when it has none of its own, and "" on any other page.
func (a *app) pageArt() string {
	if r, _ := a.route(); r.isDetail() && r.target != youtube.TargetArtist {
		return a.page.detail.art
	}
	return ""
}

// firstNonEmpty is the first of its arguments that is not empty.
func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// removeFromLibrary takes a track out of a list by its key.
func (a *app) removeFromLibrary(id, key string) {
	lib, ok := a.libraries.Get(id)
	if !ok {
		return
	}
	title := ""
	for _, item := range lib.Items {
		if item.Key == key {
			title = item.Title
			break
		}
	}
	if err := a.libraries.RemoveItem(id, key); err != nil {
		a.notice = "Could not remove from the list: " + firstLine(err.Error())
		return
	}
	removed := "The track"
	if title != "" {
		removed = "“" + title + "”"
	}
	a.notice = "Removed " + removed + " from “" + lib.Name + "”"
	a.page.rowsDirty = true
}

// playLibrary plays a whole list, from its first track, as one queue: the
// tracks keep the order the list holds, whichever provider each came from.
func (a *app) playLibrary(id string) {
	lib, ok := a.libraries.Get(id)
	if !ok || len(lib.Items) == 0 {
		return
	}
	items := make([]youtube.MusicItem, 0, len(lib.Items))
	for _, item := range lib.Items {
		items = append(items, musicItem(item))
	}
	a.playFrom(items[0], items, 0, youtube.UpNextOptions{}, lib.Name)
}

// openLibraryName opens the dialog that names a new list, or renames one that
// has an ID. A new list made for a track holds it.
func (a *app) openLibraryName(id, name string) {
	a.libName = &libNameState{open: true, id: id, name: name}
}

// openLibraryPicker asks which list a track goes into.
func (a *app) openLibraryPicker(item youtube.MusicItem) {
	a.libPicker = &libPickerState{open: true, item: item}
}

// commitLibraryName makes or renames the list the dialog names.
func (a *app) commitLibraryName() {
	d := a.libName
	if d == nil {
		return
	}
	name := strings.TrimSpace(d.name)
	if name == "" {
		return
	}
	if d.id == "" {
		lib, err := a.libraries.Create(name)
		if err != nil {
			a.notice = "Could not make the list: " + firstLine(err.Error())
			return
		}
		a.libSelected = lib.ID
		a.notice = "Made “" + name + "”"
		if d.song.Key() != "" {
			a.addItemToLibrary(lib.ID, d.song)
		}
	} else if err := a.libraries.Rename(d.id, name); err != nil {
		a.notice = "Could not rename the list: " + firstLine(err.Error())
		return
	} else {
		a.notice = "Renamed to “" + name + "”"
	}
	a.page.rowsDirty = true
	a.libName = nil
	// A list made or added to from elsewhere is shown where it lives.
	if r, _ := a.route(); r.id != routeLibraries {
		a.navigate(routeLibraries)
	}
}

// askDeleteLibrary opens the question a delete asks.
func (a *app) askDeleteLibrary(id, name string) {
	a.libDelete = &libDeleteState{open: true, id: id, name: name}
}

// confirmDeleteLibrary takes the list away.
func (a *app) confirmDeleteLibrary() {
	d := a.libDelete
	if d == nil {
		return
	}
	if err := a.libraries.Delete(d.id); err != nil {
		a.notice = "Could not delete the list: " + firstLine(err.Error())
		return
	}
	if a.libSelected == d.id {
		a.libSelected = ""
	}
	a.notice = "Deleted “" + d.name + "”"
	a.page.rowsDirty = true
	a.libDelete = nil
}

// libNameState is the dialog that makes or renames a list. id is empty when
// the list is new; song, when set, is the track the new list is made for.
type libNameState struct {
	open bool
	id   string
	name string
	song youtube.MusicItem
}

// libPickerState is the dialog that puts a track in a list.
type libPickerState struct {
	open bool
	item youtube.MusicItem
	list ui.ListState
}

// libDeleteState is the question a delete of a list asks.
type libDeleteState struct {
	open bool
	id   string
	name string
}

// librariesPage shows the user's own lists, and the tracks in the one opened.
// It is the app's page, not a service's, so it reads the same under either
// provider; under SoundCloud a line says there is no service library to read.
func (a *app) librariesPage(c *ui.Context) {
	ui.Scroll(c).Grow(1).Children(func() {
		ui.Column(c).Padding(8, 0, a.clearance()).Children(func() {
			if a.libSelected != "" {
				if lib, ok := a.libraries.Get(a.libSelected); ok {
					a.libraryDetail(c, lib)
					return
				}
				a.libSelected = ""
			}
			a.libraryList(c)
		})
	})
}

// libraryList shows every list, and the button that makes one.
func (a *app) libraryList(c *ui.Context) {
	sc := m3.Of(c).Scheme
	make := false
	ui.Row(c).Gap(12).AlignItems(ui.Center).Padding(12, pageGutter).Children(func() {
		m3.EmphasizedText(c, m3.TitleLarge, "Your playlists").Grow(1).TextColor(sc.OnSurface)
		if m3.Button(c, m3.ButtonSpec{Label: "New playlist", Icon: m3.IconAdd, Kind: m3.Tonal, Key: "new-library"}).Clicked() {
			make = true
		}
	})
	if make {
		a.openLibraryName("", "")
	}
	lists := a.libraries.List()
	if len(lists) == 0 {
		a.message(c, m3.IconQueue, "No playlists yet", "Make a list, then add songs to it from any track's menu.", "New playlist", func() { a.openLibraryName("", "") })
		return
	}
	for _, lib := range lists {
		a.libraryRow(c, lib)
	}
}

// libraryRow is one list: its name and how many tracks it holds, and the menu
// that renames or deletes it.
func (a *app) libraryRow(c *ui.Context, lib library.Library) {
	sc := m3.Of(c).Scheme
	key := "library-" + lib.ID
	row := ui.Row(c.Key(key))
	row.Height(64).Shrink(0).Margin(0, pageGutter-8).Padding(4, 8).Gap(8).AlignItems(ui.Center).Radius(m3.Large)
	var main, menuButton ui.Element
	open := false
	row.Children(func() {
		main = ui.ButtonBase(c.Key(key + "-open"))
		main.Grow(1).Basis(0).MinWidth(0).Height(56).Padding(4, 8).Gap(12).AlignItems(ui.Center).Radius(m3.Large).
			Cursor(ui.CursorPointer).Label(lib.Name).
			Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), main.Pressed(), main.FocusVisible()))
		main.Children(func() {
			if picture := lib.Picture(); picture != "" {
				a.art(c, picture, 96, 40, m3.Medium)
			} else {
				ui.Box(c).Size(40, 40).Shrink(0).Radius(m3.Medium).Background(sc.SecondaryContainer).Center().Children(func() {
					ui.Icon(c, m3.IconQueue).FontSize(20).TextColor(sc.OnSecondaryContainer)
				})
			}
			ui.Column(c).Grow(1).MinWidth(0).Children(func() {
				m3.Text(c, m3.BodyLarge, lib.Name).SingleLine().TextColor(sc.OnSurface).Tooltip(lib.Name)
				summary := listSummary(lib.Items)
				m3.Text(c, m3.BodySmall, summary).SingleLine().TextColor(sc.OnSurfaceVariant).Tooltip(summary)
			})
			ui.Row(c).Shrink(0).Gap(4).AlignItems(ui.Center).Children(func() {
				providerMarks(c, lib.Items)
			})
			ui.Icon(c, m3.IconChevronRight).FontSize(24).TextColor(sc.OnSurfaceVariant)
		})
		menuButton = m3.IconButton(c, m3.IconButtonSpec{Icon: m3.IconMore, Label: "More options for " + lib.Name, Key: key + "-menu"})
	})
	if menuButton.Clicked() {
		a.menu.show(key, false, 0, 0)
	}
	if a.menu.owns(key) {
		m3.Menu(c, menuButton, &a.menu.open, 220, func() {
			if m3.MenuItem(c, "Rename", nil).Clicked() {
				a.openLibraryName(lib.ID, lib.Name)
				a.menu.close()
			}
			if m3.MenuItem(c, "Delete playlist", m3.IconDelete).Clicked() {
				a.askDeleteLibrary(lib.ID, lib.Name)
				a.menu.close()
			}
		})
	}
	if main.Clicked() {
		open = true
	}
	if open {
		a.libSelected = lib.ID
		a.page.rowsDirty = true
	}
}

// libraryDetail shows one list: its tracks, a way to play the whole list, and
// a way back to the lists.
func (a *app) libraryDetail(c *ui.Context, lib library.Library) {
	sc := m3.Of(c).Scheme
	back := false
	play := false
	ui.Row(c).Gap(8).AlignItems(ui.Center).Padding(4, pageGutter).Children(func() {
		if m3.IconButton(c, m3.IconButtonSpec{Icon: m3.IconArrowBack, Label: "Back to playlists", Key: "library-back"}).Clicked() {
			back = true
		}
		ui.Column(c).Grow(1).MinWidth(0).Gap(2).Children(func() {
			// The kind and the marks of the providers it holds, apart from the
			// name and with room between them.
			ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
				m3.EmphasizedText(c, m3.LabelMedium, "MEIRO PLAYLIST").LetterSpacing(1.2).TextColor(sc.OnSurfaceVariant)
				providerMarks(c, lib.Items)
			})
			m3.EmphasizedText(c, m3.HeadlineSmall, lib.Name).SingleLine().TextColor(sc.OnSurface).Tooltip(lib.Name)
			m3.Text(c, m3.BodyMedium, listSummary(lib.Items)).TextColor(sc.OnSurfaceVariant)
		})
		if len(lib.Items) > 0 && m3.Button(c, m3.ButtonSpec{Label: "Play", Icon: m3.IconPlay, Kind: m3.Filled, Key: "play-library"}).Clicked() {
			play = true
		}
	})
	if back {
		a.libSelected = ""
		return
	}
	if play {
		a.playLibrary(lib.ID)
	}
	if len(lib.Items) == 0 {
		a.message(c, m3.IconMusicNote, "This playlist is empty", "Add songs to it from any track's menu.", "", nil)
		return
	}
	items := make([]youtube.MusicItem, 0, len(lib.Items))
	for _, item := range lib.Items {
		song := musicItem(item)
		if item.Why != "" {
			// A song the app recommended says why, under its title.
			song.Subtitle = item.Artist + " · " + item.Why
		}
		items = append(items, song)
	}
	for i := range lib.Items {
		a.songRow(c, items[i], items, songOptions{
			list: libraryListPrefix + lib.ID, position: i, inset: true, doublePress: true,
		})
	}
}

// libraryStateChip is the badge a list row shows for what the app made of a
// track: matched, unknown, or the listener's own other.
func libraryStateChip(c *ui.Context, state string) {
	if state == "" {
		return
	}
	sc := m3.Of(c).Scheme
	background, content := sc.SurfaceContainerHighest, sc.OnSurfaceVariant
	switch library.State(state) {
	case library.Matched:
		background, content = sc.SecondaryContainer, sc.OnSecondaryContainer
	case library.Other:
		background, content = sc.TertiaryContainer, sc.OnTertiaryContainer
	case library.Unknown:
	}
	ui.Row(c).Shrink(0).Padding(2, 8).Radius(m3.Full).Background(background).Children(func() {
		m3.Text(c, m3.LabelSmall, state).SingleLine().TextColor(content)
	})
}

// listSummary says what a list holds on one line: how many tracks, how long
// they run and how they divide between providers, such as "28 tracks · 1 hr 52
// min · YouTube Music 18, SoundCloud 10". A list with nothing in it has only
// the count.
func listSummary(items []library.Item) string {
	parts := []string{trackCount(len(items))}
	var total int64
	for _, item := range items {
		total += item.DurationMS
	}
	if length := listLength(total); length != "" {
		parts = append(parts, length)
	}
	if summary := providerSummary(items); summary != "" {
		parts = append(parts, summary)
	}
	return strings.Join(parts, " · ")
}

// listLength writes a run of milliseconds as "1 hr 52 min", or "" when it is
// under a minute.
func listLength(ms int64) string {
	minutes := int(ms / 60000)
	switch {
	case minutes <= 0:
		return ""
	case minutes < 60:
		return strconv.Itoa(minutes) + " min"
	default:
		return strconv.Itoa(minutes/60) + " hr " + strconv.Itoa(minutes%60) + " min"
	}
}

// trackCount names how many tracks a list holds.
func trackCount(n int) string {
	switch n {
	case 0:
		return "No tracks"
	case 1:
		return "1 track"
	default:
		return strconv.Itoa(n) + " tracks"
	}
}

// libraryNameDialog asks for the name of a new list, or a new name for one
// that has an ID.
func (a *app) libraryNameDialog(c *ui.Context) {
	d := a.libName
	if d == nil {
		return
	}
	if !d.open {
		a.libName = nil // closed by its scrim or the Escape key
		return
	}
	sc := m3.Of(c).Scheme
	title := "New playlist"
	if d.id != "" {
		title = "Rename playlist"
	}
	m3.Dialog(c, &d.open, 480, func() {
		m3.EmphasizedText(c, m3.HeadlineSmall, title)
		if d.song.Key() != "" {
			m3.Text(c, m3.BodyMedium, "It will hold “"+d.song.Title+"”.").TextColor(sc.OnSurfaceVariant).SingleLine()
		}
		box := ui.Column(c.Key("library-name")).Padding(12, 16).Radius(m3.Large).Background(sc.SurfaceContainerHighest)
		if box.FocusWithin() {
			box.Border(2, sc.Primary)
		}
		box.Children(func() {
			ui.TextInputBase(c.Key("library-name-field"), &d.name).
				Placeholder("Playlist name").Label("Name").TextColor(sc.OnSurface).FontSize(16).AutoFocus().
				OnSubmit(a.commitLibraryName)
		})
		ui.Row(c).Gap(8).Justify(ui.End).AlignItems(ui.Center).Children(func() {
			if m3.Button(c, m3.ButtonSpec{Label: "Cancel", Kind: m3.TextOnly, Key: "cancel-library-name"}).Clicked() {
				a.libName = nil
			}
			if m3.Button(c, m3.ButtonSpec{Label: "Save", Kind: m3.Filled, Key: "save-library-name"}).Clicked() {
				a.commitLibraryName()
			}
		})
	})
}

// libraryPickerDialog asks which list a track goes into.
func (a *app) libraryPickerDialog(c *ui.Context) {
	d := a.libPicker
	if d == nil {
		return
	}
	if !d.open {
		a.libPicker = nil // closed by its scrim or the Escape key
		return
	}
	sc := m3.Of(c).Scheme
	m3.Dialog(c, &d.open, 480, func() {
		m3.EmphasizedText(c, m3.HeadlineSmall, "Add to library")
		if title := d.item.Title; title != "" {
			m3.Text(c, m3.BodyMedium, title).TextColor(sc.OnSurfaceVariant).SingleLine()
		}
		lists := a.libraries.List()
		if len(lists) == 0 {
			m3.Text(c, m3.BodyMedium, "You have no playlists yet. Make one to add this track to it.").TextColor(sc.OnSurfaceVariant)
		} else {
			list := &d.list
			list.Key = func(i int) any {
				if i >= len(lists) {
					return nil
				}
				return "library-choice-" + lists[i].ID
			}
			ui.List(c, list, len(lists), func(i int) {
				a.libraryChoice(c, lists[i])
			}).Height(min(float32(len(lists))*58, 280))
		}
		ui.Row(c).Gap(8).Justify(ui.End).AlignItems(ui.Center).Children(func() {
			if m3.Button(c, m3.ButtonSpec{Label: "New playlist", Kind: m3.TextOnly, Key: "new-library-from-picker"}).Clicked() {
				song := d.item
				a.libPicker = nil
				a.libName = &libNameState{open: true, song: song}
			}
			if m3.Button(c, m3.ButtonSpec{Label: "Cancel", Kind: m3.TextOnly, Key: "cancel-library-picker"}).Clicked() {
				a.libPicker = nil
			}
		})
	})
}

// libraryChoice is one list of the picker: a list the track can go into.
func (a *app) libraryChoice(c *ui.Context, lib library.Library) {
	sc := m3.Of(c).Scheme
	row := ui.ButtonBase(c.Key("library-choice-" + lib.ID))
	row.Height(56).Margin(0, 0, 2).PaddingX(8).Gap(12).AlignItems(ui.Center).Radius(m3.Large).Cursor(ui.CursorPointer).
		Label("Add to " + lib.Name).
		Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), row.Pressed(), row.FocusVisible()))
	row.Children(func() {
		ui.Box(c).Size(40, 40).Shrink(0).Radius(m3.Medium).Background(sc.SecondaryContainer).Center().Children(func() {
			ui.Icon(c, m3.IconQueue).FontSize(20).TextColor(sc.OnSecondaryContainer)
		})
		ui.Column(c).Grow(1).MinWidth(0).Children(func() {
			m3.Text(c, m3.BodyLarge, lib.Name).SingleLine().Tooltip(lib.Name).TextColor(sc.OnSurface)
			m3.Text(c, m3.BodySmall, trackCount(len(lib.Items))).SingleLine().Tooltip(trackCount(len(lib.Items))).TextColor(sc.OnSurfaceVariant)
		})
	})
	if row.Clicked() {
		item := a.libPicker.item
		a.libPicker = nil
		a.addItemToLibrary(lib.ID, item)
	}
}

// libraryDeleteDialog asks whether a list should be deleted.
func (a *app) libraryDeleteDialog(c *ui.Context) {
	d := a.libDelete
	if d == nil {
		return
	}
	if !d.open {
		a.libDelete = nil // closed by its scrim or the Escape key
		return
	}
	sc := m3.Of(c).Scheme
	m3.Dialog(c, &d.open, 480, func() {
		m3.EmphasizedText(c, m3.HeadlineSmall, "Delete playlist")
		m3.Text(c, m3.BodyMedium, "“"+d.name+"” is deleted from Meiro, and this cannot be undone.").TextColor(sc.OnSurfaceVariant)
		ui.Row(c).Gap(8).Justify(ui.End).AlignItems(ui.Center).Children(func() {
			if m3.Button(c, m3.ButtonSpec{Label: "Cancel", Kind: m3.TextOnly, Key: "cancel-library-delete"}).Clicked() {
				a.libDelete = nil
			}
			if m3.Button(c, m3.ButtonSpec{Label: "Delete", Kind: m3.Filled, Key: "confirm-library-delete"}).Clicked() {
				a.confirmDeleteLibrary()
			}
		})
	})
}
