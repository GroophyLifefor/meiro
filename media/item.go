package media

import "strings"

// ItemKind is the renderer an item was parsed from. It says how the provider
// drew the item, not what it opens: see Target for that.
type ItemKind string

const (
	KindTrack     ItemKind = "track"
	KindMusicItem ItemKind = "music_item"
	KindVideo     ItemKind = "video"
	KindPlaylist  ItemKind = "playlist"
	KindAlbum     ItemKind = "album"
	KindArtist    ItemKind = "artist"
	// KindEpisode is an episode of a podcast. It plays as a track does.
	KindEpisode ItemKind = "episode"
)

// TargetKind is what an item opens when it is chosen.
type TargetKind uint8

const (
	// TargetNone is an item that opens nothing.
	TargetNone TargetKind = iota
	// TargetTrack plays the item.
	TargetTrack
	// TargetAlbum, TargetPlaylist, TargetArtist and TargetPodcast open the
	// item's page.
	TargetAlbum
	TargetPlaylist
	TargetArtist
	TargetPodcast
)

// Target is what an item opens, and the ID that names it: a video ID for a
// track, a browse ID for an album, a playlist, an artist or a podcast.
type Target struct {
	Kind TargetKind
	ID   string
}

// Opens reports whether the target opens a page of its own.
func (t Target) Opens() bool {
	return t.Kind == TargetAlbum || t.Kind == TargetPlaylist || t.Kind == TargetArtist || t.Kind == TargetPodcast
}

// SourceRef names one provider's copy of an item: the source it is read and
// played through, and the ID it is named by there. ID is a video ID for
// YouTube Music and an upload ID for SoundCloud; URL is the provider page for
// a source that resolves by URL rather than by ID.
type SourceRef struct {
	Source Source `json:"source"`
	ID     string `json:"id,omitempty"`
	URL    string `json:"url,omitempty"`
}

// Item is the common subset of a provider's song, video, album, artist and
// playlist renderers. It is what the interface carries and the queue keeps.
type Item struct {
	// Source is where the item came from, which chooses the resolver that
	// plays it. An item with no source reads as YouTube Music, which keeps a
	// queue saved before sources existed playable. When Sources lists more
	// than one provider, Source names the one that plays by default.
	Source Source `json:"source,omitempty"`
	ID     string `json:"id,omitempty"`
	// Sources are the providers that hold this item, in preference order: the
	// same recording found on two providers is one item with two refs. It is
	// empty for an item that predates it, whose single Source field still
	// names where it came from.
	Sources []SourceRef `json:"sources,omitempty"`
	// VideoID, BrowseID, PlaylistID and PlaylistSetID are YouTube Music's own
	// fields; they are generalized when a second provider is added.
	VideoID       string `json:"videoId,omitempty"`
	BrowseID      string `json:"browseId,omitempty"`
	PlaylistID    string `json:"playlistId,omitempty"`
	PlaylistSetID string `json:"playlistSetVideoId,omitempty"`
	// URL is the provider page an item is read and resolved by, for a provider
	// that has no video ID. It is empty for YouTube Music, whose items carry
	// a VideoID instead.
	URL       string   `json:"url,omitempty"`
	Title     string   `json:"title,omitempty"`
	Subtitle  string   `json:"subtitle,omitempty"`
	Kind      ItemKind `json:"kind,omitempty"`
	Duration  string   `json:"duration,omitempty"`
	Thumbnail string   `json:"thumbnail,omitempty"`
	// Description is what a show's page says of an episode, on one line. It
	// is not saved with the item: it runs long, and only the show's page
	// shows it.
	Description string `json:"-"`
	// ProgressPercent is the account's reported episode progress, when the
	// show page includes it. It is a hint for resume, not saved with the item.
	ProgressPercent int `json:"-"`
	// Like is whether the account has liked the track, when the page that
	// held it said. It is not saved with the item: a like changes elsewhere.
	Like LikeStatus `json:"-"`
	// Library is whether the account has saved the album or the playlist, when
	// the item's menu said, and nil when it did not. It is not saved with the
	// item, for the same reason, and it is a pointer so that the items that
	// have none, which are nearly all of them, stay small: a page's rows copy
	// them. Nothing changes what it points to; a change gives the item another.
	Library *LibraryState `json:"-"`
	// Artist is the first artist the item credits, which the scrobble and the
	// lyrics search take, Album the album, and Artists every artist. They are
	// read from the links of the byline rather than from the flattened
	// Subtitle, which also holds the kind, the year and the length. They are
	// empty when the byline links none, and nothing reconstructs them from the
	// Subtitle. They are strings, not a list, so that an item stays comparable.
	Artist string `json:"artist,omitempty"`
	// Artists is every artist the item credits, in the order its byline links
	// them, joined as "First, Second". It is empty for an item whose byline links
	// none. Artist is the first of them.
	Artists string `json:"artists,omitempty"`
	Album   string `json:"album,omitempty"`
}

