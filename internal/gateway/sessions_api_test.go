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
