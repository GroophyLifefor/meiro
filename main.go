// Meiro is a small desktop client for YouTube Music, drawn by MyGo itself in
// Material 3 Expressive.
//
// It browses the public catalogue with the youtube package, and the
// account's library once the user signs in with their YouTube Music session.
// Playback resolves a stream URL and decodes it in a child ffmpeg; see
// package player. The interface is built from the components of package m3,
// which take their colours, shapes and motion from one theme.
//
// The main package is the app itself: its state, the pages and the views
// that draw them. Everything that can stand on its own lives under internal.
// See AGENTS.md for the conventions the package keeps.
package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/plugins/updater"
	"github.com/egoist/mygo/plugins/updater/native"
	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/applog"
	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/cookies"
	"github.com/elianiva/meiro/internal/diskcache"
	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/internal/mblinks"
	"github.com/elianiva/meiro/internal/memprof"
	"github.com/elianiva/meiro/internal/proc"
	"github.com/elianiva/meiro/internal/report"
	"github.com/elianiva/meiro/internal/settings"
	"github.com/elianiva/meiro/musicbrainz"
	"github.com/elianiva/meiro/player"
	"github.com/elianiva/meiro/systemmedia"
)

func main() {
	// The heap is mostly artwork, which lives as long as the user browses and
	// then goes at once. The default lets the heap grow to twice what is live
	// before a collection; half as much costs a little time and saves tens of
	// megabytes.
	debug.SetGCPercent(50)
	stopMemprof := memprof.Start(15 * time.Second)
	proc.ExtendPath()
	proc.SweepTemp()
	// A second launch hands its work to the running app instead of starting
	// another player with its own audio.
	if !mygo.App.RequestSingleInstanceLock() {
		return
	}
	logsDir, closeLog := openLog()
	// Signed updates with the updater's native window, checked for only when
	// the user asks in Settings: in builds made without update signing it
	// stays idle and says so when asked.
	mygo.Use(native.New(updater.Options{DisableAutomaticChecks: true}))
	a := newApp()
	a.logsDir = logsDir
	mygo.App.WhenReady(func() {
		a.setup()
		a.desktop = &desktop{win: mygo.NewWindow(mygo.WindowOptions{
			Title:         "Meiro",
			Width:         1180,
			Height:        760,
			MinWidth:      860,
			MinHeight:     560,
			StateKey:      "meiro",
			TitleBarStyle: mygo.TitleBarHidden,
			Content:       ui.View(a.frame),
		})}
		// Start in the appearance the user saved, so the desktop's window
		// buttons match the window rather than the desktop's own theme.
		a.applyAppearance()
		a.applyWindowIcon()
		controls := a.systemMediaControls()
		controls.Window = a.desktop.win.NativeHandle()
		session, err := systemmedia.New(controls)
		if err != nil {
			log.Printf("system media controls unavailable: %v", err)
		} else {
			a.media = session
		}
		a.setupDesktop()
		a.syncSystemMedia()
		// Background work reaches the window through the desktop, so it
		// starts only once the window is there.
		a.restoreAccount()
		a.loadScrobbleSession()
		a.loadSyncToken()
		a.loadHistory()
		a.startTicker()
		a.every(persistInterval, func() { a.update(a.persistPlayback) })
		// Browsing decodes and drops pictures in bursts, and the runtime
		// keeps the freed memory for a while before it gives it back.
		a.every(30*time.Second, debug.FreeOSMemory)
		a.watchSession()
		a.every(flushInterval, func() { a.update(a.flushScrobbles) })
		a.every(syncInterval, func() { a.update(a.syncNow) })
		a.every(syncInterval, func() { a.update(a.pullChanges) })
		a.every(syncInterval, func() { a.update(a.pullEvents) })
		a.every(recommendEvery, func() { a.update(a.recommendTick) })
		time.AfterFunc(recommendStartDelay, func() { a.update(a.recommendTick) })
		a.desktop.win.OnClosed(a.stopTicking)
	})
	err := mygo.App.Run()
	a.stopTicking()
	a.shutdown()
	stopMemprof()
	if err != nil {
		log.Print(err)
		closeLog()
		os.Exit(1)
	}
	closeLog()
}

