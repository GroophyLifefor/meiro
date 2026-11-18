package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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

// A removed download is sent to the server as deleted, at the time it was
// removed and from this device, and goes on being told until the server
// has it; a download that is kept is still sent as live.
func TestARemovedDownloadIsDeletedOnTheServer(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a := signedInTo(t, srv.URL)
	a.device = "laptop"
	for _, key := range []string{"keep", "gone"} {
		if err := a.downloads.Add(downloads.Item{Source: media.SourceYTM, Key: key, Title: "Song " + key, Artist: "A", DownloadedAt: time.Now().Add(-time.Hour)}); err != nil {
			t.Fatal(err)
		}
	}
	syncBoth(t, a)
	server.mu.Lock()
	for _, d := range server.downloads {
		if d.Deleted {
			t.Errorf("a kept download was sent as deleted: %+v", d)
		}
	}
	sentBefore := len(server.downloads)
	server.mu.Unlock()
	if sentBefore != 2 {
		t.Fatalf("the server was sent %d downloads, want 2", sentBefore)
	}

	rec, _ := a.downloads.Get(media.SourceYTM, "gone")
	before := time.Now()
	a.removeDownload(rec)
	syncBoth(t, a)

	server.mu.Lock()
	defer server.mu.Unlock()
	var deleted []meirosync.Download
	for _, d := range server.downloads[sentBefore:] {
		if d.Deleted {
			deleted = append(deleted, d)
		}
	}
	if len(deleted) != 1 {
		t.Fatalf("the server was told of %d removals: %+v", len(deleted), server.downloads[sentBefore:])
	}
	got := deleted[0]
	if got.Track.Provider != "ytm" || got.Track.ProviderTrackID != "gone" || got.Device != "laptop" || got.UpdatedAt.Before(before) {
		t.Errorf("the removal was sent as %+v", got)
	}
}

// The snapshot carries a removal for ninety days and not longer, and a
// download made again is live and not also a removal.
func TestTheSnapshotCarriesRemovedDownloads(t *testing.T) {
	a := newTestApp(t)
	a.device = "laptop"
	if err := a.downloads.Add(downloads.Item{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y", URL: "https://soundcloud.com/x/y", Title: "Cloud"}); err != nil {
		t.Fatal(err)
	}
	a.removeDownload(downloads.Item{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y", Title: "Cloud"})
	snap := a.syncSnapshot()
	if len(snap.downloads) != 1 || !snap.downloads[0].Deleted || snap.downloads[0].Track.Provider != "soundcloud" || snap.downloads[0].Track.ProviderTrackID != "https://soundcloud.com/x/y" {
		t.Fatalf("snapshot downloads = %+v", snap.downloads)
	}
	if err := a.downloads.Add(downloads.Item{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y", URL: "https://soundcloud.com/x/y", Title: "Cloud"}); err != nil {
		t.Fatal(err)
	}
	snap = a.syncSnapshot()
	if len(snap.downloads) != 1 || snap.downloads[0].Deleted {
		t.Errorf("a download made again is still sent as deleted: %+v", snap.downloads)
	}
}

// signedOutServer answers every call of the session with 401, as a server does
// once the token has run out, except a sign in, which it takes while it is
// told to, and passes the rest to the server behind it.
type signedOutServer struct {
	mu       sync.Mutex
	ended    bool
	requests int
	behind   http.Handler
}

func (s *signedOutServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	ended := s.ended
	if !strings.HasSuffix(r.URL.Path, "/auth/login") {
		s.requests++
	}
	s.mu.Unlock()
	switch {
	case strings.HasSuffix(r.URL.Path, "/auth/login"):
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"token":"NEW"}`))
	case ended:
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"token expired"}`))
	default:
		s.behind.ServeHTTP(w, r)
	}
}

func (s *signedOutServer) asked() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.requests
}

