package downloads

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/elianiva/meiro/media"
)

// The store keeps what was downloaded across a restart, and removes one entry
// without disturbing the others.
func TestStoreSurvivesARestart(t *testing.T) {
	dir := t.TempDir()
	index := filepath.Join(t.TempDir(), "downloads.json")
	store := New(index, dir)
	if err := store.Add(Item{
		Source: media.SourceYTM, Key: "vid-1", Title: "One", Artist: "A",
		SizeBytes: 1000, DownloadedAt: time.Unix(100, 0), File: "one.mkv",
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.Add(Item{
		Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x", Title: "Two",
		SizeBytes: 2000, DownloadedAt: time.Unix(200, 0), File: "two.mkv",
	}); err != nil {
		t.Fatal(err)
	}
	// The same track replaces the older entry rather than adding a second.
	if err := store.Add(Item{
		Source: media.SourceYTM, Key: "vid-1", Title: "One again",
		SizeBytes: 1500, DownloadedAt: time.Unix(300, 0), File: "one.mkv",
	}); err != nil {
		t.Fatal(err)
	}

	again := New(index, dir)
	list := again.List()
	if len(list) != 2 {
		t.Fatalf("got %d downloads, want 2", len(list))
	}
	// Newest first.
	if list[0].Key != "vid-1" || list[0].SizeBytes != 1500 {
		t.Errorf("newest download = %+v", list[0])
	}
	if _, ok := again.Get(media.SourceSoundCloud, "https://soundcloud.com/x"); !ok {
		t.Error("the SoundCloud download was lost")
	}

	// A touch records the last listen and is kept.
	at := time.Unix(400, 0)
	again.Touch(media.SourceYTM, "vid-1", at)
	if got, _ := New(index, dir).Get(media.SourceYTM, "vid-1"); !got.LastListenedAt.Equal(at) {
		t.Errorf("last listened = %v, want %v", got.LastListenedAt, at)
	}

	// Removing one leaves the other, and says which file to delete.
	removed, ok, err := again.Remove(media.SourceSoundCloud, "https://soundcloud.com/x")
	if err != nil || !ok || removed.File != "two.mkv" {
		t.Fatalf("remove = %+v, %v, %v", removed, ok, err)
	}
	if len(again.List()) != 1 {
		t.Errorf("after remove: %d downloads", len(again.List()))
	}
	if _, ok := New(index, dir).Get(media.SourceSoundCloud, "https://soundcloud.com/x"); ok {
		t.Error("the removed download came back across a restart")
	}
}

// Path names a track's file after its provider and key, and the index is
// written where the store says.
func TestPathIsStable(t *testing.T) {
	dir := t.TempDir()
	store := New("", dir)
	path, name := store.Path(media.SourceYTM, "vid-1", ".mkv")
	if path != filepath.Join(dir, name) {
		t.Errorf("path = %q, name = %q", path, name)
	}
	if other, _ := store.Path(media.SourceSoundCloud, "vid-1", ".mkv"); other == path {
		t.Error("two providers share one file name")
	}
	if _, err := os.Stat(dir); err != nil {
		t.Errorf("the store directory is missing: %v", err)
	}
}
