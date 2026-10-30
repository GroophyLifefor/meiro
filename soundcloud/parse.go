package soundcloud

import (
	"encoding/json"
	"fmt"
	"strconv"

	"github.com/elianiva/meiro/media"
)

// playlist is the flat JSON yt-dlp prints for a page or a search: its heading
// and its entries.
type playlist struct {
	Title   string  `json:"title"`
	Entries []entry `json:"entries"`
}

// entry is one track as a flat listing gives it. A search fills every field; a
// set, a user page, a recommendation or a station gives only the id and the
// page URL.
type entry struct {
	ID         string  `json:"id"`
	Title      string  `json:"title"`
	Uploader   string  `json:"uploader"`
	URL        string  `json:"url"`
	WebpageURL string  `json:"webpage_url"`
	Duration   float64 `json:"duration"`
}

// decodePlaylist reads yt-dlp's flat JSON.
func decodePlaylist(output []byte) (playlist, error) {
	var page playlist
	if err := json.Unmarshal(output, &page); err != nil {
		return playlist{}, fmt.Errorf("soundcloud: yt-dlp returned no page: %w", err)
	}
	return page, nil
}

// items maps the page's entries to the neutral items, skipping the ones with
// nothing to identify them by.
func (p playlist) items() []media.Item {
	items := make([]media.Item, 0, len(p.Entries))
	for _, e := range p.Entries {
		if e.ID == "" && e.pageURL() == "" {
			continue
		}
		items = append(items, e.item())
	}
	return items
}

// item maps one track to the neutral item. The page URL is what plays it and
// what GetRelated and GetStation read it by; a search gives it as webpage_url,
// every other listing as url.
func (e entry) item() media.Item {
	return media.Item{
		Source:   media.SourceSoundCloud,
		ID:       e.ID,
		URL:      e.pageURL(),
		Title:    e.Title,
		Subtitle: e.Uploader,
		Artist:   e.Uploader,
		Artists:  e.Uploader,
		Kind:     media.KindTrack,
		Duration: durationText(e.Duration),
	}
}

// pageURL is the canonical SoundCloud page of the track.
func (e entry) pageURL() string {
	if e.WebpageURL != "" {
		return e.WebpageURL
	}
	return e.URL
}

// durationText formats a length in seconds as "3:42" or "1:02:03", and an
// unknown one as "". A 30 second length is SoundCloud's preview of a track
// that is not fully available; it is shown as any other length is.
func durationText(seconds float64) string {
	if seconds <= 0 {
		return ""
	}
	total := int(seconds)
	hours, minutes, secs := total/3600, (total%3600)/60, total%60
	if hours > 0 {
		return strconv.Itoa(hours) + ":" + two(minutes) + ":" + two(secs)
	}
	return strconv.Itoa(minutes) + ":" + two(secs)
}

// two pads a minutes or seconds part below ten.
func two(value int) string {
	if value < 10 {
		return "0" + strconv.Itoa(value)
	}
	return strconv.Itoa(value)
}
