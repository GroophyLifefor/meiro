package main

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/musicbrainz"
)

// openSettings loads what Settings shows that costs more than a frame: the
// versions of the playback tools and what the MusicBrainz cache holds.
func (a *app) openSettings() {
	a.loadToolVersions()
	a.loadMetadataCache()
}

// metadataEntriesShown is how many of the newest cached answers the card lists.
const metadataEntriesShown = 8

// loadMetadataCache reads what the MusicBrainz cache holds off the main thread,
// since it reads a folder of files.
func (a *app) loadMetadataCache() {
	if a.mb == nil || a.mbEntriesLoad.busy() {
		return
	}
	client := a.mb
	startTask(a, &a.mbEntriesLoad, noTimeout, func(context.Context) ([]musicbrainz.CacheEntry, error) {
		return client.CacheEntries(), nil
	}, func(entries []musicbrainz.CacheEntry, _ error) {
		a.mbEntries, a.mbEntriesLoaded = entries, true
	})
}

// forgetMetadata forgets one cached answer, or every one when id is empty, off
// the main thread, and reads the cache again.
func (a *app) forgetMetadata(id string) {
	if a.mb == nil {
		return
	}
	client := a.mb
	spawn(a, func() error {
		if id == "" {
			return client.ClearCache()
		}
		return client.RemoveCacheEntry(id)
	}, func(err error) {
		if err != nil {
			log.Printf("musicbrainz cache: %v", err)
			a.notice = "Could not forget the answer: " + firstLine(err.Error())
		} else if id == "" {
			a.notice = "Cleared the MusicBrainz cache"
		}
		a.loadMetadataCache()
	})
}

// metadataCacheCard shows what the MusicBrainz cache holds and lets the
// listener forget it: how many answers and how much room, the newest answers
// with what was asked and when it is asked again, and a button to forget one
// or all. The links already made are not in it, so forgetting it unlinks
// nothing.
func (a *app) metadataCacheCard(c *ui.Context) {
	sc := m3.Of(c).Scheme
	a.settingsCard(c, "MusicBrainz cache", m3.IconFolder, func() {
		m3.Text(c, m3.BodyMedium, "Keeps the answers of MusicBrainz and ListenBrainz on disk so a search made again costs no request: artist searches for 30 days, song searches for a day, artist listeners for a week. Links you made are kept apart and are not forgotten here.").TextColor(sc.OnSurfaceVariant)
		entries := a.mbEntries
		var bytes int64
		for _, entry := range entries {
			bytes += entry.Bytes
		}
		summary := "Reading the cache"
		if a.mbEntriesLoaded {
			summary = fmt.Sprintf("%s · %s", answerCount(len(entries)), formatMegabytes(bytes))
		}
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Kept now", summary).Grow(1)
			if m3.Button(c, m3.ButtonSpec{Label: "Clear MusicBrainz cache", Kind: m3.Outlined, Key: "mb-cache-clear", Disabled: len(entries) == 0}).Clicked() {
				a.forgetMetadata("")
			}
		})
		shown := entries
		if len(shown) > metadataEntriesShown {
			shown = shown[:metadataEntriesShown]
		}
		if len(shown) > 0 {
			settingLabel(c, "Newest answers", "")
		}
		now := time.Now()
		for _, entry := range shown {
			a.metadataEntryRow(c, entry, now)
		}
		if len(entries) > len(shown) {
			m3.Text(c, m3.BodySmall, fmt.Sprintf("and %d older", len(entries)-len(shown))).TextColor(sc.OnSurfaceVariant)
		}
	})
}

// metadataEntryRow is one cached answer: what was asked, how many things it
// lists, how long ago it was kept and when it is asked again, and a button that
// forgets it.
func (a *app) metadataEntryRow(c *ui.Context, entry musicbrainz.CacheEntry, now time.Time) {
	sc := m3.Of(c).Scheme
	title := entry.Kind
	if entry.Query != "" {
		title += ": " + entry.Query
	}
	detail := fmt.Sprintf("%s · kept %s · %s", resultCount(entry.Results), agoText(now.Sub(entry.StoredAt)), againText(entry.Expires.Sub(now)))
	ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
		ui.Column(c).Grow(1).MinWidth(0).Children(func() {
			m3.Text(c, m3.BodyLarge, title).SingleLine().Tooltip(title).TextColor(sc.OnSurface)
			m3.Text(c, m3.BodySmall, detail).SingleLine().Tooltip(detail).TextColor(sc.OnSurfaceVariant)
		})
		if m3.IconButton(c, m3.IconButtonSpec{Icon: m3.IconClose, Label: "Forget " + title, Size: m3.ExtraSmall32, Key: "mb-forget-" + entry.ID}).Clicked() {
			a.forgetMetadata(entry.ID)
		}
	})
}

// answerCount names how many answers the cache holds.
func answerCount(n int) string {
	if n == 1 {
		return "1 answer"
	}
	return fmt.Sprintf("%d answers", n)
}

// resultCount names how many things an answer lists.
func resultCount(n int) string {
	if n == 1 {
		return "1 result"
	}
	return fmt.Sprintf("%d results", n)
}

// agoText says how long ago something was, roughly: "just now", "5 min ago",
// "3 h ago", "2 days ago".
func agoText(d time.Duration) string {
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%d min ago", int(d/time.Minute))
	case d < 24*time.Hour:
		return fmt.Sprintf("%d h ago", int(d/time.Hour))
	default:
		return fmt.Sprintf("%d days ago", int(d/(24*time.Hour)))
	}
}

// againText says when an answer is asked for again: in so many days or hours,
// or at its next use when it has run out.
func againText(d time.Duration) string {
	switch {
	case d <= 0:
		return "asked again at its next use"
	case d < 48*time.Hour:
		return fmt.Sprintf("asked again in %d h", max(int(d/time.Hour), 1))
	default:
		return fmt.Sprintf("asked again in %d days", int(d/(24*time.Hour)))
	}
}
