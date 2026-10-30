package main

import (
	"fmt"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/audio"
	"github.com/elianiva/meiro/internal/keymap"
	"github.com/elianiva/meiro/internal/settings"
	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/media"
	"github.com/elianiva/meiro/player"
)

// swatches are the seed colours the settings offer.
var swatches = []struct{ name, hex string }{
	{"Violet", "#6750a4"},
	{"Indigo", "#4c5ed6"},
	{"Blue", "#1b6ef3"},
	{"Sky", "#0288d1"},
	{"Teal", "#00897b"},
	{"Green", "#2e8b3a"},
	{"Lime", "#7a9a00"},
	{"Amber", "#d99a00"},
	{"Orange", "#e0620d"},
	{"Red", "#d6323a"},
	{"Rose", "#d81b78"},
	{"Orchid", "#a73fc0"},
}

// settingsRow is one row of the settings page: a section heading, or a card
// under it.
type settingsRow struct {
	key     string
	heading string                      // set for a heading
	search  string                      // words the card answers
	card    func(a *app, c *ui.Context) // set for a card
}

type settingsSearchState struct {
	query string
	open  bool
	field ui.Handle
}

// settingsRows are the rows of the settings page, top to bottom. Cards that
// answer the same question sit under one heading.
var settingsRows = []settingsRow{
	{key: "h-account", heading: "Account"},
	{key: "meiro-account", search: "meiro account sync server dashboard sign in email password sending plays playlists downloads", card: (*app).meiroAccountCard},
	{key: "account", search: "youtube music account sign in profile session channel optional", card: (*app).youtubeAccountCard},
	{key: "lastfm", search: "last.fm scrobbling music service", card: (*app).lastFMCard},

	{key: "h-look", heading: "Appearance and sound"},
	{key: "appearance", search: "theme appearance system light dark colour color colours colors violet indigo blue sky teal green lime amber orange red rose orchid hue palette tonal spot vibrant expressive neutral monochrome artwork dynamic", card: (*app).appearanceCard},
	{key: "sound", search: "sound audio normalize loudness equaliser volume", card: (*app).soundCard},

	{key: "h-sources", heading: "Music sources"},
	{key: "recommendations", search: "recommendations suggestions daily mix discover explore new artists taste queue", card: (*app).recommendationsCard},
	{key: "preferred-provider", search: "preferred provider source sources music source order ytm youtube music soundcloud", card: (*app).preferredProviderCard},

	{key: "h-system", heading: "System"},
	{key: "desktop", search: "desktop linux notifications discord rich presence activity status tray icon wordmark o mark low key subtle", card: (*app).desktopCard},
	{key: "keyboard", search: "keyboard shortcuts keys", card: (*app).keyboardCard},

	{key: "h-data", heading: "Storage and privacy"},
	{key: "cache", search: "storage cache youtube music ytm playback downloads size megabytes", card: (*app).youtubeCacheCard},
	{key: "soundcloud-cache", search: "storage cache soundcloud offline downloads size megabytes", card: (*app).soundcloudCacheCard},
	{key: "cache-folder", search: "storage cache folder location directory", card: (*app).cacheFolderCard},
	{key: "metadata-cache", search: "storage cache musicbrainz listenbrainz metadata artists songs searches answers clear", card: (*app).metadataCacheCard},
	{key: "history", search: "privacy history listening plays", card: (*app).historyCard},

	{key: "h-about", heading: "About and support"},
	{key: "about", search: "about credits author fork murat kirazkaya", card: (*app).aboutCard},
	{key: "updates", search: "about updates version", card: (*app).updatesCard},
	{key: "tools", search: "support tools", card: (*app).toolsCard},
	{key: "logs", search: "logs folder diagnostics", card: (*app).logsCard},
}

func settingsRowsFor(query string) []settingsRow {
	query = strings.ToLower(strings.TrimSpace(query))
	if query == "" {
		return settingsRows
	}
	var filtered []settingsRow
	for start := 0; start < len(settingsRows); {
		heading := settingsRows[start]
		end := start + 1
		for end < len(settingsRows) && settingsRows[end].heading == "" {
			end++
		}
		sectionMatches := strings.Contains(strings.ToLower(heading.heading), query)
		var matchingCards []settingsRow
		for _, row := range settingsRows[start+1 : end] {
			if sectionMatches || strings.Contains(row.search, query) {
				matchingCards = append(matchingCards, row)
			}
		}
		if len(matchingCards) > 0 {
			filtered = append(filtered, heading)
			filtered = append(filtered, matchingCards...)
		}
		start = end
	}
	return filtered
}

