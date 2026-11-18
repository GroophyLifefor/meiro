// Package downloads keeps the tracks the user chose to download and keep:
// the audio file outlives the playback cache, and what is known of each one
// is read back between runs. It is not a cache: an entry stays until the user
// removes it.
//
// A download is identified by its provider and its key, the same pair the
// playback cache uses, so a YouTube Music video ID and a SoundCloud page URL
// can never name the same entry.
package downloads

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"

	"github.com/elianiva/meiro/internal/fsutil"
	"github.com/elianiva/meiro/media"
)

// Item is one downloaded track: what plays it, what names it, and when it was
// downloaded and last played. File is the audio file's name inside the
// store's directory.
type Item struct {
	Source media.Source `json:"source,omitempty"`
	Key    string       `json:"key"`
	URL    string       `json:"url,omitempty"`
	Title  string       `json:"title,omitempty"`
	Artist string       `json:"artist,omitempty"`
	// Thumbnail is the track's cover, or its album's when it has none, kept
	// with the file so the downloads page can show it.
	Thumbnail      string    `json:"thumbnail,omitempty"`
	SizeBytes      int64     `json:"sizeBytes,omitempty"`
	DownloadedAt   time.Time `json:"downloadedAt,omitempty"`
	LastListenedAt time.Time `json:"lastListenedAt,omitempty"`
	File           string    `json:"file,omitempty"`
	// Sources are the providers that hold the track, in preference order. It
	// is what lets a kept track that two providers hold be switched between
	// them while it is shown. It is empty for a track only one holds.
	Sources []media.SourceRef `json:"sources,omitempty"`
	// Active is the provider a kept track plays from now, when it is another
	// than the one its file came from. It is empty while it plays from Source.
	Active media.Source `json:"active,omitempty"`
}

// Tombstone is a download that was removed, kept so the removal can be told to
// the server: a download that is only missing from the next push is not one
// the server would know to forget. It names the track as the server knew it,
// by the provider and key the track played from when it went.
type Tombstone struct {
	Source media.Source `json:"source,omitempty"`
	Key    string       `json:"key"`
	URL    string       `json:"url,omitempty"`
	Title  string       `json:"title,omitempty"`
	Artist string       `json:"artist,omitempty"`
	// RemovedAt is when the download went.
	RemovedAt time.Time `json:"removedAt"`
}

// tombstoneLife is how long a removal is remembered. A device that has not
// reached the server for longer than this may leave a removed download on it,
// which is the price of a file that does not grow without end.
const tombstoneLife = 90 * 24 * time.Hour

// file is what a store writes to disk.
type file struct {
	Items   []Item      `json:"items"`
	Removed []Tombstone `json:"removed,omitempty"`
}

// Store keeps the downloaded tracks in one JSON index beside their audio
// files, and is read and changed on the main thread alone. A store with no
// index path keeps the entries in memory only, which is what a test uses.
type Store struct {
	indexPath string
	dir       string

	mu      sync.Mutex
	items   []Item
	removed []Tombstone
}

// New reads the index kept at indexPath, with the audio files in dir. A
// missing or unreadable index starts empty. An empty indexPath keeps the
// entries in memory.
func New(indexPath, dir string) *Store {
	s := &Store{indexPath: indexPath, dir: dir}
	s.load()
	return s
}

// load reads the index, and leaves the store empty when it cannot. A damaged
// index is reported and replaced by the next save rather than stopping the
// app.
func (s *Store) load() {
	if s.indexPath == "" {
		return
	}
	data, err := os.ReadFile(s.indexPath)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("reading downloads: %v", err)
		}
		return
	}
	var f file
	if err := json.Unmarshal(data, &f); err != nil {
		log.Printf("downloads in %s are not readable, starting empty: %v", s.indexPath, err)
		return
	}
	s.items, s.removed = f.Items, f.Removed
	s.prune(time.Now())
}

// prune lets go of the removals older than tombstoneLife. It has no lock of
// its own: its callers hold s.mu, or have the store to themselves.
func (s *Store) prune(now time.Time) {
	s.removed = slices.DeleteFunc(s.removed, func(t Tombstone) bool { return now.Sub(t.RemovedAt) > tombstoneLife })
}

// Dir is the directory the downloaded audio files live in.
func (s *Store) Dir() string { return s.dir }

// save writes the index atomically. It has no lock of its own: its callers
// hold s.mu.
func (s *Store) save() error {
	if s.indexPath == "" {
		return nil
	}
	data, err := json.MarshalIndent(file{Items: s.items, Removed: s.removed}, "", "  ")
	if err != nil {
		return err
	}
	return fsutil.WriteFileAtomic(s.indexPath, data, 0o600)
}

// index is the place of an entry by its provider and key, or -1. It has no
// lock of its own: its callers hold s.mu.
func (s *Store) index(source media.Source, key string) int {
	for i := range s.items {
		if s.items[i].Source == source && s.items[i].Key == key {
			return i
		}
	}
	return -1
}

