package gateway

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gorilla/websocket"
	"github.com/yearsyan/oh-pi-app/internal/filebrowser"
	"github.com/yearsyan/oh-pi-app/internal/providerauth"
	"github.com/yearsyan/oh-pi-app/internal/scheduledtask"
)

var sessionChangingCommands = map[string]struct{}{
	"new_session":    {},
	"switch_session": {},
	"fork":           {},
	"clone":          {},
}

const maxSessionNameRunes = 200

const gatewayProtocolVersion = 3

const gatewayFeatureSessionProcessStop = "session_process_stop"
const gatewayFeatureWorkspaces = "workspaces_v2"
const gatewayFeatureWorkspaceDelete = "workspace_delete_v1"
const gatewayFeatureWorkspaceResources = "workspace_resources_v1"
const gatewayFeatureRuntimeConfig = "runtime_config_v1"
const gatewayFeatureScheduledTasks = "scheduled_tasks_v1"
const gatewayFeatureScheduledTaskSkills = "scheduled_task_skills_v1"
const gatewayFeatureScheduledSessionManagement = "scheduled_session_management_v1"
const gatewayFeatureScheduledTaskSessions = "scheduled_task_sessions_v1"

// Gateway owns the HTTP handlers and every pi process created through them.
type Gateway struct {
	cfg            Config
	manager        *sessionManager
	workspaces     *workspaceStore
	capabilities   *capabilitiesLoader
	providers      *providerService
	runtimeConfig  *runtimeConfigService
	scheduledTasks *scheduledtask.Engine
	sessionCleaner *scheduledtask.RetentionCleaner
	upgrader       websocket.Upgrader
	handler        http.Handler
}

