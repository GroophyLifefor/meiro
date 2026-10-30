package main

import (
	"fmt"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/history"
	meirosync "github.com/elianiva/meiro/internal/sync"
)

// historyApp is an app on the listening history page, with plays on three
// days: some of this computer's, heard to the end, in part or skipped, and
// some of a phone.
func historyApp(t *testing.T) (*app, *ui.Tester) {
	t.Helper()
	a := newTestApp(t)
	a.device = "laptop"
	a.historyLoaded = true
	now := time.Now()
	at := func(daysAgo, hour, minute int) time.Time {
		return time.Date(now.Year(), now.Month(), now.Day()-daysAgo, hour, minute, 0, 0, time.Local)
	}
	play := func(id, title, artist, device string, played, length time.Duration, completed bool, when time.Time) history.Entry {
		return history.Entry{
			EventID: id, Device: device, Source: "ytm", VideoID: "v-" + id, Title: title, Artist: artist,
			PlayedMS: played.Milliseconds(), DurationMS: length.Milliseconds(), Completed: completed, PlayedAt: when,
		}
	}
	a.history = []history.Entry{
		play("e1", "Old Tune", "Past Band", "phone", 3*time.Minute, 3*time.Minute, true, at(3, 19, 40)),
		play("e2", "Last Night", "Home Band", "laptop", 4*time.Minute, 4*time.Minute, true, at(1, 23, 17)),
		play("e3", "Quick Skip", "Home Band", "", 8*time.Second, 3*time.Minute, false, at(1, 23, 12)),
		play("e4", "Half Way", "Home Band", "laptop", 125*time.Second, 258*time.Second, false, at(0, 10, 2)),
		play("e5", "Phone Song", "Phone Band", "phone", 200*time.Second, 200*time.Second, true, at(0, 14, 44)),
		play("e6", "Skipped Song", "Home Band", "laptop", 12*time.Second, 200*time.Second, false, at(0, 14, 48)),
	}
	a.router.Push("/history")
	tt := ui.NewTester(a.view, 1000, 1500)
	tt.Frame()
	return a, tt
}

// The page lists the plays newest first under a heading for each day with its
// totals, and says of each one when, where and how much of it was heard.
func TestHistoryPageListsPlaysByDay(t *testing.T) {
	a, tt := historyApp(t)
	mustShow(t, tt, "Today", "Yesterday", time.Now().AddDate(0, 0, -3).Format("Mon, Jan 2"))
	// Today: three plays; the two that count were heard for 200 s and 125 s.
	mustShow(t, tt, "3 plays · ~5 min", "2 plays · ~4 min", "1 play · ~3 min")
	mustShow(t, tt, "Phone Song", "Phone Band", "Skipped Song", "Half Way", "Last Night", "Old Tune")
	mustShow(t, tt,
		"14:44 · phone · heard to the end",
		"14:48 · This computer · skipped at 0:12",
		"10:02 · This computer · 2:05 of 4:18",
		"23:12 · This computer · skipped at 0:08",
	)
	texts := tt.Texts()
	if indexOfText(texts, "Skipped Song") > indexOfText(texts, "Phone Song") || indexOfText(texts, "Phone Song") > indexOfText(texts, "Half Way") {
		t.Errorf("the plays are not newest first: %q", texts)
	}

	// A press on a row does not change the song: the list is for looking back.
	if err := tt.Click("Phone Song"); err != nil {
		t.Fatal(err)
	}
	if a.current.Key() != "" {
		t.Errorf("a single press started %q", a.current.Key())
	}
}

func indexOfText(texts []string, want string) int {
	for i, text := range texts {
		if text == want {
			return i
		}
	}
	return len(texts)
}

