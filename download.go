package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// downloadTimeout bounds one download, from resolving the stream to the last
// byte. A long track on a slow link can take many minutes.
const downloadTimeout = 30 * time.Minute

// downloadStatus is where a download is.
type downloadStatus uint8

const (
	// downloadIdle is a track with no download asked for.
	downloadIdle downloadStatus = iota
	// downloadQueued is a download asked for that waits for a place among
	// the ones running.
	downloadQueued
	// downloadRunning is a download in flight.
	downloadRunning
	// downloadDone is a track that finished downloading.
	downloadDone
	// downloadFailed is a download that did not finish.
	downloadFailed
)

// maxDownloads is how many downloads run at once. A whole playlist is asked
// for in one go, and a process for every song would starve the machine and the
// link, so the rest wait their turn.
const maxDownloads = 2

// queuedDownload is a download that waits for a place to run in.
type queuedDownload struct {
	item   youtube.MusicItem
	source media.Source
	key    string
	akey   string
}

// downloadState is the state of one track's download, for the interface.
type downloadState struct {
	status downloadStatus
	title  string
	err    string
	// item is the track the download was asked for, kept so a failed one can
	// be asked for again.
	item youtube.MusicItem
}

// networkOnline reports whether the network can be reached, with a short
// timeout, by opening a connection to the music service. It runs only after a
// resolve has already failed, so a reachable network costs nothing extra.
func networkOnline() bool {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var dialer net.Dialer
	conn, err := dialer.DialContext(ctx, "tcp", "music.youtube.com:443")
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

// downloadItem flattens a track and the file it was downloaded to for the
// download store.
func downloadItem(item youtube.MusicItem, file string, size int64) downloads.Item {
	return downloads.Item{
		Source:       resolveSource(item),
		Key:          streamKey(item),
		URL:          itemLink(item),
		Title:        item.Title,
		Artist:       item.Artist,
		Thumbnail:    item.Thumbnail,
		SizeBytes:    size,
		DownloadedAt: time.Now(),
		File:         file,
		Sources:      item.Sources,
		Active:       resolveSource(item),
	}
}

// downloadMusicItem builds the track a downloads row plays. A YouTube Music
// track is keyed by its video ID, a SoundCloud track by its page URL. A track
// the download holds on more than one provider plays from the one it is set
// to, its own when none was chosen.
func downloadMusicItem(rec downloads.Item) youtube.MusicItem {
	out := youtube.MusicItem{
		Source:    rec.Source,
		Title:     rec.Title,
		Subtitle:  rec.Artist,
		Artist:    rec.Artist,
		Thumbnail: rec.Thumbnail,
		Kind:      media.KindTrack,
		Sources:   rec.Sources,
	}
	active := rec.Active
	if active == "" {
		active = rec.Source
	}
	if len(rec.Sources) > 0 {
		if ref, ok := out.RefFor(active); ok {
			return out.WithSource(ref.Source)
		}
	}
	if rec.Source == media.SourceSoundCloud {
		out.URL = rec.URL
	} else {
		out.VideoID = rec.Key
	}
	return out
}

// isDownloaded reports whether a track is kept in the download store.
func (a *app) isDownloaded(item youtube.MusicItem) bool {
	if a.downloads == nil || item.Key() == "" {
		return false
	}
	_, ok := a.downloads.Get(resolveSource(item), streamKey(item))
	return ok
}

// downloadedMark is the green check a song row shows for a track kept as a
// download. Its slot is as wide as a provider badge whether or not it holds
// the mark, so rows do not move when a download finishes or is removed.
func (a *app) downloadedMark(c *ui.Context, item youtube.MusicItem) {
	ui.Box(c).Size(badgeSize, badgeSize).Shrink(0).Center().Children(func() {
		if !a.isDownloaded(item) {
			return
		}
		ui.Icon(c, m3.IconCheckCircle).FontSize(badgeIcon).TextColor(downloadedColour(m3.Of(c).Scheme)).Label("Downloaded").Tooltip("Downloaded")
	})
}

// downloadedColour is the green of the mark of a downloaded song: dark enough
// to read on a light surface and light enough to read on a dark one.
func downloadedColour(sc m3.Scheme) ui.Color {
	if sc.Dark {
		return ui.RGB(0x81, 0xc9, 0x95)
	}
	return ui.RGB(0x18, 0x80, 0x38)
}

// downloadStatusOf is where a track's download is, or the idle state.
func (a *app) downloadStatusOf(item youtube.MusicItem) downloadState {
	if a.downloadStates == nil {
		return downloadState{status: downloadIdle}
	}
	if st, ok := a.downloadStates[audioKey(item)]; ok {
		return st
	}
	return downloadState{status: downloadIdle}
}

// startDownload asks for a track to be downloaded and kept, so it plays
// without the network and without the playback cache ever evicting it. It
// runs at once when fewer than maxDownloads are running, and waits its turn
// otherwise.
func (a *app) startDownload(item youtube.MusicItem) {
	if a.downloads == nil || item.Key() == "" {
		return
	}
	source, key := resolveSource(item), streamKey(item)
	if item.Thumbnail == "" {
		// A song on an album page has no cover of its own: the album's is
		// what it is shown with, and what the download keeps.
		item.Thumbnail = a.pageArt()
	}
	if _, ok := a.downloads.Get(source, key); ok {
		a.notice = "Already downloaded: " + item.Title
		return
	}
	akey := audioKey(item)
	if st, ok := a.downloadStates[akey]; ok && (st.status == downloadRunning || st.status == downloadQueued) {
		return
	}
	if a.downloadStates == nil {
		a.downloadStates = make(map[string]downloadState)
	}
	a.downloadStates[akey] = downloadState{status: downloadQueued, title: item.Title, item: item}
	a.downloadQueue = append(a.downloadQueue, queuedDownload{item: item, source: source, key: key, akey: akey})
	a.page.rowsDirty = true
	a.runQueuedDownloads()
}

// runQueuedDownloads starts the downloads that wait, oldest first, while there
// is a place for them.
func (a *app) runQueuedDownloads() {
	for a.downloadsRunning < maxDownloads && len(a.downloadQueue) > 0 {
		next := a.downloadQueue[0]
		a.downloadQueue = a.downloadQueue[1:]
		a.downloadsRunning++
		a.downloadStates[next.akey] = downloadState{status: downloadRunning, title: next.item.Title, item: next.item}
		a.page.rowsDirty = true
		a.runDownload(next.item, next.source, next.key, next.akey)
	}
}

// downloadEnded frees the place of a download that finished or failed, on the
// main thread, and starts the next one that waits.
func (a *app) downloadEnded() {
	a.downloadsRunning--
	a.runQueuedDownloads()
}

// runDownload resolves a track's audio URL and copies it into the download
// store, off the main thread.
func (a *app) runDownload(item youtube.MusicItem, source media.Source, key, akey string) {
	streams, store, fetch := a.streamsFor(source), a.downloads, a.downloadAudio
	a.run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), downloadTimeout)
		defer cancel()
		base, _ := store.Path(source, key, "")
		ext, size, err := fetch(ctx, streams, key, base)
		if err != nil {
			a.finishDownload(akey, item.Title, err)
			return
		}
		_, name := store.Path(source, key, ext)
		a.update(func() {
			if err := store.Add(downloadItem(item, name, size)); err != nil {
				a.finishDownload(akey, item.Title, err)
				return
			}
			a.downloadStates[akey] = downloadState{status: downloadDone, title: item.Title, item: item}
			a.notice = "Downloaded " + item.Title
			a.page.rowsDirty = true
			a.downloadEnded()
		})
	})
}

