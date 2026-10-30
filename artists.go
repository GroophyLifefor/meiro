package main

import (
	"context"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/musicbrainz"
	"github.com/elianiva/meiro/youtube"
)

// metadataClient is the artist and recording data the app reads: MusicBrainz
// for what an artist is and where a recording plays, and ListenBrainz for how
// much it was listened to. It is an interface so a test can answer without a
// network.
type metadataClient interface {
	SearchArtists(ctx context.Context, query string) ([]musicbrainz.Artist, error)
	ArtistPopularity(ctx context.Context, mbids []string) (map[string]musicbrainz.Popularity, error)
	TopRecordings(ctx context.Context, artistMBID string) ([]musicbrainz.Recording, error)
	LookupURL(ctx context.Context, resource string) ([]musicbrainz.RecordingRef, error)
	LookupISRC(ctx context.Context, isrc string) ([]musicbrainz.RecordingRef, error)
	RecordingLinks(ctx context.Context, recordingMBID string) ([]musicbrainz.Link, error)
	SearchRecordings(ctx context.Context, title, artist string) ([]musicbrainz.RecordingMatch, error)
	// CacheEntries lists the answers kept on disk, and the other two forget
	// them, for the settings card that shows the cache.
	SimilarArtists(ctx context.Context, artistMBID string) ([]musicbrainz.SimilarArtist, error)
	CacheEntries() []musicbrainz.CacheEntry
	RemoveCacheEntry(id string) error
	ClearCache() error
}

// artistTimeout bounds the artist section of a search.
const artistTimeout = 30 * time.Second

// mbLinkLimit is how many of an artist's top recordings the page resolves
// provider links for. Each is a MusicBrainz request a second apart, so only
// the first few are worth the wait; the rest are shown without a link.
const mbLinkLimit = 12

// artistSection is the MusicBrainz artist part of the search page: the
// artists a submitted query matched, most listened to first.
type artistSection struct {
	// query is the search the artists are for; it is what ties them to the
	// results shown beside them.
	query   string
	loading bool
	err     string
	artists []musicbrainz.Artist
	load    task
}

// loadArtists reads the MusicBrainz artists a search matched, and their
// ListenBrainz popularity, and puts them most listened to first. An empty
// query clears the section.
func (a *app) loadArtists(query string) {
	query = strings.TrimSpace(query)
	s := &a.artists
	s.load.stop()
	if query == "" {
		*s = artistSection{}
		return
	}
	s.query, s.loading, s.err, s.artists = query, true, "", nil
	if a.mb == nil {
		s.loading = false
		return
	}
	client := a.mb
	startTask(a, &s.load, artistTimeout, func(ctx context.Context) ([]musicbrainz.Artist, error) {
		artists, err := client.SearchArtists(ctx, query)
		if err != nil {
			return nil, err
		}
		// Popularity is a second call, and a failure to get it is not a
		// failure to find the artists: they are shown in MusicBrainz's own
		// order instead.
		if len(artists) > 0 {
			if popular, err := client.ArtistPopularity(ctx, artistIDs(artists)); err == nil {
				for i := range artists {
					if p, ok := popular[artists[i].ID]; ok {
						artists[i].Listeners, artists[i].Listens = p.Listeners, p.Listens
					}
				}
			}
		}
		sortArtists(artists)
		return artists, nil
	}, func(artists []musicbrainz.Artist, err error) {
		s.loading = false
		if err != nil {
			s.err = firstLine(err.Error())
			return
		}
		s.artists = artists
	})
}

// artistIDs is the IDs of a list of artists, for a popularity lookup.
func artistIDs(artists []musicbrainz.Artist) []string {
	ids := make([]string, 0, len(artists))
	for _, artist := range artists {
		if artist.ID != "" {
			ids = append(ids, artist.ID)
		}
	}
	return ids
}

// sortArtists puts the most listened to artists first, keeping MusicBrainz's
// own relevance order for the ones ListenBrainz had nothing on.
func sortArtists(artists []musicbrainz.Artist) {
	sort.SliceStable(artists, func(i, j int) bool {
		return artists[i].Listeners > artists[j].Listeners
	})
}

