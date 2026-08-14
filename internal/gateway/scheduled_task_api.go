package gateway

import (
	"encoding/json"
	"errors"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/yearsyan/oh-pi-app/internal/scheduledtask"
)

const (
	scheduledTaskRequestLimit      = 256 << 10
	scheduledTaskEventRequestLimit = 256 << 10
	scheduledTaskEventMaximumDelay = 24 * time.Hour
)

type scheduledTaskListResponse struct {
	Tasks []scheduledtask.Task `json:"tasks"`
}

type scheduledTaskSessionPageResponse struct {
	TaskID       string            `json:"task_id"`
	SessionCount int               `json:"session_count"`
	Sessions     []sessionResponse `json:"sessions"`
	NextCursor   string            `json:"next_cursor,omitempty"`
}

type scheduledTaskEventResponse struct {
	Status string `json:"status"`
	RunID  string `json:"run_id"`
}

type scheduledTaskMutationRequest struct {
	Name        *string                 `json:"name"`
	WorkspaceID *string                 `json:"workspace_id"`
	Model       *string                 `json:"model"`
	Thinking    *string                 `json:"thinking"`
	SkillPaths  *[]string               `json:"skill_paths"`
	NoSkills    *bool                   `json:"no_skills"`
	Prompt      *string                 `json:"prompt"`
	Schedule    *scheduledtask.Schedule `json:"schedule"`
	Enabled     *bool                   `json:"enabled"`
}

func (g *Gateway) handleScheduledTasks(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet && request.Method != http.MethodPost {
		writer.Header().Set("Allow", strings.Join([]string{http.MethodGet, http.MethodPost}, ", "))
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET and POST are allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	if request.Method == http.MethodGet {
		tasks, err := g.scheduledTasks.List()
		if err != nil {
			g.cfg.Logger.Error("list scheduled tasks", "error", err)
			writeHTTPError(writer, http.StatusInternalServerError, "scheduled_task_list_failed", "could not list scheduled tasks")
			return
		}
		writeJSONResponse(writer, http.StatusOK, scheduledTaskListResponse{Tasks: tasks})
		return
	}

	mutation, ok := decodeScheduledTaskMutation(writer, request)
	if !ok {
		return
	}
	definition, err := mutation.definition(nil)
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_scheduled_task", err.Error())
		return
	}
	if !g.validateScheduledTaskDefinition(writer, definition) {
		return
	}
	task, err := g.scheduledTasks.Create(definition)
	if err != nil {
		if errors.Is(err, scheduledtask.ErrNoFutureOccurrence) {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_scheduled_task", err.Error())
		} else {
			g.cfg.Logger.Error("create scheduled task", "error", err)
			writeHTTPError(writer, http.StatusInternalServerError, "scheduled_task_create_failed", "could not create scheduled task")
		}
		return
	}
	writeJSONResponse(writer, http.StatusCreated, task)
}

