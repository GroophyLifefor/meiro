package soundcloud

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/elianiva/meiro/media"
)

const searchFixture = `{
  "extractor": "soundcloud:search",
  "title": "daft punk",
  "entries": [
    {"id": "88335161", "title": "Get Lucky [Original Version] - Daft Punk", "uploader": "DJ KB",
     "url": "https://api.soundcloud.com/tracks/soundcloud%3Atracks%3A88335161",
     "webpage_url": "https://soundcloud.com/eldjkb/get-lucky-original-version",
     "duration": 246.381, "view_count": 21507215},
    {"id": "254112221", "title": "Around the World", "uploader": "Daft Punk",
     "webpage_url": "https://soundcloud.com/daftpunkofficialmusic/around-the-world",
     "duration": 30.0, "view_count": 2195940}
  ]
}`

const setFixture = `{
  "extractor": "soundcloud:set",
  "id": "2284613",
  "title": "The Royal Concept EP",
  "entries": [
    {"id": "75206121", "url": "https://soundcloud.com/the-concept-band/world-on-fire-1"},
    {"id": "47127625", "url": "https://soundcloud.com/the-concept-band/gimme-twice-mastered"}
  ]
}`

const userFixture = `{
  "extractor": "soundcloud:user",
  "id": "203812859",
  "title": "Daft Punk (All)",
  "entries": [
    {"id": "254111859", "title": "Human After All (Medley)", "url": "https://soundcloud.com/daftpunkofficialmusic/human-after-all-medley"}
  ]
}`

const relatedFixture = `{
  "extractor": "soundcloud:related",
  "id": "88335161",
  "title": "Get Lucky [Original Version] - Daft Punk (Recommended)",
  "entries": [
    {"id": "127686675", "title": "MILKY CHANCE - Stolen Dance", "url": "https://soundcloud.com/pias-france/milky-chance-stolen-dance"},
    {"id": "7649914", "title": "Manu Chao - Me Gustas Tu", "url": "https://soundcloud.com/because/manu-chao-me-gustas-tu"}
  ]
}`

// fixtureRunner answers the target a call names and records it.
type fixtureRunner struct {
	targets []string
	answers map[string]string
	err     error
}

func (r *fixtureRunner) run(_ context.Context, args ...string) ([]byte, error) {
	target := args[len(args)-1]
	r.targets = append(r.targets, target)
	if r.err != nil {
		return nil, r.err
	}
	return []byte(r.answers[target]), nil
}

func newTestClient(t *testing.T, answers map[string]string) (*Client, *fixtureRunner) {
	t.Helper()
	runner := &fixtureRunner{answers: answers}
	return New(Options{Runner: runner.run}), runner
}

func TestSearchReadsTracks(t *testing.T) {
	client, runner := newTestClient(t, map[string]string{"scsearch50:daft punk": searchFixture})
	result, err := client.Search(context.Background(), "daft punk", media.SearchOptions{})
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if len(result.Items) != 2 {
		t.Fatalf("items = %d, want 2", len(result.Items))
	}
	first := result.Items[0]
	want := media.Item{
		Source:   media.SourceSoundCloud,
		ID:       "88335161",
		URL:      "https://soundcloud.com/eldjkb/get-lucky-original-version",
		Title:    "Get Lucky [Original Version] - Daft Punk",
		Subtitle: "DJ KB",
		Artist:   "DJ KB",
		Artists:  "DJ KB",
		Kind:     media.KindTrack,
		Duration: "4:06",
	}
	if !reflect.DeepEqual(first, want) {
		t.Fatalf("first item = %+v, want %+v", first, want)
	}
	if preview := result.Items[1]; preview.Duration != "0:30" {
		t.Fatalf("preview duration = %q, want 0:30", preview.Duration)
	}
	if target := runner.targets[0]; target != "scsearch50:daft punk" {
		t.Fatalf("search target = %q", target)
	}
}

func TestSearchCommandFlags(t *testing.T) {
	var got []string
	client := New(Options{Runner: func(_ context.Context, args ...string) ([]byte, error) {
		got = args
		return []byte(searchFixture), nil
	}})
	if _, err := client.Search(context.Background(), "daft punk", media.SearchOptions{}); err != nil {
		t.Fatalf("Search: %v", err)
	}
	want := []string{"--flat-playlist", "--dump-single-json", "--no-warnings", "scsearch50:daft punk"}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Fatalf("args = %q, want %q", got, want)
	}
}

func TestGetSetReadsTracks(t *testing.T) {
	const page = "https://soundcloud.com/the-concept-band/sets/the-royal-concept-ep"
	client, _ := newTestClient(t, map[string]string{page: setFixture})
	result, err := client.GetSet(context.Background(), page)
	if err != nil {
		t.Fatalf("GetSet: %v", err)
	}
	if result.Title != "The Royal Concept EP" {
		t.Fatalf("title = %q", result.Title)
	}
	if len(result.Items) != 2 {
		t.Fatalf("items = %d, want 2", len(result.Items))
	}
	// A set gives no title, uploader or length: the page URL is all there is.
	first := result.Items[0]
	if first.URL != "https://soundcloud.com/the-concept-band/world-on-fire-1" {
		t.Fatalf("url = %q", first.URL)
	}
	if first.Source != media.SourceSoundCloud || first.Kind != media.KindTrack {
		t.Fatalf("item = %+v", first)
	}
	if first.Title != "" || first.Duration != "" {
		t.Fatalf("expected empty title and duration, got %+v", first)
	}
}

