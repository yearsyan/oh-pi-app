package gateway

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSessionManagementAPI(t *testing.T) {
	dataDir := t.TempDir()
	_, server := startTestGateway(t, dataDir)

	response := sessionAPIRequest(t, server, http.MethodGet, "/api/sessions", nil, "")
	if response.StatusCode != http.StatusUnauthorized {
		response.Body.Close()
		t.Fatalf("unauthenticated list status = %d, want %d", response.StatusCode, http.StatusUnauthorized)
	}
	response.Body.Close()

	workDir := t.TempDir()
	resolvedWorkDir, err := filepath.EvalSymlinks(workDir)
	if err != nil {
		t.Fatalf("resolve test work directory: %v", err)
	}
	client := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {workDir},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")

	listed := getSessionList(t, server)
	if len(listed.Sessions) != 1 {
		t.Fatalf("session list = %#v, want one session", listed.Sessions)
	}
	created := listed.Sessions[0]
	if created.ID != sessionID || created.WorkDir != resolvedWorkDir || !created.Running {
		t.Fatalf("created session summary = %#v", created)
	}
	if created.CreatedAt <= 0 || created.LastActive < created.CreatedAt {
		t.Fatalf("created timestamps = (%d, %d)", created.CreatedAt, created.LastActive)
	}

	response = sessionAPIRequest(
		t,
		server,
		http.MethodPatch,
		"/api/sessions/"+sessionID,
		[]byte(`{"name":"  managed name  "}`),
		testToken,
	)
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("rename status = %d, body = %s", response.StatusCode, body)
	}
	var renamed sessionResponse
	decodeHTTPJSON(t, response, &renamed)
	if renamed.Name != "managed name" || !renamed.Running {
		t.Fatalf("renamed session = %#v", renamed)
	}
	if forwarded := readEvent(t, client); forwarded.string("command") != "set_session_name" {
		t.Fatalf("forwarded rename response = %#v", forwarded)
	}

	writeJSON(t, client, map[string]any{
		"id":   "rename-over-ws",
		"type": "set_session_name",
		"name": "  websocket name  ",
	})
	if wsRename := readEvent(t, client); wsRename.string("id") != "rename-over-ws" {
		t.Fatalf("WebSocket rename response = %#v", wsRename)
	}
	listed = getSessionList(t, server)
	if got := listed.Sessions[0].Name; got != "websocket name" {
		t.Fatalf("name after WebSocket rename = %q", got)
	}

	response = sessionAPIRequest(t, server, http.MethodDelete, "/api/sessions/"+sessionID, nil, testToken)
	if response.StatusCode != http.StatusNoContent {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("delete status = %d, body = %s", response.StatusCode, body)
	}
	response.Body.Close()
	if _, err := os.Stat(filepath.Join(dataDir, "sessions", sessionID)); !os.IsNotExist(err) {
		t.Fatalf("deleted session directory stat error = %v, want not exist", err)
	}

	listed = getSessionList(t, server)
	if len(listed.Sessions) != 0 {
		t.Fatalf("session list after delete = %#v, want empty", listed.Sessions)
	}
	response = sessionAPIRequest(t, server, http.MethodGet, "/api/sessions/"+sessionID, nil, testToken)
	if response.StatusCode != http.StatusNotFound {
		response.Body.Close()
		t.Fatalf("deleted session status = %d, want %d", response.StatusCode, http.StatusNotFound)
	}
	response.Body.Close()
}

