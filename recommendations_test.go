package main

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/history"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/soundcloud"
	"github.com/elianiva/meiro/youtube"
)

// relatedAsked is what the SoundCloud of recsApp was asked to read.
var relatedAsked []string

// recsApp is an app with a listening history on SoundCloud, whose fake answers
// every related request with the same two songs, and no waiting between requests.
func recsApp(t *testing.T) *app {
	t.Helper()
	resetFakes()
	oldYT, oldSC, oldMin := pauseYouTube, pauseSoundCloud, minDailyMix
	pauseYouTube, pauseSoundCloud, minDailyMix = 0, 0, 1
	t.Cleanup(func() { pauseYouTube, pauseSoundCloud, minDailyMix = oldYT, oldSC, oldMin })
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	a.recs.cache = openRelatedCache("")
	// The shared fake answers a page with nothing; here SoundCloud lists two
	// related songs for any page it is asked about, and says what it was asked.
	relatedAsked = nil
	a.soundcloud = soundcloud.New(soundcloud.Options{Runner: func(_ context.Context, args ...string) ([]byte, error) {
		for _, arg := range args {
			if strings.HasPrefix(arg, "https://soundcloud.com") {
				relatedAsked = append(relatedAsked, arg)
			}
		}
		return []byte(soundcloudSearchResponse), nil
	}})
	for i, artist := range []string{"Seed Artist", "Other Artist"} {
		a.history = append(a.history, history.Entry{
			VideoID: "https://soundcloud.com/x/seed-" + string(rune('a'+i)), Title: "Seed song " + artist, Artist: artist, Source: "soundcloud",
			PlayedAt: time.Now().Add(-time.Duration(i+1) * 24 * time.Hour), PlayedMS: 200_000, DurationMS: 200_000, Completed: true,
		})
	}
	return a
}

// With a history, a run finds what is related to the songs the listener plays,
// ranks it, and makes the Daily mix once, naming the reason for each song.
func TestRecommendationsMakeTheDailyMix(t *testing.T) {
	a := recsApp(t)
	a.refreshRecommendations()
	pollUntil(t, a, "the picks", func() bool { return len(a.recs.picks) > 0 })
	titles := map[string]bool{}
	for _, p := range a.recs.picks {
		titles[p.Item.Title] = true
		if p.Why == "" {
			t.Errorf("a pick without a reason: %+v", p.Item.Title)
		}
	}
	if !titles["Cloud Song"] || !titles["Sky Tune"] {
		t.Fatalf("picks = %v, want the songs the fake related list holds", titles)
	}
	if a.settings.DailyMixDate != today() || a.settings.DailyMixID == "" {
		t.Fatalf("the Daily mix was not made: %q %q", a.settings.DailyMixID, a.settings.DailyMixDate)
	}
	mix, ok := a.libraries.Get(a.settings.DailyMixID)
	if !ok || mix.Name != "Daily mix" || len(mix.Items) != len(a.recs.picks) || mix.Items[0].Why == "" {
		t.Fatalf("the Daily mix = %+v %v", mix, ok)
	}

	// Asked again the same day it is not made again, and there is still one.
	a.recs.load.stop()
	a.refreshRecommendations()
	pollUntil(t, a, "the second run", func() bool { return !a.recs.load.busy() })
	count := 0
	for _, lib := range a.libraries.List() {
		if lib.Name == "Daily mix" {
			count++
		}
	}
	if count != 1 {
		t.Errorf("%d Daily mix playlists after two runs, want 1", count)
	}

	// The mix is not counted as the listener's own choice: the app would be
	// voting for what it picked.
	for _, s := range a.tasteSignals() {
		if s.Title == "Cloud Song" {
			t.Errorf("the Daily mix counted as a signal: %+v", s)
		}
	}
}

// What a provider listed as related is kept for a day, so a second run asks
// nothing, and a recommendation is never of a song just played.
func TestRecommendationsAskEachSeedOncePerDay(t *testing.T) {
	a := recsApp(t)
	a.refreshRecommendations()
	pollUntil(t, a, "the picks", func() bool { return len(a.recs.picks) > 0 })
	asked := len(relatedAsked)
	if asked == 0 {
		t.Fatal("SoundCloud was never asked for what is related")
	}
	a.recs.load.stop()
	a.recs.picks = nil
	a.refreshRecommendations()
	pollUntil(t, a, "the picks again", func() bool { return len(a.recs.picks) > 0 })
	if got := len(relatedAsked); got != asked {
		t.Errorf("SoundCloud was asked %d more times within the day", got-asked)
	}
}

