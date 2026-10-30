package soundcloud

import (
	"context"

	"github.com/elianiva/meiro/media"
)

// The calls below are the read side's own and SoundCloud's are not written
// yet. They return ErrUnsupported rather than panicking, so a page that asks
// through the neutral interface gets a stated answer. The library and the
// likes need a signed-in account SoundCloud's free yt-dlp path has not, and
// the app reads its page types through the concrete client meanwhile.

// ContinueSearch reads the next page of a search.
func (c *Client) ContinueSearch(ctx context.Context, token string) (*media.SearchResult, error) {
	return nil, ErrUnsupported
}

// GetSearchSuggestions reads what SoundCloud offers for a query being typed.
func (c *Client) GetSearchSuggestions(ctx context.Context, input string) (*media.SearchSuggestions, error) {
	return nil, ErrUnsupported
}

// GetHomeFeed reads the app's landing page.
func (c *Client) GetHomeFeed(ctx context.Context) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// GetExplore reads the app's explore page.
func (c *Client) GetExplore(ctx context.Context) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// GetLibrary reads the account's library.
func (c *Client) GetLibrary(ctx context.Context) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// StreamLibrary reads the library and every continuation page of it.
func (c *Client) StreamLibrary(ctx context.Context, progress func(*media.BrowseResult)) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// ContinueBrowse reads the next page a browse token names.
func (c *Client) ContinueBrowse(ctx context.Context, token string) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// GetArtist reads an artist detail page.
func (c *Client) GetArtist(ctx context.Context, artistID string) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// GetAlbum reads an album detail page.
func (c *Client) GetAlbum(ctx context.Context, albumID string) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// GetPlaylist reads a playlist detail page.
func (c *Client) GetPlaylist(ctx context.Context, playlistID string) (*media.BrowseResult, error) {
	return nil, ErrUnsupported
}

// RateTrack likes a track or takes the like back.
func (c *Client) RateTrack(ctx context.Context, videoID string, like bool) error {
	return ErrUnsupported
}

// AddToLibrary saves an album or a playlist to the account's library.
func (c *Client) AddToLibrary(ctx context.Context, playlistID string) error {
	return ErrUnsupported
}

// RemoveFromLibrary takes an album or a playlist out of the library.
func (c *Client) RemoveFromLibrary(ctx context.Context, playlistID string) error {
	return ErrUnsupported
}
