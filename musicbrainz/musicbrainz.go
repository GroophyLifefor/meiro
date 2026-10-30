// Package musicbrainz reads artist and recording data from MusicBrainz, and
// artist and recording popularity from ListenBrainz. It is the metadata side
// of the app: it names artists, their best known recordings, and where those
// recordings can be played.
//
// MusicBrainz asks that a client identify itself and make no more than one
// request a second. Every MusicBrainz request goes through one queue that
// spaces them a second apart, and answers are kept in a local cache so a
// search made again costs no request. ListenBrainz has no such rule here and
// is read directly.
package musicbrainz

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"
)

// UserAgent identifies the app to MusicBrainz and ListenBrainz, which both
// ask a client to say who it is: a name, a version and a way to reach the
// maintainer, as MusicBrainz's rate limiting rules ask.
const UserAgent = "Playlist Manager SideProject/1.0.0 (muratkirazkaya0@gmail.com)"

// requestGap is the least time between two MusicBrainz requests. MusicBrainz
// answers a client that asks faster with HTTP 503, so the queue keeps to one
// request a second.
const requestGap = time.Second

// maxBody bounds what of an answer is read. The largest pages here are a few
// tens of kilobytes.
const maxBody = 4 << 20

// How long an answer is kept before it is asked for again. An artist's
// identity and links change rarely; how many people listen to one changes
// often enough to matter for the order it is shown in.
const (
	artistSearchTTL = 30 * 24 * time.Hour
	// recordingSearchTTL is shorter: a song's search is for linking a song
	// the listener has just added, and a recording MusicBrainz lacked a day
	// ago may be there now.
	recordingSearchTTL = 24 * time.Hour
	lookupTTL          = 90 * 24 * time.Hour
	popularityTTL      = 7 * 24 * time.Hour
)

// ErrRateLimited is MusicBrainz or ListenBrainz refusing a request for coming
// too fast: HTTP 429 or 503. MusicBrainz declines every request of a client
// that asks faster than once a second until its rate falls, so after one the
// client waits before it asks again.
var ErrRateLimited = errors.New("musicbrainz: rate limited")

// rateLimitError is ErrRateLimited with how long the service asked to wait,
// when it said.
type rateLimitError struct {
	status     string
	retryAfter time.Duration
}

func (e *rateLimitError) Error() string        { return "musicbrainz: answered " + e.status }
func (e *rateLimitError) Is(target error) bool { return target == ErrRateLimited }

// retryAfter reads a Retry-After header given in seconds, or zero.
func retryAfter(value string) time.Duration {
	seconds, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || seconds <= 0 {
		return 0
	}
	return time.Duration(seconds) * time.Second
}

// errNotFound is a lookup MusicBrainz has no answer for, such as a URL it has
// never seen.
var errNotFound = errors.New("musicbrainz: not found")

// Artist is a MusicBrainz artist. Listeners and Listens are filled from
// ListenBrainz when a caller asks for them, and are zero when it has none.
type Artist struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Country        string `json:"country,omitempty"`
	Disambiguation string `json:"disambiguation,omitempty"`
	Score          int    `json:"score,omitempty"`
	// Listeners is the number of distinct ListenBrainz listeners, and Listens
	// the number of times the artist was listened to, over the whole service.
	Listeners int64 `json:"-"`
	Listens   int64 `json:"-"`
}

// Popularity is what ListenBrainz knows of one artist.
type Popularity struct {
	MBID      string `json:"artist_mbid"`
	Listens   int64  `json:"total_listen_count"`
	Listeners int64  `json:"total_user_count"`
}

// Recording is one recording of an artist, with what ListenBrainz knows of
// how much it was listened to.
type Recording struct {
	ID     string
	Title  string
	Artist string
	// LengthMS is the recording's length in milliseconds, or zero.
	LengthMS  int
	Listeners int64
	Listens   int64
}

// RecordingMatch is a recording MusicBrainz found for a title and an artist.
// Score is how well MusicBrainz says it fits the search, up to 100.
type RecordingMatch struct {
	ID    string
	Title string
	// Artist is the credited artists as MusicBrainz joins them, and ArtistID
	// the first of them.
	Artist   string
	ArtistID string
	// LengthMS is the recording's length in milliseconds, or zero.
	LengthMS int
	Score    int
}

// Link is a place a recording can be played, as MusicBrainz records it.
type Link struct {
	// Type is MusicBrainz's relation type, such as "free streaming" for a
	// music.youtube.com link, or "streaming" for Spotify and Deezer.
	Type string
	URL  string
}

