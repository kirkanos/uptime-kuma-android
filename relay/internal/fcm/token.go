package fcm

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// MessagingScope is the OAuth scope required by the FCM HTTP v1 send endpoint.
const MessagingScope = "https://www.googleapis.com/auth/firebase.messaging"

// TokenSource hands out OAuth 2.0 access tokens for the FCM API.
type TokenSource interface {
	// Token returns a valid access token, fetching a new one when needed.
	Token(ctx context.Context) (string, error)
	// Invalidate drops a cached token, e.g. after FCM answered 401 UNAUTHENTICATED.
	Invalidate()
}

// TokenError is returned when the OAuth token endpoint rejects the JWT assertion, typically
// "invalid_grant" for a revoked or wrong key.
type TokenError struct {
	HTTPStatus  int
	Code        string
	Description string
}

func (e *TokenError) Error() string {
	if e.Description != "" {
		return fmt.Sprintf("oauth token endpoint returned HTTP %d: %s: %s", e.HTTPStatus, e.Code, e.Description)
	}
	return fmt.Sprintf("oauth token endpoint returned HTTP %d: %s", e.HTTPStatus, e.Code)
}

// JWTTokenSource implements the OAuth 2.0 JWT bearer grant (RFC 7523) for a Google service
// account: it signs a short-lived RS256 assertion with the service account key and exchanges it
// at the token endpoint. Tokens are cached until shortly before they expire.
type JWTTokenSource struct {
	Creds *Credentials
	HTTP  *http.Client
	// Now is the clock, overridable in tests.
	Now func() time.Time

	mu     sync.Mutex
	token  string
	expiry time.Time
}

// refreshMargin is how long before the reported expiry a token is considered stale.
const refreshMargin = 2 * time.Minute

// NewJWTTokenSource returns a token source for creds using client for the token exchange.
func NewJWTTokenSource(creds *Credentials, client *http.Client) *JWTTokenSource {
	return &JWTTokenSource{Creds: creds, HTTP: client, Now: time.Now}
}

// Token implements TokenSource. Concurrent callers wait for a single in-flight refresh.
func (s *JWTTokenSource) Token(ctx context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.Now()
	if s.token != "" && now.Add(refreshMargin).Before(s.expiry) {
		return s.token, nil
	}
	tok, ttl, err := s.fetch(ctx, now)
	if err != nil {
		return "", err
	}
	s.token, s.expiry = tok, now.Add(ttl)
	return tok, nil
}

// Invalidate implements TokenSource.
func (s *JWTTokenSource) Invalidate() {
	s.mu.Lock()
	s.token, s.expiry = "", time.Time{}
	s.mu.Unlock()
}

func (s *JWTTokenSource) fetch(ctx context.Context, now time.Time) (string, time.Duration, error) {
	assertion, err := s.assertion(now)
	if err != nil {
		return "", 0, err
	}
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {assertion},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.Creds.TokenURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", 0, fmt.Errorf("building token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := s.HTTP.Do(req)
	if err != nil {
		return "", 0, fmt.Errorf("requesting access token: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return "", 0, fmt.Errorf("reading token response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		var e struct {
			Error            string `json:"error"`
			ErrorDescription string `json:"error_description"`
		}
		_ = json.Unmarshal(raw, &e)
		if e.Error == "" {
			e.Error = http.StatusText(resp.StatusCode)
		}
		return "", 0, &TokenError{HTTPStatus: resp.StatusCode, Code: e.Error, Description: e.ErrorDescription}
	}
	var ok struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.Unmarshal(raw, &ok); err != nil || ok.AccessToken == "" {
		return "", 0, fmt.Errorf("token endpoint returned no access_token")
	}
	ttl := time.Duration(ok.ExpiresIn) * time.Second
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	return ok.AccessToken, ttl, nil
}

func (s *JWTTokenSource) assertion(now time.Time) (string, error) {
	header := map[string]string{"alg": "RS256", "typ": "JWT"}
	if s.Creds.KeyID != "" {
		header["kid"] = s.Creds.KeyID
	}
	claims := map[string]any{
		"iss":   s.Creds.ClientEmail,
		"scope": MessagingScope,
		"aud":   s.Creds.TokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	}
	h, err := json.Marshal(header)
	if err != nil {
		return "", err
	}
	c, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	enc := base64.RawURLEncoding
	signingInput := enc.EncodeToString(h) + "." + enc.EncodeToString(c)
	digest := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, s.Creds.Key, crypto.SHA256, digest[:])
	if err != nil {
		return "", fmt.Errorf("signing JWT assertion: %w", err)
	}
	return signingInput + "." + enc.EncodeToString(sig), nil
}