// Whichever call the server answers with 401, the session is over: syncing
// stops, the token is gone from memory and disk, and the account, its server,
// its cursors and the records that wait are all kept.
func TestA401EndsTheSessionAndKeepsEverything(t *testing.T) {
	for _, tc := range []struct {
		name string
		call func(a *app)
	}{
		{"push", func(a *app) { a.syncNow() }},
		{"changes", func(a *app) { a.pullChanges() }},
		{"events", func(a *app) { a.pullEvents() }},
		{"links", func(a *app) { a.pullLinks() }},
		{"clearing", func(a *app) {
			// Nothing waits to be pushed, so only the clearing can find out.
			a.sync.outbox = meirosync.Open("")
			a.settings.HistoryClearPending = time.Now()
			a.syncNow()
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := &signedOutServer{ended: true, behind: newServerState()}
			a := signedInToServer(t, server)
			a.historyLoaded = true
			tokenPath := filepath.Join(t.TempDir(), "sync-token.txt")
			a.sync.tokens = meirosync.TokenStore{Path: tokenPath}
			if err := a.sync.tokens.Save("TOKEN"); err != nil {
				t.Fatal(err)
			}
			a.settings.SyncCursor, a.settings.SyncCursorURL, a.settings.SyncCursorEmail = 7, a.settings.SyncURL, a.settings.SyncEmail
			a.settings.EventCursor, a.settings.EventCursorURL, a.settings.EventCursorEmail = 9, a.settings.SyncURL, a.settings.SyncEmail
			playAt(a, "p1", time.Now().Add(-time.Hour))
			tc.call(a)
			settled(t, a)
			pollUntil(t, a, "the token to be removed", func() bool {
				_, err := os.Stat(tokenPath)
				return errors.Is(err, os.ErrNotExist)
			})

			s := &a.sync
			if s.token != "" || !s.expired {
				t.Errorf("token %q, expired %t: the session did not end", s.token, s.expired)
			}
			if a.settings.SyncEmail != "me@example.com" || a.settings.SyncURL == "" || s.email != "me@example.com" {
				t.Errorf("the account was not kept: settings %q %q, form %q", a.settings.SyncEmail, a.settings.SyncURL, s.email)
			}
			if a.settings.SyncCursor != 7 || a.settings.EventCursor != 9 || a.settings.SyncCursorEmail == "" {
				t.Errorf("the cursors were lost: %d %d", a.settings.SyncCursor, a.settings.EventCursor)
			}
			if tc.name != "clearing" && a.sync.outbox.EventCount() != 1 {
				t.Errorf("the queued play was lost: %d", a.sync.outbox.EventCount())
			}
			if !a.settings.SyncEnabled {
				t.Error("syncing was switched off")
			}
			if tc.name == "clearing" && a.settings.HistoryClearPending.IsZero() {
				t.Error("the clearing that waits was lost")
			}

			// Nothing is tried again until the account signs in.
			asked := server.asked()
			a.syncNow()
			a.pullChanges()
			a.pullEvents()
			a.pullLinks()
			settled(t, a)
			if got := server.asked(); got != asked {
				t.Errorf("%d requests went out after the session ended", got-asked)
			}
		})
	}
}

// The card tells the user the session ended and offers the sign in form with
// the email filled in; signing in again to the same account sends what waited
// and reads on from the cursor it had.
func TestSigningInAgainResumesWhereTheSessionEnded(t *testing.T) {
	state := newServerState()
	server := &signedOutServer{ended: true, behind: state}
	a := signedInToServer(t, server)
	a.historyLoaded = true
	a.sync.tokens = meirosync.TokenStore{Path: filepath.Join(t.TempDir(), "sync-token.txt")}
	a.settings.SyncCursor, a.settings.SyncCursorURL, a.settings.SyncCursorEmail = 4, a.settings.SyncURL, a.settings.SyncEmail
	a.settings.SyncTwoWay = true
	playAt(a, "p1", time.Now().Add(-time.Hour))
	a.syncNow()
	settled(t, a)

	a.router.Push("/settings")
	tt := ui.NewTester(a.view, 1000, 8000)
	tt.Frame()
	for _, want := range []string{"Your session ended. Sign in again to keep syncing.", "Sign in to sync", "Email", "Password"} {
		if !tt.HasText(want) {
			t.Errorf("the card does not show %q: %q", want, tt.Texts())
		}
	}
	if tt.HasText("Sign out") || tt.HasText("Sync now") {
		t.Errorf("the card still offers the signed in controls: %q", tt.Texts())
	}

	server.mu.Lock()
	server.ended = false
	server.mu.Unlock()
	a.sync.password = "secret"
	a.signInSync()
	settled(t, a)
	if a.sync.token != "NEW" || a.sync.expired {
		t.Fatalf("token %q, expired %t after signing in again", a.sync.token, a.sync.expired)
	}
	tt.Frame()
	if tt.HasText("Your session ended") {
		t.Errorf("the card still says the session ended: %q", tt.Texts())
	}
	if ids := serverPlayIDs(state); len(ids) != 1 || ids[0] != "p1" {
		t.Errorf("the play that waited was not sent: %v", ids)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if len(state.asked) == 0 || state.asked[0] != 4 {
		t.Errorf("the changes were read from %v, want from the cursor 4", state.asked)
	}
}

// A refused sign in is not an ended session: the message is about the password.
func TestAWrongPasswordIsNotAnEndedSession(t *testing.T) {
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	t.Cleanup(srv.Close)
	a.settings.SyncURL = srv.URL
	a.sync.email, a.sync.password = "me@example.com", "wrong"
	a.signInSync()
	settled(t, a)
	if a.sync.expired || a.sync.err != "The server did not accept the email and password." {
		t.Errorf("expired %t, err %q", a.sync.expired, a.sync.err)
	}
}