// New constructs a gateway and initializes its persistent session store.
func New(cfg Config) (*Gateway, error) {
	cfg, err := cfg.withDefaults()
	if err != nil {
		return nil, err
	}
	store, err := newSessionStore(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	workspaces, err := newWorkspaceStore(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	if _, _, err := workspaces.ensureDefault(cfg.WorkDir); err != nil {
		return nil, fmt.Errorf("register default workspace: %w", err)
	}
	providerExtension, err := providerauth.Install(cfg.DataDir)
	if err != nil {
		return nil, fmt.Errorf("install provider authentication extension: %w", err)
	}

	manager := newSessionManager(cfg, store, workspaces)
	taskStore, err := scheduledtask.NewStore(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	if err := markExistingScheduledSessions(store, taskStore, cfg.Logger); err != nil {
		return nil, err
	}
	gateway := &Gateway{
		cfg:           cfg,
		manager:       manager,
		workspaces:    workspaces,
		capabilities:  newCapabilitiesLoader(cfg),
		providers:     newProviderService(cfg, providerExtension),
		runtimeConfig: newRuntimeConfigService(cfg),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
		},
	}
	gateway.scheduledTasks = scheduledtask.NewEngine(
		taskStore,
		scheduledtask.RunnerFunc(gateway.runScheduledTask),
		cfg.Logger,
	)
	gateway.sessionCleaner = scheduledtask.NewRetentionCleaner(
		cfg.ScheduledSessionRetention,
		manager.pruneScheduledSessions,
		cfg.Logger,
	)
	gateway.upgrader.CheckOrigin = gateway.checkOrigin

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", gateway.handleHealth)
	mux.HandleFunc("/fs/list", gateway.handleFsList)
	mux.HandleFunc("/fs/mkdir", gateway.handleFsMkdir)
	mux.HandleFunc("/api/providers", gateway.handleProviders)
	mux.HandleFunc("/api/providers/", gateway.handleProvider)
	mux.HandleFunc("/api/provider-auth", gateway.handleProviderAuth)
	mux.HandleFunc("/api/workspaces", gateway.handleWorkspaces)
	mux.HandleFunc("/api/workspaces/", gateway.handleWorkspace)
	mux.HandleFunc("/api/tasks", gateway.handleScheduledTasks)
	mux.HandleFunc("/api/tasks/", gateway.handleScheduledTask)
	if gateway.runtimeConfig != nil {
		mux.HandleFunc("/api/runtime-config", gateway.handleRuntimeConfig)
		mux.HandleFunc("/api/runtime-restart", gateway.handleRuntimeRestart)
	}
	mux.HandleFunc("/ws", gateway.handleWebSocket)
	filebrowser.New(filebrowser.Config{
		Authenticate: gateway.authenticated,
		WorkDir:      cfg.WorkDir,
		Logger:       cfg.Logger,
	}).Register(mux)
	gateway.handler = mux
	if err := gateway.scheduledTasks.Start(); err != nil {
		return nil, fmt.Errorf("start scheduled task engine: %w", err)
	}
	if err := gateway.sessionCleaner.Start(); err != nil {
		_ = gateway.scheduledTasks.Stop(context.Background())
		return nil, fmt.Errorf("start scheduled session retention: %w", err)
	}
	return gateway, nil
}

// Handler returns the gateway HTTP handler.
func (g *Gateway) Handler() http.Handler {
	return g.handler
}

// Shutdown closes WebSocket clients and gracefully stops all pi processes.
func (g *Gateway) Shutdown(ctx context.Context) error {
	var result error
	if g.sessionCleaner != nil {
		result = g.sessionCleaner.Stop(ctx)
	}
	if g.scheduledTasks != nil {
		result = errors.Join(result, g.scheduledTasks.Stop(ctx))
	}
	return errors.Join(result, g.manager.shutdown(ctx))
}

func (g *Gateway) handleHealth(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if g.runtimeConfig != nil && g.runtimeConfig.restarting.Load() {
		writer.Header().Set("Retry-After", "1")
		writeHTTPError(writer, http.StatusServiceUnavailable, "restarting", "gateway restart is in progress")
		return
	}
	features := []string{
		gatewayFeatureWorkspaces,
		gatewayFeatureSessionProcessStop,
		gatewayFeatureWorkspaceDelete,
		gatewayFeatureWorkspaceResources,
		gatewayFeatureScheduledTasks,
		gatewayFeatureScheduledTaskSkills,
		gatewayFeatureScheduledSessionManagement,
		gatewayFeatureScheduledTaskSessions,
	}
	if g.runtimeConfig != nil {
		features = append(features, gatewayFeatureRuntimeConfig)
	}
	writeJSONResponse(writer, http.StatusOK, struct {
		Status   string   `json:"status"`
		Service  string   `json:"service"`
		Version  string   `json:"version"`
		Protocol int      `json:"protocol"`
		OS       string   `json:"os"`
		Features []string `json:"features"`
	}{
		Status:   "ok",
		Service:  "ohpi-gateway",
		Version:  g.cfg.Version,
		Protocol: gatewayProtocolVersion,
		OS:       runtime.GOOS,
		Features: features,
	})
}

func (g *Gateway) handleWebSocket(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}

	action := request.URL.Query().Get("action")
	if action != "create" && action != "attach" {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_action", `action must be "create" or "attach"`)
		return
	}
	initial, err := parseInitialSessionConfig(request.URL.Query().Get("model"), request.URL.Query().Get("thinking"))
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_initial_config", err.Error())
		return
	}
	if action != "create" && !initial.empty() {
		writeHTTPError(writer, http.StatusBadRequest, "unexpected_initial_config", "model and thinking are only accepted when creating a session")
		return
	}

	sessionID := request.URL.Query().Get("session_id")
	entrySince := request.URL.Query().Get("entry_since")
	replayCursor := request.URL.Query().Get("replay_cursor")
	if replayCursor != "" && replayCursor != "1" {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_replay_cursor", "replay_cursor must be 1 when present")
		return
	}
	replayBaseRaw, hasReplayBase := request.URL.Query()["replay_base"]
	replaySinceRaw, hasReplaySince := request.URL.Query()["replay_since"]
	if hasReplayBase != hasReplaySince || (hasReplayBase && (len(replayBaseRaw) != 1 || len(replaySinceRaw) != 1)) {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_replay_resume", "replay_base and replay_since must be supplied together")
		return
	}
	var replayBase, replaySince uint64
	if hasReplayBase {
		replayBase, err = strconv.ParseUint(replayBaseRaw[0], 10, 64)
		if err == nil {
			replaySince, err = strconv.ParseUint(replaySinceRaw[0], 10, 64)
		}
		if err != nil || replaySince < replayBase {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_replay_resume", "replay resume values are invalid")
			return
		}
	}
	if action == "create" && sessionID != "" {
		writeHTTPError(writer, http.StatusBadRequest, "unexpected_session_id", "create does not accept session_id")
		return
	}
	if action == "create" && entrySince != "" {
		writeHTTPError(writer, http.StatusBadRequest, "unexpected_entry_since", "create does not accept entry_since")
		return
	}
	if action == "create" && hasReplayBase {
		writeHTTPError(writer, http.StatusBadRequest, "unexpected_replay_resume", "create does not accept replay resume values")
		return
	}
	if len(entrySince) > 512 || strings.IndexFunc(entrySince, unicode.IsControl) >= 0 {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_entry_since", "entry_since is invalid")
		return
	}

	workspaceID := ""
	if action == "create" {
		workspaceID = strings.TrimSpace(request.URL.Query().Get("workspace_id"))
		if !validSessionID(workspaceID) {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_workspace_id", "create requires a valid workspace_id")
			return
		}
		if _, err := g.workspaces.load(workspaceID); err != nil {
			if errors.Is(err, errWorkspaceNotFound) {
				writeHTTPError(writer, http.StatusNotFound, "workspace_not_found", "workspace does not exist")
				return
			}
			g.cfg.Logger.Error("load workspace", "workspace_id", workspaceID, "error", err)
			writeHTTPError(writer, http.StatusInternalServerError, "workspace_lookup_failed", "could not inspect workspace")
			return
		}
	}
	if action == "attach" {
		if sessionID == "" {
			writeHTTPError(writer, http.StatusBadRequest, "missing_session_id", "attach requires session_id")
			return
		}
		exists, err := g.manager.exists(sessionID)
		if err != nil {
			g.cfg.Logger.Error("inspect session", "session_id", sessionID, "error", err)
			writeHTTPError(writer, http.StatusInternalServerError, "session_lookup_failed", "could not inspect session")
			return
		}
		if !exists {
			writeHTTPError(writer, http.StatusNotFound, "session_not_found", "session does not exist")
			return
		}
	}

	conn, err := g.upgrader.Upgrade(writer, request, nil)
	if err != nil {
		return
	}

	var session *piSession
	if action == "create" {
		session, err = g.manager.create(workspaceID, initial)
	} else {
		session, err = g.manager.attach(sessionID)
	}
	if err != nil {
		g.cfg.Logger.Error("open session", "action", action, "session_id", sessionID, "error", err)
		sendAndClose(conn, g.cfg.WriteTimeout, gatewayEvent{
			Type:    "ohpi",
			Event:   "error",
			Code:    "session_start_failed",
			Message: "could not start pi session",
		})
		return
	}
	if err := g.manager.touch(session.id); err != nil {
		g.cfg.Logger.Warn("touch opened session", "session_id", session.id, "error", err)
	}

	client := newWSClient(
		conn,
		g.cfg.ClientQueueSize,
		g.cfg.WriteTimeout,
		g.cfg.PongTimeout,
		replayCursor == "1",
	)
	conn.SetReadLimit(g.cfg.MaxMessageBytes)
	_ = conn.SetReadDeadline(time.Now().Add(g.cfg.PongTimeout))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(g.cfg.PongTimeout))
	})

	ready, _ := json.Marshal(gatewayEvent{
		Type:               "ohpi",
		Event:              "ready",
		Action:             action,
		SessionID:          session.id,
		WorkspaceID:        session.workspaceID,
		WorkspaceDirectory: session.workDir,
	})
	go client.writePump()
	readerDone := make(chan struct{})
	go func() {
		g.readClient(client, session)
		client.abort()
		close(readerDone)
	}()
	defer func() {
		session.removeClient(client)
		client.abort()
	}()

	if action == "attach" {
		resume := replayResume{Base: replayBase, Since: replaySince, Present: hasReplayBase}
		if err := session.syncAttach(client, ready, entrySince, resume); err != nil {
			g.cfg.Logger.Warn("sync attached session", "session_id", session.id, "error", err)
			client.close(websocket.CloseInternalServerErr, "could not synchronize session")
		}
	} else if !session.addLiveClient(client, ready) {
		client.close(websocket.CloseInternalServerErr, "pi session is not running")
	}
	<-readerDone
}

