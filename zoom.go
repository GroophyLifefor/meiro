package main

import "github.com/elianiva/meiro/internal/settings"

// zoom is the interface zoom as MyGo takes it: 1 for the interface at its own
// size. Every length the view sees is in the zoomed window's DIPs, so the
// layout, the pointer and the text input all follow it.
func (a *app) zoom() float32 {
	return float32(settings.ClampZoom(a.settings.Zoom)) / 100
}

// zoomTo keeps a zoom, if it is one the interface can show.
func (a *app) zoomTo(percent int) {
	percent = settings.ClampZoom(percent)
	if percent == settings.ClampZoom(a.settings.Zoom) {
		return
	}
	a.settings.Zoom = percent
	a.saveSettings()
}

// zoomBy makes the interface a step larger or smaller.
func (a *app) zoomBy(steps int) {
	a.zoomTo(settings.ClampZoom(a.settings.Zoom) + steps*settings.ZoomStep)
}

// resetZoom puts the interface back to its own size.
func (a *app) resetZoom() { a.zoomTo(settings.DefaultZoom) }
