# Security policy

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private vulnerability reporting
("Security" tab -> "Report a vulnerability") of this repository and include steps to reproduce,
affected versions and the impact you see. You will get an answer within a week. Fixed issues are
disclosed in the release notes once a release is available.

## Scope

In scope: the Android app and the build/release pipeline in this repository, for example token
handling (`SecretStore`, Android Keystore), the Kuma and ntfy communication, the UnifiedPush receiver
and exported components, and the release signing workflow.

Out of scope: vulnerabilities in Uptime Kuma, ntfy or the UnifiedPush distributor themselves (report
them upstream), and setups that intentionally use plain `http` on untrusted networks.

## How the app handles secrets

- The Kuma session token (JWT) and the ntfy access token are encrypted with an AES-256-GCM key in the
  Android Keystore and excluded from cloud backups and device transfer.
- Passwords and TOTP codes are only sent to the Kuma server during login and never stored.
- The app allows user-installed CAs and cleartext `http` so self-hosted servers on a LAN work; the login
  screen warns about `http`.
- There is no telemetry; the app only connects to the servers the user configured.
