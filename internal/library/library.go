// Package library holds the user's own playlists: lists of tracks from any
// provider, made by hand and kept on this device. It is not a service's
// library: nothing here is read from or written to an account.
//
// A list keeps tracks as they were added, and refuses one it already holds,
// whether the same track or the same recording from another provider. The
// recording comparison is package match's, so a title's own noise and a
// length's edges do not make a duplicate.
//
// Every change stamps the time it was made on the list and the tracks it
// touched, and a track or a list taken away leaves a tombstone, so a sync can
// tell the server what happened and the server's answer can be weighed against
// it, the later of two changes winning.
package library

import (
	"cmp"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/elianiva/meiro/internal/fsutil"
	"github.com/elianiva/meiro/internal/match"
	"github.com/elianiva/meiro/media"
)

// State is what the app made of a track when it was added to a list: whether
// it is one the app knows is the same as a recording it looked up, one it
// cannot be sure of, or one the listener marked themselves. It is match's
// vocabulary, kept as a string so a file carries it plainly.
type State string

const (
	// Matched is a track the app is sure is a known recording.
	Matched State = "matched"
	// Unknown is a track the app will not claim to know.
	Unknown State = "unknown"
	// Other is the listener's own mark, which no comparison sets.
	Other State = "other"
)

// Item is one track in a list, flattened to what plays it and what names it.
// Source and Key identify it in its provider: a video ID for YouTube Music,
// a page URL for SoundCloud. URL keeps the provider page when there is one,
// which is the link a SoundCloud track is resolved by.
type Item struct {
	Source     media.Source `json:"source,omitempty"`
	Key        string       `json:"key"`
	URL        string       `json:"url,omitempty"`
	Title      string       `json:"title,omitempty"`
	Artist     string       `json:"artist,omitempty"`
	DurationMS int64        `json:"durationMs,omitempty"`
	State      State        `json:"state,omitempty"`
	// Thumbnail is the track's own cover, and AlbumThumbnail the cover of the
	// album or playlist it came from, which stands in for a track that has
	// none of its own. Both are picture addresses, kept with the track.
	Thumbnail      string `json:"thumbnail,omitempty"`
	AlbumThumbnail string `json:"albumThumbnail,omitempty"`
	// Why is the reason a track was recommended, for a list the app made.
	Why string `json:"why,omitempty"`
	// MBID is the MusicBrainz recording the track is linked to, kept when the
	// state is Matched and empty otherwise.
	MBID string `json:"mbid,omitempty"`
	// ArtistMBID is the MusicBrainz artist the track's artist is linked to,
	// and ArtistState says what the link is, in the same words as State.
	ArtistMBID  string    `json:"artistMbid,omitempty"`
	ArtistState State     `json:"artistState,omitempty"`
	AddedAt     time.Time `json:"addedAt,omitempty"`
	// UpdatedAt is when the track was last added, moved or switched to
	// another provider, which is what a sync weighs it by. A file written
	// before it existed reads as AddedAt.
	UpdatedAt time.Time `json:"updatedAt,omitempty"`
	// Sources are the providers that hold the track, in preference order. It
	// is what lets a track added from two providers be switched between them
	// while it is in a list. It is empty for a track only one holds.
	Sources []media.SourceRef `json:"sources,omitempty"`
}

// Tombstone is a track that was taken out of a list, kept so the removal can
// be told to the server: a track that is only missing from the next push is
// not one the server would know to forget. It names the track as the server
// knew it, by the provider and key the track played from when it went.
type Tombstone struct {
	Source media.Source `json:"source,omitempty"`
	Key    string       `json:"key"`
	URL    string       `json:"url,omitempty"`
	Title  string       `json:"title,omitempty"`
	Artist string       `json:"artist,omitempty"`
	// RemovedAt is when the track went.
	RemovedAt time.Time `json:"removedAt"`
}

