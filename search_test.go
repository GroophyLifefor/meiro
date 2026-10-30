package main

import (
	"testing"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// A song both providers hold becomes one row carrying both sources, while a
// song they disagree about, or only one of them has, keeps a row of its own.
func TestMergeProvidersMergesSameRecordingAndKeepsOthers(t *testing.T) {
	ytm := []youtube.MusicItem{
		{VideoID: "a", Title: "Cloud Song", Subtitle: "Cloud Artist • 3:00", Duration: "3:00"},
		{VideoID: "b", Title: "Similar Song", Subtitle: "Someone • 2:00", Duration: "2:00"},
	}
	sc := []youtube.MusicItem{
		{Source: media.SourceSoundCloud, Kind: media.KindTrack, URL: "u1", Title: "Cloud Song", Artist: "Cloud Artist", Subtitle: "Cloud Artist", Duration: "3:00"},
		{Source: media.SourceSoundCloud, Kind: media.KindTrack, URL: "u2", Title: "Similar Song", Artist: "Someone Else", Subtitle: "Someone Else", Duration: "2:00"},
		{Source: media.SourceSoundCloud, Kind: media.KindTrack, URL: "u3", Title: "Only Cloud", Artist: "Cloud Artist", Subtitle: "Cloud Artist", Duration: "3:10"},
	}
	got := mergeProviders(ytm, sc)
	if len(got) != 4 {
		t.Fatalf("merged rows = %d, want 4: %+v", len(got), got)
	}
	if got[0].Title != "Cloud Song" || len(got[0].Sources) != 2 {
		t.Fatalf("the shared song = %+v", got[0])
	}
	if !got[0].HasSource(media.SourceYTM) || !got[0].HasSource(media.SourceSoundCloud) {
		t.Fatalf("the shared song does not carry both sources: %+v", got[0].Sources)
	}
	// A song whose artist disagrees is not merged: the YouTube copy stays and
	// the SoundCloud copy is appended as its own row.
	if got[1].VideoID != "b" || got[2].URL != "u2" {
		t.Fatalf("the uncertain song was merged: %+v", got)
	}
	if got[3].URL != "u3" {
		t.Fatalf("the SoundCloud-only song = %+v", got[3])
	}
}

// The first provider to answer is shown while the other is still out, and the
// rows merge when it arrives.
func TestApplySearchResultsShowsOneProviderThenMerges(t *testing.T) {
	a := newTestApp(t)
	s := &a.search
	s.submitted, s.loading = "q", true
	s.ytm = providerSearch{done: true, items: []youtube.MusicItem{
		{VideoID: "a", Title: "Cloud Song", Subtitle: "Cloud Artist • 3:00", Duration: "3:00"},
	}}
	a.applySearchResults()
	if s.loading || !s.partial {
		t.Fatalf("after YouTube answered: loading=%v partial=%v", s.loading, s.partial)
	}
	if len(s.items) != 1 || len(s.items[0].Sources) != 0 {
		t.Fatalf("the first results = %+v", s.items)
	}
	s.sc = providerSearch{done: true, items: []youtube.MusicItem{
		{Source: media.SourceSoundCloud, Kind: media.KindTrack, URL: "u1", Title: "Cloud Song", Artist: "Cloud Artist", Subtitle: "Cloud Artist", Duration: "3:00"},
	}}
	a.applySearchResults()
	if s.partial {
		t.Fatalf("the page still says it is loading once both answered")
	}
	if len(s.items) != 1 || len(s.items[0].Sources) != 2 {
		t.Fatalf("the merged results = %+v", s.items)
	}
}

// A search that falls back to the device reads the downloads and the audio
// cache, filters them by the query, and says where they came from.
func TestLocalSearchFindsDownloadsAndCache(t *testing.T) {
	a := newTestApp(t)
	if err := a.downloads.Add(downloads.Item{
		Source: media.SourceYTM, Key: "vid-dl", Title: "Downloaded Tune", Artist: "Someone", File: "dl.m4a",
	}); err != nil {
		t.Fatal(err)
	}
	cache, _, err := audio.OpenLimits(t.TempDir(), audio.Limits{Count: 4})
	if err != nil {
		t.Fatal(err)
	}
	a.replaceAudioCache(cache)
	t.Cleanup(a.closeAudioCaches)
	kept := youtube.MusicItem{VideoID: "vid-cache", Title: "Cached Tune", Artist: "Other", Kind: media.KindTrack, Duration: "2:00"}
	cache.Note(audioKey(kept), cacheMeta(kept))

	a.localSearch("tune")
	if a.search.note != "From this device" {
		t.Fatalf("the local note = %q", a.search.note)
	}
	if len(a.search.items) != 2 {
		t.Fatalf("local items = %d, want 2: %+v", len(a.search.items), a.search.items)
	}
	a.localSearch("cached")
	if len(a.search.items) != 1 || a.search.items[0].Title != "Cached Tune" {
		t.Fatalf("the filtered local items = %+v", a.search.items)
	}
}

// A song only SoundCloud has is put among the YouTube Music rows, one after
// every three, and the rest after the last, so none waits below a whole page.
func TestSoundCloudOnlySongsAreSpreadAmongTheRows(t *testing.T) {
	var ytm, sc []youtube.MusicItem
	for _, id := range []string{"a", "b", "c", "d", "e", "f", "g"} {
		ytm = append(ytm, youtube.MusicItem{VideoID: id, Title: "Y " + id, Subtitle: "Y • 3:00", Duration: "3:00", Kind: media.KindTrack})
	}
	for _, id := range []string{"1", "2", "3", "4"} {
		sc = append(sc, youtube.MusicItem{Source: media.SourceSoundCloud, Kind: media.KindTrack, URL: "u" + id, Title: "S " + id, Artist: "Other", Subtitle: "Other", Duration: "2:00"})
	}
	got := mergeProviders(ytm, sc)
	var order []string
	for _, item := range got {
		if item.Source == media.SourceSoundCloud {
			order = append(order, item.URL)
		} else {
			order = append(order, item.VideoID)
		}
	}
	want := []string{"a", "b", "c", "u1", "d", "e", "f", "u2", "g", "u3", "u4"}
	if len(order) != len(want) {
		t.Fatalf("order = %v, want %v", order, want)
	}
	for i := range want {
		if order[i] != want[i] {
			t.Fatalf("order = %v, want %v", order, want)
		}
	}
}

// SoundCloud is asked for a search with no filter, or one to songs or videos,
// and not for one to albums, playlists or artists.
func TestSoundCloudIsAskedOnlyForTracks(t *testing.T) {
	a := newTestApp(t)
	a.search.filters = []youtube.SearchFilter{{Label: "Songs", Params: "s"}, {Label: "Albums", Params: "al"}, {Label: "Videos", Params: "v"}}
	for params, want := range map[string]bool{"": true, "s": true, "v": true, "al": false, "other": false} {
		if got := a.soundCloudWanted(params); got != want {
			t.Errorf("soundCloudWanted(%q) = %v, want %v", params, got, want)
		}
	}
}
