package main

import (
	"strconv"

	"github.com/egoist/mygo/ui"

	"github.com/elianiva/meiro/m3"
	"github.com/elianiva/meiro/youtube"
)

// accountButton is the Meiro account's picture in the top bar, which opens a
// menu of the account, the settings and the sign-in. The account is the one on
// the Meiro server; a YouTube Music session is a provider's and lives in
// Settings.
func (a *app) accountButton(c *ui.Context) {
	sc := m3.Of(c).Scheme
	button := ui.ButtonBase(c.Key("account"))
	button.Size(48, 48).Radius(m3.Full).Center().Cursor(ui.CursorPointer).Label("Account").Tooltip("Meiro account")
	if button.Hovered() || a.menuOpen {
		button.Background(sc.OnSurface.Alpha(m3.StateHover))
	}
	email := a.settings.SyncEmail
	button.Children(func() {
		if email != "" {
			a.avatar(c, email, "", 96, 36)
			return
		}
		ui.Box(c).Size(36, 36).Radius(m3.Full).Background(sc.SurfaceContainerHighest).Center().Children(func() {
			ui.Icon(c, m3.IconPerson).FontSize(22).TextColor(sc.OnSurfaceVariant)
		})
	})
	if button.Clicked() {
		a.menuOpen = !a.menuOpen
	}
	m3.Menu(c, button, &a.menuOpen, 280, func() {
		ui.Column(c).Padding(12, 12, 8).Gap(2).Children(func() {
			m3.EmphasizedText(c, m3.TitleSmall, "Meiro account").SingleLine()
			if email != "" {
				m3.Text(c, m3.BodySmall, email).SingleLine().Tooltip(email).TextColor(sc.OnSurfaceVariant)
			} else {
				m3.Text(c, m3.BodySmall, "Not signed in").SingleLine().TextColor(sc.OnSurfaceVariant)
			}
		})
		ui.Divider(c)
		if m3.MenuItem(c, "Settings", m3.IconSettings).Clicked() {
			a.menuOpen = false
			a.navigate(routeSettings)
		}
		if email != "" {
			if m3.MenuItem(c, "Sign out", m3.IconLogout).Clicked() {
				a.menuOpen = false
				a.signOutSync()
			}
		} else if m3.MenuItem(c, "Sign in", m3.IconLogin).Clicked() {
			// The sign-in form is the first card of Settings.
			a.menuOpen = false
			a.navigate(routeSettings)
		}
	})
}

// channelItems lists the channels the YouTube Music account can act as. It
// shows nothing when there is only one, and marks the one in use.
func (a *app) channelItems(c *ui.Context, current *session) {
	if len(current.channels) < 2 {
		return
	}
	hasSelectedChannel := false
	for _, channel := range current.channels {
		if channel.Selected {
			hasSelectedChannel = true
			break
		}
	}
	sc := m3.Of(c).Scheme
	m3.Text(c, m3.LabelMedium, "Channel").Padding(12, 12, 4).TextColor(sc.OnSurfaceVariant)
	for i, channel := range current.channels {
		name := channel.Name
		if name == "" {
			name = channel.Handle
		}
		row := ui.ButtonBase(c.Key("channel-" + channel.ChannelID + "-" + strconv.Itoa(i)))
		row.Height(48).PaddingX(12).Gap(12).AlignItems(ui.Center).Radius(m3.Medium).Cursor(ui.CursorPointer).Label(name).
			Background(m3.StateFill(ui.Transparent, sc.OnSurface, row.Hovered(), row.Pressed(), row.FocusVisible()))
		selected := accountChannelSelected(channel, hasSelectedChannel, current.channel)
		row.Children(func() {
			if selected {
				ui.Icon(c, m3.IconCheck).FontSize(22).TextColor(sc.Primary)
			} else {
				ui.Box(c).Size(22, 22)
			}
			m3.Text(c, m3.BodyLarge, name).Grow(1).SingleLine().Tooltip(name).TextColor(sc.OnSurface)
		})
		if row.Clicked() && !selected {
			a.switchChannel(channel.ChannelID)
		}
	}
}

// accountChannelSelected uses YouTube's selected bit when it gives one. An
// account can omit channel IDs, so an empty ID must never make every row look
// selected just because the session uses its default channel.
func accountChannelSelected(channel youtube.AccountChannel, hasSelectedChannel bool, current string) bool {
	if hasSelectedChannel {
		return channel.Selected
	}
	return current != "" && channel.ChannelID == current
}

// advancedIcon points down while the advanced sign-in options are folded away
// and up while they show.
func advancedIcon(open bool) *ui.SVG {
	if open {
		return m3.IconExpandLess
	}
	return m3.IconExpandMore
}
