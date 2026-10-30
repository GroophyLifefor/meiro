package youtube

import (
	"net/url"

	"github.com/elianiva/meiro/media"
)

// YouTube Music's parsed types are the provider-neutral types of package media.
// The client aliases them so that the app's call sites and the queue stay on
// one shape while a second provider is added behind the same interface.
type (
	MusicItem         = media.Item
	MusicSection      = media.Section
	BrowseResult      = media.BrowseResult
	SearchResult      = media.SearchResult
	SearchSuggestions = media.SearchSuggestions
	SearchFilter      = media.SearchFilter
	SearchOptions     = media.SearchOptions
	LikeStatus        = media.LikeStatus
	LibraryState      = media.LibraryState
	Subscription      = media.Subscription
	ItemKind          = media.ItemKind
	TargetKind        = media.TargetKind
	Target            = media.Target
)

const (
	KindTrack     = media.KindTrack
	KindMusicItem = media.KindMusicItem
	KindVideo     = media.KindVideo
	KindPlaylist  = media.KindPlaylist
	KindAlbum     = media.KindAlbum
	KindArtist    = media.KindArtist
	KindEpisode   = media.KindEpisode

	TargetNone     = media.TargetNone
	TargetTrack    = media.TargetTrack
	TargetAlbum    = media.TargetAlbum
	TargetPlaylist = media.TargetPlaylist
	TargetArtist   = media.TargetArtist
	TargetPodcast  = media.TargetPodcast

	LikeUnknown = media.LikeUnknown
	Liked       = media.Liked
	Indifferent = media.Indifferent
	Disliked    = media.Disliked

	// SourceYTM is the source every item this client parses carries.
	SourceYTM = media.SourceYTM
)

// WatchURL is the public link to a track.
func WatchURL(videoID string) string {
	return "https://music.youtube.com/watch?v=" + url.QueryEscape(videoID)
}

// BrowseURL is the public link to an album, a playlist, an artist or a
// podcast page.
func BrowseURL(browseID string) string {
	return "https://music.youtube.com/browse/" + url.PathEscape(browseID)
}