func TestSessionMetricsAPIReadsPersistedSummary(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	client := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")
	writeJSON(t, client, map[string]any{
		"id": "emit-metric", "type": "fake_emit",
		"events": []any{
			map[string]any{"type": "turn_start", "turnIndex": 2},
			map[string]any{
				"type":                  "message_update",
				"assistantMessageEvent": map[string]any{"type": "text_start"},
			},
			map[string]any{
				"type": "message_end",
				"message": map[string]any{
					"role": "assistant", "provider": "fake", "model": "reasoning-model",
					"stopReason": "stop", "usage": map[string]any{"output": 50},
				},
			},
		},
	})
	for _, wantType := range []string{"turn_start", "message_update", "message_end"} {
		if event := readEvent(t, client); event.string("type") != wantType {
			t.Fatalf("metric source event = %#v, want %q", event, wantType)
		}
	}
	if response := readEvent(t, client); response.string("id") != "emit-metric" {
		t.Fatalf("fake_emit response = %#v", response)
	}

	unauthorized := sessionAPIRequest(
		t, server, http.MethodGet, "/api/sessions/"+sessionID+"/metrics", nil, "",
	)
	if unauthorized.StatusCode != http.StatusUnauthorized {
		unauthorized.Body.Close()
		t.Fatalf("unauthorized metrics status = %d, want %d", unauthorized.StatusCode, http.StatusUnauthorized)
	}
	unauthorized.Body.Close()

	response := sessionAPIRequest(
		t, server, http.MethodGet, "/api/sessions/"+sessionID+"/metrics", nil, testToken,
	)
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("metrics status = %d, body = %s", response.StatusCode, body)
	}
	var metrics sessionMetricsResponse
	decodeHTTPJSON(t, response, &metrics)
	if metrics.SessionID != sessionID || metrics.SampleCount != 1 || metrics.AverageTPS == nil ||
		*metrics.AverageTPS <= 0 || metrics.AverageTTFTMillis == nil {
		t.Fatalf("metrics response = %#v", metrics)
	}

	methodRejected := sessionAPIRequest(
		t, server, http.MethodPatch, "/api/sessions/"+sessionID+"/metrics", []byte(`{}`), testToken,
	)
	if methodRejected.StatusCode != http.StatusMethodNotAllowed || methodRejected.Header.Get("Allow") != http.MethodGet {
		methodRejected.Body.Close()
		t.Fatalf("metrics PATCH = (%d, Allow %q), want (405, GET)",
			methodRejected.StatusCode, methodRejected.Header.Get("Allow"))
	}
	methodRejected.Body.Close()
}