// List returns the downloads, newest first.
func (s *Store) List() []Item {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := slices.Clone(s.items)
	slices.SortFunc(out, func(a, b Item) int { return b.DownloadedAt.Compare(a.DownloadedAt) })
	return out
}

// Get returns the download of a track.
func (s *Store) Get(source media.Source, key string) (Item, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if i := s.index(source, key); i >= 0 {
		return s.items[i], true
	}
	return Item{}, false
}

// Path returns where the audio of a track is stored, whether or not it is
// there yet, and the file name to record with it. ext names the container the
// download is written in.
func (s *Store) Path(source media.Source, key, ext string) (path, name string) {
	name = fileName(source, key, ext)
	if s.dir == "" {
		return "", name
	}
	return filepath.Join(s.dir, name), name
}

// File returns the path of a track's downloaded audio, when it is downloaded
// and the store keeps its files in a directory.
func (s *Store) File(source media.Source, key string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(source, key)
	if i < 0 || s.dir == "" || s.items[i].File == "" {
		return "", false
	}
	return filepath.Join(s.dir, s.items[i].File), true
}

// Add records a completed download, replacing the entry for the same track
// and naming its audio file.
func (s *Store) Add(item Item) error {
	if item.Key == "" {
		return errors.New("downloads: a key is needed")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if item.Source == "" {
		item.Source = media.SourceYTM
	}
	if i := s.index(item.Source, item.Key); i >= 0 {
		s.items[i] = item
	} else {
		s.items = append(s.items, item)
	}
	// A track downloaded again is no longer a removal to tell.
	ref := item.tombstone(time.Time{})
	s.removed = slices.DeleteFunc(s.removed, func(t Tombstone) bool { return t.Source == ref.Source && t.Key == ref.Key })
	return s.save()
}

// Remove takes the download of a track out of the index, and returns the
// entry it removed so the caller can delete its file.
func (s *Store) Remove(source media.Source, key string) (Item, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(source, key)
	if i < 0 {
		return Item{}, false, nil
	}
	removed := s.items[i]
	s.items = slices.Delete(s.items, i, i+1)
	s.remember(removed.tombstone(time.Now()))
	return removed, true, s.save()
}

// Removed returns the downloads taken out that the server has yet to hear of,
// oldest first. A removal is forgotten after 90 days.
func (s *Store) Removed() []Tombstone {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.prune(time.Now())
	out := slices.Clone(s.removed)
	slices.SortStableFunc(out, func(a, b Tombstone) int { return a.RemovedAt.Compare(b.RemovedAt) })
	return out
}

// remember keeps a removal, replacing an earlier one of the same track. A
// track with no key was never sent, so there is nothing to tell. It has no
// lock of its own: its callers hold s.mu.
func (s *Store) remember(t Tombstone) {
	if t.Key == "" {
		return
	}
	s.prune(t.RemovedAt)
	s.removed = slices.DeleteFunc(s.removed, func(held Tombstone) bool { return held.Source == t.Source && held.Key == t.Key })
	s.removed = append(s.removed, t)
}

// tombstone is the removal of the download as the server knows it, made at a
// moment: by the provider the track played from, which is the active one when
// the track was switched to another that holds it, and by that provider's key,
// or the page URL for SoundCloud.
func (i Item) tombstone(at time.Time) Tombstone {
	source, key, url := i.Source, i.Key, i.URL
	if source == "" {
		source = media.SourceYTM
	}
	active := i.Active
	if active == "" {
		active = source
	}
	if j := slices.IndexFunc(i.Sources, func(ref media.SourceRef) bool { return ref.Source == active }); j >= 0 {
		ref := i.Sources[j]
		source, key, url = ref.Source, ref.ID, ref.URL
	}
	if source == media.SourceSoundCloud {
		if url == "" {
			url = key
		}
		key = url
	}
	return Tombstone{Source: source, Key: key, URL: url, Title: i.Title, Artist: i.Artist, RemovedAt: at}
}

// SetActive makes active the provider a kept track plays from now, without
// changing the provider its file came from. It reports whether the track is
// downloaded.
func (s *Store) SetActive(source media.Source, key string, active media.Source) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(source, key)
	if i < 0 {
		return false, nil
	}
	s.items[i].Active = active
	return true, s.save()
}

// Touch records that a track was played now, so the downloads page can show
// when each was last listened to. It does nothing for a track that is not
// downloaded.
func (s *Store) Touch(source media.Source, key string, at time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.index(source, key)
	if i < 0 {
		return
	}
	s.items[i].LastListenedAt = at
	_ = s.save()
}

// fileName names a track's audio file after its provider and key, so the same
// track always maps to the same name.
func fileName(source media.Source, key, ext string) string {
	sum := sha256.Sum256([]byte(string(source) + ":" + key))
	return hex.EncodeToString(sum[:]) + ext
}
