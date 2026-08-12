package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/yearsyan/oh-pi-app/internal/scheduledtask"
)

func TestScheduledTaskAPICRUDAndManualRun(t *testing.T) {
	app := newScheduledTaskTestGateway(t)
	workspaces, err := app.workspaces.list()
	if err != nil || len(workspaces) != 1 {
		t.Fatalf("default workspaces = %#v, err=%v", workspaces, err)
	}
	workspaceID := workspaces[0].ID
	createBody, _ := json.Marshal(map[string]any{
		"name":         "工作日检查",
		"workspace_id": workspaceID,
		"model":        "fake/reasoning-model",
		"thinking":     "medium",
		"skill_paths":  []string{" .pi/task-skills ", "/srv/task-skills", ".pi/task-skills"},
		"no_skills":    true,
		"prompt":       "运行测试并总结失败。",
		"schedule": map[string]any{
			"kind": "cron", "expression": "0 9 * * 1-5", "timezone": "Asia/Shanghai",
		},
	})
	createdResponse := scheduledTaskRequest(app, http.MethodPost, "/api/tasks", createBody)
	if createdResponse.Code != http.StatusCreated {
		t.Fatalf("create status=%d body=%s", createdResponse.Code, createdResponse.Body.String())
	}
	var created scheduledtask.Task
	decodeRecorderJSON(t, createdResponse, &created)
	if created.ID == "" || !created.Enabled || created.NextRunAt == nil || created.WorkspaceID != workspaceID ||
		fmt.Sprint(created.SkillPaths) != fmt.Sprint([]string{".pi/task-skills", "/srv/task-skills"}) ||
		!created.NoSkills {
		t.Fatalf("created task = %#v", created)
	}

	listedResponse := scheduledTaskRequest(app, http.MethodGet, "/api/tasks", nil)
	var listed scheduledTaskListResponse
	decodeRecorderJSON(t, listedResponse, &listed)
	if len(listed.Tasks) != 1 || listed.Tasks[0].ID != created.ID {
		t.Fatalf("listed tasks = %#v", listed.Tasks)
	}

	disabledResponse := scheduledTaskRequest(
		app,
		http.MethodPatch,
		"/api/tasks/"+created.ID,
		[]byte(`{"enabled":false}`),
	)
	if disabledResponse.Code != http.StatusOK {
		t.Fatalf("disable status=%d body=%s", disabledResponse.Code, disabledResponse.Body.String())
	}
	var disabled scheduledtask.Task
	decodeRecorderJSON(t, disabledResponse, &disabled)
	if disabled.Enabled || disabled.NextRunAt != nil ||
		fmt.Sprint(disabled.SkillPaths) != fmt.Sprint(created.SkillPaths) || !disabled.NoSkills {
		t.Fatalf("disabled task = %#v", disabled)
	}

	runResponse := scheduledTaskRequest(app, http.MethodPost, "/api/tasks/"+created.ID+"/run", nil)
	if runResponse.Code != http.StatusAccepted {
		t.Fatalf("run status=%d body=%s", runResponse.Code, runResponse.Body.String())
	}
	waitFor(t, 3*time.Second, func() bool {
		task, getErr := app.scheduledTasks.Get(created.ID)
		return getErr == nil && task.CurrentRun == nil && task.LastRun != nil && task.LastRun.Status == scheduledtask.RunSucceeded
	})
	completed, err := app.scheduledTasks.Get(created.ID)
	if err != nil {
		t.Fatal(err)
	}
	if completed.LastRun == nil || completed.LastRun.SessionID == "" {
		t.Fatalf("completed run = %#v", completed.LastRun)
	}
	meta, _, err := app.manager.store.load(completed.LastRun.SessionID)
	if err != nil {
		t.Fatal(err)
	}
	if meta.Source != sessionSourceScheduledTask || meta.ScheduledTaskID != created.ID {
		t.Fatalf("scheduled run session association = (%q, %q)", meta.Source, meta.ScheduledTaskID)
	}
	if fmt.Sprint(meta.SkillPaths) != fmt.Sprint(created.SkillPaths) || !meta.NoSkills {
		t.Fatalf("scheduled run session skills = %q, no_skills=%v", meta.SkillPaths, meta.NoSkills)
	}
	attached, err := app.manager.attach(completed.LastRun.SessionID)
	if err != nil {
		t.Fatal(err)
	}
	if !hasArgument(attached.args, "--no-skills") ||
		!containsArgumentPair(attached.args, "--skill", ".pi/task-skills") ||
		!containsArgumentPair(attached.args, "--skill", "/srv/task-skills") {
		t.Fatalf("reattached scheduled session args = %q", attached.args)
	}
	attached.stop(1000, "test complete")

	historicalWorkspace, _, err := app.workspaces.ensure(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	historicalWorkspaceName := "Historical workspace"
	historicalWorkspace, err = app.workspaces.update(
		historicalWorkspace.ID,
		workspaceMetadataUpdate{Name: &historicalWorkspaceName},
	)
	if err != nil {
		t.Fatal(err)
	}
	additional, _, err := app.manager.store.createForScheduledTask(
		historicalWorkspace.ID,
		created.ID,
		nil,
		false,
	)
	if err != nil {
		t.Fatal(err)
	}
	if err := app.workspaces.delete(historicalWorkspace.ID); err != nil {
		t.Fatal(err)
	}
	unrelatedTaskID, err := newSessionID()
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := app.manager.store.createForScheduledTask(
		workspaceID,
		unrelatedTaskID,
		nil,
		false,
	); err != nil {
		t.Fatal(err)
	}

	firstPageResponse := scheduledTaskRequest(
		app,
		http.MethodGet,
		"/api/tasks/"+created.ID+"/sessions?limit=1",
		nil,
	)
	if firstPageResponse.Code != http.StatusOK {
		t.Fatalf("task sessions status=%d body=%s", firstPageResponse.Code, firstPageResponse.Body.String())
	}
	var firstPage scheduledTaskSessionPageResponse
	decodeRecorderJSON(t, firstPageResponse, &firstPage)
	if firstPage.TaskID != created.ID || firstPage.SessionCount != 2 ||
		len(firstPage.Sessions) != 1 || firstPage.NextCursor == "" {
		t.Fatalf("first task session page = %#v", firstPage)
	}
	firstSession := firstPage.Sessions[0]
	if firstSession.ID != additional.ID || firstSession.ScheduledTaskID != created.ID ||
		firstSession.WorkspaceID != historicalWorkspace.ID ||
		firstSession.WorkspaceDirectory != historicalWorkspace.Directory ||
		firstSession.WorkspaceName != historicalWorkspaceName || !firstSession.WorkspaceDeleted {
		t.Fatalf("first task session = %#v", firstSession)
	}

	secondPageResponse := scheduledTaskRequest(
		app,
		http.MethodGet,
		"/api/tasks/"+created.ID+"/sessions?limit=1&cursor="+firstPage.NextCursor,
		nil,
	)
	var secondPage scheduledTaskSessionPageResponse
	decodeRecorderJSON(t, secondPageResponse, &secondPage)
	if len(secondPage.Sessions) != 1 || secondPage.Sessions[0].ID != completed.LastRun.SessionID ||
		secondPage.NextCursor != "" {
		t.Fatalf("second task session page = %#v", secondPage)
	}

	deletedResponse := scheduledTaskRequest(app, http.MethodDelete, "/api/tasks/"+created.ID, nil)
	if deletedResponse.Code != http.StatusNoContent {
		t.Fatalf("delete status=%d body=%s", deletedResponse.Code, deletedResponse.Body.String())
	}
}

func TestScheduledTaskInitialSessionConfigAddsTaskSkills(t *testing.T) {
	initial, err := scheduledTaskInitialSessionConfig(scheduledtask.Task{
		Model:      "fake/reasoning-model",
		Thinking:   "high",
		SkillPaths: []string{"skills/workspace", "skills/task", "/srv/task-skills"},
		NoSkills:   true,
	})
	if err != nil {
		t.Fatal(err)
	}
	args := initial.args([]string{"skills/workspace"}, false)
	if !containsArgumentPair(args, "--model", "fake/reasoning-model") ||
		!containsArgumentPair(args, "--thinking", "high") ||
		!hasArgument(args, "--no-skills") ||
		!containsArgumentPair(args, "--skill", "skills/task") ||
		!containsArgumentPair(args, "--skill", "/srv/task-skills") ||
		containsArgumentPair(args, "--skill", "skills/workspace") {
		t.Fatalf("scheduled task args = %q", args)
	}
}

func TestScheduledTaskAPIRejectsSixFieldCron(t *testing.T) {
	app := newScheduledTaskTestGateway(t)
	workspaces, err := app.workspaces.list()
	if err != nil || len(workspaces) != 1 {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{
		"name": "invalid", "workspace_id": workspaces[0].ID, "prompt": "test",
		"schedule": map[string]any{
			"kind": "cron", "expression": "0 0 9 * * 1-5", "timezone": "Asia/Shanghai",
		},
	})
	response := scheduledTaskRequest(app, http.MethodPost, "/api/tasks", body)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestDeleteWorkspacePausesScheduledTasks(t *testing.T) {
	app := newScheduledTaskTestGateway(t)
	workspaces, err := app.workspaces.list()
	if err != nil || len(workspaces) != 1 {
		t.Fatalf("default workspaces = %#v, err=%v", workspaces, err)
	}
	body, _ := json.Marshal(map[string]any{
		"name": "workspace task", "workspace_id": workspaces[0].ID, "prompt": "check",
		"schedule": map[string]any{
			"kind": "cron", "expression": "0 9 * * *", "timezone": "UTC",
		},
	})
	createdResponse := scheduledTaskRequest(app, http.MethodPost, "/api/tasks", body)
	if createdResponse.Code != http.StatusCreated {
		t.Fatalf("create status=%d body=%s", createdResponse.Code, createdResponse.Body.String())
	}
	var task scheduledtask.Task
	decodeRecorderJSON(t, createdResponse, &task)

	deletedResponse := scheduledTaskRequest(
		app,
		http.MethodDelete,
		"/api/workspaces/"+workspaces[0].ID,
		nil,
	)
	if deletedResponse.Code != http.StatusNoContent {
		t.Fatalf("delete workspace status=%d body=%s", deletedResponse.Code, deletedResponse.Body.String())
	}
	paused, err := app.scheduledTasks.Get(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if paused.Enabled || paused.NextRunAt != nil {
		t.Fatalf("task was not paused: %#v", paused)
	}
}

func newScheduledTaskTestGateway(t *testing.T) *Gateway {
	t.Helper()
	app, err := New(Config{
		Token:           testToken,
		DataDir:         t.TempDir(),
		WorkDir:         t.TempDir(),
		PiCommand:       os.Args[0],
		PiArgs:          []string{"-test.run=TestPiHelperProcess", "--"},
		ProviderPiArgs:  []string{"-test.run=TestPiHelperProcess", "--"},
		MaxMessageBytes: 1 << 20,
		WriteTimeout:    250 * time.Millisecond,
		PongTimeout:     2 * time.Second,
		Logger:          slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatalf("create gateway: %v", err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := app.Shutdown(ctx); err != nil {
			t.Errorf("shutdown gateway: %v", err)
		}
	})
	return app
}

func scheduledTaskRequest(app *Gateway, method, path string, body []byte) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+testToken)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response := httptest.NewRecorder()
	app.Handler().ServeHTTP(response, request)
	return response
}

func decodeRecorderJSON(t *testing.T, response *httptest.ResponseRecorder, target any) {
	t.Helper()
	if err := json.Unmarshal(response.Body.Bytes(), target); err != nil {
		t.Fatalf("decode response: %v body=%s", err, response.Body.String())
	}
}
