package recommend

import (
	"sort"

	"github.com/elianiva/meiro/internal/taste"
)

// Source finds candidates for a set of seeds. A real one asks the providers; a
// test one answers from a fixed map.
type Source interface {
	Candidates(seeds []taste.Entry) []Candidate
}

// Report is how well a ranking did against the listener's real history.
type Report struct {
	// Evaluated is how many plays were tried: the plays after the first few,
	// which have a history to learn from.
	Evaluated int
	// SongHits and ArtistHits count the plays whose song, or whose artist, was
	// among the picks made just before it.
	SongHits   int
	ArtistHits int
	// Coverage is how many plays had any pick at all.
	Coverage int
}

// SongRate is the share of evaluated plays whose song was picked.
func (r Report) SongRate() float64 { return rate(r.SongHits, r.Evaluated) }

// ArtistRate is the share of evaluated plays whose artist was picked.
func (r Report) ArtistRate() float64 { return rate(r.ArtistHits, r.Evaluated) }

func rate(n, of int) float64 {
	if of == 0 {
		return 0
	}
	return float64(n) / float64(of)
}

// Replay plays the history forward. For each play after the first minHistory,
// it builds a profile from only what came before, asks the source for
// candidates seeded from that profile, ranks them, and checks whether the song
// really played next, or its artist, is among the first n picks. It answers
// the question that matters, "would this have put the right thing in front of
// me?", without waiting for the listener to try it.
func Replay(plays []taste.Play, src Source, minHistory, n, seeds int) Report {
	sorted := append([]taste.Play(nil), plays...)
	sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].At.Before(sorted[j].At) })
	var report Report
	for i := minHistory; i < len(sorted); i++ {
		actual := sorted[i]
		profile := taste.Build(sorted[:i], nil, actual.At)
		o := Defaults(actual.At)
		o.N = n
		o.Recent = 0 // the play being predicted may be a repeat; do not forbid it
		picks := Rank(profile, src.Candidates(profile.TopArtists(seeds)), o)
		report.Evaluated++
		if len(picks) > 0 {
			report.Coverage++
		}
		song, artist := taste.TrackKey(actual.Title, actual.Artist), taste.ArtistKey(actual.Artist)
		songHit, artistHit := false, false
		for _, pick := range picks {
			if taste.TrackKey(pick.Title, pick.Artist) == song {
				songHit = true
			}
			if taste.ArtistKey(pick.Artist) == artist {
				artistHit = true
			}
		}
		if songHit {
			report.SongHits++
		}
		if artistHit {
			report.ArtistHits++
		}
	}
	return report
}
