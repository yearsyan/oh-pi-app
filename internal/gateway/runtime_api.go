package gateway

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	runtimeConfigTitleModelKey         = "OHPI_TITLE_MODEL"
	runtimeConfigEnvFileKey            = "OHPI_PI_ENV_FILE"
	runtimeConfigEnvShellKey           = "OHPI_PI_ENV_SHELL"
	runtimeConfigScheduledRetentionKey = "OHPI_SCHEDULED_SESSION_RETENTION"
	runtimeConfigMaxBody               = 16 << 10
	runtimeConfigMaxFile               = 64 << 10
)

type runtimeSettings struct {
	TitleModel                string
	PiEnvironmentFile         string
	PiEnvironmentShell        string
	ScheduledSessionRetention time.Duration
}

type runtimeConfigResponse struct {
	TitleModel                       string `json:"title_model"`
	PiEnvironmentFile                string `json:"pi_env_file"`
	PiEnvironmentShell               string `json:"pi_env_shell"`
	ScheduledSessionRetentionSeconds int64  `json:"scheduled_session_retention_seconds"`
	RestartRequired                  bool   `json:"restart_required"`
	RestartSupported                 bool   `json:"restart_supported"`
}

type runtimeConfigUpdate struct {
	TitleModel                       *string `json:"title_model"`
	PiEnvironmentFile                *string `json:"pi_env_file"`
	PiEnvironmentShell               *string `json:"pi_env_shell"`
	ScheduledSessionRetentionSeconds *int64  `json:"scheduled_session_retention_seconds"`
}

type runtimeConfigService struct {
	mu             sync.Mutex
	path           string
	active         runtimeSettings
	pending        *runtimeSettings
	requestRestart func()
	restarting     atomic.Bool
}

func newRuntimeConfigService(cfg Config) *runtimeConfigService {
	if cfg.RuntimeConfigPath == "" {
		return nil
	}
	return &runtimeConfigService{
		path: cfg.RuntimeConfigPath,
		active: runtimeSettings{
			TitleModel:                cfg.TitleModel,
			PiEnvironmentFile:         cfg.PiEnvironmentFile,
			PiEnvironmentShell:        cfg.PiEnvironmentShell,
			ScheduledSessionRetention: cfg.ScheduledSessionRetention,
		},
		requestRestart: cfg.RequestRestart,
	}
}

func (service *runtimeConfigService) responseLocked(settings runtimeSettings) runtimeConfigResponse {
	return runtimeConfigResponse{
		TitleModel:                       settings.TitleModel,
		PiEnvironmentFile:                settings.PiEnvironmentFile,
		PiEnvironmentShell:               settings.PiEnvironmentShell,
		ScheduledSessionRetentionSeconds: int64(settings.ScheduledSessionRetention / time.Second),
		RestartRequired:                  settings != service.active,
		RestartSupported:                 service.requestRestart != nil,
	}
}

func (service *runtimeConfigService) current() runtimeConfigResponse {
	service.mu.Lock()
	defer service.mu.Unlock()
	settings := service.active
	if service.pending != nil {
		settings = *service.pending
	}
	return service.responseLocked(settings)
}

func (service *runtimeConfigService) update(update runtimeConfigUpdate) (runtimeConfigResponse, error) {
	service.mu.Lock()
	defer service.mu.Unlock()

	settings := service.active
	if service.pending != nil {
		settings = *service.pending
	}
	if update.TitleModel != nil {
		value, err := normalizeRuntimeTitleModel(*update.TitleModel)
		if err != nil {
			return runtimeConfigResponse{}, &runtimeConfigValidationError{err: err}
		}
		settings.TitleModel = value
	}
	if update.PiEnvironmentFile != nil {
		settings.PiEnvironmentFile = strings.TrimSpace(*update.PiEnvironmentFile)
		if settings.PiEnvironmentFile == "" {
			settings.PiEnvironmentShell = ""
		}
	}
	if update.PiEnvironmentShell != nil {
		settings.PiEnvironmentShell = strings.TrimSpace(*update.PiEnvironmentShell)
	}
	if update.ScheduledSessionRetentionSeconds != nil {
		seconds := *update.ScheduledSessionRetentionSeconds
		const maximumSeconds = int64((10 * 365 * 24 * time.Hour) / time.Second)
		if seconds < int64(time.Hour/time.Second) || seconds > maximumSeconds {
			return runtimeConfigResponse{}, &runtimeConfigValidationError{
				err: errors.New("scheduled session retention must be between one hour and ten years"),
			}
		}
		settings.ScheduledSessionRetention = time.Duration(seconds) * time.Second
	}
	if settings.PiEnvironmentFile == "" && settings.PiEnvironmentShell != "" {
		return runtimeConfigResponse{}, &runtimeConfigValidationError{err: errors.New("pi_env_shell requires pi_env_file")}
	}
	if settings.PiEnvironmentFile != "" {
		if len(settings.PiEnvironmentFile) > 4096 || len(settings.PiEnvironmentShell) > 4096 ||
			strings.ContainsAny(settings.PiEnvironmentFile, "\x00\r\n") ||
			strings.ContainsAny(settings.PiEnvironmentShell, "\x00\r\n") {
			return runtimeConfigResponse{}, &runtimeConfigValidationError{err: errors.New("pi environment file or shell is invalid")}
		}
		if _, _, err := resolvePiEnvironmentSource(
			settings.PiEnvironmentFile,
			settings.PiEnvironmentShell,
			childEnvironment(""),
		); err != nil {
			return runtimeConfigResponse{}, &runtimeConfigValidationError{err: err}
		}
	}

	values, err := readRuntimeConfigValues(service.path)
	if err != nil {
		return runtimeConfigResponse{}, err
	}
	values[runtimeConfigTitleModelKey] = settings.TitleModel
	values[runtimeConfigScheduledRetentionKey] = settings.ScheduledSessionRetention.String()
	if settings.PiEnvironmentFile == "" {
		delete(values, runtimeConfigEnvFileKey)
		delete(values, runtimeConfigEnvShellKey)
	} else {
		values[runtimeConfigEnvFileKey] = settings.PiEnvironmentFile
		if settings.PiEnvironmentShell == "" {
			delete(values, runtimeConfigEnvShellKey)
		} else {
			values[runtimeConfigEnvShellKey] = settings.PiEnvironmentShell
		}
	}
	if err := writeRuntimeConfigValues(service.path, values); err != nil {
		return runtimeConfigResponse{}, err
	}
	service.pending = &settings
	return service.responseLocked(settings), nil
}

