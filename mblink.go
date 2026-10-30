package main

import (
	"context"
	"errors"
	"log"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/internal/match"
	"github.com/elianiva/meiro/internal/mblinks"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/musicbrainz"
	"github.com/elianiva/meiro/youtube"
)

// MusicBrainz is where the app keeps what a track and its artist are. Not every
// recording or artist is there, so a link is never required: a track the app
// cannot tie to a record with certainty stays unknown, and the listener may
// tie it themselves with a search, or mark it as one MusicBrainz does not
// hold. Both a recording and an artist can be linked.

// linkKind is what a link is made for.
type linkKind int

const (
	// linkSong ties a track to a MusicBrainz recording.
	linkSong linkKind = iota
	// linkArtist ties a track's artist to a MusicBrainz artist.
	linkArtist
)

// linkTimeout bounds one search for a link.
const linkTimeout = 30 * time.Second

// linkResultsShown is how many results the dialog lists.
const linkResultsShown = 6

// linkCandidate is a MusicBrainz record a link can be made to.
type linkCandidate struct {
	id, name, detail string
	// similarity is how well MusicBrainz says the record fits the search, as
	// a percentage.
	similarity int
	// listeners is how many people listen to an artist on ListenBrainz, which
	// orders the artists that fit the search equally well.
	listeners int64
	// artistID is the first artist a recording credits.
	artistID string
}

// linkState is the dialog that ties a song or its artist to MusicBrainz.
type linkState struct {
	open bool
	kind linkKind
	// item is the song, wherever it was pressed: a search, an album, the
	// queue or a playlist.
	item youtube.MusicItem
	// linked is set when the dialog reopens a link that exists, which it then
	// offers to take away.
	linked bool
	// query is what the field holds.
	query      string
	candidates []linkCandidate
	selected   int
	loading    bool
	err        string
	load       task
}

// songQuery is what a search for a song's recording is made from.
type songQuery struct {
	title, artist string
	durationMS    int64
}

// queryOf reads what a song says of itself: its title, the artist its byline
// names, and its length.
func queryOf(item youtube.MusicItem) songQuery {
	track := matchTrack(item)
	return songQuery{title: item.Title, artist: track.Artist, durationMS: track.Duration.Milliseconds()}
}

// songRefs are the names a song goes by in the link store: one for each
// provider's copy that it is known by.
func songRefs(item youtube.MusicItem) []mblinks.Song {
	var refs []mblinks.Song
	add := func(source media.Source, id string) {
		if id == "" {
			return
		}
		provider := string(source)
		if provider == "" {
			provider = string(media.SourceYTM)
		}
		for _, r := range refs {
			if r.Provider == provider && r.ID == id {
				return
			}
		}
		refs = append(refs, mblinks.Song{Provider: provider, ID: id})
	}
	for _, ref := range item.Sources {
		if ref.Source == media.SourceSoundCloud {
			add(ref.Source, firstNonEmpty(ref.URL, ref.ID))
		} else {
			add(ref.Source, ref.ID)
		}
	}
	add(resolveSource(item), streamKey(item))
	return refs
}

// linkOf is what is known of a song's link: the first of its names the store
// has a record for.
func (a *app) linkOf(item youtube.MusicItem) (mblinks.Song, bool) {
	if a.links == nil {
		return mblinks.Song{}, false
	}
	for _, ref := range songRefs(item) {
		if song, ok := a.links.Song(ref.Provider, ref.ID); ok {
			return song, true
		}
	}
	return mblinks.Song{}, false
}