// RecordingRef is a recording MusicBrainz named for a URL or an ISRC.
type RecordingRef struct {
	ID       string
	Title    string
	LengthMS int
}

// Options configures a Client. Every field has a working default.
type Options struct {
	// HTTP is the client to send with; nil is one with a timeout.
	HTTP *http.Client
	// MusicBrainzURL and ListenBrainzURL replace the real services, for a
	// test. They are hosts, without a trailing slash.
	MusicBrainzURL  string
	ListenBrainzURL string
	// LabsURL replaces ListenBrainz Labs, which answers the similar artists.
	LabsURL string
	// CacheDir is where answers are kept between runs. Empty keeps nothing.
	CacheDir string
	// Clock and Sleep let a test drive the request queue without waiting.
	Clock func() time.Time
	Sleep func(context.Context, time.Duration) error
}

// Client reads MusicBrainz and ListenBrainz. It is safe for concurrent use:
// the MusicBrainz queue serialises its requests.
type Client struct {
	http    *http.Client
	mb      string
	lb      string
	labs    string
	cache   *cache
	limiter *limiter
}

// New returns a Client. No request is made until a method is called.
func New(opts Options) *Client {
	if opts.HTTP == nil {
		opts.HTTP = &http.Client{Timeout: 20 * time.Second}
	}
	if opts.MusicBrainzURL == "" {
		opts.MusicBrainzURL = "https://musicbrainz.org"
	}
	if opts.ListenBrainzURL == "" {
		opts.ListenBrainzURL = "https://api.listenbrainz.org"
	}
	if opts.LabsURL == "" {
		opts.LabsURL = "https://labs.api.listenbrainz.org"
	}
	return &Client{
		labs:    strings.TrimRight(opts.LabsURL, "/"),
		http:    opts.HTTP,
		mb:      strings.TrimRight(opts.MusicBrainzURL, "/"),
		lb:      strings.TrimRight(opts.ListenBrainzURL, "/"),
		cache:   newCache(opts.CacheDir, opts.Clock),
		limiter: newLimiter(requestGap, opts.Clock, opts.Sleep),
	}
}

// SearchArtists finds the artists whose name matches query, best match first
// as MusicBrainz scores it. The search is kept for artistSearchTTL.
func (c *Client) SearchArtists(ctx context.Context, query string) ([]Artist, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return nil, nil
	}
	key := "mb:artist-search:" + query
	return fetchCached(c, ctx, key, artistSearchTTL, func(ctx context.Context) ([]Artist, error) {
		values := url.Values{"query": {query}, "fmt": {"json"}, "limit": {"25"}}
		var out struct {
			Artists []Artist `json:"artists"`
		}
		if err := c.mbGet(ctx, "artist", values, &out); err != nil {
			return nil, err
		}
		return out.Artists, nil
	})
}

// SearchRecordings finds the recordings whose title, and artist when one is
// given, match, best match first as MusicBrainz scores it. It is how a track
// from a provider is tied to a recording when it has no ISRC. The search is
// kept for recordingSearchTTL.
func (c *Client) SearchRecordings(ctx context.Context, title, artist string) ([]RecordingMatch, error) {
	title, artist = strings.TrimSpace(title), strings.TrimSpace(artist)
	if title == "" {
		return nil, nil
	}
	query := "recording:" + quoteTerm(title)
	if artist != "" {
		query += " AND artist:" + quoteTerm(artist)
	}
	key := "mb:recording-search:" + query
	return fetchCached(c, ctx, key, recordingSearchTTL, func(ctx context.Context) ([]RecordingMatch, error) {
		values := url.Values{"query": {query}, "fmt": {"json"}, "limit": {"10"}}
		var out struct {
			Recordings []struct {
				ID           string `json:"id"`
				Title        string `json:"title"`
				Length       int    `json:"length"`
				Score        int    `json:"score"`
				ArtistCredit []struct {
					Name       string `json:"name"`
					JoinPhrase string `json:"joinphrase"`
					Artist     struct {
						ID   string `json:"id"`
						Name string `json:"name"`
					} `json:"artist"`
				} `json:"artist-credit"`
			} `json:"recordings"`
		}
		if err := c.mbGet(ctx, "recording", values, &out); err != nil {
			return nil, err
		}
		matches := make([]RecordingMatch, 0, len(out.Recordings))
		for _, r := range out.Recordings {
			match := RecordingMatch{ID: r.ID, Title: r.Title, LengthMS: r.Length, Score: r.Score}
			var credit strings.Builder
			for i, part := range r.ArtistCredit {
				name := part.Name
				if name == "" {
					name = part.Artist.Name
				}
				credit.WriteString(name)
				credit.WriteString(part.JoinPhrase)
				if i == 0 {
					match.ArtistID = part.Artist.ID
				}
			}
			match.Artist = strings.TrimSpace(credit.String())
			matches = append(matches, match)
		}
		return matches, nil
	})
}

