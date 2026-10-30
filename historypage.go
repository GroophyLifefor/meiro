package main

import (
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/history"
	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// What the listening history page shows: every play, or the ones the stats
// count, or the ones they leave out, or the plays of one device. A device is
// chosen by the name the page shows it by.
const (
	historyAll          = ""
	historyCounted      = "counted"
	historySkipped      = "skipped"
	historyDevicePrefix = "device:"
)

// thisComputer is the name the history gives the device it is shown on, in
// place of the machine's own name.
const thisComputer = "This computer"

// historyRow is one line of the history page: the heading of a day, or a play.
type historyRow struct {
	// A heading has the day's name, how many plays the page shows of it and how
	// long the ones that count were heard for.
	header  bool
	title   string
	plays   int
	heardMS int64
	// A play has its entry, whether the stats count it, and its place in the
	// history, which names it for as long as it is kept.
	entry   history.Entry
	counted bool
	place   int
}

// historyMemo holds the rows the history page last built and what they were
// built from. The page builds on every frame, but the history changes only when
// a play starts, ends or arrives from another device.
type historyMemo struct {
	rows []historyRow
	// devices are the names the plays are shown under, this computer first.
	devices   []string
	entries   int
	last      time.Time
	lastHeard int64
	filter    string
	today     int
	valid     bool
}

// openHistory runs as the page opens: it starts with every play shown, and
// reads the history if that has not been done.
func (a *app) openHistory() {
	a.historyFilter = historyAll
	a.loadHistory()
}

// deviceName is the name a play's device is shown under. A play from before
// plays carried a device was made here.
func (a *app) deviceName(e history.Entry) string {
	if e.Device == "" || e.Device == a.device {
		return thisComputer
	}
	return e.Device
}

// historyRows returns the rows of the page for the filter, newest play first
// and grouped by day, building them again only when the history or the day has
// changed.
func (a *app) historyRows(now time.Time) *historyMemo {
	now = now.Local()
	var last time.Time
	var heard int64
	if n := len(a.history); n > 0 {
		last, heard = a.history[n-1].PlayedAt, a.history[n-1].PlayedMS
	}
	memo := &a.historyPlays
	today := history.CalendarDay(now)
	if memo.valid && memo.entries == len(a.history) && memo.last.Equal(last) && memo.lastHeard == heard && memo.filter == a.historyFilter && memo.today == today {
		return memo
	}
	built := historyMemo{entries: len(a.history), last: last, lastHeard: heard, filter: a.historyFilter, today: today, valid: true}
	seen := map[string]bool{}
	group, day := -1, 0
	for i := len(a.history) - 1; i >= 0; i-- {
		e := a.history[i]
		device := a.deviceName(e)
		seen[device] = true
		counted := history.Counted(e)
		switch {
		case a.historyFilter == historyCounted && !counted,
			a.historyFilter == historySkipped && counted,
			strings.HasPrefix(a.historyFilter, historyDevicePrefix) && a.historyFilter != historyDevicePrefix+device:
			continue
		}
		at := e.PlayedAt.Local()
		if d := history.CalendarDay(at); group < 0 || d != day {
			built.rows = append(built.rows, historyRow{header: true, title: historyDay(at, now)})
			group, day = len(built.rows)-1, d
		}
		built.rows[group].plays++
		if counted {
			built.rows[group].heardMS += e.PlayedMS
		}
		built.rows = append(built.rows, historyRow{entry: e, counted: counted, place: i})
	}
	for device := range seen {
		built.devices = append(built.devices, device)
	}
	slices.SortFunc(built.devices, func(x, y string) int {
		if (x == thisComputer) != (y == thisComputer) {
			if x == thisComputer {
				return -1
			}
			return 1
		}
		return strings.Compare(x, y)
	})
	*memo = built
	return memo
}

// historyDay names a day of listening: Today, Yesterday, or its date.
func historyDay(at, now time.Time) string {
	switch history.CalendarDay(now) - history.CalendarDay(at) {
	case 0:
		return "Today"
	case 1:
		return "Yesterday"
	}
	return at.Format("Mon, Jan 2")
}

// historyTotals is what a day's heading says of it: how many plays, and about
// how long the ones that count were heard for.
func historyTotals(plays int, heardMS int64) string {
	minutes := (heardMS + 30_000) / 60_000
	if heardMS > 0 && minutes == 0 {
		minutes = 1
	}
	return pluralPlays(plays) + " · ~" + strconv.FormatInt(minutes, 10) + " min"
}

// historyNote is the line under a play: when it began, where, and how much of
// it was heard.
func historyNote(e history.Entry, counted bool, device string, playing bool) string {
	parts := []string{e.PlayedAt.Local().Format("15:04"), device}
	heard := time.Duration(e.PlayedMS) * time.Millisecond
	total := time.Duration(e.DurationMS) * time.Millisecond
	switch {
	case playing:
		parts = append(parts, "playing now")
	case e.EventID == "" && e.PlayedMS == 0:
		// Recorded before plays were timed: how much was heard is not known.
	case !counted:
		parts = append(parts, "skipped at "+clock(heard))
	case e.Completed:
		parts = append(parts, "heard to the end")
	case total > 0:
		parts = append(parts, clock(heard)+" of "+clock(total))
	default:
		parts = append(parts, "heard "+clock(heard))
	}
	return strings.Join(parts, " · ")
}

// historyItem is the song a play of the history plays again.
func historyItem(e history.Entry) youtube.MusicItem {
	title := e.Title
	if title == "" {
		title = "Unknown track"
	}
	return musicItem(library.Item{
		Source: media.Source(e.Source), Key: e.VideoID, URL: e.VideoID,
		Title: title, Artist: e.Artist, DurationMS: e.DurationMS,
	})
}

// historyPage lists every play, newest first and day by day, whichever device
// it happened on. The plays are the rows of a list, so only the ones in view
// are built: the history holds thousands.
func (a *app) historyPage(c *ui.Context) {
	if len(a.history) == 0 && a.historyLoaded {
		// Nothing to retry: only listening fills the history.
		ui.Column(c).Fill().Center().Children(func() {
			a.message(c, m3.IconHistory, "No plays yet", "Songs you listen to in Meiro, on any device you sync, are listed here.", "", nil)
		})
		return
	}
	memo := a.historyRows(c.Now())
	a.historyFilters(c, memo.devices)
	rows := memo.rows
	if len(rows) == 0 {
		if a.historyLoaded {
			ui.Column(c).Grow(1).Center().Children(func() {
				a.message(c, m3.IconHistory, "No plays here", "No play matches this filter.", "", nil)
			})
		}
		return
	}
	// The last row of the list is the note about what counts.
	a.list.Key = func(i int) any {
		switch {
		case i == len(rows):
			return "note"
		case i > len(rows):
			return nil
		case rows[i].header:
			return "day:" + rows[i].title
		}
		return "play:" + strconv.Itoa(rows[i].place)
	}
	a.list.Label = func(i int) string {
		if i >= len(rows) {
			return ""
		}
		if rows[i].header {
			return rows[i].title
		}
		return rows[i].entry.Title
	}
	ui.List(c, a.list, len(rows)+1, func(i int) {
		switch {
		case i == len(rows):
			a.historyNoteRow(c)
		case rows[i].header:
			a.historyHeading(c, rows[i], i == 0)
		default:
			a.historyPlay(c, rows[i])
		}
	}).Grow(1).Padding(0, 0, a.clearance())
}

// historyFilters is the row of chips that choose which plays are listed: all of
// them, the counted and the skipped, and one chip for each device.
func (a *app) historyFilters(c *ui.Context, devices []string) {
	ui.Row(c).Gap(8).Wrap().Shrink(0).Padding(4, pageGutter, 12).Children(func() {
		chip := func(label, filter string) {
			if selected := a.historyFilter == filter; m3.Chip(c, label, selected, "history-filter-"+filter).Clicked() && !selected {
				a.historyFilter = filter
				a.list.ScrollTo(0, ui.Start)
			}
		}
		chip("All", historyAll)
		chip("Counted", historyCounted)
		chip("Skipped", historySkipped)
		for _, device := range devices {
			chip(device, historyDevicePrefix+device)
		}
	})
}

// historyHeading names a day, and says how much was played in it.
func (a *app) historyHeading(c *ui.Context, r historyRow, first bool) {
	sc := m3.Of(c).Scheme
	top := float32(20)
	if first {
		top = 4
	}
	ui.Row(c).FillWidth().Padding(top, pageGutter, 4).Gap(12).AlignItems(ui.Center).Children(func() {
		m3.EmphasizedText(c, m3.TitleLarge, r.title).SingleLine().Grow(1).MinWidth(0).TextColor(sc.OnSurface)
		m3.Text(c, m3.BodyMedium, historyTotals(r.plays, r.heardMS)).SingleLine().Shrink(0).TextColor(sc.OnSurfaceVariant)
	})
}

// historyPlay is one play as a song row. A press on it does not change the song
// unless it is a double one, since the list is for looking back; a play the
// stats do not count is faded.
func (a *app) historyPlay(c *ui.Context, r historyRow) {
	item := historyItem(r.entry)
	playing := a.record.active && a.record.eventID != "" && a.record.eventID == r.entry.EventID
	a.songRow(c, item, []youtube.MusicItem{item}, songOptions{
		list: "history", position: r.place, track: -1, inset: true, doublePress: true,
		note: historyNote(r.entry, r.counted, a.deviceName(r.entry), playing), dim: !r.counted && !playing,
	})
}

// historyNoteRow says what the stats count.
func (a *app) historyNoteRow(c *ui.Context) {
	body := "Plays on this device. "
	if a.settings.SyncEmail != "" {
		body = "Plays on every device signed in to your Meiro account. "
	}
	body += "A play that is under 30 seconds and under half the song is faded: it stays here, but the stats and the recommendations do not count it."
	m3.Text(c, m3.BodySmall, body).Padding(16, pageGutter, 8).TextColor(m3.Of(c).Scheme.OnSurfaceVariant)
}