// openLog sends the standard logger, and slog, to the log file as well as to
// standard error, and returns the folder the log is in and what closes the
// file. A log that cannot be
// opened is not worth stopping for: the app runs without one.
func openLog() (dir string, closeLog func()) {
	dir, err := mygo.App.Path(mygo.PathLogs)
	if err != nil {
		log.Printf("finding the logs folder: %v", err)
		return "", func() {}
	}
	tools := applog.NewLazy("ffmpeg, yt-dlp: looking up")
	file, err := applog.Open(dir, logHeader(tools))
	if err != nil {
		log.Printf("opening the log file: %v", err)
		return "", func() {}
	}
	applog.Install(file)
	// The tools are asked after the log is open, so launch does not wait on
	// them; the answer goes into the log, and into the header of the next file.
	tools.Start(toolVersions, func(v string) { log.Print("tools: ", v) })
	return dir, func() { _ = file.Close() }
}

// toolVersions asks the tools the app ships for their versions.
func toolVersions() string {
	info := report.Collect(context.Background(), mygo.App.Version())
	return fmt.Sprintf("ffmpeg %s, yt-dlp %s", info.FFmpeg, info.YtDlp)
}

// logHeader names the versions in use, for the top of each log file.
func logHeader(tools *applog.Lazy) func() string {
	return func() string {
		version := mygo.App.Version()
		if version == "" {
			version = "development build"
		}
		return fmt.Sprintf("Meiro %s on %s/%s, %s\n%s",
			version, runtime.GOOS, runtime.GOARCH, runtime.Version(), tools.Get())
	}
}

// playerOptions decode with the ffmpeg the app ships, when there is one.
func playerOptions() []player.Option {
	if ffmpeg, err := proc.Tool("ffmpeg"); err == nil {
		return []player.Option{player.WithFFmpeg(ffmpeg)}
	}
	return nil
}

// setup reads the settings, opens the audio cache and the credential store,
// and makes the public client.
func (a *app) setup() {
	if directory, err := mygo.App.Path(mygo.PathUserData); err == nil {
		a.saver.Path = filepath.Join(directory, "settings.json")
		a.settings = settings.Load(a.saver.Path)
		a.account.creds.store = &cookies.FileStore{Path: filepath.Join(directory, "cookie.txt")}
		a.historyPath = filepath.Join(directory, "history.json")
		a.libraries = library.New(filepath.Join(directory, "libraries.json"))
		a.links = mblinks.Open(filepath.Join(directory, "mb-links.json"))
		a.recs.cache = openRelatedCache(filepath.Join(directory, "recommendations-cache.json"))
		a.importListLinks()
		a.downloads = downloads.New(filepath.Join(directory, "downloads.json"), filepath.Join(directory, "downloads"))
		a.restorePlayback(filepath.Join(directory, "playback.json"))
		a.openScrobbler(filepath.Join(directory, "lastfm-session.txt"), filepath.Join(directory, "lastfm-pending.json"))
		a.openSync(filepath.Join(directory, "sync-outbox.json"), filepath.Join(directory, "sync-token.txt"))
		a.openCredits(filepath.Join(directory, "credits.json"))
	} else {
		a.account.signIn.err = err.Error()
	}
	a.youtubeCacheMBText = strconv.Itoa(a.settings.YouTubeCacheMB)
	a.soundcloudCacheMBText = strconv.Itoa(a.settings.SoundCloudCacheMB)
	a.volume = a.settings.Volume
	a.player.SetVolume(a.volume / 100)
	a.loadEQ()
	a.openAudioCache()
	a.openSoundCloudCache()
	a.openArtworkCache()
	a.openMetadata()
}

// recommendEvery is how often the app checks whether the day's recommendations
// are made, and recommendStartDelay how long after launch it first looks, so
// the work waits until the app is up and the first page is shown.
const (
	recommendEvery      = time.Hour
	recommendStartDelay = 45 * time.Second
)

// openMetadata opens the client that reads MusicBrainz and ListenBrainz, with
// its answers kept under the app's cache folder. Without a cache folder the
// client still works, it just fetches every time.
func (a *app) openMetadata() {
	opts := musicbrainz.Options{}
	if root, err := mygo.App.Path(mygo.PathCache); err == nil {
		opts.CacheDir = filepath.Join(root, "Meiro Metadata Cache")
	}
	a.mb = musicbrainz.New(opts)
}

// artworkCacheLimit is how much downloaded artwork is kept on disk.
const artworkCacheLimit = 256 << 20

// openArtworkCache keeps downloaded artwork in the cache folder, so covers
// that leave memory are read back instead of downloaded again. Opening reads
// the details of every file in the folder, thousands of them once it is full,
// so it goes off the main thread: covers asked for before it is done are
// downloaded, as they are without a cache.
func (a *app) openArtworkCache() {
	root, err := mygo.App.Path(mygo.PathCache)
	if err != nil {
		return
	}
	dir := filepath.Join(root, "Meiro Artwork Cache")
	a.run(func() {
		store, err := diskcache.Open(dir, artworkCacheLimit)
		if err != nil {
			log.Printf("setting up artwork cache: %v", err)
			return
		}
		a.thumbs.UseStore(store)
	})
}

