package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/internal/mblinks"
	meirosync "github.com/elianiva/meiro/internal/sync"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// hostname is the machine's name, which a play and a download report as the
// device they happened on.
func hostname() string {
	name, err := os.Hostname()
	if err != nil || strings.TrimSpace(name) == "" {
		return "unknown"
	}
	return name
}

const (
	// syncCallTimeout bounds one request to the server.
	syncCallTimeout = 30 * time.Second
	// syncAttempts is how many times a delivery that fails for a reason that
	// may pass is tried, with sync.Backoff between tries, before it waits for
	// the next one.
	syncAttempts = 4
	// syncInterval is how often records that wait are tried again while the
	// app runs.
	syncInterval = 5 * time.Minute
)

// syncer is the app's connection to the Meiro server: the session token, the
// records waiting for it, and the work in flight. It belongs to the main
// thread, apart from the outbox, which locks itself. The zero value keeps
// nothing and sends nothing.
type syncer struct {
	// token is the session token, empty until one is read or signed in for.
	token string
	// outbox holds the records the server has not taken yet, apart from the
	// settings, on disk.
	outbox *meirosync.Outbox
	// tokens keeps the session token, apart from the settings.
	tokens meirosync.TokenStore

	// load reads the saved token, login signs in, and push delivers what
	// waits.
	load  task
	login task
	push  task
	pull  task
	// changes reads the lists the server holds, and pullAgain says a read was
	// asked for while one was in flight, so the answer to that one, which may
	// predate what asked, is followed by another.
	changes   task
	pullAgain bool
	// events reads the plays of every device of the account, with its own
	// cursor and its own pullEventsAgain.
	events          task
	pullEventsAgain bool

	// err is why signing in or syncing last failed, for the card.
	err string
	// email and password are what the sign in form holds; the password is
	// never saved.
	email, password string
	// urlText is the server address the form holds.
	urlText string
}

func newSyncer() syncer {
	return syncer{outbox: meirosync.Open("")}
}

// openSync opens the files the sync keeps: the records waiting for the server
// and the session token, both apart from the settings.
func (a *app) openSync(outboxPath, tokenPath string) {
	a.sync.outbox = meirosync.Open(outboxPath)
	a.sync.tokens = meirosync.TokenStore{Path: tokenPath}
	a.sync.urlText = a.settings.SyncURL
}

// loadSyncToken reads the saved session token off the main thread, when an
// account is signed in, and sends what is waiting.
func (a *app) loadSyncToken() {
	s := &a.sync
	if a.settings.SyncEmail == "" || s.token != "" || s.load.busy() || s.tokens.Path == "" {
		return
	}
	store := s.tokens
	startTask(a, &s.load, noTimeout, func(context.Context) (string, error) {
		return store.Load()
	}, func(token string, err error) {
		if err != nil {
			log.Printf("sync: reading the saved session: %v", err)
			return
		}
		s.token = token
		a.pullLinks()
		a.pullChanges()
		a.pullEvents()
		a.syncNow()
	})
}

// syncNow sends what waits, off the main thread, if a delivery is not already
// on its way and an account is signed in. A failure leaves everything in the
// queue for the next try.
func (a *app) syncNow() {
	s := &a.sync
	if !a.settings.SyncEnabled || s.token == "" || s.push.busy() {
		return
	}
	client := &meirosync.Client{BaseURL: a.settings.SyncURL, Token: s.token}
	outbox, snapshot, sleep := s.outbox, a.syncSnapshot(), a.sleep
	startTask(a, &s.push, noTimeout, func(ctx context.Context) (int, error) {
		snapshot.queue(outbox)
		delivered := 0
		for tries := 1; ; tries++ {
			n, err := meirosync.Push(ctx, client, outbox)
			delivered += n
			if err == nil {
				return delivered, nil
			}
			if ctx.Err() != nil || !meirosync.Retryable(err) || tries >= syncAttempts {
				return delivered, err
			}
			if sleep(ctx, meirosync.Backoff(tries)) != nil {
				return delivered, ctx.Err()
			}
		}
	}, func(delivered int, err error) {
		if err != nil {
			log.Printf("sync: %v", err)
			s.err = syncMessage(err)
			return
		}
		s.err = ""
		if a.settings.SyncLastAt.IsZero() {
			// A device whose first delivery is this one has never left stale
			// rows on the server, so it has nothing to migrate: see pullChanges.
			a.settings.SyncTwoWay = true
		}
		a.settings.SyncLastAt = time.Now()
		a.saveSettings()
		// What another device changed meanwhile is read once ours is there.
		a.pullChanges()
		a.pullEvents()
	})
}

