package main

import (
	"context"
	"errors"
	"log"
	"os"
	"time"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// errOffline is why a track that is neither downloaded nor cached cannot
// play: the network is out of reach, and nothing is kept for the track. The
// message it carries is what the listener sees, through playErrorMessage.
var errOffline = errors.New("offline: this track is not downloaded")

// resolvedAudio is where a track's audio is, and how long it plays.
type resolvedAudio struct {
	url       string
	total     time.Duration
	fromCache bool
	// fromDownload says the audio is a file the user downloaded to keep, which
	// outlives the cache and plays without the network.
	fromDownload bool
	// playErr is why the player could not open the audio, once found.
	playErr error
	// loudness is the track's loudness when it was looked up on the way.
	loudness   youtube.Loudness
	loudnessOK bool
}

// resolveSource is where a track's audio comes from. An item saved before
// sources existed has none, and reads as YouTube Music.
func resolveSource(item youtube.MusicItem) media.Source {
	if item.Source == "" {
		return media.SourceYTM
	}
	return item.Source
}

// streamsFor chooses the resolver a source plays through. YouTube Music plays
// through yt-dlp; SoundCloud resolves a track's page URL through its own
// resolver and its own URL cache.
func (a *app) streamsFor(source media.Source) *audio.URLs {
	switch source {
	case media.SourceYTM:
		// YouTube Music resolves through yt-dlp below.
	case media.SourceSoundCloud:
		return a.soundcloudStreams
	}
	return a.streams
}

// streamKey is what a track's audio is resolved by: the YouTube video ID for
// YouTube Music, and the page URL for SoundCloud, which carries no video ID.
func streamKey(item youtube.MusicItem) string {
	if resolveSource(item) == media.SourceSoundCloud {
		return item.URL
	}
	return item.VideoID
}

// audioKey is the key a track's audio file is cached under, qualified by its
// source so a YouTube video ID and a SoundCloud page URL can never name the
// same file, even when the two happen to read alike.
func audioKey(item youtube.MusicItem) string {
	return string(resolveSource(item)) + ":" + streamKey(item)
}

// cacheMeta is what is kept of a track as it is cached, so a local search can
// find it later without the network. Key is the provider's own key.
func cacheMeta(item youtube.MusicItem) audio.TrackMeta {
	return audio.TrackMeta{
		Key:      streamKey(item),
		Source:   string(resolveSource(item)),
		Title:    item.Title,
		Artist:   item.Artist,
		Duration: item.Duration,
	}
}

// streamAudio finds a track's audio off the main thread, in the cache or
// with yt-dlp, and plays it.
func (a *app) streamAudio(item youtube.MusicItem, startAt time.Duration, changed bool) {
	cache := a.cacheFor(resolveSource(item))
	accountProgress := a.signedIn()
	// The work below runs off the main thread, so it takes what it needs of
	// the app's state now.
	loudnessClient := a.loudnessClient(item.VideoID)
	known, knownOK := a.loudnessMap.Get(item.VideoID)
	volume, normalize := a.volume, a.settings.NormalizeLoudness
	streams := a.streamsFor(resolveSource(item))
	a.markSourceTried(resolveSource(item))
	log.Printf("playback: resolving video_id=%s", item.VideoID)
	startTask(a, &a.stream, audio.ResolveTimeout, func(ctx context.Context) (resolvedAudio, error) {
		a.player.Stop()
		// The gain is in place before the device starts, so a track that is
		// louder or quieter than the last one does not begin at the wrong
		// volume for a moment. A lookup that fails, or that runs past
		// loudnessWait, plays the track unnormalized rather than not at all.
		var fresh youtube.Loudness
		var freshOK bool
		if loudnessClient != nil {
			wait, cancel := context.WithTimeout(ctx, loudnessWait)
			value, err := loudnessClient.Loudness(wait, item.VideoID)
			cancel()
			if err != nil {
				log.Printf("playback: loudness unavailable video_id=%s: %v", item.VideoID, err)
			} else {
				known, knownOK = value, true
				fresh, freshOK = value, true
			}
		}
		a.player.SetVolume(playbackVolume(volume, known.LoudnessDB, knownOK && normalize))
		found, err := findAudio(ctx, streams, item, cache, a.downloads, a.networkOnline)
		found.loudness, found.loudnessOK = fresh, freshOK
		if err != nil || ctx.Err() != nil {
			return found, err
		}
		if accountProgress && item.IsEpisode() {
			startAt = max(startAt, episodeProgressPosition(item, found.total))
		}
		// Opening the audio device and starting ffmpeg take long enough to
		// be felt, so they happen here and not on the main thread.
		found.playErr = a.player.PlayAtContext(ctx, found.url, startAt, false)
		return found, nil
	}, func(found resolvedAudio, err error) {
		if found.loudnessOK {
			a.loudnessMap.Put(item.VideoID, found.loudness)
		}
		if err != nil {
			if a.tryFallback(item, err.Error()) {
				return
			}
			a.fallbackMsg = ""
			a.failPlayback(item, "audio resolution failed", err.Error())
			a.skipUnplayable(item)
			return
		}
		a.applyLoudness()
		if found.total > 0 {
			a.total = found.total
		}
		if found.playErr != nil {
			if a.tryFallback(item, found.playErr.Error()) {
				return
			}
			a.fallbackMsg = ""
			a.failPlayback(item, "player could not open", found.playErr.Error())
			a.skipUnplayable(item)
			return
		}
		log.Printf("playback: audio started video_id=%s from_cache=%t duration=%s", item.VideoID, found.fromCache, found.total)
		a.audioStarted(item, found, a.player.Position(), changed, cache)
		a.syncSystemMedia()
	})
}

// fallbackNotice is how long the message that another provider took over
// shows, as the design asks.
const fallbackNotice = 2 * time.Second

// markSourceTried records that the current track's audio has been looked for
// in source, so a failure there moves on rather than back.
func (a *app) markSourceTried(source media.Source) {
	if a.triedSources == nil {
		a.triedSources = map[media.Source]bool{}
	}
	a.triedSources[source] = true
}

// nextSource is the next provider item should be played from after the one it
// was tried on failed: the first of the preference order that holds it and has
// not been tried. It reports false when every provider has been tried.
func (a *app) nextSource(item youtube.MusicItem) (media.Source, bool) {
	for _, source := range a.providerOrder() {
		if _, ok := item.RefFor(source); !ok {
			continue
		}
		if a.triedSources[source] {
			continue
		}
		return source, true
	}
	return "", false
}

// tryFallback plays the current track from the next provider that holds it
// when the one it was on failed, and shows a short message saying so. It
// reports whether it took over; a track no other provider holds keeps its
// failure.
func (a *app) tryFallback(item youtube.MusicItem, raw string) bool {
	next, ok := a.nextSource(item)
	if !ok {
		return false
	}
	failed := resolveSource(item)
	log.Printf("playback: %s failed for video_id=%s, trying %s: %s", failed, item.VideoID, next, raw)
	updated := item.WithSource(next)
	a.queue.setSource(item.Key(), next)
	a.replaceSourceEverywhere(item.Key(), updated, next)
	// The message waits until the other provider actually plays, so a source
	// that fails in turn announces nothing.
	a.fallbackMsg = sourceLabel(string(failed)) + " failed, playing from " + sourceLabel(string(next))
	a.current = updated
	a.replayFromSource(updated)
	return true
}

// audioStarted takes note of a track whose audio has begun to play from
// position: the run of skips ends, the play is recorded and reported, its
// audio is cached for the next time, and the track after it is readied.
// cache is the audio cache the track was looked for in.
func (a *app) audioStarted(item youtube.MusicItem, found resolvedAudio, position time.Duration, changed bool, cache *audio.Cache) {
	a.skipped = 0
	if a.fallbackMsg != "" {
		a.notice, a.noticeTimeout, a.fallbackMsg = a.fallbackMsg, fallbackNotice, ""
	}
	if changed {
		a.beginPlayRecord(item)
	}
	if item.IsEpisode() {
		a.beginEpisodeProgress(item, position, found.total)
	} else if changed {
		a.reportPlayback(item)
	}
	if found.fromDownload {
		// A downloaded track was played; note when, for the downloads page.
		a.touchDownload(item)
	} else if !found.fromCache {
		// The direct URL is already in hand, so the cache downloads it as it
		// is instead of resolving the same track again.
		cache.Add(audioKey(item), found.url, cacheMeta(item))
	}
	a.warmNext()
}

// preloadLead is how long before the end of a track the next one is opened:
// long enough for ffmpeg to have it decoding when the sound runs out, short
// enough not to hold a connection to the server open through the whole song.
const preloadLead = 20 * time.Second

// preloadedAudio is the audio of the track the player holds ready.
type preloadedAudio struct {
	id string
	resolvedAudio
}

// following is the place in the queue of the track that plays after this
// one, and the track. A repeated track follows itself, at place -1.
func (a *app) following() (int, youtube.MusicItem, bool) {
	if a.repeat == repeatTrack {
		return -1, a.current, a.current.Key() != ""
	}
	i, ok := a.queue.next(a.settings.AutoPlay, a.repeat)
	if !ok {
		return 0, youtube.MusicItem{}, false
	}
	return i, a.queue.at(i), true
}

// syncPreload keeps the player's preloaded stream the one for the track that
// follows, and starts it once the current track is near its end. It runs on
// every poll and after anything that can change what follows.
func (a *app) syncPreload() {
	_, next, ok := a.following()
	if held := a.player.Preloaded(); held != "" && (!ok || held != next.Key()) {
		a.player.CancelPreload()
		a.preloaded = preloadedAudio{}
	}
	if !ok || a.preload.busy() && a.preloadFor != next.Key() {
		a.preload.stop()
	}
	if !ok || a.stream.busy() || a.preload.busy() || a.preloadFailed == next.Key() ||
		a.player.Preloaded() == next.Key() || a.total <= 0 || !a.player.Playing() ||
		a.total-a.player.Position() > preloadLead {
		return
	}
	cache := a.cacheFor(resolveSource(next))
	a.preloadFor = next.Key()
	a.fetchLoudness(next, &a.preloadLoudnessLoad)
	startTask(a, &a.preload, audio.ResolveTimeout, func(ctx context.Context) (preloadedAudio, error) {
		found, err := findAudio(ctx, a.streamsFor(resolveSource(next)), next, cache, a.downloads, a.networkOnline)
		if err == nil {
			err = ctx.Err()
		}
		if err != nil {
			return preloadedAudio{}, err
		}
		return preloadedAudio{next.Key(), found}, a.player.PreloadContext(ctx, next.Key(), found.url)
	}, func(found preloadedAudio, err error) {
		if err != nil {
			log.Printf("playback: could not preload video_id=%s: %v", next.VideoID, err)
			a.preloadFailed = next.Key()
			return
		}
		log.Printf("playback: preloaded video_id=%s from_cache=%t", next.VideoID, found.fromCache)
		a.preloaded = found
	})
}

// followed moves on to the track the player took over with, as the track
// before it ended. The queue may have changed since the player took it:
// then what it plays is not what follows, and the right track is started.
func (a *app) followed(tag string) {
	i, next, ok := a.following()
	if !ok || next.Key() != tag || a.preloaded.id != tag {
		a.trackEnded()
		return
	}
	found := a.preloaded
	item := next
	if i >= 0 {
		item = a.queue.moveTo(i)
	}
	startAt, changed := a.enterTrack(item, true)
	a.applyLoudness()
	if found.total > 0 {
		a.total = found.total
	}
	if item.IsEpisode() {
		if a.signedIn() {
			startAt = max(startAt, episodeProgressPosition(item, found.total))
		}
		if startAt > 0 {
			a.seek(startAt)
		}
	}
	log.Printf("playback: audio followed on video_id=%s from_cache=%t duration=%s", item.VideoID, found.fromCache, found.total)
	a.audioStarted(item, found.resolvedAudio, startAt, changed, a.cacheFor(resolveSource(item)))
	a.afterEnter(item, changed)
	a.syncSystemMedia()
}

// failPlayback records why item did not play: the raw error goes to the log
// and the listener sees a short reason.
func (a *app) failPlayback(item youtube.MusicItem, what, raw string) {
	log.Printf("playback: %s video_id=%s: %s", what, item.VideoID, raw)
	a.rawPlayErr, a.playErr = raw, playErrorMessage(raw)
	a.syncSystemMedia()
}

const (
	// maxRecoveries is how many times in a row a stream that broke off is
	// found again before the track is given up on.
	maxRecoveries = 3
	// recoveryHealthy is how far a recovered stream plays before it counts as
	// working again.
	recoveryHealthy = 10 * time.Second
	// recoveryBackoff is the wait before the first retry; each one waits
	// longer, which gives a network that just came back a moment to settle.
	recoveryBackoff = 500 * time.Millisecond
	// endOfTrackLead is how much of a track may be left when its stream
	// breaks off and the track still counts as over. YouTube's server stops
	// at the end of a track's data, which breaks the stream off in the last
	// moment of it: what is left is shorter than the wait before the stream
	// is found again, and playing that moment again is what the listener
	// hears as the ending twice.
	endOfTrackLead = 3 * time.Second
)

// atTrackEnd reports whether the track playing has reached the last moment of
// its length, where a stream that breaks off is over rather than broken. A
// paused stream is not at its end: it holds where the listener left it.
func (a *app) atTrackEnd() bool {
	if a.total <= 0 || a.player.Paused() {
		return false
	}
	return a.total-a.player.Position() <= endOfTrackLead
}

// recoverStream picks the current track up where its stream broke off, as it
// does when the network drops mid-song, or the machine wakes with an audio
// URL that has expired: it resolves the track again, which the URL cache
// must not answer from memory, and plays from the position reached. It
// reports false when the tries are used up, and a failure that ends one try
// starts the next from the apply.
func (a *app) recoverStream(failure string) bool {
	item := a.current
	if item.Key() == "" || a.recoveries >= maxRecoveries {
		return false
	}
	a.recoveries++
	attempt := a.recoveries
	at := a.player.Position()
	if attempt == 1 {
		a.recoveredFrom = at
	}
	paused, bySleep := a.player.Paused(), a.suspendedWhilePlaying
	cache := a.cacheFor(resolveSource(item))
	streams := a.streamsFor(resolveSource(item))
	log.Printf("playback: stream broke off video_id=%s at=%s attempt=%d/%d: %s", item.VideoID, at.Round(time.Second), attempt, maxRecoveries, failure)
	a.fetchLoudness(item, &a.loudnessLoad)
	startTask(a, &a.stream, audio.ResolveTimeout+time.Duration(attempt)*recoveryBackoff, func(ctx context.Context) (resolvedAudio, error) {
		if err := a.sleep(ctx, time.Duration(attempt)*recoveryBackoff); err != nil {
			return resolvedAudio{}, err
		}
		streams.Forget(streamKey(item))
		found, err := findAudio(ctx, streams, item, cache, a.downloads, a.networkOnline)
		if err != nil || ctx.Err() != nil {
			return found, err
		}
		found.playErr = a.player.PlayAtContext(ctx, found.url, at, paused)
		return found, nil
	}, func(found resolvedAudio, err error) {
		if err == nil {
			err = found.playErr
		}
		if err != nil {
			log.Printf("playback: recovery failed video_id=%s attempt=%d/%d: %v", item.VideoID, attempt, maxRecoveries, err)
			if !a.recoverStream(err.Error()) {
				a.failPlayback(item, "recovery failed", err.Error())
				a.skipUnplayable(item)
			}
			return
		}
		a.applyLoudness()
		if paused && bySleep && !a.suspendedWhilePlaying {
			a.player.Resume() // the wake came while the stream was on its way
		}
		a.syncSystemMedia()
	})
	return true
}

// findAudio is where a track's audio is: a file the user downloaded to keep,
// then the playback cache, then the network. The order is what makes a
// downloaded track play offline, and what makes the cache only a cache.
func findAudio(ctx context.Context, streams *audio.URLs, item youtube.MusicItem, cache *audio.Cache, store *downloads.Store, online func() bool) (resolvedAudio, error) {
	found := resolvedAudio{total: parseDuration(item.Duration)}
	if path, ok := downloadedAudio(store, item); ok {
		log.Printf("playback: audio from download video_id=%s", item.VideoID)
		found.url, found.fromDownload = path, true
		if found.total <= 0 {
			probe, cancel := context.WithTimeout(ctx, 15*time.Second)
			defer cancel()
			found.total = audio.Probe(probe, path)
		}
		return found, nil
	}
	if path, ok := cache.Get(audioKey(item)); ok {
		log.Printf("playback: audio cache hit video_id=%s", item.VideoID)
		found.url, found.fromCache = path, true
		if found.total <= 0 {
			// A track cached ahead of time was never resolved, so its
			// length is written nowhere but the file itself.
			probe, cancel := context.WithTimeout(ctx, 15*time.Second)
			defer cancel()
			found.total = audio.Probe(probe, path)
		}
		return found, nil
	}
	url, total, err := streams.Get(ctx, streamKey(item))
	found.url = url
	if total > 0 {
		found.total = total
	}
	if err != nil && online != nil && !online() {
		// The resolve failed and the network is out of reach: the honest
		// reason is that the track was never downloaded, not that the
		// provider said no.
		return found, errOffline
	}
	return found, err
}

// downloadedAudio is the path of a track the user downloaded to keep, when
// there is one and its file is still there.
func downloadedAudio(store *downloads.Store, item youtube.MusicItem) (string, bool) {
	if store == nil {
		return "", false
	}
	path, ok := store.File(resolveSource(item), streamKey(item))
	if !ok {
		return "", false
	}
	info, err := os.Stat(path)
	if err != nil || info.IsDir() || info.Size() == 0 {
		return "", false
	}
	return path, true
}

// warmNext resolves the next track's audio URL while the current one plays,
// so a play that follows skips yt-dlp. That costs a few kilobytes. When the
// cache is big enough for a warmed track to survive until it plays, the
// track is also downloaded from that URL.
func (a *app) warmNext() {
	if a.queue.remaining() < 1 {
		return
	}
	next := a.queue.at(a.queue.index + 1)
	if next.Key() == "" || next.Key() == a.current.Key() {
		return
	}
	cache := a.cacheFor(resolveSource(next))
	a.run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), audio.ResolveTimeout)
		defer cancel()
		url, _, err := a.streamsFor(resolveSource(next)).Get(ctx, streamKey(next))
		if err != nil {
			log.Printf("playback: could not resolve next video_id=%s: %v", next.VideoID, err)
			return
		}
		if cache.Warmable() {
			cache.Add(audioKey(next), url, cacheMeta(next))
		}
	})
}
