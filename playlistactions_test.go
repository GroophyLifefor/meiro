package main

import (
	"context"
	"os"
	"slices"
	"sync"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// fakeFetches stands in for the audio download: it writes a small file for the
// track and remembers which tracks were asked for, in order.
type fakeFetches struct {
	mu   sync.Mutex
	keys []string
}

func (f *fakeFetches) fetch(_ context.Context, _ *audio.URLs, key, base string) (string, int64, error) {
	f.mu.Lock()
	f.keys = append(f.keys, key)
	f.mu.Unlock()
	if err := os.WriteFile(base+".webm", []byte("audio of "+key), 0o600); err != nil {
		return "", 0, err
	}
	return ".webm", 10, nil
}

func (f *fakeFetches) asked() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.keys)
}

// downloadingApp is a signed-in app that keeps its downloads in a temporary
// folder and downloads through a fake that never touches the network.
func downloadingApp(t *testing.T) (*app, *fakeFetches) {
	t.Helper()
	resetFakes()
	a := signedInApp(t)
	a.downloads = newDownloadStore(t)
	fake := &fakeFetches{}
	a.downloadAudio = fake.fetch
	return a, fake
}

// keepDownloaded records the tracks as already downloaded.
func keepDownloaded(t *testing.T, a *app, ids ...string) {
	t.Helper()
	for _, id := range ids {
		addDownload(t, a.downloads, media.SourceYTM, downloadTestItem(id))
	}
}

// loadedPage opens a page and draws frames until it has its songs.
func loadedPage(a *app, path string) *ui.Tester {
	a.router.Push(path)
	tt := ui.NewTester(a.view, 1000, 700)
	for i := 0; i < 20 && len(a.page.playable) == 0; i++ {
		tt.Frame()
	}
	tt.Frame()
	return tt
}

// The hero of a playlist and of an album has a More options button, and its
// menu offers the copy and the download. The artwork still opens the menu.
func TestHeroMoreOptionsOffersDuplicateAndDownload(t *testing.T) {
	for _, page := range []struct{ path, title, download string }{
		{"/playlist/VLPL_long", "Long Mix", "Download playlist"},
		{"/album/MPREb_test", "Deep Focus", "Download album"},
	} {
		t.Run(page.title, func(t *testing.T) {
			a, _ := downloadingApp(t)
			tt := loadedPage(a, page.path)
			if err := tt.Click("More options for " + page.title); err != nil {
				t.Fatalf("the hero has no More options button: %v: %q", err, tt.Texts())
			}
			tt.Frame()
			for _, want := range []string{"Duplicate as Meiro playlist", page.download} {
				if !tt.HasText(want) {
					t.Errorf("the menu does not offer %q: %q", want, tt.Texts())
				}
			}
			a.menu.close()
			tt.Frame()
			if err := tt.Click("Artwork menu for " + page.title); err != nil {
				t.Fatal(err)
			}
			tt.Frame()
			if !tt.HasText(page.download) {
				t.Errorf("the artwork no longer opens the menu: %q", tt.Texts())
			}
		})
	}
}

// A page that kept only the start of a long playlist still copies all of it.
func TestDuplicatePageCopiesTheWholePlaylist(t *testing.T) {
	a, _ := downloadingApp(t)
	longRestRefused.Store(true)
	tt := loadedPage(a, "/playlist/VLPL_long")
	if got := len(a.page.playable); got != 2 {
		t.Fatalf("the page holds %d songs, want only the 2 of its first page", got)
	}
	longRestRefused.Store(false)
	if err := tt.Click("More options for Long Mix"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Duplicate as Meiro playlist"); err != nil {
		t.Fatal(err)
	}
	pollUntil(t, a, "the copy", func() bool { tt.Frame(); return len(a.libraries.List()) == 1 })
	list := a.libraries.List()[0]
	var keys []string
	for _, item := range list.Items {
		keys = append(keys, item.Key)
	}
	if want := []string{"long-one", "long-two", "long-three", "long-four"}; list.Name != "Long Mix" || !slices.Equal(keys, want) {
		t.Errorf("the copy %q holds %v, want %v", list.Name, keys, want)
	}
}

