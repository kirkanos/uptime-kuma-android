package server

import (
	"math"
	"sync"
	"time"
)

// rateLimiter is a token bucket per client key (remote IP). Buckets idle for longer than it
// takes to refill completely are dropped, so memory stays bounded by the number of recently
// active clients.
type rateLimiter struct {
	ratePerSec float64
	burst      float64
	now        func() time.Time

	mu        sync.Mutex
	buckets   map[string]*bucket
	lastSweep time.Time
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newRateLimiter(perMinute float64, burst int, now func() time.Time) *rateLimiter {
	return &rateLimiter{
		ratePerSec: perMinute / 60,
		burst:      float64(burst),
		now:        now,
		buckets:    make(map[string]*bucket),
		lastSweep:  now(),
	}
}

// allow takes one token for key. When the bucket is empty it returns false and how long until
// the next token is available.
func (l *rateLimiter) allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.sweep(now)

	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	} else {
		elapsed := now.Sub(b.last).Seconds()
		if elapsed > 0 {
			b.tokens = math.Min(l.burst, b.tokens+elapsed*l.ratePerSec)
		}
		b.last = now
	}
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	wait := time.Duration((1 - b.tokens) / l.ratePerSec * float64(time.Second))
	return false, wait
}

func (l *rateLimiter) sweep(now time.Time) {
	if now.Sub(l.lastSweep) < time.Minute {
		return
	}
	l.lastSweep = now
	full := time.Duration(l.burst/l.ratePerSec*float64(time.Second)) + time.Minute
	for k, b := range l.buckets {
		if now.Sub(b.last) > full {
			delete(l.buckets, k)
		}
	}
}

func (l *rateLimiter) size() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}
