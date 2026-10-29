// Command relay is a small stateless service that forwards Uptime Kuma webhook notifications to
// Firebase Cloud Messaging, using the operator's own Firebase service account.
//
// Usage:
//
//	relay              run the server (configured through environment variables)
//	relay healthcheck  GET /healthz on the local server, exit 0 if healthy (for Docker)
//	relay version      print the version
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/kirkanos/uptime-kuma-android/relay/internal/fcm"
	"github.com/kirkanos/uptime-kuma-android/relay/internal/server"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "healthcheck":
			os.Exit(healthcheck(os.Getenv("LISTEN_ADDR")))
		case "version", "--version", "-v":
			fmt.Println(version)
			return
		default:
			fmt.Fprintf(os.Stderr, "unknown command %q; usage: relay [healthcheck|version]\n", os.Args[1])
			os.Exit(2)
		}
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Getenv, os.Stdout); err != nil {
		slog.Error("fatal", "err", err)
		stop()
		os.Exit(1)
	}
}

// run starts the server and blocks until ctx is cancelled (SIGTERM) or the listener fails.
func run(ctx context.Context, getenv func(string) string, logOut io.Writer) error {
	cfg, err := loadConfig(getenv)
	if err != nil {
		return fmt.Errorf("invalid configuration:\n%w", err)
	}
	logger := slog.New(slog.NewTextHandler(logOut, &slog.HandlerOptions{Level: cfg.LogLevel}))

	creds, err := fcm.LoadCredentials(cfg.ServiceAccountFile)
	if err != nil {
		return err
	}
	projectID := creds.ProjectID
	if cfg.ProjectID != "" {
		projectID = cfg.ProjectID
	}
	if projectID == "" {
		return errors.New("no Firebase project id: the service account file has no project_id and FCM_PROJECT_ID is not set")
	}
	if cfg.MaxBodyBytes > 4096 {
		logger.Warn("MAX_BODY_BYTES is above FCM's 4096-byte data payload limit; large bodies will be rejected by FCM", "max_body_bytes", cfg.MaxBodyBytes)
	}

	httpClient := &http.Client{Timeout: 15 * time.Second}
	tokens := fcm.NewJWTTokenSource(creds, httpClient)
	client := fcm.NewClient(projectID, tokens, httpClient)

	handler := server.New(server.Config{
		RelayToken:        cfg.RelayToken,
		MaxBodyBytes:      cfg.MaxBodyBytes,
		RatePerMinute:     cfg.RatePerMinute,
		RateBurst:         cfg.RateBurst,
		TrustForwardedFor: cfg.TrustForwardedFor,
		Version:           version,
		Ready: func() error {
			if creds.Key == nil || creds.ClientEmail == "" {
				return errors.New("service account not loaded")
			}
			return nil
		},
	}, client, logger)

	srv := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		// Covers two FCM attempts of up to 10 s each plus the backoff in between.
		WriteTimeout:   30 * time.Second,
		IdleTimeout:    60 * time.Second,
		MaxHeaderBytes: 16 << 10,
		ErrorLog:       slog.NewLogLogger(logger.Handler(), slog.LevelWarn),
	}

	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()
	logger.Info("relay started",
		"version", version, "listen", cfg.ListenAddr, "project_id", projectID,
		"service_account", creds.ClientEmail, "max_body_bytes", cfg.MaxBodyBytes,
		"rate_per_minute", cfg.RatePerMinute, "rate_burst", cfg.RateBurst,
		"trust_forwarded_for", cfg.TrustForwardedFor)

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}

// healthcheck probes /healthz on the local server. Distroless images have no shell or curl, so
// Docker's HEALTHCHECK calls the binary itself: ["CMD", "/relay", "healthcheck"].
func healthcheck(listenAddr string) int {
	url, err := healthURL(listenAddr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "healthcheck:", err)
		return 1
	}
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		fmt.Fprintln(os.Stderr, "healthcheck:", err)
		return 1
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1024))
	if resp.StatusCode != http.StatusOK {
		fmt.Fprintln(os.Stderr, "healthcheck: status", resp.StatusCode)
		return 1
	}
	return 0
}