// openAudioCache opens the audio cache where the settings say, or at the
// default location when that folder is gone.
func (a *app) openAudioCache() {
	defaultRoot, err := mygo.App.Path(mygo.PathCache)
	if err != nil {
		log.Printf("finding default audio cache directory: %v", err)
		return
	}
	root := a.settings.CacheDirectory
	if root == "" {
		root = defaultRoot
	}
	a.cacheDirectoryText = root
	cache, canonical, err := audio.OpenLimits(root, cacheLimits(a.settings.YouTubeCacheMB))
	if err != nil && root != defaultRoot {
		log.Printf("setting up audio cache at %q: %v; using the default location", root, err)
		a.cacheDirError = "The saved folder is unavailable. Using the default cache location."
		cache, canonical, err = audio.OpenLimits(defaultRoot, cacheLimits(a.settings.YouTubeCacheMB))
	}
	if err != nil {
		log.Printf("setting up audio cache: %v", err)
		return
	}
	a.cacheDirectoryText = canonical
	a.replaceAudioCache(cache)
}

// cacheLimits is what a cache keeps for a size in megabytes: no bound on the
// number of tracks, only on their size, and nothing at all for a size of zero.
func cacheLimits(mb int) audio.Limits {
	if mb <= 0 {
		return audio.Limits{}
	}
	return audio.Limits{Count: math.MaxInt, Bytes: int64(mb) << 20}
}

// cacheRoot is the folder both providers' caches are kept in, each in a folder
// of its own inside it: the one the settings name, or the app's cache folder.
func (a *app) cacheRoot() (string, error) {
	if a.cacheDirectoryText != "" {
		return a.cacheDirectoryText, nil
	}
	return mygo.App.Path(mygo.PathCache)
}

// openSoundCloudCache opens SoundCloud's own audio cache, in a folder that
// never mixes with the YouTube Music cache but sits beside it, bounded by the
// size the settings say. A size of zero removes the cache playback would use.
func (a *app) openSoundCloudCache() {
	if a.settings.SoundCloudCacheMB <= 0 {
		if previous := a.replaceSoundCloudCache(nil); previous != nil {
			a.run(func() {
				previous.Close()
				previous.Purge()
			})
		}
		return
	}
	root, err := a.cacheRoot()
	if err != nil {
		log.Printf("finding the SoundCloud cache directory: %v", err)
		return
	}
	scRoot := filepath.Join(root, "Meiro SoundCloud Cache")
	if err := os.MkdirAll(scRoot, 0o700); err != nil {
		log.Printf("setting up SoundCloud cache folder: %v", err)
		return
	}
	cache, _, err := audio.OpenLimits(scRoot, cacheLimits(a.settings.SoundCloudCacheMB))
	if err != nil {
		log.Printf("setting up SoundCloud cache: %v", err)
		return
	}
	if previous := a.replaceSoundCloudCache(cache); previous != nil {
		a.run(func() {
			previous.Close()
			if previous.Dir() != cache.Dir() {
				previous.Purge()
			}
		})
	}
}

// shutdown flushes the settings, then closes what belongs to the app.
func (a *app) shutdown() {
	a.notificationArtwork.stop()
	a.finishEpisodeProgress(true)
	a.finishPlayRecord(false)
	a.savePlayback(0)
	a.saver.Wait()
	a.historyWriter.Wait()
	a.playSave.writer.Wait()
	a.scrobble.outbox.Wait()
	a.scrobble.credits.Wait()
	a.sync.outbox.Wait()
	a.desktop.close()
	if a.discord != nil {
		a.discord.Close()
		a.discord = nil
	}
	if a.media != nil {
		if err := a.media.Close(); err != nil {
			log.Printf("close system media controls: %v", err)
		}
	}
	a.player.Stop()
	a.closeAudioCaches()
}

// stopTicking ends the periodic work. The window closing and the app exiting
// both call it.
func (a *app) stopTicking() { a.stopOnce.Do(func() { close(a.stop) }) }

// every runs fn once per interval off the main thread, until the app stops.
func (a *app) every(interval time.Duration, fn func()) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-a.stop:
				return
			case <-ticker.C:
				a.guarded("periodic work", fn)
			}
		}
	}()
}
