package main

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/match"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

const (
	// suggestDelay is how long typing pauses before the app asks YouTube
	// Music for what it offers, and suggestTimeout bounds one such request.
	suggestDelay   = 180 * time.Millisecond
	suggestTimeout = 5 * time.Second
	// searchTimeout bounds one provider's search. The two providers are asked
	// together and not waited on together: whichever answers first is shown
	// while the other is still out, and one that does not answer in time is
	// left out of the results.
	searchTimeout = 7 * time.Second
	// suggestQueries and suggestItems bound what the panel under the search
	// bar lists, as YouTube Music's own panel does.
	suggestQueries = 6
	suggestItems   = 5
	// searchPlaceholder names what a search finds.
	searchPlaceholder = "Search songs, albums, artists, podcasts"
)

// providerSearch is one provider's answer to a search out, and whether it has
// arrived.
type providerSearch struct {
	items []youtube.MusicItem
	done  bool
	err   error
}

// searchState is the search page. Typing only edits query; a search runs when
// the user submits it, and submitted is what the results are for.
type searchState struct {
	pageState
	query     string
	submitted string
	// params is the filter the results are for, as the filters of the search
	// carry it; empty searches every category.
	params string
	// filters are the categories YouTube Music offers for this query, in its
	// order. The one that clears them all has no parameters.
	filters []youtube.SearchFilter
	// top is the card the results lead with, if they have one.
	top youtube.MusicItem
	// ytm and sc are the two searches out for the query, one per provider,
	// and ytmLoad and scLoad are those requests in flight. They answer
	// independently, so the results can show one provider's tracks before the
	// other has answered.
	ytm, sc         providerSearch
	ytmLoad, scLoad task
	// note is a line shown over the results, as "From this device" for a
	// search that fell back to what the app holds locally.
	note string
	// suggestions are what YouTube Music offers for the text being typed:
	// the completed queries, and the items it already matches. suggestOpen
	// hides them again once one is chosen, until typing resumes.
	suggestions youtube.SearchSuggestions
	suggest     task
	suggestOpen bool
}

// runSearch searches for query with the filter given. It is the only way a
// search starts: typing in the field never does. An empty query clears the
// page. Every source in the preference order is searched, together.
func (a *app) runSearch(query, params string) {
	query = strings.TrimSpace(query)
	a.cancelSuggestions()
	// A "show more" in flight belongs to the results being replaced.
	a.pageMore.stop()
	s := &a.search
	a.stopSearches()
	if query == "" {
		s.query, s.submitted, s.params, s.pageState = "", "", "", pageState{}
		s.filters, s.top = nil, youtube.MusicItem{}
		s.ytm, s.sc, s.note = providerSearch{}, providerSearch{}, ""
		a.loadArtists("")
		a.page.rowsDirty = true
		return
	}
	s.query, s.submitted, s.params = query, query, params
	// The MusicBrainz artist section follows the same query as the results,
	// and loads beside them.
	a.loadArtists(query)
	// The filters of the search being replaced stay until the answer carries
	// its own: the row of chips holds its place while the results load, and
	// the filter chosen shows as chosen at once.
	a.settings.Remember(query)
	a.saveSettings()
	s.pageState = pageState{loading: true}
	s.ytm, s.sc, s.note = providerSearch{}, providerSearch{}, ""
	a.page.rowsDirty = true
	a.list.ScrollTo(0, ui.Start)
	a.searchProviders(query, params)
}

// stopSearches drops both searches in flight, so a new query's results are
// not folded together with the ones being replaced.
func (a *app) stopSearches() {
	a.search.ytmLoad.stop()
	a.search.scLoad.stop()
}

