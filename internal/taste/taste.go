// Package taste builds a picture of what the listener likes from what they
// played and what they saved, and nothing else: it reads no network and keeps
// nothing, so a profile is a pure function of the history and the clock.
//
// A play is read as a vote. Hearing a song through is a vote for it and for its
// artist; leaving it early is a vote against; a like, a playlist and a download
// are stronger votes for. Votes fade with age, so what was loved a year ago
// counts for less than what was loved last week, and a song played again and
// again counts for less each time, so one loop does not drown the rest.
package taste

import (
	"math"
	"sort"
	"strings"
	"time"

	"github.com/elianiva/meiro/internal/match"
)

// Play is one time a song was played: which, when, and how much of it was
// heard. A play with no known length is judged by how long it ran.
type Play struct {
	Provider string
	ID       string
	Title    string
	Artist   string
	At       time.Time
	// PlayedMS is how much was heard and DurationMS the song's length, both in
	// milliseconds. DurationMS is zero when it is not known.
	PlayedMS   int64
	DurationMS int64
	Completed  bool
}

// Kind is what a listener did with a song besides playing it.
type Kind int

const (
	// Like is a thumbs up.
	Like Kind = iota
	// Saved is the song put in a playlist.
	Saved
	// Downloaded is the song kept for offline.
	Downloaded
)

// Signal is a song the listener acted on, without playing it just then.
type Signal struct {
	Provider string
	ID       string
	Title    string
	Artist   string
	Kind     Kind
	At       time.Time
}

// The votes: what each thing a listener does is worth. They are the knobs of
// the whole algorithm, so they are named and kept together.
const (
	// VoteHeard is a song heard through, VotePartly one heard for a good part,
	// and VoteSkipped one left early, which is a vote against.
	VoteHeard   = 1.0
	VotePartly  = 0.3
	VoteSkipped = -0.7
	// VoteLike, VoteSaved and VoteDownloaded are the explicit votes for.
	VoteLike       = 3.0
	VoteSaved      = 2.0
	VoteDownloaded = 2.0
	// Repeat is how much each further hearing of a song is worth next to the
	// one before it.
	Repeat = 0.8
)

// HalfLife is how long it takes a vote to lose half its weight. Explicit votes
// fade a third as fast, since a like says more than a play.
const HalfLife = 30 * 24 * time.Hour

const explicitSlowdown = 3

// Profile is what is known of the listener's taste at one moment.
type Profile struct {
	now time.Time
	// artists and tracks are scores: higher is liked more, below zero is
	// disliked. They are keyed by the folded artist, and by the song.
	artists map[string]float64
	tracks  map[string]float64
	// names keeps one display name for each artist, and last the latest time a
	// song was played.
	names map[string]string
	last  map[string]time.Time
	// plays counts the plays of each artist, and left how many were left early.
	plays map[string]int
	left  map[string]int
}

// TrackKey names a song for the profile: its folded title and artist, so the
// same recording from two providers is one song.
func TrackKey(title, artist string) string {
	t, a := match.Normalize(title), match.Normalize(artist)
	if t == "" {
		return ""
	}
	return t + "\x00" + a
}

// ArtistKey names an artist for the profile.
func ArtistKey(artist string) string { return match.Normalize(artist) }

// Build reads a history of plays and signals into a profile as of now.
func Build(plays []Play, signals []Signal, now time.Time) *Profile {
	p := &Profile{
		now:     now,
		artists: map[string]float64{}, tracks: map[string]float64{},
		names: map[string]string{}, last: map[string]time.Time{},
		plays: map[string]int{}, left: map[string]int{},
	}
	sorted := append([]Play(nil), plays...)
	sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].At.Before(sorted[j].At) })
	heard := map[string]int{}
	for _, play := range sorted {
		if play.At.After(now) {
			continue
		}
		key := TrackKey(play.Title, play.Artist)
		if key == "" {
			continue
		}
		artist := ArtistKey(play.Artist)
		vote := voteOf(play)
		if vote > 0 {
			// Each further hearing counts for less than the one before.
			vote *= math.Pow(Repeat, float64(heard[key]))
			heard[key]++
		}
		weight := vote * decay(now.Sub(play.At), HalfLife)
		p.tracks[key] += weight
		if artist != "" {
			p.artists[artist] += weight
			p.names[artist] = play.Artist
			p.plays[artist]++
			if vote < 0 {
				p.left[artist]++
			}
		}
		if play.At.After(p.last[key]) {
			p.last[key] = play.At
		}
	}
	for _, s := range signals {
		if s.At.After(now) {
			continue
		}
		key := TrackKey(s.Title, s.Artist)
		if key == "" {
			continue
		}
		vote := map[Kind]float64{Like: VoteLike, Saved: VoteSaved, Downloaded: VoteDownloaded}[s.Kind]
		weight := vote * decay(now.Sub(s.At), HalfLife*explicitSlowdown)
		p.tracks[key] += weight
		if artist := ArtistKey(s.Artist); artist != "" {
			p.artists[artist] += weight
			if p.names[artist] == "" {
				p.names[artist] = s.Artist
			}
		}
	}
	return p
}

