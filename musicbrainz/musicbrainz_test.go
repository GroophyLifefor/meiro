package musicbrainz

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// newTestClient returns a Client pointed at a test server, with the request
// queue driven by a fixed clock and a sleep that takes no time, so a test
// never waits.
func newTestClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return New(Options{
		HTTP:            server.Client(),
		MusicBrainzURL:  server.URL,
		ListenBrainzURL: server.URL,
		LabsURL:         server.URL,
		CacheDir:        t.TempDir(),
		Clock:           func() time.Time { return time.Unix(1_700_000_000, 0) },
		Sleep:           func(context.Context, time.Duration) error { return nil },
	})
}

func TestSearchArtists(t *testing.T) {
	var hits atomic.Int32
	var userAgent string
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		userAgent = r.Header.Get("User-Agent")
		if r.URL.Path != "/ws/2/artist" {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("query"); got != "yorushika" {
			t.Errorf("query = %q", got)
		}
		_, _ = io.WriteString(w, `{"artists":[{"id":"a1","name":"Yorushika","country":"JP","score":100}]}`)
	})

	artists, err := client.SearchArtists(context.Background(), "yorushika")
	if err != nil {
		t.Fatalf("SearchArtists: %v", err)
	}
	if len(artists) != 1 || artists[0].Name != "Yorushika" || artists[0].Country != "JP" {
		t.Fatalf("artists = %+v", artists)
	}
	if userAgent != UserAgent || !strings.Contains(userAgent, "(muratkirazkaya0@gmail.com)") {
		t.Fatalf("User-Agent = %q, want one naming Meiro", userAgent)
	}

	// The same search is answered from the cache, so no second request goes
	// out.
	if _, err := client.SearchArtists(context.Background(), "yorushika"); err != nil {
		t.Fatalf("SearchArtists again: %v", err)
	}
	if hits.Load() != 1 {
		t.Fatalf("the search was requested %d times, want one", hits.Load())
	}
}

func TestSearchArtistsExpires(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"artists":[{"id":"a1","name":"Yorushika"}]}`)
	}))
	t.Cleanup(server.Close)
	client := New(Options{
		HTTP:            server.Client(),
		MusicBrainzURL:  server.URL,
		ListenBrainzURL: server.URL,
		CacheDir:        t.TempDir(),
		Clock:           func() time.Time { return now },
		Sleep:           func(context.Context, time.Duration) error { return nil },
	})
	if _, err := client.SearchArtists(context.Background(), "yorushika"); err != nil {
		t.Fatalf("SearchArtists: %v", err)
	}
	// A second lookup a day later is still held; one a month later is not.
	now = now.Add(24 * time.Hour)
	if !client.cache.get("mb:artist-search:yorushika", artistSearchTTL, &[]Artist{}) {
		t.Fatal("an answer a day old was not kept")
	}
	now = now.Add(30 * 24 * time.Hour)
	if client.cache.get("mb:artist-search:yorushika", artistSearchTTL, &[]Artist{}) {
		t.Fatal("an answer a month old was still kept")
	}
}

func TestArtistPopularity(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/1/popularity/artist" {
			http.NotFound(w, r)
			return
		}
		var body struct {
			MBIDs []string `json:"artist_mbids"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if len(body.MBIDs) != 2 {
			t.Errorf("asked for %v", body.MBIDs)
		}
		_, _ = io.WriteString(w, `[{"artist_mbid":"a1","total_listen_count":10,"total_user_count":3}]`)
	})

	popular, err := client.ArtistPopularity(context.Background(), []string{"a1", "a2"})
	if err != nil {
		t.Fatalf("ArtistPopularity: %v", err)
	}
	if got := popular["a1"]; got.Listeners != 3 || got.Listens != 10 {
		t.Fatalf("popularity = %+v", got)
	}
}

func TestTopRecordingsFromListenBrainz(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/1/popularity/top-recordings-for-artist/a1" {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, `[{"recording_mbid":"r1","recording_name":"Just a Moment","artist_name":"Yorushika","length":242000,"total_user_count":5}]`)
	})

	recordings, err := client.TopRecordings(context.Background(), "a1")
	if err != nil {
		t.Fatalf("TopRecordings: %v", err)
	}
	if len(recordings) != 1 || recordings[0].ID != "r1" || recordings[0].LengthMS != 242000 {
		t.Fatalf("recordings = %+v", recordings)
	}
}

