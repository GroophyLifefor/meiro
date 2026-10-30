package sync

import (
	"context"
	"errors"
	"net/url"
	"strconv"
)

// MaxChanges is how many records the server gives in one answer to a read of
// what changed.
const MaxChanges = 1000

// ChangedLibrary is a list as the server holds it, with the place in the
// server's order of changes it was last changed at.
type ChangedLibrary struct {
	Library
	Seq int64 `json:"seq"`
}

// ChangedItem is a track of a list as the server holds it, or the tombstone of
// one, with the place in the server's order of changes it was last changed at.
type ChangedItem struct {
	LibraryID string `json:"library_id"`
	LibraryItem
	Seq int64 `json:"seq"`
}

// Changes is what changed on the server after a point in its order of changes.
// Next is where to read on from, and More says the server has more to give
// than fit in one answer.
type Changes struct {
	Libraries []ChangedLibrary `json:"libraries"`
	Items     []ChangedItem    `json:"items"`
	Next      int64            `json:"next"`
	More      bool             `json:"more"`
}

// FetchChanges reads one answer of what changed after since, tombstones
// included, in the server's order. A limit outside 1 to MaxChanges asks for
// MaxChanges.
func (c *Client) FetchChanges(ctx context.Context, since int64, limit int) (Changes, error) {
	if limit < 1 || limit > MaxChanges {
		limit = MaxChanges
	}
	query := url.Values{"since": {strconv.FormatInt(since, 10)}, "limit": {strconv.Itoa(limit)}}
	var out Changes
	err := c.get(ctx, "/api/v1/sync/changes?"+query.Encode(), &out)
	return out, err
}

// FetchAllChanges reads everything that changed after since, asking again from
// where each answer left off while the server says there is more. The
// answers are joined into one whose Next is where the last one left off. A
// server that says there is more but does not move on is an error, so it
// cannot keep the reader asking for ever.
func (c *Client) FetchAllChanges(ctx context.Context, since int64) (Changes, error) {
	all := Changes{Next: since}
	for {
		page, err := c.FetchChanges(ctx, all.Next, MaxChanges)
		if err != nil {
			return Changes{}, err
		}
		all.Libraries = append(all.Libraries, page.Libraries...)
		all.Items = append(all.Items, page.Items...)
		if !page.More {
			all.Next = max(all.Next, page.Next)
			return all, nil
		}
		if page.Next <= all.Next {
			return Changes{}, errors.New("sync: the server has more changes but did not move on")
		}
		all.Next = page.Next
	}
}
