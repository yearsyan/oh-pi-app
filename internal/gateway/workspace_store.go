package gateway

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	workspaceMetadataFileName    = "workspace.json"
	maxWorkspaceNameRunes        = 200
	maxWorkspaceSystemPromptSize = 64 << 10
)

var errWorkspaceNotFound = errors.New("workspace not found")

// workspaceMetadata is the server-owned identity and configuration for one
// project root. Children can be added later without changing session identity.
type workspaceMetadata struct {
	ID                     string    `json:"id"`
	Directory              string    `json:"directory"`
	Name                   string    `json:"name,omitempty"`
	AdditionalSystemPrompt string    `json:"additional_system_prompt,omitempty"`
	CreatedAt              time.Time `json:"created_at"`
	UpdatedAt              time.Time `json:"updated_at"`
}

type workspaceStore struct {
	root string
	mu   sync.RWMutex
}

func newWorkspaceStore(dataDir string) (*workspaceStore, error) {
	root := filepath.Join(dataDir, "workspaces")
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, fmt.Errorf("create workspace store: %w", err)
	}
	return &workspaceStore{root: root}, nil
}

func resolveWorkspaceDirectory(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", errors.New("directory is required")
	}
	if !filepath.IsAbs(raw) {
		return "", fmt.Errorf("directory must be an absolute path, got %q", raw)
	}
	resolved, err := filepath.EvalSymlinks(filepath.Clean(raw))
	if err != nil {
		return "", fmt.Errorf("resolve directory %q: %w", raw, err)
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return "", fmt.Errorf("inspect directory %q: %w", raw, err)
	}
	if !info.IsDir() {
		return "", fmt.Errorf("directory %q is not a directory", raw)
	}
	return resolved, nil
}

// ensure returns the existing workspace for directory or creates one. A
// canonical directory can only have one workspace identity.
func (s *workspaceStore) ensure(directory string) (workspaceMetadata, bool, error) {
	resolved, err := resolveWorkspaceDirectory(directory)
	if err != nil {
		return workspaceMetadata{}, false, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	metas, err := s.listLocked()
	if err != nil {
		return workspaceMetadata{}, false, err
	}
	for _, meta := range metas {
		if meta.Directory == resolved {
			return meta, false, nil
		}
	}

	for range 8 {
		id, err := newSessionID()
		if err != nil {
			return workspaceMetadata{}, false, err
		}
		dir := s.workspaceDir(id)
		if err := os.Mkdir(dir, 0o700); err != nil {
			if errors.Is(err, fs.ErrExist) {
				continue
			}
			return workspaceMetadata{}, false, fmt.Errorf("create workspace directory: %w", err)
		}
		now := time.Now().UTC()
		meta := workspaceMetadata{
			ID:        id,
			Directory: resolved,
			CreatedAt: now,
			UpdatedAt: now,
		}
		if err := writeWorkspaceMetadata(dir, meta); err != nil {
			_ = os.Remove(dir)
			return workspaceMetadata{}, false, err
		}
		return meta, true, nil
	}
	return workspaceMetadata{}, false, errors.New("could not allocate a unique workspace id")
}

func (s *workspaceStore) load(id string) (workspaceMetadata, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.loadLocked(id)
}

func (s *workspaceStore) loadLocked(id string) (workspaceMetadata, error) {
	if !validSessionID(id) {
		return workspaceMetadata{}, errWorkspaceNotFound
	}
	dir := s.workspaceDir(id)
	info, err := os.Lstat(dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return workspaceMetadata{}, errWorkspaceNotFound
		}
		return workspaceMetadata{}, fmt.Errorf("inspect workspace directory: %w", err)
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return workspaceMetadata{}, errWorkspaceNotFound
	}
	path, err := resolveExistingFile(dir, workspaceMetadataFileName)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return workspaceMetadata{}, errWorkspaceNotFound
		}
		return workspaceMetadata{}, fmt.Errorf("inspect workspace metadata: %w", err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return workspaceMetadata{}, fmt.Errorf("read workspace metadata: %w", err)
	}
	var meta workspaceMetadata
	if err := json.Unmarshal(data, &meta); err != nil {
		return workspaceMetadata{}, fmt.Errorf("decode workspace metadata: %w", err)
	}
	if meta.ID != id || !filepath.IsAbs(meta.Directory) || meta.CreatedAt.IsZero() {
		return workspaceMetadata{}, fmt.Errorf("invalid workspace metadata for %q", id)
	}
	if meta.UpdatedAt.IsZero() {
		meta.UpdatedAt = meta.CreatedAt
	}
	return meta, nil
}