// fetchTrackAudio resolves a track's audio URL and saves the audio to a file
// named base plus the extension of the container it arrives in. It returns
// that extension and the file's size. It is what a download does unless a test
// replaces it.
func fetchTrackAudio(ctx context.Context, streams *audio.URLs, key, base string) (string, int64, error) {
	url, _, err := streams.Get(ctx, key)
	if err != nil {
		return "", 0, err
	}
	return fetchDownload(ctx, http.DefaultClient, url, base)
}

// finishDownload records that a download failed, on the main thread.
func (a *app) finishDownload(akey, title string, err error) {
	a.update(func() {
		a.downloadStates[akey] = downloadState{status: downloadFailed, title: title, err: err.Error(), item: a.downloadStates[akey].item}
		log.Printf("downloading %s: %v", title, err)
		a.notice = "Could not download " + title + ": " + strings.TrimSuffix(playErrorMessage(err.Error()), ".")
		a.page.rowsDirty = true
		a.downloadEnded()
	})
}

// failedDownloads lists the source-qualified audio keys of the downloads that
// failed, in a stable order. The failures are kept in memory only: they are
// gone when the app closes.
func (a *app) failedDownloads() []string {
	var keys []string
	for key, st := range a.downloadStates {
		if st.status == downloadFailed {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	return keys
}

// retryDownload asks again for a download that failed, through the same queue
// as any other, so it waits its turn among the maxDownloads that run at once.
func (a *app) retryDownload(akey string) {
	st, ok := a.downloadStates[akey]
	if !ok || st.status != downloadFailed {
		return
	}
	a.startDownload(st.item)
}

// retryFailedDownloads asks again for every download that failed.
func (a *app) retryFailedDownloads() {
	keys := a.failedDownloads()
	for _, key := range keys {
		a.retryDownload(key)
	}
	if len(keys) > 0 {
		a.notice = "Retrying " + songCount(len(keys))
	}
}

// downloadCollection downloads the songs of an album or a playlist called
// name, except the ones already kept, already downloading or waiting, and the
// episodes of a show, which are not songs. It says how many it started.
func (a *app) downloadCollection(name string, songs []youtube.MusicItem) {
	seen := make(map[string]struct{}, len(songs))
	var todo []youtube.MusicItem
	kept, pending := 0, 0
	for _, song := range songs {
		if !song.IsTrack() || song.IsEpisode() || song.Key() == "" {
			continue
		}
		akey := audioKey(song)
		if _, dup := seen[akey]; dup {
			continue
		}
		seen[akey] = struct{}{}
		switch st := a.downloadStatusOf(song).status; {
		case a.isDownloaded(song):
			kept++
		case st == downloadRunning || st == downloadQueued:
			pending++
		default:
			todo = append(todo, song)
		}
	}
	switch {
	case len(todo) > 0:
		for _, song := range todo {
			a.startDownload(song)
		}
		a.notice = "Downloading " + songCount(len(todo)) + " from “" + name + "”"
	case kept > 0 && pending == 0:
		a.notice = "All songs of “" + name + "” are already downloaded"
	case pending > 0:
		a.notice = "The songs of “" + name + "” are already downloading"
	default:
		a.notice = "There are no songs in “" + name + "” to download"
	}
}

// fetchDownload saves the audio at url as it is served, in the container it
// arrives in, so nothing is re-encoded and no ffmpeg muxer is needed: the
// shipped ffmpeg only reads. The file is named base plus that extension, and
// the player decodes it by its contents. It returns the extension and the
// file's size in bytes.
func fetchDownload(ctx context.Context, client *http.Client, url, base string) (string, int64, error) {
	ext, err := audio.Save(ctx, client, url, base)
	if err != nil {
		return "", 0, err
	}
	info, err := os.Stat(base + ext)
	if err != nil {
		return "", 0, err
	}
	return ext, info.Size(), nil
}

// removeDownload takes a download out of the store and deletes its audio file.
func (a *app) removeDownload(rec downloads.Item) {
	if a.downloads == nil {
		return
	}
	removed, ok, err := a.downloads.Remove(rec.Source, rec.Key)
	if err != nil {
		a.notice = "Could not remove the download: " + firstLine(err.Error())
		return
	}
	if !ok {
		return
	}
	if removed.File != "" && a.downloads.Dir() != "" {
		path := filepath.Join(a.downloads.Dir(), removed.File)
		a.run(func() {
			if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
				log.Printf("removing downloaded audio %s: %v", path, err)
			}
		})
	}
	delete(a.downloadStates, string(rec.Source)+":"+rec.Key)
	a.notice = "Removed " + rec.Title + " from downloads"
	if r, _ := a.route(); r.id == routeDownloads {
		a.page.rowsDirty = true
	}
}

// removeDownloadItem removes the download of a track, found by the track
// itself, which a track menu uses.
func (a *app) removeDownloadItem(item youtube.MusicItem) {
	if a.downloads == nil || item.Key() == "" {
		return
	}
	rec, ok := a.downloads.Get(resolveSource(item), streamKey(item))
	if !ok {
		return
	}
	a.removeDownload(rec)
}

// touchDownload records that a downloaded track was played now, so the
// downloads page can show when each was last listened to.
func (a *app) touchDownload(item youtube.MusicItem) {
	if a.downloads == nil {
		return
	}
	a.downloads.Touch(resolveSource(item), streamKey(item), time.Now())
}

// downloadsPage shows the tracks the user chose to keep: what plays them, how
// large each is, and when it was last played, with a way to remove one.
func (a *app) downloadsPage(c *ui.Context) {
	ui.Scroll(c).Grow(1).Children(func() {
		ui.Column(c).Padding(8, 0, a.clearance()).Children(func() {
			a.downloadsHeader(c)
			a.downloadsPending(c)
			recs := a.downloads.List()
			if len(recs) == 0 && !a.hasPendingDownloads() {
				a.message(c, m3.IconFolder, "No downloads yet", "Download a track from its menu to keep it for offline listening.", "", nil)
				return
			}
			items := make([]youtube.MusicItem, 0, len(recs))
			for _, rec := range recs {
				items = append(items, downloadMusicItem(rec))
			}
			for i, rec := range recs {
				a.downloadRow(c, rec, items, i)
			}
		})
	})
}

// downloadsHeader says how many downloads failed and offers to try them all
// again. It shows nothing while none has failed.
func (a *app) downloadsHeader(c *ui.Context) {
	failed := a.failedDownloads()
	if len(failed) == 0 {
		return
	}
	sc := m3.Of(c).Scheme
	ui.Row(c.Key("downloads-header")).FillWidth().Padding(0, pageGutter, 8).Gap(12).AlignItems(ui.Center).Children(func() {
		m3.Text(c, m3.BodyMedium, strconv.Itoa(len(failed))+" failed").SingleLine().Grow(1).MinWidth(0).TextColor(sc.Error)
		if m3.Button(c, m3.ButtonSpec{Label: "Retry all", Icon: m3.IconRefresh, Kind: m3.Tonal, Key: "downloads-retry-all"}).Clicked() {
			a.retryFailedDownloads()
		}
	})
}

// downloadsPending shows the downloads in flight, the ones waiting and the
// ones that failed,
// above the tracks already kept.
func (a *app) downloadsPending(c *ui.Context) {
	if !a.hasPendingDownloads() {
		return
	}
	sc := m3.Of(c).Scheme
	keys := make([]string, 0, len(a.downloadStates))
	for key, st := range a.downloadStates {
		if st.status == downloadRunning || st.status == downloadQueued || st.status == downloadFailed {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	for _, key := range keys {
		st := a.downloadStates[key]
		ui.Row(c.Key("download-pending-"+key)).Margin(0, pageGutter-8).Padding(8, 12).Gap(12).AlignItems(ui.Center).Radius(m3.Large).
			Background(sc.SurfaceContainerHigh).Children(func() {
			ui.Column(c).Grow(1).MinWidth(0).Children(func() {
				m3.Text(c, m3.BodyLarge, st.title).SingleLine().Tooltip(st.title).TextColor(sc.OnSurface)
				line, colour := "Downloading", sc.OnSurfaceVariant
				if st.status == downloadQueued {
					line = "Waiting to download"
				}
				if st.status == downloadFailed {
					line, colour = firstLine(playErrorMessage(st.err)), sc.Error
				}
				m3.Text(c, m3.BodySmall, line).SingleLine().Tooltip(line).TextColor(colour)
			})
			if st.status == downloadFailed {
				retry := m3.Button(c, m3.ButtonSpec{Label: "Retry", Kind: m3.TextOnly, Size: m3.ExtraSmall32, Key: "download-retry-" + key})
				retry.Label("Retry download of " + st.title)
				if retry.Clicked() {
					a.retryDownload(key)
				}
			}
		})
	}
}

// hasPendingDownloads reports whether any download is in flight, waiting or
// failed.
func (a *app) hasPendingDownloads() bool {
	for _, st := range a.downloadStates {
		if st.status == downloadRunning || st.status == downloadQueued || st.status == downloadFailed {
			return true
		}
	}
	return false
}

// downloadRow is one kept track: what plays it, how large it is, and when it
// was last listened to, with the button that removes it.
func (a *app) downloadRow(c *ui.Context, rec downloads.Item, items []youtube.MusicItem, index int) {
	sc := m3.Of(c).Scheme
	item := downloadMusicItem(rec)
	key := "download-" + string(rec.Source) + ":" + rec.Key
	row := ui.Row(c.Key(key))
	row.Height(float32(rowHeight)).Shrink(0).Margin(0, pageGutter-8).Padding(8, 8).Gap(12).AlignItems(ui.Center).Radius(m3.Large)
	var main, remove ui.Element
	sourceSwitched := false
	row.Children(func() {
		main = ui.ButtonBase(c.Key(key + "-play"))
		main.Grow(1).Basis(0).MinWidth(0).Height(float32(rowHeight - 16)).Gap(12).AlignItems(ui.Center).
			Radius(m3.Large).Cursor(ui.CursorPointer).Label(rec.Title).
			Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), main.Pressed(), false))
		main.Children(func() {
			a.art(c, item.Thumbnail, 128, 48, m3.Medium)
			ui.Column(c).Grow(1).MinWidth(0).Children(func() {
				m3.Text(c, m3.BodyLarge, rec.Title).SingleLine().Tooltip(rec.Title).TextColor(sc.OnSurface)
				m3.Text(c, m3.BodySmall, downloadSubtitle(rec)).SingleLine().Tooltip(downloadSubtitle(rec)).TextColor(sc.OnSurfaceVariant)
			})
			if a.sourceBadges(c, item) {
				sourceSwitched = true
			}
		})
		remove = m3.IconButton(c, m3.IconButtonSpec{Icon: m3.IconDelete, Label: "Remove download of " + rec.Title, Key: key + "-remove"})
	})
	if main.Clicked() && !sourceSwitched {
		a.playFrom(item, items, max(index, 0), youtube.UpNextOptions{}, "Downloads")
	}
	if remove.Clicked() {
		a.removeDownload(rec)
	}
}

// downloadSubtitle names a kept track on one line: its artist, its provider,
// its size and when it was last played.
func downloadSubtitle(rec downloads.Item) string {
	parts := []string{}
	if rec.Artist != "" {
		parts = append(parts, rec.Artist)
	}
	parts = append(parts, sourceLabel(string(rec.Source)), humanBytes(rec.SizeBytes))
	if rec.LastListenedAt.IsZero() {
		parts = append(parts, "not played yet")
	} else {
		parts = append(parts, "last listened "+rec.LastListenedAt.Format("2006-01-02"))
	}
	return strings.Join(parts, " · ")
}

// humanBytes writes a size as a short string, as a file manager does.
func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return strconv.FormatInt(n, 10) + " B"
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTPE"[exp])
}