// searchProviders asks every source for the query, together. Each answer is
// shown as it arrives; the one that fails is simply left out, and when both
// fail the results come from what this device holds.
func (a *app) searchProviders(query, params string) {
	s := &a.search
	yt, sc := a.ytClient(), a.soundcloudClient()
	startTask(a, &s.ytmLoad, searchTimeout, func(ctx context.Context) (*youtube.SearchResult, error) {
		return yt.Search(ctx, query, youtube.SearchOptions{Params: params})
	}, func(result *youtube.SearchResult, err error) {
		s.ytm.done, s.ytm.err = true, err
		if result != nil {
			s.ytm.items = result.Items
			s.filters, s.top, s.more = result.Filters, result.TopResult, result.ContinuationToken
		}
		a.applySearchResults()
	})
	if !a.soundCloudWanted(params) {
		// A filter to albums, playlists, artists or the like names things
		// SoundCloud's search does not tell apart, so it adds nothing.
		s.scLoad.stop()
		s.sc.done, s.sc.err = true, nil
		a.applySearchResults()
		return
	}
	startTask(a, &s.scLoad, searchTimeout, func(ctx context.Context) (*youtube.SearchResult, error) {
		return sc.Search(ctx, query, youtube.SearchOptions{Params: params})
	}, func(result *youtube.SearchResult, err error) {
		s.sc.done, s.sc.err = true, err
		if result != nil {
			s.sc.items = result.Items
		}
		a.applySearchResults()
	})
}

// soundCloudWanted says whether SoundCloud is asked for a search with the
// filter params: one with none is, and one narrowed to songs or videos, which
// are the tracks it has.
func (a *app) soundCloudWanted(params string) bool {
	if params == "" {
		return true
	}
	for _, filter := range a.search.filters {
		if filter.Params == params {
			label := strings.ToLower(strings.TrimSpace(filter.Label))
			return label == "songs" || label == "videos"
		}
	}
	return false
}

// applySearchResults folds the providers' answers into the rows. Whichever
// answered is shown at once, and while the other is still out the page says
// the rest is on its way. When both are out of reach the results come from
// what this device holds.
func (a *app) applySearchResults() {
	s := &a.search
	a.page.rowsDirty = true
	ytm, sc := s.ytm, s.sc
	if !ytm.done && !sc.done {
		return
	}
	if ytm.done && sc.done && ytm.err != nil && sc.err != nil {
		a.localSearch(s.submitted)
		return
	}
	s.items = mergeProviders(ytm.items, sc.items)
	s.loading, s.err, s.offline, s.note = false, "", false, ""
	s.partial = !ytm.done || !sc.done
}

// mergeProviders folds two providers' search results into one list. A track
// YouTube Music returned keeps its place and gains a SoundCloud reference when
// a SoundCloud track is the same recording; a SoundCloud track that matches
// nothing is a row of its own, put among the YouTube Music rows (see
// interleave) so it is seen without scrolling past all of them. Titles,
// artists and artwork stay YouTube Music's, which are the cleaner ones. A
// track the app cannot be sure of is left as two rows.
func mergeProviders(ytm, sc []youtube.MusicItem) []youtube.MusicItem {
	if len(sc) == 0 {
		return ytm
	}
	if len(ytm) == 0 {
		return sc
	}
	merged := make([]youtube.MusicItem, len(ytm))
	copy(merged, ytm)
	matched := make([]bool, len(merged))
	var own []youtube.MusicItem
	for _, track := range sc {
		if !track.IsTrack() {
			continue
		}
		index := matchingTrack(merged, matched, track)
		if index < 0 {
			own = append(own, track)
			continue
		}
		matched[index] = true
		merged[index] = addSourceRef(merged[index], track)
	}
	return interleave(merged, own, soundCloudEvery)
}

// soundCloudEvery is how many YouTube Music rows come before each row of a
// SoundCloud track that YouTube Music does not have.
const soundCloudEvery = 3

// interleave puts one of extra after every `every` rows of items, in order, and
// the ones left over after the last row. Appending them all at the end would
// leave them below every row YouTube Music returned, and its pages go on, so
// the listener would never reach them.
func interleave(items, extra []youtube.MusicItem, every int) []youtube.MusicItem {
	if len(extra) == 0 {
		return items
	}
	out := make([]youtube.MusicItem, 0, len(items)+len(extra))
	for i, item := range items {
		out = append(out, item)
		if (i+1)%every == 0 && len(extra) > 0 {
			out = append(out, extra[0])
			extra = extra[1:]
		}
	}
	return append(out, extra...)
}

