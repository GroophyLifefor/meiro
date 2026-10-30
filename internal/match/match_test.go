package match

import (
	"testing"
	"time"
)

func TestNormalize(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"Yorushika", "yorushika"},
		{"Just a Moment (feat. Someone)", "just a moment"},
		{"Ame [Remastered]", "ame"},
		{"Şımarık", "simarik"},
		{"İstanbul'da", "istanbul da"},
		{"Gülüşün", "gulusun"},
		{"Song - Sped Up", "song"},
		{"Song (Slowed Down)", "song"},
		{"Song (Official Video)", "song"},
		{"Song (Lyrics)", "song"},
		{"Some Song!!", "some song"},
	}
	for _, c := range cases {
		if got := Normalize(c.in); got != c.want {
			t.Errorf("Normalize(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestMatchFeatures(t *testing.T) {
	base := Track{Title: "Just a Moment", Artist: "Yorushika", Duration: 242 * time.Second}
	featured := Track{Title: "Just a Moment (feat. Someone)", Artist: "Yorushika", Duration: 243 * time.Second}
	if got := Match(base, featured); got != Matched {
		t.Fatalf("a feat variant = %q, want matched", got)
	}
}

func TestMatchTurkishLetters(t *testing.T) {
	a := Track{Title: "Sımarık", Artist: "Tarkan"}
	b := Track{Title: "Simarik", Artist: "TARKAN"}
	if got := Match(a, b); got != Matched {
		t.Fatalf("Turkish letters = %q, want matched", got)
	}
}

func TestMatchRemaster(t *testing.T) {
	a := Track{Title: "Ame", Artist: "Nova"}
	b := Track{Title: "Ame [Remastered]", Artist: "Nova"}
	if got := Match(a, b); got != Matched {
		t.Fatalf("a remaster = %q, want matched", got)
	}
}

func TestMatchSpedUp(t *testing.T) {
	a := Track{Title: "Nostalgia", Artist: "Kaori", Duration: 200 * time.Second}
	b := Track{Title: "Nostalgia - Sped Up", Artist: "Kaori", Duration: 180 * time.Second}
	// The names fold to the same title, but twenty seconds apart is a
	// different cut, so the length keeps them apart.
	if got := Match(a, b); got != Unknown {
		t.Fatalf("a sped up cut = %q, want unknown", got)
	}
	same := Track{Title: "Nostalgia - Sped Up", Artist: "Kaori", Duration: 202 * time.Second}
	if got := Match(a, same); got != Matched {
		t.Fatalf("the same length = %q, want matched", got)
	}
}

func TestMatchDurationEdges(t *testing.T) {
	a := Track{Title: "Meteorite", Artist: "Kaori", Duration: 240 * time.Second}
	within := Track{Title: "Meteorite", Artist: "Kaori", Duration: 244 * time.Second}
	over := Track{Title: "Meteorite", Artist: "Kaori", Duration: 245 * time.Second}
	if got := Match(a, within); got != Matched {
		t.Fatalf("four seconds apart = %q, want matched", got)
	}
	if got := Match(a, over); got != Unknown {
		t.Fatalf("five seconds apart = %q, want unknown", got)
	}
}

func TestMatchUnknownLength(t *testing.T) {
	a := Track{Title: "Meteorite", Artist: "Kaori"}
	b := Track{Title: "Meteorite", Artist: "Kaori", Duration: 999 * time.Second}
	if got := Match(a, b); got != Matched {
		t.Fatalf("an unknown length = %q, want matched on the names", got)
	}
}

func TestMatchDifferentArtists(t *testing.T) {
	a := Track{Title: "Home", Artist: "Nova"}
	b := Track{Title: "Home", Artist: "Kaori"}
	if got := Match(a, b); got != Unknown {
		t.Fatalf("a different artist = %q, want unknown", got)
	}
}

func TestMatchISRC(t *testing.T) {
	a := Track{Title: "One Name", Artist: "Nova", ISRC: "JPA651703790"}
	b := Track{Title: "Another Name", Artist: "Someone", ISRC: "JPA651703790"}
	if got := Match(a, b); got != Matched {
		t.Fatalf("an equal ISRC = %q, want matched", got)
	}
}
