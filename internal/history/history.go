// Package history keeps what was played on this device, and sums it up for
// the stats page.
package history

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"slices"
	"sort"
	"sync"
	"time"

	"github.com/elianiva/meiro/internal/fsutil"
)

// MaxEntries caps the history: about a year of heavy listening. Old entries
// go first when it overflows.
const MaxEntries = 5000

// Entry is one played track. The first fields are what the stats page counts;
// the rest carry what a play is synced with: where it came from, how much of
// it was heard, and the stable ID the server counts it by.
type Entry struct {
	VideoID  string    `json:"videoId"`
	Title    string    `json:"title,omitempty"`
	Artist   string    `json:"artist,omitempty"`
	PlayedAt time.Time `json:"playedAt"`

	// Source is the music source's short name ("ytm" or "soundcloud"), and
	// VideoID is that provider's own id for the track.
	Source string `json:"source,omitempty"`
	// DurationMS is the track's length and PlayedMS how much of it was heard,
	// both in milliseconds.
	DurationMS int64 `json:"durationMs,omitempty"`
	PlayedMS   int64 `json:"playedMs,omitempty"`
	// Completed says the track was heard to its end.
	Completed bool `json:"completed,omitempty"`
	// Device is the machine the play happened on.
	Device string `json:"device,omitempty"`
	// EventID is the play's own stable ID, generated once. It is what the
	// server deduplicates by, so a play sent twice counts once. It is empty
	// for a play recorded before timing existed.
	EventID string `json:"eventId,omitempty"`
}

// Counts reports whether a play meets the counting rule the stats use: at
// least thirty seconds heard, or half the track, whichever comes first.
func Counts(playedMS, durationMS int64) bool {
	if playedMS >= 30000 {
		return true
	}
	return durationMS > 0 && playedMS*2 >= durationMS
}

// Counted reports whether a play is one the stats count. Plays recorded before
// they carried timing (no EventID) count as they always did.
func Counted(e Entry) bool {
	if e.EventID == "" {
		return true
	}
	return Counts(e.PlayedMS, e.DurationMS)
}

// Append adds entries to the end of a history and drops its oldest entries
// beyond MaxEntries, in place.
func Append(history []Entry, entries ...Entry) []Entry {
	history = append(history, entries...)
	if len(history) > MaxEntries {
		// Slide the newest entries down in place: reallocating the whole
		// history on every play once it is full copied it all each time.
		history = slices.Delete(history, 0, len(history)-MaxEntries)
	}
	return history
}

// Merge adds plays that came from elsewhere to a history, such as the plays of
// another device that the server holds. A play whose EventID the history
// already has is left out, so a play that comes back is not counted twice. The
// result is in the order the plays happened, oldest first, and keeps the
// latest MaxEntries. It returns how many plays it added.
func Merge(entries, incoming []Entry) ([]Entry, int) {
	known := make(map[string]bool, len(entries))
	for _, e := range entries {
		if e.EventID != "" {
			known[e.EventID] = true
		}
	}
	added := 0
	for _, e := range incoming {
		if e.EventID != "" {
			if known[e.EventID] {
				continue
			}
			known[e.EventID] = true
		}
		entries = append(entries, e)
		added++
	}
	if added == 0 {
		return entries, 0
	}
	slices.SortStableFunc(entries, func(a, b Entry) int { return a.PlayedAt.Compare(b.PlayedAt) })
	if len(entries) > MaxEntries {
		entries = slices.Delete(entries, 0, len(entries)-MaxEntries)
	}
	return entries, added
}

// Read reads a history file. A missing or unreadable file is an empty
// history rather than an error the user must see.
func Read(path string) []Entry {
	if path == "" {
		return nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("reading listening history: %v", err)
		}
		return nil
	}
	var entries []Entry
	if err := json.Unmarshal(data, &entries); err != nil {
		log.Printf("listening history in %s is not readable, starting fresh: %v", path, err)
		return nil
	}
	return entries
}

// Write replaces a history file atomically.
func Write(path string, entries []Entry) error {
	if entries == nil {
		entries = []Entry{} // "[]", not "null"
	}
	data, err := json.Marshal(entries)
	if err != nil {
		return err
	}
	return fsutil.WriteFileAtomic(path, data, 0o600)
}

// Writer writes the history off the main thread, one write at a time, so that
// the writes of two quick plays cannot land out of order and leave the older
// one on disk. A save asked for while another is being written replaces what
// waits behind it. The zero value is ready to use.
type Writer struct {
	mu      sync.Mutex
	path    string
	next    []Entry
	pending bool
	running bool
	done    chan struct{}
}

// Save writes entries to path soon. It keeps its own copy of them. Saving
// no entries writes an empty history, which is how the history is cleared.
func (w *Writer) Save(path string, entries []Entry) {
	if path == "" {
		return
	}
	entries = slices.Clone(entries)
	w.mu.Lock()
	w.path, w.next, w.pending = path, entries, true
	if w.running {
		w.mu.Unlock()
		return
	}
	w.running = true
	w.done = make(chan struct{})
	done := w.done
	w.mu.Unlock()
	go w.drain(done)
}

