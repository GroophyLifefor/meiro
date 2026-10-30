package main

import (
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/internal/mblinks"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/musicbrainz"
	"github.com/elianiva/meiro/youtube"
)

// A song is tied to a recording only when the names agree and both lengths are
// known and close; a title that fits two different artists could be either and
// is left unknown.
func TestCertainRecordingOnlyWhenSure(t *testing.T) {
	item := songQuery{title: "Get Lucky", artist: "Daft Punk", durationMS: 248_000}
	same := musicbrainz.RecordingMatch{ID: "r1", Title: "Get Lucky", Artist: "Daft Punk", ArtistID: "a1", LengthMS: 247_000, Score: 100}

	if got, ok := certainRecording(item, []musicbrainz.RecordingMatch{same}); !ok || got.ID != "r1" {
		t.Errorf("an exact recording was not chosen: %+v %v", got, ok)
	}
	longer := same
	longer.ID, longer.LengthMS = "r2", 300_000
	if got, ok := certainRecording(item, []musicbrainz.RecordingMatch{longer}); ok {
		t.Errorf("a recording of another length was chosen: %+v", got)
	}
	unmeasured := same
	unmeasured.LengthMS = 0
	if _, ok := certainRecording(item, []musicbrainz.RecordingMatch{unmeasured}); ok {
		t.Error("a recording with no length was chosen")
	}
	if _, ok := certainRecording(songQuery{title: "Get Lucky", artist: "Daft Punk"}, []musicbrainz.RecordingMatch{same}); ok {
		t.Error("a song with no length was tied to a recording")
	}
	other := same
	other.ID, other.ArtistID = "r3", "a9"
	if _, ok := certainRecording(item, []musicbrainz.RecordingMatch{same, other}); ok {
		t.Error("a title two different artists made was tied to one of them")
	}
	again := same
	again.ID, again.Score = "r4", 80
	if got, ok := certainRecording(item, []musicbrainz.RecordingMatch{again, same}); !ok || got.ID != "r1" {
		t.Errorf("two recordings by one artist: chose %+v %v, want the better fit r1", got, ok)
	}
}

// soundCloudSong is a song only SoundCloud holds.
func soundCloudSong(slug, title, artist, duration string) youtube.MusicItem {
	url := "https://soundcloud.com/x/" + slug
	return youtube.MusicItem{Source: media.SourceSoundCloud, Kind: media.KindTrack, URL: url, Title: title, Artist: artist, Subtitle: artist, Duration: duration}
}

// listWithOneTrack is an app with a playlist holding one SoundCloud song that
// MusicBrainz has not been asked about, with the playlist open.
func listWithOneTrack(t *testing.T, recordings map[string][]musicbrainz.RecordingMatch) (*app, string, youtube.MusicItem) {
	t.Helper()
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	fakeMB(a).found = recordings
	lib, err := a.libraries.Create("Night drive")
	if err != nil {
		t.Fatal(err)
	}
	song := soundCloudSong("gece", "Gece Yarısı", "Anıl Kaya", "3:41")
	if _, err := a.libraries.AddItem(lib.ID, libraryItem(song, "")); err != nil {
		t.Fatal(err)
	}
	a.router.Push("/libraries")
	a.libSelected = lib.ID
	return a, lib.ID, song
}

// linkOfSong is what the store says of a song.
func linkOfSong(t *testing.T, a *app, song youtube.MusicItem) mblinks.Song {
	t.Helper()
	got, ok := a.linkOf(song)
	if !ok {
		t.Fatalf("the store knows nothing of %q", song.Title)
	}
	return got
}