// matchingTrack is the first unmatched song of items that is the same
// recording as track, or -1.
func matchingTrack(items []youtube.MusicItem, matched []bool, track youtube.MusicItem) int {
	other := matchTrack(track)
	for i := range items {
		if matched[i] || !items[i].IsTrack() {
			continue
		}
		if match.Match(matchTrack(items[i]), other) == match.Matched {
			return i
		}
	}
	return -1
}

// matchTrack is what the matcher compares of an item: its title, the artist
// its byline names (or the first part of its subtitle), and its length.
func matchTrack(item youtube.MusicItem) match.Track {
	artist := item.Artist
	if artist == "" {
		artist, _, _ = strings.Cut(item.Subtitle, " • ")
	}
	return match.Track{Title: item.Title, Artist: artist, Duration: parseDuration(item.Duration)}
}

// addSourceRef folds another provider's copy of a track into item: item keeps
// its own fields and gains the source the copy came from. It is a no-op when
// item already has that source.
func addSourceRef(item, other youtube.MusicItem) youtube.MusicItem {
	ref, ok := other.FirstRef()
	if !ok {
		return item
	}
	if _, exists := item.RefFor(ref.Source); exists {
		return item
	}
	if len(item.Sources) == 0 {
		if own, ok := item.FirstRef(); ok {
			item.Sources = []media.SourceRef{own}
		}
	}
	item.Sources = append(item.Sources, ref)
	// A track that two providers hold plays from whichever the default
	// chooses, not from the parse's own provider, so its Source is left for
	// the default to name.
	item.Source = ""
	return item
}

// localSearch fills the results from what this device holds: the tracks the
// user downloaded, and the tracks the playback caches kept. It runs when both
// providers are out of reach, so a search still finds music without a
// network. The results carry a note saying where they came from.
func (a *app) localSearch(query string) {
	s := &a.search
	s.items = filterLocal(a.localItems(), query)
	s.loading, s.partial, s.err, s.offline = false, false, "", false
	s.filters, s.top, s.more = nil, youtube.MusicItem{}, ""
	s.note = "From this device"
	a.page.rowsDirty = true
}

// localItems is every track this device holds, from the downloads and the
// audio caches, as the search results show them.
func (a *app) localItems() []youtube.MusicItem {
	items := make([]youtube.MusicItem, 0, 16)
	seen := make(map[string]bool)
	add := func(item youtube.MusicItem) {
		ref, ok := item.FirstRef()
		if !ok {
			return
		}
		key := string(ref.Source) + "\x00" + ref.ID + "\x00" + ref.URL
		if seen[key] {
			return
		}
		seen[key] = true
		if ref, ok := item.FirstRef(); ok {
			item.Sources = []media.SourceRef{ref}
		}
		items = append(items, item)
	}
	for _, rec := range a.downloads.List() {
		add(downloadMusicItem(rec))
	}
	for _, source := range a.providerOrder() {
		cache := a.cacheFor(source)
		if cache == nil {
			continue
		}
		for _, meta := range cache.Meta() {
			add(cachedMusicItem(source, meta))
		}
	}
	return items
}

// cachedMusicItem builds the track a cache metadata entry plays.
func cachedMusicItem(source media.Source, meta audio.TrackMeta) youtube.MusicItem {
	item := youtube.MusicItem{
		Source:   source,
		Title:    meta.Title,
		Subtitle: meta.Artist,
		Artist:   meta.Artist,
		Kind:     media.KindTrack,
		Duration: meta.Duration,
	}
	if source == media.SourceSoundCloud {
		item.URL = meta.Key
	} else {
		item.VideoID = meta.Key
	}
	return item
}

