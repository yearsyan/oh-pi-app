package gateway

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type fsListEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

type fsListResponse struct {
	Path   string        `json:"path"`
	Parent string        `json:"parent"`
	Dirs   []fsListEntry `json:"dirs"`
}

// handleFsList lists the subdirectories of a directory on the gateway host so
// clients can offer a workspace browser. Token-authenticated, GET only. An
// empty "path" query starts at the gateway's configured working directory.
func (g *Gateway) handleFsList(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}

	target := g.cfg.WorkDir
	if raw := strings.TrimSpace(request.URL.Query().Get("path")); raw != "" {
		if !filepath.IsAbs(raw) {
			writeHTTPError(writer, http.StatusBadRequest, "invalid_path", fmt.Sprintf("path must be absolute, got %q", raw))
			return
		}
		resolved, err := filepath.EvalSymlinks(filepath.Clean(raw))
		if err != nil {
			writeHTTPError(writer, http.StatusNotFound, "not_found", fmt.Sprintf("resolve path %q: %v", raw, err))
			return
		}
		target = resolved
	}

	info, err := os.Stat(target)
	if err != nil {
		writeHTTPError(writer, http.StatusNotFound, "not_found", fmt.Sprintf("inspect path: %v", err))
		return
	}
	if !info.IsDir() {
		writeHTTPError(writer, http.StatusBadRequest, "not_a_directory", fmt.Sprintf("%q is not a directory", target))
		return
	}

	entries, err := os.ReadDir(target)
	if err != nil {
		writeHTTPError(writer, http.StatusForbidden, "read_failed", fmt.Sprintf("read directory: %v", err))
		return
	}
	dirs := make([]fsListEntry, 0, len(entries))
	for _, entry := range entries {
		isDir := entry.IsDir()
		if !isDir && entry.Type()&os.ModeSymlink != 0 {
			if stat, statErr := os.Stat(filepath.Join(target, entry.Name())); statErr == nil {
				isDir = stat.IsDir()
			}
		}
		if isDir {
			dirs = append(dirs, fsListEntry{Name: entry.Name(), Path: filepath.Join(target, entry.Name())})
		}
	}
	sort.Slice(dirs, func(i, j int) bool {
		return strings.ToLower(dirs[i].Name) < strings.ToLower(dirs[j].Name)
	})

	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(writer).Encode(fsListResponse{
		Path:   target,
		Parent: filepath.Dir(target),
		Dirs:   dirs,
	})
}

type fsMkdirRequest struct {
	Parent string `json:"parent"`
	Name   string `json:"name"`
}

// handleFsMkdir creates a single directory on the gateway host so workspace
// browsers can offer a "new folder" action. Token-authenticated, POST only;
// the JSON body carries an absolute existing parent and one leaf name.
func (g *Gateway) handleFsMkdir(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodPost {
		writer.Header().Set("Allow", http.MethodPost)
		writeHTTPError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only POST is allowed")
		return
	}
	if !g.authenticated(request) {
		writeHTTPError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return
	}

	request.Body = http.MaxBytesReader(writer, request.Body, 4<<10)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var mkdir fsMkdirRequest
	if err := decoder.Decode(&mkdir); err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_request", "body must be one JSON object with parent and name fields")
		return
	}
	if err := ensureJSONEOF(decoder); err != nil {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_request", "body must contain exactly one JSON object")
		return
	}

	rawParent := strings.TrimSpace(mkdir.Parent)
	if rawParent == "" || strings.IndexByte(rawParent, 0) >= 0 || !filepath.IsAbs(rawParent) {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_path", fmt.Sprintf("parent must be absolute, got %q", rawParent))
		return
	}
	name := strings.TrimSpace(mkdir.Name)
	if !validFsDirName(name) {
		writeHTTPError(writer, http.StatusBadRequest, "invalid_name", "name must be one non-empty directory name without path separators")
		return
	}

	parent, err := filepath.EvalSymlinks(filepath.Clean(rawParent))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			writeHTTPError(writer, http.StatusNotFound, "not_found", fmt.Sprintf("resolve parent %q: %v", rawParent, err))
			return
		}
		writeHTTPError(writer, http.StatusForbidden, "resolve_failed", fmt.Sprintf("resolve parent %q: %v", rawParent, err))
		return
	}
	info, err := os.Stat(parent)
	if err != nil {
		writeHTTPError(writer, http.StatusForbidden, "resolve_failed", fmt.Sprintf("inspect parent %q: %v", parent, err))
		return
	}
	if !info.IsDir() {
		writeHTTPError(writer, http.StatusBadRequest, "not_a_directory", fmt.Sprintf("%q is not a directory", parent))
		return
	}

	// Create relative to an opened directory handle so a leaf name can never
	// escape the selected parent, even if path components change concurrently.
	root, err := os.OpenRoot(parent)
	if err != nil {
		writeHTTPError(writer, http.StatusForbidden, "mkdir_failed", fmt.Sprintf("open parent directory: %v", err))
		return
	}
	defer root.Close()

	target := filepath.Join(parent, name)
	if err := root.Mkdir(name, 0o755); err != nil {
		if errors.Is(err, os.ErrExist) {
			writeHTTPError(writer, http.StatusConflict, "already_exists", fmt.Sprintf("%q already exists", target))
			return
		}
		writeHTTPError(writer, http.StatusForbidden, "mkdir_failed", fmt.Sprintf("create directory: %v", err))
		return
	}

	writeJSONResponse(writer, http.StatusCreated, fsListEntry{
		Name: name,
		Path: target,
	})
}

func validFsDirName(name string) bool {
	return name != "" &&
		name != "." &&
		name != ".." &&
		strings.IndexByte(name, 0) < 0 &&
		!strings.ContainsAny(name, `/\`) &&
		filepath.IsLocal(name) &&
		filepath.Base(name) == name
}
