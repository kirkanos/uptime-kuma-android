package fcm

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

var (
	keyOnce sync.Once
	testKey *rsa.PrivateKey
)

func key(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	keyOnce.Do(func() {
		k, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			panic(err)
		}
		testKey = k
	})
	return testKey
}

func pkcs8PEM(t *testing.T, k *rsa.PrivateKey) string {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
}

func serviceAccountJSON(t *testing.T, mutate func(m map[string]any)) []byte {
	t.Helper()
	m := map[string]any{
		"type":           "service_account",
		"project_id":     "demo-project",
		"private_key_id": "kid123",
		"private_key":    pkcs8PEM(t, key(t)),
		"client_email":   "firebase-adminsdk@demo-project.iam.gserviceaccount.com",
		"token_uri":      "https://oauth2.googleapis.com/token",
	}
	if mutate != nil {
		mutate(m)
	}
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestParseCredentials(t *testing.T) {
	pkcs1 := string(pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key(t))}))
	tests := []struct {
		name    string
		data    []byte
		wantErr string
		check   func(t *testing.T, c *Credentials)
	}{
		{name: "pkcs8", data: serviceAccountJSON(t, nil), check: func(t *testing.T, c *Credentials) {
			if c.ProjectID != "demo-project" || c.KeyID != "kid123" || c.ClientEmail == "" || c.Key == nil {
				t.Errorf("unexpected credentials %+v", c)
			}
		}},
		{name: "pkcs1", data: serviceAccountJSON(t, func(m map[string]any) { m["private_key"] = pkcs1 })},
		{name: "default token uri", data: serviceAccountJSON(t, func(m map[string]any) { delete(m, "token_uri") }), check: func(t *testing.T, c *Credentials) {
			if c.TokenURI != DefaultTokenURI {
				t.Errorf("TokenURI = %q", c.TokenURI)
			}
		}},
		{name: "not json", data: []byte("{"), wantErr: "not valid JSON"},
		{name: "wrong type", data: serviceAccountJSON(t, func(m map[string]any) { m["type"] = "authorized_user" }), wantErr: "type"},
		{name: "no email", data: serviceAccountJSON(t, func(m map[string]any) { delete(m, "client_email") }), wantErr: "client_email"},
		{name: "no key", data: serviceAccountJSON(t, func(m map[string]any) { delete(m, "private_key") }), wantErr: "no private_key"},
		{name: "not pem", data: serviceAccountJSON(t, func(m map[string]any) { m["private_key"] = "garbage" }), wantErr: "PEM"},
		{name: "bad der", data: serviceAccountJSON(t, func(m map[string]any) {
			m["private_key"] = string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: []byte("nope")}))
		}), wantErr: "parsing"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			c, err := ParseCredentials(tc.data)
			if tc.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("err = %v, want containing %q", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if tc.check != nil {
				tc.check(t, c)
			}
		})
	}
}

func TestLoadCredentials(t *testing.T) {
	p := filepath.Join(t.TempDir(), "sa.json")
	if err := os.WriteFile(p, serviceAccountJSON(t, nil), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadCredentials(p); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadCredentials(filepath.Join(t.TempDir(), "missing.json")); err == nil {
		t.Fatal("expected error for missing file")
	}
}

// fakeTokenEndpoint verifies the JWT assertion and hands out numbered access tokens.
type fakeTokenEndpoint struct {
	t        *testing.T
	pub      *rsa.PublicKey
	calls    atomic.Int32
	status   int
	errBody  string
	lastAud  string
	lastKid  string
	lastIss  string
	lastScop string
}

func (f *fakeTokenEndpoint) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	n := f.calls.Add(1)
	if err := r.ParseForm(); err != nil {
		f.t.Errorf("parse form: %v", err)
	}
	if got := r.PostForm.Get("grant_type"); got != "urn:ietf:params:oauth:grant-type:jwt-bearer" {
		f.t.Errorf("grant_type = %q", got)
	}
	parts := strings.Split(r.PostForm.Get("assertion"), ".")
	if len(parts) != 3 {
		f.t.Fatalf("assertion has %d parts", len(parts))
	}
	dec := base64.RawURLEncoding
	sig, _ := dec.DecodeString(parts[2])
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err := rsa.VerifyPKCS1v15(f.pub, crypto.SHA256, digest[:], sig); err != nil {
		f.t.Errorf("assertion signature invalid: %v", err)
	}
	var hdr map[string]string
	hb, _ := dec.DecodeString(parts[0])
	_ = json.Unmarshal(hb, &hdr)
	if hdr["alg"] != "RS256" {
		f.t.Errorf("alg = %q", hdr["alg"])
	}
	f.lastKid = hdr["kid"]
	var claims map[string]any
	cb, _ := dec.DecodeString(parts[1])
	_ = json.Unmarshal(cb, &claims)
	f.lastAud, _ = claims["aud"].(string)
	f.lastIss, _ = claims["iss"].(string)
	f.lastScop, _ = claims["scope"].(string)
	if exp, iat := claims["exp"].(float64), claims["iat"].(float64); exp-iat != 3600 {
		f.t.Errorf("exp-iat = %v", exp-iat)
	}
	if f.status != 0 {
		w.WriteHeader(f.status)
		_, _ = io.WriteString(w, f.errBody)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"access_token": "at-" + string(rune('0'+n)),
		"expires_in":   3599,
		"token_type":   "Bearer",
	})
}