// artistRowsShown says what the artist section adds to a search's list: how
// many artists it shows, and whether it shows the line that they are out of
// reach. Both are zero while it waits or has nothing for the query.
func (a *app) artistRowsShown() (artists int, unavailable bool) {
	s := &a.artists
	if s.query == "" || s.query != a.search.submitted || !a.artistsWanted() {
		return 0, false
	}
	if len(s.artists) > 0 {
		return min(len(s.artists), suggestionsShown), false
	}
	return 0, !s.loading && s.err != ""
}

// artistsWanted says whether the search being shown calls for the MusicBrainz
// artists: one with no filter does, and one filtered to artists. A search the
// listener narrowed to songs, albums or any other kind has no use for them.
func (a *app) artistsWanted() bool {
	params := a.search.params
	if params == "" {
		return true
	}
	for _, filter := range a.search.filters {
		if filter.Params == params {
			return strings.EqualFold(strings.TrimSpace(filter.Label), "Artists")
		}
	}
	return false
}

// artistSection shows the MusicBrainz artists a search matched, as rows above
// the provider's own results. A failure to reach MusicBrainz says so in one
// line and leaves the provider's search alone.
func (a *app) artistSectionView(c *ui.Context) {
	sc := m3.Of(c).Scheme
	s := &a.artists
	if s.query == "" || s.query != a.search.submitted || !a.artistsWanted() {
		return
	}
	if len(s.artists) == 0 {
		if s.loading {
			return
		}
		if s.err != "" {
			ui.Row(c).Padding(4, pageGutter, 12).Children(func() {
				m3.Text(c, m3.BodySmall, "Artist data is unavailable right now.").TextColor(sc.OnSurfaceVariant)
			})
		}
		return
	}
	shown := s.artists
	if len(shown) > suggestionsShown {
		shown = shown[:suggestionsShown]
	}
	ui.Column(c).Shrink(0).Children(func() {
		m3.EmphasizedText(c, m3.TitleMedium, "Artists").Padding(12, pageGutter, 4).TextColor(sc.OnSurface)
		for _, artist := range shown {
			a.artistRow(c, artist)
		}
	})
}

// artistRow is one MusicBrainz artist of the search's artist section.
func (a *app) artistRow(c *ui.Context, artist musicbrainz.Artist) {
	sc := m3.Of(c).Scheme
	row := ui.ButtonBase(c.Key("mb-artist-" + artist.ID))
	row.Height(60).Margin(0, pageGutter).Padding(4, 8).Gap(12).AlignItems(ui.Center).Radius(m3.Large).
		Cursor(ui.CursorPointer).Label(artist.Name).
		Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), row.Pressed(), row.FocusVisible()))
	row.Children(func() {
		ui.Box(c).Size(40, 40).Shrink(0).Radius(m3.Full).Background(sc.SecondaryContainer).Center().Children(func() {
			ui.Icon(c, m3.IconPerson).FontSize(22).TextColor(sc.OnSecondaryContainer)
		})
		ui.Column(c).Grow(1).MinWidth(0).Children(func() {
			m3.Text(c, m3.BodyLarge, artist.Name).SingleLine().Tooltip(artist.Name).TextColor(sc.OnSurface)
			if subtitle := artistSubtitle(artist); subtitle != "" {
				m3.Text(c, m3.BodySmall, subtitle).SingleLine().Tooltip(subtitle).TextColor(sc.OnSurfaceVariant)
			}
		})
		if artist.Listeners > 0 {
			m3.Text(c, m3.LabelMedium, compactCount(artist.Listeners)+" listeners").Shrink(0).TextColor(sc.OnSurfaceVariant)
		}
	})
	if row.Clicked() {
		a.openMBArtist(artist)
	}
}

