package mblinks

import (
	"path/filepath"
	"testing"
	"time"
)

func TestLinksSurviveARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "links.json")
	s := Open(path)
	if _, err := s.SetSong(Song{Provider: "ytm", ID: "v1", State: Matched, MBID: "rec-1", Artist: "Daft Punk", ArtistMBID: "art-1", ArtistState: Matched}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetArtist(Artist{Name: "Daft Punk", State: Matched, MBID: "art-1"}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetAlt(Alt{Provider: "ytm", ID: "v1", OtherProvider: "soundcloud", OtherID: "https://soundcloud.com/x/y", OtherURL: "https://soundcloud.com/x/y"}); err != nil {
		t.Fatal(err)
	}
	again := Open(path)
	if song, ok := again.Song("ytm", "v1"); !ok || song.MBID != "rec-1" || song.UpdatedAt.IsZero() {
		t.Errorf("song = %+v %v", song, ok)
	}
	if artist, ok := again.Artist("daft punk"); !ok || artist.MBID != "art-1" {
		t.Errorf("artist by folded name = %+v %v", artist, ok)
	}
	if alt, ok := again.Alt("ytm", "v1"); !ok || alt.OtherProvider != "soundcloud" {
		t.Errorf("alt = %+v %v", alt, ok)
	}
}

// Merging keeps the later record of each, takes a record it lacks, and changes
// nothing when it is given what it already holds.
func TestMergeKeepsTheLaterRecord(t *testing.T) {
	s := Open("")
	old := time.Unix(1_700_000_000, 0)
	later := old.Add(time.Hour)
	if _, err := s.SetSong(Song{Provider: "ytm", ID: "a", State: Unknown, UpdatedAt: old}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetSong(Song{Provider: "ytm", ID: "b", State: Matched, MBID: "mine", UpdatedAt: later}); err != nil {
		t.Fatal(err)
	}
	took, err := s.Merge(Data{
		Songs: []Song{
			{Provider: "ytm", ID: "a", State: Matched, MBID: "theirs", UpdatedAt: later},
			{Provider: "ytm", ID: "b", State: Other, UpdatedAt: old},
			{Provider: "ytm", ID: "c", State: Matched, MBID: "new", UpdatedAt: old},
		},
		Artists: []Artist{{Name: "  Daft PUNK ", State: Matched, MBID: "x", UpdatedAt: old}, {Name: "!!!", State: Matched}},
	})
	if err != nil || took != 3 {
		t.Fatalf("Merge took %d, %v; want 3 (a newer, c new, one artist)", took, err)
	}
	if song, _ := s.Song("ytm", "a"); song.MBID != "theirs" {
		t.Errorf("the later record did not win: %+v", song)
	}
	if song, _ := s.Song("ytm", "b"); song.MBID != "mine" || song.State != Matched {
		t.Errorf("an older record overwrote a later one: %+v", song)
	}
	if _, ok := s.Song("ytm", "c"); !ok {
		t.Error("a record the store lacked was not taken")
	}
	if took, _ := s.Merge(s.Data()); took != 0 {
		t.Errorf("merging what it holds took %d, want 0", took)
	}
}

func TestFreshAndArtistKey(t *testing.T) {
	s := Open("")
	if s.Fresh(time.Time{}) {
		t.Error("a check never made is fresh")
	}
	if !s.Fresh(time.Now().Add(-time.Hour)) || s.Fresh(time.Now().Add(-RecheckAfter-time.Hour)) {
		t.Error("freshness does not follow RecheckAfter")
	}
	if ArtistKey("Daft  Punk") != ArtistKey("daft punk") {
		t.Error("artist names do not fold alike")
	}
}