// recordSong keeps a song's link under every name the song goes by, keeping
// what it already knew of the artist unless the new link says otherwise, and
// tells the lists that hold the song.
func (a *app) recordSong(item youtube.MusicItem, state, mbid string, artist *mblinks.Artist) {
	if a.links == nil {
		return
	}
	name := queryOf(item).artist
	prior, _ := a.linkOf(item)
	for _, ref := range songRefs(item) {
		song := mblinks.Song{Provider: ref.Provider, ID: ref.ID, State: state, Artist: name, ArtistMBID: prior.ArtistMBID, ArtistState: prior.ArtistState, CheckedAt: prior.CheckedAt}
		if state == mblinks.Matched {
			song.MBID = mbid
		}
		if artist != nil {
			song.ArtistMBID, song.ArtistState = artist.MBID, artist.State
		}
		// Whatever made the link, the song was looked at now, so the app does
		// not try to tie it by itself again at once.
		song.CheckedAt = time.Now()
		saved, err := a.links.SetSong(song)
		if err != nil {
			log.Printf("keeping a link: %v", err)
		}
		a.queueSongLink(saved)
	}
	if artist != nil {
		a.recordArtist(*artist)
	}
	a.mirrorToLists(item, state, mbid)
}

// recordArtist keeps what an artist is, and tells the lists that hold songs by
// the artist.
func (a *app) recordArtist(artist mblinks.Artist) {
	if a.links == nil {
		return
	}
	saved, err := a.links.SetArtist(artist)
	if err != nil {
		log.Printf("keeping an artist link: %v", err)
	}
	a.queueArtistLink(saved)
	if a.libraries != nil {
		_, _ = a.libraries.SetArtistLink(artist.Name, library.State(artist.State), artist.MBID)
	}
	a.page.rowsDirty = true
}

// mirrorToLists tells every playlist that holds the song what its link is, so a
// list shows the same as the rest of the app and carries it to the server.
func (a *app) mirrorToLists(item youtube.MusicItem, state, mbid string) {
	if a.libraries == nil {
		return
	}
	key := item.Key()
	for _, lib := range a.libraries.List() {
		for _, held := range lib.Items {
			if held.Key == key {
				_, _ = a.libraries.SetLink(lib.ID, key, library.State(state), mbid)
			}
		}
	}
	a.page.rowsDirty = true
}

// importListLinks takes the links the playlists already hold into the store,
// so what was linked before the store existed is not lost. A song the store
// knows already is left as it is.
func (a *app) importListLinks() {
	if a.links == nil || a.libraries == nil {
		return
	}
	for _, lib := range a.libraries.List() {
		for _, item := range lib.Items {
			if item.State != library.Matched && item.State != library.Other {
				continue
			}
			provider := string(item.Source)
			if provider == "" {
				provider = string(media.SourceYTM)
			}
			if _, ok := a.links.Song(provider, item.Key); ok {
				continue
			}
			_, _ = a.links.SetSong(mblinks.Song{
				Provider: provider, ID: item.Key, State: string(item.State), MBID: item.MBID,
				Artist: item.Artist, ArtistMBID: item.ArtistMBID, ArtistState: string(item.ArtistState),
				UpdatedAt: item.AddedAt,
			})
		}
	}
}

// openLink opens the dialog for a song, to link its recording or its artist,
// and searches at once for the song's own names.
func (a *app) openLink(item youtube.MusicItem, kind linkKind) {
	if a.link != nil {
		a.link.load.stop()
	}
	d := &linkState{open: true, kind: kind, item: item}
	q := queryOf(item)
	song, _ := a.linkOf(item)
	switch kind {
	case linkArtist:
		artist, _ := a.links.Artist(q.artist)
		d.linked = artist.State == mblinks.Matched || artist.State == mblinks.Other
		d.query = q.artist
	case linkSong:
		d.linked = song.State == mblinks.Matched || song.State == mblinks.Other
		d.query = item.Title
	}
	a.link = d
	a.searchLink()
}