func (s *workspaceStore) list() ([]workspaceMetadata, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.listLocked()
}

func (s *workspaceStore) listLocked() ([]workspaceMetadata, error) {
	entries, err := os.ReadDir(s.root)
	if err != nil {
		return nil, fmt.Errorf("read workspace store: %w", err)
	}
	metas := make([]workspaceMetadata, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() || !validSessionID(entry.Name()) {
			continue
		}
		meta, err := s.loadLocked(entry.Name())
		if err != nil {
			if errors.Is(err, errWorkspaceNotFound) {
				continue
			}
			return nil, fmt.Errorf("load workspace %q: %w", entry.Name(), err)
		}
		metas = append(metas, meta)
	}
	return metas, nil
}

func (s *workspaceStore) update(
	id string,
	name *string,
	additionalSystemPrompt *string,
) (workspaceMetadata, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	meta, err := s.loadLocked(id)
	if err != nil {
		return workspaceMetadata{}, err
	}
	if name != nil {
		normalized := strings.TrimSpace(*name)
		if utf8.RuneCountInString(normalized) > maxWorkspaceNameRunes {
			return workspaceMetadata{}, fmt.Errorf("name must not exceed %d characters", maxWorkspaceNameRunes)
		}
		meta.Name = normalized
	}
	if additionalSystemPrompt != nil {
		normalized := strings.TrimSpace(*additionalSystemPrompt)
		if !utf8.ValidString(normalized) || len(normalized) > maxWorkspaceSystemPromptSize {
			return workspaceMetadata{}, fmt.Errorf(
				"additional_system_prompt must be valid UTF-8 and not exceed %d bytes",
				maxWorkspaceSystemPromptSize,
			)
		}
		meta.AdditionalSystemPrompt = normalized
	}
	meta.UpdatedAt = time.Now().UTC()
	if err := replaceWorkspaceMetadata(s.workspaceDir(id), meta); err != nil {
		return workspaceMetadata{}, err
	}
	return meta, nil
}

func (s *workspaceStore) workspaceDir(id string) string {
	return filepath.Join(s.root, id)
}

func writeWorkspaceMetadata(dir string, meta workspaceMetadata) error {
	data, err := encodeWorkspaceMetadata(meta)
	if err != nil {
		return err
	}
	path := filepath.Join(dir, workspaceMetadataFileName)
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return fmt.Errorf("create workspace metadata: %w", err)
	}
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		_ = os.Remove(path)
		return fmt.Errorf("write workspace metadata: %w", err)
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(path)
		return fmt.Errorf("close workspace metadata: %w", err)
	}
	return nil
}

func replaceWorkspaceMetadata(dir string, meta workspaceMetadata) error {
	data, err := encodeWorkspaceMetadata(meta)
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(dir, ".ohpi-workspace-*.tmp")
	if err != nil {
		return fmt.Errorf("create temporary workspace metadata: %w", err)
	}
	temporaryPath := temporary.Name()
	committed := false
	defer func() {
		_ = temporary.Close()
		if !committed {
			_ = os.Remove(temporaryPath)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return fmt.Errorf("set temporary workspace metadata permissions: %w", err)
	}
	if _, err := temporary.Write(data); err != nil {
		return fmt.Errorf("write temporary workspace metadata: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		return fmt.Errorf("sync temporary workspace metadata: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close temporary workspace metadata: %w", err)
	}
	if err := os.Rename(temporaryPath, filepath.Join(dir, workspaceMetadataFileName)); err != nil {
		return fmt.Errorf("replace workspace metadata: %w", err)
	}
	committed = true
	return nil
}

func encodeWorkspaceMetadata(meta workspaceMetadata) ([]byte, error) {
	data, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("encode workspace metadata: %w", err)
	}
	return append(data, '\n'), nil
}
