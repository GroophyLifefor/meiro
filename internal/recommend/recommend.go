// Package recommend turns a taste profile and a pool of candidate songs into a
// short list: the songs most likely to please, with a reason for each, and
// enough variety that the list is not one artist five times.
//
// It does not find the candidates. Where they come from (a provider's related
// songs, a similar artist) is the caller's business; this package only decides
// which of them are worth the listener's time. That keeps it a pure function:
// the same profile, candidates, options and salt give the same list.
package recommend

import (
	"hash/fnv"
	"math"
	"sort"
	"time"

	"github.com/elianiva/meiro/internal/taste"
)

// Relation is how a candidate was found, which says how far to trust it.
type Relation int

const (
	// YouTubeRelated is a song YouTube Music lists as related to a seed.
	YouTubeRelated Relation = iota
	// SoundCloudRelated is a song SoundCloud lists as related to a seed.
	SoundCloudRelated
	// SimilarArtist is a recording by an artist ListenBrainz names as similar to a
	// seed's; its Strength is the similarity, from 0 to 1.
	SimilarArtist
)

// weight is how much a relation counts. A provider's related list is the best
// signal there is for the niche the listener lives in; a similar artist is a
// bonus where the data exists.
var weight = map[Relation]float64{YouTubeRelated: 1.0, SoundCloudRelated: 0.8, SimilarArtist: 0.7}

// Seed is what a candidate came from: a song or an artist the listener likes,
// and how much.
type Seed struct {
	Name  string
	Score float64
}

// Candidate is a song that might be recommended, found by one relation to one
// seed. The same song found by several is several candidates, and counts more.
type Candidate struct {
	Title  string
	Artist string
	// Ref is the caller's own handle on the song, returned with the pick.
	Ref      any
	Relation Relation
	// Strength is how strong the relation is, from 0 to 1; 0 means 1.
	Strength float64
	Seed     Seed
}

// Pick is one recommended song.
type Pick struct {
	Candidate
	Score float64
	// Why is the reason to show beside it.
	Why string
	// Fresh is set for a song by an artist the listener has not played.
	Fresh bool
}

// Options are the knobs of one ranking.
type Options struct {
	Now time.Time
	// N is how many picks to return.
	N int
	// Explore is the share of the picks that may be by artists new to the
	// listener, from 0 to 1.
	Explore float64
	// PerArtist is the most picks by one artist within any ten in a row.
	PerArtist int
	// Recent is how long a song counts as just played: it is left out for the
	// first half of it and counts for little for the rest.
	Recent time.Duration
	// Salt breaks ties the same way all day: the date, for a daily list.
	Salt string
}

// Defaults are a sensible start: thirty picks, a third of them new, two of an
// artist in ten, a song played in the last week held back.
func Defaults(now time.Time) Options {
	return Options{Now: now, N: 30, Explore: 0.3, PerArtist: 2, Recent: 7 * 24 * time.Hour, Salt: now.Format("2006-01-02")}
}

// Rank picks the songs worth recommending.
func Rank(p *taste.Profile, candidates []Candidate, o Options) []Pick {
	if o.N <= 0 {
		return nil
	}
	if o.PerArtist <= 0 {
		o.PerArtist = 2
	}
	type pooled struct {
		pick  Pick
		best  float64 // the largest single contribution, to name the reason
		total float64
	}
	pool := map[string]*pooled{}
	for _, c := range candidates {
		key := taste.TrackKey(c.Title, c.Artist)
		if key == "" || c.Seed.Score <= 0 {
			continue
		}
		strength := c.Strength
		if strength <= 0 {
			strength = 1
		}
		contribution := c.Seed.Score * weight[c.Relation] * strength
		e := pool[key]
		if e == nil {
			e = &pooled{pick: Pick{Candidate: c}}
			pool[key] = e
		}
		e.total += contribution
		if contribution > e.best {
			e.best, e.pick.Candidate = contribution, c
		}
	}

	var maxArtist float64
	for _, e := range p.TopArtists(1) {
		maxArtist = e.Score
	}
	scored := make([]Pick, 0, len(pool))
	for _, e := range pool {
		pick := e.pick
		if skip(p, pick, o) {
			continue
		}
		// Several seeds finding the same song count for more, with a falling
		// return so one well-connected song does not crowd out the rest.
		score := math.Log1p(e.total)
		if maxArtist > 0 {
			score += 0.3 * math.Max(0, p.Artist(pick.Artist)) / maxArtist
		}
		if last, ok := p.LastPlayed(pick.Title, pick.Artist); ok && o.Now.Sub(last) < o.Recent {
			score *= 0.1
		}
		pick.Score = score
		pick.Fresh = !p.Known(pick.Artist)
		pick.Why = why(pick)
		scored = append(scored, pick)
	}
	sort.Slice(scored, func(i, j int) bool {
		if scored[i].Score != scored[j].Score {
			return scored[i].Score > scored[j].Score
		}
		return tie(o.Salt, scored[i]) < tie(o.Salt, scored[j])
	})
	return choose(scored, o)
}

// skip says whether a candidate is left out altogether: a song or an artist the
// listener does not like, or a song played so lately that it would be a repeat.
func skip(p *taste.Profile, c Pick, o Options) bool {
	if p.Track(c.Title, c.Artist) < 0 || p.Artist(c.Artist) < -0.5 {
		return true
	}
	if rate := p.SkipRate(c.Artist); rate > 0.6 && p.Known(c.Artist) && p.Artist(c.Artist) <= 0 {
		return true
	}
	if last, ok := p.LastPlayed(c.Title, c.Artist); ok && o.Now.Sub(last) < o.Recent/2 {
		return true
	}
	return false
}

func why(c Pick) string {
	if c.Fresh {
		return "New to you, like " + c.Seed.Name
	}
	return "Because you listen to " + c.Seed.Name
}

// tie orders two picks of the same score the same way for a whole day.
func tie(salt string, c Pick) uint32 {
	h := fnv.New32a()
	_, _ = h.Write([]byte(salt + "\x00" + taste.TrackKey(c.Title, c.Artist)))
	return h.Sum32()
}

// choose takes the first n of the scored picks that fit: no more than
// PerArtist by one artist in any ten, and about Explore of them new to the
// listener. It is greedy: at each place it takes the best pick that is allowed,
// and a new artist's when the share of new ones has fallen behind.
func choose(scored []Pick, o Options) []Pick {
	var out []Pick
	used := make([]bool, len(scored))
	fresh := 0
	allowed := func(c Pick) bool {
		count := 0
		for i := len(out) - 1; i >= 0 && i >= len(out)-9; i-- {
			if taste.ArtistKey(out[i].Artist) == taste.ArtistKey(c.Artist) {
				count++
			}
		}
		return count < o.PerArtist
	}
	next := func(wantFresh bool) int {
		for i, c := range scored {
			if !used[i] && c.Fresh == wantFresh && allowed(c) {
				return i
			}
		}
		return -1
	}
	for len(out) < o.N {
		wantFresh := float64(fresh) < o.Explore*float64(len(out)+1)
		i := next(wantFresh)
		if i < 0 {
			i = next(!wantFresh)
		}
		if i < 0 {
			break
		}
		used[i] = true
		out = append(out, scored[i])
		if scored[i].Fresh {
			fresh++
		}
	}
	return out
}
