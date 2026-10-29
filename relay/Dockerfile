# syntax=docker/dockerfile:1
# Uptime Kuma FCM relay: static Go binary on distroless (no shell, no package manager, uid 65532).

FROM --platform=$BUILDPLATFORM golang:1.27-alpine AS build
ARG TARGETOS
ARG TARGETARCH
ARG VERSION=dev
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/relay .

FROM gcr.io/distroless/static-debian13:nonroot
LABEL org.opencontainers.image.title="uptime-kuma-fcm-relay" \
      org.opencontainers.image.description="Forwards Uptime Kuma webhook notifications to Firebase Cloud Messaging" \
      org.opencontainers.image.source="https://github.com/kirkanos/uptime-kuma-android" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"
COPY --from=build /out/relay /relay
USER nonroot:nonroot
EXPOSE 8080
# Distroless has no shell or curl; the binary probes itself.
HEALTHCHECK --interval=60s --timeout=5s --start-period=5s --retries=3 CMD ["/relay", "healthcheck"]
ENTRYPOINT ["/relay"]