// artistSubtitle is the line under an artist's name: where they are from, and
// what tells them apart from another artist of the same name.
func artistSubtitle(artist musicbrainz.Artist) string {
	parts := make([]string, 0, 2)
	if artist.Country != "" {
		parts = append(parts, artist.Country)
	}
	if artist.Disambiguation != "" {
		parts = append(parts, artist.Disambiguation)
	}
	return strings.Join(parts, " · ")
}

// canceled reports whether a context has ended. It is a function so that the
// nilerr linter, which reads a bare ctx.Err() != nil before a nil return as a
// swallowed error, does not misread a deliberate stop.
func canceled(ctx context.Context) bool { return ctx.Err() != nil }

// mbRecording is one of an artist's top recordings, and where it plays.
type mbRecording struct {
	recording musicbrainz.Recording
	link      musicbrainz.Link
	// item is what plays the recording; playable says whether there is one.
	item     youtube.MusicItem
	playable bool
}

// mbArtistState is the MusicBrainz artist page shown: the artist's name and
// its top recordings, with the provider links found for them so far.
type mbArtistState struct {
	mbid       string
	name       string
	loading    bool
	err        string
	recordings []mbRecording
	load       task
}

// openMBArtistPage loads the artist page the router moved to, unless it is
// the one already shown.
func (a *app) openMBArtistPage() {
	_, id := a.route()
	if a.mbArtist.mbid == id && (a.mbArtist.loading || len(a.mbArtist.recordings) > 0) {
		return
	}
	name := ""
	if a.mbArtist.mbid == id {
		name = a.mbArtist.name
	}
	a.loadMBArtist(id, name)
}

// openMBArtist opens an artist's MusicBrainz page, seeding the heading with
// the name the row had at hand.
func (a *app) openMBArtist(artist musicbrainz.Artist) {
	a.mbArtist.name, a.mbArtist.mbid = artist.Name, artist.ID
	a.router.Push(routeByPath(routeMBArtist, artist.ID))
}

// loadMBArtist reads an artist's top recordings, shows them, then resolves
// where each plays, one MusicBrainz request at a time, filling the badges in
// as the answers come.
func (a *app) loadMBArtist(id, name string) {
	client := a.mb
	a.mbArtist.load.stop()
	a.mbArtist = mbArtistState{mbid: id, name: name, loading: true}
	if client == nil {
		a.mbArtist.loading = false
		a.mbArtist.err = "Artist lookups are not set up in this build."
		return
	}
	startProgress(a, &a.mbArtist.load, noTimeout, func(ctx context.Context, progress func(func())) ([]mbRecording, error) {
		recordings, err := client.TopRecordings(ctx, id)
		if err != nil {
			return nil, err
		}
		shown := make([]mbRecording, 0, len(recordings))
		for _, recording := range recordings {
			shown = append(shown, mbRecording{recording: recording})
		}
		progress(func() {
			a.mbArtist.loading, a.mbArtist.recordings = false, shown
		})
		for i := range recordings {
			if i >= mbLinkLimit || canceled(ctx) {
				break
			}
			links, linkErr := client.RecordingLinks(ctx, recordings[i].ID)
			if linkErr != nil || len(links) == 0 {
				continue
			}
			item, link, ok := playableRecording(recordings[i], links)
			if !ok {
				continue
			}
			index := i
			progress(func() {
				if index < len(a.mbArtist.recordings) {
					row := &a.mbArtist.recordings[index]
					row.item, row.link, row.playable = item, link, true
				}
			})
		}
		return shown, nil
	}, func(shown []mbRecording, err error) {
		a.mbArtist.loading = false
		if err != nil {
			a.mbArtist.err = firstLine(err.Error())
			return
		}
		a.mbArtist.recordings = shown
	})
}

