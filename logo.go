package main

import (
	_ "embed"
	"log"

	"github.com/egoist/mygo/ui"
)

// iconPNG is the app's logo: the picture of its window in the taskbar and the
// one the navigation rail wears. It is embedded, so it shows however the app
// was started, with or without its resources folder beside it.
//
//go:embed resources/icon.png
var iconPNG []byte

// logoBitmap is the logo as a picture for the interface, decoded once on first
// use. It is nil when the picture cannot be read, and the rail goes without.
func (a *app) logoBitmap() *ui.Bitmap {
	if a.logo != nil || a.logoFailed {
		return a.logo
	}
	bitmap, err := ui.DecodeBitmap(iconPNG)
	if err != nil {
		log.Printf("reading the logo: %v", err)
		a.logoFailed = true
		return nil
	}
	a.logo = bitmap
	return bitmap
}

// applyWindowIcon gives the window the logo, which the taskbar and the window's
// switcher show.
func (a *app) applyWindowIcon() {
	if a.desktop == nil || a.desktop.win == nil {
		return
	}
	if err := a.desktop.win.SetIcon(iconPNG); err != nil {
		log.Printf("setting the window icon: %v", err)
	}
}
