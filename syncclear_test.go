package main

import (
	"net/http/httptest"
	"slices"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/history"
	"github.com/elianiva/meiro/internal/library"
	meirosync "github.com/elianiva/meiro/internal/sync"
	"github.com/elianiva/meiro/youtube"
)

// playAt is a finished play of a track at a time, filed for syncing as a play
// that ended is.
func playAt(a *app, id string, at time.Time) history.Entry {
	e := history.Entry{
		EventID: id, Device: a.device, Source: "ytm", VideoID: "v-" + id, Title: "Song " + id, Artist: "Artist",
		PlayedMS: 200_000, DurationMS: 200_000, Completed: true, PlayedAt: at,
	}
	a.history = history.Append(a.history, e)
	a.queueEvent(e)
	return e
}

func historyIDs(a *app) []string {
	var ids []string
	for _, e := range a.history {
		ids = append(ids, e.EventID)
	}
	return ids
}

func serverPlayIDs(s *serverState) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var ids []string
	for _, e := range s.plays {
		ids = append(ids, e.ClientEventID)
	}
	return ids
}

// A song's cover is filed with its play, sent with it, and read back on another
// device, where the row of the history uses it.
func TestPlayCoversTravelToTheHistoryRows(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	laptop, phone := signedInTo(t, srv.URL), signedInTo(t, srv.URL)
	for _, a := range []*app{laptop, phone} {
		a.historyLoaded = true
	}
	laptop.device, phone.device = "laptop", "phone"

	laptop.beginPlayRecord(youtube.MusicItem{VideoID: "cover", Title: "Covered", Artist: "A", Thumbnail: "https://pic/cover"})
	laptop.beginPlayRecord(youtube.MusicItem{VideoID: "bare", Title: "Bare", Artist: "A"})
	laptop.finishPlayRecord(true)
	if got := laptop.history[0].Thumbnail; got != "https://pic/cover" {
		t.Fatalf("the play did not keep the cover: %+v", laptop.history[0])
	}
	if got := laptop.history[1].Thumbnail; got != "" {
		t.Errorf("a song with no cover got %q", got)
	}
	syncBoth(t, laptop)

	var sent meirosync.PlayedEvent
	server.mu.Lock()
	for _, e := range server.plays {
		if e.Track.ProviderTrackID == "cover" {
			sent = e
		}
	}
	server.mu.Unlock()
	if sent.Track.Thumbnail != "https://pic/cover" {
		t.Fatalf("the server got the play as %+v", sent)
	}

	syncBoth(t, phone)
	var got history.Entry
	for _, e := range phone.history {
		if e.VideoID == "cover" {
			got = e
		}
	}
	if got.Thumbnail != "https://pic/cover" || got.Device != "laptop" {
		t.Fatalf("the phone's play = %+v", got)
	}
	if item := historyItem(got); item.Thumbnail != "https://pic/cover" {
		t.Errorf("the row of the history shows the cover %q", item.Thumbnail)
	}
}

// The reason a Daily mix song was chosen goes with it to the server, reaches
// another device, and a newer record of the song updates it.
func TestDailyMixReasonsTravel(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a, b := recsApp(t), recsApp(t)
	signInTo(a, srv.URL)
	signInTo(b, srv.URL)

	a.refreshRecommendations()
	pollUntil(t, a, "A's Daily mix", func() bool { return a.settings.DailyMixDate == today() })
	syncBoth(t, a)
	mixA := dailyMixes(a)
	if len(mixA) != 1 || len(mixA[0].Items) == 0 || mixA[0].Items[0].Why == "" {
		t.Fatalf("A's mix carries no reasons: %+v", mixA)
	}
	for _, item := range mixA[0].Items {
		source, key := item.ActiveRef()
		sent, ok := server.item(meirosync.DailyMixID(), string(source), key)
		if !ok || sent.Note != item.Why {
			t.Errorf("%s sent as %+v, want its reason %q", key, sent, item.Why)
		}
	}

	syncBoth(t, b)
	mixB := dailyMixes(b)
	if len(mixB) != 1 || len(mixB[0].Items) != len(mixA[0].Items) {
		t.Fatalf("B's mix = %+v", mixB)
	}
	for _, item := range mixB[0].Items {
		want := ""
		for _, held := range mixA[0].Items {
			if held.Key == item.Key {
				want = held.Why
			}
		}
		if item.Why != want || want == "" {
			t.Errorf("%s on B says %q, want %q", item.Key, item.Why, want)
		}
	}
}

