package settings

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWriterKeepsTheLastStateAndLoadRepairsIt(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	w := &Writer{Path: path}
	s := Defaults()
	s.Mode = "dark"
	s.DiscordPresence = true
	s.TrayMark = TrayMarkOMark
	w.Save(&s)
	s.Seed = "#d81b78"
	w.Save(&s)
	s.Seed = "not a colour" // a change after the last save is not written
	w.Wait()

	got := Load(path)
	if got.Mode != "dark" || got.Seed != "#d81b78" || !got.DiscordPresence || got.TrayMark != TrayMarkOMark {
		t.Errorf("loaded %+v", got)
	}

	// A file with values out of range comes back with the defaults for them,
	// and a field it lacks takes its default.
	if err := os.WriteFile(path, []byte(`{"volume":500,"seed":"red","youtubeCacheMB":-1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	got, want := Load(path), Defaults()
	if got.Volume != want.Volume || got.Seed != want.Seed || got.YouTubeCacheMB != want.YouTubeCacheMB || got.TrayMark != want.TrayMark || !got.AutoPlay {
		t.Errorf("repaired %+v", got)
	}
	if err := os.WriteFile(path, []byte(`{"trayMark":"unexpected"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := Load(path).TrayMark; got != want.TrayMark {
		t.Errorf("invalid tray mark = %q, want default %q", got, want.TrayMark)
	}
}

func TestClampZoomKeepsTheInterfaceReadable(t *testing.T) {
	for _, tc := range []struct{ in, want int }{
		{0, DefaultZoom}, // a settings file from before the field
		{50, MinZoom},
		{120, 120},
		{500, MaxZoom},
	} {
		if got := ClampZoom(tc.in); got != tc.want {
			t.Errorf("ClampZoom(%d) = %d, want %d", tc.in, got, tc.want)
		}
	}

	// A file's zoom comes back within the range, and one without the field
	// takes the default.
	path := filepath.Join(t.TempDir(), "settings.json")
	if err := os.WriteFile(path, []byte(`{"zoom":900}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := Load(path).Zoom; got != MaxZoom {
		t.Errorf("loaded zoom = %d, want %d", got, MaxZoom)
	}
	if err := os.WriteFile(path, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := Load(path).Zoom; got != DefaultZoom {
		t.Errorf("zoom of a file without one = %d, want %d", got, DefaultZoom)
	}
}

func TestRememberKeepsRecentSearchesUniqueAndBounded(t *testing.T) {
	var s Settings
	for _, q := range []string{"a", "b", "A", "c", "d", "e", "f", "g", "h", "i"} {
		s.Remember(q)
	}
	if len(s.Recent) != maxRecent || s.Recent[0] != "i" {
		t.Errorf("recent = %v", s.Recent)
	}
	s.Forget("i")
	if s.Recent[0] != "h" {
		t.Errorf("forget left %v", s.Recent)
	}
}

// A file from before the caches shared a measure becomes sizes: a YouTube Music
// cache that kept songs takes the default size, one that was off stays off,
// and a SoundCloud cache switched off is a size of zero.
func TestOlderCacheSettingsBecomeSizes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	for _, tc := range []struct {
		name, file     string
		wantYT, wantSC int
	}{
		{"songs on", `{"cacheSongs":25,"soundCloudCache":true,"soundCloudCacheMB":256}`, DefaultCacheMB, 256},
		{"songs off", `{"cacheSongs":0}`, 0, DefaultCacheMB},
		{"soundcloud off", `{"soundCloudCache":false,"soundCloudCacheMB":256}`, DefaultCacheMB, 0},
		{"new sizes win", `{"cacheSongs":0,"youtubeCacheMB":300,"soundCloudCacheMB":0}`, 300, 0},
		{"nothing written", `{}`, DefaultCacheMB, DefaultCacheMB},
	} {
		if err := os.WriteFile(path, []byte(tc.file), 0o600); err != nil {
			t.Fatal(err)
		}
		got := Load(path)
		if got.YouTubeCacheMB != tc.wantYT || got.SoundCloudCacheMB != tc.wantSC {
			t.Errorf("%s: youtube %d MB, soundcloud %d MB; want %d and %d", tc.name, got.YouTubeCacheMB, got.SoundCloudCacheMB, tc.wantYT, tc.wantSC)
		}
	}
}