type initialSessionConfig struct {
	model      string
	thinking   string
	skillPaths []string
	noSkills   bool
}

func (config initialSessionConfig) empty() bool {
	return config.model == "" && config.thinking == "" && len(config.skillPaths) == 0 && !config.noSkills
}

func (config initialSessionConfig) args(inheritedSkills []string, inheritedNoSkills bool) []string {
	args := make([]string, 0, 5+len(config.skillPaths)*2)
	if config.model != "" {
		args = append(args, "--model", config.model)
	}
	if config.thinking != "" {
		args = append(args, "--thinking", config.thinking)
	}
	if config.noSkills && !inheritedNoSkills {
		args = append(args, "--no-skills")
	}
	inherited := make(map[string]struct{}, len(inheritedSkills))
	for _, path := range inheritedSkills {
		inherited[path] = struct{}{}
	}
	for _, path := range config.skillPaths {
		if _, exists := inherited[path]; exists {
			continue
		}
		args = append(args, "--skill", path)
	}
	return args
}

func parseInitialSessionConfig(rawModel, rawThinking string) (initialSessionConfig, error) {
	config := initialSessionConfig{
		model:    strings.TrimSpace(rawModel),
		thinking: strings.TrimSpace(rawThinking),
	}
	if config.model != "" {
		provider, modelID, ok := strings.Cut(config.model, "/")
		if !ok || provider == "" || modelID == "" {
			return initialSessionConfig{}, errors.New(`model must use the "provider/model-id" form`)
		}
		if !utf8.ValidString(config.model) || utf8.RuneCountInString(config.model) > 300 ||
			strings.IndexFunc(config.model, func(value rune) bool {
				return unicode.IsControl(value) || unicode.IsSpace(value)
			}) >= 0 {
			return initialSessionConfig{}, errors.New("model is invalid")
		}
	}
	if config.thinking != "" {
		valid := false
		for _, level := range orderedThinkingLevels {
			if config.thinking == level {
				valid = true
				break
			}
		}
		if !valid {
			return initialSessionConfig{}, fmt.Errorf("thinking must be one of %s", strings.Join(orderedThinkingLevels, ", "))
		}
	}
	return config, nil
}