func (a *app) settingsSearchBar(c *ui.Context) {
	sc := m3.Of(c).Scheme
	const buttonWidth, maxWidth = float32(40), float32(520)
	control := ui.Row(c.Key("settings-search")).Width(maxWidth).MinWidth(0).Shrink(1)
	target := float32(0)
	if a.settingsSearch.open {
		target = 1
	}
	grown := m3.Animate(control, "open", target, m3.SpatialFast)
	if !a.settingsSearch.open && grown < 0.02 {
		control.MaxWidth(buttonWidth)
		control.Children(func() {
			button := m3.IconButton(c, m3.IconButtonSpec{
				Icon: m3.IconSearch, Label: "Search settings", Kind: m3.StandardIcon,
				Size: m3.Small40, Key: "settings-search-open", Container: sc.SurfaceContainerHigh,
			})
			if button.Clicked() {
				a.settingsSearch.open = true
				a.settingsSearch.field.Focus()
			}
		})
		return
	}
	control.MaxWidth(buttonWidth + (maxWidth-buttonWidth)*min(max(grown, 0), 1))
	control.Children(func() {
		result, _ := m3.SearchBar(c, m3.SearchSpec{
			Value:       &a.settingsSearch.query,
			Placeholder: "Search settings",
			Key:         "settings-search-bar",
			Focus:       &a.settingsSearch.field,
			MaxWidth:    maxWidth,
			Dismiss:     true,
		})
		if result.Changed || result.Cleared {
			a.list.ScrollTo(0, ui.Start)
		}
		if result.Dismissed {
			a.closeSettingsSearch()
		}
	})
}

func (a *app) closeSettingsSearch() {
	a.settingsSearch = settingsSearchState{}
}

// settingsPage is where the user chooses how the app looks, sounds and plays,
// and manages the account, the caches, Last.fm and the shortcuts.
//
// The rows are the rows of a list, so only the ones in view are built, laid
// out and painted: the page is long, and scrolling it rebuilds the frame.
func (a *app) settingsPage(c *ui.Context) {
	rows := settingsRowsFor(a.settingsSearch.query)
	if len(rows) == 0 {
		ui.Column(c).Fill().Center().Children(func() {
			a.message(c, m3.IconSearch, "No settings found", "Try another search.", "", nil)
		})
		return
	}
	a.list.Key = func(i int) any {
		if i >= len(rows) {
			return nil
		}
		return rows[i].key
	}
	ui.List(c, a.list, len(rows), func(i int) {
		row := rows[i]
		if row.card == nil {
			top := float32(16)
			if i == 0 {
				top = 0
			}
			ui.Row(c).FillWidth().Padding(top, pageGutter, 12).Children(func() {
				m3.EmphasizedText(c, m3.TitleMedium, row.heading).TextColor(m3.Of(c).Scheme.Primary)
			})
			return
		}
		ui.Column(c).FillWidth().Padding(0, pageGutter, 16).Children(func() {
			row.card(a, c)
		})
	}).Grow(1).Padding(0, 0, a.clearance()-16)
}

// settingsCard is a tonal card that holds one group of settings.
func (a *app) settingsCard(c *ui.Context, title string, icon *ui.SVG, build func()) {
	a.badgedSettingsCard(c, title, "", icon, build)
}

// badgedSettingsCard is settingsCard with a small tag after the title, such as
// "optional", when badge is not empty.
func (a *app) badgedSettingsCard(c *ui.Context, title, badge string, icon *ui.SVG, build func()) {
	sc := m3.Of(c).Scheme
	ui.Column(c).Padding(24).Gap(22).Radius(m3.ExtraLarge).Background(sc.SurfaceContainerLow).Children(func() {
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			ui.Icon(c, icon).FontSize(24).TextColor(sc.Primary)
			m3.EmphasizedText(c, m3.TitleLarge, title)
			if badge != "" {
				ui.Row(c).Padding(4, 10, 4).Radius(m3.Full).Background(sc.SurfaceContainerHighest).Children(func() {
					m3.Text(c, m3.LabelMedium, badge).TextColor(sc.OnSurfaceVariant)
				})
			}
		})
		build()
	})
}

// settingLabel names a control, with what it does under it.
func settingLabel(c *ui.Context, title, body string) ui.Element {
	sc := m3.Of(c).Scheme
	return ui.Column(c).Gap(2).MinWidth(0).Children(func() {
		m3.EmphasizedText(c, m3.TitleMedium, title)
		if body != "" {
			m3.Text(c, m3.BodyMedium, body).TextColor(sc.OnSurfaceVariant)
		}
	})
}