func TestTopRecordingsFallsBackToMusicBrainz(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/1/popularity/top-recordings-for-artist/"):
			_, _ = io.WriteString(w, `[]`)
		case r.URL.Path == "/ws/2/recording":
			_, _ = io.WriteString(w, `{"recordings":[{"id":"r2","title":"Ame","length":98000}]}`)
		default:
			http.NotFound(w, r)
		}
	})

	recordings, err := client.TopRecordings(context.Background(), "a1")
	if err != nil {
		t.Fatalf("TopRecordings: %v", err)
	}
	if len(recordings) != 1 || recordings[0].ID != "r2" || recordings[0].Title != "Ame" {
		t.Fatalf("recordings = %+v", recordings)
	}
}

func TestRecordingLinks(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/ws/2/recording/r1" {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("inc"); got != "url-rels" {
			t.Errorf("inc = %q", got)
		}
		_, _ = io.WriteString(w, `{"relations":[
			{"type":"free streaming","url":{"resource":"https://music.youtube.com/watch?v=abc"}},
			{"type":"lyrics","url":{"resource":"https://lyrics.example/song"}},
			{"type":"streaming","url":{"resource":"https://open.spotify.com/track/x"}}
		]}`)
	})

	links, err := client.RecordingLinks(context.Background(), "r1")
	if err != nil {
		t.Fatalf("RecordingLinks: %v", err)
	}
	if len(links) != 2 {
		t.Fatalf("links = %+v, want the two playable ones", links)
	}
	if links[0].Type != "free streaming" || links[0].URL != "https://music.youtube.com/watch?v=abc" {
		t.Fatalf("first link = %+v", links[0])
	}
}

func TestLookupURLAndISRC(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/ws/2/url":
			if got := r.URL.Query().Get("resource"); got != "https://music.youtube.com/watch?v=abc" {
				t.Errorf("resource = %q", got)
			}
			_, _ = io.WriteString(w, `{"relations":[{"recording":{"id":"r1","title":"Just a Moment","length":242000}}]}`)
		case strings.HasPrefix(r.URL.Path, "/ws/2/isrc/"):
			if got := strings.TrimPrefix(r.URL.Path, "/ws/2/isrc/"); got != "JPA651703790" {
				t.Errorf("isrc path = %q, want the uppercased code", got)
			}
			_, _ = io.WriteString(w, `{"isrc":"JPA651703790","recordings":[{"id":"r1","title":"Just a Moment","length":242000}]}`)
		default:
			http.NotFound(w, r)
		}
	})

	refs, err := client.LookupURL(context.Background(), "https://music.youtube.com/watch?v=abc")
	if err != nil || len(refs) != 1 || refs[0].ID != "r1" {
		t.Fatalf("LookupURL = %+v, %v", refs, err)
	}
	isrcRefs, err := client.LookupISRC(context.Background(), "jpa651703790")
	if err != nil || len(isrcRefs) != 1 || isrcRefs[0].Title != "Just a Moment" {
		t.Fatalf("LookupISRC = %+v, %v", isrcRefs, err)
	}
}

func TestLookupURLNotFoundIsNotAnError(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		http.NotFound(w, r)
	})
	refs, err := client.LookupURL(context.Background(), "https://example.com/nothing")
	if err != nil {
		t.Fatalf("LookupURL: %v", err)
	}
	if len(refs) != 0 {
		t.Fatalf("refs = %+v, want none", refs)
	}
}

func TestServerErrorIsReported(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	})
	if _, err := client.SearchArtists(context.Background(), "yorushika"); err == nil {
		t.Fatal("a 503 was not reported as an error")
	}
}

func TestSearchRecordings(t *testing.T) {
	var query string
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/ws/2/recording" {
			http.NotFound(w, r)
			return
		}
		query = r.URL.Query().Get("query")
		_, _ = io.WriteString(w, `{"recordings":[{"id":"r1","score":100,"title":"Get Lucky","length":248000,
			"artist-credit":[{"name":"Daft Punk","joinphrase":" feat. ","artist":{"id":"a1","name":"Daft Punk"}},
			{"name":"Pharrell Williams","artist":{"id":"a2","name":"Pharrell Williams"}}]}]}`)
	})
	got, err := client.SearchRecordings(context.Background(), `Get "Lucky"`, "Daft Punk")
	if err != nil {
		t.Fatalf("SearchRecordings: %v", err)
	}
	if want := `recording:"Get \"Lucky\"" AND artist:"Daft Punk"`; query != want {
		t.Errorf("query = %q, want %q", query, want)
	}
	if len(got) != 1 || got[0].ID != "r1" || got[0].Artist != "Daft Punk feat. Pharrell Williams" || got[0].ArtistID != "a1" || got[0].LengthMS != 248000 || got[0].Score != 100 {
		t.Fatalf("matches = %+v", got)
	}
	if none, err := client.SearchRecordings(context.Background(), "  ", "x"); err != nil || none != nil {
		t.Errorf("an empty title searched: %v, %v", none, err)
	}
}

