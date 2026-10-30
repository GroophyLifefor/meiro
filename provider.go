package main

import (
	"log"
	"slices"
	"strconv"
	"strings"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
)

// provider is one music source the app can read, and the name badges show for
// it.
type provider struct {
	source media.Source
	label  string
}

// providers are the sources the app reads and searches, in the order a first
// run prefers them. YouTube Music and SoundCloud each have a client behind
// media.Client.
var providers = []provider{
	{media.SourceYTM, "YouTube Music"},
	{media.SourceSoundCloud, "SoundCloud"},
}

// providerOrder is the preference order of the sources the app reads and
// searches, from the settings. Names the app does not know are dropped, and a
// source the file omitted is appended, so the order always names every
// provider exactly once.
func (a *app) providerOrder() []media.Source {
	known := make(map[media.Source]bool, len(providers))
	order := make([]media.Source, 0, len(providers))
	for _, name := range a.settings.ProviderOrder {
		source := media.Source(name)
		if known[source] || !knownSource(source) {
			continue
		}
		known[source] = true
		order = append(order, source)
	}
	for _, p := range providers {
		if !known[p.source] {
			known[p.source] = true
			order = append(order, p.source)
		}
	}
	return order
}

// moveProvider moves the source at i one place towards the top (dir -1) or the
// bottom (dir 1) of the preference order and keeps the new order. It does
// nothing at the ends. The settings page drives it.
func (a *app) moveProvider(i, dir int) {
	order := a.providerOrder()
	j := i + dir
	if i < 0 || i >= len(order) || j < 0 || j >= len(order) {
		return
	}
	order[i], order[j] = order[j], order[i]
	names := make([]string, len(order))
	for k, source := range order {
		names[k] = string(source)
	}
	a.settings.ProviderOrder = names
	a.saveSettings()
}

// knownSource reports whether the app can read a source.
func knownSource(source media.Source) bool {
	for _, p := range providers {
		if p.source == source {
			return true
		}
	}
	return false
}

// sourceLabel is the name badges show for a provider's short name, or the
// first provider's when the name is one the app does not know.
func sourceLabel(id string) string {
	for _, p := range providers {
		if string(p.source) == id {
			return p.label
		}
	}
	return providers[0].label
}

// sourceIcon is the simplified mark a provider badge draws: YouTube Music's
// circle and play, and SoundCloud's cloud.
func sourceIcon(source media.Source) *ui.SVG {
	if source == media.SourceSoundCloud {
		return m3.IconSoundCloud
	}
	return m3.IconYouTubeMusic
}

// sourceColour is the colour a provider badge draws its mark in: YouTube
// Music's red and SoundCloud's orange.
func sourceColour(source media.Source) ui.Color {
	if source == media.SourceSoundCloud {
		return ui.RGB(0xff, 0x55, 0x00)
	}
	return ui.RGB(0xff, 0x00, 0x00)
}

// sourceBadges draws the provider marks a row shows for a track the app knows
// on more than one source. Each mark is a button: pressing it makes that
// source the one the track plays from. The mark of the source it plays from
// is filled and ringed and the others are faded, which is all the row says of
// where it plays from. It reports whether a mark was pressed.
func (a *app) sourceBadges(c *ui.Context, item media.Item) bool {
	if len(item.Sources) == 0 {
		// A song only one provider is known to hold still says which: its
		// mark, not pressed for, with no other to switch to.
		if item.IsTrack() {
			ui.Box(c).Size(badgeSize, badgeSize).Shrink(0).Center().Children(func() {
				sourceMark(c, resolveSource(item))
			})
		}
		return false
	}
	sc := m3.Of(c).Scheme
	active, hasActive := a.sourceFor(item)
	switched := false
	ui.Row(c).Shrink(0).Gap(2).Padding(0, 4).AlignItems(ui.Center).Children(func() {
		for _, ref := range item.Sources {
			on := hasActive && ref.Source == active
			if a.sourceBadge(c, item, ref.Source, on, sc) {
				switched = true
			}
		}
	})
	return switched
}

// sourceMark is a provider's mark drawn small and still, to say where a card,
// a list or a page comes from. It is not a button: the marks that switch a
// track's source are sourceBadges.
func sourceMark(c *ui.Context, source media.Source) {
	ui.Icon(c, sourceIcon(source)).FontSize(badgeIcon).Shrink(0).TextColor(sourceColour(source)).Label(sourceLabel(string(source))).Tooltip(sourceLabel(string(source)))
}

// previewLength is the length SoundCloud gives a track it only lets be heard
// in part: the first thirty seconds.
const previewLength = "0:30"

