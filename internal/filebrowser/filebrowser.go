// Package filebrowser implements the pi2ws HTTP file browsing API: directory
// listing, text file preview, and raw file download. Every endpoint is GET
// only and gated by the gateway's token authentication.
package filebrowser

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf8"
)

const (
	defaultMaxReadBytes  int64 = 1 << 20 // 1 MiB of text preview
	maxListEntries             = 5000
	binarySniffBytes           = 8 << 10
	maxDownloadNameRunes       = 255
)

// Config configures a file browser Server.
type Config struct {
	// Authenticate reports whether a request may use the file API. Required.
	Authenticate func(request *http.Request) bool
	// WorkDir is listed when the request supplies no path.
	WorkDir string
	// MaxReadBytes caps how much of a text file the read endpoint returns.
	// Zero uses a 1 MiB default.
	MaxReadBytes int64
	// Logger receives request-level error logs. Nil discards.
	Logger *slog.Logger
}

// Server serves the file browsing endpoints.
type Server struct {
	authenticate func(request *http.Request) bool
	workDir      string
	maxReadBytes int64
	logger       *slog.Logger
}

// New constructs a Server. It panics when Config.Authenticate is nil because
// an unauthenticated file API must never be served accidentally.
func New(cfg Config) *Server {
	if cfg.Authenticate == nil {
		panic("filebrowser: Config.Authenticate is required")
	}
	maxRead := cfg.MaxReadBytes
	if maxRead <= 0 {
		maxRead = defaultMaxReadBytes
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Server{
		authenticate: cfg.Authenticate,
		workDir:      cfg.WorkDir,
		maxReadBytes: maxRead,
		logger:       logger,
	}
}

// Register mounts the file endpoints on mux.
func (s *Server) Register(mux *http.ServeMux) {
	mux.HandleFunc("/api/files/list", s.handleList)
	mux.HandleFunc("/api/files/read", s.handleRead)
	mux.HandleFunc("/api/files/download", s.handleDownload)
}

type fileEntry struct {
	Name    string `json:"name"`
	Path    string `json:"path"`
	IsDir   bool   `json:"is_dir"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"mod_time"`
}

type listResponse struct {
	Path      string      `json:"path"`
	Parent    string      `json:"parent"`
	Truncated bool        `json:"truncated"`
	Entries   []fileEntry `json:"entries"`
}

type readResponse struct {
	Path      string `json:"path"`
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	Truncated bool   `json:"truncated"`
	Content   string `json:"content"`
}

func (s *Server) handleList(writer http.ResponseWriter, request *http.Request) {
	if !s.allow(writer, request) {
		return
	}
	target, ok := s.resolveTarget(writer, request)
	if !ok {
		return
	}
	info, err := os.Stat(target)
	if err != nil {
		writeError(writer, http.StatusNotFound, "not_found", fmt.Sprintf("inspect path: %v", err))
		return
	}
	if !info.IsDir() {
		writeError(writer, http.StatusBadRequest, "not_a_directory", fmt.Sprintf("%q is not a directory", target))
		return
	}

	dirEntries, err := os.ReadDir(target)
	if err != nil {
		writeError(writer, http.StatusForbidden, "read_failed", fmt.Sprintf("read directory: %v", err))
		return
	}
	entries := make([]fileEntry, 0, len(dirEntries))
	truncated := false
	for _, dirEntry := range dirEntries {
		if len(entries) >= maxListEntries {
			truncated = true
			break
		}
		fullPath := filepath.Join(target, dirEntry.Name())
		// os.Stat follows symlinks so links to directories stay navigable.
		stat, statErr := os.Stat(fullPath)
		if statErr != nil {
			continue
		}
		entries = append(entries, fileEntry{
			Name:    dirEntry.Name(),
			Path:    fullPath,
			IsDir:   stat.IsDir(),
			Size:    stat.Size(),
			ModTime: stat.ModTime().UnixMilli(),
		})
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].IsDir != entries[j].IsDir {
			return entries[i].IsDir
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})

	writeJSON(writer, http.StatusOK, listResponse{
		Path:      target,
		Parent:    filepath.Dir(target),
		Truncated: truncated,
		Entries:   entries,
	})
}

