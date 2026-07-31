package gateway

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const testToken = "test-token-that-is-not-secret"

func TestPiHelperProcess(t *testing.T) {
	sessionID, ok := argumentValue(os.Args, "--session-id")
	if !ok {
		return
	}
	sessionDir, ok := argumentValue(os.Args, "--session-dir")
	if !ok {
		os.Exit(3)
	}

	startLog, err := os.OpenFile(
		filepath.Join(sessionDir, "fake-starts.log"),
		os.O_WRONLY|os.O_CREATE|os.O_APPEND,
		0o600,
	)
	if err != nil {
		os.Exit(4)
	}
	_, _ = fmt.Fprintln(startLog, os.Getpid())
	_ = startLog.Close()

	scanner := bufio.NewScanner(os.Stdin)
	scanner.Split(splitLF)
	scanner.Buffer(make([]byte, 64<<10), 1<<20)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var command map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &command); err != nil {
			os.Exit(5)
		}
		commandType, _ := command["type"].(string)
		if commandType == "fake_exit" {
			os.Exit(0)
		}
		response := map[string]any{
			"type":       "response",
			"command":    commandType,
			"success":    true,
			"session_id": sessionID,
			"fake_pid":   os.Getpid(),
		}
		if id, exists := command["id"]; exists {
			response["id"] = id
		}
		if message, exists := command["message"]; exists {
			response["message"] = message
		}
		if err := encoder.Encode(response); err != nil {
			os.Exit(6)
		}
	}
}

func TestWebSocketAuthAndMissingSession(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())

	_, response, err := websocket.DefaultDialer.Dial(webSocketURL(server, url.Values{
		"action": {"create"},
	}), nil)
	if err == nil {
		t.Fatal("connection without token unexpectedly succeeded")
	}
	if response == nil || response.StatusCode != 401 {
		t.Fatalf("unauthorized status = %v, want 401", responseStatus(response))
	}
	_ = response.Body.Close()

	_, response, err = websocket.DefaultDialer.Dial(webSocketURL(server, url.Values{
		"action":     {"attach"},
		"session_id": {"11111111-1111-4111-8111-111111111111"},
		"token":      {testToken},
	}), nil)
	if err == nil {
		t.Fatal("attach to missing session unexpectedly succeeded")
	}
	if response == nil || response.StatusCode != 404 {
		t.Fatalf("missing session status = %v, want 404", responseStatus(response))
	}
	_ = response.Body.Close()
}

func TestOnePiProcessSharedByMultipleWebSockets(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())

	first := dialWebSocket(t, server, url.Values{
		"action": {"create"},
		"token":  {testToken},
	})
	defer first.Close()
	readyFirst := readEvent(t, first)
	if readyFirst.string("type") != "pi2ws" || readyFirst.string("event") != "ready" {
		t.Fatalf("first message = %#v, want pi2ws ready", readyFirst)
	}
	sessionID := readyFirst.string("session_id")
	if !validSessionID(sessionID) {
		t.Fatalf("invalid generated session id %q", sessionID)
	}

	second := dialWebSocket(t, server, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer second.Close()
	readySecond := readEvent(t, second)
	if readySecond.string("session_id") != sessionID || readySecond.string("action") != "attach" {
		t.Fatalf("second ready = %#v", readySecond)
	}

	writeJSON(t, first, map[string]any{
		"id":      "client-one-1",
		"type":    "prompt",
		"message": "from first",
	})
	fromFirstOnFirst := readEvent(t, first)
	fromFirstOnSecond := readEvent(t, second)
	assertSameFakeResponse(t, fromFirstOnFirst, fromFirstOnSecond, "client-one-1", "from first")

	writeJSON(t, second, map[string]any{
		"id":      "client-two-1",
		"type":    "prompt",
		"message": "from second",
	})
	fromSecondOnFirst := readEvent(t, first)
	fromSecondOnSecond := readEvent(t, second)
	assertSameFakeResponse(t, fromSecondOnFirst, fromSecondOnSecond, "client-two-1", "from second")

	if fromFirstOnFirst.number("fake_pid") != fromSecondOnFirst.number("fake_pid") {
		t.Fatalf(
			"same session used different child processes: %v and %v",
			fromFirstOnFirst.number("fake_pid"),
			fromSecondOnFirst.number("fake_pid"),
		)
	}
}

