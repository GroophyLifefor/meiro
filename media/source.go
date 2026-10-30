// Package media holds the provider-neutral types the interface carries and the
// client the app reads music with. The field names are the ones the YouTube
// Music client has always used, so that client can alias these types and the
// app's call sites stay as they are. The provider-specific parts are marked:
// the provider added after YouTube Music builds its own client on this layer.
package media

// Source names where an item came from. It chooses the client that reads a
// provider and the resolver that plays its tracks.
type Source string

const (
	// SourceYTM is YouTube Music.
	SourceYTM Source = "ytm"
	// SourceSoundCloud is SoundCloud. Its client reads through yt-dlp, signed
	// out, and its tracks carry a page URL rather than a video ID.
	SourceSoundCloud Source = "soundcloud"
)
