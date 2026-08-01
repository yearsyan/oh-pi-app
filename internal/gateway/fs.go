package gateway

import (
	"encoding/json"
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