func TestSessionManagementAPIRejectsInvalidUpdates(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	client := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")

	tests := []struct {
		name string
		body string
	}{
		{name: "missing name", body: `{}`},
		{name: "empty name", body: `{"name":"  "}`},
		{name: "unknown field", body: `{"name":"ok","extra":true}`},
		{name: "trailing value", body: `{"name":"ok"} {}`},
		{name: "too long", body: `{"name":"` + strings.Repeat("界", maxSessionNameRunes+1) + `"}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := sessionAPIRequest(
				t,
				server,
				http.MethodPatch,
				"/api/sessions/"+sessionID,
				[]byte(test.body),
				testToken,
			)
			response.Body.Close()
			if response.StatusCode != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusBadRequest)
			}
		})
	}

	writeJSON(t, client, map[string]any{
		"id":   "long-name",
		"type": "set_session_name",
		"name": strings.Repeat("界", maxSessionNameRunes+1),
	})
	if event := readEvent(t, client); event.string("code") != "invalid_session_name" {
		t.Fatalf("invalid WebSocket rename event = %#v", event)
	}
	writeJSON(t, client, map[string]any{
		"id": "empty-name", "type": "set_session_name", "name": "  ",
	})
	if event := readEvent(t, client); event.string("code") != "invalid_session_name" {
		t.Fatalf("empty WebSocket rename event = %#v", event)
	}
}

func TestPiGeneratedSessionNameIsPersistedAndManualNameWins(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	client := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer client.Close()
	_ = readEvent(t, client)

	writeJSON(t, client, map[string]any{
		"id": "generated-name", "type": "fake_emit",
		"events": []any{map[string]any{
			"type": "session_info_changed", "name": "generated title",
		}},
	})
	if event := readEvent(t, client); event.string("type") != "session_info_changed" || event.string("name") != "generated title" {
		t.Fatalf("generated name event = %#v", event)
	}
	if response := readEvent(t, client); response.string("id") != "generated-name" {
		t.Fatalf("generated name response = %#v", response)
	}
	if got := getSessionList(t, server).Sessions[0].Name; got != "generated title" {
		t.Fatalf("persisted generated name = %q", got)
	}

	writeJSON(t, client, map[string]any{
		"id": "manual-name", "type": "set_session_name", "name": "manual title",
	})
	if response := readEvent(t, client); response.string("id") != "manual-name" {
		t.Fatalf("manual name response = %#v", response)
	}

	writeJSON(t, client, map[string]any{
		"id": "late-generated-name", "type": "fake_emit",
		"events": []any{map[string]any{
			"type": "session_info_changed", "name": "late generated title",
		}},
	})
	// The conflicting event is suppressed; only fake_emit's response remains.
	if response := readEvent(t, client); response.string("id") != "late-generated-name" {
		t.Fatalf("late generated name response = %#v", response)
	}
	if got := getSessionList(t, server).Sessions[0].Name; got != "manual title" {
		t.Fatalf("name after late generation = %q, want manual title", got)
	}
}

func TestSessionAPIReportsOutputtingStatus(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	client := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer client.Close()
	readEvent(t, client)

	listed := getSessionList(t, server)
	if len(listed.Sessions) != 1 || !listed.Sessions[0].Running || listed.Sessions[0].Outputting {
		t.Fatalf("idle running session = %#v", listed.Sessions)
	}

	writeJSON(t, client, map[string]any{
		"id": "start-output", "type": "fake_emit",
		"events": []any{map[string]any{"type": "agent_start"}},
	})
	if event := readEvent(t, client); event.string("type") != "agent_start" {
		t.Fatalf("start event = %#v", event)
	}
	if response := readEvent(t, client); response.string("id") != "start-output" {
		t.Fatalf("start response = %#v", response)
	}
	listed = getSessionList(t, server)
	if !listed.Sessions[0].Running || !listed.Sessions[0].Outputting {
		t.Fatalf("outputting session = %#v", listed.Sessions[0])
	}

	writeJSON(t, client, map[string]any{
		"id": "settle-output", "type": "fake_emit",
		"events": []any{map[string]any{"type": "agent_settled"}},
	})
	if event := readEvent(t, client); event.string("type") != "agent_settled" {
		t.Fatalf("settled event = %#v", event)
	}
	if response := readEvent(t, client); response.string("id") != "settle-output" {
		t.Fatalf("settled response = %#v", response)
	}
	listed = getSessionList(t, server)
	if !listed.Sessions[0].Running || listed.Sessions[0].Outputting {
		t.Fatalf("settled running session = %#v", listed.Sessions[0])
	}
}

func TestSessionProcessStopRequiresSettledAndPreservesSession(t *testing.T) {
	dataDir := t.TempDir()
	_, server := startTestGateway(t, dataDir)
	client := dialWebSocket(t, server, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")
	processPath := "/api/sessions/" + sessionID + "/process"

	unauthorized := sessionAPIRequest(t, server, http.MethodDelete, processPath, nil, "")
	unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated stop status = %d, want %d", unauthorized.StatusCode, http.StatusUnauthorized)
	}

	writeJSON(t, client, map[string]any{
		"id": "start-output", "type": "fake_emit",
		"events": []any{map[string]any{"type": "agent_start"}},
	})
	if event := readEvent(t, client); event.string("type") != "agent_start" {
		t.Fatalf("start event = %#v", event)
	}
	if response := readEvent(t, client); response.string("id") != "start-output" {
		t.Fatalf("start response = %#v", response)
	}

	blocked := sessionAPIRequest(t, server, http.MethodDelete, processPath, nil, testToken)
	var blockedError struct {
		Code string `json:"error"`
	}
	decodeHTTPJSON(t, blocked, &blockedError)
	if blocked.StatusCode != http.StatusConflict || blockedError.Code != "session_outputting" {
		t.Fatalf(
			"outputting stop response = status %d code %q, want status %d code session_outputting",
			blocked.StatusCode,
			blockedError.Code,
			http.StatusConflict,
		)
	}
	if current := getSessionList(t, server).Sessions[0]; !current.Running || !current.Outputting {
		t.Fatalf("session after rejected stop = %#v", current)
	}

	writeJSON(t, client, map[string]any{
		"id": "settle-output", "type": "fake_emit",
		"events": []any{map[string]any{"type": "agent_settled"}},
	})
	if event := readEvent(t, client); event.string("type") != "agent_settled" {
		t.Fatalf("settled event = %#v", event)
	}
	if response := readEvent(t, client); response.string("id") != "settle-output" {
		t.Fatalf("settled response = %#v", response)
	}

	stopped := sessionAPIRequest(t, server, http.MethodDelete, processPath, nil, testToken)
	stopped.Body.Close()
	if stopped.StatusCode != http.StatusNoContent {
		t.Fatalf("settled stop status = %d, want %d", stopped.StatusCode, http.StatusNoContent)
	}
	listed := getSessionList(t, server)
	if len(listed.Sessions) != 1 || listed.Sessions[0].Running || listed.Sessions[0].Outputting {
		t.Fatalf("session after process stop = %#v", listed.Sessions)
	}
	if _, err := os.Stat(filepath.Join(dataDir, "sessions", sessionID)); err != nil {
		t.Fatalf("preserved session directory: %v", err)
	}

	idempotent := sessionAPIRequest(t, server, http.MethodDelete, processPath, nil, testToken)
	idempotent.Body.Close()
	if idempotent.StatusCode != http.StatusNoContent {
		t.Fatalf("repeated stop status = %d, want %d", idempotent.StatusCode, http.StatusNoContent)
	}

	attached := dialWebSocket(t, server, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer attached.Close()
	ready, _, _ := readAttachHistory(t, attached)
	if ready.string("session_id") != sessionID {
		t.Fatalf("reattached session = %#v", ready)
	}
}

func TestSessionListSurvivesGatewayRestart(t *testing.T) {
	dataDir := t.TempDir()
	firstGateway, firstServer := startTestGateway(t, dataDir)
	client := dialWebSocket(t, firstServer, url.Values{
		"action":   {"create"},
		"token":    {testToken},
		"work_dir": {t.TempDir()},
	})
	sessionID := readEvent(t, client).string("session_id")
	writeJSON(t, client, map[string]any{
		"id":   "persist-name",
		"type": "set_session_name",
		"name": "survives restart",
	})
	if response := readEvent(t, client); response.string("id") != "persist-name" {
		t.Fatalf("rename response = %#v", response)
	}
	client.Close()
	shutdownGateway(t, firstGateway)
	firstServer.Close()

	_, secondServer := startTestGateway(t, dataDir)
	listed := getSessionList(t, secondServer)
	if len(listed.Sessions) != 1 {
		t.Fatalf("restarted session list = %#v, want one", listed.Sessions)
	}
	if listed.Sessions[0].ID != sessionID || listed.Sessions[0].Name != "survives restart" {
		t.Fatalf("restarted session = %#v", listed.Sessions[0])
	}
	if listed.Sessions[0].Running {
		t.Fatalf("restarted session running = true, want false before attach")
	}
	if listed.Sessions[0].Outputting {
		t.Fatalf("restarted session outputting = true, want false before attach")
	}

	attached := dialWebSocket(t, secondServer, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer attached.Close()
	ready, _, _ := readAttachHistory(t, attached)
	if ready.string("session_id") != sessionID {
		t.Fatalf("attached session = %#v", ready)
	}
}

func getSessionList(t *testing.T, server *httptest.Server) sessionListResponse {
	t.Helper()
	response := sessionAPIRequest(t, server, http.MethodGet, "/api/sessions", nil, testToken)
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("list status = %d, body = %s", response.StatusCode, body)
	}
	var listed sessionListResponse
	decodeHTTPJSON(t, response, &listed)
	return listed
}

func sessionAPIRequest(
	t *testing.T,
	server *httptest.Server,
	method string,
	path string,
	body []byte,
	token string,
) *http.Response {
	t.Helper()
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	request, err := http.NewRequest(method, server.URL+path, reader)
	if err != nil {
		t.Fatalf("create HTTP request: %v", err)
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := server.Client().Do(request)
	if err != nil {
		t.Fatalf("perform HTTP request: %v", err)
	}
	return response
}

func decodeHTTPJSON(t *testing.T, response *http.Response, target any) {
	t.Helper()
	defer response.Body.Close()
	if err := json.NewDecoder(response.Body).Decode(target); err != nil {
		t.Fatalf("decode HTTP JSON: %v", err)
	}
}
