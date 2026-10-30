package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/fsutil"
	"github.com/elianiva/meiro/internal/history"
	"github.com/elianiva/meiro/internal/library"
	"github.com/elianiva/meiro/internal/mblinks"
	"github.com/elianiva/meiro/internal/recommend"
	meirosync "github.com/elianiva/meiro/internal/sync"
	"github.com/elianiva/meiro/internal/taste"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/youtube"
)

// What Meiro recommends comes from three steps. The taste profile says what the
// listener likes, from what they played and saved. The providers and
// ListenBrainz say what is like it, which is the slow, rate-limited step, so it
// runs in the background, spaced out, and what it learns is kept for a day.
// Last, internal/recommend picks and orders the songs. The Daily mix and the
// songs added to the queue are two ways of showing the same ranking.

const (
	// recSeeds is how many of the listener's most liked artists the candidates
	// are looked for around.
	recSeeds = 8
	// Budgets for one run: how many requests of each kind it may make that are
	// not already kept, so a run costs a bounded time and a polite number of
	// requests whatever the history holds.
	budgetYouTube    = 10
	budgetSoundCloud = 3
	budgetSimilar    = 4
	// recCacheTTL is how long what a provider listed as related is kept.
	recCacheTTL = 24 * time.Hour
	// suggestionsPerFill is how many recommended songs go into the queue each
	// time it needs more.
	suggestionsPerFill = 5
	// dailyMixSize is how many songs the Daily mix holds.
	dailyMixSize = 30
	// recTimeout bounds one run of gathering candidates.
	recTimeout = 10 * time.Minute
)

// The pauses between requests of each kind, and the fewest picks that make a
// Daily mix worth showing. They are variables so a test can run without waiting.
var (
	pauseYouTube    = 1500 * time.Millisecond
	pauseSoundCloud = 3 * time.Second
	minDailyMix     = 5
)

// recPick is a recommended song with the reason to show beside it.
type recPick struct {
	Item  youtube.MusicItem
	Why   string
	Fresh bool
}

// recState is what the app holds of its recommendations.
type recState struct {
	// picks are the latest ranking, best first, and used the keys already put
	// in the queue, so each fill takes new ones.
	picks []recPick
	used  map[string]bool
	ranAt time.Time
	// remake says the listener asked for a new mix though one was made today,
	// so the mix of another device does not stand in for it.
	remake bool
	load   task
	cache  *relatedCache
}

// relatedCache keeps what a provider listed as related to a seed, so asking
// again within a day costs no request. It is kept on disk.
type relatedCache struct {
	path string
	mu   sync.Mutex
	data map[string]relatedEntry
}

type relatedEntry struct {
	FetchedAt time.Time           `json:"fetchedAt"`
	Items     []youtube.MusicItem `json:"items"`
}

func openRelatedCache(path string) *relatedCache {
	c := &relatedCache{path: path, data: map[string]relatedEntry{}}
	if path == "" {
		return c
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("reading the recommendations cache: %v", err)
		}
		return c
	}
	if err := json.Unmarshal(raw, &c.data); err != nil {
		log.Printf("the recommendations cache is not readable, starting empty: %v", err)
		c.data = map[string]relatedEntry{}
	}
	return c
}

func (c *relatedCache) get(key string, now time.Time) ([]youtube.MusicItem, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.data[key]
	if !ok || now.Sub(e.FetchedAt) >= recCacheTTL {
		return nil, false
	}
	return e.Items, true
}

func (c *relatedCache) put(key string, items []youtube.MusicItem, now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.data[key] = relatedEntry{FetchedAt: now, Items: items}
	// What is older than a week is no use to anyone: drop it so the file stays small.
	for k, e := range c.data {
		if now.Sub(e.FetchedAt) > 7*24*time.Hour {
			delete(c.data, k)
		}
	}
	if c.path == "" {
		return
	}
	raw, err := json.Marshal(c.data)
	if err == nil {
		err = fsutil.WriteFileAtomic(c.path, raw, 0o600)
	}
	if err != nil {
		log.Printf("saving the recommendations cache: %v", err)
	}
}