// quoteTerm quotes a search term for MusicBrainz's query syntax, so a title
// with a colon or a bracket in it is read as words.
func quoteTerm(term string) string {
	term = strings.ReplaceAll(term, `\`, `\\`)
	term = strings.ReplaceAll(term, `"`, `\"`)
	return `"` + term + `"`
}

// SimilarArtist is an artist ListenBrainz names as like another, with how
// alike, from 0 to 1.
type SimilarArtist struct {
	MBID       string  `json:"artist_mbid"`
	Name       string  `json:"name"`
	Similarity float64 `json:"score"`
}

// similarURL is ListenBrainz's service for artists that are listened to by the
// same people in the same sessions.
const similarArtistsPath = "/similar-artists/json?algorithm=session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30&artist_mbids="

// SimilarArtists reads the artists ListenBrainz says are like an artist, best
// first, with each similarity scaled to 0 to 1 against the best. An artist it
// knows too little of has none: that is no error. The answer is kept for
// popularityTTL.
func (c *Client) SimilarArtists(ctx context.Context, artistMBID string) ([]SimilarArtist, error) {
	artistMBID = strings.TrimSpace(artistMBID)
	if artistMBID == "" {
		return nil, nil
	}
	key := "lb:similar-artists:" + artistMBID
	return fetchCached(c, ctx, key, popularityTTL, func(ctx context.Context) ([]SimilarArtist, error) {
		var out []SimilarArtist
		if err := c.request(ctx, false, http.MethodGet, c.labs+similarArtistsPath+url.QueryEscape(artistMBID), nil, &out); err != nil {
			if errors.Is(err, errNotFound) {
				return nil, nil
			}
			return nil, err
		}
		var best float64
		for _, a := range out {
			best = max(best, a.Similarity)
		}
		for i := range out {
			if best > 0 {
				out[i].Similarity /= best
			}
		}
		return out, nil
	})
}

// ArtistPopularity reads how much each of mbids was listened to. The result
// is keyed by MBID. An artist ListenBrainz does not know is left out. The
// answer is kept for popularityTTL.
func (c *Client) ArtistPopularity(ctx context.Context, mbids []string) (map[string]Popularity, error) {
	mbids = compact(mbids)
	if len(mbids) == 0 {
		return map[string]Popularity{}, nil
	}
	slices.Sort(mbids)
	key := "lb:artist-popularity:" + strings.Join(mbids, ",")
	popular, err := fetchCached(c, ctx, key, popularityTTL, func(ctx context.Context) ([]Popularity, error) {
		payload, err := json.Marshal(struct {
			MBIDs []string `json:"artist_mbids"`
		}{mbids})
		if err != nil {
			return nil, err
		}
		var out []Popularity
		if err := c.lbJSON(ctx, http.MethodPost, "/1/popularity/artist", payload, &out); err != nil {
			return nil, err
		}
		return out, nil
	})
	if err != nil {
		return nil, err
	}
	byID := make(map[string]Popularity, len(popular))
	for _, p := range popular {
		byID[p.MBID] = p
	}
	return byID, nil
}

// TopRecordings reads an artist's best known recordings, most listened first.
// It is ListenBrainz's answer when it has one, and MusicBrainz's own list of
// the artist's recordings when it does not.
func (c *Client) TopRecordings(ctx context.Context, artistMBID string) ([]Recording, error) {
	artistMBID = strings.TrimSpace(artistMBID)
	if artistMBID == "" {
		return nil, nil
	}
	var out []lbRecording
	path := "/1/popularity/top-recordings-for-artist/" + url.PathEscape(artistMBID)
	if err := c.lbGet(ctx, path, &out); err == nil && len(out) > 0 {
		recordings := make([]Recording, 0, len(out))
		for _, r := range out {
			recordings = append(recordings, r.recording())
		}
		return recordings, nil
	}
	return c.mbArtistRecordings(ctx, artistMBID)
}

// LookupURL names the recordings MusicBrainz knows a URL to be, which answers
// which recording a provider link plays. The answer is kept for lookupTTL.
func (c *Client) LookupURL(ctx context.Context, resource string) ([]RecordingRef, error) {
	resource = strings.TrimSpace(resource)
	if resource == "" {
		return nil, nil
	}
	key := "mb:url:" + resource
	return fetchCached(c, ctx, key, lookupTTL, func(ctx context.Context) ([]RecordingRef, error) {
		values := url.Values{"resource": {resource}, "inc": {"recording-rels"}, "fmt": {"json"}}
		var out struct {
			Relations []struct {
				Recording *mbRecordingRef `json:"recording"`
			} `json:"relations"`
		}
		if err := c.mbGet(ctx, "url", values, &out); err != nil {
			if errors.Is(err, errNotFound) {
				return nil, nil
			}
			return nil, err
		}
		refs := make([]RecordingRef, 0, len(out.Relations))
		for _, rel := range out.Relations {
			if rel.Recording == nil || rel.Recording.ID == "" {
				continue
			}
			refs = append(refs, rel.Recording.ref())
		}
		return refs, nil
	})
}

// LookupISRC names the recordings MusicBrainz gives an ISRC, uppercased, as
// callers pass it. The answer is kept for lookupTTL.
func (c *Client) LookupISRC(ctx context.Context, isrc string) ([]RecordingRef, error) {
	isrc = strings.ToUpper(strings.TrimSpace(isrc))
	if isrc == "" {
		return nil, nil
	}
	key := "mb:isrc:" + isrc
	return fetchCached(c, ctx, key, lookupTTL, func(ctx context.Context) ([]RecordingRef, error) {
		var out struct {
			Recordings []mbRecordingRef `json:"recordings"`
		}
		path := "isrc/" + url.PathEscape(isrc)
		if err := c.mbGetPath(ctx, path, url.Values{"fmt": {"json"}}, &out); err != nil {
			if errors.Is(err, errNotFound) {
				return nil, nil
			}
			return nil, err
		}
		refs := make([]RecordingRef, 0, len(out.Recordings))
		for _, rec := range out.Recordings {
			refs = append(refs, rec.ref())
		}
		return refs, nil
	})
}

// RecordingLinks reads where a recording can be played, as MusicBrainz
// records the links. Only the link types the app can play are returned; the
// rest, such as a page about the recording, are left out.
func (c *Client) RecordingLinks(ctx context.Context, recordingMBID string) ([]Link, error) {
	recordingMBID = strings.TrimSpace(recordingMBID)
	if recordingMBID == "" {
		return nil, nil
	}
	var out struct {
		Relations []struct {
			Type string `json:"type"`
			URL  struct {
				Resource string `json:"resource"`
			} `json:"url"`
		} `json:"relations"`
	}
	path := "recording/" + url.PathEscape(recordingMBID)
	if err := c.mbGetPath(ctx, path, url.Values{"inc": {"url-rels"}, "fmt": {"json"}}, &out); err != nil {
		return nil, err
	}
	links := make([]Link, 0, len(out.Relations))
	for _, rel := range out.Relations {
		if rel.URL.Resource == "" || !playableRelation(rel.Type) {
			continue
		}
		links = append(links, Link{Type: rel.Type, URL: rel.URL.Resource})
	}
	return links, nil
}

// playableRelation reports whether a MusicBrainz relation type names a place
// a listener can play the recording. The rest name pages about it.
func playableRelation(kind string) bool {
	switch kind {
	case "free streaming", "streaming", "download for free", "purchase for download", "purchase for mail-order", "streaming (other)":
		return true
	default:
		return false
	}
}

// mbRecordingRef is a recording as MusicBrainz names it in a lookup or a
// browse.
type mbRecordingRef struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Length int    `json:"length"`
}

