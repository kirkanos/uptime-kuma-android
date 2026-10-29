package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/kirkanos/uptime-kuma-android/relay/internal/fcm"
)

const (
	relayToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	regToken   = "dGVzdA:APA91bH-example_token-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghij"
	kumaBody   = `{"v":1,"monitor":{"id":12,"name":"Web \"prod\" ✓","type":"http","url":"https://example.com/health","hostname":null,"port":null},"heartbeat":{"status":0,"msg":"timeout of 48000ms exceeded","time":"2026-10-28 20:00:00.123","localDateTime":"2026-10-28 21:00:00","ping":null,"duration":60,"retries":2,"important":true},"msg":"[Web \"prod\" ✓] [🔴 Down] timeout of 48000ms exceeded"}`
)

type fakeSender struct {
	mu    sync.Mutex
	err   error
	calls []call
}

type call struct{ token, body string }

func (f *fakeSender) Send(_ context.Context, token, body string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, call{token, body})
	if f.err != nil {
		return "", f.err
	}
	return "projects/p/messages/1", nil
}

type testEnv struct {
	srv    *Server
	sender *fakeSender
	logs   *bytes.Buffer
	now    *time.Time
}

func newEnv(t *testing.T, mutate func(*Config)) *testEnv {
	t.Helper()
	now := time.Date(2026, 10, 29, 12, 0, 0, 0, time.UTC)
	cfg := Config{
		RelayToken:    relayToken,
		MaxBodyBytes:  4096,
		RatePerMinute: 60,
		RateBurst:     30,
		Version:       "test",
		Now:           func() time.Time { return now },
	}
	if mutate != nil {
		mutate(&cfg)
	}
	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	sender := &fakeSender{}
	return &testEnv{srv: New(cfg, sender, logger), sender: sender, logs: logs, now: &now}
}

func (e *testEnv) do(method, path, auth, body string, hdr ...string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Add(hdr[i], hdr[i+1])
	}
	rec := httptest.NewRecorder()
	e.srv.ServeHTTP(rec, req)
	return rec
}

func TestForwardRequestValidation(t *testing.T) {
	bearer := "Bearer " + relayToken
	tests := []struct {
		name       string
		path       string
		auth       string
		body       string
		wantStatus int
		wantSent   bool
	}{
		{name: "ok", path: "/v1/fcm/" + regToken, auth: bearer, body: kumaBody, wantStatus: 200, wantSent: true},
		{name: "lowercase bearer scheme", path: "/v1/fcm/" + regToken, auth: "bearer " + relayToken, body: kumaBody, wantStatus: 200, wantSent: true},
		{name: "no auth", path: "/v1/fcm/" + regToken, body: kumaBody, wantStatus: 401},
		{name: "wrong token", path: "/v1/fcm/" + regToken, auth: "Bearer " + strings.Repeat("x", 64), body: kumaBody, wantStatus: 401},
		{name: "token prefix only", path: "/v1/fcm/" + regToken, auth: "Bearer " + relayToken[:32], body: kumaBody, wantStatus: 401},
		{name: "basic scheme", path: "/v1/fcm/" + regToken, auth: "Basic " + relayToken, body: kumaBody, wantStatus: 401},
		{name: "short header", path: "/v1/fcm/" + regToken, auth: "Bear", body: kumaBody, wantStatus: 401},
		{name: "auth before token check", path: "/v1/fcm/short", body: kumaBody, wantStatus: 401},
		{name: "body too large", path: "/v1/fcm/" + regToken, auth: bearer, body: strings.Repeat("a", 4097), wantStatus: 413},
		{name: "body at limit", path: "/v1/fcm/" + regToken, auth: bearer, body: strings.Repeat("a", 4096), wantStatus: 200, wantSent: true},
		{name: "token too short", path: "/v1/fcm/" + regToken[:99], auth: bearer, body: kumaBody, wantStatus: 400},
		{name: "token min length", path: "/v1/fcm/" + strings.Repeat("a", 100), auth: bearer, body: kumaBody, wantStatus: 200, wantSent: true},
		{name: "token too long", path: "/v1/fcm/" + strings.Repeat("a", 4097), auth: bearer, body: kumaBody, wantStatus: 400},
		{name: "token bad charset", path: "/v1/fcm/" + regToken[:100] + "%2B", auth: bearer, body: kumaBody, wantStatus: 400},
		{name: "token with dot", path: "/v1/fcm/" + regToken[:100] + ".x", auth: bearer, body: kumaBody, wantStatus: 400},
		{name: "empty body", path: "/v1/fcm/" + regToken, auth: bearer, body: "", wantStatus: 400},
		{name: "invalid utf8", path: "/v1/fcm/" + regToken, auth: bearer, body: "\xff\xfe", wantStatus: 400},
		{name: "missing token segment", path: "/v1/fcm/", auth: bearer, body: kumaBody, wantStatus: 404},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			env := newEnv(t, nil)
			rec := env.do(http.MethodPost, tc.path, tc.auth, tc.body)
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d, body %q", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if sent := len(env.sender.calls) > 0; sent != tc.wantSent {
				t.Fatalf("sent = %v, want %v", sent, tc.wantSent)
			}
			if tc.wantStatus == 401 && rec.Header().Get("WWW-Authenticate") == "" {
				t.Error("401 without WWW-Authenticate")
			}
			if tc.wantSent && tc.name == "ok" {
				c := env.sender.calls[0]
				if c.token != regToken || c.body != kumaBody {
					t.Errorf("forwarded %+v", c)
				}
				var resp map[string]string
				_ = json.Unmarshal(rec.Body.Bytes(), &resp)
				if resp["status"] != "sent" {
					t.Errorf("response %q", rec.Body.String())
				}
			}
			assertNoSecretsLogged(t, env.logs.String())
		})
	}
}