func newTokenSource(t *testing.T, f *fakeTokenEndpoint) (*JWTTokenSource, *time.Time) {
	t.Helper()
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	creds, err := ParseCredentials(serviceAccountJSON(t, func(m map[string]any) { m["token_uri"] = srv.URL + "/token" }))
	if err != nil {
		t.Fatal(err)
	}
	f.t = t
	f.pub = &creds.Key.PublicKey
	ts := NewJWTTokenSource(creds, srv.Client())
	now := time.Date(2026, 10, 29, 12, 0, 0, 0, time.UTC)
	ts.Now = func() time.Time { return now }
	return ts, &now
}

func TestJWTTokenSourceCachesAndRefreshes(t *testing.T) {
	f := &fakeTokenEndpoint{}
	ts, now := newTokenSource(t, f)
	ctx := context.Background()

	tok, err := ts.Token(ctx)
	if err != nil || tok != "at-1" {
		t.Fatalf("Token = %q, %v", tok, err)
	}
	if f.lastIss != "firebase-adminsdk@demo-project.iam.gserviceaccount.com" || f.lastScop != MessagingScope ||
		!strings.HasSuffix(f.lastAud, "/token") || f.lastKid != "kid123" {
		t.Errorf("claims iss=%q scope=%q aud=%q kid=%q", f.lastIss, f.lastScop, f.lastAud, f.lastKid)
	}

	*now = now.Add(50 * time.Minute)
	if tok, _ := ts.Token(ctx); tok != "at-1" || f.calls.Load() != 1 {
		t.Fatalf("expected cached token, got %q after %d calls", tok, f.calls.Load())
	}

	*now = now.Add(8 * time.Minute) // within the refresh margin of the 3599 s lifetime
	if tok, _ := ts.Token(ctx); tok != "at-2" {
		t.Fatalf("expected refreshed token, got %q", tok)
	}

	ts.Invalidate()
	if tok, _ := ts.Token(ctx); tok != "at-3" {
		t.Fatalf("expected new token after Invalidate, got %q", tok)
	}
}

func TestJWTTokenSourceError(t *testing.T) {
	f := &fakeTokenEndpoint{status: http.StatusBadRequest, errBody: `{"error":"invalid_grant","error_description":"Invalid JWT Signature."}`}
	ts, _ := newTokenSource(t, f)
	_, err := ts.Token(context.Background())
	var te *TokenError
	if !errors.As(err, &te) || te.Code != "invalid_grant" || te.HTTPStatus != 400 {
		t.Fatalf("err = %#v", err)
	}
	if !strings.Contains(err.Error(), "Invalid JWT Signature") {
		t.Errorf("error text %q", err.Error())
	}

	f.status, f.errBody = http.StatusOK, "{}"
	if _, err := ts.Token(context.Background()); err == nil || !strings.Contains(err.Error(), "no access_token") {
		t.Fatalf("err = %v", err)
	}
}

// staticTokens is a TokenSource for client tests.
type staticTokens struct {
	err         error
	invalidated atomic.Int32
}

func (s *staticTokens) Token(context.Context) (string, error) {
	if s.err != nil {
		return "", s.err
	}
	return "access-" + string(rune('0'+s.invalidated.Load())), nil
}
func (s *staticTokens) Invalidate() { s.invalidated.Add(1) }

type fcmResponse struct {
	status     int
	body       string
	retryAfter string
}

// fakeFCM answers with the queued responses in order (the last one repeats) and records requests.
type fakeFCM struct {
	t         *testing.T
	responses []fcmResponse
	mu        sync.Mutex
	requests  []recorded
}

