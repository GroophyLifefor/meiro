package main

import (
	"context"
	"log"
	"slices"
	"time"

	"github.com/elianiva/meiro/internal/history"
	"github.com/elianiva/meiro/internal/library"
	meirosync "github.com/elianiva/meiro/internal/sync"
	"github.com/elianiva/meiro/media"
)

// pullChanges reads what changed on the server since the last read and folds it
// into the local playlists, so a list made, renamed, reordered or emptied on
// another device arrives here. It reads off the main thread and changes the
// stores on it, and never runs twice at once: a read asked for while one is on
// its way is run when that one ends, since the answer on its way may be older
// than whatever asked.
//
// The cursor it reads from belongs to one server and one account, so a read of
// another starts from the beginning.
func (a *app) pullChanges() {
	s := &a.sync
	if s.token == "" || a.libraries == nil {
		return
	}
	if s.changes.busy() {
		s.pullAgain = true
		return
	}
	url, email := a.settings.SyncURL, a.settings.SyncEmail
	since := a.settings.SyncCursor
	if a.settings.SyncCursorURL != url || a.settings.SyncCursorEmail != email {
		since = 0
	}
	// An install that synced before lists could be read back left rows on the
	// server that are older than its lists and that it may have changed or
	// deleted since without the server hearing of it. Importing them would
	// bring back every list and track the user removed. So on its first read
	// this device is the truth: what the server holds that it lacks is deleted
	// there rather than imported, and nothing the server holds replaces what
	// is here. A device that never synced has nothing stale on the server and
	// imports as usual. The question is settled when the read starts, since a
	// push finishing meanwhile would otherwise make a new install look old.
	firstRead := !a.settings.SyncTwoWay
	migrate := firstRead && !a.settings.SyncLastAt.IsZero()
	client := &meirosync.Client{BaseURL: url, Token: s.token}
	startTask(a, &s.changes, noTimeout, func(ctx context.Context) (meirosync.Changes, error) {
		return client.FetchAllChanges(ctx, since)
	}, func(changes meirosync.Changes, err error) {
		again := s.pullAgain
		s.pullAgain = false
		if err != nil {
			log.Printf("sync: reading the changes: %v", err)
			return
		}
		changed, queued := a.mergeChanges(changes, migrate)
		a.settings.SyncCursor, a.settings.SyncCursorURL, a.settings.SyncCursorEmail = changes.Next, url, email
		if firstRead {
			a.settings.SyncTwoWay = true
		}
		a.saveSettings()
		if changed {
			a.page.rowsDirty = true
		}
		if queued {
			a.syncNow()
		}
		if again {
			a.pullChanges()
		}
	})
}

// pullEvents reads the plays the server holds that it has not given this device
// yet, those of every device of the account, and adds them to the listening
// history, so the stats and the recommendations count what was played anywhere.
// Like pullChanges it reads off the main thread, changes the history on it, and
// never runs twice at once: a read asked for while one is on its way is run
// when that one ends. Its cursor belongs to one server and one account too.
func (a *app) pullEvents() {
	s := &a.sync
	if s.token == "" {
		return
	}
	if s.events.busy() {
		s.pullEventsAgain = true
		return
	}
	// The plays are folded into the history, which must be read first.
	a.loadHistory()
	url, email := a.settings.SyncURL, a.settings.SyncEmail
	since := a.settings.EventCursor
	if a.settings.EventCursorURL != url || a.settings.EventCursorEmail != email {
		since = 0
	}
	client := &meirosync.Client{BaseURL: url, Token: s.token}
	startTask(a, &s.events, noTimeout, func(ctx context.Context) (meirosync.Plays, error) {
		return client.FetchAllEvents(ctx, since)
	}, func(plays meirosync.Plays, err error) {
		again := s.pullEventsAgain
		s.pullEventsAgain = false
		if err != nil {
			log.Printf("sync: reading the plays: %v", err)
			return
		}
		a.mergeEvents(plays.Events)
		a.settings.EventCursor, a.settings.EventCursorURL, a.settings.EventCursorEmail = plays.Next, url, email
		a.saveSettings()
		if again {
			a.pullEvents()
		}
	})
}