// voteOf is what one play says: heard through, heard in part, or left early. A
// play with no known length is judged by the counting rule the stats use: thirty
// seconds, or a good part of it.
func voteOf(play Play) float64 {
	if play.Completed {
		return VoteHeard
	}
	if play.DurationMS > 0 {
		ratio := float64(play.PlayedMS) / float64(play.DurationMS)
		switch {
		case ratio >= 0.8:
			return VoteHeard
		case ratio >= 0.3 || play.PlayedMS >= 30_000:
			return VotePartly
		default:
			return VoteSkipped
		}
	}
	if play.PlayedMS >= 90_000 {
		return VoteHeard
	}
	if play.PlayedMS >= 30_000 {
		return VotePartly
	}
	return VoteSkipped
}

// decay is the share of a vote left after it has aged by d.
func decay(d, halfLife time.Duration) float64 {
	if d <= 0 {
		return 1
	}
	return math.Pow(0.5, float64(d)/float64(halfLife))
}

// Artist is the score of an artist, 0 when nothing is known of them.
func (p *Profile) Artist(artist string) float64 { return p.artists[ArtistKey(artist)] }

// Track is the score of a song, 0 when nothing is known of it.
func (p *Profile) Track(title, artist string) float64 { return p.tracks[TrackKey(title, artist)] }

// Known reports whether the listener has played or acted on the artist.
func (p *Profile) Known(artist string) bool {
	_, ok := p.artists[ArtistKey(artist)]
	return ok
}

// LastPlayed is when a song was last played, and whether it ever was.
func (p *Profile) LastPlayed(title, artist string) (time.Time, bool) {
	at, ok := p.last[TrackKey(title, artist)]
	return at, ok
}

// SkipRate is the share of an artist's plays that were left early, from 0 to 1,
// and 0 for an artist never played.
func (p *Profile) SkipRate(artist string) float64 {
	key := ArtistKey(artist)
	if p.plays[key] == 0 {
		return 0
	}
	return float64(p.left[key]) / float64(p.plays[key])
}

// Entry is one artist or song and how much it is liked.
type Entry struct {
	Key   string
	Name  string
	Score float64
}

// TopArtists are the n artists liked most, best first. An artist scored at or
// below zero is not among them.
func (p *Profile) TopArtists(n int) []Entry {
	var out []Entry
	for key, score := range p.artists {
		if score > 0 {
			out = append(out, Entry{Key: key, Name: p.names[key], Score: score})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Score != out[j].Score {
			return out[i].Score > out[j].Score
		}
		return out[i].Key < out[j].Key
	})
	if len(out) > n {
		out = out[:n]
	}
	return out
}

// Songs are the n songs liked most, best first, with their title and artist
// recovered from the key.
func (p *Profile) Songs(n int) []Entry {
	var out []Entry
	for key, score := range p.tracks {
		if score > 0 {
			title, artist, _ := strings.Cut(key, "\x00")
			out = append(out, Entry{Key: key, Name: title + " by " + artist, Score: score})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Score != out[j].Score {
			return out[i].Score > out[j].Score
		}
		return out[i].Key < out[j].Key
	})
	if len(out) > n {
		out = out[:n]
	}
	return out
}

// Empty reports whether nothing is known: no plays and no signals counted.
func (p *Profile) Empty() bool { return len(p.artists) == 0 && len(p.tracks) == 0 }
