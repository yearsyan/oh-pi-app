package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadRuntimeConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	contents := `{
  "OHPI_LISTEN": "0.0.0.0:18080",
  "OHPI_DATA_DIR": "/tmp/ohpi state",
  "OHPI_WORK_DIR": "/tmp/project folder",
  "OHPI_TITLE_MODEL": "openai/gpt-5-nano"
}`
	if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := loadRuntimeConfig(path, true)
	if err != nil {
		t.Fatal(err)
	}
	want := runtimeConfig{
		"OHPI_LISTEN":      "0.0.0.0:18080",
		"OHPI_DATA_DIR":    "/tmp/ohpi state",
		"OHPI_WORK_DIR":    "/tmp/project folder",
		"OHPI_TITLE_MODEL": "openai/gpt-5-nano",
	}
	if len(got) != len(want) {
		t.Fatalf("loaded %d values, want %d", len(got), len(want))
	}
	for key, value := range want {
		if got[key] != value {
			t.Fatalf("%s = %q, want %q", key, got[key], value)
		}
	}
}

func TestNormalizeTitleModel(t *testing.T) {
	valid := map[string]string{
		" auto ":                       "auto",
		"ACTIVE":                       "active",
		"off":                          "off",
		"openai/gpt-5-nano":            "openai/gpt-5-nano",
		"openrouter/openai/gpt-5-nano": "openrouter/openai/gpt-5-nano",
	}
	for input, want := range valid {
		if got, err := normalizeTitleModel(input); err != nil || got != want {
			t.Errorf("normalizeTitleModel(%q) = (%q, %v), want (%q, nil)", input, got, err, want)
		}
	}
	for _, input := range []string{"", "model-only", "/model", "provider/", "openai/gpt nano"} {
		if _, err := normalizeTitleModel(input); err == nil {
			t.Errorf("normalizeTitleModel(%q) unexpectedly succeeded", input)
		}
	}
}

func TestLoadRuntimeConfigMissingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing")
	if got, err := loadRuntimeConfig(path, false); err != nil || len(got) != 0 {
		t.Fatalf("optional missing config = (%v, %v), want empty config", got, err)
	}
	if _, err := loadRuntimeConfig(path, true); err == nil {
		t.Fatal("required missing config unexpectedly succeeded")
	}
}

func TestLoadRuntimeConfigRejectsInvalidJSON(t *testing.T) {
	tests := map[string]string{
		"invalid JSON":        `{`,
		"non-object":          `[]`,
		"unsupported key":     `{"OHPI_TOKEN":"secret"}`,
		"duplicate key":       `{"OHPI_LISTEN":":8080","OHPI_LISTEN":":9090"}`,
		"empty value":         `{"OHPI_DATA_DIR":""}`,
		"non-string value":    `{"OHPI_WORK_DIR":42}`,
		"trailing JSON value": `{} {}`,
	}

	for name, contents := range tests {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "config.json")
			if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := loadRuntimeConfig(path, true); err == nil || !strings.Contains(err.Error(), path) {
				t.Fatalf("loadRuntimeConfig error = %v, want error containing path", err)
			}
		})
	}
}

func TestResolveRuntimeConfigValuePrecedence(t *testing.T) {
	const key = "OHPI_LISTEN"
	fileConfig := runtimeConfig{key: "file-value"}

	t.Setenv(key, "environment-value")
	if got := resolveRuntimeConfigValue("flag-value", true, key, fileConfig); got != "flag-value" {
		t.Fatalf("explicit flag resolved to %q", got)
	}
	if got := resolveRuntimeConfigValue("default-value", false, key, fileConfig); got != "environment-value" {
		t.Fatalf("environment value resolved to %q", got)
	}

	t.Setenv(key, "")
	if got := resolveRuntimeConfigValue("default-value", false, key, fileConfig); got != "file-value" {
		t.Fatalf("file value resolved to %q", got)
	}
	if got := resolveRuntimeConfigValue("default-value", false, key, nil); got != "default-value" {
		t.Fatalf("default value resolved to %q", got)
	}
}

func TestDefaultConfigFileUsesXDGConfigHome(t *testing.T) {
	configHome := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configHome)
	if got, want := defaultConfigFile(), filepath.Join(configHome, appDirectoryName, "config.json"); got != want {
		t.Fatalf("defaultConfigFile() = %q, want %q", got, want)
	}
}

func TestDefaultDataDirUsesXDGStateHome(t *testing.T) {
	stateHome := t.TempDir()
	t.Setenv("XDG_STATE_HOME", stateHome)
	if got, want := defaultDataDir(), filepath.Join(stateHome, appDirectoryName); got != want {
		t.Fatalf("defaultDataDir() = %q, want %q", got, want)
	}
}

func TestDefaultDirectoriesUseOhPiAppName(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", "")
	t.Setenv("XDG_STATE_HOME", "")

	if got, want := defaultConfigFile(), filepath.Join(home, ".config", appDirectoryName, "config.json"); got != want {
		t.Fatalf("defaultConfigFile() = %q, want %q", got, want)
	}
	if got, want := defaultDataDir(), filepath.Join(home, ".local", "state", appDirectoryName); got != want {
		t.Fatalf("defaultDataDir() = %q, want %q", got, want)
	}
}