func (r mbRecordingRef) ref() RecordingRef {
	return RecordingRef{ID: r.ID, Title: r.Title, LengthMS: r.Length}
}

// mbArtistRecordings reads the artist's recordings from MusicBrainz, for an
// artist ListenBrainz has no popularity for.
func (c *Client) mbArtistRecordings(ctx context.Context, artistMBID string) ([]Recording, error) {
	values := url.Values{"artist": {artistMBID}, "fmt": {"json"}, "limit": {"25"}}
	var out struct {
		Recordings []struct {
			ID     string `json:"id"`
			Title  string `json:"title"`
			Length int    `json:"length"`
		} `json:"recordings"`
	}
	if err := c.mbGet(ctx, "recording", values, &out); err != nil {
		return nil, err
	}
	recordings := make([]Recording, 0, len(out.Recordings))
	for _, r := range out.Recordings {
		recordings = append(recordings, Recording{ID: r.ID, Title: r.Title, LengthMS: r.Length})
	}
	return recordings, nil
}

// lbRecording is a recording as ListenBrainz's top-recordings answer gives it.
type lbRecording struct {
	MBID      string `json:"recording_mbid"`
	Title     string `json:"recording_name"`
	Artist    string `json:"artist_name"`
	LengthMS  int    `json:"length"`
	Listeners int64  `json:"total_user_count"`
	Listens   int64  `json:"total_listen_count"`
}

