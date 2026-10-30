package sync

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"slices"
	"sync"
	"time"

	"github.com/elianiva/meiro/internal/fsutil"
)

// MaxBatch is how many records of one kind go in one request. It stays well
// inside the server's own limit of 1000 events.
const MaxBatch = 200

// MaxEvents caps the plays kept while the server is out of reach; the oldest
// go first once it fills.
const MaxEvents = 5000

// outboxFile is what an outbox writes to disk.
type outboxFile struct {
	Events       []Event        `json:"events"`
	Libraries    []Library      `json:"libraries"`
	LibraryItems []LibraryItems `json:"libraryItems"`
	Downloads    []Download     `json:"downloads"`
	Links        Links          `json:"links"`
}

// clone copies the file so a write can marshal it without holding the lock,
// and without a change made meanwhile writing through the same arrays.
func (f outboxFile) clone() outboxFile {
	out := outboxFile{
		Events:    slices.Clone(f.Events),
		Libraries: slices.Clone(f.Libraries),
		Downloads: slices.Clone(f.Downloads),
		Links: Links{
			Songs:      slices.Clone(f.Links.Songs),
			Artists:    slices.Clone(f.Links.Artists),
			Alternates: slices.Clone(f.Links.Alternates),
		},
	}
	out.LibraryItems = make([]LibraryItems, len(f.LibraryItems))
	for i, group := range f.LibraryItems {
		out.LibraryItems[i] = LibraryItems{LibraryID: group.LibraryID, Items: slices.Clone(group.Items)}
	}
	return out
}

// Outbox keeps the records that have not been taken by the server yet, on
// disk, so that they survive a restart. A record stays until a push takes it,
// so a record is never lost to a network that is down.
//
// Changes are in memory at once and written by a goroutine of the outbox's
// own, so callers never wait on the disk. It is safe for concurrent use. The
// zero value keeps records in memory only.
type Outbox struct {
	path string

	mu      sync.Mutex
	file    outboxFile
	dirty   bool
	running bool
	done    chan struct{}
}

// Open reads the outbox kept at path. A missing or unreadable file is an
// empty outbox. An empty path keeps the records in memory.
func Open(path string) *Outbox {
	o := &Outbox{path: path}
	if path == "" {
		return o
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("sync: reading the outbox: %v", err)
		}
		return o
	}
	if err := json.Unmarshal(data, &o.file); err != nil {
		log.Printf("sync: outbox in %s is not readable, starting empty: %v", path, err)
		o.file = outboxFile{}
	}
	return o
}

// AddEvent keeps one play to send.
func (o *Outbox) AddEvent(e Event) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if i := slices.IndexFunc(o.file.Events, func(x Event) bool { return x.ClientEventID == e.ClientEventID }); i >= 0 {
		o.file.Events[i] = e
	} else {
		o.file.Events = append(o.file.Events, e)
	}
	if over := len(o.file.Events) - MaxEvents; over > 0 {
		o.file.Events = slices.Delete(o.file.Events, 0, over)
	}
	o.changedLocked()
}

// AddLibraries keeps libraries to send, keyed by their ID, so pushing the same
// list again does not queue it twice. A list already waiting is replaced by the
// one given unless the waiting one is the later record, and one that waits for
// another reason, as the tombstone of a list this device never had, stays.
func (o *Outbox) AddLibraries(libraries []Library) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, lib := range libraries {
		i := slices.IndexFunc(o.file.Libraries, func(x Library) bool { return x.ID == lib.ID })
		switch {
		case i < 0:
			o.file.Libraries = append(o.file.Libraries, lib)
		case !lib.UpdatedAt.Before(o.file.Libraries[i].UpdatedAt):
			o.file.Libraries[i] = lib
		}
	}
	o.changedLocked()
}