// DeletedList is a list that was taken away, kept for the same reason as a
// Tombstone. ServerID is empty for a list made here, whose server identity
// follows from ID.
type DeletedList struct {
	ID        string    `json:"id"`
	ServerID  string    `json:"serverId,omitempty"`
	Name      string    `json:"name"`
	DeletedAt time.Time `json:"deletedAt"`
}

// tombstoneLife is how long a removal is remembered. A device that has not
// reached the server for longer than this may bring a removed track back, which
// is the price of a file that does not grow without end.
const tombstoneLife = 90 * 24 * time.Hour

// Library is one of the user's lists.
type Library struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"createdAt"`
	// ServerID is the identity the server knows the list by when the list came
	// from the server. It is empty for a list made here, whose server identity
	// follows from ID.
	ServerID string `json:"serverId,omitempty"`
	// UpdatedAt is when the list itself last changed: it was made, renamed or
	// given a cover. Its tracks are records of their own with their own times,
	// so changing one leaves this alone, or a device that had not heard of a
	// rename would send the old name as the newer record. A file written before
	// it existed reads as CreatedAt.
	UpdatedAt time.Time `json:"updatedAt,omitempty"`
	// Cover is the picture of the album or playlist a list was copied from,
	// kept as the list's own. It is empty for a list made by hand.
	Cover string `json:"cover,omitempty"`
	Items []Item `json:"items"`
	// Removed are the tracks taken out of the list that the server has yet to
	// be told of, oldest first.
	Removed []Tombstone `json:"removed,omitempty"`
}

// file is what a store writes to disk.
type file struct {
	Libraries []Library     `json:"libraries"`
	Deleted   []DeletedList `json:"deleted,omitempty"`
}

// Store keeps the user's lists in one JSON file, and is read and changed on
// the main thread alone. A store with no path keeps them in memory only,
// which is what a test uses.
type Store struct {
	path string

	mu      sync.Mutex
	libs    []Library
	deleted []DeletedList
}

// New reads the lists kept at path, or starts empty when the file is missing
// or unreadable. A path that is empty keeps the lists in memory.
func New(path string) *Store {
	s := &Store{path: path}
	s.load()
	return s
}

// load reads the file, and leaves the store empty when it cannot. A file that
// is damaged is reported and replaced by the next save rather than stopping
// the app.
func (s *Store) load() {
	if s.path == "" {
		return
	}
	data, err := os.ReadFile(s.path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("reading libraries: %v", err)
		}
		return
	}
	var f file
	if err := json.Unmarshal(data, &f); err != nil {
		log.Printf("libraries in %s are not readable, starting empty: %v", s.path, err)
		return
	}
	s.libs, s.deleted = f.Libraries, f.Deleted
	s.settle(time.Now())
}

// settle gives what an older file lacks the time it can be read as, and lets
// go of removals older than tombstoneLife. It runs as the file is read, before
// anything else sees the lists.
func (s *Store) settle(now time.Time) {
	for i := range s.libs {
		lib := &s.libs[i]
		for j := range lib.Items {
			item := &lib.Items[j]
			if item.UpdatedAt.IsZero() {
				item.UpdatedAt = item.AddedAt
			}
		}
		if lib.UpdatedAt.IsZero() {
			lib.UpdatedAt = lib.CreatedAt
		}
		lib.Removed = slices.DeleteFunc(lib.Removed, func(t Tombstone) bool { return now.Sub(t.RemovedAt) > tombstoneLife })
	}
	s.deleted = slices.DeleteFunc(s.deleted, func(d DeletedList) bool { return now.Sub(d.DeletedAt) > tombstoneLife })
}

// save writes the lists atomically. It has no lock of its own: its callers
// hold s.mu.
func (s *Store) save() error {
	if s.path == "" {
		return nil
	}
	data, err := json.MarshalIndent(file{Libraries: s.libs, Deleted: s.deleted}, "", "  ")
	if err != nil {
		return err
	}
	return fsutil.WriteFileAtomic(s.path, data, 0o600)
}

// index is the place of a library by its ID, or -1. It has no lock of its
// own: its callers hold s.mu.
func (s *Store) index(id string) int {
	for i := range s.libs {
		if s.libs[i].ID == id {
			return i
		}
	}
	return -1
}

// List returns the lists, oldest first.
func (s *Store) List() []Library {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.libs)
}

// Deleted returns the lists taken away that the server has yet to be told of,
// oldest first.
func (s *Store) Deleted() []DeletedList {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.deleted)
}

// Get returns the list with an ID.
func (s *Store) Get(id string) (Library, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if i := s.index(id); i >= 0 {
		return s.libs[i], true
	}
	return Library{}, false
}

// Create makes an empty list named name.
func (s *Store) Create(name string) (Library, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return Library{}, errors.New("library: a name is needed")
	}
	now := time.Now()
	lib := Library{ID: newID(), Name: name, CreatedAt: now, UpdatedAt: now}
	s.mu.Lock()
	s.libs = append(s.libs, lib)
	err := s.save()
	s.mu.Unlock()
	return lib, err
}

// ReplaceItems puts the given tracks in a list in place of what it held, for a
// list the app makes again each day. It writes the file once.
func (s *Store) ReplaceItems(id string, items []Item) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	now := time.Now()
	kept := make([]Item, 0, len(items))
	for _, item := range items {
		if item.AddedAt.IsZero() {
			item.AddedAt = now
		}
		item.UpdatedAt = now
		if slices.ContainsFunc(kept, func(held Item) bool { return sameItem(held, item) }) {
			continue
		}
		kept = append(kept, item)
	}
	lib := &s.libs[i]
	// The tracks the new list lacks are the ones removed; the ones it holds
	// again, in whatever place, are told to the server afresh with their new
	// places, so a removal remembered for one of them is let go.
	for _, old := range lib.Items {
		if !slices.ContainsFunc(kept, func(item Item) bool { return sameRef(item, old) }) {
			lib.remember(old.tombstone(now))
		}
	}
	for _, item := range kept {
		lib.forget(item)
	}
	lib.Items = kept
	return s.save()
}

// SetCover keeps the picture a list shows as its own.
func (s *Store) SetCover(id, cover string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	s.libs[i].Cover = cover
	s.libs[i].UpdatedAt = time.Now()
	return s.save()
}

// Picture is the picture a list shows: the one it was given, and otherwise the
// first cover one of its tracks has.
func (l Library) Picture() string {
	if l.Cover != "" {
		return l.Cover
	}
	for _, item := range l.Items {
		if item.Thumbnail != "" {
			return item.Thumbnail
		}
		if item.AlbumThumbnail != "" {
			return item.AlbumThumbnail
		}
	}
	return ""
}

// Rename changes a list's name.
func (s *Store) Rename(id, name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return errors.New("library: a name is needed")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	s.libs[i].Name = name
	s.libs[i].UpdatedAt = time.Now()
	return s.save()
}

// Delete takes a list away, and remembers it was there so the server can be
// told.
func (s *Store) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	lib := s.libs[i]
	s.deleted = append(s.deleted, DeletedList{ID: lib.ID, ServerID: lib.ServerID, Name: lib.Name, DeletedAt: time.Now()})
	s.libs = slices.Delete(s.libs, i, i+1)
	return s.save()
}

// AddItem puts a track in a list unless the list already holds it, whether
// the same track or the same recording from another provider. It reports
// whether the track was added, so a caller can say a track was already there.
func (s *Store) AddItem(id string, item Item) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return false, errors.New("library: no library with that ID")
	}
	for _, existing := range s.libs[i].Items {
		if sameItem(existing, item) {
			return false, nil
		}
	}
	now := time.Now()
	if item.AddedAt.IsZero() {
		item.AddedAt = now
	}
	item.UpdatedAt = now
	s.libs[i].forget(item)
	s.libs[i].Items = append(s.libs[i].Items, item)
	return true, s.save()
}

// AddItems puts several tracks in a list as AddItem does one, leaving out the
// ones the list already holds, or that an earlier one of them repeats, and
// writes the file once. It returns how many it added.
func (s *Store) AddItems(id string, items []Item) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return 0, errors.New("library: no library with that ID")
	}
	added := 0
	now := time.Now()
	for _, item := range items {
		if slices.ContainsFunc(s.libs[i].Items, func(existing Item) bool { return sameItem(existing, item) }) {
			continue
		}
		if item.AddedAt.IsZero() {
			item.AddedAt = now
		}
		item.UpdatedAt = now
		s.libs[i].forget(item)
		s.libs[i].Items = append(s.libs[i].Items, item)
		added++
	}
	if added == 0 {
		return 0, nil
	}
	return added, s.save()
}

// RemoveItem takes the track with a key out of a list.
func (s *Store) RemoveItem(id, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	lib := &s.libs[i]
	now := time.Now()
	before := len(lib.Items)
	lib.Items = slices.DeleteFunc(lib.Items, func(item Item) bool {
		if item.Key != key {
			return false
		}
		lib.remember(item.tombstone(now))
		return true
	})
	if len(lib.Items) == before {
		return errors.New("library: no such item")
	}
	return s.save()
}

// SetSource makes source the one a track in a list plays from, keeping the
// provider its key names. The server knows a track by the provider it plays
// from, so a switch is a removal of the track as it was and an addition of it
// as it is. It reports whether the list held the track.
func (s *Store) SetSource(id, key string, source media.Source) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return false, errors.New("library: no library with that ID")
	}
	lib := &s.libs[i]
	for j := range lib.Items {
		if lib.Items[j].Key == key {
			item := &lib.Items[j]
			now := time.Now()
			was := item.tombstone(now)
			item.Source = source
			if active, ref := item.ActiveRef(); active != was.Source || ref != was.Key {
				lib.remember(was)
			}
			lib.forget(*item)
			item.UpdatedAt = now
			return true, s.save()
		}
	}
	return false, nil
}

// SetLink records what the track with a key in a list is linked to on
// MusicBrainz: Matched with the recording's MBID, Other when the listener says
// it is not there, or Unknown, which takes any link away. It reports whether
// the list held the track.
func (s *Store) SetLink(id, key string, state State, mbid string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return false, errors.New("library: no library with that ID")
	}
	for j := range s.libs[i].Items {
		if s.libs[i].Items[j].Key == key {
			item := &s.libs[i].Items[j]
			item.State, item.MBID = state, ""
			if state == Matched {
				item.MBID = mbid
			}
			return true, s.save()
		}
	}
	return false, nil
}

// SetArtistLink records what an artist is linked to on MusicBrainz in every
// track, in every list, that credits the artist by that name once folded. It
// returns how many tracks it changed.
func (s *Store) SetArtistLink(artist string, state State, mbid string) (int, error) {
	name := match.Normalize(artist)
	if name == "" {
		return 0, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	changed := 0
	for i := range s.libs {
		for j := range s.libs[i].Items {
			item := &s.libs[i].Items[j]
			if match.Normalize(item.Artist) != name {
				continue
			}
			item.ArtistState, item.ArtistMBID = state, ""
			if state == Matched {
				item.ArtistMBID = mbid
			}
			changed++
		}
	}
	if changed == 0 {
		return 0, nil
	}
	return changed, s.save()
}

// MoveItem moves the track at from to to, both places in the list.
func (s *Store) MoveItem(id string, from, to int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	items := s.libs[i].Items
	if from < 0 || from >= len(items) || to < 0 || to >= len(items) {
		return errors.New("library: no such place")
	}
	if from == to {
		return nil
	}
	item := items[from]
	items = slices.Delete(items, from, from+1)
	items = slices.Insert(items, to, item)
	// Every track between the two places moved by one, and the server keeps a
	// place for each, so each of them changed.
	now := time.Now()
	for j := min(from, to); j <= max(from, to); j++ {
		items[j].UpdatedAt = now
	}
	s.libs[i].Items = items
	return s.save()
}

// RemoteItem is a change the server reports to one track of a list. Item is
// the track as the server describes it, with the time the server holds it at as
// its UpdatedAt.
type RemoteItem struct {
	Item     Item
	Position int
	Deleted  bool
}

// AddRemote makes a list for one that came from the server, which keeps the
// identity the server gave it. The list is as new as the server's record, and a
// deletion remembered for the same list is let go: the server's record is the
// later of the two, or the caller would not be adding it.
func (s *Store) AddRemote(serverID, name, cover string, updated time.Time) (Library, error) {
	lib := Library{ID: newID(), ServerID: serverID, Name: name, CreatedAt: updated, UpdatedAt: updated, Cover: cover}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleted = slices.DeleteFunc(s.deleted, func(d DeletedList) bool { return d.ServerID == serverID })
	s.libs = append(s.libs, lib)
	return lib, s.save()
}

// ApplyRemoteList takes a list's name and cover from the server, as of the time
// the server holds them at. Unlike Rename, it is not a change made here, so it
// stamps that time and not the clock's.
func (s *Store) ApplyRemoteList(id, name, cover string, updated time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	s.libs[i].Name, s.libs[i].Cover, s.libs[i].UpdatedAt = name, cover, updated
	return s.save()
}

// DropRemote takes a list away because the server says it is gone. Unlike
// Delete it leaves nothing to tell: the server already knows.
func (s *Store) DropRemote(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return errors.New("library: no library with that ID")
	}
	s.libs = slices.Delete(s.libs, i, i+1)
	return s.save()
}

// ApplyRemoteItems folds what the server reports of a list's tracks into the
// list, the later record of each track winning, and writes the file once. A
// track is matched by the provider it plays from and its id there. None of it
// stamps the clock or leaves a tombstone, because none of it is a change made
// here: a removal the server reported must not go back to it as a new one.
//
// A deletion removes the track if the list's copy is not newer than it. A track
// the list lacks is added unless this device removed it later than the server
// says it was added. A track the list holds takes the server's place and
// pictures if the server's record is newer. The tracks the server placed are
// then put in the order it gave, and the rest keep their places. It returns how
// many tracks it changed.
func (s *Store) ApplyRemoteItems(id string, changes []RemoteItem) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(id)
	if i < 0 {
		return 0, errors.New("library: no library with that ID")
	}
	lib := &s.libs[i]
	// places is where each track asks to stand: the place the server gave it,
	// or, for one the server did not mention, the one it already has.
	places := make([]int, len(lib.Items))
	for j := range places {
		places[j] = j
	}
	changed := 0
	for _, change := range changes {
		remote := change.Item
		j := slices.IndexFunc(lib.Items, func(held Item) bool { return sameRef(held, remote) })
		switch {
		case change.Deleted:
			if j >= 0 && !remote.UpdatedAt.Before(lib.Items[j].UpdatedAt) {
				lib.Items = slices.Delete(lib.Items, j, j+1)
				places = slices.Delete(places, j, j+1)
				changed++
			}
		case j >= 0:
			held := &lib.Items[j]
			if !remote.UpdatedAt.After(held.UpdatedAt) {
				continue
			}
			held.UpdatedAt, places[j] = remote.UpdatedAt, change.Position
			if held.Thumbnail == "" {
				held.Thumbnail = remote.Thumbnail
			}
			if held.AlbumThumbnail == "" {
				held.AlbumThumbnail = remote.AlbumThumbnail
			}
			changed++
		default:
			source, ref := remote.ActiveRef()
			if slices.ContainsFunc(lib.Removed, func(t Tombstone) bool {
				return t.Source == source && t.Key == ref && t.RemovedAt.After(remote.UpdatedAt)
			}) {
				continue
			}
			lib.forget(remote)
			lib.Items = append(lib.Items, remote)
			places = append(places, change.Position)
			changed++
		}
	}
	if changed == 0 {
		return 0, nil
	}
	// Two tracks may ask for one place, as two devices each put one at the end:
	// the one with the lower reference goes first, so every device that sees
	// both puts them in the same order.
	order := make([]int, len(lib.Items))
	for j := range order {
		order[j] = j
	}
	slices.SortStableFunc(order, func(x, y int) int {
		if c := cmp.Compare(places[x], places[y]); c != 0 {
			return c
		}
		xs, xid := lib.Items[x].ActiveRef()
		ys, yid := lib.Items[y].ActiveRef()
		return cmp.Or(strings.Compare(string(xs), string(ys)), strings.Compare(xid, yid))
	})
	sorted := make([]Item, len(order))
	for j, from := range order {
		sorted[j] = lib.Items[from]
	}
	lib.Items = sorted
	return changed, s.save()
}

// ActiveRef is the provider a track plays from and the id the provider names it
// by: the video ID of a YouTube Music track, the page URL of a SoundCloud one.
// It follows Source, so a track held on two providers has the reference of the
// one it plays from now. It is how the server tells one track of a list from
// another, and the id is empty for a track that has none.
func (i Item) ActiveRef() (media.Source, string) {
	source, key, url := i.Source, i.Key, i.URL
	if source == "" {
		source = media.SourceYTM
	}
	if i.Source != "" {
		if j := slices.IndexFunc(i.Sources, func(ref media.SourceRef) bool { return ref.Source == i.Source }); j >= 0 {
			ref := i.Sources[j]
			key, url = ref.ID, ref.URL
		}
	}
	if source == media.SourceSoundCloud {
		return source, url
	}
	return source, key
}

// sameRef reports whether two tracks are the one the server knows by one
// reference. A track with no id is the same as none.
func sameRef(a, b Item) bool {
	as, aid := a.ActiveRef()
	bs, bid := b.ActiveRef()
	return aid != "" && as == bs && aid == bid
}

// tombstone is the removal of the track as the server knows it, made at a
// moment.
func (i Item) tombstone(at time.Time) Tombstone {
	source, id := i.ActiveRef()
	t := Tombstone{Source: source, Key: id, Title: i.Title, Artist: i.Artist, RemovedAt: at}
	if source == media.SourceSoundCloud {
		t.URL = id
	}
	return t
}

// remember keeps a removal, replacing an earlier one of the same track. A
// track with no id was never sent, so there is nothing to tell. It has no lock
// of its own: its callers hold the store's.
func (l *Library) remember(t Tombstone) {
	if t.Key == "" {
		return
	}
	l.Removed = slices.DeleteFunc(l.Removed, func(held Tombstone) bool { return held.Source == t.Source && held.Key == t.Key })
	l.Removed = append(l.Removed, t)
}

// forget lets go of the removal of a track that is in the list again.
func (l *Library) forget(item Item) {
	source, id := item.ActiveRef()
	l.Removed = slices.DeleteFunc(l.Removed, func(held Tombstone) bool { return held.Source == source && held.Key == id })
}

// sameItem reports whether a list already holds a track: the same track by
// provider and key, or the same recording by the folded names and the length.
// A track with no name is never the same as another, so two nameless rows
// both fit.
func sameItem(a, b Item) bool {
	if a.Source == b.Source && a.Key != "" && a.Key == b.Key {
		return true
	}
	if match.Normalize(a.Title) == "" || match.Normalize(b.Title) == "" {
		return false
	}
	return match.Match(track(a), track(b)) == match.Matched
}

// track is what a list item offers a recording comparison.
func track(item Item) match.Track {
	return match.Track{
		Title:    item.Title,
		Artist:   item.Artist,
		Duration: time.Duration(item.DurationMS) * time.Millisecond,
	}
}

// newID makes a list's ID: a short random hex string, or the clock if the
// random source is not there.
func newID() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(b[:])
}