func (r lbRecording) recording() Recording {
	return Recording{
		ID: r.MBID, Title: r.Title, Artist: r.Artist,
		LengthMS: r.LengthMS, Listeners: r.Listeners, Listens: r.Listens,
	}
}

// fetchCached answers from the cache when it holds a fresh answer, and
// otherwise fetches, keeps what it fetched, and returns it.
func fetchCached[T any](c *Client, ctx context.Context, key string, ttl time.Duration, fetch func(context.Context) (T, error)) (T, error) {
	var out T
	if c.cache.get(key, ttl, &out) {
		return out, nil
	}
	value, err := fetch(ctx)
	if err != nil {
		var zero T
		return zero, err
	}
	c.cache.put(key, value)
	return value, nil
}

// mbGet reads a MusicBrainz resource by its type, as "/ws/2/<resource>".
func (c *Client) mbGet(ctx context.Context, resource string, values url.Values, out any) error {
	return c.mbGetPath(ctx, resource, values, out)
}

// mbGetPath reads a MusicBrainz path under /ws/2, pacing the request through
// the queue that keeps MusicBrainz to one request a second.
func (c *Client) mbGetPath(ctx context.Context, path string, values url.Values, out any) error {
	address := c.mb + "/ws/2/" + strings.TrimLeft(path, "/")
	if encoded := values.Encode(); encoded != "" {
		address += "?" + encoded
	}
	return c.request(ctx, true, http.MethodGet, address, nil, out)
}

// lbGet reads a ListenBrainz path.
func (c *Client) lbGet(ctx context.Context, path string, out any) error {
	return c.request(ctx, false, http.MethodGet, c.lb+path, nil, out)
}

// lbJSON sends a ListenBrainz request with a JSON body.
func (c *Client) lbJSON(ctx context.Context, method, path string, body []byte, out any) error {
	return c.request(ctx, false, method, c.lb+path, body, out)
}

// request sends one request, paces it through the queue when paced, and
// decodes a JSON answer into out. A 404 is errNotFound; any other non-2xx
// status is an error.
func (c *Client) request(ctx context.Context, paced bool, method, address string, body []byte, out any) error {
	send := func() error { return c.send(ctx, method, address, body, out) }
	if paced {
		// The queue is held across the whole request, so a slow answer to
		// MusicBrainz does not overlap the next one.
		return c.limiter.do(ctx, send)
	}
	return send()
}

// send makes one request and decodes its answer.
func (c *Client) send(ctx context.Context, method, address string, body []byte, out any) error {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	request, err := http.NewRequestWithContext(ctx, method, address, reader)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", UserAgent)
	request.Header.Set("Accept", "application/json")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := c.http.Do(request)
	if err != nil {
		return err
	}
	defer func() { _ = response.Body.Close() }()
	data, err := io.ReadAll(io.LimitReader(response.Body, maxBody))
	if err != nil {
		return err
	}
	switch {
	case response.StatusCode == http.StatusNotFound:
		return errNotFound
	case response.StatusCode == http.StatusTooManyRequests, response.StatusCode == http.StatusServiceUnavailable:
		return &rateLimitError{status: response.Status, retryAfter: retryAfter(response.Header.Get("Retry-After"))}
	case response.StatusCode >= 500:
		return fmt.Errorf("musicbrainz: %s answered %s", request.URL, response.Status)
	case response.StatusCode < 200 || response.StatusCode >= 300:
		return fmt.Errorf("musicbrainz: %s answered %s", request.URL, response.Status)
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(data, out); err != nil {
		return fmt.Errorf("musicbrainz: unreadable answer from %s: %w", request.URL, err)
	}
	return nil
}

// compact drops empty strings and duplicates, keeping the order.
func compact(values []string) []string {
	seen := make(map[string]struct{}, len(values))
	out := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	return out
}
