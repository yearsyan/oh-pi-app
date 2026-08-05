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

func defaultConfigFile() string {
	if configHome := os.Getenv("XDG_CONFIG_HOME"); configHome != "" {
		return filepath.Join(configHome, "pi2ws", "config.json")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join(".", ".pi2ws", "config.json")
	}
	return filepath.Join(home, ".config", "pi2ws", "config.json")
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
	case "PI2WS_LISTEN", "PI2WS_DATA_DIR", "PI2WS_WORK_DIR", "PI2WS_TITLE_MODEL":
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