// A newer record of a song replaces its reason, an older one does not, and a
// newer one that carries none leaves it.
func TestARemoteReasonFollowsTheLaterRecord(t *testing.T) {
	a := newTestApp(t)
	lib, _ := a.libraries.Create("Mix")
	addSong(t, a, lib.ID, "one")
	held, _ := a.libraries.Get(lib.ID)
	base := held.Items[0]
	remote := func(why string, at time.Time) library.RemoteItem {
		item := base
		item.Why, item.UpdatedAt = why, at
		return library.RemoteItem{Item: item}
	}
	apply := func(r library.RemoteItem) string {
		t.Helper()
		if _, err := a.libraries.ApplyRemoteItems(lib.ID, []library.RemoteItem{r}); err != nil {
			t.Fatal(err)
		}
		got, _ := a.libraries.Get(lib.ID)
		return got.Items[0].Why
	}
	if got := apply(remote("new reason", base.UpdatedAt.Add(time.Hour))); got != "new reason" {
		t.Errorf("a newer record left the reason %q", got)
	}
	if got := apply(remote("stale", base.UpdatedAt.Add(time.Minute))); got != "new reason" {
		t.Errorf("an older record changed the reason to %q", got)
	}
	if got := apply(remote("", base.UpdatedAt.Add(2*time.Hour))); got != "new reason" {
		t.Errorf("a newer record with no reason cleared it: %q", got)
	}
}

// Clearing the history while signed in tells the server, empties the history
// here, lets go of the plays that waited, and the server forgets them.
func TestClearingWhileSignedInClearsTheServer(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a := signedInTo(t, srv.URL)
	a.historyLoaded = true
	now := time.Now()
	playAt(a, "p1", now.Add(-2*time.Hour))
	playAt(a, "p2", now.Add(-time.Hour))
	syncBoth(t, a)
	if got := serverPlayIDs(server); !slices.Equal(got, []string{"p1", "p2"}) {
		t.Fatalf("the server holds %v", got)
	}
	playAt(a, "p3", now.Add(-time.Minute)) // waits to be sent

	a.clearHistory()
	settled(t, a)
	if len(a.history) != 0 {
		t.Errorf("history left: %v", historyIDs(a))
	}
	if a.sync.outbox.EventCount() != 0 {
		t.Errorf("%d plays still wait to be sent", a.sync.outbox.EventCount())
	}
	if server.clears != 1 || len(server.plays) != 0 || server.clearedAt == nil {
		t.Errorf("the server was cleared %d times and holds %d plays", server.clears, len(server.plays))
	}
	if !a.settings.HistoryClearPending.IsZero() || a.settings.HistoryClearedAt.IsZero() {
		t.Errorf("pending %v, applied %v", a.settings.HistoryClearPending, a.settings.HistoryClearedAt)
	}
	// Reading the plays again brings nothing back.
	a.settings.EventCursor = 0
	syncBoth(t, a)
	if len(a.history) != 0 {
		t.Errorf("a read brought plays back: %v", historyIDs(a))
	}
}

// Signed out, clearing touches only this computer and asks nothing of a server.
func TestClearingSignedOutStaysOnThisComputer(t *testing.T) {
	a := newTestApp(t)
	a.historyLoaded = true
	playAt(a, "p1", time.Now().Add(-time.Hour))
	a.clearHistory()
	if len(a.history) != 0 || !a.settings.HistoryClearPending.IsZero() {
		t.Errorf("history %v, pending %v", historyIDs(a), a.settings.HistoryClearPending)
	}
}

