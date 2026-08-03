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

	cwd, err := os.Getwd()
	if err != nil {
		os.Exit(7)
	}
	cwdLog, err := os.OpenFile(
		filepath.Join(sessionDir, "fake-cwd.log"),
		os.O_WRONLY|os.O_CREATE|os.O_APPEND,
		0o600,
	)
	if err != nil {
		os.Exit(8)
	}
	_, _ = fmt.Fprintln(cwdLog, cwd)
	_ = cwdLog.Close()

	scanner := bufio.NewScanner(os.Stdin)
	scanner.Split(splitLF)
	scanner.Buffer(make([]byte, 64<<10), 1<<20)
	encoder := json.NewEncoder(os.Stdout)
	var entries []any
	var historyDelay time.Duration
	for scanner.Scan() {
		var command map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &command); err != nil {
			os.Exit(5)
		}
		commandType, _ := command["type"].(string)
		if commandType == "fake_exit" {
			os.Exit(0)
		}
		if commandType == "fake_set_entries" {
			entries, _ = command["entries"].([]any)
		}
		if commandType == "fake_set_history_delay" {
			milliseconds, _ := command["milliseconds"].(float64)
			historyDelay = time.Duration(milliseconds) * time.Millisecond
		}
		if commandType == "fake_emit" {
			events, _ := command["events"].([]any)
			for _, event := range events {
				if err := encoder.Encode(event); err != nil {
					os.Exit(6)
				}
			}
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
		if commandType == "get_entries" {
			if historyDelay > 0 {
				time.Sleep(historyDelay)
			}
			var leafID any
			if len(entries) > 0 {
				entry, _ := entries[len(entries)-1].(map[string]any)
				leafID = entry["id"]
			}
			response["data"] = map[string]any{"entries": entries, "leafId": leafID}
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
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
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
	readySecond, _, _ := readAttachHistory(t, second)
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
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
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
	attached, _, _ := readAttachHistory(t, second)
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

func TestCreateWithCustomWorkDir(t *testing.T) {
	dataDir := t.TempDir()
	app, server := startTestGateway(t, dataDir)
	// The gateway resolves symlinks in work_dir, so compare against the
	// canonical path (macOS temp dirs live under /private/var).
	customWorkDir, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatalf("resolve custom work dir: %v", err)
	}

	_, response, err := websocket.DefaultDialer.Dial(webSocketURL(server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {"relative/path"},
	}), nil)
	if err == nil {
		t.Fatal("create with a relative work_dir unexpectedly succeeded")
	}
	if response == nil || response.StatusCode != 400 {
		t.Fatalf("relative work_dir status = %v, want 400", responseStatus(response))
	}
	_ = response.Body.Close()

	_, response, err = websocket.DefaultDialer.Dial(webSocketURL(server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {filepath.Join(customWorkDir, "missing")},
	}), nil)
	if err == nil {
		t.Fatal("create with a missing work_dir unexpectedly succeeded")
	}
	if response == nil || response.StatusCode != 400 {
		t.Fatalf("missing work_dir status = %v, want 400", responseStatus(response))
	}
	_ = response.Body.Close()

	conn := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {customWorkDir},
	})
	ready := readEvent(t, conn)
	if ready.string("work_dir") != customWorkDir {
		t.Fatalf("ready work_dir = %q, want %q", ready.string("work_dir"), customWorkDir)
	}
	sessionID := ready.string("session_id")
	// The ready event fires as soon as the child is spawned; round-trip a
	// command so the fake pi has written its cwd log before we read it.
	writeJSON(t, conn, map[string]any{"id": "cwd-sync", "type": "get_state"})
	_ = readEvent(t, conn)
	_ = conn.Close()

	cwdData, err := os.ReadFile(filepath.Join(dataDir, "sessions", sessionID, "fake-cwd.log"))
	if err != nil {
		t.Fatalf("read fake process cwd: %v", err)
	}
	if got := strings.Fields(string(cwdData)); len(got) != 1 || got[0] != customWorkDir {
		t.Fatalf("fake process cwd = %q, want %q", strings.TrimSpace(string(cwdData)), customWorkDir)
	}

	// The workspace is persisted: after a gateway restart, attach must restart
	// the child in the session's own work_dir, not the gateway default.
	shutdownGateway(t, app)
	server.Close()

	secondGateway, secondServer := startTestGateway(t, dataDir)
	defer shutdownGateway(t, secondGateway)
	attached := dialWebSocket(t, secondServer, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer attached.Close()
	readyAttached, _, _ := readAttachHistory(t, attached)
	if readyAttached.string("work_dir") != customWorkDir {
		t.Fatalf("attached ready work_dir = %q, want %q", readyAttached.string("work_dir"), customWorkDir)
	}

	cwdData, err = os.ReadFile(filepath.Join(dataDir, "sessions", sessionID, "fake-cwd.log"))
	if err != nil {
		t.Fatalf("read fake process cwd after restart: %v", err)
	}
	for index, line := range strings.Fields(string(cwdData)) {
		if line != customWorkDir {
			t.Fatalf("fake process cwd entry %d = %q, want %q", index, line, customWorkDir)
		}
	}
}

