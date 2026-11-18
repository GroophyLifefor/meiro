package main

import (
	"context"
	"os"
	"slices"
	"sync"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/media"
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
