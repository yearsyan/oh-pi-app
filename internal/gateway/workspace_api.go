package gateway

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
)

const (
	defaultWorkspaceSessionPreview = 5
	defaultWorkspaceSessionPage    = 20
	maxWorkspaceSessionPage        = 100
)

type sessionResponse struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	CreatedAt  int64  `json:"created_at"`
	LastActive int64  `json:"last_active"`
	Running    bool   `json:"running"`
	Outputting bool   `json:"outputting"`
}

type workspaceResponse struct {
	ID                     string            `json:"id"`
	Directory              string            `json:"directory"`
	Name                   string            `json:"name"`
	AdditionalSystemPrompt string            `json:"additional_system_prompt"`
	Technology             string            `json:"technology"`
	Technologies           []string          `json:"technologies"`
	SessionCount           int               `json:"session_count"`
	Sessions               []sessionResponse `json:"sessions"`
	NextCursor             string            `json:"next_cursor,omitempty"`
	CreatedAt              int64             `json:"created_at"`
	UpdatedAt              int64             `json:"updated_at"`
}

type workspaceListResponse struct {
	Workspaces []workspaceResponse `json:"workspaces"`
}

type workspaceSessionPageResponse struct {
	WorkspaceID string            `json:"workspace_id"`
	Sessions    []sessionResponse `json:"sessions"`
	NextCursor  string            `json:"next_cursor,omitempty"`
}

type createWorkspaceRequest struct {
	Directory string `json:"directory"`
}

type updateWorkspaceRequest struct {
	Name                   *string `json:"name"`
	AdditionalSystemPrompt *string `json:"additional_system_prompt"`
}

type updateSessionRequest struct {
	Name *string `json:"name"`
}

