package musicbrainz

import (
	"encoding/json"
	"sort"
	"strings"
	"time"
)

// CacheEntry is one answer the client keeps on disk, described for the listener
// to read: what it is for, what was asked, when, and when it is asked again.
type CacheEntry struct {
	// ID names the answer for RemoveCacheEntry.
	ID string
	// Kind says what sort of question it answers, and Query what was asked.
	Kind  string
	Query string
	// Results is how many things the answer lists.
	Results  int
	StoredAt time.Time
	// Expires is when the answer stops being used and is asked for again.
	Expires time.Time
	Bytes   int64
}

// kinds are the sorts of question the client keeps answers to: the key prefix
// each is stored under, what it is called, and how long it is kept.
var kinds = []struct {
	prefix, name string
	ttl          time.Duration
}{
	{"mb:artist-search:", "Artist search", artistSearchTTL},
	{"mb:recording-search:", "Song search", recordingSearchTTL},
	{"lb:artist-popularity:", "Artist listeners", popularityTTL},
	{"lb:similar-artists:", "Similar artists", popularityTTL},
	{"mb:url:", "Link lookup", lookupTTL},
	{"mb:isrc:", "ISRC lookup", lookupTTL},
}

// CacheEntries lists the answers kept on disk, the newest first. It reads the
// folder, so it is called when the listener looks, not every frame. An answer
// written before the cache kept what it was for is called an older answer.
func (c *Client) CacheEntries() []CacheEntry {
	var out []CacheEntry
	for _, r := range c.cache.records() {
		entry := CacheEntry{ID: r.name, Kind: "Older answer", StoredAt: r.entry.StoredAt, Bytes: r.bytes}
		ttl := artistSearchTTL
		for _, kind := range kinds {
			if query, ok := strings.CutPrefix(r.entry.Key, kind.prefix); ok {
				entry.Kind, entry.Query, ttl = kind.name, query, kind.ttl
				break
			}
		}
		entry.Expires = r.entry.StoredAt.Add(ttl)
		var list []json.RawMessage
		if json.Unmarshal(r.entry.Body, &list) == nil {
			entry.Results = len(list)
		}
		out = append(out, entry)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].StoredAt.After(out[j].StoredAt) })
	return out
}

// RemoveCacheEntry forgets one answer, which is asked for again the next time
// it is needed.
func (c *Client) RemoveCacheEntry(id string) error { return c.cache.remove(id) }

// ClearCache forgets every answer.
func (c *Client) ClearCache() error { return c.cache.clear() }