func (a *app) appearanceCard(c *ui.Context) {
	sc := m3.Of(c).Scheme
	th := m3.Of(c)
	a.settingsCard(c, "Appearance", m3.IconPalette, func() {
		// Light, dark, or whatever the desktop is.
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Theme", "Follow the desktop, or stay light or dark.").Grow(1)
			mode := int(th.Mode)
			if m3.ButtonGroup(c, "mode", &mode, []string{"System", "Light", "Dark"}, nil) {
				chosen := m3.Modes()[mode]
				a.settings.SetMode(chosen)
				a.saveSettings()
				// The desktop draws the window buttons, so it is told the
				// appearance Meiro is set to, or they turn invisible on a
				// window whose colour is not the desktop's.
				a.applyAppearance()
			}
		})

		// The seed: twelve colours, and any hue between.
		ui.Column(c).Gap(14).Children(func() {
			settingLabel(c, "Colour", "Every colour in the app grows from this one.")
			ui.Row(c).Gap(8).Wrap().Children(func() {
				for _, swatch := range swatches {
					if a.swatch(c, swatch.name, swatch.hex) {
						a.settings.Seed, a.settings.Dynamic = swatch.hex, false
						a.saveSettings()
					}
				}
			})
			ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
				m3.Text(c, m3.LabelLarge, "Hue").TextColor(sc.OnSurfaceVariant).Width(36).Shrink(0)
				hue, _ := m3.HueOf(th.Seed)
				value := hue
				slider := m3.Slider(c, &value, 0, 360, m3.SliderSpec{Label: "Hue", Thickness: 16, Hue: true, Key: "hue"})
				if slider.Changed() {
					a.settings.Seed, a.settings.Dynamic = settings.SeedHex(m3.FromHue(value)), false
					a.saveSettings()
				}
			})
		})

		// How the seed is spent.
		ui.Column(c).Gap(14).Children(func() {
			settingLabel(c, "Palette", "How boldly the colour is used.")
			ui.Row(c).Gap(12).Wrap().Children(func() {
				for _, style := range m3.Styles() {
					if a.styleTile(c, style) {
						a.settings.Style = int(style)
						a.saveSettings()
					}
				}
			})
		})

		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Colour from artwork", "Take the colour from the cover of the song that is playing.").Grow(1)
			on := a.settings.Dynamic
			if m3.Switch(c, &on, "Colour from artwork").Changed() {
				a.settings.Dynamic = on
				a.saveSettings()
			}
		})

		// How large the interface is, at a readable size in the interface
		// that grows with it.
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Zoom", "Make everything larger or smaller.").Grow(1)
			zoom := settings.ClampZoom(a.settings.Zoom)
			ui.Row(c).Gap(4).AlignItems(ui.Center).Children(func() {
				a.hinted(c, func() {
					if m3.IconButton(c, m3.IconButtonSpec{
						Icon: m3.IconRemove, Label: "Zoom out", Kind: m3.StandardIcon, Size: m3.Small40,
						Key: "zoom-out", Disabled: zoom <= settings.MinZoom,
					}).Clicked() {
						a.zoomBy(-1)
					}
				}, keymap.ZoomOut)
				m3.EmphasizedText(c, m3.TitleMedium, strconv.Itoa(zoom)+"%").Width(64).TextAlign(ui.Center).TextColor(sc.OnSurface)
				a.hinted(c, func() {
					if m3.IconButton(c, m3.IconButtonSpec{
						Icon: m3.IconAdd, Label: "Zoom in", Kind: m3.StandardIcon, Size: m3.Small40,
						Key: "zoom-in", Disabled: zoom >= settings.MaxZoom,
					}).Clicked() {
						a.zoomBy(1)
					}
				}, keymap.ZoomIn)
			})
		})
	})
}

