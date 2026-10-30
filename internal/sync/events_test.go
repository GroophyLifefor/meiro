package sync

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// eventsServer answers reads of the plays with pages it is given in order, and
// records the since and limit of each read.
func eventsServer(t *testing.T, pages []string, asked *[]string) *Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer TOKEN" || r.URL.Path != "/api/v1/events" {
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

// A read of the plays decodes them as the server writes them.
func TestFetchEventsDecodesTheServersAnswer(t *testing.T) {
	var asked []string
	c := eventsServer(t, []string{`{"events":[{"client_event_id":"e1","track":{"provider":"ytm","provider_track_id":"abc","title":"T","artist":"A"},
		"played_ms":31000,"duration_ms":200000,"completed":false,"device":"phone","played_at":"2026-01-02T03:04:05Z","seq":7}],"next":7,"more":false}`}, &asked)
	got, err := c.FetchEvents(context.Background(), 3, 5000)
	if err != nil {
		t.Fatal(err)
	}
	if len(asked) != 1 || asked[0] != "3/1000" {
		t.Errorf("asked %v, want since 3 and the largest limit", asked)
	}
	e := got.Events[0]
	if e.ClientEventID != "e1" || e.Track.ProviderTrackID != "abc" || e.Track.Artist != "A" || e.PlayedMS != 31000 || e.DurationMS != 200000 ||
		e.Device != "phone" || e.Seq != 7 || !e.PlayedAt.Equal(time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)) {
		t.Errorf("event = %+v", e)
	}
	if got.Next != 7 || got.More {
		t.Errorf("next = %d, more = %t", got.Next, got.More)
	}
}

// Reading every play asks again from where each answer left off while the
// server has more, and joins the answers.
func TestFetchAllEventsPagesThroughMore(t *testing.T) {
	var asked []string
	c := eventsServer(t, []string{
		`{"events":[{"client_event_id":"a","track":{"provider":"ytm","provider_track_id":"v"},"played_ms":1,"played_at":"2026-01-01T00:00:00Z","seq":1}],"next":1,"more":true}`,
		`{"events":[{"client_event_id":"b","track":{"provider":"ytm","provider_track_id":"w"},"played_ms":1,"played_at":"2026-01-01T00:00:00Z","seq":2}],"next":2,"more":false}`,
	}, &asked)
	got, err := c.FetchAllEvents(context.Background(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(asked) != 2 || asked[0] != "0/1000" || asked[1] != "1/1000" {
		t.Errorf("asked %v", asked)
	}
	if len(got.Events) != 2 || got.Next != 2 || got.More {
		t.Errorf("joined = %+v", got)
	}
}

// A server that says there is more but stays where it was cannot hold the
// reader in a loop, and a refused read is an error.
func TestFetchAllEventsStopsOnAServerThatDoesNotMoveOn(t *testing.T) {
	var asked []string
	stuck := `{"events":[],"next":5,"more":true}`
	c := eventsServer(t, []string{stuck, stuck, stuck}, &asked)
	if _, err := c.FetchAllEvents(context.Background(), 5); err == nil {
		t.Fatal("a server that did not move on was believed")
	}
	if len(asked) != 1 {
		t.Errorf("asked %d times", len(asked))
	}
	c.Token = "WRONG"
	if _, err := c.FetchEvents(context.Background(), 0, 10); err == nil {
		t.Error("a refused read did not fail")
	}
}

// The Daily mix has one identity, which is not a library's.
func TestDailyMixIDIsFixed(t *testing.T) {
	first, second := DailyMixID(), DailyMixID()
	if first != second || len(first) != 36 || first == LibraryID("daily-mix") {
		t.Errorf("daily mix id = %q then %q", first, second)
	}
}
