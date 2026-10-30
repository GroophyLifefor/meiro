package main

import (
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/musicbrainz"
	"github.com/elianiva/meiro/youtube"
)

// The artists a search matched are the first row of the list, so they scroll
// away with the results rather than keeping a share of the page.
func TestSearchArtistsScrollWithTheResults(t *testing.T) {
	a := newTestApp(t)
	fakeMB(a).artists = []musicbrainz.Artist{
		{ID: "a1", Name: "Nova Artist", Score: 100},
		{ID: "a2", Name: "Second Artist", Score: 90},
		{ID: "a3", Name: "Third Artist", Score: 80},
		{ID: "a4", Name: "Fourth Artist", Score: 70},
	}
	a.router.Push("/search")
	// Short enough that the results do not fit and the list must scroll.
	tt := ui.NewTester(a.view, 1000, 420)
	tt.Frame()
	if err := tt.Click(searchPlaceholder); err != nil {
		t.Fatal(err)
	}
	tt.Type("nova")
	tt.Key(0, ui.KeyEnter)
	for range 5 {
		a.poll()
		tt.Frame()
	}
	if len(a.page.rows) == 0 || a.page.rows[0].kind != rowArtists {
		t.Fatalf("the artists are not the first row of the list: %+v", a.page.rows)
	}
	if !tt.HasText("Nova Artist") {
		t.Errorf("the artists are missing: %q", tt.Texts())
	}
	// Once the page is scrolled past the artists, they are gone from the
	// screen, which a part of the page that held its place would not allow.
	for range 10 {
		tt.Scroll(500, 400, 0, 400)
		tt.Frame()
	}
	if tt.HasText("Nova Artist") {
		t.Errorf("the artists stayed on screen after scrolling: %q", tt.Texts())
	}
}

// Pressing anywhere on the search bar, not only on its text, focuses the field.
func TestPressingTheSearchBarFocusesItsField(t *testing.T) {
	a := newTestApp(t)
	a.router.Push("/home")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	text, ok := tt.Find(searchPlaceholder)
	if !ok {
		t.Fatalf("no search bar: %q", tt.Texts())
	}
	// The search icon, left of the text, belongs to the bar and not to the
	// field.
	tt.ClickAt(text.X-30, text.Y+text.H/2)
	tt.Type("yor")
	tt.Frame()
	if a.search.query != "yor" {
		t.Errorf("typing after pressing the bar's icon wrote %q, want %q", a.search.query, "yor")
	}
}

// Library is the YouTube Music account's, so it is offered only to a signed-in
// user; the app's own playlists and downloads are always offered.
func TestLibraryIsOfferedOnlyWithYouTubeSignedIn(t *testing.T) {
	out := newTestApp(t)
	out.router.Push("/home")
	tt := ui.NewTester(out.view, 1000, 700)
	tt.Frame()
	if _, ok := tt.Find("Library"); ok {
		t.Errorf("a signed-out app offers Library: %q", tt.Texts())
	}
	for _, want := range []string{"Playlists", "Downloads"} {
		if !tt.HasText(want) {
			t.Errorf("a signed-out app does not offer %q: %q", want, tt.Texts())
		}
	}
	resetFakes()
	in := signedInApp(t)
	in.router.Push("/home")
	ti := ui.NewTester(in.view, 1000, 700)
	ti.Frame()
	if _, ok := ti.Find("Library"); !ok {
		t.Errorf("a signed-in app does not offer Library: %q", ti.Texts())
	}
}

// A search narrowed to songs has no artists above it, and one narrowed to
// artists, or not narrowed, has.
func TestArtistsAreShownOnlyWhenTheFilterCallsForThem(t *testing.T) {
	a := newTestApp(t)
	fakeMB(a).artists = []musicbrainz.Artist{{ID: "a1", Name: "Nova Artist", Score: 100}}
	a.router.Push("/search")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	if err := tt.Click(searchPlaceholder); err != nil {
		t.Fatal(err)
	}
	tt.Type("nova")
	tt.Key(0, ui.KeyEnter)
	for range 5 {
		a.poll()
		tt.Frame()
	}
	if !tt.HasText("Nova Artist") {
		t.Fatalf("an unfiltered search shows no artists: %q", tt.Texts())
	}
	paramsOf := map[string]string{}
	for _, filter := range a.search.filters {
		paramsOf[filter.Label] = filter.Params
	}
	for _, tc := range []struct {
		chip string
		want bool
	}{{"Songs", false}, {"Albums", false}} {
		params, ok := paramsOf[tc.chip]
		if !ok {
			t.Fatalf("the search offers no %q filter: %v", tc.chip, paramsOf)
		}
		a.runSearch("nova", "")
		for range 5 {
			a.poll()
			tt.Frame()
		}
		a.runSearch("nova", params)
		for range 5 {
			a.poll()
			tt.Frame()
		}
		if got := tt.HasText("Nova Artist"); got != tc.want {
			t.Errorf("with the %s filter the artists show = %v, want %v", tc.chip, got, tc.want)
		}
	}
}

// A filter named Artists calls for the MusicBrainz artists, and no filter does.
func TestArtistsWantedByTheArtistsFilter(t *testing.T) {
	a := newTestApp(t)
	a.search.filters = []youtube.SearchFilter{{Label: "Songs", Params: "s"}, {Label: "Artists", Params: "a"}}
	for params, want := range map[string]bool{"": true, "s": false, "a": true, "unknown": false} {
		a.search.params = params
		if got := a.artistsWanted(); got != want {
			t.Errorf("artistsWanted with params %q = %v, want %v", params, got, want)
		}
	}
}