type recorded struct {
	path, auth string
	body       map[string]any
}

func (f *fakeFCM) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var body map[string]any
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		f.t.Errorf("decode request: %v", err)
	}
	f.requests = append(f.requests, recorded{path: r.URL.Path, auth: r.Header.Get("Authorization"), body: body})
	i := len(f.requests) - 1
	if i >= len(f.responses) {
		i = len(f.responses) - 1
	}
	resp := f.responses[i]
	if resp.retryAfter != "" {
		w.Header().Set("Retry-After", resp.retryAfter)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.status)
	_, _ = io.WriteString(w, resp.body)
}

func newClient(t *testing.T, tokens TokenSource, responses ...fcmResponse) (*Client, *fakeFCM, *[]time.Duration) {
	t.Helper()
	f := &fakeFCM{t: t, responses: responses}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	c := NewClient("demo-project", tokens, srv.Client())
	c.Endpoint = srv.URL
	var slept []time.Duration
	c.Sleep = func(_ context.Context, d time.Duration) error {
		slept = append(slept, d)
		return nil
	}
	return c, f, &slept
}

const regToken = "dGVzdA:APA91bH-example_token-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghij"

func fcmErr(code int, status, fcmCode, msg string, extra ...string) string {
	details := []string{}
	if fcmCode != "" {
		details = append(details, `{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"`+fcmCode+`"}`)
	}
	details = append(details, extra...)
	b, _ := json.Marshal(msg)
	return `{"error":{"code":` + itoa(code) + `,"message":` + string(b) + `,"status":"` + status + `","details":[` + strings.Join(details, ",") + `]}}`
}

func itoa(i int) string {
	b, _ := json.Marshal(i)
	return string(b)
}

const tokenViolation = `{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{"field":"message.token","description":"Invalid registration token"}]}`

func TestSendSuccessPayloadShape(t *testing.T) {
	c, f, _ := newClient(t, &staticTokens{}, fcmResponse{status: 200, body: `{"name":"projects/demo-project/messages/0:123"}`})
	body := `{"v":1,"monitor":{"id":12,"name":"Web \"prod\" ✓"},"msg":"[Web] [🔴 Down] timeout"}`
	name, err := c.Send(context.Background(), regToken, body)
	if err != nil {
		t.Fatal(err)
	}
	if name != "projects/demo-project/messages/0:123" {
		t.Errorf("name = %q", name)
	}
	if len(f.requests) != 1 {
		t.Fatalf("%d requests", len(f.requests))
	}
	req := f.requests[0]
	if req.path != "/v1/projects/demo-project/messages:send" {
		t.Errorf("path = %q", req.path)
	}
	if req.auth != "Bearer access-0" {
		t.Errorf("auth = %q", req.auth)
	}
	want := map[string]any{
		"message": map[string]any{
			"token":   regToken,
			"data":    map[string]any{"v": "1", "body": body},
			"android": map[string]any{"priority": "HIGH", "ttl": "3600s"},
		},
	}
	got, _ := json.Marshal(req.body)
	exp, _ := json.Marshal(want)
	if string(got) != string(exp) {
		t.Errorf("payload\n got %s\nwant %s", got, exp)
	}
}

