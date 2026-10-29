// Package server implements the relay's HTTP API: Uptime Kuma's webhook notification POSTs its
// rendered body to /v1/fcm/{token}, the relay forwards it to FCM.
package server

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/kirkanos/uptime-kuma-android/relay/internal/fcm"
)

// Token length bounds for the sanity check. Real FCM registration tokens are ~140-170 chars.
const (
	minTokenLen = 100
	maxTokenLen = 4096
)

// Sender delivers a body to one FCM registration token; *fcm.Client implements it.
type Sender interface {
	Send(ctx context.Context, registrationToken, body string) (string, error)
}

// Config is the server configuration.
type Config struct {
	RelayToken   string
	MaxBodyBytes int64
	// RatePerMinute and RateBurst configure the per-client token bucket. RatePerMinute <= 0
	// disables rate limiting.
	RatePerMinute float64
	RateBurst     int
	// TrustForwardedFor makes the rate limiter key on the right-most X-Forwarded-For address
	// (the one the reverse proxy appended) instead of the TCP peer, which behind Traefik would
	// be Traefik itself for every request.
	TrustForwardedFor bool
	Version           string
	// Ready reports whether the relay can send (service account loaded); used by /healthz.
	Ready func() error
	// Now is the clock, overridable in tests.
	Now func() time.Time
}

// Server is the relay HTTP handler.
type Server struct {
	cfg     Config
	sender  Sender
	log     *slog.Logger
	limiter *rateLimiter
	mux     *http.ServeMux
	// relayTokenHash is compared in constant time against the hash of the presented token, so
	// neither content nor length of RELAY_TOKEN leaks through timing.
	relayTokenHash [32]byte
}

// New builds the handler.
func New(cfg Config, sender Sender, logger *slog.Logger) *Server {
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Ready == nil {
		cfg.Ready = func() error { return nil }
	}
	s := &Server{
		cfg:            cfg,
		sender:         sender,
		log:            logger,
		mux:            http.NewServeMux(),
		relayTokenHash: sha256.Sum256([]byte(cfg.RelayToken)),
	}
	if cfg.RatePerMinute > 0 {
		burst := cfg.RateBurst
		if burst < 1 {
			burst = 1
		}
		s.limiter = newRateLimiter(cfg.RatePerMinute, burst, cfg.Now)
	}
	s.mux.HandleFunc("POST /v1/fcm/{token}", s.handleForward)
	s.mux.HandleFunc("GET /healthz", s.handleHealth)
	s.mux.HandleFunc("GET /{$}", s.handleRoot)
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	s.mux.ServeHTTP(w, r)
}

func (s *Server) handleRoot(w http.ResponseWriter, _ *http.Request) {
	writeText(w, http.StatusOK, "uptime-kuma-fcm-relay "+s.cfg.Version+"\nPOST /v1/fcm/{registration-token} with Authorization: Bearer <RELAY_TOKEN>\n")
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	if err := s.cfg.Ready(); err != nil {
		s.log.Error("health check failed", "err", err)
		writeText(w, http.StatusServiceUnavailable, "not ready\n")
		return
	}
	writeText(w, http.StatusOK, "ok\n")
}

func (s *Server) handleForward(w http.ResponseWriter, r *http.Request) {
	start := s.cfg.Now()
	client := s.clientIP(r)
	regToken := r.PathValue("token")
	log := s.log.With("client", client, "token", TokenFingerprint(regToken))

	if s.limiter != nil {
		if ok, wait := s.limiter.allow(client); !ok {
			w.Header().Set("Retry-After", strconv.Itoa(int(math.Ceil(wait.Seconds()))))
			log.Warn("rate limited")
			writeText(w, http.StatusTooManyRequests, "rate limit exceeded\n")
			return
		}
	}

	if !s.authorized(r) {
		w.Header().Set("WWW-Authenticate", `Bearer realm="fcm-relay"`)
		log.Warn("rejected: missing or wrong bearer token")
		writeText(w, http.StatusUnauthorized, "unauthorized\n")
		return
	}

	if r.ContentLength > s.cfg.MaxBodyBytes {
		log.Warn("rejected: body too large", "content_length", r.ContentLength, "max", s.cfg.MaxBodyBytes)
		writeText(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("body exceeds %d bytes\n", s.cfg.MaxBodyBytes))
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.cfg.MaxBodyBytes))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			log.Warn("rejected: body too large", "max", s.cfg.MaxBodyBytes)
			writeText(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("body exceeds %d bytes\n", s.cfg.MaxBodyBytes))
			return
		}
		log.Warn("reading body failed", "err", err)
		writeText(w, http.StatusBadRequest, "could not read body\n")
		return
	}

	if !ValidRegistrationToken(regToken) {
		log.Warn("rejected: malformed registration token", "token_len", len(regToken))
		writeText(w, http.StatusBadRequest, "malformed FCM registration token\n")
		return
	}
	if len(body) == 0 {
		log.Warn("rejected: empty body")
		writeText(w, http.StatusBadRequest, "empty body\n")
		return
	}
	if !utf8.Valid(body) {
		log.Warn("rejected: body is not valid UTF-8")
		writeText(w, http.StatusBadRequest, "body is not valid UTF-8\n")
		return
	}

	name, err := s.sender.Send(r.Context(), regToken, string(body))
	elapsed := s.cfg.Now().Sub(start)
	if err == nil {
		log.Info("forwarded", "bytes", len(body), "duration_ms", elapsed.Milliseconds())
		writeJSON(w, http.StatusOK, map[string]string{"status": "sent", "name": name})
		return
	}
	s.writeSendError(w, log, err, elapsed)
}

