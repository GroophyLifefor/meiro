package audio

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// The cache downloads real bytes from a media server, keeps the most recent
// tracks within its limit, and survives a restart.
func TestCacheFromDownloadToRestart(t *testing.T) {
	long := make([]byte, 2*downloadRange+3)
	for i := range long {
		long[i] = byte(i % 251)
	}
	media := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/gone":
			http.Error(w, "expired", http.StatusForbidden)
		case "/long":
			// A track longer than one range. YouTube throttles a request
			// for the whole of it; this server refuses one.
			if ranged := r.Header.Get("Range"); ranged == "" || strings.HasSuffix(ranged, "-") {
				http.Error(w, "ask for a range", http.StatusTooManyRequests)
				return
			}
			http.ServeContent(w, r, "long.webm", time.Time{}, bytes.NewReader(long))
		default:
			w.Header().Set("Content-Type", "audio/mp4")
			_, _ = w.Write([]byte("audio for " + r.URL.Path))
		}
	}))
	defer media.Close()

	root := t.TempDir()
	cache, _, err := Open(root, 2)
	if err != nil {
		t.Fatal(err)
	}
	cached := func(id string) bool { _, ok := cache.Get(id); return ok }
	waitCached := func(id string) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for !cached(id) {
			if time.Now().After(deadline) {
				t.Fatalf("%s was never cached", id)
			}
			time.Sleep(5 * time.Millisecond)
		}
	}

	cache.Add("one", media.URL+"/one")
	waitCached("one")
	cache.Add("two", media.URL+"/two")
	waitCached("two")
	path, _ := cache.Get("two")
	if !strings.HasSuffix(path, ".m4a") {
		t.Errorf("a track served as audio/mp4 was kept as %q", path)
	}
	if data, _ := os.ReadFile(path); string(data) != "audio for /two" {
		t.Errorf("cached bytes = %q", data)
	}

	// A third track pushes out the least recently used one, which is "one"
	// now that "two" was just read.
	cache.Get("two")
	cache.Add("three", media.URL+"/three")
	waitCached("three")
	if cached("one") || !cached("two") {
		t.Errorf("eviction kept one=%v two=%v", cached("one"), cached("two"))
	}

	// An expired URL caches nothing and leaves nothing half written.
	cache.Add("gone", media.URL+"/gone")
	cache.Close()
	if cached("gone") {
		t.Error("a refused download was cached")
	}

	// A restart finds the files that were kept.
	again, _, err := Open(root, 2)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	if _, ok := again.Get("three"); !ok {
		t.Error("the cache lost its files across a restart")
	}

	// A track longer than one range arrives whole, every range in its place.
	cache = again
	cache.Add("long", media.URL+"/long")
	waitCached("long")
	path, _ = cache.Get("long")
	if data, _ := os.ReadFile(path); !bytes.Equal(data, long) {
		t.Error("a track downloaded by ranges was not cached byte for byte")
	}
}

// The cache keeps what it knows of a cached track beside the audio, so a
// local search can find it without the network, and drops it with the file.
func TestCacheKeepsTrackMeta(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "audio/mp4")
		_, _ = w.Write([]byte("audio"))
	}))
	defer server.Close()

	root := t.TempDir()
	cache, _, err := Open(root, 2)
	if err != nil {
		t.Fatal(err)
	}
	cached := func(id string) bool { _, ok := cache.Get(id); return ok }
	waitCached := func(id string) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for !cached(id) {
			if time.Now().After(deadline) {
				t.Fatalf("%s was never cached", id)
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	meta := func(key, title string) TrackMeta {
		return TrackMeta{Key: key, Source: "ytm", Title: title, Artist: "Someone", Duration: "3:00"}
	}

	cache.Add("ytm:vid-1", server.URL+"/one", meta("vid-1", "One"))
	waitCached("ytm:vid-1")
	if metas := cache.Meta(); len(metas) != 1 || metas[0].Title != "One" || metas[0].Key != "vid-1" {
		t.Fatalf("metadata after a cache = %+v", metas)
	}

	// The metadata survives a restart, read back beside the file. The smaller
	// limit lets the next track evict this one.
	cache.Close()
	again, _, err := Open(root, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	if metas := again.Meta(); len(metas) != 1 || metas[0].Title != "One" {
		t.Fatalf("metadata after a restart = %+v", metas)
	}

	// A track that evicts vid-1 takes its metadata with it.
	again.Add("ytm:vid-2", server.URL+"/two", meta("vid-2", "Two"))
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, ok := again.Get("ytm:vid-2"); ok {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("ytm:vid-2 was never cached")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if _, ok := again.Get("ytm:vid-1"); ok {
		t.Fatal("vid-1 was not evicted")
	}
	if metas := again.Meta(); len(metas) != 1 || metas[0].Key != "vid-2" {
		t.Fatalf("metadata after eviction = %+v", metas)
	}
}

// Requests for one track share a single resolve, and a failed resolve is
// asked again.
func TestURLsShareOneResolve(t *testing.T) {
	var calls atomic.Int32
	release := make(chan struct{})
	urls := NewURLs(func(_ context.Context, videoID string) (string, time.Duration, error) {
		calls.Add(1)
		<-release
		if videoID == "bad" {
			return "", 0, context.DeadlineExceeded
		}
		return "https://media.test/" + videoID, time.Minute, nil
	})
	done := make(chan string, 2)
	for range 2 {
		go func() {
			url, _, _ := urls.Get(context.Background(), "a")
			done <- url
		}()
	}
	time.Sleep(20 * time.Millisecond)
	close(release)
	if first, second := <-done, <-done; first != "https://media.test/a" || first != second {
		t.Fatalf("got %q and %q", first, second)
	}
	if calls.Load() != 1 {
		t.Errorf("two requests resolved %d times", calls.Load())
	}
	_, _, _ = urls.Get(context.Background(), "bad")
	_, _, _ = urls.Get(context.Background(), "bad")
	if calls.Load() != 3 {
		t.Errorf("a failed resolve was remembered: %d resolves", calls.Load())
	}
}
