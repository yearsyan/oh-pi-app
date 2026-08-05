# Repository Guidelines

## Project Structure & Module Organization

- `cmd/ohpi-gateway/main.go` is the executable entry point and owns flags, environment configuration, signals, and HTTP lifecycle.
- `internal/gateway/` contains the application: WebSocket handling, authentication, session persistence, client fan-out, and pi subprocess management.
- Tests live beside implementation files as `*_test.go`. Integration tests use a fake pi subprocess and loopback HTTP/WebSocket servers.
- `README.md` documents the public WebSocket protocol and operational configuration. There are no repository assets or generated sources.

Keep protocol policy in `server.go`, process lifecycle in `session.go`/`manager.go`, and persistence concerns in `store.go`.

## Build, Test, and Development Commands

```bash
go build -o bin/ohpi-gateway ./cmd/ohpi-gateway
OHPI_TOKEN=local-dev-token go run ./cmd/ohpi-gateway --work-dir .
go test ./...
go test -race ./...
go vet ./...
gofmt -w cmd/ohpi-gateway internal/gateway
```

The build command creates the ignored `bin/ohpi-gateway` binary. Run normal tests before every change; use the race detector for WebSocket, queue, process, or shutdown changes. `go vet` and `gofmt` are the required static and formatting checks.

### KMP Android App (`app/`)

`app/` is a Kotlin Multiplatform project (`androidApp`, `shared`, `desktopApp`, `iosApp`). Requires Java 21 and the Android SDK at `$ANDROID_HOME` (see `app/local.properties`).

```bash
cd app && ./gradlew :androidApp:assembleRelease
```

Output: `app/androidApp/build/outputs/apk/release/androidApp-release.apk` (signed). First build is slow (downloads Gradle 9.1.0 and dependencies); later builds use the configuration cache.

#### Release signing

The release build is signed with a local release keystore, wired up in `app/androidApp/build.gradle.kts` via a `release` signing config. Credentials live in two **gitignored** files (do not commit them):

- `app/androidApp/pi-release.keystore` — RSA 2048 keystore, alias `pi`
- `app/keystore.properties` — `storeFile` / `storePassword` / `keyAlias` / `keyPassword`

If either file is missing, the release signing config resolves to null and the APK comes out unsigned. Regenerate them with:

```bash
STORE_PASS=$(openssl rand -hex 16)
keytool -genkeypair -v -keystore app/androidApp/pi-release.keystore -alias pi \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
  -dname "CN=Oh Pi App, OU=oh-pi-app, O=yearsyan, L=Shanghai, ST=Shanghai, C=CN"
printf "storeFile=pi-release.keystore\nstorePassword=%s\nkeyAlias=pi\nkeyPassword=%s\n" \
  "$STORE_PASS" "$STORE_PASS" > app/keystore.properties
chmod 600 app/keystore.properties
```

Keep using the same keystore across releases: Android refuses update installs when the signing certificate changes, so losing it means users must uninstall first. Verify a built APK with `$ANDROID_HOME/build-tools/*/apksigner verify --print-certs <apk>`.

#### Publishing release APKs

Version rule: pushing a `v<major.minor.patch>` tag (e.g. `v1.10.1`) triggers the GitHub release workflow, which builds the APK with `-PversionName=<major.minor.patch>`. The Android `versionCode` is derived from the version name with each dot-segment as a two-digit field: `1.10.1` → `11001` (`major×10000 + minor×100 + patch`). Local builds without `-PversionName` fall back to the last released version.

## Coding Style & Naming Conventions

Follow standard Go conventions and let `gofmt` determine tabs and layout. Use short, lower-case package names; exported identifiers need Go doc comments, while implementation details should remain inside `internal/gateway`. Prefer descriptive lifecycle verbs such as `start`, `attach`, `shutdown`, and `forceKill`. Wrap errors with context using `%w`, avoid global mutable state, and preserve strict LF-delimited JSONL framing.

## Testing Guidelines

Use Go’s `testing` package, `httptest`, and Gorilla WebSocket clients. Name tests `TestBehaviorBeingVerified`; keep helpers unexported. Tests must not call a real model or require API credentials. Cover authentication failures, multi-client broadcasts, historical attach behavior, malformed commands, subprocess exits, and shutdown races when those paths change.

## Commit & Pull Request Guidelines

This workspace has no Git history from which to infer an existing convention. Use concise, imperative commit subjects, optionally with a conventional prefix, for example `fix: preserve session output ordering`.

Pull requests should explain behavior and protocol changes, list verification commands, and call out compatibility or security implications. Link relevant issues. Screenshots are usually unnecessary; include a short WebSocket transcript when changing wire behavior.

## Security & Configuration

Never log or pass `OHPI_TOKEN` to pi children. Preserve constant-time token comparison, session ID validation, Origin checks, and the block on session-changing RPC commands. Do not commit credentials, runtime session data, or built binaries.
