package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/elianiva/meiro/internal/history"
	"github.com/elianiva/meiro/internal/library"
	meirosync "github.com/elianiva/meiro/internal/sync"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// serverState is a Meiro server that keeps what it is sent, as the real one
// does: per record the later updated_at wins, a deletion is a tombstone an
// older live push cannot undo, a push that changes nothing does not move the
// order of changes, and a read of the changes gives everything after a point.
type serverState struct {
	mu    sync.Mutex
	seq   int64
	libs  map[string]*serverRecord[meirosync.Library]
	items map[string]*serverRecord[meirosync.ChangedItem]
	// page is how many records one read of the changes gives, to make a read
	// need several; asked are the since values the reads came with.
	page  int
	asked []int64
	// plays are the account's plays in the order they arrived, which is their
	// seq, and askedPlays the since values the reads of them came with.
	plays      []meirosync.PlayedEvent
	askedPlays []int64
}

type serverRecord[T any] struct {
	value T
	seq   int64
}

func newServerState() *serverState {
	return &serverState{
		libs:  map[string]*serverRecord[meirosync.Library]{},
		items: map[string]*serverRecord[meirosync.ChangedItem]{},
		page:  2,
	}
}

func (s *serverState) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	switch {
	case strings.HasSuffix(r.URL.Path, "/sync/libraries"):
		var body struct {
			Libraries []meirosync.Library `json:"libraries"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		for _, lib := range body.Libraries {
			held := s.libs[lib.ID]
			if held != nil && lib.UpdatedAt.Before(held.value.UpdatedAt) {
				continue
			}
			if held != nil && held.value.Name == lib.Name && held.value.Cover == lib.Cover && held.value.Deleted == lib.Deleted && held.value.UpdatedAt.Equal(lib.UpdatedAt) {
				continue
			}
			s.seq++
			s.libs[lib.ID] = &serverRecord[meirosync.Library]{value: lib, seq: s.seq}
		}
		_, _ = w.Write([]byte(`{"accepted":[]}`))
	case strings.HasSuffix(r.URL.Path, "/sync/library_items"):
		var body meirosync.LibraryItems
		_ = json.NewDecoder(r.Body).Decode(&body)
		if lib := s.libs[body.LibraryID]; lib == nil || lib.value.Deleted {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"library not found"}`))
			return
		}
		for _, item := range body.Items {
			key := body.LibraryID + "|" + item.Track.Provider + "|" + item.Track.ProviderTrackID
			held := s.items[key]
			if held != nil && item.UpdatedAt.Before(held.value.UpdatedAt) {
				continue
			}
			if held != nil && held.value.Position == item.Position && held.value.Deleted == item.Deleted && held.value.UpdatedAt.Equal(item.UpdatedAt) && held.value.Track == item.Track {
				continue
			}
			s.seq++
			s.items[key] = &serverRecord[meirosync.ChangedItem]{value: meirosync.ChangedItem{LibraryID: body.LibraryID, LibraryItem: item}, seq: s.seq}
		}
		_, _ = w.Write([]byte(`{"accepted":[]}`))
	case strings.HasSuffix(r.URL.Path, "/sync/changes"):
		since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
		s.asked = append(s.asked, since)
		_ = json.NewEncoder(w).Encode(s.changesAfter(since))
	case strings.HasSuffix(r.URL.Path, "/api/v1/links"):
		_, _ = w.Write([]byte(`{}`))
	case strings.HasSuffix(r.URL.Path, "/sync/events"):
		var body struct {
			Events []meirosync.Event `json:"events"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		out := struct {
			Accepted   []string `json:"accepted"`
			Duplicates []string `json:"duplicates"`
		}{[]string{}, []string{}}
		for _, e := range body.Events {
			if slices.ContainsFunc(s.plays, func(held meirosync.PlayedEvent) bool { return held.ClientEventID == e.ClientEventID }) {
				out.Duplicates = append(out.Duplicates, e.ClientEventID)
				continue
			}
			s.plays = append(s.plays, meirosync.PlayedEvent{Event: e, Seq: int64(len(s.plays) + 1)})
			out.Accepted = append(out.Accepted, e.ClientEventID)
		}
		_ = json.NewEncoder(w).Encode(out)
	case strings.HasSuffix(r.URL.Path, "/api/v1/events"):
		since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
		s.askedPlays = append(s.askedPlays, since)
		out := meirosync.Plays{Events: []meirosync.PlayedEvent{}, Next: since}
		for _, e := range s.plays {
			if e.Seq > since {
				out.Events = append(out.Events, e)
			}
		}
		if len(out.Events) > s.page {
			out.Events, out.More = out.Events[:s.page], true
		}
		if n := len(out.Events); n > 0 {
			out.Next = out.Events[n-1].Seq
		}
		_ = json.NewEncoder(w).Encode(out)
	default:
		_, _ = w.Write([]byte(`{"accepted":[],"duplicates":[]}`))
	}
}

// changesAfter is the first page of the records changed after since, in order.
func (s *serverState) changesAfter(since int64) meirosync.Changes {
	type entry struct {
		seq  int64
		lib  *meirosync.Library
		item *meirosync.ChangedItem
	}
	var all []entry
	for _, rec := range s.libs {
		if rec.seq > since {
			all = append(all, entry{seq: rec.seq, lib: &rec.value})
		}
	}
	for _, rec := range s.items {
		if rec.seq > since {
			all = append(all, entry{seq: rec.seq, item: &rec.value})
		}
	}
	slices.SortFunc(all, func(a, b entry) int { return int(a.seq - b.seq) })
	out := meirosync.Changes{Libraries: []meirosync.ChangedLibrary{}, Items: []meirosync.ChangedItem{}, Next: since}
	if len(all) > s.page {
		all, out.More = all[:s.page], true
	}
	for _, e := range all {
		out.Next = e.seq
		if e.lib != nil {
			out.Libraries = append(out.Libraries, meirosync.ChangedLibrary{Library: *e.lib, Seq: e.seq})
		} else {
			item := *e.item
			item.Seq = e.seq
			out.Items = append(out.Items, item)
		}
	}
	return out
}

func (s *serverState) item(libraryID, provider, id string) (meirosync.ChangedItem, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.items[libraryID+"|"+provider+"|"+id]
	if rec == nil {
		return meirosync.ChangedItem{}, false
	}
	return rec.value, true
}

func (s *serverState) library(id string) (meirosync.Library, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.libs[id]
	if rec == nil {
		return meirosync.Library{}, false
	}
	return rec.value, true
}

// settled drives an app until no push and no read of changes is on its way.
func settled(t *testing.T, a *app) {
	t.Helper()
	pollUntil(t, a, "the sync to settle", func() bool { return !a.sync.push.busy() && !a.sync.changes.busy() && !a.sync.events.busy() })
}

// syncBoth sends what the app has and reads what the server has, until both
// are done.
func syncBoth(t *testing.T, a *app) {
	t.Helper()
	a.syncNow()
	a.pullChanges()
	a.pullEvents()
	settled(t, a)
}

func listKeys(lib library.Library) []string {
	var out []string
	for _, item := range lib.Items {
		out = append(out, item.Key)
	}
	return out
}

func addSong(t *testing.T, a *app, listID, key string) {
	t.Helper()
	song := youtube.MusicItem{VideoID: key, Title: "Song " + key, Artist: "Artist", Subtitle: "Artist", Kind: media.KindTrack, Duration: "3:00", Thumbnail: "https://pic/" + key}
	added, err := a.libraries.AddItem(listID, libraryItem(song, "https://album/"+key))
	if err != nil || !added {
		t.Fatalf("adding %s: %t %v", key, added, err)
	}
}

// The reference a library item is matched by is the one the sync sends, for
// every shape of item the app keeps.
func TestActiveRefIsWhatSyncTrackSends(t *testing.T) {
	ytm := media.SourceRef{Source: media.SourceYTM, ID: "vid"}
	cloud := media.SourceRef{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/x/y"}
	items := []library.Item{
		{Source: media.SourceYTM, Key: "vid"},
		{Key: "legacy"},
		{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/a/b", URL: "https://soundcloud.com/a/b"},
		{Source: media.SourceYTM, Key: "vid", Sources: []media.SourceRef{ytm, cloud}},
		{Source: media.SourceSoundCloud, Key: "vid", Sources: []media.SourceRef{ytm, cloud}},
		{Source: media.SourceYTM, Key: "vid", Sources: []media.SourceRef{cloud}},
	}
	for _, item := range items {
		track := syncTrack(musicItem(item), "T", "A", 0)
		source, id := item.ActiveRef()
		if string(source) != track.Provider || id != track.ProviderTrackID {
			t.Errorf("%+v: active ref = %s %q, sync sends %s %q", item, source, id, track.Provider, track.ProviderTrackID)
		}
	}
}

// The push carries the time each change was made, covers and thumbnails, a
// removed track as deleted at the time it was removed, and a deleted list as
// deleted with the server's identity of the list.
func TestPushSendsRemovalsDeletionsCoversAndThumbnails(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a := signedInTo(t, srv.URL)

	kept, _ := a.libraries.Create("Kept")
	addSong(t, a, kept.ID, "one")
	addSong(t, a, kept.ID, "two")
	if err := a.libraries.SetCover(kept.ID, "https://cover/kept"); err != nil {
		t.Fatal(err)
	}
	gone, _ := a.libraries.Create("Gone")
	// A list that came from the server keeps the server's id.
	remote, _ := a.libraries.AddRemote("11111111-1111-4111-8111-111111111111", "Remote", "", time.Now())

	syncBoth(t, a)
	if err := a.libraries.RemoveItem(kept.ID, "two"); err != nil {
		t.Fatal(err)
	}
	removedAt := a.libraries.Deleted()
	if len(removedAt) != 0 {
		t.Fatalf("deleted before deleting: %+v", removedAt)
	}
	if err := a.libraries.Delete(gone.ID); err != nil {
		t.Fatal(err)
	}
	snap := a.syncSnapshot()
	var sawGone bool
	for _, lib := range snap.libraries {
		if lib.ID == meirosync.LibraryID(gone.ID) {
			sawGone = lib.Deleted && !lib.UpdatedAt.IsZero() && lib.Kind == "custom"
		}
	}
	if !sawGone {
		t.Errorf("the deleted list is not in the push as deleted: %+v", snap.libraries)
	}
	syncBoth(t, a)

	id := meirosync.LibraryID(kept.ID)
	if lib, _ := server.library(id); lib.Cover != "https://cover/kept" || lib.Deleted {
		t.Errorf("kept list on the server = %+v", lib)
	}
	one, _ := server.item(id, "ytm", "one")
	if one.Track.Thumbnail != "https://pic/one" || one.Track.AlbumThumbnail != "https://album/one" || one.Deleted {
		t.Errorf("track on the server = %+v", one)
	}
	two, _ := server.item(id, "ytm", "two")
	kept, _ = a.libraries.Get(kept.ID)
	if !two.Deleted || !two.UpdatedAt.Equal(kept.Removed[0].RemovedAt) {
		t.Errorf("removed track on the server = %+v, tombstone %+v", two, kept.Removed)
	}
	if lib, ok := server.library(meirosync.LibraryID(gone.ID)); !ok || !lib.Deleted {
		t.Errorf("deleted list on the server = %+v %t", lib, ok)
	}
	if lib, ok := server.library(remote.ServerID); !ok || lib.Name != "Remote" {
		t.Errorf("a list from the server is not pushed under its own id: %+v %t", lib, ok)
	}
}

// What the server sends is merged by time: a new list appears with the
// server's id, a newer rename wins and an older one loses, a deletion removes a
// track without echoing, a track older than this device's removal stays out,
// places are applied, and the cursor is kept and used.
func TestPullMergesWhatTheServerSends(t *testing.T) {
	now := time.Now().UTC()
	hour := time.Hour
	var mu sync.Mutex
	var asked []int64
	var pushes int

	a := signedInToServer(t, nil)
	// The server is built after the app because its answer names the app's
	// lists; a handler that reads them lazily does.
	var listA, listB library.Library
	pages := func() map[int64]meirosync.Changes {
		idA, idB := meirosync.LibraryID(listA.ID), meirosync.LibraryID(listB.ID)
		track := func(id string) meirosync.Track {
			return meirosync.Track{Provider: "ytm", ProviderTrackID: id, Title: id, Artist: "R", DurationMS: 1000, Thumbnail: "https://t/" + id}
		}
		item := func(lib, id string, pos int, at time.Time, deleted bool) meirosync.ChangedItem {
			return meirosync.ChangedItem{LibraryID: lib, LibraryItem: meirosync.LibraryItem{Track: track(id), Position: pos, UpdatedAt: at, Deleted: deleted}}
		}
		return map[int64]meirosync.Changes{
			0: {
				Libraries: []meirosync.ChangedLibrary{
					{Library: meirosync.Library{ID: "remote-new", Kind: "custom", Name: "From phone", Cover: "https://cover/phone", UpdatedAt: now}},
					{Library: meirosync.Library{ID: idA, Kind: "custom", Name: "Renamed remotely", UpdatedAt: now.Add(hour)}},
					{Library: meirosync.Library{ID: idB, Kind: "custom", Name: "Stale name", UpdatedAt: now.Add(-hour)}},
					{Library: meirosync.Library{ID: "someone-elses", Kind: "ytm_playlist", Name: "Not ours", UpdatedAt: now}},
				},
				Items: []meirosync.ChangedItem{
					item("remote-new", "p1", 1, now, false),
				},
				Next: 3, More: true,
			},
			3: {
				Items: []meirosync.ChangedItem{
					item("remote-new", "p0", 0, now, false),
					item(idA, "gone", 0, now.Add(hour), true),
					item(idA, "late", 0, now.Add(hour), false),
					item(idA, "removed-here", 5, now.Add(-hour), false),
					item(idB, "b0", 1, now.Add(hour), false),
				},
				Next: 8,
			},
		}
	}
	var served map[int64]meirosync.Changes
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if !strings.HasSuffix(r.URL.Path, "/sync/changes") {
			if strings.HasSuffix(r.URL.Path, "/api/v1/events") {
				_, _ = w.Write([]byte(`{"events":[],"next":0,"more":false}`))
				return
			}
			mu.Lock()
			pushes++
			mu.Unlock()
			_, _ = w.Write([]byte(`{"accepted":[],"duplicates":[]}`))
			return
		}
		since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
		mu.Lock()
		asked = append(asked, since)
		page, ok := served[since]
		mu.Unlock()
		if !ok {
			page = meirosync.Changes{Next: since}
		}
		_ = json.NewEncoder(w).Encode(page)
	}))
	t.Cleanup(server.Close)
	a.settings.SyncURL = server.URL

	listA, _ = a.libraries.Create("Local A")
	listB, _ = a.libraries.Create("Local B")
	// Both were made long ago, so a remote record's time decides.
	for _, id := range []string{listA.ID, listB.ID} {
		_ = a.libraries.ApplyRemoteList(id, map[string]string{listA.ID: "Local A", listB.ID: "Local B"}[id], "", now)
	}
	addSong(t, a, listA.ID, "gone")
	addSong(t, a, listA.ID, "removed-here")
	addSong(t, a, listB.ID, "b1")
	addSong(t, a, listB.ID, "b3")
	if err := a.libraries.RemoveItem(listA.ID, "removed-here"); err != nil {
		t.Fatal(err)
	}
	// The items on this device were made a moment ago.
	served = pages()

	a.pullChanges()
	settled(t, a)

	if got := a.settings.SyncCursor; got != 8 || a.settings.SyncCursorURL != server.URL || a.settings.SyncCursorEmail != "me@example.com" {
		t.Errorf("cursor = %d for %q %q", got, a.settings.SyncCursorURL, a.settings.SyncCursorEmail)
	}
	if !slices.Equal(asked, []int64{0, 3}) {
		t.Errorf("asked since %v, want 0 then 3", asked)
	}
	names := map[string]library.Library{}
	for _, lib := range a.libraries.List() {
		names[lib.Name] = lib
	}
	phone, ok := names["From phone"]
	if !ok || phone.ServerID != "remote-new" || phone.Cover != "https://cover/phone" {
		t.Fatalf("the list from the server: %+v (%v)", phone, ok)
	}
	if !slices.Equal(listKeys(phone), []string{"p0", "p1"}) || phone.Items[0].Thumbnail != "https://t/p0" || phone.Items[0].Source != media.SourceYTM {
		t.Errorf("its tracks, in the server's order: %+v", phone.Items)
	}
	if _, ok := names["Not ours"]; ok || len(names) != 3 {
		t.Errorf("lists = %v", names)
	}
	renamed, ok := names["Renamed remotely"]
	if !ok {
		t.Fatalf("a newer rename lost: %v", names)
	}
	if !slices.Equal(listKeys(renamed), []string{"late"}) {
		t.Errorf("tracks of the renamed list = %v, want the deleted one gone, the old removal not undone", listKeys(renamed))
	}
	if len(renamed.Removed) != 1 || renamed.Removed[0].Key != "removed-here" {
		t.Errorf("tombstones = %+v: the server's deletion must not leave one", renamed.Removed)
	}
	if lib, ok := names["Local B"]; !ok || !slices.Equal(listKeys(lib), []string{"b1", "b0", "b3"}) {
		t.Errorf("an older rename won, or the order is wrong: %v", names)
	}
	if pushes != 0 || a.sync.outbox.Pending() != 0 {
		t.Errorf("reading queued or sent something: %d pushes, %d waiting", pushes, a.sync.outbox.Pending())
	}

	// The next read starts where the last one ended.
	a.pullChanges()
	settled(t, a)
	if !slices.Equal(asked, []int64{0, 3, 8}) {
		t.Errorf("asked since %v, want the cursor reused", asked)
	}

	// Another server, or another account, starts from the beginning.
	a.settings.SyncURL += "/"
	a.pullChanges()
	settled(t, a)
	if got := asked[len(asked)-2:]; got[0] != 0 {
		t.Errorf("asked since %v after the address changed", asked)
	}
	a.signOutSync()
	if a.settings.SyncCursor != 0 || a.settings.SyncCursorURL != "" {
		t.Errorf("signing out kept the cursor: %d", a.settings.SyncCursor)
	}
}

// A device that synced before lists were read back takes its own lists as the
// truth on the first read: what the server has that it lacks is deleted there,
// not imported. A device that never synced imports.
func TestFirstReadOfAnOldInstallDeletesWhatItLacks(t *testing.T) {
	now := time.Now().UTC()
	build := func(t *testing.T, syncedBefore bool) (*app, *serverState) {
		server := newServerState()
		server.page = 100
		srv := httptest.NewServer(server)
		t.Cleanup(srv.Close)
		a := signedInTo(t, srv.URL)
		mine, _ := a.libraries.Create("Mine")
		addSong(t, a, mine.ID, "keep")
		// The server holds a stale list of its own, and a stale track in
		// the list that is here.
		track := func(id string) meirosync.Track {
			return meirosync.Track{Provider: "ytm", ProviderTrackID: id, Title: id}
		}
		server.libs["stale-list"] = &serverRecord[meirosync.Library]{seq: 1, value: meirosync.Library{ID: "stale-list", Kind: "custom", Name: "Stale", UpdatedAt: now.Add(-time.Hour)}}
		server.items["stale-list|ytm|s0"] = &serverRecord[meirosync.ChangedItem]{seq: 2, value: meirosync.ChangedItem{LibraryID: "stale-list", LibraryItem: meirosync.LibraryItem{Track: track("s0"), UpdatedAt: now.Add(-time.Hour)}}}
		id := meirosync.LibraryID(mine.ID)
		server.libs[id] = &serverRecord[meirosync.Library]{seq: 3, value: meirosync.Library{ID: id, Kind: "custom", Name: "Mine", UpdatedAt: now.Add(-time.Hour)}}
		server.items[id+"|ytm|stale"] = &serverRecord[meirosync.ChangedItem]{seq: 4, value: meirosync.ChangedItem{LibraryID: id, LibraryItem: meirosync.LibraryItem{Track: track("stale"), UpdatedAt: now.Add(-time.Hour)}}}
		server.seq = 4
		if syncedBefore {
			a.settings.SyncLastAt = now.Add(-24 * time.Hour)
		}
		return a, server
	}

	a, server := build(t, true)
	a.pullChanges()
	settled(t, a)
	lists := a.libraries.List()
	if len(lists) != 1 || !slices.Equal(listKeys(lists[0]), []string{"keep"}) {
		t.Errorf("an old install imported what the server had: %+v", lists)
	}
	if !a.settings.SyncTwoWay {
		t.Error("the migration was not recorded")
	}
	if lib, _ := server.library("stale-list"); !lib.Deleted {
		t.Errorf("the stale list was not deleted on the server: %+v", lib)
	}
	if item, _ := server.item(meirosync.LibraryID(lists[0].ID), "ytm", "stale"); !item.Deleted {
		t.Errorf("the stale track was not deleted on the server: %+v", item)
	}
	if item, _ := server.item(meirosync.LibraryID(lists[0].ID), "ytm", "keep"); item.Deleted || item.Track.ProviderTrackID != "keep" {
		t.Errorf("the track that is here was not kept on the server: %+v", item)
	}

	// Once migrated, the server is read as usual: a new list is imported.
	server.mu.Lock()
	server.seq++
	server.libs["later"] = &serverRecord[meirosync.Library]{seq: server.seq, value: meirosync.Library{ID: "later", Kind: "custom", Name: "Later", UpdatedAt: time.Now()}}
	server.mu.Unlock()
	a.pullChanges()
	settled(t, a)
	if len(a.libraries.List()) != 2 {
		t.Errorf("after the migration a new list was not imported: %+v", a.libraries.List())
	}

	fresh, _ := build(t, false)
	fresh.pullChanges()
	settled(t, fresh)
	names := map[string][]string{}
	for _, lib := range fresh.libraries.List() {
		names[lib.Name] = listKeys(lib)
	}
	if got := names["Stale"]; !slices.Equal(got, []string{"s0"}) || !slices.Equal(names["Mine"], []string{"keep", "stale"}) {
		t.Errorf("a new install did not import: %v", names)
	}
	if !fresh.settings.SyncTwoWay {
		t.Error("a new install did not record that it reads lists")
	}
}

// Two devices on one server converge: a list made on one reaches the other, a
// removal and a rename travel back, and tracks each added while apart both
// end up in both.
func TestTwoDevicesConverge(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a, b := signedInTo(t, srv.URL), signedInTo(t, srv.URL)

	trip, _ := a.libraries.Create("Trip")
	addSong(t, a, trip.ID, "s1")
	addSong(t, a, trip.ID, "s2")
	syncBoth(t, a)

	syncBoth(t, b)
	lists := b.libraries.List()
	if len(lists) != 1 || lists[0].Name != "Trip" || lists[0].ServerID != meirosync.LibraryID(trip.ID) || !slices.Equal(listKeys(lists[0]), []string{"s1", "s2"}) {
		t.Fatalf("B after pulling: %+v", lists)
	}
	if lists[0].Items[0].Thumbnail != "https://pic/s1" {
		t.Errorf("B did not get the cover: %+v", lists[0].Items[0])
	}
	onB := lists[0].ID

	if err := b.libraries.RemoveItem(onB, "s2"); err != nil {
		t.Fatal(err)
	}
	if err := b.libraries.Rename(onB, "Trip 2"); err != nil {
		t.Fatal(err)
	}
	syncBoth(t, b)
	syncBoth(t, a)
	got, _ := a.libraries.Get(trip.ID)
	if got.Name != "Trip 2" || !slices.Equal(listKeys(got), []string{"s1"}) {
		t.Fatalf("A after B's changes: %q %v", got.Name, listKeys(got))
	}

	// Both edit while apart.
	addSong(t, a, trip.ID, "x")
	addSong(t, b, onB, "y")
	syncBoth(t, a)
	syncBoth(t, b)
	syncBoth(t, a)
	onA, _ := a.libraries.Get(trip.ID)
	got, _ = b.libraries.Get(onB)
	if !slices.Equal(listKeys(onA), listKeys(got)) || len(got.Items) != 3 || !slices.Contains(listKeys(got), "x") || !slices.Contains(listKeys(got), "y") || got.Name != "Trip 2" {
		t.Errorf("A has %v, B has %v (%q)", listKeys(onA), listKeys(got), got.Name)
	}

	// A deletion on one reaches the other, and the other does not bring it back.
	if err := b.libraries.Delete(onB); err != nil {
		t.Fatal(err)
	}
	syncBoth(t, b)
	syncBoth(t, a)
	if len(a.libraries.List()) != 0 || len(a.libraries.Deleted()) != 0 {
		t.Errorf("A after B deleted the list: %+v", a.libraries.List())
	}
	syncBoth(t, a)
	syncBoth(t, b)
	if lib, _ := server.library(meirosync.LibraryID(trip.ID)); !lib.Deleted || len(a.libraries.List())+len(b.libraries.List()) != 0 {
		t.Errorf("the deleted list came back: %+v", lib)
	}
}

// A rename on one device is not undone by a device that had not heard of it and
// adds a song afterwards: the list and its tracks are records of their own.
func TestASongAddedByADeviceThatMissedARenameDoesNotUndoIt(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a, b := signedInTo(t, srv.URL), signedInTo(t, srv.URL)

	trip, _ := a.libraries.Create("Trip")
	addSong(t, a, trip.ID, "s1")
	syncBoth(t, a)
	syncBoth(t, b)
	onB := b.libraries.List()[0].ID

	if err := b.libraries.Rename(onB, "Renamed on B"); err != nil {
		t.Fatal(err)
	}
	syncBoth(t, b)
	// A has not pulled, and adds a song after B's rename.
	addSong(t, a, trip.ID, "s2")
	syncBoth(t, a)
	syncBoth(t, b)

	for name, app := range map[string]*app{"A": a, "B": b} {
		lib := app.libraries.List()[0]
		if lib.Name != "Renamed on B" || !slices.Equal(listKeys(lib), []string{"s1", "s2"}) {
			t.Errorf("%s has %q %v", name, lib.Name, listKeys(lib))
		}
	}
}

// phonePlay is a play of a device called phone, as the server holds it.
func phonePlay(id, video, title, artist string, at time.Time) meirosync.Event {
	return meirosync.Event{
		ClientEventID: id, Device: "phone", PlayedMS: 200_000, DurationMS: 200_000, Completed: true, PlayedAt: at,
		Track: meirosync.Track{Provider: "ytm", ProviderTrackID: video, Title: title, Artist: artist, DurationMS: 200_000},
	}
}

// A play from another device is read from the server and counted: the stats
// include it, the taste profile leans toward its artist, it is not sent back,
// this device's own plays that come back are not doubled, and reading again
// adds nothing.
func TestPlaysFromAnotherDeviceAreCounted(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a := signedInTo(t, srv.URL)
	a.historyLoaded = true
	a.device = "laptop"

	now := time.Now()
	own := history.Entry{
		EventID: meirosync.NewID(), Device: "laptop", Source: "ytm", VideoID: "mine", Title: "My song", Artist: "Home Band",
		PlayedMS: 200_000, DurationMS: 200_000, Completed: true, PlayedAt: now.Add(-3 * time.Hour),
	}
	a.history = history.Append(a.history, own)
	a.queueEvent(own)
	before := a.tasteProfile().Artist("Phone Band")
	if before != 0 {
		t.Fatalf("taste for an artist never played = %v", before)
	}

	server.plays = []meirosync.PlayedEvent{
		{Event: phonePlay("phone-1", "p1", "Phone song", "Phone Band", now.Add(-2*time.Hour)), Seq: 1},
		{Event: phonePlay("phone-2", "p2", "Phone song two", "Phone Band", now.Add(-time.Hour)), Seq: 2},
		{Event: phonePlay("phone-3", "p3", "Phone song three", "Phone Band", now.Add(-30*time.Minute)), Seq: 3},
	}
	// Reading the plays asks again from where the page left off.
	syncBoth(t, a)

	if len(a.history) != 4 {
		t.Fatalf("history holds %d plays, want this device's one and the phone's three: %+v", len(a.history), a.history)
	}
	var fromPhone history.Entry
	for _, e := range a.history {
		if e.EventID == "phone-2" {
			fromPhone = e
		}
	}
	if fromPhone.Device != "phone" || fromPhone.Source != "ytm" || fromPhone.VideoID != "p2" || fromPhone.Title != "Phone song two" || fromPhone.Artist != "Phone Band" ||
		fromPhone.PlayedMS != 200_000 || fromPhone.DurationMS != 200_000 || !fromPhone.Completed || !fromPhone.PlayedAt.Equal(now.Add(-time.Hour)) {
		t.Errorf("a play from the phone = %+v", fromPhone)
	}
	for i := 1; i < len(a.history); i++ {
		if a.history[i].PlayedAt.Before(a.history[i-1].PlayedAt) {
			t.Fatalf("history is not in the order of play: %+v", a.history)
		}
	}
	if got := a.tasteProfile().Artist("Phone Band"); got <= before {
		t.Errorf("taste for the phone's artist = %v, want it raised from %v", got, before)
	}
	if got := a.statsSummary(now, 7); got.Plays != 4 || got.Artists != 2 {
		t.Errorf("stats count %d plays by %d artists, want 4 by 2", got.Plays, got.Artists)
	}
	if a.settings.EventCursor != 4 || a.settings.EventCursorURL != srv.URL || a.settings.EventCursorEmail != "me@example.com" {
		t.Errorf("cursor = %d for %q %q", a.settings.EventCursor, a.settings.EventCursorURL, a.settings.EventCursorEmail)
	}
	// The server was read from the beginning in pages of two, then again
	// after the push, which brought this device's own play back; that play
	// went to the server once.
	if !slices.Equal(server.askedPlays, []int64{0, 2, 4}) {
		t.Errorf("asked for plays since %v, want 0, 2 then 4", server.askedPlays)
	}
	if len(server.plays) != 4 || a.sync.outbox.EventCount() != 0 {
		t.Errorf("the server holds %d plays, %d wait: the phone's plays must not be sent back", len(server.plays), a.sync.outbox.EventCount())
	}

	// Reading again, from the start as well, adds nothing.
	a.pullEvents()
	settled(t, a)
	a.settings.EventCursor = 0
	a.pullEvents()
	settled(t, a)
	if len(a.history) != 4 {
		t.Errorf("reading twice left %d plays", len(a.history))
	}
	if got := a.statsSummary(now, 7).Plays; got != 4 {
		t.Errorf("stats count %d plays after reading twice", got)
	}

	// Another account starts from the beginning, and signing out forgets the
	// cursor.
	a.signOutSync()
	if a.settings.EventCursor != 0 || a.settings.EventCursorURL != "" || a.settings.EventCursorEmail != "" {
		t.Errorf("signing out kept the cursor of the plays: %d", a.settings.EventCursor)
	}
}

// A device that signs in later has a history of its own and gets the others'
// plays in the order they were played, behind what it holds.
func TestPlaysOfAnotherDeviceArriveOnSignIn(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	phone, laptop := signedInTo(t, srv.URL), signedInTo(t, srv.URL)
	for _, a := range []*app{phone, laptop} {
		a.historyLoaded = true
	}
	phone.device, laptop.device = "phone", "laptop"
	now := time.Now()
	phone.history = history.Append(phone.history, history.Entry{
		EventID: "ph", Device: "phone", Source: "ytm", VideoID: "x", Title: "X", Artist: "Y", PlayedMS: 100_000, DurationMS: 100_000, Completed: true, PlayedAt: now.Add(-time.Hour),
	})
	phone.queueEvent(phone.history[0])
	syncBoth(t, phone)
	laptop.history = history.Append(laptop.history, history.Entry{
		EventID: "lp", Device: "laptop", Source: "ytm", VideoID: "z", Title: "Z", Artist: "W", PlayedMS: 100_000, DurationMS: 100_000, Completed: true, PlayedAt: now.Add(-2 * time.Hour),
	})
	syncBoth(t, laptop)
	if len(laptop.history) != 2 || laptop.history[0].EventID != "lp" || laptop.history[1].EventID != "ph" || laptop.history[1].Device != "phone" {
		t.Errorf("laptop history = %+v", laptop.history)
	}
}

// dailyMixes are the lists named Daily mix an app holds.
func dailyMixes(a *app) []library.Library {
	var out []library.Library
	for _, lib := range a.libraries.List() {
		if lib.Name == "Daily mix" {
			out = append(out, lib)
		}
	}
	return out
}

// signInTo signs an app in to the server at url, as signedInTo does for a new
// one.
func signInTo(a *app, url string) {
	a.settings.SyncURL, a.settings.SyncEmail, a.settings.SyncEnabled = url, "me@example.com", true
	a.sync.token = "TOKEN"
}

// Two devices of one account share one Daily mix: the list one makes reaches
// the other under the same identity, the other uses it as today's and makes no
// second, and it does not teach the taste profile.
func TestTwoDevicesShareOneDailyMix(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a, b := recsApp(t), recsApp(t)
	signInTo(a, srv.URL)
	signInTo(b, srv.URL)

	a.refreshRecommendations()
	pollUntil(t, a, "A's Daily mix", func() bool { return a.settings.DailyMixDate == today() })
	syncBoth(t, a)
	if lib, ok := server.library(meirosync.DailyMixID()); !ok || lib.Name != "Daily mix" || lib.Deleted {
		t.Fatalf("the Daily mix on the server = %+v %t", lib, ok)
	}
	mixOfA := dailyMixes(a)
	if len(mixOfA) != 1 || mixOfA[0].ServerID != meirosync.DailyMixID() || len(mixOfA[0].Items) == 0 {
		t.Fatalf("A's Daily mix = %+v", mixOfA)
	}

	syncBoth(t, b)
	got := dailyMixes(b)
	if len(got) != 1 || got[0].ServerID != meirosync.DailyMixID() || !slices.Equal(listKeys(got[0]), listKeys(mixOfA[0])) {
		t.Fatalf("B's Daily mix after pulling = %+v", got)
	}
	if b.settings.DailyMixID != got[0].ID || b.settings.DailyMixDate != today() {
		t.Errorf("B did not take the list as its mix: %q %q", b.settings.DailyMixID, b.settings.DailyMixDate)
	}
	if _, ok := b.dailyMixSection(); !ok {
		t.Error("Home on B has no Daily mix to show")
	}

	// B's own run for the day finds the mix made and makes none.
	b.recommendTick()
	pollUntil(t, b, "B's run", func() bool { return !b.recs.load.busy() && len(b.recs.picks) > 0 })
	syncBoth(t, b)
	syncBoth(t, a)
	after := dailyMixes(b)
	if len(after) != 1 || !slices.Equal(listKeys(after[0]), listKeys(mixOfA[0])) || !after[0].Items[0].UpdatedAt.Equal(got[0].Items[0].UpdatedAt) {
		t.Errorf("B made another Daily mix, or rewrote this one: %+v", after)
	}
	if len(dailyMixes(a)) != 1 || len(a.libraries.List()) != 1 || len(b.libraries.List()) != 1 {
		t.Errorf("lists: A %d, B %d", len(a.libraries.List()), len(b.libraries.List()))
	}

	// The mix is the app's own pick, so it teaches the profile nothing on
	// either device.
	for name, d := range map[string]*app{"A": a, "B": b} {
		for _, s := range d.tasteSignals() {
			if s.Title == "Cloud Song" {
				t.Errorf("the Daily mix counted as a signal on %s: %+v", name, s)
			}
		}
	}

	// Asked for a new one, a device makes it, and it is the same list.
	b.settings.DailyMixDate = ""
	b.recs.remake = true
	b.recs.load.stop()
	b.refreshRecommendations()
	pollUntil(t, b, "B's new mix", func() bool { return !b.recs.load.busy() && b.settings.DailyMixDate == today() })
	if len(dailyMixes(b)) != 1 || b.recs.remake {
		t.Errorf("a new mix made %d lists, remake %t", len(dailyMixes(b)), b.recs.remake)
	}
}

// A Daily mix made before the lists were shared, under an identity of its own,
// is replaced by the shared one, and its identity is deleted on the server.
func TestAnOldDailyMixMovesToTheSharedList(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a := recsApp(t)
	signInTo(a, srv.URL)
	old, _ := a.libraries.Create("Daily mix")
	addSong(t, a, old.ID, "old-pick")
	a.settings.DailyMixID, a.settings.DailyMixDate = old.ID, "2000-01-01"
	syncBoth(t, a)

	a.recommendTick()
	pollUntil(t, a, "the new mix", func() bool { return a.settings.DailyMixDate == today() })
	syncBoth(t, a)
	mixes := dailyMixes(a)
	if len(mixes) != 1 || mixes[0].ServerID != meirosync.DailyMixID() || a.settings.DailyMixID != mixes[0].ID {
		t.Fatalf("Daily mixes after moving = %+v", mixes)
	}
	if lib, _ := server.library(meirosync.LibraryID(old.ID)); !lib.Deleted {
		t.Errorf("the old Daily mix is still on the server: %+v", lib)
	}
	if lib, _ := server.library(meirosync.DailyMixID()); lib.Deleted || lib.Name != "Daily mix" {
		t.Errorf("the shared Daily mix on the server = %+v", lib)
	}
}