// preferredProviderCard orders the sources the app searches and plays. The
// first source is preferred for a song more than one holds, and a source that
// fails falls through to the next. A downloaded song plays from the source it
// is kept from whatever the order, which the note under the rows says.
func (a *app) preferredProviderCard(c *ui.Context) {
	sc := m3.Of(c).Scheme
	order := a.providerOrder()
	a.settingsCard(c, "Preferred provider", m3.IconMusicNote, func() {
		ui.Column(c).Gap(8).Children(func() {
			for i, source := range order {
				name := sourceLabel(string(source))
				ui.Row(c).Key("provider-" + string(source)).Gap(12).AlignItems(ui.Center).Children(func() {
					ui.Icon(c, sourceIcon(source)).FontSize(22).TextColor(sourceColour(source))
					m3.EmphasizedText(c, m3.TitleMedium, name).Grow(1)
					if m3.IconButton(c, m3.IconButtonSpec{
						Icon: m3.IconExpandLess, Label: "Move " + name + " up", Kind: m3.StandardIcon,
						Size: m3.Small40, Key: "provider-up-" + string(source), Disabled: i == 0,
					}).Clicked() {
						a.moveProvider(i, -1)
					}
					if m3.IconButton(c, m3.IconButtonSpec{
						Icon: m3.IconExpandMore, Label: "Move " + name + " down", Kind: m3.StandardIcon,
						Size: m3.Small40, Key: "provider-down-" + string(source), Disabled: i == len(order)-1,
					}).Clicked() {
						a.moveProvider(i, 1)
					}
				})
			}
		})
		m3.Text(c, m3.BodyMedium, "Playback order: a downloaded file first, then this order. If a provider fails, the next one is tried.").TextColor(sc.OnSurfaceVariant)
	})
}

// cacheCardSpec is what one provider's cache card says and does. Both
// providers' cards are drawn from it, so they read and behave alike.
type cacheCardSpec struct {
	title, intro, clearLabel string
	// key tells the cards' fields apart.
	key string
	// text is the size the field holds, and err why a value it held was not
	// accepted.
	text, err *string
	// mb is the size in force, and setMB applies a new one.
	mb    int
	setMB func(int)
	// cache is the cache the card reports on, nil while it is off.
	cache *audio.Cache
	clear func()
}

// cacheCard draws a provider's audio cache: its size in megabytes, where zero
// turns it off, how full it is, and a button that clears it. A downloaded
// track is never touched by it.
func (a *app) cacheCard(c *ui.Context, spec cacheCardSpec) {
	sc := m3.Of(c).Scheme
	a.settingsCard(c, spec.title, m3.IconFolder, func() {
		m3.Text(c, m3.BodyMedium, spec.intro).TextColor(sc.OnSurfaceVariant)
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Size limit", "Enter a whole number of megabytes and press Enter to apply. Set 0 to turn the cache off. Older songs are removed first.").Grow(1)
			input := ui.TextInputBase(c.Key(spec.key+"-cache-mb"), spec.text).
				Width(128).Height(48).Shrink(0).Padding(12).Radius(m3.Medium).Background(sc.SurfaceContainerHigh).
				TextColor(sc.OnSurface).FontSize(16).Label("Megabytes").Placeholder("512")
			m3.Text(c, m3.BodyMedium, "MB").Width(48).Shrink(0).TextColor(sc.OnSurfaceVariant)
			if input.Submitted() {
				if mb, err := strconv.Atoi(strings.TrimSpace(*spec.text)); err == nil && mb >= 0 {
					spec.setMB(mb)
					*spec.err = ""
				} else {
					*spec.err = "Enter a whole number of 0 or more."
				}
			}
		})
		if *spec.err != "" {
			m3.Text(c, m3.BodyMedium, *spec.err).TextColor(sc.Error)
		}
		a.cacheFullness(c, spec)
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Clear the cache", "Delete the songs kept on disk. Downloaded tracks are not touched.").Grow(1)
			if m3.Button(c, m3.ButtonSpec{Label: spec.clearLabel, Kind: m3.Outlined, Key: spec.key + "-cache-clear", Disabled: spec.cache == nil}).Clicked() {
				spec.clear()
			}
		})
	})
}

// cacheFullness shows how much of its size a cache uses: a bar, and in words
// the megabytes used of the limit and how many songs they are.
func (a *app) cacheFullness(c *ui.Context, spec cacheCardSpec) {
	sc := m3.Of(c).Scheme
	used, tracks := spec.cache.Usage()
	text := "Off"
	permille := 0
	if spec.mb > 0 {
		limit := int64(spec.mb) << 20
		text = fmt.Sprintf("%s of %d MB · %s", formatMegabytes(used), spec.mb, songCount(tracks))
		permille = int(min(used*1000/limit, 1000))
	}
	ui.Column(c).Gap(6).Children(func() {
		settingLabel(c, "Fullness", "")
		ui.Row(c).Height(6).FillWidth().Radius(m3.Full).Background(sc.OutlineVariant).Children(func() {
			if permille > 0 {
				ui.Box(c).Grow(float32(permille)).Basis(0).Height(6).Radius(m3.Full).Background(sc.Primary)
			}
			if permille < 1000 {
				ui.Box(c).Grow(float32(1000 - permille)).Basis(0).Height(6)
			}
		})
		m3.Text(c, m3.BodySmall, text).TextColor(sc.OnSurfaceVariant)
	})
}