// The cache says what it holds, a song search is kept shorter than an artist
// search, and one answer or all can be forgotten.
func TestCacheCanBeListedAndCleared(t *testing.T) {
	var hits atomic.Int32
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		switch r.URL.Path {
		case "/ws/2/artist":
			_, _ = io.WriteString(w, `{"artists":[{"id":"a1","name":"Hidra","score":100},{"id":"a2","name":"Hidra","score":93}]}`)
		default:
			_, _ = io.WriteString(w, `{"recordings":[]}`)
		}
	})
	if _, err := client.SearchArtists(context.Background(), "hidra"); err != nil {
		t.Fatal(err)
	}
	if _, err := client.SearchRecordings(context.Background(), "Dümen", "Hidra"); err != nil {
		t.Fatal(err)
	}
	entries := client.CacheEntries()
	if len(entries) != 2 {
		t.Fatalf("entries = %+v, want two", entries)
	}
	byKind := map[string]CacheEntry{}
	for _, e := range entries {
		byKind[e.Kind] = e
	}
	artist, song := byKind["Artist search"], byKind["Song search"]
	if artist.Query != "hidra" || artist.Results != 2 || artist.Bytes == 0 {
		t.Errorf("artist entry = %+v", artist)
	}
	if song.Query != `"Dümen" AND artist:"Hidra"` && !strings.Contains(song.Query, "Dümen") {
		t.Errorf("song entry = %+v", song)
	}
	if got := artist.Expires.Sub(artist.StoredAt); got != artistSearchTTL {
		t.Errorf("an artist search is kept %v, want %v", got, artistSearchTTL)
	}
	if got := song.Expires.Sub(song.StoredAt); got != recordingSearchTTL {
		t.Errorf("a song search is kept %v, want %v", got, recordingSearchTTL)
	}

	if err := client.RemoveCacheEntry(artist.ID); err != nil {
		t.Fatal(err)
	}
	if left := client.CacheEntries(); len(left) != 1 || left[0].Kind != "Song search" {
		t.Errorf("after removing the artist search: %+v", left)
	}
	if err := client.RemoveCacheEntry("../../etc/passwd"); err != nil {
		t.Errorf("a path that is not an answer failed instead of being ignored: %v", err)
	}
	before := hits.Load()
	if err := client.ClearCache(); err != nil {
		t.Fatal(err)
	}
	if left := client.CacheEntries(); len(left) != 0 {
		t.Errorf("after clearing: %+v", left)
	}
	if _, err := client.SearchArtists(context.Background(), "hidra"); err != nil {
		t.Fatal(err)
	}
	if hits.Load() != before+1 {
		t.Errorf("a cleared search was not asked for again: %d hits, was %d", hits.Load(), before)
	}
}

func TestSimilarArtists(t *testing.T) {
	var hits atomic.Int32
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.URL.Path != "/similar-artists/json" || r.URL.Query().Get("artist_mbids") != "a1" {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, `[{"artist_mbid":"b1","name":"Close","score":400},{"artist_mbid":"b2","name":"Far","score":100}]`)
	})
	got, err := client.SimilarArtists(context.Background(), "a1")
	if err != nil || len(got) != 2 {
		t.Fatalf("SimilarArtists = %+v, %v", got, err)
	}
	if got[0].Similarity != 1 || got[1].Similarity != 0.25 {
		t.Errorf("similarities = %v and %v, want 1 and 0.25 scaled to the best", got[0].Similarity, got[1].Similarity)
	}
	if _, err := client.SimilarArtists(context.Background(), "a1"); err != nil || hits.Load() != 1 {
		t.Errorf("a second ask went out again: %d hits, %v", hits.Load(), err)
	}
	if none, err := client.SimilarArtists(context.Background(), " "); err != nil || none != nil {
		t.Errorf("an empty id searched: %v %v", none, err)
	}
	empty := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, `[]`) })
	if got, err := empty.SimilarArtists(context.Background(), "unknown"); err != nil || len(got) != 0 {
		t.Errorf("an unknown artist = %v, %v; want none and no error", got, err)
	}
}
