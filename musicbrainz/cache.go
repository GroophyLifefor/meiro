package musicbrainz

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// cache is a folder of JSON answers, each kept under the hash of the key it
// was fetched for and stamped with when it was written. An answer older than
// its kind's time to live is not used, and is fetched again.
//
// The clock is a field so that a test can age an entry without waiting.
type cache struct {
	dir string
	now func() time.Time
	mu  sync.Mutex
}

// cacheEntry is what is written for one answer: when it was kept, and the
// answer itself.
type cacheEntry struct {
	StoredAt time.Time `json:"storedAt"`
	// Key is what the answer was fetched for, kept so the cache can say what
	// it holds. An answer written before it was kept has none.
	Key  string          `json:"key,omitempty"`
	Body json.RawMessage `json:"body"`
}

// newCache returns a cache in dir. An empty dir keeps nothing. A nil clock is
// time.Now.
func newCache(dir string, now func() time.Time) *cache {
	if now == nil {
		now = time.Now
	}
	return &cache{dir: dir, now: now}
}

// name is the file an answer is kept under: the hash of its key.
func (c *cache) name(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:16]) + ".json"
}

// get reads what is kept for key into out, and reports whether it was there
// and younger than ttl.
func (c *cache) get(key string, ttl time.Duration, out any) bool {
	if c == nil || c.dir == "" {
		return false
	}
	data, err := os.ReadFile(filepath.Join(c.dir, c.name(key)))
	if err != nil {
		return false
	}
	var entry cacheEntry
	if err := json.Unmarshal(data, &entry); err != nil {
		return false
	}
	if c.now().Sub(entry.StoredAt) >= ttl {
		return false
	}
	return json.Unmarshal(entry.Body, out) == nil
}

// put keeps v under key, replacing any answer already there. A write that
// fails is dropped: the cache is an optimisation, not a source of truth.
func (c *cache) put(key string, v any) {
	if c == nil || c.dir == "" {
		return
	}
	body, err := json.Marshal(v)
	if err != nil {
		return
	}
	data, err := json.Marshal(cacheEntry{StoredAt: c.now(), Key: key, Body: body})
	if err != nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := os.MkdirAll(c.dir, 0o700); err != nil {
		return
	}
	file, err := os.CreateTemp(c.dir, c.name(key)+".*.tmp")
	if err != nil {
		return
	}
	tmp := file.Name()
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		_ = os.Remove(tmp)
		return
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(tmp)
		return
	}
	_ = os.Rename(tmp, filepath.Join(c.dir, c.name(key)))
}

// record is an answer the cache holds, as it is on disk.
type record struct {
	name  string
	entry cacheEntry
	bytes int64
}

// records reads every answer the cache holds, skipping files that are not
// answers. It reads the folder, so it is not for a frame.
func (c *cache) records() []record {
	if c == nil || c.dir == "" {
		return nil
	}
	files, err := os.ReadDir(c.dir)
	if err != nil {
		return nil
	}
	var out []record
	for _, file := range files {
		if file.IsDir() || filepath.Ext(file.Name()) != ".json" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(c.dir, file.Name()))
		if err != nil {
			continue
		}
		var entry cacheEntry
		if json.Unmarshal(data, &entry) != nil {
			continue
		}
		out = append(out, record{name: file.Name(), entry: entry, bytes: int64(len(data))})
	}
	return out
}

// remove deletes one answer by its file name. A name that is not a plain file
// name of an answer is refused, so the call cannot leave the folder.
func (c *cache) remove(name string) error {
	if c == nil || c.dir == "" || name != filepath.Base(name) || filepath.Ext(name) != ".json" {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := os.Remove(filepath.Join(c.dir, name)); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// clear deletes every answer the cache holds.
func (c *cache) clear() error {
	var first error
	for _, r := range c.records() {
		if err := c.remove(r.name); err != nil && first == nil {
			first = err
		}
	}
	return first
}
