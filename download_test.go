package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/downloads"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// downloadTestItem is a YouTube Music track for the download tests.
func downloadTestItem(id string) youtube.MusicItem {
	return youtube.MusicItem{VideoID: id, Title: "Track " + id, Kind: media.KindTrack, Duration: "3:00"}
}

// newDownloadStore builds a download store with a real file directory in a
// temporary folder, which is what these tests keep tracks in.
func newDownloadStore(t *testing.T) *downloads.Store {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "downloads")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	return downloads.New(filepath.Join(dir, "index.json"), dir)
}

// addDownload writes a file for a track and records it, as a finished
// download would.
func addDownload(t *testing.T, store *downloads.Store, source media.Source, item youtube.MusicItem) string {
	t.Helper()
	path, name := store.Path(source, streamKey(item), ".mkv")
	if err := os.WriteFile(path, []byte("downloaded audio bytes for "+item.Title), 0o600); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Add(downloads.Item{
		Source: source, Key: streamKey(item), Title: item.Title, Artist: item.Artist,
		SizeBytes: info.Size(), DownloadedAt: time.Now(), File: name,
	}); err != nil {
		t.Fatal(err)
	}
	return path
}

// findAudio takes its audio from a download first, then the cache, then the
// network.
func TestFindAudioPrefersDownloadThenCacheThenNetwork(t *testing.T) {
	ctx := context.Background()
	networkURL := "https://media.test/network"
	streams := audio.NewURLs(func(context.Context, string) (string, time.Duration, error) {
		return networkURL, 0, nil
	})
	online := func() bool { return true }

	// A downloaded track wins over the network.
	store := newDownloadStore(t)
	item := downloadTestItem("vid-dl")
	path := addDownload(t, store, media.SourceYTM, item)
	found, err := findAudio(ctx, streams, item, nil, store, online)
	if err != nil || !found.fromDownload || found.url != path {
		t.Fatalf("download: url=%q fromDownload=%v err=%v", found.url, found.fromDownload, err)
	}

	// A cached track wins over the network.
	cacheRoot := t.TempDir()
	cache, _, err := audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	cacheItem := downloadTestItem("vid-cache")
	cachePath := filepath.Join(cache.Dir(), cacheName(cacheItem)+".webm")
	if err := os.WriteFile(cachePath, []byte("cached audio bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	cache.Close()
	cache, _, err = audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	defer cache.Close()
	found, err = findAudio(ctx, streams, cacheItem, cache, nil, online)
	if err != nil || !found.fromCache || found.url != cachePath {
		t.Fatalf("cache: url=%q fromCache=%v err=%v", found.url, found.fromCache, err)
	}

	// With neither, the audio comes from the network.
	found, err = findAudio(ctx, streams, downloadTestItem("vid-net"), nil, newDownloadStore(t), online)
	if err != nil || found.url != networkURL || found.fromCache || found.fromDownload {
		t.Fatalf("network: url=%q err=%v", found.url, err)
	}
}

// cacheName is the file name the audio cache gives a track, for a test that
// places a cached file itself.
func cacheName(item youtube.MusicItem) string {
	sum := sha256.Sum256([]byte(audioKey(item)))
	return hex.EncodeToString(sum[:])
}

// A resolve that failed with the network out of reach and no download says
// so, rather than blaming the provider.
func TestFindAudioOfflineWithoutDownload(t *testing.T) {
	streams := audio.NewURLs(func(context.Context, string) (string, time.Duration, error) {
		return "", 0, errors.New("resolve failed")
	})
	_, err := findAudio(context.Background(), streams, downloadTestItem("vid-missing"), nil, downloads.New("", ""), func() bool { return false })
	if !errors.Is(err, errOffline) {
		t.Fatalf("err = %v, want errOffline", err)
	}
	message := playErrorMessage(err.Error())
	if !strings.Contains(strings.ToLower(message), "offline") || !strings.Contains(message, "not downloaded") {
		t.Errorf("offline message = %q", message)
	}
}

// Playing a downloaded track records when it was last listened to.
func TestDownloadedTrackRecordsLastListen(t *testing.T) {
	a := newTestApp(t)
	store := newDownloadStore(t)
	a.downloads = store
	item := downloadTestItem("vid-listen")
	addDownload(t, store, media.SourceYTM, item)

	a.audioStarted(item, resolvedAudio{fromDownload: true}, 0, true, nil)

	rec, ok := store.Get(media.SourceYTM, streamKey(item))
	if !ok || rec.LastListenedAt.IsZero() {
		t.Fatalf("last listened = %v, ok=%v", rec.LastListenedAt, ok)
	}
	if time.Since(rec.LastListenedAt) > time.Minute {
		t.Errorf("last listened = %v, want about now", rec.LastListenedAt)
	}
}

// Clearing a playback cache deletes its own files and leaves a downloaded
// track untouched, on disk and in the store.
func TestClearingTheCacheKeepsDownloads(t *testing.T) {
	cacheRoot := t.TempDir()
	cache, _, err := audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	item := downloadTestItem("vid-both")
	cachePath := filepath.Join(cache.Dir(), cacheName(item)+".webm")
	if err := os.WriteFile(cachePath, []byte("cached audio bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	cache.Close()
	cache, _, err = audio.Open(cacheRoot, 5)
	if err != nil {
		t.Fatal(err)
	}
	defer cache.Close()

	store := newDownloadStore(t)
	downloadPath := addDownload(t, store, media.SourceYTM, item)

	cache.Clear("")

	if _, err := os.Stat(cachePath); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("clearing the cache left %s", cachePath)
	}
	if _, err := os.Stat(downloadPath); err != nil {
		t.Errorf("clearing the cache removed the download: %v", err)
	}
	if _, ok := store.Get(media.SourceYTM, streamKey(item)); !ok {
		t.Error("clearing the cache removed the download entry")
	}
}

// The SoundCloud cache lives in a folder of its own, so it never mixes with
// the YouTube Music cache.
func TestSoundCloudCacheIsSeparate(t *testing.T) {
	root := t.TempDir()
	ytm, ytmRoot, err := audio.Open(root, 5)
	if err != nil {
		t.Fatal(err)
	}
	defer ytm.Close()
	scRootDir := filepath.Join(root, "Meiro SoundCloud Cache")
	if err := os.MkdirAll(scRootDir, 0o700); err != nil {
		t.Fatal(err)
	}
	sc, scRoot, err := audio.OpenLimits(scRootDir, audio.Limits{Count: 1 << 30, Bytes: 10 << 20})
	if err != nil {
		t.Fatal(err)
	}
	defer sc.Close()
	if ytm.Dir() == sc.Dir() || ytmRoot == scRoot {
		t.Fatalf("the two caches share a folder: %q and %q", ytm.Dir(), sc.Dir())
	}
}

// tinyWAV is a tenth of a second of silence as a WAV file, small enough to
// build in a test and real enough for ffmpeg to decode.
func tinyWAV() []byte {
	const rate, samples = 8000, 800
	var b bytes.Buffer
	b.WriteString("RIFF")
	_ = binary.Write(&b, binary.LittleEndian, uint32(36+samples*2))
	b.WriteString("WAVEfmt ")
	_ = binary.Write(&b, binary.LittleEndian, struct {
		Size             uint32
		Format, Channels uint16
		Rate, ByteRate   uint32
		Align, Bits      uint16
	}{16, 1, 1, rate, rate * 2, 2, 16})
	b.WriteString("data")
	_ = binary.Write(&b, binary.LittleEndian, uint32(samples*2))
	b.Write(make([]byte, samples*2))
	return b.Bytes()
}

// A download keeps the audio in the container the server sent it in, without
// ffmpeg writing anything: the ffmpeg a release ships has no muxer, so any
// call to it with an output format would fail there.
func TestDownloadSavesTheStreamWithoutFFmpeg(t *testing.T) {
	wav := tinyWAV()
	media := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/wav")
		http.ServeContent(w, r, "track.wav", time.Time{}, bytes.NewReader(wav))
	}))
	defer media.Close()

	a := newTestApp(t)
	store := newDownloadStore(t)
	a.downloads = store
	a.streams = audio.NewURLs(func(context.Context, string) (string, time.Duration, error) {
		return media.URL + "/track", 0, nil
	})
	// An ffmpeg that writes down every call it gets leads PATH, so a download
	// that reached for one would leave a trace.
	bin := t.TempDir()
	calls := filepath.Join(bin, "calls")
	script := "#!/bin/sh\necho \"$@\" >> '" + calls + "'\nexit 1\n"
	if err := os.WriteFile(filepath.Join(bin, "ffmpeg"), []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin+string(os.PathListSeparator)+os.Getenv("PATH"))

	item := downloadTestItem("vid-wav")
	a.startDownload(item)

	rec, ok := store.Get(resolveSource(item), streamKey(item))
	if !ok || a.downloadStatusOf(item).status != downloadDone {
		t.Fatalf("the download did not finish: state %+v, kept %v", a.downloadStatusOf(item), ok)
	}
	path, _ := store.File(rec.Source, rec.Key)
	if got, err := os.ReadFile(path); err != nil || !bytes.Equal(got, wav) {
		t.Fatalf("the stored file differs from what the server sent: %v", err)
	}
	if filepath.Ext(path) != ".wav" || rec.SizeBytes != int64(len(wav)) {
		t.Errorf("stored as %s with size %d, want .wav and %d", filepath.Base(path), rec.SizeBytes, len(wav))
	}
	if _, err := os.Stat(calls); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("the download ran ffmpeg:\n%s", mustRead(calls))
	}
	found, err := findAudio(context.Background(), a.streams, item, nil, store, func() bool { return false })
	if err != nil || !found.fromDownload || found.url != path {
		t.Fatalf("playback does not find the download: url=%q fromDownload=%v err=%v", found.url, found.fromDownload, err)
	}
}

