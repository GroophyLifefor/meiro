package main

import (
	"context"
	"log"
	"strings"
	"time"

	"github.com/elianiva/meiro/internal/match"
	"github.com/elianiva/meiro/internal/mblinks"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// otherSourceTimeout bounds the look for a song on the other provider.
const otherSourceTimeout = 30 * time.Second

// searcher is what reads a provider's search: YouTube Music's client and
// SoundCloud's both do.
type searcher interface {
	Search(ctx context.Context, query string, options youtube.SearchOptions) (*youtube.SearchResult, error)
}

// findOtherSource looks, in the background, for the song that starts playing
// on the provider that does not hold it yet, and when it finds the same
// recording there gives the song a mark for each, so it can be played from
// either. A song reaches the app from one provider's search or page alone; only
// a search of both finds the same one on both, so without this the choice would
// show only for the songs that search happened to bring together.
//
// The match is the one the search uses: the same title and artist, folded, and
// lengths within a few seconds. A song it cannot be sure of is left with the
// one provider. Each song is looked up once.
func (a *app) findOtherSource(item youtube.MusicItem) {
	if !item.IsTrack() || len(item.Sources) > 1 || item.Key() == "" || strings.TrimSpace(item.Title) == "" {
		return
	}
	key := item.Key()
	if a.otherLooked[key] {
		return
	}
	if a.otherLooked == nil {
		a.otherLooked = make(map[string]bool)
	}
	a.otherLooked[key] = true
	own := resolveSource(item)
	ownID := streamKey(item)

	// What was found before, on this device or any other that shares the
	// links, is used as it is: no search is made for it again.
	if a.links != nil {
		if alt, ok := a.links.Alt(string(own), ownID); ok {
			if alt.OtherID != "" {
				a.applyOtherSource(key, withOtherCopy(item, own, altItem(alt)))
				return
			}
			if a.links.Fresh(alt.CheckedAt) {
				return
			}
		}
	}

	var client searcher
	// The song is looked for on the provider that is not its own.
	switch own {
	case media.SourceSoundCloud:
		client = a.ytClient()
	case media.SourceYTM:
		client = a.soundcloudClient()
	default:
		client = a.soundcloudClient()
	}
	query := strings.TrimSpace(item.Title + " " + item.Artist)
	want := matchTrack(item)
	spawn(a, func() *youtube.MusicItem {
		ctx, cancel := context.WithTimeout(context.Background(), otherSourceTimeout)
		defer cancel()
		result, err := client.Search(ctx, query, youtube.SearchOptions{})
		if err != nil || result == nil {
			return nil
		}
		for _, candidate := range result.Items {
			if candidate.IsTrack() && match.Match(want, matchTrack(candidate)) == match.Matched {
				found := candidate
				return &found
			}
		}
		return nil
	}, func(found *youtube.MusicItem) {
		a.rememberOtherSource(own, ownID, found)
		if found == nil {
			return
		}
		a.applyOtherSource(key, withOtherCopy(item, own, *found))
	})
}

// withOtherCopy is item, which is held by the provider own, with the copy of it
// another provider holds. The song goes on playing from the provider it plays
// from.
func withOtherCopy(item youtube.MusicItem, own media.Source, other youtube.MusicItem) youtube.MusicItem {
	updated := addSourceRef(item, other)
	updated.Source = own
	return updated
}

// altItem is the song a stored pairing names on the other provider, as much as
// is needed to give it a mark: where it is held and how it is named there.
func altItem(alt mblinks.Alt) youtube.MusicItem {
	source := media.Source(alt.OtherProvider)
	out := youtube.MusicItem{Source: source, Kind: media.KindTrack}
	if source == media.SourceSoundCloud {
		out.URL = firstNonEmpty(alt.OtherURL, alt.OtherID)
	} else {
		out.VideoID = alt.OtherID
	}
	return out
}

// rememberOtherSource keeps what the look for a song on the other provider
// found, or that it found nothing, for both copies of the song, so neither
// provider is asked about it again.
func (a *app) rememberOtherSource(own media.Source, ownID string, found *youtube.MusicItem) {
	if a.links == nil {
		return
	}
	now := time.Now()
	alt := mblinks.Alt{Provider: string(own), ID: ownID, CheckedAt: now}
	var reverse *mblinks.Alt
	if found != nil {
		other := resolveSource(*found)
		otherID := streamKey(*found)
		alt.OtherProvider, alt.OtherID, alt.OtherURL = string(other), otherID, found.URL
		reverse = &mblinks.Alt{Provider: string(other), ID: otherID, OtherProvider: string(own), OtherID: ownID, CheckedAt: now}
		if own == media.SourceSoundCloud {
			reverse.OtherURL = ownID
		}
	}
	for _, rec := range []*mblinks.Alt{&alt, reverse} {
		if rec == nil {
			continue
		}
		saved, err := a.links.SetAlt(*rec)
		if err != nil {
			log.Printf("keeping the other copy of a song: %v", err)
		}
		a.queueAltLink(saved)
	}
}

// applyOtherSource gives every copy of a song the app holds the sources it now
// knows: the track playing, the queue, and the pages and the search shown.
func (a *app) applyOtherSource(key string, updated youtube.MusicItem) {
	if a.current.Key() == key {
		a.current.Sources = updated.Sources
	}
	a.queue.each(func(it *youtube.MusicItem) {
		if it.Key() == key {
			it.Sources = updated.Sources
		}
	})
	own := updated.Source
	keep := func(items []youtube.MusicItem) {
		for i := range items {
			if items[i].Key() == key {
				items[i].Sources = updated.Sources
				if items[i].Source == "" {
					items[i].Source = own
				}
			}
		}
	}
	keep(a.search.items)
	for i := range a.search.sections {
		keep(a.search.sections[i].Items)
	}
	keep(a.page.feed.items)
	for i := range a.page.feed.sections {
		keep(a.page.feed.sections[i].Items)
	}
	keep(a.page.playable)
	a.page.rowsDirty = true
}