// formatMegabytes writes a size in bytes as megabytes, with a decimal below
// ten of them.
func formatMegabytes(bytes int64) string {
	mb := float64(bytes) / (1 << 20)
	if mb < 10 {
		return fmt.Sprintf("%.1f MB", mb)
	}
	return fmt.Sprintf("%.0f MB", mb)
}

// songCount names how many songs a cache holds.
func songCount(n int) string {
	if n == 1 {
		return "1 song"
	}
	return strconv.Itoa(n) + " songs"
}

// youtubeCacheCard is the YouTube Music audio cache.
func (a *app) youtubeCacheCard(c *ui.Context) {
	a.cacheCard(c, cacheCardSpec{
		title: "YouTube Music cache", key: "youtube",
		intro:      "Keeps recently played YouTube Music audio on disk so it replays without another network stream. Each song is kept in the format YouTube serves it.",
		clearLabel: "Clear YouTube Music cache",
		text:       &a.youtubeCacheMBText, err: &a.youtubeCacheError,
		mb: a.settings.YouTubeCacheMB, setMB: a.setYouTubeCacheMB,
		cache: a.currentAudioCache(), clear: a.clearAudioCache,
	})
}

// cacheFolderCard is the folder both caches are kept in. Each provider's cache
// has a folder of its own inside it.
func (a *app) cacheFolderCard(c *ui.Context) {
	sc := m3.Of(c).Scheme
	a.settingsCard(c, "Cache folder", m3.IconFolder, func() {
		ui.Column(c).Gap(12).Children(func() {
			settingLabel(c, "Folder", "Meiro creates a private folder for each provider inside this location. Changing folders removes the songs Meiro cached in the old one.")
			ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
				location := a.cacheDirectoryText
				if location == "" {
					location = "Default app cache folder"
				}
				m3.Text(c, m3.BodyMedium, location).TextColor(sc.OnSurfaceVariant).MinWidth(0).Grow(1).SingleLine().Tooltip(location)
				if m3.IconButton(c, m3.IconButtonSpec{Icon: m3.IconFolder, Label: "Choose cache folder", Kind: m3.OutlinedIcon, Size: m3.Small40, Key: "audio-cache-folder"}).Clicked() {
					a.chooseAudioCacheDirectory()
				}
			})
			if a.cacheDirError != "" {
				m3.Text(c, m3.BodyMedium, a.cacheDirError).TextColor(sc.Error)
			}
		})
	})
}

// clearAudioCache deletes the songs the playback cache keeps, leaving the one
// playing where it is and never touching a downloaded track.
func (a *app) clearAudioCache() {
	cache := a.currentAudioCache()
	if cache == nil {
		return
	}
	keep := ""
	if a.current.Key() != "" {
		keep = audioKey(a.current)
	}
	a.run(func() { cache.Clear(keep) })
	a.notice = "Cleared the YouTube Music cache"
}

// soundcloudCacheCard is the SoundCloud audio cache. It is kept apart from the
// YouTube Music cache, so the two never fill or clear together.
func (a *app) soundcloudCacheCard(c *ui.Context) {
	a.cacheCard(c, cacheCardSpec{
		title: "SoundCloud cache", key: "soundcloud",
		intro:      "Keeps recently played SoundCloud audio on disk so it replays without another network stream. It is kept apart from the YouTube Music cache.",
		clearLabel: "Clear SoundCloud cache",
		text:       &a.soundcloudCacheMBText, err: &a.soundcloudCacheError,
		mb: a.settings.SoundCloudCacheMB, setMB: a.setSoundCloudCacheMB,
		cache: a.currentSoundCloudCache(), clear: a.clearSoundCloudCache,
	})
}

// setSoundCloudCacheMB changes how much SoundCloud audio the cache keeps, and
// reopens it so the new size applies at once. Zero turns the cache off.
func (a *app) setSoundCloudCacheMB(mb int) {
	a.settings.SoundCloudCacheMB = max(mb, 0)
	a.soundcloudCacheMBText = strconv.Itoa(a.settings.SoundCloudCacheMB)
	a.saveSettings()
	a.openSoundCloudCache()
}

// clearSoundCloudCache deletes the songs the SoundCloud cache keeps, leaving
// the one playing where it is and never touching a downloaded track.
func (a *app) clearSoundCloudCache() {
	cache := a.currentSoundCloudCache()
	if cache == nil {
		return
	}
	keep := ""
	if a.current.Key() != "" && resolveSource(a.current) == media.SourceSoundCloud {
		keep = audioKey(a.current)
	}
	a.run(func() { cache.Clear(keep) })
	a.notice = "Cleared the SoundCloud cache"
}

