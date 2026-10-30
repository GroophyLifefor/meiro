package taste

import (
	"testing"
	"time"
)

var now = time.Unix(1_800_000_000, 0)

func play(title, artist string, daysAgo int, playedMS, durationMS int64) Play {
	return Play{Title: title, Artist: artist, At: now.Add(-time.Duration(daysAgo) * 24 * time.Hour), PlayedMS: playedMS, DurationMS: durationMS}
}

// A song heard through is a vote for it and its artist; one left early is a
// vote against.
func TestHeardThroughRaisesAndSkippedLowers(t *testing.T) {
	p := Build([]Play{
		play("Loved", "Artist A", 1, 200_000, 200_000),
		play("Skipped", "Artist B", 1, 5_000, 200_000),
		play("Half", "Artist C", 1, 120_000, 200_000),
	}, nil, now)
	if p.Artist("Artist A") <= p.Artist("Artist C") || p.Artist("Artist C") <= 0 {
		t.Errorf("scores A=%v C=%v, want A above C above 0", p.Artist("Artist A"), p.Artist("Artist C"))
	}
	if p.Artist("Artist B") >= 0 {
		t.Errorf("a skipped artist scores %v, want below 0", p.Artist("Artist B"))
	}
	if got := p.SkipRate("Artist B"); got != 1 {
		t.Errorf("skip rate of B = %v, want 1", got)
	}
	if p.SkipRate("Nobody") != 0 || p.Known("Nobody") {
		t.Error("an artist never played is known")
	}
}

// What was loved long ago counts for less than what was loved lately.
func TestVotesFadeWithAge(t *testing.T) {
	p := Build([]Play{
		play("Old", "Old Artist", 120, 200_000, 200_000),
		play("New", "New Artist", 1, 200_000, 200_000),
	}, nil, now)
	if p.Artist("New Artist") <= p.Artist("Old Artist") {
		t.Errorf("new %v, old %v: the recent one should lead", p.Artist("New Artist"), p.Artist("Old Artist"))
	}
	// Four half-lives old: about a sixteenth of the vote is left.
	if got := p.Artist("Old Artist"); got < 0.04 || got > 0.09 {
		t.Errorf("a vote four half-lives old is %v, want about 0.06", got)
	}
}

// A song played again and again counts for less each time, so a loop does not
// outweigh several different loved songs.
func TestRepeatsCountForLess(t *testing.T) {
	var loop []Play
	for i := range 20 {
		loop = append(loop, play("Loop", "Looper", 1, 200_000, 200_000))
		_ = i
	}
	many := Build(append(loop,
		play("S1", "Variety", 1, 200_000, 200_000), play("S2", "Variety", 1, 200_000, 200_000),
		play("S3", "Variety", 1, 200_000, 200_000), play("S4", "Variety", 1, 200_000, 200_000),
		play("S5", "Variety", 1, 200_000, 200_000), play("S6", "Variety", 1, 200_000, 200_000),
		play("S7", "Variety", 1, 200_000, 200_000), play("S8", "Variety", 1, 200_000, 200_000),
		play("S9", "Variety", 1, 200_000, 200_000), play("S10", "Variety", 1, 200_000, 200_000),
	), nil, now)
	if got := many.Artist("Looper"); got > 5.1 {
		t.Errorf("twenty plays of one song score %v, want under the 5 a geometric series allows", got)
	}
	if many.Artist("Variety") <= many.Artist("Looper") {
		t.Errorf("ten songs score %v, one looped song %v: variety should lead", many.Artist("Variety"), many.Artist("Looper"))
	}
}

// Explicit votes weigh more than a play, and fade more slowly.
func TestExplicitVotes(t *testing.T) {
	week := now.Add(-7 * 24 * time.Hour)
	p := Build([]Play{play("Played", "P", 7, 200_000, 200_000)}, []Signal{
		{Title: "Liked", Artist: "L", Kind: Like, At: week},
		{Title: "Saved", Artist: "S", Kind: Saved, At: week},
		{Title: "Kept", Artist: "K", Kind: Downloaded, At: week},
	}, now)
	if !(p.Artist("L") > p.Artist("S") && p.Artist("S") >= p.Artist("K") && p.Artist("K") > p.Artist("P")) {
		t.Errorf("like %v, saved %v, downloaded %v, played %v", p.Artist("L"), p.Artist("S"), p.Artist("K"), p.Artist("P"))
	}
}

// The same song on two providers is one song, and a play with no length is
// judged by how long it ran.
func TestSongKeysFoldAndUnknownLengthIsJudgedByTime(t *testing.T) {
	if TrackKey("Get Lucky", "Daft Punk") != TrackKey("get  lucky", "DAFT PUNK") {
		t.Error("the same song keys differently")
	}
	p := Build([]Play{
		{Title: "Long", Artist: "A", At: now, PlayedMS: 150_000},
		{Title: "Short", Artist: "B", At: now, PlayedMS: 8_000},
	}, nil, now)
	if p.Artist("A") <= 0 || p.Artist("B") >= 0 {
		t.Errorf("A=%v B=%v, want a long play liked and a short one disliked", p.Artist("A"), p.Artist("B"))
	}
}

// The profile reads nothing from the future, and lists what is liked most first.
func TestTopAndTheFuture(t *testing.T) {
	p := Build([]Play{
		play("One", "Big", 1, 200_000, 200_000), play("Two", "Big", 1, 200_000, 200_000),
		play("Three", "Small", 1, 200_000, 200_000),
		{Title: "Future", Artist: "Later", At: now.Add(time.Hour), PlayedMS: 200_000, DurationMS: 200_000},
	}, nil, now)
	if p.Known("Later") {
		t.Error("a play in the future counted")
	}
	top := p.TopArtists(5)
	if len(top) != 2 || top[0].Name != "Big" || top[1].Name != "Small" {
		t.Errorf("top artists = %+v", top)
	}
	if got := p.TopArtists(1); len(got) != 1 {
		t.Errorf("TopArtists(1) = %+v", got)
	}
	if songs := p.Songs(10); len(songs) != 3 {
		t.Errorf("songs = %+v", songs)
	}
	if at, ok := p.LastPlayed("One", "Big"); !ok || !at.Equal(now.Add(-24*time.Hour)) {
		t.Errorf("last played = %v %v", at, ok)
	}
	if !Build(nil, nil, now).Empty() {
		t.Error("an empty history is not empty")
	}
}