func (g *Gateway) handleScheduledTask(writer http.ResponseWriter, request *http.Request) {
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	tail := strings.Trim(strings.TrimPrefix(request.URL.Path, "/api/tasks/"), "/")
	parts := strings.Split(tail, "/")
	if len(parts) == 0 || parts[0] == "" {
		writeHTTPError(writer, http.StatusNotFound, "scheduled_task_not_found", "scheduled task does not exist")
		return
	}
	taskID := parts[0]
	if len(parts) == 2 && parts[1] == "run" {
		g.handleRunScheduledTask(writer, request, taskID)
		return
	}
	if len(parts) == 2 && parts[1] == "sessions" {
		g.handleScheduledTaskSessions(writer, request, taskID)
		return
	}
	if len(parts) != 1 {
		writeHTTPError(writer, http.StatusNotFound, "not_found", "endpoint does not exist")
		return
	}
	if request.Method != http.MethodGet && request.Method != http.MethodPatch && request.Method != http.MethodDelete {
		writer.Header().Set("Allow", strings.Join([]string{http.MethodGet, http.MethodPatch, http.MethodDelete}, ", "))
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET, PATCH, and DELETE are allowed")
		return
	}
	task, err := g.scheduledTasks.Get(taskID)
	if !g.writeScheduledTaskError(writer, "get", taskID, err) {
		return
	}
	switch request.Method {
	case http.MethodGet:
		writeJSONResponse(writer, http.StatusOK, task)

	case http.MethodPatch:
		mutation, ok := decodeScheduledTaskMutation(writer, request)
		if !ok {
			return
		}
		if mutation.empty() {
			writeHTTPError(writer, http.StatusBadRequest, "empty_update", "at least one scheduled task field is required")
			return
		}
		definition, err := mutation.definition(&task)
		if err != nil {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_scheduled_task", err.Error())
			return
		}
		if !g.validateScheduledTaskDefinition(writer, definition) {
			return
		}
		updated, err := g.scheduledTasks.Update(taskID, definition)
		if !g.writeScheduledTaskError(writer, "update", taskID, err) {
			return
		}
		writeJSONResponse(writer, http.StatusOK, updated)

	case http.MethodDelete:
		if err := g.scheduledTasks.Delete(taskID); !g.writeScheduledTaskError(writer, "delete", taskID, err) {
			return
		}
		writer.Header().Set("Cache-Control", "no-store")
		writer.WriteHeader(http.StatusNoContent)
	}
}

func (g *Gateway) handleScheduledTaskEvent(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodPost {
		writer.Header().Set("Allow", http.MethodPost)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only POST is allowed")
		return
	}
	eventKey := strings.TrimPrefix(request.URL.Path, "/api/task-events/")
	if len(eventKey) != 32 || strings.ContainsRune(eventKey, '/') {
		writeHTTPError(writer, http.StatusNotFound, "scheduled_task_event_not_found", "event trigger does not exist")
		return
	}
	delay, ok := decodeScheduledTaskEventDelay(writer, request)
	if !ok {
		return
	}
	eventData, ok := decodeScheduledTaskEvent(writer, request)
	if !ok {
		return
	}
	_, run, err := g.scheduledTasks.TriggerEvent(eventKey, eventData, delay)
	switch {
	case err == nil:
		writer.Header().Set("Cache-Control", "no-store")
		writeJSONResponse(writer, http.StatusAccepted, scheduledTaskEventResponse{
			Status: "accepted",
			RunID:  run.ID,
		})
	case errors.Is(err, scheduledtask.ErrNotFound):
		writeHTTPError(writer, http.StatusNotFound, "scheduled_task_event_not_found", "event trigger does not exist")
	case errors.Is(err, scheduledtask.ErrDisabled):
		writeHTTPError(writer, http.StatusConflict, "scheduled_task_disabled", "scheduled task is disabled")
	case errors.Is(err, scheduledtask.ErrRunning):
		writeHTTPError(writer, http.StatusConflict, "scheduled_task_running", "scheduled task is already running")
	default:
		// event_key is a bearer secret and must never be included in logs.
		g.cfg.Logger.Error("trigger scheduled task event", "error", err)
		writeHTTPError(
			writer,
			http.StatusInternalServerError,
			"scheduled_task_event_failed",
			"could not trigger scheduled task",
		)
	}
}

func decodeScheduledTaskEventDelay(writer http.ResponseWriter, request *http.Request) (time.Duration, bool) {
	values, present := request.URL.Query()["delay"]
	if !present {
		return 0, true
	}
	maximumMilliseconds := int64(scheduledTaskEventMaximumDelay / time.Millisecond)
	if len(values) != 1 || values[0] == "" {
		writeHTTPError(
			writer,
			http.StatusBadRequest,
			"invalid_event_delay",
			"delay must be an integer between 0 and "+strconv.FormatInt(maximumMilliseconds, 10)+" milliseconds",
		)
		return 0, false
	}
	milliseconds, err := strconv.ParseInt(values[0], 10, 64)
	if err != nil || milliseconds < 0 || milliseconds > maximumMilliseconds {
		writeHTTPError(
			writer,
			http.StatusBadRequest,
			"invalid_event_delay",
			"delay must be an integer between 0 and "+strconv.FormatInt(maximumMilliseconds, 10)+" milliseconds",
		)
		return 0, false
	}
	return time.Duration(milliseconds) * time.Millisecond, true
}