func (s *Server) writeSendError(w http.ResponseWriter, log *slog.Logger, err error, elapsed time.Duration) {
	var se *fcm.SendError
	if !errors.As(err, &se) {
		log.Error("forward failed", "err", err, "duration_ms", elapsed.Milliseconds())
		writeText(w, http.StatusBadGateway, "fcm send failed\n")
		return
	}
	attrs := []any{
		"kind", se.Kind.String(), "fcm_status", se.HTTPStatus, "fcm_code", se.Code,
		"attempts", se.Attempts, "duration_ms", elapsed.Milliseconds(), "err", se.Error(),
	}
	detail := se.Code
	if se.Message != "" {
		detail += ": " + se.Message
	}
	switch se.Kind {
	case fcm.KindTokenGone:
		log.Warn("device token is gone; remove this notification in Uptime Kuma", attrs...)
		writeText(w, http.StatusGone, "FCM registration token is no longer valid ("+detail+"); the app was uninstalled or re-registered, update the notification in Uptime Kuma\n")
	case fcm.KindBadRequest:
		log.Warn("FCM rejected the message", attrs...)
		writeText(w, http.StatusBadRequest, "FCM rejected the message ("+detail+")\n")
	case fcm.KindSenderMismatch:
		log.Error("token belongs to a different Firebase project than the service account", attrs...)
		writeText(w, http.StatusForbidden, "FCM token belongs to a different Firebase project than this relay's service account\n")
	case fcm.KindAuth:
		log.Error("relay could not authenticate to FCM; check the service account file and project id", attrs...)
		writeText(w, http.StatusBadGateway, "relay could not authenticate to FCM ("+se.Error()+"); check the service account file and project id\n")
	case fcm.KindQuota, fcm.KindUnavailable:
		retry := se.RetryAfter
		if retry <= 0 {
			retry = 30 * time.Second
		}
		w.Header().Set("Retry-After", strconv.Itoa(int(math.Ceil(retry.Seconds()))))
		log.Error("FCM temporarily unavailable", attrs...)
		writeText(w, http.StatusBadGateway, "FCM temporarily unavailable ("+detail+"), retry later\n")
	default:
		log.Error("forward failed", attrs...)
		writeText(w, http.StatusBadGateway, "FCM send failed ("+detail+")\n")
	}
}

func (s *Server) authorized(r *http.Request) bool {
	h := r.Header.Get("Authorization")
	const prefix = "bearer "
	if len(h) < len(prefix) || !strings.EqualFold(h[:len(prefix)], prefix) {
		return false
	}
	presented := sha256.Sum256([]byte(strings.TrimSpace(h[len(prefix):])))
	return subtle.ConstantTimeCompare(presented[:], s.relayTokenHash[:]) == 1
}

func (s *Server) clientIP(r *http.Request) string {
	if s.cfg.TrustForwardedFor {
		if xff := r.Header.Values("X-Forwarded-For"); len(xff) > 0 {
			parts := strings.Split(xff[len(xff)-1], ",")
			if ip := strings.TrimSpace(parts[len(parts)-1]); ip != "" {
				return ip
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// ValidRegistrationToken is a cheap sanity check before anything is sent to Google: FCM tokens
// consist of [A-Za-z0-9_:-] and are well over 100 characters long.
func ValidRegistrationToken(t string) bool {
	if len(t) < minTokenLen || len(t) > maxTokenLen {
		return false
	}
	for i := 0; i < len(t); i++ {
		c := t[i]
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9', c == '_', c == ':', c == '-':
		default:
			return false
		}
	}
	return true
}

// TokenFingerprint is what gets logged instead of a registration token: the first 12 hex chars
// of its SHA-256, enough to correlate log lines for one device, useless to send to it.
func TokenFingerprint(t string) string {
	sum := sha256.Sum256([]byte(t))
	return hex.EncodeToString(sum[:6])
}

func writeText(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, msg)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