// syncTrack is the server's reference to a track, named by the source the
// track plays from now and that provider's own id: a video ID for YouTube
// Music, a page URL for SoundCloud. A track two providers hold is sent as the
// one the user chose, not the first that holds it.
func syncTrack(item youtube.MusicItem, title, artist string, durationMS int64) meirosync.Track {
	return meirosync.TrackFor(resolveSource(item), streamKey(item), itemLink(item), title, artist, durationMS)
}

// libraryServerID is the identity the server knows a list by: the one it gave
// a list that came from it, and otherwise the one derived from the local ID.
func libraryServerID(localID, serverID string) string {
	if serverID != "" {
		return serverID
	}
	return meirosync.LibraryID(localID)
}

// syncSnapshot is the local playlists, their items and the downloads, ready to
// queue for the server.
type syncSnapshot struct {
	libraries []meirosync.Library
	items     []meirosync.LibraryItems
	downloads []meirosync.Download
}

// syncSnapshot reads the local stores. It runs off the main thread; the stores
// lock themselves.
func (a *app) syncSnapshot() syncSnapshot {
	var snap syncSnapshot
	for _, lib := range a.libraries.List() {
		items := make([]meirosync.LibraryItem, 0, len(lib.Items)+len(lib.Removed))
		for i, item := range lib.Items {
			track := syncTrack(musicItem(item), item.Title, item.Artist, item.DurationMS)
			track.Thumbnail, track.AlbumThumbnail = item.Thumbnail, item.AlbumThumbnail
			items = append(items, meirosync.LibraryItem{Track: track, Position: i, UpdatedAt: item.UpdatedAt})
		}
		// A track taken out is sent as deleted, at the time it was taken out,
		// or the server would keep it and send it back to every device.
		for _, gone := range lib.Removed {
			items = append(items, meirosync.LibraryItem{
				Track:     meirosync.TrackFor(gone.Source, gone.Key, gone.URL, gone.Title, gone.Artist, 0),
				UpdatedAt: gone.RemovedAt,
				Deleted:   true,
			})
		}
		id := libraryServerID(lib.ID, lib.ServerID)
		snap.libraries = append(snap.libraries, meirosync.Library{ID: id, Kind: "custom", Name: lib.Name, Cover: lib.Cover, UpdatedAt: lib.UpdatedAt})
		snap.items = append(snap.items, meirosync.LibraryItems{LibraryID: id, Items: items})
	}
	for _, gone := range a.libraries.Deleted() {
		snap.libraries = append(snap.libraries, meirosync.Library{
			ID: libraryServerID(gone.ID, gone.ServerID), Kind: "custom", Name: gone.Name, UpdatedAt: gone.DeletedAt, Deleted: true,
		})
	}
	for _, rec := range a.downloads.List() {
		updated := rec.DownloadedAt
		var last *time.Time
		if !rec.LastListenedAt.IsZero() {
			at := rec.LastListenedAt
			last = &at
			if at.After(updated) {
				updated = at
			}
		}
		snap.downloads = append(snap.downloads, meirosync.Download{
			Track:          syncTrack(downloadMusicItem(rec), rec.Title, rec.Artist, 0),
			Device:         a.device,
			SizeBytes:      rec.SizeBytes,
			LastListenedAt: last,
			UpdatedAt:      updated,
		})
	}
	return snap
}

// queue puts the snapshot in the outbox, replacing what was pending there for
// the same records.
func (s syncSnapshot) queue(o *meirosync.Outbox) {
	o.AddLibraries(s.libraries)
	for _, group := range s.items {
		o.AddLibraryItems(group.LibraryID, group.Items)
	}
	o.SetDownloads(s.downloads)
}