// A stored download decodes with a real ffmpeg, when the machine has one.
func TestDownloadedFileDecodes(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg is not installed")
	}
	media := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/wav")
		_, _ = w.Write(tinyWAV())
	}))
	defer media.Close()
	base := filepath.Join(t.TempDir(), "store", "track")
	ext, size, err := fetchDownload(context.Background(), media.Client(), media.URL, base)
	if err != nil || ext != ".wav" || size != int64(len(tinyWAV())) {
		t.Fatalf("fetchDownload = %q, %d, %v", ext, size, err)
	}
	if out, err := exec.Command(ffmpeg, "-v", "error", "-i", base+ext, "-f", "null", "-").CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg cannot decode the download: %v\n%s", err, out)
	}
}

// A stream that comes as an HLS playlist fails with a reason the listener can
// read, and leaves nothing behind.
func TestDownloadOfASegmentedStreamSaysSo(t *testing.T) {
	a := newTestApp(t)
	store := newDownloadStore(t)
	a.downloads = store
	media := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-ENDLIST\n"))
	}))
	defer media.Close()
	a.streams = audio.NewURLs(func(context.Context, string) (string, time.Duration, error) {
		return media.URL + "/playlist", 0, nil
	})
	item := downloadTestItem("vid-hls")
	a.startDownload(item)

	st := a.downloadStatusOf(item)
	if st.status != downloadFailed || !strings.Contains(a.notice, "cannot download yet") {
		t.Errorf("state %+v, notice %q; want a failure that says the stream cannot be downloaded", st, a.notice)
	}
	if strings.Contains(a.notice, "matroska") || strings.Contains(a.notice, "ffmpeg") {
		t.Errorf("notice = %q", a.notice)
	}
	entries, _ := os.ReadDir(store.Dir())
	if len(entries) != 0 {
		t.Errorf("the store holds %d leftovers", len(entries))
	}
}

