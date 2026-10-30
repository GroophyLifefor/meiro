package sync

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/elianiva/meiro/media"
)

// fakeServer is a Meiro server as the sync package sees it: it records what
// each push held and answers as the test sets it up to.
type fakeServer struct {
	mu       sync.Mutex
	status   int // 0 or 200 accepts; anything else is a refusal
	requests int
	eventIDs [][]string
	// links are the link pushes the server took, afterLinks runs while one is
	// being answered, and serverLinks is what a read of the links returns.
	links       []Links
	afterLinks  func()
	serverLinks Links
}

func (f *fakeServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	f.mu.Lock()
	f.requests++
	status := f.status
	f.mu.Unlock()

	if status != 0 && status != http.StatusOK {
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"error":"boom"}`))
		return
	}

	w.Header().Set("Content-Type", "application/json")
	switch {
	case strings.HasSuffix(r.URL.Path, "/sync/events"):
		var req struct {
			Events []Event `json:"events"`
		}
		_ = json.Unmarshal(body, &req)
		ids := make([]string, len(req.Events))
		for i, e := range req.Events {
			ids[i] = e.ClientEventID
		}
		f.mu.Lock()
		f.eventIDs = append(f.eventIDs, ids)
		f.mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string][]string{"accepted": ids, "duplicates": {}})
	case strings.HasSuffix(r.URL.Path, "/sync/links"):
		var links Links
		_ = json.Unmarshal(body, &links)
		f.mu.Lock()
		f.links = append(f.links, links)
		after := f.afterLinks
		f.mu.Unlock()
		if after != nil {
			after()
		}
		_, _ = w.Write([]byte(`{"accepted":` + strconv.Itoa(links.Len()) + `}`))
	case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/api/v1/links"):
		f.mu.Lock()
		out := f.serverLinks
		f.mu.Unlock()
		_ = json.NewEncoder(w).Encode(out)
	case strings.HasSuffix(r.URL.Path, "/auth/login"):
		_, _ = w.Write([]byte(`{"token":"TOKEN"}`))
	default:
		_, _ = w.Write([]byte(`{"accepted":[]}`))
	}
}

func (f *fakeServer) client(t *testing.T) *Client {
	t.Helper()
	server := httptest.NewServer(f)
	t.Cleanup(server.Close)
	return &Client{BaseURL: server.URL, Token: "TOKEN"}
}

func (f *fakeServer) batches() [][]string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]string{}, f.eventIDs...)
}

func sampleEvent(id string) Event {
	return Event{
		ClientEventID: id,
		Track:         TrackFor(media.SourceYTM, "vid-"+id, "", "Song "+id, "Artist", 180000),
		PlayedMS:      60000,
		DurationMS:    180000,
		PlayedAt:      time.Unix(1000, 0),
	}
}

// A play kept in the outbox is still there after a restart.
func TestOutboxSurvivesARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "outbox.json")
	o := Open(path)
	o.AddEvent(sampleEvent("a"))
	o.Wait()
	if o.EventCount() != 1 {
		t.Fatalf("pending = %d, want 1", o.EventCount())
	}

	again := Open(path)
	if again.EventCount() != 1 || again.snapshot().events[0].ClientEventID != "a" {
		t.Fatalf("after reopening: %+v", again.snapshot().events)
	}
}

// A push the server refuses keeps every record, and a push it takes clears
// them.
func TestARefusedPushKeepsTheQueueAndATakenOneClearsIt(t *testing.T) {
	f := &fakeServer{status: http.StatusInternalServerError}
	o := Open(filepath.Join(t.TempDir(), "outbox.json"))
	o.AddEvent(sampleEvent("a"))
	o.AddEvent(sampleEvent("b"))

	if _, err := Push(context.Background(), f.client(t), o); err == nil {
		t.Fatal("a 500 was treated as delivered")
	}
	if o.EventCount() != 2 {
		t.Fatalf("a refused push dropped plays: %d left", o.EventCount())
	}

	f.mu.Lock()
	f.status = http.StatusOK
	f.mu.Unlock()
	if n, err := Push(context.Background(), f.client(t), o); err != nil || n != 2 {
		t.Fatalf("push = %d, %v", n, err)
	}
	if o.EventCount() != 0 {
		t.Errorf("the delivered plays are still pending: %d", o.EventCount())
	}
}

// A queue larger than the server's limit goes out in batches, none of them
// over the limit.
func TestALargeQueueGoesOutInBatches(t *testing.T) {
	f := &fakeServer{}
	o := Open(filepath.Join(t.TempDir(), "outbox.json"))
	const total = MaxBatch*5 + 1
	for i := range total {
		o.AddEvent(sampleEvent(strconv.Itoa(i)))
	}

	if _, err := Push(context.Background(), f.client(t), o); err != nil {
		t.Fatal(err)
	}
	batches := f.batches()
	if len(batches) != 6 {
		t.Errorf("%d requests, want 6", len(batches))
	}
	sent := 0
	for _, batch := range batches {
		if len(batch) > 1000 {
			t.Errorf("a request held %d events, over the server's limit", len(batch))
		}
		sent += len(batch)
	}
	if sent != total || o.EventCount() != 0 {
		t.Errorf("sent %d, pending %d, want %d and 0", sent, o.EventCount(), total)
	}
}

func TestBackoffGrowsAndIsCapped(t *testing.T) {
	if Backoff(1) < time.Second || Backoff(2) <= Backoff(1) || Backoff(3) <= Backoff(2) {
		t.Errorf("backoff does not grow: %v %v %v", Backoff(1), Backoff(2), Backoff(3))
	}
	if Backoff(30) != 5*time.Minute {
		t.Errorf("backoff is not capped: %v", Backoff(30))
	}
}

// An ID is generated once and kept; two calls do not share one.
func TestIDsAreStableAndDistinct(t *testing.T) {
	first, second := NewID(), NewID()
	if first == second {
		t.Error("two records share an ID")
	}
	if len(first) != 36 {
		t.Errorf("an ID is not the hyphen form: %q", first)
	}
	local := LibraryID("local-1")
	if local != LibraryID("local-1") {
		t.Errorf("a library's server id changed: %q then %q", local, LibraryID("local-1"))
	}
	if local == LibraryID("local-2") {
		t.Error("two libraries share a server id")
	}
}

// A track's provider and provider id come from the source and the key
// playback uses.
func TestTrackForMapsTheProvider(t *testing.T) {
	for _, tc := range []struct {
		name        string
		source      media.Source
		key, url    string
		wantProv    string
		wantTrackID string
	}{
		{"ytm", media.SourceYTM, "dQw4w9WgXcQ", "", "ytm", "dQw4w9WgXcQ"},
		{"soundcloud", media.SourceSoundCloud, "https://soundcloud.com/x/y", "https://soundcloud.com/x/y", "soundcloud", "https://soundcloud.com/x/y"},
		{"empty source is ytm", "", "vid", "", "ytm", "vid"},
		{"key missing falls back to the url", media.SourceSoundCloud, "", "https://soundcloud.com/a/b", "soundcloud", "https://soundcloud.com/a/b"},
	} {
		got := TrackFor(tc.source, tc.key, tc.url, "T", "A", 1000)
		if got.Provider != tc.wantProv || got.ProviderTrackID != tc.wantTrackID {
			t.Errorf("%s: %+v, want provider %q id %q", tc.name, got, tc.wantProv, tc.wantTrackID)
		}
	}
}

func TestRetryable(t *testing.T) {
	if !Retryable(&APIError{Status: 503}) {
		t.Error("a 5xx should be retried")
	}
	if Retryable(&APIError{Status: 400}) {
		t.Error("a 400 should not be retried")
	}
	if !Retryable(context.DeadlineExceeded) {
		t.Error("a network error should be retried")
	}
}

// Links wait in the outbox like any other record, go out in batches, leave the
// queue when the server takes them, and a link changed again while it was on
// its way stays for the next push.
func TestLinksGoOutAndANewerChangeStays(t *testing.T) {
	f := &fakeServer{}
	o := Open(filepath.Join(t.TempDir(), "outbox.json"))
	t0 := time.Unix(1_700_000_000, 0)
	var songs []SongLink
	for i := range MaxBatch + 5 {
		songs = append(songs, SongLink{Provider: "ytm", ProviderTrackID: "v" + strconv.Itoa(i), State: "matched", MBID: "r", UpdatedAt: t0})
	}
	o.AddLinks(Links{
		Songs:      songs,
		Artists:    []ArtistLink{{ArtistKey: "hidra", Name: "Hidra", State: "matched", MBID: "a", UpdatedAt: t0}},
		Alternates: []AltLink{{Provider: "ytm", ProviderTrackID: "v0", OtherProvider: "soundcloud", OtherTrackID: "u", CheckedAt: t0, UpdatedAt: t0}},
	})
	if got := o.Pending(); got != MaxBatch+5+2 {
		t.Fatalf("pending = %d, want %d", got, MaxBatch+5+2)
	}
	// While the first batch is answered the same song changes again.
	var once sync.Once
	f.afterLinks = func() {
		once.Do(func() {
			o.AddLinks(Links{Songs: []SongLink{{Provider: "ytm", ProviderTrackID: "v0", State: "other", UpdatedAt: t0.Add(time.Hour)}}})
		})
	}
	if _, err := Push(context.Background(), f.client(t), o); err != nil {
		t.Fatal(err)
	}
	for _, batch := range f.links {
		if len(batch.Songs) > MaxBatch || len(batch.Artists) > MaxBatch {
			t.Fatalf("a batch was over the limit: %d songs", len(batch.Songs))
		}
	}
	left := o.snapshot().links
	if len(left.Songs) != 1 || left.Songs[0].ProviderTrackID != "v0" || left.Songs[0].State != "other" {
		t.Fatalf("what is left = %+v, want only the newer change of v0", left)
	}
	if len(left.Artists) != 0 || len(left.Alternates) != 0 {
		t.Errorf("sent links are still pending: %+v", left)
	}
}

// A pending link is replaced by a newer one of the same name and not by an
// older one.
func TestAPendingLinkKeepsTheNewerRecord(t *testing.T) {
	o := Open("")
	t0 := time.Unix(1_700_000_000, 0)
	o.AddLinks(Links{Songs: []SongLink{{Provider: "ytm", ProviderTrackID: "v", State: "matched", MBID: "new", UpdatedAt: t0.Add(time.Hour)}}})
	o.AddLinks(Links{Songs: []SongLink{{Provider: "ytm", ProviderTrackID: "v", State: "other", UpdatedAt: t0}}})
	got := o.snapshot().links.Songs
	if len(got) != 1 || got[0].MBID != "new" {
		t.Errorf("pending = %+v, want the newer record only", got)
	}
}

// The links the server holds are read back as they were stored.
func TestFetchLinks(t *testing.T) {
	f := &fakeServer{serverLinks: Links{
		Songs:      []SongLink{{Provider: "ytm", ProviderTrackID: "v", State: "matched", MBID: "r", UpdatedAt: time.Unix(1_700_000_000, 0).UTC()}},
		Alternates: []AltLink{{Provider: "ytm", ProviderTrackID: "v", OtherProvider: "soundcloud", OtherTrackID: "u"}},
	}}
	got, err := f.client(t).FetchLinks(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Songs) != 1 || got.Songs[0].MBID != "r" || len(got.Alternates) != 1 || got.Alternates[0].OtherTrackID != "u" {
		t.Errorf("links read back = %+v", got)
	}
	refused := &fakeServer{status: http.StatusUnauthorized}
	if _, err := refused.client(t).FetchLinks(context.Background()); err == nil {
		t.Error("a refused read did not fail")
	}
}
