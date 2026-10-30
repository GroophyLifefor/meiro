package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/internal/mblinks"
	meirosync "github.com/elianiva/meiro/internal/sync"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// The Meiro account card offers the server address and the sign in form before
// an account is signed in, and is the first card of Settings, ahead of the
// YouTube Music account.
func TestMeiroAccountCardOffersServerAndSignIn(t *testing.T) {
	a := newTestApp(t)
	a.router.Push("/settings")
	tt := ui.NewTester(a.view, 1000, 8000)
	tt.Frame()
	for _, want := range []string{"Meiro account", "Server address", "Sign in to sync", "YouTube Music account", "optional"} {
		if !tt.HasText(want) {
			t.Errorf("the Sync card does not show %q: %q", want, tt.Texts())
		}
	}
	// The default server is Meiro's own; the address stays editable.
	if a.settings.SyncURL != "https://meiro.muratkirazkaya.com" {
		t.Errorf("default server = %q", a.settings.SyncURL)
	}
}

// A play that meets the counting rule is counted by the stats and filed for
// syncing; one that does not is recorded but not counted.
func TestAPlayIsCountedAndQueuedByTheRule(t *testing.T) {
	a := newTestApp(t)
	a.settings.AutoPlay = false

	// A short play: recorded, but under the rule.
	a.beginPlayRecord(youtube.MusicItem{VideoID: "short", Title: "Short", Artist: "A"})
	a.observeRecord(time.Now(), 0)
	a.observeRecord(time.Now().Add(5*time.Second), 5*time.Second)
	a.finishPlayRecord(false)
	if a.sync.outbox.EventCount() != 1 {
		t.Fatalf("the short play was not queued: %d", a.sync.outbox.EventCount())
	}
	if got := a.statsSummary(time.Now(), 14).Plays; got != 0 {
		t.Errorf("a short play was counted: %d", got)
	}

	// A play heard past thirty seconds qualifies.
	a.beginPlayRecord(youtube.MusicItem{VideoID: "long", Title: "Long", Artist: "B", Duration: "3:00"})
	base := time.Now()
	for i := range 31 {
		a.observeRecord(base.Add(time.Duration(i)*time.Second), time.Duration(i)*time.Second)
	}
	a.finishPlayRecord(false)
	if a.sync.outbox.EventCount() != 2 {
		t.Fatalf("the qualifying play was not queued: %d", a.sync.outbox.EventCount())
	}
	if got := a.statsSummary(time.Now(), 14).Plays; got != 1 {
		t.Errorf("plays = %d, want the one that qualified", got)
	}
}