func mustRead(path string) string {
	data, _ := os.ReadFile(path)
	return string(data)
}

// failDownloads asks for the tracks and has every one of them fail, so they
// are listed as failed.
func failDownloads(t *testing.T, a *app, ids ...string) {
	t.Helper()
	a.downloadAudio = func(context.Context, *audio.URLs, string, string) (string, int64, error) {
		return "", 0, errors.New("server returned 500")
	}
	for _, id := range ids {
		a.startDownload(downloadTestItem(id))
	}
	for _, id := range ids {
		if st := a.downloadStatusOf(downloadTestItem(id)); st.status != downloadFailed {
			t.Fatalf("%s is %v, want failed", id, st.status)
		}
	}
}

// openDownloads opens the Downloads page and draws it.
func openDownloads(a *app) *ui.Tester {
	a.router.Push("/downloads")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	return tt
}

// A failed download shows Retry on its row, and pressing it queues that song
// again and no other.
func TestRetryRequeuesOneFailedDownload(t *testing.T) {
	a, fake := downloadingApp(t)
	failDownloads(t, a, "f1", "f2")
	a.downloadAudio = fake.fetch
	tt := openDownloads(a)
	for _, want := range []string{"Retry download of Track f1", "Retry download of Track f2", "Retry all"} {
		if !tt.HasText(want) && !hasLabel(tt, want) {
			t.Fatalf("the page does not offer %q: %q", want, tt.Texts())
		}
	}

	if err := tt.Click("Retry download of Track f2"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if got, want := fake.asked(), []string{"f2"}; !slices.Equal(got, want) {
		t.Fatalf("retried %v, want %v", got, want)
	}
	if !a.isDownloaded(downloadTestItem("f2")) || a.isDownloaded(downloadTestItem("f1")) {
		t.Errorf("f2 kept %v, f1 kept %v; want true and false", a.isDownloaded(downloadTestItem("f2")), a.isDownloaded(downloadTestItem("f1")))
	}
	if hasLabel(tt, "Retry download of Track f2") || !hasLabel(tt, "Retry download of Track f1") {
		t.Errorf("after the retry the page shows %q", tt.Texts())
	}
}

// Retry all queues every failed download again, through the queue that runs
// two at a time, and the button goes once nothing has failed.
func TestRetryAllRequeuesEveryFailedDownload(t *testing.T) {
	a, fake := downloadingApp(t)
	failDownloads(t, a, "f1", "f2", "f3")
	a.downloadAudio = fake.fetch
	var inFlight []func()
	a.run = func(work func()) { inFlight = append(inFlight, work) }
	tt := openDownloads(a)

	if err := tt.Click("Retry all"); err != nil {
		t.Fatal(err)
	}
	if a.downloadsRunning != 2 || len(a.downloadQueue) != 1 || len(inFlight) != 2 {
		t.Fatalf("running %d, queued %d, in flight %d; want 2, 1, 2", a.downloadsRunning, len(a.downloadQueue), len(inFlight))
	}
	tt.Frame()
	if hasLabel(tt, "Retry all") || !tt.HasText("Waiting to download") {
		t.Errorf("a retry in progress still offers Retry all: %q", tt.Texts())
	}
	for len(inFlight) > 0 {
		work := inFlight[0]
		inFlight = inFlight[1:]
		work()
	}
	if got, want := fake.asked(), []string{"f1", "f2", "f3"}; !slices.Equal(got, want) {
		t.Errorf("retried %v, want %v", got, want)
	}
	tt.Frame()
	if hasLabel(tt, "Retry all") || tt.HasText("failed") {
		t.Errorf("the header stays with nothing failed: %q", tt.Texts())
	}
	for _, id := range []string{"f1", "f2", "f3"} {
		if !a.isDownloaded(downloadTestItem(id)) {
			t.Errorf("%s was not kept", id)
		}
	}
}

// With no failed download the page has no Retry at all.
func TestNoRetryWithoutAFailedDownload(t *testing.T) {
	a, _ := downloadingApp(t)
	keepDownloaded(t, a, "k1")
	tt := openDownloads(a)
	if hasLabel(tt, "Retry all") || tt.HasText("Retry") {
		t.Errorf("Retry is offered with nothing failed: %q", tt.Texts())
	}
}

// hasLabel reports whether an element is labelled s exactly.
func hasLabel(tt *ui.Tester, s string) bool {
	_, ok := tt.Find(s)
	return ok
}

// A song kept as a download shows a Downloaded mark in its row beside the
// MusicBrainz state, and the link still works; one that is not kept shows none.
func TestDownloadedSongRowShowsTheMark(t *testing.T) {
	a, _ := downloadingApp(t)
	tt := loadedPage(a, "/playlist/VLPL_long")
	if tt.HasText("Downloaded") {
		t.Fatalf("a row says Downloaded with nothing downloaded: %q", tt.Texts())
	}
	rows := len(a.page.playable)
	keepDownloaded(t, a, "long-one")
	tt.Frame()
	marks := 0
	for _, text := range tt.Texts() {
		if text == "Downloaded" {
			marks++
		}
	}
	if marks != 1 || rows < 2 {
		t.Errorf("%d rows say Downloaded among %d, want exactly 1: %q", marks, rows, tt.Texts())
	}
	if !tt.HasText("unknown") || !hasLabel(tt, "Link to MusicBrainz") {
		t.Errorf("the MusicBrainz chip or link button is gone from a downloaded row: %q", tt.Texts())
	}
	if err := tt.Click("Link to MusicBrainz"); err != nil {
		t.Fatal(err)
	}
}

// A failed row stays inside the page, its Retry lined up with the header's
// Retry all rather than running past the card's edge.
func TestFailedDownloadRowsStayInsideThePage(t *testing.T) {
	a, _ := downloadingApp(t)
	failDownloads(t, a, "f1")
	tt := openDownloads(a)
	all, ok := tt.Find("Retry all")
	if !ok {
		t.Fatalf("no Retry all: %q", tt.Texts())
	}
	one, ok := tt.Find("Retry")
	if !ok {
		t.Fatalf("no Retry on the failed row: %q", tt.Texts())
	}
	if one.X+one.W > all.X+all.W+1 {
		t.Errorf("the row's Retry ends at %.0f, past Retry all at %.0f", one.X+one.W, all.X+all.W)
	}
}
