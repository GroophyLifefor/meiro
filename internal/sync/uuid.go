// Package sync sends what was played, kept and downloaded on this device to a
// Meiro server, so the dashboard can show it. It is offline first: every push
// waits in a queue on disk until the server answers 200, and nothing is ever
// dropped before that.
package sync

import (
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
)

// nameNamespace is the namespace name-based identifiers are derived in. It is
// a fixed random UUID, so a library keeps the same server identity for as long
// as its local ID does.
const nameNamespace = "6ba7b811-9dad-11d1-80b4-00c04fd430c8"

// NewID returns a random UUID (version 4) for one record: a play, or a queued
// payload. It is generated once and kept, so a retry is the same record to the
// server and is not counted twice.
func NewID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// crypto/rand failing is not a case the app can act on, and zeroing
		// would make every record share one ID: fall back to a different
		// well-known prefix so at least distinct calls stay distinct.
		for i := range b {
			b[i] = byte(i * 7)
		}
	}
	return formatV4(b)
}

// DeriveID returns the same UUID (version 5) for the same pair, so a local
// library ID that is not a UUID maps to one server identity across restarts
// without a table of its own.
func DeriveID(namespace, name string) string {
	h := sha1.New()
	h.Write([]byte(namespace))
	h.Write([]byte{0})
	h.Write([]byte(name))
	sum := h.Sum(nil)
	var b [16]byte
	copy(b[:], sum[:16])
	// Set the version (5) and the RFC 4122 variant.
	b[6] = (b[6] & 0x0f) | 0x50
	b[8] = (b[8] & 0x3f) | 0x80
	return format(b)
}

// LibraryID is the server identity of the library kept locally under localID.
func LibraryID(localID string) string { return DeriveID(nameNamespace, localID) }

func formatV4(b [16]byte) string {
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return format(b)
}

// format writes the 8-4-4-4-12 hyphen form the server parses.
func format(b [16]byte) string {
	var out [36]byte
	hex.Encode(out[0:8], b[0:4])
	out[8] = '-'
	hex.Encode(out[9:13], b[4:6])
	out[13] = '-'
	hex.Encode(out[14:18], b[6:8])
	out[18] = '-'
	hex.Encode(out[19:23], b[8:10])
	out[23] = '-'
	hex.Encode(out[24:36], b[10:16])
	return string(out[:])
}

// dailyMixNamespace is the namespace the Daily mix's identity is derived in,
// apart from the libraries', so no local library ID can ever collide with it.
const dailyMixNamespace = "6ba7b812-9dad-11d1-80b4-00c04fd430c8"

// DailyMixID is the server identity of the Daily mix. It is the same on every
// device, so the devices of one account write one list and not one each.
func DailyMixID() string { return DeriveID(dailyMixNamespace, "daily-mix") }