// signInSync signs in to the server with the account the form holds, keeps the
// token apart from the settings, and turns syncing on.
func (a *app) signInSync() {
	s := &a.sync
	email, password := strings.TrimSpace(s.email), s.password
	if email == "" || password == "" {
		s.err = "Enter your email and password."
		return
	}
	url := strings.TrimSpace(a.settings.SyncURL)
	if url == "" {
		s.err = "Enter the server address."
		return
	}
	if s.login.busy() {
		return
	}
	client, store := &meirosync.Client{BaseURL: url}, s.tokens
	startTask(a, &s.login, syncCallTimeout, func(ctx context.Context) (string, error) {
		token, err := client.Login(ctx, email, password)
		if err != nil {
			return "", err
		}
		if store.Path != "" {
			if err := store.Save(token); err != nil {
				return "", err
			}
		}
		return token, nil
	}, func(token string, err error) {
		if err != nil {
			s.err = syncMessage(err)
			return
		}
		s.token, s.err, s.password = token, "", ""
		s.urlText = url
		a.settings.SyncURL, a.settings.SyncEmail, a.settings.SyncEnabled = url, email, true
		a.saveSettings()
		a.notice = "Signed in to sync"
		a.pullLinks()
		a.pullChanges()
		a.pullEvents()
		a.syncNow()
	})
}

// signOutSync forgets the account: its token leaves the disk, syncing stops,
// and the records that wait are kept for when it is signed in again.
func (a *app) signOutSync() {
	s := &a.sync
	s.load.stop()
	s.login.stop()
	s.push.stop()
	s.pull.stop()
	s.changes.stop()
	s.pullAgain = false
	s.events.stop()
	s.pullEventsAgain = false
	s.token, s.err = "", ""
	a.settings.SyncEmail, a.settings.SyncEnabled = "", false
	a.settings.SyncCursor, a.settings.SyncCursorURL, a.settings.SyncCursorEmail = 0, "", ""
	a.settings.EventCursor, a.settings.EventCursorURL, a.settings.EventCursorEmail = 0, "", ""
	a.saveSettings()
	store := s.tokens
	spawn(a, func() error {
		if store.Path == "" {
			return nil
		}
		return store.Delete()
	}, func(err error) {
		if err != nil {
			log.Printf("sync: removing the saved session: %v", err)
		}
	})
}

// setSyncURL keeps the server address the form holds.
func (a *app) setSyncURL() {
	url := strings.TrimSpace(a.sync.urlText)
	if url == "" {
		url = meirosync.DefaultURL
	}
	a.sync.urlText = url
	a.settings.SyncURL = url
	a.saveSettings()
}

// syncMessage turns a failure into one line for the card.
func syncMessage(err error) string {
	var api *meirosync.APIError
	if errors.As(err, &api) && api.Status == 401 {
		return "The server did not accept the email and password."
	}
	return "Could not reach the server: " + firstLine(err.Error())
}

// meiroAccountCard is the main account: where the user signs in to the Meiro
// server, chooses its address, sends what waits and turns sending off. It is
// apart from any provider's account.
func (a *app) meiroAccountCard(c *ui.Context) {
	s := &a.sync
	sc := m3.Of(c).Scheme
	a.settingsCard(c, "Meiro account", m3.IconPerson, func() {
		m3.Text(c, m3.BodyMedium, "Your main account. What you play, your playlists and your downloads are sent to your Meiro server. Your playlists are kept in step both ways, so a change on another device reaches this one, and the plays of your other devices count here too; downloads are only sent.").TextColor(sc.OnSurfaceVariant)
		signedIn := a.settings.SyncEmail != ""
		if signedIn {
			a.syncAccount(c)
		}
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Server address", "The Meiro server this device sends to.").Grow(1)
			input := ui.TextInputBase(c.Key("sync-url"), &s.urlText).
				Width(280).Height(48).Shrink(0).Padding(12).Radius(m3.Medium).
				Background(sc.SurfaceContainerHigh).TextColor(sc.OnSurface).FontSize(16).
				Label("Server address").Placeholder(meirosync.DefaultURL)
			if input.Submitted() {
				a.setSyncURL()
			}
		})
		if !signedIn {
			a.syncSignIn(c)
		}
		if s.load.busy() || s.login.busy() || s.push.busy() || s.changes.busy() || s.events.busy() {
			ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
				m3.LoadingIndicator(c, 32, false)
				m3.Text(c, m3.BodyMedium, "Talking to the server.").TextColor(sc.OnSurfaceVariant)
			})
		}
		if s.err != "" {
			m3.Text(c, m3.BodyMedium, s.err).TextColor(sc.Error).MaxLines(3)
		}
	})
}

