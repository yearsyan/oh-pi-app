package gateway

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const (
	piEnvironmentLoadTimeout = 30 * time.Second
	maxPiEnvironmentBytes    = 4 << 20
)

func loadPiEnvironment(cfg Config) ([]string, error) {
	base := childEnvironment("")
	if strings.TrimSpace(cfg.PiEnvironmentFile) == "" {
		return normalizeChildEnvironment(base, cfg.PiEnvironmentPath), nil
	}

	file, shell, err := resolvePiEnvironmentSource(cfg.PiEnvironmentFile, cfg.PiEnvironmentShell, base)
	if err != nil {
		return nil, fmt.Errorf("configure pi environment source: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), piEnvironmentLoadTimeout)
	defer cancel()

	loaded, err := sourcePiEnvironment(ctx, file, shell, base)
	if err != nil {
		return nil, fmt.Errorf("source pi environment file %q with %q: %w", file, shell, err)
	}
	cfg.Logger.Info("loaded pi child environment", "file", file, "shell", shell)
	return normalizeChildEnvironment(loaded, cfg.PiEnvironmentPath), nil
}

func resolvePiEnvironmentSource(file, shell string, environment []string) (string, string, error) {
	file, err := expandUserPath(strings.TrimSpace(file))
	if err != nil {
		return "", "", err
	}
	if file == "" {
		return "", "", errors.New("environment file must not be empty")
	}
	file, err = filepath.Abs(file)
	if err != nil {
		return "", "", fmt.Errorf("resolve environment file: %w", err)
	}
	info, err := os.Stat(file)
	if err != nil {
		return "", "", fmt.Errorf("inspect environment file: %w", err)
	}
	if !info.Mode().IsRegular() {
		return "", "", fmt.Errorf("environment file %q is not a regular file", file)
	}

	shell = strings.TrimSpace(shell)
	if shell == "" {
		switch strings.ToLower(filepath.Base(file)) {
		case ".zshrc", "zshrc":
			shell = "/bin/zsh"
		case ".bashrc", "bashrc", ".bash_profile", "bash_profile":
			shell = "/bin/bash"
		default:
			shell = environmentValue(environment, "SHELL")
			if shell == "" {
				shell = "/bin/sh"
			}
		}
	}
	shell, err = expandUserPath(shell)
	if err != nil {
		return "", "", err
	}
	shell, err = lookPathInEnvironment(shell, environment)
	if err != nil {
		return "", "", fmt.Errorf("find environment shell %q: %w", shell, err)
	}
	return file, shell, nil
}

func sourcePiEnvironment(ctx context.Context, file, shell string, base []string) ([]string, error) {
	commandText := `. "$1" >&2 || exit $?; /usr/bin/env -0`
	args := []string{"-c", commandText, "ohpi-env", file}
	switch filepath.Base(shell) {
	case "zsh":
		commandText = `source "$1" >&2 || exit $?; /usr/bin/env -0`
		args = []string{"-f", "-i", "-c", commandText, "ohpi-env", file}
	case "bash":
		args = []string{"--noprofile", "--norc", "-i", "-c", commandText, "ohpi-env", file}
	}

	command := exec.CommandContext(ctx, shell, args...)
	command.Env = normalizeChildEnvironment(base, "")
	command.Stderr = os.Stderr
	var output cappedBuffer
	output.limit = maxPiEnvironmentBytes
	command.Stdout = &output
	if err := command.Run(); err != nil {
		if ctx.Err() != nil {
			return nil, fmt.Errorf("timed out after %s", piEnvironmentLoadTimeout)
		}
		return nil, err
	}
	if output.exceeded {
		return nil, fmt.Errorf("exported environment exceeds %d bytes", maxPiEnvironmentBytes)
	}
	return parseNULChildEnvironment(output.Bytes())
}

type cappedBuffer struct {
	bytes.Buffer
	limit    int
	exceeded bool
}

func (buffer *cappedBuffer) Write(value []byte) (int, error) {
	originalLength := len(value)
	remaining := buffer.limit - buffer.Len()
	if remaining <= 0 {
		buffer.exceeded = true
		return originalLength, nil
	}
	if len(value) > remaining {
		buffer.exceeded = true
		value = value[:remaining]
	}
	_, _ = buffer.Buffer.Write(value)
	return originalLength, nil
}

func parseNULChildEnvironment(output []byte) ([]string, error) {
	entries := bytes.Split(output, []byte{0})
	environment := make([]string, 0, len(entries))
	for _, raw := range entries {
		if len(raw) == 0 {
			continue
		}
		entry := string(raw)
		name, _, ok := strings.Cut(entry, "=")
		if !ok || name == "" || strings.ContainsRune(name, '\x00') {
			return nil, errors.New("shell returned an invalid environment entry")
		}
		environment = append(environment, entry)
	}
	if len(environment) == 0 {
		return nil, errors.New("shell returned an empty environment")
	}
	return environment, nil
}

func normalizeChildEnvironment(source []string, pathOverride string) []string {
	environment := make([]string, 0, len(source)+1)
	positions := make(map[string]int, len(source))
	for _, entry := range source {
		name, _, ok := strings.Cut(entry, "=")
		if !ok || name == "" || blockedChildEnvironmentName(name) ||
			(pathOverride != "" && strings.EqualFold(name, "PATH")) {
			continue
		}
		key := name
		if runtime.GOOS == "windows" {
			key = strings.ToUpper(name)
		}
		if position, exists := positions[key]; exists {
			environment[position] = entry
			continue
		}
		positions[key] = len(environment)
		environment = append(environment, entry)
	}
	if pathOverride != "" {
		environment = append(environment, "PATH="+pathOverride)
	}
	return environment
}

func blockedChildEnvironmentName(name string) bool {
	for _, blocked := range []string{
		"OHPI_TOKEN",
		"OHPI_TOKEN_FILE",
		"OHPI_PI_ENV_PATH",
		"OHPI_PI_ENV_FILE",
		"OHPI_PI_ENV_SHELL",
	} {
		if strings.EqualFold(name, blocked) {
			return true
		}
	}
	return false
}

func (c Config) childEnvironment() []string {
	return append([]string(nil), c.piEnvironment...)
}

func childEnvironment(piPath string) []string {
	return normalizeChildEnvironment(os.Environ(), piPath)
}

func environmentValue(environment []string, name string) string {
	for index := len(environment) - 1; index >= 0; index-- {
		entryName, value, ok := strings.Cut(environment[index], "=")
		if ok && strings.EqualFold(entryName, name) {
			return value
		}
	}
	return ""
}

func lookPathInEnvironment(file string, environment []string) (string, error) {
	if strings.ContainsAny(file, `/\\`) {
		return executablePath(file)
	}
	pathValue := environmentValue(environment, "PATH")
	if pathValue == "" {
		return "", exec.ErrNotFound
	}
	for _, directory := range filepath.SplitList(pathValue) {
		if directory == "" {
			directory = "."
		}
		candidate, err := executablePath(filepath.Join(directory, file))
		if err == nil {
			return candidate, nil
		}
	}
	return "", exec.ErrNotFound
}

func executablePath(path string) (string, error) {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return "", exec.ErrNotFound
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o111 == 0 {
		return "", exec.ErrNotFound
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	return abs, nil
}

func expandUserPath(path string) (string, error) {
	if path != "~" && !strings.HasPrefix(path, "~/") && !strings.HasPrefix(path, `~\`) {
		if strings.HasPrefix(path, "~") {
			return "", errors.New("only the current user's home directory may be abbreviated with ~")
		}
		return path, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve user home directory: %w", err)
	}
	if path == "~" {
		return home, nil
	}
	return filepath.Join(home, path[2:]), nil
}