func TestCreateWithoutWorkDirIsRejected(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	_, response, err := websocket.DefaultDialer.Dial(webSocketURL(server, url.Values{
		"action": {"create"},
		"token":  {testToken},
	}), nil)
	if err == nil {
		t.Fatal("create without work_dir unexpectedly succeeded")
	}
	if response == nil || response.StatusCode != 400 {
		t.Fatalf("missing work_dir status = %v, want 400", responseStatus(response))
	}
	_ = response.Body.Close()
}

func TestFsListEndpoint(t *testing.T) {
	app, server := startTestGateway(t, t.TempDir())
	root := t.TempDir()
	for _, dir := range []string{"beta", "Alpha"} {
		if err := os.Mkdir(filepath.Join(root, dir), 0o755); err != nil {
			t.Fatalf("create fixture dir: %v", err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "notes.txt"), []byte("x"), 0o644); err != nil {
		t.Fatalf("create fixture file: %v", err)
	}

	httpGet := func(rawURL string) (int, map[string]any) {
		t.Helper()
		response, err := http.Get(rawURL)
		if err != nil {
			t.Fatalf("GET %s: %v", rawURL, err)
		}
		defer response.Body.Close()
		var body map[string]any
		_ = json.NewDecoder(response.Body).Decode(&body)
		return response.StatusCode, body
	}

	base := strings.TrimPrefix(server.URL, "http") + "/fs/list"
	status, _ := httpGet("http" + base + "?path=" + url.QueryEscape(root))
	if status != 401 {
		t.Fatalf("fs/list without token status = %d, want 401", status)
	}
	status, _ = httpGet("http" + base + "?token=" + testToken + "&path=" + url.QueryEscape("relative/dir"))
	if status != 400 {
		t.Fatalf("fs/list relative path status = %d, want 400", status)
	}
	status, _ = httpGet("http" + base + "?token=" + testToken + "&path=" + url.QueryEscape(filepath.Join(root, "missing")))
	if status != 404 {
		t.Fatalf("fs/list missing path status = %d, want 404", status)
	}

	status, body := httpGet("http" + base + "?token=" + testToken + "&path=" + url.QueryEscape(root))
	if status != 200 {
		t.Fatalf("fs/list status = %d, want 200 (body %v)", status, body)
	}
	dirs, _ := body["dirs"].([]any)
	if len(dirs) != 2 {
		t.Fatalf("fs/list returned %d dirs, want 2 (files must be excluded): %v", len(dirs), body)
	}
	first, _ := dirs[0].(map[string]any)
	second, _ := dirs[1].(map[string]any)
	if first["name"] != "Alpha" || second["name"] != "beta" {
		t.Fatalf("fs/list dir order = %v, %v; want Alpha, beta", first["name"], second["name"])
	}
	resolved, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatalf("resolve fixture root: %v", err)
	}
	if body["path"] != resolved || body["parent"] != filepath.Dir(resolved) {
		t.Fatalf("fs/list path/parent = %v/%v, want %v/%v", body["path"], body["parent"], resolved, filepath.Dir(resolved))
	}

	// No path starts browsing at the gateway working directory.
	status, body = httpGet("http" + base + "?token=" + testToken)
	if status != 200 || body["path"] != app.cfg.WorkDir {
		t.Fatalf("fs/list default = (%d, %v), want (200, %q)", status, body["path"], app.cfg.WorkDir)
	}
}