// The recommended songs go ahead of the ones YouTube generated, a few at a time,
// without repeating, and without what is already queued.
func TestSuggestionsGoIntoTheQueueAheadOfYouTubes(t *testing.T) {
	a := recsApp(t)
	for _, title := range []string{"S1", "S2", "S3", "S4", "S5", "S6", "S7"} {
		a.recs.picks = append(a.recs.picks, recPick{Item: youtube.MusicItem{VideoID: "id-" + title, Title: title, Artist: "A", Kind: media.KindTrack}, Why: "because"})
	}
	generated := []youtube.MusicItem{{VideoID: "g1", Title: "G1", Kind: media.KindTrack}, {VideoID: "id-S1", Title: "S1", Kind: media.KindTrack}}
	first := a.withSuggestions(generated)
	if len(first) != suggestionsPerFill+1 || first[0].Title != "S1" || first[len(first)-1].Title != "G1" {
		t.Fatalf("first fill = %v, want five suggestions, then YouTube's without the repeat", titlesOf(first))
	}
	second := a.withSuggestions(nil)
	if len(second) != 2 || second[0].Title != "S6" {
		t.Errorf("second fill = %v, want the two not yet used", titlesOf(second))
	}
	if third := a.withSuggestions(generated); len(third) != len(generated) {
		t.Errorf("a fill with nothing left changed YouTube's list: %v", titlesOf(third))
	}
	a.settings.Suggestions = false
	a.recs.used = nil
	if got := a.withSuggestions(generated); len(got) != len(generated) || got[0].Title != "G1" {
		t.Errorf("with suggestions off the list changed: %v", titlesOf(got))
	}
}

func titlesOf(items []youtube.MusicItem) []string {
	out := make([]string, len(items))
	for i, it := range items {
		out[i] = it.Title
	}
	return out
}

// Nothing is recommended before there is something to learn from, and the
// switch turns it all off.
func TestNoRecommendationsWithoutAHistoryOrWhenOff(t *testing.T) {
	a := recsApp(t)
	a.history = nil
	a.refreshRecommendations()
	if a.recs.load.busy() || len(a.recs.picks) != 0 {
		t.Error("recommendations were made with no history")
	}
	a = recsApp(t)
	a.settings.Suggestions = false
	a.refreshRecommendations()
	if a.recs.load.busy() || len(relatedAsked) != 0 {
		t.Error("a request went out with recommendations off")
	}
}

// The settings card shows the switch, the share of new artists and the Daily mix.
func TestRecommendationsCardIsInSettings(t *testing.T) {
	a := newTestApp(t)
	a.router.Push("/settings")
	tt := ui.NewTester(a.view, 1000, 9000)
	tt.Frame()
	for _, want := range []string{"Recommendations", "Recommend songs", "New artists", "Daily mix", "Make it now"} {
		if !tt.HasText(want) {
			t.Errorf("Settings does not show %q", want)
		}
	}
}

// Once the Daily mix is made, Home leads with it, ahead of what YouTube Music
// shows; before that Home is only YouTube Music's.
func TestHomeLeadsWithTheDailyMix(t *testing.T) {
	a := recsApp(t)
	a.router.Push("/home")
	tt := ui.NewTester(a.view, 1000, 2400)
	tt.Frame()
	if tt.HasText("Daily mix") {
		t.Fatal("Home shows a Daily mix before one was made")
	}
	a.refreshRecommendations()
	pollUntil(t, a, "the Daily mix", func() bool { return a.settings.DailyMixDate != "" })
	tt.Frame()
	if !tt.HasText("Daily mix") {
		t.Errorf("Home does not show the Daily mix: %q", tt.Texts())
	}
}

// Settings says who makes this Meiro, under About and support.
func TestSettingsCreditsTheAuthor(t *testing.T) {
	a := newTestApp(t)
	a.router.Push("/settings")
	tt := ui.NewTester(a.view, 1000, 12000)
	tt.Frame()
	if !tt.HasText(credit) {
		t.Errorf("Settings does not show the credit %q", credit)
	}
}
