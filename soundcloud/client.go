// Package soundcloud reads SoundCloud tracks, sets, artists and the lists
// around a track through yt-dlp. It is the data side of the provider: it turns
// yt-dlp's JSON into the provider-neutral media types. Playing a track is
// resolved separately, by ytdlp.ResolveSoundCloud.
package soundcloud

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"

	"github.com/elianiva/meiro/internal/proc"
	"github.com/elianiva/meiro/media"
)

// ErrUnsupported reports a media.Client call SoundCloud's read side does not
// answer: it is signed out, so it has no library and no likes, and the app
// reads its page types directly instead of through the neutral interface.
var ErrUnsupported = errors.New("soundcloud: this is not supported yet")

// ErrMissing reports that yt-dlp is neither inside the app nor on PATH.
var ErrMissing = errors.New("reading SoundCloud needs yt-dlp on PATH")

// searchLimit is how many tracks a search asks for. yt-dlp pages the
// results itself, so one run reads a full page.
const searchLimit = 50

// Runner runs yt-dlp and returns its standard output. Tests replace it, both
// to check the arguments and to answer from a fixture.
type Runner func(ctx context.Context, args ...string) ([]byte, error)

// Client reads SoundCloud through yt-dlp. The runner is a field so a test can
// answer without a network or a yt-dlp run.
type Client struct {
	run Runner
}

// Options configures a Client.
type Options struct {
	// Runner runs yt-dlp. A nil Runner runs the yt-dlp on PATH, or the one
	// inside the app.
	Runner Runner
}

// New returns a Client. A nil Options.Runner runs the app's yt-dlp.
func New(opts Options) *Client {
	client := &Client{run: opts.Runner}
	if client.run == nil {
		client.run = runYtdlp
	}
	return client
}

// SoundCloud's read side answers the interface's Search; the rest is added
// when the app reads a page through the neutral interface rather than its own.
var _ media.Client = (*Client)(nil)

func runYtdlp(ctx context.Context, args ...string) ([]byte, error) {
	path, err := proc.Tool("yt-dlp")
	if err != nil {
		return nil, ErrMissing
	}
	return proc.Command(ctx, path, args...).Output()
}

// Search reads a query's tracks. SoundCloud has no search filters, so options
// is ignored.
func (c *Client) Search(ctx context.Context, query string, options media.SearchOptions) (*media.SearchResult, error) {
	page, err := c.read(ctx, searchQuery(query))
	if err != nil {
		return nil, err
	}
	return &media.SearchResult{Items: page.items()}, nil
}

// GetSet reads a set page's tracks.
func (c *Client) GetSet(ctx context.Context, pageURL string) (*media.BrowseResult, error) {
	return c.browse(ctx, pageURL)
}

// GetUser reads an artist page's tracks.
func (c *Client) GetUser(ctx context.Context, pageURL string) (*media.BrowseResult, error) {
	return c.browse(ctx, pageURL)
}

// GetRelated reads the tracks SoundCloud recommends beside a track. pageURL is
// the track's canonical page, the one its items carry in URL.
func (c *Client) GetRelated(ctx context.Context, pageURL string) (*media.BrowseResult, error) {
	return c.browse(ctx, relatedURL(pageURL))
}

// GetStation reads the continuous track station that starts from a track.
// pageURL is the track's canonical page, the one its items carry in URL.
func (c *Client) GetStation(ctx context.Context, pageURL string) (*media.BrowseResult, error) {
	station, err := stationURL(pageURL)
	if err != nil {
		return nil, err
	}
	return c.browse(ctx, station)
}

// browse reads a flat SoundCloud page and maps its tracks.
func (c *Client) browse(ctx context.Context, pageURL string) (*media.BrowseResult, error) {
	page, err := c.read(ctx, pageURL)
	if err != nil {
		return nil, err
	}
	return &media.BrowseResult{Title: page.Title, Items: page.items()}, nil
}

// read runs yt-dlp over a page target and decodes its flat JSON.
func (c *Client) read(ctx context.Context, target string) (playlist, error) {
	output, err := c.run(ctx, "--flat-playlist", "--dump-single-json", "--no-warnings", target)
	if err != nil {
		return playlist{}, err
	}
	return decodePlaylist(output)
}

// searchQuery is the yt-dlp target for a track search.
func searchQuery(query string) string {
	return fmt.Sprintf("scsearch%d:%s", searchLimit, query)
}

// relatedURL is the page yt-dlp reads a track's recommendations from.
func relatedURL(pageURL string) string {
	trimmed := strings.TrimRight(pageURL, "/")
	if strings.HasSuffix(trimmed, "/recommended") {
		return trimmed
	}
	return trimmed + "/recommended"
}

// stationURL is the page yt-dlp reads a track's station from. SoundCloud
// spells it as the track page under /stations/track.
func stationURL(pageURL string) (string, error) {
	parsed, err := url.Parse(pageURL)
	if err != nil {
		return "", fmt.Errorf("soundcloud: a track page was expected: %w", err)
	}
	if parsed.Host == "" || parsed.Path == "" {
		return "", fmt.Errorf("soundcloud: %q is not a track page", pageURL)
	}
	return parsed.Scheme + "://" + parsed.Host + "/stations/track" + strings.TrimRight(parsed.Path, "/"), nil
}