// Target says what the item opens. A video ID always wins: rows name their
// artist beside the track itself, and the artist must not capture the tap.
// Album and playlist IDs route only items with no video; the artist only
// items with neither video nor collection.
func (item Item) Target() Target {
	browse, playlist := item.BrowseID, item.PlaylistID
	if playlist == "" {
		playlist = browse
	}
	album, list := IsAlbumID(browse), IsBrowsablePlaylist(playlist)
	switch {
	case item.VideoID != "" && !album && !list:
		return Target{TargetTrack, item.VideoID}
	case album:
		return Target{TargetAlbum, browse}
	case list:
		return Target{TargetPlaylist, playlist}
	case IsPodcastID(browse):
		return Target{TargetPodcast, browse}
	case IsArtistID(browse):
		return Target{TargetArtist, browse}
	case item.VideoID != "":
		return Target{TargetTrack, item.VideoID}
	case item.URL != "" && item.Kind == KindTrack:
		// A provider with no video ID, as SoundCloud, carries the page URL
		// it plays and is read by instead.
		return Target{TargetTrack, item.URL}
	}
	return Target{}
}

// Key is the identity playback and the queue use for an item: its video ID
// for a provider that has one, and the page URL for one that does not.
// YouTube Music items carry a video ID; a SoundCloud track carries the page
// URL it is resolved by.
func (item Item) Key() string {
	if item.VideoID != "" {
		return item.VideoID
	}
	return item.URL
}

// effectiveSource is the source an item is played from: its Source field, or
// YouTube Music for one that predates the field.
func (item Item) effectiveSource() Source {
	if item.Source == "" {
		return SourceYTM
	}
	return item.Source
}

// ownRef reads the reference an item's own fields name: YouTube Music is
// keyed by a video ID, a provider that resolves by URL by its page URL.
func (item Item) ownRef() SourceRef {
	source := item.effectiveSource()
	ref := SourceRef{Source: source, ID: item.ID, URL: item.URL}
	if source == SourceYTM && ref.ID == "" {
		ref.ID = item.VideoID
	}
	return ref
}

// HasSource reports whether the item has a copy on source.
func (item Item) HasSource(source Source) bool {
	_, ok := item.RefFor(source)
	return ok
}

// RefFor returns the item's reference on source, and whether it has one. An
// item whose Sources are empty is read from its own fields: its Source, and
// the video ID or page URL it carries.
func (item Item) RefFor(source Source) (SourceRef, bool) {
	for _, ref := range item.Sources {
		if ref.Source == source {
			return ref, true
		}
	}
	if len(item.Sources) > 0 || item.effectiveSource() != source {
		return SourceRef{}, false
	}
	return item.ownRef(), true
}

// FirstRef returns the item's preferred reference: the first provider that
// holds it, or a reference read from its own fields when it lists none.
func (item Item) FirstRef() (SourceRef, bool) {
	if len(item.Sources) > 0 {
		return item.Sources[0], true
	}
	return item.ownRef(), true
}