func assertNoSecretsLogged(t *testing.T, logs string) {
	t.Helper()
	for _, secret := range []string{regToken[:40], relayToken, "timeout of 48000ms", "Web \\\"prod\\\""} {
		if strings.Contains(logs, secret) {
			t.Errorf("logs contain secret/body fragment %q:\n%s", secret, logs)
		}
	}
}

func TestBodyTooLargeWithoutContentLength(t *testing.T) {
	env := newEnv(t, func(c *Config) { c.MaxBodyBytes = 10 })
	req := httptest.NewRequest(http.MethodPost, "/v1/fcm/"+regToken, io.NopCloser(strings.NewReader(strings.Repeat("a", 20))))
	req.ContentLength = -1 // chunked
	req.Header.Set("Authorization", "Bearer "+relayToken)
	rec := httptest.NewRecorder()
	env.srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d", rec.Code)
	}
}

type errReader struct{}

func (errReader) Read([]byte) (int, error) { return 0, errors.New("boom") }

func TestBodyReadError(t *testing.T) {
	env := newEnv(t, nil)
	req := httptest.NewRequest(http.MethodPost, "/v1/fcm/"+regToken, errReader{})
	req.Header.Set("Authorization", "Bearer "+relayToken)
	rec := httptest.NewRecorder()
	env.srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestSendErrorMapping(t *testing.T) {
	tests := []struct {
		name           string
		err            error
		wantStatus     int
		wantRetryAfter string
		wantBody       string
	}{
		{name: "unregistered", err: &fcm.SendError{Kind: fcm.KindTokenGone, HTTPStatus: 404, Code: "UNREGISTERED"}, wantStatus: 410, wantBody: "UNREGISTERED"},
		{name: "invalid token", err: &fcm.SendError{Kind: fcm.KindTokenGone, HTTPStatus: 400, Code: "INVALID_ARGUMENT", Message: "The registration token is not a valid FCM registration token"}, wantStatus: 410, wantBody: "not a valid"},
		{name: "bad request", err: &fcm.SendError{Kind: fcm.KindBadRequest, HTTPStatus: 400, Code: "INVALID_ARGUMENT", Message: "Message is too big"}, wantStatus: 400, wantBody: "too big"},
		{name: "sender mismatch", err: &fcm.SendError{Kind: fcm.KindSenderMismatch, HTTPStatus: 403, Code: "SENDER_ID_MISMATCH"}, wantStatus: 403, wantBody: "different Firebase project"},
		{name: "quota", err: &fcm.SendError{Kind: fcm.KindQuota, HTTPStatus: 429, Code: "QUOTA_EXCEEDED", RetryAfter: 60 * time.Second}, wantStatus: 502, wantRetryAfter: "60", wantBody: "QUOTA_EXCEEDED"},
		{name: "unavailable default retry", err: &fcm.SendError{Kind: fcm.KindUnavailable, HTTPStatus: 503, Code: "UNAVAILABLE", Attempts: 2}, wantStatus: 502, wantRetryAfter: "30", wantBody: "retry later"},
		{name: "internal", err: &fcm.SendError{Kind: fcm.KindUnavailable, HTTPStatus: 500, Code: "INTERNAL", Attempts: 2}, wantStatus: 502, wantRetryAfter: "30"},
		{name: "auth", err: &fcm.SendError{Kind: fcm.KindAuth, Err: &fcm.TokenError{HTTPStatus: 400, Code: "invalid_grant"}}, wantStatus: 502, wantBody: "authenticate"},
		{name: "other", err: &fcm.SendError{Kind: fcm.KindOther, HTTPStatus: 409, Code: "ABORTED"}, wantStatus: 502, wantBody: "ABORTED"},
		{name: "plain error", err: errors.New("weird"), wantStatus: 502},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			env := newEnv(t, nil)
			env.sender.err = tc.err
			rec := env.do(http.MethodPost, "/v1/fcm/"+regToken, "Bearer "+relayToken, kumaBody)
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d (%q)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if got := rec.Header().Get("Retry-After"); got != tc.wantRetryAfter {
				t.Errorf("Retry-After = %q, want %q", got, tc.wantRetryAfter)
			}
			if !strings.Contains(rec.Body.String(), tc.wantBody) {
				t.Errorf("body %q does not contain %q", rec.Body.String(), tc.wantBody)
			}
			if !strings.Contains(env.logs.String(), "token="+TokenFingerprint(regToken)) {
				t.Errorf("log lacks token fingerprint:\n%s", env.logs.String())
			}
			assertNoSecretsLogged(t, env.logs.String())
		})
	}
}

