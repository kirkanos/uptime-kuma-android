package fcm

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// DefaultEndpoint is the FCM HTTP v1 API base URL.
const DefaultEndpoint = "https://fcm.googleapis.com"

// SchemaVersion is the value of the "v" data key; it tells the app how to read "body".
const SchemaVersion = "1"

// TTL is the android.ttl of every message: an alert older than an hour is not worth waking a
// phone for.
const TTL = "3600s"

// ErrorKind groups FCM failures by what the caller should do about them.
type ErrorKind int

const (
	// KindOther is any failure not covered below.
	KindOther ErrorKind = iota
	// KindTokenGone means the registration token is unregistered or invalid; stop using it.
	KindTokenGone
	// KindBadRequest means FCM rejected the message itself (e.g. too large).
	KindBadRequest
	// KindQuota means the project or device exceeded its FCM quota (HTTP 429).
	KindQuota
	// KindUnavailable is a transient FCM or network failure.
	KindUnavailable
	// KindAuth means the relay could not authenticate to Google (bad key, wrong project, ...).
	KindAuth
	// KindSenderMismatch means the token belongs to a different Firebase project.
	KindSenderMismatch
)

func (k ErrorKind) String() string {
	switch k {
	case KindTokenGone:
		return "token_gone"
	case KindBadRequest:
		return "bad_request"
	case KindQuota:
		return "quota"
	case KindUnavailable:
		return "unavailable"
	case KindAuth:
		return "auth"
	case KindSenderMismatch:
		return "sender_mismatch"
	default:
		return "other"
	}
}

// SendError describes a failed send after all attempts.
type SendError struct {
	Kind ErrorKind
	// HTTPStatus is the status FCM answered with, 0 for network or token endpoint failures.
	HTTPStatus int
	// Code is FCM's errorCode (UNREGISTERED, QUOTA_EXCEEDED, ...) or the google.rpc status.
	Code    string
	Message string
	// RetryAfter is FCM's Retry-After hint, zero if none.
	RetryAfter time.Duration
	Attempts   int
	Err        error
}

func (e *SendError) Error() string {
	var b strings.Builder
	b.WriteString("fcm")
	if e.HTTPStatus != 0 {
		fmt.Fprintf(&b, " HTTP %d", e.HTTPStatus)
	}
	if e.Code != "" {
		b.WriteString(" ")
		b.WriteString(e.Code)
	}
	if e.Message != "" {
		b.WriteString(": ")
		b.WriteString(e.Message)
	}
	if e.Err != nil {
		b.WriteString(": ")
		b.WriteString(e.Err.Error())
	}
	return b.String()
}

func (e *SendError) Unwrap() error { return e.Err }

// Client sends data messages to single registration tokens.
type Client struct {
	// Endpoint is the API base URL, DefaultEndpoint in production.
	Endpoint  string
	ProjectID string
	Tokens    TokenSource
	HTTP      *http.Client
	// AttemptTimeout bounds each HTTP call to FCM.
	AttemptTimeout time.Duration
	// RetryDelay is the wait before the single retry when FCM gave no usable Retry-After.
	RetryDelay time.Duration
	// MaxRetryAfter: a Retry-After longer than this is not waited for; the error is returned.
	MaxRetryAfter time.Duration
	// Sleep waits for d or until ctx is done; overridable in tests.
	Sleep func(ctx context.Context, d time.Duration) error
}

// NewClient returns a Client with production defaults.
func NewClient(projectID string, tokens TokenSource, httpClient *http.Client) *Client {
	return &Client{
		Endpoint:       DefaultEndpoint,
		ProjectID:      projectID,
		Tokens:         tokens,
		HTTP:           httpClient,
		AttemptTimeout: 10 * time.Second,
		RetryDelay:     time.Second,
		MaxRetryAfter:  5 * time.Second,
		Sleep:          sleepCtx,
	}
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// BuildMessage returns the JSON request body for projects.messages.send.
func BuildMessage(registrationToken, body string) ([]byte, error) {
	msg := map[string]any{
		"message": map[string]any{
			"token": registrationToken,
			"data": map[string]string{
				"v":    SchemaVersion,
				"body": body,
			},
			"android": map[string]any{
				"priority": "HIGH",
				"ttl":      TTL,
			},
		},
	}
	return json.Marshal(msg)
}

// Send delivers body to registrationToken as a high-priority data message. It retries once on
// transient failures (5xx, UNAVAILABLE, INTERNAL, network errors) and once after a 401 with a
// fresh access token. On success it returns FCM's message name.
func (c *Client) Send(ctx context.Context, registrationToken, body string) (string, error) {
	payload, err := BuildMessage(registrationToken, body)
	if err != nil {
		return "", &SendError{Kind: KindOther, Err: err}
	}
	const maxAttempts = 2
	var last *SendError
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		name, sendErr := c.attempt(ctx, payload)
		if sendErr == nil {
			return name, nil
		}
		sendErr.Attempts = attempt
		last = sendErr
		if attempt == maxAttempts || !c.retryable(sendErr) {
			break
		}
		delay := c.RetryDelay
		if sendErr.Kind == KindAuth {
			c.Tokens.Invalidate()
			delay = 0
		} else if sendErr.RetryAfter > 0 {
			delay = sendErr.RetryAfter
		}
		if delay > 0 {
			if err := c.Sleep(ctx, delay); err != nil {
				return "", last
			}
		}
	}
	return "", last
}

