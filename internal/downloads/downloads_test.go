package downloads

import (
	"fmt"
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

// A removed download is kept as a removal to tell the server, named as the
// server knew the track, and survives a restart. Downloading the track again
// cancels it.
func TestARemovalIsKeptUntilTheTrackIsDownloadedAgain(t *testing.T) {
	index := filepath.Join(t.TempDir(), "downloads.json")
	store := New(index, t.TempDir())
	cloud := media.SourceRef{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/x/cloud"}
	for _, item := range []Item{
		{Source: media.SourceYTM, Key: "vid-1", Title: "One", Artist: "A"},
		{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y", URL: "https://soundcloud.com/x/y", Title: "Two"},
		// Kept from YouTube Music, played from SoundCloud now.
		{Source: media.SourceYTM, Key: "vid-3", Title: "Three", Sources: []media.SourceRef{{Source: media.SourceYTM, ID: "vid-3"}, cloud}, Active: media.SourceSoundCloud},
	} {
		if err := store.Add(item); err != nil {
			t.Fatal(err)
		}
	}
	if len(store.Removed()) != 0 {
		t.Fatalf("removals before any removal: %+v", store.Removed())
	}

	before := time.Now()
	for _, gone := range []Item{
		{Source: media.SourceYTM, Key: "vid-1"},
		{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y"},
		{Source: media.SourceYTM, Key: "vid-3"},
	} {
		if _, ok, err := store.Remove(gone.Source, gone.Key); err != nil || !ok {
			t.Fatalf("removing %s: %v %v", gone.Key, ok, err)
		}
	}
	// Removing what is not there tells nothing.
	if _, ok, _ := store.Remove(media.SourceYTM, "nope"); ok || len(store.Removed()) != 3 {
		t.Errorf("a removal of nothing was kept: %+v", store.Removed())
	}

	again := New(index, t.TempDir()).Removed()
	if len(again) != 3 {
		t.Fatalf("after a restart: %+v", again)
	}
	want := []struct {
		source media.Source
		key    string
	}{
		{media.SourceYTM, "vid-1"},
		{media.SourceSoundCloud, "https://soundcloud.com/x/y"},
		{media.SourceSoundCloud, "https://soundcloud.com/x/cloud"},
	}
	for i, w := range want {
		got := again[i]
		if got.Source != w.source || got.Key != w.key || got.RemovedAt.Before(before) {
			t.Errorf("removal %d = %+v, want %s %s removed after %v", i, got, w.source, w.key, before)
		}
	}
	if again[0].Title != "One" || again[1].URL != "https://soundcloud.com/x/y" {
		t.Errorf("the removals lost what names the track: %+v", again)
	}

	// The track downloaded again is no longer a removal.
	if err := store.Add(Item{Source: media.SourceYTM, Key: "vid-1", Title: "One"}); err != nil {
		t.Fatal(err)
	}
	if got := store.Removed(); len(got) != 2 || got[0].Key == "vid-1" {
		t.Errorf("a track downloaded again is still a removal: %+v", got)
	}
}

// A removal is forgotten after 90 days, whether it is read from the index or
// goes stale while the app runs.
func TestARemovalIsForgottenAfterNinetyDays(t *testing.T) {
	index := filepath.Join(t.TempDir(), "downloads.json")
	now := time.Now()
	data := fmt.Sprintf(`{"items":[],"removed":[`+
		`{"source":"ytm","key":"old","removedAt":%q},`+
		`{"source":"ytm","key":"recent","removedAt":%q}]}`,
		now.Add(-91*24*time.Hour).Format(time.RFC3339), now.Add(-89*24*time.Hour).Format(time.RFC3339))
	if err := os.WriteFile(index, []byte(data), 0o600); err != nil {
		t.Fatal(err)
	}
	store := New(index, t.TempDir())
	got := store.Removed()
	if len(got) != 1 || got[0].Key != "recent" {
		t.Fatalf("removals = %+v, want only the recent one", got)
	}

	// One that goes stale after the store was opened goes too.
	store.mu.Lock()
	store.removed[0].RemovedAt = now.Add(-91 * 24 * time.Hour)
	store.mu.Unlock()
	if got := store.Removed(); len(got) != 0 {
		t.Errorf("a stale removal was kept: %+v", got)
	}
}