// A song added to a playlist is linked on its own when MusicBrainz knows it for
// certain, and its artist with it; one it cannot be sure of stays unknown, and
// is not asked about again at once.
func TestAddingASongLinksItWhenCertain(t *testing.T) {
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	fakeMB(a).found = map[string][]musicbrainz.RecordingMatch{
		"Sure":   {{ID: "rec-sure", Title: "Sure", Artist: "Someone", ArtistID: "art-1", LengthMS: 181_000, Score: 100}},
		"Unsure": {{ID: "rec-x", Title: "Unsure", Artist: "Someone", ArtistID: "art-1", LengthMS: 90_000, Score: 100}},
	}
	sure := youtube.MusicItem{VideoID: "k-sure", Title: "Sure", Artist: "Someone", Subtitle: "Someone", Kind: media.KindTrack, Duration: "3:00"}
	unsure := youtube.MusicItem{VideoID: "k-unsure", Title: "Unsure", Artist: "Someone", Subtitle: "Someone", Kind: media.KindTrack, Duration: "3:00"}
	a.autoLinkAll([]youtube.MusicItem{sure, unsure})
	pollUntil(t, a, "the certain song to be linked", func() bool {
		s, ok := a.linkOf(sure)
		return ok && s.State == mblinks.Matched
	})
	pollUntil(t, a, "the unsure song to be tried", func() bool {
		_, ok := a.linkOf(unsure)
		return ok
	})
	if s := linkOfSong(t, a, sure); s.MBID != "rec-sure" || s.ArtistMBID != "art-1" || s.ArtistState != mblinks.Matched {
		t.Errorf("a certain song was not linked with its artist: %+v", s)
	}
	if artist, ok := a.links.Artist("Someone"); !ok || artist.MBID != "art-1" {
		t.Errorf("the artist was not kept: %+v %v", artist, ok)
	}
	u := linkOfSong(t, a, unsure)
	if u.State != mblinks.Unknown || u.MBID != "" || u.CheckedAt.IsZero() {
		t.Errorf("a song MusicBrainz was unsure of: %+v, want unknown and tried", u)
	}
	// What is known is not asked about again: not the linked one, and not the
	// one tried a moment ago.
	if a.needsAutoLink(sure) || a.needsAutoLink(unsure) {
		t.Error("a song the store knows is asked about again")
	}
}