// tastePlays reads the history into the plays the profile is built from.
func tastePlays(entries []history.Entry) []taste.Play {
	plays := make([]taste.Play, 0, len(entries))
	for _, e := range entries {
		provider := e.Source
		if provider == "" {
			provider = string(media.SourceYTM)
		}
		plays = append(plays, taste.Play{
			Provider: provider, ID: e.VideoID, Title: e.Title, Artist: e.Artist, At: e.PlayedAt,
			PlayedMS: e.PlayedMS, DurationMS: e.DurationMS, Completed: e.Completed,
		})
	}
	return plays
}

// tasteSignals are the songs the listener acted on: put in a playlist of their
// own making, which is a vote, and downloaded. The Daily mix is not one: the app
// made it, and counting it would have the app vote for its own picks.
func (a *app) tasteSignals() []taste.Signal {
	var out []taste.Signal
	if a.libraries != nil {
		for _, lib := range a.libraries.List() {
			if lib.ID == a.settings.DailyMixID || libraryServerID(lib.ID, lib.ServerID) == meirosync.DailyMixID() {
				continue
			}
			for _, item := range lib.Items {
				out = append(out, taste.Signal{Provider: string(item.Source), ID: item.Key, Title: item.Title, Artist: item.Artist, Kind: taste.Saved, At: item.AddedAt})
			}
		}
	}
	if a.downloads != nil {
		for _, rec := range a.downloads.List() {
			out = append(out, taste.Signal{Provider: string(rec.Source), ID: rec.Key, Title: rec.Title, Artist: rec.Artist, Kind: taste.Downloaded, At: rec.DownloadedAt})
		}
	}
	return out
}

// tasteProfile is the listener's taste as of now.
func (a *app) tasteProfile() *taste.Profile {
	return taste.Build(tastePlays(a.history), a.tasteSignals(), time.Now())
}

// seedTrack is a song to look for related songs around: the latest song of a
// liked artist, which is what the providers are asked about.
type seedTrack struct {
	taste.Entry
	provider string
	id       string
	mbid     string
}

// recSeedTracks turns the most liked artists into songs to ask the providers
// about, with the MusicBrainz id of the artist when it is known.
func (a *app) recSeedTracks(profile *taste.Profile) []seedTrack {
	var out []seedTrack
	for _, artist := range profile.TopArtists(recSeeds) {
		var latest history.Entry
		for _, e := range a.history {
			if taste.ArtistKey(e.Artist) == artist.Key && e.VideoID != "" && e.PlayedAt.After(latest.PlayedAt) {
				latest = e
			}
		}
		if latest.VideoID == "" {
			continue
		}
		seed := seedTrack{Entry: artist, provider: latest.Source, id: latest.VideoID}
		if seed.provider == "" {
			seed.provider = string(media.SourceYTM)
		}
		if a.links != nil {
			if linked, ok := a.links.Artist(artist.Name); ok && linked.State == mblinks.Matched {
				seed.mbid = linked.MBID
			}
		}
		out = append(out, seed)
	}
	return out
}

// relatedOf returns the tracks of a related page.
func relatedTracks(r *media.BrowseResult) []media.Item {
	if r == nil {
		return nil
	}
	var out []media.Item
	for _, it := range r.Items {
		if it.IsTrack() {
			out = append(out, it)
		}
	}
	for _, s := range r.Sections {
		for _, it := range s.Items {
			if it.IsTrack() {
				out = append(out, it)
			}
		}
	}
	return out
}

// itemArtist is the artist a song names, from its credit or its byline.
func itemArtist(it youtube.MusicItem) string {
	if it.Artist != "" {
		return it.Artist
	}
	artist, _, _ := strings.Cut(it.Subtitle, " • ")
	return strings.TrimSpace(artist)
}

