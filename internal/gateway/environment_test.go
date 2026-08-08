package gateway

import (
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestPiEnvironmentFileIsSourcedOnceForChildEnvironment(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("requires a POSIX shell")
	}
	directory := t.TempDir()
	piPath := filepath.Join(directory, "test-pi")
	if err := os.WriteFile(piPath, []byte("#!/bin/sh\nexit 0\n"), 0o700); err != nil {
		t.Fatal(err)
	}
	environmentFile := filepath.Join(directory, "environment file")
	contents := strings.Join([]string{
		`export OHPI_TEST_FROM_RC="loaded"`,
		`OHPI_TEST_NOT_EXPORTED="hidden"`,
		`export OHPI_TOKEN="must-be-filtered"`,
		`export OHPI_TOKEN_FILE="must-also-be-filtered"`,
		`export PATH="$OHPI_TEST_BIN:/usr/bin:/bin"`,
	}, "\n")
	if err := os.WriteFile(environmentFile, []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("OHPI_TEST_BIN", directory)
	t.Setenv("OHPI_TOKEN", "gateway-secret")
	t.Setenv("OHPI_TOKEN_FILE", "/gateway/token")

	cfg, err := (Config{
		Token:              "test-token",
		DataDir:            filepath.Join(directory, "data"),
		WorkDir:            directory,
		PiCommand:          "test-pi",
		PiEnvironmentFile:  environmentFile,
		PiEnvironmentShell: "/bin/sh",
		Logger:             slog.New(slog.NewTextHandler(io.Discard, nil)),
	}).withDefaults()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.PiCommand != piPath {
		t.Fatalf("pi command = %q, want %q", cfg.PiCommand, piPath)
	}
	environment := cfg.childEnvironment()
	if got := environmentValue(environment, "OHPI_TEST_FROM_RC"); got != "loaded" {
		t.Fatalf("OHPI_TEST_FROM_RC = %q, want loaded", got)
	}
	if got := environmentValue(environment, "OHPI_TEST_NOT_EXPORTED"); got != "" {
		t.Fatalf("unexported variable reached child environment: %q", got)
	}
	for _, name := range []string{"OHPI_TOKEN", "OHPI_TOKEN_FILE", "OHPI_PI_ENV_FILE", "OHPI_PI_ENV_SHELL"} {
		if got := environmentValue(environment, name); got != "" {
			t.Fatalf("%s reached child environment: %q", name, got)
		}
	}
}

func TestResolvePiEnvironmentSourceInfersShell(t *testing.T) {
	shell := requirePOSIXShell(t)
	path := filepath.Join(t.TempDir(), ".profile")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	resolvedFile, resolvedShell, err := resolvePiEnvironmentSource(path, "", []string{"SHELL=" + shell})
	if err != nil {
		t.Fatal(err)
	}
	if resolvedFile != path || resolvedShell != shell {
		t.Fatalf("resolved source = (%q, %q), want (%q, %q)", resolvedFile, resolvedShell, path, shell)
	}
}

func requirePOSIXShell(t *testing.T) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("requires a POSIX shell")
	}
	shell, err := exec.LookPath("sh")
	if err != nil {
		t.Skipf("POSIX shell is unavailable: %v", err)
	}
	shell, err = filepath.Abs(shell)
	if err != nil {
		t.Fatalf("resolve POSIX shell path: %v", err)
	}
	return shell
}

func TestParseNULChildEnvironmentRejectsEmptyOutput(t *testing.T) {
	if _, err := parseNULChildEnvironment(nil); err == nil {
		t.Fatal("empty shell environment unexpectedly succeeded")
	}
}

func TestPiEnvironmentShellRequiresFile(t *testing.T) {
	directory := t.TempDir()
	_, err := (Config{
		Token:              "test-token",
		DataDir:            filepath.Join(directory, "data"),
		WorkDir:            directory,
		PiCommand:          os.Args[0],
		PiEnvironmentShell: "/bin/sh",
		Logger:             slog.New(slog.NewTextHandler(io.Discard, nil)),
	}).withDefaults()
	if err == nil || !strings.Contains(err.Error(), "requires an environment file") {
		t.Fatalf("shell without file error = %v", err)
	}
}
