package main

import (
	"testing"

	"github.com/egoist/mygo"

	"github.com/elianiva/meiro/m3"
)

// mygoThemeSource gives the appearance the desktop's window buttons follow,
// so each mode Meiro offers reaches the matching MyGo source.
func TestMygoThemeSource(t *testing.T) {
	cases := []struct {
		name string
		mode m3.Mode
		want mygo.ThemeSource
	}{
		{"System", m3.System, mygo.ThemeSystem},
		{"Light", m3.Light, mygo.ThemeLight},
		{"Dark", m3.Dark, mygo.ThemeDark},
	}
	for _, tc := range cases {
		if got := mygoThemeSource(tc.mode); got != tc.want {
			t.Errorf("mygoThemeSource(%s) = %q, want %q", tc.name, got, tc.want)
		}
	}
}
