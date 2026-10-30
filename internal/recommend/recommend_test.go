package recommend

import (
	"fmt"
	"testing"
	"time"

	"github.com/elianiva/meiro/internal/taste"
)

var now = time.Unix(1_800_000_000, 0)

func heard(title, artist string, daysAgo int) taste.Play {
	return taste.Play{Title: title, Artist: artist, At: now.Add(-time.Duration(daysAgo) * 24 * time.Hour), PlayedMS: 200_000, DurationMS: 200_000}
}

func cand(title, artist string, rel Relation, seed string, score float64) Candidate {
	return Candidate{Title: title, Artist: artist, Relation: rel, Seed: Seed{Name: seed, Score: score}}
}

func titles(picks []Pick) []string {
	out := make([]string, len(picks))
	for i, p := range picks {
		out[i] = p.Title
	}
	return out
}

// A song several seeds found, or a seed that is liked more, ranks higher; a
// provider's related list counts for more than a similar artist.
func TestRankOrdersByEvidence(t *testing.T) {
	p := taste.Build([]taste.Play{heard("S1", "Seed One", 1), heard("S2", "Seed Two", 1)}, nil, now)
	o := Defaults(now)
	o.Explore = 0
	picks := Rank(p, []Candidate{
		cand("Found Twice", "A", YouTubeRelated, "Seed One", 1), cand("Found Twice", "A", YouTubeRelated, "Seed Two", 1),
		cand("Found Once", "B", YouTubeRelated, "Seed One", 1),
		cand("Similar Only", "C", SimilarArtist, "Seed One", 1),
		cand("Strong Seed", "D", YouTubeRelated, "Seed Two", 3),
	}, o)
	got := titles(picks)
	if got[0] != "Strong Seed" && got[0] != "Found Twice" {
		t.Errorf("order = %v, want the best evidenced first", got)
	}
	idx := map[string]int{}
	for i, g := range got {
		idx[g] = i
	}
	if idx["Found Twice"] > idx["Found Once"] || idx["Found Once"] > idx["Similar Only"] {
		t.Errorf("order = %v: twice found, then once, then similar only", got)
	}
}

// What the listener dislikes, and what they just played, is not recommended.
func TestRankLeavesOutWhatIsDislikedOrJustPlayed(t *testing.T) {
	p := taste.Build([]taste.Play{
		heard("Love", "Seed", 1),
		{Title: "Nope", Artist: "Disliked", At: now.Add(-time.Hour), PlayedMS: 4_000, DurationMS: 200_000},
		heard("Just Now", "Fresh Artist", 0),
		heard("Last Month", "Old Artist", 30),
	}, nil, now)
	o := Defaults(now)
	picks := Rank(p, []Candidate{
		cand("Another", "Disliked", YouTubeRelated, "Seed", 1),
		cand("Just Now", "Fresh Artist", YouTubeRelated, "Seed", 1),
		cand("Last Month", "Old Artist", YouTubeRelated, "Seed", 1),
		cand("Fine", "Someone", YouTubeRelated, "Seed", 1),
	}, o)
	got := map[string]bool{}
	for _, t := range titles(picks) {
		got[t] = true
	}
	if got["Another"] || got["Just Now"] {
		t.Errorf("picks %v hold a disliked artist or a song just played", titles(picks))
	}
	if !got["Fine"] || !got["Last Month"] {
		t.Errorf("picks %v lack what is fine to recommend", titles(picks))
	}
}

// No artist takes more than its share of any ten in a row, and about the share
// asked for is new to the listener.
func TestRankKeepsVarietyAndTheExploreShare(t *testing.T) {
	// Six artists the listener knows, each with songs to offer, so the cap on
	// one artist still leaves enough known songs to fill the list.
	var plays []taste.Play
	var cands []Candidate
	for k := range 6 {
		artist := fmt.Sprintf("Known %d", k)
		plays = append(plays, heard("Love "+artist, artist, 1))
		for i := range 6 {
			cands = append(cands, cand(fmt.Sprintf("%s song %d", artist, i), artist, YouTubeRelated, artist, 3))
		}
	}
	for i := range 12 {
		cands = append(cands, cand(fmt.Sprintf("New song %d", i), fmt.Sprintf("New artist %d", i), YouTubeRelated, "Known 0", 1))
	}
	p := taste.Build(plays, nil, now)
	o := Defaults(now)
	o.N = 20
	o.Explore = 0.3
	picks := Rank(p, cands, o)
	if len(picks) != 20 {
		t.Fatalf("got %d picks, want 20", len(picks))
	}
	for start := 0; start+10 <= len(picks); start++ {
		count := map[string]int{}
		for _, pick := range picks[start : start+10] {
			count[taste.ArtistKey(pick.Artist)]++
		}
		for artist, n := range count {
			if n > o.PerArtist {
				t.Errorf("window %d: %d picks by %q, want at most %d", start, n, artist, o.PerArtist)
			}
		}
	}
	fresh := 0
	for _, pick := range picks {
		if pick.Fresh {
			fresh++
		}
	}
	if fresh < 5 || fresh > 7 {
		t.Errorf("%d of 20 picks are new to the listener, want about 6", fresh)
	}
}

