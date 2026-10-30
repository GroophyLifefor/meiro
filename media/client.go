package media

import "context"

// Client is the read side of a provider the interface drives: searching,
// browsing the pages it offers, and the account's likes and library. A
// provider has more calls than these; the ones a page reads through directly
// stay on the concrete client until a second provider needs them.
type Client interface {
	// Search reads a query's results, narrowed by one of the filters the
	// results themselves name.
	Search(ctx context.Context, query string, options SearchOptions) (*SearchResult, error)
	// ContinueSearch reads the next page of a search.
	ContinueSearch(ctx context.Context, token string) (*SearchResult, error)
	// GetSearchSuggestions reads what a provider offers for a query being
	// typed.
	GetSearchSuggestions(ctx context.Context, input string) (*SearchSuggestions, error)

	// GetHomeFeed, GetExplore and GetLibrary read the app's landing pages.
	GetHomeFeed(ctx context.Context) (*BrowseResult, error)
	GetExplore(ctx context.Context) (*BrowseResult, error)
	GetLibrary(ctx context.Context) (*BrowseResult, error)
	// StreamLibrary reads the library and every continuation page of it,
	// calling progress with what it has so far.
	StreamLibrary(ctx context.Context, progress func(*BrowseResult)) (*BrowseResult, error)
	// ContinueBrowse reads the next page a browse token names.
	ContinueBrowse(ctx context.Context, token string) (*BrowseResult, error)

	// GetArtist, GetAlbum and GetPlaylist read a detail page.
	GetArtist(ctx context.Context, artistID string) (*BrowseResult, error)
	GetAlbum(ctx context.Context, albumID string) (*BrowseResult, error)
	GetPlaylist(ctx context.Context, playlistID string) (*BrowseResult, error)

	// RateTrack likes a track or takes the like back.
	RateTrack(ctx context.Context, videoID string, like bool) error
	// AddToLibrary and RemoveFromLibrary save an album or a playlist to the
	// account's library and take it out.
	AddToLibrary(ctx context.Context, playlistID string) error
	RemoveFromLibrary(ctx context.Context, playlistID string) error
}