// Download playlist on a YouTube Music page queues the songs that are not kept
// yet, all of them and not only the loaded ones, and says how many.
func TestDownloadPlaylistFromTheHero(t *testing.T) {
	a, fake := downloadingApp(t)
	longRestRefused.Store(true)
	tt := loadedPage(a, "/playlist/VLPL_long")
	longRestRefused.Store(false)
	keepDownloaded(t, a, "long-two")
	if err := tt.Click("More options for Long Mix"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Download playlist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if want := "Downloading 3 songs from “Long Mix”"; !tt.HasText(want) {
		t.Errorf("the download was not announced as %q: %q", want, tt.Texts())
	}
	pollUntil(t, a, "the downloads", func() bool { tt.Frame(); return len(fake.asked()) == 3 })
	if got, want := fake.asked(), []string{"long-one", "long-three", "long-four"}; !slices.Equal(got, want) {
		t.Errorf("downloaded %v, want %v", got, want)
	}

	// With everything kept, it says so and downloads nothing more.
	if err := tt.Click("More options for Long Mix"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Download playlist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if want := "All songs of “Long Mix” are already downloaded"; !tt.HasText(want) {
		t.Errorf("the notice %q is missing: %q", want, tt.Texts())
	}
	if got := len(fake.asked()); got != 3 {
		t.Errorf("a second download asked for %d songs in all, want 3", got)
	}
}

// An album's menu says Download album and downloads its songs.
func TestDownloadAlbumFromTheHero(t *testing.T) {
	a, fake := downloadingApp(t)
	tt := loadedPage(a, "/album/MPREb_test")
	if err := tt.Click("More options for Deep Focus"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Download album"); err != nil {
		t.Fatal(err)
	}
	pollUntil(t, a, "the download", func() bool { tt.Frame(); return len(fake.asked()) == 1 })
	if got := fake.asked(); !slices.Equal(got, []string{"vid-3"}) {
		t.Errorf("downloaded %v, want the album's one song", got)
	}
}

// ownList makes one of the app's own playlists of the given YouTube Music
// songs.
func ownList(t *testing.T, a *app, name string, ids ...string) library.Library {
	t.Helper()
	lib, err := a.libraries.Create(name)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range ids {
		if _, err := a.libraries.AddItem(lib.ID, library.Item{Source: media.SourceYTM, Key: id, Title: "Track " + id, DurationMS: 180000}); err != nil {
			t.Fatal(err)
		}
	}
	return a.libraries.List()[0]
}

// A Meiro playlist is downloaded whole from its page.
func TestDownloadPlaylistFromTheMeiroPlaylistPage(t *testing.T) {
	a, fake := downloadingApp(t)
	lib := ownList(t, a, "Night drive", "n1", "n2", "n3")
	keepDownloaded(t, a, "n1")
	a.router.Push("/libraries")
	a.libSelected = lib.ID
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	if err := tt.Click("Download playlist"); err != nil {
		t.Fatalf("the playlist page has no download button: %v: %q", err, tt.Texts())
	}
	tt.Frame()
	if want := "Downloading 2 songs from “Night drive”"; !tt.HasText(want) {
		t.Errorf("the download was not announced as %q: %q", want, tt.Texts())
	}
	pollUntil(t, a, "the downloads", func() bool { tt.Frame(); return len(fake.asked()) == 2 })
	if got, want := fake.asked(), []string{"n2", "n3"}; !slices.Equal(got, want) {
		t.Errorf("downloaded %v, want %v", got, want)
	}
}

// The menu of a row of the Playlists page downloads that playlist, between
// Rename and Delete playlist.
func TestDownloadPlaylistFromThePlaylistsRowMenu(t *testing.T) {
	a, fake := downloadingApp(t)
	ownList(t, a, "Night drive", "n1", "n2")
	keepDownloaded(t, a, "n1", "n2")
	a.router.Push("/libraries")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	openRowMenu(t, tt, "Night drive")
	texts := tt.Texts()
	rename, download, remove := slices.Index(texts, "Rename"), slices.Index(texts, "Download playlist"), slices.Index(texts, "Delete playlist")
	if rename < 0 || download < rename || remove < download {
		t.Fatalf("the row menu is not Rename, Download playlist, Delete playlist: %q", texts)
	}
	if err := tt.Click("Download playlist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if want := "All songs of “Night drive” are already downloaded"; !tt.HasText(want) {
		t.Errorf("the notice %q is missing: %q", want, tt.Texts())
	}
	if got := fake.asked(); len(got) != 0 {
		t.Errorf("downloaded %v, want nothing: every song was kept", got)
	}
}

// A whole playlist never runs more than two downloads at once: the rest wait,
// show on the Downloads page as waiting, and start as places free up. Episodes
// and songs already kept or downloading are left out.
func TestBulkDownloadsRunTwoAtATime(t *testing.T) {
	a, fake := downloadingApp(t)
	// Downloads run when the test says so, so that what is in flight can be
	// counted.
	var inFlight []func()
	a.run = func(work func()) { inFlight = append(inFlight, work) }
	peak := 0
	songs := []youtube.MusicItem{
		downloadTestItem("d1"), downloadTestItem("d2"), downloadTestItem("d3"),
		downloadTestItem("d4"), downloadTestItem("d5"), downloadTestItem("d1"),
		{VideoID: "ep-1", Title: "An episode", Kind: media.KindEpisode},
	}
	keepDownloaded(t, a, "d5")

	a.downloadCollection("Mix", songs)
	if want := "Downloading 4 songs from “Mix”"; a.notice != want {
		t.Errorf("notice = %q, want %q", a.notice, want)
	}
	count := func(status downloadStatus) int {
		n := 0
		for _, st := range a.downloadStates {
			if st.status == status {
				n++
			}
		}
		return n
	}
	if count(downloadRunning) != 2 || count(downloadQueued) != 2 || len(inFlight) != 2 {
		t.Fatalf("running %d, queued %d, in flight %d; want 2, 2, 2", count(downloadRunning), count(downloadQueued), len(inFlight))
	}
	if _, ok := a.downloadStates[audioKey(songs[6])]; ok {
		t.Error("an episode was queued")
	}

	// The Downloads page lists the waiting ones.
	a.router.Push("/downloads")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	if !tt.HasText("Track d3") || !tt.HasText("Waiting to download") {
		t.Errorf("the Downloads page does not show the waiting downloads: %q", tt.Texts())
	}

	// Asking again changes nothing: they are running or waiting already.
	a.downloadCollection("Mix", songs)
	if want := "The songs of “Mix” are already downloading"; a.notice != want {
		t.Errorf("notice = %q, want %q", a.notice, want)
	}
	if len(inFlight) != 2 || count(downloadQueued) != 2 {
		t.Fatalf("asking again changed the queue: in flight %d, queued %d", len(inFlight), count(downloadQueued))
	}

	for len(inFlight) > 0 {
		peak = max(peak, len(inFlight))
		work := inFlight[0]
		inFlight = inFlight[1:]
		work()
	}
	if peak > maxDownloads {
		t.Errorf("%d downloads ran at once, want at most %d", peak, maxDownloads)
	}
	if got, want := fake.asked(), []string{"d1", "d2", "d3", "d4"}; !slices.Equal(got, want) {
		t.Errorf("downloaded %v, want %v in order", got, want)
	}
	if a.downloadsRunning != 0 || len(a.downloadQueue) != 0 || a.hasPendingDownloads() {
		t.Errorf("downloads are left over: running %d, queued %d", a.downloadsRunning, len(a.downloadQueue))
	}
	for _, id := range []string{"d1", "d2", "d3", "d4", "d5"} {
		if !a.isDownloaded(downloadTestItem(id)) {
			t.Errorf("%s was not kept", id)
		}
	}
}

// A single download takes the same queue, and a failed one frees its place.
func TestFailedDownloadFreesItsPlace(t *testing.T) {
	a, _ := downloadingApp(t)
	var inFlight []func()
	a.run = func(work func()) { inFlight = append(inFlight, work) }
	a.downloadAudio = func(context.Context, *audio.URLs, string, string) (string, int64, error) {
		return "", 0, os.ErrPermission
	}
	for _, id := range []string{"f1", "f2", "f3"} {
		a.startDownload(downloadTestItem(id))
	}
	if a.downloadsRunning != 2 || len(a.downloadQueue) != 1 {
		t.Fatalf("running %d, queued %d; want 2 and 1", a.downloadsRunning, len(a.downloadQueue))
	}
	inFlight[0]()
	if a.downloadsRunning != 2 || len(a.downloadQueue) != 0 || len(inFlight) != 3 {
		t.Errorf("after a failure: running %d, queued %d, in flight %d; want 2, 0, 3", a.downloadsRunning, len(a.downloadQueue), len(inFlight))
	}
}