// The same inputs and salt give the same list, a new salt may order ties
// another way, and each pick says why.
func TestRankIsDeterministicAndExplains(t *testing.T) {
	p := taste.Build([]taste.Play{heard("Love", "Hidra", 1)}, nil, now)
	var cands []Candidate
	for i := range 8 {
		cands = append(cands, cand(fmt.Sprintf("Tie %d", i), fmt.Sprintf("Artist %d", i), YouTubeRelated, "Hidra", 1))
	}
	o := Defaults(now)
	first, again := Rank(p, cands, o), Rank(p, cands, o)
	for i := range first {
		if first[i].Title != again[i].Title {
			t.Fatalf("the same salt gave different lists: %v vs %v", titles(first), titles(again))
		}
	}
	o.Salt = "another day"
	other := Rank(p, cands, o)
	same := true
	for i := range first {
		if first[i].Title != other[i].Title {
			same = false
		}
	}
	if same {
		t.Error("a new salt left eight tied songs in the same order")
	}
	if first[0].Why != "New to you, like Hidra" {
		t.Errorf("why = %q", first[0].Why)
	}
}

// fakeGraph is a world of two kinds of music: each artist's related songs are
// the other artists of its kind.
type fakeGraph map[string][]string

func (g fakeGraph) Candidates(seeds []taste.Entry) []Candidate {
	var out []Candidate
	for _, seed := range seeds {
		for _, other := range g[seed.Key] {
			out = append(out, Candidate{Title: other + " hit", Artist: other, Relation: YouTubeRelated, Seed: Seed{Name: seed.Name, Score: seed.Score}})
		}
	}
	return out
}

// repeater recommends only what the listener already plays: the best that can
// be done with no idea of what is related to what.
type repeater struct{}

func (repeater) Candidates(seeds []taste.Entry) []Candidate {
	var out []Candidate
	for _, seed := range seeds {
		out = append(out, Candidate{Title: seed.Name + " hit", Artist: seed.Name, Relation: YouTubeRelated, Seed: Seed{Name: seed.Name, Score: seed.Score}})
	}
	return out
}

// Replayed over a history, a source that knows what is related finds artists the
// listener went on to play, which one that only repeats cannot.
func TestReplayShowsTheGraphBeatsRepeating(t *testing.T) {
	rock := []string{"rock a", "rock b", "rock c", "rock d", "rock e"}
	rap := []string{"rap a", "rap b", "rap c", "rap d", "rap e"}
	graph := fakeGraph{}
	for _, group := range [][]string{rock, rap} {
		for _, a := range group {
			var others []string
			for _, b := range group {
				if a != b {
					others = append(others, b)
				}
			}
			graph[taste.ArtistKey(a)] = others
		}
	}
	// The listener works through the rap artists one at a time, so each new
	// artist is related to the ones before and was never played.
	var plays []taste.Play
	day := 40
	for _, a := range []string{"rap a", "rap a", "rap b", "rap b", "rap c", "rap d", "rap e"} {
		plays = append(plays, taste.Play{Title: a + " hit", Artist: a, At: now.Add(-time.Duration(day) * 24 * time.Hour), PlayedMS: 200_000, DurationMS: 200_000})
		day -= 5
	}
	withGraph := Replay(plays, graph, 2, 20, 5)
	withoutGraph := Replay(plays, repeater{}, 2, 20, 5)
	if withGraph.Evaluated != 5 || withoutGraph.Evaluated != 5 {
		t.Fatalf("evaluated %d and %d plays, want 5 each", withGraph.Evaluated, withoutGraph.Evaluated)
	}
	if withGraph.ArtistHits <= withoutGraph.ArtistHits {
		t.Errorf("artist hits: graph %d, repeating %d; the graph should find the next artist", withGraph.ArtistHits, withoutGraph.ArtistHits)
	}
	if withGraph.ArtistRate() < 0.6 {
		t.Errorf("the graph found the next artist %.0f%% of the time, want at least 60%%", 100*withGraph.ArtistRate())
	}
	if withGraph.Coverage != withGraph.Evaluated {
		t.Errorf("coverage %d of %d", withGraph.Coverage, withGraph.Evaluated)
	}
}

func TestReplayWithNothingToEvaluate(t *testing.T) {
	if r := Replay(nil, repeater{}, 3, 10, 5); r.Evaluated != 0 || r.SongRate() != 0 || r.ArtistRate() != 0 {
		t.Errorf("report of no history = %+v", r)
	}
}