// searchLink looks for the records the field names and ranks them.
func (a *app) searchLink() {
	d := a.link
	if d == nil {
		return
	}
	query := strings.TrimSpace(d.query)
	d.candidates, d.selected, d.err = nil, 0, ""
	if query == "" || a.mb == nil {
		d.loading = false
		return
	}
	d.loading = true
	client, kind, item := a.mb, d.kind, d.item
	startTask(a, &d.load, linkTimeout, func(ctx context.Context) ([]linkCandidate, error) {
		if kind == linkArtist {
			return searchArtistLinks(ctx, client, query)
		}
		return searchSongLinks(ctx, client, query, queryOf(item))
	}, func(found []linkCandidate, err error) {
		d.loading = false
		if err != nil {
			d.err = "MusicBrainz is unavailable right now."
			return
		}
		d.candidates = found
	})
}

// searchArtistLinks finds the artists a query names, those that fit it best
// first and, among the ones that fit alike, the most listened to first.
func searchArtistLinks(ctx context.Context, client metadataClient, query string) ([]linkCandidate, error) {
	artists, err := client.SearchArtists(ctx, query)
	if err != nil {
		return nil, err
	}
	// Popularity only orders the artists; a failure to read it leaves the
	// order MusicBrainz gave.
	if popularity, err := client.ArtistPopularity(ctx, artistIDs(artists)); err == nil {
		for i := range artists {
			artists[i].Listeners = popularity[artists[i].ID].Listeners
		}
	}
	found := make([]linkCandidate, 0, len(artists))
	for _, artist := range artists {
		detail := "Artist"
		if artist.Country != "" {
			detail += " · " + artist.Country
		}
		if artist.Disambiguation != "" {
			detail += " · " + artist.Disambiguation
		}
		if artist.Listeners > 0 {
			detail += " · " + strconv.FormatInt(artist.Listeners, 10) + " listeners"
		}
		found = append(found, linkCandidate{id: artist.ID, name: artist.Name, detail: detail, similarity: artist.Score, listeners: artist.Listeners})
	}
	sort.SliceStable(found, func(i, j int) bool {
		if found[i].similarity/10 != found[j].similarity/10 {
			return found[i].similarity > found[j].similarity
		}
		return found[i].listeners > found[j].listeners
	})
	return found, nil
}

// searchSongLinks finds the recordings a title names, those that fit best
// first and, among the ones that fit alike, the ones nearest the track's
// length. A title with the track's artist that finds nothing is tried again
// without the artist.
func searchSongLinks(ctx context.Context, client metadataClient, title string, item songQuery) ([]linkCandidate, error) {
	recordings, err := client.SearchRecordings(ctx, title, item.artist)
	if err != nil {
		return nil, err
	}
	if len(recordings) == 0 && item.artist != "" {
		if recordings, err = client.SearchRecordings(ctx, title, ""); err != nil {
			return nil, err
		}
	}
	found := make([]linkCandidate, 0, len(recordings))
	for _, r := range recordings {
		detail := "Recording · " + r.Artist
		if length := formatMilliseconds(r.LengthMS); length != "" {
			detail += " · " + length
		}
		found = append(found, linkCandidate{id: r.ID, name: r.Title, detail: detail, similarity: r.Score, artistID: r.ArtistID})
	}
	distance := func(c linkCandidate) int64 {
		for _, r := range recordings {
			if r.ID == c.id && r.LengthMS > 0 && item.durationMS > 0 {
				return abs64(int64(r.LengthMS) - item.durationMS)
			}
		}
		return 1 << 40
	}
	sort.SliceStable(found, func(i, j int) bool {
		if found[i].similarity/10 != found[j].similarity/10 {
			return found[i].similarity > found[j].similarity
		}
		return distance(found[i]) < distance(found[j])
	})
	return found, nil
}

func abs64(n int64) int64 {
	if n < 0 {
		return -n
	}
	return n
}

// autoLink ties a song to its MusicBrainz recording when the match is certain,
// and its artist with it. Anything less leaves the song unknown, for the
// listener to link or to leave.
func (a *app) autoLink(item youtube.MusicItem) {
	a.autoLinkAll([]youtube.MusicItem{item})
}