// soundCard holds the equaliser's bands. Slider changes update the player as
// they happen; only saving the settings waits for the drag to finish.
func (a *app) soundCard(c *ui.Context) {
	sc := m3.Of(c).Scheme
	a.settingsCard(c, "Sound", m3.IconVolumeUp, func() {
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Normalize loudness", "Adjust track gain to keep songs at a more consistent volume. Requires a signed-in YouTube session.").Grow(1)
			on := a.settings.NormalizeLoudness
			if m3.Switch(c, &on, "Normalize loudness").Changed() {
				a.setNormalizeLoudness(on)
			}
		})
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			settingLabel(c, "Equaliser", "Shape ten bands of the sound as you adjust them, without restarting the stream.").Grow(1)
			on := a.settings.EQOn
			if m3.Switch(c, &on, "Equaliser on").Changed() {
				a.settings.EQOn = on
				a.applyEQ()
			}
		})
		a.normalizeEQBands()
		ui.Row(c).Gap(8).Wrap().Children(func() {
			for _, preset := range eqPresets {
				selected := slices.Equal(a.settings.EQBands, preset.bands)
				if m3.Button(c, m3.ButtonSpec{
					Label: preset.name, Kind: m3.Tonal, Size: m3.ExtraSmall32,
					Toggle: true, Selected: selected, Disabled: !a.settings.EQOn, Key: "eq-preset-" + preset.name,
				}).Clicked() {
					copy(a.settings.EQBands, preset.bands)
					copy(a.eqApplied[:], preset.bands)
					a.applyEQ()
				}
			}
		})
		// The bands, one upright slider each above its frequency, the way an
		// equaliser is drawn: the handle rests on the 0 dB mark, and a boost
		// or cut fills between the two.
		ui.Row(c).FillWidth().MaxWidth(640).Gap(4).Justify(ui.SpaceBetween).Children(func() {
			for i := range player.EQBandCount {
				ui.Column(c).Gap(6).Width(m3.MinTarget).AlignItems(ui.Center).Children(func() {
					m3.Slider(c, &a.settings.EQBands[i], -eqBoostRange, eqBoostRange, m3.SliderSpec{
						Label: eqLabels[i] + " dB", Key: "eq-" + strconv.Itoa(i),
						Thickness: 6, Vertical: true, Disabled: !a.settings.EQOn,
					}).Height(148).FillWidth()
					if a.eqApplied[i] != a.settings.EQBands[i] {
						copy(a.eqApplied[:], a.settings.EQBands)
						a.player.SetEQ(a.eqBands())
						a.scheduleEQ()
					}
					m3.Text(c, m3.LabelLarge, eqLabels[i]).TextColor(sc.OnSurfaceVariant)
				})
			}
		})
	})
}

// swatch is one seed colour, drawn as the primary colour it would give. The
// chosen one squares off and shows a check.
func (a *app) swatch(c *ui.Context, name, hex string) bool {
	th := m3.Of(c)
	sc := th.Scheme
	seed, _ := settings.ParseSeed(hex)
	fill, on := seed, ui.RGB(255, 255, 255)
	if m3.Contrast(fill, on) < 3 {
		on = ui.RGB(24, 24, 27)
	}
	selected := !a.settings.Dynamic && hex == a.settings.Seed
	b := ui.ButtonBase(c.Key("swatch-" + hex))
	rest := float32(28)
	if selected {
		rest = 16
	}
	radius := m3.Animate(b, "r", rest, m3.SpatialFast)
	if b.Pressed() {
		radius = 12
	}
	b.Size(56, 56).Radius(max(radius, 0)).Background(fill).Center().Cursor(ui.CursorPointer).Label(name).Tooltip(name)
	border := ui.Transparent
	if selected {
		border = sc.Surface
	} else if b.Hovered() {
		border = sc.OnSurface.Alpha(0.16)
	}
	b.Border(3, border)
	if selected {
		m3.Elevation(c, b, 1)
	}
	b.Children(func() {
		if selected {
			ui.Icon(c, m3.IconCheck).FontSize(28).TextColor(on)
		}
	})
	return b.Clicked()
}

// stylePreview returns the scheme a palette style would give the seed in a
// light or dark appearance, reusing what it resolved until either changes.
func (a *app) stylePreview(seed ui.Color, dark bool, style m3.Style) m3.Scheme {
	t := &a.theme
	if t.previews == nil || seed != t.previewSeed || dark != t.previewDark {
		t.previews, t.previewSeed, t.previewDark = make(map[m3.Style]m3.Scheme, len(m3.Styles())), seed, dark
	}
	if preview, ok := t.previews[style]; ok {
		return preview
	}
	preview := m3.NewScheme(m3.NewPalettes(seed, style), dark)
	t.previews[style] = preview
	return preview
}