func TestInvalidAndSessionChangingCommandsAreRejected(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	conn := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
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
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
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

func TestAttachReceivesHistoryThenActiveReplayThenLiveOutput(t *testing.T) {
	app, server := startTestGateway(t, t.TempDir())
	first := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer first.Close()
	ready := readEvent(t, first)
	sessionID := ready.string("session_id")

	activeEvents := []any{
		map[string]any{"type": "agent_start"},
		map[string]any{"type": "message_start", "message": map[string]any{
			"role": "user", "content": []any{map[string]any{"type": "text", "text": "hello"}}, "timestamp": 1,
		}},
		map[string]any{"type": "message_end", "message": map[string]any{
			"role": "user", "content": []any{map[string]any{"type": "text", "text": "hello"}}, "timestamp": 1,
		}},
		map[string]any{"type": "message_start", "message": map[string]any{
			"role": "assistant", "content": []any{}, "timestamp": 2,
		}},
		map[string]any{
			"type": "message_update",
			"assistantMessageEvent": map[string]any{
				"type": "text_delta", "contentIndex": 0, "delta": "partial",
			},
		},
		map[string]any{
			"type": "extension_ui_request", "id": "dialog-1", "method": "confirm",
			"title": "Continue?", "message": "The pi process is waiting for this response.",
		},
	}
	writeJSON(t, first, map[string]any{"id": "emit-active", "type": "fake_emit", "events": activeEvents})
	for range activeEvents {
		_ = readEvent(t, first)
	}
	if response := readEvent(t, first); response.string("id") != "emit-active" {
		t.Fatalf("fake emit response = %#v", response)
	}

	second := dialWebSocket(t, server, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer second.Close()
	attached, history, replay := readAttachHistory(t, second)
	if attached.string("session_id") != sessionID || !attached.boolean("history") {
		t.Fatalf("attach ready = %#v", attached)
	}
	if history.string("command") != "get_entries" || !history.boolean("success") {
		t.Fatalf("history response = %#v", history)
	}
	wantTypes := []string{
		"agent_start", "message_start", "message_end", "message_start", "message_update", "extension_ui_request",
	}
	if len(replay) != len(wantTypes) {
		t.Fatalf("replay event count = %d, want %d: %#v", len(replay), len(wantTypes), replay)
	}
	for index, want := range wantTypes {
		if replay[index].string("type") != want {
			t.Fatalf("replay[%d] type = %q, want %q", index, replay[index].string("type"), want)
		}
	}

	liveEvent := map[string]any{
		"type": "message_update",
		"assistantMessageEvent": map[string]any{
			"type": "text_delta", "contentIndex": 0, "delta": " live",
		},
	}
	writeJSON(t, first, map[string]any{"id": "emit-live", "type": "fake_emit", "events": []any{liveEvent}})
	if got := readEvent(t, first); got.string("type") != "message_update" {
		t.Fatalf("first live event = %#v", got)
	}
	if got := readEvent(t, second); got.string("type") != "message_update" {
		t.Fatalf("second live event = %#v", got)
	}
	if got := readEvent(t, first); got.string("id") != "emit-live" {
		t.Fatalf("first live response = %#v", got)
	}
	if got := readEvent(t, second); got.string("id") != "emit-live" {
		t.Fatalf("second live response = %#v", got)
	}

	_ = app
}

func TestAgentSettledCheckpointsHistoryAndClearsReplay(t *testing.T) {
	app, server := startTestGateway(t, t.TempDir())
	first := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer first.Close()
	sessionID := readEvent(t, first).string("session_id")

	entry := map[string]any{
		"type": "message", "id": "entry-1", "parentId": nil,
		"message": map[string]any{"role": "user", "content": "persisted", "timestamp": 1},
	}
	writeJSON(t, first, map[string]any{"id": "set-entries", "type": "fake_set_entries", "entries": []any{entry}})
	if got := readEvent(t, first); got.string("id") != "set-entries" {
		t.Fatalf("set entries response = %#v", got)
	}
	writeJSON(t, first, map[string]any{
		"id": "settle", "type": "fake_emit", "events": []any{
			map[string]any{"type": "agent_start"},
			map[string]any{"type": "agent_settled"},
		},
	})
	if got := readEvent(t, first); got.string("type") != "agent_start" {
		t.Fatalf("agent_start = %#v", got)
	}
	if got := readEvent(t, first); got.string("type") != "agent_settled" {
		t.Fatalf("agent_settled = %#v", got)
	}
	if got := readEvent(t, first); got.string("id") != "settle" {
		t.Fatalf("settle response = %#v", got)
	}

	session := activeSession(t, app, sessionID)
	waitFor(t, 3*time.Second, func() bool {
		session.replayMu.Lock()
		defer session.replayMu.Unlock()
		return session.historyThrough > 0 && len(session.replay) == 0
	})

	second := dialWebSocket(t, server, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer second.Close()
	_, history, replay := readAttachHistory(t, second)
	data, _ := history["data"].(map[string]any)
	entries, _ := data["entries"].([]any)
	if len(entries) != 1 {
		t.Fatalf("checkpoint entries = %#v, want one", entries)
	}
	if len(replay) != 0 {
		t.Fatalf("settled session replay = %#v, want empty", replay)
	}
}

func TestActiveReplaySurvivesGatewayRestart(t *testing.T) {
	dataDir := t.TempDir()
	firstGateway, firstServer := startTestGateway(t, dataDir)
	first := dialWebSocket(t, firstServer, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	sessionID := readEvent(t, first).string("session_id")
	events := []any{
		map[string]any{"type": "agent_start"},
		map[string]any{"type": "message_start", "message": map[string]any{
			"role": "assistant", "content": []any{}, "timestamp": 2,
		}},
		map[string]any{
			"type": "message_update",
			"assistantMessageEvent": map[string]any{
				"type": "text_delta", "contentIndex": 0, "delta": "survives restart",
			},
		},
	}
	writeJSON(t, first, map[string]any{"id": "persist-tail", "type": "fake_emit", "events": events})
	for range events {
		_ = readEvent(t, first)
	}
	_ = readEvent(t, first)
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
	_, _, replay := readAttachHistory(t, second)
	if len(replay) != len(events) {
		t.Fatalf("recovered replay count = %d, want %d: %#v", len(replay), len(events), replay)
	}
	if replay[2].string("type") != "message_update" {
		t.Fatalf("recovered final replay event = %#v", replay[2])
	}
}

func TestCheckpointPreservesWALTailAfterReplayOverflow(t *testing.T) {
	dir := t.TempDir()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	newStoredSession := func(newSession bool) *piSession {
		return newPiSession(piSessionConfig{
			ID:             "11111111-1111-4111-8111-111111111111",
			Dir:            dir,
			MaxEventBytes:  1 << 20,
			MaxReplayBytes: 1 << 20,
			InputQueueSize: 1,
			SessionIdle:    time.Minute,
			HistoryTimeout: time.Second,
			NewSession:     newSession,
			Logger:         logger,
			OnExit:         func(*piSession, error) {},
		})
	}

	session := newStoredSession(true)
	if err := session.openReplayStore(true); err != nil {
		t.Fatalf("open replay store: %v", err)
	}
	session.replayMu.Lock()
	for seq := uint64(1); seq <= 3; seq++ {
		payload, _ := json.Marshal(map[string]any{"type": "message_update", "seq": seq})
		if err := session.appendReplayLocked(replayRecord{Seq: seq, Payload: payload}); err != nil {
			session.replayMu.Unlock()
			t.Fatalf("append replay %d: %v", seq, err)
		}
	}
	session.outputSeq = 3
	session.replayTruncated = true
	if err := session.checkpointHistoryLocked(emptyHistoryResponse(), 2); err != nil {
		session.replayMu.Unlock()
		t.Fatalf("checkpoint history: %v", err)
	}
	session.replayMu.Unlock()
	session.closeReplayStore()

	recovered := newStoredSession(false)
	if err := recovered.openReplayStore(false); err != nil {
		t.Fatalf("recover replay store: %v", err)
	}
	defer recovered.closeReplayStore()
	recovered.replayMu.Lock()
	defer recovered.replayMu.Unlock()
	if recovered.historyThrough != 2 {
		t.Fatalf("history through = %d, want 2", recovered.historyThrough)
	}
	if len(recovered.replay) != 1 || recovered.replay[0].Seq != 3 {
		t.Fatalf("recovered replay = %#v, want only seq 3", recovered.replay)
	}
}

func TestSettledSessionStopsAfterIdleTimeout(t *testing.T) {
	dataDir := t.TempDir()
	app, server := startTestGatewayWithConfig(t, dataDir, func(cfg *Config) {
		cfg.SessionIdle = 120 * time.Millisecond
	})
	first := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	sessionID := readEvent(t, first).string("session_id")
	session := activeSession(t, app, sessionID)

	writeJSON(t, first, map[string]any{
		"id": "start", "type": "fake_emit", "events": []any{map[string]any{"type": "agent_start"}},
	})
	_ = readEvent(t, first)
	_ = readEvent(t, first)
	time.Sleep(2 * app.cfg.SessionIdle)
	if session.isDone() {
		t.Fatal("busy session stopped at the idle timeout")
	}

	writeJSON(t, first, map[string]any{
		"id": "settled", "type": "fake_emit", "events": []any{map[string]any{"type": "agent_settled"}},
	})
	_ = readEvent(t, first)
	_ = readEvent(t, first)
	waitFor(t, 3*time.Second, session.isDone)

	_ = first.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err := first.ReadMessage()
	if err == nil || !websocket.IsCloseError(err, websocket.CloseNormalClosure) {
		t.Fatalf("idle WebSocket close = %v, want code %d", err, websocket.CloseNormalClosure)
	}

	second := dialWebSocket(t, server, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer second.Close()
	_, _, _ = readAttachHistory(t, second)
	writeJSON(t, second, map[string]any{"id": "restart-sync", "type": "get_state"})
	if got := readEvent(t, second); got.string("id") != "restart-sync" {
		t.Fatalf("restart sync response = %#v", got)
	}
	waitFor(t, time.Second, func() bool {
		starts, err := os.ReadFile(filepath.Join(dataDir, "sessions", sessionID, "fake-starts.log"))
		return err == nil && len(strings.Fields(string(starts))) == 2
	})
}

func TestIdleTimeoutWaitsForHistoryCheckpoint(t *testing.T) {
	app, server := startTestGatewayWithConfig(t, t.TempDir(), func(cfg *Config) {
		cfg.SessionIdle = 80 * time.Millisecond
		cfg.HistoryTimeout = time.Second
	})
	client := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")
	session := activeSession(t, app, sessionID)

	writeJSON(t, client, map[string]any{
		"id": "delay-history", "type": "fake_set_history_delay", "milliseconds": 250,
	})
	_ = readEvent(t, client)
	writeJSON(t, client, map[string]any{
		"id": "settle-slow-checkpoint", "type": "fake_emit", "events": []any{
			map[string]any{"type": "agent_start"},
			map[string]any{"type": "agent_settled"},
		},
	})
	_ = readEvent(t, client)
	_ = readEvent(t, client)
	_ = readEvent(t, client)

	time.Sleep(2 * app.cfg.SessionIdle)
	if session.isDone() {
		t.Fatal("session stopped before its history checkpoint completed")
	}
	waitFor(t, 2*time.Second, session.isDone)
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
	return startTestGatewayWithConfig(t, dataDir, nil)
}

func startTestGatewayWithConfig(t *testing.T, dataDir string, configure func(*Config)) (*Gateway, *httptest.Server) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	cfg := Config{
		Token:           testToken,
		DataDir:         dataDir,
		WorkDir:         t.TempDir(),
		PiCommand:       os.Args[0],
		PiArgs:          []string{"-test.run=TestPiHelperProcess", "--"},
		MaxMessageBytes: 1 << 20,
		WriteTimeout:    250 * time.Millisecond,
		PongTimeout:     2 * time.Second,
		Logger:          logger,
	}
	if configure != nil {
		configure(&cfg)
	}
	app, err := New(cfg)
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

func activeSession(t *testing.T, app *Gateway, sessionID string) *piSession {
	t.Helper()
	app.manager.mu.Lock()
	defer app.manager.mu.Unlock()
	session := app.manager.sessions[sessionID]
	if session == nil {
		t.Fatalf("session %q is not active", sessionID)
	}
	return session
}

func waitFor(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition was not satisfied before timeout")
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

func readAttachHistory(t *testing.T, conn *websocket.Conn) (event, event, []event) {
	t.Helper()
	ready := readEvent(t, conn)
	if ready.string("type") != "pi2ws" || ready.string("event") != "ready" {
		t.Fatalf("attach first message = %#v, want ready", ready)
	}
	history := readEvent(t, conn)
	if history.string("type") != "response" || history.string("command") != "get_entries" {
		t.Fatalf("attach history message = %#v, want get_entries response", history)
	}
	begin := readEvent(t, conn)
	if begin.string("type") != "pi2ws" || begin.string("event") != "replay_begin" {
		t.Fatalf("attach replay start = %#v", begin)
	}

	var replay []event
	for {
		message := readEvent(t, conn)
		if message.string("type") != "pi2ws" {
			t.Fatalf("attach replay envelope = %#v", message)
		}
		switch message.string("event") {
		case "replay":
			payload, ok := message["payload"].(map[string]any)
			if !ok {
				t.Fatalf("replay payload = %#v", message["payload"])
			}
			replay = append(replay, event(payload))
		case "replay_unavailable":
			// The caller can inspect the empty/incomplete replay through later
			// assertions; this helper still drains the initial backlog.
		case "replay_end":
			return ready, history, replay
		default:
			t.Fatalf("unexpected replay event = %#v", message)
		}
	}
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
