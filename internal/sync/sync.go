package sync

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/elianiva/meiro/media"
)

// DefaultURL is the server Meiro talks to on a first run. The address can be
// changed in Settings, so a server on the same machine, such as
// http://localhost:8080, still works.
const DefaultURL = "https://meiro.muratkirazkaya.com"

// TrackFor builds the server's reference to a track from what this device
// knows. The provider is the music source's short name, which the server uses
// too ("ytm" and "soundcloud"); the provider's own id is the key playback uses
// (a video ID for YouTube Music), or the page URL for a track that carries no
// id of its own, as a SoundCloud track does.
func TrackFor(source media.Source, key, url, title, artist string, durationMS int64) Track {
	provider := string(source)
	if provider == "" {
		provider = string(media.SourceYTM)
	}
	id := key
	if id == "" {
		id = url
	}
	return Track{
		Provider:        provider,
		ProviderTrackID: id,
		Title:           title,
		Artist:          artist,
		DurationMS:      durationMS,
	}
}

// Track describes a track to the server, which identifies it by its provider
// and the provider's own track id: a video ID for YouTube Music, a page URL
// for SoundCloud.
type Track struct {
	Provider        string `json:"provider"`
	ProviderTrackID string `json:"provider_track_id"`
	Title           string `json:"title"`
	Artist          string `json:"artist"`
	Album           string `json:"album,omitempty"`
	DurationMS      int64  `json:"duration_ms,omitempty"`
	ISRC            string `json:"isrc,omitempty"`
	// Thumbnail is the track's own cover and AlbumThumbnail the cover of the
	// album or playlist it came from, as picture addresses, so a device that
	// reads the track back can show it without looking it up.
	Thumbnail      string `json:"thumbnail,omitempty"`
	AlbumThumbnail string `json:"album_thumbnail,omitempty"`
}

// Event is one play: what was played, how much of it, and when.
type Event struct {
	ClientEventID string    `json:"client_event_id"`
	Track         Track     `json:"track"`
	PlayedMS      int64     `json:"played_ms"`
	DurationMS    int64     `json:"duration_ms,omitempty"`
	Completed     bool      `json:"completed"`
	Device        string    `json:"device,omitempty"`
	PlayedAt      time.Time `json:"played_at"`
}

// Library is one of the user's own lists.
type Library struct {
	ID         string    `json:"id,omitempty"`
	Kind       string    `json:"kind"`
	Provider   string    `json:"provider,omitempty"`
	ProviderID string    `json:"provider_id,omitempty"`
	Name       string    `json:"name"`
	Cover      string    `json:"cover,omitempty"`
	UpdatedAt  time.Time `json:"updated_at"`
	Deleted    bool      `json:"deleted,omitempty"`
}

// LibraryItem is one track of a list, with the place it holds.
type LibraryItem struct {
	Track     Track     `json:"track"`
	Position  int       `json:"position"`
	UpdatedAt time.Time `json:"updated_at"`
	Deleted   bool      `json:"deleted,omitempty"`
}

// LibraryItems is the items of one library, which the server takes in one
// request each.
type LibraryItems struct {
	LibraryID string        `json:"library_id"`
	Items     []LibraryItem `json:"items"`
}

// Download is one kept track on one device.
type Download struct {
	Track          Track      `json:"track"`
	Device         string     `json:"device"`
	SizeBytes      int64      `json:"size_bytes,omitempty"`
	LastListenedAt *time.Time `json:"last_listened_at,omitempty"`
	UpdatedAt      time.Time  `json:"updated_at"`
	Deleted        bool       `json:"deleted,omitempty"`
}

// Client talks to a Meiro server over HTTP.
type Client struct {
	// BaseURL is the server's root, without a trailing slash.
	BaseURL string
	// Token is the bearer token of the signed-in account, empty before one.
	Token string
	// HTTP is the client requests are made with; nil uses a default.
	HTTP *http.Client
}

// APIError is a refusal the server sent back, with its status.
type APIError struct {
	Status  int
	Message string
}

func (e *APIError) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("sunucu %d döndü", e.Status)
	}
	return fmt.Sprintf("sunucu %d: %s", e.Status, e.Message)
}

// Retryable reports whether an error may pass on a later try: a network
// failure, or a server that is temporarily unwell, which is a 5xx.
func Retryable(err error) bool {
	if err == nil {
		return false
	}
	var api *APIError
	if errors.As(err, &api) {
		return api.Status >= http.StatusInternalServerError
	}
	return true
}

func (c *Client) http() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return &http.Client{Timeout: 30 * time.Second}
}

func (c *Client) url(path string) string {
	return strings.TrimRight(c.BaseURL, "/") + path
}

// post sends a JSON body and decodes the JSON answer into out. A 401 asks the
// caller to sign in again; any other non-200 is an APIError.
func (c *Client) post(ctx context.Context, path string, body, out any) error {
	data, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url(path), bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := c.http().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return apiError(resp)
	}
	if out == nil {
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// get reads a JSON answer into out. A non-200 is an APIError.
func (c *Client) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url(path), nil)
	if err != nil {
		return err
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := c.http().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return apiError(resp)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// Login exchanges an email and password for a session token.
func (c *Client) Login(ctx context.Context, email, password string) (string, error) {
	var out struct {
		Token string `json:"token"`
	}
	err := c.post(ctx, "/api/v1/auth/login", map[string]string{"email": email, "password": password}, &out)
	if err != nil {
		return "", err
	}
	return out.Token, nil
}

// PushEvents sends plays, up to the server's limit, and returns which the
// server took and which it had already.
func (c *Client) PushEvents(ctx context.Context, events []Event) (accepted, duplicates []string, err error) {
	var out struct {
		Accepted   []string `json:"accepted"`
		Duplicates []string `json:"duplicates"`
	}
	err = c.post(ctx, "/api/v1/sync/events", map[string]any{"events": events}, &out)
	return out.Accepted, out.Duplicates, err
}

// PushLibraries sends libraries.
func (c *Client) PushLibraries(ctx context.Context, libraries []Library) ([]string, error) {
	var out struct {
		Accepted []string `json:"accepted"`
	}
	err := c.post(ctx, "/api/v1/sync/libraries", map[string]any{"libraries": libraries}, &out)
	return out.Accepted, err
}

// PushLibraryItems sends the items of one library.
func (c *Client) PushLibraryItems(ctx context.Context, payload LibraryItems) ([]string, error) {
	var out struct {
		Accepted []string `json:"accepted"`
	}
	err := c.post(ctx, "/api/v1/sync/library_items", payload, &out)
	return out.Accepted, err
}

// PushDownloads sends downloads.
func (c *Client) PushDownloads(ctx context.Context, downloads []Download) ([]string, error) {
	var out struct {
		Accepted []string `json:"accepted"`
	}
	err := c.post(ctx, "/api/v1/sync/downloads", map[string]any{"downloads": downloads}, &out)
	return out.Accepted, err
}

// apiError reads a refusal, taking the server's own message when it has one.
func apiError(resp *http.Response) error {
	var body struct {
		Error string `json:"error"`
	}
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	_ = json.Unmarshal(data, &body)
	return &APIError{Status: resp.StatusCode, Message: body.Error}
}