func (w *Writer) drain(done chan struct{}) {
	defer close(done)
	for {
		w.mu.Lock()
		if !w.pending {
			w.running = false
			w.mu.Unlock()
			return
		}
		path, entries := w.path, w.next
		w.next, w.pending = nil, false
		w.mu.Unlock()
		if err := Write(path, entries); err != nil {
			log.Printf("saving listening history: %v", err)
		}
	}
}

// Wait returns when every save asked for so far is on disk.
func (w *Writer) Wait() {
	w.mu.Lock()
	if !w.running {
		w.mu.Unlock()
		return
	}
	done := w.done
	w.mu.Unlock()
	<-done
}

// Day is one day of listening.
type Day struct {
	Day   time.Time
	Plays int
}

// Count is the plays of one track or artist.
type Count struct {
	Title  string
	Artist string
	Plays  int
}

// Summary is what the stats page shows.
type Summary struct {
	Plays      int
	Artists    int
	ByDay      []Day
	TopTracks  []Count
	TopArtists []Count
}

// Summarize folds entries into totals, daily counts for the days days ending
// on the day of now, and the five most played tracks and artists.
func Summarize(entries []Entry, now time.Time, days int) Summary {
	var summary Summary
	tracks := make(map[string]*Count)
	artists := make(map[string]*Count)
	cutoff := startOfDay(now).AddDate(0, 0, -days+1)
	summary.ByDay = make([]Day, days)
	for d := range summary.ByDay {
		summary.ByDay[d].Day = cutoff.AddDate(0, 0, d)
	}
	// Days are told apart by their calendar number, so placing a play costs
	// arithmetic instead of formatting its date into a string to look up.
	firstDay := CalendarDay(cutoff)
	for _, e := range entries {
		if e.PlayedAt.Before(cutoff) || !Counted(e) {
			continue
		}
		summary.Plays++
		key := e.VideoID
		if key == "" {
			key = e.Title
		}
		tc := tracks[key]
		if tc == nil {
			tc = &Count{Title: e.Title, Artist: e.Artist}
			tracks[key] = tc
		}
		tc.Plays++
		if e.Artist != "" {
			ac := artists[e.Artist]
			if ac == nil {
				ac = &Count{Title: e.Artist}
				artists[e.Artist] = ac
			}
			ac.Plays++
		}
		if i := CalendarDay(e.PlayedAt) - firstDay; i >= 0 && i < days {
			summary.ByDay[i].Plays++
		}
	}
	summary.Artists = len(artists)
	summary.TopTracks = top(tracks, 5)
	summary.TopArtists = top(artists, 5)
	return summary
}

// CalendarDay numbers the day t falls on, in its own zone, counting from a
// fixed origin, so consecutive days differ by one whatever the zone's offset.
func CalendarDay(t time.Time) int {
	y, m, d := t.Date()
	return int(time.Date(y, m, d, 0, 0, 0, 0, time.UTC).Unix() / 86400)
}

// top keeps the n highest counts, most plays first.
func top(counts map[string]*Count, n int) []Count {
	all := make([]Count, 0, len(counts))
	for _, c := range counts {
		all = append(all, *c)
	}
	sort.Slice(all, func(i, j int) bool {
		if all[i].Plays != all[j].Plays {
			return all[i].Plays > all[j].Plays
		}
		return all[i].Title < all[j].Title
	})
	return all[:min(len(all), n)]
}

// startOfDay is midnight of the day t falls on, in its own zone.
func startOfDay(t time.Time) time.Time {
	y, m, d := t.Date()
	return time.Date(y, m, d, 0, 0, 0, 0, t.Location())
}

// The waiting and the movement a sample may have and still count as listening.
// A longer wait is a pause, a stall or the machine asleep, and the time in it
// is not credited; the slack covers the position being read a moment apart
// from the clock.
const (
	watchMaxGap = 2 * time.Second
	watchSlack  = 500 * time.Millisecond
)

// Watch measures how long one play was listened to, from the positions the
// player reports, so that a pause, a stall or a seek forward is not counted as
// listening. The zero value has listened to nothing. One Watch is one play.
type Watch struct {
	have     bool
	at       time.Time
	pos      time.Duration
	listened time.Duration
}

// Break says the sound stopped flowing between samples, as it does while the
// player is paused, buffering or moving to another position. The next sample
// only sets where counting resumes from.
func (w *Watch) Break() { w.have = false }

// Observe takes a sample: when it was taken and the player's position. The
// movement since the last sample is credited only when the two are close
// enough in time and in place to be the sound playing on.
func (w *Watch) Observe(at time.Time, position time.Duration) {
	if w.have {
		wait, moved := at.Sub(w.at), position-w.pos
		if wait > 0 && wait <= watchMaxGap && moved >= 0 && moved <= wait+watchSlack {
			w.listened += moved
		}
	}
	w.have, w.at, w.pos = true, at, position
}

// Listened is how long the play has been listened to.
func (w *Watch) Listened() time.Duration { return w.listened }
