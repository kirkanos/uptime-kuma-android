package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func writeServiceAccount(t *testing.T, projectID string) string {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(k)
	sa, _ := json.Marshal(map[string]string{
		"type":         "service_account",
		"project_id":   projectID,
		"private_key":  string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})),
		"client_email": "relay@demo.iam.gserviceaccount.com",
	})
	p := filepath.Join(t.TempDir(), "sa.json")
	if err := os.WriteFile(p, sa, 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func freePort(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().String()
}

func TestRunStartupErrors(t *testing.T) {
	token := strings.Repeat("t", 32)
	noProject := writeServiceAccount(t, "")
	tests := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{name: "invalid config", env: map[string]string{}, wantErr: "invalid configuration"},
		{name: "missing file", env: map[string]string{"FCM_SERVICE_ACCOUNT_FILE": "/nonexistent/sa.json", "RELAY_TOKEN": token}, wantErr: "reading service account file"},
		{name: "no project id", env: map[string]string{"FCM_SERVICE_ACCOUNT_FILE": noProject, "RELAY_TOKEN": token}, wantErr: "no Firebase project id"},
		{name: "listen fails", env: map[string]string{"FCM_SERVICE_ACCOUNT_FILE": noProject, "FCM_PROJECT_ID": "p", "RELAY_TOKEN": token, "LISTEN_ADDR": "256.0.0.1:1"}, wantErr: "listen"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := run(context.Background(), envMap(tc.env), io.Discard)
			if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("err = %v, want containing %q", err, tc.wantErr)
			}
		})
	}
}