// mbArtistPage shows a MusicBrainz artist: its name, and its top recordings
// with where each can be played. A recording with no provider link is shown
// greyed out, so it is clear there is nothing to play.
func (a *app) mbArtistPage(c *ui.Context) {
	sc := m3.Of(c).Scheme
	s := &a.mbArtist
	playable := make([]youtube.MusicItem, 0, len(s.recordings))
	for _, recording := range s.recordings {
		if recording.playable {
			playable = append(playable, recording.item)
		}
	}
	ui.Scroll(c).Grow(1).Children(func() {
		ui.Column(c).Padding(8, 0, a.clearance()).Children(func() {
			title := s.name
			if title == "" {
				title = "Artist"
			}
			m3.EmphasizedText(c, m3.HeadlineMedium, title).Padding(8, pageGutter, 4).TextColor(sc.OnSurface)
			m3.Text(c, m3.BodyMedium, "Top recordings from MusicBrainz and ListenBrainz").Padding(0, pageGutter, 12).TextColor(sc.OnSurfaceVariant)
			switch {
			case s.loading && len(s.recordings) == 0:
				ui.Row(c).Height(240).Justify(ui.Center).AlignItems(ui.Center).Children(func() {
					m3.LoadingIndicator(c, 56, true)
				})
			case s.err != "" && len(s.recordings) == 0:
				a.message(c, m3.IconError, "Could not load this artist", s.err, "Retry", a.retry)
			case len(s.recordings) == 0:
				a.message(c, m3.IconMusicNote, "No recordings", "MusicBrainz has no recordings for this artist.", "Retry", a.retry)
			default:
				index := 0
				for _, recording := range s.recordings {
					playIndex := -1
					if recording.playable {
						playIndex = index
						index++
					}
					a.mbRecordingRow(c, recording, playIndex, playable)
				}
			}
		})
	})
}

// mbRecordingRow is one recording of the artist page. A recording with a
// provider link plays from the list of the artist's playable recordings; one
// without is drawn greyed out and does nothing.
func (a *app) mbRecordingRow(c *ui.Context, recording mbRecording, playIndex int, playable []youtube.MusicItem) {
	sc := m3.Of(c).Scheme
	row := ui.Row(c.Key("mb-rec-" + recording.recording.ID))
	row.Height(64).Shrink(0).Margin(0, pageGutter-8).Padding(4, 8).Gap(12).AlignItems(ui.Center).Radius(m3.Large)
	if !recording.playable {
		row.Opacity(0.55)
	}
	var main ui.Element
	row.Children(func() {
		main = ui.ButtonBase(c.Key("mb-rec-main-" + recording.recording.ID))
		main.Grow(1).Basis(0).MinWidth(0).Height(56).Padding(4, 8).Gap(12).AlignItems(ui.Center).Radius(m3.Large)
		if recording.playable {
			main.Cursor(ui.CursorPointer).Label(recording.recording.Title).
				Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), main.Pressed(), main.FocusVisible()))
		} else {
			main.PassThrough()
		}
		main.Children(func() {
			ui.Box(c).Size(40, 40).Shrink(0).Radius(m3.Medium).Background(sc.SurfaceContainerHighest).Center().Children(func() {
				icon := m3.IconMusicNote
				if recording.playable {
					icon = m3.IconPlay
				}
				ui.Icon(c, icon).FontSize(20).TextColor(sc.OnSurfaceVariant)
			})
			ui.Column(c).Grow(1).MinWidth(0).Children(func() {
				m3.Text(c, m3.BodyLarge, recording.recording.Title).SingleLine().Tooltip(recording.recording.Title).TextColor(sc.OnSurface)
				if subtitle := recordingSubtitle(recording.recording); subtitle != "" {
					m3.Text(c, m3.BodySmall, subtitle).SingleLine().Tooltip(subtitle).TextColor(sc.OnSurfaceVariant)
				}
			})
			if recording.playable {
				providerPill(c, sourceLabel(string(recording.item.Source)))
			} else {
				providerPill(c, "No link")
			}
		})
	})
	if recording.playable && main.Clicked() {
		a.playFrom(recording.item, playable, max(playIndex, 0), youtube.UpNextOptions{}, "Artist")
	}
}

