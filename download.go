package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/internal/proc"
	"github.com/elianiva/meiro/internal/ytdlp"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// downloadExtension is the container a download is written in. Matroska
// holds any codec the source serves, so the audio is copied and never
// re-encoded, and it reads back through the same player as anything else.
const downloadExtension = ".mkv"

// downloadTimeout bounds one download, from resolving the stream to the last
// byte. A long track on a slow link can take many minutes.
const downloadTimeout = 30 * time.Minute

// downloadStatus is where a download is.
type downloadStatus uint8

const (
	// downloadIdle is a track with no download asked for.
	downloadIdle downloadStatus = iota
	// downloadRunning is a download in flight.
	downloadRunning
	// downloadDone is a track that finished downloading.
	downloadDone
	// downloadFailed is a download that did not finish.
	downloadFailed
)

// downloadState is the state of one track's download, for the interface.
type downloadState struct {
	status downloadStatus
	title  string
	err    string
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

// startDownload downloads a track and keeps it, so it plays without the
// network and without the playback cache ever evicting it.
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
	if st, ok := a.downloadStates[akey]; ok && st.status == downloadRunning {
		return
	}
	if a.downloadStates == nil {
		a.downloadStates = make(map[string]downloadState)
	}
	a.downloadStates[akey] = downloadState{status: downloadRunning, title: item.Title}
	a.page.rowsDirty = true
	a.runDownload(item, source, key, akey)
}

// runDownload resolves a track's audio URL and copies it into the download
// store, off the main thread.
func (a *app) runDownload(item youtube.MusicItem, source media.Source, key, akey string) {
	streams, store := a.streamsFor(source), a.downloads
	a.run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), downloadTimeout)
		defer cancel()
		url, _, err := streams.Get(ctx, key)
		if err != nil {
			a.finishDownload(akey, item.Title, err)
			return
		}
		dest, name := store.Path(source, key, downloadExtension)
		size, err := fetchDownload(ctx, url, dest)
		if err != nil {
			a.finishDownload(akey, item.Title, err)
			return
		}
		a.update(func() {
			if err := store.Add(downloadItem(item, name, size)); err != nil {
				a.finishDownload(akey, item.Title, err)
				return
			}
			a.downloadStates[akey] = downloadState{status: downloadDone, title: item.Title}
			a.notice = "Downloaded " + item.Title
			a.page.rowsDirty = true
		})
	})
}

// finishDownload records that a download failed, on the main thread.
func (a *app) finishDownload(akey, title string, err error) {
	a.update(func() {
		a.downloadStates[akey] = downloadState{status: downloadFailed, title: title, err: err.Error()}
		log.Printf("downloading %s: %v", title, err)
		a.notice = "Could not download " + title + ": " + strings.TrimSuffix(playErrorMessage(err.Error()), ".")
		a.page.rowsDirty = true
	})
}

// fetchDownload writes the audio at url into dest with ffmpeg, copying the
// stream as it is so nothing is re-encoded. An HLS manifest and an ordinary
// media file both open through it. It returns the file's size in bytes.
func fetchDownload(ctx context.Context, url, dest string) (int64, error) {
	if strings.TrimSpace(url) == "" {
		return 0, errors.New("no resolved audio URL to download")
	}
	if strings.TrimSpace(dest) == "" {
		return 0, errors.New("no place to keep the download")
	}
	ffmpeg, err := proc.Tool("ffmpeg")
	if err != nil {
		return 0, errors.New("downloading needs ffmpeg")
	}
	dir := filepath.Dir(dest)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return 0, err
	}
	tempDir, err := os.MkdirTemp(dir, ".download-*")
	if err != nil {
		return 0, err
	}
	defer func() { _ = os.RemoveAll(tempDir) }()
	temp := filepath.Join(tempDir, "audio"+downloadExtension)
	command := proc.Command(ctx, ffmpeg,
		"-y", "-nostdin", "-loglevel", "error",
		"-i", url, "-vn", "-c", "copy", "-f", "matroska", temp)
	var stderr bytes.Buffer
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if reason := ytdlp.LastError(stderr.String()); reason != "" {
			return 0, errors.New(reason)
		}
		if message := strings.TrimSpace(stderr.String()); message != "" {
			return 0, errors.New(firstLine(message))
		}
		return 0, fmt.Errorf("ffmpeg could not download the audio: %w", err)
	}
	if err := os.Rename(temp, dest); err != nil {
		return 0, err
	}
	info, err := os.Stat(dest)
	if err != nil {
		return 0, err
	}
	return info.Size(), nil
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

// downloadsPending shows the downloads in flight and the ones that failed,
// above the tracks already kept.
func (a *app) downloadsPending(c *ui.Context) {
	if !a.hasPendingDownloads() {
		return
	}
	sc := m3.Of(c).Scheme
	keys := make([]string, 0, len(a.downloadStates))
	for key, st := range a.downloadStates {
		if st.status == downloadRunning || st.status == downloadFailed {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	for _, key := range keys {
		st := a.downloadStates[key]
		ui.Row(c.Key("download-pending-"+key)).FillWidth().Margin(0, pageGutter-8).Padding(8, 12).Gap(12).AlignItems(ui.Center).Radius(m3.Large).
			Background(sc.SurfaceContainerHigh).Children(func() {
			ui.Column(c).Grow(1).MinWidth(0).Children(func() {
				m3.Text(c, m3.BodyLarge, st.title).SingleLine().Tooltip(st.title).TextColor(sc.OnSurface)
				line, colour := "Downloading", sc.OnSurfaceVariant
				if st.status == downloadFailed {
					line, colour = firstLine(playErrorMessage(st.err)), sc.Error
				}
				m3.Text(c, m3.BodySmall, line).SingleLine().Tooltip(line).TextColor(colour)
			})
		})
	}
}

// hasPendingDownloads reports whether any download is in flight or failed.
func (a *app) hasPendingDownloads() bool {
	for _, st := range a.downloadStates {
		if st.status == downloadRunning || st.status == downloadFailed {
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