func TestRateLimitPerClient(t *testing.T) {
	env := newEnv(t, func(c *Config) { c.RatePerMinute = 60; c.RateBurst = 3 })
	send := func(remote string, hdr ...string) int {
		req := httptest.NewRequest(http.MethodPost, "/v1/fcm/"+regToken, strings.NewReader(kumaBody))
		req.RemoteAddr = remote
		req.Header.Set("Authorization", "Bearer "+relayToken)
		for i := 0; i+1 < len(hdr); i += 2 {
			req.Header.Add(hdr[i], hdr[i+1])
		}
		rec := httptest.NewRecorder()
		env.srv.ServeHTTP(rec, req)
		if rec.Code == 429 && rec.Header().Get("Retry-After") != "1" {
			t.Errorf("Retry-After = %q", rec.Header().Get("Retry-After"))
		}
		return rec.Code
	}
	for i := 0; i < 3; i++ {
		if c := send("10.0.0.1:1234"); c != 200 {
			t.Fatalf("request %d: %d", i, c)
		}
	}
	if c := send("10.0.0.1:1235"); c != 429 {
		t.Fatalf("4th request: %d, want 429", c)
	}
	if c := send("10.0.0.2:1234"); c != 200 {
		t.Fatalf("other client: %d", c)
	}
	// X-Forwarded-For is ignored unless trusted: still the same client.
	if c := send("10.0.0.1:1234", "X-Forwarded-For", "203.0.113.9"); c != 429 {
		t.Fatalf("spoofed XFF: %d, want 429", c)
	}
	*env.now = env.now.Add(time.Second)
	if c := send("10.0.0.1:1234"); c != 200 {
		t.Fatalf("after refill: %d", c)
	}
	if c := send("10.0.0.1:1234"); c != 429 {
		t.Fatalf("after one token used: %d", c)
	}
}

