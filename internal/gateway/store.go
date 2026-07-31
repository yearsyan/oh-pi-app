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
	"time"
)

const metadataFileName = "pi2ws-session.json"

var errSessionNotFound = errors.New("session not found")

type sessionMetadata struct {
	ID        string    `json:"id"`
	CreatedAt time.Time `json:"created_at"`
}

type sessionStore struct {
	root string
}

func newSessionStore(dataDir string) (*sessionStore, error) {
	root := filepath.Join(dataDir, "sessions")
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, fmt.Errorf("create session store: %w", err)
	}
	return &sessionStore{root: root}, nil
}

func (s *sessionStore) create() (sessionMetadata, string, error) {
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

		meta := sessionMetadata{ID: id, CreatedAt: time.Now().UTC()}
		if err := writeMetadata(dir, meta); err != nil {
			_ = os.Remove(dir)
			return sessionMetadata{}, "", err
		}
		return meta, dir, nil
	}
	return sessionMetadata{}, "", fmt.Errorf("could not allocate a unique session id")
}

func (s *sessionStore) load(id string) (sessionMetadata, string, error) {
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

	path := filepath.Join(dir, metadataFileName)
	info, err = os.Lstat(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return sessionMetadata{}, "", errSessionNotFound
		}
		return sessionMetadata{}, "", fmt.Errorf("inspect session metadata: %w", err)
	}
	if !info.Mode().IsRegular() {
		return sessionMetadata{}, "", fmt.Errorf("%w: invalid session metadata", errSessionNotFound)
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
	return meta, dir, nil
}

func (s *sessionStore) sessionDir(id string) string {
	return filepath.Join(s.root, id)
}

func (s *sessionStore) discard(id string) error {
	if !validSessionID(id) {
		return fmt.Errorf("invalid session id %q", id)
	}
	dir := s.sessionDir(id)
	if err := os.Remove(filepath.Join(dir, metadataFileName)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("remove session metadata: %w", err)
	}
	if err := os.Remove(dir); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("remove session directory: %w", err)
	}
	return nil
}

func writeMetadata(dir string, meta sessionMetadata) error {
	data, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return fmt.Errorf("encode session metadata: %w", err)
	}
	data = append(data, '\n')
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
