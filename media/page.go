package media

// BrowseResult represents a provider browse page such as an artist, album,
// playlist, home feed, explore page or library.
type BrowseResult struct {
	// Title and Thumbnail are the page's own heading, the name and picture it
	// puts over its content, as an artist's does.
	Title     string `json:"title,omitempty"`
	Thumbnail string `json:"thumbnail,omitempty"`
	// Description is what a podcast show says of itself, on one line.
	Description       string    `json:"description,omitempty"`
	Items             []Item    `json:"items"`
	Sections          []Section `json:"sections,omitempty"`
	ContinuationToken string    `json:"continuationToken,omitempty"`
	// Library is the save button of an album's or a playlist's header: whether
	// the page is in the library, and what to write to change it. Its ID is
	// empty for a page that offers none.
	Library LibraryState `json:"-"`
	// Subscription is the subscribe button of an artist's header: whether the
	// account follows the artist, and the channel to write. Its ChannelID is
	// empty for a page that offers none.
	Subscription Subscription `json:"-"`
	// QueuePlaylistID is the playlist panel's ID, used to continue radio queues.
	QueuePlaylistID string `json:"queuePlaylistId,omitempty"`
}

// Section is a shelf or grid in a browse response. A continuation response
// without an owning shelf is represented as a synthetic section with an empty
// Title and its continuation renderer as Kind. ContinuationToken can be passed
// to the client's ContinueBrowse to load more items in that section.
type Section struct {
	Title             string `json:"title,omitempty"`
	Kind              string `json:"kind"`
	Items             []Item `json:"items"`
	ContinuationToken string `json:"continuationToken,omitempty"`
}

// SearchFilter is one of the filters a provider offers for a search: the name
// it shows, and the parameters that search with it. The filter that clears them
// all carries neither.
type SearchFilter struct {
	Label  string `json:"label,omitempty"`
	Params string `json:"params,omitempty"`
}

// SearchOptions controls a music search. Params is what one of the search's own
// filters carries; an empty value searches every category.
type SearchOptions struct {
	Params string
}

// SearchResult contains the parsed music items of a search.
type SearchResult struct {
	Items             []Item `json:"items"`
	ContinuationToken string `json:"continuationToken,omitempty"`
	// TopResult is the card a search leads with, which opens a page or plays
	// a video. Its zero value says the search has none.
	TopResult Item `json:"topResult,omitempty"`
	// Filters are the categories the search can be narrowed to, in the order
	// the provider offers them for this query.
	Filters []SearchFilter `json:"filters,omitempty"`
}

// SearchSuggestions is what a provider offers for a query being typed: the
// completed queries, and the items the query already matches.
type SearchSuggestions struct {
	Queries []string `json:"queries"`
	Items   []Item   `json:"items,omitempty"`
}

// Subscription is whether the signed-in account follows an artist, and the
// channel the write names. ChannelID is empty for what the page offers no
// button for: an artist seen signed out, or a page that is not an artist's.
type Subscription struct {
	// ChannelID is the channel of the artist. It is read from the page's
	// button: the browse ID an artist page is opened with can differ from it.
	ChannelID  string
	Subscribed bool
}