// isPreview reports whether item, played from source, is SoundCloud's thirty
// second preview of a track rather than the track.
func isPreview(item media.Item, source media.Source) bool {
	return source == media.SourceSoundCloud && item.Duration == previewLength
}

// previewChip is the small tag a row carries when what it plays is only a
// preview, so its length does not leave the listener wondering.
func previewChip(c *ui.Context) {
	sc := m3.Of(c).Scheme
	ui.Row(c).Shrink(0).Padding(2, 8).Radius(m3.Full).Background(sc.TertiaryContainer).Children(func() {
		m3.Text(c, m3.LabelSmall, "preview").SingleLine().TextColor(sc.OnTertiaryContainer).Tooltip("SoundCloud only lets the first 30 seconds of this track be heard")
	})
}

// providerCounts counts how many of a list's tracks play from each provider,
// in the order of providers, leaving out the ones no track plays from.
func providerCounts(items []library.Item) []providerCount {
	var counts []providerCount
	for _, p := range providers {
		n := 0
		for _, item := range items {
			source := item.Source
			if source == "" {
				source = media.SourceYTM
			}
			if source == p.source {
				n++
			}
		}
		if n > 0 {
			counts = append(counts, providerCount{p, n})
		}
	}
	return counts
}

// providerCount is how many tracks of a list play from one provider.
type providerCount struct {
	provider provider
	n        int
}

// providerSummary says how a list's tracks divide between providers, as
// "YouTube Music 18, SoundCloud 10", or "" for an empty list.
func providerSummary(items []library.Item) string {
	counts := providerCounts(items)
	parts := make([]string, len(counts))
	for i, count := range counts {
		parts[i] = count.provider.label + " " + strconv.Itoa(count.n)
	}
	return strings.Join(parts, ", ")
}

// providerMarks draws the mark of each provider a list's tracks play from.
func providerMarks(c *ui.Context, items []library.Item) {
	for _, count := range providerCounts(items) {
		sourceMark(c, count.provider.source)
	}
}

// badgeSize is the side of a button that carries a provider's mark, and
// badgeIcon the mark inside it. Every icon button of a provider has these
// sizes.
const (
	badgeSize = 24
	badgeIcon = 17
)

// sourceBadge is one provider mark: a small button that makes the track play
// from that provider. The mark of the source playing now is on.
func (a *app) sourceBadge(c *ui.Context, item media.Item, source media.Source, on bool, sc m3.Scheme) bool {
	label := "Play from " + sourceLabel(string(source))
	button := ui.ButtonBase(c.Key("source-" + string(source)))
	button.Size(badgeSize, badgeSize).Radius(m3.Full).Center().Cursor(ui.CursorPointer).Label(label).Tooltip(label)
	opacity := float32(0.45)
	container := ui.Transparent
	if on {
		opacity, container = 1, sc.SurfaceContainerHigh
	}
	button.Opacity(opacity)
	button.Background(m3.StateFill(container, sc.OnSurface, button.Hovered(), button.Pressed(), button.FocusVisible()))
	if on {
		button.Border(1, sc.Outline)
	}
	button.Children(func() {
		ui.Icon(c, sourceIcon(source)).FontSize(badgeIcon).TextColor(sourceColour(source))
	})
	if !on && button.Clicked() {
		return a.chooseSource(item, source)
	}
	return false
}

// chooseSource makes source the one item plays from, wherever the app holds
// the track: the queue record's Source field, the page or the search results
// it was drawn from, and the app's own lists and downloads. The track playing
// is started again from the new source when it is the one changed, holding
// its place. It reports whether the choice changed anything.
func (a *app) chooseSource(item media.Item, source media.Source) bool {
	if _, ok := item.RefFor(source); !ok {
		return false
	}
	if active, ok := a.sourceFor(item); ok && active == source {
		return false
	}
	updated := item.WithSource(source)
	key := item.Key()

	a.queue.setSource(key, source)
	a.replaceSourceEverywhere(key, updated, source)

	if a.current.Key() != "" && a.current.Key() == key {
		a.resumeID = key
		a.startTrack(updated, false)
		a.notice, a.noticeTimeout = "Continuing on "+sourceLabel(string(source)), fallbackNotice
	}
	a.syncSystemMedia()
	return true
}

// replaceSourceEverywhere rewrites the copies of a track the app holds: the
// page and the search it was drawn from, and the app's own lists and
// downloads. key is what the rows were drawn with, and source the provider the
// track now plays from.
func (a *app) replaceSourceEverywhere(key string, updated media.Item, source media.Source) {
	a.replaceSourceIn(a.search.items, key, updated)
	for i := range a.search.sections {
		a.replaceSourceIn(a.search.sections[i].Items, key, updated)
	}
	a.replaceSourceIn(a.page.feed.items, key, updated)
	for i := range a.page.feed.sections {
		a.replaceSourceIn(a.page.feed.sections[i].Items, key, updated)
	}
	a.replaceSourceIn(a.page.feed.similar, key, updated)
	a.replaceSourceIn(a.page.playable, key, updated)
	a.setLibrarySource(updated, source)
	a.setDownloadSource(updated, source)
	a.page.rowsDirty = true
}

