package gateway

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

var sessionChangingCommands = map[string]struct{}{
	"new_session":    {},
	"switch_session": {},
	"fork":           {},
	"clone":          {},
}

// Gateway owns the HTTP handlers and every pi process created through them.
type Gateway struct {
	cfg      Config
	manager  *sessionManager
	upgrader websocket.Upgrader
	handler  http.Handler
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

	gateway := &Gateway{
		cfg:     cfg,
		manager: newSessionManager(cfg, store),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
		},
	}
	gateway.upgrader.CheckOrigin = gateway.checkOrigin

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", gateway.handleHealth)
	mux.HandleFunc("/ws", gateway.handleWebSocket)
	gateway.handler = mux
	return gateway, nil
}

// Handler returns the gateway HTTP handler.
func (g *Gateway) Handler() http.Handler {
	return g.handler
}

// Shutdown closes WebSocket clients and gracefully stops all pi processes.
func (g *Gateway) Shutdown(ctx context.Context) error {
	return g.manager.shutdown(ctx)
}

func (g *Gateway) handleHealth(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	_, _ = writer.Write([]byte("{\"status\":\"ok\"}\n"))
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

	sessionID := request.URL.Query().Get("session_id")
	if action == "create" && sessionID != "" {
		writeHTTPError(writer, http.StatusBadRequest, "unexpected_session_id", "create does not accept session_id")
		return
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
		session, err = g.manager.create()
	} else {
		session, err = g.manager.attach(sessionID)
	}
	if err != nil {
		g.cfg.Logger.Error("open session", "action", action, "session_id", sessionID, "error", err)
		sendAndClose(conn, g.cfg.WriteTimeout, gatewayEvent{
			Type:    "pi2ws",
			Event:   "error",
			Code:    "session_start_failed",
			Message: "could not start pi session",
		})
		return
	}

	client := newWSClient(
		conn,
		g.cfg.ClientQueueSize,
		g.cfg.WriteTimeout,
		g.cfg.PongTimeout,
	)
	conn.SetReadLimit(g.cfg.MaxMessageBytes)
	_ = conn.SetReadDeadline(time.Now().Add(g.cfg.PongTimeout))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(g.cfg.PongTimeout))
	})

	go client.writePump()
	ready, _ := json.Marshal(gatewayEvent{
		Type:      "pi2ws",
		Event:     "ready",
		Action:    action,
		SessionID: session.id,
	})
	if !client.enqueue(ready) || !session.addClient(client) {
		client.close(websocket.CloseInternalServerErr, "pi session is not running")
		return
	}
	defer func() {
		session.removeClient(client)
		client.abort()
	}()

	g.readClient(client, session)
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
				fmt.Sprintf("%q is managed by pi2ws; use a create or attach connection instead", commandType),
			)
			continue
		}
		if err := session.submit(client.done, command); err != nil {
			client.close(websocket.CloseInternalServerErr, "pi session is not running")
			return
		}
	}
}

func (g *Gateway) authenticated(request *http.Request) bool {
	values, ok := request.URL.Query()["token"]
	if !ok || len(values) != 1 {
		return false
	}
	provided := []byte(values[0])
	expected := []byte(g.cfg.Token)
	return subtle.ConstantTimeCompare(provided, expected) == 1
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
		Type string `json:"type"`
	}
	if err := json.Unmarshal(command, &envelope); err != nil {
		return nil, "", fmt.Errorf("decode command: %w", err)
	}
	if envelope.Type == "" {
		return nil, "", errors.New(`command requires a non-empty "type" field`)
	}
	return command, envelope.Type, nil
}

type gatewayEvent struct {
	Type      string `json:"type"`
	Event     string `json:"event"`
	Action    string `json:"action,omitempty"`
	SessionID string `json:"session_id,omitempty"`
	Code      string `json:"code,omitempty"`
	Message   string `json:"message,omitempty"`
}

func gatewayError(client *wsClient, code, message string) {
	event, _ := json.Marshal(gatewayEvent{
		Type:    "pi2ws",
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