// The chips narrow the list: counted and skipped by the rule the stats use, and
// one for each device the history holds.
func TestHistoryPageFilters(t *testing.T) {
	_, tt := historyApp(t)
	mustShow(t, tt, "All", "Counted", "Skipped", "This computer", "phone")

	if err := tt.Click("Skipped"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	mustShow(t, tt, "Skipped Song", "Quick Skip", "1 play · ~0 min")
	mustNotShow(t, tt, "Phone Song", "Half Way", "Last Night", "Old Tune", time.Now().AddDate(0, 0, -3).Format("Mon, Jan 2"))

	if err := tt.Click("Counted"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	mustShow(t, tt, "Phone Song", "Half Way", "Last Night", "Old Tune", "2 plays · ~5 min")
	mustNotShow(t, tt, "Skipped Song", "Quick Skip")

	if err := tt.Click("phone"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	mustShow(t, tt, "Phone Song", "Old Tune", "1 play · ~3 min")
	mustNotShow(t, tt, "Half Way", "Skipped Song", "Last Night", "Yesterday")

	// A play from before plays carried a device was made here.
	if err := tt.Click("This computer"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	mustShow(t, tt, "Quick Skip", "Half Way", "Last Night", "Skipped Song")
	mustNotShow(t, tt, "Phone Song", "Old Tune")

	if err := tt.Click("All"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	mustShow(t, tt, "Phone Song", "Quick Skip")
}

// A play that another device made and the server gave back is listed under
// that device, and the device gets a chip of its own.
func TestHistoryPageShowsAPlayMergedFromAnotherDevice(t *testing.T) {
	a := newTestApp(t)
	a.device = "laptop"
	a.historyLoaded = true
	a.router.Push("/history")
	tt := ui.NewTester(a.view, 1000, 900)
	tt.Frame()
	mustShow(t, tt, "No plays yet")
	mustNotShow(t, tt, "Counted")

	play := phonePlay("phone-9", "p9", "Merged Song", "Merged Band", time.Now().Add(-time.Minute))
	if !a.mergeEvents([]meirosync.PlayedEvent{{Event: play, Seq: 1}}) {
		t.Fatal("the play was not taken in")
	}
	tt.Frame()
	mustShow(t, tt, "Today", "Merged Song", "Merged Band", "phone", "heard to the end", "1 play · ~3 min")
	mustNotShow(t, tt, "No plays yet", "This computer")
	if err := tt.Click("phone"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	mustShow(t, tt, "Merged Song")
}

// A long history builds only the rows in view.
func TestHistoryPageBuildsOnlyWhatIsInView(t *testing.T) {
	a := newTestApp(t)
	a.historyLoaded = true
	now := time.Now()
	for i := range history.MaxEntries {
		a.history = append(a.history, history.Entry{
			EventID: fmt.Sprint("e", i), Source: "ytm", VideoID: fmt.Sprint("v", i), Title: fmt.Sprint("Track ", i), Artist: "Band",
			PlayedMS: 200_000, DurationMS: 200_000, Completed: true, PlayedAt: now.Add(-time.Duration(history.MaxEntries-i) * time.Minute),
		})
	}
	a.router.Push("/history")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	mustShow(t, tt, fmt.Sprint("Track ", history.MaxEntries-1))
	mustNotShow(t, tt, "Track 100")
	if got := len(tt.Texts()); got > 150 {
		t.Errorf("%d texts are built for a window of a few rows", got)
	}
}

// The page is reached from the stats and from the settings.
func TestHistoryPageIsReachable(t *testing.T) {
	a := newTestApp(t)
	a.historyLoaded = true
	tt := open(a, "/stats")
	mustShow(t, tt, "History")
	if err := tt.Click("History"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	mustShow(t, tt, "No plays yet")
	if got := a.router.Path(); got != "/history" {
		t.Errorf("History opened %q", got)
	}

	a = newTestApp(t)
	a.router.Push("/settings")
	tt = ui.NewTester(a.view, 1000, 9000)
	tt.Frame()
	if err := tt.Click("Open"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if got := a.router.Path(); got != "/history" {
		t.Errorf("Open on the settings opened %q", got)
	}
}
