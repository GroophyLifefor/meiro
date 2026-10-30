package library

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/elianiva/meiro/media"
)

// TestPersistsAcrossReads writes a list and its track, then reads them back
// from a fresh store, the way the app does after a restart.
func TestPersistsAcrossReads(t *testing.T) {
	path := filepath.Join(t.TempDir(), "libraries.json")
	s := New(path)
	lib, err := s.Create("Road trip")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	added, err := s.AddItem(lib.ID, Item{Source: media.SourceYTM, Key: "vid-1", Title: "Song", Artist: "Someone", DurationMS: 180_000})
	if err != nil || !added {
		t.Fatalf("add: added=%t err=%v", added, err)
	}

	again := New(path)
	lists := again.List()
	if len(lists) != 1 || lists[0].Name != "Road trip" || len(lists[0].Items) != 1 {
		t.Fatalf("after reading back: %+v", lists)
	}
	if lists[0].Items[0].Key != "vid-1" || lists[0].Items[0].Title != "Song" {
		t.Fatalf("the track did not survive: %+v", lists[0].Items[0])
	}
}

// TestRefusesTheSameTrackTwice adds the same provider and key twice.
func TestRefusesTheSameTrackTwice(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	item := Item{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/a/b", URL: "https://soundcloud.com/a/b", Title: "Cloud Song"}
	if added, _ := s.AddItem(lib.ID, item); !added {
		t.Fatal("the first add was refused")
	}
	if added, _ := s.AddItem(lib.ID, item); added {
		t.Fatal("the second, identical add was accepted")
	}
	got, _ := s.Get(lib.ID)
	if len(got.Items) != 1 {
		t.Fatalf("the list holds %d tracks, want 1", len(got.Items))
	}
}

// TestRefusesTheSameRecordingFromAnotherProvider adds a YouTube Music track
// and then a SoundCloud track that is the same recording, folded by name and
// within the length tolerance.
func TestRefusesTheSameRecordingFromAnotherProvider(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	ytm := Item{Source: media.SourceYTM, Key: "vid-1", Title: "Just a Moment", Artist: "Yorushika", DurationMS: 242_000}
	cloud := Item{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/y/just-a-moment", URL: "https://soundcloud.com/y/just-a-moment", Title: "Just a Moment (Official Video)", Artist: "yorushika", DurationMS: 243_000}
	if added, _ := s.AddItem(lib.ID, ytm); !added {
		t.Fatal("the YouTube Music track was refused")
	}
	if added, _ := s.AddItem(lib.ID, cloud); added {
		t.Fatal("the SoundCloud copy of the recording was accepted")
	}
	got, _ := s.Get(lib.ID)
	if len(got.Items) != 1 {
		t.Fatalf("the list holds %d tracks, want 1", len(got.Items))
	}
}

// TestOrderMoves moves a track within a list.
func TestOrderMoves(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	for _, key := range []string{"a", "b", "c"} {
		if _, err := s.AddItem(lib.ID, Item{Source: media.SourceYTM, Key: key, Title: key}); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.MoveItem(lib.ID, 0, 2); err != nil {
		t.Fatalf("move: %v", err)
	}
	got, _ := s.Get(lib.ID)
	want := []string{"b", "c", "a"}
	for i := range want {
		if got.Items[i].Key != want[i] {
			t.Fatalf("order after move = %v, want %v", keys(got.Items), want)
		}
	}
}

// TestUnreadableFileStartsEmpty reads a file that is not JSON.
func TestUnreadableFileStartsEmpty(t *testing.T) {
	path := filepath.Join(t.TempDir(), "libraries.json")
	if err := os.WriteFile(path, []byte("not json at all"), 0o600); err != nil {
		t.Fatal(err)
	}
	s := New(path)
	if lists := s.List(); len(lists) != 0 {
		t.Fatalf("a damaged file left %d lists, want none", len(lists))
	}
	// The store still works, and replaces the damaged file.
	if _, err := s.Create("Fresh"); err != nil {
		t.Fatalf("create after a damaged file: %v", err)
	}
	if lists := New(path).List(); len(lists) != 1 {
		t.Fatalf("the fresh list did not save over the damaged file: %+v", lists)
	}
}

// TestRemoveItem takes a track out.
func TestRemoveItem(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	_, _ = s.AddItem(lib.ID, Item{Source: media.SourceYTM, Key: "a", Title: "A"})
	_, _ = s.AddItem(lib.ID, Item{Source: media.SourceYTM, Key: "b", Title: "B"})
	if err := s.RemoveItem(lib.ID, "a"); err != nil {
		t.Fatalf("remove: %v", err)
	}
	got, _ := s.Get(lib.ID)
	if len(got.Items) != 1 || got.Items[0].Key != "b" {
		t.Fatalf("after remove: %+v", got.Items)
	}
	if err := s.RemoveItem(lib.ID, "a"); err == nil {
		t.Fatal("removing a track that is not there succeeded")
	}
}

func keys(items []Item) []string {
	out := make([]string, len(items))
	for i, item := range items {
		out[i] = item.Key
	}
	return out
}

// A link to MusicBrainz is kept with the track, a matched one with its MBID,
// and taking it away or marking the track other leaves no MBID behind. An
// artist's link reaches every track that credits the artist, in every list.
func TestLinksAreKeptWithTheTrack(t *testing.T) {
	store := New("")
	one, _ := store.Create("One")
	two, _ := store.Create("Two")
	for _, add := range []struct {
		list string
		item Item
	}{
		{one.ID, Item{Source: media.SourceYTM, Key: "a", Title: "Song A", Artist: "Daft Punk"}},
		{one.ID, Item{Source: media.SourceYTM, Key: "b", Title: "Song B", Artist: "Someone Else"}},
		{two.ID, Item{Source: media.SourceSoundCloud, Key: "c", Title: "Song C", Artist: "daft punk"}},
	} {
		if _, err := store.AddItem(add.list, add.item); err != nil {
			t.Fatal(err)
		}
	}
	if ok, err := store.SetLink(one.ID, "a", Matched, "rec-1"); err != nil || !ok {
		t.Fatalf("SetLink = %v, %v", ok, err)
	}
	got, _ := store.Get(one.ID)
	if got.Items[0].State != Matched || got.Items[0].MBID != "rec-1" {
		t.Errorf("the link was not kept: %+v", got.Items[0])
	}
	if _, err := store.SetLink(one.ID, "a", Other, "ignored"); err != nil {
		t.Fatal(err)
	}
	got, _ = store.Get(one.ID)
	if got.Items[0].State != Other || got.Items[0].MBID != "" {
		t.Errorf("an other mark kept an MBID: %+v", got.Items[0])
	}
	if ok, _ := store.SetLink(one.ID, "missing", Matched, "x"); ok {
		t.Error("SetLink reported a track the list does not hold")
	}

	n, err := store.SetArtistLink("Daft Punk", Matched, "artist-1")
	if err != nil || n != 2 {
		t.Fatalf("SetArtistLink = %d, %v; want 2 tracks across both lists", n, err)
	}
	first, _ := store.Get(one.ID)
	second, _ := store.Get(two.ID)
	if first.Items[0].ArtistMBID != "artist-1" || second.Items[0].ArtistMBID != "artist-1" || first.Items[1].ArtistMBID != "" {
		t.Errorf("the artist link reached the wrong tracks: %+v %+v", first.Items, second.Items)
	}
}

// AddItems adds the tracks a list lacks, once each, and writes the file once.
func TestAddItemsSkipsWhatTheListHolds(t *testing.T) {
	store := New("")
	lib, _ := store.Create("Copy")
	if _, err := store.AddItem(lib.ID, Item{Source: media.SourceYTM, Key: "a", Title: "Song A", Artist: "X", DurationMS: 180_000}); err != nil {
		t.Fatal(err)
	}
	added, err := store.AddItems(lib.ID, []Item{
		{Source: media.SourceYTM, Key: "a", Title: "Song A", Artist: "X", DurationMS: 180_000},
		{Source: media.SourceYTM, Key: "b", Title: "Song B", Artist: "X", DurationMS: 200_000},
		{Source: media.SourceSoundCloud, Key: "b2", Title: "Song B", Artist: "X", DurationMS: 200_000},
		{Source: media.SourceYTM, Key: "c", Title: "Song C", Artist: "Y", DurationMS: 150_000},
	})
	if err != nil || added != 2 {
		t.Fatalf("AddItems = %d, %v; want 2 added, the held and the repeated one left out", added, err)
	}
	got, _ := store.Get(lib.ID)
	if len(got.Items) != 3 {
		t.Errorf("the list holds %d tracks, want 3: %+v", len(got.Items), got.Items)
	}
	if _, err := store.AddItems("missing", nil); err == nil {
		t.Error("AddItems to a list that is not there did not fail")
	}
}

// ago is a moment a number of days before now.
func ago(days int) time.Time { return time.Now().Add(-time.Duration(days) * 24 * time.Hour) }

func addTracks(t *testing.T, s *Store, id string, keys ...string) {
	t.Helper()
	for _, key := range keys {
		if _, err := s.AddItem(id, Item{Source: media.SourceYTM, Key: key, Title: key, Artist: "Artist"}); err != nil {
			t.Fatal(err)
		}
	}
}

// Every change stamps the list, and the tracks it touched, with the clock.
func TestChangesStampTheListAndTheTracksTheyTouch(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	addTracks(t, s, lib.ID, "a", "b", "c", "d")
	stamp := func() Library { got, _ := s.Get(lib.ID); return got }
	// Make the stamps old so a change shows as the clock's.
	s.mu.Lock()
	old := ago(10)
	s.libs[0].UpdatedAt = old
	for j := range s.libs[0].Items {
		s.libs[0].Items[j].UpdatedAt = old
	}
	s.mu.Unlock()

	if err := s.Rename(lib.ID, "Renamed"); err != nil {
		t.Fatal(err)
	}
	if !stamp().UpdatedAt.After(old) {
		t.Error("a rename did not advance the list")
	}
	s.mu.Lock()
	s.libs[0].UpdatedAt = old
	s.mu.Unlock()
	if err := s.SetCover(lib.ID, "https://cover"); err != nil {
		t.Fatal(err)
	}
	if !stamp().UpdatedAt.After(old) {
		t.Error("a new cover did not advance the list")
	}

	// Moving b to the end changes the places of b, c and d, not a's.
	if err := s.MoveItem(lib.ID, 1, 3); err != nil {
		t.Fatal(err)
	}
	got := stamp()
	for _, item := range got.Items {
		moved := item.UpdatedAt.After(old)
		if moved != (item.Key != "a") {
			t.Errorf("after the move, %s moved = %t", item.Key, moved)
		}
	}
}

// A track taken out and a list taken away are remembered, and a track put back
// is not.
func TestRemovalsLeaveTombstonesAndReAddingTakesThemBack(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	addTracks(t, s, lib.ID, "a", "b")
	if err := s.RemoveItem(lib.ID, "a"); err != nil {
		t.Fatal(err)
	}
	got, _ := s.Get(lib.ID)
	if len(got.Removed) != 1 || got.Removed[0].Key != "a" || got.Removed[0].Source != media.SourceYTM || got.Removed[0].Title != "a" || got.Removed[0].RemovedAt.IsZero() {
		t.Fatalf("tombstones = %+v", got.Removed)
	}
	addTracks(t, s, lib.ID, "a")
	if got, _ = s.Get(lib.ID); len(got.Removed) != 0 {
		t.Errorf("a track put back still has its tombstone: %+v", got.Removed)
	}

	if err := s.Delete(lib.ID); err != nil {
		t.Fatal(err)
	}
	deleted := s.Deleted()
	if len(deleted) != 1 || deleted[0].ID != lib.ID || deleted[0].Name != "List" || deleted[0].DeletedAt.IsZero() {
		t.Errorf("deleted = %+v", deleted)
	}
}

// Switching a track to another provider removes it as the server knew it and
// adds it as it is; switching back lets the first removal go.
func TestSetSourceTombstonesTheOldReference(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	ytm := media.SourceRef{Source: media.SourceYTM, ID: "vid"}
	cloud := media.SourceRef{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/x/y"}
	_, _ = s.AddItem(lib.ID, Item{Source: media.SourceYTM, Key: "vid", Title: "Song", Sources: []media.SourceRef{ytm, cloud}})
	before, _ := s.Get(lib.ID)

	if ok, err := s.SetSource(lib.ID, "vid", media.SourceSoundCloud); err != nil || !ok {
		t.Fatalf("set source: %t %v", ok, err)
	}
	got, _ := s.Get(lib.ID)
	if len(got.Removed) != 1 || got.Removed[0].Source != media.SourceYTM || got.Removed[0].Key != "vid" {
		t.Fatalf("tombstones = %+v", got.Removed)
	}
	if source, id := got.Items[0].ActiveRef(); source != media.SourceSoundCloud || id != cloud.URL {
		t.Errorf("active ref = %s %s", source, id)
	}
	if got.Items[0].UpdatedAt.Before(before.Items[0].UpdatedAt) || !got.Items[0].UpdatedAt.After(before.Items[0].AddedAt.Add(-time.Second)) {
		t.Errorf("the switched track was not stamped")
	}

	if _, err := s.SetSource(lib.ID, "vid", media.SourceYTM); err != nil {
		t.Fatal(err)
	}
	got, _ = s.Get(lib.ID)
	if len(got.Removed) != 1 || got.Removed[0].Source != media.SourceSoundCloud {
		t.Errorf("after switching back, tombstones = %+v", got.Removed)
	}
}

// Replacing a list's tracks removes the ones that are gone and stamps the rest.
func TestReplaceItemsTombstonesWhatWentAndStampsWhatStays(t *testing.T) {
	s := New("")
	lib, _ := s.Create("Daily mix")
	addTracks(t, s, lib.ID, "a", "b")
	if err := s.ReplaceItems(lib.ID, []Item{{Source: media.SourceYTM, Key: "b", Title: "b2", Artist: "Other"}, {Source: media.SourceYTM, Key: "c", Title: "c", Artist: "Other"}}); err != nil {
		t.Fatal(err)
	}
	got, _ := s.Get(lib.ID)
	if len(got.Removed) != 1 || got.Removed[0].Key != "a" {
		t.Errorf("tombstones = %+v", got.Removed)
	}
	for _, item := range got.Items {
		if item.UpdatedAt.IsZero() || item.UpdatedAt.Before(got.UpdatedAt) {
			t.Errorf("%s was not stamped with the list's time: %v", item.Key, item.UpdatedAt)
		}
	}
}

// A file written before the stamps and tombstones existed loads, reading the
// times from what it has; removals older than ninety days are let go.
func TestOldFilesLoadAndOldTombstonesArePruned(t *testing.T) {
	path := filepath.Join(t.TempDir(), "libraries.json")
	added, created := ago(5).UTC().Format(time.RFC3339), ago(9).UTC().Format(time.RFC3339)
	old := `{"libraries":[{"id":"old","name":"Old","createdAt":"` + created + `","items":[{"source":"ytm","key":"k","addedAt":"` + added + `"}]}]}`
	if err := os.WriteFile(path, []byte(old), 0o600); err != nil {
		t.Fatal(err)
	}
	lib := New(path).List()[0]
	if lib.Items[0].UpdatedAt.IsZero() || lib.Items[0].UpdatedAt.Format(time.RFC3339) != added {
		t.Errorf("an old track reads as %v, want its AddedAt", lib.Items[0].UpdatedAt)
	}
	if lib.UpdatedAt.Format(time.RFC3339) != created || lib.ServerID != "" {
		t.Errorf("an old list reads as %v (%q), want its CreatedAt", lib.UpdatedAt, lib.ServerID)
	}

	s := New(path)
	addTracks(t, s, "old", "fresh", "stale")
	_ = s.RemoveItem("old", "fresh")
	_ = s.RemoveItem("old", "stale")
	_ = s.Delete("old")
	s.mu.Lock()
	s.deleted = append(s.deleted, DeletedList{ID: "ancient", DeletedAt: ago(91)})
	s.mu.Unlock()
	if err := s.Delete("none"); err == nil {
		t.Fatal("deleting a list that is not there succeeded")
	}
	// Age one removal by hand, on a list that is kept.
	keep, _ := s.Create("Keep")
	addTracks(t, s, keep.ID, "fresh", "stale")
	_ = s.RemoveItem(keep.ID, "fresh")
	_ = s.RemoveItem(keep.ID, "stale")
	s.mu.Lock()
	s.libs[0].Removed[1].RemovedAt = ago(91)
	s.mu.Unlock()
	if err := s.SetCover(keep.ID, "c"); err != nil {
		t.Fatal(err)
	}

	again := New(path)
	got, _ := again.Get(keep.ID)
	if len(got.Removed) != 1 || got.Removed[0].Key != "fresh" {
		t.Errorf("after loading, tombstones = %+v", got.Removed)
	}
	if deleted := again.Deleted(); len(deleted) != 1 || deleted[0].ID != "old" {
		t.Errorf("after loading, deleted = %+v", deleted)
	}
}

func remote(key string, at time.Time) Item {
	return Item{Source: media.SourceYTM, Key: key, Title: key, AddedAt: at, UpdatedAt: at}
}

// What the server reports is applied by time, leaves no tombstone, and puts the
// tracks it placed in its order.
func TestApplyRemoteItems(t *testing.T) {
	s := New("")
	lib, _ := s.Create("List")
	addTracks(t, s, lib.ID, "a", "b", "c")
	_ = s.RemoveItem(lib.ID, "c")
	now := time.Now()
	future, past := now.Add(time.Hour), now.Add(-time.Hour)

	n, err := s.ApplyRemoteItems(lib.ID, []RemoteItem{
		{Item: remote("a", future), Deleted: true},            // newer deletion: removed
		{Item: remote("b", future), Position: 5},              // newer: takes its place
		{Item: remote("d", future), Position: 0},              // unknown: added in front
		{Item: remote("c", past)},                             // older than this device's removal: stays out
		{Item: remote("e", past), Position: 1, Deleted: true}, // deletion of a track not here: nothing
	})
	if err != nil {
		t.Fatal(err)
	}
	got, _ := s.Get(lib.ID)
	if n != 3 || len(got.Items) != 2 || got.Items[0].Key != "d" || got.Items[1].Key != "b" {
		t.Errorf("applied %d, items = %v", n, keys(got.Items))
	}
	if len(got.Removed) != 1 || got.Removed[0].Key != "c" {
		t.Errorf("tombstones = %+v, want only this device's own removal", got.Removed)
	}

	// A deletion older than the track's own stamp does not remove it, and a
	// newer live record of a track removed here brings it back.
	n, _ = s.ApplyRemoteItems(lib.ID, []RemoteItem{{Item: remote("b", past), Deleted: true}, {Item: remote("c", future), Position: 9}})
	got, _ = s.Get(lib.ID)
	if n != 1 || len(got.Items) != 3 || got.Items[2].Key != "c" || len(got.Removed) != 0 {
		t.Errorf("applied %d, items = %v, tombstones = %+v", n, keys(got.Items), got.Removed)
	}
}