// replaceSourceIn rewrites the copy of a track a page or a search holds, by
// the key the row was drawn with.
func (a *app) replaceSourceIn(items []media.Item, key string, updated media.Item) {
	if key == "" {
		return
	}
	for i := range items {
		if items[i].Key() == key {
			items[i] = updated
		}
	}
}

// setLibrarySource makes source the one a track plays from in the app's own
// lists that hold it, matched by the recording. A list entry that knows only
// one source cannot be switched and is left as it is.
func (a *app) setLibrarySource(item media.Item, source media.Source) {
	if a.libraries == nil {
		return
	}
	for _, lib := range a.libraries.List() {
		for _, stored := range lib.Items {
			if !holdsSource(stored.Sources, source) || !sameRecording(item, musicItem(stored)) {
				continue
			}
			if _, err := a.libraries.SetSource(lib.ID, stored.Key, source); err != nil {
				log.Printf("switching a list track's source: %v", err)
			}
		}
	}
}

// setDownloadSource makes source the one a kept track plays from, matched by
// the recording. A download that knows only one source is left as it is.
func (a *app) setDownloadSource(item media.Item, source media.Source) {
	if a.downloads == nil {
		return
	}
	for _, rec := range a.downloads.List() {
		if !holdsSource(rec.Sources, source) || !sameRecording(item, downloadMusicItem(rec)) {
			continue
		}
		if _, err := a.downloads.SetActive(rec.Source, rec.Key, source); err != nil {
			log.Printf("switching a download's source: %v", err)
		}
	}
}

// holdsSource reports whether refs name source.
func holdsSource(refs []media.SourceRef, source media.Source) bool {
	for _, ref := range refs {
		if ref.Source == source {
			return true
		}
	}
	return false
}

// sameRecording reports whether two items name the same song: the same
// provider key, or the same title and artist once their noise is folded away.
func sameRecording(a, b media.Item) bool {
	if a.Key() != "" && a.Key() == b.Key() {
		return true
	}
	first, second := songIDOf(a), songIDOf(b)
	return first.title != "" && first == second
}

// sourceFor is the source a track plays from: the one its Source field names
// when that provider holds it, and otherwise the default: the provider it is
// downloaded from, and failing that the first of the preference order that
// holds it. It reports false for an item no provider holds.
func (a *app) sourceFor(item media.Item) (media.Source, bool) {
	if item.Source != "" {
		if _, ok := item.RefFor(item.Source); ok {
			return item.Source, true
		}
	}
	ref, ok := item.FirstRef()
	if !ok {
		return "", false
	}
	if len(item.Sources) < 2 {
		return ref.Source, true
	}
	for _, source := range a.providerOrder() {
		if a.isDownloadedFrom(item, source) {
			return source, true
		}
	}
	for _, source := range a.providerOrder() {
		if _, ok := item.RefFor(source); ok {
			return source, true
		}
	}
	return ref.Source, true
}

// withDefaultSource sets the source a track plays from when it does not name
// one already: the one it is downloaded from, and otherwise the first of the
// preference order that holds it. An item that already names a source it is
// held by is returned as it is.
func (a *app) withDefaultSource(item media.Item) media.Item {
	source, ok := a.sourceFor(item)
	if !ok || item.Source == source {
		return item
	}
	return item.WithSource(source)
}

// withDefaultSources is withDefaultSource over a queue. The slice is copied
// only when a track changes.
func (a *app) withDefaultSources(tracks []media.Item) []media.Item {
	var out []media.Item
	for i, item := range tracks {
		set := a.withDefaultSource(item)
		if set.Source == item.Source {
			if out != nil {
				out[i] = set
			}
			continue
		}
		if out == nil {
			out = slices.Clone(tracks)
		}
		out[i] = set
	}
	if out == nil {
		return tracks
	}
	return out
}

// isDownloadedFrom reports whether item is kept as a download from source.
func (a *app) isDownloadedFrom(item media.Item, source media.Source) bool {
	if a.downloads == nil {
		return false
	}
	ref, ok := item.RefFor(source)
	if !ok {
		return false
	}
	key := ref.ID
	if source == media.SourceSoundCloud {
		key = ref.URL
	}
	if key == "" {
		return false
	}
	_, ok = a.downloads.Get(source, key)
	return ok
}
