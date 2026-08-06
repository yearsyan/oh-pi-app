package gateway

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	providerEventPrefix  = "__OHPI_PROVIDER_EVENT__"
	providerPromptPrefix = "__OHPI_PROVIDER_PROMPT__"
	providerCommandID    = "ohpi-provider-command"
	providerStderrMax    = 64 << 10
	providerClientMax    = int64(1 << 20)
)

var providerIDPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,99}$`)

type providerAuthMethod struct {
	Type  string `json:"type"`
	Name  string `json:"name"`
	Label string `json:"label,omitempty"`
}

type providerModel struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	Reasoning bool     `json:"reasoning"`
	Input     []string `json:"input"`
}

type providerInfo struct {
	ID             string               `json:"id"`
	Name           string               `json:"name"`
	Configured     bool                 `json:"configured"`
	AuthSource     string               `json:"auth_source,omitempty"`
	AuthLabel      string               `json:"auth_label,omitempty"`
	StoredAuthType string               `json:"stored_auth_type,omitempty"`
	AuthMethods    []providerAuthMethod `json:"auth_methods"`
	Models         []providerModel      `json:"models"`
}

type providersResponse struct {
	Providers []providerInfo `json:"providers"`
}

type providerExtensionEvent struct {
	Event        string         `json:"event"`
	Action       string         `json:"action,omitempty"`
	ProviderID   string         `json:"provider_id,omitempty"`
	ProviderName string         `json:"provider_name,omitempty"`
	AuthType     string         `json:"auth_type,omitempty"`
	Message      string         `json:"message,omitempty"`
	URL          string         `json:"url,omitempty"`
	Instructions string         `json:"instructions,omitempty"`
	UserCode     string         `json:"userCode,omitempty"`
	VerifyURI    string         `json:"verificationUri,omitempty"`
	Interval     int            `json:"intervalSeconds,omitempty"`
	ExpiresIn    int            `json:"expiresInSeconds,omitempty"`
	Links        []providerLink `json:"links,omitempty"`
	Providers    []providerInfo `json:"providers,omitempty"`
}

type providerLink struct {
	URL   string `json:"url"`
	Label string `json:"label,omitempty"`
}

type providerPromptMetadata struct {
	Kind         string   `json:"kind"`
	Message      string   `json:"message"`
	Placeholder  string   `json:"placeholder,omitempty"`
	Descriptions []string `json:"descriptions,omitempty"`
}

type providerWireEvent struct {
	Type         string         `json:"type"`
	Event        string         `json:"event"`
	ID           string         `json:"id,omitempty"`
	Kind         string         `json:"kind,omitempty"`
	Message      string         `json:"message,omitempty"`
	Placeholder  string         `json:"placeholder,omitempty"`
	Options      []string       `json:"options,omitempty"`
	Descriptions []string       `json:"descriptions,omitempty"`
	Action       string         `json:"action,omitempty"`
	ProviderID   string         `json:"provider_id,omitempty"`
	ProviderName string         `json:"provider_name,omitempty"`
	AuthType     string         `json:"auth_type,omitempty"`
	URL          string         `json:"url,omitempty"`
	Instructions string         `json:"instructions,omitempty"`
	UserCode     string         `json:"user_code,omitempty"`
	VerifyURI    string         `json:"verification_uri,omitempty"`
	Interval     int            `json:"interval_seconds,omitempty"`
	ExpiresIn    int            `json:"expires_in_seconds,omitempty"`
	Links        []providerLink `json:"links,omitempty"`
}

type providerRPCMessage struct {
	Type       string          `json:"type"`
	ID         string          `json:"id"`
	Method     string          `json:"method"`
	Title      string          `json:"title"`
	Options    []string        `json:"options"`
	Message    string          `json:"message"`
	NotifyType string          `json:"notifyType"`
	Command    string          `json:"command"`
	Success    bool            `json:"success"`
	Error      string          `json:"error"`
	Data       json.RawMessage `json:"data"`
}

type providerAuthScanResult struct {
	event    providerWireEvent
	terminal bool
	err      error
}

type providerService struct {
	cfg           Config
	extensionPath string
	mutationMu    sync.Mutex
}

func newProviderService(cfg Config, extensionPath string) *providerService {
	return &providerService{cfg: cfg, extensionPath: extensionPath}
}

type providerProcess struct {
	command    *exec.Cmd
	stdin      io.WriteCloser
	stdout     io.ReadCloser
	stderrDone chan struct{}
	stderr     *limitedBuffer
}

func (service *providerService) start(ctx context.Context) (*providerProcess, error) {
	args := append([]string(nil), service.cfg.ProviderPiArgs...)
	args = append(args,
		"--no-extensions",
		"--no-skills",
		"--no-prompt-templates",
		"--no-context-files",
		"--no-tools",
		"--offline",
		"--extension", service.extensionPath,
		"--mode", "rpc",
		"--no-session",
	)
	command := newPiProcessContext(ctx, service.cfg.PiCommand, args...)
	command.Dir = service.cfg.WorkDir
	command.Env = childEnvironment(service.cfg.PiEnvironmentPath)
	stdin, err := command.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("open provider helper stdin: %w", err)
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return nil, fmt.Errorf("open provider helper stdout: %w", err)
	}
	stderrPipe, err := command.StderrPipe()
	if err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		return nil, fmt.Errorf("open provider helper stderr: %w", err)
	}
	if err := command.Start(); err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		_ = stderrPipe.Close()
		return nil, fmt.Errorf("start provider helper: %w", err)
	}
	process := &providerProcess{
		command:    command,
		stdin:      stdin,
		stdout:     stdout,
		stderrDone: make(chan struct{}),
		stderr:     &limitedBuffer{limit: providerStderrMax},
	}
	go func() {
		_, _ = io.Copy(process.stderr, stderrPipe)
		close(process.stderrDone)
	}()
	return process, nil
}

func (process *providerProcess) send(value any) error {
	return json.NewEncoder(process.stdin).Encode(value)
}

func (process *providerProcess) closeAndWait() error {
	_ = process.stdin.Close()
	err := process.command.Wait()
	<-process.stderrDone
	return err
}

func providerPromptCommand(action, providerID, authType string) map[string]string {
	parts := []string{"/ohpi-provider", action}
	if providerID != "" {
		parts = append(parts, providerID)
	}
	if authType != "" {
		parts = append(parts, authType)
	}
	return map[string]string{
		"id":      providerCommandID,
		"type":    "prompt",
		"message": strings.Join(parts, " "),
	}
}

func (service *providerService) run(ctx context.Context, action, providerID string) (providerExtensionEvent, error) {
	process, err := service.start(ctx)
	if err != nil {
		return providerExtensionEvent{}, err
	}
	if err := process.send(providerPromptCommand(action, providerID, "")); err != nil {
		_ = process.closeAndWait()
		return providerExtensionEvent{}, fmt.Errorf("start provider %s command: %w", action, err)
	}

	scanner := bufio.NewScanner(process.stdout)
	scanner.Split(splitLF)
	scanner.Buffer(make([]byte, 64<<10), int(service.cfg.MaxMessageBytes))
	var result providerExtensionEvent
	for scanner.Scan() {
		event, found, parseErr := parseProviderExtensionEvent(scanner.Bytes())
		if parseErr != nil {
			_ = process.closeAndWait()
			return providerExtensionEvent{}, parseErr
		}
		if !found {
			continue
		}
		result = event
		break
	}
	scanErr := scanner.Err()
	waitErr := process.closeAndWait()
	if ctx.Err() != nil {
		return providerExtensionEvent{}, ctx.Err()
	}
	if scanErr != nil {
		return providerExtensionEvent{}, fmt.Errorf("read provider helper output: %w", scanErr)
	}
	if result.Event == "" {
		if waitErr != nil {
			return providerExtensionEvent{}, fmt.Errorf("provider helper exited: %w: %s", waitErr, process.stderr.String())
		}
		return providerExtensionEvent{}, errors.New("provider helper returned no result")
	}
	if result.Event == "error" {
		return providerExtensionEvent{}, errors.New(result.Message)
	}
	if waitErr != nil {
		return providerExtensionEvent{}, fmt.Errorf("stop provider helper: %w: %s", waitErr, process.stderr.String())
	}
	return result, nil
}

func parseProviderExtensionEvent(line []byte) (providerExtensionEvent, bool, error) {
	var message providerRPCMessage
	if err := json.Unmarshal(line, &message); err != nil || message.Type != "extension_ui_request" || message.Method != "notify" {
		return providerExtensionEvent{}, false, nil
	}
	if !strings.HasPrefix(message.Message, providerEventPrefix) {
		return providerExtensionEvent{}, false, nil
	}
	var event providerExtensionEvent
	if err := json.Unmarshal([]byte(strings.TrimPrefix(message.Message, providerEventPrefix)), &event); err != nil {
		return providerExtensionEvent{}, false, fmt.Errorf("decode provider helper event: %w", err)
	}
	return event, true, nil
}

func (g *Gateway) handleProviders(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	ctx, cancel := context.WithTimeout(request.Context(), max(2*g.cfg.HistoryTimeout, 20*time.Second))
	result, err := g.providers.run(ctx, "list", "")
	cancel()
	if err != nil {
		g.writeProviderError(writer, "list", err)
		return
	}
	writeJSONResponse(writer, http.StatusOK, providersResponse{Providers: result.Providers})
}

func (g *Gateway) handleProvider(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodDelete {
		writer.Header().Set("Allow", http.MethodDelete)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only DELETE is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	tail := strings.TrimPrefix(request.URL.Path, "/api/providers/")
	providerID, suffix, found := strings.Cut(tail, "/")
	if !found || suffix != "credential" || !validProviderID(providerID) {
		writeHTTPError(writer, http.StatusNotFound, "provider_not_found", "built-in provider does not exist")
		return
	}
	g.providers.mutationMu.Lock()
	defer g.providers.mutationMu.Unlock()
	ctx, cancel := context.WithTimeout(request.Context(), max(2*g.cfg.HistoryTimeout, 20*time.Second))
	_, err := g.providers.run(ctx, "logout", providerID)
	cancel()
	if err != nil {
		g.writeProviderError(writer, "logout", err)
		return
	}
	g.providerConfigurationChanged()
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(http.StatusNoContent)
}

func (g *Gateway) writeProviderError(writer http.ResponseWriter, operation string, err error) {
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		writeHTTPError(writer, http.StatusGatewayTimeout, "provider_"+operation+"_timeout", "pi provider operation timed out")
		return
	}
	g.cfg.Logger.Error(operation+" providers", "error", err)
	writeHTTPError(writer, http.StatusBadGateway, "provider_"+operation+"_failed", err.Error())
}

func validProviderID(value string) bool {
	return providerIDPattern.MatchString(value)
}

func (g *Gateway) providerConfigurationChanged() {
	g.capabilities.invalidate()
	g.manager.recycleSettled("pi provider configuration changed")
}

func (g *Gateway) handleProviderAuth(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	providerID := request.URL.Query().Get("provider_id")
	authType := request.URL.Query().Get("auth_type")
	if !validProviderID(providerID) {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_provider", "provider_id is invalid")
		return
	}
	if authType != "api_key" && authType != "oauth" {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_auth_type", "auth_type must be api_key or oauth")
		return
	}

	connection, err := g.upgrader.Upgrade(writer, request, nil)
	if err != nil {
		return
	}
	defer connection.Close()
	providerClientLimit := min(g.cfg.MaxMessageBytes, providerClientMax)
	connection.SetReadLimit(providerClientLimit)

	g.providers.mutationMu.Lock()
	defer g.providers.mutationMu.Unlock()
	ctx, cancel := context.WithCancel(request.Context())
	defer cancel()
	process, err := g.providers.start(ctx)
	if err != nil {
		_ = g.writeProviderAuthEvent(connection, providerWireEvent{Type: "ohpi_provider", Event: "error", Message: err.Error()})
		return
	}
	defer process.closeAndWait()
	if err := g.writeProviderAuthEvent(connection, providerWireEvent{Type: "ohpi_provider", Event: "ready", ProviderID: providerID, AuthType: authType}); err != nil {
		return
	}
	if err := process.send(providerPromptCommand("login", providerID, authType)); err != nil {
		_ = g.writeProviderAuthEvent(connection, providerWireEvent{Type: "ohpi_provider", Event: "error", Message: "could not start provider login"})
		return
	}

	clientDone := make(chan struct{})
	go func() {
		defer close(clientDone)
		g.forwardProviderAuthResponses(connection, process, providerClientLimit)
	}()

	output := make(chan providerAuthScanResult)
	go scanProviderAuthOutput(ctx, process.stdout, g.cfg.MaxMessageBytes, output)
	for {
		select {
		case <-clientDone:
			// A login helper can otherwise remain blocked on an OAuth callback or
			// an input prompt after its App connection has gone away.
			cancel()
			return
		case result, open := <-output:
			if !open {
				return
			}
			if result.err != nil {
				_ = g.writeProviderAuthEvent(connection, providerWireEvent{
					Type: "ohpi_provider", Event: "error", Message: "provider authentication helper stopped",
				})
				return
			}
			if err := g.writeProviderAuthEvent(connection, result.event); err != nil {
				cancel()
				return
			}
			if result.terminal {
				if result.event.Event == "complete" {
					g.providerConfigurationChanged()
				}
				return
			}
		}
	}
}

func (g *Gateway) writeProviderAuthEvent(connection *websocket.Conn, event providerWireEvent) error {
	if err := connection.SetWriteDeadline(time.Now().Add(g.cfg.WriteTimeout)); err != nil {
		return err
	}
	err := connection.WriteJSON(event)
	if clearErr := connection.SetWriteDeadline(time.Time{}); err == nil {
		err = clearErr
	}
	return err
}

func scanProviderAuthOutput(ctx context.Context, stdout io.Reader, maxMessageBytes int64, output chan<- providerAuthScanResult) {
	defer close(output)
	scanner := bufio.NewScanner(stdout)
	scanner.Split(splitLF)
	scanner.Buffer(make([]byte, 64<<10), int(maxMessageBytes))
	for scanner.Scan() {
		event, terminal, found := providerAuthOutput(scanner.Bytes())
		if !found {
			continue
		}
		select {
		case output <- providerAuthScanResult{event: event, terminal: terminal}:
		case <-ctx.Done():
			return
		}
		if terminal {
			return
		}
	}
	if err := scanner.Err(); err != nil && ctx.Err() == nil {
		select {
		case output <- providerAuthScanResult{err: err}:
		case <-ctx.Done():
		}
	}
}

func (g *Gateway) forwardProviderAuthResponses(connection *websocket.Conn, process *providerProcess, maxMessageBytes int64) {
	for {
		messageType, data, err := connection.ReadMessage()
		if err != nil {
			return
		}
		if messageType != websocket.TextMessage || int64(len(data)) > maxMessageBytes {
			return
		}
		var response struct {
			Type      string `json:"type"`
			ID        string `json:"id"`
			Value     string `json:"value,omitempty"`
			Cancelled bool   `json:"cancelled,omitempty"`
		}
		if err := json.Unmarshal(data, &response); err != nil || response.Type != "extension_ui_response" ||
			response.ID == "" || len(response.ID) > 256 {
			continue
		}
		if err := process.send(response); err != nil {
			return
		}
	}
}

func providerAuthOutput(line []byte) (providerWireEvent, bool, bool) {
	var message providerRPCMessage
	if err := json.Unmarshal(line, &message); err != nil {
		return providerWireEvent{}, false, false
	}
	if message.Type == "extension_error" {
		return providerWireEvent{Type: "ohpi_provider", Event: "error", Message: message.Error}, true, true
	}
	if message.Type != "extension_ui_request" {
		return providerWireEvent{}, false, false
	}
	if message.Method == "notify" && strings.HasPrefix(message.Message, providerEventPrefix) {
		var event providerExtensionEvent
		if err := json.Unmarshal([]byte(strings.TrimPrefix(message.Message, providerEventPrefix)), &event); err != nil {
			return providerWireEvent{Type: "ohpi_provider", Event: "error", Message: "invalid provider authentication event"}, true, true
		}
		return providerWireEvent{
			Type:         "ohpi_provider",
			Event:        event.Event,
			Message:      event.Message,
			Action:       event.Action,
			ProviderID:   event.ProviderID,
			ProviderName: event.ProviderName,
			AuthType:     event.AuthType,
			URL:          event.URL,
			Instructions: event.Instructions,
			UserCode:     event.UserCode,
			VerifyURI:    event.VerifyURI,
			Interval:     event.Interval,
			ExpiresIn:    event.ExpiresIn,
			Links:        event.Links,
		}, event.Event == "complete" || event.Event == "error", true
	}
	if (message.Method == "input" || message.Method == "select") && strings.HasPrefix(message.Title, providerPromptPrefix) {
		var prompt providerPromptMetadata
		if err := json.Unmarshal([]byte(strings.TrimPrefix(message.Title, providerPromptPrefix)), &prompt); err != nil {
			return providerWireEvent{Type: "ohpi_provider", Event: "error", Message: "invalid provider authentication prompt"}, true, true
		}
		return providerWireEvent{
			Type:         "ohpi_provider",
			Event:        "prompt",
			ID:           message.ID,
			Kind:         prompt.Kind,
			Message:      prompt.Message,
			Placeholder:  prompt.Placeholder,
			Options:      message.Options,
			Descriptions: prompt.Descriptions,
		}, false, true
	}
	return providerWireEvent{}, false, false
}