func normalizeRuntimeTitleModel(value string) (string, error) {
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

func (g *Gateway) handleRuntimeConfig(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet && request.Method != http.MethodPatch {
		writer.Header().Set("Allow", http.MethodGet+", "+http.MethodPatch)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET and PATCH are allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	if request.Method == http.MethodGet {
		writeJSONResponse(writer, http.StatusOK, g.runtimeConfig.current())
		return
	}

	request.Body = http.MaxBytesReader(writer, request.Body, runtimeConfigMaxBody)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var update runtimeConfigUpdate
	if err := decoder.Decode(&update); err != nil || ensureJSONEOF(decoder) != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_request", "body must contain runtime configuration fields")
		return
	}
	if update.TitleModel == nil && update.PiEnvironmentFile == nil && update.PiEnvironmentShell == nil &&
		update.ScheduledSessionRetentionSeconds == nil {
		writeHTTPError(writer, http.StatusBadRequest, "empty_update", "at least one runtime configuration field is required")
		return
	}
	response, err := g.runtimeConfig.update(update)
	if err != nil {
		var validationError *runtimeConfigValidationError
		if errors.As(err, &validationError) {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_runtime_config", validationError.Error())
			return
		}
		g.cfg.Logger.Error("persist runtime configuration", "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "runtime_config_write_failed", "could not persist runtime configuration")
		return
	}
	writeJSONResponse(writer, http.StatusOK, response)
}

func (g *Gateway) handleRuntimeRestart(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodPost {
		writer.Header().Set("Allow", http.MethodPost)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only POST is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	if g.runtimeConfig.requestRestart == nil {
		writeHTTPError(writer, http.StatusNotImplemented, "restart_unavailable", "gateway restart is not managed by this process")
		return
	}
	alreadyRestarting := g.runtimeConfig.restarting.Swap(true)
	writeJSONResponse(writer, http.StatusAccepted, struct {
		Status string `json:"status"`
	}{Status: "restarting"})
	if flusher, ok := writer.(http.Flusher); ok {
		flusher.Flush()
	}
	if !alreadyRestarting {
		go g.runtimeConfig.requestRestart()
	}
}

type runtimeConfigValidationError struct {
	err error
}

func (err *runtimeConfigValidationError) Error() string {
	return err.err.Error()
}

func readRuntimeConfigValues(path string) (map[string]string, error) {
	file, err := os.Open(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return make(map[string]string), nil
		}
		return nil, fmt.Errorf("open runtime configuration: %w", err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil, fmt.Errorf("inspect runtime configuration: %w", err)
	}
	if info.Size() > runtimeConfigMaxFile {
		return nil, fmt.Errorf("runtime configuration exceeds %d bytes", runtimeConfigMaxFile)
	}
	decoder := json.NewDecoder(io.LimitReader(file, runtimeConfigMaxFile+1))
	var values map[string]string
	if err := decoder.Decode(&values); err != nil || ensureJSONEOF(decoder) != nil {
		return nil, errors.New("runtime configuration must be one JSON object containing string values")
	}
	if values == nil {
		return nil, errors.New("runtime configuration must be a JSON object")
	}
	for key, value := range values {
		if !supportedRuntimeConfigKey(key) {
			return nil, fmt.Errorf("runtime configuration contains unsupported key %q", key)
		}
		if value == "" {
			return nil, fmt.Errorf("runtime configuration value %s must not be empty", key)
		}
	}
	return values, nil
}

func writeRuntimeConfigValues(path string, values map[string]string) error {
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("create runtime configuration directory: %w", err)
	}
	mode := os.FileMode(0o600)
	if info, err := os.Stat(path); err == nil {
		mode = info.Mode().Perm()
	} else if !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("inspect runtime configuration: %w", err)
	}

	temporary, err := os.CreateTemp(directory, ".config-*.tmp")
	if err != nil {
		return fmt.Errorf("create temporary runtime configuration: %w", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(mode); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("set runtime configuration permissions: %w", err)
	}
	encoder := json.NewEncoder(temporary)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(values); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("encode runtime configuration: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("sync runtime configuration: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close runtime configuration: %w", err)
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("replace runtime configuration: %w", err)
	}
	return nil
}

func supportedRuntimeConfigKey(key string) bool {
	switch key {
	case "OHPI_LISTEN", "OHPI_DATA_DIR", "OHPI_WORK_DIR", runtimeConfigTitleModelKey,
		"OHPI_PI_COMMAND", "OHPI_PI_ENV_PATH", runtimeConfigEnvFileKey, runtimeConfigEnvShellKey,
		runtimeConfigScheduledRetentionKey:
		return true
	default:
		return false
	}
}
