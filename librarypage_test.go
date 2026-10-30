package main

import (
	"slices"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/musicbrainz"
	"github.com/elianiva/meiro/youtube"
)

// TestLibraryPageCreatesAShowAndPlays proves the app's own playlists: a list
// is made, a YouTube Music track and a SoundCloud track are added to it, both
// show on the page with their state chips, and the whole list plays as one
// queue in the order it holds.
func TestLibraryPageCreatesAShowAndPlays(t *testing.T) {
	resetFakes()
	a, r := playbackApp(t)
	a.router.Push("/libraries")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()

	if !tt.HasText("Your playlists") {
		t.Fatalf("the playlists page is missing: %q", tt.Texts())
	}
	if err := tt.Click("New playlist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	tt.Type("Road trip")
	if err := tt.Click("Save"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()

	lists := a.libraries.List()
	if len(lists) != 1 || lists[0].Name != "Road trip" {
		t.Fatalf("the list was not made: %+v", lists)
	}
	id := lists[0].ID
	if !tt.HasText("This playlist is empty") {
		t.Fatalf("an empty list does not say so: %q", tt.Texts())
	}

	a.addItemToLibrary(id, youtube.MusicItem{VideoID: "vid-1", Title: "Yt Song", Artist: "Someone", Kind: media.KindTrack, Duration: "3:00"})
	a.addItemToLibrary(id, youtube.MusicItem{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/x/cloud-song", Title: "Cloud Song", Artist: "Cloud Artist", Kind: media.KindTrack, Duration: "3:20"})
	tt.Frame()

	if !tt.HasText("Yt Song") || !tt.HasText("Cloud Song") {
		t.Fatalf("both tracks should show: %q", tt.Texts())
	}
	if !tt.HasText("unknown") {
		t.Fatalf("the state chip is missing: %q", tt.Texts())
	}
	if lib, _ := a.libraries.Get(id); len(lib.Items) != 2 {
		t.Fatalf("the list holds %d tracks, want 2", len(lib.Items))
	}

	if err := tt.Click("Play"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.current.Key() != "vid-1" || !r.asked("vid-1") {
		t.Fatalf("playing the list started %q (resolver: %v)", a.current.Key(), r.asked("vid-1"))
	}
}

// TestAddingTheSameTrackTwiceIsReported proves a list refuses a track it
// already holds, whether the same track or the same recording from another
// provider, and says so.
func TestAddingTheSameTrackTwiceIsReported(t *testing.T) {
	a := newTestApp(t)
	lib, err := a.libraries.Create("List")
	if err != nil {
		t.Fatal(err)
	}
	ytm := youtube.MusicItem{Source: media.SourceYTM, VideoID: "vid-1", Title: "Just a Moment", Artist: "Yorushika", Duration: "4:02"}
	a.addItemToLibrary(lib.ID, ytm)
	a.addItemToLibrary(lib.ID, ytm)
	if got, _ := a.libraries.Get(lib.ID); len(got.Items) != 1 {
		t.Fatalf("the same track was added twice: %d tracks", len(got.Items))
	}
	if !strings.Contains(a.notice, "already in") {
		t.Fatalf("adding a duplicate said %q", a.notice)
	}

	a.addItemToLibrary(lib.ID, youtube.MusicItem{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/y/just-a-moment", Title: "Just a Moment (Official Video)", Artist: "yorushika", Duration: "4:03"})
	if got, _ := a.libraries.Get(lib.ID); len(got.Items) != 1 {
		t.Fatalf("the SoundCloud copy of the recording was added: %d tracks", len(got.Items))
	}
}

// TestLibraryRowMenuRenamesAndDeletes proves a list is renamed and deleted
// from its own row's menu.
func TestLibraryRowMenuRenamesAndDeletes(t *testing.T) {
	a := newTestApp(t)
	if _, err := a.libraries.Create("Old name"); err != nil {
		t.Fatal(err)
	}
	a.router.Push("/libraries")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()

	openRowMenu(t, tt, "Old name")
	if !tt.HasText("Rename") || !tt.HasText("Delete playlist") {
		t.Fatalf("the row menu is missing its actions: %q", tt.Texts())
	}
	if err := tt.Click("Rename"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	tt.Key(ui.Cmd, ui.KeyA)
	tt.Type("New name")
	if err := tt.Click("Save"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if lists := a.libraries.List(); len(lists) != 1 || lists[0].Name != "New name" {
		t.Fatalf("the list was not renamed: %+v", lists)
	}

	openRowMenu(t, tt, "New name")
	if err := tt.Click("Delete playlist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Delete"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if lists := a.libraries.List(); len(lists) != 0 {
		t.Fatalf("the list was not deleted: %+v", lists)
	}
}

// TestLibraryMenuAddsAndRemovesATrack proves a track goes into a list from its
// own menu, and comes out again from the list's row menu.
func TestLibraryMenuAddsAndRemovesATrack(t *testing.T) {
	resetFakes()
	a := newTestApp(t)
	a.router.Push("/search")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	if err := tt.Click(searchPlaceholder); err != nil {
		t.Fatal(err)
	}
	tt.Type("yor")
	tt.Key(0, ui.KeyEnter)
	tt.Frame()

	openRowMenu(t, tt, "Search Result Song")
	if err := tt.Click("Add to library"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("New playlist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	tt.Type("Saved songs")
	if err := tt.Click("Save"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()

	if lists := a.libraries.List(); len(lists) != 1 || lists[0].Name != "Saved songs" || len(lists[0].Items) != 1 {
		t.Fatalf("the track did not land in a new list: %+v", lists)
	}
	if err := tt.Click("Back to playlists"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Saved songs"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !tt.HasText("Search Result Song") {
		t.Fatalf("the list does not show its track: %q", tt.Texts())
	}

	openRowMenu(t, tt, "Search Result Song")
	if err := tt.Click("Remove from playlist"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if lists := a.libraries.List(); len(lists[0].Items) != 0 {
		t.Fatalf("the track was not removed: %+v", lists[0].Items)
	}
}

// TestLibrariesSurviveARestart proves the lists are kept on disk and read
// back by the next run.
func TestLibrariesSurviveARestart(t *testing.T) {
	path := t.TempDir() + "/libraries.json"
	first := newTestApp(t)
	first.libraries = library.New(path)
	lib, err := first.libraries.Create("Kept")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := first.libraries.AddItem(lib.ID, library.Item{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y", URL: "https://soundcloud.com/x/y", Title: "Cloud Song"}); err != nil {
		t.Fatal(err)
	}

	// A second app, as a restart builds it, reads the same file.
	second := newTestApp(t)
	second.libraries = library.New(path)
	lists := second.libraries.List()
	if len(lists) != 1 || lists[0].Name != "Kept" || len(lists[0].Items) != 1 {
		t.Fatalf("the list did not survive the restart: %+v", lists)
	}
	if lists[0].Items[0].Title != "Cloud Song" {
		t.Fatalf("the track did not survive the restart: %+v", lists[0].Items[0])
	}
}

// TestMixedQueuePlaysBothProviders proves a queue that holds a YouTube Music
// track and a SoundCloud track plays across the provider switch, each from its
// own resolver, whichever order the two are in.
func TestMixedQueuePlaysBothProviders(t *testing.T) {
	a, ytm := playbackApp(t)
	cloud := &resolved{err: map[string]error{}}
	a.soundcloudStreams = audio.NewURLs(cloud.resolve)

	yt := youtube.MusicItem{VideoID: "vid-1", Title: "Yt Song", Kind: media.KindTrack}
	sc := youtube.MusicItem{Source: media.SourceSoundCloud, URL: "https://soundcloud.com/x/cloud-song", Title: "Cloud Song", Kind: media.KindTrack}

	// YouTube Music first, SoundCloud after it.
	a.play(yt, []youtube.MusicItem{yt, sc})
	if a.current.Key() != "vid-1" || !ytm.asked("vid-1") {
		t.Fatalf("a YouTube Music track first did not play: %q", a.current.Key())
	}
	a.trackEnded()
	if a.current.Key() != sc.URL || !cloud.asked(sc.URL) {
		t.Fatalf("the SoundCloud track after a YouTube Music one did not play: %q", a.current.Key())
	}

	// SoundCloud first, YouTube Music after it.
	a.play(sc, []youtube.MusicItem{sc, yt})
	if a.current.Key() != sc.URL || !cloud.asked(sc.URL) {
		t.Fatalf("a SoundCloud track first did not play: %q", a.current.Key())
	}
	a.trackEnded()
	if a.current.Key() != "vid-1" || !ytm.asked("vid-1") {
		t.Fatalf("the YouTube Music track after a SoundCloud one did not play: %q", a.current.Key())
	}
}

// openRowMenu reveals a row's menu button and opens its menu.
func openRowMenu(t *testing.T, tt *ui.Tester, title string) {
	t.Helper()
	row, ok := tt.Find(title)
	if !ok {
		t.Fatalf("no row %q: %q", title, tt.Texts())
	}
	tt.Move(row.X+row.W/2, row.Y+row.H/2)
	tt.Frame()
	if err := tt.Click("More options for " + title); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
}

// A list says how its tracks divide between the providers, how long they run
// and how many there are; a list with nothing in it only the count.
func TestListSummaryNamesProvidersAndLength(t *testing.T) {
	items := []library.Item{
		{Source: media.SourceYTM, Key: "a", DurationMS: 4 * 60 * 1000},
		{Key: "b", DurationMS: 3 * 60 * 1000}, // no source reads as YouTube Music
		{Source: media.SourceSoundCloud, Key: "c", DurationMS: 63 * 60 * 1000},
	}
	if got, want := listSummary(items), "3 tracks · 1 hr 10 min · YouTube Music 2, SoundCloud 1"; got != want {
		t.Errorf("listSummary = %q, want %q", got, want)
	}
	if got := listSummary(nil); got != "No tracks" {
		t.Errorf("listSummary of nothing = %q, want %q", got, "No tracks")
	}
	only := []library.Item{{Source: media.SourceSoundCloud, Key: "c"}}
	if got, want := listSummary(only), "1 track · SoundCloud 1"; got != want {
		t.Errorf("listSummary of one SoundCloud track = %q, want %q", got, want)
	}
}

// Only SoundCloud's thirty second length is a preview; the same length from
// YouTube Music, or any other length, is not.
func TestOnlySoundCloudThirtySecondsIsAPreview(t *testing.T) {
	short := media.Item{Duration: "0:30"}
	if !isPreview(short, media.SourceSoundCloud) {
		t.Error("a 0:30 SoundCloud track is not marked a preview")
	}
	if isPreview(short, media.SourceYTM) {
		t.Error("a 0:30 YouTube Music track is marked a preview")
	}
	if isPreview(media.Item{Duration: "3:30"}, media.SourceSoundCloud) {
		t.Error("a full length SoundCloud track is marked a preview")
	}
}

// A Meiro playlist's page names its kind and shows what it holds by provider,
// and a row that plays a preview says so.
func TestPlaylistPageShowsProviderSummaryAndPreview(t *testing.T) {
	a := newTestApp(t)
	lib, err := a.libraries.Create("Night drive")
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range []library.Item{
		{Source: media.SourceYTM, Key: "yt1", Title: "Road", Artist: "A", DurationMS: 200000},
		{Source: media.SourceSoundCloud, Key: "https://soundcloud.com/x/y", URL: "https://soundcloud.com/x/y", Title: "Short cut", Artist: "B", DurationMS: 30000},
	} {
		if _, err := a.libraries.AddItem(lib.ID, item); err != nil {
			t.Fatal(err)
		}
	}
	a.router.Push("/libraries")
	a.libSelected = lib.ID
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	tt.Frame()
	for _, want := range []string{"MEIRO PLAYLIST", "2 tracks · 3 min · YouTube Music 1, SoundCloud 1", "preview"} {
		if !tt.HasText(want) {
			t.Errorf("the playlist page does not show %q: %q", want, tt.Texts())
		}
	}
}

// An album or a playlist can be copied into a playlist of the app's own, from
// the menu of its page and from the menu of its card, which reads the tracks
// first. The copy keeps the name and the songs, once each, and nothing is
// written to the account.
func TestDuplicateAsMeiroPlaylist(t *testing.T) {
	resetFakes()
	a := signedInApp(t)
	a.router.Push("/album/MPREb_test")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	title := a.page.detail.title
	if title == "" || len(a.page.playable) == 0 {
		t.Fatalf("the album page did not load: %q, %d songs", title, len(a.page.playable))
	}
	if err := tt.Click("More options for " + title); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Duplicate as Meiro playlist"); err != nil {
		t.Fatalf("the page menu does not offer the copy: %v: %q", err, tt.Texts())
	}
	tt.Frame()
	lists := a.libraries.List()
	if len(lists) != 1 || lists[0].Name != title {
		t.Fatalf("lists after the copy: %+v, want one named %q", lists, title)
	}
	if got, want := len(lists[0].Items), len(a.page.playable); got != want {
		t.Errorf("the copy holds %d songs, the page has %d", got, want)
	}
	for _, item := range lists[0].Items {
		if item.State != library.Unknown {
			t.Errorf("a copied song starts %q, want unknown until MusicBrainz answers", item.State)
		}
	}
	if !tt.HasText("Copied") {
		t.Errorf("the copy was not announced: %q", tt.Texts())
	}
}

// Copying a card's collection reads its page, then fills the new playlist.
func TestDuplicateACardAsMeiroPlaylist(t *testing.T) {
	resetFakes()
	a := signedInApp(t)
	a.router.Push("/home")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	if err := tt.RightClick("Deep Focus"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Duplicate as Meiro playlist"); err != nil {
		t.Fatalf("the card menu does not offer the copy: %v: %q", err, tt.Texts())
	}
	pollUntil(t, a, "the copy", func() bool { tt.Frame(); return len(a.libraries.List()) == 1 })
	list := a.libraries.List()[0]
	if list.Name != "Deep Focus" || len(list.Items) == 0 {
		t.Errorf("the copy of the card: %+v", list)
	}
}

// The songs of a copy are tied to MusicBrainz in the background, one after the
// other, as it answers: the song it is sure of becomes matched, with its
// artist, and the rest stay unknown.
func TestDuplicateLinksTheCopiedSongsInTheBackground(t *testing.T) {
	resetFakes()
	a := signedInApp(t)
	a.run = func(work func()) { work() }
	a.router.Push("/album/MPREb_test")
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()
	// The album's songs name their artist only in their byline; the copy
	// reads the credited artist, so give them one.
	songs := slices.Clone(a.page.playable)
	for i := range songs {
		songs[i].Artist = "Someone"
	}
	// And a song MusicBrainz will have no answer for.
	songs = append(songs, youtube.MusicItem{VideoID: "vid-unmatched", Title: "Unmatched", Artist: "Someone", Kind: media.KindTrack, Duration: "2:00"})
	if durationMillis(songs[0].Duration) == 0 {
		t.Fatalf("the album does not give the test a song with an artist and a length: %+v", songs)
	}
	first := songs[0]
	fakeMB(a).found = map[string][]musicbrainz.RecordingMatch{
		first.Title: {{ID: "rec-first", Title: first.Title, Artist: first.Artist, ArtistID: "art-first", LengthMS: int(durationMillis(first.Duration)), Score: 100}},
	}
	a.duplicateAsMeiro("Copy", "https://img/cover.jpg", songs)
	pollUntil(t, a, "the first song to be linked", func() bool {
		list := a.libraries.List()
		return len(list) == 1 && list[0].Items[0].State == library.Matched
	})
	list := a.libraries.List()[0]
	if list.Items[0].MBID != "rec-first" || list.Items[0].ArtistMBID != "art-first" {
		t.Errorf("the first song was not linked with its artist: %+v", list.Items[0])
	}
	for _, item := range list.Items[1:] {
		if item.State != library.Unknown {
			t.Errorf("a song MusicBrainz had no answer for is %q, want unknown: %+v", item.State, item)
		}
	}
}

// A song keeps its own cover, and the cover of the album it came from stands in
// when it has none; a copied album keeps its picture as the playlist's own.
func TestCoversAreKeptWithSongsAndCopies(t *testing.T) {
	own := libraryItem(youtube.MusicItem{VideoID: "a", Title: "A", Thumbnail: "https://img/own.jpg"}, "https://img/album.jpg")
	bare := libraryItem(youtube.MusicItem{VideoID: "b", Title: "B"}, "https://img/album.jpg")
	if got := musicItem(own).Thumbnail; got != "https://img/own.jpg" {
		t.Errorf("a song with a cover shows %q, want its own", got)
	}
	if got := musicItem(bare).Thumbnail; got != "https://img/album.jpg" {
		t.Errorf("a song with no cover shows %q, want the album's", got)
	}

	resetFakes()
	a := signedInApp(t)
	songs := []youtube.MusicItem{{VideoID: "x", Title: "X", Artist: "Y", Kind: media.KindTrack, Duration: "2:00"}}
	a.duplicateAsMeiro("Album copy", "https://img/cover.jpg", songs)
	lists := a.libraries.List()
	if len(lists) != 1 || lists[0].Cover != "https://img/cover.jpg" || lists[0].Picture() != "https://img/cover.jpg" {
		t.Fatalf("the copy did not keep the cover: %+v", lists)
	}
	if lists[0].Items[0].AlbumThumbnail != "https://img/cover.jpg" {
		t.Errorf("the copied song did not keep the album cover: %+v", lists[0].Items[0])
	}
	// A list made by hand takes the first cover a song has.
	plain := library.Library{Items: []library.Item{{Title: "no cover"}, {Title: "with", AlbumThumbnail: "https://img/a.jpg"}}}
	if plain.Picture() != "https://img/a.jpg" {
		t.Errorf("a list's picture = %q, want the first cover a song has", plain.Picture())
	}
}