func TestRunServesAndShutsDown(t *testing.T) {
	addr := freePort(t)
	env := map[string]string{
		"FCM_SERVICE_ACCOUNT_FILE": writeServiceAccount(t, "demo"),
		"RELAY_TOKEN":              strings.Repeat("t", 32),
		"LISTEN_ADDR":              addr,
		"MAX_BODY_BYTES":           "8192",
	}
	ctx, cancel := context.WithCancel(context.Background())
	logs := &syncBuffer{}
	done := make(chan error, 1)
	go func() { done <- run(ctx, envMap(env), logs) }()

	deadline := time.Now().Add(5 * time.Second)
	for healthcheck(addr) != 0 {
		if time.Now().After(deadline) {
			t.Fatalf("server did not become healthy; logs:\n%s", logs.String())
		}
		time.Sleep(20 * time.Millisecond)
	}
	resp, err := http.Post("http://"+addr+"/v1/fcm/x", "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("unauthenticated POST = %d", resp.StatusCode)
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("run returned %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("run did not shut down")
	}
	out := logs.String()
	for _, want := range []string{"relay started", "project_id=demo", "above FCM's 4096-byte", "shutting down"} {
		if !strings.Contains(out, want) {
			t.Errorf("logs lack %q:\n%s", want, out)
		}
	}
}

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func envMap(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestLoadConfig(t *testing.T) {
	valid := map[string]string{
		"FCM_SERVICE_ACCOUNT_FILE": "/secrets/sa.json",
		"RELAY_TOKEN":              strings.Repeat("a", 32),
	}
	with := func(kv ...string) map[string]string {
		m := map[string]string{}
		for k, v := range valid {
			m[k] = v
		}
		for i := 0; i+1 < len(kv); i += 2 {
			m[kv[i]] = kv[i+1]
		}
		return m
	}
	tests := []struct {
		name    string
		env     map[string]string
		wantErr string
		check   func(t *testing.T, c config)
	}{
		{name: "defaults", env: valid, check: func(t *testing.T, c config) {
			if c.ListenAddr != ":8080" || c.MaxBodyBytes != 4096 || c.LogLevel != slog.LevelInfo ||
				c.RatePerMinute != 60 || c.RateBurst != 30 || c.TrustForwardedFor || c.ProjectID != "" {
				t.Errorf("unexpected defaults %+v", c)
			}
		}},
		{name: "overrides", env: with("LISTEN_ADDR", "127.0.0.1:9000", "MAX_BODY_BYTES", "2048", "LOG_LEVEL", "debug",
			"FCM_PROJECT_ID", "other", "RATE_LIMIT_PER_MINUTE", "120", "RATE_LIMIT_BURST", "5", "TRUST_FORWARDED_FOR", "true"),
			check: func(t *testing.T, c config) {
				if c.ListenAddr != "127.0.0.1:9000" || c.MaxBodyBytes != 2048 || c.LogLevel != slog.LevelDebug ||
					c.ProjectID != "other" || c.RatePerMinute != 120 || c.RateBurst != 5 || !c.TrustForwardedFor {
					t.Errorf("unexpected config %+v", c)
				}
			}},
		{name: "no service account", env: with("FCM_SERVICE_ACCOUNT_FILE", ""), wantErr: "FCM_SERVICE_ACCOUNT_FILE"},
		{name: "no relay token", env: with("RELAY_TOKEN", ""), wantErr: "RELAY_TOKEN"},
		{name: "short relay token", env: with("RELAY_TOKEN", strings.Repeat("a", 31)), wantErr: "at least 32"},
		{name: "relay token whitespace only counts trimmed", env: with("RELAY_TOKEN", "  "+strings.Repeat("a", 31)+"  "), wantErr: "RELAY_TOKEN"},
		{name: "bad listen", env: with("LISTEN_ADDR", "8080"), wantErr: "LISTEN_ADDR"},
		{name: "bad body size", env: with("MAX_BODY_BYTES", "0"), wantErr: "MAX_BODY_BYTES"},
		{name: "bad log level", env: with("LOG_LEVEL", "loud"), wantErr: "LOG_LEVEL"},
		{name: "bad rate", env: with("RATE_LIMIT_PER_MINUTE", "-1"), wantErr: "RATE_LIMIT_PER_MINUTE"},
		{name: "bad burst", env: with("RATE_LIMIT_BURST", "0"), wantErr: "RATE_LIMIT_BURST"},
		{name: "bad trust", env: with("TRUST_FORWARDED_FOR", "maybe"), wantErr: "TRUST_FORWARDED_FOR"},
		{name: "multiple errors reported", env: map[string]string{}, wantErr: "FCM_SERVICE_ACCOUNT_FILE is required\nRELAY_TOKEN"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			c, err := loadConfig(envMap(tc.env))
			if tc.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("err = %v, want containing %q", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			tc.check(t, c)
		})
	}
}

func TestHealthURL(t *testing.T) {
	cases := map[string]string{
		"":               "http://127.0.0.1:8080/healthz",
		":8080":          "http://127.0.0.1:8080/healthz",
		"0.0.0.0:9000":   "http://127.0.0.1:9000/healthz",
		"[::]:9000":      "http://127.0.0.1:9000/healthz",
		"127.0.0.1:1234": "http://127.0.0.1:1234/healthz",
		"[::1]:1234":     "http://[::1]:1234/healthz",
	}
	for in, want := range cases {
		got, err := healthURL(in)
		if err != nil || got != want {
			t.Errorf("healthURL(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	if _, err := healthURL("nope"); err == nil {
		t.Error("expected error")
	}
}

func TestHealthcheckCommand(t *testing.T) {
	status := http.StatusOK
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/healthz" {
			t.Errorf("path %q", r.URL.Path)
		}
		w.WriteHeader(status)
	}))
	defer srv.Close()
	_, port, _ := net.SplitHostPort(strings.TrimPrefix(srv.URL, "http://"))

	if code := healthcheck(":" + port); code != 0 {
		t.Fatalf("healthy: exit %d", code)
	}
	status = http.StatusServiceUnavailable
	if code := healthcheck(":" + port); code != 1 {
		t.Fatalf("unhealthy: exit %d", code)
	}
	srv.Close()
	if code := healthcheck(":" + port); code != 1 {
		t.Fatalf("down: exit %d", code)
	}
	if code := healthcheck("bogus"); code != 1 {
		t.Fatalf("bad addr: exit %d", code)
	}
}
