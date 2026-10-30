package main

import (
	"context"
	"errors"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/soundcloud"
	"github.com/elianiva/meiro/youtube"
)

// A song only one provider holds still shows which one, with no other to
// switch to.
func TestEverySongShowsItsProvider(t *testing.T) {
	a, list, _ := listWithOneTrack(t, nil)
	if _, err := a.libraries.AddItem(list, library.Item{Source: media.SourceYTM, Key: "yt-only", Title: "From YouTube", Artist: "X", DurationMS: 120_000}); err != nil {
		t.Fatal(err)
	}
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	for _, want := range []string{"SoundCloud", "YouTube Music"} {
		if !tt.HasText(want) {
			t.Errorf("a song of %s shows no mark: %q", want, tt.Texts())
		}
	}
	if tt.HasText("Play from SoundCloud") || tt.HasText("Play from YouTube Music") {
		t.Errorf("a song one provider holds offers a switch: %q", tt.Texts())
	}
}

// The song that starts playing is looked for on the other provider, and the
// same recording found there gives it a mark for each, while it goes on playing
// from the one it plays from. Each song is looked up once.
func TestThePlayingSongIsLookedForOnTheOtherProvider(t *testing.T) {
	resetFakes()
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	// The fake SoundCloud answers every search with "Cloud Song" by "Cloud
	// Artist", three minutes long.
	song := youtube.MusicItem{VideoID: "vid-cloud", Title: "Cloud Song", Artist: "Cloud Artist", Subtitle: "Cloud Artist • 3:00", Kind: media.KindTrack, Duration: "3:00"}
	a.current = song
	a.queue.replace(song, []youtube.MusicItem{song}, 0, "Test")
	a.findOtherSource(song)
	a.poll()
	if !a.current.HasSource(media.SourceYTM) || !a.current.HasSource(media.SourceSoundCloud) {
		t.Fatalf("the playing song did not gain the SoundCloud copy: %+v", a.current.Sources)
	}
	if a.current.Source == media.SourceSoundCloud {
		t.Error("finding the other copy switched where the song plays from")
	}
	if got := a.queue.current().Sources; len(got) != 2 {
		t.Errorf("the queue's copy has %d sources, want 2", len(got))
	}

	// A different recording is not taken for it.
	other := youtube.MusicItem{VideoID: "vid-other", Title: "Different Tune", Artist: "Cloud Artist", Subtitle: "Cloud Artist • 3:00", Kind: media.KindTrack, Duration: "3:00"}
	a.findOtherSource(other)
	a.poll()
	a.current = other
	if len(a.otherLooked) != 2 {
		t.Errorf("looked up %d songs, want 2", len(a.otherLooked))
	}
	if other.HasSource(media.SourceSoundCloud) {
		t.Error("an unrelated song was given a SoundCloud copy")
	}
	// Asking again does not ask again.
	a.findOtherSource(song)
	if len(a.otherLooked) != 2 {
		t.Errorf("a song was looked up twice: %d", len(a.otherLooked))
	}
}

// What was found of a song on the other provider is kept, so a song looked up
// before is paired again without a search, and a song that was not there is not
// looked for again for a while.
func TestTheOtherProviderIsNotAskedAgainForAKnownSong(t *testing.T) {
	resetFakes()
	a := newTestApp(t)
	a.run = func(work func()) { work() }
	song := youtube.MusicItem{VideoID: "vid-cloud", Title: "Cloud Song", Artist: "Cloud Artist", Subtitle: "Cloud Artist • 3:00", Kind: media.KindTrack, Duration: "3:00"}
	a.findOtherSource(song)
	a.poll()
	if alt, ok := a.links.Alt("ytm", "vid-cloud"); !ok || alt.OtherProvider != "soundcloud" || alt.OtherID == "" {
		t.Fatalf("the pairing was not kept: %+v %v", alt, ok)
	}
	if back, ok := a.links.Alt("soundcloud", "https://soundcloud.com/cloud-voice/cloud-song"); !ok || back.OtherID != "vid-cloud" {
		t.Errorf("the pairing was not kept for the other copy too: %+v %v", back, ok)
	}

	// A later run, with a SoundCloud that cannot be asked, pairs it from the
	// store alone.
	asked := 0
	a.soundcloud = soundcloud.New(soundcloud.Options{Runner: func(context.Context, ...string) ([]byte, error) {
		asked++
		return nil, errors.New("must not be asked")
	}})
	a.otherLooked = nil
	a.current = song
	a.findOtherSource(song)
	a.poll()
	if asked != 0 {
		t.Errorf("SoundCloud was asked %d times for a song already paired", asked)
	}
	if !a.current.HasSource(media.SourceSoundCloud) {
		t.Errorf("the stored pairing was not applied: %+v", a.current.Sources)
	}

	// A song that was looked for and not found is left alone.
	lonely := youtube.MusicItem{VideoID: "vid-lonely", Title: "Lonely Song", Artist: "Nobody", Subtitle: "Nobody • 1:00", Kind: media.KindTrack, Duration: "1:00"}
	a.findOtherSource(lonely)
	a.poll()
	before := asked
	a.otherLooked = nil
	a.findOtherSource(lonely)
	a.poll()
	if asked != before {
		t.Errorf("a song not found was looked for again at once: %d, was %d", asked, before)
	}
}