func (s *Server) handleRead(writer http.ResponseWriter, request *http.Request) {
	if !s.allow(writer, request) {
		return
	}
	target, info, ok := s.resolveFile(writer, request)
	if !ok {
		return
	}

	file, err := os.Open(target)
	if err != nil {
		writeError(writer, http.StatusForbidden, "read_failed", fmt.Sprintf("open file: %v", err))
		return
	}
	defer file.Close()

	content, err := io.ReadAll(io.LimitReader(file, s.maxReadBytes+1))
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "read_failed", fmt.Sprintf("read file: %v", err))
		return
	}
	truncated := int64(len(content)) > s.maxReadBytes
	if truncated {
		content = content[:s.maxReadBytes]
	}
	if looksBinary(content) {
		writeError(writer, http.StatusUnsupportedMediaType, "binary_file", fmt.Sprintf("%q is a binary file", target))
		return
	}

	writeJSON(writer, http.StatusOK, readResponse{
		Path:      target,
		Name:      info.Name(),
		Size:      info.Size(),
		Truncated: truncated,
		Content:   string(content),
	})
}

func (s *Server) handleDownload(writer http.ResponseWriter, request *http.Request) {
	if !s.allow(writer, request) {
		return
	}
	target, info, ok := s.resolveFile(writer, request)
	if !ok {
		return
	}

	file, err := os.Open(target)
	if err != nil {
		writeError(writer, http.StatusForbidden, "read_failed", fmt.Sprintf("open file: %v", err))
		return
	}
	defer file.Close()

	name := info.Name()
	if utf8.RuneCountInString(name) > maxDownloadNameRunes {
		name = string([]rune(name)[:maxDownloadNameRunes])
	}
	writer.Header().Set("Content-Type", "application/octet-stream")
	writer.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	// ServeContent adds Content-Length, Last-Modified, and range support; it
	// keeps the Content-Type set above.
	http.ServeContent(writer, request, name, info.ModTime(), file)
}

// allow enforces GET-only and token authentication for every endpoint.
func (s *Server) allow(writer http.ResponseWriter, request *http.Request) bool {
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		writeError(writer, http.StatusMethodNotAllowed, "method_not_allowed", "only GET is allowed")
		return false
	}
	if !s.authenticate(request) {
		writeError(writer, http.StatusUnauthorized, "unauthorized", "invalid authentication token")
		return false
	}
	return true
}

// resolveTarget returns the cleaned, symlink-resolved absolute directory the
// request points at, or writes the error response and returns false.
func (s *Server) resolveTarget(writer http.ResponseWriter, request *http.Request) (string, bool) {
	raw := strings.TrimSpace(request.URL.Query().Get("path"))
	if raw == "" {
		if s.workDir == "" {
			writeError(writer, http.StatusBadRequest, "invalid_path", "path is required")
			return "", false
		}
		raw = s.workDir
	}
	return s.resolvePath(writer, raw)
}

func (s *Server) resolvePath(writer http.ResponseWriter, raw string) (string, bool) {
	if strings.IndexByte(raw, 0) >= 0 {
		writeError(writer, http.StatusBadRequest, "invalid_path", "path contains a NUL byte")
		return "", false
	}
	if !filepath.IsAbs(raw) {
		writeError(writer, http.StatusBadRequest, "invalid_path", fmt.Sprintf("path must be absolute, got %q", raw))
		return "", false
	}
	resolved, err := filepath.EvalSymlinks(filepath.Clean(raw))
	if err != nil {
		writeError(writer, http.StatusNotFound, "not_found", fmt.Sprintf("resolve path %q: %v", raw, err))
		return "", false
	}
	return resolved, true
}

// resolveFile resolves the request path and confirms it is a regular file.
func (s *Server) resolveFile(writer http.ResponseWriter, request *http.Request) (string, os.FileInfo, bool) {
	raw := strings.TrimSpace(request.URL.Query().Get("path"))
	if raw == "" {
		writeError(writer, http.StatusBadRequest, "invalid_path", "path is required")
		return "", nil, false
	}
	target, ok := s.resolvePath(writer, raw)
	if !ok {
		return "", nil, false
	}
	info, err := os.Stat(target)
	if err != nil {
		writeError(writer, http.StatusNotFound, "not_found", fmt.Sprintf("inspect path: %v", err))
		return "", nil, false
	}
	if info.IsDir() {
		writeError(writer, http.StatusBadRequest, "not_a_file", fmt.Sprintf("%q is a directory", target))
		return "", nil, false
	}
	if !info.Mode().IsRegular() {
		writeError(writer, http.StatusBadRequest, "not_a_file", fmt.Sprintf("%q is not a regular file", target))
		return "", nil, false
	}
	return target, info, true
}

// looksBinary reports whether the sample contains NUL bytes, the common
// heuristic git uses to detect binary content.
func looksBinary(sample []byte) bool {
	if len(sample) > binarySniffBytes {
		sample = sample[:binarySniffBytes]
	}
	for _, b := range sample {
		if b == 0 {
			return true
		}
	}
	return false
}

func writeError(writer http.ResponseWriter, status int, code, message string) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(map[string]string{
		"error":   code,
		"message": message,
	})
}

func writeJSON(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}