// pause waits for d, or for the context to end, and says whether it may go on.
func pause(ctx context.Context, d time.Duration) bool {
	select {
	case <-time.After(d):
		return true
	case <-ctx.Done():
		return false
	}
}

// gatherCandidates asks the providers what is related to each seed, within the
// request budgets, and keeps the answers. It runs off the main thread and is
// slow on purpose: its requests are spaced, so it stays well inside every
// service's limits.
func (a *app) gatherCandidates(ctx context.Context, seeds []seedTrack, now time.Time) []recommend.Candidate {
	yt, sc, mb, cache := a.ytClient(), a.soundcloudClient(), a.mb, a.recs.cache
	var out []recommend.Candidate
	add := func(seed seedTrack, items []youtube.MusicItem, rel recommend.Relation, strength float64) {
		for _, it := range items {
			artist := itemArtist(it)
			if it.Title == "" || artist == "" {
				continue
			}
			out = append(out, recommend.Candidate{
				Title: it.Title, Artist: artist, Ref: it, Relation: rel, Strength: strength,
				Seed: recommend.Seed{Name: seed.Name, Score: seed.Score},
			})
		}
	}
	ytLeft, scLeft, simLeft := budgetYouTube, budgetSoundCloud, budgetSimilar
	for _, seed := range seeds {
		if ctx.Err() != nil {
			return out
		}
		key := seed.provider + "|" + seed.id
		items, ok := cache.get(key, now)
		rel := recommend.YouTubeRelated
		if seed.provider == string(media.SourceSoundCloud) {
			rel = recommend.SoundCloudRelated
		}
		if !ok {
			var res *media.BrowseResult
			var err error
			switch {
			case rel == recommend.SoundCloudRelated && scLeft > 0:
				scLeft--
				if !pause(ctx, pauseSoundCloud) {
					return out
				}
				res, err = sc.GetRelated(ctx, seed.id)
			case rel == recommend.YouTubeRelated && ytLeft > 0:
				ytLeft--
				if !pause(ctx, pauseYouTube) {
					return out
				}
				res, err = yt.GetRelated(ctx, seed.id)
			default:
				continue
			}
			if err != nil {
				log.Printf("recommendations: related to %q: %v", seed.Name, err)
				continue
			}
			items = relatedTracks(res)
			cache.put(key, items, now)
		}
		add(seed, items, rel, 1)

		// Where ListenBrainz knows the artist, the artists it names as similar
		// are found on YouTube Music, a couple of songs each.
		if seed.mbid == "" || mb == nil || simLeft <= 0 {
			continue
		}
		similar, err := mb.SimilarArtists(ctx, seed.mbid)
		if err != nil || len(similar) == 0 {
			continue
		}
		for _, s := range similar[:min(len(similar), 3)] {
			if simLeft <= 0 || ctx.Err() != nil {
				break
			}
			skey := "similar|" + s.MBID
			found, ok := cache.get(skey, now)
			if !ok {
				simLeft--
				if !pause(ctx, pauseYouTube) {
					return out
				}
				res, err := yt.Search(ctx, s.Name, youtube.SearchOptions{})
				if err != nil || res == nil {
					continue
				}
				for _, it := range res.Items {
					if it.IsTrack() && taste.ArtistKey(itemArtist(it)) == taste.ArtistKey(s.Name) {
						found = append(found, it)
						if len(found) == 2 {
							break
						}
					}
				}
				cache.put(skey, found, now)
			}
			add(seed, found, recommend.SimilarArtist, s.Similarity)
		}
	}
	return out
}