func (g *Gateway) handleWorkspaces(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet && request.Method != http.MethodPost {
		writer.Header().Set("Allow", strings.Join([]string{http.MethodGet, http.MethodPost}, ", "))
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET and POST are allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	if request.Method == http.MethodPost {
		g.handleCreateWorkspace(writer, request)
		return
	}

	limit, err := parsePageLimit(request.URL.Query().Get("session_limit"), defaultWorkspaceSessionPreview)
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_session_limit", err.Error())
		return
	}
	workspaces, err := g.workspaces.list()
	if err != nil {
		g.cfg.Logger.Error("list workspaces", "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "workspace_list_failed", "could not list workspaces")
		return
	}
	allSessions, err := g.manager.list()
	if err != nil {
		g.cfg.Logger.Error("list workspace sessions", "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "workspace_list_failed", "could not list workspace sessions")
		return
	}
	byWorkspace := make(map[string][]managedSession, len(workspaces))
	for _, session := range allSessions {
		byWorkspace[session.Metadata.WorkspaceID] = append(byWorkspace[session.Metadata.WorkspaceID], session)
	}

	responses := make([]workspaceResponse, 0, len(workspaces))
	for _, workspace := range workspaces {
		responses = append(responses, makeWorkspaceResponse(workspace, byWorkspace[workspace.ID], limit))
	}
	sort.SliceStable(responses, func(i, j int) bool {
		leftActive := workspaceResponseActive(responses[i])
		rightActive := workspaceResponseActive(responses[j])
		if leftActive != rightActive {
			return leftActive
		}
		leftActivity := workspaceResponseLastActivity(responses[i])
		rightActivity := workspaceResponseLastActivity(responses[j])
		if leftActivity != rightActivity {
			return leftActivity > rightActivity
		}
		return responses[i].Directory < responses[j].Directory
	})
	writeJSONResponse(writer, http.StatusOK, workspaceListResponse{Workspaces: responses})
}

func (g *Gateway) handleCreateWorkspace(writer http.ResponseWriter, request *http.Request) {
	request.Body = http.MaxBytesReader(writer, request.Body, 8<<10)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var create createWorkspaceRequest
	if err := decoder.Decode(&create); err != nil || ensureJSONEOF(decoder) != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_request", "body must be one JSON object with a directory field")
		return
	}
	workspace, created, err := g.workspaces.ensure(create.Directory)
	if err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_directory", err.Error())
		return
	}
	sessions, err := g.manager.listWorkspace(workspace.ID)
	if err != nil {
		g.cfg.Logger.Error("list created workspace sessions", "workspace_id", workspace.ID, "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "workspace_sessions_failed", "could not list workspace sessions")
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSONResponse(writer, status, makeWorkspaceResponse(workspace, sessions, defaultWorkspaceSessionPreview))
}

func (g *Gateway) handleWorkspace(writer http.ResponseWriter, request *http.Request) {
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}
	tail := strings.Trim(strings.TrimPrefix(request.URL.Path, "/api/workspaces/"), "/")
	parts := strings.Split(tail, "/")
	if len(parts) == 0 || !validSessionID(parts[0]) {
		writeHTTPError(writer, http.StatusNotFound, "workspace_not_found", "workspace does not exist")
		return
	}
	workspaceID := parts[0]
	switch {
	case len(parts) == 1:
		g.handleWorkspaceMetadata(writer, request, workspaceID)
	case len(parts) == 2 && parts[1] == "capabilities":
		g.handleWorkspaceCapabilities(writer, request, workspaceID)
	case len(parts) == 2 && parts[1] == "sessions":
		g.handleWorkspaceSessions(writer, request, workspaceID)
	case len(parts) == 3 && parts[1] == "sessions":
		g.handleWorkspaceSession(writer, request, workspaceID, parts[2])
	case len(parts) == 4 && parts[1] == "sessions" && parts[3] == "process":
		g.handleWorkspaceSessionProcess(writer, request, workspaceID, parts[2])
	case len(parts) == 4 && parts[1] == "sessions" && parts[3] == "metrics":
		g.handleWorkspaceSessionMetrics(writer, request, workspaceID, parts[2])
	default:
		writeHTTPError(writer, http.StatusNotFound, "not_found", "endpoint does not exist")
	}
}

func (g *Gateway) handleWorkspaceMetadata(writer http.ResponseWriter, request *http.Request, workspaceID string) {
	if request.Method != http.MethodGet && request.Method != http.MethodPatch {
		writer.Header().Set("Allow", strings.Join([]string{http.MethodGet, http.MethodPatch}, ", "))
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET and PATCH are allowed")
		return
	}
	workspace, err := g.workspaces.load(workspaceID)
	if err != nil {
		g.writeWorkspaceError(writer, err)
		return
	}
	if request.Method == http.MethodPatch {
		request.Body = http.MaxBytesReader(writer, request.Body, maxWorkspaceSystemPromptSize+(8<<10))
		decoder := json.NewDecoder(request.Body)
		decoder.DisallowUnknownFields()
		var update updateWorkspaceRequest
		if err := decoder.Decode(&update); err != nil || ensureJSONEOF(decoder) != nil {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_request", "body must contain workspace metadata fields")
			return
		}
		if update.Name == nil && update.AdditionalSystemPrompt == nil {
			writeHTTPError(writer, http.StatusBadRequest, "empty_update", "at least one workspace metadata field is required")
			return
		}
		workspace, err = g.workspaces.update(workspaceID, update.Name, update.AdditionalSystemPrompt)
		if err != nil {
			if errors.Is(err, errWorkspaceNotFound) {
				g.writeWorkspaceError(writer, err)
				return
			}
			writeHTTPError(writer, http.StatusBadRequest, "invalid_workspace_metadata", err.Error())
			return
		}
	}
	sessions, err := g.manager.listWorkspace(workspace.ID)
	if err != nil {
		g.cfg.Logger.Error("list workspace sessions", "workspace_id", workspace.ID, "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "workspace_sessions_failed", "could not list workspace sessions")
		return
	}
	writeJSONResponse(writer, http.StatusOK, makeWorkspaceResponse(workspace, sessions, defaultWorkspaceSessionPreview))
}

func (g *Gateway) handleWorkspaceSessions(writer http.ResponseWriter, request *http.Request, workspaceID string) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
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
	sessions, err := g.manager.listWorkspace(workspaceID)
	if err != nil {
		if errors.Is(err, errWorkspaceNotFound) {
			g.writeWorkspaceError(writer, err)
			return
		}
		g.cfg.Logger.Error("list paged workspace sessions", "workspace_id", workspaceID, "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "workspace_sessions_failed", "could not list workspace sessions")
		return
	}
	if offset > len(sessions) {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_cursor", "cursor is beyond the session list")
		return
	}
	end := min(offset+limit, len(sessions))
	response := workspaceSessionPageResponse{
		WorkspaceID: workspaceID,
		Sessions:    makeSessionResponses(sessions[offset:end]),
	}
	if end < len(sessions) {
		response.NextCursor = encodeSessionCursor(end)
	}
	writeJSONResponse(writer, http.StatusOK, response)
}

func (g *Gateway) handleWorkspaceSession(
	writer http.ResponseWriter,
	request *http.Request,
	workspaceID,
	sessionID string,
) {
	if request.Method != http.MethodGet && request.Method != http.MethodPatch && request.Method != http.MethodDelete {
		writer.Header().Set("Allow", strings.Join([]string{http.MethodGet, http.MethodPatch, http.MethodDelete}, ", "))
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET, PATCH, and DELETE are allowed")
		return
	}
	if !validSessionID(sessionID) {
		writeHTTPError(writer, http.StatusNotFound, "session_not_found", "session does not exist")
		return
	}
	session, err := g.manager.getInWorkspace(workspaceID, sessionID)
	if !g.writeSessionManagerError(writer, "get", sessionID, err) {
		return
	}
	switch request.Method {
	case http.MethodGet:
		writeJSONResponse(writer, http.StatusOK, makeSessionResponse(session))
	case http.MethodPatch:
		request.Body = http.MaxBytesReader(writer, request.Body, 8<<10)
		decoder := json.NewDecoder(request.Body)
		decoder.DisallowUnknownFields()
		var update updateSessionRequest
		if err := decoder.Decode(&update); err != nil || ensureJSONEOF(decoder) != nil {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_request", "body must be one JSON object with a name field")
			return
		}
		if update.Name == nil {
			writeHTTPError(writer, http.StatusBadRequest, "missing_name", "name is required")
			return
		}
		name, err := normalizeSessionName(*update.Name)
		if err != nil {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_name", err.Error())
			return
		}
		session, err = g.manager.rename(sessionID, name, true)
		if !g.writeSessionManagerError(writer, "rename", sessionID, err) {
			return
		}
		writeJSONResponse(writer, http.StatusOK, makeSessionResponse(session))
	case http.MethodDelete:
		ctx, cancel := context.WithTimeout(request.Context(), g.cfg.HistoryTimeout)
		err := g.manager.delete(ctx, sessionID)
		cancel()
		if !g.writeSessionManagerError(writer, "delete", sessionID, err) {
			return
		}
		writer.Header().Set("Cache-Control", "no-store")
		writer.WriteHeader(http.StatusNoContent)
	}
}

func (g *Gateway) handleWorkspaceSessionProcess(
	writer http.ResponseWriter,
	request *http.Request,
	workspaceID,
	sessionID string,
) {
	if request.Method != http.MethodDelete {
		writer.Header().Set("Allow", http.MethodDelete)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only DELETE is allowed")
		return
	}
	if _, err := g.manager.getInWorkspace(workspaceID, sessionID); !g.writeSessionManagerError(writer, "get", sessionID, err) {
		return
	}
	ctx, cancel := context.WithTimeout(request.Context(), g.cfg.HistoryTimeout)
	err := g.manager.stop(ctx, sessionID)
	cancel()
	if !g.writeSessionManagerError(writer, "stop", sessionID, err) {
		return
	}
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(http.StatusNoContent)
}

func (g *Gateway) handleWorkspaceSessionMetrics(
	writer http.ResponseWriter,
	request *http.Request,
	workspaceID,
	sessionID string,
) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if _, err := g.manager.getInWorkspace(workspaceID, sessionID); !g.writeSessionManagerError(writer, "get", sessionID, err) {
		return
	}
	metrics, err := g.manager.metrics(sessionID)
	if err != nil {
		g.cfg.Logger.Error("get session metrics", "workspace_id", workspaceID, "session_id", sessionID, "error", err)
		writeHTTPError(writer, http.StatusInternalServerError, "session_metrics_failed", "could not load session metrics")
		return
	}
	writeJSONResponse(writer, http.StatusOK, metrics)
}

func (g *Gateway) writeWorkspaceError(writer http.ResponseWriter, err error) {
	if errors.Is(err, errWorkspaceNotFound) {
		writeHTTPError(writer, http.StatusNotFound, "workspace_not_found", "workspace does not exist")
		return
	}
	g.cfg.Logger.Error("workspace operation failed", "error", err)
	writeHTTPError(writer, http.StatusInternalServerError, "workspace_operation_failed", "workspace operation failed")
}

func (g *Gateway) writeSessionManagerError(writer http.ResponseWriter, operation, id string, err error) bool {
	if err == nil {
		return true
	}
	if errors.Is(err, errSessionNotFound) {
		writeHTTPError(writer, http.StatusNotFound, "session_not_found", "session does not exist")
		return false
	}
	if errors.Is(err, errWorkspaceNotFound) {
		writeHTTPError(writer, http.StatusNotFound, "workspace_not_found", "workspace does not exist")
		return false
	}
	if errors.Is(err, errSessionDeleting) {
		writeHTTPError(writer, http.StatusConflict, "session_deleting", "session is already being deleted")
		return false
	}
	if errors.Is(err, errSessionOutputting) {
		writeHTTPError(
			writer,
			http.StatusConflict,
			"session_outputting",
			"session is outputting; request an abort and wait for it to settle before stopping the pi process",
		)
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		writeHTTPError(writer, http.StatusGatewayTimeout, "session_stop_timeout", "could not stop session before the timeout")
		return false
	}
	g.cfg.Logger.Error(operation+" session", "session_id", id, "error", err)
	writeHTTPError(writer, http.StatusInternalServerError, "session_"+operation+"_failed", "could not "+operation+" session")
	return false
}

func makeWorkspaceResponse(
	workspace workspaceMetadata,
	sessions []managedSession,
	limit int,
) workspaceResponse {
	if limit > len(sessions) {
		limit = len(sessions)
	}
	technology := detectWorkspaceTechnology(workspace.Directory)
	response := workspaceResponse{
		ID:                     workspace.ID,
		Directory:              workspace.Directory,
		Name:                   workspace.Name,
		AdditionalSystemPrompt: workspace.AdditionalSystemPrompt,
		Technology:             technology.Primary,
		Technologies:           technology.Technologies,
		SessionCount:           len(sessions),
		Sessions:               makeSessionResponses(sessions[:limit]),
		CreatedAt:              workspace.CreatedAt.UnixMilli(),
		UpdatedAt:              workspace.UpdatedAt.UnixMilli(),
	}
	if limit < len(sessions) {
		response.NextCursor = encodeSessionCursor(limit)
	}
	return response
}

func makeSessionResponses(sessions []managedSession) []sessionResponse {
	result := make([]sessionResponse, 0, len(sessions))
	for _, session := range sessions {
		result = append(result, makeSessionResponse(session))
	}
	return result
}

func makeSessionResponse(session managedSession) sessionResponse {
	meta := session.Metadata
	return sessionResponse{
		ID:         meta.ID,
		Name:       meta.Name,
		CreatedAt:  meta.CreatedAt.UnixMilli(),
		LastActive: meta.UpdatedAt.UnixMilli(),
		Running:    session.Running,
		Outputting: session.Outputting,
	}
}

func workspaceResponseActive(workspace workspaceResponse) bool {
	for _, session := range workspace.Sessions {
		if session.Running {
			return true
		}
	}
	return false
}

func workspaceResponseLastActivity(workspace workspaceResponse) int64 {
	latest := workspace.UpdatedAt
	for _, session := range workspace.Sessions {
		if session.LastActive > latest {
			latest = session.LastActive
		}
	}
	return latest
}

func parsePageLimit(raw string, fallback int) (int, error) {
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 1 || value > maxWorkspaceSessionPage {
		return 0, fmt.Errorf("limit must be between 1 and %d", maxWorkspaceSessionPage)
	}
	return value, nil
}

func encodeSessionCursor(offset int) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strconv.Itoa(offset)))
}

func decodeSessionCursor(raw string) (int, error) {
	if raw == "" {
		return 0, nil
	}
	data, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return 0, err
	}
	offset, err := strconv.Atoi(string(data))
	if err != nil || offset < 0 {
		return 0, errors.New("invalid cursor offset")
	}
	return offset, nil
}