func decodeScheduledTaskEvent(writer http.ResponseWriter, request *http.Request) (string, bool) {
	mediaType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		writeHTTPError(
			writer,
			http.StatusUnsupportedMediaType,
			"json_body_required",
			"Content-Type must be application/json",
		)
		return "", false
	}
	request.Body = http.MaxBytesReader(writer, request.Body, scheduledTaskEventRequestLimit)
	decoder := json.NewDecoder(request.Body)
	decoder.UseNumber()
	var payload any
	if err := decoder.Decode(&payload); err != nil || ensureJSONEOF(decoder) != nil {
		writeHTTPError(
			writer,
			http.StatusBadRequest,
			"invalid_event_data",
			"body must contain exactly one JSON value",
		)
		return "", false
	}
	canonical, err := json.Marshal(payload)
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_event_data", "body must be valid JSON")
		return "", false
	}
	return string(canonical), true
}

func (g *Gateway) handleScheduledTaskSessions(
	writer http.ResponseWriter,
	request *http.Request,
	taskID string,
) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if _, err := g.scheduledTasks.Get(taskID); !g.writeScheduledTaskError(writer, "get", taskID, err) {
		return
	}
	limit, err := parsePageLimit(request.URL.Query().Get("limit"), defaultWorkspaceSessionPage)
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_limit", err.Error())
		return
	}
	offset, err := decodeSessionCursor(request.URL.Query().Get("cursor"))
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_cursor", "cursor is invalid")
		return
	}
	sessions, err := g.manager.listScheduledTask(taskID)
	if err != nil {
		g.cfg.Logger.Error("list scheduled task sessions", "task_id", taskID, "error", err)
		writeHTTPError(
			writer,
			http.StatusInternalServerError,
			"scheduled_task_session_list_failed",
			"could not list scheduled task sessions",
		)
		return
	}
	if offset > len(sessions) {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_cursor", "cursor is beyond the session list")
		return
	}
	end := min(offset+limit, len(sessions))
	responses := makeSessionResponses(sessions[offset:end])
	for index := range responses {
		meta := sessions[offset+index].Metadata
		responses[index].WorkspaceID = meta.WorkspaceID
		workspace, loadErr := g.workspaces.loadIncludingDeleted(meta.WorkspaceID)
		switch {
		case loadErr == nil:
			responses[index].WorkspaceDirectory = workspace.Directory
			responses[index].WorkspaceName = workspace.Name
			responses[index].WorkspaceDeleted = workspace.Deleted
		case errors.Is(loadErr, errWorkspaceNotFound):
			responses[index].WorkspaceDeleted = true
		default:
			g.cfg.Logger.Error(
				"load scheduled task session workspace",
				"task_id", taskID,
				"workspace_id", meta.WorkspaceID,
				"error", loadErr,
			)
			writeHTTPError(
				writer,
				http.StatusInternalServerError,
				"scheduled_task_session_list_failed",
				"could not list scheduled task sessions",
			)
			return
		}
	}
	response := scheduledTaskSessionPageResponse{
		TaskID:       taskID,
		SessionCount: len(sessions),
		Sessions:     responses,
	}
	if end < len(sessions) {
		response.NextCursor = encodeSessionCursor(end)
	}
	writeJSONResponse(writer, http.StatusOK, response)
}

