package sync

import (
	"context"
	"errors"
	"net/url"
	"strconv"
)

// MaxPlays is how many plays the server gives in one answer to a read of the
// account's plays.
const MaxPlays = 1000

// PlayedEvent is a play as the server holds it, with the place in the server's
// order of plays it was stored at. The server gives the plays of every device
// of the account, this one's included.
type PlayedEvent struct {
	Event
	Seq int64 `json:"seq"`
}

// Plays is what the server holds of an account's plays after a point in its
// order. Next is where to read on from, and More says the server has more to
// give than fit in one answer.
type Plays struct {
	Events []PlayedEvent `json:"events"`
	Next   int64         `json:"next"`
	More   bool          `json:"more"`
}

// FetchEvents reads one answer of the plays stored after since, in the
// server's order. A limit outside 1 to MaxPlays asks for MaxPlays.
func (c *Client) FetchEvents(ctx context.Context, since int64, limit int) (Plays, error) {
	if limit < 1 || limit > MaxPlays {
		limit = MaxPlays
	}
	query := url.Values{"since": {strconv.FormatInt(since, 10)}, "limit": {strconv.Itoa(limit)}}
	var out Plays
	err := c.get(ctx, "/api/v1/events?"+query.Encode(), &out)
	return out, err
}

// FetchAllEvents reads every play stored after since, asking again from where
// each answer left off while the server says there is more. The answers are
// joined into one whose Next is where the last one left off. A server that
// says there is more but does not move on is an error, so it cannot keep the
// reader asking for ever.
func (c *Client) FetchAllEvents(ctx context.Context, since int64) (Plays, error) {
	all := Plays{Next: since}
	for {
		page, err := c.FetchEvents(ctx, all.Next, MaxPlays)
		if err != nil {
			return Plays{}, err
		}
		all.Events = append(all.Events, page.Events...)
		if !page.More {
			all.Next = max(all.Next, page.Next)
			return all, nil
		}
		if page.Next <= all.Next {
			return Plays{}, errors.New("sync: the server has more plays but did not move on")
		}
		all.Next = page.Next
	}
}