func TestRateLimitTrustForwardedFor(t *testing.T) {
	env := newEnv(t, func(c *Config) { c.RateBurst = 1; c.TrustForwardedFor = true })
	send := func(xff ...string) int {
		req := httptest.NewRequest(http.MethodPost, "/v1/fcm/"+regToken, strings.NewReader(kumaBody))
		req.RemoteAddr = "172.18.0.2:5555" // Traefik
		req.Header.Set("Authorization", "Bearer "+relayToken)
		for _, v := range xff {
			req.Header.Add("X-Forwarded-For", v)
		}
		rec := httptest.NewRecorder()
		env.srv.ServeHTTP(rec, req)
		return rec.Code
	}
	if c := send("1.1.1.1, 198.51.100.7"); c != 200 {
		t.Fatal(c)
	}
	// Same right-most (proxy-appended) address, different spoofed left part: limited.
	if c := send("9.9.9.9, 198.51.100.7"); c != 429 {
		t.Fatalf("got %d, want 429", c)
	}
	if c := send("198.51.100.8"); c != 200 {
		t.Fatalf("different client: %d", c)
	}
	// Multiple header lines: the last one wins.
	if c := send("198.51.100.99", "198.51.100.8"); c != 429 {
		t.Fatalf("multi-line XFF: %d", c)
	}
	// No XFF: falls back to the peer address.
	if c := send(); c != 200 {
		t.Fatalf("no XFF: %d", c)
	}
}

func TestRateLimitDisabled(t *testing.T) {
	env := newEnv(t, func(c *Config) { c.RatePerMinute = 0 })
	for i := 0; i < 100; i++ {
		if rec := env.do(http.MethodPost, "/v1/fcm/"+regToken, "Bearer "+relayToken, kumaBody); rec.Code != 200 {
			t.Fatalf("request %d: %d", i, rec.Code)
		}
	}
}

func TestRateLimiterSweep(t *testing.T) {
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	l := newRateLimiter(60, 30, func() time.Time { return now })
	for _, ip := range []string{"a", "b", "c"} {
		l.allow(ip)
	}
	if l.size() != 3 {
		t.Fatalf("size = %d", l.size())
	}
	now = now.Add(30 * time.Second)
	l.allow("d")
	if l.size() != 4 {
		t.Fatalf("swept too early: %d", l.size())
	}
	now = now.Add(2 * time.Minute)
	l.allow("d")
	if l.size() != 1 {
		t.Fatalf("size after sweep = %d", l.size())
	}
}

func TestHealthAndRoot(t *testing.T) {
	env := newEnv(t, nil)
	if rec := env.do(http.MethodGet, "/healthz", "", ""); rec.Code != 200 || rec.Body.String() != "ok\n" {
		t.Fatalf("healthz = %d %q", rec.Code, rec.Body.String())
	}
	rec := env.do(http.MethodGet, "/", "", "")
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "uptime-kuma-fcm-relay test") {
		t.Fatalf("root = %d %q", rec.Code, rec.Body.String())
	}
	if rec := env.do(http.MethodGet, "/nope", "", ""); rec.Code != 404 {
		t.Fatalf("unknown path = %d", rec.Code)
	}
	if rec := env.do(http.MethodGet, "/v1/fcm/"+regToken, "", ""); rec.Code != 405 {
		t.Fatalf("GET forward = %d", rec.Code)
	}

	bad := newEnv(t, func(c *Config) { c.Ready = func() error { return errors.New("no key") } })
	if rec := bad.do(http.MethodGet, "/healthz", "", ""); rec.Code != 503 {
		t.Fatalf("unhealthy healthz = %d", rec.Code)
	}
}

func TestValidRegistrationToken(t *testing.T) {
	cases := map[string]bool{
		regToken:                        true,
		strings.Repeat("A", 100):        true,
		strings.Repeat("A", 4096):       true,
		strings.Repeat("A", 99):         false,
		strings.Repeat("A", 4097):       false,
		strings.Repeat("A", 99) + "/":   false,
		strings.Repeat("A", 99) + " ":   false,
		strings.Repeat("A", 99) + "é":   false,
		strings.Repeat("a-_:9Z", 20):    true,
		strings.Repeat("A", 120) + "%3": false,
	}
	for tok, want := range cases {
		if got := ValidRegistrationToken(tok); got != want {
			t.Errorf("ValidRegistrationToken(len %d, %q...) = %v, want %v", len(tok), tok[len(tok)-3:], got, want)
		}
	}
}

func TestTokenFingerprint(t *testing.T) {
	fp := TokenFingerprint(regToken)
	if len(fp) != 12 || strings.Contains(regToken, fp) {
		t.Fatalf("fingerprint %q", fp)
	}
	if fp == TokenFingerprint(regToken+"x") {
		t.Fatal("fingerprints collide")
	}
}