// recordingSubtitle is the line under a recording's name: its artist, when
// ListenBrainz named one, and its length.
func recordingSubtitle(recording musicbrainz.Recording) string {
	parts := make([]string, 0, 2)
	if recording.Artist != "" {
		parts = append(parts, recording.Artist)
	}
	if length := formatMilliseconds(recording.LengthMS); length != "" {
		parts = append(parts, length)
	}
	return strings.Join(parts, " · ")
}

// providerPill is a small badge naming the provider a recording plays on.
func providerPill(c *ui.Context, label string) {
	sc := m3.Of(c).Scheme
	ui.Row(c).Shrink(0).Padding(4, 10).Radius(m3.Full).Background(sc.SecondaryContainer).Children(func() {
		m3.Text(c, m3.LabelSmall, label).SingleLine().TextColor(sc.OnSecondaryContainer)
	})
}

// playableRecording maps a recording and its MusicBrainz links to the item
// that plays it, and the link it came from. The first link whose provider the
// app can play wins: a music.youtube.com or youtube.com link plays as YouTube
// Music, a soundcloud.com link as SoundCloud.
func playableRecording(recording musicbrainz.Recording, links []musicbrainz.Link) (youtube.MusicItem, musicbrainz.Link, bool) {
	item := youtube.MusicItem{
		Title:    recording.Title,
		Subtitle: recording.Artist,
		Kind:     media.KindTrack,
		Duration: formatMilliseconds(recording.LengthMS),
	}
	// A free streaming link is the one a listener can play without paying,
	// so it is preferred over a paid streaming or a download.
	prefer := make([]musicbrainz.Link, 0, len(links))
	for _, link := range links {
		if link.Type == "free streaming" {
			prefer = append(prefer, link)
		}
	}
	prefer = append(prefer, links...)
	for _, link := range prefer {
		switch {
		case youtubeWatchID(link.URL) != "":
			item.Source, item.VideoID = media.SourceYTM, youtubeWatchID(link.URL)
			return item, link, true
		case isSoundCloudURL(link.URL):
			item.Source, item.URL = media.SourceSoundCloud, link.URL
			return item, link, true
		}
	}
	return youtube.MusicItem{}, musicbrainz.Link{}, false
}

// youtubeWatchID reads the video ID from a YouTube or YouTube Music watch
// link, or "".
func youtubeWatchID(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	host := strings.ToLower(parsed.Hostname())
	if host != "youtube.com" && !strings.HasSuffix(host, ".youtube.com") && host != "youtu.be" {
		return ""
	}
	if host == "youtu.be" {
		return strings.Trim(parsed.Path, "/")
	}
	return parsed.Query().Get("v")
}

// isSoundCloudURL reports whether a link is a SoundCloud page.
func isSoundCloudURL(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil {
		return false
	}
	host := strings.ToLower(parsed.Hostname())
	return host == "soundcloud.com" || strings.HasSuffix(host, ".soundcloud.com")
}

// formatMilliseconds writes a length in milliseconds as "3:42" or "1:02:03",
// and an unknown one as "".
func formatMilliseconds(ms int) string {
	if ms <= 0 {
		return ""
	}
	total := ms / 1000
	hours, minutes, seconds := total/3600, (total%3600)/60, total%60
	if hours > 0 {
		return strconv.Itoa(hours) + ":" + twoDigits(minutes) + ":" + twoDigits(seconds)
	}
	return strconv.Itoa(minutes) + ":" + twoDigits(seconds)
}

func twoDigits(value int) string {
	if value < 10 {
		return "0" + strconv.Itoa(value)
	}
	return strconv.Itoa(value)
}

// compactCount writes a listener count shortly: 1234 as "1.2k", 1200000 as
// "1.2M".
func compactCount(count int64) string {
	switch {
	case count >= 1_000_000:
		return strconv.FormatFloat(float64(count)/1_000_000, 'f', 1, 64) + "M"
	case count >= 1_000:
		return strconv.FormatFloat(float64(count)/1_000, 'f', 1, 64) + "k"
	default:
		return strconv.FormatInt(count, 10)
	}
}