// syncSignIn is the form of an account not signed in yet.
func (a *app) syncSignIn(c *ui.Context) {
	s := &a.sync
	sc := m3.Of(c).Scheme
	ui.Column(c).Gap(12).Children(func() {
		settingLabel(c, "Sign in", "Sign in to send what you played, your playlists and your downloads to the server.")
		ui.Row(c).Gap(12).AlignItems(ui.Center).Wrap().Children(func() {
			ui.TextInputBase(c.Key("sync-email"), &s.email).
				Width(240).Height(48).Shrink(0).Padding(12).Radius(m3.Medium).
				Background(sc.SurfaceContainerHigh).TextColor(sc.OnSurface).FontSize(16).
				Label("Email").Placeholder("you@example.com")
			ui.TextInputBase(c.Key("sync-password"), &s.password).
				Width(200).Height(48).Shrink(0).Padding(12).Radius(m3.Medium).
				Background(sc.SurfaceContainerHigh).TextColor(sc.OnSurface).FontSize(16).
				Label("Password").Password()
			if m3.Button(c, m3.ButtonSpec{Label: "Sign in to sync", Kind: m3.Filled, Size: m3.Medium56, Key: "sync-sign-in", Disabled: s.login.busy()}).Clicked() {
				a.signInSync()
			}
		})
	})
}

// syncAccount is the card of an account that is signed in: who it is, what
// waits, when it last synced, and the switch that pauses sending.
func (a *app) syncAccount(c *ui.Context) {
	s := &a.sync
	ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
		body := "Signed in"
		if !a.settings.SyncLastAt.IsZero() {
			body += " · last synced " + a.settings.SyncLastAt.Format("2006-01-02 15:04")
		}
		if n := s.outbox.EventCount(); n > 0 {
			body += fmt.Sprintf(" · %d waiting to be sent", n)
		}
		settingLabel(c, a.settings.SyncEmail, body).Grow(1)
		if m3.Button(c, m3.ButtonSpec{Label: "Sync now", Kind: m3.Tonal, Size: m3.Medium56, Key: "sync-now", Disabled: s.push.busy()}).Clicked() {
			a.syncNow()
		}
		if m3.Button(c, m3.ButtonSpec{Label: "Sign out", Kind: m3.Outlined, Size: m3.Medium56, Key: "sync-sign-out"}).Clicked() {
			a.signOutSync()
		}
	})
	ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
		settingLabel(c, "Send automatically", "Turn off to stop sending; what waits is kept for when it is on again.").Grow(1)
		on := a.settings.SyncEnabled
		if m3.Switch(c, &on, "Sync").Changed() {
			a.settings.SyncEnabled = on
			a.saveSettings()
			if on {
				a.syncNow()
			}
		}
	})
}

// linksSyncing says whether links are kept in step with the server: an account
// is signed in and sending is on.
func (a *app) linksSyncing() bool {
	return a.settings.SyncEmail != "" && a.settings.SyncEnabled
}

// queueLinks files changed links to send, and sends them at once when it can.
func (a *app) queueLinks(links meirosync.Links) {
	if !a.linksSyncing() || links.Empty() {
		return
	}
	a.sync.outbox.AddLinks(links)
	a.syncNow()
}

// queueSongLink, queueArtistLink and queueAltLink file one changed link.
func (a *app) queueSongLink(song mblinks.Song) {
	a.queueLinks(meirosync.Links{Songs: []meirosync.SongLink{songLinkOut(song)}})
}

func (a *app) queueArtistLink(artist mblinks.Artist) {
	a.queueLinks(meirosync.Links{Artists: []meirosync.ArtistLink{artistLinkOut(artist)}})
}

func (a *app) queueAltLink(alt mblinks.Alt) {
	a.queueLinks(meirosync.Links{Alternates: []meirosync.AltLink{altLinkOut(alt)}})
}

// songLinkOut, artistLinkOut and altLinkOut put a link in the shape the server
// takes.
func songLinkOut(s mblinks.Song) meirosync.SongLink {
	out := meirosync.SongLink{
		Provider: mblinks.Clean(s.Provider), ProviderTrackID: s.ID, State: s.State, MBID: s.MBID,
		Artist: s.Artist, ArtistMBID: s.ArtistMBID, ArtistState: s.ArtistState, UpdatedAt: s.UpdatedAt,
	}
	if !s.CheckedAt.IsZero() {
		checked := s.CheckedAt
		out.CheckedAt = &checked
	}
	return out
}