func (g *Gateway) readClient(client *wsClient, session *piSession) {
	for {
		messageType, message, err := client.conn.ReadMessage()
		if err != nil {
			return
		}
		if messageType != websocket.TextMessage {
			client.close(websocket.CloseUnsupportedData, "RPC commands must be WebSocket text messages")
			return
		}

		command, commandType, err := normalizeCommand(message)
		if err != nil {
			gatewayError(client, "invalid_rpc_command", err.Error())
			continue
		}
		if _, forbidden := sessionChangingCommands[commandType]; forbidden {
			gatewayError(
				client,
				"session_command_forbidden",
				fmt.Sprintf("%q is managed by ohpi; use a create or attach connection instead", commandType),
			)
			continue
		}
		if commandType == "extension_ui_response" {
			requestID, parseErr := extensionUIResponseID(command)
			if parseErr != nil {
				gatewayError(client, "invalid_ui_response", parseErr.Error())
				continue
			}
			resolveErr := session.resolveUIRequest(client.done, requestID, command)
			switch {
			case resolveErr == nil:
				continue
			case errors.Is(resolveErr, errUIRequestAlreadyResolved):
				gatewayError(client, "ui_request_already_resolved", "UI request was already answered")
				continue
			case errors.Is(resolveErr, errUIRequestNotPending):
				gatewayError(client, "ui_request_not_pending", "UI request is no longer pending")
				continue
			default:
				client.close(websocket.CloseInternalServerErr, "pi session is not running")
				return
			}
		}

		var sessionName *string
		if commandType == "set_session_name" {
			command, sessionName, err = normalizeSessionNameCommand(command)
			if err != nil {
				gatewayError(client, "invalid_session_name", err.Error())
				continue
			}
			if _, err := g.manager.rename(session.id, *sessionName, false); err != nil {
				g.cfg.Logger.Error("persist session name", "session_id", session.id, "error", err)
				gatewayError(client, "session_metadata_failed", "could not persist session name")
				continue
			}
		}
		if err := session.submit(client.done, command); err != nil {
			client.close(websocket.CloseInternalServerErr, "pi session is not running")
			return
		}
	}
}

func (g *Gateway) authenticated(request *http.Request) bool {
	provided := ""
	if authorization := strings.TrimSpace(request.Header.Get("Authorization")); authorization != "" {
		scheme, token, ok := strings.Cut(authorization, " ")
		if !ok || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(token) == "" {
			return false
		}
		provided = strings.TrimSpace(token)
	} else {
		values, ok := request.URL.Query()["token"]
		if !ok || len(values) != 1 {
			return false
		}
		provided = values[0]
	}
	providedBytes := []byte(provided)
	expected := []byte(g.cfg.Token)
	return subtle.ConstantTimeCompare(providedBytes, expected) == 1
}

