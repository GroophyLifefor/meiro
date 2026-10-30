package musicbrainz

import (
	"testing"
	"time"
)

func TestCacheKeepsAnAnswerUntilItExpires(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	c := newCache(t.TempDir(), func() time.Time { return now })
	c.put("key", []string{"a", "b"})

	var out []string
	if !c.get("key", time.Hour, &out) {
		t.Fatal("a fresh answer was not read back")
	}
	if len(out) != 2 || out[0] != "a" || out[1] != "b" {
		t.Fatalf("read back %v", out)
	}
	now = now.Add(2 * time.Hour)
	if c.get("key", time.Hour, &out) {
		t.Fatal("an expired answer was read back")
	}
}

func TestCacheKeepsNothingWithoutADirectory(t *testing.T) {
	c := newCache("", nil)
	c.put("key", "value")
	var out string
	if c.get("key", time.Hour, &out) {
		t.Fatal("a cache with no directory kept something")
	}
}

func TestCacheMissesWhatItNeverKept(t *testing.T) {
	c := newCache(t.TempDir(), nil)
	var out string
	if c.get("absent", time.Hour, &out) {
		t.Fatal("a key that was never kept was found")
	}
}