// mergeEvents adds the plays the server reported to the history and says
// whether any was new. A play whose event ID the history holds is this
// device's own, come back, or one read twice, and is left out. None of them is
// queued for the server: it is where they came from.
func (a *app) mergeEvents(events []meirosync.PlayedEvent) bool {
	incoming := make([]history.Entry, 0, len(events))
	for _, e := range events {
		if e.ClientEventID == "" || e.Track.ProviderTrackID == "" {
			continue
		}
		incoming = append(incoming, history.Entry{
			EventID:    e.ClientEventID,
			Device:     e.Device,
			Source:     e.Track.Provider,
			VideoID:    e.Track.ProviderTrackID,
			Title:      e.Track.Title,
			Artist:     e.Track.Artist,
			PlayedMS:   e.PlayedMS,
			DurationMS: e.DurationMS,
			Completed:  e.Completed,
			PlayedAt:   e.PlayedAt.Local(),
		})
	}
	merged, added := history.Merge(a.history, incoming)
	a.history = merged
	if added == 0 {
		return false
	}
	a.saveHistory()
	return true
}

// localList finds the list the server knows by an identity: one that came from
// the server under it, or one made here whose derived identity it is.
func (a *app) localList(serverID string) (library.Library, bool) {
	for _, lib := range a.libraries.List() {
		if libraryServerID(lib.ID, lib.ServerID) == serverID {
			return lib, true
		}
	}
	return library.Library{}, false
}

// remoteItem builds the list item a server's track is: the source is its
// provider, and the key what the provider names the track by, which is also
// the page of a SoundCloud track. A provider this app cannot play is not
// built.
func remoteItem(change meirosync.ChangedItem) (library.Item, bool) {
	track := change.Track
	source := media.Source(track.Provider)
	if source != media.SourceYTM && source != media.SourceSoundCloud {
		return library.Item{}, false
	}
	item := library.Item{
		Source:         source,
		Key:            track.ProviderTrackID,
		Title:          track.Title,
		Artist:         track.Artist,
		DurationMS:     track.DurationMS,
		State:          library.Unknown,
		Thumbnail:      track.Thumbnail,
		AlbumThumbnail: track.AlbumThumbnail,
		AddedAt:        change.UpdatedAt,
		UpdatedAt:      change.UpdatedAt,
	}
	if source == media.SourceSoundCloud {
		item.URL = track.ProviderTrackID
	}
	return item, item.Key != ""
}

// adoptDailyMix makes the shared Daily mix list this device's, when it is
// here: the list the server holds under the fixed identity of the mix is the
// one mix of the account, whichever device made it. If its tracks were put
// there today, by this device or another, today's mix is made, and this device
// does not make a second. It says whether it changed anything.
func (a *app) adoptDailyMix() bool {
	lib, ok := a.localList(meirosync.DailyMixID())
	if !ok {
		return false
	}
	changed := false
	if a.settings.DailyMixID != lib.ID {
		a.settings.DailyMixID = lib.ID
		changed = true
	}
	if a.settings.DailyMixDate != today() && madeToday(lib) {
		a.settings.DailyMixDate = today()
		changed = true
	}
	return changed
}

// madeToday says a list has a track that was put in it today.
func madeToday(lib library.Library) bool {
	return slices.ContainsFunc(lib.Items, func(item library.Item) bool {
		return item.UpdatedAt.Local().Format("2006-01-02") == today()
	})
}

