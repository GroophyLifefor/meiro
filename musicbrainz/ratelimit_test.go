package musicbrainz

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"testing"
	"time"
)

func TestLimiterSpacesRequests(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	var slept []time.Duration
	l := newLimiter(time.Second, func() time.Time { return now }, func(_ context.Context, d time.Duration) error {
		slept = append(slept, d)
		now = now.Add(d)
		return nil
	})
	ctx := context.Background()
	call := func() { _ = l.do(ctx, func() error { return nil }) }

	call()
	if len(slept) != 0 {
		t.Fatalf("the first request waited %v", slept)
	}
	call()
	if len(slept) != 1 || slept[0] != time.Second {
		t.Fatalf("the second request waited %v, want one second", slept)
	}
	now = now.Add(time.Second)
	call()
	if len(slept) != 1 {
		t.Fatalf("a request a second later waited again: %v", slept)
	}
}

func TestLimiterRunsOneAtATime(t *testing.T) {
	l := newLimiter(0, nil, nil)
	var inflight, most int32
	var mu sync.Mutex
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = l.do(context.Background(), func() error {
				mu.Lock()
				inflight++
				most = max(most, inflight)
				mu.Unlock()
				time.Sleep(time.Millisecond)
				mu.Lock()
				inflight--
				mu.Unlock()
				return nil
			})
		}()
	}
	wg.Wait()
	if most != 1 {
		t.Fatalf("%d requests ran at once, want one", most)
	}
}

func TestLimiterEndsOnContext(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	l := newLimiter(time.Second, func() time.Time { return now }, func(ctx context.Context, _ time.Duration) error {
		return ctx.Err()
	})
	ctx, cancel := context.WithCancel(context.Background())
	if err := l.do(ctx, func() error { return nil }); err != nil {
		t.Fatalf("the first request failed: %v", err)
	}
	cancel()
	ran := false
	err := l.do(ctx, func() error { ran = true; return nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v, want context.Canceled", err)
	}
	if ran {
		t.Fatal("the work ran although the context had ended")
	}
}

// After the service refuses a request for coming too fast, the next waits out a
// pause that doubles with each refusal in a row, or the one the service named,
// and a request that is answered ends the run of refusals.
func TestLimiterPausesAfterARateLimit(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	var slept []time.Duration
	l := newLimiter(time.Second, func() time.Time { return now }, func(_ context.Context, d time.Duration) error {
		slept = append(slept, d)
		now = now.Add(d)
		return nil
	})
	ctx := context.Background()
	refuse := func(after time.Duration) {
		_ = l.do(ctx, func() error { return &rateLimitError{status: "503", retryAfter: after} })
	}
	answer := func() { _ = l.do(ctx, func() error { return nil }) }
	last := func() time.Duration { return slept[len(slept)-1] }

	refuse(0) // the first request: no wait, then a pause of 10 s
	answer()  // waits the pause out
	if last() < firstPause-time.Second || last() > firstPause {
		t.Errorf("after one refusal the next request waited %v, want about %v", last(), firstPause)
	}
	refuse(0)
	refuse(0)
	answer()
	if want := 2 * firstPause; last() < want-time.Second || last() > want {
		t.Errorf("after two refusals in a row the pause was %v, want about %v", last(), want)
	}
	refuse(45 * time.Second)
	answer()
	if want := 45 * time.Second; last() < want-time.Second || last() > want {
		t.Errorf("a Retry-After of 45 s was waited %v", last())
	}
	for range 10 {
		refuse(0)
	}
	answer()
	if last() > maxPause {
		t.Errorf("the pause grew past the longest, %v: %v", maxPause, last())
	}
	// A request that is answered ends the run, so the next refusal starts from
	// the first pause again.
	answer()
	refuse(0)
	answer()
	if last() < firstPause-time.Second || last() > firstPause {
		t.Errorf("after an answer the pause started again at %v, want about %v", last(), firstPause)
	}
}

func TestRateLimitIsReportedAsSuch(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "7")
		w.WriteHeader(http.StatusServiceUnavailable)
	})
	_, err := client.SearchArtists(context.Background(), "hidra")
	if !errors.Is(err, ErrRateLimited) {
		t.Fatalf("a 503 is %v, want ErrRateLimited", err)
	}
	var limited *rateLimitError
	if !errors.As(err, &limited) || limited.retryAfter != 7*time.Second {
		t.Errorf("Retry-After was read as %+v", limited)
	}
}
