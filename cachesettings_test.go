package main

import (
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/musicbrainz"
)

// The two providers' caches read alike on the settings page: a card each, with
// a size in megabytes, how full it is and a way to clear it, and one card for
// the folder they share.
func TestCacheCardsReadAlike(t *testing.T) {
	a := newTestApp(t)
	a.router.Push("/settings")
	tt := ui.NewTester(a.view, 1000, 9000)
	tt.Frame()
	for _, want := range []string{
		"YouTube Music cache", "SoundCloud cache", "Cache folder",
		"Clear YouTube Music cache", "Clear SoundCloud cache",
	} {
		if !tt.HasText(want) {
			t.Errorf("Settings does not show %q: %q", want, tt.Texts())
		}
	}
	sizes, fullness := 0, 0
	for _, text := range tt.Texts() {
		switch text {
		case "Size limit":
			sizes++
		case "Fullness":
			fullness++
		}
	}
	if sizes != 2 || fullness != 2 {
		t.Errorf("the cache cards show %d size limits and %d fullness lines, want 2 and 2", sizes, fullness)
	}
	if tt.HasText("Playback cache") || tt.HasText("Keep recent songs") {
		t.Errorf("the old count-of-songs card is still shown: %q", tt.Texts())
	}
}

// A size of zero turns a cache off and a size above zero turns it on, for the
// YouTube Music cache and the SoundCloud one alike.
func TestACacheOfZeroMegabytesIsOff(t *testing.T) {
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	a.settings.CacheDirectory = t.TempDir()
	a.openAudioCache()

	a.setSoundCloudCacheMB(0)
	if a.currentSoundCloudCache() != nil {
		t.Error("a SoundCloud cache of 0 MB is still open")
	}
	a.setSoundCloudCacheMB(64)
	if a.currentSoundCloudCache() == nil {
		t.Error("a SoundCloud cache of 64 MB is not open")
	}

	cache := a.currentAudioCache()
	if cache == nil {
		t.Fatal("no YouTube Music cache is open")
	}
	a.setYouTubeCacheMB(100)
	if !cache.Warmable() {
		t.Error("a YouTube Music cache of 100 MB does not keep tracks")
	}
	a.setYouTubeCacheMB(0)
	if cache.Warmable() {
		t.Error("a YouTube Music cache of 0 MB keeps tracks")
	}
	if a.settings.YouTubeCacheMB != 0 || a.youtubeCacheMBText != "0" {
		t.Errorf("the size was not kept: %d, field %q", a.settings.YouTubeCacheMB, a.youtubeCacheMBText)
	}
}

// Both caches are kept in a folder of their own inside the one folder the
// settings name.
func TestCachesShareAParentFolder(t *testing.T) {
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	root := t.TempDir()
	a.settings.CacheDirectory = root
	a.openAudioCache()
	a.openSoundCloudCache()
	yt, sc := a.currentAudioCache(), a.currentSoundCloudCache()
	if yt == nil || sc == nil {
		t.Fatalf("a cache is missing: youtube %v, soundcloud %v", yt != nil, sc != nil)
	}
	for name, cache := range map[string]*audio.Cache{"YouTube Music": yt, "SoundCloud": sc} {
		if !strings.HasPrefix(cache.Dir(), root) {
			t.Errorf("the %s cache %q is not inside %q", name, cache.Dir(), root)
		}
	}
	if yt.Dir() == sc.Dir() {
		t.Errorf("the caches share one folder, %q", yt.Dir())
	}
	if bytes, tracks := sc.Usage(); bytes != 0 || tracks != 0 {
		t.Errorf("an empty cache reports %d bytes and %d tracks", bytes, tracks)
	}
}

// The MusicBrainz cache card says how many answers are kept and how much room
// they take, lists the newest with what was asked and when it is asked again,
// and forgets one or all.
func TestMetadataCacheCardListsAndForgets(t *testing.T) {
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	now := time.Now()
	fakeMB(a).cache = []musicbrainz.CacheEntry{
		{ID: "1.json", Kind: "Artist search", Query: "hidra", Results: 2, StoredAt: now.Add(-3*24*time.Hour - time.Hour), Expires: now.Add(27*24*time.Hour + time.Hour), Bytes: 2048},
		{ID: "2.json", Kind: "Song search", Query: "Dümen", Results: 5, StoredAt: now.Add(-2*time.Hour - time.Minute), Expires: now.Add(22*time.Hour + 30*time.Minute), Bytes: 1024},
	}
	a.router.Push("/settings")
	tt := ui.NewTester(a.view, 1000, 9000)
	pollUntil(t, a, "the cache to be read", func() bool { tt.Frame(); return a.mbEntriesLoaded })
	tt.Frame()
	for _, want := range []string{
		"MusicBrainz cache", "2 answers", "Artist search: hidra", "Song search: Dümen",
		"2 results · kept 3 days ago · asked again in 27 days",
		"5 results · kept 2 h ago · asked again in 22 h",
	} {
		if !tt.HasText(want) {
			t.Errorf("the card does not show %q: %q", want, tt.Texts())
		}
	}

	if err := tt.Click("Forget Artist search: hidra"); err != nil {
		t.Fatal(err)
	}
	pollUntil(t, a, "one answer to be forgotten", func() bool { tt.Frame(); return len(a.mbEntries) == 1 })
	if left := fakeMB(a).CacheEntries(); len(left) != 1 || left[0].ID != "2.json" {
		t.Fatalf("after forgetting one: %+v", left)
	}
	if err := tt.Click("Clear MusicBrainz cache"); err != nil {
		t.Fatal(err)
	}
	pollUntil(t, a, "the cache to be cleared", func() bool { tt.Frame(); return len(a.mbEntries) == 0 })
	tt.Frame()
	if !tt.HasText("0 answers") {
		t.Errorf("an empty cache is not said to be empty: %q", tt.Texts())
	}
}