func (g *Gateway) handleRunScheduledTask(writer http.ResponseWriter, request *http.Request, taskID string) {
	if request.Method != http.MethodPost {
		writer.Header().Set("Allow", http.MethodPost)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only POST is allowed")
		return
	}
	task, err := g.scheduledTasks.Get(taskID)
	if !g.writeScheduledTaskError(writer, "get", taskID, err) {
		return
	}
	if _, err := g.workspaces.load(task.WorkspaceID); err != nil {
		g.writeWorkspaceError(writer, err)
		return
	}
	claimed, _, err := g.scheduledTasks.RunNow(taskID)
	if !g.writeScheduledTaskError(writer, "run", taskID, err) {
		return
	}
	writeJSONResponse(writer, http.StatusAccepted, claimed)
}

func decodeScheduledTaskMutation(writer http.ResponseWriter, request *http.Request) (scheduledTaskMutationRequest, bool) {
	request.Body = http.MaxBytesReader(writer, request.Body, scheduledTaskRequestLimit)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var mutation scheduledTaskMutationRequest
	if err := decoder.Decode(&mutation); err != nil || ensureJSONEOF(decoder) != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_request", "body must be one scheduled task JSON object")
		return scheduledTaskMutationRequest{}, false
	}
	return mutation, true
}

func (mutation scheduledTaskMutationRequest) definition(existing *scheduledtask.Task) (scheduledtask.Definition, error) {
	definition := scheduledtask.Definition{Enabled: true}
	if existing != nil {
		definition = existing.Definition()
	}
	if mutation.Name != nil {
		definition.Name = *mutation.Name
	}
	if mutation.WorkspaceID != nil {
		definition.WorkspaceID = *mutation.WorkspaceID
	}
	if mutation.Model != nil {
		definition.Model = *mutation.Model
	}
	if mutation.Thinking != nil {
		definition.Thinking = *mutation.Thinking
	}
	if mutation.SkillPaths != nil {
		definition.SkillPaths = append([]string(nil), (*mutation.SkillPaths)...)
	}
	if mutation.NoSkills != nil {
		definition.NoSkills = *mutation.NoSkills
	}
	if mutation.Prompt != nil {
		definition.Prompt = *mutation.Prompt
	}
	if mutation.Schedule != nil {
		definition.Schedule = *mutation.Schedule
	}
	if mutation.Enabled != nil {
		definition.Enabled = *mutation.Enabled
	}
	if err := definition.Validate(); err != nil {
		return scheduledtask.Definition{}, err
	}
	return definition, nil
}

func (mutation scheduledTaskMutationRequest) empty() bool {
	return mutation.Name == nil && mutation.WorkspaceID == nil && mutation.Model == nil &&
		mutation.Thinking == nil && mutation.SkillPaths == nil && mutation.NoSkills == nil &&
		mutation.Prompt == nil && mutation.Schedule == nil && mutation.Enabled == nil
}

func (g *Gateway) validateScheduledTaskDefinition(writer http.ResponseWriter, definition scheduledtask.Definition) bool {
	if _, err := g.workspaces.load(definition.WorkspaceID); err != nil {
		g.writeWorkspaceError(writer, err)
		return false
	}
	if _, err := parseInitialSessionConfig(definition.Model, definition.Thinking); err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_scheduled_task_model", err.Error())
		return false
	}
	return true
}

func (g *Gateway) writeScheduledTaskError(writer http.ResponseWriter, operation, id string, err error) bool {
	if err == nil {
		return true
	}
	switch {
	case errors.Is(err, scheduledtask.ErrNotFound):
		writeHTTPError(writer, http.StatusNotFound, "scheduled_task_not_found", "scheduled task does not exist")
	case errors.Is(err, scheduledtask.ErrRunning):
		writeHTTPError(writer, http.StatusConflict, "scheduled_task_running", "scheduled task is already running")
	case errors.Is(err, scheduledtask.ErrNoFutureOccurrence):
		writeHTTPError(writer, http.StatusBadRequest, "invalid_scheduled_task", err.Error())
	default:
		g.cfg.Logger.Error(operation+" scheduled task", "task_id", id, "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "scheduled_task_"+operation+"_failed", "could not "+operation+" scheduled task")
	}
	return false
}