// styleTile previews a palette style in the colours the seed would give it.
func (a *app) styleTile(c *ui.Context, style m3.Style) bool {
	th := m3.Of(c)
	sc := th.Scheme
	preview := a.stylePreview(th.Seed, th.Dark, style)
	selected := th.Style == style
	b := ui.ButtonBase(c.Key("style-" + style.String()))
	b.Column().AlignItems(ui.Stretch).Width(138).MinHeight(120).Padding(14).Gap(10).Radius(m3.ExtraLarge - 4).Background(preview.PrimaryContainer).Cursor(ui.CursorPointer).
		Label(style.String())
	border := ui.Transparent
	if selected {
		border = sc.Primary
	} else if b.Hovered() {
		border = sc.OnSurface.Alpha(0.16)
	}
	b.Border(3, border)
	b.Children(func() {
		ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
			for _, dot := range []ui.Color{preview.Primary, preview.Secondary, preview.Tertiary} {
				ui.Box(c).Size(22, 22).Radius(m3.Full).Background(dot)
			}
			ui.Spacer(c)
			if selected {
				ui.Icon(c, m3.IconCheck).FontSize(20).TextColor(preview.OnPrimaryContainer)
			}
		})
		ui.Column(c).Gap(2).Children(func() {
			m3.EmphasizedText(c, m3.TitleSmall, style.String()).TextColor(preview.OnPrimaryContainer)
			m3.Text(c, m3.BodySmall, style.Blurb()).TextColor(preview.OnPrimaryContainer.Alpha(0.8)).MaxLines(2)
		})
	})
	return b.Clicked()
}

// youtubeAccountCard is the YouTube Music provider's account: optional, and
// needed only to read its library, likes and recap. Search and playback work
// without it. The channel the account acts as is chosen here.
func (a *app) youtubeAccountCard(c *ui.Context) {
	sc := m3.Of(c).Scheme
	a.badgedSettingsCard(c, "YouTube Music account", "optional", m3.IconMusicNote, func() {
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			if current := a.account.session; current != nil {
				a.avatar(c, current.name(), current.details.Thumbnail, 128, 56)
				ui.Column(c).Gap(2).Grow(1).MinWidth(0).Children(func() {
					m3.EmphasizedText(c, m3.TitleMedium, current.name()).SingleLine().Tooltip(current.name())
					if current.details.Email != "" {
						m3.Text(c, m3.BodyMedium, current.details.Email).SingleLine().Tooltip(current.details.Email).TextColor(sc.OnSurfaceVariant)
					}
				})
				if m3.Button(c, m3.ButtonSpec{Label: "Sign out", Icon: m3.IconLogout, Kind: m3.Tonal, Size: m3.Medium56, Key: "sign-out"}).Clicked() {
					a.signOut()
				}
				return
			}
			settingLabel(c, "Not signed in", "Needed only to read your YouTube Music library, likes and recap. Search and playback work without it.").Grow(1)
			if m3.Button(c, m3.ButtonSpec{Label: "Sign in", Icon: m3.IconLogin, Size: m3.Medium56, Key: "sign-in"}).Clicked() {
				a.openSignIn()
			}
		})
		if current := a.account.session; current != nil {
			ui.Column(c).Gap(2).Children(func() {
				a.channelItems(c, current)
			})
		}
		if s := &a.account.signIn; s.err != "" && !s.open {
			m3.Text(c, m3.BodyMedium, s.err).TextColor(sc.Error).MaxLines(3)
		}
	})
}

