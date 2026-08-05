package gateway

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os/exec"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	capabilitiesCacheTTL  = time.Minute
	capabilitiesCacheSize = 32
	capabilitiesStderrMax = 64 << 10
)

var orderedThinkingLevels = []string{"off", "minimal", "low", "medium", "high", "xhigh", "max"}

type capabilitySelection struct {
	Provider      string `json:"provider"`
	ModelID       string `json:"model_id"`
	ThinkingLevel string `json:"thinking_level"`
}

type capabilityModel struct {
	ID             string   `json:"id"`
	Name           string   `json:"name"`
	Provider       string   `json:"provider"`
	ThinkingLevels []string `json:"thinking_levels"`
}

type capabilityCommand struct {
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
	Source      string `json:"source"`
}

type capabilitiesResponse struct {
	WorkDir  string               `json:"work_dir"`
	Default  *capabilitySelection `json:"default,omitempty"`
	Models   []capabilityModel    `json:"models"`
	Commands []capabilityCommand  `json:"commands"`
}

type cachedCapabilities struct {
	response  capabilitiesResponse
	fetchedAt time.Time
}

type capabilitiesCall struct {
	done     chan struct{}
	response capabilitiesResponse
	err      error
}

type capabilitiesLoader struct {
	cfg Config

	mu       sync.Mutex
	cache    map[string]cachedCapabilities
	inflight map[string]*capabilitiesCall
}

func newCapabilitiesLoader(cfg Config) *capabilitiesLoader {
	return &capabilitiesLoader{
		cfg:      cfg,
		cache:    make(map[string]cachedCapabilities),
		inflight: make(map[string]*capabilitiesCall),
	}
}

func (loader *capabilitiesLoader) get(ctx context.Context, workDir string) (capabilitiesResponse, error) {
	now := time.Now()
	loader.mu.Lock()
	if cached, ok := loader.cache[workDir]; ok && now.Sub(cached.fetchedAt) < capabilitiesCacheTTL {
		loader.mu.Unlock()
		return cached.response, nil
	}
	if call := loader.inflight[workDir]; call != nil {
		loader.mu.Unlock()
		select {
		case <-call.done:
			return call.response, call.err
		case <-ctx.Done():
			return capabilitiesResponse{}, ctx.Err()
		}
	}
	call := &capabilitiesCall{done: make(chan struct{})}
	loader.inflight[workDir] = call
	loader.mu.Unlock()

	probeContext, cancel := context.WithTimeout(ctx, loader.cfg.HistoryTimeout)
	response, err := probeCapabilities(probeContext, loader.cfg, workDir)
	cancel()

	loader.mu.Lock()
	delete(loader.inflight, workDir)
	call.response = response
	call.err = err
	if err == nil {
		loader.insertLocked(workDir, cachedCapabilities{response: response, fetchedAt: time.Now()})
	}
	close(call.done)
	loader.mu.Unlock()
	return response, err
}

func (loader *capabilitiesLoader) insertLocked(workDir string, value cachedCapabilities) {
	if len(loader.cache) >= capabilitiesCacheSize {
		oldestKey := ""
		var oldestTime time.Time
		for key, cached := range loader.cache {
			if oldestKey == "" || cached.fetchedAt.Before(oldestTime) {
				oldestKey = key
				oldestTime = cached.fetchedAt
			}
		}
		delete(loader.cache, oldestKey)
	}
	loader.cache[workDir] = value
}

func (g *Gateway) handleCapabilities(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}

	workDir, err := g.resolveWorkDir(request.URL.Query().Get("work_dir"))
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_work_dir", err.Error())
		return
	}
	response, err := g.capabilities.get(request.Context(), workDir)
	if err != nil {
		g.cfg.Logger.Error("inspect pi capabilities", "work_dir", workDir, "error", err)
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
			writeHTTPError(writer, http.StatusGatewayTimeout, "capabilities_timeout", "pi capability discovery timed out")
			return
		}
		writeHTTPError(writer, http.StatusBadGateway, "capabilities_failed", "could not inspect pi capabilities")
		return
	}
	writeJSONResponse(writer, http.StatusOK, response)
}

