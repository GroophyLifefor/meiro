// Package mblinks keeps what the app has learned about which MusicBrainz record
// a song and its artist are, and which provider's copy of a song is the same
// recording as another's. It belongs to the songs, not to any playlist, so a
// song linked while searching is linked wherever it appears; and it is kept so
// the app never asks MusicBrainz or a provider again for what it already knows.
//
// Every record carries when it last changed. Two copies of the store, on two
// devices or on a device and the server, are merged by keeping the later of the
// two records for each song, artist and pair of providers.
package mblinks

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/elianiva/meiro/internal/fsutil"
	"github.com/elianiva/meiro/internal/match"
)

// The states a song or an artist is in: tied to a record, not known to be, or
// marked by the listener as a record MusicBrainz does not hold.
const (
	Matched = "matched"
	Unknown = "unknown"
	Other   = "other"
)

// RecheckAfter is how long the app waits before it tries again to tie a song it
// tried and could not be sure of, or to find a song on the other provider.
const RecheckAfter = 30 * 24 * time.Hour

// Song is what is known of one provider's copy of a song, named by the
// provider and the id the provider's playback uses (a video ID for YouTube
// Music, a page address for SoundCloud).
type Song struct {
	Provider string `json:"provider"`
	ID       string `json:"id"`
	State    string `json:"state"`
	// MBID is the recording, kept when State is Matched.
	MBID string `json:"mbid,omitempty"`
	// Artist is the artist the song names, ArtistMBID and ArtistState what the
	// artist is, so a song carries the answer for its artist too.
	Artist      string `json:"artist,omitempty"`
	ArtistMBID  string `json:"artistMbid,omitempty"`
	ArtistState string `json:"artistState,omitempty"`
	// CheckedAt is when the app last tried to tie the song by itself, whether
	// or not it could, so it does not try again at once.
	CheckedAt time.Time `json:"checkedAt,omitempty"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// Artist is what is known of an artist by the name the providers give it,
// folded so that spelling and case do not make two.
type Artist struct {
	Name      string    `json:"name"`
	State     string    `json:"state"`
	MBID      string    `json:"mbid,omitempty"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// Alt is the same recording on the other provider: for the copy named by
// Provider and ID, the copy named by OtherProvider, OtherID and OtherURL. An
// Alt with no OtherID says the other provider was looked in and does not hold
// it, until CheckedAt is old enough to look again.
type Alt struct {
	Provider      string    `json:"provider"`
	ID            string    `json:"id"`
	OtherProvider string    `json:"otherProvider,omitempty"`
	OtherID       string    `json:"otherId,omitempty"`
	OtherURL      string    `json:"otherUrl,omitempty"`
	CheckedAt     time.Time `json:"checkedAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

// Data is everything a store holds, as it is written to disk and exchanged
// with the server.
type Data struct {
	Songs   []Song   `json:"songs,omitempty"`
	Artists []Artist `json:"artists,omitempty"`
	Alts    []Alt    `json:"alts,omitempty"`
}

// Store is the links, kept in one JSON file. It is safe for concurrent use. A
// store with no path keeps them in memory, which is what a test uses.
type Store struct {
	path string
	now  func() time.Time

	mu      sync.Mutex
	songs   map[string]Song
	artists map[string]Artist
	alts    map[string]Alt
}

// Open reads the links kept at path, or starts empty when the file is missing or
// unreadable.
func Open(path string) *Store {
	s := &Store{
		path: path, now: time.Now,
		songs: map[string]Song{}, artists: map[string]Artist{}, alts: map[string]Alt{},
	}
	if path == "" {
		return s
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("reading links: %v", err)
		}
		return s
	}
	var d Data
	if err := json.Unmarshal(data, &d); err != nil {
		log.Printf("links in %s are not readable, starting empty: %v", path, err)
		return s
	}
	for _, song := range d.Songs {
		s.songs[songKey(song.Provider, song.ID)] = song
	}
	for _, artist := range d.Artists {
		s.artists[ArtistKey(artist.Name)] = artist
	}
	for _, alt := range d.Alts {
		s.alts[songKey(alt.Provider, alt.ID)] = alt
	}
	return s
}

func songKey(provider, id string) string { return provider + "\x00" + id }

// ArtistKey is what an artist is known by: the name folded, so "Daft Punk" and
// "daft punk" are one.
func ArtistKey(name string) string { return match.Normalize(name) }

// Song returns what is known of a provider's copy of a song.
func (s *Store) Song(provider, id string) (Song, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	song, ok := s.songs[songKey(provider, id)]
	return song, ok
}

// SetSong keeps what is known of a song, stamped with now when it carries no
// time of its own, and returns what it kept.
func (s *Store) SetSong(song Song) (Song, error) {
	if song.UpdatedAt.IsZero() {
		song.UpdatedAt = s.now()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.songs[songKey(song.Provider, song.ID)] = song
	return song, s.saveLocked()
}

// Artist returns what is known of an artist by name.
func (s *Store) Artist(name string) (Artist, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	artist, ok := s.artists[ArtistKey(name)]
	return artist, ok
}

// SetArtist keeps what is known of an artist, stamped with now when it carries
// no time, and returns what it kept. An artist with no name that folds to
// something is not kept.
func (s *Store) SetArtist(artist Artist) (Artist, error) {
	key := ArtistKey(artist.Name)
	if key == "" {
		return artist, nil
	}
	if artist.UpdatedAt.IsZero() {
		artist.UpdatedAt = s.now()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.artists[key] = artist
	return artist, s.saveLocked()
}

// Alt returns the other provider's copy of a song, or the record that none was
// found.
func (s *Store) Alt(provider, id string) (Alt, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	alt, ok := s.alts[songKey(provider, id)]
	return alt, ok
}

// SetAlt keeps the other provider's copy of a song, and returns what it kept.
func (s *Store) SetAlt(alt Alt) (Alt, error) {
	if alt.UpdatedAt.IsZero() {
		alt.UpdatedAt = s.now()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.alts[songKey(alt.Provider, alt.ID)] = alt
	return alt, s.saveLocked()
}

// Data is everything the store holds.
func (s *Store) Data() Data {
	s.mu.Lock()
	defer s.mu.Unlock()
	var d Data
	for _, song := range s.songs {
		d.Songs = append(d.Songs, song)
	}
	for _, artist := range s.artists {
		d.Artists = append(d.Artists, artist)
	}
	for _, alt := range s.alts {
		d.Alts = append(d.Alts, alt)
	}
	return d
}

// Merge folds another copy of the links into the store, keeping the later
// record of each, and returns how many it took. A record that is no later than
// the one held is dropped, so merging what the store already has changes
// nothing.
func (s *Store) Merge(d Data) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	took := 0
	for _, song := range d.Songs {
		key := songKey(song.Provider, song.ID)
		if have, ok := s.songs[key]; !ok || song.UpdatedAt.After(have.UpdatedAt) {
			s.songs[key] = song
			took++
		}
	}
	for _, artist := range d.Artists {
		key := ArtistKey(artist.Name)
		if key == "" {
			continue
		}
		if have, ok := s.artists[key]; !ok || artist.UpdatedAt.After(have.UpdatedAt) {
			s.artists[key] = artist
			took++
		}
	}
	for _, alt := range d.Alts {
		key := songKey(alt.Provider, alt.ID)
		if have, ok := s.alts[key]; !ok || alt.UpdatedAt.After(have.UpdatedAt) {
			s.alts[key] = alt
			took++
		}
	}
	if took == 0 {
		return 0, nil
	}
	return took, s.saveLocked()
}

// Fresh reports whether a time is recent enough that what was checked then need
// not be checked again.
func (s *Store) Fresh(checked time.Time) bool {
	return !checked.IsZero() && s.now().Sub(checked) < RecheckAfter
}

// saveLocked writes the store atomically. s.mu is held.
func (s *Store) saveLocked() error {
	if s.path == "" {
		return nil
	}
	var d Data
	for _, song := range s.songs {
		d.Songs = append(d.Songs, song)
	}
	for _, artist := range s.artists {
		d.Artists = append(d.Artists, artist)
	}
	for _, alt := range s.alts {
		d.Alts = append(d.Alts, alt)
	}
	data, err := json.Marshal(d)
	if err != nil {
		return err
	}
	return fsutil.WriteFileAtomic(s.path, data, 0o600)
}

// Clean trims a provider name, so a record written by a client that left it
// blank reads as YouTube Music's.
func Clean(provider string) string {
	provider = strings.TrimSpace(provider)
	if provider == "" {
		return "ytm"
	}
	return provider
}
