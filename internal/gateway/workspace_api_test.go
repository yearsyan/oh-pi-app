package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWorkspaceAPIReplacesFlatSessionAPI(t *testing.T) {
	dataDir := t.TempDir()
	_, server := startTestGateway(t, dataDir)

	legacy := workspaceAPIRequest(t, server, http.MethodGet, "/api/sessions", nil, testToken)
	legacy.Body.Close()
	if legacy.StatusCode != http.StatusNotFound {
		t.Fatalf("legacy session endpoint status = %d, want 404", legacy.StatusCode)
	}

	directory := t.TempDir()
	if err := os.WriteFile(filepath.Join(directory, "go.mod"), []byte("module example.test/workspace\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	workspace := createTestWorkspace(t, server, directory)
	client := dialWebSocket(t, server, url.Values{
		"action":       {"create"},
		"token":        {testToken},
		"workspace_id": {workspace.ID},
	})
	defer client.Close()
	ready := readEvent(t, client)
	sessionID := ready.string("session_id")
	if ready.string("workspace_id") != workspace.ID || ready.string("workspace_directory") != workspace.Directory {
		t.Fatalf("ready workspace = %#v", ready)
	}

	listed := getWorkspaceList(t, server, 2)
	current := findWorkspace(t, listed.Workspaces, workspace.ID)
	if current.Technology != "go" || len(current.Sessions) != 1 || current.Sessions[0].ID != sessionID {
		t.Fatalf("listed workspace = %#v", current)
	}
	if !current.Sessions[0].Running || current.Sessions[0].Outputting {
		t.Fatalf("listed session state = %#v", current.Sessions[0])
	}

	sessionPath := workspaceSessionPath(workspace.ID, sessionID)
	rename := workspaceAPIRequest(
		t,
		server,
		http.MethodPatch,
		sessionPath,
		[]byte(`{"name":"  managed name  "}`),
		testToken,
	)
	if rename.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(rename.Body)
		rename.Body.Close()
		t.Fatalf("rename status = %d body = %s", rename.StatusCode, body)
	}
	var renamed sessionResponse
	decodeHTTPJSON(t, rename, &renamed)
	if renamed.Name != "managed name" || !renamed.Running {
		t.Fatalf("renamed session = %#v", renamed)
	}
	if forwarded := readEvent(t, client); forwarded.string("command") != "set_session_name" {
		t.Fatalf("forwarded rename = %#v", forwarded)
	}

	deleted := workspaceAPIRequest(t, server, http.MethodDelete, sessionPath, nil, testToken)
	deleted.Body.Close()
	if deleted.StatusCode != http.StatusNoContent {
		t.Fatalf("delete status = %d", deleted.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(dataDir, "sessions", sessionID)); !os.IsNotExist(err) {
		t.Fatalf("deleted session stat error = %v", err)
	}
	if got := findWorkspace(t, getWorkspaceList(t, server, 2).Workspaces, workspace.ID); got.SessionCount != 0 {
		t.Fatalf("workspace after delete = %#v", got)
	}
}

func TestWorkspaceSessionListsCanExcludeScheduledTaskSessions(t *testing.T) {
	app, server := startTestGateway(t, t.TempDir())
	workspace := createTestWorkspace(t, server, t.TempDir())
	regular, _, err := app.manager.store.create(workspace.ID)
	if err != nil {
		t.Fatal(err)
	}
	scheduled, _, err := app.manager.store.createWithSource(workspace.ID, sessionSourceScheduledTask)
	if err != nil {
		t.Fatal(err)
	}

	all := findWorkspace(t, getWorkspaceList(t, server, 10).Workspaces, workspace.ID)
	if all.SessionCount != 2 || len(all.Sessions) != 2 {
		t.Fatalf("unfiltered workspace sessions = %#v", all)
	}
	var sawScheduled bool
	for _, session := range all.Sessions {
		if session.ID == scheduled.ID && session.Source == sessionSourceScheduledTask {
			sawScheduled = true
		}
	}
	if !sawScheduled {
		t.Fatalf("scheduled session source not returned: %#v", all.Sessions)
	}

	filteredResponse := workspaceAPIRequest(
		t,
		server,
		http.MethodGet,
		"/api/workspaces?session_limit=10&include_scheduled=false",
		nil,
		testToken,
	)
	var filtered workspaceListResponse
	decodeHTTPJSON(t, filteredResponse, &filtered)
	visible := findWorkspace(t, filtered.Workspaces, workspace.ID)
	if visible.SessionCount != 1 || len(visible.Sessions) != 1 || visible.Sessions[0].ID != regular.ID {
		t.Fatalf("filtered workspace sessions = %#v", visible)
	}

	pageResponse := workspaceAPIRequest(
		t,
		server,
		http.MethodGet,
		"/api/workspaces/"+workspace.ID+"/sessions?limit=10&include_scheduled=false",
		nil,
		testToken,
	)
	var page workspaceSessionPageResponse
	decodeHTTPJSON(t, pageResponse, &page)
	if len(page.Sessions) != 1 || page.Sessions[0].ID != regular.ID || page.NextCursor != "" {
		t.Fatalf("filtered session page = %#v", page)
	}

	invalid := workspaceAPIRequest(
		t,
		server,
		http.MethodGet,
		"/api/workspaces?include_scheduled=1",
		nil,
		testToken,
	)
	invalid.Body.Close()
	if invalid.StatusCode != http.StatusBadRequest {
		t.Fatalf("invalid include_scheduled status = %d, want 400", invalid.StatusCode)
	}
}

func TestWorkspaceMetadataAndSystemPrompt(t *testing.T) {
	app, server := startTestGateway(t, t.TempDir())
	workspace := createTestWorkspace(t, server, t.TempDir())
	updatedResponse := workspaceAPIRequest(
		t,
		server,
		http.MethodPatch,
		"/api/workspaces/"+workspace.ID,
		[]byte(`{
			"name":"Gateway work",
			"additional_system_prompt":"Always run tests before answering.",
			"skill_paths":[" .pi/team-skills ","/srv/shared-skills",".pi/team-skills"],
			"no_skills":true,
			"extension_paths":[".pi/extensions/team.ts","/srv/extensions"],
			"no_extensions":true
		}`),
		testToken,
	)
	if updatedResponse.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(updatedResponse.Body)
		updatedResponse.Body.Close()
		t.Fatalf("update workspace status = %d body = %s", updatedResponse.StatusCode, body)
	}
	var updated workspaceResponse
	decodeHTTPJSON(t, updatedResponse, &updated)
	if updated.Name != "Gateway work" || updated.AdditionalSystemPrompt != "Always run tests before answering." ||
		fmt.Sprint(updated.SkillPaths) != fmt.Sprint([]string{".pi/team-skills", "/srv/shared-skills"}) ||
		!updated.NoSkills ||
		fmt.Sprint(updated.ExtensionPaths) != fmt.Sprint([]string{".pi/extensions/team.ts", "/srv/extensions"}) ||
		!updated.NoExtensions {
		t.Fatalf("updated workspace = %#v", updated)
	}

	client := dialWebSocket(t, server, url.Values{
		"action":       {"create"},
		"token":        {testToken},
		"workspace_id": {workspace.ID},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")
	active := activeSession(t, app, sessionID)
	if !containsArgumentPair(active.args, "--append-system-prompt", updated.AdditionalSystemPrompt) {
		t.Fatalf("pi args = %q, missing workspace system prompt", active.args)
	}
	if !hasArgument(active.args, "--no-skills") ||
		!containsArgumentPair(active.args, "--skill", ".pi/team-skills") ||
		!containsArgumentPair(active.args, "--skill", "/srv/shared-skills") ||
		!hasArgument(active.args, "--no-extensions") ||
		!containsArgumentPair(active.args, "--extension", ".pi/extensions/team.ts") ||
		!containsArgumentPair(active.args, "--extension", "/srv/extensions") {
		t.Fatalf("pi args = %q, missing workspace resource configuration", active.args)
	}

	invalid := workspaceAPIRequest(
		t,
		server,
		http.MethodPatch,
		"/api/workspaces/"+workspace.ID,
		[]byte(`{"additional_system_prompt":"ok","unknown":true}`),
		testToken,
	)
	invalid.Body.Close()
	if invalid.StatusCode != http.StatusBadRequest {
		t.Fatalf("unknown metadata field status = %d, want 400", invalid.StatusCode)
	}

	invalidResource := workspaceAPIRequest(
		t,
		server,
		http.MethodPatch,
		"/api/workspaces/"+workspace.ID,
		[]byte(`{"skill_paths":["  "]}`),
		testToken,
	)
	invalidResource.Body.Close()
	if invalidResource.StatusCode != http.StatusBadRequest {
		t.Fatalf("empty skill path status = %d, want 400", invalidResource.StatusCode)
	}
}

func TestDeleteWorkspacePreservesSessionsAndRecreatingDirectoryRestoresThem(t *testing.T) {
	dataDir := t.TempDir()
	app, server := startTestGateway(t, dataDir)
	directory := t.TempDir()
	workspace := createTestWorkspace(t, server, directory)
	updated := workspaceAPIRequest(
		t,
		server,
		http.MethodPatch,
		"/api/workspaces/"+workspace.ID,
		[]byte(`{"name":"Restorable","additional_system_prompt":"Keep this."}`),
		testToken,
	)
	updated.Body.Close()

	client := dialWebSocket(t, server, url.Values{
		"action":       {"create"},
		"token":        {testToken},
		"workspace_id": {workspace.ID},
	})
	sessionID := readEvent(t, client).string("session_id")

	deleted := workspaceAPIRequest(
		t,
		server,
		http.MethodDelete,
		"/api/workspaces/"+workspace.ID,
		nil,
		testToken,
	)
	deleted.Body.Close()
	if deleted.StatusCode != http.StatusNoContent {
		t.Fatalf("delete workspace status = %d, want 204", deleted.StatusCode)
	}
	for _, listed := range getWorkspaceList(t, server, 5).Workspaces {
		if listed.ID == workspace.ID {
			t.Fatalf("deleted workspace remained in list: %#v", listed)
		}
	}
	if _, err := os.Stat(filepath.Join(dataDir, "sessions", sessionID)); err != nil {
		t.Fatalf("preserved session stat: %v", err)
	}
	if _, _, err := app.manager.store.load(sessionID); err != nil {
		t.Fatalf("load preserved session: %v", err)
	}
	missing := workspaceAPIRequest(
		t,
		server,
		http.MethodGet,
		workspaceSessionPath(workspace.ID, sessionID),
		nil,
		testToken,
	)
	missing.Body.Close()
	if missing.StatusCode != http.StatusNotFound {
		t.Fatalf("session under deleted workspace status = %d, want 404", missing.StatusCode)
	}
	attachURL := server.URL + "/ws?action=attach&token=" + testToken + "&session_id=" + sessionID
	attachResponse, err := server.Client().Get(attachURL)
	if err != nil {
		t.Fatalf("request attach under deleted workspace: %v", err)
	}
	attachResponse.Body.Close()
	if attachResponse.StatusCode != http.StatusNotFound {
		t.Fatalf("attach under deleted workspace status = %d, want 404", attachResponse.StatusCode)
	}
	writeJSON(t, client, map[string]any{"id": "after-delete", "type": "get_state"})
	if response := readEvent(t, client); response.string("id") != "after-delete" {
		t.Fatalf("established client response after workspace deletion = %#v", response)
	}
	client.Close()
	shutdownGateway(t, app)
	server.Close()

	_, restartedServer := startTestGateway(t, dataDir)
	for _, listed := range getWorkspaceList(t, restartedServer, 5).Workspaces {
		if listed.ID == workspace.ID {
			t.Fatalf("deleted workspace revived after restart: %#v", listed)
		}
	}
	restored := createTestWorkspace(t, restartedServer, directory)
	if restored.ID != workspace.ID {
		t.Fatalf("restored workspace id = %q, want %q", restored.ID, workspace.ID)
	}
	if restored.Name != "Restorable" || restored.AdditionalSystemPrompt != "Keep this." {
		t.Fatalf("restored workspace metadata = %#v", restored)
	}
	if restored.SessionCount != 1 || len(restored.Sessions) != 1 || restored.Sessions[0].ID != sessionID {
		t.Fatalf("restored workspace sessions = %#v", restored)
	}

	attached := dialWebSocket(t, restartedServer, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer attached.Close()
	ready, _, _ := readAttachHistory(t, attached)
	if ready.string("workspace_id") != workspace.ID {
		t.Fatalf("restored attach ready = %#v", ready)
	}
}

func TestDeletedDefaultWorkspaceStaysDeletedAcrossRestart(t *testing.T) {
	dataDir := t.TempDir()
	directory := t.TempDir()
	firstGateway, firstServer := startTestGatewayWithConfig(t, dataDir, func(cfg *Config) {
		cfg.WorkDir = directory
	})
	resolvedDirectory, err := resolveWorkspaceDirectory(directory)
	if err != nil {
		t.Fatal(err)
	}
	var workspace workspaceResponse
	for _, candidate := range getWorkspaceList(t, firstServer, 5).Workspaces {
		if candidate.Directory == resolvedDirectory {
			workspace = candidate
			break
		}
	}
	if workspace.ID == "" {
		t.Fatalf("default workspace for %q not found", directory)
	}
	deleted := workspaceAPIRequest(
		t,
		firstServer,
		http.MethodDelete,
		"/api/workspaces/"+workspace.ID,
		nil,
		testToken,
	)
	deleted.Body.Close()
	if deleted.StatusCode != http.StatusNoContent {
		t.Fatalf("delete default workspace status = %d, want 204", deleted.StatusCode)
	}
	shutdownGateway(t, firstGateway)
	firstServer.Close()

	_, secondServer := startTestGatewayWithConfig(t, dataDir, func(cfg *Config) {
		cfg.WorkDir = directory
	})
	for _, listed := range getWorkspaceList(t, secondServer, 5).Workspaces {
		if listed.ID == workspace.ID {
			t.Fatalf("deleted default workspace revived after restart: %#v", listed)
		}
	}
	restored := createTestWorkspace(t, secondServer, directory)
	if restored.ID != workspace.ID {
		t.Fatalf("restored default workspace id = %q, want %q", restored.ID, workspace.ID)
	}
}

func TestWorkspaceAPIValidatesAuthOwnershipAndUpdates(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())

	unauthorized := workspaceAPIRequest(t, server, http.MethodGet, "/api/workspaces", nil, "")
	unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated workspace list status = %d, want 401", unauthorized.StatusCode)
	}

	workspace := createTestWorkspace(t, server, t.TempDir())
	otherWorkspace := createTestWorkspace(t, server, t.TempDir())
	client := dialWebSocket(t, server, url.Values{
		"action":       {"create"},
		"token":        {testToken},
		"workspace_id": {workspace.ID},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")

	wrongWorkspace := workspaceAPIRequest(
		t,
		server,
		http.MethodGet,
		workspaceSessionPath(otherWorkspace.ID, sessionID),
		nil,
		testToken,
	)
	wrongWorkspace.Body.Close()
	if wrongWorkspace.StatusCode != http.StatusNotFound {
		t.Fatalf("cross-workspace session status = %d, want 404", wrongWorkspace.StatusCode)
	}

	invalidSessionUpdates := []string{
		`{}`,
		`{"name":"  "}`,
		`{"name":"ok","extra":true}`,
		`{"name":"ok"} {}`,
		`{"name":"` + strings.Repeat("界", maxSessionNameRunes+1) + `"}`,
	}
	for _, body := range invalidSessionUpdates {
		response := workspaceAPIRequest(
			t,
			server,
			http.MethodPatch,
			workspaceSessionPath(workspace.ID, sessionID),
			[]byte(body),
			testToken,
		)
		response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("invalid session update %q status = %d, want 400", body, response.StatusCode)
		}
	}

	invalidWorkspaceUpdates := []string{
		`{}`,
		`{"name":"` + strings.Repeat("界", maxWorkspaceNameRunes+1) + `"}`,
		`{"name":"ok","extra":true}`,
	}
	for _, body := range invalidWorkspaceUpdates {
		response := workspaceAPIRequest(
			t,
			server,
			http.MethodPatch,
			"/api/workspaces/"+workspace.ID,
			[]byte(body),
			testToken,
		)
		response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("invalid workspace update %q status = %d, want 400", body, response.StatusCode)
		}
	}

	for _, suffix := range []string{"?limit=0", "?limit=101", "?cursor=not-base64!"} {
		response := workspaceAPIRequest(
			t,
			server,
			http.MethodGet,
			"/api/workspaces/"+workspace.ID+"/sessions"+suffix,
			nil,
			testToken,
		)
		response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("invalid pagination %q status = %d, want 400", suffix, response.StatusCode)
		}
	}
}

func TestWorkspaceSessionPaginationKeepsRunningSessionsFirst(t *testing.T) {
	app, server := startTestGateway(t, t.TempDir())
	workspace := createTestWorkspace(t, server, t.TempDir())
	ids := make([]string, 0, 4)
	for range 4 {
		client := dialWebSocket(t, server, url.Values{
			"action":       {"create"},
			"token":        {testToken},
			"workspace_id": {workspace.ID},
		})
		ids = append(ids, readEvent(t, client).string("session_id"))
		client.Close()
	}
	// Stop the newest three; the oldest still-running session must sort first.
	for _, id := range ids[1:] {
		ctx, cancel := context.WithCancel(context.Background())
		if err := app.manager.stop(ctx, id); err != nil {
			cancel()
			t.Fatalf("stop session: %v", err)
		}
		cancel()
	}

	preview := findWorkspace(t, getWorkspaceList(t, server, 2).Workspaces, workspace.ID)
	if len(preview.Sessions) != 2 || preview.Sessions[0].ID != ids[0] || !preview.Sessions[0].Running {
		t.Fatalf("active-first preview = %#v", preview.Sessions)
	}
	if preview.SessionCount != 4 || preview.NextCursor == "" {
		t.Fatalf("preview pagination metadata = %#v", preview)
	}

	pageResponse := workspaceAPIRequest(
		t,
		server,
		http.MethodGet,
		"/api/workspaces/"+workspace.ID+"/sessions?limit=2&cursor="+url.QueryEscape(preview.NextCursor),
		nil,
		testToken,
	)
	if pageResponse.StatusCode != http.StatusOK {
		t.Fatalf("page status = %d", pageResponse.StatusCode)
	}
	var page workspaceSessionPageResponse
	decodeHTTPJSON(t, pageResponse, &page)
	if len(page.Sessions) != 2 || page.NextCursor != "" {
		t.Fatalf("remaining page = %#v", page)
	}
}

func TestWorkspaceSessionProcessStopAndMetrics(t *testing.T) {
	dataDir := t.TempDir()
	_, server := startTestGateway(t, dataDir)
	workspace := createTestWorkspace(t, server, t.TempDir())
	client := dialWebSocket(t, server, url.Values{
		"action":       {"create"},
		"token":        {testToken},
		"workspace_id": {workspace.ID},
	})
	defer client.Close()
	sessionID := readEvent(t, client).string("session_id")
	basePath := workspaceSessionPath(workspace.ID, sessionID)

	writeJSON(t, client, map[string]any{
		"id": "emit", "type": "fake_emit",
		"events": []any{
			map[string]any{"type": "turn_start", "turnIndex": 1},
			map[string]any{"type": "message_update", "assistantMessageEvent": map[string]any{"type": "text_start"}},
			map[string]any{
				"type": "message_end",
				"message": map[string]any{
					"role": "assistant", "provider": "fake", "model": "reasoning-model",
					"stopReason": "stop", "usage": map[string]any{"output": 25},
				},
			},
		},
	})
	for range 4 {
		_ = readEvent(t, client)
	}
	metricsResponse := workspaceAPIRequest(t, server, http.MethodGet, basePath+"/metrics", nil, testToken)
	var metrics sessionMetricsResponse
	decodeHTTPJSON(t, metricsResponse, &metrics)
	if metrics.SessionID != sessionID || metrics.SampleCount != 1 {
		t.Fatalf("metrics = %#v", metrics)
	}

	writeJSON(t, client, map[string]any{
		"id": "start-output", "type": "fake_emit",
		"events": []any{map[string]any{"type": "agent_start"}},
	})
	_ = readEvent(t, client)
	_ = readEvent(t, client)
	blocked := workspaceAPIRequest(t, server, http.MethodDelete, basePath+"/process", nil, testToken)
	if blocked.StatusCode != http.StatusConflict {
		blocked.Body.Close()
		t.Fatalf("outputting stop status = %d, want 409", blocked.StatusCode)
	}
	blocked.Body.Close()

	writeJSON(t, client, map[string]any{
		"id": "settle-output", "type": "fake_emit",
		"events": []any{map[string]any{"type": "agent_settled"}},
	})
	_ = readEvent(t, client)
	_ = readEvent(t, client)
	stopped := workspaceAPIRequest(t, server, http.MethodDelete, basePath+"/process", nil, testToken)
	stopped.Body.Close()
	if stopped.StatusCode != http.StatusNoContent {
		t.Fatalf("settled stop status = %d", stopped.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(dataDir, "sessions", sessionID)); err != nil {
		t.Fatalf("stopped session was not preserved: %v", err)
	}
	repeated := workspaceAPIRequest(t, server, http.MethodDelete, basePath+"/process", nil, testToken)
	repeated.Body.Close()
	if repeated.StatusCode != http.StatusNoContent {
		t.Fatalf("repeated stop status = %d, want 204", repeated.StatusCode)
	}

	attached := dialWebSocket(t, server, url.Values{
		"action":     {"attach"},
		"session_id": {sessionID},
		"token":      {testToken},
	})
	defer attached.Close()
	ready, _, _ := readAttachHistory(t, attached)
	if ready.string("workspace_id") != workspace.ID || ready.string("workspace_directory") != workspace.Directory {
		t.Fatalf("reattached workspace = %#v", ready)
	}
}

func TestWorkspaceAndSessionMetadataSurviveRestart(t *testing.T) {
	dataDir := t.TempDir()
	firstGateway, firstServer := startTestGateway(t, dataDir)
	workspace := createTestWorkspace(t, firstServer, t.TempDir())
	updated := workspaceAPIRequest(
		t,
		firstServer,
		http.MethodPatch,
		"/api/workspaces/"+workspace.ID,
		[]byte(`{
			"name":"Persistent workspace",
			"additional_system_prompt":"Persistent prompt",
			"skill_paths":["/srv/skills/a","/srv/skills/b"],
			"no_skills":true,
			"extension_paths":["/srv/extensions/a.ts"],
			"no_extensions":true
		}`),
		testToken,
	)
	updated.Body.Close()
	client := dialWebSocket(t, firstServer, url.Values{
		"action":       {"create"},
		"token":        {testToken},
		"workspace_id": {workspace.ID},
	})
	sessionID := readEvent(t, client).string("session_id")
	writeJSON(t, client, map[string]any{"id": "name", "type": "set_session_name", "name": "Persistent session"})
	_ = readEvent(t, client)
	client.Close()
	shutdownGateway(t, firstGateway)
	firstServer.Close()

	_, secondServer := startTestGateway(t, dataDir)
	restarted := findWorkspace(t, getWorkspaceList(t, secondServer, 5).Workspaces, workspace.ID)
	if restarted.Name != "Persistent workspace" || restarted.AdditionalSystemPrompt != "Persistent prompt" ||
		fmt.Sprint(restarted.SkillPaths) != fmt.Sprint([]string{"/srv/skills/a", "/srv/skills/b"}) ||
		!restarted.NoSkills ||
		fmt.Sprint(restarted.ExtensionPaths) != fmt.Sprint([]string{"/srv/extensions/a.ts"}) ||
		!restarted.NoExtensions ||
		len(restarted.Sessions) != 1 || restarted.Sessions[0].ID != sessionID || restarted.Sessions[0].Name != "Persistent session" {
		t.Fatalf("restarted workspace = %#v", restarted)
	}
}

func createTestWorkspace(t *testing.T, server *httptest.Server, directory string) workspaceResponse {
	t.Helper()
	body, err := json.Marshal(createWorkspaceRequest{Directory: directory})
	if err != nil {
		t.Fatal(err)
	}
	response := workspaceAPIRequest(t, server, http.MethodPost, "/api/workspaces", body, testToken)
	if response.StatusCode != http.StatusCreated && response.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("create workspace status = %d body = %s", response.StatusCode, data)
	}
	var workspace workspaceResponse
	decodeHTTPJSON(t, response, &workspace)
	return workspace
}

func getWorkspaceList(t *testing.T, server *httptest.Server, sessionLimit int) workspaceListResponse {
	t.Helper()
	response := workspaceAPIRequest(
		t,
		server,
		http.MethodGet,
		fmt.Sprintf("/api/workspaces?session_limit=%d", sessionLimit),
		nil,
		testToken,
	)
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("list workspaces status = %d body = %s", response.StatusCode, body)
	}
	var listed workspaceListResponse
	decodeHTTPJSON(t, response, &listed)
	return listed
}

func findWorkspace(t *testing.T, workspaces []workspaceResponse, id string) workspaceResponse {
	t.Helper()
	for _, workspace := range workspaces {
		if workspace.ID == id {
			return workspace
		}
	}
	t.Fatalf("workspace %q not found in %#v", id, workspaces)
	return workspaceResponse{}
}

func workspaceSessionPath(workspaceID, sessionID string) string {
	return "/api/workspaces/" + workspaceID + "/sessions/" + sessionID
}

func containsArgumentPair(args []string, key, value string) bool {
	for index := 0; index+1 < len(args); index++ {
		if args[index] == key && args[index+1] == value {
			return true
		}
	}
	return false
}

func workspaceAPIRequest(
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
