package gateway

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestRuntimeConfigAPIUpdatesConfigAndRequestsRestart(t *testing.T) {
	directory := t.TempDir()
	configPath := filepath.Join(directory, "config.json")
	initial := `{"OHPI_LISTEN":"127.0.0.1:18080","OHPI_TITLE_MODEL":"auto"}`
	if err := os.WriteFile(configPath, []byte(initial), 0o640); err != nil {
		t.Fatal(err)
	}
	initialInfo, err := os.Stat(configPath)
	if err != nil {
		t.Fatal(err)
	}
	environmentFile := filepath.Join(directory, ".profile")
	if err := os.WriteFile(environmentFile, []byte("export TEST_VALUE=loaded\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	restarted := make(chan struct{}, 1)
	app, err := New(Config{
		Token:             testToken,
		DataDir:           filepath.Join(directory, "data"),
		WorkDir:           directory,
		PiCommand:         os.Args[0],
		RuntimeConfigPath: configPath,
		TitleModel:        "auto",
		RequestRestart: func() {
			restarted <- struct{}{}
		},
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { shutdownGateway(t, app) })

	unauthorized := httptest.NewRequest(http.MethodGet, "/api/runtime-config", nil)
	unauthorizedResponse := httptest.NewRecorder()
	app.Handler().ServeHTTP(unauthorizedResponse, unauthorized)
	if unauthorizedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized status = %d, want %d", unauthorizedResponse.Code, http.StatusUnauthorized)
	}

	updateBody, err := json.Marshal(map[string]string{
		"title_model":  "active",
		"pi_env_file":  environmentFile,
		"pi_env_shell": "/bin/sh",
	})
	if err != nil {
		t.Fatal(err)
	}
	updateRequest := authenticatedRuntimeRequest(http.MethodPatch, "/api/runtime-config", updateBody)
	updateResponse := httptest.NewRecorder()
	app.Handler().ServeHTTP(updateResponse, updateRequest)
	if updateResponse.Code != http.StatusOK {
		t.Fatalf("update status = %d, body=%s", updateResponse.Code, updateResponse.Body.String())
	}
	var response runtimeConfigResponse
	if err := json.NewDecoder(updateResponse.Body).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if response.TitleModel != "active" || response.PiEnvironmentFile != environmentFile ||
		response.PiEnvironmentShell != "/bin/sh" || !response.RestartRequired || !response.RestartSupported {
		t.Fatalf("unexpected runtime config response: %+v", response)
	}

	values, err := readRuntimeConfigValues(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if values["OHPI_LISTEN"] != "127.0.0.1:18080" ||
		values[runtimeConfigTitleModelKey] != "active" ||
		values[runtimeConfigEnvFileKey] != environmentFile ||
		values[runtimeConfigEnvShellKey] != "/bin/sh" {
		t.Fatalf("persisted runtime config = %#v", values)
	}
	if info, err := os.Stat(configPath); err != nil || info.Mode().Perm() != initialInfo.Mode().Perm() {
		t.Fatalf("config permissions = (%v, %v), want %v", info, err, initialInfo.Mode().Perm())
	}

	restartRequest := authenticatedRuntimeRequest(http.MethodPost, "/api/runtime-restart", nil)
	restartResponse := httptest.NewRecorder()
	app.Handler().ServeHTTP(restartResponse, restartRequest)
	if restartResponse.Code != http.StatusAccepted {
		t.Fatalf("restart status = %d, body=%s", restartResponse.Code, restartResponse.Body.String())
	}
	restartingHealth := httptest.NewRecorder()
	app.Handler().ServeHTTP(restartingHealth, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if restartingHealth.Code != http.StatusServiceUnavailable {
		t.Fatalf("health during restart = %d, want %d", restartingHealth.Code, http.StatusServiceUnavailable)
	}
	select {
	case <-restarted:
	case <-time.After(time.Second):
		t.Fatal("restart callback was not invoked")
	}
}

func TestRuntimeConfigAPIClearsEnvironmentSourceAndRejectsInvalidValues(t *testing.T) {
	directory := t.TempDir()
	configPath := filepath.Join(directory, "config.json")
	environmentFile := filepath.Join(directory, ".zshrc")
	if err := os.WriteFile(environmentFile, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	values := map[string]string{
		runtimeConfigTitleModelKey: "auto",
		runtimeConfigEnvFileKey:    environmentFile,
		runtimeConfigEnvShellKey:   "/bin/zsh",
	}
	if err := writeRuntimeConfigValues(configPath, values); err != nil {
		t.Fatal(err)
	}
	app, err := New(Config{
		Token:              testToken,
		DataDir:            filepath.Join(directory, "data"),
		WorkDir:            directory,
		PiCommand:          os.Args[0],
		RuntimeConfigPath:  configPath,
		TitleModel:         "auto",
		PiEnvironmentFile:  environmentFile,
		PiEnvironmentShell: "/bin/zsh",
		Logger:             slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { shutdownGateway(t, app) })

	invalid := authenticatedRuntimeRequest(http.MethodPatch, "/api/runtime-config", []byte(`{"title_model":"not-a-model"}`))
	invalidResponse := httptest.NewRecorder()
	app.Handler().ServeHTTP(invalidResponse, invalid)
	if invalidResponse.Code != http.StatusBadRequest {
		t.Fatalf("invalid update status = %d, body=%s", invalidResponse.Code, invalidResponse.Body.String())
	}

	clearRequest := authenticatedRuntimeRequest(http.MethodPatch, "/api/runtime-config", []byte(`{"pi_env_file":""}`))
	clearResponse := httptest.NewRecorder()
	app.Handler().ServeHTTP(clearResponse, clearRequest)
	if clearResponse.Code != http.StatusOK {
		t.Fatalf("clear status = %d, body=%s", clearResponse.Code, clearResponse.Body.String())
	}
	persisted, err := readRuntimeConfigValues(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := persisted[runtimeConfigEnvFileKey]; ok {
		t.Fatalf("environment file key was not removed: %#v", persisted)
	}
	if _, ok := persisted[runtimeConfigEnvShellKey]; ok {
		t.Fatalf("environment shell key was not removed: %#v", persisted)
	}
}

func authenticatedRuntimeRequest(method, target string, body []byte) *http.Request {
	request := httptest.NewRequest(method, target, bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+testToken)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	return request
}