// A playlist's track and a download map to the server's track reference by
// their provider, and the library is given a stable server id.
func TestSyncSnapshotMapsTheLocalStores(t *testing.T) {
	a := newTestApp(t)
	lib, err := a.libraries.Create("Road trip")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.libraries.AddItem(lib.ID, library.Item{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y", URL: "https://soundcloud.com/x/y", Title: "Cloud Song", Artist: "C", DurationMS: 200000}); err != nil {
		t.Fatal(err)
	}
	snap := a.syncSnapshot()
	if len(snap.libraries) != 1 || snap.libraries[0].Kind != "custom" || snap.libraries[0].Name != "Road trip" {
		t.Fatalf("libraries = %+v", snap.libraries)
	}
	if snap.libraries[0].ID != meirosync.LibraryID(lib.ID) {
		t.Errorf("library server id = %q", snap.libraries[0].ID)
	}
	if len(snap.items) != 1 || len(snap.items[0].Items) != 1 {
		t.Fatalf("items = %+v", snap.items)
	}
	track := snap.items[0].Items[0].Track
	if track.Provider != "soundcloud" || track.ProviderTrackID != "https://soundcloud.com/x/y" {
		t.Errorf("track = %+v", track)
	}
}

// A song two providers hold is sent to the server on the source it plays from
// now, with that provider's own key, not the first source that holds it.
func TestSyncSnapshotSendsTheActiveSource(t *testing.T) {
	a := newTestApp(t)
	ytm := media.SourceRef{Source: media.SourceYTM, ID: "vid-cloud"}
	cloud := media.SourceRef{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/x/cloud"}

	// A list track kept on YouTube Music but switched to SoundCloud.
	lib, err := a.libraries.Create("Mixed")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.libraries.AddItem(lib.ID, library.Item{
		Source: media.SourceYTM, Key: "vid-cloud", Title: "Cloud Song", Artist: "C",
		Sources: []media.SourceRef{ytm, cloud},
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := a.libraries.SetSource(lib.ID, "vid-cloud", media.SourceSoundCloud); err != nil {
		t.Fatal(err)
	}

	// A download kept from YouTube Music but played from SoundCloud.
	if err := a.downloads.Add(downloads.Item{
		Source: media.SourceYTM, Key: "vid-cloud", Title: "Cloud Song", Artist: "C",
		Sources: []media.SourceRef{ytm, cloud}, Active: media.SourceSoundCloud,
	}); err != nil {
		t.Fatal(err)
	}

	snap := a.syncSnapshot()
	if got := snap.items[0].Items[0].Track; got.Provider != "soundcloud" || got.ProviderTrackID != "https://soundcloud.com/x/cloud" {
		t.Errorf("library track = %+v, want the SoundCloud page URL", got)
	}
	if got := snap.downloads[0].Track; got.Provider != "soundcloud" || got.ProviderTrackID != "https://soundcloud.com/x/cloud" {
		t.Errorf("download track = %+v, want the SoundCloud page URL", got)
	}
}

// A play of a song two providers hold is filed under the source it played
// from, with that provider's own key, so the sync sends the right one.
func TestRecordedPlayUsesTheActiveSource(t *testing.T) {
	a := newTestApp(t)
	a.recordHistory(youtube.MusicItem{
		VideoID: "vid-cloud", URL: "https://soundcloud.com/x/cloud",
		Source: media.SourceSoundCloud, Title: "Cloud Song", Artist: "C",
		Sources: []media.SourceRef{
			{Source: media.SourceYTM, ID: "vid-cloud"},
			{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/x/cloud"},
		},
	})
	got := a.history[len(a.history)-1]
	if got.Source != "soundcloud" || got.VideoID != "https://soundcloud.com/x/cloud" {
		t.Fatalf("history entry = %+v, want the SoundCloud source and page URL", got)
	}
}

// signedInToServer is an app that is signed in to a test server and has its
// session, so what it sends and reads goes there.
func signedInToServer(t *testing.T, handler http.Handler) *app {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return signedInTo(t, server.URL)
}

// signedInTo is an app signed in to the server at url, for a test that has
// more than one device on one server.
func signedInTo(t *testing.T, url string) *app {
	t.Helper()
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	a.settings.SyncURL, a.settings.SyncEmail, a.settings.SyncEnabled = url, "me@example.com", true
	a.sync.token = "TOKEN"
	return a
}

// A link changed on this device is queued for the server and sent at once, and
// a link the server holds that this device lacks is taken in, the later of two
// records winning, and shown on the rows.
func TestLinksAreSentAndReadBackFromTheServer(t *testing.T) {
	var mu sync.Mutex
	var pushed []meirosync.Links
	later := time.Now().Add(time.Hour).UTC()
	remote := meirosync.Links{
		Songs: []meirosync.SongLink{
			{Provider: "ytm", ProviderTrackID: "from-other-device", State: "matched", MBID: "rec-remote", UpdatedAt: later},
			{Provider: "ytm", ProviderTrackID: "mine", State: "other", UpdatedAt: time.Unix(1, 0).UTC()}, // older than mine: must not win
		},
		Artists: []meirosync.ArtistLink{{ArtistKey: "remote artist", Name: "Remote Artist", State: "matched", MBID: "art-remote", UpdatedAt: later}},
	}
	a := signedInToServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/api/v1/links"):
			_ = json.NewEncoder(w).Encode(remote)
		case strings.HasSuffix(r.URL.Path, "/sync/links"):
			var links meirosync.Links
			_ = json.NewDecoder(r.Body).Decode(&links)
			mu.Lock()
			pushed = append(pushed, links)
			mu.Unlock()
			_, _ = w.Write([]byte(`{"accepted":1}`))
		default:
			_, _ = w.Write([]byte(`{"accepted":[],"duplicates":[]}`))
		}
	}))

	// A link made here goes to the server.
	mine := youtube.MusicItem{VideoID: "mine", Title: "Mine", Artist: "Me", Subtitle: "Me", Kind: media.KindTrack, Duration: "3:00"}
	a.recordSong(mine, mblinks.Matched, "rec-mine", nil)
	pollUntil(t, a, "the link to be sent", func() bool {
		mu.Lock()
		defer mu.Unlock()
		return len(pushed) > 0
	})
	mu.Lock()
	first := pushed[0]
	mu.Unlock()
	if len(first.Songs) != 1 || first.Songs[0].ProviderTrackID != "mine" || first.Songs[0].MBID != "rec-mine" || first.Songs[0].CheckedAt == nil {
		t.Errorf("what was sent = %+v", first)
	}

	// What the server holds is read, the later record of each winning.
	a.pullLinks()
	pollUntil(t, a, "the links to be read", func() bool {
		_, ok := a.links.Song("ytm", "from-other-device")
		return ok
	})
	if s, _ := a.links.Song("ytm", "from-other-device"); s.MBID != "rec-remote" {
		t.Errorf("a link from the server was not taken: %+v", s)
	}
	if s, _ := a.links.Song("ytm", "mine"); s.State != mblinks.Matched || s.MBID != "rec-mine" {
		t.Errorf("an older record from the server replaced a newer one: %+v", s)
	}
	if artist, ok := a.links.Artist("remote artist"); !ok || artist.MBID != "art-remote" {
		t.Errorf("the artist from the server was not taken: %+v %v", artist, ok)
	}
	// And the song from the other device shows its link without a search.
	other := youtube.MusicItem{VideoID: "from-other-device", Title: "Other", Kind: media.KindTrack}
	if s, ok := a.linkOf(other); !ok || s.State != mblinks.Matched {
		t.Errorf("the row of a song linked elsewhere does not show it: %+v %v", s, ok)
	}
}

// Signed out, links stay on the device and nothing is sent.
func TestLinksAreNotSentWhenSignedOut(t *testing.T) {
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	song := youtube.MusicItem{VideoID: "v", Title: "T", Artist: "A", Subtitle: "A", Kind: media.KindTrack, Duration: "3:00"}
	a.recordSong(song, mblinks.Matched, "rec", nil)
	if got := a.sync.outbox.Pending(); got != 0 {
		t.Errorf("a signed-out app queued %d records", got)
	}
	if s, ok := a.linkOf(song); !ok || s.MBID != "rec" {
		t.Errorf("the link was not kept on the device: %+v %v", s, ok)
	}
}