// mergeChanges folds what the server reported into the local playlists, the
// later record of each list and each track winning, and says whether anything
// here changed and whether it queued something for the server. Lists come
// first, so the tracks that follow have a list to go in.
//
// During the first read of a device that synced before (migrate), it queues
// deletions for the lists and tracks the server has that this device lacks, and
// changes nothing here.
func (a *app) mergeChanges(changes meirosync.Changes, migrate bool) (changed, queued bool) {
	now := time.Now()
	deleted := a.libraries.Deleted()
	var staleLists []meirosync.Library
	staleIDs := map[string]bool{}
	for _, remote := range changes.Libraries {
		if remote.Kind != "custom" {
			continue
		}
		local, found := a.localList(remote.ID)
		if migrate {
			if !found && !remote.Deleted {
				staleIDs[remote.ID] = true
				staleLists = append(staleLists, meirosync.Library{ID: remote.ID, Kind: "custom", Name: remote.Name, UpdatedAt: now, Deleted: true})
			}
			continue
		}
		switch {
		case !found && !remote.Deleted:
			// A list deleted here after the server's record was made stays
			// deleted: the push tells the server.
			if slices.ContainsFunc(deleted, func(d library.DeletedList) bool {
				return libraryServerID(d.ID, d.ServerID) == remote.ID && d.DeletedAt.After(remote.UpdatedAt)
			}) {
				continue
			}
			if _, err := a.libraries.AddRemote(remote.ID, remote.Name, remote.Cover, remote.UpdatedAt); err != nil {
				log.Printf("sync: keeping a list from the server: %v", err)
				continue
			}
			changed = true
		case found && remote.Deleted:
			if remote.UpdatedAt.Before(local.UpdatedAt) {
				continue
			}
			if err := a.libraries.DropRemote(local.ID); err != nil {
				log.Printf("sync: dropping a list the server deleted: %v", err)
				continue
			}
			changed = true
		case found && remote.UpdatedAt.After(local.UpdatedAt):
			if err := a.libraries.ApplyRemoteList(local.ID, remote.Name, remote.Cover, remote.UpdatedAt); err != nil {
				log.Printf("sync: taking a list's changes from the server: %v", err)
				continue
			}
			changed = true
		}
	}

	var order []string
	byList := map[string][]meirosync.ChangedItem{}
	for _, remote := range changes.Items {
		if _, ok := byList[remote.LibraryID]; !ok {
			order = append(order, remote.LibraryID)
		}
		byList[remote.LibraryID] = append(byList[remote.LibraryID], remote)
	}
	staleItems := map[string][]meirosync.LibraryItem{}
	for _, serverID := range order {
		local, found := a.localList(serverID)
		if !found {
			// A list the server holds that was not taken in is another kind
			// of list, or one this device keeps deleted. A list queued for
			// deletion takes its tracks with it.
			continue
		}
		var applied []library.RemoteItem
		for _, remote := range byList[serverID] {
			item, ok := remoteItem(remote)
			if !ok {
				continue
			}
			if !migrate {
				applied = append(applied, library.RemoteItem{Item: item, Position: remote.Position, Deleted: remote.Deleted})
				continue
			}
			source, id := item.ActiveRef()
			if remote.Deleted || slices.ContainsFunc(local.Items, func(held library.Item) bool {
				heldSource, heldID := held.ActiveRef()
				return heldSource == source && heldID == id
			}) {
				continue
			}
			staleItems[serverID] = append(staleItems[serverID], meirosync.LibraryItem{Track: remote.Track, Position: remote.Position, UpdatedAt: now, Deleted: true})
		}
		if len(applied) == 0 {
			continue
		}
		n, err := a.libraries.ApplyRemoteItems(local.ID, applied)
		if err != nil {
			log.Printf("sync: taking a list's tracks from the server: %v", err)
			continue
		}
		changed = changed || n > 0
	}

	if a.adoptDailyMix() {
		changed = true
	}
	if len(staleLists) > 0 {
		a.sync.outbox.AddLibraries(staleLists)
		queued = true
	}
	for serverID, items := range staleItems {
		a.sync.outbox.AddLibraryItems(serverID, items)
		queued = true
	}
	return changed, queued
}