// filterLocal keeps the local tracks whose title or artist holds the query,
// compared in lower case.
func filterLocal(items []youtube.MusicItem, query string) []youtube.MusicItem {
	needle := strings.ToLower(strings.TrimSpace(query))
	if needle == "" {
		return items
	}
	out := make([]youtube.MusicItem, 0, len(items))
	for _, item := range items {
		if strings.Contains(strings.ToLower(item.Title), needle) ||
			strings.Contains(strings.ToLower(item.Artist), needle) ||
			strings.Contains(strings.ToLower(item.Subtitle), needle) {
			out = append(out, item)
		}
	}
	return out
}

// runSearchFrom runs a search from wherever the user is: submitting the search
// bar shows the search page with what it ran.
func (a *app) runSearchFrom(query string) {
	if strings.TrimSpace(query) == "" {
		return
	}
	if r, _ := a.route(); r.id != routeSearch {
		a.navigate(routeSearch)
	}
	a.runSearch(query, "")
}

// suggestSearches asks YouTube Music what it offers for the text being typed
// once typing pauses. Each call replaces the request before it, so only the
// last query's answer is kept.
func (a *app) suggestSearches(query string) {
	query = strings.TrimSpace(query)
	a.cancelSuggestions()
	if query == "" {
		return
	}
	a.search.suggestOpen = true
	client := a.client()
	startTask(a, &a.search.suggest, suggestDelay+suggestTimeout, func(ctx context.Context) (*youtube.SearchSuggestions, error) {
		if err := a.sleep(ctx, suggestDelay); err != nil {
			return nil, err
		}
		return client.GetSearchSuggestions(ctx, query)
	}, func(suggestions *youtube.SearchSuggestions, err error) {
		if err != nil || suggestions == nil {
			suggestions = &youtube.SearchSuggestions{}
		}
		a.search.suggestions = *suggestions
	})
}

// cancelSuggestions stops a pending request for suggestions, forgets what it
// found, and closes the panel they open: what a search, a navigation and a
// cleared field do.
func (a *app) cancelSuggestions() {
	a.search.suggest.stop()
	a.search.suggestions = youtube.SearchSuggestions{}
	a.search.suggestOpen = false
}

// searchBar is the field every page searches from, in the top bar. Submitting
// it shows the search page; typing asks YouTube Music for what it offers,
// which the panel under the bar shows.
func (a *app) searchBar(c *ui.Context) ui.Element {
	trailingPadding := float32(0)
	if a.keyboard.hints {
		trailingPadding = 40
	}
	result, bar := m3.SearchBar(c, m3.SearchSpec{
		Value:           &a.search.query,
		Placeholder:     searchPlaceholder,
		Key:             "search",
		Focus:           &a.searchField,
		Height:          48,
		MaxWidth:        520,
		TrailingPadding: trailingPadding,
	})
	switch {
	case result.Submitted:
		a.runSearchFrom(a.search.query)
	case result.Cleared:
		a.search.query = ""
		a.cancelSuggestions()
	case result.Changed:
		a.suggestSearches(a.search.query)
	}
	return bar
}

// searchSuggestions shows under the bar what YouTube Music offers for the text
// being typed: the completed queries, and the items it already matches.
// Choosing either searches what it names.
func (a *app) searchSuggestions(c *ui.Context, bar ui.Element) {
	s := &a.search
	if !s.suggestOpen || strings.TrimSpace(s.query) == "" {
		return
	}
	queries := s.suggestions.Queries
	if len(queries) > suggestQueries {
		queries = queries[:suggestQueries]
	}
	items := s.suggestions.Items
	if len(items) > suggestItems {
		items = items[:suggestItems]
	}
	if len(queries) == 0 && len(items) == 0 {
		return
	}
	width := bar.Bounds().W
	if width < 320 {
		width = 320
	}
	ui.PopoverBase(c, bar, &s.suggestOpen, func(panel ui.Element) {
		sc := m3.Of(c).Scheme
		panel.Key("search-suggestions").Width(width).Padding(8).Gap(2).Radius(m3.Large).Margin(8, 0, 0, 0).
			Background(sc.SurfaceContainerHigh).TextColor(sc.OnSurface)
		m3.Elevation(c, panel, 3)
		panel.Children(func() {
			for _, query := range queries {
				a.suggestionRow(c, query)
			}
			if len(queries) > 0 && len(items) > 0 {
				ui.Box(c).Height(1).FillWidth().Margin(8, 8).Background(sc.OutlineVariant)
			}
			var tracks []youtube.MusicItem
			for _, item := range items {
				if item.IsTrack() {
					tracks = append(tracks, item)
				}
			}
			for i, item := range items {
				a.suggestionItem(c, item, tracks, i)
			}
		})
	})
}

