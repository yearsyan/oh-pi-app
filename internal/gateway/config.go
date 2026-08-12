package gateway

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	defaultMaxMessageBytes     = int64(128 << 20)
	defaultInputQueueSize      = 64
	defaultClientQueueSize     = 128
	defaultSessionIdle         = 5 * time.Minute
	defaultScheduledRetention  = 7 * 24 * time.Hour
	defaultHistoryTimeout      = 10 * time.Second
	defaultCapabilitiesTimeout = 30 * time.Second
)

// Config controls the HTTP gateway and the pi child processes it owns.
type Config struct {
	Token     string
	Version   string
	DataDir   string
	WorkDir   string
	PiCommand string
	// PiEnvironmentPath overrides PATH only for pi child processes.
	PiEnvironmentPath string
	// PiEnvironmentFile is sourced once when the gateway starts. Only exported
	// variables are copied into pi child processes.
	PiEnvironmentFile string
	// PiEnvironmentShell selects the shell used to source PiEnvironmentFile.
	// When empty, the gateway infers zsh or bash from the file name and falls
	// back to the user's login shell.
	PiEnvironmentShell string
	PiArgs             []string
	// RuntimeConfigPath enables the authenticated runtime configuration API.
	RuntimeConfigPath string
	// TitleModel is the effective title model exposed by the runtime
	// configuration API.
	TitleModel string
	// RequestRestart asks the process supervisor to restart the gateway by
	// causing the current process to exit after a graceful shutdown.
	RequestRestart func()
	// ProviderPiArgs are prepended only to short-lived provider-management
	// probes. Production leaves this empty; tests use it to launch the helper
	// process through the Go test binary without leaking session extensions or
	// their flags into the authentication runtime.
	ProviderPiArgs  []string
	AllowedOrigins  []string
	MaxMessageBytes int64
	InputQueueSize  int
	ClientQueueSize int
	WriteTimeout    time.Duration
	PongTimeout     time.Duration
	SessionIdle     time.Duration
	// ScheduledSessionRetention deletes inactive sessions produced by scheduled
	// tasks after this duration. Opening a session refreshes its activity time.
	ScheduledSessionRetention time.Duration
	HistoryTimeout            time.Duration
	CapabilitiesTimeout       time.Duration
	Logger                    *slog.Logger

	piEnvironment []string
}

func (c Config) withDefaults() (Config, error) {
	if c.Token == "" {
		return Config{}, fmt.Errorf("authentication token must not be empty")
	}
	if c.DataDir == "" {
		return Config{}, fmt.Errorf("data directory must not be empty")
	}
	if c.Version == "" {
		c.Version = "dev"
	}
	if c.WorkDir == "" {
		var err error
		c.WorkDir, err = os.Getwd()
		if err != nil {
			return Config{}, fmt.Errorf("get working directory: %w", err)
		}
	}
	if c.PiCommand == "" {
		c.PiCommand = "pi"
	}
	if c.MaxMessageBytes == 0 {
		c.MaxMessageBytes = defaultMaxMessageBytes
	}
	if c.MaxMessageBytes < 1 {
		return Config{}, fmt.Errorf("max message bytes must be positive")
	}
	if c.InputQueueSize == 0 {
		c.InputQueueSize = defaultInputQueueSize
	}
	if c.InputQueueSize < 1 {
		return Config{}, fmt.Errorf("input queue size must be positive")
	}
	if c.ClientQueueSize == 0 {
		c.ClientQueueSize = defaultClientQueueSize
	}
	if c.ClientQueueSize < 1 {
		return Config{}, fmt.Errorf("client queue size must be positive")
	}
	if c.WriteTimeout == 0 {
		c.WriteTimeout = 10 * time.Second
	}
	if c.WriteTimeout < 1 {
		return Config{}, fmt.Errorf("write timeout must be positive")
	}
	if c.PongTimeout == 0 {
		c.PongTimeout = 60 * time.Second
	}
	if c.PongTimeout < 2*c.WriteTimeout {
		return Config{}, fmt.Errorf("pong timeout must be at least twice the write timeout")
	}
	if c.SessionIdle == 0 {
		c.SessionIdle = defaultSessionIdle
	}
	if c.SessionIdle < 1 {
		return Config{}, fmt.Errorf("session idle timeout must be positive")
	}
	if c.ScheduledSessionRetention == 0 {
		c.ScheduledSessionRetention = defaultScheduledRetention
	}
	if c.ScheduledSessionRetention < time.Hour {
		return Config{}, fmt.Errorf("scheduled session retention must be at least one hour")
	}
	if c.HistoryTimeout == 0 {
		c.HistoryTimeout = defaultHistoryTimeout
	}
	if c.HistoryTimeout < 1 {
		return Config{}, fmt.Errorf("history timeout must be positive")
	}
	if c.CapabilitiesTimeout == 0 {
		c.CapabilitiesTimeout = defaultCapabilitiesTimeout
	}
	if c.CapabilitiesTimeout < 1 {
		return Config{}, fmt.Errorf("capabilities timeout must be positive")
	}
	if c.Logger == nil {
		c.Logger = slog.Default()
	}
	if c.TitleModel == "" {
		c.TitleModel = "auto"
	}
	if strings.TrimSpace(c.PiEnvironmentFile) == "" && strings.TrimSpace(c.PiEnvironmentShell) != "" {
		return Config{}, fmt.Errorf("pi environment shell requires an environment file")
	}
	var err error
	c.TitleModel, err = normalizeRuntimeTitleModel(c.TitleModel)
	if err != nil {
		return Config{}, fmt.Errorf("configure title model: %w", err)
	}

	for _, arg := range c.PiArgs {
		if isReservedPiArg(arg) {
			return Config{}, fmt.Errorf("pi argument %q is managed by ohpi", arg)
		}
	}

	c.piEnvironment, err = loadPiEnvironment(c)
	if err != nil {
		return Config{}, err
	}

	piPath, err := lookPathInEnvironment(c.PiCommand, c.piEnvironment)
	if err != nil {
		return Config{}, fmt.Errorf("find pi executable %q: %w", c.PiCommand, err)
	}
	c.PiCommand = piPath

	c.WorkDir, err = filepath.Abs(c.WorkDir)
	if err != nil {
		return Config{}, fmt.Errorf("resolve working directory: %w", err)
	}
	info, err := os.Stat(c.WorkDir)
	if err != nil {
		return Config{}, fmt.Errorf("inspect working directory: %w", err)
	}
	if !info.IsDir() {
		return Config{}, fmt.Errorf("working directory %q is not a directory", c.WorkDir)
	}

	c.DataDir, err = filepath.Abs(c.DataDir)
	if err != nil {
		return Config{}, fmt.Errorf("resolve data directory: %w", err)
	}
	if c.RuntimeConfigPath != "" {
		c.RuntimeConfigPath, err = expandUserPath(c.RuntimeConfigPath)
		if err != nil {
			return Config{}, fmt.Errorf("resolve runtime configuration path: %w", err)
		}
		c.RuntimeConfigPath, err = filepath.Abs(c.RuntimeConfigPath)
		if err != nil {
			return Config{}, fmt.Errorf("resolve runtime configuration path: %w", err)
		}
	}
	return c, nil
}

func isReservedPiArg(arg string) bool {
	for _, reserved := range []string{
		"--mode",
		"--session",
		"--session-id",
		"--session-dir",
		"--no-session",
		"--continue",
		"-c",
		"--resume",
		"-r",
		"--fork",
	} {
		if arg == reserved || strings.HasPrefix(arg, reserved+"=") {
			return true
		}
	}
	return false
}
