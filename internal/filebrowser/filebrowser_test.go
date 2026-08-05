package filebrowser

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testToken = "test-token"

func newTestServer(t *testing.T, workDir string) *httptest.Server {
	t.Helper()
	server := New(Config{
		Authenticate: func(request *http.Request) bool {
			return request.Header.Get("Authorization") == "Bearer "+testToken
		},
		WorkDir: workDir,
	})
	mux := http.NewServeMux()
	server.Register(mux)
	httpServer := httptest.NewServer(mux)
	t.Cleanup(httpServer.Close)
	return httpServer
}

func authedRequest(t *testing.T, url string) *http.Request {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	request.Header.Set("Authorization", "Bearer "+testToken)
	return request
}

func get(t *testing.T, httpServer *httptest.Server, path string) *httptest.ResponseRecorder {
	t.Helper()
	request := authedRequest(t, httpServer.URL+path)
	recorder := httptest.NewRecorder()
	httpServer.Config.Handler.ServeHTTP(recorder, request)
	return recorder
}

func setupTree(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	// Temp dirs on macOS live under symlinks; match the server's resolution.
	root, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatalf("resolve temp dir: %v", err)
	}
	if err := os.MkdirAll(filepath.Join(root, "sub", "nested"), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	files := map[string]string{
		"alpha.txt":      "hello world\n",
		"notes.md":       "# notes\n",
		"sub/data.json":  "{\"ok\":true}\n",
		"sub/binary.bin": "PK\x03\x04\x00\x00binary",
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o644); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	return root
}

func TestListRequiresAuthentication(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	response, err := http.Get(httpServer.URL + "/api/files/list")
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.StatusCode)
	}
}

func TestListRejectsNonGetMethods(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	request, err := http.NewRequest(http.MethodPost, httpServer.URL+"/api/files/list", nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	request.Header.Set("Authorization", "Bearer "+testToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", response.StatusCode)
	}
}

func TestListDefaultsToWorkDirAndSortsDirsFirst(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/list")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", recorder.Code, recorder.Body.String())
	}
	var body listResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.Path != root {
		t.Fatalf("path = %q, want %q", body.Path, root)
	}
	if body.Parent != filepath.Dir(root) {
		t.Fatalf("parent = %q, want %q", body.Parent, filepath.Dir(root))
	}
	if len(body.Entries) != 3 {
		t.Fatalf("entries = %d, want 3: %+v", len(body.Entries), body.Entries)
	}
	if !body.Entries[0].IsDir || body.Entries[0].Name != "sub" {
		t.Fatalf("first entry should be the sub directory, got %+v", body.Entries[0])
	}
	if body.Entries[1].IsDir || body.Entries[2].IsDir {
		t.Fatalf("files should sort after directories: %+v", body.Entries)
	}
	if body.Entries[1].Name != "alpha.txt" || body.Entries[2].Name != "notes.md" {
		t.Fatalf("files not sorted by name: %+v", body.Entries)
	}
}

func TestListRejectsRelativePath(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/list?path=etc/passwd")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
}

func TestListMissingDirectory(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/list?path="+filepath.Join(root, "missing"))
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", recorder.Code)
	}
}

func TestListFileRejectedAsNotDirectory(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/list?path="+filepath.Join(root, "alpha.txt"))
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
}

func TestReadReturnsTextContent(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/read?path="+filepath.Join(root, "alpha.txt"))
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", recorder.Code, recorder.Body.String())
	}
	var body readResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.Content != "hello world\n" {
		t.Fatalf("content = %q", body.Content)
	}
	if body.Name != "alpha.txt" || body.Size != int64(len("hello world\n")) {
		t.Fatalf("metadata = %+v", body)
	}
	if body.Truncated {
		t.Fatal("small file must not be truncated")
	}
}

func TestReadRejectsBinaryFiles(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/read?path="+filepath.Join(root, "sub", "binary.bin"))
	if recorder.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("status = %d, want 415", recorder.Code)
	}
}

func TestReadRejectsDirectories(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/read?path="+filepath.Join(root, "sub"))
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
}

func TestReadTruncatesLargeFiles(t *testing.T) {
	root := setupTree(t)
	server := New(Config{
		Authenticate: func(*http.Request) bool { return true },
		WorkDir:      root,
		MaxReadBytes: 4,
	})
	mux := http.NewServeMux()
	server.Register(mux)
	limited := httptest.NewServer(mux)
	t.Cleanup(limited.Close)

	response, err := http.Get(limited.URL + "/api/files/read?path=" + filepath.Join(root, "alpha.txt"))
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer response.Body.Close()
	var body readResponse
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !body.Truncated {
		t.Fatal("expected truncation beyond MaxReadBytes")
	}
	if body.Content != "hell" {
		t.Fatalf("content = %q, want first 4 bytes", body.Content)
	}
}

func TestDownloadStreamsFileWithAttachmentHeaders(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	request := authedRequest(t, httpServer.URL+"/api/files/download?path="+filepath.Join(root, "sub", "binary.bin"))
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	disposition := response.Header.Get("Content-Disposition")
	if !strings.Contains(disposition, "attachment") || !strings.Contains(disposition, "binary.bin") {
		t.Fatalf("Content-Disposition = %q", disposition)
	}
	if contentType := response.Header.Get("Content-Type"); contentType != "application/octet-stream" {
		t.Fatalf("Content-Type = %q", contentType)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if string(content) != "PK\x03\x04\x00\x00binary" {
		t.Fatalf("body = %q", content)
	}
}

func TestDownloadMissingFile(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/download?path="+filepath.Join(root, "missing.apk"))
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", recorder.Code)
	}
}

func TestPathMustNotContainNUL(t *testing.T) {
	root := setupTree(t)
	httpServer := newTestServer(t, root)

	recorder := get(t, httpServer, "/api/files/read?path="+filepath.Join(root, "alpha.txt")+"%00.txt")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
}