// A clearing the server cannot be told of waits, the plays queued before it are
// let go so they are not sent, and the next sync tells the server; the plays
// made after it go out.
func TestAnOfflineClearingIsRetriedAndDropsQueuedPlays(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	a := signedInTo(t, srv.URL)
	a.historyLoaded = true
	now := time.Now()
	playAt(a, "old", now.Add(-time.Hour))
	server.plays = []meirosync.PlayedEvent{{Event: phonePlay("other", "o", "Other", "O", now.Add(-3*time.Hour)), Seq: 1}}

	server.offline = true
	a.clearHistory()
	settled(t, a)
	if server.clears != 0 {
		t.Fatal("the server was reached while down")
	}
	if a.settings.HistoryClearPending.IsZero() {
		t.Fatal("the clearing was not remembered")
	}
	if len(a.history) != 0 || a.sync.outbox.EventCount() != 0 {
		t.Errorf("history %v, %d plays wait", historyIDs(a), a.sync.outbox.EventCount())
	}
	// While it waits, the plays the server still holds do not come back.
	server.offline = false
	a.pullEvents()
	settled(t, a)
	if len(a.history) != 0 {
		t.Fatalf("a play came back before the server was told: %v", historyIDs(a))
	}

	a.settings.EventCursor = 0
	future := time.Now().Add(time.Minute)
	playAt(a, "after", future)
	syncBoth(t, a)
	if server.clears != 1 || !a.settings.HistoryClearPending.IsZero() {
		t.Errorf("clears %d, pending %v", server.clears, a.settings.HistoryClearPending)
	}
	if got := serverPlayIDs(server); !slices.Equal(got, []string{"after"}) {
		t.Errorf("the server holds %v, want only the play made after the clearing", got)
	}
	if got := historyIDs(a); !slices.Equal(got, []string{"after"}) {
		t.Errorf("history = %v", got)
	}
}

// A device that reads a clearing newer than the last it applied drops the plays
// up to it and keeps those made after it; the same clearing read again does
// nothing more.
func TestAClearingOnAnotherDeviceDropsOlderPlays(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	b := signedInTo(t, srv.URL)
	b.historyLoaded = true
	now := time.Now()
	playAt(b, "old1", now.Add(-2*time.Hour))
	playAt(b, "old2", now.Add(-time.Hour))
	playAt(b, "new", now.Add(time.Hour))
	b.sync.outbox.DropEventsThrough(now.Add(2 * time.Hour)) // already sent

	cleared := now
	server.clearedAt = &cleared
	b.pullEvents()
	settled(t, b)
	if got := historyIDs(b); !slices.Equal(got, []string{"new"}) {
		t.Fatalf("history = %v, want only the play after the clearing", got)
	}
	if !b.settings.HistoryClearedAt.Equal(cleared) {
		t.Errorf("applied = %v, want %v", b.settings.HistoryClearedAt, cleared)
	}
	if got := b.statsSummary(now.Add(2*time.Hour), 7).Plays; got != 1 {
		t.Errorf("stats count %d plays", got)
	}

	// A play made before the same clearing that is somehow here again stays:
	// the clearing was applied once.
	playAt(b, "late", now.Add(-30*time.Minute))
	b.pullEvents()
	settled(t, b)
	if !slices.Contains(historyIDs(b), "late") {
		t.Errorf("an applied clearing was applied again: %v", historyIDs(b))
	}
}

// The history page shows the dropped plays gone: its rows are rebuilt.
func TestAClearingRebuildsTheHistoryRows(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	b := signedInTo(t, srv.URL)
	b.historyLoaded = true
	now := time.Now()
	playAt(b, "old", now.Add(-time.Hour))
	if rows := b.historyRows(now).rows; len(rows) != 2 {
		t.Fatalf("rows = %d", len(rows))
	}
	cleared := now
	server.clearedAt = &cleared
	b.pullEvents()
	settled(t, b)
	if rows := b.historyRows(now).rows; len(rows) != 0 {
		t.Errorf("rows after the clearing = %d", len(rows))
	}
}