// AddLibraryItems keeps items of one library to send, keyed by their track, in
// the same way: the later record of a track waits, and a track waiting for
// another reason stays.
func (o *Outbox) AddLibraryItems(libraryID string, items []LibraryItem) {
	o.mu.Lock()
	defer o.mu.Unlock()
	i := slices.IndexFunc(o.file.LibraryItems, func(x LibraryItems) bool { return x.LibraryID == libraryID })
	if i < 0 {
		o.file.LibraryItems = append(o.file.LibraryItems, LibraryItems{LibraryID: libraryID})
		i = len(o.file.LibraryItems) - 1
	}
	group := &o.file.LibraryItems[i]
	for _, item := range items {
		j := slices.IndexFunc(group.Items, func(x LibraryItem) bool {
			return x.Track.Provider == item.Track.Provider && x.Track.ProviderTrackID == item.Track.ProviderTrackID
		})
		switch {
		case j < 0:
			group.Items = append(group.Items, item)
		case !item.UpdatedAt.Before(group.Items[j].UpdatedAt):
			group.Items[j] = item
		}
	}
	o.changedLocked()
}

// SetDownloads replaces the pending downloads, keyed by track and device.
func (o *Outbox) SetDownloads(downloads []Download) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.file.Downloads = slices.Clone(downloads)
	o.changedLocked()
}

// EventCount is how many plays wait.
func (o *Outbox) EventCount() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return len(o.file.Events)
}

// Pending is how many records of every kind wait.
func (o *Outbox) Pending() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	n := len(o.file.Events) + len(o.file.Libraries) + len(o.file.Downloads) + o.file.Links.Len()
	for _, group := range o.file.LibraryItems {
		n += len(group.Items)
	}
	return n
}

// Config returns what a push works from, without the lock held during the
// network calls.
type config struct {
	events    []Event
	libraries []Library
	items     []LibraryItems
	downloads []Download
	links     Links
}

func (o *Outbox) snapshot() config {
	o.mu.Lock()
	defer o.mu.Unlock()
	out := config{
		events:    slices.Clone(o.file.Events),
		libraries: slices.Clone(o.file.Libraries),
		downloads: slices.Clone(o.file.Downloads),
		links: Links{
			Songs:      slices.Clone(o.file.Links.Songs),
			Artists:    slices.Clone(o.file.Links.Artists),
			Alternates: slices.Clone(o.file.Links.Alternates),
		},
	}
	out.items = make([]LibraryItems, len(o.file.LibraryItems))
	for i, group := range o.file.LibraryItems {
		out.items[i] = LibraryItems{LibraryID: group.LibraryID, Items: slices.Clone(group.Items)}
	}
	return out
}

func libraryIDs(libraries []Library) []string {
	ids := make([]string, len(libraries))
	for i, l := range libraries {
		ids[i] = l.ID
	}
	return ids
}

// Push sends everything that waits, in batches, oldest first. A batch the
// server answers 200 to is taken out of the queue; anything else stops the
// push and stays, so the next try sends it again. It returns how many records
// were taken this time.
func Push(ctx context.Context, c *Client, o *Outbox) (int, error) {
	snap := o.snapshot()
	sent := 0

	for _, batch := range chunks(snap.libraries, MaxBatch) {
		if _, err := c.PushLibraries(ctx, batch); err != nil {
			return sent, err
		}
		o.removeLibraries(libraryIDs(batch))
		sent += len(batch)
	}

	for _, group := range snap.items {
		for _, batch := range chunks(group.Items, MaxBatch) {
			_, err := c.PushLibraryItems(ctx, LibraryItems{LibraryID: group.LibraryID, Items: batch})
			var api *APIError
			if errors.As(err, &api) && api.Status == http.StatusNotFound {
				// The server does not know the library, or has it deleted,
				// as another device may have deleted it. It will never take
				// these items, so they are let go rather than failing every
				// push; the next read of what changed brings the deletion.
				log.Printf("sync: the server has no library %s, dropping its %d waiting tracks", group.LibraryID, len(group.Items))
				o.dropLibraryItems(group.LibraryID)
				break
			}
			if err != nil {
				return sent, err
			}
			o.removeLibraryItems(group.LibraryID, batch)
			sent += len(batch)
		}
	}

	for _, batch := range chunks(snap.downloads, MaxBatch) {
		if _, err := c.PushDownloads(ctx, batch); err != nil {
			return sent, err
		}
		o.removeDownloads(batch)
		sent += len(batch)
	}

	for _, batch := range linkBatches(snap.links) {
		if err := c.PushLinks(ctx, batch); err != nil {
			return sent, err
		}
		o.removeLinks(batch)
		sent += batch.Len()
	}

	for _, batch := range chunks(snap.events, MaxBatch) {
		accepted, duplicates, err := c.PushEvents(ctx, batch)
		if err != nil {
			return sent, err
		}
		if len(accepted)+len(duplicates) == 0 {
			// The server took the request but named nothing: stop rather than
			// loop on the same batch forever.
			return sent, errors.New("sync: sunucu kabul edilen olay dondurmedi")
		}
		o.removeEvents(append(append([]string{}, accepted...), duplicates...))
		sent += len(batch)
	}
	return sent, nil
}