func TestAttachRestartsHistoricalSessionAfterGatewayRestart(t *testing.T) {
	dataDir := t.TempDir()
	firstGateway, firstServer := startTestGateway(t, dataDir)

	first := dialWebSocket(t, firstServer, url.Values{
		"action": {"create"},
		"token":  {testToken},
	})
	ready := readEvent(t, first)
	sessionID := ready.string("session_id")
	writeJSON(t, first, map[string]any{"id": "before", "type": "get_state"})
	before := readEvent(t, first)
	_ = first.Close()

	shutdownGateway(t, firstGateway)
	firstServer.Close()

	secondGateway, secondServer := startTestGateway(t, dataDir)
	defer shutdownGateway(t, secondGateway)
	second := dialWebSocket(t, secondServer, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer second.Close()
	attached := readEvent(t, second)
	if attached.string("session_id") != sessionID {
		t.Fatalf("attached session = %q, want %q", attached.string("session_id"), sessionID)
	}
	writeJSON(t, second, map[string]any{"id": "after", "type": "get_state"})
	after := readEvent(t, second)
	if after.string("session_id") != sessionID {
		t.Fatalf("restarted pi session = %q, want %q", after.string("session_id"), sessionID)
	}
	if before.number("fake_pid") == after.number("fake_pid") {
		t.Fatalf("historical attach did not start a new child process: pid %v", before.number("fake_pid"))
	}

	starts, err := os.ReadFile(filepath.Join(dataDir, "sessions", sessionID, "fake-starts.log"))
	if err != nil {
		t.Fatalf("read fake process starts: %v", err)
	}
	if got := len(strings.Fields(string(starts))); got != 2 {
		t.Fatalf("fake process start count = %d, want 2", got)
	}
}

func TestInvalidAndSessionChangingCommandsAreRejected(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	conn := dialWebSocket(t, server, url.Values{
		"action": {"create"},
		"token":  {testToken},
	})
	defer conn.Close()
	_ = readEvent(t, conn)

	if err := conn.WriteMessage(websocket.TextMessage, []byte("not-json")); err != nil {
		t.Fatalf("write invalid JSON: %v", err)
	}
	invalid := readEvent(t, conn)
	if invalid.string("event") != "error" || invalid.string("code") != "invalid_rpc_command" {
		t.Fatalf("invalid JSON response = %#v", invalid)
	}

	writeJSON(t, conn, map[string]any{"type": "switch_session", "sessionPath": "/tmp/other"})
	forbidden := readEvent(t, conn)
	if forbidden.string("event") != "error" || forbidden.string("code") != "session_command_forbidden" {
		t.Fatalf("forbidden command response = %#v", forbidden)
	}

	prettyCommand := "{\n  \"id\": \"still-running\",\n  \"type\": \"get_state\"\n}"
	if err := conn.WriteMessage(websocket.TextMessage, []byte(prettyCommand)); err != nil {
		t.Fatalf("write pretty JSON: %v", err)
	}
	response := readEvent(t, conn)
	if response.string("id") != "still-running" || !response.boolean("success") {
		t.Fatalf("valid command after errors = %#v", response)
	}
}

func TestPiExitClosesAttachedWebSockets(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	conn := dialWebSocket(t, server, url.Values{
		"action": {"create"},
		"token":  {testToken},
	})
	_ = readEvent(t, conn)

	writeJSON(t, conn, map[string]any{"type": "fake_exit"})
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, _, err := conn.ReadMessage()
	if err == nil {
		t.Fatal("WebSocket remained open after pi exited")
	}
	if !websocket.IsCloseError(err, websocket.CloseInternalServerErr) {
		t.Fatalf("close error = %v, want close code %d", err, websocket.CloseInternalServerErr)
	}
}

func TestNormalizeCommandUsesOneStrictLFRecord(t *testing.T) {
	input := []byte("{\n \"type\": \"prompt\", \"message\": \"left\u2028right\" \n}")
	command, commandType, err := normalizeCommand(input)
	if err != nil {
		t.Fatalf("normalize command: %v", err)
	}
	if commandType != "prompt" {
		t.Fatalf("command type = %q, want prompt", commandType)
	}
	if strings.ContainsRune(string(command), '\n') {
		t.Fatalf("normalized command contains LF: %q", command)
	}
	if !strings.ContainsRune(string(command), '\u2028') {
		t.Fatalf("normalized command lost U+2028: %q", command)
	}
}

func TestChildEnvironmentDoesNotExposeGatewayToken(t *testing.T) {
	t.Setenv("PI2WS_TOKEN", "must-not-reach-pi")
	t.Setenv("PI2WS_TEST_VISIBLE", "visible")

	environment := childEnvironment()
	foundVisible := false
	for _, entry := range environment {
		if strings.HasPrefix(entry, "PI2WS_TOKEN=") {
			t.Fatalf("child environment contains gateway token: %q", entry)
		}
		if entry == "PI2WS_TEST_VISIBLE=visible" {
			foundVisible = true
		}
	}
	if !foundVisible {
		t.Fatal("child environment unexpectedly removed a non-secret variable")
	}
}

func startTestGateway(t *testing.T, dataDir string) (*Gateway, *httptest.Server) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	app, err := New(Config{
		Token:           testToken,
		DataDir:         dataDir,
		WorkDir:         t.TempDir(),
		PiCommand:       os.Args[0],
		PiArgs:          []string{"-test.run=TestPiHelperProcess", "--"},
		MaxMessageBytes: 1 << 20,
		WriteTimeout:    250 * time.Millisecond,
		PongTimeout:     2 * time.Second,
		Logger:          logger,
	})
	if err != nil {
		t.Fatalf("create gateway: %v", err)
	}
	server := httptest.NewServer(app.Handler())
	t.Cleanup(func() {
		shutdownGateway(t, app)
		server.Close()
	})
	return app, server
}