// refreshRecommendations looks for candidates in the background, ranks them, and
// shows the result: the picks the queue draws from and, once a day, the Daily
// mix. It does nothing while another run is in flight or when the listener has
// turned recommendations off or has no history to learn from.
func (a *app) refreshRecommendations() {
	if !a.settings.Suggestions || a.recs.load.busy() {
		return
	}
	profile := a.tasteProfile()
	if profile.Empty() {
		return
	}
	seeds := a.recSeedTracks(profile)
	if len(seeds) == 0 {
		return
	}
	if a.recs.cache == nil {
		a.recs.cache = openRelatedCache("")
	}
	now := time.Now()
	options := recommend.Defaults(now)
	options.N = dailyMixSize
	options.Explore = float64(a.settings.Explore) / 100
	startTask(a, &a.recs.load, recTimeout, func(ctx context.Context) ([]recommend.Candidate, error) {
		return a.gatherCandidates(ctx, seeds, now), nil
	}, func(candidates []recommend.Candidate, err error) {
		if err != nil {
			log.Printf("recommendations: %v", err)
			return
		}
		// Rank against the profile as it is when the answer comes back, which
		// may include songs played while the providers were asked.
		picks := recommend.Rank(a.tasteProfile(), candidates, options)
		a.recs.picks = a.recs.picks[:0]
		for _, pick := range picks {
			if item, ok := pick.Ref.(youtube.MusicItem); ok {
				a.recs.picks = append(a.recs.picks, recPick{Item: item, Why: pick.Why, Fresh: pick.Fresh})
			}
		}
		a.recs.used, a.recs.ranAt = map[string]bool{}, time.Now()
		a.writeDailyMix()
	})
}

// today is the local calendar day, as the Daily mix is dated.
func today() string { return time.Now().Format("2006-01-02") }

// writeDailyMix puts the picks in the Daily mix playlist, once a day. The
// playlist has one identity on the server whichever device makes it, so the
// devices of an account share one list and the first to make today's fills it:
// a device that finds the list already holds tracks put there today uses that
// mix and makes none. The playlist is made the first time and replaced after
// that, never added to, so there is one of it however long the app has been
// used.
func (a *app) writeDailyMix() {
	remake := a.recs.remake
	if a.libraries == nil || len(a.recs.picks) < minDailyMix || (!remake && a.settings.DailyMixDate == today()) {
		return
	}
	if !remake && a.adoptDailyMix() {
		a.saveSettings()
		a.page.rowsDirty = true
		if a.settings.DailyMixDate == today() {
			return
		}
	}
	a.recs.remake = false
	items := make([]library.Item, 0, len(a.recs.picks))
	for _, pick := range a.recs.picks {
		item := libraryItem(pick.Item, "")
		item.Why = pick.Why
		items = append(items, item)
	}
	shared := meirosync.DailyMixID()
	lib, ok := a.localList(shared)
	if !ok {
		// A mix made before the lists were shared is a list of its own, which
		// the server knows under another identity: take it away, or the other
		// devices would show it beside the shared one.
		if old, found := a.libraries.Get(a.settings.DailyMixID); found && a.settings.DailyMixID != "" {
			if err := a.libraries.Delete(old.ID); err != nil {
				log.Printf("replacing the old Daily mix: %v", err)
			}
		}
		made, err := a.libraries.AddRemote(shared, "Daily mix", "", time.Now())
		if err != nil {
			log.Printf("making the Daily mix: %v", err)
			return
		}
		lib = made
	}
	if err := a.libraries.ReplaceItems(lib.ID, items); err != nil {
		log.Printf("filling the Daily mix: %v", err)
		return
	}
	a.settings.DailyMixID, a.settings.DailyMixDate = lib.ID, today()
	a.saveSettings()
	a.page.rowsDirty = true
}

// dailyMixSection is the Daily mix as a shelf of songs, for the top of Home. It
// reports false until a mix has been made.
func (a *app) dailyMixSection() (youtube.MusicSection, bool) {
	if a.libraries == nil || a.settings.DailyMixDate == "" {
		return youtube.MusicSection{}, false
	}
	lib, ok := a.libraries.Get(a.settings.DailyMixID)
	if !ok || len(lib.Items) == 0 {
		return youtube.MusicSection{}, false
	}
	items := make([]youtube.MusicItem, len(lib.Items))
	for i, item := range lib.Items {
		items[i] = musicItem(item)
	}
	return youtube.MusicSection{Title: "Daily mix", Items: items}, true
}