// suggestionRow is one completed query of the panel under the search bar.
func (a *app) suggestionRow(c *ui.Context, query string) {
	sc := m3.Of(c).Scheme
	row := ui.ButtonBase(c.Key("suggestion-" + query))
	row.Height(m3.MinTarget).PaddingX(12).Gap(12).AlignItems(ui.Center).Radius(m3.Medium).
		Cursor(ui.CursorPointer).Label(query).
		Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), row.Pressed(), row.FocusVisible()))
	row.Children(func() {
		ui.Icon(c, m3.IconSearch).FontSize(20).TextColor(sc.OnSurfaceVariant)
		m3.Text(c, m3.BodyLarge, query).Grow(1).MinWidth(0).SingleLine().Tooltip(query).TextColor(sc.OnSurface)
	})
	if row.Clicked() {
		// The panel hangs under the field every page searches from, so a
		// query chosen from it shows the search page wherever it was typed.
		a.runSearchFrom(query)
	}
}

// suggestionItem is one item of the panel under the search bar: what YouTube
// Music already matches the text being typed with. Choosing it opens what it
// names; a right click offers the menu the item offers wherever else it
// appears, as a song row or a card does.
func (a *app) suggestionItem(c *ui.Context, item youtube.MusicItem, tracks []youtube.MusicItem, position int) {
	sc := m3.Of(c).Scheme
	key := "suggestion-item-" + strconv.Itoa(position)
	row := ui.ButtonBase(c.Key(key))
	row.Height(56).Padding(8, 8).Gap(12).Radius(m3.Medium).AlignItems(ui.Center).
		Cursor(ui.CursorPointer).Label(item.Title).
		Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), row.Pressed(), row.FocusVisible()))
	var menuAnchor ui.Element
	hasAnchor := false
	row.Children(func() {
		a.art(c, item.Thumbnail, 128, 40, artRadius(item, m3.Small))
		ui.Column(c).Grow(1).MinWidth(0).Children(func() {
			m3.Text(c, m3.BodyLarge, item.Title).SingleLine().Tooltip(item.Title).TextColor(sc.OnSurface)
			if item.Subtitle != "" {
				m3.Text(c, m3.BodySmall, item.Subtitle).SingleLine().Tooltip(item.Subtitle).TextColor(sc.OnSurfaceVariant)
			}
		})
		// A menu the pointer opened points at the pointer, in the row's own
		// layout.
		menuAnchor, hasAnchor = a.menu.pointerAnchor(c, key)
	})
	if row.RightClicked() {
		x, y, _ := row.PointerPosition()
		a.menu.show(key, true, x, y)
	}
	if row.Shortcut(ui.Shift, ui.KeyF10) {
		a.menu.show(key, false, 0, 0)
	}
	if a.menu.owns(key) {
		// The row has no menu button to point at: the menu follows the
		// pointer, and the row on the one frame the pointer does not know
		// about yet.
		anchor := row
		if hasAnchor {
			anchor = menuAnchor
		}
		a.cardMenu(c, anchor, key, item, tracks)
	}
	if row.Clicked() {
		a.cancelSuggestions()
		a.activate(item, tracks)
	}
}

// searchPage is the filters of the search over what it found. The field
// itself is in the top bar, which every page searches from.
func (a *app) searchPage(c *ui.Context) {
	ui.Column(c).Fill().Children(func() {
		a.searchFilters(c)
		if a.search.submitted == "" {
			a.searchLanding(c)
			return
		}
		if a.search.note != "" {
			sc := m3.Of(c).Scheme
			m3.Text(c, m3.LabelMedium, a.search.note).Padding(4, pageGutter, 4).TextColor(sc.OnSurfaceVariant)
		}
		a.pageList(c)
	})
}