// A press on the link button of an unknown song, wherever the song is shown,
// opens a search; choosing a record ties the song to it, and its artist to the
// recording's artist.
func TestLinkingASongByHand(t *testing.T) {
	a, list, song := listWithOneTrack(t, map[string][]musicbrainz.RecordingMatch{
		"Gece Yarısı": {
			{ID: "rec-live", Title: "Gece Yarısı (live)", Artist: "Anıl Kaya", ArtistID: "art-ak", LengthMS: 220_000, Score: 97},
			{ID: "rec-studio", Title: "Gece Yarısı", Artist: "Anıl Kaya", ArtistID: "art-ak", LengthMS: 221_000, Score: 100},
		},
	})
	tt := ui.NewTester(a.view, 1000, 900)
	tt.Frame()
	if !tt.HasText("unknown") {
		t.Fatalf("the song is not shown unknown: %q", tt.Texts())
	}
	if err := tt.Click("Link to MusicBrainz"); err != nil {
		t.Fatal(err)
	}
	pollUntil(t, a, "the results", func() bool { tt.Frame(); return tt.HasText("100% fit") })
	// The best fit leads and is the choice until another is picked.
	if got := a.link.candidates[0].id; got != "rec-studio" {
		t.Errorf("the best fit is not first: %q", got)
	}
	if err := tt.Click("Link"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	s := linkOfSong(t, a, song)
	if s.State != mblinks.Matched || s.MBID != "rec-studio" || s.ArtistMBID != "art-ak" {
		t.Errorf("the song was not linked to the first result: %+v", s)
	}
	if a.link != nil {
		t.Error("the dialog stayed open")
	}
	// The playlist that holds the song says the same.
	lib, _ := a.libraries.Get(list)
	if lib.Items[0].State != library.Matched || lib.Items[0].MBID != "rec-studio" {
		t.Errorf("the playlist's copy does not say matched: %+v", lib.Items[0])
	}
	if !tt.HasText("matched") || tt.HasText("unknown") {
		t.Errorf("the row does not say matched: %q", tt.Texts())
	}
}

// A song is linked from a search, with no playlist involved, and stays linked
// when it turns up again anywhere.
func TestLinkingASongFromASearch(t *testing.T) {
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	a.router.Push("/search")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	if err := tt.Click(searchPlaceholder); err != nil {
		t.Fatal(err)
	}
	tt.Type("song")
	tt.Key(0, ui.KeyEnter)
	for range 8 {
		a.poll()
		tt.Frame()
	}
	var first youtube.MusicItem
	for _, item := range a.search.items {
		if item.IsTrack() {
			first = item
			break
		}
	}
	if first.Title == "" {
		t.Fatalf("the search found no song: %+v", a.search.items)
	}
	fakeMB(a).found = map[string][]musicbrainz.RecordingMatch{
		first.Title: {{ID: "rec-found", Title: first.Title, Artist: "Someone", ArtistID: "art-s", LengthMS: 200_000, Score: 100}},
	}
	if len(a.libraries.List()) != 0 {
		t.Fatal("the test starts with a playlist")
	}
	if err := tt.Click("Link to MusicBrainz"); err != nil {
		t.Fatalf("a search row has no link button: %v: %q", err, tt.Texts())
	}
	pollUntil(t, a, "the results", func() bool { tt.Frame(); return a.link != nil && len(a.link.candidates) > 0 })
	if err := tt.Click("Link"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if s := linkOfSong(t, a, first); s.State != mblinks.Matched || s.MBID != "rec-found" {
		t.Errorf("the song was not linked from the search: %+v", s)
	}
	if !tt.HasText("matched") {
		t.Errorf("the search row does not say matched: %q", tt.Texts())
	}
}

// "Not on MusicBrainz" marks the song other, and a link can be taken away from
// the chip.
func TestMarkingASongOtherAndRemovingALink(t *testing.T) {
	a, _, song := listWithOneTrack(t, nil)
	tt := ui.NewTester(a.view, 1000, 900)
	tt.Frame()
	if err := tt.Click("Link to MusicBrainz"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Not on MusicBrainz"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if got := linkOfSong(t, a, song); got.State != mblinks.Other || got.MBID != "" {
		t.Fatalf("the song was not marked other: %+v", got)
	}
	// Open the chip: an existing mark is offered to be removed.
	if err := tt.Click("Change the MusicBrainz link"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !tt.HasText("Remove link") {
		t.Fatalf("a marked song does not offer removing it: %q", tt.Texts())
	}
	if err := tt.Click("Remove link"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if got := linkOfSong(t, a, song); got.State != mblinks.Unknown {
		t.Errorf("the mark was not removed: %+v", got)
	}
}

// The artist tab ranks the artists a search found by how well they fit and,
// among those that fit alike, by how many listen to them, and links the
// song's artist.
func TestLinkingAnArtistRanksByFitThenListeners(t *testing.T) {
	a, _, song := listWithOneTrack(t, nil)
	fakeMB(a).artists = []musicbrainz.Artist{
		{ID: "art-small", Name: "Anil Kaya", Score: 100},
		{ID: "art-big", Name: "Anıl Kaya", Score: 100},
		{ID: "art-far", Name: "Anıl Chawla", Score: 71},
	}
	fakeMB(a).popularity = map[string]musicbrainz.Popularity{
		"art-small": {Listeners: 300}, "art-big": {Listeners: 12000}, "art-far": {Listeners: 999999},
	}
	tt := ui.NewTester(a.view, 1000, 900)
	tt.Frame()
	if err := tt.Click("Link to MusicBrainz"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Artist"); err != nil {
		t.Fatal(err)
	}
	pollUntil(t, a, "the artists", func() bool { tt.Frame(); return a.link != nil && len(a.link.candidates) == 3 })
	var order []string
	for _, c := range a.link.candidates {
		order = append(order, c.id)
	}
	want := []string{"art-big", "art-small", "art-far"}
	for i := range want {
		if order[i] != want[i] {
			t.Fatalf("artists ranked %v, want %v: fit first, then listeners", order, want)
		}
	}
	if err := tt.Click("Link"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if artist, ok := a.links.Artist(song.Artist); !ok || artist.State != mblinks.Matched || artist.MBID != "art-big" {
		t.Errorf("the artist was not linked: %+v %v", artist, ok)
	}
}

// What a playlist already held as linked before the store existed is taken
// into it, so nothing is lost.
func TestListLinksAreImportedIntoTheStore(t *testing.T) {
	a := newTestApp(t)
	lib, _ := a.libraries.Create("Old")
	if _, err := a.libraries.AddItem(lib.ID, library.Item{Source: media.SourceYTM, Key: "old-1", Title: "Old", Artist: "A", DurationMS: 100_000, State: library.Unknown}); err != nil {
		t.Fatal(err)
	}
	if _, err := a.libraries.SetLink(lib.ID, "old-1", library.Matched, "rec-old"); err != nil {
		t.Fatal(err)
	}
	a.importListLinks()
	if s, ok := a.links.Song("ytm", "old-1"); !ok || s.State != mblinks.Matched || s.MBID != "rec-old" {
		t.Errorf("a playlist's link was not imported: %+v %v", s, ok)
	}
}