func (g *Gateway) checkOrigin(request *http.Request) bool {
	origin := request.Header.Get("Origin")
	if origin == "" {
		return true
	}
	for _, allowed := range g.cfg.AllowedOrigins {
		if allowed == "*" || strings.EqualFold(strings.TrimRight(allowed, "/"), strings.TrimRight(origin, "/")) {
			return true
		}
	}
	parsed, err := url.Parse(origin)
	return err == nil && strings.EqualFold(parsed.Host, request.Host)
}

func normalizeCommand(message []byte) ([]byte, string, error) {
	var compacted bytes.Buffer
	if err := json.Compact(&compacted, message); err != nil {
		return nil, "", fmt.Errorf("command must be one valid JSON object: %w", err)
	}
	command := []byte(compacted.String())
	if len(command) == 0 || command[0] != '{' {
		return nil, "", errors.New("command must be a JSON object")
	}
	var envelope struct {
		Type string          `json:"type"`
		ID   json.RawMessage `json:"id,omitempty"`
	}
	if err := json.Unmarshal(command, &envelope); err != nil {
		return nil, "", fmt.Errorf("decode command: %w", err)
	}
	if envelope.Type == "" {
		return nil, "", errors.New(`command requires a non-empty "type" field`)
	}
	if len(envelope.ID) > 0 {
		var id string
		if json.Unmarshal(envelope.ID, &id) == nil && strings.HasPrefix(id, internalRPCIDPrefix) {
			return nil, "", errors.New("command id uses a reserved ohpi prefix")
		}
	}
	return command, envelope.Type, nil
}

func normalizeSessionNameCommand(command []byte) ([]byte, *string, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(command, &fields); err != nil {
		return nil, nil, fmt.Errorf("decode set_session_name: %w", err)
	}
	rawName, ok := fields["name"]
	if !ok {
		return nil, nil, errors.New("set_session_name requires a name")
	}
	var raw string
	if err := json.Unmarshal(rawName, &raw); err != nil {
		return nil, nil, errors.New("session name must be a string")
	}
	name, err := normalizeSessionName(raw)
	if err != nil {
		return nil, nil, err
	}
	fields["name"], _ = json.Marshal(name)
	normalized, err := json.Marshal(fields)
	if err != nil {
		return nil, nil, fmt.Errorf("encode set_session_name: %w", err)
	}
	return normalized, &name, nil
}

func normalizeSessionName(raw string) (string, error) {
	name := strings.TrimSpace(raw)
	if name == "" {
		return "", errors.New("name must not be empty")
	}
	if utf8.RuneCountInString(name) > maxSessionNameRunes {
		return "", fmt.Errorf("name must not exceed %d characters", maxSessionNameRunes)
	}
	return name, nil
}

func ensureJSONEOF(decoder *json.Decoder) error {
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("unexpected trailing JSON value")
		}
		return err
	}
	return nil
}

type gatewayEvent struct {
	Type               string          `json:"type"`
	Event              string          `json:"event"`
	Action             string          `json:"action,omitempty"`
	SessionID          string          `json:"session_id,omitempty"`
	WorkspaceID        string          `json:"workspace_id,omitempty"`
	WorkspaceDirectory string          `json:"workspace_directory,omitempty"`
	Code               string          `json:"code,omitempty"`
	Message            string          `json:"message,omitempty"`
	RequestID          string          `json:"request_id,omitempty"`
	FromSeq            uint64          `json:"from_seq,omitempty"`
	ThroughSeq         uint64          `json:"through_seq,omitempty"`
	Seq                uint64          `json:"seq,omitempty"`
	Payload            json.RawMessage `json:"payload,omitempty"`
	Reset              *bool           `json:"reset,omitempty"`
	EntryID            string          `json:"entry_id,omitempty"`
	TotalBytes         uint64          `json:"total_bytes,omitempty"`
}

func gatewayError(client *wsClient, code, message string) {
	event, _ := json.Marshal(gatewayEvent{
		Type:    "ohpi",
		Event:   "error",
		Code:    code,
		Message: message,
	})
	client.enqueue(event)
}

func sendAndClose(conn *websocket.Conn, timeout time.Duration, event gatewayEvent) {
	message, _ := json.Marshal(event)
	_ = conn.SetWriteDeadline(time.Now().Add(timeout))
	_ = conn.WriteMessage(websocket.TextMessage, message)
	_ = conn.WriteControl(
		websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseInternalServerErr, event.Message),
		time.Now().Add(timeout),
	)
	_ = conn.Close()
}

func writeHTTPError(writer http.ResponseWriter, status int, code, message string) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(map[string]string{
		"error":   code,
		"message": message,
	})
}

func writeJSONResponse(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}