// searchFilters are the filters YouTube Music offers for the search shown, with
// the chip that clears them when one of them is on. Which one is on is the
// parameters the search ran with rather than a place in the row: the row is
// YouTube's own, and it differs from query to query.
func (a *app) searchFilters(c *ui.Context) {
	if len(a.search.filters) == 0 {
		return
	}
	ui.Row(c).Gap(8).Wrap().Shrink(0).Padding(4, pageGutter, 12).Children(func() {
		for _, filter := range a.search.filters {
			key := "filter-" + filter.Label + filter.Params
			if filter.Params == "" {
				// The chip that clears the filters, which YouTube draws only
				// while one of them is on.
				if a.search.params == "" {
					continue
				}
				if m3.IconChip(c, m3.IconClose, "Clear filters", key).Clicked() {
					a.runSearch(a.search.submitted, "")
				}
				continue
			}
			selected := filter.Params == a.search.params
			if m3.Chip(c, filter.Label, selected, key).Clicked() && !selected {
				a.runSearch(a.search.submitted, filter.Params)
			}
		}
	})
}

// searchLanding is the page before the first search: the searches the user
// made before, or an invitation to make one.
func (a *app) searchLanding(c *ui.Context) {
	sc := m3.Of(c).Scheme
	if len(a.settings.Recent) == 0 {
		ui.Column(c).Grow(1).Center().Children(func() {
			a.message(c, m3.IconSearch, "Find your next favourite",
				"Search for songs, albums, artists and playlists, then press Enter.", "", nil)
		})
		return
	}
	ui.Scroll(c).Grow(1).Padding(8, 0, a.clearance()).Children(func() {
		m3.EmphasizedText(c, m3.TitleMedium, "Recent searches").Padding(8, pageGutter, 8).TextColor(sc.OnSurfaceVariant)
		for _, query := range a.settings.Recent {
			row := ui.ButtonBase(c.Key("recent-" + query))
			row.Height(56).Margin(0, pageGutter-8).Padding(0, 4, 0, 16).Gap(16).AlignItems(ui.Center).Radius(m3.Large).
				Cursor(ui.CursorPointer).Label(query).
				Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), row.Pressed(), row.FocusVisible()))
			removed := false
			row.Children(func() {
				ui.Icon(c, m3.IconHistory).FontSize(24).TextColor(sc.OnSurfaceVariant)
				m3.Text(c, m3.BodyLarge, query).Grow(1).SingleLine().Tooltip(query).TextColor(sc.OnSurface)
				if m3.IconButton(c, m3.IconButtonSpec{Icon: m3.IconClose, Label: "Remove " + query, Size: m3.ExtraSmall32, Key: "forget-" + query}).Clicked() {
					removed = true
				}
			})
			switch {
			case removed:
				a.settings.Forget(query)
				a.saveSettings()
			case row.Clicked():
				a.runSearch(query, "")
			}
		}
	})
}