// recommendTick is run on a timer: it makes the day's recommendations when
// there are none yet, or the day has changed.
func (a *app) recommendTick() {
	if !a.settings.Suggestions {
		return
	}
	if len(a.recs.picks) == 0 || a.settings.DailyMixDate != today() {
		a.refreshRecommendations()
	}
}

// withSuggestions puts a few recommended songs ahead of what YouTube Music
// generated for the queue, so the songs that follow are the ones Meiro would
// pick, and YouTube's fill the rest. Songs already queued or just played are
// left out, and each fill takes new ones.
func (a *app) withSuggestions(generated []youtube.MusicItem) []youtube.MusicItem {
	if !a.settings.Suggestions || len(a.recs.picks) == 0 {
		return generated
	}
	if a.recs.used == nil {
		a.recs.used = map[string]bool{}
	}
	var chosen []youtube.MusicItem
	for _, pick := range a.recs.picks {
		key := pick.Item.Key()
		if key == "" || a.recs.used[key] || a.queue.find(key) >= 0 || key == a.current.Key() {
			continue
		}
		a.recs.used[key] = true
		chosen = append(chosen, pick.Item)
		if len(chosen) == suggestionsPerFill {
			break
		}
	}
	if len(chosen) == 0 {
		return generated
	}
	return append(chosen, slices.DeleteFunc(slices.Clone(generated), func(it youtube.MusicItem) bool {
		return slices.ContainsFunc(chosen, func(c youtube.MusicItem) bool { return c.Key() == it.Key() })
	})...)
}

// recommendationsCard is where the listener turns Meiro's own recommendations
// on and off, says how much of them may be new, and asks for the day's mix now.
func (a *app) recommendationsCard(c *ui.Context) {
	sc := m3.Of(c).Scheme
	a.settingsCard(c, "Recommendations", m3.IconMusicNote, func() {
		m3.Text(c, m3.BodyMedium, "Meiro learns what you like from what you play, keep and download, finds what YouTube Music, SoundCloud and ListenBrainz say is like it, and makes a Daily mix and adds a few songs to the queue. It runs on this device. Playing a song through counts for it and leaving it early counts against it, and what you did a month ago counts half as much as what you did today.").TextColor(sc.OnSurfaceVariant)
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Recommend songs", "Make the Daily mix and put suggestions in the queue.").Grow(1)
			on := a.settings.Suggestions
			if m3.Switch(c, &on, "Recommend songs").Changed() {
				a.settings.Suggestions = on
				a.saveSettings()
				if on {
					a.recommendTick()
				}
			}
		})
		explore := float64(a.settings.Explore)
		ui.Column(c).Gap(4).Children(func() {
			settingLabel(c, "New artists", "How many of every hundred songs may be by artists you have not played. "+strconv.Itoa(a.settings.Explore)+"% now.")
			m3.Slider(c, &explore, 0, 100, m3.SliderSpec{Label: "New artists", Key: "recommend-explore", Disabled: !a.settings.Suggestions})
		})
		if int(explore) != a.settings.Explore {
			a.settings.Explore = int(explore)
			a.saveSettings()
		}
		status := "No Daily mix yet. It needs some listening first."
		switch {
		case a.recs.load.busy():
			status = "Looking for songs like the ones you play."
		case a.settings.DailyMixDate == today():
			status = "Today's Daily mix is in your playlists."
		case a.settings.DailyMixDate != "":
			status = "The last Daily mix was made on " + a.settings.DailyMixDate + "."
		}
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Daily mix", status).Grow(1)
			if m3.Button(c, m3.ButtonSpec{Label: "Make it now", Kind: m3.Tonal, Key: "recommend-now", Disabled: !a.settings.Suggestions || a.recs.load.busy()}).Clicked() {
				a.settings.DailyMixDate = ""
				a.recs.remake = true
				a.refreshRecommendations()
			}
		})
	})
}
