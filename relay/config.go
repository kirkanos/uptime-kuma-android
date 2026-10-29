package main

import (
	"errors"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
)

const minRelayTokenLen = 32

type config struct {
	ServiceAccountFile string
	ProjectID          string // optional override of the project_id in the service account file
	RelayToken         string
	ListenAddr         string
	MaxBodyBytes       int64
	LogLevel           slog.Level
	RatePerMinute      float64
	RateBurst          int
	TrustForwardedFor  bool
}

// loadConfig reads the configuration from getenv (os.Getenv in production).
func loadConfig(getenv func(string) string) (config, error) {
	c := config{
		ServiceAccountFile: strings.TrimSpace(getenv("FCM_SERVICE_ACCOUNT_FILE")),
		ProjectID:          strings.TrimSpace(getenv("FCM_PROJECT_ID")),
		RelayToken:         strings.TrimSpace(getenv("RELAY_TOKEN")),
		ListenAddr:         strings.TrimSpace(getenv("LISTEN_ADDR")),
		MaxBodyBytes:       4096,
		LogLevel:           slog.LevelInfo,
		RatePerMinute:      60,
		RateBurst:          30,
	}
	var errs []error
	if c.ServiceAccountFile == "" {
		errs = append(errs, errors.New("FCM_SERVICE_ACCOUNT_FILE is required"))
	}
	if len(c.RelayToken) < minRelayTokenLen {
		errs = append(errs, fmt.Errorf("RELAY_TOKEN is required and must be at least %d characters (generate one with: openssl rand -hex 32)", minRelayTokenLen))
	}
	if c.ListenAddr == "" {
		c.ListenAddr = ":8080"
	}
	if _, _, err := net.SplitHostPort(c.ListenAddr); err != nil {
		errs = append(errs, fmt.Errorf("LISTEN_ADDR %q: %w", c.ListenAddr, err))
	}
	if v := strings.TrimSpace(getenv("MAX_BODY_BYTES")); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n <= 0 {
			errs = append(errs, fmt.Errorf("MAX_BODY_BYTES %q must be a positive integer", v))
		} else {
			c.MaxBodyBytes = n
		}
	}
	if v := strings.TrimSpace(getenv("LOG_LEVEL")); v != "" {
		if err := c.LogLevel.UnmarshalText([]byte(v)); err != nil {
			errs = append(errs, fmt.Errorf("LOG_LEVEL %q must be one of debug, info, warn, error", v))
		}
	}
	if v := strings.TrimSpace(getenv("RATE_LIMIT_PER_MINUTE")); v != "" {
		n, err := strconv.ParseFloat(v, 64)
		if err != nil || n < 0 {
			errs = append(errs, fmt.Errorf("RATE_LIMIT_PER_MINUTE %q must be a number >= 0 (0 disables)", v))
		} else {
			c.RatePerMinute = n
		}
	}
	if v := strings.TrimSpace(getenv("RATE_LIMIT_BURST")); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			errs = append(errs, fmt.Errorf("RATE_LIMIT_BURST %q must be an integer >= 1", v))
		} else {
			c.RateBurst = n
		}
	}
	if v := strings.TrimSpace(getenv("TRUST_FORWARDED_FOR")); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			errs = append(errs, fmt.Errorf("TRUST_FORWARDED_FOR %q must be true or false", v))
		} else {
			c.TrustForwardedFor = b
		}
	}
	return c, errors.Join(errs...)
}

// healthURL is the URL the healthcheck subcommand probes: the configured port on loopback.
func healthURL(listenAddr string) (string, error) {
	if listenAddr == "" {
		listenAddr = ":8080"
	}
	host, port, err := net.SplitHostPort(listenAddr)
	if err != nil {
		return "", err
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	return "http://" + net.JoinHostPort(host, port) + "/healthz", nil
}