func artistLinkOut(a mblinks.Artist) meirosync.ArtistLink {
	return meirosync.ArtistLink{ArtistKey: mblinks.ArtistKey(a.Name), Name: a.Name, State: a.State, MBID: a.MBID, UpdatedAt: a.UpdatedAt}
}

func altLinkOut(a mblinks.Alt) meirosync.AltLink {
	return meirosync.AltLink{
		Provider: mblinks.Clean(a.Provider), ProviderTrackID: a.ID, OtherProvider: a.OtherProvider,
		OtherTrackID: a.OtherID, OtherURL: a.OtherURL, CheckedAt: a.CheckedAt, UpdatedAt: a.UpdatedAt,
	}
}

// linksIn puts what the server holds in the shape the link store keeps.
func linksIn(l meirosync.Links) mblinks.Data {
	var d mblinks.Data
	for _, s := range l.Songs {
		song := mblinks.Song{
			Provider: s.Provider, ID: s.ProviderTrackID, State: s.State, MBID: s.MBID,
			Artist: s.Artist, ArtistMBID: s.ArtistMBID, ArtistState: s.ArtistState, UpdatedAt: s.UpdatedAt,
		}
		if s.CheckedAt != nil {
			song.CheckedAt = *s.CheckedAt
		}
		d.Songs = append(d.Songs, song)
	}
	for _, art := range l.Artists {
		// The server names an artist by its folded name; the store folds the
		// name it is given, so the display name is what goes in.
		d.Artists = append(d.Artists, mblinks.Artist{Name: art.Name, State: art.State, MBID: art.MBID, UpdatedAt: art.UpdatedAt})
	}
	for _, alt := range l.Alternates {
		d.Alts = append(d.Alts, mblinks.Alt{
			Provider: alt.Provider, ID: alt.ProviderTrackID, OtherProvider: alt.OtherProvider,
			OtherID: alt.OtherTrackID, OtherURL: alt.OtherURL, CheckedAt: alt.CheckedAt, UpdatedAt: alt.UpdatedAt,
		})
	}
	return d
}

// queueAllLinks files every link the store holds, so a device that signs in
// sends what it found before it had an account. The server keeps the later of
// two records, so sending one it already has changes nothing.
func (a *app) queueAllLinks() {
	if a.links == nil {
		return
	}
	data := a.links.Data()
	var out meirosync.Links
	for _, s := range data.Songs {
		out.Songs = append(out.Songs, songLinkOut(s))
	}
	for _, art := range data.Artists {
		out.Artists = append(out.Artists, artistLinkOut(art))
	}
	for _, alt := range data.Alts {
		out.Alternates = append(out.Alternates, altLinkOut(alt))
	}
	a.queueLinks(out)
}

// pullLinks reads the links the server holds and folds them into the store, the
// later record of each winning, so what another device found is used here
// without asking MusicBrainz or a provider again. It then sends what only this
// device has.
func (a *app) pullLinks() {
	s := &a.sync
	if s.token == "" || a.links == nil || s.pull.busy() {
		return
	}
	client := &meirosync.Client{BaseURL: a.settings.SyncURL, Token: s.token}
	startTask(a, &s.pull, syncCallTimeout, func(ctx context.Context) (meirosync.Links, error) {
		return client.FetchLinks(ctx)
	}, func(links meirosync.Links, err error) {
		if err != nil {
			log.Printf("sync: reading the links: %v", err)
			return
		}
		took, err := a.links.Merge(linksIn(links))
		if err != nil {
			log.Printf("sync: keeping the links: %v", err)
		}
		if took > 0 {
			a.mirrorStoreToLists()
			a.page.rowsDirty = true
		}
		a.queueAllLinks()
	})
}

// mirrorStoreToLists brings the playlists' own copy of each link up to what the
// store says, after links came from the server.
func (a *app) mirrorStoreToLists() {
	if a.libraries == nil || a.links == nil {
		return
	}
	for _, lib := range a.libraries.List() {
		for _, item := range lib.Items {
			provider := string(item.Source)
			if provider == "" {
				provider = string(media.SourceYTM)
			}
			song, ok := a.links.Song(provider, item.Key)
			if !ok || library.State(song.State) == item.State && song.MBID == item.MBID {
				continue
			}
			_, _ = a.libraries.SetLink(lib.ID, item.Key, library.State(song.State), song.MBID)
		}
	}
}
