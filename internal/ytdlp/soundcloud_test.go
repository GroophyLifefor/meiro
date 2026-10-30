package ytdlp

import (
	"context"
	"errors"
	"slices"
	"testing"
	"time"
)

// stubSoundCloud stands in for yt-dlp in a test: it records the arguments and
// answers with output, so the command and the parsing are checked without a
// network or a yt-dlp run.
func stubSoundCloud(t *testing.T, output string, err error) *[]string {
	t.Helper()
	original := runSoundCloud
	t.Cleanup(func() { runSoundCloud = original })
	args := new([]string)
	runSoundCloud = func(_ context.Context, given ...string) ([]byte, error) {
		*args = given
		if err != nil {
			return nil, err
		}
		return []byte(output), nil
	}
	return args
}

func TestResolveSoundCloudRunsTheRightCommand(t *testing.T) {
	pageURL := "https://soundcloud.com/eldjkb/get-lucky-original-version"
	args := stubSoundCloud(t, `{"url":"https://cf-hls-media.sndcdn.com/media/x/playlist.m3u8","duration":246.381}`, nil)

	stream, total, err := ResolveSoundCloud(context.Background(), pageURL)
	if err != nil {
		t.Fatalf("ResolveSoundCloud: %v", err)
	}
	want := []string{"-f", "bestaudio", "--dump-single-json", "--no-warnings", pageURL}
	if !slices.Equal(*args, want) {
		t.Fatalf("args = %q, want %q", *args, want)
	}
	if stream != "https://cf-hls-media.sndcdn.com/media/x/playlist.m3u8" {
		t.Fatalf("stream = %q", stream)
	}
	if total != time.Duration(246.381*float64(time.Second)) {
		t.Fatalf("total = %s", total)
	}
}

func TestResolveSoundCloudReadsTheTrack(t *testing.T) {
	for _, test := range []struct {
		name    string
		output  string
		stream  string
		total   time.Duration
		wantErr bool
	}{
		{"full track", `{"url":"https://x/playlist.m3u8","duration":205.5}`, "https://x/playlist.m3u8", time.Duration(205.5 * float64(time.Second)), false},
		{"preview", `{"url":"https://cf-preview-media.sndcdn.com/preview/0/30/x.mp3","duration":30.0}`, "https://cf-preview-media.sndcdn.com/preview/0/30/x.mp3", 30 * time.Second, false},
		{"missing duration", `{"url":"https://x/y"}`, "https://x/y", 0, false},
		{"missing url", `{"duration":30}`, "", 0, true},
		{"not json", `not json`, "", 0, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			stubSoundCloud(t, test.output, nil)
			stream, total, err := ResolveSoundCloud(context.Background(), "https://soundcloud.com/a/b")
			if test.wantErr {
				if err == nil {
					t.Fatal("expected an error")
				}
				return
			}
			if err != nil {
				t.Fatalf("ResolveSoundCloud: %v", err)
			}
			if stream != test.stream || total != test.total {
				t.Fatalf("got (%q, %s), want (%q, %s)", stream, total, test.stream, test.total)
			}
		})
	}
}

func TestResolveSoundCloudKeepsTheMissingToolError(t *testing.T) {
	stubSoundCloud(t, "", ErrMissing)
	_, _, err := ResolveSoundCloud(context.Background(), "https://soundcloud.com/a/b")
	if !errors.Is(err, ErrMissing) {
		t.Fatalf("err = %v, want ErrMissing", err)
	}
}