func shutdownGateway(t *testing.T, app *Gateway) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := app.Shutdown(ctx); err != nil {
		t.Errorf("shutdown gateway: %v", err)
	}
}

func dialWebSocket(t *testing.T, server *httptest.Server, query url.Values) *websocket.Conn {
	t.Helper()
	conn, response, err := websocket.DefaultDialer.Dial(webSocketURL(server, query), nil)
	if err != nil {
		if response != nil {
			_ = response.Body.Close()
		}
		t.Fatalf("dial WebSocket: %v", err)
	}
	return conn
}

func webSocketURL(server *httptest.Server, query url.Values) string {
	return "ws" + strings.TrimPrefix(server.URL, "http") + "/ws?" + query.Encode()
}

type event map[string]any

func readEvent(t *testing.T, conn *websocket.Conn) event {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	messageType, message, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read WebSocket event: %v", err)
	}
	if messageType != websocket.TextMessage {
		t.Fatalf("message type = %d, want text", messageType)
	}
	var value event
	if err := json.Unmarshal(message, &value); err != nil {
		t.Fatalf("decode WebSocket event %q: %v", message, err)
	}
	return value
}

func writeJSON(t *testing.T, conn *websocket.Conn, value any) {
	t.Helper()
	if err := conn.WriteJSON(value); err != nil {
		t.Fatalf("write WebSocket JSON: %v", err)
	}
}

func assertSameFakeResponse(t *testing.T, first, second event, id, message string) {
	t.Helper()
	if first.string("id") != id || second.string("id") != id {
		t.Fatalf("response ids = %q and %q, want %q", first.string("id"), second.string("id"), id)
	}
	if first.string("message") != message || second.string("message") != message {
		t.Fatalf(
			"response messages = %q and %q, want %q",
			first.string("message"),
			second.string("message"),
			message,
		)
	}
	if first.number("fake_pid") != second.number("fake_pid") {
		t.Fatalf("broadcast came from different pids: %#v and %#v", first, second)
	}
}

func (e event) string(key string) string {
	value, _ := e[key].(string)
	return value
}

func (e event) number(key string) float64 {
	value, _ := e[key].(float64)
	return value
}

func (e event) boolean(key string) bool {
	value, _ := e[key].(bool)
	return value
}

func responseStatus(response *http.Response) int {
	if response == nil {
		return 0
	}
	return response.StatusCode
}

func argumentValue(arguments []string, name string) (string, bool) {
	for index := 0; index+1 < len(arguments); index++ {
		if arguments[index] == name {
			return arguments[index+1], true
		}
	}
	return "", false
}
