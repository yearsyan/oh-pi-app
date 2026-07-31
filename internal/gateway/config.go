package gateway

import (
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const (
	defaultMaxMessageBytes = int64(16 << 20)
	defaultInputQueueSize  = 64
	defaultClientQueueSize = 128
)

// Config controls the HTTP gateway and the pi child processes it owns.
type Config struct {
	Token           string
	DataDir         string
	WorkDir         string
	PiCommand       string
	PiArgs          []string
	AllowedOrigins  []string
	MaxMessageBytes int64
	InputQueueSize  int
	ClientQueueSize int
	WriteTimeout    time.Duration
	PongTimeout     time.Duration
	Logger          *slog.Logger
}

func (c Config) withDefaults() (Config, error) {
	if c.Token == "" {
		return Config{}, fmt.Errorf("authentication token must not be empty")
	}
	if c.DataDir == "" {
		return Config{}, fmt.Errorf("data directory must not be empty")
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
	if c.Logger == nil {
		c.Logger = slog.Default()
	}

	for _, arg := range c.PiArgs {
		if isReservedPiArg(arg) {
			return Config{}, fmt.Errorf("pi argument %q is managed by pi2ws", arg)
		}
	}

	piPath, err := exec.LookPath(c.PiCommand)
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