// needsAutoLink says whether the app should look for a song's recording by
// itself: not when it is linked, or marked as not there, or tried lately.
func (a *app) needsAutoLink(item youtube.MusicItem) bool {
	if !item.IsTrack() || strings.TrimSpace(item.Title) == "" {
		return false
	}
	song, ok := a.linkOf(item)
	if !ok {
		return true
	}
	if song.State == mblinks.Matched || song.State == mblinks.Other {
		return false
	}
	return !a.links.Fresh(song.CheckedAt)
}

// autoLinkAll does autoLink for each of items, one after the other in the
// background: MusicBrainz allows a request a second, so a long list takes a
// while, and each song is marked as its answer comes in while the app stays at
// the listener's hands. A song the store already knows is not asked about
// again.
func (a *app) autoLinkAll(items []youtube.MusicItem) {
	if a.mb == nil || a.links == nil {
		return
	}
	var todo []youtube.MusicItem
	for _, item := range items {
		if a.needsAutoLink(item) {
			todo = append(todo, item)
		}
	}
	if len(todo) == 0 {
		return
	}
	client := a.mb
	a.run(func() {
		for _, item := range todo {
			q := queryOf(item)
			ctx, cancel := context.WithTimeout(context.Background(), linkTimeout)
			recordings, err := client.SearchRecordings(ctx, q.title, q.artist)
			cancel()
			if errors.Is(err, musicbrainz.ErrRateLimited) {
				// MusicBrainz asked us to slow down: the rest stay unknown
				// for the listener to link, rather than adding to the load.
				return
			}
			if err != nil {
				continue
			}
			recording, ok := certainRecording(q, recordings)
			a.update(func() { a.applyAutoLink(item, recording, ok) })
		}
	})
}

// applyAutoLink records what the look for a song's recording found: the
// recording and its artist when it was sure, and otherwise only that it was
// tried, so the song is not asked about again for a while.
func (a *app) applyAutoLink(item youtube.MusicItem, recording musicbrainz.RecordingMatch, found bool) {
	if !found {
		prior, _ := a.linkOf(item)
		state := prior.State
		if state == "" {
			state = mblinks.Unknown
		}
		a.recordSong(item, state, prior.MBID, nil)
		return
	}
	var artist *mblinks.Artist
	if recording.ArtistID != "" {
		artist = &mblinks.Artist{Name: queryOf(item).artist, State: mblinks.Matched, MBID: recording.ArtistID}
	}
	a.recordSong(item, mblinks.Matched, recording.ID, artist)
}

// certainRecording picks the recording a song is, only when it is sure: the
// title and the artist, folded, are the same and both lengths are known and
// within match.DurationTolerance. When recordings that fit name different
// first artists the song could be any of them, so none is chosen.
func certainRecording(item songQuery, recordings []musicbrainz.RecordingMatch) (musicbrainz.RecordingMatch, bool) {
	if item.durationMS <= 0 || match.Normalize(item.artist) == "" {
		return musicbrainz.RecordingMatch{}, false
	}
	var best musicbrainz.RecordingMatch
	found := false
	for _, r := range recordings {
		if r.LengthMS <= 0 {
			continue
		}
		same := match.Match(
			match.Track{Title: item.title, Artist: item.artist, Duration: time.Duration(item.durationMS) * time.Millisecond},
			match.Track{Title: r.Title, Artist: r.Artist, Duration: time.Duration(r.LengthMS) * time.Millisecond},
		) == match.Matched
		if !same {
			continue
		}
		if found && r.ArtistID != best.ArtistID {
			return musicbrainz.RecordingMatch{}, false
		}
		if !found || r.Score > best.Score {
			best = r
		}
		found = true
	}
	return best, found
}

