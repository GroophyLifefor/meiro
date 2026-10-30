package main

import (
	"errors"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/musicbrainz"
)

// errMetadataDown is what a metadata lookup answers with when the service is
// out of reach.
var errMetadataDown = errors.New("musicbrainz is unreachable")

// TestSearchShowsArtistsSection proves a search shows the MusicBrainz artists
// it matched, most listened to first, above the provider's own results.
func TestSearchShowsArtistsSection(t *testing.T) {
	resetFakes()
	a := newTestApp(t)
	fm := fakeMB(a)
	fm.artists = []musicbrainz.Artist{
		{ID: "a2", Name: "Second Artist", Country: "US"},
		{ID: "a1", Name: "Nova Artist", Country: "JP", Disambiguation: "the band"},
	}
	fm.popularity = map[string]musicbrainz.Popularity{
		"a1": {MBID: "a1", Listeners: 5000},
		"a2": {MBID: "a2", Listeners: 10},
	}
	a.router.Push("/search")
	tt := ui.NewTester(a.view, 1000, 700)

	if err := tt.Click(searchPlaceholder); err != nil {
		t.Fatal(err)
	}
	tt.Type("nova")
	tt.Key(0, ui.KeyEnter)
	tt.Frame()

	if !tt.HasText("Artists") {
		t.Fatalf("the artist section is missing: %q", tt.Texts())
	}
	if !tt.HasText("Nova Artist") || !tt.HasText("Second Artist") {
		t.Fatalf("the artists are missing: %q", tt.Texts())
	}
	if len(a.artists.artists) != 2 || a.artists.artists[0].Name != "Nova Artist" {
		t.Fatalf("artists are not most listened to first: %+v", a.artists.artists)
	}
	if a.artists.artists[0].Listeners != 5000 {
		t.Fatalf("the popularity was not applied: %+v", a.artists.artists[0])
	}
	// The provider's own results are still there beside the artists.
	if !tt.HasText("Search Result Song") {
		t.Fatalf("the provider results are missing: %q", tt.Texts())
	}
}

// TestArtistPageShowsRecordings proves opening an artist shows its top
// recordings, that a recording with a provider link plays, and one without is
// left unplayable.
func TestArtistPageShowsRecordings(t *testing.T) {
	resetFakes()
	a := newTestApp(t)
	fm := fakeMB(a)
	fm.artists = []musicbrainz.Artist{{ID: "a1", Name: "Nova Artist"}}
	fm.recordings = map[string][]musicbrainz.Recording{
		"a1": {
			{ID: "r1", Title: "Playable Song", Artist: "Nova Artist", LengthMS: 242000},
			{ID: "r2", Title: "Unlinked Song", Artist: "Nova Artist", LengthMS: 200000},
		},
	}
	fm.links = map[string][]musicbrainz.Link{
		"r1": {{Type: "free streaming", URL: "https://music.youtube.com/watch?v=abc"}},
	}
	a.router.Push("/search")
	tt := ui.NewTester(a.view, 1000, 700)

	if err := tt.Click(searchPlaceholder); err != nil {
		t.Fatal(err)
	}
	tt.Type("nova")
	tt.Key(0, ui.KeyEnter)
	tt.Frame()

	if err := tt.Click("Nova Artist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()

	if a.path() != "/mb/artist/a1" {
		t.Fatalf("the artist page is %q", a.path())
	}
	if !tt.HasText("Playable Song") || !tt.HasText("Unlinked Song") {
		t.Fatalf("the recordings are missing: %q", tt.Texts())
	}
	if !tt.HasText("YouTube Music") {
		t.Fatalf("the provider badge is missing: %q", tt.Texts())
	}
	if !tt.HasText("No link") {
		t.Fatalf("the unlinked recording is not marked: %q", tt.Texts())
	}
	if !a.mbArtist.recordings[0].playable || a.mbArtist.recordings[1].playable {
		t.Fatalf("the recordings are not marked the way their links are: %+v", a.mbArtist.recordings)
	}

	if err := tt.Click("Playable Song"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.current.Key() != "abc" {
		t.Fatalf("playing the linked recording started %q, want abc", a.current.Key())
	}
}

// TestArtistPageSurvivesAMusicBrainzFailure proves a metadata outage does not
// block the provider search, and only the artist section says so.
func TestArtistPageSurvivesAMusicBrainzFailure(t *testing.T) {
	resetFakes()
	a := newTestApp(t)
	fakeMB(a).err = errMetadataDown
	a.router.Push("/search")
	tt := ui.NewTester(a.view, 1000, 700)

	if err := tt.Click(searchPlaceholder); err != nil {
		t.Fatal(err)
	}
	tt.Type("nova")
	tt.Key(0, ui.KeyEnter)
	tt.Frame()

	if !tt.HasText("Search Result Song") {
		t.Fatalf("the provider search was blocked by the metadata failure: %q", tt.Texts())
	}
	if !tt.HasText("Artist data is unavailable right now.") {
		t.Fatalf("the artist section did not say it was unavailable: %q", tt.Texts())
	}
}