func chunks[T any](items []T, size int) [][]T {
	if len(items) == 0 {
		return nil
	}
	var out [][]T
	for i := 0; i < len(items); i += size {
		out = append(out, items[i:min(i+size, len(items))])
	}
	return out
}

func (o *Outbox) removeEvents(ids []string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.file.Events = slices.DeleteFunc(o.file.Events, func(e Event) bool { return slices.Contains(ids, e.ClientEventID) })
	o.changedLocked()
}

func (o *Outbox) removeLibraries(ids []string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.file.Libraries = slices.DeleteFunc(o.file.Libraries, func(l Library) bool { return slices.Contains(ids, l.ID) })
	o.changedLocked()
}

func (o *Outbox) removeLibraryItems(libraryID string, items []LibraryItem) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for i := range o.file.LibraryItems {
		if o.file.LibraryItems[i].LibraryID != libraryID {
			continue
		}
		sent := o.file.LibraryItems[i].Items
		o.file.LibraryItems[i].Items = slices.DeleteFunc(sent, func(item LibraryItem) bool {
			for _, s := range items {
				if item.Track.Provider == s.Track.Provider && item.Track.ProviderTrackID == s.Track.ProviderTrackID {
					return true
				}
			}
			return false
		})
		break
	}
	o.changedLocked()
}

// dropLibraryItems forgets every waiting track of a library.
func (o *Outbox) dropLibraryItems(libraryID string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.file.LibraryItems = slices.DeleteFunc(o.file.LibraryItems, func(g LibraryItems) bool { return g.LibraryID == libraryID })
	o.changedLocked()
}

func (o *Outbox) removeDownloads(sent []Download) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.file.Downloads = slices.DeleteFunc(o.file.Downloads, func(d Download) bool {
		for _, s := range sent {
			if d.Device == s.Device && d.Track.Provider == s.Track.Provider && d.Track.ProviderTrackID == s.Track.ProviderTrackID {
				return true
			}
		}
		return false
	})
	o.changedLocked()
}

// changedLocked schedules a write. o.mu is held.
func (o *Outbox) changedLocked() {
	if o.path == "" {
		return
	}
	o.dirty = true
	if o.running {
		return
	}
	o.running, o.done = true, make(chan struct{})
	go o.drain(o.done)
}

func (o *Outbox) drain(done chan struct{}) {
	defer close(done)
	for {
		o.mu.Lock()
		if !o.dirty {
			o.running = false
			o.mu.Unlock()
			return
		}
		file := o.file.clone()
		o.dirty = false
		o.mu.Unlock()
		data, err := json.Marshal(file)
		if err == nil {
			err = fsutil.WriteFileAtomic(o.path, data, 0o600)
		}
		if err != nil {
			log.Printf("sync: saving the outbox: %v", err)
		}
	}
}

// Wait returns once what changed so far is on disk.
func (o *Outbox) Wait() {
	o.mu.Lock()
	done, running := o.done, o.running
	o.mu.Unlock()
	if running {
		<-done
	}
}

// Backoff is how long to wait before the attempt after the given number of
// failures, which doubles each time up to a cap. It is what keeps a server
// that is down from being hammered.
func Backoff(attempt int) time.Duration {
	const base, cap = 2 * time.Second, 5 * time.Minute
	if attempt < 1 {
		attempt = 1
	}
	d := base << min(attempt-1, 20)
	if d > cap || d <= 0 {
		return cap
	}
	return d
}
