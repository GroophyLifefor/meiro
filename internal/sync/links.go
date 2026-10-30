package sync

import (
	"context"
	"slices"
	"time"
)

// SongLink is what is known of one provider's copy of a song: which MusicBrainz
// record it is, and the artist's. The server keeps it so no other device has to
// ask MusicBrainz again for what one already found.
type SongLink struct {
	Provider        string     `json:"provider"`
	ProviderTrackID string     `json:"provider_track_id"`
	State           string     `json:"state"`
	MBID            string     `json:"mbid,omitempty"`
	Artist          string     `json:"artist,omitempty"`
	ArtistMBID      string     `json:"artist_mbid,omitempty"`
	ArtistState     string     `json:"artist_state,omitempty"`
	CheckedAt       *time.Time `json:"checked_at,omitempty"`
	UpdatedAt       time.Time  `json:"updated_at"`
}

// ArtistLink is what is known of an artist, named by its folded name.
type ArtistLink struct {
	ArtistKey string    `json:"artist_key"`
	Name      string    `json:"name"`
	State     string    `json:"state"`
	MBID      string    `json:"mbid,omitempty"`
	UpdatedAt time.Time `json:"updated_at"`
}

// AltLink is the same song on the other provider. With no OtherTrackID it says
// the other provider was searched and does not hold it.
type AltLink struct {
	Provider        string    `json:"provider"`
	ProviderTrackID string    `json:"provider_track_id"`
	OtherProvider   string    `json:"other_provider,omitempty"`
	OtherTrackID    string    `json:"other_track_id,omitempty"`
	OtherURL        string    `json:"other_url,omitempty"`
	CheckedAt       time.Time `json:"checked_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}

// Links is every kind of link in one value, as it is sent and as it is read.
type Links struct {
	Songs      []SongLink   `json:"songs"`
	Artists    []ArtistLink `json:"artists"`
	Alternates []AltLink    `json:"alternates"`
}

// Empty reports whether there is no link of any kind.
func (l Links) Empty() bool { return len(l.Songs)+len(l.Artists)+len(l.Alternates) == 0 }

// Len is how many links there are.
func (l Links) Len() int { return len(l.Songs) + len(l.Artists) + len(l.Alternates) }

// PushLinks sends links.
func (c *Client) PushLinks(ctx context.Context, links Links) error {
	return c.post(ctx, "/api/v1/sync/links", links, nil)
}

// FetchLinks reads every link the server holds for the account.
func (c *Client) FetchLinks(ctx context.Context) (Links, error) {
	var out Links
	err := c.get(ctx, "/api/v1/links", &out)
	return out, err
}

// AddLinks keeps links to send, replacing a pending link of the same name with
// the newer one, so a link changed twice before the server is reached is sent
// once, as it last was.
func (o *Outbox) AddLinks(links Links) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, song := range links.Songs {
		i := slices.IndexFunc(o.file.Links.Songs, func(x SongLink) bool {
			return x.Provider == song.Provider && x.ProviderTrackID == song.ProviderTrackID
		})
		switch {
		case i < 0:
			o.file.Links.Songs = append(o.file.Links.Songs, song)
		case !song.UpdatedAt.Before(o.file.Links.Songs[i].UpdatedAt):
			o.file.Links.Songs[i] = song
		}
	}
	for _, artist := range links.Artists {
		i := slices.IndexFunc(o.file.Links.Artists, func(x ArtistLink) bool { return x.ArtistKey == artist.ArtistKey })
		switch {
		case i < 0:
			o.file.Links.Artists = append(o.file.Links.Artists, artist)
		case !artist.UpdatedAt.Before(o.file.Links.Artists[i].UpdatedAt):
			o.file.Links.Artists[i] = artist
		}
	}
	for _, alt := range links.Alternates {
		i := slices.IndexFunc(o.file.Links.Alternates, func(x AltLink) bool {
			return x.Provider == alt.Provider && x.ProviderTrackID == alt.ProviderTrackID
		})
		switch {
		case i < 0:
			o.file.Links.Alternates = append(o.file.Links.Alternates, alt)
		case !alt.UpdatedAt.Before(o.file.Links.Alternates[i].UpdatedAt):
			o.file.Links.Alternates[i] = alt
		}
	}
	o.changedLocked()
}

// removeLinks takes sent links out of the queue, but not one that was changed
// again after it was sent: that newer one still waits.
func (o *Outbox) removeLinks(sent Links) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.file.Links.Songs = slices.DeleteFunc(o.file.Links.Songs, func(x SongLink) bool {
		return slices.ContainsFunc(sent.Songs, func(s SongLink) bool {
			return s.Provider == x.Provider && s.ProviderTrackID == x.ProviderTrackID && !x.UpdatedAt.After(s.UpdatedAt)
		})
	})
	o.file.Links.Artists = slices.DeleteFunc(o.file.Links.Artists, func(x ArtistLink) bool {
		return slices.ContainsFunc(sent.Artists, func(s ArtistLink) bool {
			return s.ArtistKey == x.ArtistKey && !x.UpdatedAt.After(s.UpdatedAt)
		})
	})
	o.file.Links.Alternates = slices.DeleteFunc(o.file.Links.Alternates, func(x AltLink) bool {
		return slices.ContainsFunc(sent.Alternates, func(s AltLink) bool {
			return s.Provider == x.Provider && s.ProviderTrackID == x.ProviderTrackID && !x.UpdatedAt.After(s.UpdatedAt)
		})
	})
	o.changedLocked()
}

// linkBatches splits links into requests of at most MaxBatch of each kind.
func linkBatches(l Links) []Links {
	var out []Links
	for _, b := range chunks(l.Songs, MaxBatch) {
		out = append(out, Links{Songs: b})
	}
	for _, b := range chunks(l.Artists, MaxBatch) {
		out = append(out, Links{Artists: b})
	}
	for _, b := range chunks(l.Alternates, MaxBatch) {
		out = append(out, Links{Alternates: b})
	}
	return out
}