type capabilityRPCResponse struct {
	Type    string          `json:"type"`
	ID      string          `json:"id"`
	Command string          `json:"command"`
	Success bool            `json:"success"`
	Error   string          `json:"error"`
	Data    json.RawMessage `json:"data"`
}

type capabilityRPCState struct {
	Model         *capabilityRawModel `json:"model"`
	ThinkingLevel string              `json:"thinkingLevel"`
}

type capabilityRPCModels struct {
	Models []capabilityRawModel `json:"models"`
}

type capabilityRPCCommands struct {
	Commands []capabilityCommand `json:"commands"`
}

type capabilityRawModel struct {
	ID               string                     `json:"id"`
	Name             string                     `json:"name"`
	Provider         string                     `json:"provider"`
	Reasoning        bool                       `json:"reasoning"`
	ThinkingLevelMap map[string]json.RawMessage `json:"thinkingLevelMap"`
}

func probeCapabilities(ctx context.Context, cfg Config, workDir string) (capabilitiesResponse, error) {
	args := append([]string(nil), cfg.PiArgs...)
	args = append(args, "--mode", "rpc", "--no-session")
	command := exec.CommandContext(ctx, cfg.PiCommand, args...)
	command.Dir = workDir
	command.Env = childEnvironment()

	stdin, err := command.StdinPipe()
	if err != nil {
		return capabilitiesResponse{}, fmt.Errorf("open capability probe stdin: %w", err)
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return capabilitiesResponse{}, fmt.Errorf("open capability probe stdout: %w", err)
	}
	stderr := &limitedBuffer{limit: capabilitiesStderrMax}
	command.Stderr = stderr
	if err := command.Start(); err != nil {
		_ = stdin.Close()
		return capabilitiesResponse{}, fmt.Errorf("start capability probe: %w", err)
	}

	encoder := json.NewEncoder(stdin)
	commands := []map[string]string{
		{"id": "ohpi-capabilities-state", "type": "get_state"},
		{"id": "ohpi-capabilities-models", "type": "get_available_models"},
		{"id": "ohpi-capabilities-commands", "type": "get_commands"},
	}
	for _, rpcCommand := range commands {
		if err := encoder.Encode(rpcCommand); err != nil {
			_ = stdin.Close()
			_ = command.Wait()
			return capabilitiesResponse{}, fmt.Errorf("send capability probe command: %w", err)
		}
	}

	var state *capabilityRPCState
	var models *capabilityRPCModels
	var availableCommands capabilityRPCCommands
	commandsDone := false
	scanner := bufio.NewScanner(stdout)
	scanner.Split(splitLF)
	scanner.Buffer(make([]byte, 64<<10), int(cfg.MaxMessageBytes))
	for scanner.Scan() {
		var response capabilityRPCResponse
		if err := json.Unmarshal(scanner.Bytes(), &response); err != nil {
			continue
		}
		if response.Type != "response" {
			continue
		}
		if !response.Success {
			if response.ID == "ohpi-capabilities-commands" {
				// get_commands was added after the core capability RPCs. Keep
				// older pi binaries usable and return an empty command list.
				commandsDone = true
				if state != nil && models != nil {
					break
				}
			} else if response.ID == "ohpi-capabilities-state" || response.ID == "ohpi-capabilities-models" {
				_ = stdin.Close()
				_ = command.Wait()
				return capabilitiesResponse{}, fmt.Errorf("%s: %s", response.Command, response.Error)
			}
			continue
		}
		switch response.ID {
		case "ohpi-capabilities-state":
			var value capabilityRPCState
			if err := json.Unmarshal(response.Data, &value); err != nil {
				_ = stdin.Close()
				_ = command.Wait()
				return capabilitiesResponse{}, fmt.Errorf("decode capability state: %w", err)
			}
			state = &value
		case "ohpi-capabilities-models":
			var value capabilityRPCModels
			if err := json.Unmarshal(response.Data, &value); err != nil {
				_ = stdin.Close()
				_ = command.Wait()
				return capabilitiesResponse{}, fmt.Errorf("decode capability models: %w", err)
			}
			models = &value
		case "ohpi-capabilities-commands":
			if err := json.Unmarshal(response.Data, &availableCommands); err != nil {
				_ = stdin.Close()
				_ = command.Wait()
				return capabilitiesResponse{}, fmt.Errorf("decode capability commands: %w", err)
			}
			commandsDone = true
		}
		if state != nil && models != nil && commandsDone {
			break
		}
	}
	scanErr := scanner.Err()
	_ = stdin.Close()
	waitErr := command.Wait()
	if ctx.Err() != nil {
		return capabilitiesResponse{}, ctx.Err()
	}
	if scanErr != nil {
		return capabilitiesResponse{}, fmt.Errorf("read capability probe output: %w", scanErr)
	}
	if state == nil || models == nil {
		if waitErr != nil {
			return capabilitiesResponse{}, fmt.Errorf("capability probe exited: %w: %s", waitErr, stderr.String())
		}
		return capabilitiesResponse{}, errors.New("capability probe did not return state and models")
	}
	if waitErr != nil {
		return capabilitiesResponse{}, fmt.Errorf("stop capability probe: %w: %s", waitErr, stderr.String())
	}

	response := capabilitiesResponse{
		WorkDir:  workDir,
		Models:   make([]capabilityModel, 0, len(models.Models)),
		Commands: make([]capabilityCommand, 0, len(availableCommands.Commands)),
	}
	for _, model := range models.Models {
		if model.ID == "" || model.Provider == "" {
			continue
		}
		response.Models = append(response.Models, capabilityModel{
			ID:             model.ID,
			Name:           model.Name,
			Provider:       model.Provider,
			ThinkingLevels: supportedThinkingLevels(model),
		})
	}
	sort.SliceStable(response.Models, func(i, j int) bool {
		if response.Models[i].Provider != response.Models[j].Provider {
			return response.Models[i].Provider < response.Models[j].Provider
		}
		return response.Models[i].ID < response.Models[j].ID
	})
	for _, available := range availableCommands.Commands {
		available.Name = strings.TrimSpace(available.Name)
		available.Description = strings.TrimSpace(available.Description)
		available.Source = strings.TrimSpace(available.Source)
		if available.Name == "" || available.Source == "" {
			continue
		}
		response.Commands = append(response.Commands, available)
	}
	sort.SliceStable(response.Commands, func(i, j int) bool {
		if response.Commands[i].Source != response.Commands[j].Source {
			return response.Commands[i].Source < response.Commands[j].Source
		}
		return response.Commands[i].Name < response.Commands[j].Name
	})
	if state.Model != nil && state.Model.ID != "" && state.Model.Provider != "" {
		response.Default = &capabilitySelection{
			Provider:      state.Model.Provider,
			ModelID:       state.Model.ID,
			ThinkingLevel: state.ThinkingLevel,
		}
	}
	return response, nil
}

func supportedThinkingLevels(model capabilityRawModel) []string {
	if !model.Reasoning {
		return []string{"off"}
	}
	levels := make([]string, 0, len(orderedThinkingLevels))
	for _, level := range orderedThinkingLevels {
		mapped, exists := model.ThinkingLevelMap[level]
		if exists && bytes.Equal(bytes.TrimSpace(mapped), []byte("null")) {
			continue
		}
		if (level == "xhigh" || level == "max") && !exists {
			continue
		}
		levels = append(levels, level)
	}
	return levels
}

type limitedBuffer struct {
	buffer bytes.Buffer
	limit  int
}

func (buffer *limitedBuffer) Write(data []byte) (int, error) {
	written := len(data)
	remaining := buffer.limit - buffer.buffer.Len()
	if remaining > 0 {
		_, _ = buffer.buffer.Write(data[:min(remaining, len(data))])
	}
	return written, nil
}

func (buffer *limitedBuffer) String() string {
	return strings.TrimSpace(buffer.buffer.String())
}
