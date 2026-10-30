package main

import (
	"context"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/plugins/updater"
	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/internal/report"
	"github.com/elianiva/meiro/m3"
)

// credit says who makes this Meiro, which grew out of a fork of the original.
const credit = "Meiro fork'u üzerine Murat Kirazkaya tarafından geliştirilmiştir"

// aboutCard leads About and support with who makes the app.
func (a *app) aboutCard(c *ui.Context) {
	a.settingsCard(c, "About", m3.IconPerson, func() {
		settingLabel(c, credit, "")
	})
}

// updatesCard is where the user checks for updates by hand: Meiro never looks
// for one on its own. Builds made without update signing say so instead.
func (a *app) updatesCard(c *ui.Context) {
	a.settingsCard(c, "Updates", m3.IconRefresh, func() {
		version := mygo.App.Version()
		if version == "" {
			version = "a development build"
		}
		if !a.desktop.updates() {
			settingLabel(c, "Meiro "+version, "Updates come with a signed release. Development builds and package installs update another way.").Grow(1)
			return
		}
		ui.Column(c).Gap(12).Children(func() {
			ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
				settingLabel(c, "Meiro "+version, updateSubtitle()).Grow(1)
				if m3.Button(c, m3.ButtonSpec{Label: "Check for updates", Kind: m3.Tonal, Size: m3.Medium56, Key: "check-updates"}).Clicked() {
					updater.CheckForUpdates()
				}
			})
		})
	})
}

// updateSubtitle names the last successful update check, when there was one.
func updateSubtitle() string {
	if last := updater.LastCheck(); !last.IsZero() {
		return "Last checked " + last.Format("Jan 2, 15:04") + "."
	}
	return "Not checked yet."
}

// loadToolVersions reads the versions of yt-dlp and ffmpeg when Settings
// opens, once: they only change with the app. Reading them runs the tools, so
// it happens off the main thread.
func (a *app) loadToolVersions() {
	if a.toolsLoaded || a.toolsLookup.busy() {
		return
	}
	version := mygo.App.Version()
	startTask(a, &a.toolsLookup, reportTimeout, func(ctx context.Context) (report.Info, error) {
		return a.collectInfo(ctx, version), nil
	}, func(info report.Info, _ error) {
		a.tools, a.toolsLoaded = info, true
	})
}

// toolsCard shows the versions of the playback programs the app finds. yt-dlp
// is refreshed with every release in the full build, so its version tells
// whether a bug report comes from a stale bundle or system install.
func (a *app) toolsCard(c *ui.Context) {
	a.settingsCard(c, "Playback tools", m3.IconSettings, func() {
		yt, ff := "Looking up…", "Looking up…"
		if a.toolsLoaded {
			yt, ff = a.tools.YtDlp, a.tools.FFmpeg
		}
		settingLabel(c, "yt-dlp "+yt, "Finds the audio of each track. The full build includes it; the minimal build uses PATH and needs Node.js or Bun.")
		settingLabel(c, "ffmpeg "+ff, "Decodes audio for playback. The full build includes it; the minimal build uses the version on PATH.")
	})
}

// reportTimeout bounds reading the tool versions for a report.
const reportTimeout = 15 * time.Second

// logsCard is where the user finds the log file, which says what went wrong
// when something did.
func (a *app) logsCard(c *ui.Context) {
	a.settingsCard(c, "Logs", m3.IconFolder, func() {
		ui.Row(c).Gap(16).AlignItems(ui.Center).Children(func() {
			body := "Meiro keeps a log of what it does and what goes wrong, with no passwords or cookies in it."
			if a.logsDir == "" {
				body = "Meiro could not open its log file, so nothing is being logged."
			}
			settingLabel(c, "Log files", body).Grow(1)
			if m3.Button(c, m3.ButtonSpec{Label: "Open logs folder", Kind: m3.Tonal, Size: m3.Medium56, Disabled: a.logsDir == "", Key: "open-logs"}).Clicked() {
				a.openLogs()
			}
		})
	})
}

// openLogs shows the folder of the log files in the system's file manager.
func (a *app) openLogs() {
	dir := a.logsDir
	if dir == "" {
		return
	}
	spawn(a, func() error { return a.openPath(dir) }, func(err error) {
		if err != nil {
			a.notice = "Could not open the logs folder: " + err.Error()
		}
	})
}
