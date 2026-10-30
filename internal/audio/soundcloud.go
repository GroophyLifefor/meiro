package audio

import (
	"context"
	"time"

	"github.com/elianiva/meiro/internal/ytdlp"
)

// SoundCloudResolver resolves a SoundCloud track page to a direct audio URL
// through yt-dlp. It has the shape of Resolver, so SoundCloud plays through
// the same URL cache as YouTube Music. The value it is handed is the track's
// SoundCloud page URL, which is what a SoundCloud item carries, not a YouTube
// video ID.
func SoundCloudResolver(ctx context.Context, pageURL string) (string, time.Duration, error) {
	return ytdlp.ResolveSoundCloud(ctx, pageURL)
}