func TestSendErrorMapping(t *testing.T) {
	tests := []struct {
		name         string
		responses    []fcmResponse
		tokenErr     error
		wantKind     ErrorKind
		wantCode     string
		wantAttempts int
		wantSleeps   []time.Duration
		wantInval    int32
	}{
		{
			name:         "unregistered",
			responses:    []fcmResponse{{status: 404, body: fcmErr(404, "NOT_FOUND", "UNREGISTERED", "Requested entity was not found.")}},
			wantKind:     KindTokenGone,
			wantCode:     "UNREGISTERED",
			wantAttempts: 1,
		},
		{
			name:         "invalid token via field violation",
			responses:    []fcmResponse{{status: 400, body: fcmErr(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "The registration token is not a valid FCM registration token", tokenViolation)}},
			wantKind:     KindTokenGone,
			wantCode:     "INVALID_ARGUMENT",
			wantAttempts: 1,
		},
		{
			name:         "invalid token via message only",
			responses:    []fcmResponse{{status: 400, body: fcmErr(400, "INVALID_ARGUMENT", "", "The registration token is not a valid FCM registration token")}},
			wantKind:     KindTokenGone,
			wantCode:     "INVALID_ARGUMENT",
			wantAttempts: 1,
		},
		{
			name:         "invalid argument other than token",
			responses:    []fcmResponse{{status: 400, body: fcmErr(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "Message is too big")}},
			wantKind:     KindBadRequest,
			wantCode:     "INVALID_ARGUMENT",
			wantAttempts: 1,
		},
		{
			name:         "sender id mismatch",
			responses:    []fcmResponse{{status: 403, body: fcmErr(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH", "SenderId mismatch")}},
			wantKind:     KindSenderMismatch,
			wantCode:     "SENDER_ID_MISMATCH",
			wantAttempts: 1,
		},
		{
			name:         "quota exceeded is not retried",
			responses:    []fcmResponse{{status: 429, retryAfter: "60", body: fcmErr(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED", "Quota exceeded")}},
			wantKind:     KindQuota,
			wantCode:     "QUOTA_EXCEEDED",
			wantAttempts: 1,
		},
		{
			name: "unavailable then success",
			responses: []fcmResponse{
				{status: 503, body: fcmErr(503, "UNAVAILABLE", "UNAVAILABLE", "The service is currently unavailable.")},
				{status: 200, body: `{"name":"projects/demo-project/messages/1"}`},
			},
			wantSleeps: []time.Duration{time.Second},
		},
		{
			name: "internal twice",
			responses: []fcmResponse{
				{status: 500, body: fcmErr(500, "INTERNAL", "INTERNAL", "Internal error")},
			},
			wantKind:     KindUnavailable,
			wantCode:     "INTERNAL",
			wantAttempts: 2,
			wantSleeps:   []time.Duration{time.Second},
		},
		{
			name: "short retry-after is honoured",
			responses: []fcmResponse{
				{status: 503, retryAfter: "3", body: fcmErr(503, "UNAVAILABLE", "UNAVAILABLE", "busy")},
				{status: 200, body: `{"name":"x"}`},
			},
			wantSleeps: []time.Duration{3 * time.Second},
		},
		{
			name:         "long retry-after is not waited for",
			responses:    []fcmResponse{{status: 503, retryAfter: "120", body: fcmErr(503, "UNAVAILABLE", "UNAVAILABLE", "busy")}},
			wantKind:     KindUnavailable,
			wantCode:     "UNAVAILABLE",
			wantAttempts: 1,
		},
		{
			name: "plain 502 without json",
			responses: []fcmResponse{
				{status: 502, body: "<html>bad gateway</html>"},
			},
			wantKind:     KindUnavailable,
			wantCode:     "Bad Gateway",
			wantAttempts: 2,
			wantSleeps:   []time.Duration{time.Second},
		},
		{
			name: "401 refreshes the access token once",
			responses: []fcmResponse{
				{status: 401, body: fcmErr(401, "UNAUTHENTICATED", "", "Request had invalid authentication credentials.")},
				{status: 200, body: `{"name":"x"}`},
			},
			wantInval: 1,
		},
		{
			name:         "third party auth error",
			responses:    []fcmResponse{{status: 401, body: fcmErr(401, "UNAUTHENTICATED", "THIRD_PARTY_AUTH_ERROR", "APNs auth")}},
			wantKind:     KindAuth,
			wantCode:     "THIRD_PARTY_AUTH_ERROR",
			wantAttempts: 2,
			wantInval:    1,
		},
		{
			name:         "permission denied (wrong project)",
			responses:    []fcmResponse{{status: 403, body: fcmErr(403, "PERMISSION_DENIED", "", "Permission denied")}},
			wantKind:     KindAuth,
			wantCode:     "PERMISSION_DENIED",
			wantAttempts: 1,
		},
		{
			name:         "404 without UNREGISTERED",
			responses:    []fcmResponse{{status: 404, body: "not found"}},
			wantKind:     KindAuth,
			wantCode:     "Not Found",
			wantAttempts: 1,
		},
		{
			name:         "unexpected 409",
			responses:    []fcmResponse{{status: 409, body: fcmErr(409, "ABORTED", "", "aborted")}},
			wantKind:     KindOther,
			wantCode:     "ABORTED",
			wantAttempts: 1,
		},
		{
			name:         "token endpoint rejects key",
			responses:    []fcmResponse{{status: 200, body: `{}`}},
			tokenErr:     &TokenError{HTTPStatus: 400, Code: "invalid_grant", Description: "Invalid JWT Signature."},
			wantKind:     KindAuth,
			wantAttempts: 1,
		},
		{
			name:         "token endpoint 503",
			responses:    []fcmResponse{{status: 200, body: `{}`}},
			tokenErr:     &TokenError{HTTPStatus: 503, Code: "Service Unavailable"},
			wantKind:     KindUnavailable,
			wantAttempts: 2,
			wantSleeps:   []time.Duration{time.Second},
		},
		{
			name:         "token endpoint unreachable",
			responses:    []fcmResponse{{status: 200, body: `{}`}},
			tokenErr:     errors.New("dial tcp: connection refused"),
			wantKind:     KindUnavailable,
			wantAttempts: 2,
			wantSleeps:   []time.Duration{time.Second},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			tokens := &staticTokens{err: tc.tokenErr}
			c, _, slept := newClient(t, tokens, tc.responses...)
			_, err := c.Send(context.Background(), regToken, `{"v":1}`)
			if tc.wantKind == KindOther && tc.wantCode == "" {
				if err != nil {
					t.Fatalf("unexpected error %v", err)
				}
			} else {
				var se *SendError
				if !errors.As(err, &se) {
					t.Fatalf("err = %v, want *SendError", err)
				}
				if se.Kind != tc.wantKind || se.Code != tc.wantCode || se.Attempts != tc.wantAttempts {
					t.Errorf("got kind=%v code=%q attempts=%d, want kind=%v code=%q attempts=%d (%v)",
						se.Kind, se.Code, se.Attempts, tc.wantKind, tc.wantCode, tc.wantAttempts, se)
				}
			}
			if len(*slept) != len(tc.wantSleeps) {
				t.Fatalf("sleeps = %v, want %v", *slept, tc.wantSleeps)
			}
			for i := range tc.wantSleeps {
				if (*slept)[i] != tc.wantSleeps[i] {
					t.Errorf("sleep[%d] = %v, want %v", i, (*slept)[i], tc.wantSleeps[i])
				}
			}
			if got := tokens.invalidated.Load(); got != tc.wantInval {
				t.Errorf("invalidations = %d, want %d", got, tc.wantInval)
			}
		})
	}
}

func TestSendAttemptTimeout(t *testing.T) {
	block := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-block:
		case <-r.Context().Done():
		}
	}))
	t.Cleanup(func() { close(block); srv.Close() })
	c := NewClient("p", &staticTokens{}, srv.Client())
	c.Endpoint = srv.URL
	c.AttemptTimeout = 50 * time.Millisecond
	c.Sleep = func(context.Context, time.Duration) error { return nil }
	start := time.Now()
	_, err := c.Send(context.Background(), regToken, "x")
	var se *SendError
	if !errors.As(err, &se) || se.Kind != KindUnavailable || se.Attempts != 2 {
		t.Fatalf("err = %v", err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("expected deadline exceeded in chain, got %v", err)
	}
	if time.Since(start) > 5*time.Second {
		t.Error("timeout not applied")
	}
}

func TestSendContextCancelledDuringBackoff(t *testing.T) {
	c, f, _ := newClient(t, &staticTokens{}, fcmResponse{status: 503, body: fcmErr(503, "UNAVAILABLE", "UNAVAILABLE", "busy")})
	c.Sleep = func(context.Context, time.Duration) error { return context.Canceled }
	_, err := c.Send(context.Background(), regToken, "x")
	var se *SendError
	if !errors.As(err, &se) || se.Attempts != 1 || len(f.requests) != 1 {
		t.Fatalf("err = %v, requests = %d", err, len(f.requests))
	}
}

func TestParseRetryAfter(t *testing.T) {
	now := time.Date(2026, 10, 29, 12, 0, 0, 0, time.UTC)
	tests := map[string]time.Duration{
		"":                              0,
		"7":                             7 * time.Second,
		"-3":                            0,
		"soon":                          0,
		"Thu, 29 Oct 2026 12:00:30 GMT": 30 * time.Second,
		"Thu, 29 Oct 2026 11:00:00 GMT": 0,
	}
	for in, want := range tests {
		if got := parseRetryAfter(in, now); got != want {
			t.Errorf("parseRetryAfter(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestSendErrorString(t *testing.T) {
	e := &SendError{HTTPStatus: 404, Code: "UNREGISTERED", Message: "gone", Err: errors.New("x")}
	if got := e.Error(); got != "fcm HTTP 404 UNREGISTERED: gone: x" {
		t.Errorf("Error() = %q", got)
	}
	for k := KindOther; k <= KindSenderMismatch; k++ {
		if k.String() == "" {
			t.Errorf("empty name for kind %d", k)
		}
	}
}