func (c *Client) retryable(e *SendError) bool {
	switch e.Kind {
	case KindUnavailable:
		return e.RetryAfter <= c.MaxRetryAfter
	case KindAuth:
		// Only an expired/revoked access token is worth one more try; a token endpoint failure
		// (bad key) would fail the same way again.
		return e.HTTPStatus == http.StatusUnauthorized
	default:
		return false
	}
}

func (c *Client) attempt(ctx context.Context, payload []byte) (string, *SendError) {
	ctx, cancel := context.WithTimeout(ctx, c.AttemptTimeout)
	defer cancel()

	accessToken, err := c.Tokens.Token(ctx)
	if err != nil {
		var te *TokenError
		if errors.As(err, &te) && te.HTTPStatus >= 500 {
			return "", &SendError{Kind: KindUnavailable, Err: err}
		}
		if errors.As(err, &te) {
			return "", &SendError{Kind: KindAuth, Err: err}
		}
		// Network error talking to the token endpoint.
		return "", &SendError{Kind: KindUnavailable, Err: err}
	}

	endpoint := strings.TrimRight(c.Endpoint, "/") + "/v1/projects/" + url.PathEscape(c.ProjectID) + "/messages:send"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return "", &SendError{Kind: KindOther, Err: err}
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", &SendError{Kind: KindUnavailable, Err: err}
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return "", &SendError{Kind: KindUnavailable, HTTPStatus: resp.StatusCode, Err: err}
	}
	if resp.StatusCode == http.StatusOK {
		var ok struct {
			Name string `json:"name"`
		}
		_ = json.Unmarshal(raw, &ok)
		return ok.Name, nil
	}
	e := classify(resp.StatusCode, raw)
	e.RetryAfter = parseRetryAfter(resp.Header.Get("Retry-After"), time.Now())
	return "", e
}

type fcmErrorBody struct {
	Error struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
		Status  string `json:"status"`
		Details []struct {
			Type            string `json:"@type"`
			ErrorCode       string `json:"errorCode"`
			FieldViolations []struct {
				Field       string `json:"field"`
				Description string `json:"description"`
			} `json:"fieldViolations"`
		} `json:"details"`
	} `json:"error"`
}

// classify maps an FCM v1 error response to a SendError. See
// https://firebase.google.com/docs/reference/fcm/rest/v1/ErrorCode.
func classify(status int, raw []byte) *SendError {
	var body fcmErrorBody
	_ = json.Unmarshal(raw, &body)
	code := body.Error.Status
	tokenField := false
	for _, d := range body.Error.Details {
		if strings.HasSuffix(d.Type, "google.firebase.fcm.v1.FcmError") && d.ErrorCode != "" {
			code = d.ErrorCode
		}
		for _, v := range d.FieldViolations {
			if v.Field == "message.token" {
				tokenField = true
			}
		}
	}
	msg := body.Error.Message
	e := &SendError{HTTPStatus: status, Code: code, Message: msg}

	switch {
	case code == "UNREGISTERED":
		e.Kind = KindTokenGone
	case code == "INVALID_ARGUMENT" && (tokenField || strings.Contains(strings.ToLower(msg), "registration token")):
		e.Kind = KindTokenGone
	case code == "INVALID_ARGUMENT":
		e.Kind = KindBadRequest
	case code == "SENDER_ID_MISMATCH":
		e.Kind = KindSenderMismatch
	case code == "QUOTA_EXCEEDED" || code == "RESOURCE_EXHAUSTED" || status == http.StatusTooManyRequests:
		e.Kind = KindQuota
	case code == "THIRD_PARTY_AUTH_ERROR" || code == "UNAUTHENTICATED" || code == "PERMISSION_DENIED" ||
		status == http.StatusUnauthorized || status == http.StatusForbidden:
		e.Kind = KindAuth
	case code == "UNAVAILABLE" || code == "INTERNAL" || status >= 500:
		e.Kind = KindUnavailable
	case status == http.StatusNotFound:
		// A 404 without UNREGISTERED usually means a wrong project id.
		e.Kind = KindAuth
	default:
		e.Kind = KindOther
	}
	if e.Code == "" {
		e.Code = http.StatusText(status)
	}
	return e
}

func parseRetryAfter(v string, now time.Time) time.Duration {
	v = strings.TrimSpace(v)
	if v == "" {
		return 0
	}
	if secs, err := strconv.Atoi(v); err == nil {
		if secs < 0 {
			return 0
		}
		return time.Duration(secs) * time.Second
	}
	if t, err := http.ParseTime(v); err == nil {
		if d := t.Sub(now); d > 0 {
			return d
		}
	}
	return 0
}