// topResult is the card a search's results lead with: the artwork of what the
// search found, its name, and what can be done with it. Selecting it opens the
// page it names, or plays it; the play and shuffle buttons do that without
// opening the page first.
func (a *app) topResult(c *ui.Context, item youtube.MusicItem) {
	sc := m3.Of(c).Scheme
	target := item.Target()
	key := "top-result"
	card := ui.ButtonBase(c.Key(key))
	card.Padding(8).Margin(0, pageGutter-8).Gap(20).Radius(m3.ExtraLarge).AlignItems(ui.Center).Cursor(ui.CursorPointer).Label(item.Title).
		Background(m3.StateFill(ui.Transparent, sc.OnSurface, card.Hovered(), card.Pressed(), card.FocusVisible()))
	played, shuffled, menuClicked := false, false, false
	hovered := card.Hovered()
	var menuButton, menuAnchor ui.Element
	hasMenu, hasAnchor := false, false
	card.Children(func() {
		a.art(c, item.Thumbnail, 256, 132, artRadius(item, m3.LargeIncreased), func() {
			if a.isOpening(key) {
				// The scrim covers the artwork: attached, it leaves the flow
				// the picture is laid out in.
				ui.Box(c).Size(132, 132).Radius(artRadius(item, m3.LargeIncreased)).Attach(ui.AnchorTopLeft, ui.AnchorTopLeft).
					Center().Background(sc.Scrim.Alpha(0.45)).Children(func() {
					m3.LoadingIndicatorIn(c, 28, ui.RGB(255, 255, 255))
				})
				return
			}
			// The menu sits on the artwork, as a card's does.
			if target.Kind != youtube.TargetNone {
				menuButton = m3.IconButton(c, m3.IconButtonSpec{
					Icon: m3.IconMore, Label: "More options for " + item.Title, Key: key + "-menu",
					// On a picture the dots need a ground of their own.
					Container: sc.SurfaceContainerHighest.Alpha(0.85),
				}).Attach(ui.AnchorTopRight, ui.AnchorTopRight).Top(0).Right(0)
				hasMenu = true
				if !hovered && !card.FocusWithin() && !a.menu.opened(key+"-menu") {
					menuButton.Opacity(0).PassThrough()
				}
			}
		})
		ui.Column(c).Grow(1).MinWidth(0).Gap(6).Children(func() {
			m3.EmphasizedText(c, m3.HeadlineSmall, item.Title).MaxLines(2).TextColor(sc.OnSurface)
			if item.Subtitle != "" {
				m3.Text(c, m3.BodyMedium, item.Subtitle).SingleLine().Tooltip(item.Subtitle).TextColor(sc.OnSurfaceVariant)
			}
			switch target.Kind {
			case youtube.TargetTrack:
				ui.Row(c).Padding(4, 0, 0).Children(func() {
					if m3.Button(c, m3.ButtonSpec{Label: "Play", Icon: m3.IconPlay, Size: m3.Small40, Key: key + "-play"}).Clicked() {
						played = true
					}
				})
			case youtube.TargetAlbum, youtube.TargetPlaylist, youtube.TargetArtist:
				ui.Row(c).Gap(8).Padding(4, 0, 0).Children(func() {
					if m3.Button(c, m3.ButtonSpec{Label: "Play", Icon: m3.IconPlay, Size: m3.Small40, Key: key + "-play"}).Clicked() {
						played = true
					}
					if m3.Button(c, m3.ButtonSpec{Label: "Shuffle", Icon: m3.IconShuffle, Kind: m3.Tonal, Size: m3.Small40, Key: key + "-shuffle"}).Clicked() {
						shuffled = true
					}
				})
			case youtube.TargetPodcast, youtube.TargetNone:
				// A show is followed from its page, episode by episode, not
				// played whole from its card.
			}
		})
		// A menu the pointer opened points at the pointer; one the menu
		// button opened points at the button.
		menuAnchor, hasAnchor = a.menu.pointerAnchor(c, key+"-menu")
	})
	if hasMenu {
		pointer := card.RightClicked() || menuButton.RightClicked()
		x, y, _ := card.PointerPosition()
		if menuButton.Clicked() || pointer || card.Shortcut(ui.Shift, ui.KeyF10) {
			a.menu.show(key+"-menu", pointer, x, y)
			menuClicked = true
		}
	}
	if hasMenu && a.menu.owns(key+"-menu") {
		a.cardMenu(c, a.menu.anchor(menuButton, menuAnchor, hasAnchor), key+"-menu", item, a.page.playable)
	}
	switch {
	case menuClicked:
		// The buttons inside the card receive their presses too: the switch
		// is what keeps a press on the menu from opening the page under it.
	case played:
		a.playCollection(item, key, false)
	case shuffled:
		a.playCollection(item, key, true)
	case card.Clicked():
		a.activate(item, a.page.playable)
	}
}