// chooseAudioCacheDirectory opens the native folder picker, then switches
// future playback to an app-owned audio folder inside the chosen directory.
// The songs cached in the folder before are removed.
func (a *app) chooseAudioCacheDirectory() {
	defaultRoot, _ := mygo.App.Path(mygo.PathCache)
	current := a.cacheDirectoryText
	if current == "" {
		current = defaultRoot
	}
	limits, parent := cacheLimits(a.settings.YouTubeCacheMB), a.desktop.window()
	type chosen struct {
		root  string
		cache *audio.Cache
		err   string
	}
	spawn(a, func() chosen {
		paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{
			Parent: parent, Title: "Choose audio cache folder", DefaultPath: current,
			Directory: true, CreateDirectories: true,
		})
		if err != nil {
			return chosen{err: "Could not open the folder picker: " + err.Error()}
		}
		if len(paths) == 0 {
			return chosen{}
		}
		root, err := filepath.Abs(paths[0])
		if err != nil {
			return chosen{err: "Could not use that folder: " + err.Error()}
		}
		if filepath.Clean(root) == filepath.Clean(current) {
			return chosen{root: root}
		}
		cache, root, err := audio.OpenLimits(root, limits)
		if err != nil {
			return chosen{err: "Could not use that folder: " + err.Error()}
		}
		return chosen{root: root, cache: cache}
	}, func(c chosen) {
		if c.err != "" {
			a.cacheDirError = c.err
			return
		}
		if c.root == "" {
			return // the picker was cancelled
		}
		if c.cache != nil {
			if previous := a.replaceAudioCache(c.cache); previous != nil {
				a.run(func() {
					previous.Close()
					// The old location is app-owned and no longer used.
					if previous.Dir() != c.cache.Dir() {
						previous.Purge()
					}
				})
			}
		}
		a.settings.CacheDirectory = c.root
		if filepath.Clean(c.root) == filepath.Clean(defaultRoot) {
			a.settings.CacheDirectory = ""
		}
		a.cacheDirectoryText, a.cacheDirError = c.root, ""
		if c.cache != nil {
			// SoundCloud's cache is kept beside the YouTube Music one, so it
			// moves with it.
			a.openSoundCloudCache()
		}
		a.saveSettings()
	})
}

// desktopCard holds the switches that tie Meiro to the rest of the system:
// notifications, the tray, the global shortcut and starting at login.
func (a *app) desktopCard(c *ui.Context) {
	a.settingsCard(c, "Desktop", m3.IconNotifications, func() {
		ui.Column(c).Gap(4).Children(func() {
			a.desktopSwitch(c, "Track notifications", "Announce each new track. Clicking one brings Meiro back.",
				a.settings.NotifyTrack, func(on bool) {
					a.settings.NotifyTrack = on
					if !on {
						a.notificationArtwork.stop()
					}
				})
			a.desktopSwitch(c, "Menu bar icon", "Keep Meiro in the menu bar with play, next and quit.",
				a.settings.ShowTray, func(on bool) {
					a.settings.ShowTray = on
					a.syncDesktop()
				})
			if trayMarkSupported(runtime.GOOS) {
				ui.Row(c).Gap(16).AlignItems(ui.Center).Padding(8, 0).Children(func() {
					settingLabel(c, "Tray icon", "Choose which icon appears in the system tray; the O mark is more subtle.").Grow(1)
					mark := 0
					if a.settings.TrayMark == settings.TrayMarkOMark {
						mark = 1
					}
					if m3.ButtonGroup(c, "tray-mark", &mark, []string{"Wordmark", "O mark"}, nil) {
						if mark == 1 {
							a.settings.TrayMark = settings.TrayMarkOMark
						} else {
							a.settings.TrayMark = settings.TrayMarkWordmark
						}
						a.syncDesktop()
						a.saveSettings()
					}
				})
			}
			a.desktopSwitch(c, "Close to tray", "Closing the window keeps Meiro running in the tray instead of quitting.",
				a.settings.CloseToTray != nil && *a.settings.CloseToTray, func(on bool) {
					a.answerClose(on, true)
				})
			a.desktopSwitch(c, "Global shortcut", "Play or pause from anywhere with "+toggleShortcut+".",
				a.settings.GlobalShortcut, func(on bool) {
					a.settings.GlobalShortcut = on
					a.syncShortcut()
				})
			a.desktopSwitch(c, "Discord Rich Presence", "Share the track, artist, cover and elapsed time while music plays. The status clears when playback pauses or stops.",
				a.settings.DiscordPresence, func(on bool) {
					a.settings.DiscordPresence = on
					a.syncSystemMedia()
				})
			a.desktopSwitch(c, "Open at login", "Start Meiro when you log in.",
				a.settings.OpenAtLogin, func(on bool) {
					a.settings.OpenAtLogin = on
					a.desktop.setOpenAtLogin(on)
				})
		})
	})
}

// desktopSwitch is one system-integration row: a label and a switch that
// saves through apply.
func (a *app) desktopSwitch(c *ui.Context, title, body string, on bool, apply func(bool)) {
	ui.Row(c).Gap(16).AlignItems(ui.Center).Padding(8, 0).Children(func() {
		settingLabel(c, title, body).Grow(1)
		if m3.Switch(c, &on, title).Changed() {
			apply(on)
			a.saveSettings()
		}
	})
}