// applyLink records the dialog's answer: the chosen record, the listener's mark
// that there is none, or no link.
func (a *app) applyLink(state string, chosen *linkCandidate) {
	d := a.link
	if d == nil {
		return
	}
	id := ""
	if chosen != nil {
		id = chosen.id
	}
	q := queryOf(d.item)
	switch d.kind {
	case linkArtist:
		a.recordArtist(mblinks.Artist{Name: q.artist, State: state, MBID: id})
	case linkSong:
		var artist *mblinks.Artist
		if state == mblinks.Matched && chosen != nil && chosen.artistID != "" {
			// The recording names its artist, which ties the artist too.
			artist = &mblinks.Artist{Name: q.artist, State: mblinks.Matched, MBID: chosen.artistID}
		}
		a.recordSong(d.item, state, id, artist)
	}
	a.link.load.stop()
	a.link = nil
	a.page.rowsDirty = true
}

// linkDialog asks which MusicBrainz record a track, or its artist, is. The
// records are listed by how well they fit and how much they are listened to;
// the listener chooses one, says MusicBrainz does not hold it, or takes a link
// away.
func (a *app) linkDialog(c *ui.Context) {
	d := a.link
	if d == nil {
		return
	}
	if !d.open {
		d.load.stop()
		a.link = nil // closed by its scrim or the Escape key
		return
	}
	sc := m3.Of(c).Scheme
	m3.Dialog(c, &d.open, 560, func() {
		m3.EmphasizedText(c, m3.HeadlineSmall, "Link to MusicBrainz")
		name := d.item.Title
		if d.kind == linkArtist {
			name = queryOf(d.item).artist
		}
		m3.Text(c, m3.BodyMedium, "“"+name+"”").TextColor(sc.OnSurfaceVariant).SingleLine().Tooltip(name)
		ui.Row(c).Gap(8).Children(func() {
			for _, tab := range []struct {
				label string
				kind  linkKind
			}{{"Song", linkSong}, {"Artist", linkArtist}} {
				if m3.Button(c, m3.ButtonSpec{
					Label: tab.label, Kind: m3.Tonal, Size: m3.ExtraSmall32, Toggle: true, Selected: d.kind == tab.kind, Key: "link-kind-" + tab.label,
				}).Clicked() && d.kind != tab.kind {
					a.openLink(d.item, tab.kind)
					return
				}
			}
		})
		m3.Text(c, m3.BodySmall, "Results are ordered by how well they fit the search and how much they are listened to. Pick the right one.").TextColor(sc.OnSurfaceVariant)
		box := ui.Row(c.Key("link-search")).Padding(8, 16).Radius(m3.Full).Background(sc.SurfaceContainerHighest)
		if box.FocusWithin() {
			box.Border(2, sc.Primary)
		}
		box.Children(func() {
			ui.TextInputBase(c.Key("link-search-field"), &d.query).
				Placeholder("Search MusicBrainz").Label("Search MusicBrainz").TextColor(sc.OnSurface).FontSize(16).Grow(1).
				OnSubmit(a.searchLink)
		})
		ui.Column(c).Gap(4).Children(func() {
			switch {
			case d.loading:
				m3.LoadingIndicator(c, 32, false)
			case d.err != "":
				m3.Text(c, m3.BodyMedium, d.err).TextColor(sc.OnSurfaceVariant)
			case len(d.candidates) == 0:
				m3.Text(c, m3.BodyMedium, "Nothing found. Search again, or say it is not on MusicBrainz.").TextColor(sc.OnSurfaceVariant)
			default:
				shown := d.candidates
				if len(shown) > linkResultsShown {
					shown = shown[:linkResultsShown]
				}
				for i, candidate := range shown {
					a.linkResult(c, d, i, candidate)
				}
			}
		})
		ui.Row(c).Gap(8).AlignItems(ui.Center).Wrap().Children(func() {
			if m3.Button(c, m3.ButtonSpec{Label: "Not on MusicBrainz", Kind: m3.Outlined, Size: m3.ExtraSmall32, Key: "link-other"}).Clicked() {
				a.applyLink(mblinks.Other, nil)
				return
			}
			if d.linked && m3.Button(c, m3.ButtonSpec{Label: "Remove link", Kind: m3.Outlined, Size: m3.ExtraSmall32, Key: "link-remove"}).Clicked() {
				a.applyLink(mblinks.Unknown, nil)
				return
			}
			ui.Spacer(c)
			if m3.Button(c, m3.ButtonSpec{Label: "Cancel", Kind: m3.TextOnly, Key: "link-cancel"}).Clicked() {
				d.open = false
				return
			}
			chosen := d.selected >= 0 && d.selected < min(len(d.candidates), linkResultsShown)
			if m3.Button(c, m3.ButtonSpec{Label: "Link", Kind: m3.Filled, Disabled: !chosen, Key: "link-confirm"}).Clicked() && chosen {
				candidate := d.candidates[d.selected]
				a.applyLink(mblinks.Matched, &candidate)
			}
		})
	})
}

