# Repository Guidelines

## Project Structure & Module Organization

- `cmd/pi2ws/main.go` is the executable entry point and owns flags, environment configuration, signals, and HTTP lifecycle.
- `internal/gateway/` contains the application: WebSocket handling, authentication, session persistence, client fan-out, and pi subprocess management.
- Tests live beside implementation files as `*_test.go`. Integration tests use a fake pi subprocess and loopback HTTP/WebSocket servers.
- `README.md` documents the public WebSocket protocol and operational configuration. There are no repository assets or generated sources.

Keep protocol policy in `server.go`, process lifecycle in `session.go`/`manager.go`, and persistence concerns in `store.go`.

## Build, Test, and Development Commands

```bash
go build -o bin/pi2ws ./cmd/pi2ws
PI2WS_TOKEN=local-dev-token go run ./cmd/pi2ws --work-dir .
go test ./...
go test -race ./...
go vet ./...
gofmt -w cmd/pi2ws internal/gateway
```

The build command creates the ignored `bin/pi2ws` binary. Run normal tests before every change; use the race detector for WebSocket, queue, process, or shutdown changes. `go vet` and `gofmt` are the required static and formatting checks.

### KMP Android App (`app/`)

`app/` is a Kotlin Multiplatform project (`androidApp`, `shared`, `desktopApp`, `iosApp`). Requires Java 21 and the Android SDK at `$ANDROID_HOME` (see `app/local.properties`).

```bash
cd app && ./gradlew :androidApp:assembleRelease
```

Output: `app/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk` (unsigned, since no `signingConfig` is configured). First build is slow (downloads Gradle 9.1.0 and dependencies); later builds use the configuration cache.

## Coding Style & Naming Conventions

Follow standard Go conventions and let `gofmt` determine tabs and layout. Use short, lower-case package names; exported identifiers need Go doc comments, while implementation details should remain inside `internal/gateway`. Prefer descriptive lifecycle verbs such as `start`, `attach`, `shutdown`, and `forceKill`. Wrap errors with context using `%w`, avoid global mutable state, and preserve strict LF-delimited JSONL framing.

## Testing Guidelines

Use Go’s `testing` package, `httptest`, and Gorilla WebSocket clients. Name tests `TestBehaviorBeingVerified`; keep helpers unexported. Tests must not call a real model or require API credentials. Cover authentication failures, multi-client broadcasts, historical attach behavior, malformed commands, subprocess exits, and shutdown races when those paths change.

## Commit & Pull Request Guidelines

This workspace has no Git history from which to infer an existing convention. Use concise, imperative commit subjects, optionally with a conventional prefix, for example `fix: preserve session output ordering`.

Pull requests should explain behavior and protocol changes, list verification commands, and call out compatibility or security implications. Link relevant issues. Screenshots are usually unnecessary; include a short WebSocket transcript when changing wire behavior.

## Security & Configuration

Never log or pass `PI2WS_TOKEN` to pi children. Preserve constant-time token comparison, session ID validation, Origin checks, and the block on session-changing RPC commands. Do not commit credentials, runtime session data, or built binaries.