// Two devices of one account: one clears, the other follows with the plays it
// had already sent, and a third that was offline with a play from before the
// clearing sends it without bringing it back.
func TestClearingOnOneDeviceClearsTheOthers(t *testing.T) {
	server := newServerState()
	srv := httptest.NewServer(server)
	t.Cleanup(srv.Close)
	laptop, phone, tablet := signedInTo(t, srv.URL), signedInTo(t, srv.URL), signedInTo(t, srv.URL)
	for _, a := range []*app{laptop, phone, tablet} {
		a.historyLoaded = true
	}
	laptop.device, phone.device, tablet.device = "laptop", "phone", "tablet"
	now := time.Now()

	playAt(laptop, "l1", now.Add(-3*time.Hour))
	playAt(phone, "p1", now.Add(-2*time.Hour))
	syncBoth(t, laptop)
	syncBoth(t, phone)
	syncBoth(t, laptop)
	if len(laptop.history) != 2 || len(phone.history) != 2 {
		t.Fatalf("laptop %v, phone %v", historyIDs(laptop), historyIDs(phone))
	}
	// The tablet made a play while offline, before the clearing.
	playAt(tablet, "t1", now.Add(-time.Hour))

	laptop.clearHistory()
	settled(t, laptop)
	if server.clears != 1 || len(server.plays) != 0 {
		t.Fatalf("clears %d, plays %v", server.clears, serverPlayIDs(server))
	}

	syncBoth(t, phone)
	if len(phone.history) != 0 {
		t.Errorf("the phone kept %v", historyIDs(phone))
	}

	syncBoth(t, tablet)
	if len(tablet.history) != 0 || tablet.sync.outbox.EventCount() != 0 {
		t.Errorf("the tablet kept %v, %d wait", historyIDs(tablet), tablet.sync.outbox.EventCount())
	}
	if len(server.plays) != 0 {
		t.Errorf("the offline play came back to the server: %v", serverPlayIDs(server))
	}

	// A play made after the clearing reaches everyone.
	playAt(phone, "p2", time.Now().Add(time.Minute))
	syncBoth(t, phone)
	syncBoth(t, laptop)
	if got := historyIDs(laptop); !slices.Equal(got, []string{"p2"}) {
		t.Errorf("laptop = %v", got)
	}
}

// A history still being read when a clearing arrives from the server does not
// bring the cleared plays back.
func TestAClearingWhileTheHistoryLoads(t *testing.T) {
	a := newTestApp(t)
	var finish func()
	a.run = func(work func()) { finish = work }
	a.historyPath = t.TempDir() + "/history.json"
	old := []history.Entry{{VideoID: "old", PlayedAt: time.Now().Add(-time.Hour)}, {VideoID: "new", PlayedAt: time.Now().Add(time.Hour)}}
	if err := history.Write(a.historyPath, old); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(a.historyWriter.Wait)
	a.loadHistory()
	a.dropHistoryThrough(time.Now())
	a.run = func(work func()) { work() }
	finish()
	if len(a.history) != 1 || a.history[0].VideoID != "new" {
		t.Errorf("history = %+v", a.history)
	}
}

// Settings says where a clearing reaches.
func TestHistoryCardSaysWhereClearingReaches(t *testing.T) {
	a := newTestApp(t)
	a.historyLoaded = true
	playAt(a, "p1", time.Now().Add(-time.Hour))
	a.router.Push("/settings")
	tt := ui.NewTester(a.view, 1000, 8000)
	tt.Frame()
	if !tt.HasText("Clearing removes them from this computer only.") {
		t.Errorf("signed out: %q", tt.Texts())
	}
	a.settings.SyncEmail = "me@example.com"
	tt.Frame()
	if !tt.HasText("Clearing removes the plays from every device signed in to your Meiro account.") {
		t.Errorf("signed in: %q", tt.Texts())
	}
}

// Signing out forgets a clearing that never reached the server, so it cannot
// clear whichever account signs in next.
func TestSigningOutForgetsAPendingClearing(t *testing.T) {
	a := signedInTo(t, "http://127.0.0.1:1")
	a.settings.HistoryClearPending = time.Now()
	a.signOutSync()
	if !a.settings.HistoryClearPending.IsZero() {
		t.Error("the pending clearing survived signing out")
	}
}
