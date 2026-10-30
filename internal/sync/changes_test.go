package sync

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// changesServer answers reads of what changed with pages it is given in order,
// and records the since and limit of each read.
func changesServer(t *testing.T, pages []string, asked *[]string) *Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer TOKEN" || r.URL.Path != "/api/v1/sync/changes" {
			http.Error(w, `{"error":"no"}`, http.StatusUnauthorized)
			return
		}
		*asked = append(*asked, r.URL.Query().Get("since")+"/"+r.URL.Query().Get("limit"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(pages[len(*asked)-1]))
	}))
	t.Cleanup(server.Close)
	return &Client{BaseURL: server.URL, Token: "TOKEN"}
}

// A read of the changes decodes lists, tracks and tombstones as the server
// writes them, null fields included.
func TestFetchChangesDecodesTheServersAnswer(t *testing.T) {
	var asked []string
	c := changesServer(t, []string{`{"libraries":[{"id":"L1","kind":"custom","provider":null,"provider_id":null,"name":"X","cover":"https://c","updated_at":"2026-01-02T03:04:05Z","deleted":false,"seq":12}],
		"items":[{"library_id":"L1","track":{"provider":"ytm","provider_track_id":"abc","title":"T","artist":"A","album":null,"duration_ms":200000,"isrc":null,"thumbnail":"https://t","album_thumbnail":null},"position":3,"updated_at":"2026-01-02T03:04:06Z","deleted":true,"seq":13}],
		"next":13,"more":false}`}, &asked)
	got, err := c.FetchChanges(context.Background(), 7, 5000)
	if err != nil {
		t.Fatal(err)
	}
	if len(asked) != 1 || asked[0] != "7/1000" {
		t.Errorf("asked %v, want since 7 and the largest limit", asked)
	}
	lib, item := got.Libraries[0], got.Items[0]
	if lib.ID != "L1" || lib.Kind != "custom" || lib.Cover != "https://c" || lib.Seq != 12 || !lib.UpdatedAt.Equal(time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)) {
		t.Errorf("library = %+v", lib)
	}
	if item.LibraryID != "L1" || item.Track.ProviderTrackID != "abc" || item.Track.Thumbnail != "https://t" || item.Position != 3 || !item.Deleted || item.Seq != 13 {
		t.Errorf("item = %+v", item)
	}
	if got.Next != 13 || got.More {
		t.Errorf("next = %d, more = %t", got.Next, got.More)
	}
}

// Reading everything asks again from where each answer left off while the
// server has more, and joins the answers.
func TestFetchAllChangesPagesThroughMore(t *testing.T) {
	var asked []string
	c := changesServer(t, []string{
		`{"libraries":[{"id":"A","kind":"custom","name":"A","updated_at":"2026-01-01T00:00:00Z","seq":1}],"items":[],"next":1,"more":true}`,
		`{"libraries":[],"items":[{"library_id":"A","track":{"provider":"ytm","provider_track_id":"v"},"position":0,"updated_at":"2026-01-01T00:00:00Z","seq":2}],"next":2,"more":false}`,
	}, &asked)
	got, err := c.FetchAllChanges(context.Background(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(asked) != 2 || asked[0] != "0/1000" || asked[1] != "1/1000" {
		t.Errorf("asked %v", asked)
	}
	if len(got.Libraries) != 1 || len(got.Items) != 1 || got.Next != 2 || got.More {
		t.Errorf("joined = %+v", got)
	}
}

// A server that says there is more but stays where it was cannot hold the
// reader in a loop.
func TestFetchAllChangesStopsOnAServerThatDoesNotMoveOn(t *testing.T) {
	var asked []string
	stuck := `{"libraries":[],"items":[],"next":5,"more":true}`
	c := changesServer(t, []string{stuck, stuck, stuck}, &asked)
	if _, err := c.FetchAllChanges(context.Background(), 5); err == nil {
		t.Fatal("a server that did not move on was believed")
	}
	if len(asked) != 1 {
		t.Errorf("asked %d times", len(asked))
	}
}

// A refused read is an error.
func TestFetchChangesRefused(t *testing.T) {
	c := &Client{BaseURL: "http://127.0.0.1:1"}
	if _, err := c.FetchChanges(context.Background(), 0, 10); err == nil {
		t.Error("an unreachable server did not fail the read")
	}
	var asked []string
	unauthorized := changesServer(t, nil, &asked)
	unauthorized.Token = "WRONG"
	if _, err := unauthorized.FetchChanges(context.Background(), 0, 10); err == nil {
		t.Error("a refused read did not fail")
	}
}

// Lists and tracks waiting for the server are kept by identity, the later
// record of each staying, and one waiting for another reason is not dropped.
func TestAddLibrariesAndItemsKeepTheLaterRecordAndTheRest(t *testing.T) {
	o := Open("")
	t1, t2 := time.Unix(100, 0), time.Unix(200, 0)
	o.AddLibraries([]Library{{ID: "A", Name: "old", UpdatedAt: t1}, {ID: "B", Name: "other", UpdatedAt: t1}})
	o.AddLibraries([]Library{{ID: "A", Name: "new", UpdatedAt: t2}})
	o.AddLibraries([]Library{{ID: "A", Name: "stale", UpdatedAt: t1}})
	libs := o.snapshot().libraries
	if len(libs) != 2 || libs[0].Name != "new" || libs[1].ID != "B" {
		t.Errorf("libraries = %+v", libs)
	}

	track := func(id string) Track { return Track{Provider: "ytm", ProviderTrackID: id} }
	o.AddLibraryItems("A", []LibraryItem{{Track: track("x"), UpdatedAt: t1}, {Track: track("gone"), UpdatedAt: t1, Deleted: true}})
	o.AddLibraryItems("A", []LibraryItem{{Track: track("x"), Position: 4, UpdatedAt: t2}})
	items := o.snapshot().items
	if len(items) != 1 || len(items[0].Items) != 2 || items[0].Items[0].Position != 4 || !items[0].Items[1].Deleted {
		t.Errorf("items = %+v", items)
	}
}

// A library the server answers 404 to, as one another device deleted, has its
// waiting tracks dropped, and the rest of the push goes on.
func TestPushDropsTheTracksOfALibraryTheServerDoesNotKnow(t *testing.T) {
	var pushed []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body LibraryItems
		_ = json.NewDecoder(r.Body).Decode(&body)
		pushed = append(pushed, body.LibraryID)
		if body.LibraryID == "gone" {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"library not found"}`))
			return
		}
		_, _ = w.Write([]byte(`{"accepted":[]}`))
	}))
	t.Cleanup(server.Close)
	o := Open("")
	for _, lib := range []string{"gone", "kept"} {
		var items []LibraryItem
		for i := range MaxBatch + 1 {
			items = append(items, LibraryItem{Track: Track{Provider: "ytm", ProviderTrackID: lib + strconv.Itoa(i)}, UpdatedAt: time.Unix(1, 0)})
		}
		o.AddLibraryItems(lib, items)
	}
	if _, err := Push(context.Background(), &Client{BaseURL: server.URL}, o); err != nil {
		t.Fatalf("a 404 stopped the push: %v", err)
	}
	if got := o.Pending(); got != 0 {
		t.Errorf("%d records still wait", got)
	}
	// The dropped library's second batch was never tried.
	if len(pushed) != 3 || pushed[0] != "gone" || pushed[1] != "kept" {
		t.Errorf("pushed to %v", pushed)
	}
}
