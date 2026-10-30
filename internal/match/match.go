// Package match decides whether two tracks are the same recording. It compares
// what a provider and a metadata service call a track: the names, folded to a
// common shape, and the lengths, within a tolerance that covers the moment a
// rip gains or loses at the edges.
//
// A track the app cannot be sure of is left unknown rather than guessed at.
// Marking one as something else is the listener's to do, and not this
// package's.
package match

import (
	"strings"
	"time"
	"unicode"
)

// Status is what a comparison concluded about two tracks.
type Status string

const (
	// Matched is two tracks that are the same recording.
	Matched Status = "matched"
	// Unknown is two tracks the app will not say are the same: the names or
	// the lengths do not agree closely enough.
	Unknown Status = "unknown"
	// Other is a listener's own mark, for a track the app could not match
	// and they did, or the reverse. No comparison returns it.
	Other Status = "other"
)

// Track is what a comparison needs of a track. A zero Duration is a length
// that is not known, which does not narrow the comparison.
type Track struct {
	Title    string
	Artist   string
	Duration time.Duration
	// ISRC is the recording code, when it is known. Two equal codes decide
	// the comparison on their own, and are the surest match there is.
	ISRC string
}

// DurationTolerance is how far two lengths may differ and still be the same
// recording. A provider's length is often rounded to the second, and a
// streaming copy may differ by a moment at the start or the end.
const DurationTolerance = 4 * time.Second

// Match reports whether a and b are the same recording. Two equal ISRCs are
// the same recording whatever else is said. Otherwise the names, folded, must
// agree, and the lengths must be within DurationTolerance unless one of them
// is unknown.
func Match(a, b Track) Status {
	if a.ISRC != "" && a.ISRC == b.ISRC {
		return Matched
	}
	if Normalize(a.Title) == Normalize(b.Title) && Normalize(a.Artist) == Normalize(b.Artist) && withinTolerance(a.Duration, b.Duration) {
		return Matched
	}
	return Unknown
}

// withinTolerance reports whether two lengths are close enough to be the same
// recording. A length that is not known does not narrow it.
func withinTolerance(a, b time.Duration) bool {
	if a <= 0 || b <= 0 {
		return true
	}
	diff := a - b
	if diff < 0 {
		diff = -diff
	}
	return diff <= DurationTolerance
}

// noise are the words a provider adds to a title that say nothing about which
// recording it is. They are removed, longest phrase first so that "sped up"
// goes before "up" would be reached on its own.
var noise = []string{
	"official music video",
	"official video",
	"official audio",
	"official lyric video",
	"lyric video",
	"sped up",
	"slowed down",
	"slowed",
	"remastered",
	"remaster",
	"explicit",
	"lyrics",
	"audio",
	"video",
}

// Normalize folds a name to the shape the comparison uses: lower case, the
// Turkish letters written with Latin ones, bracketed asides such as "(feat.
// …)" dropped, the words that say nothing about the recording removed, and
// punctuation read as a space.
func Normalize(name string) string {
	var folded strings.Builder
	for _, r := range name {
		switch r {
		case 'ı', 'İ', 'I':
			r = 'i'
		case 'ş', 'Ş':
			r = 's'
		case 'ğ', 'Ğ':
			r = 'g'
		case 'ü', 'Ü':
			r = 'u'
		case 'ö', 'Ö':
			r = 'o'
		case 'ç', 'Ç':
			r = 'c'
		}
		folded.WriteRune(r)
	}
	name = strings.ToLower(folded.String())
	name = dropBrackets(name)
	for _, word := range noise {
		name = strings.ReplaceAll(name, word, " ")
	}
	name = strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return r
		}
		return ' '
	}, name)
	return strings.Join(strings.Fields(name), " ")
}

// dropBrackets removes everything between a matching pair of parentheses or
// square brackets, which is where a credit or a version note is written.
func dropBrackets(name string) string {
	var out strings.Builder
	depth := 0
	for _, r := range name {
		switch r {
		case '(', '[':
			depth++
		case ')', ']':
			if depth > 0 {
				depth--
			}
		default:
			if depth == 0 {
				out.WriteRune(r)
			}
		}
	}
	return out.String()
}
