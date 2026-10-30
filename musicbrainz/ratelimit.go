package musicbrainz

import (
	"context"
	"errors"
	"sync"
	"time"
)

// limiter runs requests one at a time, spaced apart. MusicBrainz answers a
// client that asks faster than once a second with HTTP 503, so every request
// waits for the one before it to finish, and then for the gap to pass. The
// lock is held across both, so callers queue rather than race: one request is
// in flight at a time.
//
// The clock and the sleep are fields so that a test can run the queue without
// waiting: it supplies a clock it moves itself and a sleep that records the
// delay instead of taking it.
type limiter struct {
	mu   sync.Mutex
	min  time.Duration
	last time.Time
	// until is when the requests may go on after the service refused one for
	// coming too fast, and strikes is how many refusals in a row led to it.
	until   time.Time
	strikes int
	now     func() time.Time
	sleep   func(context.Context, time.Duration) error
}

// newLimiter returns a limiter that keeps requests at least minGap apart. A
// nil clock is time.Now, and a nil sleep waits as sleepContext does.
func newLimiter(minGap time.Duration, now func() time.Time, sleep func(context.Context, time.Duration) error) *limiter {
	if now == nil {
		now = time.Now
	}
	if sleep == nil {
		sleep = sleepContext
	}
	return &limiter{min: minGap, now: now, sleep: sleep}
}

// do runs fn once the gap since the request before it has passed, and nothing
// else runs through the limiter until fn returns. It returns why the context
// ended when the wait ends first, without running fn.
func (l *limiter) do(ctx context.Context, fn func() error) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	if !l.last.IsZero() {
		if gap := l.min - l.now().Sub(l.last); gap > 0 {
			if err := l.sleep(ctx, gap); err != nil {
				return err
			}
		}
	}
	// After a refusal for asking too fast the service declines everything
	// until the rate falls, so wait out the pause before asking again.
	if wait := l.until.Sub(l.now()); wait > 0 {
		if err := l.sleep(ctx, wait); err != nil {
			return err
		}
	}
	err := fn()
	l.last = l.now()
	var limited *rateLimitError
	switch {
	case errors.As(err, &limited):
		l.strikes++
		pause := limited.retryAfter
		if pause <= 0 {
			pause = min(firstPause<<(l.strikes-1), maxPause)
		}
		l.until = l.last.Add(min(pause, maxPause))
	case err == nil:
		l.strikes = 0
	}
	return err
}

// firstPause is how long the requests stop after the first refusal for asking
// too fast when the service does not say; each refusal in a row doubles it, up
// to maxPause.
const (
	firstPause = 10 * time.Second
	maxPause   = 5 * time.Minute
)

// sleepContext waits for delay, or until ctx ends.
func sleepContext(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
