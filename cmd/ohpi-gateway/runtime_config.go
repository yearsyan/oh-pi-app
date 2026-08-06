package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type runtimeConfig map[string]string

const appDirectoryName = "oh-pi-app"

func defaultConfigFile() string {
	if configHome := os.Getenv("XDG_CONFIG_HOME"); configHome != "" {
		return filepath.Join(configHome, appDirectoryName, "config.json")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join(".", "."+appDirectoryName, "config.json")
	}
	return filepath.Join(home, ".config", appDirectoryName, "config.json")
}

func loadRuntimeConfig(path string, required bool) (runtimeConfig, error) {
	values := make(runtimeConfig)
	if path == "" {
		return values, nil
	}

	file, err := os.Open(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) && !required {
			return values, nil
		}
		return nil, fmt.Errorf("open config: %w", err)
	}
	defer file.Close()

	values, err = decodeRuntimeConfig(file)
	if err != nil {
		return nil, fmt.Errorf("parse config %q: %w", path, err)
	}
	return values, nil
}

func decodeRuntimeConfig(reader io.Reader) (runtimeConfig, error) {
	decoder := json.NewDecoder(reader)
	start, err := decoder.Token()
	if err != nil {
		if errors.Is(err, io.EOF) {
			return nil, errors.New("expected a JSON object")
		}
		return nil, err
	}
	if delimiter, ok := start.(json.Delim); !ok || delimiter != '{' {
		return nil, errors.New("top-level value must be a JSON object")
	}

	values := make(runtimeConfig)
	for decoder.More() {
		keyToken, err := decoder.Token()
		if err != nil {
			return nil, err
		}
		key, ok := keyToken.(string)
		if !ok {
			return nil, errors.New("configuration key must be a string")
		}
		if !isRuntimeConfigKey(key) {
			return nil, fmt.Errorf("unsupported key %q", key)
		}
		if _, exists := values[key]; exists {
			return nil, fmt.Errorf("duplicate key %q", key)
		}

		var value string
		if err := decoder.Decode(&value); err != nil {
			return nil, fmt.Errorf("decode %s: %w", key, err)
		}
		if value == "" {
			return nil, fmt.Errorf("%s must not be empty", key)
		}
		values[key] = value
	}
	if _, err := decoder.Token(); err != nil {
		return nil, err
	}

	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		if err == nil {
			return nil, errors.New("multiple top-level JSON values")
		}
		return nil, err
	}
	return values, nil
}

func isRuntimeConfigKey(key string) bool {
	switch key {
	case "OHPI_LISTEN", "OHPI_DATA_DIR", "OHPI_WORK_DIR", "OHPI_TITLE_MODEL", "OHPI_PI_COMMAND", "OHPI_PI_ENV_PATH":
		return true
	default:
		return false
	}
}

func normalizeTitleModel(value string) (string, error) {
	value = strings.TrimSpace(value)
	switch strings.ToLower(value) {
	case "auto", "active", "off":
		return strings.ToLower(value), nil
	}
	provider, modelID, ok := strings.Cut(value, "/")
	if !ok || strings.TrimSpace(provider) == "" || strings.TrimSpace(modelID) == "" {
		return "", errors.New("title model must be auto, active, off, or provider/model-id")
	}
	if strings.ContainsAny(value, "\r\n\t ") {
		return "", errors.New("title model must not contain whitespace")
	}
	return value, nil
}

func resolveRuntimeConfigValue(
	flagValue string,
	flagProvided bool,
	environmentKey string,
	fileConfig runtimeConfig,
) string {
	if flagProvided {
		return flagValue
	}
	if value := os.Getenv(environmentKey); value != "" {
		return value
	}
	if value, ok := fileConfig[environmentKey]; ok {
		return value
	}
	return flagValue
}

func resolveAuthenticationToken(value, path string) (string, error) {
	if value != "" || path == "" {
		return value, nil
	}
	contents, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read token file %q: %w", path, err)
	}
	if len(contents) > 64<<10 {
		return "", fmt.Errorf("token file %q is too large", path)
	}
	token := strings.TrimSuffix(string(contents), "\n")
	token = strings.TrimSuffix(token, "\r")
	if token == "" {
		return "", fmt.Errorf("token file %q is empty", path)
	}
	if strings.ContainsAny(token, "\r\n") {
		return "", fmt.Errorf("token file %q must contain exactly one line", path)
	}
	return token, nil
}