func TestGetUserReadsTracks(t *testing.T) {
	const page = "https://soundcloud.com/daftpunkofficialmusic"
	client, _ := newTestClient(t, map[string]string{page: userFixture})
	result, err := client.GetUser(context.Background(), page)
	if err != nil {
		t.Fatalf("GetUser: %v", err)
	}
	if result.Title != "Daft Punk (All)" || len(result.Items) != 1 {
		t.Fatalf("result = %+v", result)
	}
	if result.Items[0].Duration != "" {
		t.Fatalf("duration = %q, want empty", result.Items[0].Duration)
	}
}

func TestGetRelatedAppendsRecommended(t *testing.T) {
	page := "https://soundcloud.com/eldjkb/get-lucky-original-version"
	target := relatedURL(page)
	client, _ := newTestClient(t, map[string]string{target: relatedFixture})
	result, err := client.GetRelated(context.Background(), page)
	if err != nil {
		t.Fatalf("GetRelated: %v", err)
	}
	if target != page+"/recommended" {
		t.Fatalf("related target = %q", target)
	}
	if len(result.Items) != 2 {
		t.Fatalf("items = %d, want 2", len(result.Items))
	}
}

func TestGetStationBuildsTrackStationURL(t *testing.T) {
	page := "https://soundcloud.com/eldjkb/get-lucky-original-version"
	station := "https://soundcloud.com/stations/track/eldjkb/get-lucky-original-version"
	client, runner := newTestClient(t, map[string]string{station: relatedFixture})
	if _, err := client.GetStation(context.Background(), page); err != nil {
		t.Fatalf("GetStation: %v", err)
	}
	if runner.targets[0] != station {
		t.Fatalf("station target = %q, want %q", runner.targets[0], station)
	}
}

func TestEntryWithNoIdentityIsSkipped(t *testing.T) {
	client := New(Options{Runner: func(_ context.Context, args ...string) ([]byte, error) {
		return []byte(`{"entries": [{"title": "no id"}, {"id": "5", "url": "https://soundcloud.com/a/b"}]}`), nil
	}})
	result, err := client.Search(context.Background(), "x", media.SearchOptions{})
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if len(result.Items) != 1 || result.Items[0].ID != "5" {
		t.Fatalf("items = %+v", result.Items)
	}
}

func TestReadKeepsTheMissingToolError(t *testing.T) {
	client, _ := newTestClient(t, map[string]string{})
	client.run = func(_ context.Context, args ...string) ([]byte, error) { return nil, ErrMissing }
	if _, err := client.Search(context.Background(), "x", media.SearchOptions{}); !errors.Is(err, ErrMissing) {
		t.Fatalf("err = %v, want ErrMissing", err)
	}
}

func TestUnsupportedCallsReturnAnError(t *testing.T) {
	client := New(Options{})
	ctx := context.Background()
	calls := map[string]func() error{
		"ContinueSearch":       func() error { _, err := client.ContinueSearch(ctx, "t"); return err },
		"GetSearchSuggestions": func() error { _, err := client.GetSearchSuggestions(ctx, "x"); return err },
		"GetHomeFeed":          func() error { _, err := client.GetHomeFeed(ctx); return err },
		"GetExplore":           func() error { _, err := client.GetExplore(ctx); return err },
		"GetLibrary":           func() error { _, err := client.GetLibrary(ctx); return err },
		"StreamLibrary":        func() error { _, err := client.StreamLibrary(ctx, nil); return err },
		"ContinueBrowse":       func() error { _, err := client.ContinueBrowse(ctx, "t"); return err },
		"GetArtist":            func() error { _, err := client.GetArtist(ctx, "a"); return err },
		"GetAlbum":             func() error { _, err := client.GetAlbum(ctx, "a"); return err },
		"GetPlaylist":          func() error { _, err := client.GetPlaylist(ctx, "a"); return err },
		"RateTrack":            func() error { return client.RateTrack(ctx, "v", true) },
		"AddToLibrary":         func() error { return client.AddToLibrary(ctx, "p") },
		"RemoveFromLibrary":    func() error { return client.RemoveFromLibrary(ctx, "p") },
	}
	for name, call := range calls {
		t.Run(name, func(t *testing.T) {
			if err := call(); !errors.Is(err, ErrUnsupported) {
				t.Fatalf("err = %v, want ErrUnsupported", err)
			}
		})
	}
}

func TestStationURLRefusesAPageWithNoPath(t *testing.T) {
	if _, err := stationURL("https://soundcloud.com"); err == nil {
		t.Fatal("expected an error for a page with no path")
	}
}