// linkResult is one record the search found: its name and what tells it from
// the others, and how well it fits.
func (a *app) linkResult(c *ui.Context, d *linkState, i int, candidate linkCandidate) {
	sc := m3.Of(c).Scheme
	row := ui.ButtonBase(c.Key("link-result-" + strconv.Itoa(i)))
	row.Height(56).PaddingX(12).Gap(12).AlignItems(ui.Center).Radius(m3.Large).Cursor(ui.CursorPointer).
		Label("Link to " + candidate.name + ", " + candidate.detail).
		Background(m3.StateFill(sc.SurfaceContainerHighest, sc.OnSurface, row.Hovered(), row.Pressed(), row.FocusVisible()))
	if d.selected == i {
		row.Border(2, sc.Primary)
	}
	row.Children(func() {
		ui.Column(c).Grow(1).MinWidth(0).Children(func() {
			m3.Text(c, m3.BodyLarge, candidate.name).SingleLine().Tooltip(candidate.name).TextColor(sc.OnSurface)
			m3.Text(c, m3.BodySmall, candidate.detail).SingleLine().Tooltip(candidate.detail).TextColor(sc.OnSurfaceVariant)
		})
		if candidate.similarity > 0 {
			m3.Text(c, m3.BodySmall, strconv.Itoa(candidate.similarity)+"% fit").SingleLine().Shrink(0).TextColor(sc.OnSurfaceVariant)
		}
	})
	if row.Clicked() {
		d.selected = i
	}
}

// linkControls draws what a song says of its MusicBrainz link: a chip for what
// it is, which opens the link to change or remove it, and for a song that is
// unknown a small button that opens the search. The unknown song is never
// pressed for: the button waits. It reports whether anything was pressed, so
// the row is not also played.
func (a *app) linkControls(c *ui.Context, item youtube.MusicItem) bool {
	sc := m3.Of(c).Scheme
	pressed := false
	song, _ := a.linkOf(item)
	state := song.State
	if state == "" {
		state = mblinks.Unknown
	}
	key := item.Key()
	if state == mblinks.Unknown {
		libraryStateChip(c, state)
		button := ui.ButtonBase(c.Key("link-" + key))
		button.Size(badgeSize, badgeSize).Radius(m3.Full).Center().Cursor(ui.CursorPointer).
			Label("Link to MusicBrainz").Tooltip("Link to MusicBrainz").
			Background(m3.StateFill(ui.Transparent, sc.OnSurface, button.Hovered(), button.Pressed(), button.FocusVisible()))
		button.Children(func() {
			ui.Icon(c, m3.IconLink).FontSize(badgeIcon).TextColor(sc.OnSurfaceVariant)
		})
		if button.Clicked() {
			a.openLink(item, linkSong)
			pressed = true
		}
		return pressed
	}
	chip := ui.ButtonBase(c.Key("link-chip-" + key))
	chip.Radius(m3.Full).Cursor(ui.CursorPointer).Label("Change the MusicBrainz link").Tooltip("Change or remove the MusicBrainz link")
	chip.Children(func() { libraryStateChip(c, state) })
	if chip.Clicked() {
		a.openLink(item, linkSong)
		pressed = true
	}
	return pressed
}
