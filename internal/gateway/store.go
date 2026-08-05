package gateway

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	metadataFileName       = "ohpi-session.json"
	legacyMetadataFileName = "pi2ws-session.json" // pre-rename brand
)

var errSessionNotFound = errors.New("session not found")

type sessionMetadata struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	NameSet   bool      `json:"name_set,omitempty"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
	WorkDir   string    `json:"work_dir,omitempty"`
}

type sessionStore struct {
	root string
	mu   sync.RWMutex
}

func newSessionStore(dataDir string) (*sessionStore, error) {
	root := filepath.Join(dataDir, "sessions")
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, fmt.Errorf("create session store: %w", err)
	}
	return &sessionStore{root: root}, nil
}

func (s *sessionStore) create(workDir string) (sessionMetadata, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for range 8 {
		id, err := newSessionID()
		if err != nil {
			return sessionMetadata{}, "", err
		}
		dir := s.sessionDir(id)
		if err := os.Mkdir(dir, 0o700); err != nil {
			if errors.Is(err, fs.ErrExist) {
				continue
			}
			return sessionMetadata{}, "", fmt.Errorf("create session directory: %w", err)
		}

		now := time.Now().UTC()
		meta := sessionMetadata{
			ID:        id,
			CreatedAt: now,
			UpdatedAt: now,
			WorkDir:   workDir,
		}
		if err := writeMetadata(dir, meta); err != nil {
			_ = os.Remove(dir)
			return sessionMetadata{}, "", err
		}
		return meta, dir, nil
	}
	return sessionMetadata{}, "", fmt.Errorf("could not allocate a unique session id")
}

func (s *sessionStore) load(id string) (sessionMetadata, string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.loadLocked(id)
}

func (s *sessionStore) loadLocked(id string) (sessionMetadata, string, error) {
	if !validSessionID(id) {
		return sessionMetadata{}, "", fmt.Errorf("%w: invalid session id", errSessionNotFound)
	}
	dir := s.sessionDir(id)
	info, err := os.Lstat(dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return sessionMetadata{}, "", errSessionNotFound
		}
		return sessionMetadata{}, "", fmt.Errorf("inspect session directory: %w", err)
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return sessionMetadata{}, "", fmt.Errorf("%w: invalid session directory", errSessionNotFound)
	}

	path, err := resolveExistingFile(dir, metadataFileName, legacyMetadataFileName)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return sessionMetadata{}, "", errSessionNotFound
		}
		return sessionMetadata{}, "", fmt.Errorf("inspect session metadata: %w", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return sessionMetadata{}, "", fmt.Errorf("read session metadata: %w", err)
	}
	var meta sessionMetadata
	if err := json.Unmarshal(data, &meta); err != nil {
		return sessionMetadata{}, "", fmt.Errorf("decode session metadata: %w", err)
	}
	if meta.ID != id || meta.CreatedAt.IsZero() {
		return sessionMetadata{}, "", fmt.Errorf("invalid session metadata for %q", id)
	}
	if meta.UpdatedAt.IsZero() {
		// Metadata created by older gateways did not record activity time.
		meta.UpdatedAt = meta.CreatedAt
	}
	if strings.TrimSpace(meta.Name) == "" {
		// A previous gateway wrote name_set=true for every newly-created,
		// unnamed session. Treat that state as unset so pi can supply a title.
		meta.Name = ""
		meta.NameSet = false
	} else if !meta.NameSet {
		// Preserve non-empty names written before name_set was introduced.
		meta.NameSet = true
	}
	return meta, dir, nil
}

func (s *sessionStore) list() ([]sessionMetadata, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	entries, err := os.ReadDir(s.root)
	if err != nil {
		return nil, fmt.Errorf("read session store: %w", err)
	}
	metas := make([]sessionMetadata, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() || !validSessionID(entry.Name()) {
			continue
		}
		meta, _, err := s.loadLocked(entry.Name())
		if err != nil {
			if errors.Is(err, errSessionNotFound) {
				continue
			}
			return nil, fmt.Errorf("load session %q: %w", entry.Name(), err)
		}
		metas = append(metas, meta)
	}
	return metas, nil
}

func (s *sessionStore) rename(id, name string) (sessionMetadata, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	meta, dir, err := s.loadLocked(id)
	if err != nil {
		return sessionMetadata{}, err
	}
	meta.Name = name
	meta.NameSet = true
	meta.UpdatedAt = time.Now().UTC()
	if err := replaceMetadata(dir, meta); err != nil {
		return sessionMetadata{}, err
	}
	return meta, nil
}

func (s *sessionStore) adoptName(id, name string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	meta, dir, err := s.loadLocked(id)
	if err != nil {
		return false, err
	}
	if meta.NameSet {
		return meta.Name == name, nil
	}
	meta.Name = name
	meta.NameSet = true
	meta.UpdatedAt = time.Now().UTC()
	if err := replaceMetadata(dir, meta); err != nil {
		return false, err
	}
	return true, nil
}

func (s *sessionStore) touch(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	meta, dir, err := s.loadLocked(id)
	if err != nil {
		return err
	}
	meta.UpdatedAt = time.Now().UTC()
	return replaceMetadata(dir, meta)
}

func (s *sessionStore) sessionDir(id string) string {
	return filepath.Join(s.root, id)
}

func (s *sessionStore) discard(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !validSessionID(id) {
		return fmt.Errorf("invalid session id %q", id)
	}
	dir := s.sessionDir(id)
	for _, name := range []string{metadataFileName, legacyMetadataFileName} {
		if err := os.Remove(filepath.Join(dir, name)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return fmt.Errorf("remove session metadata: %w", err)
		}
	}
	for _, name := range []string{
		historyCacheFileName, legacyHistoryCacheFileName,
		replayLogFileName, legacyReplayLogFileName,
	} {
		if err := os.Remove(filepath.Join(dir, name)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return fmt.Errorf("remove session gateway state %q: %w", name, err)
		}
	}
	if err := os.Remove(dir); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("remove session directory: %w", err)
	}
	return nil
}

func (s *sessionStore) delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	_, dir, err := s.loadLocked(id)
	if err != nil {
		return err
	}
	if err := os.RemoveAll(dir); err != nil {
		return fmt.Errorf("remove session directory: %w", err)
	}
	return nil
}

func writeMetadata(dir string, meta sessionMetadata) error {
	data, err := encodeMetadata(meta)
	if err != nil {
		return err
	}
	path := filepath.Join(dir, metadataFileName)
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return fmt.Errorf("create session metadata: %w", err)
	}
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		_ = os.Remove(path)
		return fmt.Errorf("write session metadata: %w", err)
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(path)
		return fmt.Errorf("close session metadata: %w", err)
	}
	return nil
}

func replaceMetadata(dir string, meta sessionMetadata) error {
	data, err := encodeMetadata(meta)
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(dir, ".ohpi-session-*.tmp")
	if err != nil {
		return fmt.Errorf("create temporary session metadata: %w", err)
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
		return fmt.Errorf("set temporary session metadata permissions: %w", err)
	}
	if _, err := temporary.Write(data); err != nil {
		return fmt.Errorf("write temporary session metadata: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		return fmt.Errorf("sync temporary session metadata: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close temporary session metadata: %w", err)
	}
	if err := os.Rename(temporaryPath, filepath.Join(dir, metadataFileName)); err != nil {
		return fmt.Errorf("replace session metadata: %w", err)
	}
	committed = true
	// Drop pre-rename metadata once the new name is in place.
	_ = os.Remove(filepath.Join(dir, legacyMetadataFileName))
	return nil
}

// resolveExistingFile returns the first regular file among names that exists in dir.
func resolveExistingFile(dir string, names ...string) (string, error) {
	var firstErr error
	for _, name := range names {
		path := filepath.Join(dir, name)
		info, err := os.Lstat(path)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				continue
			}
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		if !info.Mode().IsRegular() {
			return "", fmt.Errorf("%w: invalid session metadata", errSessionNotFound)
		}
		return path, nil
	}
	if firstErr != nil {
		return "", firstErr
	}
	return "", fs.ErrNotExist
}

func encodeMetadata(meta sessionMetadata) ([]byte, error) {
	data, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("encode session metadata: %w", err)
	}
	return append(data, '\n'), nil
}

func newSessionID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", fmt.Errorf("generate session id: %w", err)
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80

	var text [36]byte
	hex.Encode(text[0:8], id[0:4])
	text[8] = '-'
	hex.Encode(text[9:13], id[4:6])
	text[13] = '-'
	hex.Encode(text[14:18], id[6:8])
	text[18] = '-'
	hex.Encode(text[19:23], id[8:10])
	text[23] = '-'
	hex.Encode(text[24:36], id[10:16])
	return string(text[:]), nil
}

func validSessionID(id string) bool {
	if len(id) != 36 || id[8] != '-' || id[13] != '-' || id[18] != '-' || id[23] != '-' {
		return false
	}
	for i, char := range id {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			continue
		}
		if !strings.ContainsRune("0123456789abcdef", char) {
			return false
		}
	}
	return true
}