// WithSource returns the item played from source: its fields name the
// reference that provider holds. A YouTube Music item keeps the video ID its
// Key is named by, so the same track stays the same track in a queue or a
// page after it is switched to another provider. An item with no reference on
// source is returned unchanged.
func (item Item) WithSource(source Source) Item {
	ref, ok := item.RefFor(source)
	if !ok {
		return item
	}
	item.Source = ref.Source
	item.ID = ref.ID
	if item.VideoID == "" && ref.Source == SourceYTM {
		item.VideoID = ref.ID
	}
	item.URL = ref.URL
	return item
}

// IsTrack reports whether choosing the item plays it.
func (item Item) IsTrack() bool { return item.Target().Kind == TargetTrack }

// IsEpisode reports whether the item is an episode of a podcast.
func (item Item) IsEpisode() bool { return item.Kind == KindEpisode }

// IsVideo reports whether the item is a music video rather than a song.
func (item Item) IsVideo() bool { return item.Kind == KindVideo }

// ArtistID is the artist a track names beside itself, or "" when it names
// none.
func (item Item) ArtistID() string {
	if item.VideoID != "" && IsArtistID(item.BrowseID) {
		return item.BrowseID
	}
	return ""
}

// InLibrary is what the item's menu said of the album or the playlist, or the
// zero state when it said nothing.
func (item Item) InLibrary() LibraryState {
	if item.Library == nil {
		return LibraryState{}
	}
	return *item.Library
}

// LikeStatus is whether the signed-in account has liked a track, as the
// provider's like buttons name it. It is a byte, since every item of every
// page carries one.
type LikeStatus uint8

const (
	// LikeUnknown is a track whose page did not say.
	LikeUnknown LikeStatus = iota
	// Liked is a track the account has given a thumbs-up.
	Liked
	// Indifferent is a track the account has rated neither way.
	Indifferent
	// Disliked is a track the account has given a thumbs-down. Meiro reads it
	// but does not write it.
	Disliked
)

// LibraryState is whether an album or a playlist is in the account's library,
// and what to write to change it. ID is empty for what the page offers no way
// to save: a playlist the account made, a mix, or anything seen signed out.
type LibraryState struct {
	// ID is the playlist the write names. For an album it is the album's audio
	// playlist, not its browse ID, which is why it is read from the page
	// rather than derived.
	ID    string
	Saved bool
}

// The predicates below read YouTube Music's ID shapes. They live beside the
// item because what an item opens is decided from them, and a second provider
// brings its own: the layer is generalized when it does.

// IsAlbumID reports whether an ID names an album page.
func IsAlbumID(id string) bool {
	return strings.HasPrefix(id, "MPR") || strings.Contains(id, "privately_owned_release")
}

// IsPodcastID reports whether an ID names a podcast show's page. Its episodes
// are a playlist, but the show's page is not the playlist's.
func IsPodcastID(id string) bool { return strings.HasPrefix(id, "MPSPP") }

// EpisodeVideoIDFromBrowseID reads the video ID carried by an episode page's
// browse ID, such as MPEDUZ83quirqLA.
func EpisodeVideoIDFromBrowseID(id string) string {
	videoID, ok := strings.CutPrefix(id, "MPED")
	if !ok || len(videoID) != 11 {
		return ""
	}
	return videoID
}

// IsArtistID reports whether an ID names an artist page.
func IsArtistID(id string) bool {
	return strings.HasPrefix(id, "UC") || strings.Contains(id, "privately_owned_artist")
}

// IsBrowsablePlaylist reports whether an ID names a page the app can open.
// Mix and radio queues ride along in taps and menus, but browsing one is
// empty: only library, community and product playlists open.
func IsBrowsablePlaylist(id string) bool {
	return strings.HasPrefix(id, "VL") || strings.HasPrefix(id, "PL")
}
