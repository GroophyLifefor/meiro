package audio

import (
	"encoding/json"
	"log"
	"os"

	"github.com/elianiva/meiro/internal/fsutil"
)

// metaFileName is where the cache keeps what it knows of its tracks, beside
// the audio files.
const metaFileName = "meta.json"

// TrackMeta is what is known of a cached track: enough to find it in a local
// search and to play it again without the network. Key is the provider's own
// key, a video ID or a page URL, and Source names the provider it plays from.
type TrackMeta struct {
	Key      string `json:"key"`
	Source   string `json:"source,omitempty"`
	Title    string `json:"title,omitempty"`
	Artist   string `json:"artist,omitempty"`
	Duration string `json:"duration,omitempty"`
}

// Meta returns what is known of every cached track, for a local search. It
// runs on the main thread alone, like the rest of the store.
func (c *Cache) Meta() []TrackMeta {
	if c == nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]TrackMeta, 0, len(c.meta))
	for _, meta := range c.meta {
		out = append(out, meta)
	}
	return out
}

// Note keeps what is known of a track the cache holds, under the same key Add
// uses. Add keeps the same itself as it caches the audio; Note is for a caller
// that already holds the audio and wants a local search to find the track.
func (c *Cache) Note(videoID string, meta TrackMeta) {
	if c == nil || videoID == "" || meta.Key == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	c.meta[audioCacheKey(videoID)] = meta
	c.saveMetaLocked()
}

// loadMetaLocked reads the metadata index. The caller holds c.mu.
func (c *Cache) loadMetaLocked() {
	if c.metaPath == "" {
		return
	}
	data, err := os.ReadFile(c.metaPath)
	if err != nil {
		return
	}
	var entries map[string]TrackMeta
	if err := json.Unmarshal(data, &entries); err != nil {
		log.Printf("audio cache metadata in %s is not readable, starting empty: %v", c.metaPath, err)
		return
	}
	for key, entry := range entries {
		if entry.Key != "" {
			c.meta[key] = entry
		}
	}
}

// pruneMetaLocked drops what is known of files the cache no longer holds. The
// caller holds c.mu.
func (c *Cache) pruneMetaLocked() {
	for key := range c.meta {
		if _, ok := c.files[key]; !ok {
			delete(c.meta, key)
		}
	}
}

// saveMetaLocked writes the metadata index atomically, keyed by each track's
// cache key. The caller holds c.mu.
func (c *Cache) saveMetaLocked() {
	if c.metaPath == "" {
		return
	}
	data, err := json.MarshalIndent(c.meta, "", "  ")
	if err != nil {
		return
	}
	if err := fsutil.WriteFileAtomic(c.metaPath, data, 0o600); err != nil {
		log.Printf("saving audio cache metadata: %v", err)
	}
}
