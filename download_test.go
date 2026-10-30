package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// downloadTestItem is a YouTube Music track for the download tests.
func downloadTestItem(id string) youtube.MusicItem {
	return youtube.MusicItem{VideoID: id, Title: "Track " + id, Kind: media.KindTrack, Duration: "3:00"}
}

// newDownloadStore builds a download store with a real file directory in a
// temporary folder, which is what these tests keep tracks in.
func newDownloadStore(t *testing.T) *downloads.Store {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "downloads")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	return downloads.New(filepath.Join(dir, "index.json"), dir)
}

// addDownload writes a file for a track and records it, as a finished
// download would.
func addDownload(t *testing.T, store *downloads.Store, source media.Source, item youtube.MusicItem) string {
	t.Helper()
	path, name := store.Path(source, streamKey(item), ".mkv")
	if err := os.WriteFile(path, []byte("downloaded audio bytes for "+item.Title), 0o600); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Add(downloads.Item{
		Source: source, Key: streamKey(item), Title: item.Title, Artist: item.Artist,
		SizeBytes: info.Size(), DownloadedAt: time.Now(), File: name,
	}); err != nil {
		t.Fatal(err)
	}
	return path
}

// findAudio takes its audio from a download first, then the cache, then the
// network.
func TestFindAudioPrefersDownloadThenCacheThenNetwork(t *testing.T) {
	ctx := context.Background()
	networkURL := "https://media.test/network"
	streams := audio.NewURLs(func(context.Context, string) (string, time.Duration, error) {
		return networkURL, 0, nil
	})
	online := func() bool { return true }

	// A downloaded track wins over the network.
	store := newDownloadStore(t)
	item := downloadTestItem("vid-dl")
	path := addDownload(t, store, media.SourceYTM, item)
	found, err := findAudio(ctx, streams, item, nil, store, online)
	if err != nil || !found.fromDownload || found.url != path {
		t.Fatalf("download: url=%q fromDownload=%v err=%v", found.url, found.fromDownload, err)
	}

	// A cached track wins over the network.
	cacheRoot := t.TempDir()
	cache, _, err := audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	cacheItem := downloadTestItem("vid-cache")
	cachePath := filepath.Join(cache.Dir(), cacheName(cacheItem)+".webm")
	if err := os.WriteFile(cachePath, []byte("cached audio bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	cache.Close()
	cache, _, err = audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	defer cache.Close()
	found, err = findAudio(ctx, streams, cacheItem, cache, nil, online)
	if err != nil || !found.fromCache || found.url != cachePath {
		t.Fatalf("cache: url=%q fromCache=%v err=%v", found.url, found.fromCache, err)
	}

	// With neither, the audio comes from the network.
	found, err = findAudio(ctx, streams, downloadTestItem("vid-net"), nil, newDownloadStore(t), online)
	if err != nil || found.url != networkURL || found.fromCache || found.fromDownload {
		t.Fatalf("network: url=%q err=%v", found.url, err)
	}
}

// cacheName is the file name the audio cache gives a track, for a test that
// places a cached file itself.
func cacheName(item youtube.MusicItem) string {
	sum := sha256.Sum256([]byte(audioKey(item)))
	return hex.EncodeToString(sum[:])
}

// A resolve that failed with the network out of reach and no download says
// so, rather than blaming the provider.
func TestFindAudioOfflineWithoutDownload(t *testing.T) {
	streams := audio.NewURLs(func(context.Context, string) (string, time.Duration, error) {
		return "", 0, errors.New("resolve failed")
	})
	_, err := findAudio(context.Background(), streams, downloadTestItem("vid-missing"), nil, downloads.New("", ""), func() bool { return false })
	if !errors.Is(err, errOffline) {
		t.Fatalf("err = %v, want errOffline", err)
	}
	message := playErrorMessage(err.Error())
	if !strings.Contains(strings.ToLower(message), "offline") || !strings.Contains(message, "not downloaded") {
		t.Errorf("offline message = %q", message)
	}
}

// Playing a downloaded track records when it was last listened to.
func TestDownloadedTrackRecordsLastListen(t *testing.T) {
	a := newTestApp(t)
	store := newDownloadStore(t)
	a.downloads = store
	item := downloadTestItem("vid-listen")
	addDownload(t, store, media.SourceYTM, item)

	a.audioStarted(item, resolvedAudio{fromDownload: true}, 0, true, nil)

	rec, ok := store.Get(media.SourceYTM, streamKey(item))
	if !ok || rec.LastListenedAt.IsZero() {
		t.Fatalf("last listened = %v, ok=%v", rec.LastListenedAt, ok)
	}
	if time.Since(rec.LastListenedAt) > time.Minute {
		t.Errorf("last listened = %v, want about now", rec.LastListenedAt)
	}
}

// Clearing a playback cache deletes its own files and leaves a downloaded
// track untouched, on disk and in the store.
func TestClearingTheCacheKeepsDownloads(t *testing.T) {
	cacheRoot := t.TempDir()
	cache, _, err := audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	item := downloadTestItem("vid-both")
	cachePath := filepath.Join(cache.Dir(), cacheName(item)+".webm")
	if err := os.WriteFile(cachePath, []byte("cached audio bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	cache.Close()
	cache, _, err = audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	defer cache.Close()

	store := newDownloadStore(t)
	downloadPath := addDownload(t, store, media.SourceYTM, item)

	cache.Clear("")

	if _, err := os.Stat(cachePath); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("clearing the cache left %s", cachePath)
	}
	if _, err := os.Stat(downloadPath); err != nil {
		t.Errorf("clearing the cache removed the download: %v", err)
	}
	if _, ok := store.Get(media.SourceYTM, streamKey(item)); !ok {
		t.Error("clearing the cache removed the download entry")
	}
}

// The SoundCloud cache lives in a folder of its own, so it never mixes with
// the YouTube Music cache.
func TestSoundCloudCacheIsSeparate(t *testing.T) {
	root := t.TempDir()
	ytm, ytmRoot, err := audio.Open(root, 5)
	if err != nil {
		t.Fatal(err)
	}
	defer ytm.Close()
	scRootDir := filepath.Join(root, "Meiro SoundCloud Cache")
	if err := os.MkdirAll(scRootDir, 0o700); err != nil {
		t.Fatal(err)
	}
	sc, scRoot, err := audio.OpenLimits(scRootDir, audio.Limits{Count: 1 << 30, Bytes: 10 << 20})
	if err != nil {
		t.Fatal(err)
	}
	defer sc.Close()
	if ytm.Dir() == sc.Dir() || ytmRoot == scRoot {
		t.Fatalf("the two caches share a folder: %q and %q", ytm.Dir(), sc.Dir())
	}
}
